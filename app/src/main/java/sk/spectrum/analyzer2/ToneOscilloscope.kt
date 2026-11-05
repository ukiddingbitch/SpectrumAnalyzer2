package sk.spectrum.analyzer2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Oscilloscope rendering, triggering, sinc reconstruction and PCM measurement helpers. */
/** Adjustable-timebase scope: actual generated PCM during playback, modeled signal while stopped. */
internal data class ToneScopeMeasurement(val rms: Double, val peak: Double, val frequencyHz: Double?)

/** Measurements use signed 16-bit full scale, independently of UI size or generator settings. */
internal fun measureToneScope(samples: ShortArray, count: Int, rate: Int, periodic: Boolean): ToneScopeMeasurement {
    val n = count.coerceIn(0, samples.size)
    if (n == 0 || rate <= 0) return ToneScopeMeasurement(0.0, 0.0, null)
    var sum = 0.0; var squares = 0.0; var peak = 0.0
    for (i in 0 until n) {
        val value = samples[i].toDouble()
        sum += value; squares += value * value; peak = maxOf(peak, abs(value))
    }
    var frequency: Double? = null
    if (periodic && peak > 8 && n >= 8) {
        val mean = sum / n
        var armed = false
        var first = 0.0; var last = 0.0; var previous = 0.0
        var crossings = 0; var shortest = Double.POSITIVE_INFINITY; var longest = 0.0
        for (i in 1 until n) {
            val before = samples[i - 1] - mean; val after = samples[i] - mean
            if (before < -peak * 0.05) armed = true
            if (armed && before <= 0 && after > 0) {
                val at = i - 1 + (-before / (after - before))
                if (crossings == 0) first = at else {
                    val period = at - previous
                    shortest = minOf(shortest, period); longest = maxOf(longest, period)
                }
                last = at; previous = at; crossings++; armed = false
            }
        }
        if (crossings >= 3 && last > first) {
            val period = (last - first) / (crossings - 1)
            if (longest - shortest <= maxOf(1.1, period * 0.08)) frequency = rate / period
        }
    }
    return ToneScopeMeasurement(kotlin.math.sqrt(squares / n) / 32768.0, peak / 32768.0, frequency)
}

internal fun toneScopeTriggerStart(samples: ShortArray, count: Int, window: Int, search: Int): Double? {
    val latest = count.coerceAtMost(samples.size) - window
    if (latest < 1 || search <= 0) return null
    for (i in latest downTo maxOf(1, latest - search)) {
        val a = samples[i - 1].toDouble(); val b = samples[i].toDouble()
        if (a <= 0 && b > 0) return i - 1 + (-a / (b - a))
    }
    return null
}

/** Windowed-sinc estimate of a band-limited waveform. Original PCM is never modified. */
internal object ToneScopeSinc {
    const val radius = 32
    private const val phaseCount = 1024
    private val coefficients = Array(phaseCount + 1) { phase ->
        val fraction = phase.toDouble() / phaseCount
        DoubleArray(radius * 2).also { weights ->
            var sum = 0.0
            for (tap in weights.indices) {
                val distance = fraction - (tap - radius + 1)
                val weight = if (abs(distance) >= radius) 0.0 else {
                    val sinc = if (abs(distance) < 1e-12) 1.0 else sin(PI * distance) / (PI * distance)
                    sinc * (0.5 + 0.5 * cos(PI * distance / radius))
                }
                weights[tap] = weight; sum += weight
            }
            if (abs(sum) > 1e-12) for (tap in weights.indices) weights[tap] /= sum
        }
    }
    fun valueAt(samples: ShortArray, count: Int, position: Double): Double? {
        if (!position.isFinite()) return null
        val center = kotlin.math.floor(position).toInt()
        val fraction = position - center
        val available = count.coerceAtMost(samples.size)
        if (center < 0 || center >= available) return null
        if (fraction < 1e-10) return samples[center] / 32768.0
        val first = center - radius + 1
        if (first < 0 || first + radius * 2 > available) return null
        val phase = fraction * phaseCount
        val phaseIndex = phase.toInt().coerceIn(0, phaseCount - 1)
        val mix = phase - phaseIndex
        val before = coefficients[phaseIndex]; val after = coefficients[phaseIndex + 1]
        var sum = 0.0
        for (tap in before.indices) sum += samples[first + tap] * (before[tap] + (after[tap] - before[tap]) * mix)
        return sum / 32768.0
    }

    fun refineRisingZero(samples: ShortArray, count: Int, crossing: Double): Double {
        var left = kotlin.math.floor(crossing)
        var right = left + 1.0
        val a = valueAt(samples, count, left) ?: return crossing
        val b = valueAt(samples, count, right) ?: return crossing
        if (a > 0 || b <= 0) return crossing
        if (a == 0.0) return left
        repeat(20) {
            val mid = (left + right) * 0.5
            val value = valueAt(samples, count, mid) ?: return crossing
            if (value <= 0) left = mid else right = mid
        }
        return (left + right) * 0.5
    }
}

internal class ToneSignalPreviewView(
    context: Context,
    private val sampleRate: Int,
    private val pcmFrame: TonePcmFrame
) : View(context) {
    private val maxScopeWindowSamples = (sampleRate * 0.200).roundToInt() + 1
    private val renderPcmSamples = ShortArray((sampleRate * 0.4).roundToInt() + 1)
    var reconstructionEnabled = true
        set(value) { field = value; invalidate() }
    private val rawSamplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(93, 173, 213); style = Paint.Style.FILL
    }
    var triggerEnabled = true
        set(value) { field = value; invalidate() }
    private var timeZoom = 1f
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(178, 192, 208); textAlign = Paint.Align.LEFT
    }
    private val scopeScaleDetector = android.view.ScaleGestureDetector(context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                timeZoom = (timeZoom * detector.scaleFactor).coerceIn(1f, 100f)
                controlDragActive = false; invalidate(); return true
            }
        })
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 36)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(49, 66, 83)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(67, 83, 101)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 48, 65)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 0.65f
    }
    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 90)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val signalPath = Path()
    private val noiseRandom = java.util.Random(1L)
    private val pinkNoiseState = DoubleArray(7)
    private val dragTouchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var controlDragActive = false
    private var controlDragMode = 0 // 0 = undecided/tap, 1 = frequency, 2 = volume
    private var dragStartX = 0f
    private var dragStartY = 0f

    var minimumFrequency: Int = 20
    var maximumFrequency: Int = 20000
    var frequencyControlEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
        }
    var onFrequencyChanged: ((Int) -> Unit)? = null
    var onVolumeChanged: ((Float) -> Unit)? = null

    var waveform: String = "Sine"
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
            invalidate()
        }
    var frequency: Int = 440
        set(value) {
            val next = value.coerceIn(20, 20000)
            if (field == next) return
            field = next
            updateDescription()
            invalidate()
        }
    var manualTimebaseMs: Int? = null
        set(value) {
            if (field == value) return
            field = value
            timeZoom = 1f
            updateDescription()
            invalidate()
        }
    var volume: Float = 0.5f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            updateDescription()
            invalidate()
        }
    @Volatile var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
            invalidate()
        }
    var sweepActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
            invalidate()
        }
    var dtmfMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
            invalidate()
        }
    var dtmfLowFrequency: Int = 697
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
            invalidate()
        }
    var dtmfHighFrequency: Int = 1209
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
            invalidate()
        }

    init {
        minimumHeight = (48f * resources.displayMetrics.density).roundToInt()
        contentDescription = "Tone waveform preview"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isFocusable = true
        updateDescription()
    }

    private fun updateDescription() {
        val type = when {
            dtmfMode -> "DTMF waveform preview, ${dtmfLowFrequency} and ${dtmfHighFrequency} hertz"
            playing -> "Live oscilloscope"
            else -> "$waveform waveform preview at $frequency hertz"
        }
        val hint = if (frequencyControlEnabled) {
            "Drag horizontally to change frequency or vertically to change volume."
        } else {
            "Drag vertically to change volume."
        }
        val timebase = manualTimebaseMs?.let { "$it milliseconds across the display" } ?: when {
            waveform.equals("White noise", ignoreCase = true) ||
                waveform.equals("Pink noise", ignoreCase = true) -> "automatic 20 milliseconds"
            else -> "automatic time scale, about three cycles"
        }
        contentDescription = "$type. $hint Oscilloscope timebase: $timebase."
    }

    private fun currentWindowSampleCount(): Int {
        val windowSeconds = manualTimebaseMs?.let { it / 1000.0 } ?: when {
            dtmfMode -> 3.0 / maxOf(dtmfLowFrequency, dtmfHighFrequency).coerceAtLeast(1)
            waveform.equals("White noise", ignoreCase = true) ||
                waveform.equals("Pink noise", ignoreCase = true) -> 0.020
            else -> 3.0 / frequency.coerceAtLeast(1)
        }
        return ((windowSeconds * sampleRate / timeZoom).roundToInt() + 1)
            .coerceIn(2, maxScopeWindowSamples)
    }

    private fun previewSampleAt(index: Int): Double {
        val timeSeconds = index.toDouble() / sampleRate.coerceAtLeast(1)
        val phase = 2.0 * PI * frequency.coerceIn(20, 20000) * timeSeconds
        val sine = sin(phase)
        val normalized = when {
            dtmfMode -> {
                val low = sin(2.0 * PI * dtmfLowFrequency * timeSeconds)
                val high = sin(2.0 * PI * dtmfHighFrequency * timeSeconds)
                (low + high) * 0.5
            }
            waveform.equals("Square", ignoreCase = true) -> if (sine >= 0.0) 1.0 else -1.0
            waveform.equals("Triangle", ignoreCase = true) -> 2.0 / PI * kotlin.math.asin(sine)
            waveform.equals("Sawtooth", ignoreCase = true) -> 2.0 * (phase / (2.0 * PI) % 1.0) - 1.0
            waveform.equals("White noise", ignoreCase = true) -> noiseRandom.nextDouble() * 2.0 - 1.0
            waveform.equals("Pink noise", ignoreCase = true) -> {
                val white = noiseRandom.nextDouble() * 2.0 - 1.0
                pinkNoiseState[0] = 0.99886 * pinkNoiseState[0] + white * 0.0555179
                pinkNoiseState[1] = 0.99332 * pinkNoiseState[1] + white * 0.0750759
                pinkNoiseState[2] = 0.96900 * pinkNoiseState[2] + white * 0.1538520
                pinkNoiseState[3] = 0.86650 * pinkNoiseState[3] + white * 0.3104856
                pinkNoiseState[4] = 0.55000 * pinkNoiseState[4] + white * 0.5329522
                pinkNoiseState[5] = -0.7616 * pinkNoiseState[5] - white * 0.0168980
                val pink = pinkNoiseState[0] + pinkNoiseState[1] + pinkNoiseState[2] +
                    pinkNoiseState[3] + pinkNoiseState[4] + pinkNoiseState[5] +
                    pinkNoiseState[6] + white * 0.5362
                pinkNoiseState[6] = white * 0.115926
                (pink * 0.20).coerceIn(-1.0, 1.0)
            }
            else -> sine
        }
        return normalized * volume
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        scopeScaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1 || scopeScaleDetector.isInProgress) {
            controlDragActive = false; parent?.requestDisallowInterceptTouchEvent(true); return true
        }
        val density = resources.displayMetrics.density
        val plotTop = 7f * density
        val plotBottom = height - 6f * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y !in plotTop..plotBottom) return false
                controlDragActive = true
                controlDragMode = 0
                dragStartX = event.x
                dragStartY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!controlDragActive) return false
                if (controlDragMode == 0) {
                    val deltaX = event.x - dragStartX
                    val deltaY = event.y - dragStartY
                    if (hypot(deltaX.toDouble(), deltaY.toDouble()) >= dragTouchSlop.toDouble()) {
                        controlDragMode = if (kotlin.math.abs(deltaX) >= kotlin.math.abs(deltaY)) 1 else 2
                    }
                }
                when (controlDragMode) {
                    1 -> if (frequencyControlEnabled) updateFrequencyAtTouch(event.x)
                    2 -> updateVolumeAtTouch(event.y)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!controlDragActive) return false
                if (controlDragMode == 0 && frequencyControlEnabled) updateFrequencyAtTouch(event.x)
                else if (controlDragMode == 1 && frequencyControlEnabled) updateFrequencyAtTouch(event.x)
                else if (controlDragMode == 2) updateVolumeAtTouch(event.y)
                controlDragActive = false
                controlDragMode = 0
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                controlDragActive = false
                controlDragMode = 0
                return true
            }
        }
        return controlDragActive
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFrequencyAtTouch(touchX: Float) {
        val density = resources.displayMetrics.density
        val plotLeft = 11f * density
        val plotRight = width - 11f * density
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val clampedTouchX = touchX.coerceIn(plotLeft, plotRight)
        val ratio = ((clampedTouchX - plotLeft) / plotWidth).toDouble().coerceIn(0.0, 1.0)
        val low = minimumFrequency.coerceIn(20, 19999)
        val high = maximumFrequency.coerceIn(low + 1, 20000)
        val nextFrequency = (low * exp(ratio * ln(high.toDouble() / low)))
            .roundToInt().coerceIn(low, high)
        if (nextFrequency != frequency) {
            frequency = nextFrequency
            onFrequencyChanged?.invoke(nextFrequency)
        }
    }

    private fun updateVolumeAtTouch(touchY: Float) {
        val density = resources.displayMetrics.density
        val plotTop = 7f * density
        val plotBottom = (height - 6f * density).coerceAtLeast(plotTop + 1f)
        val ratio = ((plotBottom - touchY) / (plotBottom - plotTop)).coerceIn(0f, 1f)
        if (ratio != volume) {
            volume = ratio
            onVolumeChanged?.invoke(ratio)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val inset = 2f * density
        val radius = 12f * density
        val left = inset
        val top = inset
        val right = width - inset
        val bottom = height - inset
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, backgroundPaint)
        borderPaint.color = when {
            sweepActive -> Color.rgb(79, 164, 194)
            playing -> Color.rgb(77, 112, 133)
            else -> Color.rgb(49, 66, 83)
        }
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, borderPaint)

        val plotLeft = left + 32f * density
        val plotRight = right - 8f * density
        val plotTop = top + 20f * density
        val plotBottom = bottom - 34f * density
        val centerY = (plotTop + plotBottom) * 0.5f
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop
        if (plotWidth <= 0f || plotHeight <= 0f) return

        val sampleCount = currentWindowSampleCount()
        val periodic = !dtmfMode && !sweepActive && !waveform.contains("noise", ignoreCase = true)
        val search = if (triggerEnabled && periodic) (sampleRate * 1.5 / frequency.coerceAtLeast(20)).roundToInt() else 0
        val guard = ToneScopeSinc.radius
        val captureCount = (maxOf(sampleCount + search, sampleRate / 20) + guard * 2).coerceAtMost(renderPcmSamples.size)
        val live = playing && pcmFrame.copyForPlaybackTo(renderPcmSamples, captureCount, lookAhead = guard)
        val windowEnd = captureCount - guard
        val linearTrigger = if (live && search > 0) toneScopeTriggerStart(renderPcmSamples, windowEnd, sampleCount, search) else null
        val triggeredStart = linearTrigger?.let {
            if (reconstructionEnabled && sampleCount <= plotWidth * 2) ToneScopeSinc.refineRisingZero(renderPcmSamples, captureCount, it) else it
        }
        val startSample = triggeredStart ?: (windowEnd - sampleCount).toDouble()
        val reconstructed = live && reconstructionEnabled && sampleCount <= plotWidth * 2
        val durationMs = (sampleCount - 1) * 1000.0 / sampleRate
        labelPaint.textSize = 9f * density; labelPaint.textAlign = Paint.Align.LEFT
        val state = when { live && triggeredStart != null -> "LIVE · TRIG ↑"; live -> "LIVE · FREE"; playing -> "WAITING"; else -> "PREVIEW" }
        canvas.drawText(state + if (reconstructed) " · SINC" else if (live) " · PCM" else "", plotLeft, top + 12f * density, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${sampleRate / 1000} kHz", plotRight, top + 12f * density, labelPaint)
        for (division in 0..10) {
            val x = plotLeft + plotWidth * division / 10f
            canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
        }
        for (division in 0..4) {
            val y = plotTop + plotHeight * division / 4f
            canvas.drawLine(plotLeft, y, plotRight, y, if (division == 2) baselinePaint else gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(java.util.Locale.US, "%.2f", 0.5 - division * 0.25), plotLeft - 4f * density, y + 3f * density, labelPaint)
        }
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("FS", left + 3f * density, plotTop - 3f * density, labelPaint)
        for (i in 0..4) {
            labelPaint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(String.format(java.util.Locale.US, if (durationMs < 1) "%.3f" else "%.2f", durationMs * i / 4),
                plotLeft + plotWidth * i / 4, plotBottom + 12f * density, labelPaint)
        }
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(String.format(java.util.Locale.US, "Time (ms) · %.3f ms/div", durationMs / 10),
            (plotLeft + plotRight) / 2, plotBottom + 25f * density, labelPaint)
        val verticalScale = plotHeight
        if (playing && !live) {
            // A generated preview must not masquerade as captured output PCM.
            val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(143, 158, 175); textSize = 11f * density; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Waiting for audio…", (plotLeft + plotRight) / 2, centerY, statusPaint)
            if (isShown) postInvalidateOnAnimation()
            return
        }
        if (!live) {
            noiseRandom.setSeed(1L)
            pinkNoiseState.fill(0.0)
        }
        val firstRawSample = startSample.toInt().coerceIn(0, captureCount - 1)
        val triggerFraction = if (live) startSample - firstRawSample else 0.0
        val renderSampleCount = sampleCount + if (live && triggerFraction > 0 && firstRawSample + sampleCount < captureCount) 1 else 0
        fun sampleAt(index: Int): Double {
            if (!live) return previewSampleAt(index) * 12000.0 / 32768.0
            // Trigger shifts sample positions in X, never filters or attenuates their values.
            return renderPcmSamples[(firstRawSample + index).coerceAtMost(captureCount - 1)].toDouble() / 32768.0
        }
        signalPath.reset()
        val pixelColumns = plotWidth.toInt().coerceAtLeast(1)
        if (renderSampleCount > pixelColumns * 2) {
            // Preserve every peak in dense windows instead of drawing an aliased zigzag.
            for (column in 0 until pixelColumns) {
                val first = column * renderSampleCount / pixelColumns
                val end = ((column + 1) * renderSampleCount / pixelColumns).coerceAtMost(renderSampleCount)
                var minimum = Double.POSITIVE_INFINITY
                var maximum = Double.NEGATIVE_INFINITY
                for (i in first until end) {
                    val value = sampleAt(i)
                    minimum = minOf(minimum, value); maximum = maxOf(maximum, value)
                }
                val x = plotLeft + column + 0.5f
                signalPath.moveTo(x, centerY - maximum.toFloat() * verticalScale)
                signalPath.lineTo(x, centerY - minimum.toFloat() * verticalScale)
            }
        } else if (reconstructed) {
            // Draw an explicitly labelled reconstruction; sample dots below remain raw PCM.
            for (column in 0..pixelColumns) {
                val ratio = column.toDouble() / pixelColumns
                val position = startSample + (sampleCount - 1) * ratio
                val value = ToneScopeSinc.valueAt(renderPcmSamples, captureCount, position)
                    ?: renderPcmSamples[position.toInt().coerceIn(0, captureCount - 1)] / 32768.0
                val x = plotLeft + plotWidth * ratio.toFloat()
                val y = centerY - value.toFloat() * verticalScale
                if (column == 0) signalPath.moveTo(x, y) else signalPath.lineTo(x, y)
            }
        } else {
            for (sampleIndex in 0 until renderSampleCount) {
                val normalized = sampleAt(sampleIndex)
                val xRatio = (sampleIndex - triggerFraction).toFloat() / (sampleCount - 1).coerceAtLeast(1)
                val x = plotLeft + plotWidth * xRatio
                val y = centerY - normalized.toFloat() * verticalScale
                if (sampleIndex == 0) signalPath.moveTo(x, y) else signalPath.lineTo(x, y)
            }
        }
        signalPaint.alpha = if (live) 255 else 220
        canvas.save(); canvas.clipRect(plotLeft, plotTop, plotRight, plotBottom)
        canvas.drawPath(signalPath, signalPaint)
        if (live && sampleCount <= plotWidth / (6f * density)) {
            for (i in 0 until renderSampleCount) {
                val x = plotLeft + plotWidth * (i - triggerFraction).toFloat() / (sampleCount - 1)
                canvas.drawCircle(x, centerY - sampleAt(i).toFloat() * verticalScale, 1.6f * density, rawSamplePaint)
            }
        }
        canvas.restore()
        if (playing && isShown) postInvalidateDelayed(16L)
    }
}
