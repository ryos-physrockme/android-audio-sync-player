package io.github.ryosphysrockme.audiosync

/**
 * Smoothstep fade-in gain, 0 to 1. Used only when an external recording
 * triggers an automatic playback start, not for manual player operations.
 *
 * No additional seek, pause, or speed adjustment is performed by fading.
 */
internal object FadeInEnvelope {
    const val DURATION_MS = 600L

    fun gain(elapsedMs: Long, durationMs: Long = DURATION_MS): Float {
        require(durationMs > 0L)
        val fraction = (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        return (fraction * fraction * (3.0 - 2.0 * fraction)).toFloat()
    }
}
