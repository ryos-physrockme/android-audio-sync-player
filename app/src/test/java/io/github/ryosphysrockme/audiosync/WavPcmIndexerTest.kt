package io.github.ryosphysrockme.audiosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.sin

class WavPcmIndexerTest {
    private fun ByteArrayOutputStream.putAscii(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }
    private fun ByteArrayOutputStream.put16(value: Int) {
        write(value and 255)
        write((value ushr 8) and 255)
    }
    private fun ByteArrayOutputStream.put32(value: Int) {
        repeat(4) { write((value ushr (8 * it)) and 255) }
    }

    private fun generatedWav(
        seconds: Int = 5,
        channels: Int = 2,
        sampleRate: Int = 44_100,
        code: Int = 1,
        withMetadata: Boolean = false
    ): ByteArray {
        val n = seconds * sampleRate
        val bytes = n * channels * 2
        val metadataLength = if (withMetadata) 12 else 0 // JUNK: 3+pad+8
        val output = ByteArrayOutputStream(bytes + 64)
        output.putAscii("RIFF")
        output.put32(36 + bytes + metadataLength)
        output.putAscii("WAVE")
        output.putAscii("fmt ")
        output.put32(16)
        output.put16(code)
        output.put16(channels)
        output.put32(sampleRate)
        output.put32(sampleRate * channels * 2)
        output.put16(channels * 2)
        output.put16(16)

        if (withMetadata) {
            output.putAscii("JUNK")
            output.put32(3)
            output.write(byteArrayOf(1, 2, 3, 0)) // RIFF odd-chunk padding
        }

        output.putAscii("data")
        output.put32(bytes)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val frequency = 220.0 + 55.0 * ((t * 2).toInt() % 4)
            val sample = (10000 * sin(2 * PI * frequency * t)).toInt()
            output.put16(sample)
            if (channels == 2) output.put16(sample)
        }
        return output.toByteArray()
    }

    @Test
    fun standardPcm16StereoIsIndexedWithoutPlatformMediaExtractor() {
        val wav = generatedWav(withMetadata = true)
        val frames = WavPcmIndexer.indexPcm16(ByteArrayInputStream(wav))
        assertTrue(frames != null && frames.size > 96)
        assertTrue(frames!!.count { it.valid } > 96)
        val lastPosition = frames.last().centerSample * 1000 / SpectralFeatures.SAMPLE_RATE
        assertTrue(lastPosition in 4_500L..5_000L)
    }

    @Test
    fun monoPcm16IsIndexed() {
        val frames = WavPcmIndexer.indexPcm16(
            ByteArrayInputStream(generatedWav(seconds = 4, channels = 1, sampleRate = 16_000))
        )
        assertTrue(frames != null && frames.size >= 96)
    }

    @Test
    fun compressedWavFallsBackToOtherDecoder() {
        val frames = WavPcmIndexer.indexPcm16(
            ByteArrayInputStream(generatedWav(code = 0x55))
        )
        assertNull(frames)
    }

    @Test
    fun otherContainerFallsBackToOriginalDecoder() {
        assertNull(WavPcmIndexer.indexPcm16(ByteArrayInputStream("ID3".toByteArray())))
    }

    @Test
    fun partialInputReadsStillWork() {
        val input = object : InputStream() {
            val src = ByteArrayInputStream(generatedWav(seconds = 4))
            override fun read(): Int = src.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                src.read(b, off, minOf(len, 83))
            override fun skip(n: Long): Long = src.skip(minOf(n, 13))
        }
        val frames = WavPcmIndexer.indexPcm16(input)
        assertTrue(frames != null && frames.size >= 96)
    }

    @Test(expected = java.io.IOException::class)
    fun truncatedPcmFileIsNotSilentlyAccepted() {
        val wav = generatedWav(seconds = 4)
        WavPcmIndexer.indexPcm16(ByteArrayInputStream(wav.copyOf(wav.size / 2)))
    }
}
