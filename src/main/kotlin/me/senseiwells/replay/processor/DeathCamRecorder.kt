package me.senseiwells.replay.processor

import me.senseiwells.replay.ServerReplay
import net.casual.arcade.events.GlobalEventHandler
import net.casual.arcade.events.ListenerRegistry.Companion.register
import net.casual.arcade.events.server.player.PlayerDeathEvent
import net.casual.arcade.events.server.player.PlayerLeaveEvent
import net.casual.arcade.events.server.player.PlayerLoginEvent
import net.casual.arcade.replay.events.ReplayRecorderSaveEvent
import net.casual.arcade.replay.recorder.player.ReplayPlayerRecorders
import net.casual.arcade.replay.recorder.settings.RecorderSettings
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.time.Duration.Companion.minutes

import net.casual.arcade.replay.recorder.settings.SimpleRecorderSettings

object DeathCamRecorder {
    private val deletable = HashMap<UUID, ArrayDeque<Path>>()
    private val preserveNext = HashSet<UUID>()
    private val recorders = HashSet<UUID>()

    internal fun registerEvents() {
        GlobalEventHandler.Server.register<PlayerLoginEvent> { (server, profile) ->
            if (ServerReplay.config.deathCam) {
                val player = server.playerList.getPlayer(profile.id)
                if (player != null) {
                    this.start(server, player)
                }
            }
        }
        GlobalEventHandler.Server.register<PlayerLeaveEvent> { (player) ->
            this.deletable.remove(player.uuid)
            this.preserveNext.remove(player.uuid)
            this.recorders.remove(player.uuid)
        }
        GlobalEventHandler.Server.register<PlayerDeathEvent> { (player, _) ->
            this.onDeath(player)
        }
        GlobalEventHandler.Server.register<ReplayRecorderSaveEvent>(
            phase = ReplayRecorderSaveEvent.PHASE_POST,
            listener = ::onReplaySave
        )
    }

    private fun start(server: MinecraftServer, player: ServerPlayer) {
        val path = ServerReplay.config.playerRecordingPath.resolve("deathcam").resolve(player.scoreboardName)
        val format = ServerReplay.config.defaultReplayFormat
        val config = ServerReplay.config
        
        val limits = RecorderSettings.FileLimits(
            config.maxFileSize,
            config.restartAfterMaxFileSize,
            3.minutes,
            true
        )
        
        val settings = SimpleRecorderSettings(
            config.debug,
            config.worldName,
            config.serverName,
            config.fixedDaylightCycle,
            config.includeResourcePacks,
            config.chunkRecorderLoadRadius,
            config.chunkRecordingStrategy,
            limits,
            RecorderSettings.IgnorePackets(
                config.ignoreCustomPayloads,
                config.ignoreSoundPackets,
                config.ignoreLightPackets,
                config.ignoreChatPackets,
                config.ignoreActionBarPackets,
                config.ignoreScoreboardPackets
            ),
            RecorderSettings.OptimizePackets(
                config.optimizeExplosionPackets,
                config.optimizeEntityPackets
            ),
            config.recordHotbar,
            config.recordVoiceChat
        )

        val recorder = ReplayPlayerRecorders.create(server, player.gameProfile, path, format, settings)
        recorder.onStart()
        recorder.afterLogin()
        
        this.recorders.add(player.uuid)
    }

    private fun onDeath(player: ServerPlayer) {
        if (!this.recorders.contains(player.uuid)) {
            return
        }
        // Preserve the previous recording
        this.deletable.remove(player.uuid)
        // Preserve the current recording (when it finishes)
        this.preserveNext.add(player.uuid)
    }

    private fun onReplaySave(event: ReplayRecorderSaveEvent) {
        val recorder = event.recorder
        // We can't easily check if it's a death cam recorder from the recorder object itself
        // unless we track the recorder UUIDs or something.
        // But we know we started it for a player.
        // ReplayPlayerRecorder has a uuid property? Or we can check the path?
        
        // Let's try to match the player.
        // The recorder name usually contains the player name or UUID.
        // But a better way is to check if the file path is within our deathcam directory.
        
        val path = event.output
        if (!path.toString().contains("deathcam")) {
            return
        }

        // We need to find which player this belongs to.
        // The path structure is .../deathcam/<player_name>/...
        // We can iterate over our tracked players.
        
        for (uuid in this.recorders) {
            // This is a bit inefficient but safe.
            // Ideally we would map recorder -> player.
            // But we don't have the player object easily here if they are offline.
            // However, we only care if the player is in our 'recorders' set (which implies they are online or we are tracking them).
            
            // Wait, if the player leaves, we remove them from 'recorders'.
            // So if a save happens after leave, we ignore it (and don't delete it).
            // That seems fine.
            
            // But we need to know WHICH player this file belongs to, to manage their queue.
            // We can parse the path.
            
            // Let's assume the path contains the player name.
            // Or we can just check if the path starts with the expected prefix for that player.
            // But we don't have the player object to get the name if we only have UUID.
            // We can get the player from the server if online.
            
            val player = recorder.server.playerList.getPlayer(uuid) ?: continue
            val expectedPath = ServerReplay.config.playerRecordingPath.resolve("deathcam").resolve(player.scoreboardName)
            
            if (path.startsWith(expectedPath)) {
                this.handleSave(uuid, path)
                return
            }
        }
    }

    private fun handleSave(uuid: UUID, path: Path) {
        if (this.preserveNext.remove(uuid)) {
            // Do not add to deletable queue
            return
        }

        val queue = this.deletable.computeIfAbsent(uuid) { ArrayDeque() }
        queue.addLast(path)

        if (queue.size > 1) {
            val toDelete = queue.removeFirst()
            try {
                Files.deleteIfExists(toDelete)
            } catch (e: Exception) {
                ServerReplay.logger.error("Failed to delete death cam replay: $toDelete", e)
            }
        }
    }
}
