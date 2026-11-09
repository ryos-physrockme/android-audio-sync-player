package io.github.ryosphysrockme.audiosync

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private var lastSyncButtonActive: Boolean? = null
    private var lastSyncButtonEnabled: Boolean? = null
    private val manualNudge = ManualNudge()
    private var lastNudgeTickMs = 0L
    private var wasPlayingDuringPreviousTick = false
    private var appliedNudgeSpeed = 1f
    private var nudgeTickScheduled = false

    private lateinit var fileText: TextView
    private lateinit var fileMetaText: TextView
    private lateinit var syncStateText: TextView
    private lateinit var recognitionStateText: TextView
    private lateinit var recognitionCandidateText: TextView
    private lateinit var playbackEndText: TextView
    private lateinit var fileSelectButton: Button
    private lateinit var similarityBar: SeekBar
    private lateinit var offsetBar: SeekBar
    private lateinit var nudgeRow: LinearLayout
    private lateinit var headerText: TextView
    private lateinit var playbackText: TextView
    private lateinit var microphoneText: TextView
    private lateinit var matchText: TextView
    private lateinit var timelineText: TextView
    private lateinit var playerStatusText: TextView
    private lateinit var offsetText: TextView
    private lateinit var similarityText: TextView
    private lateinit var playButton: Button
    private lateinit var nudgeBackButton: Button
    private lateinit var nudgeBackHundredButton: Button
    private lateinit var nudgeForwardButton: Button
    private lateinit var nudgeForwardHundredButton: Button
    private lateinit var nudgeStatusText: TextView
    private lateinit var microphoneButton: Button
    private lateinit var seekBar: SeekBar

    private val uiHandler = Handler(Looper.getMainLooper())
    private val nudgeUpdate = object : Runnable {
        override fun run() {
            nudgeTickScheduled = false
            val active = player ?: return
            if (!prepared || !active.playWhenReady) {
                resetManualNudge()
                return
            }
            try {
                updateManualNudge(active)
                if (manualNudge.active) scheduleNudgeUpdate()
            } catch (error: Exception) {
                resetManualNudge()
                nudgeStatusText.text = "速度補正に失敗：" +
                    (error.localizedMessage ?: error.javaClass.simpleName)
            }
        }
    }

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
                    playbackText.text = formatTime(active.currentPosition)
                    playbackEndText.text = formatTime(active.duration)
                    val canNudge = active.isPlaying && !pendingAutoStart
                    nudgeBackButton.isEnabled = canNudge
                    nudgeBackHundredButton.isEnabled = canNudge
                    nudgeForwardButton.isEnabled = canNudge
                    nudgeForwardHundredButton.isEnabled = canNudge
                    updatePlayerStatus()
                } catch (_: IllegalStateException) {
                    // Player can be released when a new reference is chosen.
                }
            }
            updateSyncState()
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

        fileSelectButton = Button(this).apply {
            setText(R.string.choose_audio)
            setOnClickListener { chooseAudioFile.launch(arrayOf("audio/*")) }
        }
        container.addView(fileSelectButton, fullWidth())

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
                    resetManualNudge()
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

        nudgeStatusText = TextView(this).apply {
            text = "再生中の時間補正：左＝遅らせる、右＝進める（現在1.00倍）"
            textSize = 14f
            setPadding(0, 12, 0, 6)
        }
        container.addView(nudgeStatusText, fullWidth())

        // Four equally weighted buttons, with the 100 ms controls outside
        // the original 50 ms controls. Short labels fit narrow phones.
        nudgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        nudgeBackHundredButton = Button(this).apply {
            text = "−100 ms"
            isEnabled = false
            setOnClickListener { nudgePlayback(-ManualNudge.LARGE_STEP_MS) }
        }
        nudgeBackButton = Button(this).apply {
            text = "−50 ms"
            isEnabled = false
            setOnClickListener { nudgePlayback(-ManualNudge.STEP_MS) }
        }
        nudgeForwardButton = Button(this).apply {
            text = "＋50 ms"
            isEnabled = false
            setOnClickListener { nudgePlayback(ManualNudge.STEP_MS) }
        }
        nudgeForwardHundredButton = Button(this).apply {
            text = "＋100 ms"
            isEnabled = false
            setOnClickListener { nudgePlayback(ManualNudge.LARGE_STEP_MS) }
        }
        for (button in listOf(
            nudgeBackHundredButton, nudgeBackButton,
            nudgeForwardButton, nudgeForwardHundredButton
        )) {
            // Avoid Android's default Button minimum width, which would make
            // four controls overflow narrow portrait screens.
            button.minWidth = 0
            button.minimumWidth = 0
            button.setPadding(0, button.paddingTop, 0, button.paddingBottom)
            button.textSize = 12f
            button.isAllCaps = false
            nudgeRow.addView(
                button, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
        }
        container.addView(nudgeRow, fullWidth())

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

        similarityBar = SeekBar(this).apply {
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
        }
        container.addView(similarityBar, fullWidth())

        offsetText = TextView(this).apply {
            text = offsetLabel(correctionOffsetMs)
            textSize = 15f
        }
        container.addView(offsetText, fullWidth())

        offsetBar = SeekBar(this).apply {
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
        }
        container.addView(offsetBar, fullWidth())

        setContentView(ScrollView(this).apply { addView(container) })
        installCompactScreen()
    }

    /**
     * Reuses the original controls and listeners, but moves them into a
     * sync-first dark layout. The player remains at the bottom, while the
     * source and sync card scroll on smaller / rotated displays.
     *
     * All diagnostic TextViews remain live, but are collapsed by default.
     */
    private fun installCompactScreen() {
        fun dip(value: Int): Int = UiDesign.dp(this, value)
        fun detach(view: View) {
            (view.parent as? ViewGroup)?.removeView(view)
        }
        fun styleText(view: TextView, size: Float, color: Int, medium: Boolean = false) {
            view.textSize = size
            view.setTextColor(color)
            view.includeFontPadding = false
            if (medium) view.typeface =
                android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        fun rowParams(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiDesign.BACKGROUND)
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(18), dip(18), dip(18), dip(16))
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            addView(main)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Header: no redundant section names.
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            contentDescription = null
        }, LinearLayout.LayoutParams(dip(31), dip(31)))
        toolbar.addView(
            UiDesign.text(this, "音声同期プレーヤー", 18f, medium = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dip(7)
            }
        )
        main.addView(toolbar, rowParams())

        // File card: filename + current load state + a compact change action.
        val fileCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiDesign.rounded(this@MainActivity, UiDesign.SURFACE, 11)
            setPadding(dip(13), dip(11), dip(11), dip(11))
        }
        val fileContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        detach(fileText)
        styleText(fileText, 15f, UiDesign.FOREGROUND, medium = true)
        fileText.maxLines = 1
        fileText.ellipsize = TextUtils.TruncateAt.END
        fileContent.addView(fileText, rowParams())

        fileMetaText = UiDesign.text(this, "ファイル未選択", 12f, UiDesign.SECONDARY).apply {
            setPadding(0, dip(5), 0, 0)
        }
        fileContent.addView(fileMetaText, rowParams())
        fileCard.addView(fileContent, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dip(8) })

        detach(fileSelectButton)
        fileSelectButton.text = "変更"
        UiDesign.button(
            fileSelectButton,
            UiDesign.rounded(this, UiDesign.SURFACE_STRONG, 10),
            UiDesign.FOREGROUND,
            13f
        )
        fileCard.addView(fileSelectButton, LinearLayout.LayoutParams(dip(70), dip(43)))
        main.addView(fileCard, rowParams().apply { topMargin = dip(17) })

        // The large sync button is the only primary action.
        val syncCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dip(16), 0, dip(4))
        }
        syncStateText = UiDesign.text(
            this, "音源を選択してください", 17f, UiDesign.SECONDARY, medium = true
        ).apply { gravity = Gravity.START }
        syncCard.addView(syncStateText, rowParams())

        detach(microphoneButton)
        UiDesign.button(
            microphoneButton,
            UiDesign.primary(this),
            UiDesign.BACKGROUND,
            18f
        )
        syncCard.addView(microphoneButton, rowParams(dip(56)).apply {
            topMargin = dip(13)
        })

        // Two compact lines provide feedback even when diagnostics are hidden.
        recognitionStateText = UiDesign.text(
            this, "外部音声の認識待ち", 14f, UiDesign.SECONDARY, medium = true
        )
        recognitionStateText.visibility = View.GONE
        syncCard.addView(recognitionStateText, rowParams().apply {
            topMargin = dip(12)
        })
        recognitionCandidateText = UiDesign.text(
            this, "", 12f, UiDesign.SECONDARY
        )
        recognitionCandidateText.visibility = View.GONE
        syncCard.addView(recognitionCandidateText, rowParams().apply {
            topMargin = dip(3)
        })
        main.addView(syncCard, rowParams().apply { topMargin = dip(17) })

        // The offset for the NEXT automatic lock is a regular visible setting.
        // It does not affect the current playback position.
        val offsetSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dip(9), 0, dip(2))
        }
        detach(offsetText)
        styleText(offsetText, 14f, UiDesign.FOREGROUND, medium = true)
        offsetSection.addView(offsetText, rowParams())
        detach(offsetBar)
        offsetBar.progressTintList = ColorStateList.valueOf(UiDesign.PRIMARY)
        offsetBar.thumbTintList = ColorStateList.valueOf(UiDesign.PRIMARY)
        offsetSection.addView(offsetBar, rowParams().apply { topMargin = dip(4) })
        main.addView(offsetSection, rowParams().apply { topMargin = dip(13) })

        // Keep parameters and diagnostics available without consuming the
        // main player's space during routine listening.
        val detailPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = UiDesign.rounded(this@MainActivity, UiDesign.SURFACE, 10)
            setPadding(dip(14), dip(14), dip(14), dip(17))
        }
        val detailsToggle = Button(this).apply {
            text = "設定・診断  ▾"
            UiDesign.button(
                this, UiDesign.rounded(this@MainActivity, UiDesign.SURFACE, 8),
                UiDesign.SECONDARY, 14f
            )
            setOnClickListener {
                val expanded = detailPanel.visibility != View.VISIBLE
                detailPanel.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "設定・診断  ▴" else "設定・診断  ▾"
            }
        }
        main.addView(detailsToggle, rowParams(dip(44)).apply { topMargin = dip(16) })

        detach(similarityText)
        styleText(similarityText, 14f, UiDesign.FOREGROUND, medium = true)
        detailPanel.addView(similarityText, rowParams())
        detach(similarityBar)
        similarityBar.progressTintList = ColorStateList.valueOf(UiDesign.PRIMARY)
        similarityBar.thumbTintList = ColorStateList.valueOf(UiDesign.PRIMARY)
        detailPanel.addView(similarityBar, rowParams().apply { topMargin = dip(7) })

        detailPanel.addView(
            UiDesign.text(
                this, "次の同期開始時に適用（＋はスマホ側を進める）", 12f, UiDesign.SECONDARY
            ),
            rowParams().apply { topMargin = dip(15) }
        )

        val diagnosticSections = listOf(
            "音源" to headerText,
            "マイク" to microphoneText,
            "照合" to matchText,
            "時刻差" to timelineText,
            "再生状態" to playerStatusText
        )
        diagnosticSections.forEach { (heading, diagnostic) ->
            detailPanel.addView(
                UiDesign.text(this, heading, 13f, UiDesign.FOREGROUND, medium = true),
                rowParams().apply { topMargin = dip(18) }
            )
            detach(diagnostic)
            styleText(diagnostic, 12f, UiDesign.SECONDARY)
            detailPanel.addView(diagnostic, rowParams().apply { topMargin = dip(5) })
        }
        main.addView(detailPanel, rowParams().apply { topMargin = dip(7) })

        // The lower player is separate and remains within reach while the
        // user expands diagnostics above or rotates the device.
        val bottomShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiDesign.SURFACE)
            setPadding(dip(16), dip(10), dip(16), dip(12))
        }
        // Plain bottom surface, rather than another floating oversized card.
        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        detach(playbackText)
        playbackText.text = "00:00"
        styleText(playbackText, 12f, UiDesign.SECONDARY)
        playbackEndText = UiDesign.text(this, "00:00", 12f, UiDesign.SECONDARY).apply {
            gravity = Gravity.END
        }
        timeRow.addView(playbackText, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        timeRow.addView(playbackEndText, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        playerCard.addView(timeRow, rowParams())

        detach(seekBar)
        seekBar.progressTintList = ColorStateList.valueOf(UiDesign.PRIMARY)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(UiDesign.OUTLINE)
        seekBar.thumbTintList = ColorStateList.valueOf(UiDesign.PRIMARY)
        playerCard.addView(seekBar, rowParams().apply { topMargin = dip(2) })

        detach(nudgeRow)
        val adjustments = listOf(
            nudgeBackHundredButton, nudgeBackButton,
            nudgeForwardButton, nudgeForwardHundredButton
        )
        val buttonLabels = listOf("−100", "−50", "+50", "+100")
        adjustments.forEachIndexed { index, button ->
            button.text = buttonLabels[index]
            UiDesign.button(
                button,
                UiDesign.rounded(this, UiDesign.SURFACE_STRONG, 12),
                UiDesign.FOREGROUND,
                13f
            )
            button.contentDescription = when (index) {
                0 -> "100ミリ秒遅らせる"
                1 -> "50ミリ秒遅らせる"
                2 -> "50ミリ秒進める"
                else -> "100ミリ秒進める"
            }
            (button.layoutParams as? LinearLayout.LayoutParams)?.apply {
                height = dip(45)
                marginStart = if (index == 0) 0 else dip(6)
            }
        }
        playerCard.addView(nudgeRow, rowParams().apply { topMargin = dip(4) })

        detach(nudgeStatusText)
        styleText(nudgeStatusText, 15f, UiDesign.PRIMARY, medium = true)
        nudgeStatusText.text = "補正 +0 ms"
        nudgeStatusText.gravity = Gravity.CENTER
        nudgeStatusText.setPadding(0, dip(9), 0, dip(3))
        playerCard.addView(nudgeStatusText, rowParams())

        detach(playButton)
        UiDesign.button(
            playButton,
            UiDesign.rounded(this, UiDesign.SURFACE_STRONG, 12),
            UiDesign.FOREGROUND,
            14f
        )
        val playRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        playRow.addView(playButton, LinearLayout.LayoutParams(dip(136), dip(44)))
        playerCard.addView(playRow, rowParams().apply { topMargin = dip(9) })
        bottomShell.addView(playerCard, rowParams())
        root.addView(bottomShell, rowParams())

        // targetSdk 35+ draws underneath system bars on Android 15. Use the
        // actual window insets rather than guessing a fixed navigation height.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            windowInsets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        updateSyncState()
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
        "一致度閾値 %.2f".format(Locale.JAPAN, threshold)

    /**
     * Primary status is intentionally concise. Playback control and detailed
     * diagnostics are independent from microphone synchronization.
     */
    private fun updateSyncState() {
        if (!::syncStateText.isInitialized || !::microphoneButton.isInitialized) return
        val active = player
        val hasFailure = ::fileMetaText.isInitialized &&
            fileMetaText.text.toString().contains("失敗")
        val label = when {
            selectedAudioUri == null -> "音源を選択してください"
            hasFailure -> "読み込みに失敗"
            captureRunning && initialAlignment.phase == InitialAlignmentPolicy.Phase.PLAYING &&
                active?.playWhenReady == true -> "同期して再生中"
            captureRunning && pendingAutoStart -> "再生を準備中"
            captureRunning -> "外部音声を認識中"
            active?.playWhenReady == true -> "再生中"
            !prepared || reference == null -> "音源を準備中"
            else -> "同期待機中"
        }
        syncStateText.text = label
        syncStateText.setTextColor(when {
            hasFailure -> UiDesign.ERROR
            captureRunning && initialAlignment.phase == InitialAlignmentPolicy.Phase.PLAYING ->
                UiDesign.SUCCESS
            captureRunning -> UiDesign.PRIMARY
            else -> UiDesign.FOREGROUND
        })

        // The live result remains visible without opening the debug panel.
        if (::recognitionStateText.isInitialized) {
            val show = captureRunning || (prepared && reference != null)
            recognitionStateText.visibility = if (show) View.VISIBLE else View.GONE
            if (!captureRunning) {
                recognitionStateText.text = "外部音声の認識待ち"
                recognitionStateText.setTextColor(UiDesign.SECONDARY)
                recognitionCandidateText.visibility = View.GONE
            }
        }

        // A disabled primary action must not look like a bright active button.
        val enabled = microphoneButton.isEnabled
        if (lastSyncButtonActive != captureRunning || lastSyncButtonEnabled != enabled) {
            lastSyncButtonActive = captureRunning
            lastSyncButtonEnabled = enabled
            microphoneButton.setText(
                if (captureRunning) R.string.stop_microphone else R.string.start_microphone
            )
            UiDesign.button(
                microphoneButton,
                when {
                    !enabled -> UiDesign.rounded(this, UiDesign.SURFACE_RAISED, 13)
                    captureRunning -> UiDesign.rounded(this, UiDesign.SURFACE_STRONG, 13)
                    else -> UiDesign.primary(this, 13)
                },
                if (enabled && !captureRunning) UiDesign.BACKGROUND else if (enabled) {
                    UiDesign.FOREGROUND
                } else {
                    UiDesign.SECONDARY
                },
                if (captureRunning) 16f else 18f
            )
        }
    }

    private fun offsetLabel(offsetMs: Long): String =
        "同期オフセット %+d ms".format(Locale.JAPAN, offsetMs)

    private fun loadAudioFile(uri: Uri) {
        selectedAudioUri = uri
        stopMicrophone()
        cancelFadeIn()
        resetManualNudge()
        val token = ++indexGeneration
        reference = null
        consensus.reset()
        initialAlignment.reset()
        timelineErrors.reset()
        timelineText.text = "推定時刻差：初回同期後に表示（再生中は自動シークしません）"
        prepared = false
        pendingAutoStart = false
        nudgeBackButton.isEnabled = false
        nudgeBackHundredButton.isEnabled = false
        nudgeForwardButton.isEnabled = false
        nudgeForwardHundredButton.isEnabled = false
        lastPlaybackEvent = "新しいファイルを準備しています。"
        seekBar.isEnabled = false
        playButton.isEnabled = false
        microphoneButton.isEnabled = false
        playButton.setText(R.string.play_audio)
        player?.release()
        player = null

        fileText.text = displayName(uri)
        fileMetaText.text = "解析中"
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
                    resetManualNudge()
                    playButton.setText(R.string.play_audio)
                    lastPlaybackEvent = "曲の末尾まで再生しました。"
                    updatePlayerStatus()
                }
            }
            next.setOnErrorListener { _, what, extra ->
                if (player === next) {
                    resetManualNudge()
                    lastPlaybackEvent = "Media3再生エラー（code=$what）：${next.errorDetail}"
                    pendingAutoStart = false
                    updatePlayerStatus()
                    prepared = false
                    playButton.isEnabled = false
                    microphoneButton.isEnabled = false
                    seekBar.isEnabled = false
                    playbackText.setText(R.string.file_error)
                    fileMetaText.text = "再生の準備に失敗"
                }
                true
            }
            player = next
            next.prepareAsync()
        } catch (error: Exception) {
            next.release()
            player = null
            playbackText.setText(R.string.file_error)
            fileMetaText.text = "再生の準備に失敗"
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
                        fileMetaText.text = if (indexWarnings.isEmpty()) {
                            "解析完了"
                        } else {
                            "解析完了・警告あり"
                        }
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
                        fileMetaText.text = "解析に失敗"
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

    /**
     * Each button press schedules a time correction through pitch-preserving
     * playback-speed changes. No seekTo, pause or microphone restart.
     */
    private fun nudgePlayback(deltaMs: Long) {
        val active = player ?: return
        if (!prepared || pendingAutoStart || !active.isPlaying) {
            nudgeStatusText.text = "微調整は音声再生中に使用できます。"
            return
        }
        try {
            updateManualNudge(active)
            val accepted = manualNudge.request(deltaMs)
            if (accepted == 0L) {
                return
            }
            updateManualNudge(active)
            nudgeStatusText.text = "補正 %+d ms".format(
                Locale.JAPAN, manualNudge.requestedTotalMs
            )
            lastPlaybackEvent = "手動で時間差%+d msの速度補正を要求。音源はシークしません。"
                .format(Locale.JAPAN, accepted)
            updatePlayerStatus()
            scheduleNudgeUpdate()
        } catch (error: Exception) {
            resetManualNudge()
            nudgeStatusText.text = "速度変更に失敗：" +
                (error.localizedMessage ?: error.javaClass.simpleName)
        }
    }

    private fun updateManualNudge(active: LocalPlayback) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (lastNudgeTickMs == 0L) 0L
            else (now - lastNudgeTickMs).coerceAtLeast(0L)
        val speed = manualNudge.advance(elapsed, wasPlayingDuringPreviousTick)
        lastNudgeTickMs = now
        wasPlayingDuringPreviousTick = active.isPlaying
        if (kotlin.math.abs(speed - appliedNudgeSpeed) > 0.0001f) {
            active.setPlaybackSpeed(speed)
            appliedNudgeSpeed = speed
        }
    }

    private fun scheduleNudgeUpdate() {
        if (!nudgeTickScheduled && manualNudge.active) {
            nudgeTickScheduled = true
            uiHandler.postDelayed(nudgeUpdate, 40L)
        }
    }

    /** Return speed to 1.00x on pause, new file or Activity stop. */
    private fun resetManualNudge() {
        uiHandler.removeCallbacks(nudgeUpdate)
        nudgeTickScheduled = false
        lastNudgeTickMs = 0L
        wasPlayingDuringPreviousTick = false
        appliedNudgeSpeed = 1f
        manualNudge.reset()
        try {
            player?.setPlaybackSpeed(1f)
        } catch (_: Exception) {
            // Player may already be released.
        }
        if (::nudgeStatusText.isInitialized) {
            nudgeStatusText.text = "補正 +0 ms"
        }
    }

    private fun togglePlayback() {
        if (!prepared) return
        stopMicrophone()
        pendingAutoStart = false
        try {
            val active = player ?: return
            if (active.playWhenReady) {
                resetManualNudge()
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
            resetManualNudge()
            timelineText.text = "推定時刻差：外部音源の認識と初回同期を待っています。"
            pendingAutoStart = false
            microphoneButton.setText(R.string.stop_microphone)
            matchText.text = "外部音声を約3秒取得してから照合を始めます。"
            recognitionStateText.text = "外部音声を待っています"
            recognitionStateText.setTextColor(UiDesign.PRIMARY)
            recognitionStateText.visibility = View.VISIBLE
            recognitionCandidateText.visibility = View.GONE
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
            recognitionStateText.text = "音源を探索中"
            recognitionStateText.setTextColor(UiDesign.SECONDARY)
            recognitionCandidateText.visibility = View.GONE
            matchText.text = "照合保留：静音、または照合に使える音響特徴が不足しています。"
            return
        }

        // The current candidate and the decision are visible even when the
        // diagnostics section is collapsed. A candidate is not a confirmed lock.
        recognitionStateText.text = MatchPresentation.status(evaluation.status)
        recognitionStateText.setTextColor(when (evaluation.status) {
            MatchConsensus.Status.ACCEPTED,
            MatchConsensus.Status.TRACKING -> UiDesign.SUCCESS
            MatchConsensus.Status.WAITING_FOR_CONFIRMATION -> UiDesign.PRIMARY
            else -> UiDesign.SECONDARY
        })
        recognitionCandidateText.text = MatchPresentation.candidate(
            match.referencePositionMs, match.score
        )
        recognitionCandidateText.visibility = View.VISIBLE

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
        evaluation.acceptance?.let { alignPlayer(it, observationNs) }
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
        resetManualNudge()
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
        resetManualNudge()
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
        resetManualNudge()
        prepared = false
        player?.release()
        player = null
        super.onDestroy()
    }
}
