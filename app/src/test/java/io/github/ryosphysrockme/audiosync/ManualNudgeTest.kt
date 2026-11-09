package io.github.ryosphysrockme.audiosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ManualNudgeTest {
    @Test
    fun forwardFiftyMillisAdjustsAtOnePointZeroFiveSpeedThenRestoresNormal() {
        val nudge = ManualNudge()
        assertEquals(50L, nudge.request(50L))
        assertEquals(1.05f, nudge.advance(0, wasPlaying = true), 1e-6f)
        repeat(5) { nudge.advance(200, wasPlaying = true) }
        assertFalse(nudge.active)
        assertEquals(1f, nudge.speed, 1e-6f)
        assertEquals(50L, nudge.requestedTotalMs)
    }

    @Test
    fun backwardFiftyMillisUsesZeroPointNineFiveSpeed() {
        val nudge = ManualNudge()
        nudge.request(-50L)
        assertEquals(0.95f, nudge.advance(0, wasPlaying = true), 1e-6f)
        repeat(5) { nudge.advance(200, wasPlaying = true) }
        assertFalse(nudge.active)
        assertEquals(1f, nudge.speed, 1e-6f)
    }

    @Test
    fun hundredMillisecondButtonsUseTheSameSpeedController() {
        val forward = ManualNudge()
        assertEquals(100L, forward.request(ManualNudge.LARGE_STEP_MS))
        assertEquals(1.05f, forward.advance(0L, wasPlaying = true), 1e-6f)
        repeat(10) { forward.advance(200L, wasPlaying = true) }
        assertFalse(forward.active)
        assertEquals(1f, forward.speed, 1e-6f)

        val backward = ManualNudge()
        assertEquals(-100L, backward.request(-ManualNudge.LARGE_STEP_MS))
        assertEquals(0.95f, backward.advance(0L, wasPlaying = true), 1e-6f)
        repeat(10) { backward.advance(200L, wasPlaying = true) }
        assertFalse(backward.active)
        assertEquals(1f, backward.speed, 1e-6f)
    }

    @Test
    fun mixedSizeButtonsAccumulateAndCanCancel() {
        val control = ManualNudge()
        assertEquals(100L, control.request(ManualNudge.LARGE_STEP_MS))
        assertEquals(50L, control.request(ManualNudge.STEP_MS))
        assertEquals(-100L, control.request(-ManualNudge.LARGE_STEP_MS))
        assertEquals(50.0, control.remainingCorrectionMs, 1e-7)
        assertEquals(50L, control.requestedTotalMs)
        assertEquals(-50L, control.request(-ManualNudge.STEP_MS))
        assertFalse(control.active)
    }

    @Test
    fun nothingIsConsumedDuringPauseOrBuffering() {
        val nudge = ManualNudge()
        nudge.request(50L)
        nudge.advance(0L, wasPlaying = false)
        nudge.advance(1_000L, wasPlaying = false)
        assertEquals(50.0, nudge.remainingCorrectionMs, 1e-7)
        assertEquals(1.05f, nudge.speed, 1e-6f)
        nudge.advance(500L, wasPlaying = true)
        assertTrue(nudge.remainingCorrectionMs in 24.9..25.1)
    }

    @Test
    fun rapidPressesAccumulateAndOppositePressesCancelRemaining() {
        val nudge = ManualNudge()
        repeat(4) { nudge.request(50L) }
        assertEquals(200.0, nudge.remainingCorrectionMs, 1e-7)
        nudge.advance(0, wasPlaying = true)
        nudge.advance(1_000, wasPlaying = true)
        assertTrue(abs(nudge.remainingCorrectionMs - 150.0) < 0.01)
        assertEquals(-50L, nudge.request(-50L))
        assertTrue(abs(nudge.remainingCorrectionMs - 100.0) < 0.01)
        nudge.advance(0, wasPlaying = true)
        assertEquals(1.05f, nudge.speed, 1e-6f)
    }

    @Test
    fun cancellationReturnsToNormalSpeedWithoutJump() {
        val nudge = ManualNudge()
        nudge.request(50L)
        nudge.advance(0L, wasPlaying = true)
        nudge.reset()
        assertFalse(nudge.active)
        assertEquals(1f, nudge.speed, 1e-6f)
        assertEquals(0L, nudge.requestedTotalMs)
    }

    @Test
    fun outstandingCorrectionIsBoundedAndDoesNotFlipSignWhenOvershooting() {
        val nudge = ManualNudge()
        repeat(100) { nudge.request(50L) }
        assertEquals(500.0, nudge.remainingCorrectionMs, 1e-7)
        nudge.advance(0, wasPlaying = true)
        nudge.advance(30_000, wasPlaying = true)
        assertEquals(0.0, nudge.remainingCorrectionMs, 1e-7)
        assertEquals(1f, nudge.speed, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeElapsedTime() {
        ManualNudge().advance(-1L, wasPlaying = true)
    }
}
