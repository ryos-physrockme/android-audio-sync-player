package io.github.ryosphysrockme.audiosync

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import java.io.IOException

/**
 * Open a selected local audio document for compressed-audio indexing.
 *
 * Some providers expose a content URI which native MediaExtractor cannot open
 * by URI, even though the picker gave this app access to its bytes. First try
 * the explicitly granted AssetFileDescriptor, then a fresh extractor with the
 * original content URI. AssetFileDescriptor is closed after setDataSource().
 *
 * ExoPlayer handles actual playback; this factory only supplies compressed
 * samples to MediaCodec for on-device spectral reference extraction.
 */
internal object ExtractorSource {
    fun open(context: Context, uri: Uri, onWarning: (String) -> Unit = {}): MediaExtractor {
        var firstFailure: Exception? = null

        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
                val extractor = MediaExtractor()
                try {
                    when {
                        asset.length >= 0L -> extractor.setDataSource(
                            asset.fileDescriptor, asset.startOffset, asset.length
                        )
                        asset.startOffset == 0L -> extractor.setDataSource(asset.fileDescriptor)
                        else -> throw IOException(
                            "The provider has a non-zero offset and unknown data length"
                        )
                    }
                    return extractor
                } catch (error: Exception) {
                    extractor.release()
                    firstFailure = error
                }
            }
        } catch (error: Exception) {
            firstFailure = error
        }

        val fallback = MediaExtractor()
        try {
            fallback.setDataSource(context, uri, null)
            if (firstFailure != null) {
                onWarning(
                    "ファイル記述子からの読込に失敗したため、URI経由に切り替えました：" +
                        firstFailure!!.javaClass.simpleName
                )
            }
            return fallback
        } catch (error: Exception) {
            fallback.release()
            val detail = if (firstFailure != null) {
                "FileDescriptor: " + firstFailure!!.javaClass.simpleName +
                    " (" + (firstFailure!!.localizedMessage ?: "no detail") + "); "
            } else {
                "FileDescriptor not available; "
            }
            throw IOException(
                "圧縮音源を開けませんでした。 " + detail + "URI: " +
                    error.javaClass.simpleName + " (" +
                    (error.localizedMessage ?: "no detail") + ")",
                error
            )
        }
    }
}
