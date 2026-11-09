package io.github.ryosphysrockme.audiosync

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Reads an Android-supported local audio file, decodes it to mono PCM and
 * builds streaming spectral features. Work must run off the UI thread.
 *
 * Does not keep decoded audio or microphone data. The selected URI remains
 * local, and no external audio-fingerprinting service is used.
 */
internal object ReferenceDecoder {
    private const val MAX_REFERENCE_FRAMES = 40_000 // ~21 minutes at a 32 ms hop

    fun index(
        context: Context,
        uri: Uri,
        cancelled: () -> Boolean = { false },
        onWarning: (String) -> Unit = {}
    ): List<SpectralFeatures.Frame> {
        // Fast, platform-independent path for the common PCM16 WAV format.
        // In particular this avoids MediaExtractor.setDataSource(), which
        // fails on some device/file combinations despite a valid WAV header.
        val wavFrames = WavPcmIndexer.tryIndex(context.contentResolver, uri, cancelled, onWarning)
        if (wavFrames != null) return wavFrames

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false

        try {
            extractor.setDataSource(context, uri, null)

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrack = i
                    break
                }
            }
            require(audioTrack >= 0) { "No decodable audio track was found" }
            extractor.selectTrack(audioTrack)

            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Audio MIME type is missing")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true
            val activeCodec = codec

            val stream = SpectralFeatures.Stream()
            val frames = ArrayList<SpectralFeatures.Frame>()
            var inputDone = false
            var outputDone = false
            var resampler: MonoResampler? = null
            var outputChannels = 0
            var decodedSampleRate = 0
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferInfo = MediaCodec.BufferInfo()

            fun refreshOutputFormat() {
                val format = activeCodec.outputFormat
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                require(channels > 0 && sampleRate > 0)
                require(
                    encoding == AudioFormat.ENCODING_PCM_16BIT ||
                        encoding == AudioFormat.ENCODING_PCM_FLOAT
                ) { "Unsupported decoder PCM encoding: $encoding" }
                if (resampler != null) {
                    // Changing the sample rate in the middle would require an
                    // explicit discontinuity; reject it for the first prototype.
                    require(sampleRate == decodedSampleRate)
                } else {
                    resampler = MonoResampler(sampleRate)
                    decodedSampleRate = sampleRate
                }
                outputChannels = channels
                pcmEncoding = encoding
            }

            refreshOutputFormat()

            while (!outputDone) {
                if (cancelled()) throw InterruptedException("Audio indexing cancelled")
                if (!inputDone) {
                    val inIndex = activeCodec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val input = activeCodec.getInputBuffer(inIndex)
                            ?: throw IllegalStateException("Missing codec input buffer")
                        input.clear()
                        val count = extractor.readSampleData(input, 0)
                        if (count < 0) {
                            activeCodec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                            activeCodec.queueInputBuffer(inIndex, 0, count, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> refreshOutputFormat()
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex >= 0 -> {
                        try {
                            if (bufferInfo.size > 0) {
                                val output = activeCodec.getOutputBuffer(outIndex)
                                    ?: throw IllegalStateException("Missing decoder output buffer")
                                output.position(bufferInfo.offset)
                                output.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = output.slice().order(ByteOrder.LITTLE_ENDIAN)
                                val bytesPerSample = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    else -> 2
                                }
                                val frameBytes = bytesPerSample * outputChannels
                                require(bytes.remaining() % frameBytes == 0) {
                                    "Decoder returned an incomplete PCM frame"
                                }
                                val count = bytes.remaining() / frameBytes
                                repeat(count) {
                                    var mono = 0f
                                    repeat(outputChannels) {
                                        mono += if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                            bytes.float
                                        } else {
                                            bytes.short / 32768f
                                        }
                                    }
                                    mono /= outputChannels
                                    resampler!!.add(mono) { sample ->
                                        stream.add(sample) { frame ->
                                            if (frames.size >= MAX_REFERENCE_FRAMES) {
                                                throw IllegalArgumentException(
                                                    "Reference recording exceeds the 21-minute prototype limit"
                                                )
                                            }
                                            frames.add(frame)
                                        }
                                    }
                                }
                            }
                        } finally {
                            activeCodec.releaseOutputBuffer(outIndex, false)
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            require(frames.size >= 96) {
                "Recording is too short: at least 3.1 seconds of decoded audio are needed"
            }
            return frames
        } finally {
            if (codecStarted) {
                try { codec?.stop() } catch (_: Exception) { }
            }
            codec?.release()
            extractor.release()
        }
    }

}
