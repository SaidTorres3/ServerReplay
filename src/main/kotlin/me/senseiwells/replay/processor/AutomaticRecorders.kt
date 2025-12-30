package me.senseiwells.replay.processor

/**
 * AutomaticRecorders is no longer used.
 * All player recording is handled by DeathCamRecorder which provides:
 * - Continuous 1-minute rolling buffer for survival players
 * - Automatic death POV capture
 * - Game mode aware recording (survival only)
 * 
 * This object is kept for backwards compatibility but does nothing.
 */
object AutomaticRecorders {
    internal fun registerEvents() {
        // Death cam recording is now the only mode
        // All player recording is handled by DeathCamRecorder
    }
}