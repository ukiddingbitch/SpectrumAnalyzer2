package sk.spectrum.analyzer2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ScrollView
import android.widget.Toast

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Spectrum/spectrogram rendering, axes, gestures and display state. */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundColor = Color.rgb(15, 23, 36)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(49, 63, 81)
        strokeWidth = 1f
    }
    private val minorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 42, 56)
        strokeWidth = 1f
    }
    private val spectrogramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Live spectrum is white for strong contrast against the dark blue background.
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val peakHoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 100, 120)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val peakLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 70)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val peakPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 70)
        style = Paint.Style.FILL
    }

    private val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val cursorPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 255)
        style = Paint.Style.FILL
    }

    private val cursorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(125, 235, 255)
        // Match the 14sp text size used by the RESET GRAPH button.
        textSize = 14f * resources.displayMetrics.scaledDensity
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val cursorInfoBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 36)
        style = Paint.Style.FILL
    }

    private val cursorInfoBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 10f * resources.displayMetrics.density
    }

    private val dbLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 10f * resources.displayMetrics.density
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val dbAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(106, 127, 149)
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val dbAxisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 10f * resources.displayMetrics.density
        textAlign = Paint.Align.RIGHT
    }
    private val dbAxisUnitPaint = Paint(dbLabelPaint).apply { textAlign = Paint.Align.RIGHT }

    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 135)
        textSize = 8.5f * resources.displayMetrics.density
        style = Paint.Style.FILL
        setShadowLayer(3f, 0f, 1f, Color.rgb(15, 23, 36))
    }

    private val spectrumPath = Path()
    private val peakHoldPath = Path()
    private val handler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private var multiTouchInProgress = false
    private val longPressTimeoutMs = 650L

    @Volatile
    private var magnitudes = DoubleArray(0)

    private var smoothedMagnitudes = DoubleArray(0)
    private val averagingAlpha = 0.28

    private var linearFrequencyAxis = false
    @Volatile
    private var spectrogramEnabled = false
    private val spectrogramHistory = java.util.ArrayDeque<DoubleArray>()
    private val spectrogramHistorySeconds = 6.0
    private var spectrogramColumnFirstBins = IntArray(0)
    private var spectrogramColumnLastBins = IntArray(0)
    private var peakHoldEnabled = false
    private var peakHoldMagnitudes = DoubleArray(0)
    private var dbCeiling = -30
    private var dbFloor = -150
    private var dbAxisTransitionRange: Pair<Double, Double>? = null
    private var frequencyAxisTransition: Triple<Pair<Double, Double>, Pair<Double, Double>, Float>? = null
    private var dbAxisTransition: Triple<Pair<Int, Int>, Pair<Int, Int>, Float>? = null
    private var dbRangeMode = "DEFAULT"
    private var savedDbRange: Pair<Int, Int>? = null
    private var savedFrequencyRangeForKeys: Pair<Double, Double>? = null
    private var keysFrequencyRangeExitStart: Pair<Double, Double>? = null
    private var panStartX = 0f
    private var panStartY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var draggingYScale = false
    private var spectrogramPanOffsetY = 0f
    private var yScaleStartY = 0f
    private var yScaleStartRange = 80f
    private var yScaleStartCenter = -90f
    // Reserve the right edge for the dB axis and make that axis itself draggable for zoom.
    private val dbAxisMarginWidth = 40f * resources.displayMetrics.density
    private var initialAutoRangePending = true
    @Volatile
    private var resetGeneration = 0
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var soundIdPortraitLayoutActive = false
    private var plotInsetsDirty = true
    private var cachedPlotCutoutInsets = 0f to 0f

    fun setSoundIdPortraitLayoutActive(active: Boolean) {
        if (soundIdPortraitLayoutActive == active) return
        soundIdPortraitLayoutActive = active
        plotInsetsDirty = true
        invalidate()
    }

    private fun plotCutoutInsets(): Pair<Float, Float> {
        if (!plotInsetsDirty) return cachedPlotCutoutInsets
        plotInsetsDirty = false
        // In portrait Sound ID mode, use the full screen width for the plot.
        // In landscape, keep the same 4 dp gap beyond the OS-reported camera cutout.
        if (soundIdPortraitLayoutActive || android.os.Build.VERSION.SDK_INT < 28) {
            cachedPlotCutoutInsets = 0f to 0f
            return cachedPlotCutoutInsets
        }
        val cutout = rootWindowInsets?.displayCutout ?: run {
            cachedPlotCutoutInsets = 0f to 0f
            return cachedPlotCutoutInsets
        }
        val root = rootView
        if (root.width <= 0 || width <= 0) {
            plotInsetsDirty = true
            return 0f to 0f
        }
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        getLocationOnScreen(viewLocation)
        val rootLeft = rootLocation[0].toFloat()
        val rootRight = rootLeft + root.width
        val viewLeft = viewLocation[0].toFloat()
        val viewRight = viewLeft + width
        val leftOverlap = (rootLeft + cutout.safeInsetLeft - viewLeft).coerceAtLeast(0f)
        val rightOverlap = (viewRight - (rootRight - cutout.safeInsetRight)).coerceAtLeast(0f)
        val gap = 4f * resources.displayMetrics.density
        cachedPlotCutoutInsets = (if (leftOverlap > 0f) leftOverlap + gap else 0f) to
                (if (rightOverlap > 0f) rightOverlap + gap else 0f)
        return cachedPlotCutoutInsets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        plotInsetsDirty = true
    }

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        val result = super.onApplyWindowInsets(insets)
        plotInsetsDirty = true
        postInvalidateOnAnimation()
        return result
    }

    private fun plotTopInset(): Float = 20f * resources.displayMetrics.density

    private fun plotBottomInset(): Float = 15f * resources.displayMetrics.density

    private fun plotLeftInset(): Float {
        if (soundIdPortraitLayoutActive) return 0f
        val (leftInset, _) = plotCutoutInsets()
        // Preserve the existing landscape fallback on Android versions without cutout APIs.
        return if (leftInset > 0f) leftInset else 18f * resources.displayMetrics.density
    }

    private fun plotRightInset(): Float {
        if (soundIdPortraitLayoutActive) return 0f
        return plotCutoutInsets().second
    }

    private fun plotRightEdge(): Float =
        width.toFloat() - plotRightInset() - dbAxisMarginWidth - 2f

    private fun axisLabelRight(): Float =
        width.toFloat() - plotRightInset() - 7f * resources.displayMetrics.density

    fun getResetGeneration(): Int = resetGeneration
    private var controlStateListener: ((String, Boolean, Int, Int) -> Unit)? = null
    private var frequencyRangeListener: ((Int, Int) -> Unit)? = null

    @Volatile
    private var sourceSampleRate = 48000

    @Volatile
    private var sourceFftSize = 8192

    @Volatile
    private var peakFrequency = 0.0

    private var selectedFrequency: Double? = null
    private var selectedSpectrogramXRatio: Double? = null
    private var selectedSpectrogramYRatio: Double? = null

    private var visibleMinFrequency = 20.0
    private var visibleMaxFrequency = 20000.0

    private val minFrequencyBound = 20.0
    private val maxFrequencyBound = 40000.0
    private val minLogSpan = ln(1.5)
    private val maxLogSpan = ln(maxFrequencyBound / minFrequencyBound)

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAt(detector.focusX, detector.scaleFactor)
                return true
            }
        }
    )

    private val longPressRunnable = Runnable {
        if (!multiTouchInProgress) {
            longPressTriggered = true
            togglePeakHold()
        }
    }

    // Keep 8192 samples for the full view and switch to 16384 when zoomed in.
    // Hysteresis prevents rapid FFT-size switching around the zoom threshold,
    // which can make the spectrum appear to jump vertically during pinch zoom.
    @Volatile
    private var highResolutionFftEnabled = false

    fun getRecommendedFftSize(): Int {
        val zoomFactor = if (linearFrequencyAxis) {
            (maxFrequencyBound - minFrequencyBound) /
                (visibleMaxFrequency - visibleMinFrequency).coerceAtLeast(1.0)
        } else {
            val fullSpan = ln(maxFrequencyBound / minFrequencyBound)
            val visibleSpan = logAxisPosition(visibleMaxFrequency) - logAxisPosition(visibleMinFrequency)
            fullSpan / visibleSpan.coerceAtLeast(1e-9)
        }

        if (!highResolutionFftEnabled && zoomFactor >= 1.6) {
            highResolutionFftEnabled = true
        } else if (highResolutionFftEnabled && zoomFactor <= 1.2) {
            highResolutionFftEnabled = false
        }

        return if (highResolutionFftEnabled) 16384 else 8192
    }

    fun setSpectrum(
        data: DoubleArray,
        sampleRate: Int,
        fftSize: Int,
        peakFrequency: Double
    ) {
        if (smoothedMagnitudes.size != data.size) {
            synchronized(spectrogramHistory) { spectrogramHistory.clear() }
            smoothedMagnitudes = data.copyOf()
        } else {
            for (i in data.indices) {
                smoothedMagnitudes[i] =
                    averagingAlpha * data[i] +
                            (1.0 - averagingAlpha) * smoothedMagnitudes[i]
            }
        }

        magnitudes = smoothedMagnitudes.copyOf()
        synchronized(spectrogramHistory) {
            spectrogramHistory.addLast(magnitudes.copyOf())
            val frameHop = (fftSize / 4).coerceAtLeast(1)
            val historyLimit = ceil(spectrogramHistorySeconds * sampleRate / frameHop).toInt().coerceAtLeast(1)
            while (spectrogramHistory.size > historyLimit) spectrogramHistory.removeFirst()
        }
        // Keep the default spectrum range at -150 dB to -30 dB after startup and reset.
        // Do not auto-center the graph on the strongest peak, which can make it jump vertically.
        if (initialAutoRangePending) {
            dbFloor = -150
            dbCeiling = -30
            initialAutoRangePending = false
            notifyControlState()
        }
        if (peakHoldEnabled) {
            val binWidth = sampleRate.toDouble() / fftSize
            if (peakHoldMagnitudes.size != magnitudes.size) {
                peakHoldMagnitudes = copySpectrumUpTo20kHz(magnitudes, sampleRate, fftSize)
            } else {
                for (i in magnitudes.indices) {
                    if (i * binWidth > 20000.0) {
                        peakHoldMagnitudes[i] = 0.0
                    } else if (magnitudes[i] > peakHoldMagnitudes[i]) {
                        peakHoldMagnitudes[i] = magnitudes[i]
                    }
                }
            }
        }
        sourceSampleRate = sampleRate
        sourceFftSize = fftSize
        this.peakFrequency = peakFrequency
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Keep parent containers (for example, a ScrollView) from interpreting
        // the vertical movement of a pinch gesture as a request to scroll the UI.
        if (event.pointerCount > 1 || scaleGestureDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                multiTouchInProgress = false
                dragging = false
                // Dragging the dB-axis margin on the right changes the vertical range.
                draggingYScale = !spectrogramEnabled && event.x >= plotRightEdge()
                if (draggingYScale) parent?.requestDisallowInterceptTouchEvent(true)
                yScaleStartY = event.y
                yScaleStartRange = (dbCeiling - dbFloor).toFloat()
                yScaleStartCenter = (dbCeiling + dbFloor) / 2f
                panStartX = event.x
                panStartY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                handler.removeCallbacks(longPressRunnable)
                if (!draggingYScale) handler.postDelayed(longPressRunnable, longPressTimeoutMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Lock the gesture to the graph for the entire multi-touch sequence.
                parent?.requestDisallowInterceptTouchEvent(true)
                multiTouchInProgress = true
                dragging = false
                draggingYScale = false
                handler.removeCallbacks(longPressRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    multiTouchInProgress = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    handler.removeCallbacks(longPressRunnable)
                } else if (multiTouchInProgress) {
                    // After a pinch, ignore the remaining finger until the whole
                    // gesture ends; otherwise its last movement can pan the graph.
                    handler.removeCallbacks(longPressRunnable)
                } else if (draggingYScale && !longPressTriggered) {
                    initialAutoRangePending = false
                    handler.removeCallbacks(longPressRunnable)
                    // Scale from the gesture's starting point, using a low, density-independent
                    // sensitivity so short drags make small, predictable dB-range changes.
                    val totalDyDp = (event.y - yScaleStartY) / resources.displayMetrics.density
                    val newRange = (yScaleStartRange * kotlin.math.exp(totalDyDp.toDouble() * 0.0018))
                        .roundToInt().coerceIn(20, 240)
                    // Keep the starting midpoint fixed so zooming does not shift the graph.
                    var newFloor = kotlin.math.round(yScaleStartCenter.toDouble() - newRange / 2.0).toInt()
                    var newCeiling = newFloor + newRange
                    if (newFloor < -160) {
                        newFloor = -160
                        newCeiling = newFloor + newRange
                    }
                    if (newCeiling > 10) {
                        newCeiling = 10
                        newFloor = newCeiling - newRange
                    }
                    dbFloor = newFloor
                    dbCeiling = newCeiling
                    notifyControlState()
                    invalidate()
                } else if (!longPressTriggered) {
                    val totalDx = event.x - panStartX
                    val totalDy = event.y - panStartY
                    if (!dragging && hypot(totalDx, totalDy) > touchSlop) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging && !scaleGestureDetector.isInProgress) {
                        initialAutoRangePending = false
                        panBy(event.x - lastTouchX, event.y - lastTouchY)
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Keep the parent from intercepting while the last finger is lifted.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val wasMultiTouch = multiTouchInProgress
                multiTouchInProgress = false
                // Allow the parent to handle normal scrolling again after the full gesture ends.
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!longPressTriggered && !dragging && !draggingYScale && !wasMultiTouch && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    performClick()
                    if (spectrogramEnabled) {
                        selectSpectrogramAt(event.x, event.y)
                    } else {
                        selectedSpectrogramXRatio = null
                        selectedSpectrogramYRatio = null
                        selectFrequencyAt(event.x)
                    }
                }
                draggingYScale = false
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setControlStateListener(listener: (String, Boolean, Int, Int) -> Unit) {
        controlStateListener = listener
        notifyControlState()
    }

    fun setFrequencyRangeListener(listener: (Int, Int) -> Unit) {
        frequencyRangeListener = listener
        notifyFrequencyRange()
    }

    private fun notifyFrequencyRange() {
        val (minHz, maxHz) = getVisibleFrequencyRangeHz()
        frequencyRangeListener?.invoke(minHz, maxHz)
    }

    private fun notifyControlState() {
        controlStateListener?.invoke(if (linearFrequencyAxis) "LINEAR" else "LOG", peakHoldEnabled, dbCeiling, dbFloor)
    }

    fun isLinearFrequencyAxis(): Boolean = linearFrequencyAxis

    fun isSpectrogramEnabled(): Boolean = spectrogramEnabled

    fun setDbRangeMode(mode: String) {
        val nextMode = when (mode) {
              "TONE", "KEYS", "SOUND" -> mode
            else -> "DEFAULT"
        }
        if (nextMode == dbRangeMode) return
        dbAxisTransitionRange = null
        dbAxisTransition = null

        if (dbRangeMode == "DEFAULT" && nextMode != "DEFAULT") {
            savedDbRange = if (initialAutoRangePending) (-150 to -30) else (dbFloor to dbCeiling)
        }

        dbRangeMode = nextMode
        when (nextMode) {
            "TONE" -> { dbFloor = -135; dbCeiling = -30 }
            "KEYS" -> { dbFloor = -140; dbCeiling = -30 }
              "SOUND" -> { dbFloor = -140; dbCeiling = -30 }
            else -> {
                val restoredRange = savedDbRange ?: (-150 to -30)
                dbFloor = restoredRange.first
                dbCeiling = restoredRange.second
                savedDbRange = null
            }
        }
        initialAutoRangePending = false
        invalidate()
        notifyControlState()
    }

    fun setKeysFrequencyRangeActive(active: Boolean, notify: Boolean = true) {
        frequencyAxisTransition = null
        if (active) {
            if (savedFrequencyRangeForKeys == null) {
                savedFrequencyRangeForKeys = visibleMinFrequency to visibleMaxFrequency
            }
            keysFrequencyRangeExitStart = null
            visibleMinFrequency = 65.0
            visibleMaxFrequency = 12500.0
        } else {
            val previousRange = savedFrequencyRangeForKeys ?: return
            visibleMinFrequency = previousRange.first
            visibleMaxFrequency = previousRange.second
            savedFrequencyRangeForKeys = null
            keysFrequencyRangeExitStart = null
        }
        invalidate()
        if (notify) notifyFrequencyRange()
    }

    fun getVisibleFrequencyRangeForTransition(): Pair<Double, Double> =
        visibleMinFrequency to visibleMaxFrequency

    fun setFrequencyRangeForTransition(
        start: Pair<Double, Double>,
        target: Pair<Double, Double>,
        progress: Float
    ) {
        val fraction = progress.coerceIn(0f, 1f).toDouble()
        frequencyAxisTransition = if (fraction < 1.0) Triple(start, target, fraction.toFloat()) else null
        if (linearFrequencyAxis) {
            visibleMinFrequency = start.first + (target.first - start.first) * fraction
            visibleMaxFrequency = start.second + (target.second - start.second) * fraction
        } else {
            val startMin = logAxisPosition(start.first)
            val startMax = logAxisPosition(start.second)
            val targetMin = logAxisPosition(target.first)
            val targetMax = logAxisPosition(target.second)
            visibleMinFrequency = frequencyAtLogAxisPosition(startMin + (targetMin - startMin) * fraction)
            visibleMaxFrequency = frequencyAtLogAxisPosition(startMax + (targetMax - startMax) * fraction)
        }
        invalidate()
    }

    fun notifyVisibleFrequencyRangeChanged() = notifyFrequencyRange()

    fun getVisibleDbRangeForTransition(): Pair<Int, Int> = dbFloor to dbCeiling

    fun getDbRangeTargetForMode(mode: String): Pair<Int, Int> = when (mode) {
        "TONE" -> -135 to -30
        "KEYS", "SOUND" -> -140 to -30
        else -> savedDbRange ?: (-150 to -30)
    }

    fun setDbRangeForTransition(
        start: Pair<Int, Int>,
        target: Pair<Int, Int>,
        progress: Float
    ) {
        val fraction = progress.coerceIn(0f, 1f).toDouble()
        dbAxisTransition = if (fraction < 1.0) Triple(start, target, fraction.toFloat()) else null
        val floorDb = start.first + (target.first - start.first) * fraction
        val ceilingDb = start.second + (target.second - start.second) * fraction
        dbAxisTransitionRange = floorDb to max(ceilingDb, floorDb + 1.0)
        invalidate()
    }

    fun beginKeysFrequencyRangeExitTransition() {
        if (savedFrequencyRangeForKeys == null) return
        keysFrequencyRangeExitStart = visibleMinFrequency to visibleMaxFrequency
    }

    fun setKeysFrequencyRangeExitProgress(progress: Float) {
        val start = keysFrequencyRangeExitStart ?: return
        val target = savedFrequencyRangeForKeys ?: return
        setFrequencyRangeForTransition(start, target, progress)
    }

    fun cancelKeysFrequencyRangeExitTransition() {
        val start = keysFrequencyRangeExitStart ?: return
        visibleMinFrequency = start.first
        visibleMaxFrequency = start.second
        keysFrequencyRangeExitStart = null
        frequencyAxisTransition = null
        invalidate()
    }

    fun toggleFrequencyAxis() {
        linearFrequencyAxis = !linearFrequencyAxis
        invalidate()
        notifyControlState()
    }

    fun toggleSpectrogram(): Boolean {
        spectrogramEnabled = !spectrogramEnabled
        // Show the selected view on the next frame. History duration stays stable
        // when the graph changes FFT size.
        postInvalidateOnAnimation()
        return spectrogramEnabled
    }

    private fun spectrogramColor(db: Double): Int {
        val normalized = ((db - dbFloor) / (dbCeiling - dbFloor).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        // Near-black for weak signals, purple for stronger signals, and occasional white for the strongest.
        return when {
            normalized < 0.38 -> {
                val t = normalized / 0.38
                Color.rgb((8 + 34 * t).toInt(), (2 + 4 * t).toInt(), (14 + 48 * t).toInt())
            }
            normalized < 0.68 -> {
                val t = (normalized - 0.38) / 0.30
                Color.rgb((42 + 105 * t).toInt(), (6 + 18 * t).toInt(), (62 + 150 * t).toInt())
            }
            normalized < 0.88 -> {
                val t = (normalized - 0.68) / 0.20
                Color.rgb((147 + 82 * t).toInt(), (24 + 105 * t).toInt(), (212 + 35 * t).toInt())
            }
            else -> {
                val t = (normalized - 0.88) / 0.12
                Color.rgb((229 + 26 * t).toInt(), (129 + 126 * t).toInt(), (247 + 8 * t).toInt())
            }
        }
    }

    private fun drawSpectrogram(
        canvas: Canvas,
        history: List<DoubleArray>,
        sampleRate: Int,
        fftSize: Int,
        minFreq: Double,
        maxFreq: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float
    ) {
        if (history.isEmpty() || right <= left || bottom <= top) return
        // Limit horizontal raster columns to avoid thousands of tiny draw calls per frame.
        // Aggregate the bins that cross each pixel column so narrow tones do not disappear.
        val columns = max(1, (right - left).toInt().coerceAtMost(240))
        val rows = history.size
        val cellWidth = (right - left) / columns
        val cellHeight = (bottom - top) / rows
        val highestAnalyzedFrequency = min(sampleRate / 2.0, 20000.0)
        if (spectrogramColumnFirstBins.size != columns) {
            spectrogramColumnFirstBins = IntArray(columns)
            spectrogramColumnLastBins = IntArray(columns)
        }
        for (column in 0 until columns) {
            val firstRatio = column.toDouble() / columns
            val lastRatio = (column + 1).toDouble() / columns
            val firstFrequency = frequencyAtRatio(firstRatio, minFreq, maxFreq)
            val lastFrequency = frequencyAtRatio(lastRatio, minFreq, maxFreq)
            val bins = spectrogramBinRange(
                firstFrequency,
                lastFrequency,
                sampleRate,
                fftSize,
                highestAnalyzedFrequency
            )
            spectrogramColumnFirstBins[column] = bins?.first ?: -1
            spectrogramColumnLastBins[column] = bins?.last ?: -1
        }

        for (row in history.indices) {
            val frame = history[row]
            if (frame.isEmpty()) continue
            for (column in 0 until columns) {
                val firstBin = spectrogramColumnFirstBins[column]
                val lastBin = spectrogramColumnLastBins[column].coerceAtMost(frame.lastIndex)
                if (firstBin < 0 || lastBin < firstBin) {
                    spectrogramPaint.color = backgroundColor
                } else {
                    var maximumMagnitude = 0.0
                    for (bin in firstBin..lastBin) {
                        val magnitude = frame[bin]
                        if (magnitude.isFinite() && magnitude > maximumMagnitude) maximumMagnitude = magnitude
                    }
                    val normalizedMagnitude = maximumMagnitude * 4.0 / fftSize
                    val magnitudeDb = if (normalizedMagnitude > 1e-8) 20.0 * log10(normalizedMagnitude) else -160.0
                    spectrogramPaint.color = spectrogramColor(magnitudeDb)
                }
                val x0 = left + column * cellWidth
                // Oldest frames are at the top; newest frames are at the bottom.
                val y0 = top + row * cellHeight
                canvas.drawRect(x0, y0, x0 + cellWidth + 1f, y0 + cellHeight + 1f, spectrogramPaint)
            }
        }
    }

    fun togglePeakHold() {
        peakHoldEnabled = !peakHoldEnabled
        if (peakHoldEnabled) {
            peakHoldMagnitudes = copySpectrumUpTo20kHz(magnitudes, sourceSampleRate, sourceFftSize)
        } else {
            peakHoldMagnitudes = DoubleArray(0)
        }
        invalidate()
        notifyControlState()
    }

    fun getVisibleFrequencyRangeHz(): Pair<Int, Int> =
        visibleMinFrequency.roundToInt().coerceAtLeast(0) to visibleMaxFrequency.roundToInt().coerceAtLeast(0)

    fun setFrequencyRange(minFrequencyHz: Int, maxFrequencyHz: Int) {
        val minHz = minFrequencyHz.coerceIn(0, 20000).toDouble().coerceAtLeast(minFrequencyBound)
        val maxHz = maxFrequencyHz.coerceIn(0, 20000).toDouble()
        if (maxHz <= minHz) {
            Toast.makeText(context, "The selected range must be wider than 20 Hz.", Toast.LENGTH_SHORT).show()
            return
        }
        visibleMinFrequency = minHz
        visibleMaxFrequency = maxHz
        notifyFrequencyRange()
        selectedFrequency = null
        selectedSpectrogramXRatio = null
        selectedSpectrogramYRatio = null
        highResolutionFftEnabled = false
        invalidate()
    }

    private fun selectFrequencyAt(touchX: Float) {
        val left = plotLeftInset()
        val right = plotRightEdge()
        if (right <= left || touchX < left || touchX > right) return
        val ratio = ((touchX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        val selected = frequencyAtRatio(ratio, visibleMinFrequency, visibleMaxFrequency)
        selectedFrequency = if (selected in minFrequencyBound..20000.0) selected else null
        invalidate()
    }

    private fun selectSpectrogramAt(touchX: Float, touchY: Float) {
        val left = plotLeftInset()
        val right = plotRightEdge()
        val top = plotTopInset()
        val bottom = height.toFloat() - plotBottomInset()
        if (right <= left || bottom <= top || touchX < left || touchX > right || touchY < top || touchY > bottom) return
        val frequencyRatio = ((touchX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        val selectedHz = frequencyAtRatio(frequencyRatio, visibleMinFrequency, visibleMaxFrequency)
        if (selectedHz !in minFrequencyBound..20000.0) {
            selectedSpectrogramXRatio = null
            selectedSpectrogramYRatio = null
            invalidate()
            return
        }
        selectedSpectrogramXRatio = frequencyRatio
        val historyRatio = (touchY - top - spectrogramPanOffsetY) / (bottom - top)
        if (historyRatio !in 0f..1f) return
        selectedSpectrogramYRatio = historyRatio.toDouble()
        selectedFrequency = null
        invalidate()
    }

    fun resetView(preserveFrequencyRange: Boolean = false, useKeysFrequencyRange: Boolean = false) {
        // Invalidate any FFT frame that was already being calculated before reset.
        resetGeneration++
        if (!preserveFrequencyRange) {
            if (useKeysFrequencyRange && dbRangeMode == "KEYS") {
                visibleMinFrequency = 65.0
                visibleMaxFrequency = 12500.0
            } else {
                visibleMinFrequency = minFrequencyBound
                visibleMaxFrequency = 20000.0
            }
            linearFrequencyAxis = false
        }
        peakHoldEnabled = false
        peakHoldMagnitudes = DoubleArray(0)
        synchronized(spectrogramHistory) { spectrogramHistory.clear() }
        selectedFrequency = null
        selectedSpectrogramXRatio = null
        selectedSpectrogramYRatio = null
        spectrogramPanOffsetY = 0f
        dbCeiling = -30
        dbFloor = when (dbRangeMode) {
            "TONE" -> -135
              "KEYS", "SOUND" -> -140
            else -> -150
        }
        highResolutionFftEnabled = false
        sourceFftSize = 8192
        peakFrequency = 0.0

        // Discard the previous smoothed spectrum so the next FFT frame sets the
        // automatic dB range without blending in the previous graph's peak level.
        smoothedMagnitudes = DoubleArray(0)
        magnitudes = DoubleArray(0)
        peakFrequency = 0.0

        // Keep the same fixed vertical range as a fresh analysis session.
        initialAutoRangePending = false
        invalidate()
        notifyControlState()
        notifyFrequencyRange()
    }

    /** Exact log spacing for physical frequencies, with a linear empty-grid extension below 20 Hz. */
    private fun logAxisPosition(frequency: Double): Double =
        if (frequency >= minFrequencyBound) ln(frequency / minFrequencyBound)
        else frequency / minFrequencyBound - 1.0

    private fun frequencyAtLogAxisPosition(position: Double): Double =
        if (position >= 0.0) minFrequencyBound * exp(position.coerceAtMost(700.0))
        else minFrequencyBound * (position + 1.0)

    // LOG uses equal spacing per frequency ratio; LIN uses equal spacing per Hz.
    private fun frequencyRatio(frequency: Double, minFreq: Double, maxFreq: Double): Double {
        if (linearFrequencyAxis) return (frequency - minFreq) / (maxFreq - minFreq)
        val low = logAxisPosition(minFreq)
        val high = logAxisPosition(maxFreq)
        return (logAxisPosition(frequency) - low) / (high - low).coerceAtLeast(1e-12)
    }

    private fun frequencyAtRatio(ratio: Double, minFreq: Double, maxFreq: Double): Double {
        if (linearFrequencyAxis) return minFreq + ratio * (maxFreq - minFreq)
        val low = logAxisPosition(minFreq)
        val high = logAxisPosition(maxFreq)
        return frequencyAtLogAxisPosition(low + ratio.coerceIn(0.0, 1.0) * (high - low))
            .coerceIn(minFreq, maxFreq)
    }

    private fun zoomAt(focusX: Float, scaleFactor: Float) {
        if (spectrogramEnabled) return
        if (!scaleFactor.isFinite() || scaleFactor <= 0f || width <= 0) return
        val left = plotLeftInset()
        val right = plotRightEdge()
        if (right <= left) return
        val focusRatio = ((focusX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        val oldMin = visibleMinFrequency
        val oldMax = visibleMaxFrequency
        val anchorFrequency = frequencyAtRatio(focusRatio, oldMin, oldMax)
        val oldMinAxis = if (linearFrequencyAxis) oldMin else logAxisPosition(oldMin)
        val oldMaxAxis = if (linearFrequencyAxis) oldMax else logAxisPosition(oldMax)
        val oldSpan = (oldMaxAxis - oldMinAxis).coerceAtLeast(1e-12)
        val minSpan = if (linearFrequencyAxis) 10.0 else minLogSpan
        val maxSpan = if (linearFrequencyAxis) maxFrequencyBound - minFrequencyBound else maxLogSpan
        val newSpan = (oldSpan / scaleFactor.toDouble()).coerceIn(minSpan, maxSpan)
        // Keep the frequency under the pinch midpoint at the same screen position.
        val anchorAxisPosition = if (linearFrequencyAxis) anchorFrequency else logAxisPosition(anchorFrequency)
        val newMinAxis = anchorAxisPosition - focusRatio * newSpan
        val newMaxAxis = newMinAxis + newSpan
        visibleMinFrequency = if (linearFrequencyAxis) newMinAxis else frequencyAtLogAxisPosition(newMinAxis)
        visibleMaxFrequency = if (linearFrequencyAxis) newMaxAxis else frequencyAtLogAxisPosition(newMaxAxis)
        notifyFrequencyRange()
        invalidate()
    }

    private fun panBy(dx: Float, dy: Float) {
        // Waterfall grid stays fixed; only RANGE and LIN/LOG controls change its scale.
        if (spectrogramEnabled) {
            invalidate()
            return
        }
        // Horizontal dragging pans the frequency range; vertical dragging pans the
        // visible dB window without changing its height (dB span).
        val plotWidth = (plotRightEdge() - plotLeftInset()).coerceAtLeast(1f)
        val fraction = (-dx / plotWidth).toDouble()
        val frequencySpan = if (linearFrequencyAxis) visibleMaxFrequency - visibleMinFrequency
        else logAxisPosition(visibleMaxFrequency) - logAxisPosition(visibleMinFrequency)
        val frequencyShift = fraction * frequencySpan
        if (linearFrequencyAxis) {
            visibleMinFrequency += frequencyShift
            visibleMaxFrequency += frequencyShift
        } else {
            visibleMinFrequency = frequencyAtLogAxisPosition(logAxisPosition(visibleMinFrequency) + frequencyShift)
            visibleMaxFrequency = frequencyAtLogAxisPosition(logAxisPosition(visibleMaxFrequency) + frequencyShift)
        }

        // Keep the current dB range size fixed while moving it vertically.
        // The default/reset floor remains -130 dB, but panning can move the
        // visible window below that value so a zoomed trace can be repositioned.
        val plotHeight = (height.toFloat() - plotTopInset() - plotBottomInset()).coerceAtLeast(1f)
        val dbSpan = (dbCeiling - dbFloor).coerceAtLeast(20)
        val dbShift = (dy / plotHeight * dbSpan.toFloat()).roundToInt()
        if (dbShift != 0) {
            val newFloor = dbFloor + dbShift
            dbFloor = newFloor
            dbCeiling = newFloor + dbSpan
            notifyControlState()
        }
        invalidate()
        notifyFrequencyRange()
    }

    private data class Peak(
        val bin: Int,
        val frequency: Double,
        val db: Double
    )

    private fun magnitudeToDb(magnitude: Double, fftSize: Int): Double {
        val normalized = magnitude * 4.0 / fftSize
        return if (normalized > 1e-12) 20.0 * log10(normalized) else -100.0
    }

    // Keep only spectrum bins at or below 20 kHz in stored traces.
    private fun copySpectrumUpTo20kHz(data: DoubleArray, sampleRate: Int, fftSize: Int): DoubleArray {
        val copy = data.copyOf()
        val binWidth = sampleRate.toDouble() / fftSize
        for (bin in copy.indices) {
            if (bin * binWidth > 20000.0) copy[bin] = 0.0
        }
        return copy
    }

    private fun drawSpectrumTrace(
        canvas: Canvas,
        data: DoubleArray,
        sampleRate: Int,
        fftSize: Int,
        minFreq: Double,
        maxFreq: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        displayDbFloor: Double,
        displayDbCeiling: Double,
        paint: Paint,
        path: Path
    ) {
        if (data.isEmpty() || right <= left || minFreq >= 20000.0) return
        val binWidth = sampleRate.toDouble() / fftSize
        val pixels = max(1, (right - left).toInt())
        path.reset()
        var started = false

        for (pixel in 0..pixels) {
            val x = left + (right - left) * pixel.toFloat() / pixels
            val ratio = (x - left) / (right - left)
            val frequency = frequencyAtRatio(ratio.toDouble(), minFreq, maxFreq)
            // Stop at 20 kHz. The remaining 20–40 kHz area is display-only.
            if (frequency > 20000.0) break
            val binPosition = frequency / binWidth
            val lowerBin = binPosition.toInt()
            if (lowerBin <= 0 || lowerBin >= data.lastIndex) continue
            val fraction = (binPosition - lowerBin).coerceIn(0.0, 1.0)
            // Interpolate magnitudes between bins to avoid a blocky, stair-stepped trace when zoomed.
            val interpolatedMagnitude = data[lowerBin] * (1.0 - fraction) + data[lowerBin + 1] * fraction
            val db = magnitudeToDb(interpolatedMagnitude, fftSize).coerceIn(displayDbFloor, displayDbCeiling)
            val dbSpan = (displayDbCeiling - displayDbFloor).coerceAtLeast(1.0)
            val y = top + ((displayDbCeiling - db) / dbSpan).toFloat() * (bottom - top)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        if (started) canvas.drawPath(path, paint)
    }

    private fun findTopPeaks(
        data: DoubleArray,
        sampleRate: Int,
        fftSize: Int,
        minFreq: Double,
        maxFreq: Double
    ): List<Peak> {
        if (data.size < 5) return emptyList()
        val binWidth = sampleRate.toDouble() / fftSize
        val firstBin = max(2, kotlin.math.ceil(minFreq / binWidth).toInt())
        // Do not report peaks above 20 kHz, even though the display extends to 40 kHz.
        val lastBin = min(data.lastIndex - 1, kotlin.math.floor(min(maxFreq, 20000.0) / binWidth).toInt())
        if (lastBin <= firstBin) return emptyList()

        fun shoulderLevelDb(startBin: Int, endBin: Int): Double? {
            if (endBin < startBin) return null
            var powerSum = 0.0
            var count = 0
            for (bin in startBin..endBin) {
                val magnitude = data[bin]
                if (magnitude.isFinite()) {
                    powerSum += magnitude * magnitude
                    count++
                }
            }
            if (count == 0) return null
            return magnitudeToDb(sqrt(powerSum / count), fftSize)
        }

        val candidates = ArrayList<Peak>()
        for (bin in firstBin..lastBin) {
            val magnitude = data[bin]
            if (!magnitude.isFinite() || magnitude <= 0.0) continue
            if (magnitude < data[bin - 1] || magnitude <= data[bin + 1]) continue

            val db = magnitudeToDb(magnitude, fftSize)
            if (db <= -95.0) continue

            // A local maximum alone is often just FFT noise or leakage. Require
            // at least 6 dB of prominence over the RMS level on both shoulders,
            // outside the Hann-window main lobe (about 3–8 FFT bins away).
            val leftFloor = shoulderLevelDb(max(firstBin, bin - 8), bin - 3) ?: continue
            val rightFloor = shoulderLevelDb(bin + 3, min(lastBin, bin + 8)) ?: continue
            if (db - max(leftFloor, rightFloor) < 6.0) continue

            // Refine the displayed frequency with a three-bin parabolic fit in
            // log magnitude, which is more accurate than labeling the bin center.
            val leftLog = ln(max(data[bin - 1], 1e-15))
            val centerLog = ln(max(magnitude, 1e-15))
            val rightLog = ln(max(data[bin + 1], 1e-15))
            val curvature = leftLog - 2.0 * centerLog + rightLog
            val binOffset = if (curvature < -1e-12) {
                (0.5 * (leftLog - rightLog) / curvature).coerceIn(-0.5, 0.5)
            } else {
                0.0
            }
            val peakFrequency = (bin + binOffset) * binWidth
            if (peakFrequency in minFreq..min(maxFreq, 20000.0)) {
                candidates.add(Peak(bin, peakFrequency, db))
            }
        }

        candidates.sortByDescending { it.db }
        val selected = ArrayList<Peak>(4)
        for (candidate in candidates) {
            // Keep one marker per resolvable peak; scale spacing with FFT
            // resolution and frequency so neighboring bins do not become labels.
            val minimumSeparationHz = max(binWidth * 5.0, candidate.frequency * 0.002)
            if (selected.none { kotlin.math.abs(it.frequency - candidate.frequency) < minimumSeparationHz }) {
                selected.add(candidate)
                if (selected.size == 4) break
            }
        }
        return selected
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val h = height.toFloat()
        val density = resources.displayMetrics.density
        canvas.drawColor(backgroundColor)

        // Respect the camera cutout on either landscape edge.
        val left = plotLeftInset()
        val right = plotRightEdge()
        val top = plotTopInset()
        val bottom = h - plotBottomInset()
        if (right <= left || bottom <= top) return

        // Keep the X-axis line out of the graph: it should not remain fixed when
        // the spectrum is panned vertically beyond the plot viewport.

        val minFreq = visibleMinFrequency
        val maxFreq = visibleMaxFrequency
        if (maxFreq <= minFreq) return

        fun xForFrequency(freq: Double): Float {
            val ratio = frequencyRatio(freq, minFreq, maxFreq)
            return left + (right - left) * ratio.toFloat()
        }

        if (linearFrequencyAxis) {
            // Use a readable 1/2/5 sequence whose density adapts to the
            // visible range and screen width instead of drawing every custom tick.
            val axisStart = minFreq
            val axisEnd = maxFreq
            val span = axisEnd - axisStart
            val density = resources.displayMetrics.density
            val targetMajorCount = ((right - left) / (90f * density)).roundToInt().coerceIn(4, 10)
            val rawMajorStep = span / targetMajorCount
            val stepMagnitude = 10.0.pow(floor(log10(rawMajorStep.coerceAtLeast(1e-9))))
            val normalizedStep = rawMajorStep / stepMagnitude
            val majorStep = (when {
                normalizedStep <= 1.0 -> 1.0
                normalizedStep <= 2.0 -> 2.0
                normalizedStep <= 5.0 -> 5.0
                else -> 10.0
            }) * stepMagnitude
            val majorSpacingDp = (right - left) * (majorStep / span) / density
            val subdivisions = if (majorSpacingDp >= 110f) 2L else 1L
            val gridStep = majorStep / subdivisions
            val firstTick = floor(axisStart / gridStep).toLong()
            val lastTick = ceil(axisEnd / gridStep).toLong()
            var tick = firstTick
            var drawnLines = 0
            while (tick <= lastTick && drawnLines < 180) {
                val position = tick * gridStep
                val isMajor = tick % subdivisions == 0L
                val x = left + (right - left) * ((position - axisStart) / span).toFloat()
                canvas.drawLine(x, top, x, bottom, if (isMajor) gridPaint else minorGridPaint)
                if (isMajor && position > 0.0 && position <= 20000.0) {
                    val label = if (position >= 1000.0) {
                        val kilo = position / 1000.0
                        val roundedKilo = kilo.roundToInt().toDouble()
                        if (kotlin.math.abs(kilo - roundedKilo) < 0.01) {
                            "${roundedKilo.roundToInt()}k"
                        } else {
                            String.format(java.util.Locale.US, "%.1fk", kilo)
                        }
                    } else if (kotlin.math.abs(position - position.roundToInt()) < 0.01) {
                        position.roundToInt().toString()
                    } else {
                        String.format(java.util.Locale.US, "%.1f", position)
                    }
                    val labelWidth = textPaint.measureText(label)
                    val labelX = (x - labelWidth / 2f).coerceIn(left, max(left, right - labelWidth))
                    canvas.drawText(label, labelX, bottom + 11f * density, textPaint)
                }
                tick++
                drawnLines++
            }
        } else {
            val axisStart = logAxisPosition(minFreq)
            val axisEnd = logAxisPosition(maxFreq)
            val axisSpan = (axisEnd - axisStart).coerceAtLeast(1e-12)
            fun drawLogGridLine(position: Double, label: String? = null) {
                if (position < axisStart || position > axisEnd) return
                val x = left + (right - left) * ((position - axisStart) / axisSpan).toFloat()
                canvas.drawLine(x, top, x, bottom, gridPaint)
                if (label != null) {
                    val labelX = (x - textPaint.measureText(label) / 2f)
                        .coerceIn(left, max(left, right - textPaint.measureText(label)))
                    canvas.drawText(label, labelX, bottom + 11f * density, textPaint)
                }
            }

            // Keep the empty, sub-20 Hz/negative-frequency grid pannable without
            // inventing frequency labels in a region where the microphone has no data.
            if (axisStart < 0.0) {
                val negativeEnd = min(axisEnd, 0.0)
                val targetLines = ((right - left) / (32f * resources.displayMetrics.density))
                    .roundToInt().coerceAtLeast(1)
                val step = max(1.0, ceil((negativeEnd - axisStart) / targetLines.toDouble()))
                var position = ceil(axisStart / step) * step
                var drawn = 0
                while (position <= negativeEnd && drawn < targetLines + 2) {
                    drawLogGridLine(position)
                    position += step
                    drawn++
                }
            }

            // Standard logarithmic decade lines (1–9 × 10ⁿ); label the 1, 2 and 5 lines.
            val positiveMin = max(minFrequencyBound, minFreq)
            val positiveMax = max(minFrequencyBound, maxFreq)
            val firstDecade = (floor(log10(positiveMin)) - 1.0).toInt().coerceIn(-300, 300)
            val lastDecade = (ceil(log10(positiveMax)) + 1.0).toInt().coerceIn(-300, 300)
            for (decade in firstDecade..lastDecade) {
                val base = 10.0.pow(decade)
                for (multiplier in 1..9) {
                    val frequency = multiplier.toDouble() * base
                    if (frequency < minFrequencyBound || frequency < minFreq || frequency > maxFreq) continue
                    val label = if (multiplier == 1 || multiplier == 2 || multiplier == 5) {
                        when {
                            frequency >= 1000.0 -> {
                                val kilo = frequency / 1000.0
                                if (kilo == kilo.roundToInt().toDouble()) "${kilo.roundToInt()}k"
                                else String.format(java.util.Locale.US, "%.1fk", kilo)
                            }
                            else -> frequency.roundToInt().toString()
                        }
                    } else null
                    drawLogGridLine(logAxisPosition(frequency), label)
                }
            }
        }

        // Ease the 20 Hz endpoint label into view while the axis is animating out
        // of KEYS. The normal tick takes over at the end of the transition.
        if (!linearFrequencyAxis) {
            val transition = frequencyAxisTransition
            if (transition != null) {
                val (startRange, targetRange, progress) = transition
                val startsAt20 = startRange.first <= 20.001 && startRange.second >= 20.0
                val endsAt20 = targetRange.first <= 20.001 && targetRange.second >= 20.0
                val fade = when {
                    endsAt20 && startRange.first > 20.001 && minFreq > 20.001 && progress > 0.76f ->
                        ((progress - 0.76f) / 0.22f).coerceIn(0f, 1f)
                    startsAt20 && targetRange.first > 20.001 && minFreq > 20.001 && progress < 0.22f ->
                        (1f - progress / 0.22f).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (fade > 0f) {
                    val oldAlpha = textPaint.alpha
                    textPaint.alpha = (oldAlpha * fade).roundToInt().coerceIn(0, 255)
                    canvas.drawText("20", left, bottom + 11f * density, textPaint)
                    textPaint.alpha = oldAlpha
                }
            }
        }

        val displayDbRange = dbAxisTransitionRange
        val displayDbFloor = displayDbRange?.first ?: dbFloor.toDouble()
        val displayDbCeiling = displayDbRange?.second ?: dbCeiling.toDouble()
        fun yForDb(db: Double): Float {
            val clampedDb = db.coerceIn(displayDbFloor, displayDbCeiling)
            val ratio = ((displayDbCeiling - clampedDb) / (displayDbCeiling - displayDbFloor)).toFloat()
            return top + ratio * (bottom - top)
        }

        val historySnapshot = if (spectrogramEnabled) {
            synchronized(spectrogramHistory) { spectrogramHistory.toList() }
        } else {
            emptyList()
        }

        if (spectrogramEnabled) {
            // In waterfall mode, vertical position represents time rather than dB level.
            val historySeconds = historySnapshot.size * (sourceFftSize.toDouble() / 4.0 / sourceSampleRate.coerceAtLeast(1))
            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            canvas.translate(0f, spectrogramPanOffsetY)
            drawSpectrogram(
                canvas, historySnapshot, sourceSampleRate, sourceFftSize,
                minFreq, maxFreq, left, right, top, bottom
            )
            canvas.restore()
            val plotHeight = bottom - top
            for (i in 0..4) {
                val ratio = i / 4f
                val originalY = top + plotHeight * ratio + spectrogramPanOffsetY
                val firstCycle = kotlin.math.floor((top - originalY) / plotHeight).toInt() - 1
                val lastCycle = kotlin.math.ceil((bottom - originalY) / plotHeight).toInt() + 1
                for (cycle in firstCycle..lastCycle) {
                    val y = originalY + cycle * plotHeight
                    if (y !in top..bottom) continue
                    canvas.drawLine(left, y, right, y, gridPaint)
                    if (cycle == 0) {
                        val secondsAgo = historySeconds * (1.0 - ratio)
                        val label = if (i == 4) "0 s" else String.format(java.util.Locale.US, "%.1f s", secondsAgo)
                        canvas.drawText(label, axisLabelRight(), y + 3f * density, dbAxisTextPaint)
                    }
                }
            }
            canvas.drawText("TIME", axisLabelRight(), max(10f * density, top - 10f * density), dbAxisUnitPaint)
        } else {
            val axisX = right
            val dbTickStep = if (displayDbCeiling - displayDbFloor > 160.0) 20 else 10
            var dbTick = kotlin.math.floor(displayDbCeiling / dbTickStep).toInt() * dbTickStep
            canvas.drawLine(axisX, top, axisX, bottom, dbAxisPaint)
            while (dbTick >= displayDbFloor) {
                val y = yForDb(dbTick.toDouble())
                canvas.drawLine(left, y, right, y, gridPaint)
                canvas.drawLine(axisX - 4f, y, axisX + 3f, y, dbAxisPaint)
                canvas.drawText(dbTick.toString(), axisLabelRight(), y + 3f * density, dbAxisTextPaint)
                dbTick -= dbTickStep
            }
            // TONE ends at -135, KEYS at -140. Do not wait until the final
            // frame to add/remove the -140 label: project it with the same moving
            // axis as other ticks, even while just below the plot, and blend it.
            dbAxisTransition?.let { (startRange, targetRange, progress) ->
                val entersKeys = startRange.first == -135 && targetRange.first == -140
                val leavesKeys = startRange.first == -140 && targetRange.first == -135
                if ((entersKeys || leavesKeys) && displayDbFloor > -140.0) {
                    val y = top + ((displayDbCeiling + 140.0) /
                        (displayDbCeiling - displayDbFloor)).toFloat() * (bottom - top)
                    val opacity = if (entersKeys) progress else 1f - progress
                    val oldAlpha = dbAxisTextPaint.alpha
                    dbAxisTextPaint.alpha = (oldAlpha * opacity).roundToInt().coerceIn(0, 255)
                    canvas.drawText("-140", axisLabelRight(), y + 3f * density, dbAxisTextPaint)
                    dbAxisTextPaint.alpha = oldAlpha
                }
            }
            // Fade the restored -150 dBFS label in near the end of KEYS -> Spectrum,
            // rather than letting a new endpoint tick pop into view on the last frame.
            dbAxisTransition?.let { (startRange, targetRange, progress) ->
                val fadesIn = startRange.first > -150 && targetRange.first <= -150
                val fadesOut = startRange.first <= -150 && targetRange.first > -150
                val fade = when {
                    fadesIn && displayDbFloor > -150.0 && progress > 0.76f ->
                        ((progress - 0.76f) / 0.22f).coerceIn(0f, 1f)
                    fadesOut && displayDbFloor > -150.0 && progress < 0.22f ->
                        (1f - progress / 0.22f).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (fade > 0f) {
                    val oldAlpha = dbAxisTextPaint.alpha
                    dbAxisTextPaint.alpha = (oldAlpha * fade).roundToInt().coerceIn(0, 255)
                    canvas.drawText("-150", axisLabelRight(), bottom + 3f * density, dbAxisTextPaint)
                    dbAxisTextPaint.alpha = oldAlpha
                }
            }
            canvas.drawText("dBFS", axisLabelRight(), top - 10f * density, dbAxisUnitPaint)
        }

        val data = magnitudes
        if (data.isEmpty()) {
            canvas.drawText("Waiting for audio...", left + 20f, top + 50f * density, textPaint)
            return
        }

        if (spectrogramEnabled) {
            // Draw the frequency/time grid over the waterfall so it remains readable.
            // Tapping the waterfall selects a frequency and time. Show a vertical
            // cursor so the selected frequency is easy to follow across the timeline.
            val selectedXRatio = selectedSpectrogramXRatio
            val selectedYRatio = selectedSpectrogramYRatio
            if (selectedXRatio != null && selectedYRatio != null && historySnapshot.isNotEmpty()) {
                val cursorY = top + (bottom - top) * selectedYRatio.toFloat() + spectrogramPanOffsetY
                val cursorX = left + (right - left) * selectedXRatio.toFloat()
                canvas.drawLine(cursorX, top, cursorX, bottom, cursorLinePaint)
                canvas.drawCircle(cursorX, cursorY, 4f, cursorPointPaint)

                val row = (selectedYRatio * (historySnapshot.size - 1)).roundToInt().coerceIn(0, historySnapshot.lastIndex)
                val frame = historySnapshot[row]
                val selectedHz = frequencyAtRatio(selectedXRatio, minFreq, maxFreq).coerceIn(20.0, 20000.0)
                val bin = (selectedHz * sourceFftSize / sourceSampleRate.coerceAtLeast(1)).roundToInt().coerceIn(0, frame.lastIndex.coerceAtLeast(0))
                val magnitude = if (frame.isNotEmpty()) frame[bin] * 4.0 / sourceFftSize else 0.0
                val levelDb = if (magnitude > 1e-8) 20.0 * log10(magnitude) else -160.0
                val historySeconds = historySnapshot.size * (sourceFftSize.toDouble() / 4.0 / sourceSampleRate.coerceAtLeast(1))
                val secondsAgo = historySeconds * (1.0 - selectedYRatio)
                val frequencyLabel = if (selectedHz >= 1000.0) {
                    String.format(java.util.Locale.US, "%.2f kHz", selectedHz / 1000.0)
                } else {
                    String.format(java.util.Locale.US, "%.1f Hz", selectedHz)
                }
                val label = "$frequencyLabel   ${String.format(java.util.Locale.US, "%.1f", levelDb)} dBFS   ${String.format(java.util.Locale.US, "%.1f", secondsAgo)} s"
                val horizontalPadding = 10f
                val verticalPadding = 7f
                val labelWidth = cursorTextPaint.measureText(label)
                val boxWidth = labelWidth + horizontalPadding * 2f
                val boxHeight = cursorTextPaint.textSize + verticalPadding * 2f
                val boxLeft = (left - 12f).coerceAtMost(max(0f, right - boxWidth)).coerceAtLeast(0f)
                val boxTop = max(0f, top - 14f)
                val infoRect = android.graphics.RectF(boxLeft, boxTop, min(right, boxLeft + boxWidth), boxTop + boxHeight)
                canvas.drawRoundRect(infoRect, 6f, 6f, cursorInfoBackgroundPaint)
                canvas.drawRoundRect(infoRect, 6f, 6f, cursorInfoBorderPaint)
                canvas.drawText(label, boxLeft + horizontalPadding, boxTop + verticalPadding + cursorTextPaint.textSize * 0.82f, cursorTextPaint)
            }
            return
        }

        // The live spectrum is white; the permanent Peak Hold trace is red.
        drawSpectrumTrace(
            canvas, data, sourceSampleRate, sourceFftSize, minFreq, maxFreq,
            left, right, top, bottom, displayDbFloor, displayDbCeiling, spectrumPaint, spectrumPath
        )

        if (peakHoldEnabled && peakHoldMagnitudes.isNotEmpty()) {
            drawSpectrumTrace(
                canvas, peakHoldMagnitudes, sourceSampleRate, sourceFftSize, minFreq, maxFreq,
                left, right, top, bottom, displayDbFloor, displayDbCeiling, peakHoldPaint, peakHoldPath
            )
        }

        // Mark and label the four strongest distinct peaks in the visible frequency range.
        val peaks = findTopPeaks(data, sourceSampleRate, sourceFftSize, minFreq, maxFreq)
        val placedLabels = ArrayList<Pair<Float, Float>>()
        for ((index, peak) in peaks.withIndex()) {
            val peakX = xForFrequency(peak.frequency)
            val peakY = yForDb(peak.db)
            canvas.drawCircle(peakX, peakY, 5.5f, peakPointPaint)

            val frequencyLabel = if (peak.frequency >= 1000.0) {
                String.format(java.util.Locale.US, "%.2f kHz", peak.frequency / 1000.0)
            } else {
                String.format(java.util.Locale.US, "%.1f Hz", peak.frequency)
            }
            val label = frequencyLabel
            val labelWidth = peakTextPaint.measureText(label)
            val labelX = (peakX - labelWidth / 2f).coerceIn(left, max(left, right - labelWidth))
            var labelY = max(top + 18f, peakY - 13f - index * 2f)

            // Keep nearby peak labels from occupying exactly the same position.
            for ((previousX, previousY) in placedLabels) {
                if (kotlin.math.abs(previousX - labelX) < labelWidth && kotlin.math.abs(previousY - labelY) < 24f) {
                    labelY = max(top + 18f, labelY - 25f)
                }
            }
            placedLabels.add(labelX to labelY)
            canvas.drawText(label, labelX, labelY, peakTextPaint)
        }

        // Keep the interpolated dominant peak frequency visible as a vertical guide.
        val peakHz = peakFrequency
        if (peakHz in minFreq..min(maxFreq, 20000.0) && peakHz > 0.0) {
            val peakX = xForFrequency(peakHz)
            canvas.drawLine(peakX, top, peakX, bottom, peakLinePaint)
        }

        // A short tap pins a cursor to a frequency; its level follows the live spectrum.
        val cursorHz = selectedFrequency
        if (cursorHz != null && cursorHz in minFreq..min(maxFreq, 20000.0)) {
            val binWidth = sourceSampleRate.toDouble() / sourceFftSize
            val binPosition = cursorHz / binWidth
            val lowerBin = binPosition.toInt().coerceIn(0, data.lastIndex)
            val upperBin = (lowerBin + 1).coerceAtMost(data.lastIndex)
            val fraction = (binPosition - lowerBin).coerceIn(0.0, 1.0)
            val magnitude = data[lowerBin] * (1.0 - fraction) + data[upperBin] * fraction
            val levelDb = magnitudeToDb(magnitude, sourceFftSize)
            val cursorX = xForFrequency(cursorHz)
            val cursorY = yForDb(levelDb)
            canvas.drawLine(cursorX, top, cursorX, bottom, cursorLinePaint)
            canvas.drawCircle(cursorX, cursorY, 6f, cursorPointPaint)
            val frequencyLabel = if (cursorHz >= 1000.0) {
                String.format(java.util.Locale.US, "%.2f kHz", cursorHz / 1000.0)
            } else {
                String.format(java.util.Locale.US, "%.1f Hz", cursorHz)
            }
            val label = "$frequencyLabel   ${String.format(java.util.Locale.US, "%.1f", levelDb)} dBFS"
            val horizontalPadding = 10f
            val verticalPadding = 7f
            val labelWidth = cursorTextPaint.measureText(label)
            val boxWidth = labelWidth + horizontalPadding * 2f
            val boxHeight = cursorTextPaint.textSize + verticalPadding * 2f

            // Keep the selected frequency and level in a fixed, high-contrast panel
            // aligned with the top edge of the graph grid.
            val boxLeft = (left - 12f).coerceAtMost(max(0f, right - boxWidth)).coerceAtLeast(0f)
            val boxTop = max(0f, top - 14f)
            val boxRight = min(right, boxLeft + boxWidth)
            val boxBottom = boxTop + boxHeight
            val infoRect = android.graphics.RectF(boxLeft, boxTop, boxRight, boxBottom)
            canvas.drawRoundRect(infoRect, 6f, 6f, cursorInfoBackgroundPaint)
            canvas.drawRoundRect(infoRect, 6f, 6f, cursorInfoBorderPaint)
            canvas.drawText(
                label,
                boxLeft + horizontalPadding,
                boxTop + verticalPadding + cursorTextPaint.textSize * 0.82f,
                cursorTextPaint
            )
        }
    }
}
