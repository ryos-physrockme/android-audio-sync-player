package io.github.ryosphysrockme.audiosync

import org.junit.Assert.*
import org.junit.Test

class InitialAlignmentPolicyTest {

    @Test
    fun acceptedPositionsProduceOnlyOneAutomaticSeekEvenWhenErrorChangesSign() {
        val policy = InitialAlignmentPolicy()
        val simulatedErrorsMs = listOf(600L, -500L, 700L, -350L, 230L, -1100L)
        var automaticSeeks = 0
        for (error in simulatedErrorsMs) {
            // Every accepted measurement can be far from the local position.
            assertNotEquals(0L, error)
            if (policy.tryRequestInitialSeek()) automaticSeeks++
        }
        assertEquals(1, automaticSeeks)
        assertEquals(InitialAlignmentPolicy.Phase.SEEK_REQUESTED, policy.phase)
    }

    @Test
    fun playbackStartKeepsAutomaticSeekingDisabled() {
        val policy = InitialAlignmentPolicy()
        assertTrue(policy.tryRequestInitialSeek())
        policy.onPlayerStarted()
        assertEquals(InitialAlignmentPolicy.Phase.PLAYING, policy.phase)
        assertFalse(policy.tryRequestInitialSeek())
    }

    @Test
    fun newListeningSessionExplicitlyRearmsSingleInitialSeek() {
        val policy = InitialAlignmentPolicy()
        assertTrue(policy.tryRequestInitialSeek())
        policy.onPlayerStarted()
        policy.reset()
        assertEquals(InitialAlignmentPolicy.Phase.WAITING, policy.phase)
        assertTrue(policy.tryRequestInitialSeek())
        assertFalse(policy.tryRequestInitialSeek())
    }

    @Test
    fun historyShowsInitialOffsetAndLaterSmoothedChange() {
        val history = TimelineErrorHistory(window = 3)
        history.add(150L)
        history.add(130L)
        val summary = history.add(170L)
        assertEquals(150L, summary.firstMs)
        assertEquals(150L, summary.currentMedianMs)
        assertEquals(0L, summary.changeMs)
        assertEquals(3, summary.samples)
        val drift = history.add(-50L)
        assertEquals(130L, drift.currentMedianMs)
        assertEquals(-20L, drift.changeMs)
        assertEquals(4, drift.samples)
    }

    @Test
    fun historyClearsBetweenSessions() {
        val history = TimelineErrorHistory()
        history.add(1200L)
        history.reset()
        val first = history.add(-75L)
        assertEquals(-75L, first.firstMs)
        assertEquals(-75L, first.currentMedianMs)
        assertEquals(0L, first.changeMs)
        assertEquals(1, first.samples)
    }
}
