package io.github.ryosphysrockme.audiosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
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
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Offline same-recording synchronizer, experimental:
 *  1. Decode/index a local track.
 *  2. Record the phone microphone in short buffers.
 *  3. Match a ~3 s rolling spectrogram against the local reference.
 *  4. Seek the player only after two consistent, unambiguous matches.
 *
 * No network service or third-party fingerprinting library is required.
 * This prototype does not yet guarantee acoustic output alignment.
 */
class MainActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var prepared = false
    private var reference: List<SpectralFeatures.Frame>? = null

    @Volatile
    private var indexGeneration = 0
    @Volatile
    private var captureRunning = false

    private var microphoneRecorder: AudioRecord? = null
    private val consensus = MatchConsensus()
    private var correctionOffsetMs = 0L
    private var pendingAutoStart = false
    private var lastCorrectionElapsedMs = 0L

    private lateinit var fileText: TextView
    private lateinit var playbackText: TextView
    private lateinit var microphoneText: TextView
    private lateinit var matchText: TextView
    private lateinit var offsetText: TextView
    private lateinit var playButton: Button
    private lateinit var microphoneButton: Button
    private lateinit var seekBar: SeekBar

    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressUpdate = object : Runnable {
        override fun run() {
            val active = player
            if (prepared && active != null) {
                try {
                    if (!seekBar.isPressed && active.duration > 0) {
                        seekBar.progress = (
                            active.currentPosition.toLong() * seekBar.max /
                                active.duration.toLong()
                            ).toInt()
                    }
                    playbackText.text = formatTime(active.currentPosition) +
                        " / " + formatTime(active.duration)
                } catch (_: IllegalStateException) {
                    // Player can be released when a new reference is chosen.
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
                    // The active document permission remains usable in this session.
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
        container.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        }, fullWidth())

        container.addView(TextView(this).apply {
            setText(R.string.prototype_notice)
            textSize = 15f
            setPadding(0, 12, 0, 20)
        }, fullWidth())

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
                    stopMicrophone()
                    try {
                        val target = active.duration.toLong() * progress / seekBar.max
                        active.seekTo(target.toInt())
                    } catch (_: IllegalStateException) {
                        // Ignore a seek during a player state transition.
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
            isEnabled = false
            setOnClickListener {
                if (captureRunning) {
                    stopMicrophone()
                } else if (microphoneRecorder == null && prepared && reference != null) {
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

        matchText = TextView(this).apply {
            text = "音源の特徴量を計算すると、再生位置の照合を開始できます。"
            textSize = 15f
            setPadding(0, 10, 0, 20)
        }
        container.addView(matchText, fullWidth())

        offsetText = TextView(this).apply {
            text = offsetLabel(0L)
            textSize = 15f
        }
        container.addView(offsetText, fullWidth())

        container.addView(SeekBar(this).apply {
            max = 120
            progress = 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        correctionOffsetMs = (progress - 60) * 10L
                        offsetText.text = offsetLabel(correctionOffsetMs)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }, fullWidth())

        setContentView(ScrollView(this).apply { addView(container) })
    }

    private fun fullWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun offsetLabel(offsetMs: Long): String =
        "出力位置の手動補正：%+d ms（＋はイヤホン側を先に進める）"
            .format(Locale.JAPAN, offsetMs)

    private fun loadAudioFile(uri: Uri) {
        stopMicrophone()
        val token = ++indexGeneration
        reference = null
        consensus.reset()
        prepared = false
        pendingAutoStart = false
        seekBar.isEnabled = false
        playButton.isEnabled = false
        microphoneButton.isEnabled = false
        playButton.setText(R.string.play_audio)
        player?.release()
        player = null

        fileText.text = displayName(uri)
        playbackText.setText(R.string.loading)
        matchText.text = "音源を解析中。数秒程度の音声特徴列を曲全体について計算します。"

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
                if (player === next && token == indexGeneration) {
                    prepared = true
                    playButton.isEnabled = true
                    seekBar.isEnabled = true
                    microphoneButton.isEnabled = reference != null
                    playbackText.setText(R.string.ready)
                }
            }
            next.setOnSeekCompleteListener {
                if (player === next && pendingAutoStart && captureRunning) {
                    pendingAutoStart = false
                    try {
                        if (!next.isPlaying) {
                            next.start()
                            playButton.setText(R.string.pause_audio)
                        }
                    } catch (_: IllegalStateException) {
                        matchText.text = "再生開始に失敗しました。"
                    }
                }
            }
            next.setOnCompletionListener {
                if (player === next) playButton.setText(R.string.play_audio)
            }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) {
                    prepared = false
                    playButton.isEnabled = false
                    microphoneButton.isEnabled = false
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

        // A separate decoder is needed even if MediaPlayer has already prepared.
        Thread({
            try {
                val indexed = ReferenceDecoder.index(applicationContext, uri) {
                    token != indexGeneration
                }
                runOnUiThread {
                    if (token == indexGeneration && !isFinishing && !isDestroyed) {
                        reference = indexed
                        microphoneButton.isEnabled = prepared && microphoneRecorder == null
                        matchText.text = "解析完了（%dフレーム）。マイクを開始してください。"
                            .format(Locale.JAPAN, indexed.size)
                    }
                }
            } catch (_: InterruptedException) {
                // A new track was chosen while the old one was being decoded.
            } catch (error: Exception) {
                runOnUiThread {
                    if (token == indexGeneration && !isFinishing && !isDestroyed) {
                        reference = null
                        microphoneButton.isEnabled = false
                        matchText.text = "解析できませんでした：" +
                            (error.localizedMessage ?: error.javaClass.simpleName)
                    }
                }
            }
        }, "reference-indexer").start()
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "Audio file"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Audio file"
        }
    }

    private fun togglePlayback() {
        if (!prepared) return
        stopMicrophone()
        pendingAutoStart = false
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
        val index = reference ?: return
        if (!prepared || captureRunning || microphoneRecorder != null) return
        microphoneText.setText(R.string.mic_starting)

        val rate = SpectralFeatures.SAMPLE_RATE
        val minimum = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
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
                throw IllegalStateException("AudioRecord is not initialized")
            }

            val manager = getSystemService(AUDIO_SERVICE) as AudioManager
            val builtInMic = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtInMic != null) capture.setPreferredDevice(builtInMic)

            capture.startRecording()
            if (capture.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not start")
            }

            val active = capture
            microphoneRecorder = active
            captureRunning = true
            consensus.reset()
            lastCorrectionElapsedMs = 0L
            pendingAutoStart = false
            microphoneButton.setText(R.string.stop_microphone)
            matchText.text = "外部音声を約3秒取得してから照合を始めます。"
            microphoneText.text = "マイク入力中（音声は保存しません）"
            Thread({
                monitorMicrophone(active, RecordingMatcher(index))
            }, "microphone-matcher").start()
        } catch (_: Exception) {
            capture?.release()
            microphoneRecorder = null
            captureRunning = false
            microphoneButton.setText(R.string.start_microphone)
            microphoneText.setText(R.string.mic_error)
        }
    }

    private fun monitorMicrophone(capture: AudioRecord, matcher: RecordingMatcher) {
        val samples = ShortArray(2048)
        val stream = SpectralFeatures.Stream()
        val recent = ArrayDeque<SpectralFeatures.Frame>(96)
        var processedSamples = 0L
        var framesReceived = 0
        var lastMatchFrame = 0
        var lastLevelUpdatedNs = 0L

        try {
            while (captureRunning) {
                val count = capture.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) break
                processedSamples += count
                val readCompletedNs = System.nanoTime()

                val hardwareTime = AudioTimestamp()
                val hasTimestamp = capture.getTimestamp(
                    hardwareTime, AudioTimestamp.TIMEBASE_MONOTONIC
                ) == AudioRecord.SUCCESS

                stream.add(samples, count) { frame ->
                    framesReceived++
                    if (recent.size == 96) recent.removeFirst()
                    recent.addLast(frame)

                    if (recent.size == 96 && framesReceived - lastMatchFrame >= 24) {
                        lastMatchFrame = framesReceived
                        val observationNs = if (hasTimestamp) {
                            hardwareTime.nanoTime +
                                (frame.centerSample - hardwareTime.framePosition) *
                                    1_000_000_000L / SpectralFeatures.SAMPLE_RATE
                        } else {
                            readCompletedNs -
                                (processedSamples - frame.centerSample) *
                                    1_000_000_000L / SpectralFeatures.SAMPLE_RATE
                        }
                        val estimate = matcher.match(recent.toList())
                        runOnUiThread {
                            if (captureRunning && microphoneRecorder === capture) {
                                showMatchResult(estimate, observationNs)
                            }
                        }
                    }
                }

                if (readCompletedNs - lastLevelUpdatedNs >= 250_000_000L) {
                    lastLevelUpdatedNs = readCompletedNs
                    var energy = 0.0
                    for (i in 0 until count) {
                        val s = samples[i].toDouble() / 32768.0
                        energy += s * s
                    }
                    val rms = sqrt(energy / count)
                    val level = if (rms > 0.0) {
                        "%.1f dBFS".format(Locale.US, 20.0 * log10(rms))
                    } else {
                        "-∞ dBFS"
                    }
                    runOnUiThread {
                        if (captureRunning && microphoneRecorder === capture) {
                            microphoneText.text = "マイク入力レベル：$level"
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Some implementations abort a blocking read when recording stops.
        } finally {
            try { capture.stop() } catch (_: Exception) { }
            capture.release()
            runOnUiThread {
                if (microphoneRecorder === capture) {
                    microphoneRecorder = null
                    captureRunning = false
                    pendingAutoStart = false
                    microphoneButton.setText(R.string.start_microphone)
                    microphoneButton.isEnabled = reference != null && prepared
                    microphoneText.setText(R.string.mic_idle)
                }
            }
        }
    }

    private fun showMatchResult(match: RecordingMatcher.Result?, observationNs: Long) {
        if (match == null) {
            matchText.text = "照合保留：静音または十分な特徴がありません。"
            consensus.submit(null, observationNs / 1_000_000L)
            return
        }
        val quality = "一致度 %.2f / 次候補との差 %.2f"
            .format(Locale.US, match.score, match.margin)
        matchText.text = if (match.confident) {
            "候補：%s（%s）".format(
                Locale.JAPAN,
                formatTime(match.referencePositionMs.toInt()),
                quality
            )
        } else {
            "照合保留（候補 %s、%s）".format(
                Locale.JAPAN,
                formatTime(match.referencePositionMs.toInt()),
                quality
            )
        }

        val accepted = consensus.submit(match, observationNs / 1_000_000L)
        if (accepted != null) {
            alignPlayer(accepted, observationNs)
        }
    }

    private fun alignPlayer(accepted: MatchConsensus.Acceptance, observationNs: Long) {
        val active = player ?: return
        if (!prepared || !captureRunning || pendingAutoStart) return
        val nowMs = System.nanoTime() / 1_000_000L
        val target = PlaybackTiming.targetPositionMillis(
            referencePositionAtCaptureMs = accepted.observation.positionMs,
            captureClockMs = observationNs / 1_000_000L,
            nowClockMs = maxOf(nowMs, observationNs / 1_000_000L),
            outputLatencyMs = 0L, // Output hardware latency is not measured.
            userOffsetMs = correctionOffsetMs
        ).coerceIn(0L, (active.duration - 100L).coerceAtLeast(0L))

        try {
            val currentlyPlaying = active.isPlaying
            val positionError = target - active.currentPosition
            val nowElapsedMs = SystemClock.elapsedRealtime()
            if (!currentlyPlaying) {
                pendingAutoStart = true
                active.seekTo(target.toInt())
                lastCorrectionElapsedMs = nowElapsedMs
                matchText.append("\n自動再生を開始：%s".format(
                    Locale.JAPAN, formatTime(target.toInt())
                ))
            } else if (
                abs(positionError) >= 220L &&
                nowElapsedMs - lastCorrectionElapsedMs >= 1_500L
            ) {
                active.seekTo(target.toInt())
                lastCorrectionElapsedMs = nowElapsedMs
                matchText.append("\n再生位置を補正：%+d ms".format(Locale.JAPAN, positionError))
            } else {
                matchText.append("\n同期追跡中（推定ずれ %+d ms）".format(
                    Locale.JAPAN, positionError
                ))
            }
        } catch (_: IllegalStateException) {
            matchText.append("\n再生位置の制御に失敗しました。")
            pendingAutoStart = false
        }
    }

    private fun stopMicrophone() {
        captureRunning = false
        pendingAutoStart = false
        if (microphoneRecorder != null) {
            microphoneButton.isEnabled = false
        }
        try {
            microphoneRecorder?.stop()
        } catch (_: Exception) {
            // A pending read can also stop as part of the capture thread cleanup.
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }

    override fun onStop() {
        stopMicrophone()
        pendingAutoStart = false
        try {
            if (player?.isPlaying == true) {
                player?.pause()
                playButton.setText(R.string.play_audio)
            }
        } catch (_: IllegalStateException) { }
        super.onStop()
    }

    override fun onDestroy() {
        indexGeneration++
        uiHandler.removeCallbacks(progressUpdate)
        stopMicrophone()
        prepared = false
        player?.release()
        player = null
        super.onDestroy()
    }
}
