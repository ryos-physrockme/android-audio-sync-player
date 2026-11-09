package io.github.ryosphysrockme.audiosync

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import java.util.Random

class RecordingMatcherTest {
    private val sr = SpectralFeatures.SAMPLE_RATE

    // Deterministic synthetic melody with varying pitches and accents.
    // We use wholly generated PCM rather than any copyrighted source audio.
    private fun track(seconds: Int = 22): FloatArray {
        val samples = FloatArray(seconds * sr)
        val notes = intArrayOf(196, 247, 294, 370, 440, 330, 262, 392, 220, 349, 523, 415, 233, 311, 466, 392)
        for (i in samples.indices) {
            val t = i.toDouble() / sr
            val part = (t * 2.0).toInt()
            val frequency = notes[(part + (part / 5)) % notes.size].toDouble()
            val envelope = if ((t * 2.0) % 1.0 < 0.10) 0.55 else 1.0
            samples[i] = (0.40 * envelope * (
                sin(2.0 * PI * frequency * t) +
                    0.24 * sin(4.0 * PI * frequency * t) +
                    0.10 * sin(6.0 * PI * frequency * t)
                )).toFloat()
        }
        return samples
    }

    private fun features(source: FloatArray): List<SpectralFeatures.Frame> {
        val stream = SpectralFeatures.Stream()
        val output = ArrayList<SpectralFeatures.Frame>()
        for (s in source) stream.add(s) { frame -> output.add(frame) }
        return output
    }

    @Test
    fun matchesExcerptFromMiddleWithChangedGainAndNoise() {
        val referencePcm = track()
        val reference = features(referencePcm)
        val offsetSeconds = 8
        val lengthSeconds = 5
        val noise = Random(72)
        val microphone = FloatArray(sr * lengthSeconds) { i ->
            (referencePcm[offsetSeconds * sr + i] * 0.63f +
                0.025f * (noise.nextDouble() - 0.5)).toFloat()
        }
        val live = features(microphone).takeLast(96)
        val estimate = RecordingMatcher(reference).match(live)
        assertNotNull(estimate)
        assertTrue("Expected a high-confidence position match: $estimate", estimate!!.confident)
        val expectedMs = offsetSeconds * 1000L + live.last().centerSample * 1000L / sr
        assertTrue("Wrong segment: $estimate vs $expectedMs", abs(estimate.referencePositionMs - expectedMs) <= 160L)
    }

    @Test
    fun similarityThresholdIsAdjustableWithoutDisablingAmbiguityCheck() {
        val source = track()
        val reference = features(source)
        val noise = Random(72)
        val start = 8 * sr
        val mic = FloatArray(sr * 5) { i ->
            (source[start + i] * 0.63f +
                0.025f * (noise.nextDouble() - 0.5)).toFloat()
        }
        val live = features(mic).takeLast(96)
        val matcher = RecordingMatcher(reference)
        val baseline = matcher.match(live)
        assertNotNull(baseline)
        assertTrue(baseline!!.confident)
        val relaxed = matcher.match(live, similarityThreshold = (baseline.score - 0.01).coerceAtLeast(0.0))
        val strict = matcher.match(live, similarityThreshold = (baseline.score + 0.01).coerceAtMost(1.0))
        assertTrue(relaxed!!.confident)
        assertFalse(strict!!.confident)

        // A repeated passage is still rejected even at a very lenient
        // similarity threshold because the runner-up ambiguity margin is fixed.
        val repeated = track(12)
        val ambiguous = RecordingMatcher(features(repeated + repeated)).match(
            features(repeated.copyOfRange(2 * sr, 7 * sr)).takeLast(96),
            similarityThreshold = 0.50
        )
        assertNotNull(ambiguous)
        assertFalse(ambiguous!!.confident)
    }

    @Test
    fun matchesAnExcerptWithAnEchoAndFractionalStartTime() {
        val source = track(26)
        val reference = features(source)
        val offset = 11 * sr + sr / 4 // start at 11.25 seconds
        val random = Random(107)
        val microphone = FloatArray(5 * sr) { i ->
            val dry = source[offset + i]
            val reflection = if (i >= 320) source[offset + i - 320] else 0f
            (0.45f * dry + 0.12f * reflection +
                0.018f * (random.nextDouble() - 0.5)).toFloat()
        }
        val micFrames = features(microphone).takeLast(96)
        val result = RecordingMatcher(reference).match(micFrames)
        assertNotNull(result)
        assertTrue("Echoed signal could not be located: $result", result!!.confident)
        val expectedMs = offset * 1000L / sr +
            micFrames.last().centerSample * 1000L / sr
        assertTrue("Wrong position under echo: $result", abs(result.referencePositionMs - expectedMs) <= 225L)
    }

    @Test
    fun reLockRequiresTwoConsistentPostSeekObservations() {
        val gate = MatchConsensus()
        val first = RecordingMatcher.Result(5_000, .94, .3, .64, confident = true)
        assertNull(gate.submit(first, 10_000))
        assertNotNull(gate.submit(first.copy(referencePositionMs = 5_800), 10_800))
        gate.submit(null, 12_000)
        assertNull(gate.submit(first.copy(referencePositionMs = 15_000), 12_100))
        val recovered = gate.submit(first.copy(referencePositionMs = 15_800), 12_900)
        assertNotNull(recovered)
        assertTrue(recovered!!.recovered)
    }

    @Test
    fun silenceCannotProduceAMatch() {
        val reference = features(track())
        val silence = features(FloatArray(sr * 5)).takeLast(96)
        val estimate = RecordingMatcher(reference).match(silence)
        assertNull(estimate)
    }

    @Test
    fun aRepeatedSegmentDoesNotGainFalseConfidence() {
        val half = track(12)
        val repeated = half + half
        val reference = features(repeated)
        val live = features(half.copyOfRange(sr * 2, sr * 7)).takeLast(96)
        val estimate = RecordingMatcher(reference).match(live)
        assertNotNull(estimate)
        assertFalse("Repeated passage should be ambiguous: $estimate", estimate!!.confident)
    }

    @Test
    fun consensusNeedsTwoConsistentObservations() {
        val gate = MatchConsensus()
        val high = RecordingMatcher.Result(8500L, .9, .2, .7, confident = true)
        assertNull(gate.submit(high, 1000L))
        val next = high.copy(referencePositionMs = 9300L)
        val accepted = gate.submit(next, 1800L)
        assertNotNull(accepted)
        assertFalse(accepted!!.recovered)
        assertNull(gate.submit(high.copy(referencePositionMs = 4100L), 2600L))
        assertNotNull(gate.submit(high.copy(referencePositionMs = 4900L), 3400L))
    }

    @Test
    fun resamplerPreservesDurationAndPhaseAcrossChunks() {
        val converter = MonoResampler(44_100)
        var outputSamples = 0
        repeat(44_100) {
            converter.add(sin(2.0 * PI * it / 147.0).toFloat()) { outputSamples++ }
        }
        assertTrue(abs(outputSamples - 16_000) <= 1)
    }
}
