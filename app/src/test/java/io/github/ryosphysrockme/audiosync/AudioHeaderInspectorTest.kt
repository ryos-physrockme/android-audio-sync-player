package io.github.ryosphysrockme.audiosync

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioHeaderInspectorTest {
    private fun putAscii(data: ByteArray, position: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        for (index in bytes.indices) data[position + index] = bytes[index]
    }
    private fun putU16(data: ByteArray, position: Int, value: Int) {
        data[position] = value.toByte()
        data[position + 1] = (value ushr 8).toByte()
    }
    private fun putU32(data: ByteArray, position: Int, value: Int) {
        for (i in 0..3) data[position + i] = (value ushr (8 * i)).toByte()
    }
    private fun sampleWav(code: Int = 1, bits: Int = 16, extensible: Boolean = false): ByteArray {
        val size = if (extensible) 72 else 44
        val data = ByteArray(size)
        putAscii(data, 0, "RIFF")
        putU32(data, 4, size - 8)
        putAscii(data, 8, "WAVE")
        putAscii(data, 12, "fmt ")
        putU32(data, 16, if (extensible) 40 else 16)
        putU16(data, 20, if (extensible) 0xfffe else code)
        putU16(data, 22, 2)
        putU32(data, 24, 44_100)
        putU32(data, 28, 44_100 * 2 * (bits / 8))
        putU16(data, 32, 2 * (bits / 8))
        putU16(data, 34, bits)
        if (extensible) {
            putU16(data, 36, 22) // cbSize
            putU16(data, 38, bits) // valid bits
            putU32(data, 40, 3) // channel mask
            putU16(data, 44, code) // first two bytes of the subtype GUID
            putAscii(data, 60, "data")
        } else {
            putAscii(data, 36, "data")
        }
        return data
    }

    @Test
    fun pcm16StereoReportsEncodingRateAndChannels() {
        val report = AudioHeaderInspector.inspect(sampleWav())
        assertTrue(report.description.contains("PCM整数"))
        assertTrue(report.description.contains("16bit"))
        assertTrue(report.description.contains("44100Hz"))
        assertTrue(report.description.contains("2ch"))
        assertEquals(null, report.warning)
    }

    @Test
    fun floatWavIsDistinguishedFromIntegerPcm() {
        val report = AudioHeaderInspector.inspect(sampleWav(code = 3, bits = 32))
        assertTrue(report.description.contains("IEEE浮動小数点"))
    }

    @Test
    fun extensibleWavIsFlaggedSeparately() {
        val report = AudioHeaderInspector.inspect(sampleWav(extensible = true))
        assertTrue(report.description.contains("WAVEFORMATEXTENSIBLE"))
        assertTrue(report.warning != null)
    }

    @Test
    fun mp3RenamedWavIsIdentifiedByContent() {
        val bytes = ByteArray(32)
        putAscii(bytes, 0, "ID3")
        val report = AudioHeaderInspector.inspect(bytes)
        assertTrue(report.description.contains("MP3"))
    }

    @Test
    fun unknownOrTruncatedHeadersAreNotCalledPcm() {
        val truncated = AudioHeaderInspector.inspect(byteArrayOf(1, 2, 3))
        assertTrue(truncated.description.contains("短すぎ"))
        val strange = AudioHeaderInspector.inspect(ByteArray(20) { 0 })
        assertTrue(strange.warning != null)
    }
}
