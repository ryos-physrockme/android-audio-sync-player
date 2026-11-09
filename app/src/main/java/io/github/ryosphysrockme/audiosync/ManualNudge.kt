package io.github.ryosphysrockme.audiosync

import kotlin.math.roundToLong

/**
 * Generates time corrections by slightly changing playback speed rather than
 * calling seekTo(): +50 ms at 1.05x for about a second, -50 ms at 0.95x.
 *
 * This pure controller does not assume the rate changes have zero latency.
 * Time spent paused/buffering must not be counted as correction progress.
 */
internal class ManualNudge(
    private val speedOffset: Float = 0.05f,
    private val maxPendingMs: Double = 500.0
) {
    companion object {
        const val STEP_MS = 50L
        const val LARGE_STEP_MS = 100L
    }

    init {
        require(speedOffset > 0f && speedOffset < 1f)
        require(maxPendingMs >= STEP_MS)
    }

    var remainingCorrectionMs: Double = 0.0
        private set

    var requestedTotalMs: Long = 0L
        private set

    var speed: Float = 1f
        private set

    /** Adds or cancels queued correction. Returns the accepted time delta. */
    fun request(deltaMs: Long): Long {
        val before = remainingCorrectionMs
        remainingCorrectionMs =
            (before + deltaMs.toDouble()).coerceIn(-maxPendingMs, maxPendingMs)
        val accepted = (remainingCorrectionMs - before).roundToLong()
        requestedTotalMs += accepted
        return accepted
    }

    /** Integrate the previous rate, then decide the next rate. */
    fun advance(elapsedMs: Long, wasPlaying: Boolean): Float {
        require(elapsedMs >= 0L)
        if (wasPlaying && remainingCorrectionMs != 0.0) {
            val before = remainingCorrectionMs
            remainingCorrectionMs -= elapsedMs * (speed.toDouble() - 1.0)
            if ((before > 0 && remainingCorrectionMs <= 0) ||
                (before < 0 && remainingCorrectionMs >= 0)) {
                remainingCorrectionMs = 0.0
            }
        }
        speed = when {
            remainingCorrectionMs > 0.5 -> 1f + speedOffset
            remainingCorrectionMs < -0.5 -> 1f - speedOffset
            else -> {
                remainingCorrectionMs = 0.0
                1f
            }
        }
        return speed
    }

    val active: Boolean get() = remainingCorrectionMs != 0.0

    fun reset() {
        remainingCorrectionMs = 0.0
        requestedTotalMs = 0L
        speed = 1f
    }
}
