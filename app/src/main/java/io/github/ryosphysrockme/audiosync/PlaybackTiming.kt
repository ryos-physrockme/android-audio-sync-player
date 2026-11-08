package io.github.ryosphysrockme.audiosync

/**
 * Predicts the media position that should be audible at the listener, given a
 * reference position matched to a timestamped microphone sample.
 *
 * All positions, durations, and timestamps use milliseconds. The two clock
 * timestamps must come from the same monotonic clock.
 *
 * This calculation is not itself an acoustic matching algorithm.
 */
internal object PlaybackTiming {
    fun targetPositionMillis(
        referencePositionAtCaptureMs: Long,
        captureClockMs: Long,
        nowClockMs: Long,
        outputLatencyMs: Long,
        userOffsetMs: Long
    ): Long {
        require(nowClockMs >= captureClockMs) {
            "Current timestamp must not precede the captured sample timestamp."
        }
        return (
            referencePositionAtCaptureMs +
                (nowClockMs - captureClockMs) +
                outputLatencyMs +
                userOffsetMs
        ).coerceAtLeast(0L)
    }
}
