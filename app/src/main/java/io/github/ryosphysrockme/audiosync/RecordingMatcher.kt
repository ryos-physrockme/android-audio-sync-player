package io.github.ryosphysrockme.audiosync

import kotlin.math.abs

/**
 * Deliberately conservative subsequence matcher for the same recording.
 *
 * A query consists of the most recent 96 consecutive spectral frames, or ~3.0 s.
 * Each candidate offset is scored by average cosine similarity of spectral
 * vectors, with a coarse search followed by a local full-hop refinement.
 *
 * It is not designed for differently arranged accompaniment, transposed
 * songs, or changing live tempo.
 */
internal class RecordingMatcher(
    private val reference: List<SpectralFeatures.Frame>,
    private val windowSize: Int = 96
) {
    companion object {
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.72
        const val MINIMUM_ALTERNATIVE_MARGIN = 0.075
    }

    data class Result(
        /** Position of the LAST matched microphone feature in the reference. */
        val referencePositionMs: Long,
        val score: Double,
        val alternativeScore: Double,
        val margin: Double,
        val confident: Boolean
    )

    init {
        require(windowSize >= 32 && windowSize % 4 == 0)
    }

    fun match(
        live: List<SpectralFeatures.Frame>,
        similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD
    ): Result? {
        require(similarityThreshold in 0.0..1.0)
        if (live.size != windowSize || reference.size < windowSize) return null
        val validMicFrames = live.count { it.valid }
        if (validMicFrames < windowSize * 0.65) return null

        val lastStart = reference.size - windowSize
        var bestIndex = -1
        var bestScore = Double.NEGATIVE_INFINITY
        val coarse = DoubleArray(lastStart / 4 + 1) { Double.NEGATIVE_INFINITY }
        for (coarseIdx in coarse.indices) {
            val start = coarseIdx * 4
            val score = similarity(live, start, 4)
            coarse[coarseIdx] = score
            if (score > bestScore) {
                bestScore = score
                bestIndex = start
            }
        }
        if (bestIndex < 0 || !bestScore.isFinite()) return null

        // Refine by 32 ms increments around the best coarse candidate.
        val refineStart = (bestIndex - 4).coerceAtLeast(0)
        val refineEnd = (bestIndex + 4).coerceAtMost(lastStart)
        var refinedScore = Double.NEGATIVE_INFINITY
        var refinedIndex = bestIndex
        for (start in refineStart..refineEnd) {
            val score = similarity(live, start, 2)
            if (score > refinedScore) {
                refinedScore = score
                refinedIndex = start
            }
        }
        if (!refinedScore.isFinite()) return null
        bestScore = refinedScore
        bestIndex = refinedIndex

        // Ignore only immediate alignment jitter (within ~0.5 s), rather
        // than whole musical phrases. Distinct repeated passages, even when
        // fairly close together, should count as ambiguous alternatives.
        val exclusion = (SpectralFeatures.SAMPLE_RATE / 2) / SpectralFeatures.HOP_SIZE
        var alternativeIndex = -1
        var alternativeScore = Double.NEGATIVE_INFINITY
        for (i in coarse.indices) {
            val start = i * 4
            if (abs(start - bestIndex) > exclusion && coarse[i] > alternativeScore) {
                alternativeScore = coarse[i]
                alternativeIndex = start
            }
        }

        // Refine the runner-up to the SAME resolution and scoring stride as
        // the best match. Comparing a refined winner against a coarse
        // runner-up would artificially inflate confidence on repeated motifs.
        var alternative = Double.NEGATIVE_INFINITY
        if (alternativeIndex >= 0) {
            val secondStart = (alternativeIndex - 4).coerceAtLeast(0)
            val secondEnd = (alternativeIndex + 4).coerceAtMost(lastStart)
            for (start in secondStart..secondEnd) {
                if (abs(start - bestIndex) > exclusion) {
                    alternative = maxOf(alternative, similarity(live, start, 2))
                }
            }
        }
        val margin = if (alternative.isFinite()) bestScore - alternative else 1.0
        val centerSample = reference[bestIndex + windowSize - 1].centerSample

        // Conservative initial criteria; not yet tuned with room recordings.
        val confident = bestScore >= similarityThreshold && margin >= MINIMUM_ALTERNATIVE_MARGIN
        return Result(
            referencePositionMs = centerSample * 1000L / SpectralFeatures.SAMPLE_RATE,
            score = bestScore,
            alternativeScore = alternative,
            margin = margin,
            confident = confident
        )
    }

    private fun similarity(
        mic: List<SpectralFeatures.Frame>,
        referenceStart: Int,
        stride: Int
    ): Double {
        var sum = 0.0
        var compared = 0
        var eligible = 0
        for (q in mic.indices step stride) {
            if (!mic[q].valid) continue
            eligible++
            val ref = reference[referenceStart + q]
            if (!ref.valid) continue
            var dot = 0.0
            for (band in 0 until SpectralFeatures.BAND_COUNT) {
                dot += mic[q].vector[band] * ref.vector[band]
            }
            sum += dot
            compared++
        }
        // Do not reward a candidate consisting predominantly of silence.
        if (eligible < (windowSize / stride) * 0.65 ||
            compared < eligible * 0.65) {
            return Double.NEGATIVE_INFINITY
        }
        return sum / compared
    }
}

/**
 * Requires two temporally consistent, high-confidence matches before seeking.
 * Previous reliable matches are retained to detect unexpected external seeks.
 */
internal class MatchConsensus {
    data class Observation(val positionMs: Long, val capturedAtMs: Long)
    data class Acceptance(val observation: Observation, val recovered: Boolean)

    /** A diagnostic status rather than a calibrated statistical probability. */
    enum class Status {
        NO_MATCH,
        LOW_SIMILARITY,
        AMBIGUOUS,
        WAITING_FOR_CONFIRMATION,
        CANDIDATE_CHANGED,
        ACCEPTED,
        TRACKING
    }

    data class Evaluation(val status: Status, val acceptance: Acceptance? = null)

    private var accepted: Observation? = null
    private var pending: Observation? = null

    fun reset() {
        accepted = null
        pending = null
    }

    /**
     * UI callers use [evaluate] to distinguish insufficient confidence from
     * a missing player callback. Kept separate from the matcher itself.
     */
    fun evaluate(
        result: RecordingMatcher.Result?,
        capturedAtMs: Long,
        similarityThreshold: Double = RecordingMatcher.DEFAULT_SIMILARITY_THRESHOLD
    ): Evaluation {
        if (result == null) {
            pending = null
            return Evaluation(Status.NO_MATCH)
        }
        if (!result.confident) {
            pending = null
            return Evaluation(
                if (result.score < similarityThreshold) Status.LOW_SIMILARITY else Status.AMBIGUOUS
            )
        }

        val current = Observation(result.referencePositionMs, capturedAtMs)
        val previousAccepted = accepted
        if (previousAccepted != null && isContinuous(previousAccepted, current, 450L)) {
            accepted = current
            pending = null
            return Evaluation(Status.TRACKING, Acceptance(current, recovered = false))
        }

        val previousPending = pending
        if (previousPending != null && isContinuous(previousPending, current, 250L)) {
            val recovered = previousAccepted != null
            accepted = current
            pending = null
            return Evaluation(Status.ACCEPTED, Acceptance(current, recovered))
        }

        pending = current
        return Evaluation(
            if (previousPending == null) Status.WAITING_FOR_CONFIRMATION
            else Status.CANDIDATE_CHANGED
        )
    }

    /** Compatibility with original unit tests. */
    fun submit(
        result: RecordingMatcher.Result?,
        capturedAtMs: Long
    ): Acceptance? = evaluate(result, capturedAtMs).acceptance

    private fun isContinuous(previous: Observation, next: Observation, toleranceMs: Long): Boolean {
        val elapsed = next.capturedAtMs - previous.capturedAtMs
        if (elapsed <= 0L || elapsed > 10_000L) return false
        val expected = previous.positionMs + elapsed
        return abs(expected - next.positionMs) <= toleranceMs
    }
}
