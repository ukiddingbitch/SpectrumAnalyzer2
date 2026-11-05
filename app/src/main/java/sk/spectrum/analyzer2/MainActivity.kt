package sk.spectrum.analyzer2

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Activity lifecycle, shared screen state and initial UI wiring. Feature implementations live in the sibling files. */
class MainActivity : Activity() {

    // Spectrum header and shared view references
    internal lateinit var spectrumView: SpectrumView
    internal lateinit var statusText: TextView
    internal lateinit var peakText: TextView
    internal lateinit var startButton: Button
    internal var headerPeakFrequencyText: TextView? = null
    internal var headerPeakDbText: TextView? = null
    internal var headerPeakReadout: LinearLayout? = null
    internal var headerSplText: TextView? = null
    // Audio route UI and selected devices
    internal var audioRoutePickerButton: android.widget.ImageButton? = null
    internal var audioRouteHeaderGroup: LinearLayout? = null
    internal var audioRouteDialog: Dialog? = null
    internal var audioRouteDialogRefresh: (() -> Unit)? = null
    internal var preferredInputDeviceId: Int? = null
    internal var preferredOutputDeviceId: Int? = null
    // Generic fallback for phones whose audio HAL does not publish microphone sensitivity.
    // Treats a typical microphone output near -24 dBFS at 94 dB SPL as an estimate only.
    // SPL calibration and reference levels (existing behavior)
    internal val genericSplEstimateOffsetDb = 118.0
    internal var splCalibrationOffsetDb: Double? = null
    internal var splSystemCalibrationOffsetDb: Double? = null
    internal var splReferenceLabel: TextView? = null
    internal var splReferenceLevelA: Double? = null

    internal val splReferences = listOf(
        SplReference(0, "Hearing threshold"),
        SplReference(20, "Quiet study"),
        SplReference(40, "Whisper · ~1.5 m"),
        SplReference(50, "Residential surroundings"),
        SplReference(60, "Conversation · ~0.9 m"),
        SplReference(70, "Classroom voices"),
        SplReference(80, "Freight train · ~30 m"),
        SplReference(90, "Industrial boiler room"),
        SplReference(100, "Building site"),
        SplReference(110, "Amplified club music"),
        SplReference(120, "Heavy machinery"),
        SplReference(130, "Aircraft takeoff · ~60 m"),
        SplReference(140, "Pain threshold")
    )

    internal var splInfoClickBlockedUntilMs = 0L
    internal var splDetailDialog: Dialog? = null
    internal var splMeterPanel: SplMeterPanel? = null
    internal var splFrequencyWeighting = SplWeighting.A
    internal val splTrendBuffer = SplTrendBuffer()
    @Volatile internal var splMeterSessionGeneration = 0L
    @Volatile internal var splSessionSnapshot: SplSessionSnapshot? = null
    internal val splWaveHistory = SplWaveHistory()
    internal var lastCardTouch: android.graphics.PointF? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        rememberCardTouch(event)
        if (splDetailDialog?.isShowing == true && interceptSplToolbarTouch(event)) return true
        return super.dispatchTouchEvent(event)
    }

    // SPL card presentation and live readings
    internal var splDetailValueView: TextView? = null
    internal var splDetailDeltaView: TextView? = null
    internal var splScaleMeterView: SplScaleMeterView? = null
    internal var splArcMeterView: SplArcMeterView? = null
    internal var splUseArcScale = false
    internal var pendingSplToolbarClick: View? = null
    internal var splResponseButton: Button? = null
    internal var splHoldButton: Button? = null
    internal var splMinButton: Button? = null
    internal var splMaxButton: Button? = null
    internal var splTimeWeighting = "FAST"
    internal var splHoldEnabled = false
    internal var splHeldValueDb: Double? = null
    internal var splMinimumTrackingEnabled = false
    internal var splMaximumTrackingEnabled = false
    internal var splDisplayedExtremumMode: String? = null
    internal var splMinimumDb: Double? = null
    internal var splMaximumDb: Double? = null
    internal var splLastResponseValueDb: Double? = null
    @Volatile internal var latestSplFastADbfs = -120.0
    @Volatile internal var latestSplSlowADbfs = -120.0
    internal var splHasCurrentSample = false
    internal val splRecentReadings = java.util.ArrayDeque<Pair<Long, Double>>()
    internal var splSlowDisplayValueDb: Double? = null
    internal var splSlowDisplayUpdatedAt = 0L
    // Analyzer mode layout and transition state
    internal var spectrumHeader: android.widget.FrameLayout? = null
    internal var rangePickerButton: Button? = null
    internal var keyboardControlsRow: LinearLayout? = null
    internal var headerTitleText: TextView? = null
    internal var splitContainer: LinearLayout? = null
    internal var bottomActionBar: android.widget.FrameLayout? = null
    internal var keyboardModeButtonGroup: LinearLayout? = null
    internal var mainActionsSpacer: View? = null
    internal var tonePanel: View? = null
    internal var toneExpanded = false
    internal var toneSplTextSizePx: Float? = null
    internal var toneExpandFraction = 0f
    internal var toneExpansionAnimator: android.animation.ValueAnimator? = null
    internal var toneExpandButton: android.widget.ImageButton? = null
    internal var toggleToneExpanded: (() -> Unit)? = null
    internal var configureExpandedToneControls: ((Boolean) -> Unit)? = null
    internal val toneFixedControlRows = mutableListOf<View>()
    internal val toneOriginalRowLayouts = mutableMapOf<View, Pair<Int, Int>>()

    internal var keyboardPanel: View? = null
    internal var keysClosingVisual: android.widget.ImageView? = null
    internal var keysClosingVisualRoot: ViewGroup? = null

    internal var keyboardToggleButton: Button? = null
    internal var fullScaleKeysButton: Button? = null
    internal var fullScaleKeysDialog: Dialog? = null
    internal var keyboardSectionView: View? = null
    internal var fullScaleKeyboardOpen = false
    internal var fullScaleTransitionAnimator: android.animation.ValueAnimator? = null
    internal var portraitGraphControlsRow: LinearLayout? = null
    internal var resetDisplayButton: AnalyzerIconButton? = null
    internal var spectrogramModeButton: AnalyzerIconButton? = null
    internal var soundIdPanel: View? = null
    internal var soundModeOpen = false
    internal var switchAnalyzerMode: ((String) -> Unit)? = null
    internal var switchKeysToolbarLayout: ((Boolean) -> Unit)? = null
    internal var toneModeOpen = false
    internal var keyboardModeOpen = false
    internal var adaptiveWindowLayoutUpdate: (() -> Unit)? = null
    @Volatile internal var modeTransitionInProgress = false
    internal var toneToggleButton: Button? = null
    internal var axisToggleButton: Button? = null
    internal var mainActionRow: LinearLayout? = null
    // Sound identification and personal sound session state
    @Volatile internal var soundIdActive = false
    internal var soundIdBestSoundView: TextView? = null
    internal var soundIdScoreView: TextView? = null
    internal var soundIdAlternativesView: TextView? = null
    internal var soundIdDurationView: TextView? = null
    internal var soundIdToggleButton: Button? = null
    internal var soundIdModeButton: Button? = null
    internal var soundIdLivePanel: LinearLayout? = null
    internal var soundIdSummaryPanel: LinearLayout? = null
    internal var soundIdSummaryChart: SoundSessionChartView? = null
    internal var soundIdSummaryTimeline: SoundSessionTimelineView? = null
    internal var soundIdPdfButton: Button? = null
    internal var soundIdLastSummary: SoundSessionSummary? = null
    internal var personalSoundListRefresh: (() -> Unit)? = null
    internal var pendingSoundIdPdf: ByteArray? = null
    internal val personalSoundExportInProgress = AtomicBoolean(false)
    internal val personalSoundImportInProgress = AtomicBoolean(false)
    @Volatile internal var personalPreviewTrack: AudioTrack? = null
    internal var personalPreviewKey: String? = null
    internal var personalPreviewButton: Button? = null
    internal var personalPreviewPaused = false
    internal var soundIdListeningStartedAt = 0L
    internal val soundIdTimerHandler = Handler(Looper.getMainLooper())
    internal val soundIdTimerRunnable = object : Runnable {
        override fun run() {
            if (soundIdActive) {
                val seconds = ((SystemClock.elapsedRealtime() - soundIdListeningStartedAt) / 1000L).coerceAtLeast(0L)
                soundIdDurationView?.text = "Listening · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                soundIdTimerHandler.postDelayed(this, 1000L)
            }
        }
    }
    internal val soundIdEngine by lazy {
        SoundIdEngine(applicationContext) { result ->
            runOnUiThread {
                soundIdBestSoundView?.text = result.bestSound
                soundIdScoreView?.text = if (result.modelScorePercent > 0) {
                    "${if (result.isPersonalMatch) "Saved sample similarity" else "Match strength"} · ${result.modelScorePercent}%"
                } else ""
                soundIdAlternativesView?.text = result.alternatives
                result.sessionSummary?.let(this@MainActivity::showSoundIdSummary)
            }
        }
    }
    internal val soundIdExportRequestCode = 72
    internal val personalSoundExportFolderRequestCode = 73
    internal val personalSoundImportRequestCode = 74
    // Tone playback and peak following
    @Volatile internal var tonePlaying = false
    @Volatile internal var toneFrequency = 440
    internal var tonePeakFollowEnabled = false
    internal var tonePeakFollowWindowStartedAt = 0L
    internal var tonePeakLastAcceptedAt = 0L
    internal var tonePeakBestFrequency = 0.0
    internal var tonePeakBestDb = -120.0
    internal var tonePeakDetectionWindowMs = 500L
    internal var tonePeakHoldDurationMs = 5000L
    internal var latestAnalyzedPeakFrequency = 0.0
    internal var latestAnalyzedPeakDb = -120.0
    internal var latestAnalyzedRmsDb = -120.0
    internal var tonePeakPresetButton: AnalyzerIconButton? = null

    // Piano samples, voices and keyboard controls
    internal val pianoSamples = ConcurrentHashMap<Int, PianoSample>()
    internal val pianoHeldNotes = mutableSetOf<Int>()
    internal val pianoPressedTargets = mutableMapOf<Int, Int>()
    internal val pianoAudioLock = Any()
    internal val pianoVoices = mutableListOf<PianoVoice>()
    internal var pianoAudioTrack: AudioTrack? = null
    internal var pianoAudioThread: Thread? = null
    @Volatile internal var pianoEngineRunning = false
    @Volatile internal var pianoSamplesLoading = false
    @Volatile internal var pianoSamplesInitialized = false
    internal val pianoLoadedCount = AtomicInteger(0)
    internal val pianoLoadFailedCount = AtomicInteger(0)
    internal var pianoVolume = 0.5f
    internal var pianoVolumeBeforeMute = 0.5f
    @Volatile internal var pianoReverbEnabled = true
    internal var pianoReverbButton: ImageButton? = null
    internal var fullScalePianoReverbButton: ImageButton? = null
    internal var pianoTranspose = 0
    internal var pianoVolumeSlider: SeekBar? = null
    internal var pianoVolumeLabel: TextView? = null
    internal var pianoVolumeIconButton: AnalyzerIconButton? = null
    internal var pianoOctaveButton: ImageButton? = null
    internal val pianoAudioShutdownHandler = Handler(Looper.getMainLooper())
    internal val pianoAudioShutdownRunnable = Runnable { stopPianoAudioEngine() }

    internal val pianoOutputRate = 44100
    internal val pianoBlockFrames = 256
    internal val pianoReverbLeft = PianoReverbProcessor(pianoOutputRate)
    internal val pianoReverbRight = PianoReverbProcessor(pianoOutputRate, stereoOffset = 23)

    // Tone synthesis, oscilloscope and sweep state
    internal var toneMinFrequency = 20
    internal var toneMaxFrequency = 20000
    @Volatile internal var toneVolume = 0.5
    @Volatile internal var toneWaveform = "Sine"
    @Volatile internal var toneOutputChannel = "Stereo"
    @Volatile internal var toneDtmfActive = false
    @Volatile internal var toneDtmfLowFrequency = 697
    @Volatile internal var toneDtmfHighFrequency = 1209
    internal var toneDtmfSelectedKey = "1"
    internal var toneDtmfModeButton: Button? = null
    internal var toneWaveformButton: View? = null
    @Volatile internal var toneSignalPreview: ToneSignalPreviewView? = null
    @Volatile internal var toneStopRequested = false
    internal var toneTrack: AudioTrack? = null
    internal var toneThread: Thread? = null
    internal var tonePlayButton: Button? = null
    @Volatile internal var toneSweepActive = false
    internal var toneSweepStartedAt = 0L
    internal var toneSweepDurationMs = 32_000L
    internal var toneSweepStartFrequency = 20
    internal var toneSweepEndFrequency = 20000
    @Volatile internal var toneSweepAscending = true
    @Volatile internal var toneSweepLogarithmic = true
    internal val toneSweepHandler = Handler(Looper.getMainLooper())
    internal var toneSweepUiUpdate: (() -> Unit)? = null
    internal var toneSweepButton: AnalyzerIconButton? = null
    internal var toneSweepRangeButton: Button? = null
    internal var toneSweepDurationButton: Button? = null
    internal var toneSweepDirectionButton: Button? = null
    internal var toneSweepScaleButton: Button? = null

    internal val toneSweepRunnable = object : Runnable {
        override fun run() {
            if (!toneSweepActive) return
            val low = toneSweepStartFrequency.coerceIn(20, 19999)
            val high = toneSweepEndFrequency.coerceIn(low + 1, 20000)
            val start = if (toneSweepAscending) low else high
            val end = if (toneSweepAscending) high else low
            val progress = ((SystemClock.elapsedRealtime() - toneSweepStartedAt).toDouble() / toneSweepDurationMs)
                .coerceIn(0.0, 1.0)
            val sweptFrequency = if (toneSweepLogarithmic) {
                start * exp(progress * ln(end.toDouble() / start))
            } else {
                start + (end - start) * progress
            }
            toneFrequency = sweptFrequency.roundToInt().coerceIn(low, high)
            toneSweepUiUpdate?.invoke()
            if (progress >= 1.0) {
                cancelToneSweep()
                stopTone()
                resetTonePlayButton()
            } else {
                toneSweepHandler.postDelayed(this, 30L)
            }
        }
    }

    @Volatile
    // Microphone capture, routing and analysis buffers
    internal var running = false
    @Volatile
    internal var audioCaptureGeneration = 0L

    internal var recorder: AudioRecord? = null
    internal var audioThread: Thread? = null
    internal val audioRouteHandler = Handler(Looper.getMainLooper())
    internal var audioDeviceCallback: android.media.AudioDeviceCallback? = null
    internal var pendingAudioRouteRestart: Runnable? = null
    internal var audioRestartInProgress = false
    @Volatile
    internal var preferBuiltInMicrophone = false
    internal var silentCaptureStartedAtMs = 0L
    @Volatile
    internal var silenceFallbackAttempted = false

    internal val sampleRate = 48000
    internal val tonePreviewPcmFrame = TonePcmFrame(sampleRate)

    // FFT configuration
    internal val fftSize = 8192

    internal val permissionRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAnalyzerOrientation(soundId = false)
        setContentView(R.layout.activity_main)
        hideSystemBars()
        ensureAudioDeviceCallbackRegistered()

        val splPreferences = getSharedPreferences("sound_level_calibration", Context.MODE_PRIVATE)
        splTimeWeighting = if (splPreferences.getString("time_weighting", "FAST") == "SLOW") "SLOW" else "FAST"

        val peakPreferences = getSharedPreferences("tone_peak_follow", Context.MODE_PRIVATE)
        tonePeakDetectionWindowMs = peakPreferences.getLong("detection_window_ms", 500L)
            .takeIf { it in longArrayOf(250L, 500L, 1000L, 2000L, 5000L) } ?: 500L
        tonePeakHoldDurationMs = peakPreferences.getLong("hold_duration_ms", 5000L)
            .takeIf { it in longArrayOf(1000L, 2000L, 5000L, 10000L, 0L) } ?: 5000L

        spectrumView = findViewById(R.id.spectrumView)
        statusText = findViewById(R.id.statusText)
        peakText = findViewById(R.id.peakText)
        startButton = findViewById(R.id.startButton)
        configureSpectrumHeader()
        spectrumView.setControlStateListener { axisMode, _, _, _ ->
            headerTitleText?.text = if (axisMode == "LINEAR") {
                "SPECTRUM ANALYZER (LINEAR)"
            } else {
                "SPECTRUM ANALYZER (LOGARITHMIC)"
            }
            axisToggleButton?.apply {
                text = ""
                (this as? AnalyzerIconButton)?.icon = if (axisMode == "LINEAR") {
                    AnalyzerIconButton.Icon.LOG
                } else {
                    AnalyzerIconButton.Icon.LIN
                }
            }
        }
        statusText.visibility = View.GONE

        // Keep the bottom controls in equal, fixed-size cells. Reset Graph is removed.
        peakText.visibility = View.GONE
        statusText.visibility = View.GONE

        val startParent = startButton.parent as? ViewGroup
        if (startParent != null) {
            val startIndex = startParent.indexOfChild(startButton)
            val originalParams = startButton.layoutParams
            startParent.removeView(startButton)

            fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                )
            }

            fun configureActionButton(button: Button, primary: Boolean = false) {
                button.textSize = 14f
                button.minHeight = dp(40)
                button.minimumHeight = dp(40)
                button.setPadding(if (button is AnalyzerIconButton) 0 else dp(10), dp(2), if (button is AnalyzerIconButton) 0 else dp(10), dp(2))
                button.maxLines = 1
                button.includeFontPadding = false
                button.backgroundTintList = null
                button.setTextColor(Color.WHITE)
                val iconButton = button is AnalyzerIconButton || button.contentDescription == "Piano keyboard"
                if (iconButton) {
                    button.background = GradientDrawable().apply {
                        setColor(if (primary) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.rgb(56, 73, 91))
                    }
                    button.stateListAnimator = null
                } else {
                    styleXButton(button, primary = primary)
                }
                val buttonWidth = when {
                    button is AnalyzerIconButton && (button.icon == AnalyzerIconButton.Icon.LIN || button.icon == AnalyzerIconButton.Icon.LOG) -> dp(48)
                    button is AnalyzerIconButton && (button.icon == AnalyzerIconButton.Icon.TONE || button.icon == AnalyzerIconButton.Icon.SOUND || button.icon == AnalyzerIconButton.Icon.SPECTRUM || button.icon == AnalyzerIconButton.Icon.SPECTROGRAM) -> dp(48)
                    iconButton -> dp(48)
                    else -> ViewGroup.LayoutParams.WRAP_CONTENT
                }
                actionRow.addView(button, LinearLayout.LayoutParams(buttonWidth, dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }

            // Reset remains the rightmost graph control after analysis start/stop is removed.
            val resetButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.RESET)
            resetDisplayButton = resetButton
            configureActionButton(resetButton)
            styleFlatToolbarButton(resetButton)
            resetButton.layoutParams = (resetButton.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(48)
                setMargins(dp(3), dp(2), dp(3), dp(2))
            }
            resetButton.contentDescription = "Reset display"
            resetButton.setOnClickListener {
                leaveFullScaleKeyboard()
                spectrumView.resetView(useKeysFrequencyRange = keyboardModeOpen)
                if (splDetailDialog?.isShowing == true) resetSplMeterSession()
            }

            val spectrogramIconBitmap = android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.ic_spectrogram_custom,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            )
            val spectrumIconBitmap = android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.ic_spectrum_custom,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            )
            val spectrogramInitiallyEnabled = spectrumView.isSpectrogramEnabled()
            val spectrogramButton = AnalyzerIconButton(this, if (spectrogramInitiallyEnabled) AnalyzerIconButton.Icon.SPECTRUM else AnalyzerIconButton.Icon.SPECTROGRAM).apply {
                setCustomIcon(if (spectrogramInitiallyEnabled) spectrumIconBitmap else spectrogramIconBitmap)
                contentDescription = if (spectrogramInitiallyEnabled) "Switch to spectrum view" else "Switch to spectrogram view"
                // Show the mode this button will switch TO, not the current mode.
                setOnClickListener {
                    leaveFullScaleKeyboard()
                    val enabled = spectrumView.toggleSpectrogram()
                    if (enabled) {
                        setCustomIcon(spectrumIconBitmap)
                        icon = AnalyzerIconButton.Icon.SPECTRUM
                        contentDescription = "Switch to spectrum view"
                    } else {
                        icon = AnalyzerIconButton.Icon.SPECTROGRAM
                        setCustomIcon(spectrogramIconBitmap)
                        contentDescription = "Switch to spectrogram view"
                    }
                }
            }
            spectrogramModeButton = spectrogramButton
            configureActionButton(spectrogramButton)
            styleFlatToolbarButton(spectrogramButton)

            val logIconBitmap = android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.ic_lin_custom,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            )
            val linIconBitmap = android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.ic_log_custom,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            )
            val linearAxisInitially = spectrumView.isLinearFrequencyAxis()
            val axisButton = AnalyzerIconButton(this, if (linearAxisInitially) AnalyzerIconButton.Icon.LOG else AnalyzerIconButton.Icon.LIN).apply {
                setCustomIcon(if (linearAxisInitially) logIconBitmap else linIconBitmap)
                contentDescription = if (linearAxisInitially) "Switch to logarithmic frequency scale" else "Switch to linear frequency scale"
                // Show the axis mode this button will switch TO.
                setOnClickListener {
                    leaveFullScaleKeyboard()
                    spectrumView.toggleFrequencyAxis()
                    if (spectrumView.isLinearFrequencyAxis()) {
                        icon = AnalyzerIconButton.Icon.LOG
                        setCustomIcon(logIconBitmap)
                        contentDescription = "Switch to logarithmic frequency scale"
                    } else {
                        icon = AnalyzerIconButton.Icon.LIN
                        setCustomIcon(linIconBitmap)
                        contentDescription = "Switch to linear frequency scale"
                    }
                }
            }
            axisToggleButton = axisButton
            configureActionButton(axisButton)
            styleFlatToolbarButton(axisButton)

            // Flexible spacer pins the graph controls to the far right.
            val controlsSpacer = View(this)
            mainActionsSpacer = controlsSpacer
            actionRow.addView(controlsSpacer, LinearLayout.LayoutParams(0, 1, 1f))

            val keysButton = Button(this).apply {
                text = "🎹"
                textSize = 20f
                contentDescription = "Piano keyboard"
            }
            keyboardToggleButton = keysButton
            configureActionButton(keysButton)
            styleFlatToolbarButton(keysButton)
            keysButton.layoutParams = (keysButton.layoutParams as LinearLayout.LayoutParams).apply { width = dp(48) }
            keysButton.textSize = 20f

            val fullScaleButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.FULL_SCALE_OPEN).apply {
                visibility = View.GONE
                contentDescription = "Open full-screen piano keyboard"
                setOnClickListener {
                    if (fullScaleKeyboardOpen) leaveFullScaleKeyboard() else showFullScaleKeyboard()
                }
            }
            fullScaleKeysButton = fullScaleButton
            configureActionButton(fullScaleButton)
            styleFlatToolbarButton(fullScaleButton)

            val soundIdButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.SOUND).apply {
                setOnClickListener { showSoundIdDialog() }
            }
            soundIdButton.setCustomIcon(android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.ic_soundid_custom,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            ))
            soundIdModeButton = soundIdButton
            configureActionButton(soundIdButton)
            styleFlatToolbarButton(soundIdButton)

            val toneButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.TONE)
            toneButton.setCustomIcon(android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.ic_tone_generator_original,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            ))
            toneToggleButton = toneButton
            configureActionButton(toneButton)
            styleFlatToolbarButton(toneButton)

            // Arrange the controls from left to right, with graph actions anchored at the right edge.
            actionRow.removeAllViews()
            actionRow.addView(toneButton)
            val keysGroup = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(3), 0, dp(3), 0)
                background = android.graphics.drawable.ColorDrawable(Color.rgb(15, 23, 36))
                // These controls move between toolbars; LayoutTransition would animate
                // their removal as well as the surrounding mode transition.
                layoutTransition = null
            }
            keyboardModeButtonGroup = keysGroup
            (keysButton.parent as? ViewGroup)?.removeView(keysButton)
            (fullScaleButton.parent as? ViewGroup)?.removeView(fullScaleButton)
            keysGroup.addView(keysButton)
            keysGroup.addView(fullScaleButton)
            actionRow.addView(keysGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
            actionRow.addView(soundIdButton)
            actionRow.addView(controlsSpacer, LinearLayout.LayoutParams(0, 1, 1f))
            actionRow.addView(axisButton)
            actionRow.addView(spectrogramButton)
            actionRow.addView(resetButton)
            mainActionRow = actionRow
            val actionBar = android.widget.FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                setBackgroundColor(Color.rgb(15, 23, 36))
            }
            bottomActionBar = actionBar
            actionBar.addView(
                actionRow,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                )
            )
            rangePickerButton?.let { rangeButton ->
                (rangeButton.parent as? ViewGroup)?.removeView(rangeButton)
                actionBar.addView(
                    rangeButton,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(38),
                        Gravity.CENTER
                    )
                )
            }
            originalParams.height = dp(44)
            startParent.addView(actionBar, startIndex, originalParams)
            startButton.visibility = View.GONE
            installToneGeneratorSplit(toneButton, keysButton)
        }
        updateStartButtonAppearance()

        // Start the live graph automatically when the app opens.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAnalysis()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
        }

        // Begin decoding piano samples shortly after the first screen is shown.
        // The decoder workers prioritize the middle octave and never block the UI thread.
        window.decorView.postDelayed({
            if (!isFinishing) initializePianoSamples()
        }, 250L)

    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        toneSplTextSizePx = null
        hideSystemBars()
        if (!isCompactAppWindow()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val reopenSplDetail = splDetailDialog?.isShowing == true
        val reopenAudioRoutePicker = audioRouteDialog?.isShowing == true
        if (reopenSplDetail) dismissForLayout(splDetailDialog)
        if (reopenAudioRoutePicker) dismissForLayout(audioRouteDialog)
        val refresh = Runnable {
            adaptiveWindowLayoutUpdate?.invoke()
            updateSpectrumHeaderWidths()
            keyboardControlsRow?.post { applyKeyboardVolumeSafeInset() }
            keyboardPanel?.post { applyPianoKeyboardSafeInsets() }
            if (reopenSplDetail && !isFinishing) showSplDetailDialog()
            if (reopenAudioRoutePicker && !isFinishing) showAudioRouteDialog()
        }
        // Allow an active mode animation to finish before measuring the new window.
        val delay = if (modeTransitionInProgress) 520L else 0L
        window.decorView.postDelayed(refresh, delay)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            keyboardControlsRow?.post {
                applyKeyboardVolumeSafeInset()
                keyboardPanel?.post { applyPianoKeyboardSafeInsets() }
            }
        }
    }

    internal var splOpenPending = false

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == permissionRequest &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startAnalysis()
        } else if (requestCode == permissionRequest) {
            Toast.makeText(
                this,
                "Microphone permission is required.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            soundIdExportRequestCode -> {
                val pdf = pendingSoundIdPdf
                pendingSoundIdPdf = null
                if (resultCode != RESULT_OK || uri == null || pdf == null) return
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Could not open the selected destination")
                    output.use { it.write(pdf) }
                    Toast.makeText(this, "Sound summary saved.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not save the summary.", Toast.LENGTH_LONG).show()
                }
            }
            personalSoundExportFolderRequestCode -> {
                if (resultCode != RESULT_OK || uri == null) return
                try {
                    val returnedFlags = data?.flags ?: 0
                    val readGranted = returnedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
                    val writeGranted = returnedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                    if (!writeGranted) {
                        Toast.makeText(this, "The selected folder did not grant write access.", Toast.LENGTH_LONG).show()
                        return
                    }
                    if (readGranted) {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } else {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    getSharedPreferences("personal_sound_export", Context.MODE_PRIVATE)
                        .edit().putString("folder_uri", uri.toString()).apply()
                    exportPersonalSoundProfiles()
                } catch (error: Exception) {
                    Toast.makeText(this, "Could not save the export folder: ${error.message ?: "access denied"}", Toast.LENGTH_LONG).show()
                }
            }
            personalSoundImportRequestCode -> {
                if (resultCode != RESULT_OK) return
                val selectedUris = buildList {
                    uri?.let { add(it) }
                    data?.clipData?.let { clips ->
                        for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri)
                    }
                }.distinct()
                if (selectedUris.isEmpty()) return
                importPersonalSoundFiles(selectedUris)
            }
        }
    }

    internal fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        clearKeysClosingVisual()
        toneExpansionAnimator?.cancel()
        toneExpansionAnimator = null
        toggleToneExpanded = null
        configureExpandedToneControls = null
        dismissForLayout(fullScaleKeysDialog)
        dismissForLayout(audioRouteDialog)
        dismissForLayout(splDetailDialog)
        soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
        stopPersonalSoundPreview()
        stopTone()
        stopAnalysis()
        unregisterAudioDeviceCallback()
        if (soundIdPanel != null) soundIdEngine.close()
        stopPianoAudioEngine()
        pianoSamples.clear()
        pianoHeldNotes.clear()
        super.onDestroy()
    }

    // Kotlin only permits checking a lateinit backing field in its declaring class.
    internal val isStartButtonInitialized: Boolean get() = ::startButton.isInitialized
}
