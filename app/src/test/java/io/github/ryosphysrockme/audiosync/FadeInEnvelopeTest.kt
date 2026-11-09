package io.github.ryosphysrockme.audiosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeInEnvelopeTest {
    @Test
    fun startsMutedAndEndsAtUnityGain() {
        assertEquals(0f, FadeInEnvelope.gain(0L), 0f)
        assertEquals(0f, FadeInEnvelope.gain(-50L), 0f)
        assertEquals(1f, FadeInEnvelope.gain(FadeInEnvelope.DURATION_MS), 0f)
        assertEquals(1f, FadeInEnvelope.gain(2000L), 0f)
    }

    @Test
    fun remainsMonotonicAndSmooth() {
        val values = (0..60).map { FadeInEnvelope.gain(it * 10L) }
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(0.5f, FadeInEnvelope.gain(300L), 1e-6f)
        assertTrue(FadeInEnvelope.gain(10L) < 0.01f)
        assertTrue(FadeInEnvelope.gain(590L) > 0.99f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDuration() {
        FadeInEnvelope.gain(10L, 0L)
    }
}
