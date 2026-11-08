package io.github.ryosphysrockme.audiosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Development milestone: choose/play a local file and monitor the microphone.
 *
 * This screen deliberately does not claim to synchronize playback. Position
 * matching, timestamp integration, and headphone calibration come next.
 */
class MainActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var prepared = false

    private lateinit var fileText: TextView
    private lateinit var playbackText: TextView
    private lateinit var microphoneText: TextView
    private lateinit var playButton: Button
    private lateinit var microphoneButton: Button
    private lateinit var seekBar: SeekBar

    private val uiHandler = Handler(Looper.getMainLooper())

    private val progressUpdate = object : Runnable {
        override fun run() {
            if (prepared) {
                try {
                    player?.let { active ->
                        if (!seekBar.isPressed && active.duration > 0) {
                            seekBar.progress = ((active.currentPosition.toLong() * 1000L) /
                                active.duration.toLong()).toInt()
                        }
                        playbackText.text =
                            formatTime(active.currentPosition) + " / " + formatTime(active.duration)
                    }
                } catch (_: IllegalStateException) {
                    // The player may have been released during an asynchronous callback.
                }
            }
            uiHandler.postDelayed(this, 200L)
        }
    }

    private val chooseAudioFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // The current document grant still permits playback in this session.
                }
                loadAudioFile(uri)
            }
        }

    private val requestMicrophonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMicrophone()
            } else {
                microphoneText.setText(R.string.mic_denied)
            }
        }

    @Volatile
    private var captureRunning = false
    private var microphoneRecorder: AudioRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        uiHandler.post(progressUpdate)
    }

    private fun buildScreen() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val heading = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        }
        container.addView(heading, fullWidth())

        val explanation = TextView(this).apply {
            setText(R.string.prototype_notice)
            textSize = 15f
            setPadding(0, 12, 0, 20)
        }
        container.addView(explanation, fullWidth())

        container.addView(Button(this).apply {
            setText(R.string.choose_audio)
            setOnClickListener { chooseAudioFile.launch(arrayOf("audio/*")) }
        }, fullWidth())

        fileText = TextView(this).apply {
            setText(R.string.no_audio)
            textSize = 15f
            setPadding(0, 12, 0, 12)
        }
        container.addView(fileText, fullWidth())

        playbackText = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 16f
        }
        container.addView(playbackText, fullWidth())

        seekBar = SeekBar(this).apply {
            max = 1000
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}

                override fun onStartTrackingTouch(bar: SeekBar?) {}

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val active = player ?: return
                    if (!prepared) return
                    try {
                        val targetMs =
                            ((active.duration.toLong() * progress.toLong()) / 1000L).toInt()
                        active.seekTo(targetMs)
                    } catch (_: IllegalStateException) {
                        // Ignore seeking while the player changes state.
                    }
                }
            })
        }
        container.addView(seekBar, fullWidth())

        playButton = Button(this).apply {
            setText(R.string.play_audio)
            isEnabled = false
            setOnClickListener { togglePlayback() }
        }
        container.addView(playButton, fullWidth())

        microphoneText = TextView(this).apply {
            setText(R.string.mic_idle)
            textSize = 16f
            setPadding(0, 20, 0, 12)
        }
        container.addView(microphoneText, fullWidth())

        microphoneButton = Button(this).apply {
            setText(R.string.start_microphone)
            setOnClickListener {
                if (captureRunning) {
                    stopMicrophone()
                } else if (microphoneRecorder == null) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        startMicrophone()
                    } else {
                        requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
        container.addView(microphoneButton, fullWidth())

        setContentView(ScrollView(this).apply { addView(container) })
    }

    private fun fullWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun loadAudioFile(uri: Uri) {
        prepared = false
        seekBar.isEnabled = false
        playButton.isEnabled = false
        playButton.setText(R.string.play_audio)
        player?.release()
        player = null

        fileText.text = displayName(uri)
        playbackText.setText(R.string.loading)
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            next.setDataSource(this, uri)
            next.setOnPreparedListener {
                if (player === next) {
                    prepared = true
                    playButton.isEnabled = true
                    seekBar.isEnabled = true
                    playbackText.setText(R.string.ready)
                }
            }
            next.setOnCompletionListener {
                if (player === next) playButton.setText(R.string.play_audio)
            }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) {
                    prepared = false
                    playButton.isEnabled = false
                    seekBar.isEnabled = false
                    playbackText.setText(R.string.file_error)
                }
                true
            }
            player = next
            next.prepareAsync()
        } catch (_: Exception) {
            next.release()
            player = null
            playbackText.setText(R.string.file_error)
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "Audio file"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Audio file"
        }
    }

    private fun togglePlayback() {
        if (!prepared) return
        try {
            val active = player ?: return
            if (active.isPlaying) {
                active.pause()
                playButton.setText(R.string.play_audio)
            } else {
                active.start()
                playButton.setText(R.string.pause_audio)
            }
        } catch (_: IllegalStateException) {
            playbackText.setText(R.string.file_error)
        }
    }

    private fun startMicrophone() {
        if (captureRunning || microphoneRecorder != null) return
        microphoneText.setText(R.string.mic_starting)

        val rate = 16000
        val minimum = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) {
            microphoneText.setText(R.string.mic_error)
            return
        }

        var capture: AudioRecord? = null
        try {
            capture = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(2 * minimum, rate))
                .build()

            if (capture.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize")
            }

            val manager = getSystemService(AUDIO_SERVICE) as AudioManager
            val builtInMicrophone = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtInMicrophone != null) {
                capture.setPreferredDevice(builtInMicrophone)
            }

            capture.startRecording()
            if (capture.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord failed to start")
            }

            val activeCapture = capture
            microphoneRecorder = activeCapture
            captureRunning = true
            microphoneButton.setText(R.string.stop_microphone)
            microphoneText.text = "マイク：録音入力を確認中（音源は保存しません）"

            Thread({ monitorMicrophone(activeCapture) }, "microphone-monitor").start()
        } catch (_: Exception) {
            capture?.release()
            microphoneRecorder = null
            captureRunning = false
            microphoneButton.setText(R.string.start_microphone)
            microphoneText.setText(R.string.mic_error)
        }
    }

    private fun monitorMicrophone(capture: AudioRecord) {
        val samples = ShortArray(2048)
        var lastDisplayedMs = 0L
        try {
            while (captureRunning) {
                val count = capture.read(
                    samples,
                    0,
                    samples.size,
                    AudioRecord.READ_BLOCKING
                )
                if (count <= 0) break

                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastDisplayedMs < 200L) continue
                lastDisplayedMs = nowMs

                var energy = 0.0
                for (index in 0 until count) {
                    val value = samples[index].toDouble() / 32768.0
                    energy += value * value
                }
                val rms = sqrt(energy / count)
                val decibels = if (rms > 0.0) {
                    String.format(Locale.US, "%.1f dBFS", 20.0 * log10(rms))
                } else {
                    "-∞ dBFS"
                }

                runOnUiThread {
                    if (microphoneRecorder === capture && captureRunning) {
                        microphoneText.text = "マイク入力レベル：$decibels"
                    }
                }
            }
        } catch (_: Exception) {
            // On some devices stopping AudioRecord interrupts the blocking read.
        } finally {
            try {
                capture.stop()
            } catch (_: IllegalStateException) {
                // Stopping twice is not an error here.
            }
            capture.release()
            runOnUiThread {
                if (microphoneRecorder === capture) {
                    microphoneRecorder = null
                    captureRunning = false
                    microphoneButton.setText(R.string.start_microphone)
                    microphoneText.setText(R.string.mic_idle)
                }
            }
        }
    }

    private fun stopMicrophone() {
        captureRunning = false
        microphoneButton.isEnabled = false
        try {
            microphoneRecorder?.stop()
        } catch (_: IllegalStateException) {
            // The monitor thread also attempts to clean up.
        } finally {
            microphoneButton.isEnabled = true
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds.coerceAtLeast(0)) / 1000
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    override fun onStop() {
        stopMicrophone()
        try {
            if (player?.isPlaying == true) {
                player?.pause()
                playButton.setText(R.string.play_audio)
            }
        } catch (_: IllegalStateException) {
            // The playback may already have completed.
        }
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(progressUpdate)
        stopMicrophone()
        prepared = false
        player?.release()
        player = null
        super.onDestroy()
    }
}
