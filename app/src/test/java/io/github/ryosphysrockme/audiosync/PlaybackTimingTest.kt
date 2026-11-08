package io.github.ryosphysrockme.audiosync

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimingTest {
    @Test
    fun advancesPositionByElapsedTimeAndOffsets() {
        assertEquals(
            5310L,
            PlaybackTiming.targetPositionMillis(
                referencePositionAtCaptureMs = 5000L,
                captureClockMs = 10000L,
                nowClockMs = 10250L,
                outputLatencyMs = 80L,
                userOffsetMs = -20L
            )
        )
    }

    @Test
    fun avoidsNegativeMediaPositions() {
        assertEquals(
            0L,
            PlaybackTiming.targetPositionMillis(
                referencePositionAtCaptureMs = 100L,
                captureClockMs = 200L,
                nowClockMs = 200L,
                outputLatencyMs = 0L,
                userOffsetMs = -500L
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonMonotonicTimestampPair() {
        PlaybackTiming.targetPositionMillis(
            referencePositionAtCaptureMs = 100L,
            captureClockMs = 300L,
            nowClockMs = 200L,
            outputLatencyMs = 0L,
            userOffsetMs = 0L
        )
    }
}
