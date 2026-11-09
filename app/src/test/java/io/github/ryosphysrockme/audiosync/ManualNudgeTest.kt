package io.github.ryosphysrockme.audiosync

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualNudgeTest {
    @Test
    fun forwardNudgeAdvancesByFiftyMilliseconds() {
        assertEquals(5_050L, ManualNudge.targetPositionMs(5_000L, 60_000L, ManualNudge.STEP_MS))
    }

    @Test
    fun backwardNudgeMovesBackFiftyMilliseconds() {
        assertEquals(4_950L, ManualNudge.targetPositionMs(5_000L, 60_000L, -ManualNudge.STEP_MS))
    }

    @Test
    fun neverSeeksOutsideRecording() {
        assertEquals(0L, ManualNudge.targetPositionMs(20L, 10_000L, -ManualNudge.STEP_MS))
        assertEquals(10_000L, ManualNudge.targetPositionMs(9_980L, 10_000L, ManualNudge.STEP_MS))
    }

    @Test
    fun repeatPressesCanAccumulateFiftyMillisecondSteps() {
        var position = 5_000L
        repeat(4) {
            position = ManualNudge.targetPositionMs(position, 60_000L, ManualNudge.STEP_MS)
        }
        assertEquals(5_200L, position)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeDuration() {
        ManualNudge.targetPositionMs(10L, -1L, 50L)
    }
}
