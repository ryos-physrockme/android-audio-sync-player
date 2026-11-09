package io.github.ryosphysrockme.audiosync

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads 16-bit integer PCM RIFF/WAVE without Android MediaExtractor or
 * MediaCodec. The native extractor has failed on real Android devices even
 * when the selected file has a standard PCM WAV header.
 *
 * Reads the document URI as a stream; supports 1 or 2 interleaved channels.
 * PCM bytes are never retained, stored or uploaded. The output is a list of
 * feature frames (~32 ms each).
 *
 * Returns null for files that are not standard PCM16 RIFF/WAVE, so that
 * ReferenceDecoder may use the original decoder for other formats.
 */
internal object WavPcmIndexer {
    private const val MAX_FEATURE_FRAMES = 40_000
    private const val MAX_HEADER_BYTES = 16L * 1024L * 1024L

    fun tryIndex(
        resolver: ContentResolver,
        uri: Uri,
        cancelled: () -> Boolean = { false }
    ): List<SpectralFeatures.Frame>? {
        val stream = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open selected audio file as InputStream")
        return BufferedInputStream(stream, 64 * 1024).use { indexPcm16(it, cancelled) }
    }

    // Pure parser for reproducible JVM tests.
    fun indexPcm16(
        input: InputStream,
        cancelled: () -> Boolean = { false }
    ): List<SpectralFeatures.Frame>? {
        val riff = ByteArray(12)
        if (!readExactly(input, riff, 0, riff.size)) return null
        if (fourCC(riff, 0) != "RIFF" || fourCC(riff, 8) != "WAVE") return null

        var sampleRate = 0
        var channels = 0
        var formatCode = 0
        var bitsPerSample = 0
        var headerBytes = 12L

        while (true) {
            if (cancelled()) throw InterruptedException("Reference indexing cancelled")
            val chunkHeader = ByteArray(8)
            if (!readExactly(input, chunkHeader, 0, chunkHeader.size)) {
                throw IOException("WAV file has no readable data chunk")
            }
            headerBytes += chunkHeader.size
            val name = fourCC(chunkHeader, 0)
            val size = unsigned32(chunkHeader, 4)
            val padding = size and 1L

            when (name) {
                "fmt " -> {
                    require(size in 16L..1_048_576L) {
                        "Unexpected WAV format chunk length: $size"
                    }
                    val fmtSize = minOf(size.toInt(), 64)
                    val fmt = ByteArray(fmtSize)
                    if (!readExactly(input, fmt, 0, fmtSize)) {
                        throw IOException("Truncated WAV format chunk")
                    }
                    if (size > fmtSize) skipExactly(input, size - fmtSize)
                    headerBytes += size
                    formatCode = unsigned16(fmt, 0)
                    channels = unsigned16(fmt, 2)
                    sampleRate = unsigned32(fmt, 4).toInt()
                    bitsPerSample = unsigned16(fmt, 14)
                    if (formatCode == 0xfffe && fmt.size >= 26) {
                        formatCode = unsigned16(fmt, 24)
                    }
                }
                "data" -> {
                    if (formatCode != 1 || bitsPerSample != 16 ||
                        channels !in 1..2 || sampleRate !in 8_000..192_000) {
                        return null
                    }
                    return indexData(input, size, sampleRate, channels, cancelled)
                }
                else -> {
                    // Skip metadata without allocating a buffer for it.
                    skipExactly(input, size)
                    headerBytes += size
                }
            }
            if (padding != 0L) {
                skipExactly(input, 1L)
                headerBytes += 1L
            }
            if (headerBytes > MAX_HEADER_BYTES) {
                throw IOException("WAV header exceeds the 16 MiB safety limit")
            }
        }
    }

    private fun indexData(
        input: InputStream,
        bytesToRead: Long,
        sourceRate: Int,
        channels: Int,
        cancelled: () -> Boolean
    ): List<SpectralFeatures.Frame> {
        val frames = ArrayList<SpectralFeatures.Frame>()
        val features = SpectralFeatures.Stream()
        val resampler = MonoResampler(sourceRate)
        val bytesPerFrame = channels * 2
        val buffer = ByteArray(16 * 1024 + 4)
        var remaining = bytesToRead
        var leftover = 0

        fun acceptSample(sample: Float) {
            resampler.add(sample) { mono ->
                features.add(mono) { frame ->
                    if (frames.size >= MAX_FEATURE_FRAMES) {
                        throw IllegalArgumentException("Reference audio exceeds the 21-minute limit")
                    }
                    frames.add(frame)
                }
            }
        }

        while (remaining > 0L) {
            if (cancelled()) throw InterruptedException("Reference indexing cancelled")
            val wanted = minOf(16 * 1024L, remaining).toInt()
            val count = input.read(buffer, leftover, wanted)
            if (count < 0) throw IOException("WAV audio data ended unexpectedly")
            if (count == 0) continue
            remaining -= count
            val available = leftover + count
            val aligned = available / bytesPerFrame * bytesPerFrame
            var i = 0
            while (i < aligned) {
                val first = signed16(buffer, i)
                val mono = if (channels == 2) {
                    val second = signed16(buffer, i + 2)
                    ((first + second) / 65536.0f)
                } else first / 32768.0f
                acceptSample(mono)
                i += bytesPerFrame
            }
            leftover = available - aligned
            if (leftover > 0) {
                buffer.copyInto(buffer, 0, aligned, available)
            }
        }
        if (leftover != 0) throw IOException("WAV data ends in an incomplete PCM frame")
        require(frames.size >= 96) {
            "WAV recording is too short for the 3-second matching window"
        }
        return frames
    }

    private fun readExactly(
        input: InputStream, buffer: ByteArray, offset: Int, len: Int
    ): Boolean {
        var done = 0
        while (done < len) {
            val n = input.read(buffer, offset + done, len - done)
            if (n < 0) return false
            if (n == 0) continue
            done += n
        }
        return true
    }

    private fun skipExactly(input: InputStream, length: Long) {
        var remaining = length
        val scratch = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n <= 0) throw IOException("WAV chunk extends past end of stream")
            remaining -= n
        }
    }

    private fun fourCC(b: ByteArray, off: Int): String =
        String(b, off, 4, Charsets.US_ASCII)

    private fun unsigned16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun signed16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or (b[off + 1].toInt() shl 8)

    private fun unsigned32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)
}
