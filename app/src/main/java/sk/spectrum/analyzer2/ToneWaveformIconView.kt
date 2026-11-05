package sk.spectrum.analyzer2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

import kotlin.math.PI
import kotlin.math.sin

/** Waveform picker icon rendering. */
internal class ToneWaveformIconView(
    context: Context,
    waveform: String,
    color: Int = Color.WHITE
) : View(context) {
    var waveform: String = waveform
        set(value) {
            field = value
            invalidate()
        }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = width * 0.12f
        val right = width * 0.88f
        val centerY = height * 0.5f
        val amplitude = height * 0.30f
        val plotWidth = right - left
        val path = Path()

        when (waveform.lowercase()) {
            "sine" -> {
                for (step in 0..40) {
                    val x = left + plotWidth * step / 40f
                    val y = centerY - amplitude * sin(2.0 * PI * step / 40.0).toFloat()
                    if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            "square" -> {
                path.moveTo(left, centerY + amplitude)
                path.lineTo(left + plotWidth * 0.25f, centerY + amplitude)
                path.lineTo(left + plotWidth * 0.25f, centerY - amplitude)
                path.lineTo(left + plotWidth * 0.75f, centerY - amplitude)
                path.lineTo(left + plotWidth * 0.75f, centerY + amplitude)
                path.lineTo(right, centerY + amplitude)
            }
            "triangle" -> {
                path.moveTo(left, centerY + amplitude)
                path.lineTo(left + plotWidth * 0.25f, centerY - amplitude)
                path.lineTo(left + plotWidth * 0.5f, centerY + amplitude)
                path.lineTo(left + plotWidth * 0.75f, centerY - amplitude)
                path.lineTo(right, centerY + amplitude)
            }
            "white noise" -> {
                val levels = floatArrayOf(0.15f, 0.82f, 0.28f, 0.68f, 0.12f, 0.91f, 0.35f, 0.76f, 0.20f, 0.62f, 0.10f, 0.85f, 0.40f)
                levels.forEachIndexed { index, level ->
                    val x = left + plotWidth * index / (levels.lastIndex).toFloat()
                    val y = centerY + (level - 0.5f) * amplitude * 2f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            "pink noise" -> {
                path.moveTo(left, centerY + amplitude * 0.55f)
                path.cubicTo(left + plotWidth * 0.18f, centerY + amplitude * 0.35f, left + plotWidth * 0.18f, centerY - amplitude * 0.85f, left + plotWidth * 0.40f, centerY - amplitude * 0.45f)
                path.cubicTo(left + plotWidth * 0.62f, centerY - amplitude * 0.05f, left + plotWidth * 0.61f, centerY + amplitude * 0.92f, left + plotWidth * 0.80f, centerY + amplitude * 0.42f)
                path.cubicTo(left + plotWidth * 0.91f, centerY + amplitude * 0.15f, left + plotWidth * 0.90f, centerY - amplitude * 0.15f, right, centerY - amplitude * 0.34f)
            }
            else -> { // Sawtooth
                path.moveTo(left, centerY + amplitude)
                path.lineTo(left + plotWidth * 0.42f, centerY - amplitude)
                path.lineTo(left + plotWidth * 0.42f, centerY + amplitude)
                path.lineTo(left + plotWidth * 0.92f, centerY - amplitude)
            }
        }
        canvas.drawPath(path, wavePaint)
    }
}
