package io.github.ryosphysrockme.audiosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
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
import kotlin.math.log10
import kotlin.math.roundToInt
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
    companion object {
        private const val SELECTED_AUDIO_URI_STATE = "selected_audio_uri"
        private const val SETTINGS_NAME = "synchronization_preferences"
        private const val SIMILARITY_THRESHOLD_KEY = "minimum_similarity_percent"
        private const val HEADPHONE_OFFSET_KEY = "initial_offset_ms"
    }

    private var selectedAudioUri: Uri? = null
    private var player: LocalPlayback? = null
    private var prepared = false
    private var reference: List<SpectralFeatures.Frame>? = null

    @Volatile
    private var indexGeneration = 0
    @Volatile
    private var captureRunning = false

    private var microphoneRecorder: AudioRecord? = null
    private val consensus = MatchConsensus()
    private val initialAlignment = InitialAlignmentPolicy()
    private val timelineErrors = TimelineErrorHistory()

    // Read by the microphone worker after each chunk; written on the UI thread.
    @Volatile
    private var similarityThreshold = RecordingMatcher.DEFAULT_SIMILARITY_THRESHOLD

    private var correctionOffsetMs = 0L
    private var fadeInGeneration = 0L
    private var pendingAutoStart = false
    private var lastPlaybackEvent = "待機中"

    private lateinit var fileText: TextView
    private lateinit var headerText: TextView
    private lateinit var playbackText: TextView
    private lateinit var microphoneText: TextView
    private lateinit var matchText: TextView
    private lateinit var timelineText: TextView
    private lateinit var playerStatusText: TextView
    private lateinit var offsetText: TextView
    private lateinit var similarityText: TextView
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
                    updatePlayerStatus()
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
        val settings = getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
        similarityThreshold = settings.getInt(SIMILARITY_THRESHOLD_KEY, 72)
            .coerceIn(50, 95) / 100.0
        correctionOffsetMs = settings.getLong(HEADPHONE_OFFSET_KEY, 0L)
            .coerceIn(-600L, 600L)
        buildScreen()
        uiHandler.post(progressUpdate)

        // Preserve the user's file selection if Android recreates the Activity
        // for a configuration change we do not explicitly handle.
        savedInstanceState?.getString(SELECTED_AUDIO_URI_STATE)?.let { encoded ->
            loadAudioFile(Uri.parse(encoded))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedAudioUri?.let { outState.putString(SELECTED_AUDIO_URI_STATE, it.toString()) }
        super.onSaveInstanceState(outState)
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

        headerText = TextView(this).apply {
            text = "音声形式：未選択"
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        container.addView(headerText, fullWidth())

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

        timelineText = TextView(this).apply {
            text = "推定時刻差：初回同期後に表示（再生中は自動シークしません）"
            textSize = 15f
            setPadding(0, 0, 0, 14)
        }
        container.addView(timelineText, fullWidth())

        playerStatusText = TextView(this).apply {
            text = "プレーヤー：停止中"
            textSize = 15f
            setPadding(0, 0, 0, 18)
        }
        container.addView(playerStatusText, fullWidth())

        similarityText = TextView(this).apply {
            text = similarityLabel(similarityThreshold)
            textSize = 15f
        }
        container.addView(similarityText, fullWidth())

        container.addView(SeekBar(this).apply {
            max = 45
            progress = (similarityThreshold * 100.0).roundToInt() - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    similarityThreshold = (50 + progress) / 100.0
                    similarityText.text = similarityLabel(similarityThreshold)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE).edit()
                        .putInt(SIMILARITY_THRESHOLD_KEY, (similarityThreshold * 100.0).roundToInt())
                        .apply()
                }
            })
        }, fullWidth())

        offsetText = TextView(this).apply {
            text = offsetLabel(correctionOffsetMs)
            textSize = 15f
        }
        container.addView(offsetText, fullWidth())

        container.addView(SeekBar(this).apply {
            max = 120
            progress = 60 + (correctionOffsetMs / 10L).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        correctionOffsetMs = (progress - 60) * 10L
                        offsetText.text = offsetLabel(correctionOffsetMs)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE).edit()
                        .putLong(HEADPHONE_OFFSET_KEY, correctionOffsetMs).apply()
                }
            })
        }, fullWidth())

        setContentView(ScrollView(this).apply { addView(container) })
    }

    private fun fullWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun updatePlayerStatus() {
        val state = try {
            when {
                player == null || !prepared -> "準備中"
                pendingAutoStart -> "シーク完了待ち"
                player?.isPlaying == true -> "再生中"
                else -> "停止中"
            }
        } catch (_: IllegalStateException) {
            "状態取得に失敗"
        }
        playerStatusText.text = "プレーヤー：$state\n$lastPlaybackEvent"
    }

    private fun similarityLabel(threshold: Double): String =
        ("再生開始の一致度閾値：%.2f（低いほど開始しやすい）\n" +
            "他候補との差0.075以上・2回連続確認は固定")
                .format(Locale.JAPAN, threshold)

    private fun offsetLabel(offsetMs: Long): String =
        "次の初回同期位置の補正：%+d ms（＋はスマホ側を先に進める）"
            .format(Locale.JAPAN, offsetMs)

    private fun loadAudioFile(uri: Uri) {
        selectedAudioUri = uri
        stopMicrophone()
        cancelFadeIn()
        val token = ++indexGeneration
        reference = null
        consensus.reset()
        initialAlignment.reset()
        timelineErrors.reset()
        timelineText.text = "推定時刻差：初回同期後に表示（再生中は自動シークしません）"
        prepared = false
        pendingAutoStart = false
        lastPlaybackEvent = "新しいファイルを準備しています。"
        seekBar.isEnabled = false
        playButton.isEnabled = false
        microphoneButton.isEnabled = false
        playButton.setText(R.string.play_audio)
        player?.release()
        player = null

        fileText.text = displayName(uri)
        headerText.text = "音声形式：ヘッダーを調べています"
        Thread({
            val report = AudioHeaderInspector.inspect(applicationContext.contentResolver, uri)
            runOnUiThread {
                if (token == indexGeneration && !isFinishing && !isDestroyed) {
                    headerText.text = "音声形式：${report.description}" +
                        (report.warning?.let { "\n注意：$it" } ?: "")
                }
            }
        }, "audio-header-inspector").start()

        playbackText.setText(R.string.loading)
        matchText.text = "音源を解析中。数秒程度の音声特徴列を曲全体について計算します。"

        val next = LocalPlayback(this)
        try {
            next.setDataSource(uri)
            next.setOnPreparedListener {
                if (player === next && token == indexGeneration) {
                    prepared = true
                    lastPlaybackEvent = "ローカル音源を再生する準備ができました。"
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
                        startPlaybackWithFade(
                            next, automatic = true,
                            statusMessage = "初回同期完了。600 msでフェードインします（以後は自動シークしません）。"
                        )
                    } catch (error: IllegalStateException) {
                        lastPlaybackEvent = "再生開始に失敗：" +
                            (error.localizedMessage ?: "MediaPlayerの状態異常")
                        updatePlayerStatus()
                    }
                }
            }
            next.setOnCompletionListener {
                if (player === next) {
                    playButton.setText(R.string.play_audio)
                    lastPlaybackEvent = "曲の末尾まで再生しました。"
                    updatePlayerStatus()
                }
            }
            next.setOnErrorListener { _, what, extra ->
                if (player === next) {
                    lastPlaybackEvent = "Media3再生エラー（code=$what）：${next.errorDetail}"
                    pendingAutoStart = false
                    updatePlayerStatus()
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
        } catch (error: Exception) {
            next.release()
            player = null
            playbackText.setText(R.string.file_error)
            lastPlaybackEvent = "読み込み処理で例外が発生：${error.javaClass.simpleName} / " +
                (error.localizedMessage ?: "詳細なし")
            updatePlayerStatus()
        }

        // Reference features are computed independently of playback.
        Thread({
            try {
                val indexWarnings = mutableListOf<String>()
                val indexed = ReferenceDecoder.index(
                    context = applicationContext,
                    uri = uri,
                    cancelled = { token != indexGeneration },
                    onWarning = { indexWarnings.add(it) }
                )
                runOnUiThread {
                    if (token == indexGeneration && !isFinishing && !isDestroyed) {
                        reference = indexed
                        microphoneButton.isEnabled = prepared && microphoneRecorder == null
                        matchText.text = "解析完了（%dフレーム）。マイクを開始してください。"
                            .format(Locale.JAPAN, indexed.size) +
                            if (indexWarnings.isNotEmpty()) {
                                "\n注意：WAVヘッダーの長さとファイル末尾を確認してください。\n" +
                                    indexWarnings.joinToString("\n")
                            } else {
                                ""
                            }
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
                cancelFadeIn()
                active.pause()
                playButton.setText(R.string.play_audio)
                lastPlaybackEvent = "手動で一時停止しました。"
                updatePlayerStatus()
            } else {
                startPlaybackWithFade(
                    active, automatic = false, statusMessage = "手動再生：600 msでフェードイン"
                )
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
            initialAlignment.reset()
            timelineErrors.reset()
            timelineText.text = "推定時刻差：外部音源の認識と初回同期を待っています。"
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
                        val selectedThreshold = similarityThreshold
                        val estimate = matcher.match(
                            recent.toList(), similarityThreshold = selectedThreshold
                        )
                        runOnUiThread {
                            if (captureRunning && microphoneRecorder === capture) {
                                showMatchResult(estimate, observationNs, selectedThreshold)
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

    private fun showMatchResult(
        match: RecordingMatcher.Result?,
        observationNs: Long,
        selectedThreshold: Double
    ) {
        val evaluation = consensus.evaluate(
            match, observationNs / 1_000_000L, similarityThreshold = selectedThreshold
        )
        if (match == null) {
            matchText.text = "照合保留：静音、または照合に使える音響特徴が不足しています。"
            return
        }
        val quality = "一致度 %.3f（設定%.2f以上）／他候補との差 %.3f（0.075以上）"
            .format(Locale.US, match.score, selectedThreshold, match.margin)
        val reason = when (evaluation.status) {
            MatchConsensus.Status.NO_MATCH -> "音源が認識されていません"
            MatchConsensus.Status.LOW_SIMILARITY -> "再生保留：一致度が不足しています"
            MatchConsensus.Status.AMBIGUOUS -> "再生保留：似た候補が複数あります"
            MatchConsensus.Status.WAITING_FOR_CONFIRMATION -> "再生待機：2回目の位置確認が必要です"
            MatchConsensus.Status.CANDIDATE_CHANGED -> "再生保留：候補時刻の進み方が一致しません"
            MatchConsensus.Status.ACCEPTED -> "位置を確認しました"
            MatchConsensus.Status.TRACKING -> "位置を追跡中（自動シークは初回だけ）"
        }
        matchText.text = "推定位置 %s\n%s\n%s".format(
            Locale.JAPAN, formatTime(match.referencePositionMs.toInt()), quality, reason
        )
        val accepted = evaluation.acceptance
        if (accepted != null) {
            alignPlayer(accepted, observationNs)
        }
    }

    private fun alignPlayer(accepted: MatchConsensus.Acceptance, observationNs: Long) {
        val active = player ?: return
        if (!prepared || !captureRunning) return
        val nowMs = System.nanoTime() / 1_000_000L
        val capturedMs = observationNs / 1_000_000L
        val target = PlaybackTiming.targetPositionMillis(
            referencePositionAtCaptureMs = accepted.observation.positionMs,
            captureClockMs = capturedMs,
            nowClockMs = maxOf(nowMs, capturedMs),
            outputLatencyMs = 0L, // Not a measured headset or output-device latency.
            userOffsetMs = correctionOffsetMs
        ).coerceIn(0L, (active.duration - 100L).coerceAtLeast(0L))

        try {
            // A listening session makes ONE automatic seek. All later accepted
            // estimates are diagnostics only, not seek or speed commands.
            if (initialAlignment.tryRequestInitialSeek()) {
                pendingAutoStart = true
                lastPlaybackEvent = "初回同期位置 %s に移動しています。"
                    .format(Locale.JAPAN, formatTime(target.toInt()))
                timelineText.text = "初回のシーク中。再生開始後の推定時刻差を観測します。"
                active.seekTo(target.toInt())
                updatePlayerStatus()

                // Some MediaPlayer implementations deliver completion late.
                // This is a one-time start fallback, NOT another seek.
                uiHandler.postDelayed({
                    if (player === active && prepared && captureRunning && pendingAutoStart) {
                        pendingAutoStart = false
                        try {
                            startPlaybackWithFade(
                                active, automatic = true,
                                statusMessage = "シーク通知が遅れたため再生開始。600 msでフェードインします。"
                            )
                        } catch (error: IllegalStateException) {
                            lastPlaybackEvent = "初回の自動再生に失敗：" +
                                (error.localizedMessage ?: "MediaPlayerの状態異常") +
                                "。マイクを停止・開始して再試行してください。"
                        }
                        updatePlayerStatus()
                    }
                }, 1200L)
                return
            }

            // Continue acoustic tracking, but do not attempt to control playback.
            if (initialAlignment.phase == InitialAlignmentPolicy.Phase.PLAYING &&
                active.isPlaying) {
                val differenceMs = target - active.currentPosition.toLong()
                val summary = timelineErrors.add(differenceMs)
                timelineText.text = (
                    "推定時刻差（外部−スマホ）：%+d ms（直近中央値）\n" +
                        "追跡開始時：%+d ms、そこからの変化：%+d ms（%d回）\n" +
                        "※実際に耳に届く音の差ではありません。自動シークなし。"
                    ).format(
                        Locale.JAPAN,
                        summary.currentMedianMs,
                        summary.firstMs,
                        summary.changeMs,
                        summary.samples
                    )
            } else if (pendingAutoStart) {
                timelineText.text = "初回のシーク完了を待機中（再シークはしません）。"
            }
        } catch (error: IllegalStateException) {
            lastPlaybackEvent = "再生位置の制御に失敗：" +
                (error.localizedMessage ?: "MediaPlayerの状態異常")
            matchText.append("\n再生位置の制御に失敗しました。")
            pendingAutoStart = false
            updatePlayerStatus()
        }
    }

    /**
     * Fades in the chosen audio output without ever changing the media
     * position or the external recording's playback state.
     */
    private fun startPlaybackWithFade(
        active: LocalPlayback,
        automatic: Boolean,
        statusMessage: String
    ) {
        val generation = ++fadeInGeneration
        active.setVolume(0f)
        try {
            if (!active.isPlaying) active.start()
        } catch (error: IllegalStateException) {
            active.setVolume(1f)
            throw error
        }
        if (automatic) {
            initialAlignment.onPlayerStarted()
            timelineErrors.reset()
        }
        playButton.setText(R.string.pause_audio)
        lastPlaybackEvent = statusMessage
        updatePlayerStatus()

        val requestedAtMs = SystemClock.uptimeMillis()
        val tick = object : Runnable {
            var actualPlaybackStartMs = -1L
            override fun run() {
                if (player !== active || generation != fadeInGeneration) return
                try {
                    val nowMs = SystemClock.uptimeMillis()
                    if (!active.isPlaying) {
                        if (nowMs - requestedAtMs < 5_000L) {
                            uiHandler.postDelayed(this, 40L)
                        } else {
                            // Never leave the player accidentally muted.
                            active.setVolume(1f)
                        }
                        return
                    }
                    if (actualPlaybackStartMs < 0L) actualPlaybackStartMs = nowMs
                    val elapsed = nowMs - actualPlaybackStartMs
                    active.setVolume(FadeInEnvelope.gain(elapsed))
                    if (elapsed < FadeInEnvelope.DURATION_MS) {
                        uiHandler.postDelayed(this, 20L)
                    }
                } catch (_: IllegalStateException) {
                    // The player may have been released by an Activity change.
                }
            }
        }
        uiHandler.post(tick)
    }

    private fun cancelFadeIn() {
        fadeInGeneration++
        try {
            player?.setVolume(1f)
        } catch (_: IllegalStateException) {
            // Safe if already released.
        }
    }

    private fun stopMicrophone() {
        cancelFadeIn()
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
