package sk.spectrum.analyzer2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

import kotlin.math.roundToInt

/** Sound identification summary and timeline charts. */
internal class SoundSessionChartView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 207, 222)
        textSize = 12f * density
        textAlign = Paint.Align.RIGHT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(43, 55, 70) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 14f * density
    }
    private var rows: List<SoundSessionEntry> = emptyList()

    fun setSummary(summary: SoundSessionSummary) {
        rows = summary.entries
        contentDescription = if (rows.isEmpty()) "No sound was analyzed" else rows.joinToString { "${it.label}: ${it.percent.roundToInt()} percent" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) {
            canvas.drawText("No sound was analyzed in this session.", 0f, height * 0.5f, mutedPaint)
            return
        }
        val rowHeight = height.toFloat() / rows.size
        val labelBaselineOffset = 15f * density
        val trackHeight = 5f * density
        val valueSpace = 76f * density
        val barRight = (width - valueSpace).coerceAtLeast(0f)
        val labelLimit = barRight - 8f * density
        rows.forEachIndexed { index, row ->
            val top = index * rowHeight
            val baseline = top + labelBaselineOffset
            val label = ellipsize(row.label, textPaint, labelLimit)
            canvas.drawText(label, 0f, baseline, textPaint)
            percentPaint.color = Color.rgb(190, 207, 222)
            val resultText = String.format(java.util.Locale.US, "%d%% · %.0fs", row.percent.roundToInt(), row.seconds)
            canvas.drawText(resultText, width.toFloat(), baseline, percentPaint)
            val trackTop = top + 23f * density
            canvas.drawRoundRect(0f, trackTop, barRight, trackTop + trackHeight, trackHeight, trackHeight, trackPaint)
            val fillRight = (barRight * row.percent / 100.0).toFloat().coerceIn(0f, barRight)
            barPaint.color = if (row.label.startsWith("Unclear")) SOUND_SESSION_OTHER_COLOR
                else SOUND_SESSION_COLORS[index % SOUND_SESSION_COLORS.size]
            canvas.drawRoundRect(0f, trackTop, fillRight, trackTop + trackHeight, trackHeight, trackHeight, barPaint)
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }
}

internal class SoundSessionTimelineView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 47, 62) }
    private val segmentPaint = Paint().apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 207, 222)
        textSize = 10f * density
    }
    private var summary: SoundSessionSummary? = null

    fun setSummary(value: SoundSessionSummary) {
        summary = value
        val labels = value.timeline.distinct().take(8)
        contentDescription = if (labels.isEmpty()) "No sound timeline available" else
            "Sound sequence from start to end: ${labels.joinToString()}. Colors match the session share chart."
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = summary
        val events = current?.timeline.orEmpty()
        if (events.isEmpty()) {
            canvas.drawText("No analyzed sound sequence", 0f, height * 0.72f, textPaint)
            return
        }
        val radius = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backgroundPaint)
        val topLabels = current?.entries.orEmpty().take(SOUND_SESSION_COLORS.size)
            .mapIndexed { index, entry -> entry.label to SOUND_SESSION_COLORS[index] }.toMap()
        val columns = minOf(events.size, width.coerceAtLeast(1))
        for (column in 0 until columns) {
            val eventIndex = (((column.toLong() + 1L) * events.size.toLong()) / columns.toLong() - 1L)
                .toInt().coerceIn(0, events.lastIndex)
            val label = events[eventIndex]
            segmentPaint.color = if (label.startsWith("Unclear")) SOUND_SESSION_OTHER_COLOR
            else topLabels[label] ?: Color.rgb(68, 78, 91)
            val left = width.toFloat() * (column.toFloat() / columns.toFloat())
            val right = width.toFloat() * ((column + 1).toFloat() / columns.toFloat())
            canvas.drawRect(left, 1f * density, right + 0.5f, height - 1f * density, segmentPaint)
        }
    }
}
