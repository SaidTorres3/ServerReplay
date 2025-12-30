package me.senseiwells.replay.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.lucko.fabric.api.permissions.v0.Permissions
import me.senseiwells.replay.ServerReplay
import me.senseiwells.replay.http.DownloadReplaysHttpInjector
import me.senseiwells.replay.processor.DeathCamRecorder
import me.senseiwells.replay.processor.RecorderWarner
import net.casual.arcade.commands.*
import net.casual.arcade.commands.arguments.EnumArgument
import net.casual.arcade.replay.io.FlashbackIO
import net.casual.arcade.replay.io.ReplayFormat
import net.casual.arcade.replay.io.ReplayModIO
import net.casual.arcade.replay.recorder.player.ReplayPlayerRecorders
import net.casual.arcade.replay.util.FileUtils.streamDirectoryEntriesOrEmpty
import net.casual.arcade.replay.viewer.ReplayViewers
import net.casual.arcade.utils.component.bold
import net.casual.arcade.utils.component.link
import net.casual.arcade.utils.component.yellow
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.PermissionLevel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.*

/**
 * Death Cam Recorder Commands
 * 
 * Simplified command set for death cam functionality:
 * - /deathcam status - Show recording status
 * - /deathcam reload - Reload configuration
 * - /deathcam start <players> - Force start recording for players
 * - /deathcam stop <players> [save] - Force stop recording for players
 * - /deathcam view <name> <replay> - View a saved death replay
 * - /deathcam download <name> <replay> - Get download link for replay
 * - /deathcam encoding set <format> - Change replay format
 */
object ReplayCommand: CommandTree {
    override fun create(buildContext: CommandBuildContext): LiteralArgumentBuilder<CommandSourceStack> {
        return CommandTree.buildLiteral("deathcam") {
            requires { Permissions.check(it, "server-replay.commands.deathcam", PermissionLevel.OWNERS) }
            
            literal("status") {
                executes(::queryStatuses)
            }
            
            literal("reload") {
                executes(::reload)
            }
            
            literal("start") {
                argument("players", EntityArgument.players()) {
                    executes(::forceStartRecording)
                }
            }
            
            literal("stop") {
                argument("players", EntityArgument.players()) {
                    executes { forceStopRecording(it, save = false) }
                    argument("save", BoolArgumentType.bool()) {
                        executes(::forceStopRecording)
                    }
                }
            }
            
            literal("view") {
                argument("name", StringArgumentType.string()) {
                    suggests(::suggestSavedPlayerName)
                    argument("replay", StringArgumentType.string()) {
                        suggests(::suggestSavedReplayName)
                        executes(::viewReplay)
                    }
                }
            }
            
            literal("download") {
                argument("name", StringArgumentType.string()) {
                    suggests(::suggestSavedPlayerName)
                    argument("replay", StringArgumentType.string()) {
                        suggests(::suggestSavedReplayName)
                        executes(::downloadReplay)
                    }
                }
            }
            
            literal("encoding") {
                literal("set") {
                    argument("encoding", EnumArgument.enumeration<ReplayFormat> { it.id() }) {
                        executes(::setDefaultEncoding)
                    }
                }
            }
        }
    }

    private fun queryStatuses(context: CommandContext<CommandSourceStack>): Int {
        val builder = StringBuilder("Death Cam Status:\n")
        
        val server = context.source.server
        val recordingPlayers = DeathCamRecorder.getRecordingPlayers(server)
        
        if (recordingPlayers.isNotEmpty()) {
            builder.append("Currently Recording:\n")
            for (player in recordingPlayers) {
                builder.append("  - ${player.scoreboardName}\n")
            }
        } else {
            builder.append("No players currently being recorded\n")
        }
        
        // Show active recorders from the API
        val recorders = ReplayPlayerRecorders.recorders()
        val deathCamRecorders = recorders.filter { it.location.toString().contains("deathcam") }
        if (deathCamRecorders.isNotEmpty()) {
            builder.append("\nActive Recorders:\n")
            for (recorder in deathCamRecorders) {
                builder.append("  ${recorder.getName()} - ${recorder.getStatus()}\n")
            }
        }
        
        val closing = ReplayPlayerRecorders.closing().filter { it.location.toString().contains("deathcam") }
        if (closing.isNotEmpty()) {
            builder.append("\nCurrently Saving:\n")
            for (saving in closing) {
                builder.append("  ${saving.getName()}\n")
            }
        }

        return context.source.success(builder.removeSuffix("\n").toString())
    }

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        ServerReplay.reload()
        context.source.sendSuccess({ Component.literal("Successfully reloaded death cam config.") }, true)
        return 1
    }

    private fun forceStartRecording(context: CommandContext<CommandSourceStack>): Int {
        val players = EntityArgument.getPlayers(context, "players")
        val format = ServerReplay.config.defaultReplayFormat
        RecorderWarner.output(context.source, format)
        
        var successes = 0
        for (player in players) {
            if (!DeathCamRecorder.isRecording(player)) {
                DeathCamRecorder.forceStartRecording(player)
                successes++
            }
        }
        
        if (successes > 0) {
            return context.source.success("Force started death cam recording for $successes player(s)", true)
        }
        return context.source.fail("Failed to start any recordings (players may already be recording)")
    }

    private fun forceStopRecording(
        context: CommandContext<CommandSourceStack>,
        save: Boolean = BoolArgumentType.getBool(context, "save")
    ): Int {
        val players = EntityArgument.getPlayers(context, "players")
        var successes = 0
        
        for (player in players) {
            if (DeathCamRecorder.isRecording(player)) {
                DeathCamRecorder.forceStopRecording(player, save)
                successes++
            }
        }
        
        if (successes > 0) {
            val action = if (save) "saved" else "discarded"
            return context.source.success("Force stopped and $action $successes recording(s)", true)
        }
        return context.source.fail("Failed to stop any recordings (players may not be recording)")
    }

    private fun viewReplay(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val name = StringArgumentType.getString(context, "name")
        val replay = StringArgumentType.getString(context, "replay")
        
        val directory = ServerReplay.config.playerRecordingPath
            .resolve("deathcam")
            .resolve(name)

        var path = directory.resolve(ReplayModIO.addFileExtension(replay))
        if (path.notExists()) {
            path = directory.resolve(FlashbackIO.addFileExtension(replay))
        }

        if (path.exists()) {
            ReplayViewers.create(path, player).start()
            return Command.SINGLE_SUCCESS
        }
        return context.source.fail("Failed to view replay, death cam $name/$replay doesn't exist!")
    }

    private fun downloadReplay(context: CommandContext<CommandSourceStack>): Int {
        if (!ServerReplay.config.allowDownloadingReplays) {
            return context.source.fail("Downloading replays is disabled, you must enable it in the config")
        }

        val name = StringArgumentType.getString(context, "name")
        val replay = StringArgumentType.getString(context, "replay")
        
        val root = "player/${URLEncoder.encode("deathcam/$name", StandardCharsets.UTF_8)}"
        val pathStr = "$root/${URLEncoder.encode(replay, StandardCharsets.UTF_8)}"
        val url = DownloadReplaysHttpInjector.createUrl(context.source.server, pathStr)
        val here = Component.literal("[here]").yellow().bold().link(url)
        val message = Component.literal("You can download the death cam replay ").append(here)
        context.source.sendSystemMessage(message)
        return Command.SINGLE_SUCCESS
    }

    private fun setDefaultEncoding(context: CommandContext<CommandSourceStack>): Int {
        val format = EnumArgument.getEnumeration<ReplayFormat>(context, "encoding")
        if (!format.supported) {
            return context.source.fail("Encoding ${format.id()} is not yet supported for this version of Minecraft")
        }

        ServerReplay.updateConfig { config -> config.copy(defaultReplayFormat = format) }
        format.warn { message ->
            context.source.sendSystemMessage(Component.literal(message))
        }
        return context.source.success("Successfully changed encoding type to ${format.id()}")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun suggestSavedPlayerName(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        val deathCamPath = ServerReplay.config.playerRecordingPath.resolve("deathcam")
        val names = deathCamPath.streamDirectoryEntriesOrEmpty()
            .filter { it.isDirectory() }
            .map { "\"${it.name}\"" }
        return SharedSuggestionProvider.suggest(names, builder)
    }

    private fun suggestSavedReplayName(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        val name = StringArgumentType.getString(context, "name")
        val playerPath = ServerReplay.config.playerRecordingPath.resolve("deathcam").resolve(name)
        val names = playerPath.streamDirectoryEntriesOrEmpty()
            .filter(this::isReplayFile)
            .map { "\"${it.nameWithoutExtension}\"" }
        return SharedSuggestionProvider.suggest(names, builder)
    }

    private fun isReplayFile(path: Path): Boolean {
        return ReplayFormat.formatOf(path) != null
    }
}