package me.senseiwells.replay.config

import com.mojang.authlib.GameProfile
import kotlinx.serialization.*
import kotlinx.serialization.EncodeDefault.Mode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import me.senseiwells.replay.ServerReplay
import me.senseiwells.replay.config.chunk.ChunkAreaConfig
import me.senseiwells.replay.config.serialization.ExtraCodecs
import me.senseiwells.replay.config.serialization.PathSerializer
import net.casual.arcade.replay.io.ReplayFormat
import net.casual.arcade.replay.recorder.settings.RecorderSettings
import net.casual.arcade.replay.recorder.settings.RecorderSettings.ChunkRecordingStrategy
import net.casual.arcade.replay.recorder.settings.SimpleRecorderSettings
import net.casual.arcade.replay.util.io.FileSize
import net.casual.arcade.utils.serialization.codec.ArcadeExtraCodecs
import net.casual.arcade.utils.serialization.kotlin.CodecSerializersModule
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import org.apache.commons.lang3.SerializationException
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.time.Duration

/**
 * Configuration for Death Cam Recorder
 * 
 * This plugin continuously records players in survival mode with a 1-minute rolling buffer.
 * When a player dies, the recording is saved to capture their death POV.
 * Perfect for UHC servers to capture death moments and prevent "lag" excuses.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ReplayConfig(
    @SerialName("debug")
    @EncodeDefault(Mode.NEVER)
    val debug: Boolean = false,
    @Contextual
    @JsonNames("encoding")
    @SerialName("default_encoding")
    val defaultReplayFormat: ReplayFormat = ReplayFormat.ReplayMod,
    @SerialName("world_name")
    val worldName: String = "World",
    @SerialName("server_name")
    val serverName: String = "Server",
    @SerialName("player_recording_path")
    @Serializable(with = PathSerializer::class)
    val playerRecordingPath: Path = recordings.resolve("deathcams"),
    @Contextual
    @SerialName("max_file_size")
    val maxFileSize: FileSize = FileSize(0),
    @SerialName("restart_after_max_file_size")
    val restartAfterMaxFileSize: Boolean = false,
    @SerialName("recover_unsaved_replays")
    val recoverUnsavedReplays: Boolean = true,
    @EncodeDefault(Mode.NEVER)
    @SerialName("fixed_daylight_cycle")
    val fixedDaylightCycle: Long = -1L,
    @SerialName("chunk_recorder_load_radius")
    val chunkRecorderLoadRadius: Int = -1,
    @Contextual
    @SerialName("chunk_recording_strategy")
    val chunkRecordingStrategy: ChunkRecordingStrategy = ChunkRecordingStrategy.Always,
    @SerialName("notify_admins_of_status")
    val notifyAdminsOfStatus: Boolean = true,
    @SerialName("include_resource_packs")
    val includeResourcePacks: Boolean = true,
    @SerialName("ignore_custom_payloads")
    val ignoreCustomPayloads: Boolean = false,
    @SerialName("ignore_sound_packets")
    val ignoreSoundPackets: Boolean = false,
    @SerialName("ignore_light_packets")
    val ignoreLightPackets: Boolean = true,
    @SerialName("ignore_chat_packets")
    val ignoreChatPackets: Boolean = false,
    @SerialName("ignore_action_bar_packets")
    val ignoreActionBarPackets: Boolean = false,
    @SerialName("ignore_scoreboard_packets")
    val ignoreScoreboardPackets: Boolean = false,
    @SerialName("optimize_explosion_packets")
    val optimizeExplosionPackets: Boolean = true,
    @SerialName("optimize_entity_packets")
    val optimizeEntityPackets: Boolean = false,
    @SerialName("record_hotbar")
    val recordHotbar: Boolean = false,
    @SerialName("record_voice_chat")
    val recordVoiceChat: Boolean = false,
    @JsonNames("replay_viewer_pack_ip")
    @SerialName("replay_server_ip")
    val replayServerIp: String? = null,
    @SerialName("allow_downloading_replays")
    val allowDownloadingReplays: Boolean = false,
) {
    // For backwards compatibility - not used in death cam mode
    val chunkRecordingPath: Path get() = recordings.resolve("chunks")
    val chunks: List<ChunkAreaConfig> get() = listOf()

    fun getPlayerRecordingLocation(profile: GameProfile): Path {
        return this.playerRecordingPath.resolve("deathcam").resolve(profile.name)
    }

    fun getRootRecordingPaths(): List<Path> {
        return listOf(this.playerRecordingPath)
    }

    fun createSettings(): SimpleRecorderSettings {
        return SimpleRecorderSettings(
            this.debug,
            this.worldName,
            this.serverName,
            this.fixedDaylightCycle,
            this.includeResourcePacks,
            this.chunkRecorderLoadRadius,
            this.chunkRecordingStrategy,
            RecorderSettings.FileLimits(
                this.maxFileSize,
                this.restartAfterMaxFileSize,
                Duration.ZERO,
                false
            ),
            RecorderSettings.IgnorePackets(
                this.ignoreCustomPayloads,
                this.ignoreSoundPackets,
                this.ignoreLightPackets,
                this.ignoreChatPackets,
                this.ignoreActionBarPackets,
                this.ignoreScoreboardPackets
            ),
            RecorderSettings.OptimizePackets(
                this.optimizeExplosionPackets,
                this.optimizeEntityPackets
            ),
            this.recordHotbar,
            this.recordVoiceChat
        )
    }

    companion object {
        private val recordings = FabricLoader.getInstance().configDir.resolveSibling("recordings")

        private val root = FabricLoader.getInstance().configDir.resolve("server-replay")
        private val config = this.root.resolve("config.json")

        private val json = Json {
            encodeDefaults = true
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true

            serializersModule = CodecSerializersModule {
                contextual(FileSize.STRING_CODEC)
                contextual(Identifier.CODEC)
                contextual(ChunkRecordingStrategy.CODEC)
                contextual(ArcadeExtraCodecs.DURATION.orElse(Duration.ZERO))
                contextual(ReplayFormat.CODEC.orElse(ReplayFormat.ReplayMod))
                contextual(ExtraCodecs.LENIENT_PERMISSION_LEVEL)
            }
        }

        fun resolve(path: String): Path {
            return this.root.resolve(path)
        }

        @JvmStatic
        fun read(): ReplayConfig {
            if (!this.config.exists()) {
                ServerReplay.logger.info("Generating default config")
                val config = ReplayConfig()
                this.write(config)
                return config
            }
            try {
                return this.config.inputStream().use {
                    json.decodeFromStream(it)
                }
            } catch (e: Exception) {
                ServerReplay.logger.error("Failed to read replay config, generating default", e)
                val config = ReplayConfig()
                this.write(config)
                return config
            }
        }

        @JvmStatic
        fun write(config: ReplayConfig) {
            try {
                this.config.createParentDirectories()
                this.config.outputStream().use {
                    json.encodeToStream(config, it)
                }
            } catch (e: IOException) {
                ServerReplay.logger.error("Failed to write replay config", e)
            } catch (e: SerializationException) {
                ServerReplay.logger.error("Failed to serialize replay config", e)
            }
        }

        @Deprecated("Temporary function to migrate old configs")
        @OptIn(ExperimentalPathApi::class)
        internal fun migrateOldConfigs() {
            val oldPath = this.root.resolveSibling("ServerReplay")
            try {
                if (oldPath.isDirectory()) {
                    oldPath.copyToRecursively(this.root, overwrite = false, followLinks = true)
                    oldPath.deleteRecursively()
                }
            } catch (_: IOException) {
                ServerReplay.logger.error("Failed to migrate ServerReplay configs!")
            }
        }
    }
}
