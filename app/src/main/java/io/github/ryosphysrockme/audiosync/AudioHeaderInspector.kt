package io.github.ryosphysrockme.audiosync

import android.content.ContentResolver
import android.net.Uri

/**
 * Read at most 128 KiB to identify the file format independently from
 * MediaPlayer/MediaExtractor. Audio bytes are never stored or uploaded.
 */
internal object AudioHeaderInspector {
    private const val MAX_BYTES = 128 * 1024

    data class Report(val description: String, val warning: String? = null)

    fun inspect(resolver: ContentResolver, uri: Uri): Report {
        return try {
            val header = ByteArray(MAX_BYTES)
            val count = resolver.openInputStream(uri)?.use { stream ->
                var total = 0
                while (total < header.size) {
                    val n = stream.read(header, total, header.size - total)
                    if (n <= 0) break
                    total += n
                }
                total
            } ?: return Report("ファイルを開けません", "ContentResolver.openInputStream が null を返しました")
            inspect(header.copyOf(count))
        } catch (error: Exception) {
            Report(
                "ヘッダー読取に失敗しました",
                "${error.javaClass.simpleName}: ${error.localizedMessage ?: "詳細なし"}"
            )
        }
    }

    /** Pure parser for unit testing; not a codec support check. */
    fun inspect(bytes: ByteArray): Report {
        if (bytes.size < 12) return Report("音声ヘッダーが短すぎます")
        val signature = ascii(bytes, 0, 4)
        val type = ascii(bytes, 8, 4)
        if (ascii(bytes, 0, 3) == "ID3") return Report("MP3（ID3ヘッダー）")
        if ((bytes[0].toInt() and 0xff) == 0xff &&
            ((bytes[1].toInt() and 0xe0) == 0xe0)) {
            val layer = (bytes[1].toInt() ushr 1) and 0x03
            if (layer != 0) return Report("MPEG Audioフレーム（MP3など）")
            return Report("AAC ADTSの可能性がある音声フレーム")
        }
        if (signature == "fLaC") return Report("FLAC")
        if (signature == "OggS") return Report("Oggコンテナー")
        if (ascii(bytes, 4, 4) == "ftyp") return Report("MP4 / M4Aコンテナー")
        if (signature != "RIFF" && signature != "RF64") {
            return Report("WAV以外の形式、または不明なヘッダー", "先頭識別子：${safeAscii(bytes, 0, 4)}")
        }
        if (type != "WAVE") return Report("$signature / $type", "RIFFですがWAVEではありません")

        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val chunk = ascii(bytes, cursor, 4)
            val length = unsigned32(bytes, cursor + 4)
            val start = cursor + 8
            if (chunk == "fmt ") {
                if (length < 16L || start + 16 > bytes.size) {
                    return Report("$signature/WAVE", "fmtチャンクが不完全です")
                }
                val code = unsigned16(bytes, start)
                val channels = unsigned16(bytes, start + 2)
                val rate = unsigned32(bytes, start + 4)
                val bits = unsigned16(bytes, start + 14)
                val resolvedCode = if (code == 0xfffe && length >= 40 &&
                    start + 26 <= bytes.size) unsigned16(bytes, start + 24) else code
                val kind = when (resolvedCode) {
                    1 -> "PCM整数"
                    3 -> "IEEE浮動小数点"
                    0x55 -> "MP3圧縮"
                    else -> "形式コード $resolvedCode"
                }
                val extended = if (code == 0xfffe) "（WAVEFORMATEXTENSIBLE）" else ""
                val description = "$signature/WAVE、$kind$extended、${bits}bit、${rate}Hz、${channels}ch"
                val warning = when {
                    channels == 0 || rate == 0L -> "チャンネル数またはサンプルレートが不正です"
                    code == 0xfffe -> "拡張形式です。機種により対応が異なります"
                    resolvedCode != 1 && resolvedCode != 3 -> "通常の非圧縮PCMではありません"
                    bits != 8 && bits != 16 && bits != 24 && bits != 32 ->
                        "一般的でないビット深度です"
                    else -> null
                }
                return Report(description, warning)
            }
            if (length > Int.MAX_VALUE.toLong() || start + length > bytes.size) break
            val next = start + length.toInt() + (length.toInt() and 1)
            if (next <= cursor) break
            cursor = next
        }
        return Report("$signature/WAVE", "先頭128KiBにfmtチャンクを確認できませんでした")
    }

    private fun ascii(bytes: ByteArray, start: Int, length: Int): String =
        String(bytes, start, length, Charsets.US_ASCII)

    private fun safeAscii(bytes: ByteArray, start: Int, length: Int): String =
        (start until start + length).joinToString("") {
            val c = bytes[it].toInt() and 0xff
            if (c in 32..126) c.toChar().toString() else "\\x%02X".format(c)
        }

    private fun unsigned16(bytes: ByteArray, i: Int): Int =
        (bytes[i].toInt() and 0xff) or ((bytes[i+1].toInt() and 0xff) shl 8)

    private fun unsigned32(bytes: ByteArray, i: Int): Long =
        ((bytes[i].toLong() and 0xff) or
            ((bytes[i+1].toLong() and 0xff) shl 8) or
            ((bytes[i+2].toLong() and 0xff) shl 16) or
            ((bytes[i+3].toLong() and 0xff) shl 24))
}
