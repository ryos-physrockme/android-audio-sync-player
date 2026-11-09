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
        cancelled: () -> Boolean = { false },
        onWarning: (String) -> Unit = {}
    ): List<SpectralFeatures.Frame>? {
        val stream = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open selected audio file as InputStream")
        return BufferedInputStream(stream, 64 * 1024).use { indexPcm16(it, cancelled, onWarning) }
    }

    // Pure parser for reproducible JVM tests.
    fun indexPcm16(
        input: InputStream,
        cancelled: () -> Boolean = { false },
        onWarning: (String) -> Unit = {}
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
                    return indexData(input, size, sampleRate, channels, cancelled, onWarning)
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
        cancelled: () -> Boolean,
        onWarning: (String) -> Unit
    ): List<SpectralFeatures.Frame> {
        val frames = ArrayList<SpectralFeatures.Frame>()
        val features = SpectralFeatures.Stream()
        val resampler = MonoResampler(sourceRate)
        val bytesPerFrame = channels * 2
        val buffer = ByteArray(16 * 1024 + 4)
        var remaining = bytesToRead
        var actualBytes = 0L
        var stoppedAtEof = false
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
            if (count < 0) {
                // A valid playable PCM recording may declare 0xFFFFFFFF as
                // its unknown data length, or overstate the number of data
                // bytes actually available. Index all complete PCM frames
                // that can be read, then surface a warning instead of
                // rejecting the entire recording.
                stoppedAtEof = true
                break
            }
            if (count == 0) continue
            remaining -= count
            actualBytes += count
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
        // Incomplete tail bytes cannot form an interleaved PCM sample frame.
        // Do not fabricate samples from the remaining 1-3 bytes.
        if (leftover != 0) {
            onWarning(
                "音声データ末尾の%dバイトを除外しました（PCMフレーム未完了）"
                    .format(leftover)
            )
        }
        if (stoppedAtEof) {
            if (bytesToRead == 0xffff_ffffL) {
                onWarning(
                    "WAVのdata長が0xFFFFFFFF（未確定）です。実際に読み取れた%,dバイトを解析しました。"
                        .format(actualBytes)
                )
            } else {
                onWarning(
                    "WAVのdata長が一致しません（指定%,dバイト、取得%,dバイト）。読み取れた範囲を解析しました。"
                        .format(bytesToRead, actualBytes)
                )
            }
        }
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
