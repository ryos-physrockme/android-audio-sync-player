package io.github.ryosphysrockme.audiosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MatchConsensusEvaluationTest {
    private fun candidate(
        timeMs: Long,
        score: Double = 0.90,
        margin: Double = 0.20,
        confident: Boolean = true
    ) = RecordingMatcher.Result(
        referencePositionMs = timeMs,
        score = score,
        alternativeScore = score - margin,
        margin = margin,
        confident = confident
    )

    @Test
    fun reportsWhyAnEstimatedPositionIsNotPlayable() {
        val gate = MatchConsensus()
        assertEquals(
            MatchConsensus.Status.NO_MATCH,
            gate.evaluate(null, 1_000).status
        )
        assertEquals(
            MatchConsensus.Status.LOW_SIMILARITY,
            gate.evaluate(candidate(8_000, score = 0.68, confident = false), 2_000).status
        )
        assertEquals(
            MatchConsensus.Status.AMBIGUOUS,
            gate.evaluate(candidate(8_000, margin = 0.03, confident = false), 3_000).status
        )
    }

    @Test
    fun diagnosticReasonUsesCurrentSliderThreshold() {
        val gate = MatchConsensus()
        val blocked = gate.evaluate(
            candidate(8_000L, score = 0.80, confident = false),
            capturedAtMs = 1_000L,
            similarityThreshold = 0.85
        )
        assertEquals(MatchConsensus.Status.LOW_SIMILARITY, blocked.status)

        val ambiguous = gate.evaluate(
            candidate(8_000L, score = 0.80, margin = 0.02, confident = false),
            capturedAtMs = 2_000L,
            similarityThreshold = 0.60
        )
        assertEquals(MatchConsensus.Status.AMBIGUOUS, ambiguous.status)
    }

    @Test
    fun showsConfirmationThenAcceptanceThenTracking() {
        val gate = MatchConsensus()
        val first = gate.evaluate(candidate(7_000), 1_000)
        assertEquals(MatchConsensus.Status.WAITING_FOR_CONFIRMATION, first.status)
        assertNull(first.acceptance)

        val second = gate.evaluate(candidate(7_768), 1_768)
        assertEquals(MatchConsensus.Status.ACCEPTED, second.status)
        assertNotNull(second.acceptance)

        val third = gate.evaluate(candidate(8_536), 2_536)
        assertEquals(MatchConsensus.Status.TRACKING, third.status)
        assertNotNull(third.acceptance)
    }

    @Test
    fun aConfidentButDiscontinuousCandidateDoesNotAutoplay() {
        val gate = MatchConsensus()
        gate.evaluate(candidate(5_000), 1_000)
        val changed = gate.evaluate(candidate(20_000), 1_768)
        assertEquals(MatchConsensus.Status.CANDIDATE_CHANGED, changed.status)
        assertNull(changed.acceptance)

        val confirmed = gate.evaluate(candidate(20_768), 2_536)
        assertEquals(MatchConsensus.Status.ACCEPTED, confirmed.status)
        assertNotNull(confirmed.acceptance)
    }

    @Test
    fun failedIntermediateMatchMustRestartConfirmation() {
        val gate = MatchConsensus()
        gate.evaluate(candidate(5_000), 1_000)
        gate.evaluate(candidate(5_768, confident = false), 1_768)
        val result = gate.evaluate(candidate(6_536), 2_536)
        assertEquals(MatchConsensus.Status.WAITING_FOR_CONFIRMATION, result.status)
        assertNull(result.acceptance)
    }
}
