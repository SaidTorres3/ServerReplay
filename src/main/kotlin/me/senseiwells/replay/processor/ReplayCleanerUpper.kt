package me.senseiwells.replay.processor

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import me.senseiwells.replay.ServerReplay
import net.casual.arcade.replay.io.ReplayFormat
import java.nio.file.FileVisitResult
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.visitFileTree
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * ReplayCleanerUpper - Disabled for death cam mode
 * 
 * Death cam recordings are kept until manually deleted.
 * This object is kept for backwards compatibility but does nothing.
 */
object ReplayCleanerUpper {
    @OptIn(DelicateCoroutinesApi::class)
    internal fun run() {
        // Cleanup disabled for death cam mode - recordings are preserved
        // Users should manually manage their death cam recordings
    }
}