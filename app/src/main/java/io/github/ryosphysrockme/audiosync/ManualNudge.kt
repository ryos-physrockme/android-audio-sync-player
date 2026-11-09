package io.github.ryosphysrockme.audiosync

/**
 * Compute a small, user-triggered playback seek while preserving play/pause
 * state. Only the calling UI may initiate a manual nudge; microphone matches
 * must never trigger this function.
 */
internal object ManualNudge {
    const val STEP_MS = 50L

    fun targetPositionMs(currentPositionMs: Long, durationMs: Long, deltaMs: Long): Long {
        require(durationMs >= 0L)
        val current = currentPositionMs.coerceIn(0L, durationMs)
        return (current + deltaMs).coerceIn(0L, durationMs)
    }
}
