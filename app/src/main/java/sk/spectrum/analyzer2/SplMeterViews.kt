package sk.spectrum.analyzer2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** SPL meter scales and chart panel rendering. */
internal data class SplScaleLayout(val stackMeterBelowReadout: Boolean, val meterHeightPx: Int)

/** Chooses a readable SPL card arrangement from the safe app-window size. */
internal fun calculateSplScaleLayout(safeWidthPx: Int, safeHeightPx: Int, density: Float): SplScaleLayout {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    fun dp(value: Int) = (value * safeDensity).roundToInt()
    val stacked = safeWidthPx / safeDensity < 560f
    val meterHeight = if (stacked) {
        (safeHeightPx - dp(208)).coerceAtLeast(dp(52)).coerceAtMost(dp(190))
    } else {
        (safeHeightPx - dp(44)).coerceAtLeast(dp(152)).coerceAtMost(dp(236))
    }
    return SplScaleLayout(stacked, meterHeight)
}

/** Full-screen SPL layout: the original card on the left, live graphs on the right. */
internal class SplMeterPanel(
    context: Context,
    private val trendBuffer: SplTrendBuffer,
    originalCard: View
) : LinearLayout(context) {
    private val plotBackgroundColor = Color.rgb(15, 23, 36)
    private val ink = Color.rgb(190, 204, 219)
    private val muted = Color.rgb(143, 158, 175)
    private val accent = Color.rgb(255, 213, 90)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false; setTextColor(ink)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(10), 0, dp(10), 0); isSingleLine = true
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.rgb(38, 54, 70)),
            android.graphics.drawable.ColorDrawable(Color.rgb(15, 23, 36)), null)
        backgroundTintList = null
        stateListAnimator = null; contentDescription = value; setOnClickListener { action() }
    }
    private val chart = ChartView(context)
    val graphView: View get() = chart
    val graphControls = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = HORIZONTAL }
    private val leftPane = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(2), dp(2), dp(4), dp(2)) }
    private var selectedWeighting = SplWeighting.A
    private var selectedTab = 0
    private var rangeSeconds = 60
    private var currentHeld = false
    private var graphGeneration = -1L
    private var graphTime = -1.0
    private val rangeButton = button("1 min") {
        rangeSeconds = when (rangeSeconds) { 10 -> 60; 60 -> 300; else -> 10 }
        refreshChartMode()
    }
    private fun loadGraphIcon(name: String) = context.assets.open("icons/$name").use {
        android.graphics.BitmapFactory.decodeStream(it, null,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
    }
    private val waveIcon = loadGraphIcon("spl_wave.png")
    private val trendIcon = loadGraphIcon("spl_trend.png")
    private val modeButton = AnalyzerIconButton(context, AnalyzerIconButton.Icon.SPECTRUM).apply {
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0); text = ""
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.rgb(38, 54, 70)),
            android.graphics.drawable.ColorDrawable(Color.rgb(15, 23, 36)), null)
        backgroundTintList = null; stateListAnimator = null
        setOnClickListener { selectedTab = if (selectedTab == 0) 2 else 0; refreshChartMode() }
    }

    init {
        orientation = HORIZONTAL; setBackgroundColor(plotBackgroundColor)
        leftPane.addView(originalCard, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(leftPane, LinearLayout.LayoutParams(dp(180), -1))
        addView(View(context).apply { setBackgroundColor(Color.rgb(42, 52, 65)) }, LinearLayout.LayoutParams(dp(1), -1))
        val right = LinearLayout(context).apply {
            orientation = VERTICAL; setBackgroundColor(plotBackgroundColor)
            setPadding(dp(6), dp(3), dp(2), dp(2))
        }
        val timeButtonWidth = kotlin.math.ceil(
            listOf("10 s", "1 min", "5 min").maxOf { rangeButton.paint.measureText(it).toDouble() }
        ).toInt() + rangeButton.paddingLeft + rangeButton.paddingRight
        for (b in listOf(rangeButton, modeButton)) {
            val buttonWidth = if (b === rangeButton) timeButtonWidth else dp(48)
            graphControls.addView(b, LinearLayout.LayoutParams(buttonWidth, dp(40)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
        }
        right.addView(chart, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(5) })
        addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        refreshChartMode()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val wanted = ((w * 0.32f).roundToInt() + dp(24)).coerceIn(dp(176), dp(340)).coerceAtMost((w * 0.48f).roundToInt())
        // Resolve the final width in the first measure, before any transition snapshot.
        leftPane.layoutParams.width = wanted
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun refreshChartMode() {
        rangeButton.text = when (rangeSeconds) { 10 -> "10 s"; 300 -> "5 min"; else -> "1 min" }
        rangeButton.contentDescription = "Time window ${rangeButton.text}. Tap to cycle 10 seconds, 1 minute, 5 minutes."
        val target = if (selectedTab == 0) "Wave" else "Trend"
        if (modeButton.contentDescription != "Switch to $target graph") {
            modeButton.setCustomIcon(if (selectedTab == 0) waveIcon else trendIcon)
            modeButton.contentDescription = "Switch to $target graph"
        }
        rangeButton.visibility = if (selectedTab == 0) View.VISIBLE else View.GONE
        chart.mode = selectedTab; chart.range = rangeSeconds; chart.weighting = selectedWeighting.ordinal; chart.invalidate()
    }
    fun update(running: Boolean, held: Boolean, weighting: SplWeighting, snapshot: SplSessionSnapshot?) {
        selectedWeighting = weighting; currentHeld = held
        if (snapshot == null) { chart.trend = emptyList(); graphTime = -1.0; graphGeneration = -1 }
        else if (!held && (graphTime != trendBuffer.lastSeconds || graphGeneration != trendBuffer.generation)) {
            chart.trend = trendBuffer.points.toList(); graphTime = trendBuffer.lastSeconds; graphGeneration = trendBuffer.generation
        }
        refreshChartMode()
    }
    fun setSignal(spectrum: DoubleArray, sampleRate: Int, fftSize: Int, waveform: FloatArray) {
        if (currentHeld) return
        chart.sampleRate = sampleRate; chart.waveform = waveform; chart.invalidate()
    }
    private inner class ChartView(context: Context) : View(context) {
        var mode = 0
        var range = 60
        var weighting = 0
        var trend: List<SplLogPoint> = emptyList()
        var spectrum = DoubleArray(0)
        var waveform = FloatArray(0)
        var sampleRate = 48000
        var fftSize = 8192
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        init { contentDescription = "Measurement graph" }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val left = dp(if (mode == 0) 8 else 65).toFloat()
            val top = dp(12).toFloat()
            val right = width - dp(if (mode == 0) 55 else 8).toFloat()
            val bottom = height - dp(44).toFloat()
            if (right <= left || bottom <= top) return
            c.drawColor(plotBackgroundColor)
            val pw = right - left; val ph = bottom - top
            val end = trend.lastOrNull()?.seconds ?: 0.0
            val start = max(0.0, end - range)
            val visible = trend.filter { it.seconds >= start }
            // Fixed normalized PCM range: incoming peaks must not resize older waveform history.
            val waveLimit = 0.25
            val low = if (mode == 0) 30.0 else if (mode == 1) -120.0 else -waveLimit
            val high = if (mode == 0) 120.0 else if (mode == 1) 0.0 else waveLimit
            fun y(v: Double) = bottom - ((v - low) / (high - low)).toFloat() * ph
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL); paint.textSize = dp(10).toFloat()
            val gridSteps = if (mode == 0) 9 else 4
            for (i in 0..gridSteps) {
                val py = top + ph * i / gridSteps
                paint.color = Color.rgb(47, 47, 47); paint.strokeWidth = resources.displayMetrics.density * 0.7f
                paint.pathEffect = DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f)
                c.drawLine(left, py, right, py, paint)
                paint.pathEffect = null
                paint.color = muted; paint.textAlign = if (mode == 0) Paint.Align.LEFT else Paint.Align.RIGHT
                val v = high - (high - low) * i / gridSteps
                c.drawText(if (mode == 2) String.format(java.util.Locale.US, if (waveLimit < 0.1) "%.3f" else "%.2f", v) else v.roundToInt().toString(), (if (mode == 0) right + dp(5) else left - dp(5)), py + dp(3), paint)
            }
            paint.textAlign = Paint.Align.CENTER
            for (i in 0..4) {
                val x = left + pw * i / 4
                val text = when (mode) {
                    0 -> "${(-range + range * i / 4.0).roundToInt()}"
                    1 -> listOf("20", "100", "1k", "10k", "20k")[i]
                    else -> String.format(java.util.Locale.US, "%.1f", -5.0 + 5.0 * i / 4)
                }
                // Spectrum labels use the same logarithmic position as their data.
                val px = if (mode == 1) left + (ln(listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)[i] / 20) / ln(1000.0)).toFloat() * pw else x
                paint.color = muted
                if (mode == 0) {
                    paint.pathEffect = DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f)
                    paint.color = Color.rgb(35, 35, 35); c.drawLine(px, top, px, bottom, paint); paint.pathEffect = null
                }
                paint.color = muted
                paint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                c.drawText(text, px, bottom + dp(16), paint)
            }
            paint.color = muted; paint.textAlign = Paint.Align.CENTER
            c.drawText(if (mode == 1) "Frequency (Hz)" else "Time (s)", left + pw / 2, height - dp(5).toFloat(), paint)
            val yLabel = when (mode) {
                0 -> "SPL (dB${listOf("A", "C", "Z")[weighting.coerceIn(0, 2)]})"
                1 -> "Level (dBFS)"
                else -> "Amplitude (relative)"
            }
            c.save()
            c.translate(if (mode == 0) width - dp(10).toFloat() else dp(12).toFloat(), top + ph / 2)
            c.rotate(-90f)
            c.drawText(yLabel, 0f, 0f, paint)
            c.restore()
            c.save(); c.clipRect(left, top, right, bottom)
            path.reset(); var started = false
            fun point(x: Float, yy: Float) { if (!started) { path.moveTo(x, yy); started = true } else path.lineTo(x, yy) }
            when (mode) {
                0 -> for (p in visible) point(right - ((end - p.seconds) / range).toFloat() * pw, y(p.levels[weighting]))
                1 -> {
                    // Max per horizontal pixel preserves narrow peaks without huge Canvas paths.
                    val buckets = DoubleArray(pw.toInt().coerceAtLeast(1)) { -160.0 }
                    for (i in 1 until spectrum.size) {
                        val frequency = i * sampleRate.toDouble() / fftSize
                        if (frequency < 20 || frequency > min(20000.0, sampleRate / 2.0)) continue
                        val px = ((ln(frequency / 20) / ln(1000.0)) * buckets.lastIndex).toInt().coerceIn(0, buckets.lastIndex)
                        val v = 20 * log10((spectrum[i] * 4 / fftSize).coerceAtLeast(1e-8))
                        buckets[px] = max(buckets[px], v)
                    }
                    for (i in buckets.indices) if (buckets[i] > -160) point(left + i, y(buckets[i]))
                }
                else -> {
                    val bins = waveform.size / 2
                    for (i in 0 until bins) {
                        val x = left + i.toFloat() / (bins - 1).coerceAtLeast(1) * pw
                        path.moveTo(x, y(waveform[i * 2].toDouble()))
                        path.lineTo(x, y(waveform[i * 2 + 1].toDouble()))
                    }
                }
            }
            paint.color = if (mode == 0) Color.rgb(235, 71, 51) else accent; paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 1.2f; paint.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, paint); paint.style = Paint.Style.FILL; c.restore()
            val empty = when (mode) { 0 -> visible.isEmpty(); 1 -> spectrum.isEmpty(); else -> waveform.isEmpty() }
            if (empty) { paint.color = muted; paint.textAlign = Paint.Align.CENTER; c.drawText("—", left + pw / 2, top + ph / 2, paint) }
        }
    }
}

internal class SplScaleMeterView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var levelDb: Double? = null
    private fun dp(value: Float) = value * density

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(174, 188, 205)
        textSize = dp(11f)
        textAlign = Paint.Align.RIGHT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(65, 82, 101)
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(119, 141, 163)
        strokeWidth = dp(1.4f)
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(67, 82, 98)
        strokeWidth = dp(1f)
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 90)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    fun setLevel(value: Double?) {
        levelDb = value?.takeIf { it.isFinite() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = dp(7f)
        val bottom = (height - dp(7f)).coerceAtLeast(top + dp(20f))
        val labelRight = dp(32f)
        val tickStart = dp(39f)
        val trackX = width - dp(12f)
        canvas.drawLine(trackX, top, trackX, bottom, trackPaint)
        val labelStep = if (height < dp(150f)) 20 else 10
        for (db in 30..130 step 10) {
            val y = bottom - ((db - 30) / 100f) * (bottom - top)
            if (db % labelStep == 0) {
                canvas.drawText(db.toString(), labelRight, y + labelPaint.textSize * 0.35f, labelPaint)
            }
            val tickPaint = if (db % 20 == 0 || db == 30 || db == 130) majorTickPaint else minorTickPaint
            canvas.drawLine(tickStart, y, trackX + dp(5f), y, tickPaint)
        }
        val level = levelDb ?: return
        val ratio = ((level - 30.0) / 100.0).coerceIn(0.0, 1.0)
        val markerY = bottom - ((bottom - top) * ratio).toFloat()
        canvas.drawLine(trackX, bottom, trackX, markerY, levelPaint)
        canvas.drawLine(tickStart, markerY, trackX + dp(7f), markerY, levelPaint)
        canvas.drawCircle(trackX, markerY, dp(5f), levelPaint)
    }
}

internal class SplArcMeterView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level: Double? = null
    private var minimum: Double? = null
    private var peak: Double? = null
    fun setLevels(value: Double?, low: Double?, high: Double?) {
        level = value; minimum = low; peak = high
        contentDescription = "Analog dB scale: ${value?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "no reading"}"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val radius = min(width * 0.43f, (height * 0.90f - 26 * density).coerceAtLeast(1f))
        val cy = height * 0.91f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        fun angle(db: Double) = (210.0 + (db.coerceIn(20.0, 120.0) - 20) * 1.2) * PI / 180
        fun xy(db: Double, r: Float): android.graphics.PointF {
            val a = angle(db)
            return android.graphics.PointF(cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
        paint.color = Color.rgb(209, 216, 224)
        canvas.drawArc(rect, 210f, 84f, false, paint)
        paint.color = Color.rgb(238, 74, 54)
        canvas.drawArc(rect, 306f, 24f, false, paint)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = min(14f * density, width * 0.045f)
        paint.textAlign = Paint.Align.CENTER
        for (db in 20..120 step 5) {
            val major = db % 10 == 0
            val a = xy(db.toDouble(), radius)
            val b = xy(db.toDouble(), radius + if (major) 12 * density else 6 * density)
            paint.color = if (db >= 100) Color.rgb(238, 74, 54) else Color.rgb(209, 216, 224)
            paint.style = Paint.Style.STROKE
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            if (major) {
                val t = xy(db.toDouble(), radius + 22 * density)
                paint.style = Paint.Style.FILL
                canvas.drawText(db.toString(), t.x, t.y + paint.textSize * 0.3f, paint)
            }
        }
        fun marker(value: Double?, name: String, color: Int) {
            if (value == null) return
            val a = xy(value, radius - 8 * density)
            val b = xy(value, radius - 16 * density)
            paint.color = color; paint.strokeWidth = 3 * density
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            paint.style = Paint.Style.FILL; paint.textSize = min(10 * density, width * 0.035f)
            val t = xy(value, radius - 30 * density)
            canvas.drawText(name, t.x, t.y, paint)
            canvas.drawText(String.format(java.util.Locale.US, "%.1f", value), t.x, t.y + paint.textSize * 1.2f, paint)
        }
        marker(minimum, "Min", Color.rgb(178, 192, 208))
        marker(peak, "Peak", Color.rgb(238, 74, 54))
        level?.let {
            val a = xy(it, radius + 5 * density)
            val b = xy(it, radius * 0.35f)
            paint.color = Color.rgb(238, 74, 54); paint.strokeWidth = 2.5f * density
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }
}
