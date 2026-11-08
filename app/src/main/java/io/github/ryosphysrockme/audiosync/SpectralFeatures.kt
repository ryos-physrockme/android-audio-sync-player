package io.github.ryosphysrockme.audiosync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming spectral features for coarse position alignment of the SAME recording.
 *
 * Frames use 16 kHz mono PCM, a Hann window of 2048 samples and a 512-sample
 * hop (32 ms). Output vectors have 16 logarithmically spaced frequency bands
 * and are normalized to suppress changes in overall recording volume.
 *
 * The frame's position is the CENTER of its window in the input stream.
 * Silence is explicitly marked invalid; it must not trigger a match.
 */
internal object SpectralFeatures {
    const val SAMPLE_RATE = 16_000
    const val FFT_SIZE = 2_048
    const val HOP_SIZE = 512
    const val BAND_COUNT = 16

    data class Frame(val centerSample: Long, val vector: FloatArray, val valid: Boolean) {
        init {
            require(vector.size == BAND_COUNT)
        }
    }

    class Stream {
        private val ring = FloatArray(FFT_SIZE)
        private val hann = FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
        }
        private val real = FloatArray(FFT_SIZE)
        private val imaginary = FloatArray(FFT_SIZE)
        private val boundaries = IntArray(BAND_COUNT + 1) { i ->
            val lowerHz = 120.0
            val upperHz = 5_800.0
            val ratio = i.toDouble() / BAND_COUNT.toDouble()
            val hz = lowerHz * kotlin.math.exp(ln(upperHz / lowerHz) * ratio)
            (hz * FFT_SIZE / SAMPLE_RATE).toInt().coerceIn(1, FFT_SIZE / 2)
        }

        private var written = 0L
        private var nextFrameAt = FFT_SIZE.toLong()

        fun add(sample: Float, onFrame: (Frame) -> Unit) {
            val position = (written % FFT_SIZE).toInt()
            ring[position] = sample.coerceIn(-1f, 1f)
            written++
            if (written >= nextFrameAt) {
                onFrame(computeFrame())
                nextFrameAt += HOP_SIZE
            }
        }

        fun add(samples: ShortArray, count: Int, onFrame: (Frame) -> Unit) {
            require(count in 0..samples.size)
            for (i in 0 until count) add(samples[i] / 32768f, onFrame)
        }

        private fun computeFrame(): Frame {
            val start = (written % FFT_SIZE).toInt()
            var energy = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = ring[(start + i) % FFT_SIZE].toDouble()
                energy += s * s
                real[i] = (s * hann[i]).toFloat()
                imaginary[i] = 0f
            }
            val rms = sqrt(energy / FFT_SIZE)
            if (rms < 0.003) {
                return Frame(written - FFT_SIZE / 2, FloatArray(BAND_COUNT), false)
            }
            fftInPlace(real, imaginary)

            val vector = FloatArray(BAND_COUNT)
            for (b in 0 until BAND_COUNT) {
                val low = boundaries[b]
                val high = maxOf(low + 1, boundaries[b + 1])
                var bandEnergy = 0.0
                for (k in low until high.coerceAtMost(FFT_SIZE / 2)) {
                    val re = real[k].toDouble()
                    val im = imaginary[k].toDouble()
                    bandEnergy += re * re + im * im
                }
                // Compression makes a change in microphone gain/room acoustics
                // less important than the shape of the spectrum.
                vector[b] = ln(bandEnergy + 1e-4).toFloat()
            }

            var mean = 0.0
            for (v in vector) mean += v
            mean /= BAND_COUNT
            var normSquared = 0.0
            for (b in vector.indices) {
                vector[b] = (vector[b] - mean).toFloat()
                normSquared += vector[b] * vector[b]
            }
            val norm = sqrt(normSquared)
            if (norm < 1e-5) {
                return Frame(written - FFT_SIZE / 2, FloatArray(BAND_COUNT), false)
            }
            for (b in vector.indices) vector[b] = (vector[b] / norm).toFloat()
            return Frame(written - FFT_SIZE / 2, vector, true)
        }
    }

    // In-place radix-2 FFT, with one sine/cosine computation per butterfly stage.
    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            val half = length / 2
            var offset = 0
            while (offset < n) {
                var twiddleRe = 1.0
                var twiddleIm = 0.0
                for (k in 0 until half) {
                    val even = offset + k
                    val odd = even + half
                    val r = re[odd] * twiddleRe - im[odd] * twiddleIm
                    val v = re[odd] * twiddleIm + im[odd] * twiddleRe
                    val oldRe = re[even].toDouble()
                    val oldIm = im[even].toDouble()
                    re[even] = (oldRe + r).toFloat()
                    im[even] = (oldIm + v).toFloat()
                    re[odd] = (oldRe - r).toFloat()
                    im[odd] = (oldIm - v).toFloat()
                    val nextRe = twiddleRe * stepRe - twiddleIm * stepIm
                    val nextIm = twiddleRe * stepIm + twiddleIm * stepRe
                    twiddleRe = nextRe
                    twiddleIm = nextIm
                }
                offset += length
            }
            length *= 2
        }
    }
}

/**
 * Converts decoded mono samples at their actual sample rate into 16 kHz samples.
 * Linear interpolation preserves a continuous resampling phase across buffers.
 *
 * For a same-recording match this is sufficient as a baseline; higher-quality
 * anti-aliasing resampling can be substituted if measurements require it.
 */
internal class MonoResampler(private val sourceRate: Int) {
    init {
        require(sourceRate > 0)
    }

    private var inputIndex = 0L
    private var lastSample = 0f
    private var nextOutputIndex = 0.0
    private val sourceSamplesPerOutput =
        sourceRate.toDouble() / SpectralFeatures.SAMPLE_RATE.toDouble()

    fun add(sample: Float, emit: (Float) -> Unit) {
        val currentIndex = inputIndex.toDouble()
        while (nextOutputIndex <= currentIndex + 1e-10) {
            val fraction = (nextOutputIndex - (currentIndex - 1.0)).coerceIn(0.0, 1.0)
            val interpolated = if (inputIndex == 0L) {
                sample
            } else {
                lastSample + (sample - lastSample) * fraction.toFloat()
            }
            emit(interpolated)
            nextOutputIndex += sourceSamplesPerOutput
        }
        lastSample = sample
        inputIndex++
    }
}
