package io.github.ryosphysrockme.audiosync

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Minimal facade around Media3 ExoPlayer so the acoustic matching UI can
 * retain its familiar playback operations.
 *
 * Media3 ships a WAV extractor of its own. This avoids a device's platform
 * MediaPlayer/MediaExtractor WAV parsing path, though output still uses
 * Android's audio drivers.
 */
internal class LocalPlayback(context: Context) {
    private val delegate = ExoPlayer.Builder(context).build().apply {
        val attributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        setAudioAttributes(attributes, /* handleAudioFocus = */ true)
    }

    private var onPrepared: ((LocalPlayback) -> Unit)? = null
    private var onSeekComplete: ((LocalPlayback) -> Unit)? = null
    private var onCompletion: ((LocalPlayback) -> Unit)? = null
    private var onError: ((LocalPlayback, Int, Int) -> Boolean)? = null

    var errorDetail: String = ""
        private set

    init {
        delegate.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> onPrepared?.invoke(this@LocalPlayback)
                    Player.STATE_ENDED -> onCompletion?.invoke(this@LocalPlayback)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    onSeekComplete?.invoke(this@LocalPlayback)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorDetail = error.errorCodeName + ": " + (error.message ?: "no description")
                onError?.invoke(this@LocalPlayback, error.errorCode, 0)
            }
        })
    }

    val isPlaying: Boolean
        get() = delegate.isPlaying

    /** Remains true while a seek temporarily buffers; seeking must not pause it. */
    val playWhenReady: Boolean
        get() = delegate.playWhenReady

    val duration: Int
        get() = delegate.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0

    val currentPosition: Int
        get() = delegate.currentPosition.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    fun setDataSource(uri: Uri) {
        // Android's system file picker grants content:// URI read access.
        delegate.setMediaItem(MediaItem.fromUri(uri))
    }

    fun setOnPreparedListener(listener: (LocalPlayback) -> Unit) { onPrepared = listener }
    fun setOnSeekCompleteListener(listener: (LocalPlayback) -> Unit) { onSeekComplete = listener }
    fun setOnCompletionListener(listener: (LocalPlayback) -> Unit) { onCompletion = listener }
    fun setOnErrorListener(listener: (LocalPlayback, Int, Int) -> Boolean) { onError = listener }

    /** Linear playback gain: 0 = muted, 1 = full. Does not change device volume. */
    fun setVolume(gain: Float) { delegate.volume = gain.coerceIn(0f, 1f) }

    /**
     * Adjust playback speed while keeping audio pitch unchanged. The Media3
     * audio pipeline may briefly buffer on some devices after a speed change.
     */
    fun setPlaybackSpeed(speed: Float) {
        require(speed in 0.9f..1.1f)
        delegate.setPlaybackSpeed(speed)
    }

    val playbackSpeed: Float get() = delegate.playbackParameters.speed

    fun prepareAsync() { delegate.prepare() }
    fun start() { delegate.play() }
    fun pause() { delegate.pause() }
    fun seekTo(positionMs: Int) { delegate.seekTo(positionMs.toLong().coerceAtLeast(0L)) }
    fun release() { delegate.release() }
}
