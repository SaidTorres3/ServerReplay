package me.senseiwells.replay.processor

import me.senseiwells.replay.ServerReplay
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.casual.arcade.events.GlobalEventHandler
import net.casual.arcade.events.ListenerRegistry.Companion.register
import net.casual.arcade.events.server.ServerTickEvent
import net.casual.arcade.events.server.player.PlayerDeathEvent
import net.casual.arcade.events.server.player.PlayerLeaveEvent
import net.casual.arcade.events.server.player.PlayerJoinEvent
import net.casual.arcade.events.server.player.PlayerRespawnEvent
import net.casual.arcade.replay.events.ReplayRecorderSaveEvent
import net.casual.arcade.replay.recorder.player.ReplayPlayerRecorder
import net.casual.arcade.replay.recorder.player.ReplayPlayerRecorders
import net.casual.arcade.replay.recorder.settings.RecorderSettings
import net.casual.arcade.replay.recorder.settings.SimpleRecorderSettings
import net.casual.arcade.utils.PlayerUtils.server
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * DeathCamRecorder - Rolling buffer recorder for death POV captures
 * 
 * This recorder continuously maintains a 1-minute rolling buffer for each player in survival mode.
 * When a player dies, the recording is saved (2 seconds after death to capture the death scene).
 * 
 * Key features:
 * - Continuous rolling buffer (last 1 minute) per player
 * - Only records players in SURVIVAL mode
 * - Saves recording only on death
 * - Auto-starts recording when player is in survival for 15+ seconds
 * - Does not record creative/spectator players
 */
object DeathCamRecorder {
    // Track recording state per player
    private data class PlayerRecordingState(
        var isRecording: Boolean = false,
        var pendingDeath: Boolean = false,              // Player died, waiting to save
        var survivalStartTick: Long = -1L,              // When player entered survival mode
        var wasDeathTransition: Boolean = false,        // Player just died and might become spectator
        var lastGameMode: GameType? = null              // Track game mode changes
    )
    
    private val playerStates = ConcurrentHashMap<UUID, PlayerRecordingState>()
    private val pendingStops = HashSet<UUID>()
    
    // Time formatting for death cam files
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    
    // Configuration constants
    private const val BUFFER_DURATION_MINUTES = 1
    private const val SURVIVAL_DELAY_SECONDS = 15
    private const val DEATH_SAVE_DELAY_SECONDS = 2
    
    internal fun registerEvents() {
        // Player joins server
        GlobalEventHandler.Server.register<PlayerJoinEvent> { (player) ->
            onPlayerJoin(player)
        }
        
        // Player leaves server
        GlobalEventHandler.Server.register<PlayerLeaveEvent> { (player) ->
            onPlayerLeave(player)
        }
        
        // Player dies - save the recording
        GlobalEventHandler.Server.register<PlayerDeathEvent> { (player, _) ->
            onPlayerDeath(player)
        }
        
        // Player respawns - restart recording if in survival
        GlobalEventHandler.Server.register<PlayerRespawnEvent> { event ->
            onPlayerRespawn(event.player)
        }
        
        // Server tick - check game mode changes and survival duration
        GlobalEventHandler.Server.register<ServerTickEvent> { (server) ->
            onServerTick(server)
        }
        
        // Handle replay save events for cleanup
        GlobalEventHandler.Server.register<ReplayRecorderSaveEvent>(
            phase = ReplayRecorderSaveEvent.PHASE_POST,
            listener = ::onReplaySave
        )
    }
    
    private fun onPlayerJoin(player: ServerPlayer) {
        val uuid = player.uuid
        val state = playerStates.computeIfAbsent(uuid) { PlayerRecordingState() }
        state.lastGameMode = player.gameMode.gameModeForPlayer
        
        // If player joins in survival mode, start the 15-second countdown
        if (player.gameMode.gameModeForPlayer == GameType.SURVIVAL) {
            state.survivalStartTick = player.server.tickCount.toLong()
            ServerReplay.logger.info("Player ${player.scoreboardName} joined in survival mode, starting 15s countdown")
        } else {
            state.survivalStartTick = -1L
            ServerReplay.logger.info("Player ${player.scoreboardName} joined in ${player.gameMode.gameModeForPlayer.name}, not recording")
        }
    }
    
    private fun onPlayerLeave(player: ServerPlayer) {
        val uuid = player.uuid
        val state = playerStates[uuid] ?: return
        
        // Stop recording without saving (unless they had a pending death)
        if (state.isRecording && !state.pendingDeath) {
            stopRecording(player, save = false)
        }
        
        playerStates.remove(uuid)
        pendingStops.remove(uuid)
    }
    
    private fun onPlayerDeath(player: ServerPlayer) {
        val uuid = player.uuid
        val state = playerStates[uuid] ?: return
        
        if (!state.isRecording) {
            ServerReplay.logger.info("Player ${player.scoreboardName} died but was not being recorded")
            return
        }
        
        ServerReplay.logger.info("Player ${player.scoreboardName} died! Saving death cam in ${DEATH_SAVE_DELAY_SECONDS} seconds...")
        
        // Mark as death transition to handle spectator mode correctly
        state.pendingDeath = true
        state.wasDeathTransition = true
        
        // Schedule save after delay
        scheduleDeathSave(player)
    }
    
    @OptIn(DelicateCoroutinesApi::class)
    private fun scheduleDeathSave(player: ServerPlayer) {
        val uuid = player.uuid
        if (!pendingStops.add(uuid)) {
            return // Already pending
        }
        
        val server = player.server
        
        GlobalScope.launch {
            delay(DEATH_SAVE_DELAY_SECONDS.seconds)
            server.execute {
                try {
                    val state = playerStates[uuid]
                    if (state != null && state.pendingDeath) {
                        stopRecording(player, save = true)
                        state.pendingDeath = false
                        ServerReplay.logger.info("Saved death cam for ${player.scoreboardName}")
                        
                        // Don't immediately restart - wait for respawn
                    }
                } finally {
                    pendingStops.remove(uuid)
                }
            }
        }
    }
    
    private fun onPlayerRespawn(player: ServerPlayer) {
        val uuid = player.uuid
        val state = playerStates[uuid] ?: return
        
        // After respawn, check game mode and potentially restart recording
        state.wasDeathTransition = false
        state.lastGameMode = player.gameMode.gameModeForPlayer
        
        if (player.gameMode.gameModeForPlayer == GameType.SURVIVAL) {
            // Start the 15-second countdown before recording again
            state.survivalStartTick = player.server.tickCount.toLong()
            ServerReplay.logger.info("Player ${player.scoreboardName} respawned in survival, starting 15s countdown")
        } else {
            state.survivalStartTick = -1L
            ServerReplay.logger.info("Player ${player.scoreboardName} respawned in ${player.gameMode.gameModeForPlayer.name}, not recording")
        }
    }
    
    private fun onServerTick(server: MinecraftServer) {
        val currentTick = server.tickCount.toLong()
        
        for (player in server.playerList.players) {
            val uuid = player.uuid
            val state = playerStates[uuid] ?: continue
            val currentGameMode = player.gameMode.gameModeForPlayer
            
            // Detect game mode changes
            if (state.lastGameMode != currentGameMode) {
                onGameModeChange(player, state, state.lastGameMode, currentGameMode, currentTick)
                state.lastGameMode = currentGameMode
            }
            
            // Check if 15 seconds in survival have passed
            if (!state.isRecording && 
                !state.pendingDeath && 
                state.survivalStartTick > 0 &&
                currentGameMode == GameType.SURVIVAL) {
                
                val ticksInSurvival = currentTick - state.survivalStartTick
                val secondsInSurvival = ticksInSurvival / 20
                
                if (secondsInSurvival >= SURVIVAL_DELAY_SECONDS) {
                    startRecording(player)
                    state.survivalStartTick = -1L // Reset countdown
                }
            }
        }
    }
    
    private fun onGameModeChange(
        player: ServerPlayer,
        state: PlayerRecordingState,
        oldMode: GameType?,
        newMode: GameType,
        currentTick: Long
    ) {
        val playerName = player.scoreboardName
        
        when {
            // Player switched TO survival
            newMode == GameType.SURVIVAL -> {
                if (!state.isRecording && !state.pendingDeath) {
                    // Start 15-second countdown
                    state.survivalStartTick = currentTick
                    ServerReplay.logger.info("Player $playerName switched to survival, starting 15s countdown")
                }
            }
            
            // Player switched FROM survival to creative/spectator
            newMode == GameType.CREATIVE || newMode == GameType.SPECTATOR -> {
                state.survivalStartTick = -1L // Cancel any pending countdown
                
                // If it's a death transition to spectator, don't stop - let death save handle it
                if (state.wasDeathTransition && newMode == GameType.SPECTATOR) {
                    ServerReplay.logger.info("Player $playerName became spectator after death, death cam save will handle it")
                    return
                }
                
                // Otherwise, stop recording without saving (player left survival voluntarily)
                if (state.isRecording && !state.pendingDeath) {
                    ServerReplay.logger.info("Player $playerName left survival mode (${newMode.name}), stopping recording without saving")
                    stopRecording(player, save = false)
                }
            }
            
            // Adventure mode - treat like survival
            newMode == GameType.ADVENTURE -> {
                if (!state.isRecording && !state.pendingDeath) {
                    state.survivalStartTick = currentTick
                    ServerReplay.logger.info("Player $playerName switched to adventure, starting 15s countdown")
                }
            }
        }
    }
    
    private fun startRecording(player: ServerPlayer) {
        val uuid = player.uuid
        val state = playerStates[uuid] ?: return
        
        if (state.isRecording) {
            return // Already recording
        }
        
        val server = player.server
        val profile = player.gameProfile
        val config = ServerReplay.config
        
        // Create path for death cam recording
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val path = config.playerRecordingPath
            .resolve("deathcam")
            .resolve(profile.name)
            .resolve("recording_$timestamp")
        
        val format = config.defaultReplayFormat
        
        // Create settings with 1-minute max duration (rolling buffer)
        // The restartAfterMaxDuration = true enables the rolling buffer behavior
        val limits = RecorderSettings.FileLimits(
            config.maxFileSize,
            config.restartAfterMaxFileSize,
            BUFFER_DURATION_MINUTES.minutes,
            true  // This is key - restart after max duration to maintain rolling buffer
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
        
        try {
            val recorder = ReplayPlayerRecorders.create(server, profile, path, format, settings)
            recorder.onStart()
            recorder.afterLogin()
            
            state.isRecording = true
            
            ServerReplay.logger.info("Started death cam recording for ${profile.name}")
        } catch (e: Exception) {
            ServerReplay.logger.error("Failed to start death cam recording for ${profile.name}", e)
        }
    }
    
    private fun stopRecording(player: ServerPlayer, save: Boolean) {
        val uuid = player.uuid
        val state = playerStates[uuid] ?: return
        
        if (!state.isRecording) {
            return
        }
        
        val profile = player.gameProfile
        val expectedPath = ServerReplay.config.playerRecordingPath
            .resolve("deathcam")
            .resolve(profile.name)
        
        // Find and stop the recorder
        for (recorder in ReplayPlayerRecorders.recorders()) {
            if (recorder.location.startsWith(expectedPath)) {
                try {
                    recorder.stop(save)
                    if (save) {
                        ServerReplay.logger.info("Saved death cam for ${profile.name}")
                    } else {
                        ServerReplay.logger.info("Discarded death cam buffer for ${profile.name}")
                    }
                } catch (e: Exception) {
                    ServerReplay.logger.error("Failed to stop death cam recording for ${profile.name}", e)
                }
                break
            }
        }
        
        state.isRecording = false
    }
    
    private fun onReplaySave(event: ReplayRecorderSaveEvent) {
        val path = event.output
        
        // Only process death cam saves
        if (!path.toString().contains("deathcam")) {
            return
        }
        
        // For the rolling buffer, we need to clean up old segment files
        // The API creates new files when restarting after max duration
        // We want to keep only the current segment and death saves
        cleanupOldBufferFiles(path.parent, path)
    }
    
    private fun cleanupOldBufferFiles(directory: Path, keepFile: Path) {
        try {
            if (!Files.exists(directory)) return
            
            Files.list(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it != keepFile }
                    .filter { 
                        val name = it.fileName.toString()
                        // Keep files with "death" in name (actual death saves), delete rolling buffer segments
                        name.startsWith("recording_") && (name.endsWith(".mcpr") || name.endsWith(".flashback"))
                    }
                    .forEach { file ->
                        try {
                            // Only delete files older than 2 minutes (old rolling buffer segments)
                            val lastModified = Files.getLastModifiedTime(file).toMillis()
                            val now = System.currentTimeMillis()
                            if (now - lastModified > 2 * 60 * 1000) {
                                Files.deleteIfExists(file)
                                ServerReplay.logger.debug("Cleaned up old buffer file: $file")
                            }
                        } catch (e: Exception) {
                            ServerReplay.logger.warn("Failed to cleanup buffer file: $file", e)
                        }
                    }
            }
        } catch (e: Exception) {
            ServerReplay.logger.warn("Failed to cleanup buffer files in: $directory", e)
        }
    }
    
    /**
     * Check if a player is currently being recorded
     */
    fun isRecording(player: ServerPlayer): Boolean {
        return playerStates[player.uuid]?.isRecording ?: false
    }
    
    /**
     * Get all players currently being recorded
     */
    fun getRecordingPlayers(server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filter { isRecording(it) }
    }
    
    /**
     * Force start recording for a player (manual override)
     */
    fun forceStartRecording(player: ServerPlayer) {
        val state = playerStates.computeIfAbsent(player.uuid) { PlayerRecordingState() }
        state.lastGameMode = player.gameMode.gameModeForPlayer
        startRecording(player)
    }
    
    /**
     * Force stop recording for a player (manual override)
     */
    fun forceStopRecording(player: ServerPlayer, save: Boolean) {
        stopRecording(player, save)
        playerStates[player.uuid]?.let {
            it.survivalStartTick = -1L
        }
    }
}
