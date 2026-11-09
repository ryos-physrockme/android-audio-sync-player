package io.github.ryosphysrockme.audiosync

/**
 * This trial deliberately uses at most ONE automatic seek per microphone
 * listening session, irrespective of subsequent position-estimation errors.
 *
 * Seeking is re-armed only by explicitly starting a new listening session.
 * This avoids repeatedly seeking forward and backward due to noisy estimates.
 */
internal class InitialAlignmentPolicy {
    enum class Phase {
        WAITING,
        SEEK_REQUESTED,
        PLAYING
    }

    var phase = Phase.WAITING
        private set

    fun reset() {
        phase = Phase.WAITING
    }

    /** True only for the first accepted position in a listening session. */
    fun tryRequestInitialSeek(): Boolean {
        if (phase != Phase.WAITING) return false
        phase = Phase.SEEK_REQUESTED
        return true
    }

    fun onPlayerStarted() {
        if (phase == Phase.SEEK_REQUESTED) phase = Phase.PLAYING
    }
}

/**
 * Diagnostics for estimated timeline offset. The inputs are the predicted
 * external reference media position minus MediaPlayer.currentPosition, in ms.
 *
 * These are *software/media timeline differences*, NOT acoustic output
 * synchronization measurements. They can be biased by input/output latency.
 */
internal class TimelineErrorHistory(private val window: Int = 7) {
    init {
        require(window > 0 && window % 2 == 1)
    }

    data class Summary(
        val firstMs: Long,
        val currentMedianMs: Long,
        val changeMs: Long,
        val samples: Int
    )

    private val last = ArrayDeque<Long>()
    private var firstMs: Long? = null
    private var samples = 0

    fun reset() {
        last.clear()
        firstMs = null
        samples = 0
    }

    fun add(errorMs: Long): Summary {
        if (firstMs == null) firstMs = errorMs
        last.addLast(errorMs)
        if (last.size > window) last.removeFirst()
        samples++

        val sorted = last.sorted()
        val median = sorted[sorted.size / 2]
        val first = firstMs ?: errorMs
        return Summary(
            firstMs = first,
            currentMedianMs = median,
            changeMs = median - first,
            samples = samples
        )
    }
}
