package io.github.ryosphysrockme.audiosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPresentationTest {
    @Test
    fun aCandidateDoesNotImplySynchronizationIsComplete() {
        assertEquals(
            "位置を確認中",
            MatchPresentation.status(MatchConsensus.Status.WAITING_FOR_CONFIRMATION)
        )
        assertEquals(
            "一致度が不足",
            MatchPresentation.status(MatchConsensus.Status.LOW_SIMILARITY)
        )
        assertEquals(
            "候補が複数",
            MatchPresentation.status(MatchConsensus.Status.AMBIGUOUS)
        )
        assertEquals(
            "同期位置を確認",
            MatchPresentation.status(MatchConsensus.Status.ACCEPTED)
        )
        assertEquals(
            "位置を追跡中",
            MatchPresentation.status(MatchConsensus.Status.TRACKING)
        )
    }

    @Test
    fun candidateIncludesRealReferencePositionAndSimilarityScore() {
        val text = MatchPresentation.candidate(263_000L, 0.657)
        assertTrue(text.contains("04:23"))
        assertTrue(text.contains("0.657"))
        assertTrue(text.contains("候補"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeReferencePositionIsNotShown() {
        MatchPresentation.candidate(-1L, 0.5)
    }
}
