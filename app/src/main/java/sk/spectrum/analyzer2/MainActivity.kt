package sk.spectrum.analyzer2

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.tensorflow.lite.Interpreter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import androidx.appcompat.widget.AppCompatButton
import android.widget.EditText
import android.text.InputFilter
import android.text.InputType
import android.app.AlertDialog
import android.app.Dialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

@android.annotation.TargetApi(26)
private class AnalyzerIconButton(
    context: Context,
    icon: Icon
) : AppCompatButton(context) {
    enum class Icon { PLAY, STOP, MIC, RESET, TONE, PRESETS, SWEEP, KEYS, SOUND, AXIS, LIN, LOG, IMPORT, EXPORT, FULL_SCALE_OPEN, FULL_SCALE_CLOSE, SPECTRUM, SPECTROGRAM }

    var icon: Icon = icon
        set(value) {
            field = value
            invalidate()
            contentDescription = when (value) {
                Icon.PLAY -> "Start analysis"
                Icon.STOP -> "Stop analysis"
                Icon.MIC -> "Start listening"
                Icon.RESET -> "Reset display"
                Icon.TONE -> "Tone generator"
                Icon.PRESETS -> "Frequency presets"
                Icon.SWEEP -> "Start frequency sweep"
                Icon.KEYS -> "Piano keyboard"
                Icon.SOUND -> "Sound identification"
                Icon.AXIS -> "Change frequency scale"
                Icon.LIN -> "Switch to linear frequency scale"
                Icon.LOG -> "Switch to logarithmic frequency scale"
                Icon.IMPORT -> "Import My Sound ID recordings"
                Icon.EXPORT -> "Export My Sound ID recordings"
                Icon.FULL_SCALE_OPEN -> "Expand piano keyboard"
                Icon.FULL_SCALE_CLOSE -> "Return from full-screen keyboard"
                Icon.SPECTRUM -> "Show spectrum"
                Icon.SPECTROGRAM -> "Show spectrogram"
            }
        }

    var playStopIconScale = 0.30f
        set(value) {
            field = value.coerceIn(0.30f, 0.80f)
            invalidate()
        }

    var roundedPlayStopGlyphs = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val playStopRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val resetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        text = ""
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        contentDescription = when (icon) {
            Icon.PLAY -> "Start analysis"
            Icon.STOP -> "Stop analysis"
            Icon.MIC -> "Start listening"
            Icon.RESET -> "Reset display"
            Icon.TONE -> "Tone generator"
            Icon.PRESETS -> "Frequency presets"
            Icon.SWEEP -> "Start frequency sweep"
            Icon.KEYS -> "Piano keyboard"
            Icon.SOUND -> "Sound identification"
            Icon.AXIS -> "Change frequency scale"
            Icon.LIN -> "Switch to linear frequency scale"
            Icon.LOG -> "Switch to logarithmic frequency scale"
            Icon.IMPORT -> "Import My Sound ID recordings"
            Icon.EXPORT -> "Export My Sound ID recordings"
            Icon.FULL_SCALE_OPEN -> "Expand piano keyboard"
            Icon.FULL_SCALE_CLOSE -> "Return from full-screen keyboard"
            Icon.SPECTRUM -> "Show spectrum"
            Icon.SPECTROGRAM -> "Show spectrogram"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // Play and stop use the same compact visual bounds and have no outline.
        val iconSize = minOf(width, height) * playStopIconScale
        val path = Path()
        fillPaint.color = Color.WHITE
        when (icon) {
            Icon.PLAY -> {
                if (roundedPlayStopGlyphs) {
                    canvas.drawCircle(cx, cy, iconSize * 0.57f, playStopRingPaint)
                    val glyphSize = iconSize * 0.72f
                    path.moveTo(cx - glyphSize * 0.28f, cy - glyphSize * 0.46f)
                    path.cubicTo(cx - glyphSize * 0.28f, cy - glyphSize * 0.56f, cx - glyphSize * 0.20f, cy - glyphSize * 0.57f, cx - glyphSize * 0.10f, cy - glyphSize * 0.50f)
                    path.lineTo(cx + glyphSize * 0.38f, cy - glyphSize * 0.05f)
                    path.cubicTo(cx + glyphSize * 0.49f, cy, cx + glyphSize * 0.49f, cy, cx + glyphSize * 0.38f, cy + glyphSize * 0.05f)
                    path.lineTo(cx - glyphSize * 0.10f, cy + glyphSize * 0.50f)
                    path.cubicTo(cx - glyphSize * 0.20f, cy + glyphSize * 0.57f, cx - glyphSize * 0.28f, cy + glyphSize * 0.56f, cx - glyphSize * 0.28f, cy + glyphSize * 0.46f)
                    path.close()
                } else {
                    path.moveTo(cx - iconSize * 0.5f, cy - iconSize * 0.5f)
                    path.lineTo(cx + iconSize * 0.5f, cy)
                    path.lineTo(cx - iconSize * 0.5f, cy + iconSize * 0.5f)
                    path.close()
                }
                canvas.drawPath(path, fillPaint)
            }
            Icon.STOP -> {
                if (roundedPlayStopGlyphs) {
                    canvas.drawCircle(cx, cy, iconSize * 0.57f, playStopRingPaint)
                    val half = iconSize * 0.31f
                    canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, half * 0.24f, half * 0.24f, fillPaint)
                } else {
                    val half = iconSize * 0.5f
                    path.addRect(cx - half, cy - half, cx + half, cy + half, Path.Direction.CW)
                    canvas.drawPath(path, fillPaint)
                }
            }
            Icon.RESET -> {
                // Material-style refresh symbol: a clean circular arrow, drawn as one filled shape.
                val iconSize = minOf(width, height) * 0.48f
                val scale = iconSize / 24f
                canvas.save()
                canvas.translate(cx - iconSize / 2f, cy - iconSize / 2f)
                canvas.scale(scale, scale)
                val refreshPath = Path().apply {
                    moveTo(17.65f, 6.35f)
                    cubicTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                    cubicTo(7.58f, 4f, 4f, 7.58f, 4f, 12f)
                    cubicTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
                    cubicTo(16.08f, 20f, 19.44f, 16.95f, 19.93f, 13f)
                    lineTo(17.91f, 13f)
                    cubicTo(17.43f, 15.83f, 14.97f, 18f, 12f, 18f)
                    cubicTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
                    cubicTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
                    cubicTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
                    lineTo(13f, 11f)
                    lineTo(20f, 11f)
                    lineTo(20f, 4f)
                    close()
                }
                fillPaint.color = Color.WHITE
                canvas.drawPath(refreshPath, fillPaint)
                fillPaint.color = Color.BLACK
                canvas.restore()
            }
            Icon.TONE, Icon.PRESETS, Icon.SWEEP, Icon.MIC, Icon.SOUND, Icon.SPECTRUM, Icon.SPECTROGRAM, Icon.AXIS, Icon.KEYS, Icon.LIN, Icon.LOG, Icon.IMPORT, Icon.EXPORT, Icon.FULL_SCALE_OPEN, Icon.FULL_SCALE_CLOSE -> {
                val s = minOf(width, height) * when (icon) {
                    Icon.KEYS -> 0.48f
                    Icon.MIC -> 0.62f
                    Icon.TONE, Icon.SOUND, Icon.SPECTRUM, Icon.SPECTROGRAM -> 0.40f
                    Icon.PRESETS, Icon.IMPORT, Icon.EXPORT -> 0.48f
                    else -> 0.34f
                }
                val l = cx - s / 2f
                val t = cy - s / 2f
                val r = cx + s / 2f
                val b = cy + s / 2f
                when (icon) {
                    Icon.MIC -> {
                        canvas.drawRoundRect(
                            cx - s * 0.15f, cy - s * 0.38f,
                            cx + s * 0.15f, cy + s * 0.12f,
                            s * 0.15f, s * 0.15f, symbolPaint
                        )
                        val cradle = Path().apply {
                            moveTo(cx - s * 0.30f, cy - s * 0.03f)
                            cubicTo(cx - s * 0.30f, cy + s * 0.24f, cx - s * 0.15f, cy + s * 0.38f, cx, cy + s * 0.38f)
                            cubicTo(cx + s * 0.15f, cy + s * 0.38f, cx + s * 0.30f, cy + s * 0.24f, cx + s * 0.30f, cy - s * 0.03f)
                        }
                        canvas.drawPath(cradle, symbolPaint)
                        canvas.drawLine(cx, cy + s * 0.38f, cx, cy + s * 0.49f, symbolPaint)
                        canvas.drawLine(cx - s * 0.17f, cy + s * 0.49f, cx + s * 0.17f, cy + s * 0.49f, symbolPaint)
                    }
                    Icon.TONE -> {
                        val p = Path().apply {
                            moveTo(l, cy)
                            cubicTo(l + s * .16f, cy - s * .55f, l + s * .34f, cy + s * .55f, cx, cy)
                            cubicTo(cx + s * .16f, cy - s * .55f, cx + s * .34f, cy + s * .55f, r, cy)
                        }
                        canvas.drawPath(p, symbolPaint)
                    }
                    Icon.PRESETS -> {
                        for (row in 0..2) {
                            val y = t + s * (0.24f + row * 0.26f)
                            canvas.drawLine(l + s * 0.08f, y, r - s * 0.08f, y, symbolPaint)
                            val knobX = l + s * floatArrayOf(0.34f, 0.68f, 0.48f)[row]
                            canvas.drawCircle(knobX, y, s * 0.075f, fillPaint)
                        }
                    }
                    Icon.SWEEP -> {
                        val sweep = Path().apply {
                            moveTo(l + s * 0.08f, b - s * 0.10f)
                            cubicTo(l + s * 0.38f, b - s * 0.08f, l + s * 0.36f, t + s * 0.18f, r - s * 0.18f, t + s * 0.12f)
                        }
                        canvas.drawPath(sweep, symbolPaint)
                        canvas.drawLine(r - s * 0.38f, t + s * 0.12f, r - s * 0.16f, t + s * 0.12f, symbolPaint)
                        canvas.drawLine(r - s * 0.16f, t + s * 0.12f, r - s * 0.16f, t + s * 0.34f, symbolPaint)
                    }
                    Icon.SOUND -> {
                        val wave = Path().apply {
                            moveTo(l, cy + s * .08f)
                            lineTo(l + s * .15f, cy + s * .08f)
                            lineTo(l + s * .27f, cy - s * .28f)
                            lineTo(l + s * .39f, cy + s * .30f)
                            lineTo(l + s * .51f, cy - s * .48f)
                            lineTo(l + s * .62f, cy + s * .08f)
                            lineTo(l + s * .69f, cy - s * .08f)
                        }
                        canvas.drawPath(wave, symbolPaint)
                        val lensX = l + s * .75f
                        val lensY = cy + s * .10f
                        canvas.drawCircle(lensX, lensY, s * .20f, symbolPaint)
                        canvas.drawLine(lensX + s * .14f, lensY + s * .14f, r, b, symbolPaint)
                    }
                    Icon.KEYS -> {
                        canvas.drawRoundRect(l, t + s * .12f, r, b - s * .12f, s * .05f, s * .05f, symbolPaint)
                        for (i in 1..4) canvas.drawLine(l + s * i / 5f, t + s * .12f, l + s * i / 5f, b - s * .12f, symbolPaint)
                        for (i in 1..3) canvas.drawRoundRect(l + s * (i * 1.25f - .27f), t + s * .12f, l + s * (i * 1.25f + .04f), cy, s * .03f, s * .03f, fillPaint)
                    }
                    Icon.AXIS -> {
                        canvas.drawLine(l, b, l, t, symbolPaint)
                        canvas.drawLine(l, b, r, b, symbolPaint)
                        canvas.drawLine(l + s * .18f, b - s * .25f, r - s * .1f, t + s * .2f, symbolPaint)
                    }
                    Icon.LIN, Icon.LOG -> {
                        // Keep the axis glyph legible in the wider LIN/LOG cells.
                        // Its horizontal and vertical drawing bounds are doubled with the cell width.
                        val tickSize = width * .36f
                        val tickLeft = cx - tickSize / 2f
                        val tickRight = cx + tickSize / 2f
                        val baseline = cy + tickSize * .20f
                        val startX = tickLeft + tickSize * .06f
                        val endX = tickRight - tickSize * .06f
                        canvas.drawLine(startX, baseline, endX, baseline, symbolPaint)
                        val ticks = if (icon == Icon.LIN) {
                            floatArrayOf(.08f, .22f, .43f, .68f, .92f)
                        } else {
                            floatArrayOf(.08f, .36f, .64f, .92f)
                        }
                        ticks.forEach { fraction ->
                            val x = startX + (endX - startX) * fraction
                            canvas.drawLine(x, baseline, x, baseline - tickSize * .58f, symbolPaint)
                        }
                    }
                    Icon.IMPORT, Icon.EXPORT -> {
                        val trayY = b - s * .18f
                        val tray = Path().apply {
                            moveTo(l + s * .12f, trayY - s * .12f)
                            lineTo(l + s * .12f, trayY + s * .08f)
                            lineTo(r - s * .12f, trayY + s * .08f)
                            lineTo(r - s * .12f, trayY - s * .12f)
                        }
                        canvas.drawPath(tray, symbolPaint)
                        val arrowX = cx
                        if (icon == Icon.IMPORT) {
                            canvas.drawLine(arrowX, t + s * .12f, arrowX, cy + s * .08f, symbolPaint)
                            canvas.drawLine(arrowX, cy + s * .08f, arrowX - s * .20f, cy - s * .10f, symbolPaint)
                            canvas.drawLine(arrowX, cy + s * .08f, arrowX + s * .20f, cy - s * .10f, symbolPaint)
                        } else {
                            canvas.drawLine(arrowX, cy + s * .08f, arrowX, t + s * .12f, symbolPaint)
                            canvas.drawLine(arrowX, t + s * .12f, arrowX - s * .20f, t + s * .30f, symbolPaint)
                            canvas.drawLine(arrowX, t + s * .12f, arrowX + s * .20f, t + s * .30f, symbolPaint)
                        }
                    }
                    Icon.FULL_SCALE_OPEN, Icon.FULL_SCALE_CLOSE -> {
                        canvas.save()
                        canvas.rotate(if (icon == Icon.FULL_SCALE_OPEN) -45f else 135f, cx, cy)
                        canvas.drawLine(l + s * .18f, cy, r - s * .18f, cy, symbolPaint)
                        val p = Path().apply {
                            moveTo(r - s * .18f, cy)
                            lineTo(r - s * .42f, cy - s * .24f)
                            moveTo(r - s * .18f, cy)
                            lineTo(r - s * .42f, cy + s * .24f)
                        }
                        canvas.drawPath(p, symbolPaint)
                        canvas.restore()
                    }
                    Icon.SPECTRUM -> {
                        val p = Path().apply {
                            moveTo(l, b - s * .10f)
                            lineTo(l + s * .12f, b - s * .10f)
                            cubicTo(l + s * .25f, b - s * .10f, l + s * .28f, cy, l + s * .38f, cy)
                            cubicTo(l + s * .47f, cy, l + s * .48f, b - s * .10f, l + s * .55f, b - s * .10f)
                            cubicTo(l + s * .66f, b - s * .10f, l + s * .72f, t, l + s * .80f, t)
                            cubicTo(l + s * .89f, t, l + s * .90f, b - s * .10f, r, b - s * .10f)
                        }
                        canvas.drawPath(p, symbolPaint)
                        canvas.drawLine(l, b - s * .10f, r, b - s * .10f, symbolPaint)
                    }
                    Icon.SPECTROGRAM -> {
                        symbolPaint.color = Color.rgb(190, 126, 255)
                        canvas.drawLine(l + s * .08f, t, l + s * .08f, b - s * .08f, symbolPaint)
                        canvas.drawLine(l + s * .08f, b - s * .08f, r, b - s * .08f, symbolPaint)
                        for (row in 0..2) {
                            val baseY = b - s * (.22f + row * .22f)
                            val wave = Path().apply {
                                moveTo(l + s * .18f, baseY)
                                cubicTo(l + s * .38f, baseY - s * .02f, l + s * .35f, baseY - s * .56f, l + s * .58f, baseY - s * .45f)
                                cubicTo(l + s * .78f, baseY - s * .34f, l + s * .80f, baseY + s * .12f, r - s * .04f, baseY - s * .58f)
                            }
                            canvas.drawPath(wave, symbolPaint)
                        }
                        symbolPaint.color = Color.WHITE
                    }
                    else -> Unit
                }
            }
        }
    }
}

private class ToneWaveformIconView(
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

@android.annotation.TargetApi(26)
class MainActivity : Activity() {

    private lateinit var spectrumView: SpectrumView
    private lateinit var statusText: TextView
    private lateinit var peakText: TextView
    private lateinit var startButton: Button
    private var headerPeakFrequencyText: TextView? = null
    private var headerPeakDbText: TextView? = null
    private var headerSplText: TextView? = null
    private var splCalibrationOffsetDb: Double? = null
    private var splManualCalibrationOffsetDb: Double? = null
    private var splSystemCalibrationOffsetDb: Double? = null
    private var splDetailDialog: Dialog? = null
    private var splDetailValueView: TextView? = null
    private var splDetailInfoView: TextView? = null
    private var splDetailDeltaView: TextView? = null
    private var splScaleMeterView: SplScaleMeterView? = null
    private var splHasCurrentSample = false
    private val splRecentReadings = java.util.ArrayDeque<Pair<Long, Double>>()
    private var spectrumHeader: android.widget.FrameLayout? = null
    private var spectrumHeaderCompactHeightPx = 0
    private var rangePickerButton: Button? = null
    private var keyboardControlsRow: LinearLayout? = null
    private var headerTitleText: TextView? = null
    private var splitContainer: LinearLayout? = null
    private var tonePanel: View? = null
    private var keyboardPanel: View? = null
    private var keyboardToggleButton: Button? = null
    private var fullScaleKeysButton: Button? = null
    private var fullScaleKeysDialog: Dialog? = null
    private var keyboardSectionView: View? = null
    private var fullScaleKeyboardOpen = false
    private var portraitGraphControlsRow: LinearLayout? = null
    private var resetDisplayButton: AnalyzerIconButton? = null
    private var spectrogramModeButton: AnalyzerIconButton? = null
    private var soundIdPanel: View? = null
    private var soundModeOpen = false
    private var switchAnalyzerMode: ((String) -> Unit)? = null
    private var toneModeOpen = false
    private var keyboardModeOpen = false
    private var toneToggleButton: Button? = null
    private var axisToggleButton: Button? = null
    private var mainActionRow: LinearLayout? = null
    @Volatile private var soundIdActive = false
    private var soundIdBestSoundView: TextView? = null
    private var soundIdScoreView: TextView? = null
    private var soundIdAlternativesView: TextView? = null
    private var soundIdPersonalButton: Button? = null
    private var soundIdDurationView: TextView? = null
    private var soundIdToggleButton: Button? = null
    private var soundIdModeButton: Button? = null
    private var soundIdDialog: AlertDialog? = null
    private var soundIdLivePanel: LinearLayout? = null
    private var soundIdSummaryPanel: LinearLayout? = null
    private var soundIdSummaryChart: SoundSessionChartView? = null
    private var soundIdSummaryTimeline: SoundSessionTimelineView? = null
    private var soundIdPdfButton: Button? = null
    private var soundIdLastSummary: SoundSessionSummary? = null
    private var personalSoundListRefresh: (() -> Unit)? = null
    private var pendingSoundIdPdf: ByteArray? = null
    private val personalSoundExportInProgress = AtomicBoolean(false)
    private val personalSoundImportInProgress = AtomicBoolean(false)
    @Volatile private var personalPreviewTrack: AudioTrack? = null
    private var personalPreviewKey: String? = null
    private var personalPreviewButton: Button? = null
    private var personalPreviewPaused = false
    private var soundIdListeningStartedAt = 0L
    private val soundIdTimerHandler = Handler(Looper.getMainLooper())
    private val soundIdTimerRunnable = object : Runnable {
        override fun run() {
            if (soundIdActive) {
                val seconds = ((SystemClock.elapsedRealtime() - soundIdListeningStartedAt) / 1000L).coerceAtLeast(0L)
                soundIdDurationView?.text = "Listening · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                soundIdTimerHandler.postDelayed(this, 1000L)
            }
        }
    }
    private val soundIdEngine by lazy {
        SoundIdEngine(applicationContext) { result ->
            runOnUiThread {
                soundIdBestSoundView?.text = result.bestSound
                soundIdScoreView?.text = if (result.modelScorePercent > 0) {
                    "${if (result.isPersonalMatch) "Saved sample similarity" else "Match strength"} · ${result.modelScorePercent}%"
                } else ""
                soundIdAlternativesView?.text = result.alternatives
                result.sessionSummary?.let(::showSoundIdSummary)
            }
        }
    }
    private val soundIdExportRequestCode = 72
    private val personalSoundExportFolderRequestCode = 73
    private val personalSoundImportRequestCode = 74
    @Volatile private var tonePlaying = false
    @Volatile private var toneFrequency = 440
    private var tonePeakFollowEnabled = false
    private var tonePeakFollowWindowStartedAt = 0L
    private var tonePeakBestFrequency = 0.0
    private var tonePeakBestDb = -120.0
    private var latestAnalyzedPeakFrequency = 0.0
    private var latestAnalyzedPeakDb = -120.0
    private var latestAnalyzedRmsDb = -120.0
    private var tonePeakPresetButton: AnalyzerIconButton? = null
    private data class PianoSample(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int
    )

    private data class PianoVoice(
        val note: Int,
        val sample: PianoSample,
        var position: Double = 0.0,
        var volume: Float,
        val pitchRatio: Double = 1.0,
        var releaseRemainingFrames: Int = 0,
        var releaseTotalFrames: Int = 0
    )

    private class PianoReverbProcessor(private val sampleRate: Int, private val stereoOffset: Int = 0) {
        private class CombFilter(size: Int) {
            private val buffer = FloatArray(size.coerceAtLeast(1))
            private var index = 0
            private var filtered = 0f

            fun process(input: Float): Float {
                val output = buffer[index]
                filtered = output * 0.78f + filtered * 0.22f
                buffer[index] = input + filtered * 0.72f
                index = (index + 1) % buffer.size
                return output
            }
        }

        private class AllPassFilter(size: Int) {
            private val buffer = FloatArray(size.coerceAtLeast(1))
            private var index = 0

            fun process(input: Float): Float {
                val delayed = buffer[index]
                val output = delayed - input
                buffer[index] = input + delayed * 0.5f
                index = (index + 1) % buffer.size
                return output
            }
        }

        private fun scaledSize(baseSize: Int): Int =
            (baseSize * sampleRate / 44100f).roundToInt() + stereoOffset

        private val combFilters = intArrayOf(1116, 1188, 1277, 1356)
            .map { CombFilter(scaledSize(it)) }
        private val allPassFilters = intArrayOf(556, 441)
            .map { AllPassFilter(scaledSize(it)) }

        fun process(input: Float): Float {
            var output = 0f
            for (filter in combFilters) output += filter.process(input)
            output *= 0.25f
            for (filter in allPassFilters) output = filter.process(output)
            return output
        }
    }

    private val pianoSamples = ConcurrentHashMap<Int, PianoSample>()
    private val pianoHeldNotes = mutableSetOf<Int>()
    private val pianoPressedTargets = mutableMapOf<Int, Int>()
    private val pianoAudioLock = Any()
    private val pianoVoices = mutableListOf<PianoVoice>()
    private var pianoAudioTrack: AudioTrack? = null
    private var pianoAudioThread: Thread? = null
    @Volatile private var pianoEngineRunning = false
    @Volatile private var pianoSamplesLoading = false
    @Volatile private var pianoSamplesInitialized = false
    private val pianoLoadedCount = AtomicInteger(0)
    private val pianoLoadFailedCount = AtomicInteger(0)
    private var pianoVolume = 0.8f
    @Volatile private var pianoReverbEnabled = true
    private var pianoReverbButton: Button? = null
    private var pianoTranspose = 0
    private var pianoVolumeSlider: SeekBar? = null
    private var pianoVolumeLabel: TextView? = null
    private var pianoOctaveButton: Button? = null
    private var pianoNoteReadout: TextView? = null
    private val pianoAudioShutdownHandler = Handler(Looper.getMainLooper())
    private val pianoAudioShutdownRunnable = Runnable { stopPianoAudioEngine() }

    private val pianoOutputRate = 44100
    private val pianoBlockFrames = 256
    private val pianoReverbLeft = PianoReverbProcessor(pianoOutputRate)
    private val pianoReverbRight = PianoReverbProcessor(pianoOutputRate, stereoOffset = 23)

    /** Decode the middle octave first, then the remaining OGG samples off the UI thread. */
    private fun initializePianoSamples() {
        if (pianoSamplesLoading || pianoSamplesInitialized) return
        pianoSamplesInitialized = true
        pianoSamplesLoading = true
        val names = arrayOf("c", "cs", "d", "ds", "e", "f", "fs", "g", "gs", "a", "as", "b")
        // Decode the middle octave first, where most users begin playing, then fill
        // outward. A few independent decoders make common keys available sooner.
        val firstNotes = (60..71).toList()
        val noteOrder = firstNotes + (36..96).filterNot { it in firstNotes }.sortedBy { abs(it - 65) }
        val nextNote = AtomicInteger(0)
        val missing = AtomicInteger(0)
        val workersRemaining = AtomicInteger(2)
        repeat(2) { workerIndex ->
            Thread({
                while (true) {
                    val index = nextNote.getAndIncrement()
                    if (index >= noteOrder.size) break
                    val note = noteOrder[index]
                    val resourceName = "piano_${names[note % 12]}${note / 12 - 1}"
                    val resourceId = resources.getIdentifier(resourceName, "raw", packageName)
                    if (resourceId == 0) {
                        missing.incrementAndGet()
                        Log.w(PIANO_LOAD_TAG, "Missing raw piano resource: $resourceName")
                        continue
                    }
                    try {
                        val decoded = decodePianoOgg(resourceId)
                        pianoSamples[note] = decoded
                        pianoLoadedCount.incrementAndGet()
                        runOnUiThread {
                            pianoHeldNotes.filter { it.coerceIn(36, 96) == note }
                                .forEach(::startPianoSample)
                        }
                    } catch (e: Exception) {
                        pianoLoadFailedCount.incrementAndGet()
                        Log.e(PIANO_LOAD_TAG, "Failed to decode $resourceName", e)
                    }
                }
                if (workersRemaining.decrementAndGet() == 0) {
                    pianoSamplesLoading = false
                    Log.i(PIANO_LOAD_TAG, "Piano ready: ${pianoLoadedCount.get()} decoded, ${pianoLoadFailedCount.get()} failed, ${missing.get()} missing")
                }
            }, "PianoSampleDecoder-$workerIndex").apply { priority = Thread.NORM_PRIORITY; start() }
        }
    }

    private fun startPianoAudioEngine() {
        if (pianoEngineRunning || pianoAudioTrack?.state == AudioTrack.STATE_INITIALIZED) return
        val minBuffer = AudioTrack.getMinBufferSize(
            pianoOutputRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(PIANO_LOAD_TAG, "AudioTrack does not support the requested output format: $minBuffer")
            return
        }
        val bufferBytes = max(minBuffer, pianoBlockFrames * 2 * 2 * 4)
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            pianoOutputRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
            AudioTrack.MODE_STREAM
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            Log.e(PIANO_LOAD_TAG, "Could not initialize piano AudioTrack")
            return
        }
        pianoAudioTrack = track
        pianoEngineRunning = true
        track.play()
        pianoAudioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val output = ShortArray(pianoBlockFrames * 2)
            while (pianoEngineRunning) {
                java.util.Arrays.fill(output, 0)
                synchronized(pianoAudioLock) {
                    val iterator = pianoVoices.iterator()
                    while (iterator.hasNext()) {
                        val voice = iterator.next()
                        val sample = voice.sample
                        val frameCount = sample.samples.size / sample.channels
                        if (frameCount <= 1 || voice.position >= frameCount - 1) {
                            iterator.remove()
                            continue
                        }
                        for (frame in 0 until pianoBlockFrames) {
                            val index = voice.position.toInt()
                            if (index >= frameCount - 1) break
                            val fraction = (voice.position - index).toFloat()
                            val leftA = sample.samples[index * sample.channels].toFloat()
                            val leftB = sample.samples[(index + 1) * sample.channels].toFloat()
                            val left = leftA + (leftB - leftA) * fraction
                            val right = if (sample.channels > 1) {
                                val rightA = sample.samples[index * sample.channels + 1].toFloat()
                                val rightB = sample.samples[(index + 1) * sample.channels + 1].toFloat()
                                rightA + (rightB - rightA) * fraction
                            } else left
                            var gain = voice.volume
                            if (voice.releaseRemainingFrames > 0) {
                                gain *= voice.releaseRemainingFrames.toFloat() / voice.releaseTotalFrames.coerceAtLeast(1)
                                voice.releaseRemainingFrames--
                            }
                            val outIndex = frame * 2
                            output[outIndex] = (output[outIndex].toInt() + (left * gain).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            output[outIndex + 1] = (output[outIndex + 1].toInt() + (right * gain).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            voice.position += sample.sampleRate.toDouble() / pianoOutputRate * voice.pitchRatio
                        }
                        if (voice.releaseTotalFrames > 0 && voice.releaseRemainingFrames <= 0) iterator.remove()
                    }
                }
                val reverbEnabled = pianoReverbEnabled
                for (frame in 0 until pianoBlockFrames) {
                    val outIndex = frame * 2
                    val dryLeft = output[outIndex].toFloat() / 32768f
                    val dryRight = output[outIndex + 1].toFloat() / 32768f
                    val wetLeft = pianoReverbLeft.process(if (reverbEnabled) dryLeft else 0f)
                    val wetRight = pianoReverbRight.process(if (reverbEnabled) dryRight else 0f)
                    if (reverbEnabled) {
                        output[outIndex] = ((dryLeft + wetLeft * 0.22f) * 32767f)
                            .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        output[outIndex + 1] = ((dryRight + wetRight * 0.22f) * 32767f)
                            .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                val written = track.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0 && pianoEngineRunning) Log.e(PIANO_LOAD_TAG, "AudioTrack write failed: $written")
            }
        }, "PianoAudioEngine").apply { start() }
    }

    private fun schedulePianoAudioShutdown() {
        pianoAudioShutdownHandler.removeCallbacks(pianoAudioShutdownRunnable)
        pianoAudioShutdownHandler.postDelayed(pianoAudioShutdownRunnable, 12_000L)
    }

    private fun stopPianoAudioEngine() {
        pianoAudioShutdownHandler.removeCallbacks(pianoAudioShutdownRunnable)
        pianoEngineRunning = false
        val track = pianoAudioTrack
        try { track?.pause() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { pianoAudioThread?.join(350) } catch (_: InterruptedException) {}
        pianoAudioThread = null
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        pianoAudioTrack = null
        synchronized(pianoAudioLock) { pianoVoices.clear() }
        pianoHeldNotes.clear()
        pianoPressedTargets.clear()
    }

    private fun updatePianoVolumeControls() {
        val percent = (pianoVolume * 100).roundToInt().coerceIn(0, 100)
        pianoVolumeLabel?.text = "VOL $percent%"
        pianoVolumeSlider?.let { if (it.progress != percent) it.progress = percent }
        synchronized(pianoAudioLock) { pianoVoices.forEach { it.volume = pianoVolume } }
    }

    private fun applyKeyboardVolumeSafeInset() {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val row = keyboardControlsRow ?: return
        val volumeCell = row.getChildAt(0) ?: return
        val insets = window.decorView.rootWindowInsets ?: return
        val cameraInset = insets.displayCutout?.safeInsetLeft ?: 0
        val rowLocation = IntArray(2)
        row.getLocationOnScreen(rowLocation)
        val localInset = (cameraInset - rowLocation[0]).coerceAtLeast(0)
        val gap = (6f * resources.displayMetrics.density).roundToInt()
        val targetMargin = if (localInset > 0) localInset + gap else 0
        val params = volumeCell.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.marginStart != targetMargin) {
            params.marginStart = targetMargin
            volumeCell.layoutParams = params
        }
    }

    private fun showPianoVolumeDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val value = TextView(this).apply {
            text = "${(pianoVolume * 100).roundToInt()}%"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = (pianoVolume * 100).roundToInt()
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                pianoVolume = progress.coerceIn(0, 100).toFloat() / 100f
                value.text = "$progress%"
                updatePianoVolumeControls()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        content.addView(value, LinearLayout.LayoutParams(-1, dp(30)))
        content.addView(slider, LinearLayout.LayoutParams(-1, dp(48)))
        AlertDialog.Builder(this)
            .setTitle("Piano volume")
            .setView(content)
            .setPositiveButton("Done", null)
            .show()
    }

    /** Decode an OGG resource into interleaved PCM16 without blocking the UI thread. */
    private fun decodePianoOgg(resourceId: Int): PianoSample {
        val extractor = MediaExtractor()
        val outputBytes = ByteArrayOutputStream()
        var codec: MediaCodec? = null
        var afd: android.content.res.AssetFileDescriptor? = null
        var outputRate = pianoOutputRate
        var outputChannels = 1
        try {
            afd = resources.openRawResourceFd(resourceId)
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) throw IllegalStateException("No audio track in resource")
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Missing audio MIME type")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw IllegalStateException("Missing decoder input buffer")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                            throw IllegalStateException("Decoder did not output PCM16")
                        }
                    }
                    outputIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            outputBytes.write(chunk)
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
            try { afd?.close() } catch (_: Exception) {}
        }
        val raw = outputBytes.toByteArray()
        val shorts = ShortArray(raw.size / 2)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = buffer.short
        return PianoSample(shorts, outputRate, outputChannels.coerceIn(1, 2))
    }

    private fun playPianoSample(midiNote: Int) {
        pianoAudioShutdownHandler.removeCallbacks(pianoAudioShutdownRunnable)
        initializePianoSamples()
        startPianoAudioEngine()
        val playedNote = midiNote + pianoTranspose
        pianoPressedTargets[midiNote] = playedNote
        pianoHeldNotes.add(playedNote)
        updatePianoNoteReadout(playedNote)
        startPianoSample(playedNote)
    }

    private fun startPianoSample(playedNote: Int) {
        val sourceNote = playedNote.coerceIn(36, 96)
        val sample = pianoSamples[sourceNote] ?: return
        val pitchRatio = 2.0.pow((playedNote - sourceNote) / 12.0)
        synchronized(pianoAudioLock) {
            // Retrigger this note without creating an unbounded number of voices.
            pianoVoices.removeAll { it.note == playedNote }
            pianoVoices.add(PianoVoice(playedNote, sample, volume = pianoVolume, pitchRatio = pitchRatio))
        }
    }

    private fun releasePianoSample(midiNote: Int) {
        val playedNote = pianoPressedTargets.remove(midiNote) ?: (midiNote + pianoTranspose)
        pianoHeldNotes.remove(playedNote)
        if (pianoReverbEnabled) {
            schedulePianoAudioShutdown()
            return
        }
        synchronized(pianoAudioLock) {
            for (voice in pianoVoices) {
                if (voice.note == playedNote && voice.releaseTotalFrames == 0) {
                    voice.releaseTotalFrames = (pianoOutputRate * 0.42f).toInt()
                    voice.releaseRemainingFrames = voice.releaseTotalFrames
                }
            }
        }
        schedulePianoAudioShutdown()
    }

    private fun updatePianoNoteReadout(midiNote: Int) {
        val noteName = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
            .let { it[Math.floorMod(midiNote, 12)] + (Math.floorDiv(midiNote, 12) - 1) }
        val frequency = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
        pianoNoteReadout?.text = String.format(java.util.Locale.US, "%s\n%.1f Hz", noteName, frequency)
    }

    private var toneMinFrequency = 20
    private var toneMaxFrequency = 20000
    @Volatile private var toneVolume = 0.5
    @Volatile private var toneWaveform = "Sine"
    @Volatile private var toneOutputChannel = "Stereo"
    @Volatile private var toneDtmfActive = false
    @Volatile private var toneDtmfLowFrequency = 697
    @Volatile private var toneDtmfHighFrequency = 1209
    private var toneDtmfSelectedKey = "1"
    private var toneDtmfModeButton: Button? = null
    private var toneWaveformButton: View? = null
    @Volatile private var toneStopRequested = false
    private var toneTrack: AudioTrack? = null
    private var toneThread: Thread? = null
    private var tonePlayButton: Button? = null
    @Volatile private var toneSweepActive = false
    private var toneSweepStartedAt = 0L
    private var toneSweepDurationMs = 8000L
    private var toneSweepStartFrequency = 20
    private var toneSweepEndFrequency = 20000
    @Volatile private var toneSweepAscending = true
    private val toneSweepHandler = Handler(Looper.getMainLooper())
    private var toneSweepUiUpdate: (() -> Unit)? = null
    private var toneSweepButton: AnalyzerIconButton? = null
    private var toneSweepRangeButton: Button? = null
    private var toneSweepDurationButton: Button? = null
    private var toneSweepDirectionButton: Button? = null

    private fun loadCustomToneFrequencies(): List<Int> = getSharedPreferences(
        "tone_generator", Context.MODE_PRIVATE
    ).getStringSet("custom_frequency_presets", emptySet()).orEmpty()
        .mapNotNull { it.toIntOrNull() }
        .filter { it in 20..20000 }
        .distinct()
        .sorted()

    private fun saveCustomToneFrequencies(frequencies: Collection<Int>) {
        getSharedPreferences("tone_generator", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("custom_frequency_presets", frequencies.map { it.toString() }.toSet())
            .apply()
    }

    private fun showAddTonePresetDialog(
        minFrequency: Int,
        maxFrequency: Int,
        existingFrequencies: Set<Int>,
        onAdded: () -> Unit
    ) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 21, 31))
                cornerRadius = dp(20).toFloat()
            }
        }
        content.addView(TextView(this).apply {
            text = "Add frequency preset"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Enter a frequency from $minFrequency to $maxFrequency Hz."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(10))
        })
        val input = EditText(this).apply {
            setText(toneFrequency.toString())
            textSize = 19f
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            isSingleLine = true
            isCursorVisible = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = false
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 23, 36))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(67, 91, 119))
            }
            setSelection(text.length)
        }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(48)))

        val keypad = android.widget.GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
        }
        fun addFrequencyKey(label: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = label
                textSize = 16f
                minWidth = dp(48)
                minimumWidth = dp(48)
                minHeight = dp(42)
                minimumHeight = dp(42)
                isFocusable = false
                setPadding(0, 0, 0, 0)
                setTextColor(Color.rgb(255, 213, 90))
                setOnClickListener { action() }
            }
            styleNumericDialogButton(button)
            keypad.addView(button, android.widget.GridLayout.LayoutParams().apply {
                width = dp(52)
                height = dp(42)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        fun appendFrequencyDigit(digit: String) {
            val editable = input.text
            val start = input.selectionStart.coerceIn(0, editable.length)
            val end = input.selectionEnd.coerceIn(start, editable.length)
            if (editable.length - (end - start) < 5) {
                editable.replace(start, end, digit)
                input.setSelection(start + digit.length)
            }
        }
        addFrequencyKey("1") { appendFrequencyDigit("1") }
        addFrequencyKey("2") { appendFrequencyDigit("2") }
        addFrequencyKey("3") { appendFrequencyDigit("3") }
        addFrequencyKey("4") { appendFrequencyDigit("4") }
        addFrequencyKey("5") { appendFrequencyDigit("5") }
        addFrequencyKey("6") { appendFrequencyDigit("6") }
        addFrequencyKey("7") { appendFrequencyDigit("7") }
        addFrequencyKey("8") { appendFrequencyDigit("8") }
        addFrequencyKey("9") { appendFrequencyDigit("9") }
        addFrequencyKey("⌫") {
            val editable = input.text
            val start = input.selectionStart.coerceIn(0, editable.length)
            val end = input.selectionEnd.coerceIn(start, editable.length)
            when {
                end > start -> editable.delete(start, end)
                start > 0 -> editable.delete(start - 1, start)
            }
            input.setSelection((if (end > start) start else (start - 1).coerceAtLeast(0)).coerceAtMost(editable.length))
        }
        addFrequencyKey("0") { appendFrequencyDigit("0") }
        addFrequencyKey("CLR") {
            input.setText("")
            input.setSelection(0)
        }
        content.addView(keypad, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(5)
        })

        lateinit var dialog: Dialog
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 57, 75))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { dialog.dismiss() }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        val add = Button(this).apply {
            text = "Add"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(39, 113, 147))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                val frequency = input.text.toString().trim().toIntOrNull()
                when {
                    frequency == null -> input.error = "Enter a frequency in Hz"
                    frequency !in minFrequency..maxFrequency -> input.error = "Use $minFrequency–$maxFrequency Hz"
                    frequency in existingFrequencies || frequency in loadCustomToneFrequencies() ->
                        input.error = "That frequency is already in the presets"
                    else -> {
                        saveCustomToneFrequencies(loadCustomToneFrequencies() + frequency)
                        dialog.dismiss()
                        onAdded()
                    }
                }
            }
        }
        actions.addView(add, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(actions)
        dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
        }
        centerDialogOnScreen(dialog, dp(320))
        dialog.show()
        input.requestFocus()
        input.setSelection(input.text.length)
    }

    private fun showDeleteTonePresetDialog(frequency: Int, onDelete: () -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 21, 31))
                cornerRadius = dp(20).toFloat()
            }
        }
        content.addView(TextView(this).apply {
            text = "Delete $frequency Hz preset?"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "This removes the custom frequency from your saved presets."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(7), 0, dp(12))
        })
        lateinit var dialog: Dialog
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 57, 75))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        actions.addView(Button(this).apply {
            text = "Delete"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(164, 69, 80))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                saveCustomToneFrequencies(loadCustomToneFrequencies().filterNot { it == frequency })
                dialog.dismiss()
                onDelete()
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(actions)
        dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        centerDialogOnScreen(dialog, dp(320))
        dialog.show()
    }

    private fun isNoiseTone(): Boolean = toneWaveform == "White noise" || toneWaveform == "Pink noise"

    private fun isUsableTonePeak(frequency: Double, peakDb: Double, rmsDb: Double): Boolean {
        return frequency.isFinite() && frequency in 20.0..20000.0 &&
                peakDb > -85.0 && rmsDb > -90.0 && peakDb - rmsDb >= 1.5
    }

    private fun setTonePeakFollowEnabled(enabled: Boolean, refreshUi: Boolean = true) {
        tonePeakFollowEnabled = enabled
        tonePeakFollowWindowStartedAt = if (enabled) SystemClock.elapsedRealtime() else 0L
        tonePeakBestFrequency = 0.0
        tonePeakBestDb = -120.0
        if (enabled && isUsableTonePeak(latestAnalyzedPeakFrequency, latestAnalyzedPeakDb, latestAnalyzedRmsDb)) {
            tonePeakBestFrequency = latestAnalyzedPeakFrequency
            tonePeakBestDb = latestAnalyzedPeakDb
            toneFrequency = latestAnalyzedPeakFrequency.roundToInt().coerceIn(20, 20000)
        }
        tonePeakPresetButton?.backgroundTintList = if (enabled) {
            android.content.res.ColorStateList.valueOf(Color.rgb(29, 105, 145))
        } else null
        tonePeakPresetButton?.contentDescription = if (enabled) {
            "Peak tracking active; frequency updates every five seconds and holds its last value. Tap to change."
        } else "Frequency presets"
        if (refreshUi) toneSweepUiUpdate?.invoke()
    }

    private fun updateTonePeakFollow(frequency: Double, peakDb: Double, rmsDb: Double) {
        if (!tonePeakFollowEnabled) return

        if (isUsableTonePeak(frequency, peakDb, rmsDb) && peakDb > tonePeakBestDb) {
            tonePeakBestFrequency = frequency
            tonePeakBestDb = peakDb
        }

        val now = SystemClock.elapsedRealtime()
        if (now - tonePeakFollowWindowStartedAt < 5000L) return

        if (tonePeakBestFrequency > 0.0) {
            toneFrequency = tonePeakBestFrequency.roundToInt().coerceIn(20, 20000)
            toneSweepUiUpdate?.invoke()
        }

        // Keep the selected frequency when the room falls quiet. Only a new valid
        // peak in the next five-second window can replace it.
        tonePeakFollowWindowStartedAt = now
        tonePeakBestFrequency = 0.0
        tonePeakBestDb = -120.0
        if (isUsableTonePeak(frequency, peakDb, rmsDb)) {
            tonePeakBestFrequency = frequency
            tonePeakBestDb = peakDb
        }
    }

    private val toneSweepRunnable = object : Runnable {
        override fun run() {
            if (!toneSweepActive) return
            val low = toneSweepStartFrequency.coerceIn(20, 19999)
            val high = toneSweepEndFrequency.coerceIn(low + 1, 20000)
            val start = if (toneSweepAscending) low else high
            val end = if (toneSweepAscending) high else low
            val progress = ((SystemClock.elapsedRealtime() - toneSweepStartedAt).toDouble() / toneSweepDurationMs)
                .coerceIn(0.0, 1.0)
            toneFrequency = (start * exp(progress * ln(end.toDouble() / start))).roundToInt().coerceIn(low, high)
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
    private var running = false

    private var recorder: AudioRecord? = null
    private var audioThread: Thread? = null

    private val sampleRate = 48000

    // FFT configuration
    private val fftSize = 8192

    // 75% overlap: process a new FFT every 2048 samples.
    private val hopSize = fftSize / 4

    private val permissionRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)
        hideSystemBars()

        val savedSplOffset = getSharedPreferences("sound_level_calibration", Context.MODE_PRIVATE)
            .getFloat("offset_db", Float.NaN)
        splManualCalibrationOffsetDb = savedSplOffset.takeIf { it.isFinite() }?.toDouble()
        splCalibrationOffsetDb = splManualCalibrationOffsetDb

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
                    dp(42)
                )
            }

            fun configureActionButton(button: Button, primary: Boolean = false) {
                button.textSize = 14f
                button.minHeight = dp(38)
                button.minimumHeight = dp(38)
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
                    button is AnalyzerIconButton && (button.icon == AnalyzerIconButton.Icon.LIN || button.icon == AnalyzerIconButton.Icon.LOG) -> dp(84)
                    button is AnalyzerIconButton && (button.icon == AnalyzerIconButton.Icon.TONE || button.icon == AnalyzerIconButton.Icon.SOUND || button.icon == AnalyzerIconButton.Icon.SPECTRUM || button.icon == AnalyzerIconButton.Icon.SPECTROGRAM) -> dp(46)
                    iconButton -> dp(42)
                    else -> ViewGroup.LayoutParams.WRAP_CONTENT
                }
                actionRow.addView(button, LinearLayout.LayoutParams(buttonWidth, dp(38)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }

            // Reset remains the rightmost graph control after analysis start/stop is removed.
            val resetButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.RESET)
            resetDisplayButton = resetButton
            configureActionButton(resetButton)
            resetButton.layoutParams = (resetButton.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(39)
                setMargins(dp(3), dp(2), dp(3), dp(2))
            }
            resetButton.contentDescription = "Reset display"
            resetButton.setOnClickListener {
                leaveFullScaleKeyboard()
                spectrumView.resetView(useKeysFrequencyRange = keyboardModeOpen)
            }

            val spectrogramButton = AnalyzerIconButton(this, if (spectrumView.isSpectrogramEnabled()) AnalyzerIconButton.Icon.SPECTRUM else AnalyzerIconButton.Icon.SPECTROGRAM).apply {
                // Show the mode this button will switch TO, not the current mode.
                setOnClickListener {
                    leaveFullScaleKeyboard()
                    val enabled = spectrumView.toggleSpectrogram()
                    icon = if (enabled) AnalyzerIconButton.Icon.SPECTRUM else AnalyzerIconButton.Icon.SPECTROGRAM
                }
            }
            spectrogramModeButton = spectrogramButton
            configureActionButton(spectrogramButton)

            val axisButton = AnalyzerIconButton(this, if (spectrumView.isLinearFrequencyAxis()) AnalyzerIconButton.Icon.LOG else AnalyzerIconButton.Icon.LIN).apply {
                // Show the axis mode this button will switch TO.
                setOnClickListener {
                    leaveFullScaleKeyboard()
                    spectrumView.toggleFrequencyAxis()
                    icon = if (spectrumView.isLinearFrequencyAxis()) AnalyzerIconButton.Icon.LOG else AnalyzerIconButton.Icon.LIN
                }
            }
            axisToggleButton = axisButton
            configureActionButton(axisButton)

            // Flexible spacer pins the graph controls to the far right.
            val controlsSpacer = View(this)
            actionRow.addView(controlsSpacer, LinearLayout.LayoutParams(0, 1, 1f))

            val keysButton = Button(this).apply {
                text = "🎹"
                textSize = 20f
                contentDescription = "Piano keyboard"
            }
            keyboardToggleButton = keysButton
            configureActionButton(keysButton)
            keysButton.layoutParams = (keysButton.layoutParams as LinearLayout.LayoutParams).apply { width = dp(42) }
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

            val soundIdButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.SOUND).apply {
                setOnClickListener { showSoundIdDialog() }
            }
            soundIdModeButton = soundIdButton
            configureActionButton(soundIdButton)

            val toneButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.TONE)
            toneToggleButton = toneButton
            configureActionButton(toneButton)

            // Arrange the controls from left to right, with graph actions anchored at the right edge.
            actionRow.removeAllViews()
            actionRow.addView(toneButton)
            val keysGroup = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(3), 0, dp(3), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(17, 25, 36))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.rgb(49, 66, 83))
                }
                layoutTransition = android.animation.LayoutTransition()
            }
            (keysButton.parent as? ViewGroup)?.removeView(keysButton)
            (fullScaleButton.parent as? ViewGroup)?.removeView(fullScaleButton)
            keysGroup.addView(keysButton)
            keysGroup.addView(fullScaleButton)
            actionRow.addView(keysGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
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
            }
            actionBar.addView(
                actionRow,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)
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
            originalParams.height = dp(42)
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

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            keyboardControlsRow?.post { applyKeyboardVolumeSafeInset() }
        }
    }
    private fun installToneGeneratorSplit(toggleButton: Button, keysButton: Button) {
        val parent = spectrumView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(spectrumView)
        val originalParams = spectrumView.layoutParams
        parent.removeView(spectrumView)

        val split = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.FILL
            setBackgroundColor(Color.rgb(15, 23, 36))
        }
        spectrumView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f)
        split.addView(spectrumView)
        val portraitControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        portraitGraphControlsRow = portraitControlsRow
        split.addView(
            portraitControlsRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (42f * resources.displayMetrics.density).roundToInt()
            )
        )
        val panel = createToneGeneratorPanel()
        tonePanel = panel
        // Keep the graph dominant while slightly widening the tone controls.
        split.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f))
        panel.visibility = View.GONE

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

        val keyboardSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.FILL
            setPadding(dp(4), dp(4), dp(4), dp(2))
            setBackgroundColor(Color.rgb(15, 23, 36))
        }
        keyboardSectionView = keyboardSection
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        keyboardControlsRow = controls
        fun controlButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            minHeight = dp(34)
            minimumHeight = dp(34)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(3), 0, dp(3), 0)
            setTextColor(Color.WHITE)
            styleXButton(this, primary = false)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = dp(3)
            }
            setOnClickListener { action() }
        }
        val volumeCell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(5), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 32, 42))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1).coerceAtLeast(1), Color.rgb(56, 68, 77))
            }
            contentDescription = "Piano volume"
        }
        val volumeLabel = TextView(this).apply {
            text = "VOL ${(pianoVolume * 100).roundToInt()}%"
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val volumeSlider = SeekBar(this).apply {
            max = 100
            progress = (pianoVolume * 100).roundToInt().coerceIn(0, 100)
            contentDescription = "Piano volume level"
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(75, 196, 255))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(130, 216, 255))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(60, 76, 91))
        }
        volumeCell.addView(volumeLabel, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.MATCH_PARENT))
        volumeCell.addView(volumeSlider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                pianoVolume = progress.coerceIn(0, 100).toFloat() / 100f
                updatePianoVolumeControls()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        pianoVolumeSlider = volumeSlider
        pianoVolumeLabel = volumeLabel
        val octaveButton = controlButton("OCTAVE 0") {
            pianoTranspose = when (pianoTranspose) { 0 -> 12; 12 -> -12; else -> 0 }
            pianoOctaveButton?.text = when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }
            (keyboardPanel as? PianoKeyboardView)?.setTranspose(pianoTranspose)
        }
        val reverbButton = controlButton(if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF") {
            pianoReverbEnabled = !pianoReverbEnabled
            pianoReverbButton?.apply {
                text = if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF"
                styleXButton(this, primary = pianoReverbEnabled)
            }
        }
        pianoOctaveButton = octaveButton
        pianoReverbButton = reverbButton
        styleXButton(reverbButton, primary = pianoReverbEnabled)
        val noteReadout = TextView(this).apply {
            text = "--\n-- Hz"
            textSize = 9f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
            minHeight = dp(34)
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 32, 42))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(49, 66, 83))
            }
            contentDescription = "Last played piano note and frequency"
        }
        pianoNoteReadout = noteReadout
        controls.addView(volumeCell, LinearLayout.LayoutParams(0, dp(38), 2.3f).apply { marginEnd = dp(3) })
        controls.addView(octaveButton, LinearLayout.LayoutParams(0, dp(38), 0.9f).apply { marginEnd = dp(3) })
        controls.addView(reverbButton, LinearLayout.LayoutParams(0, dp(38), 0.9f).apply { marginEnd = dp(3) })
        controls.addView(noteReadout, LinearLayout.LayoutParams(0, dp(38), 0.9f).apply {
            marginEnd = dp(3)
        })
        keyboardSection.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val keys = PianoKeyboardView(this) { midiNote, pressed ->
            if (pressed) {
                stopTone()
                playPianoSample(midiNote)
            } else {
                releasePianoSample(midiNote)
            }
        }
        keys.setTranspose(pianoTranspose)
        keyboardPanel = keys
        keyboardSection.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyboardSection.visibility = View.GONE
        split.addView(keyboardSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        splitContainer = split
        parent.addView(split, index, originalParams)
        controls.post { applyKeyboardVolumeSafeInset() }

        fun showMode(mode: String) {
            val wasSoundModeOpen = soundModeOpen
            val wasKeyboardModeOpen = keyboardModeOpen
            toneModeOpen = mode == "tone"
            keyboardModeOpen = mode == "keys"
            soundModeOpen = mode == "sound"
            if (keyboardModeOpen) {
                pianoAudioShutdownHandler.removeCallbacks(pianoAudioShutdownRunnable)
                initializePianoSamples()
            } else if (wasKeyboardModeOpen) {
                schedulePianoAudioShutdown()
            }
            requestedOrientation = if (soundModeOpen) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            spectrumView.setSoundIdPortraitLayoutActive(soundModeOpen)
            fullScaleKeyboardOpen = false
            spectrumView.setKeysFrequencyRangeActive(keyboardModeOpen)
            spectrumView.setDbRangeMode(when {
                toneModeOpen -> "TONE"
                keyboardModeOpen -> "KEYS"
                else -> "DEFAULT"
            })
            panel.visibility = if (toneModeOpen) View.VISIBLE else View.GONE
            soundIdPanel?.visibility = if (soundModeOpen) View.VISIBLE else View.GONE
            keyboardSection.visibility = if (keyboardModeOpen) View.VISIBLE else View.GONE
            spectrumView.visibility = View.VISIBLE
            val portraitControls = listOfNotNull(spectrogramModeButton, axisToggleButton, resetDisplayButton)
            val landscapeControls = listOfNotNull(axisToggleButton, spectrogramModeButton, resetDisplayButton)
            if (soundModeOpen && !wasSoundModeOpen) {
                portraitControls.forEach { control ->
                    (control.parent as? ViewGroup)?.removeView(control)
                    portraitGraphControlsRow?.addView(control)
                }
            } else if (!soundModeOpen && wasSoundModeOpen) {
                landscapeControls.forEach { control ->
                    (control.parent as? ViewGroup)?.removeView(control)
                    mainActionRow?.addView(control)
                }
            }
            portraitGraphControlsRow?.visibility = if (soundModeOpen) View.VISIBLE else View.GONE
            rangePickerButton?.visibility = if (soundModeOpen) View.GONE else View.VISIBLE
            if (keyboardModeOpen || soundModeOpen) {
                split.orientation = LinearLayout.VERTICAL
                spectrumView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                if (keyboardModeOpen) {
                    keyboardSection.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                if (soundModeOpen) {
                    soundIdPanel?.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }
            } else {
                split.orientation = LinearLayout.HORIZONTAL
                spectrumView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f)
                panel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f)
                keyboardSection.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                soundIdPanel?.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f)
            }
            mainActionRow?.let { row ->
                row.gravity = if (soundModeOpen) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
                for (index in 0 until row.childCount) {
                    val child = row.getChildAt(index)
                    child.visibility = if (!soundModeOpen || child === soundIdModeButton) View.VISIBLE else View.GONE
                }
            }
            toggleButton.text = ""
            toggleButton.contentDescription = "Tone generator"
            toggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (toneModeOpen) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42)
            )
            keysButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (keyboardModeOpen) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42)
            )
            soundIdModeButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (soundModeOpen) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42)
            )
            fullScaleKeysButton?.visibility = if (keyboardModeOpen) View.VISIBLE else View.GONE
            if (!toneModeOpen && !keyboardModeOpen) {
                stopTone()
                resetTonePlayButton()
            } else if (keyboardModeOpen) {
                stopTone()
                resetTonePlayButton()
            }
            split.requestLayout()
            spectrumView.invalidate()
            if (keyboardModeOpen) keyboardControlsRow?.post { applyKeyboardVolumeSafeInset() }
        }
        switchAnalyzerMode = ::showMode
        toggleButton.setOnClickListener {
            leaveFullScaleKeyboard()
            showMode(if (toneModeOpen) "none" else "tone")
        }
        keysButton.setOnClickListener {
            if (fullScaleKeyboardOpen) {
                leaveFullScaleKeyboard()
                showMode("none")
                return@setOnClickListener
            }
            showMode(if (keyboardModeOpen) "none" else "keys")
        }
    }

    private fun createToneGeneratorPanel(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(19, 29, 43))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(49, 66, 83))
            }
            elevation = dp(2).toFloat()
        }

        val valueLabel = TextView(this).apply {
            text = "$toneFrequency Hz"
            textSize = 18f
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5))
            contentDescription = "Select tone frequency"
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 23, 36))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(67, 91, 119))
            }
        }

        // Keep frequency, waveform selection, and presets together in one compact row.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val presetsButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.PRESETS).apply {
            contentDescription = "Frequency presets"
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 32, 45))
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(56, 73, 91))
            }
            stateListAnimator = null
        }
        tonePeakPresetButton = presetsButton
        header.addView(presetsButton, LinearLayout.LayoutParams(dp(44), dp(42)).apply {
            marginEnd = dp(6)
        })
        header.addView(valueLabel, LinearLayout.LayoutParams(dp(112), dp(42)).apply {
            marginEnd = dp(6)
        })
        val selectedWaveformIcon = ToneWaveformIconView(this, toneWaveform, Color.rgb(255, 213, 90))
        val waveCell = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 23, 36))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(67, 91, 119))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "$toneWaveform signal. Tap to choose signal type."
            addView(selectedWaveformIcon, android.widget.FrameLayout.LayoutParams(dp(56), dp(38), Gravity.CENTER))
        }
        toneWaveformButton = waveCell
        header.addView(waveCell, LinearLayout.LayoutParams(dp(64), dp(42)).apply { marginEnd = dp(6) })
        val playButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.PLAY).apply {
            playStopIconScale = 0.72f
            roundedPlayStopGlyphs = true
            contentDescription = "Play selected tone"
            background = GradientDrawable().apply {
                setColor(Color.rgb(29, 125, 65))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(67, 91, 86))
            }
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(29, 125, 65))
            setTextColor(Color.WHITE)
            stateListAnimator = null
            setOnClickListener {
                if (tonePlaying) {
                    if (toneSweepActive) cancelToneSweep()
                    stopTone()
                    resetTonePlayButton()
                } else {
                    startTone()
                    if (tonePlaying) setTonePlayButtonPlaying(true)
                }
            }
        }
        tonePlayButton = playButton
        header.addView(playButton, LinearLayout.LayoutParams(dp(44), dp(42)))
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        waveCell.setOnClickListener {
            val waveforms = arrayOf("Sine", "Square", "Triangle", "Sawtooth", "White noise", "Pink noise")
            val picker = Dialog(this)
            val pickerContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(24, 31, 42))
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), Color.rgb(49, 66, 83))
                }
            }
            pickerContent.addView(TextView(this).apply {
                text = "SIGNAL"
                textSize = 13f
                setTextColor(Color.rgb(225, 233, 243))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            for (rowValues in waveforms.toList().chunked(2)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                rowValues.forEachIndexed { columnIndex, waveform ->
                    val option = android.widget.FrameLayout(this).apply {
                        background = GradientDrawable().apply {
                            setColor(if (toneWaveform == waveform) Color.rgb(48, 59, 75) else Color.rgb(15, 23, 36))
                            cornerRadius = dp(11).toFloat()
                            setStroke(dp(1), if (toneWaveform == waveform) Color.rgb(255, 213, 90) else Color.rgb(67, 91, 119))
                        }
                        isClickable = true
                        isFocusable = true
                        contentDescription = if (waveform.endsWith("noise", ignoreCase = true)) "$waveform signal" else "$waveform waveform"
                        addView(ToneWaveformIconView(this@MainActivity, waveform), android.widget.FrameLayout.LayoutParams(dp(58), dp(34), Gravity.CENTER))
                        setOnClickListener {
                            setTonePeakFollowEnabled(false, refreshUi = false)
                            toneWaveform = waveform
                            selectedWaveformIcon.waveform = waveform
                            waveCell.contentDescription = "$waveform signal. Tap to choose signal type."
                            toneSweepUiUpdate?.invoke()
                            setTonePlayButtonPlaying(tonePlaying)
                            picker.dismiss()
                        }
                    }
                    row.addView(option, LinearLayout.LayoutParams(dp(72), dp(48)).apply {
                        if (columnIndex > 0) marginStart = dp(4)
                        if (columnIndex == 0) marginEnd = dp(4)
                    })
                }
                pickerContent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                    if (pickerContent.childCount > 1) topMargin = dp(5)
                })
            }
            picker.setContentView(pickerContent)
            picker.window?.setBackgroundDrawableResource(android.R.color.transparent)
            centerDialogOnScreen(picker, dp(184))
            picker.show()
        }

        // Keep the sliders and actions centered directly beneath the selector row.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(1), dp(2), 0)
        }
        panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val slider = SeekBar(this).apply {
            max = 1000
            progress = ((kotlin.math.ln(toneFrequency / toneMinFrequency.toDouble()) / kotlin.math.ln(toneMaxFrequency / toneMinFrequency.toDouble())) * max).roundToInt().coerceIn(0, max)
        }
        controls.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(2), dp(5), dp(2), 0)
        })
        fun utilityIcon(icon: AnalyzerIconButton.Icon, description: String) = AnalyzerIconButton(this, icon).apply {
            contentDescription = description
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 32, 45))
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(56, 73, 91))
            }
            stateListAnimator = null
        }
        presetsButton.setOnClickListener {
            val low = toneMinFrequency.coerceAtLeast(20)
            val high = toneMaxFrequency.coerceAtMost(20000)
            val builtInPresets = listOf(20, 50, 60, 100, 120, 250, 440, 500, 1000, 2000, 5000, 10000, 15000, 20000)
                .filter { it in low..high }
            val customPresets = loadCustomToneFrequencies().filter { it in low..high }
            val frequencies = (builtInPresets + customPresets).distinct()
            val presetItems: List<Pair<Int?, Boolean>> = frequencies.map { it to (it in customPresets) } + listOf(null to false)
            val presetDialog = Dialog(this@MainActivity)
            val presetContent = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(24, 31, 42))
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.rgb(49, 66, 83))
                }
            }
            presetContent.addView(TextView(this@MainActivity).apply {
                text = "FREQUENCY PRESETS"
                textSize = 13f
                setTextColor(Color.rgb(225, 233, 243))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val presetGrid = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            for ((rowIndex, rowValues) in presetItems.chunked(3).withIndex()) {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                rowValues.forEachIndexed { columnIndex, item ->
                    val frequency = item.first
                    val isCustom = item.second
                    val label = frequency?.let {
                        if (it >= 1000 && it % 1000 == 0) "${it / 1000} kHz" else "$it Hz"
                    } ?: "PEAK"
                    val presetButton = Button(this@MainActivity).apply {
                        text = label
                        textSize = 13f
                        isAllCaps = false
                        minHeight = dp(42)
                        minimumHeight = dp(42)
                        contentDescription = when {
                            frequency == null -> "Follow the strongest detected frequency, updating every five seconds and holding the last value."
                            isCustom -> "Set tone frequency to $frequency hertz. Long-press to delete this custom preset."
                            else -> "Set tone frequency to $frequency hertz"
                        }
                        setOnClickListener {
                            cancelToneSweep()
                            if (frequency == null) {
                                setTonePeakFollowEnabled(true)
                            } else {
                                setTonePeakFollowEnabled(false, refreshUi = false)
                                toneFrequency = frequency
                                toneSweepUiUpdate?.invoke()
                            }
                            presetDialog.dismiss()
                        }
                    }
                    styleNumericDialogButton(presetButton)
                    presetButton.setTextColor(if (frequency == null) Color.rgb(125, 211, 252) else Color.rgb(255, 213, 90))
                    if (isCustom && frequency != null) {
                        presetButton.setOnLongClickListener {
                            showDeleteTonePresetDialog(frequency) {
                                presetDialog.dismiss()
                                presetsButton.post { presetsButton.performClick() }
                            }
                            true
                        }
                    }
                    row.addView(presetButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (columnIndex > 0) marginStart = dp(3)
                        if (columnIndex < 2) marginEnd = dp(3)
                    })
                }
                repeat(3 - rowValues.size) { index ->
                    val columnIndex = rowValues.size + index
                    row.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (columnIndex > 0) marginStart = dp(3)
                        if (columnIndex < 2) marginEnd = dp(3)
                    })
                }
                presetGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                    if (rowIndex > 0) topMargin = dp(5)
                })
            }
            val presetScroll = android.widget.ScrollView(this@MainActivity).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = presetItems.size > 15
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(presetGrid)
            }
            val visibleRows = ((presetItems.size + 2) / 3).coerceAtMost(5)
            val visibleGridHeight = dp(visibleRows * 42 + (visibleRows - 1).coerceAtLeast(0) * 5)
            presetContent.addView(presetScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, visibleGridHeight))
            val addPresetButton = Button(this@MainActivity).apply {
                text = "+  ADD FREQUENCY"
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.WHITE)
                setOnClickListener {
                    showAddTonePresetDialog(low, high, (builtInPresets + customPresets).toSet()) {
                        presetDialog.dismiss()
                        presetsButton.post { presetsButton.performClick() }
                    }
                }
            }
            styleNumericDialogButton(addPresetButton)
            addPresetButton.setTextColor(Color.rgb(105, 220, 185))
            presetContent.addView(addPresetButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(7)
            })
            val closeButton = Button(this@MainActivity).apply {
                text = "CANCEL"
                textSize = 11f
                setOnClickListener { presetDialog.dismiss() }
            }
            styleNumericDialogButton(closeButton)
            closeButton.setTextColor(Color.rgb(225, 233, 243))
            presetContent.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(7)
            })
            presetDialog.setContentView(presetContent)
            presetDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            centerDialogOnScreen(presetDialog, dp(260))
            presetDialog.show()
        }
        val sweepButton = utilityIcon(AnalyzerIconButton.Icon.SWEEP, "Play frequency sweep")
        toneSweepButton = sweepButton
        sweepButton.setOnClickListener {
            if (toneSweepActive) {
                cancelToneSweep()
                stopTone()
                resetTonePlayButton()
            } else {
                setTonePeakFollowEnabled(false, refreshUi = false)
                toneFrequency = if (toneSweepAscending) toneSweepStartFrequency else toneSweepEndFrequency
                toneSweepUiUpdate?.invoke()
                if (!tonePlaying) startTone()
                if (tonePlaying) {
                    toneSweepStartedAt = SystemClock.elapsedRealtime()
                    toneSweepActive = true
                    toneSweepRangeButton?.isEnabled = false
                    toneSweepDurationButton?.isEnabled = false
                    toneSweepDirectionButton?.isEnabled = false
                    toneDtmfModeButton?.isEnabled = false
                    toneWaveformButton?.isEnabled = false
                    sweepButton.contentDescription = "Stop frequency sweep"
                    sweepButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(29, 105, 145))
                    setTonePlayButtonPlaying(true)
                    toneSweepHandler.post(toneSweepRunnable)
                }
            }
        }
        fun formatSweepFrequency(frequency: Int): String = when {
            frequency >= 1000 && frequency % 1000 == 0 -> "${frequency / 1000} kHz"
            frequency >= 1000 -> String.format(java.util.Locale.US, "%.1f kHz", frequency / 1000.0)
            else -> "$frequency Hz"
        }
        fun styleSweepSettingButton(button: Button) {
            button.isAllCaps = false
            button.textSize = 11f
            button.minWidth = 0
            button.minimumWidth = 0
            button.minHeight = dp(36)
            button.minimumHeight = dp(36)
            button.setPadding(dp(5), 0, dp(5), 0)
            button.setTextColor(Color.rgb(225, 233, 243))
            button.background = GradientDrawable().apply {
                setColor(Color.rgb(15, 23, 36))
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(67, 91, 119))
            }
            button.stateListAnimator = null
        }
        val sweepSettingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sweepRangeButton = Button(this).apply {
            text = "${formatSweepFrequency(toneSweepStartFrequency)} → ${formatSweepFrequency(toneSweepEndFrequency)}"
            contentDescription = "Frequency sweep from $toneSweepStartFrequency to $toneSweepEndFrequency Hz. Tap to change."
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        styleSweepSettingButton(sweepRangeButton)
        val widestSweepRangeText = "${formatSweepFrequency(19999)} → ${formatSweepFrequency(20000)}"
        val sweepRangeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * resources.displayMetrics.scaledDensity
            typeface = sweepRangeButton.typeface
        }
        val sweepRangeMinimumWidth = (sweepRangeTextPaint.measureText(widestSweepRangeText) + dp(16)).roundToInt()
        sweepRangeButton.minWidth = sweepRangeMinimumWidth
        sweepRangeButton.minimumWidth = sweepRangeMinimumWidth
        sweepRangeButton.setPadding(dp(8), 0, dp(8), 0)
        toneSweepRangeButton = sweepRangeButton
        val sweepDirectionButton = Button(this).apply {
            text = if (toneSweepAscending) "↗" else "↙"
            contentDescription = if (toneSweepAscending) "Sweep from low to high frequency. Tap to reverse." else "Sweep from high to low frequency. Tap to reverse."
        }
        styleSweepSettingButton(sweepDirectionButton)
        sweepDirectionButton.textSize = 18f
        toneSweepDirectionButton = sweepDirectionButton
        sweepDirectionButton.setOnClickListener {
            if (!toneSweepActive) {
                toneSweepAscending = !toneSweepAscending
                sweepDirectionButton.text = if (toneSweepAscending) "↗" else "↙"
                sweepDirectionButton.contentDescription = if (toneSweepAscending) {
                    "Sweep from low to high frequency. Tap to reverse."
                } else {
                    "Sweep from high to low frequency. Tap to reverse."
                }
            }
        }
        sweepRangeButton.setOnClickListener {
            val rangeDialog = Dialog(this)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(24, 31, 42))
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.rgb(49, 66, 83))
                }
            }
            fun sweepFrequencyInput(initial: Int): EditText = EditText(this).apply {
                setText(initial.toString())
                textSize = 20f
                setTextColor(Color.rgb(255, 213, 90))
                gravity = Gravity.CENTER
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(5))
                isSingleLine = true
                showSoftInputOnFocus = false
                isFocusableInTouchMode = true
                isCursorVisible = true
                background = GradientDrawable().apply {
                    setColor(Color.rgb(15, 23, 36))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.rgb(67, 91, 119))
                }
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            val sweepStartInput = sweepFrequencyInput(toneSweepStartFrequency)
            val sweepEndInput = sweepFrequencyInput(toneSweepEndFrequency)
            sweepStartInput.contentDescription = "Sweep start frequency in hertz"
            sweepEndInput.contentDescription = "Sweep end frequency in hertz"
            val fields = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fields.addView(sweepStartInput, LinearLayout.LayoutParams(0, dp(46), 1f))
            fields.addView(TextView(this).apply {
                text = "→"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 213, 90))
            }, LinearLayout.LayoutParams(dp(30), dp(46)))
            fields.addView(sweepEndInput, LinearLayout.LayoutParams(0, dp(46), 1f))
            content.addView(fields, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

            var activeInput = sweepStartInput
            fun updateInputFocus(input: EditText, hasFocus: Boolean) {
                if (hasFocus) activeInput = input
                input.background = GradientDrawable().apply {
                    setColor(Color.rgb(15, 23, 36))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), if (hasFocus) Color.rgb(255, 213, 90) else Color.rgb(67, 91, 119))
                }
            }
            sweepStartInput.setOnFocusChangeListener { _, hasFocus -> updateInputFocus(sweepStartInput, hasFocus) }
            sweepEndInput.setOnFocusChangeListener { _, hasFocus -> updateInputFocus(sweepEndInput, hasFocus) }

            val keypad = android.widget.GridLayout(this).apply {
                columnCount = 3
                rowCount = 4
            }
            fun addKey(label: String, action: () -> Unit) {
                val button = Button(this).apply {
                    text = label
                    textSize = 16f
                    minWidth = dp(48)
                    minimumWidth = dp(48)
                    minHeight = dp(42)
                    minimumHeight = dp(42)
                    isFocusable = false
                    setPadding(0, 0, 0, 0)
                    setTextColor(Color.rgb(255, 213, 90))
                    setOnClickListener { action() }
                }
                styleNumericDialogButton(button)
                keypad.addView(button, android.widget.GridLayout.LayoutParams().apply {
                    width = dp(52)
                    height = dp(42)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            fun appendDigit(digit: String) {
                val editable = activeInput.text
                val start = activeInput.selectionStart.coerceIn(0, editable.length)
                val end = activeInput.selectionEnd.coerceIn(start, editable.length)
                if (editable.length - (end - start) < 5) {
                    editable.replace(start, end, digit)
                    activeInput.setSelection(start + digit.length)
                }
            }
            addKey("1") { appendDigit("1") }
            addKey("2") { appendDigit("2") }
            addKey("3") { appendDigit("3") }
            addKey("4") { appendDigit("4") }
            addKey("5") { appendDigit("5") }
            addKey("6") { appendDigit("6") }
            addKey("7") { appendDigit("7") }
            addKey("8") { appendDigit("8") }
            addKey("9") { appendDigit("9") }
            addKey("⌫") {
                val editable = activeInput.text
                val start = activeInput.selectionStart.coerceIn(0, editable.length)
                val end = activeInput.selectionEnd.coerceIn(start, editable.length)
                if (end > start) editable.delete(start, end) else if (start > 0) editable.delete(start - 1, start)
                activeInput.setSelection((if (end > start) start else (start - 1).coerceAtLeast(0)).coerceAtMost(editable.length))
            }
            addKey("0") { appendDigit("0") }
            addKey("CLR") {
                activeInput.setText("")
                activeInput.setSelection(0)
            }
            content.addView(keypad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
                bottomMargin = dp(5)
            })

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val cancelButton = Button(this).apply {
                text = "CANCEL"
                textSize = 11f
                setOnClickListener { rangeDialog.dismiss() }
            }
            val applyButton = Button(this).apply {
                text = "APPLY"
                textSize = 11f
                setOnClickListener {
                    val startHz = sweepStartInput.text.toString().trim().toIntOrNull()
                    val endHz = sweepEndInput.text.toString().trim().toIntOrNull()
                    when {
                        startHz == null || endHz == null -> Toast.makeText(this@MainActivity, "Enter both frequencies.", Toast.LENGTH_SHORT).show()
                        startHz !in 20..19999 || endHz !in 21..20000 -> Toast.makeText(this@MainActivity, "Use frequencies from 20 to 20,000 Hz.", Toast.LENGTH_SHORT).show()
                        startHz >= endHz -> Toast.makeText(this@MainActivity, "The end frequency must be higher than the start.", Toast.LENGTH_SHORT).show()
                        else -> {
                            toneSweepStartFrequency = startHz
                            toneSweepEndFrequency = endHz
                            sweepRangeButton.text = "${formatSweepFrequency(startHz)} → ${formatSweepFrequency(endHz)}"
                            sweepRangeButton.contentDescription = "Frequency sweep from $startHz to $endHz Hz. Tap to change."
                            rangeDialog.dismiss()
                        }
                    }
                }
            }
            for (button in listOf(cancelButton, applyButton)) {
                styleNumericDialogButton(button)
                button.setTextColor(Color.rgb(255, 213, 90))
                actions.addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                })
            }
            content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            rangeDialog.setContentView(content)
            rangeDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            rangeDialog.show()
            centerDialogOnScreen(rangeDialog, dp(320))
            sweepStartInput.requestFocus()
            sweepStartInput.setSelection(sweepStartInput.text.length)
        }
        val sweepDurationOptions = intArrayOf(4, 8, 16, 32)
        var sweepDurationIndex = sweepDurationOptions.indexOf((toneSweepDurationMs / 1000L).toInt()).coerceAtLeast(0)
        val sweepDurationButton = Button(this).apply {
            val seconds = sweepDurationOptions[sweepDurationIndex]
            text = "$seconds s"
            contentDescription = "Sweep duration, $seconds seconds. Tap to change."
        }
        styleSweepSettingButton(sweepDurationButton)
        toneSweepDurationButton = sweepDurationButton
        sweepDurationButton.setOnClickListener {
            if (!toneSweepActive) {
                sweepDurationIndex = (sweepDurationIndex + 1) % sweepDurationOptions.size
                val seconds = sweepDurationOptions[sweepDurationIndex]
                toneSweepDurationMs = seconds * 1000L
                sweepDurationButton.text = "$seconds s"
                sweepDurationButton.contentDescription = "Sweep duration, $seconds seconds. Tap to change."
            }
        }
        val sweepRangeGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sweepRangeGroup.addView(sweepRangeButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
        sweepRangeGroup.addView(sweepDirectionButton, LinearLayout.LayoutParams(dp(40), dp(36)))
        sweepSettingsRow.addView(sweepButton, LinearLayout.LayoutParams(dp(44), dp(36)).apply { marginEnd = dp(6) })
        sweepSettingsRow.addView(sweepRangeGroup, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
        sweepSettingsRow.addView(sweepDurationButton, LinearLayout.LayoutParams(dp(62), dp(36)))
        controls.addView(sweepSettingsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply {
            topMargin = dp(3)
            bottomMargin = dp(2)
        })

        val volumeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, 0)
        }
        volumeHeader.addView(TextView(this).apply {
            text = "Volume"
            textSize = 11f
            setTextColor(Color.rgb(150, 166, 184))
            gravity = Gravity.CENTER
        })
        val volumeLabel = TextView(this).apply {
            text = "${(toneVolume * 100).roundToInt()}%"
            textSize = 12f
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, 0, 0)
        }
        volumeHeader.addView(volumeLabel)
        controls.addView(volumeHeader)
        val volumeSlider = SeekBar(this).apply { max = 100; progress = (toneVolume * 100).roundToInt() }
        controls.addView(volumeSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 0) })
        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                toneVolume = progress / 100.0
                volumeLabel.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        var dtmfModeEnabled = false
        val dtmfModeButton = Button(this).apply {
            text = "DTMF"
            contentDescription = "Open DTMF two-tone keypad"
            textSize = 11f
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(6), 0, dp(6), 0)
            styleXButton(this, primary = false)
        }
        toneDtmfModeButton = dtmfModeButton
        controls.addView(dtmfModeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply {
            topMargin = dp(3)
        })

        val outputChannelPreferences = getSharedPreferences("tone_generator", Context.MODE_PRIVATE)
        toneOutputChannel = outputChannelPreferences.getString("output_channel", "Stereo")
            ?.takeIf { it in setOf("Left", "Right", "Stereo") } ?: "Stereo"
        val outputChannelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val outputChannelButtons = linkedMapOf<String, Button>()
        fun refreshOutputChannelButtons() {
            outputChannelButtons.forEach { (channel, button) ->
                val selected = channel == toneOutputChannel
                button.background = GradientDrawable().apply {
                    setColor(if (selected) Color.rgb(39, 113, 147) else Color.rgb(22, 32, 45))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), if (selected) Color.rgb(93, 173, 213) else Color.rgb(56, 73, 91))
                }
                button.contentDescription = if (selected) "$channel audio output selected" else "Select $channel audio output"
            }
        }
        listOf("Left", "Right", "Stereo").forEachIndexed { index, channel ->
            val channelButton = Button(this).apply {
                text = channel.uppercase(java.util.Locale.ROOT)
                textSize = 10f
                isAllCaps = false
                minHeight = dp(32)
                minimumHeight = dp(32)
                setPadding(dp(3), 0, dp(3), 0)
                setTextColor(Color.WHITE)
                stateListAnimator = null
                setOnClickListener {
                    toneOutputChannel = channel
                    outputChannelPreferences.edit().putString("output_channel", channel).apply()
                    refreshOutputChannelButtons()
                }
            }
            outputChannelButtons[channel] = channelButton
            outputChannelRow.addView(channelButton, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
        refreshOutputChannelButtons()
        controls.addView(outputChannelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply {
            topMargin = dp(3)
        })

        val dtmfInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.CENTER
            visibility = View.GONE
            contentDescription = "Selected DTMF symbol and its two frequencies"
        }
        controls.addView(dtmfInfo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

        val dtmfGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val dtmfKeyButtons = linkedMapOf<String, Button>()
        val dtmfLowFrequencies = intArrayOf(697, 770, 852, 941)
        val dtmfHighFrequencies = intArrayOf(1209, 1336, 1477)
        val dtmfKeys = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("*", "0", "#")
        )
        var refreshDtmfKeySelection: () -> Unit = {}
        dtmfKeys.forEachIndexed { rowIndex, rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowKeys.forEachIndexed { columnIndex, key ->
                val keyButton = Button(this).apply {
                    text = key
                    textSize = 15f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = dp(36)
                    minimumHeight = dp(36)
                    setPadding(0, 0, 0, 0)
                    setTextColor(Color.WHITE)
                    stateListAnimator = null
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(22, 32, 45))
                        cornerRadius = dp(11).toFloat()
                        setStroke(dp(1), Color.rgb(56, 73, 91))
                    }
                    setOnClickListener {
                        toneDtmfSelectedKey = key
                        toneDtmfLowFrequency = dtmfLowFrequencies[rowIndex]
                        toneDtmfHighFrequency = dtmfHighFrequencies[columnIndex]
                        toneDtmfActive = dtmfModeEnabled
                        dtmfInfo.text = "$key  ·  ${toneDtmfLowFrequency} + ${toneDtmfHighFrequency} Hz"
                        valueLabel.text = "DTMF $key"
                        refreshDtmfKeySelection()
                    }
                }
                keyButton.contentDescription = "DTMF $key: ${dtmfLowFrequencies[rowIndex]} and ${dtmfHighFrequencies[columnIndex]} hertz"
                dtmfKeyButtons[key] = keyButton
                row.addView(keyButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    setMargins(dp(3), dp(1), dp(3), dp(1))
                })
            }
            dtmfGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
        refreshDtmfKeySelection = {
            dtmfKeyButtons.forEach { (key, button) ->
                val selected = key == toneDtmfSelectedKey
                button.background = GradientDrawable().apply {
                    setColor(if (selected) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 45))
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(1), if (selected) Color.rgb(93, 173, 213) else Color.rgb(56, 73, 91))
                }
            }
        }
        dtmfInfo.text = "1  ·  697 + 1209 Hz"
        refreshDtmfKeySelection()
        controls.addView(dtmfGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun updateDtmfMode(enabled: Boolean) {
            if (enabled == dtmfModeEnabled) return
            if (enabled) setTonePeakFollowEnabled(false, refreshUi = false)
            if (toneSweepActive) cancelToneSweep()
            if (tonePlaying) stopTone()
            dtmfModeEnabled = enabled
            toneDtmfActive = enabled
            valueLabel.text = if (enabled) "DTMF ${toneDtmfSelectedKey}" else "$toneFrequency Hz"
            valueLabel.textSize = if (enabled) 13f else 18f
            valueLabel.isClickable = !enabled
            valueLabel.isEnabled = true
            valueLabel.contentDescription = if (enabled) "Selected DTMF key $toneDtmfSelectedKey" else "Select tone frequency"
            presetsButton.visibility = if (enabled) View.GONE else View.VISIBLE
            waveCell.visibility = if (enabled) View.GONE else View.VISIBLE
            slider.visibility = if (enabled) View.GONE else View.VISIBLE
            sweepSettingsRow.visibility = if (enabled) View.GONE else View.VISIBLE
            dtmfInfo.visibility = if (enabled) View.VISIBLE else View.GONE
            dtmfGrid.visibility = if (enabled) View.VISIBLE else View.GONE
            dtmfModeButton.text = if (enabled) "SINGLE TONE" else "DTMF"
            dtmfModeButton.contentDescription = if (enabled) "Return to single tone controls" else "Open DTMF two-tone keypad"
            styleXButton(dtmfModeButton, primary = enabled)
            resetTonePlayButton()
            if (!enabled) toneSweepUiUpdate?.invoke()
        }
        dtmfModeButton.setOnClickListener { updateDtmfMode(!dtmfModeEnabled) }

        fun updateToneSliderFromFrequency() {
            val noiseSelected = isNoiseTone()
            val selectedToneControlsEnabled = !noiseSelected && !toneSweepActive && !dtmfModeEnabled
            if (dtmfModeEnabled) {
                valueLabel.isEnabled = true
                slider.isEnabled = false
                presetsButton.isEnabled = false
                sweepButton.isEnabled = false
                toneWaveformButton?.isEnabled = false
                toneSweepRangeButton?.isEnabled = false
                toneSweepDurationButton?.isEnabled = false
                toneSweepDirectionButton?.isEnabled = false
                toneDtmfModeButton?.isEnabled = !toneSweepActive
                return
            }
            valueLabel.isEnabled = !noiseSelected
            slider.isEnabled = !noiseSelected
            presetsButton.isEnabled = !noiseSelected
            sweepButton.isEnabled = !noiseSelected
            toneWaveformButton?.isEnabled = !toneSweepActive
            toneSweepRangeButton?.isEnabled = selectedToneControlsEnabled
            toneSweepDurationButton?.isEnabled = selectedToneControlsEnabled
            toneSweepDirectionButton?.isEnabled = selectedToneControlsEnabled
            toneDtmfModeButton?.isEnabled = !toneSweepActive
            if (noiseSelected) {
                valueLabel.text = if (toneWaveform == "White noise") "WHITE" else "PINK"
                valueLabel.textSize = 14f
                valueLabel.contentDescription = "$toneWaveform selected; tone frequency controls are unavailable."
                if (!tonePlaying) tonePlayButton?.contentDescription = "Play ${toneWaveform.lowercase()}"
                return
            }
            val low = toneMinFrequency.coerceAtLeast(20)
            val high = toneMaxFrequency.coerceAtMost(20000).coerceAtLeast(low + 1)
            toneFrequency = toneFrequency.coerceIn(low, high)
            slider.progress = ((kotlin.math.ln(toneFrequency / low.toDouble()) / kotlin.math.ln(high / low.toDouble())) * slider.max).roundToInt().coerceIn(0, slider.max)
            valueLabel.text = "$toneFrequency Hz"
            valueLabel.textSize = 18f
            valueLabel.contentDescription = if (tonePeakFollowEnabled) {
                "Peak hold: $toneFrequency hertz. The strongest detected tonal peak refreshes every five seconds and remains selected if it disappears."
            } else "Select tone frequency"
            if (!tonePlaying) tonePlayButton?.contentDescription = "Play selected tone"
        }
        toneSweepUiUpdate = ::updateToneSliderFromFrequency
        updateToneSliderFromFrequency()
        valueLabel.setOnClickListener {
            // Use the same custom numeric keypad as the Spectrum Analyzer.
            // Confirming a value only updates the frequency; it never starts playback.
            val dialog = android.app.Dialog(this)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(24, 31, 42))
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.rgb(49, 66, 83))
                }
            }
            content.addView(TextView(this).apply {
                text = "Frequency (Hz)"
                textSize = 14f
                setTextColor(Color.rgb(225, 233, 243))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val display = EditText(this).apply {
                setText(toneFrequency.toString())
                textSize = 22f
                setTextColor(Color.rgb(255, 213, 90))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(8))
                minWidth = dp(160)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
                showSoftInputOnFocus = false
                isFocusableInTouchMode = true
                isCursorVisible = true
                setSelection(text.length)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(15, 23, 36))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.rgb(67, 91, 119))
                }
            }
            content.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val keypad = android.widget.GridLayout(this).apply {
                columnCount = 3
                rowCount = 4
            }
            fun key(label: String, action: () -> Unit) {
                val button = Button(this).apply {
                    text = label
                    textSize = 16f
                    minWidth = dp(48)
                    minimumWidth = dp(48)
                    minHeight = dp(42)
                    minimumHeight = dp(42)
                    isFocusable = false
                    setPadding(0, 0, 0, 0)
                    setTextColor(Color.rgb(255, 213, 90))
                    setOnClickListener { action() }
                }
                styleNumericDialogButton(button)
                keypad.addView(button, android.widget.GridLayout.LayoutParams().apply {
                    width = dp(52)
                    height = dp(42)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            fun appendDigit(digit: String) {
                val editable = display.text
                val start = display.selectionStart.coerceAtLeast(0)
                val end = display.selectionEnd.coerceAtLeast(start)
                if (editable.length - (end - start) < 5) {
                    editable.replace(start, end, digit)
                    display.setSelection(start + digit.length)
                }
            }
            key("1") { appendDigit("1") }
            key("2") { appendDigit("2") }
            key("3") { appendDigit("3") }
            key("4") { appendDigit("4") }
            key("5") { appendDigit("5") }
            key("6") { appendDigit("6") }
            key("7") { appendDigit("7") }
            key("8") { appendDigit("8") }
            key("9") { appendDigit("9") }
            key("⌫") {
                val editable = display.text
                val start = display.selectionStart.coerceAtLeast(0)
                val end = display.selectionEnd.coerceAtLeast(start)
                when {
                    end > start -> editable.delete(start, end)
                    start > 0 -> editable.delete(start - 1, start)
                }
                val cursor = if (end > start) start else (start - 1).coerceAtLeast(0)
                display.setSelection(cursor.coerceAtMost(editable.length))
            }
            key("0") { appendDigit("0") }
            key("CLR") { display.setText(""); display.setSelection(0) }
            content.addView(keypad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val cancelButton = Button(this).apply {
                text = "CANCEL"
                textSize = 11f
                setOnClickListener { dialog.dismiss() }
            }
            val doneButton = Button(this).apply {
                text = "DONE"
                textSize = 11f
                setOnClickListener {
                    val frequency = display.text.toString().toIntOrNull()
                    if (frequency == null || frequency !in 20..20000) {
                        display.error = "Enter 20–20000 Hz"
                    } else {
                        setTonePeakFollowEnabled(false, refreshUi = false)
                        toneFrequency = frequency
                        updateToneSliderFromFrequency()
                        dialog.dismiss()
                    }
                }
            }
            for (button in listOf(cancelButton, doneButton)) {
                styleNumericDialogButton(button)
                button.setTextColor(Color.rgb(255, 213, 90))
                actions.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(3), dp(5), dp(3), 0)
                })
            }
            content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setContentView(content)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            centerDialogOnScreen(dialog, dp(220))
            dialog.show()
            display.requestFocus()
            display.setSelection(display.text.length)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setTonePeakFollowEnabled(false, refreshUi = false)
                    val low = toneMinFrequency.coerceAtLeast(20)
                    val high = toneMaxFrequency.coerceAtMost(20000).coerceAtLeast(low + 1)
                    toneFrequency = (low * kotlin.math.exp(progress.toDouble() / slider.max * kotlin.math.ln(high.toDouble() / low))).roundToInt().coerceIn(low, high)
                }
                valueLabel.text = "$toneFrequency Hz"
                if (!tonePeakFollowEnabled) valueLabel.contentDescription = "Select tone frequency"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        return panel
    }

    private fun centerDialogOnScreen(dialog: Dialog, width: Int, horizontalOffsetDp: Int = -19) {
        val window = dialog.window ?: return
        val horizontalOffset = (horizontalOffsetDp * resources.displayMetrics.density).roundToInt()
        fun applyCenter() {
            window.setGravity(Gravity.CENTER)
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.attributes = window.attributes.apply {
                gravity = Gravity.CENTER
                x = horizontalOffset
                y = 0
            }
        }
        // Apply once before show and again after attachment, before the first frame is drawn.
        applyCenter()
        dialog.setOnShowListener { applyCenter() }
    }

    private fun attachSplDialogDragHandle(
        dialog: Dialog,
        handle: View,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val margin = (16f * resources.displayMetrics.density).roundToInt()
        var downRawX = 0f
        var downRawY = 0f
        var startOffsetX = 0
        var startOffsetY = 0
        var dragging = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            val window = dialog.window ?: return@setOnTouchListener false
            val decor = window.decorView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val attributes = window.attributes
                    startOffsetX = attributes.x
                    startOffsetY = attributes.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragging && hypot(deltaX, deltaY) < touchSlop) return@setOnTouchListener true
                    dragging = true
                    val attributes = window.attributes
                    val halfWidth = decor.width / 2
                    val halfHeight = decor.height / 2
                    val minX = margin + halfWidth - screenWidth / 2
                    val maxX = screenWidth / 2 - margin - halfWidth
                    val minY = margin + halfHeight - screenHeight / 2
                    val maxY = screenHeight / 2 - margin - halfHeight
                    attributes.gravity = Gravity.CENTER
                    attributes.x = (startOffsetX + deltaX).roundToInt().coerceIn(minX, maxX.coerceAtLeast(minX))
                    attributes.y = (startOffsetY + deltaY).roundToInt().coerceIn(minY, maxY.coerceAtLeast(minY))
                    window.attributes = attributes
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setTonePlayButtonPlaying(playing: Boolean) {
        val button = tonePlayButton as? AnalyzerIconButton ?: return
        button.icon = if (playing) AnalyzerIconButton.Icon.STOP else AnalyzerIconButton.Icon.PLAY
        button.contentDescription = when {
            playing && toneDtmfActive -> "Stop DTMF tone"
            playing && isNoiseTone() -> "Stop ${toneWaveform.lowercase()}"
            playing -> "Stop tone"
            toneDtmfActive -> "Play selected DTMF tone"
            isNoiseTone() -> "Play ${toneWaveform.lowercase()}"
            else -> "Play selected tone"
        }
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (playing) Color.rgb(150, 40, 45) else Color.rgb(29, 125, 65)
        )
    }

    private fun resetTonePlayButton() {
        setTonePlayButtonPlaying(false)
    }

    private fun startTone() {
        if (tonePlaying) return
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Toast.makeText(this, "Audio output is unavailable.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, 4096), AudioTrack.MODE_STREAM
            )
            toneTrack = track
            toneStopRequested = false
            tonePlaying = true
            track.play()
            toneThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val buffer = ShortArray(2048)
                var phase = 0.0
                var secondPhase = 0.0
                var noiseState = System.nanoTime().toInt().let { if (it == 0) 1 else it }
                var pink0 = 0.0
                var pink1 = 0.0
                var pink2 = 0.0
                var pink3 = 0.0
                var pink4 = 0.0
                var pink5 = 0.0
                var pink6 = 0.0
                fun nextWhiteNoise(): Double {
                    noiseState = noiseState xor (noiseState shl 13)
                    noiseState = noiseState xor (noiseState ushr 17)
                    noiseState = noiseState xor (noiseState shl 5)
                    return (noiseState.toDouble() / Int.MAX_VALUE.toDouble()).coerceIn(-1.0, 1.0)
                }
                fun nextPinkNoise(): Double {
                    val white = nextWhiteNoise()
                    pink0 = 0.99886 * pink0 + white * 0.0555179
                    pink1 = 0.99332 * pink1 + white * 0.0750759
                    pink2 = 0.96900 * pink2 + white * 0.1538520
                    pink3 = 0.86650 * pink3 + white * 0.3104856
                    pink4 = 0.55000 * pink4 + white * 0.5329522
                    pink5 = -0.7616 * pink5 - white * 0.0168980
                    val pink = pink0 + pink1 + pink2 + pink3 + pink4 + pink5 + pink6 + white * 0.5362
                    pink6 = white * 0.115926
                    return (pink * 0.20).coerceIn(-1.0, 1.0)
                }
                var fadeRemaining = -1
                val fadeSamples = (sampleRate * 0.04).toInt()
                while (!Thread.currentThread().isInterrupted) {
                    if (toneStopRequested && fadeRemaining < 0) fadeRemaining = fadeSamples
                    if (!tonePlaying && fadeRemaining <= 0) break
                    val dtmf = toneDtmfActive
                    val frequency = (if (dtmf) toneDtmfLowFrequency else toneFrequency).toDouble().coerceIn(20.0, 20000.0)
                    val step = 2.0 * PI * frequency / sampleRate
                    val secondFrequency = toneDtmfHighFrequency.toDouble().coerceIn(20.0, 20000.0)
                    val secondStep = 2.0 * PI * secondFrequency / sampleRate
                    val waveform = toneWaveform
                    val volume = toneVolume
                    val outputChannel = toneOutputChannel
                    for (frameIndex in 0 until buffer.size / 2) {
                        val normalized = if (dtmf) {
                            (sin(phase) + sin(secondPhase)) * 0.5
                        } else when (waveform) {
                            "White noise" -> nextWhiteNoise()
                            "Pink noise" -> nextPinkNoise()
                            "Square" -> if (sin(phase) >= 0.0) 1.0 else -1.0
                            "Triangle" -> 2.0 / PI * kotlin.math.asin(sin(phase))
                            "Sawtooth" -> 2.0 * (phase / (2.0 * PI)) - 1.0
                            else -> sin(phase)
                        }
                        var envelope = 1.0
                        if (fadeRemaining >= 0) {
                            envelope = (fadeRemaining.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                            fadeRemaining--
                        }
                        val sample = (normalized.coerceIn(-1.0, 1.0) * 12000.0 * volume * envelope).toInt().toShort()
                        val leftIndex = frameIndex * 2
                        val rightIndex = leftIndex + 1
                        when (outputChannel) {
                            "Left" -> {
                                buffer[leftIndex] = sample
                                buffer[rightIndex] = 0
                            }
                            "Right" -> {
                                buffer[leftIndex] = 0
                                buffer[rightIndex] = sample
                            }
                            else -> {
                                buffer[leftIndex] = sample
                                buffer[rightIndex] = sample
                            }
                        }
                        phase += step
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI
                        secondPhase += secondStep
                        if (secondPhase >= 2.0 * PI) secondPhase -= 2.0 * PI
                    }
                    try { track.write(buffer, 0, buffer.size) } catch (_: Exception) { break }
                    if (toneStopRequested && fadeRemaining <= 0) break
                }
                try { track.pause(); track.flush(); track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                if (toneTrack === track) toneTrack = null
                tonePlaying = false
                toneThread = null
                toneStopRequested = false
                runOnUiThread { resetTonePlayButton() }
            }.apply { name = "ToneGeneratorAudio"; start() }
        } catch (_: Exception) {
            tonePlaying = false
            try { toneTrack?.release() } catch (_: Exception) {}
            toneTrack = null
            resetTonePlayButton()
            Toast.makeText(this, "Could not start tone output.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopTone() {
        cancelToneSweep()
        if (!tonePlaying) {
            resetTonePlayButton()
            return
        }
        toneStopRequested = true
        // The audio thread performs a short fade-out before releasing AudioTrack.
    }

    private fun cancelToneSweep() {
        toneSweepActive = false
        toneSweepHandler.removeCallbacks(toneSweepRunnable)
        toneSweepButton?.contentDescription = "Play frequency sweep"
        toneSweepButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(22, 32, 45))
        toneSweepRangeButton?.isEnabled = true
        toneSweepDurationButton?.isEnabled = true
        toneSweepDirectionButton?.isEnabled = true
        toneDtmfModeButton?.isEnabled = true
        toneWaveformButton?.isEnabled = true
        toneSweepUiUpdate?.invoke()
    }

    private fun styleXButton(button: Button, primary: Boolean) {
        val density = resources.displayMetrics.density
        val background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 13f * density
            setColor(if (primary) Color.rgb(29, 155, 240) else Color.rgb(22, 32, 42))
            if (!primary) {
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.rgb(56, 68, 77))
            }
        }
        button.background = background
        button.setTextColor(Color.WHITE)
        button.isAllCaps = true
        button.stateListAnimator = null
    }

    private fun styleNumericDialogButton(button: Button) {
        val density = resources.displayMetrics.density
        button.background = GradientDrawable().apply {
            setColor(Color.rgb(49, 63, 81))
            cornerRadius = 10f * density
            setStroke((density).roundToInt().coerceAtLeast(1), Color.rgb(72, 91, 111))
        }
        button.backgroundTintList = null
        button.stateListAnimator = null
    }

    private fun configureSpectrumHeader() {
        fun findTitle(view: View): TextView? {
            if (view is TextView && view.text.toString().trim().equals("SPECTRUM ANALYZER", ignoreCase = true)) {
                return view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val found = findTitle(view.getChildAt(i))
                    if (found != null) return found
                }
            }
            return null
        }

        val title = findTitle(findViewById(android.R.id.content))
        val parent = title?.parent as? ViewGroup
        if (title == null || parent == null) return

        fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

        val index = parent.indexOfChild(title)
        val oldParams = title.layoutParams
        val headerHeight = maxOf(
            dp(26),
            (22f * resources.configuration.fontScale * resources.displayMetrics.density).roundToInt()
        )
        spectrumHeaderCompactHeightPx = headerHeight
        parent.removeView(title)
        headerTitleText = null
        title.visibility = View.GONE

        val header = android.widget.FrameLayout(this).apply {
            setPadding(0, 0, 0, 0)
        }
        spectrumHeader = header
        // The title is intentionally removed; the peak readout remains in this header.

        fun frequencyInput(initialValue: String, description: String): EditText = EditText(this).apply {
            setText(initialValue)
            contentDescription = description
            textSize = 13f
            setTextColor(Color.rgb(255, 213, 90))
            setHintTextColor(Color.rgb(150, 160, 175))
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            setSingleLine(true)
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            showSoftInputOnFocus = false
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(17, 25, 36))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(90, 105, 124))
            }
        }

        val minFrequencyInput = frequencyInput("20", "Minimum frequency in Hz")
        val maxFrequencyInput = frequencyInput("20000", "Maximum frequency in Hz")
        minFrequencyInput.hint = "Min Hz"
        maxFrequencyInput.hint = "Max Hz"

        var selectedRangeInput: EditText = minFrequencyInput
        fun selectRangeInput(input: EditText) {
            selectedRangeInput = input
            listOf(minFrequencyInput, maxFrequencyInput).forEach { field ->
                field.background = GradientDrawable().apply {
                    setColor(Color.rgb(17, 25, 36))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (field === input) 2 else 1), if (field === input) Color.rgb(255, 213, 90) else Color.rgb(90, 105, 124))
                }
            }
            input.requestFocus()
        }
        fun attachRangeFieldTouch(input: EditText) {
            input.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    selectRangeInput(input)
                    val cursor = input.getOffsetForPosition(event.x, event.y)
                        .coerceIn(0, input.text.length)
                    input.setSelection(cursor)
                }
                // Let EditText finish its normal tap handling after we activate it.
                false
            }
        }
        attachRangeFieldTouch(minFrequencyInput)
        attachRangeFieldTouch(maxFrequencyInput)

        val rangeDialog = android.app.Dialog(this)
        val rangeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 31, 42))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(49, 66, 83))
            }
        }

        val rangeFields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addRangeField(input: EditText) {
            val field = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            field.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            rangeFields.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addRangeField(minFrequencyInput)
        rangeFields.addView(TextView(this).apply {
            text = "–"
            textSize = 18f
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), dp(60)))
        addRangeField(maxFrequencyInput)
        rangeContent.addView(rangeFields, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rangeKeypad = android.widget.GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
        }
        fun addRangeKey(label: String, action: () -> Unit) {
            val keyButton = Button(this).apply {
                text = label
                textSize = 16f
                minWidth = dp(48)
                minimumWidth = dp(48)
                minHeight = dp(42)
                minimumHeight = dp(42)
                isFocusable = false
                setPadding(0, 0, 0, 0)
                setTextColor(Color.rgb(255, 213, 90))
                setOnClickListener { action() }
            }
            styleNumericDialogButton(keyButton)
            rangeKeypad.addView(keyButton, android.widget.GridLayout.LayoutParams().apply {
                width = dp(52)
                height = dp(42)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        fun appendRangeDigit(digit: String) {
            val editable = selectedRangeInput.text
            val start = selectedRangeInput.selectionStart.coerceAtLeast(0)
            val end = selectedRangeInput.selectionEnd.coerceAtLeast(start)
            if (editable.length - (end - start) < 5) {
                editable.replace(start, end, digit)
                selectedRangeInput.setSelection(start + digit.length)
            }
        }
        addRangeKey("1") { appendRangeDigit("1") }
        addRangeKey("2") { appendRangeDigit("2") }
        addRangeKey("3") { appendRangeDigit("3") }
        addRangeKey("4") { appendRangeDigit("4") }
        addRangeKey("5") { appendRangeDigit("5") }
        addRangeKey("6") { appendRangeDigit("6") }
        addRangeKey("7") { appendRangeDigit("7") }
        addRangeKey("8") { appendRangeDigit("8") }
        addRangeKey("9") { appendRangeDigit("9") }
        addRangeKey("⌫") {
            val editable = selectedRangeInput.text
            val start = selectedRangeInput.selectionStart.coerceAtLeast(0)
            val end = selectedRangeInput.selectionEnd.coerceAtLeast(start)
            when {
                end > start -> editable.delete(start, end)
                start > 0 -> editable.delete(start - 1, start)
            }
            val newCursor = if (end > start) start else (start - 1).coerceAtLeast(0)
            selectedRangeInput.setSelection(newCursor.coerceAtMost(editable.length))
        }
        addRangeKey("0") { appendRangeDigit("0") }
        addRangeKey("CLR") { selectedRangeInput.setText(""); selectedRangeInput.setSelection(0) }
        rangeContent.addView(rangeKeypad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) })

        val rangeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val cancelRangeButton = Button(this).apply {
            text = "CANCEL"
            textSize = 11f
            setOnClickListener { rangeDialog.dismiss() }
        }
        val rangeInfoButton = TextView(this).apply {
            text = "ⓘ"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 213, 90))
            background = GradientDrawable().apply {
                setColor(Color.rgb(49, 63, 81))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(72, 91, 111))
            }
            contentDescription = "Frequency range information"
            setOnClickListener {
                val infoDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Frequency range")
                    .setMessage("Enter the minimum and maximum frequencies in Hz, from 0 to 20000. The minimum must be lower than the maximum. Press DONE to apply the range. The sampling rate remains 48 kHz; analysis is limited to 20 kHz.")
                    .setPositiveButton("OK", null)
                    .create()
                infoDialog.setOnShowListener {
                    infoDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let(::styleNumericDialogButton)
                }
                infoDialog.show()
            }
        }
        val applyRangeButton = Button(this).apply {
            text = "DONE"
            textSize = 12f
            setOnClickListener {
                val minValue = minFrequencyInput.text.toString().trim().toIntOrNull()
                val maxValue = maxFrequencyInput.text.toString().trim().toIntOrNull()
                when {
                    minValue == null || maxValue == null -> Toast.makeText(this@MainActivity, "Enter both frequencies.", Toast.LENGTH_SHORT).show()
                    minValue !in 0..20000 || maxValue !in 0..20000 -> Toast.makeText(this@MainActivity, "Frequencies must be between 0 and 20000 Hz.", Toast.LENGTH_SHORT).show()
                    minValue >= maxValue -> Toast.makeText(this@MainActivity, "The minimum frequency must be lower than the maximum.", Toast.LENGTH_SHORT).show()
                    maxValue <= 20 -> Toast.makeText(this@MainActivity, "The maximum frequency must be above 20 Hz.", Toast.LENGTH_SHORT).show()
                    else -> {
                        spectrumView.setFrequencyRange(minValue, maxValue)
                        rangeDialog.dismiss()
                    }
                }
            }
        }
        for (button in listOf(cancelRangeButton, applyRangeButton)) {
            styleNumericDialogButton(button)
            button.setTextColor(Color.rgb(255, 213, 90))
        }
        rangeActions.addView(cancelRangeButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        rangeActions.addView(rangeInfoButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        rangeActions.addView(applyRangeButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        rangeContent.addView(rangeActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rangeDialog.setContentView(rangeContent)
        rangeDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val initialRange = spectrumView.getVisibleFrequencyRangeHz()
        val rangeButton = Button(this).apply {
            text = "(${initialRange.first}–${initialRange.second} Hz)"
            textSize = 14f
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(14), 0, dp(14), 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(49, 63, 81))
            setTextColor(Color.rgb(255, 213, 90))
            contentDescription = "Choose graph frequency range"
            setOnClickListener {
                val (minHz, maxHz) = spectrumView.getVisibleFrequencyRangeHz()
                minFrequencyInput.setText(minHz.toString())
                maxFrequencyInput.setText(maxHz.toString())
                selectRangeInput(minFrequencyInput)
                centerDialogOnScreen(rangeDialog, dp(300))
                rangeDialog.show()
            }
        }
        rangePickerButton = rangeButton
        styleXButton(rangeButton, primary = false)
        rangeButton.isAllCaps = false
        rangeButton.setTextColor(Color.rgb(255, 213, 90))
        rangeButton.textSize = 14f
        val rangeButtonWidth = rangeButton.paint.measureText(rangeButton.text.toString()).roundToInt() +
                rangeButton.paddingLeft + rangeButton.paddingRight + dp(4)
        rangeButton.layoutParams = android.widget.FrameLayout.LayoutParams(rangeButtonWidth, dp(42), Gravity.CENTER)
        spectrumView.setFrequencyRangeListener { minHz, maxHz ->
            rangeButton.text = "($minHz–$maxHz Hz)"
            val width = rangeButton.paint.measureText(rangeButton.text.toString()).roundToInt() +
                    rangeButton.paddingLeft + rangeButton.paddingRight + dp(4)
            val params = rangeButton.layoutParams as android.widget.FrameLayout.LayoutParams
            params.width = width
            params.gravity = Gravity.CENTER
            rangeButton.layoutParams = params
        }
        // Keep SPL at the upper-left and peak information at the upper-right of the graph header.
        val peakReadout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(6), 0, dp(8), 0)
        }
        headerPeakFrequencyText = TextView(this).apply {
            text = "-- Hz"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.END
            includeFontPadding = false
        }
        headerPeakDbText = TextView(this).apply {
            text = "-- dBFS"
            textSize = 11f
            setTextColor(Color.rgb(210, 220, 232))
            gravity = Gravity.END
            includeFontPadding = false
        }
        headerSplText = TextView(this).apply {
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            minHeight = headerHeight
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "Show enlarged SPL estimate"
            setOnClickListener { showSplDetailDialog() }
        }
        updateSplReadout()
        peakReadout.addView(headerPeakFrequencyText)
        peakReadout.addView(headerPeakDbText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(7)
        })
        header.addView(headerSplText, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight, Gravity.CENTER_VERTICAL or Gravity.START))
        header.addView(peakReadout, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight, Gravity.CENTER_VERTICAL or Gravity.END))

        oldParams.height = headerHeight
        parent.addView(header, index, oldParams)
    }

    private fun updateSplReadout() {
        val offset = splCalibrationOffsetDb
        val currentSpl = if (offset != null && running && splHasCurrentSample) {
            (latestAnalyzedRmsDb + offset).takeIf { it.isFinite() }
        } else {
            null
        }
        val displayValue = currentSpl?.let {
            String.format(java.util.Locale.US, "SPL ≈ %.1f dB", it)
        } ?: "SPL -- dB"
        var deltaOverOneSecond: Double? = null
        if (currentSpl != null) {
            val now = SystemClock.elapsedRealtime()
            splRecentReadings.addLast(now to currentSpl)
            while (splRecentReadings.peekFirst()?.first?.let { it < now - 2000L } == true) {
                splRecentReadings.removeFirst()
            }
            val reference = splRecentReadings.lastOrNull { it.first <= now - 1000L }
            deltaOverOneSecond = reference?.let { currentSpl - it.second }
        } else {
            splRecentReadings.clear()
        }
        val detail = when {
            !running -> "Start audio analysis to see the live SPL estimate."
            offset == null -> "This phone does not report usable microphone sensitivity. Tap Calibrate to enter a reference meter reading."
            splManualCalibrationOffsetDb != null -> "Unweighted SPL estimate · using your reference calibration. Phone microphone processing can affect accuracy."
            else -> "Unweighted SPL estimate · using microphone sensitivity reported by Android. Phone processing can affect accuracy."
        }
        headerSplText?.apply {
            text = displayValue
            contentDescription = "Show enlarged SPL estimate. $detail"
        }
        splDetailValueView?.text = displayValue
        splDetailInfoView?.text = detail
        splDetailDeltaView?.text = deltaOverOneSecond?.let {
            String.format(java.util.Locale.US, "Δ 1 s   %+.1f dB", it)
        } ?: "Δ 1 s   —"
        splScaleMeterView?.setLevel(currentSpl)
    }

    private fun readSystemSplCalibrationOffset(audioRecord: AudioRecord): Double? {
        if (android.os.Build.VERSION.SDK_INT < 28) return null
        return try {
            audioRecord.getActiveMicrophones()
                .asSequence()
                .map { it.getSensitivity() }
                .firstOrNull { it.isFinite() && it in -150f..0f }
                ?.let { 94.0 - it.toDouble() }
        } catch (_: Exception) {
            null
        }
    }

    private inner class SplScaleMeterView(context: Context) : View(context) {
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
            for (db in 30..130 step 10) {
                val y = bottom - ((db - 30) / 100f) * (bottom - top)
                canvas.drawText(db.toString(), labelRight, y + labelPaint.textSize * 0.35f, labelPaint)
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

    private fun showSplDetailDialog() {
        if (splDetailDialog?.isShowing == true) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        val hasAutomaticCalibration = splSystemCalibrationOffsetDb != null
        val content = LinearLayout(this).apply {
            orientation = if (hasAutomaticCalibration) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dp(if (hasAutomaticCalibration) 18 else 22),
                dp(if (hasAutomaticCalibration) 16 else 20),
                dp(if (hasAutomaticCalibration) 18 else 22),
                dp(if (hasAutomaticCalibration) 16 else 18)
            )
            background = rounded(Color.rgb(14, 21, 31), 22)
        }
        if (!hasAutomaticCalibration) {
            content.addView(TextView(this).apply {
                text = "SOUND PRESSURE LEVEL"
                textSize = 12f
                letterSpacing = 0.08f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(170, 187, 204))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val value = TextView(this).apply {
            text = headerSplText?.text ?: "SPL -- dB"
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            isSingleLine = true
            setPadding(0, dp(14), 0, dp(10))
        }
        splDetailValueView = value
        val dialog = Dialog(this)
        splDetailInfoView = null
        splDetailDeltaView = null
        splScaleMeterView = null
        if (hasAutomaticCalibration) {
            val readout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            readout.addView(value, LinearLayout.LayoutParams(-1, -2))
            val delta = TextView(this).apply {
                text = "Δ 1 s   —"
                textSize = 13f
                setTextColor(Color.rgb(178, 192, 208))
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                setPadding(0, dp(8), 0, 0)
            }
            splDetailDeltaView = delta
            readout.addView(delta, LinearLayout.LayoutParams(-1, -2))
            content.addView(readout, LinearLayout.LayoutParams(0, -2, 1f))
            val meter = SplScaleMeterView(this)
            splScaleMeterView = meter
            content.addView(meter, LinearLayout.LayoutParams(dp(100), dp(236)))
        } else {
            content.addView(value, LinearLayout.LayoutParams(-1, -2))
            val info = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
            }
            splDetailInfoView = info
            content.addView(info, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(16)
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            actions.addView(Button(this).apply {
                text = "Calibrate"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(42, 57, 75), 14)
                setOnClickListener { showSplCalibrationDialog() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            actions.addView(Button(this).apply {
                text = "Done"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(39, 113, 147), 14)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            content.addView(actions)
        }
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (splDetailDialog === dialog) {
                splDetailDialog = null
                splDetailValueView = null
                splDetailInfoView = null
                splDetailDeltaView = null
                splScaleMeterView = null
            }
        }
        splDetailDialog = dialog
        updateSplReadout()
        val compactWidth = if (hasAutomaticCalibration) {
            val maxValueWidth = value.paint.measureText("SPL ≈ 280.0 dB").roundToInt()
            (maxValueWidth + dp(18 * 2 + 100 + 8))
                .coerceAtMost((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(1)))
        } else {
            null
        }
        val displaySize = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
        val displayWidth = displaySize.x.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val dialogWidth = compactWidth ?: (displayWidth * 0.82f).roundToInt()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.38f)
        }
        centerDialogOnScreen(dialog, dialogWidth, horizontalOffsetDp = -10)
        dialog.show()
        attachSplDialogDragHandle(dialog, content, displayWidth, displaySize.y)
    }

    private fun showSplCalibrationDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        content.addView(TextView(this).apply {
            text = "Place the phone beside a calibrated SPL meter, or use an acoustic calibrator. Keep the reference sound steady and enter its reading below. This gives an unweighted estimate; the phone microphone’s response and processing affect accuracy."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(10))
        })
        content.addView(TextView(this).apply {
            text = "Reference level (dB SPL)"
            textSize = 13f
            setTextColor(Color.WHITE)
        })
        val referenceInput = EditText(this).apply {
            hint = "e.g. 94.0"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 55))
                cornerRadius = dp(12).toFloat()
            }
        }
        content.addView(referenceInput, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(6)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Calibrate SPL estimate")
            .setView(content)
            .setPositiveButton("Calibrate", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Auto", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                visibility = if (splManualCalibrationOffsetDb == null) View.GONE else View.VISIBLE
                setOnClickListener {
                    splManualCalibrationOffsetDb = null
                    splCalibrationOffsetDb = splSystemCalibrationOffsetDb
                    splRecentReadings.clear()
                    getSharedPreferences("sound_level_calibration", Context.MODE_PRIVATE)
                        .edit().remove("offset_db").apply()
                    updateSplReadout()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val referenceSpl = referenceInput.text.toString().toDoubleOrNull()
                when {
                    !running -> referenceInput.error = "Start audio analysis first"
                    referenceSpl == null || referenceSpl !in 20.0..160.0 ->
                        referenceInput.error = "Enter a reference from 20 to 160 dB SPL"
                    !latestAnalyzedRmsDb.isFinite() ->
                        referenceInput.error = "No microphone level is available yet"
                    else -> {
                        val offset = referenceSpl - latestAnalyzedRmsDb
                        splManualCalibrationOffsetDb = offset
                        splCalibrationOffsetDb = offset
                        splRecentReadings.clear()
                        getSharedPreferences("sound_level_calibration", Context.MODE_PRIVATE)
                            .edit().putFloat("offset_db", offset.toFloat()).apply()
                        updateSplReadout()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun updateStartButtonAppearance() {
        if (!::startButton.isInitialized) return
        val color = if (running) Color.rgb(120, 25, 35) else Color.rgb(30, 125, 65)
        startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        startButton.setTextColor(Color.WHITE)
        (startButton as? AnalyzerIconButton)?.icon =
            if (running) AnalyzerIconButton.Icon.STOP else AnalyzerIconButton.Icon.PLAY
    }

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

    private fun showSoundIdDialog() {
        leaveFullScaleKeyboard()
        if (soundIdPanel != null) {
            switchAnalyzerMode?.invoke(if (soundModeOpen) "none" else "sound")
            return
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.roundToInt()).toFloat()
        }

        val background = Color.rgb(12, 18, 27)
        val cardColor = Color.rgb(22, 31, 43)
        val accent = Color.rgb(75, 196, 255)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(6), 0, dp(6), dp(6))
            setBackgroundColor(background)
        }

        val livePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            this.background = roundedBackground(cardColor, 20f)
        }
        val duration = label("", 12f, Color.LTGRAY)
        val bestSound = label("Tap Listen to identify a sound", 20f, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(12), 0, dp(6))
        }
        val score = label("", 12f, accent, true).apply { letterSpacing = 0.06f }
        val alternatives = label("", 12f, Color.LTGRAY).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        val personalSounds = Button(this).apply {
            text = "＋ 〰"
            isAllCaps = false
            textSize = 19f
            setTextColor(Color.WHITE)
            minHeight = dp(42)
            minWidth = dp(58)
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "My Sound ID: add or manage personal sounds"
            this.background = roundedBackground(Color.rgb(42, 57, 75), 15f)
            setOnClickListener { showMySoundIdsDialog() }
        }
        val toggle = AnalyzerIconButton(this, if (soundIdActive) AnalyzerIconButton.Icon.STOP else AnalyzerIconButton.Icon.MIC).apply {
            minWidth = dp(46)
            minimumWidth = dp(46)
            minHeight = dp(46)
            minimumHeight = dp(46)
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            playStopIconScale = 0.48f
            setOnClickListener {
                val start = !soundIdActive
                soundIdActive = start
                updateSoundIdToggleStyle()
                if (start) {
                    soundIdLastSummary = null
                    soundIdLivePanel?.visibility = View.VISIBLE
                    soundIdSummaryPanel?.visibility = View.GONE
                    soundIdPdfButton?.visibility = View.GONE
                    bestSound.text = "Listening for sounds…"
                    score.text = ""
                    alternatives.text = ""
                    duration.text = "Listening · 0:00"
                    soundIdListeningStartedAt = SystemClock.elapsedRealtime()
                    soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
                    soundIdTimerHandler.post(soundIdTimerRunnable)
                    soundIdEngine.setListening(true)
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        if (!running) startAnalysis()
                    } else {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
                    }
                } else {
                    soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
                    duration.text = ""
                    soundIdEngine.setListening(false)
                }
            }
        }
        soundIdDurationView = duration
        soundIdBestSoundView = bestSound
        soundIdScoreView = score
        soundIdAlternativesView = alternatives
        soundIdToggleButton = toggle
        livePanel.addView(duration, LinearLayout.LayoutParams(-1, -2))
        livePanel.addView(bestSound)
        livePanel.addView(score)
        livePanel.addView(alternatives)
        livePanel.addView(personalSounds, LinearLayout.LayoutParams(-2, dp(42)).apply {
            topMargin = dp(9)
            gravity = Gravity.END
        })

        val summaryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            this.background = roundedBackground(cardColor, 20f)
            visibility = View.GONE
        }
        val chart = SoundSessionChartView(this)
        soundIdSummaryChart = chart
        summaryPanel.addView(chart, LinearLayout.LayoutParams(-1, dp(132)))
        val timeline = SoundSessionTimelineView(this)
        soundIdSummaryTimeline = timeline
        summaryPanel.addView(timeline, LinearLayout.LayoutParams(-1, dp(22)))
        val summaryPersonalSounds = Button(this).apply {
            text = "＋ 〰"
            isAllCaps = false
            textSize = 19f
            setTextColor(Color.WHITE)
            minHeight = dp(42)
            minWidth = dp(58)
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "My Sound ID: add or manage personal sounds"
            this.background = roundedBackground(Color.rgb(42, 57, 75), 15f)
            setOnClickListener { showMySoundIdsDialog() }
        }
        summaryPanel.addView(summaryPersonalSounds, LinearLayout.LayoutParams(-2, dp(42)).apply {
            topMargin = dp(7)
            gravity = Gravity.END
        })
        val export = Button(this).apply {
            text = "↓  PDF"
            isAllCaps = false
            textSize = 14f
            setPadding(dp(18), 0, dp(18), 0)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            contentDescription = "Save sound session as PDF"
            this.background = roundedBackground(Color.rgb(40, 83, 111), 18f)
            setOnClickListener { exportSoundIdSummary() }
        }
        val panels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(livePanel, LinearLayout.LayoutParams(-1, -2))
            addView(summaryPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
        val panelScroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(panels)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(toggle, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
            addView(export, LinearLayout.LayoutParams(-2, dp(46)))
        }
        root.addView(panelScroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(actionRow, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4) })

        soundIdLivePanel = livePanel
        soundIdSummaryPanel = summaryPanel
        soundIdPdfButton = export
        updateSoundIdToggleStyle()
        if (soundIdActive) {
            livePanel.visibility = View.VISIBLE
            summaryPanel.visibility = View.GONE
        } else if (soundIdLastSummary != null) {
            showSoundIdSummary(soundIdLastSummary!!)
        }

        soundIdPanel = root
        val split = splitContainer ?: return
        split.addView(root, 2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f))
        root.visibility = View.GONE
        switchAnalyzerMode?.invoke("sound")
    }

    private fun showDialogCenteredOnScreen(
        dialog: Dialog,
        dragHandle: View? = null,
        widthFraction: Float? = null,
        anchorTop: View? = null
    ) {
        val window = dialog.window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val margin = (16f * resources.displayMetrics.density).roundToInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val maxScreenWidth = (screenWidth - margin * 2).coerceAtLeast(1)
        val targetWidth = if (widthFraction != null) {
            minOf((screenWidth * widthFraction.coerceIn(0.5f, 0.98f)).roundToInt(), maxScreenWidth)
        } else {
            val paneWidth = (soundIdPanel as? View)?.width?.takeIf { it > 0 } ?: (screenWidth / 2)
            minOf((paneWidth - margin * 2).coerceAtLeast(margin * 12), maxScreenWidth)
        }
        val decor = window.decorView
        // Keep the window hidden until its first real layout has been centered on screen.
        decor.alpha = 0f
        window.setWindowAnimations(0)
        window.setDimAmount(0f)
        window.setGravity(Gravity.TOP or Gravity.LEFT)
        window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (screenWidth - targetWidth) / 2
            y = screenHeight / 3
        }
        val positionBeforeFirstDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!dialog.isShowing) {
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                if (decor.width <= 0 || decor.height <= 0) return false
                val dialogWidth = decor.width
                val dialogHeight = decor.height
                val attributes = window.attributes
                attributes.gravity = Gravity.TOP or Gravity.LEFT
                attributes.x = ((screenWidth - dialogWidth) / 2)
                    .coerceIn(margin, (screenWidth - dialogWidth - margin).coerceAtLeast(margin))
                val anchorPosition = IntArray(2)
                val visibleAnchor = anchorTop?.takeIf { it.isShown && it.height > 0 }
                visibleAnchor?.getLocationOnScreen(anchorPosition)
                val lowestAllowedY = (screenHeight - dialogHeight - margin).coerceAtLeast(margin)
                attributes.y = if (visibleAnchor != null) {
                    val moveUpBy = (32f * resources.displayMetrics.density).roundToInt()
                    (anchorPosition[1] - moveUpBy).coerceAtLeast(margin)
                } else {
                    ((screenHeight - dialogHeight) / 2).coerceIn(margin, lowestAllowedY)
                }
                attributes.dimAmount = 0.38f
                window.attributes = attributes
                decor.alpha = 1f
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                // Let the window manager apply the anchored position before this dialog is drawn.
                return false
            }
        }
        dialog.show()
        window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        decor.viewTreeObserver.addOnPreDrawListener(positionBeforeFirstDraw)
        dragHandle?.let { attachDialogDragHandle(dialog, it, margin, screenWidth, screenHeight) }
    }

    private fun attachDialogDragHandle(
        dialog: Dialog,
        handle: View,
        margin: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            val window = dialog.window ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = window.attributes.x
                    startY = window.attributes.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val attributes = window.attributes
                    val dialogWidth = window.decorView.width.coerceAtLeast(1)
                    val dialogHeight = window.decorView.height.coerceAtLeast(1)
                    attributes.gravity = Gravity.TOP or Gravity.LEFT
                    attributes.x = (startX + event.rawX - downRawX).roundToInt()
                        .coerceIn(margin, (screenWidth - dialogWidth - margin).coerceAtLeast(margin))
                    attributes.y = (startY + event.rawY - downRawY).roundToInt()
                        .coerceIn(margin, (screenHeight - dialogHeight - margin).coerceAtLeast(margin))
                    window.attributes = attributes
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun showPersonalSoundExportInfo() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 21, 31))
                cornerRadius = dp(20).toFloat()
            }
        }
        content.addView(TextView(this).apply {
            text = "Export recordings to review or share"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Choose an export folder the first time. New recordings are saved there as individual WAV files named after their sound. Already exported samples are skipped, so each export adds only new recordings."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(14))
        })
        val done = Button(this).apply {
            text = "Got it"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(39, 113, 147))
                cornerRadius = dp(14).toFloat()
            }
        }
        content.addView(done, LinearLayout.LayoutParams(-1, dp(44)))
        val dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        done.setOnClickListener { dialog.dismiss() }
        showDialogCenteredOnScreen(dialog)
    }

    private fun showDeletePersonalSoundDialog(
        profile: PersonalSoundProfile,
        sampleIds: Set<String>,
        onDeleted: () -> Unit
    ) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 21, 31))
                cornerRadius = dp(20).toFloat()
            }
        }
        content.addView(TextView(this).apply {
            text = "Delete saved sound?"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            val sampleCount = sampleIds.size
            text = if (sampleCount == 1) "Remove “${profile.name}” and its saved recording from this phone?"
            else "Remove “${profile.name}” and all $sampleCount saved recordings from this phone?"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(14))
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val dialog = Dialog(this)
        actions.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 57, 75))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        actions.addView(Button(this).apply {
            text = "Delete"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(164, 69, 80))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                if (personalPreviewKey?.removePrefix("saved:")?.let { it in sampleIds } == true) stopPersonalSoundPreview()
                soundIdEngine.removePersonalSounds(profile.name)
                onDeleted()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        content.addView(actions)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        showDialogCenteredOnScreen(dialog)
    }

    private fun showMySoundIdsDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun panel(color: Int, radius: Int = 15) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = panel(Color.rgb(12, 18, 27), 20)
        }
        lateinit var managerDialog: Dialog
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "My Sound ID"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "Done"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = panel(Color.rgb(39, 113, 147), 14)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { managerDialog.dismiss() }
        }, LinearLayout.LayoutParams(-2, dp(40)))
        content.addView(header)
        content.addView(TextView(this).apply {
            text = "Saved sounds · recordings are grouped by name"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(4))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(list)
        }
        fun refreshList() {
            list.removeAllViews()
            val profiles = soundIdEngine.getPersonalSounds()
            val rowHeightDp = maxOf(54, (22f * resources.configuration.fontScale + 8f).roundToInt())
            if (profiles.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "No personal sounds yet. Record a short sample, then give it a name."
                    textSize = 14f
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(10))
                })
            } else profiles.asReversed()
                .groupBy { it.name.trim().lowercase(java.util.Locale.ROOT) }
                .values
                .forEach { samples ->
                val profile = samples.first()
                val groupIds = samples.map { it.id }.toSet()
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(rowHeightDp)
                    clipChildren = false
                    clipToPadding = false
                    setPadding(dp(12), dp(2), dp(7), dp(2))
                    background = panel(Color.rgb(30, 41, 55), 13)
                }
                row.addView(TextView(this).apply {
                    text = "〰   ${profile.name}\n${samples.size} sample${if (samples.size == 1) "" else "s"}"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = true
                    setPadding(0, dp(2), 0, dp(10))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this).apply {
                    text = "▶"
                    isAllCaps = false
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    background = panel(Color.TRANSPARENT, 12)
                    contentDescription = "Play saved recording: ${profile.name}"
                    setOnClickListener { view ->
                        togglePersonalSoundPreview(profile.id, profile.name, view as Button)
                    }
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                row.addView(Button(this).apply {
                    text = "+"
                    isAllCaps = false
                    textSize = 19f
                    setTextColor(Color.rgb(105, 220, 185))
                    background = panel(Color.TRANSPARENT, 12)
                    contentDescription = "Add another sample for ${profile.name}"
                    setOnClickListener {
                        showPersonalSoundRecorderDialog({ refreshList() }, profile.name)
                    }
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                row.addView(Button(this).apply {
                    text = "✎"
                    isAllCaps = false
                    textSize = 17f
                    setTextColor(Color.rgb(135, 205, 255))
                    background = panel(Color.TRANSPARENT, 12)
                    contentDescription = "Rename ${profile.name}"
                    setOnClickListener { showRenamePersonalSoundDialog(profile, samples.size) { refreshList() } }
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                row.addView(Button(this).apply {
                    text = "×"
                    isAllCaps = false
                    textSize = 18f
                    setTextColor(Color.LTGRAY)
                    background = panel(Color.TRANSPARENT, 12)
                    contentDescription = "Delete ${profile.name}"
                    setOnClickListener {
                        showDeletePersonalSoundDialog(profile, groupIds) { refreshList() }
                    }
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
            }
            val soundCount = profiles.asReversed().groupBy { it.name.trim().lowercase(java.util.Locale.ROOT) }.size
            val visibleRows = if (profiles.isEmpty()) 1 else minOf(soundCount, 3)
            listScroll.layoutParams = LinearLayout.LayoutParams(-1, dp(visibleRows * (rowHeightDp + 7)))
            listScroll.isVerticalScrollBarEnabled = soundCount > 3
        }
        content.addView(listScroll)
        content.addView(Button(this).apply {
            text = "＋  Add a sound"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = panel(Color.rgb(39, 113, 147), 16)
            setOnClickListener { showPersonalSoundRecorderDialog(onSaved = { refreshList() }) }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        val fileActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fileActions.addView(AnalyzerIconButton(this, AnalyzerIconButton.Icon.IMPORT).apply {
            background = panel(Color.rgb(42, 57, 75), 13)
            setOnClickListener { importPersonalSoundProfiles() }
        }, LinearLayout.LayoutParams(dp(46), dp(42)).apply { marginEnd = dp(7) })
        fileActions.addView(AnalyzerIconButton(this, AnalyzerIconButton.Icon.EXPORT).apply {
            background = panel(Color.rgb(42, 57, 75), 13)
            setOnClickListener { exportPersonalSoundProfiles() }
        }, LinearLayout.LayoutParams(dp(46), dp(42)).apply { marginEnd = dp(7) })
        fileActions.addView(Button(this).apply {
            text = "i"
            isAllCaps = false
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            contentDescription = "Information about exporting recordings"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(42, 57, 75))
                setStroke(dp(1), Color.rgb(94, 116, 139))
            }
            setOnClickListener { showPersonalSoundExportInfo() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(fileActions)
        refreshList()
        val refreshCallback: () -> Unit = { refreshList() }
        personalSoundListRefresh = refreshCallback
        managerDialog = Dialog(this)
        managerDialog.setContentView(content)
        managerDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        managerDialog.setCancelable(false)
        managerDialog.setCanceledOnTouchOutside(false)
        managerDialog.setOnDismissListener {
            if (personalSoundListRefresh === refreshCallback) personalSoundListRefresh = null
        }
        showDialogCenteredOnScreen(managerDialog, title, anchorTop = soundIdPanel)
    }

    private fun showRenamePersonalSoundDialog(profile: PersonalSoundProfile, sampleCount: Int, onRenamed: () -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 21, 31))
                cornerRadius = dp(20).toFloat()
            }
        }
        content.addView(TextView(this).apply {
            text = "Rename saved sound"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        val nameInput = EditText(this).apply {
            setText(profile.name)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 55))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), 0, dp(14), 0)
        }
        content.addView(nameInput, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(12)
            bottomMargin = dp(if (sampleCount > 1) 5 else 12)
        })
        if (sampleCount > 1) content.addView(TextView(this).apply {
            text = "This name applies to all $sampleCount samples."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(2), 0, 0, dp(10))
        })
        val dialog = Dialog(this)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 57, 75))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        val saveName: () -> Unit = {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                nameInput.error = "Enter a name"
            } else {
                val renamedIds = soundIdEngine.renamePersonalSounds(profile.name, name)
                if (renamedIds.isNotEmpty()) {
                    if (personalPreviewKey?.removePrefix("saved:")?.let { it in renamedIds } == true) {
                        personalPreviewButton?.contentDescription = "Play saved recording: $name"
                    }
                    onRenamed()
                    dialog.dismiss()
                    Toast.makeText(this, "Renamed to $name", Toast.LENGTH_SHORT).show()
                }
            }
        }
        actions.addView(Button(this).apply {
            text = "Save"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(39, 132, 166))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { saveName() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(actions)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        val renameDialogWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(260))
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
            setGravity(Gravity.CENTER)
            setLayout(renameDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnShowListener {
            nameInput.requestFocus()
            nameInput.selectAll()
            nameInput.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.showSoftInput(nameInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveName()
                true
            } else false
        }
        dialog.show()
        dialog.window?.setLayout(renameDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun requestPersonalSoundExportFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, personalSoundExportFolderRequestCode)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not choose an export folder.", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportPersonalSoundProfiles() {
        if (!personalSoundExportInProgress.compareAndSet(false, true)) {
            Toast.makeText(this, "An export is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        val profiles = soundIdEngine.getPersonalSounds()
        if (profiles.isEmpty()) {
            personalSoundExportInProgress.set(false)
            Toast.makeText(this, "Record and save a personal sound first.", Toast.LENGTH_SHORT).show()
            return
        }
        val preferences = getSharedPreferences("personal_sound_export", Context.MODE_PRIVATE)
        val exportedIds = preferences.getStringSet("exported_profile_ids", emptySet()).orEmpty()
        val newProfiles = profiles.filter { it.id !in exportedIds }
        if (newProfiles.isEmpty()) {
            personalSoundExportInProgress.set(false)
            Toast.makeText(this, "There are no new samples to export.", Toast.LENGTH_SHORT).show()
            return
        }
        val folderString = preferences.getString("folder_uri", null)
        if (folderString.isNullOrBlank()) {
            personalSoundExportInProgress.set(false)
            requestPersonalSoundExportFolder()
            return
        }
        val folderUri = Uri.parse(folderString)
        val hasWritePermission = contentResolver.persistedUriPermissions.any {
            it.uri == folderUri && it.isWritePermission
        }
        if (!hasWritePermission) {
            preferences.edit().remove("folder_uri").apply()
            personalSoundExportInProgress.set(false)
            requestPersonalSoundExportFolder()
            return
        }

        Toast.makeText(this, "Saving new WAV samples…", Toast.LENGTH_SHORT).show()
        Thread({
            var exportedCount = 0
            val failures = mutableListOf<String>()
            val markedExported = exportedIds.toMutableSet()
            try {
                val folderDocumentId = android.provider.DocumentsContract.getTreeDocumentId(folderUri)
                val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(folderUri, folderDocumentId)
                for (profile in newProfiles) {
                    try {
                        val audio = soundIdEngine.getPersonalSoundAudio(profile.id)
                            ?: error("Recording is missing for ${profile.name}.")
                        val safeName = profile.name.trim()
                            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
                            .trim('.', '_', '-')
                            .take(48)
                            .ifBlank { "sound" }
                        val fileName = "${safeName}__${profile.id.take(8)}.wav"
                        val documentUri = android.provider.DocumentsContract.createDocument(
                            contentResolver,
                            parentUri,
                            "audio/wav",
                            fileName
                        ) ?: error("Could not create $fileName in the selected folder.")
                        val wav = encodePersonalSoundWav(audio)
                        val output = contentResolver.openOutputStream(documentUri, "w")
                            ?: error("Could not write $fileName.")
                        output.use { it.write(wav) }
                        markedExported.add(profile.id)
                        preferences.edit().putStringSet("exported_profile_ids", markedExported.toSet()).apply()
                        exportedCount++
                    } catch (error: Exception) {
                        failures.add(error.message ?: "Could not export ${profile.name}.")
                    }
                }
            } catch (error: Exception) {
                failures.add(error.message ?: "Could not access the selected folder.")
            } finally {
                personalSoundExportInProgress.set(false)
                runOnUiThread {
                    val message = when {
                        failures.isEmpty() -> "Saved $exportedCount new WAV sample(s) to the selected folder."
                        exportedCount == 0 -> "Could not export samples: ${failures.first()}"
                        else -> "Saved $exportedCount sample(s); skipped ${failures.size}. ${failures.first()}"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }, "PersonalSoundExporter").apply { priority = Thread.NORM_PRIORITY; start() }
    }

    private fun importPersonalSoundProfiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "audio/wav",
                "audio/x-wav",
                "audio/vnd.wave"
            ))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, personalSoundImportRequestCode)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open sample files.", Toast.LENGTH_LONG).show()
        }
    }

    private fun importPersonalSoundFiles(uris: List<Uri>) {
        if (!personalSoundImportInProgress.compareAndSet(false, true)) {
            Toast.makeText(this, "A sample import is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Importing sound samples…", Toast.LENGTH_SHORT).show()
        Thread({
            var imported = 0
            val failures = mutableListOf<String>()
            try {
                for (uri in uris) {
                    val fileName = getImportedDocumentName(uri)
                    try {
                        val bytes = readImportedDocument(uri, 32 * 1024 * 1024)
                        when {
                            isZipArchive(bytes) -> imported += importPersonalSoundArchive(bytes)
                            isWavFile(bytes) -> {
                                val audio = decodePersonalSoundWav(bytes)
                                    ?: error("Unsupported WAV format or duration. Use a PCM or 32-bit float WAV between 1 and 30 seconds.")
                                val soundName = fileName.substringBeforeLast('.', fileName)
                                    .replace(Regex("__[0-9a-fA-F]{8}$"), "")
                                    .trim().take(80).ifBlank { "Imported sound" }
                                val result = arrayOfNulls<String>(1)
                                val finished = java.util.concurrent.CountDownLatch(1)
                                soundIdEngine.createPersonalSoundFromAudio(soundName, audio) { errorMessage ->
                                    result[0] = errorMessage
                                    finished.countDown()
                                }
                                finished.await()
                                result[0]?.let { error(it) }
                                imported++
                            }
                            else -> error("Choose a My Sound ID ZIP archive or WAV recording.")
                        }
                    } catch (error: Exception) {
                        failures.add("$fileName: ${error.message ?: "could not import"}")
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                failures.add("Import was interrupted.")
            } finally {
                personalSoundImportInProgress.set(false)
                runOnUiThread {
                    personalSoundListRefresh?.invoke()
                    val message = when {
                        failures.isEmpty() -> "Imported $imported sound sample(s)."
                        imported == 0 -> "Could not import samples: ${failures.first()}"
                        else -> "Imported $imported sample(s); skipped ${failures.size}. ${failures.first()}"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }, "PersonalSoundImporter").apply { priority = Thread.NORM_PRIORITY; start() }
    }

    private fun getImportedDocumentName(uri: Uri): String {
        val name = contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "sample"
    }

    private fun readImportedDocument(uri: Uri, maxBytes: Int): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: error("Could not read the selected file.")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "File is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun isZipArchive(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
                (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
                (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())

    private fun isWavFile(bytes: ByteArray): Boolean =
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"

    private fun encodePersonalSoundWav(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(48_000)
            putInt(96_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
            samples.forEach { putShort(it) }
        }.array()
    }

    private fun importPersonalSoundArchive(bytes: ByteArray): Int {
        require(bytes.size <= 32 * 1024 * 1024) { "Archive is too large." }
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var totalBytes = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val content = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count <= 0) break
                        totalBytes += count
                        require(totalBytes <= 32 * 1024 * 1024) { "Archive contents are too large." }
                        content.write(buffer, 0, count)
                    }
                    entries[entry.name] = content.toByteArray()
                }
                zip.closeEntry()
            }
        }
        val manifestBytes = entries["manifest.json"] ?: error("Archive is missing manifest.json")
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.optString("format") == "spectrum-analyzer-personal-sound-samples") {
            "This is not a My Sound ID sample archive."
        }
        require(manifest.optInt("format_version") == 1) { "This sample archive version is not supported." }
        val samples = manifest.optJSONArray("samples") ?: error("Archive has no sample list.")
        var imported = 0
        for (index in 0 until samples.length()) {
            val item = samples.optJSONObject(index) ?: continue
            val name = item.optString("name").trim().take(80)
            val vector = item.optJSONArray("embedding") ?: continue
            val audioPath = item.optString("audio")
            val wav = entries[audioPath] ?: continue
            if (name.isBlank() || vector.length() != PERSONAL_SOUND_FEATURE_COUNT) continue
            val audio = decodePersonalSoundWav(wav) ?: continue
            val embedding = FloatArray(PERSONAL_SOUND_FEATURE_COUNT) { vector.optDouble(it).toFloat() }
            if (embedding.any { !it.isFinite() }) continue
            soundIdEngine.savePersonalSound(name, embedding, audio)
            imported++
        }
        return imported
    }

    private fun decodePersonalSoundWav(wav: ByteArray): ShortArray? {
        if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var blockAlign = 0
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= wav.size) {
            val chunkId = String(wav, offset, 4, Charsets.US_ASCII)
            val chunkSizeLong = buffer.getInt(offset + 4).toLong() and 0xffff_ffffL
            val chunkData = offset + 8
            if (chunkSizeLong > wav.size - chunkData) return null
            val chunkSize = chunkSizeLong.toInt()
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16) return null
                    format = buffer.getShort(chunkData).toInt() and 0xffff
                    if (format == 0xfffe && chunkSize >= 40) {
                        // WAVE_FORMAT_EXTENSIBLE stores the underlying PCM/float format in its GUID.
                        format = buffer.getInt(chunkData + 24)
                    }
                    channels = buffer.getShort(chunkData + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(chunkData + 4)
                    blockAlign = buffer.getShort(chunkData + 12).toInt() and 0xffff
                    bitsPerSample = buffer.getShort(chunkData + 14).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = chunkData
                    dataSize = chunkSize
                }
            }
            val next = chunkData.toLong() + chunkSizeLong + (chunkSizeLong and 1L)
            if (next > wav.size) break
            offset = next.toInt()
        }
        val bytesPerSample = bitsPerSample / 8
        if ((format != 1 && format != 3) || channels !in 1..8 || sampleRate !in 8_000..192_000 ||
            bitsPerSample !in setOf(8, 16, 24, 32) || (format == 3 && bitsPerSample != 32) ||
            blockAlign != channels * bytesPerSample || dataOffset < 0 || dataSize < blockAlign) return null
        val frameCount = dataSize / blockAlign
        val durationSeconds = frameCount.toDouble() / sampleRate
        if (durationSeconds < 0.975 || durationSeconds > 30.0) return null

        val mono = FloatArray(frameCount) { frame ->
            var mixed = 0.0
            val frameOffset = dataOffset + frame * blockAlign
            for (channel in 0 until channels) {
                val sampleOffset = frameOffset + channel * bytesPerSample
                val sample = when {
                    format == 3 -> Float.fromBits(buffer.getInt(sampleOffset)).toDouble()
                        .takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
                    bitsPerSample == 8 -> ((wav[sampleOffset].toInt() and 0xff) - 128) / 128.0
                    bitsPerSample == 16 -> buffer.getShort(sampleOffset).toInt() / 32768.0
                    bitsPerSample == 24 -> {
                        var value = (wav[sampleOffset].toInt() and 0xff) or
                                ((wav[sampleOffset + 1].toInt() and 0xff) shl 8) or
                                ((wav[sampleOffset + 2].toInt() and 0xff) shl 16)
                        if (value and 0x800000 != 0) value = value or -0x1000000
                        value / 8_388_608.0
                    }
                    else -> buffer.getInt(sampleOffset) / 2_147_483_648.0
                }
                mixed += sample
            }
            (mixed / channels).coerceIn(-1.0, 1.0).toFloat()
        }
        val outputCount = (frameCount.toLong() * 48_000L / sampleRate).toInt()
        if (outputCount < 46_800 || outputCount > 48_000 * 30) return null
        return ShortArray(outputCount) { index ->
            val sourcePosition = index.toDouble() * sampleRate / 48_000.0
            val first = sourcePosition.toInt().coerceIn(0, mono.lastIndex)
            val second = (first + 1).coerceAtMost(mono.lastIndex)
            val fraction = (sourcePosition - first).coerceIn(0.0, 1.0)
            ((mono[first] * (1.0 - fraction) + mono[second] * fraction) * 32767.0)
                .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun setLiveSoundPredictionsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        soundIdBestSoundView?.visibility = visibility
        soundIdScoreView?.visibility = visibility
        soundIdAlternativesView?.visibility = visibility
    }

    private fun showPersonalSoundRecorderDialog(onSaved: () -> Unit, initialName: String? = null) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 21, 31))
                cornerRadius = dp(22).toFloat()
            }
        }
        lateinit var dialog: Dialog
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Record a sound"
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val closeButton = Button(this).apply {
            text = "×"
            isAllCaps = false
            textSize = 21f
            setTextColor(Color.LTGRAY)
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 46, 61))
                cornerRadius = dp(14).toFloat()
            }
            contentDescription = "Close"
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(header)
        val instruction = TextView(this).apply {
            text = "Choose a length, then play a clear example of the sound near your phone."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(12))
        }
        val countdown = TextView(this).apply {
            text = "Ready · 10 seconds"
            textSize = 19f
            setTextColor(Color.rgb(75, 196, 255))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 36, 51))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(8), dp(15), dp(8), dp(15))
        }
        var selectedRecordingSeconds = PERSONAL_SOUND_SAMPLE_SECONDS
        fun durationButton(label: String) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(5), 0, dp(5), 0)
            setTextColor(Color.WHITE)
        }
        val fiveSecondButton = durationButton("5 s · Quick")
        val tenSecondButton = durationButton("10 s · Recommended")
        fun updateDurationChoices() {
            fiveSecondButton.background = GradientDrawable().apply {
                setColor(if (selectedRecordingSeconds == 5) Color.rgb(39, 113, 147) else Color.rgb(30, 41, 55))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), if (selectedRecordingSeconds == 5) Color.rgb(75, 196, 255) else Color.rgb(56, 73, 91))
            }
            tenSecondButton.background = GradientDrawable().apply {
                setColor(if (selectedRecordingSeconds == 10) Color.rgb(39, 113, 147) else Color.rgb(30, 41, 55))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), if (selectedRecordingSeconds == 10) Color.rgb(75, 196, 255) else Color.rgb(56, 73, 91))
            }
        }
        val durationChoices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        durationChoices.addView(fiveSecondButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        durationChoices.addView(tenSecondButton, LinearLayout.LayoutParams(0, dp(42), 1.25f))
        updateDurationChoices()
        val record = Button(this).apply {
            text = "●   Start recording"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(39, 113, 147))
                cornerRadius = dp(17).toFloat()
            }
        }
        fiveSecondButton.setOnClickListener {
            if (record.isEnabled) {
                selectedRecordingSeconds = 5
                updateDurationChoices()
                countdown.text = "Ready · 5 seconds"
            }
        }
        tenSecondButton.setOnClickListener {
            if (record.isEnabled) {
                selectedRecordingSeconds = 10
                updateDurationChoices()
                countdown.text = "Ready · 10 seconds"
            }
        }
        content.addView(instruction)
        content.addView(durationChoices, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })
        content.addView(countdown, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        content.addView(record, LinearLayout.LayoutParams(-1, dp(50)))
        dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        val handler = Handler(Looper.getMainLooper())
        var cancelled = false
        var handingOffToNaming = false
        var countdownRemaining = 3
        var recordingRemaining = 10
        var recordingStarted = false
        lateinit var captureTimeout: Runnable
        captureTimeout = Runnable {
            if (dialog.isShowing && !cancelled) {
                soundIdEngine.cancelPersonalSoundCapture()
                setLiveSoundPredictionsVisible(true)
                countdown.text = "No usable sample captured. Try again."
                record.isEnabled = true
                fiveSecondButton.isEnabled = true
                tenSecondButton.isEnabled = true
                record.text = "●  Record again"
            }
        }
        lateinit var ticker: Runnable
        ticker = object : Runnable {
            override fun run() {
                if (cancelled || !dialog.isShowing) return
                when {
                    countdownRemaining > 0 -> {
                        countdown.text = "Recording starts in $countdownRemaining…"
                        countdownRemaining--
                        handler.postDelayed(this, 1000L)
                    }
                    !recordingStarted -> {
                        recordingStarted = true
                        countdown.text = "Recording · $selectedRecordingSeconds seconds remaining"
                        if (!soundIdActive) soundIdToggleButton?.performClick()
                        soundIdEngine.beginPersonalSoundCapture(selectedRecordingSeconds)
                        handler.postDelayed(captureTimeout, 20_000L)
                        handler.postDelayed(this, 1000L)
                    }
                    recordingRemaining > 0 -> {
                        recordingRemaining--
                        countdown.text = "Recording · $recordingRemaining seconds remaining"
                        handler.postDelayed(this, 1000L)
                    }
                    else -> countdown.text = "Finishing sample…"
                }
            }
        }
        record.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
            }
            setLiveSoundPredictionsVisible(false)
            record.isEnabled = false
            fiveSecondButton.isEnabled = false
            tenSecondButton.isEnabled = false
            countdownRemaining = 3
            recordingRemaining = selectedRecordingSeconds
            recordingStarted = false
            handler.post(ticker)
        }
        soundIdEngine.setPersonalSoundCapturedListener { embedding, audio ->
            runOnUiThread {
                if (dialog.isShowing) {
                    cancelled = true
                    handler.removeCallbacks(ticker)
                    handler.removeCallbacks(captureTimeout)
                    soundIdEngine.setPersonalSoundCapturedListener(null)
                    handingOffToNaming = true
                    dialog.dismiss()
                    showPersonalSoundNamingDialog(embedding, audio, onSaved, initialName)
                }
            }
        }
        dialog.setOnDismissListener {
            cancelled = true
            handler.removeCallbacks(ticker)
            handler.removeCallbacks(captureTimeout)
            soundIdEngine.cancelPersonalSoundCapture()
            soundIdEngine.setPersonalSoundCapturedListener(null)
            if (!handingOffToNaming) setLiveSoundPredictionsVisible(true)
        }
        showDialogCenteredOnScreen(dialog)
    }

    private fun showPersonalSoundNamingDialog(
        embedding: FloatArray,
        audio: ShortArray,
        onSaved: () -> Unit,
        initialName: String? = null
    ) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 21, 31))
                cornerRadius = dp(22).toFloat()
            }
        }
        lateinit var dialog: Dialog
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Name this sound"
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val closeButton = Button(this).apply {
            text = "×"
            isAllCaps = false
            textSize = 21f
            setTextColor(Color.LTGRAY)
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 46, 61))
                cornerRadius = dp(14).toFloat()
            }
            contentDescription = "Close"
        }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(header)
        content.addView(TextView(this).apply {
            text = "Listen, name it, and save."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(7))
        })
        val input = EditText(this).apply {
            hint = "e.g. Elephant"
            setText(initialName?.takeIf { it.isNotBlank() } ?: soundIdEngine.suggestPersonalSoundName())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 55))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), 0, dp(14), 0)
        }
        val preview = Button(this).apply {
            text = "▶   Play sample"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 57, 75))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { view ->
                togglePersonalSoundPreview(audio, "recorded sample", view as Button)
            }
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(7) })
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(7) })
        val save = Button(this).apply {
            text = "✓"
            isAllCaps = false
            textSize = 22f
            setTextColor(Color.WHITE)
            contentDescription = "Save sound sample"
            background = GradientDrawable().apply {
                setColor(Color.rgb(39, 132, 166))
                cornerRadius = dp(16).toFloat()
            }
        }
        inputRow.addView(save, LinearLayout.LayoutParams(dp(48), dp(46)))
        content.addView(inputRow)

        fun saveSample() {
            val soundName = input.text.toString().trim()
            if (soundName.isEmpty()) {
                input.error = "Enter a name"
                input.requestFocus()
            } else {
                soundIdEngine.savePersonalSound(soundName, embedding, audio)
                Toast.makeText(this, "Saved $soundName", Toast.LENGTH_SHORT).show()
                onSaved()
                setLiveSoundPredictionsVisible(true)
                dialog.dismiss()
            }
        }
        closeButton.setOnClickListener {
            val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            input.clearFocus()
            inputManager?.hideSoftInputFromWindow(input.windowToken, 0)

            val confirmationContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(14, 21, 31))
                    cornerRadius = dp(20).toFloat()
                }
            }
            confirmationContent.addView(TextView(this).apply {
                text = "Discard this recording?"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            confirmationContent.addView(TextView(this).apply {
                text = "Are you sure you want to leave naming? The unsaved recording will be lost."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(8), 0, dp(14))
            })
            val confirmation = Dialog(this)
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            actions.addView(Button(this).apply {
                text = "Keep editing"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(42, 57, 75))
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener {
                    confirmation.dismiss()
                    input.requestFocus()
                    input.post { inputManager?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            actions.addView(Button(this).apply {
                text = "Discard"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(164, 69, 80))
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener {
                    if (personalPreviewKey == "pending:${System.identityHashCode(audio)}") {
                        stopPersonalSoundPreview()
                    }
                    setLiveSoundPredictionsVisible(true)
                    confirmation.dismiss()
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 0.75f))
            confirmationContent.addView(actions)
            confirmation.setContentView(confirmationContent)
            confirmation.setCanceledOnTouchOutside(false)
            confirmation.setCancelable(false)
            showDialogCenteredOnScreen(confirmation, widthFraction = 0.92f)
        }
        save.setOnClickListener { saveSample() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveSample()
                true
            } else false
        }
        dialog = Dialog(this)
        dialog.setContentView(content)
        val namingDialogWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(260))
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
            setGravity(Gravity.CENTER)
            setLayout(namingDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setOnShowListener {
            input.requestFocus()
            input.setSelection(0, input.text.length)
            input.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
        dialog.window?.setLayout(namingDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun togglePersonalSoundPreview(id: String, name: String, button: Button) {
        val samples = soundIdEngine.getPersonalSoundAudio(id)
        if (samples == null) {
            Toast.makeText(this, "Recording for $name is unavailable.", Toast.LENGTH_SHORT).show()
            return
        }
        togglePersonalSoundPreview("saved:$id", samples, name, button)
    }

    private fun togglePersonalSoundPreview(samples: ShortArray, name: String, button: Button) {
        togglePersonalSoundPreview("pending:${System.identityHashCode(samples)}", samples, name, button)
    }

    private fun togglePersonalSoundPreview(key: String, samples: ShortArray, name: String, button: Button) {
        if (samples.isEmpty()) {
            Toast.makeText(this, "No audio sample is available.", Toast.LENGTH_SHORT).show()
            return
        }
        if (personalPreviewKey == key && personalPreviewTrack != null) {
            val track = personalPreviewTrack ?: return
            if (personalPreviewPaused) {
                track.play()
                personalPreviewPaused = false
                button.text = "Ⅱ"
                button.contentDescription = "Pause recording: $name"
            } else {
                track.pause()
                personalPreviewPaused = true
                button.text = "▶"
                button.contentDescription = "Resume recording: $name"
            }
            return
        }
        try {
            stopPersonalSoundPreview()
            val bufferSize = AudioTrack.getMinBufferSize(
                48_000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                48_000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                Toast.makeText(this, "Could not play this recording.", Toast.LENGTH_SHORT).show()
                return
            }
            personalPreviewTrack = track
            personalPreviewKey = key
            personalPreviewButton = button
            personalPreviewPaused = false
            button.text = "Ⅱ"
            button.contentDescription = "Pause recording: $name"
            track.play()
            Thread {
                try {
                    var offset = 0
                    while (offset < samples.size && personalPreviewTrack === track) {
                        val written = track.write(
                            samples,
                            offset,
                            minOf(4096, samples.size - offset),
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        offset += written
                    }
                } catch (_: Exception) {
                } finally {
                    runOnUiThread {
                        if (personalPreviewTrack === track) {
                            try { track.stop() } catch (_: Exception) {}
                            track.release()
                            personalPreviewTrack = null
                            personalPreviewKey = null
                            personalPreviewButton?.text = "▶"
                            personalPreviewButton?.contentDescription = "Play recording: $name"
                            personalPreviewButton = null
                            personalPreviewPaused = false
                        }
                    }
                }
            }.start()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not play $name.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPersonalSoundPreview() {
        val track = personalPreviewTrack
        personalPreviewTrack = null
        personalPreviewKey = null
        personalPreviewPaused = false
        personalPreviewButton?.text = "▶"
        personalPreviewButton?.contentDescription = "Play recording"
        personalPreviewButton = null
        if (track != null) {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun updateSoundIdToggleStyle() {
        val density = resources.displayMetrics.density
        soundIdToggleButton?.background = GradientDrawable().apply {
            setColor(if (soundIdActive) Color.rgb(164, 69, 80) else Color.rgb(27, 121, 157))
            cornerRadius = 14f * density
        }
        (soundIdToggleButton as? AnalyzerIconButton)?.apply {
            icon = if (soundIdActive) AnalyzerIconButton.Icon.STOP else AnalyzerIconButton.Icon.MIC
            contentDescription = if (soundIdActive) "Stop listening" else "Start listening"
        }
    }

    private fun showSoundIdSummary(summary: SoundSessionSummary) {
        soundIdLastSummary = summary
        soundIdActive = false
        soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
        soundIdLivePanel?.visibility = View.GONE
        soundIdSummaryPanel?.visibility = View.VISIBLE
        soundIdPdfButton?.visibility = View.VISIBLE
        soundIdDurationView?.text = "${formatSoundDuration(summary.totalSeconds)} analyzed"
        soundIdBestSoundView?.text = ""
        soundIdScoreView?.text = ""
        soundIdAlternativesView?.text = ""
        soundIdSummaryChart?.setSummary(summary)
        soundIdSummaryTimeline?.setSummary(summary)
        updateSoundIdToggleStyle()
    }

    private fun formatSoundDuration(seconds: Double): String {
        val wholeSeconds = seconds.roundToInt().coerceAtLeast(0)
        return "${wholeSeconds / 60}:${(wholeSeconds % 60).toString().padStart(2, '0')}"
    }

    private fun exportSoundIdSummary() {
        val summary = soundIdLastSummary ?: return
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(22, 34, 50)
            textSize = 25f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(44, 56, 70); textSize = 15f }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(105, 117, 132); textSize = 12f }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 75, 210); style = Paint.Style.FILL }
        canvas.drawColor(Color.WHITE)
        canvas.drawText("Sound session summary", 42f, 62f, titlePaint)
        canvas.drawText("Analyzed ${formatSoundDuration(summary.totalSeconds)}", 42f, 91f, smallPaint)
        canvas.drawLine(42f, 111f, 553f, 111f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = 1f })
        canvas.drawText("Timeline · start to end · each segment is a classified window", 42f, 130f, smallPaint)
        val timelineLeft = 42f
        val timelineRight = 553f
        val timelineTop = 139f
        val timelineBottom = 153f
        canvas.drawRoundRect(timelineLeft, timelineTop, timelineRight, timelineBottom, 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 229, 235) })
        if (summary.timeline.isNotEmpty()) {
            val colorByLabel = summary.entries.take(SOUND_SESSION_COLORS.size)
                .mapIndexed { index, entry -> entry.label to SOUND_SESSION_COLORS[index] }.toMap()
            val columns = minOf(summary.timeline.size, (timelineRight - timelineLeft).toInt().coerceAtLeast(1))
            for (column in 0 until columns) {
                val eventIndex = (((column.toLong() + 1L) * summary.timeline.size.toLong()) / columns.toLong() - 1L)
                    .toInt().coerceIn(0, summary.timeline.lastIndex)
                val label = summary.timeline[eventIndex]
                val color = if (label.startsWith("Unclear")) SOUND_SESSION_OTHER_COLOR
                    else colorByLabel[label] ?: Color.rgb(68, 78, 91)
                val left = timelineLeft + (timelineRight - timelineLeft) * (column.toFloat() / columns.toFloat())
                val right = timelineLeft + (timelineRight - timelineLeft) * ((column + 1).toFloat() / columns.toFloat())
                barPaint.color = color
                canvas.drawRect(left, timelineTop, right + 0.25f, timelineBottom, barPaint)
            }
        }
        var y = 183f
        summary.entries.take(10).forEachIndexed { index, entry ->
            val label = entry.label.take(42)
            textPaint.typeface = if (index == 0) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            canvas.drawText(label, 42f, y, textPaint)
            canvas.drawText(String.format(java.util.Locale.US, "%.1f%% · %.1f s", entry.percent, entry.seconds), 553f, y, textPaint.apply { textAlign = Paint.Align.RIGHT })
            textPaint.textAlign = Paint.Align.LEFT
            barPaint.color = if (entry.label.startsWith("Unclear")) SOUND_SESSION_OTHER_COLOR
                else SOUND_SESSION_COLORS[index % SOUND_SESSION_COLORS.size]
            val barWidth = (entry.percent.coerceAtLeast(0.0) / 100.0 * 470.0).toFloat().coerceIn(0f, 470f)
            canvas.drawRoundRect(42f, y + 10f, 42f + barWidth, y + 17f, 3f, 3f, barPaint)
            y += 56f
        }
        canvas.drawText("Scores are ranking values, not probabilities.", 42f, 782f, smallPaint)
        canvas.drawText("Percentages show normalized shares; the timeline shows each window's strongest candidate.", 42f, 800f, smallPaint)
        pdf.finishPage(page)
        val output = java.io.ByteArrayOutputStream()
        try {
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
        pendingSoundIdPdf = output.toByteArray()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "sound-id-session.pdf")
        }
        try {
            startActivityForResult(intent, soundIdExportRequestCode)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open a location to save the summary.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFullScaleKeyboard() {
        if (!keyboardModeOpen || splitContainer == null) return
        moveKeyboardControlsToHeader()
        fullScaleKeyboardOpen = true
        (fullScaleKeysButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.FULL_SCALE_CLOSE
        toneModeOpen = false
        soundModeOpen = false
        spectrumView.setKeysFrequencyRangeActive(true)
        spectrumView.setDbRangeMode("KEYS")
        tonePanel?.visibility = View.GONE
        soundIdPanel?.visibility = View.GONE
        spectrumView.visibility = View.GONE
        keyboardSectionView?.visibility = View.VISIBLE
        splitContainer?.orientation = LinearLayout.VERTICAL
        keyboardSectionView?.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val pianoView = keyboardPanel as? PianoKeyboardView
        if (pianoView != null) {
            val cutoutSafeLeft = if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.decorView.rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
            } else {
                0
            }
            val viewLocation = IntArray(2)
            pianoView.getLocationInWindow(viewLocation)
            val localCutoutInset = (cutoutSafeLeft - viewLocation[0]).coerceAtLeast(0)
            val smallGap = (6f * resources.displayMetrics.density).roundToInt()
            pianoView.setSafeEdgeInset((localCutoutInset + smallGap).toFloat())
        }
        fullScaleKeysButton?.text = ""
        splitContainer?.requestLayout()
    }

    private fun moveKeyboardControlsToHeader() {
        val controls = keyboardControlsRow ?: return
        val header = spectrumHeader ?: return
        (controls.parent as? ViewGroup)?.removeView(controls)
        rangePickerButton?.visibility = View.GONE
        val density = resources.displayMetrics.density
        header.layoutParams = header.layoutParams.apply { height = (42f * density).roundToInt() }
        val peakReadoutSpace = (220f * density).roundToInt()
        val availableWidth = (header.width - peakReadoutSpace * 2 - (12f * density).roundToInt())
            .coerceAtLeast((180f * density).roundToInt())
        val targetWidth = minOf(availableWidth, (360f * density).roundToInt())
        header.addView(
            controls,
            android.widget.FrameLayout.LayoutParams(targetWidth, (42f * density).roundToInt(), Gravity.CENTER)
        )
    }

    private fun restoreKeyboardControls() {
        val controls = keyboardControlsRow ?: return
        val keyboardSection = keyboardSectionView as? ViewGroup ?: return
        (controls.parent as? ViewGroup)?.removeView(controls)
        spectrumHeader?.let { header ->
            if (spectrumHeaderCompactHeightPx > 0) {
                header.layoutParams = header.layoutParams.apply { height = spectrumHeaderCompactHeightPx }
            }
        }
        keyboardSection.addView(
            controls,
            0,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40f * resources.displayMetrics.density).roundToInt())
        )
        controls.post { applyKeyboardVolumeSafeInset() }
        rangePickerButton?.visibility = View.VISIBLE
        (keyboardPanel as? PianoKeyboardView)?.setSafeEdgeInset(0f)
    }

    private fun leaveFullScaleKeyboard() {
        if (!fullScaleKeyboardOpen) return
        fullScaleKeyboardOpen = false
        (fullScaleKeysButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.FULL_SCALE_OPEN
        restoreKeyboardControls()
        fullScaleKeysButton?.text = ""
        spectrumView.visibility = View.VISIBLE
        if (keyboardModeOpen) {
            splitContainer?.orientation = LinearLayout.VERTICAL
            spectrumView.layoutParams = LinearLayout.LayoutParams( ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            keyboardSectionView?.visibility = View.VISIBLE
            keyboardSectionView?.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            soundIdPanel?.visibility = View.GONE
            tonePanel?.visibility = View.GONE
        }
        splitContainer?.requestLayout()
    }

    @Suppress("unused")
    private fun showFullScaleKeyboardDialogLegacy() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun control(text: String, action: (Button) -> Unit) = Button(this).apply {
            this.text = text
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(Color.WHITE)
            styleXButton(this, primary = false)
            setOnClickListener { action(this) }
        }

        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(15, 23, 36))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = "FULL KEYBOARD"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        topRow.addView(control("DONE") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(90), dp(42)))
        root.addView(topRow)

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val volume = control("VOLUME ${(pianoVolume * 100).roundToInt()}%") { button ->
            pianoVolume = if (pianoVolume >= 0.99f) 0.2f else (pianoVolume + 0.2f).coerceAtMost(1f)
            updatePianoVolumeControls()
            button.text = "VOLUME ${(pianoVolume * 100).roundToInt()}%"
        }
        val octave = control(when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }) { button ->
            pianoTranspose = when (pianoTranspose) { 0 -> 12; 12 -> -12; else -> 0 }
            pianoOctaveButton?.text = when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }
            button.text = when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }
        }
        val reverb = control(if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF") { button ->
            pianoReverbEnabled = !pianoReverbEnabled
            pianoReverbButton?.apply {
                text = if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF"
                styleXButton(this, primary = pianoReverbEnabled)
            }
            button.text = if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF"
        }
        controlRow.addView(volume, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        controlRow.addView(octave, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        controlRow.addView(reverb, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(controlRow, LinearLayout.LayoutParams(-1, dp(48)))

        val keys = PianoKeyboardView(this) { midiNote, pressed ->
            if (pressed) {
                stopTone()
                playPianoSample(midiNote)
            } else {
                releasePianoSample(midiNote)
            }
        }
        root.addView(keys, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            keys.releaseAllPointers()
            if (fullScaleKeysDialog === dialog) fullScaleKeysDialog = null
        }
        dialog.show()
        fullScaleKeysDialog = dialog
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
            addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
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

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    private fun startAnalysis() {
        if (running) return

        // Clear old spectrum data while preserving the selected frequency range and axis mode.
        spectrumView.resetView()

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showStatus("Microphone permission is required.")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            showStatus("Audio recording is not supported.")
            return
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, fftSize * 2)
            )
        } catch (e: SecurityException) {
            showStatus("Microphone permission denied.")
            return
        } catch (e: IllegalArgumentException) {
            showStatus("Invalid audio configuration.")
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            showStatus("Microphone initialization failed.")
            return
        }

        try {
            audioRecord.startRecording()
        } catch (e: SecurityException) {
            audioRecord.release()
            showStatus("Microphone permission denied.")
            return
        } catch (e: IllegalStateException) {
            audioRecord.release()
            showStatus("Unable to start recording.")
            return
        }

        recorder = audioRecord
        splSystemCalibrationOffsetDb = readSystemSplCalibrationOffset(audioRecord)
        splCalibrationOffsetDb = splManualCalibrationOffsetDb ?: splSystemCalibrationOffsetDb
        splRecentReadings.clear()
        splHasCurrentSample = false
        running = true
        updateSplReadout()

        (startButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.STOP
        updateStartButtonAppearance()
        statusText.text = ""
        statusText.visibility = View.GONE

        audioThread = Thread {
            processAudio(audioRecord)
        }.also {
            it.start()
        }
    }

    private fun processAudio(audioRecord: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Keep a rolling buffer large enough for the maximum supported FFT size.
        val ringBuffer = ShortArray(16384)
        val readBuffer = ShortArray(2048)
        var writeIndex = 0
        var samplesSeen = 0L
        var samplesSinceFft = 0
        var activeFftSize = 8192
        var activeHopSize = activeFftSize / 4
        var samples = ShortArray(activeFftSize)
        var real = DoubleArray(activeFftSize)
        var imaginary = DoubleArray(activeFftSize)
        var magnitudes = DoubleArray(activeFftSize / 2)

        while (running) {
            // Frames started before RESET must not be allowed to restore old graph state.
            val frameResetGeneration = spectrumView.getResetGeneration()
            val count = try {
                audioRecord.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) {
                -1
            }
            if (count <= 0) {
                if (!running) break
                continue
            }

            // Sound ID consumes the same microphone samples as the spectrum graph.
            // It never opens a second AudioRecord session.
            if (soundIdActive) soundIdEngine.offerAudio(readBuffer, count)

            for (i in 0 until count) {
                ringBuffer[writeIndex] = readBuffer[i]
                writeIndex = (writeIndex + 1) % ringBuffer.size
            }
            samplesSeen += count
            samplesSinceFft += count

            // Increase FFT resolution automatically when the user zooms into a narrower band.
            val requestedFftSize = spectrumView.getRecommendedFftSize()
            if (requestedFftSize != activeFftSize) {
                activeFftSize = requestedFftSize
                activeHopSize = activeFftSize / 4
                samples = ShortArray(activeFftSize)
                real = DoubleArray(activeFftSize)
                imaginary = DoubleArray(activeFftSize)
                magnitudes = DoubleArray(activeFftSize / 2)
                samplesSinceFft = 0
            }

            if (samplesSeen < activeFftSize || samplesSinceFft < activeHopSize) continue
            samplesSinceFft = 0

            // Copy the newest FFT window from the circular buffer in chronological order.
            var readIndex = (writeIndex - activeFftSize + ringBuffer.size) % ringBuffer.size
            for (i in 0 until activeFftSize) {
                samples[i] = ringBuffer[readIndex]
                readIndex = (readIndex + 1) % ringBuffer.size
            }

            var sumSquares = 0.0
            for (i in 0 until activeFftSize) {
                val value = samples[i].toDouble() / 32768.0
                sumSquares += value * value
                val window = 0.5 * (1.0 - cos(2.0 * PI * i / (activeFftSize - 1)))
                real[i] = value * window
                imaginary[i] = 0.0
            }

            fft(real, imaginary)

            var peakBin = 1
            var peakMagnitude = 0.0
            for (i in magnitudes.indices) {
                val magnitude = hypot(real[i], imaginary[i])
                magnitudes[i] = magnitude
                val binFrequency = i * sampleRate.toDouble() / activeFftSize
                // Keep peak detection within the intended 20 kHz analysis range.
                if (i > 0 && binFrequency <= 20000.0 && magnitude > peakMagnitude) {
                    peakMagnitude = magnitude
                    peakBin = i
                }
            }

            // Parabolic interpolation improves the displayed peak frequency estimate.
            var peakOffset = 0.0
            if (peakBin > 1 && peakBin < magnitudes.size - 1) {
                val leftMagnitude = ln(max(magnitudes[peakBin - 1], 1e-12))
                val centerMagnitude = ln(max(magnitudes[peakBin], 1e-12))
                val rightMagnitude = ln(max(magnitudes[peakBin + 1], 1e-12))
                val denominator = leftMagnitude - 2.0 * centerMagnitude + rightMagnitude
                if (denominator != 0.0) {
                    peakOffset = (0.5 * (leftMagnitude - rightMagnitude) / denominator).coerceIn(-0.5, 0.5)
                }
            }

            val peakFrequency = (peakBin + peakOffset) * sampleRate / activeFftSize
            val rms = kotlin.math.sqrt(sumSquares / activeFftSize)
            val levelDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
            val spectrumSnapshot = magnitudes.copyOf()
            val currentFftSize = activeFftSize
            val peakDb = if (peakMagnitude > 0.0) {
                20.0 * log10(peakMagnitude * 4.0 / currentFftSize)
            } else {
                -120.0
            }

            runOnUiThread {
                if (running && frameResetGeneration == spectrumView.getResetGeneration()) {
                    spectrumView.setSpectrum(spectrumSnapshot, sampleRate, currentFftSize, peakFrequency)
                    latestAnalyzedPeakFrequency = peakFrequency
                    latestAnalyzedPeakDb = peakDb
                    latestAnalyzedRmsDb = levelDb
                    splHasCurrentSample = true
                    headerPeakFrequencyText?.text = if (peakFrequency >= 1000.0) {
                        String.format(java.util.Locale.US, "%.2f kHz", peakFrequency / 1000.0)
                    } else {
                        String.format(java.util.Locale.US, "%.1f Hz", peakFrequency)
                    }
                    headerPeakDbText?.text = String.format(java.util.Locale.US, "%.1f dBFS", peakDb)
                    updateSplReadout()
                    updateTonePeakFollow(peakFrequency, peakDb, levelDb)
                    peakText.text = String.format(
                        java.util.Locale.US,
                        "Peak: %.1f Hz    Level: %.1f dBFS    FFT: %d",
                        peakFrequency,
                        levelDb,
                        currentFftSize
                    )
                }
            }
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0

        // Bit-reversal permutation.
        for (i in 1 until n) {
            var bit = n shr 1

            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }

            j = j xor bit

            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal

                val tempImaginary = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = tempImaginary
            }
        }

        var length = 2

        while (length <= n) {
            val angle = -2.0 * PI / length

            val wLengthReal = cos(angle)
            val wLengthImaginary = sin(angle)

            val half = length / 2
            var start = 0

            while (start < n) {
                var wReal = 1.0
                var wImaginary = 0.0

                for (k in 0 until half) {
                    val even = start + k
                    val odd = even + half

                    val oddReal =
                        real[odd] * wReal -
                                imaginary[odd] * wImaginary

                    val oddImaginary =
                        real[odd] * wImaginary +
                                imaginary[odd] * wReal

                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary

                    real[even] += oddReal
                    imaginary[even] += oddImaginary

                    val nextWReal =
                        wReal * wLengthReal -
                                wImaginary * wLengthImaginary

                    wImaginary =
                        wReal * wLengthImaginary +
                                wImaginary * wLengthReal

                    wReal = nextWReal
                }

                start += length
            }

            length = length shl 1
        }
    }

    private fun stopAnalysis() {
        running = false
        splHasCurrentSample = false
        splRecentReadings.clear()
        updateSplReadout()
        if (soundIdActive) {
            soundIdActive = false
            if (soundIdPanel != null) {
                soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
                updateSoundIdToggleStyle()
            }
            soundIdEngine.setListening(false)
        }

        (startButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.PLAY
        updateStartButtonAppearance()
        statusText.text = ""
        statusText.visibility = View.GONE

        val audioRecord = recorder
        recorder = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        audioRecord?.release()

        spectrumView.resetView()
    }

    override fun onDestroy() {
        fullScaleKeysDialog?.dismiss()
        splDetailDialog?.dismiss()
        soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
        stopPersonalSoundPreview()
        stopTone()
        stopAnalysis()
        if (soundIdPanel != null) soundIdEngine.close()
        stopPianoAudioEngine()
        pianoSamples.clear()
        pianoHeldNotes.clear()
        super.onDestroy()
    }

    companion object {
        private const val PIANO_LOAD_TAG = "SpectrumPianoLoad"
    }
}

private data class SoundSessionEntry(
    val label: String,
    val seconds: Double,
    val percent: Double
)

private data class SoundCandidate(val label: String, val scorePercent: Int)

private data class SoundSessionSummary(
    val totalSeconds: Double,
    val entries: List<SoundSessionEntry>,
    val timeline: List<String> = emptyList()
)

private val SOUND_SESSION_COLORS = intArrayOf(
    Color.rgb(75, 196, 255), Color.rgb(80, 220, 176), Color.rgb(255, 190, 86),
    Color.rgb(183, 139, 255), Color.rgb(255, 124, 145)
)
private val SOUND_SESSION_OTHER_COLOR = Color.rgb(136, 158, 181)

private data class SoundIdResult(
    val status: String,
    val bestSound: String,
    val modelScorePercent: Int = 0,
    val alternatives: String = "",
    val sessionSummary: SoundSessionSummary? = null,
    val isPersonalMatch: Boolean = false
)

private const val PERSONAL_SOUND_FEATURE_COUNT = 585
private const val PERSONAL_SOUND_SAMPLE_SECONDS = 10
private const val PERSONAL_SOUND_DEFAULT_PROFILE_WINDOWS = 19
private const val PERSONAL_SOUND_MATCH_THRESHOLD = 0.72f
private const val PERSONAL_SOUND_NOTE_THRESHOLD = 0.55f

private data class PersonalSoundProfile(val id: String, val name: String, val embedding: FloatArray)

/**
 * YAMNet bridge for the analyzer's existing 48 kHz mono microphone stream.
 * Capture is supplied by MainActivity; this class only resamples and classifies
 * bounded, overlapping windows on one background worker.
 */
private class SoundIdEngine(
    private val context: Context,
    private val onResult: (SoundIdResult) -> Unit
) {
    companion object {
        private const val MODEL_RATE = 16_000
        private const val MODEL_SAMPLES = 15_600 // 0.975 s at 16 kHz
        private const val WINDOW_SAMPLES = MODEL_SAMPLES * 3
        private const val HOP_SAMPLES = 23_040 // 0.48 s at 48 kHz
        private const val CLASS_COUNT = 521
        private const val PERSONAL_SPECTRAL_BANDS = 64
        private const val PERSONAL_FEATURE_COUNT = PERSONAL_SOUND_FEATURE_COUNT
        private const val QUIET_DBFS = -60.0
        private const val MIN_MODEL_SCORE = 0.14f
        private const val MIN_CHILD_SCORE = 0.12f
        private const val MIN_CHILD_PARENT_RATIO = 0.30f
        private const val CLASSIFIED_SECONDS_PER_WINDOW = HOP_SAMPLES / 48_000.0
    }

    @Volatile private var listening = false
    @Volatile private var ready = false
    @Volatile private var modelLoadError: String? = null
    private val loadStarted = AtomicBoolean(false)
    private val inferencePending = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SoundId-YAMNet").apply { priority = Thread.NORM_PRIORITY }
    }

    // offerAudio() is called only from the single AudioRecord consumer thread.
    private val inputRing = ShortArray(WINDOW_SAMPLES)
    private var writeIndex = 0
    private var samplesSeen = 0L
    private var samplesSinceWindow = 0
    private var wasListening = false

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var childClassIndices: Array<IntArray> = emptyArray()
    private val smoothedScores = FloatArray(CLASS_COUNT)
    private var hasSmoothedScores = false
    private val personalSoundLock = Any()
    private val personalSounds = loadPersonalSounds()
    @Volatile private var personalCaptureName: String? = null
    private var personalCaptureSum = FloatArray(PERSONAL_FEATURE_COUNT)
    private var personalCaptureWindows = 0
    private var personalCaptureTargetWindows = PERSONAL_SOUND_DEFAULT_PROFILE_WINDOWS
    private var personalCaptureWarmup = true
    @Volatile private var personalSoundCapturedListener: ((FloatArray, ShortArray) -> Unit)? = null
    private var personalCapturePcm = ShortArray(0)
    private var personalCapturePcmCount = 0
    private var personalCapturePcmActive = false
    private val secondsByLabel = linkedMapOf<String, Double>()
    private val timelineLabels = mutableListOf<String>()
    private var analyzedSeconds = 0.0
    private val filter = createDecimatorFilter()

    fun setListening(value: Boolean) {
        listening = value
        if (!value) {
            worker.execute {
                onResult(SoundIdResult("SESSION COMPLETE", "", sessionSummary = createSessionSummary()))
            }
            return
        }
        onResult(SoundIdResult("LOADING MODEL", "Waiting for the first sound…"))
        worker.execute {
            secondsByLabel.clear()
            timelineLabels.clear()
            analyzedSeconds = 0.0
            smoothedScores.fill(0f)
            hasSmoothedScores = false
            if (loadStarted.compareAndSet(false, true)) loadModel()
            else if (ready) onResult(SoundIdResult("LISTENING", "Listening for sounds…"))
        }
    }

    fun getPersonalSounds(): List<PersonalSoundProfile> = synchronized(personalSoundLock) {
        personalSounds.map { PersonalSoundProfile(it.id, it.name, it.embedding.copyOf()) }
    }

    fun suggestPersonalSoundName(): String = synchronized(personalSoundLock) {
        var index = 1
        while (personalSounds.any { it.name.equals("My Sound $index", ignoreCase = true) }) index++
        "My Sound $index"
    }

    fun renamePersonalSound(id: String, name: String): Boolean = synchronized(personalSoundLock) {
        val cleanName = name.trim()
        val index = personalSounds.indexOfFirst { it.id == id }
        if (cleanName.isEmpty() || index < 0) return@synchronized false
        personalSounds[index] = personalSounds[index].copy(name = cleanName)
        persistPersonalSoundsLocked()
        true
    }

    fun removePersonalSound(id: String) {
        synchronized(personalSoundLock) {
            personalSounds.removeAll { profile ->
                if (profile.id == id) {
                    File(context.filesDir, "personal_sound_samples/${profile.id}.pcm").delete()
                    true
                } else false
            }
            persistPersonalSoundsLocked()
        }
    }

    fun renamePersonalSounds(existingName: String, newName: String): Set<String> = synchronized(personalSoundLock) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return@synchronized emptySet()
        val matching = personalSounds.filter { it.name.equals(existingName.trim(), ignoreCase = true) }
        if (matching.isEmpty()) return@synchronized emptySet()
        val ids = matching.mapTo(linkedSetOf<String>()) { it.id }
        for (index in personalSounds.indices) {
            if (personalSounds[index].id in ids) personalSounds[index] = personalSounds[index].copy(name = cleanName)
        }
        persistPersonalSoundsLocked()
        ids
    }

    fun removePersonalSounds(name: String): Set<String> = synchronized(personalSoundLock) {
        val matchingIds = personalSounds.filter { it.name.equals(name.trim(), ignoreCase = true) }
            .mapTo(linkedSetOf<String>()) { it.id }
        if (matchingIds.isEmpty()) return@synchronized emptySet()
        personalSounds.removeAll { profile ->
            if (profile.id in matchingIds) {
                File(context.filesDir, "personal_sound_samples/${profile.id}.pcm").delete()
                true
            } else false
        }
        persistPersonalSoundsLocked()
        matchingIds
    }

    fun beginPersonalSoundCapture(durationSeconds: Int = PERSONAL_SOUND_SAMPLE_SECONDS) {
        val sampleDurationSeconds = durationSeconds.coerceIn(5, 10)
        synchronized(personalSoundLock) {
            personalCaptureName = "pending"
            personalCaptureSum = FloatArray(PERSONAL_FEATURE_COUNT)
            personalCaptureWindows = 0
            val capturedSampleCount = sampleDurationSeconds * 48_000
            personalCaptureTargetWindows = 1 + maxOf(0, (capturedSampleCount - WINDOW_SAMPLES) / HOP_SAMPLES)
            personalCaptureWarmup = true
            personalCapturePcm = ShortArray(MODEL_RATE * 3 * sampleDurationSeconds)
            personalCapturePcmCount = 0
            personalCapturePcmActive = true
        }
    }

    fun cancelPersonalSoundCapture() {
        synchronized(personalSoundLock) {
            personalCaptureName = null
            personalCaptureSum = FloatArray(PERSONAL_FEATURE_COUNT)
            personalCaptureWindows = 0
            personalCaptureWarmup = true
            personalCapturePcm = ShortArray(0)
            personalCapturePcmCount = 0
            personalCapturePcmActive = false
        }
    }

    fun setPersonalSoundCapturedListener(listener: ((FloatArray, ShortArray) -> Unit)?) {
        personalSoundCapturedListener = listener
    }

    fun getPersonalSoundAudio(id: String): ShortArray? {
        val file = File(context.filesDir, "personal_sound_samples/$id.pcm")
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 2 || bytes.size % 2 != 0) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buffer.short }
    }

    fun savePersonalSound(name: String, embedding: FloatArray, audio: ShortArray) {
        val normalized = normalizeEmbedding(embedding)
        synchronized(personalSoundLock) {
            val id = java.util.UUID.randomUUID().toString()
            val directory = File(context.filesDir, "personal_sound_samples")
            directory.mkdirs()
            val pcm = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            audio.forEach { sample -> pcm.putShort(sample) }
            File(directory, "$id.pcm").writeBytes(pcm.array())
            personalSounds.add(PersonalSoundProfile(id, name, normalized))
            persistPersonalSoundsLocked()
        }
    }

    fun createPersonalSoundFromAudio(
        name: String,
        source48k: ShortArray,
        onComplete: (String?) -> Unit
    ) {
        worker.execute {
            val failure = try {
                val cleanName = name.trim().take(80)
                require(cleanName.isNotEmpty()) { "The sample needs a name." }
                require(source48k.size in WINDOW_SAMPLES..(48_000 * 30)) {
                    "WAV must contain between 1 and 30 seconds of audio."
                }
                if (!ready && loadStarted.compareAndSet(false, true)) loadModel(announceState = false)
                check(ready) { modelLoadError ?: "The sound model could not be loaded." }
                val model = interpreter ?: error("The sound model is not ready.")
                val embeddingSum = DoubleArray(PERSONAL_FEATURE_COUNT)
                var usableWindows = 0
                var start = 0
                while (start + WINDOW_SAMPLES <= source48k.size) {
                    val frame = source48k.copyOfRange(start, start + WINDOW_SAMPLES)
                    val audio16k = resample48kTo16k(frame)
                    val rms = sqrt(audio16k.sumOf { it.toDouble() * it.toDouble() } / audio16k.size)
                    val rmsDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
                    if (rmsDb >= QUIET_DBFS) {
                        val output = Array(1) { FloatArray(CLASS_COUNT) }
                        model.run(arrayOf(audio16k), output)
                        val embedding = createPersonalEmbedding(output[0], audio16k)
                        for (index in embedding.indices) embeddingSum[index] += embedding[index]
                        usableWindows++
                    }
                    start += HOP_SAMPLES
                }
                require(usableWindows > 0) { "No clear sound was found in this WAV." }
                val averaged = FloatArray(PERSONAL_FEATURE_COUNT) { index ->
                    (embeddingSum[index] / usableWindows).toFloat()
                }
                savePersonalSound(cleanName, averaged, source48k)
                null
            } catch (error: Exception) {
                error.message ?: "Could not create a profile from this WAV."
            }
            onComplete(failure)
        }
    }

    fun offerAudio(samples: ShortArray, count: Int) {
        if (!listening) {
            if (wasListening) {
                writeIndex = 0
                samplesSeen = 0L
                samplesSinceWindow = 0
                wasListening = false
            }
            return
        }
        if (!wasListening) {
            writeIndex = 0
            samplesSeen = 0L
            samplesSinceWindow = 0
            wasListening = true
        }
        synchronized(personalSoundLock) {
            if (personalCapturePcmActive && personalCapturePcmCount < personalCapturePcm.size) {
                val copyCount = minOf(count, personalCapturePcm.size - personalCapturePcmCount)
                samples.copyInto(personalCapturePcm, personalCapturePcmCount, 0, copyCount)
                personalCapturePcmCount += copyCount
                if (personalCapturePcmCount >= personalCapturePcm.size) personalCapturePcmActive = false
            }
        }
        for (i in 0 until count) {
            inputRing[writeIndex] = samples[i]
            writeIndex = (writeIndex + 1) % inputRing.size
        }
        samplesSeen += count
        samplesSinceWindow += count
        if (samplesSeen < WINDOW_SAMPLES || samplesSinceWindow < HOP_SAMPLES) return
        samplesSinceWindow = 0
        if (!ready || !inferencePending.compareAndSet(false, true)) return

        val frame = ShortArray(WINDOW_SAMPLES)
        var index = writeIndex
        for (i in frame.indices) {
            frame[i] = inputRing[index]
            index = (index + 1) % inputRing.size
        }
        worker.execute {
            try {
                classify(frame)
            } catch (error: Exception) {
                onResult(SoundIdResult(
                    "ANALYSIS ERROR",
                    error.message ?: "Could not analyze this audio."
                ))
            } finally {
                inferencePending.set(false)
            }
        }
    }

    private fun loadModel(announceState: Boolean = true) {
        try {
            val modelBytes = context.assets.open("yamnet.tflite").use { it.readBytes() }
            require(modelBytes.isNotEmpty()) { "yamnet.tflite is empty" }
            val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            model.put(modelBytes)
            model.rewind()
            val classRows = context.assets.open("yamnet_class_map.csv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("index,")) null
                    else {
                        val fields = trimmed.split(',', limit = 3)
                        if (fields.size < 3) null else {
                            val classId = fields[1].trim()
                            val name = fields[2].trim().removeSurrounding("\"")
                            classId to name
                        }
                    }
                }.toList()
            }
            val classNames = classRows.map { it.second }
            require(classNames.size == CLASS_COUNT) {
                "Expected 521 YAMNet labels, found ${classNames.size}"
            }
            val classIndexById = classRows.mapIndexed { index, row -> row.first to index }.toMap()
            val children = Array(CLASS_COUNT) { mutableListOf<Int>() }
            val ontology = context.assets.open("audioset_ontology.json").bufferedReader().use { reader ->
                JSONArray(reader.readText())
            }
            for (nodeIndex in 0 until ontology.length()) {
                val node = ontology.getJSONObject(nodeIndex)
                val parentIndex = classIndexById[node.optString("id")] ?: continue
                val childIds = node.optJSONArray("child_ids") ?: continue
                for (childIndex in 0 until childIds.length()) {
                    val modelChildIndex = classIndexById[childIds.optString(childIndex)] ?: continue
                    children[parentIndex].add(modelChildIndex)
                }
            }
            childClassIndices = Array(CLASS_COUNT) { children[it].distinct().toIntArray() }
            val options = Interpreter.Options().setNumThreads(2)
            interpreter = Interpreter(model, options)
            labels = classNames
            ready = true
            modelLoadError = null
            if (announceState) onResult(SoundIdResult("LISTENING", "Listening for sounds…"))
        } catch (error: Exception) {
            ready = false
            modelLoadError = error.message ?: "Check that the sound model is included in the app."
            if (announceState) onResult(SoundIdResult("MODEL COULD NOT LOAD", modelLoadError!!))
        }
    }

    private fun classify(source48k: ShortArray) {
        val audio16k = resample48kTo16k(source48k)
        val rms = sqrt(audio16k.sumOf { it.toDouble() * it.toDouble() } / audio16k.size)
        val rmsDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
        if (rmsDb < QUIET_DBFS) {
            recordWinner("Unclear / quiet")
            onResult(SoundIdResult("LISTENING", "No clear sound", 0))
            return
        }

        val output = Array(1) { FloatArray(CLASS_COUNT) }
        interpreter?.run(arrayOf(audio16k), output)
            ?: error("The YAMNet interpreter is not ready")
        val scores = output[0]
        val needsPersonalEmbedding = synchronized(personalSoundLock) {
            personalSounds.isNotEmpty() || personalCaptureName != null
        }
        val personalEmbedding = if (needsPersonalEmbedding) createPersonalEmbedding(scores, audio16k) else null
        val capturedEmbedding = synchronized(personalSoundLock) {
            if (personalCaptureName == null || personalEmbedding == null) null else if (personalCaptureWarmup) {
                personalCaptureWarmup = false
                null
            } else {
                for (index in personalEmbedding.indices) personalCaptureSum[index] += personalEmbedding[index]
                personalCaptureWindows++
                if (personalCaptureWindows >= personalCaptureTargetWindows) {
                    val averaged = FloatArray(PERSONAL_FEATURE_COUNT) { personalCaptureSum[it] / personalCaptureWindows }
                    personalCaptureName = null
                    personalCaptureSum = FloatArray(PERSONAL_FEATURE_COUNT)
                    personalCaptureWindows = 0
                    averaged
                } else null
            }
        }
        capturedEmbedding?.let { embedding ->
            val pcm = synchronized(personalSoundLock) {
                val captured = personalCapturePcm.copyOf(personalCapturePcmCount)
                personalCapturePcm = ShortArray(0)
                personalCapturePcmCount = 0
                personalCapturePcmActive = false
                captured
            }
            personalSoundCapturedListener?.invoke(embedding, pcm)
        }
        if (!hasSmoothedScores) {
            scores.copyInto(smoothedScores)
            hasSmoothedScores = true
        } else {
            for (i in scores.indices) smoothedScores[i] = smoothedScores[i] * 0.55f + scores[i] * 0.45f
        }
        val ranked = smoothedScores.indices
            .asSequence()
            .filter { smoothedScores[it] >= 0.05f }
            .map { mostSpecificSupportedClass(it, smoothedScores) }
            .distinct()
            .sortedByDescending { smoothedScores[it] }
            .take(3)
            .toList()
        val personalMatch = personalEmbedding?.let(::findPersonalSoundMatch)
        val customCandidate = personalMatch?.takeIf { it.second >= PERSONAL_SOUND_MATCH_THRESHOLD }?.let { (profile, similarity) ->
            SoundCandidate(profile.name, (similarity * 100f).roundToInt())
        }
        val best = ranked.firstOrNull() ?: -1
        if (best < 0 || smoothedScores[best] < MIN_MODEL_SCORE) {
            if (customCandidate != null) {
                recordWinner(customCandidate.label)
                onResult(SoundIdResult(
                    "LISTENING · SAVED SOUND MATCH",
                    customCandidate.label,
                    customCandidate.scorePercent,
                    "Similarity to your saved sample",
                    isPersonalMatch = true
                ))
            } else {
                recordWinner("Unclear / mixed")
                onResult(SoundIdResult("LISTENING", "No clear sound", 0))
            }
            return
        }
        val bestScore = smoothedScores[best].coerceIn(0f, 1f)
        val candidates = ranked.mapNotNull { index ->
            val score = smoothedScores[index].coerceIn(0f, 1f)
            if (score < 0.05f) null else SoundCandidate(
                labels.getOrElse(index) { "Unknown sound" },
                (score * 100f).roundToInt()
            )
        }
        if (candidates.isEmpty()) {
            if (customCandidate != null) {
                recordWinner(customCandidate.label)
                onResult(SoundIdResult("LISTENING · SAVED SOUND MATCH", customCandidate.label,
                    customCandidate.scorePercent, "Similarity to your saved sample", isPersonalMatch = true))
            } else {
                recordWinner("Unclear / mixed")
                onResult(SoundIdResult("LISTENING", "No clear sound", 0))
            }
            return
        }
        val allCandidates = (listOfNotNull(customCandidate) + candidates).distinctBy { it.label }.take(3)
        recordWindow(allCandidates)
        val label = allCandidates.first().label
        val alternatives = allCandidates.drop(1).takeIf { it.isNotEmpty() }?.let { others ->
            "Also possible: " + others.joinToString("   ·   ") { "${it.label} ${it.scorePercent}%" }
        }.orEmpty()
        val displayedScore = if (customCandidate != null) customCandidate.scorePercent else (bestScore * 100f).roundToInt()
        val status = when {
            customCandidate != null -> "LISTENING · SAVED SOUND MATCH"
            displayedScore >= 35 -> "LISTENING · CLEAR MATCH"
            else -> "LISTENING · LOW MATCH"
        }
        val personalNote = personalMatch?.takeIf { it.second >= PERSONAL_SOUND_NOTE_THRESHOLD }?.let { (profile, similarity) ->
            "Nearest saved sample: ${profile.name} · ${(similarity * 100f).roundToInt()}% similarity"
        }.orEmpty()
        val fullAlternatives = listOf(personalNote, alternatives).filter { it.isNotBlank() }.joinToString("\n")
        onResult(SoundIdResult(status, label, displayedScore, fullAlternatives, isPersonalMatch = customCandidate != null))
    }

    private fun createPersonalEmbedding(scores: FloatArray, audio: FloatArray): FloatArray {
        val classifierVector = normalizeEmbedding(scores)
        val spectralBands = DoubleArray(PERSONAL_SPECTRAL_BANDS)
        val bandCounts = IntArray(PERSONAL_SPECTRAL_BANDS)
        val fftSize = 2048
        val minHz = 45.0
        val maxHz = MODEL_RATE / 2.0
        val logSpan = ln(maxHz / minHz)
        var frameCount = 0
        var frameStart = 0
        while (frameStart + fftSize <= audio.size) {
            val real = DoubleArray(fftSize)
            val imaginary = DoubleArray(fftSize)
            for (sampleIndex in 0 until fftSize) {
                val hann = 0.5 * (1.0 - cos(2.0 * PI * sampleIndex / (fftSize - 1)))
                real[sampleIndex] = audio[frameStart + sampleIndex] * hann
            }
            fft(real, imaginary)
            for (bin in 1 until fftSize / 2) {
                val frequency = bin * MODEL_RATE.toDouble() / fftSize
                if (frequency < minHz) continue
                val band = (ln(frequency / minHz) / logSpan * PERSONAL_SPECTRAL_BANDS)
                    .toInt().coerceIn(0, PERSONAL_SPECTRAL_BANDS - 1)
                spectralBands[band] += hypot(real[bin], imaginary[bin])
                bandCounts[band]++
            }
            frameCount++
            frameStart += fftSize
        }
        val spectralVector = FloatArray(PERSONAL_SPECTRAL_BANDS) { band ->
            if (bandCounts[band] == 0 || frameCount == 0) 0f
            else (spectralBands[band] / bandCounts[band] / frameCount).toFloat()
        }
        val normalizedSpectrum = normalizeEmbedding(spectralVector)
        val modelWeight = 0.70710677f
        val spectrumWeight = 0.70710677f
        return FloatArray(PERSONAL_FEATURE_COUNT).also { combined ->
            for (index in classifierVector.indices) combined[index] = classifierVector[index] * modelWeight
            for (index in normalizedSpectrum.indices) {
                combined[CLASS_COUNT + index] = normalizedSpectrum[index] * spectrumWeight
            }
        }
    }

    private fun findPersonalSoundMatch(embedding: FloatArray): Pair<PersonalSoundProfile, Float>? {
        val normalized = normalizeEmbedding(embedding)
        val profiles = getPersonalSounds()
        return profiles.mapNotNull { profile ->
            if (profile.embedding.size != normalized.size) return@mapNotNull null
            val similarity = profile.embedding.indices.sumOf { index ->
                profile.embedding[index].toDouble() * normalized[index]
            }.toFloat().coerceIn(-1f, 1f)
            profile to similarity
        }.maxByOrNull { it.second }
    }

    private fun normalizeEmbedding(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        return if (norm <= 1e-8f) values.copyOf() else FloatArray(values.size) { values[it] / norm }
    }

    private fun loadPersonalSounds(): MutableList<PersonalSoundProfile> {
        val profiles = mutableListOf<PersonalSoundProfile>()
        val stored = context.getSharedPreferences("personal_sound_ids", Context.MODE_PRIVATE)
            .getString("profiles", null) ?: return profiles
        return try {
            val array = JSONArray(stored)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val vector = item.optJSONArray("embedding") ?: continue
                if (name.isBlank() || vector.length() != PERSONAL_FEATURE_COUNT) continue
                val id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                profiles.add(PersonalSoundProfile(id, name, FloatArray(PERSONAL_FEATURE_COUNT) { vector.optDouble(it).toFloat() }))
            }
            profiles
        } catch (_: Exception) {
            profiles
        }
    }

    private fun persistPersonalSoundsLocked() {
        val array = JSONArray()
        personalSounds.forEach { profile ->
            val vector = JSONArray()
            profile.embedding.forEach { value -> vector.put(value.toDouble()) }
            array.put(JSONObject().put("id", profile.id).put("name", profile.name).put("embedding", vector))
        }
        context.getSharedPreferences("personal_sound_ids", Context.MODE_PRIVATE)
            .edit().putString("profiles", array.toString()).apply()
    }

    /**
     * YAMNet's AudioSet outputs include broad and specific classes independently.
     * Prefer a supported child class (e.g. Dog over Animal) only when that child
     * has a meaningful score of its own; otherwise retain the broader prediction.
     */
    private fun mostSpecificSupportedClass(startIndex: Int, scores: FloatArray): Int {
        var currentIndex = startIndex
        val visited = HashSet<Int>()
        while (visited.add(currentIndex)) {
            val parentScore = scores.getOrElse(currentIndex) { 0f }
            val supportedChild = childClassIndices.getOrNull(currentIndex)
                ?.asSequence()
                ?.filter { child ->
                    val childScore = scores.getOrElse(child) { 0f }
                    childScore >= MIN_CHILD_SCORE && childScore >= parentScore * MIN_CHILD_PARENT_RATIO
                }
                ?.maxByOrNull { scores.getOrElse(it) { 0f } }
                ?: return currentIndex
            currentIndex = supportedChild
        }
        return currentIndex
    }

    private fun recordWinner(label: String) = recordWindow(listOf(SoundCandidate(label, 100)))

    private fun recordWindow(candidates: List<SoundCandidate>) {
        val seconds = CLASSIFIED_SECONDS_PER_WINDOW
        analyzedSeconds += seconds
        val uniqueCandidates = candidates.distinctBy { it.label }
        if (uniqueCandidates.isEmpty()) return
        uniqueCandidates.maxByOrNull { it.scorePercent }?.label?.let(timelineLabels::add)
        val scoreTotal = uniqueCandidates.sumOf { it.scorePercent.coerceAtLeast(0) }.takeIf { it > 0 }
        uniqueCandidates.forEach { candidate ->
            val share = if (scoreTotal == null) 1.0 / uniqueCandidates.size else candidate.scorePercent.coerceAtLeast(0).toDouble() / scoreTotal
            secondsByLabel[candidate.label] = (secondsByLabel[candidate.label] ?: 0.0) + seconds * share
        }
    }

    private fun createSessionSummary(): SoundSessionSummary {
        if (analyzedSeconds <= 0.0) return SoundSessionSummary(0.0, emptyList(), emptyList())
        val ranked = secondsByLabel.entries.sortedByDescending { it.value }
        val entries = ranked.take(5).map { (label, seconds) ->
            SoundSessionEntry(label, seconds, seconds / analyzedSeconds * 100.0)
        }
        return SoundSessionSummary(analyzedSeconds, entries, timelineLabels.toList())
    }

    /** Windowed-sinc low-pass followed by 3:1 decimation; this is not rate reinterpretation. */
    private fun resample48kTo16k(source: ShortArray): FloatArray {
        val output = FloatArray(MODEL_SAMPLES)
        val centerOffset = filter.size / 2
        for (outIndex in output.indices) {
            val center = outIndex * 3
            var sum = 0.0
            for (tap in filter.indices) {
                val sourceIndex = center + tap - centerOffset
                if (sourceIndex in source.indices) sum += source[sourceIndex].toDouble() / 32768.0 * filter[tap]
            }
            output[outIndex] = sum.toFloat().coerceIn(-1f, 1f)
        }
        return output
    }

    private fun measureAcoustics(audio: FloatArray, rmsDb: Double): String {
        val n = 4096
        val real = DoubleArray(n)
        val imaginary = DoubleArray(n)
        val start = audio.size - n
        for (i in 0 until n) {
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            real[i] = audio[start + i] * window
        }
        fft(real, imaginary)
        val magnitudes = DoubleArray(n / 2)
        var total = 0.0
        var weighted = 0.0
        var logSum = 0.0
        var arithmeticSum = 0.0
        var peakBin = 1
        for (bin in 1 until magnitudes.size) {
            val magnitude = hypot(real[bin], imaginary[bin])
            magnitudes[bin] = magnitude
            if (magnitude > magnitudes[peakBin]) peakBin = bin
            val power = magnitude * magnitude
            total += power
            weighted += (bin * MODEL_RATE.toDouble() / n) * power
            val safe = max(magnitude, 1e-12)
            logSum += ln(safe)
            arithmeticSum += safe
        }
        val centroid = if (total > 0.0) weighted / total else 0.0
        val flatness = if (arithmeticSum > 0.0) exp(logSum / magnitudes.size) / (arithmeticSum / magnitudes.size) else 0.0
        val dominantHz = peakBin * MODEL_RATE.toDouble() / n
        var harmonicEnergy = 0.0
        for (harmonic in 1..5) {
            val bin = peakBin * harmonic
            if (bin >= magnitudes.lastIndex) break
            for (near in max(1, bin - 1)..min(magnitudes.lastIndex, bin + 1)) {
                harmonicEnergy += magnitudes[near] * magnitudes[near]
            }
        }
        val harmonicRatio = if (total > 0.0) (harmonicEnergy / total).coerceIn(0.0, 1.0) else 0.0
        val texture = when {
            flatness < 0.08 && harmonicRatio > 0.25 -> "tonal / harmonic"
            flatness > 0.28 -> "noise-like / broadband"
            else -> "mixed spectrum"
        }
        fun bandDb(firstHz: Double, lastHz: Double): String {
            val first = max(1, (firstHz * n / MODEL_RATE).toInt())
            val last = min(magnitudes.lastIndex, (lastHz * n / MODEL_RATE).toInt())
            var power = 0.0
            for (i in first..last) power += magnitudes[i] * magnitudes[i]
            // Correct the Hann-windowed, unnormalised FFT magnitude to mean-square level.
            val meanSquare = 16.0 * power / (3.0 * n * n)
            val level = sqrt(meanSquare)
            val db = if (level > 0.0) 20.0 * log10(level) else -120.0
            return String.format(java.util.Locale.US, "%.0f dBFS", db)
        }
        val peakText = if (dominantHz >= 1000.0) String.format(java.util.Locale.US, "%.2f kHz", dominantHz / 1000.0)
        else String.format(java.util.Locale.US, "%.0f Hz", dominantHz)
        return String.format(
            java.util.Locale.US,
            "RMS %.1f dBFS · dominant %s · centroid %.0f Hz · flatness %.2f · harmonic energy %.0f%% (%s)\nBands (low/mid/high/top): %s / %s / %s / %s",
            rmsDb, peakText, centroid, flatness, harmonicRatio * 100.0, texture,
            bandDb(20.0, 250.0), bandDb(250.0, 1000.0), bandDb(1000.0, 4000.0), bandDb(4000.0, 8000.0)
        )
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            for (start in 0 until n step length) {
                var wReal = 1.0
                var wImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImaginary
                    val oddImaginary = real[odd] * wImaginary + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = wReal * stepReal - wImaginary * stepImaginary
                    wImaginary = wReal * stepImaginary + wImaginary * stepReal
                    wReal = nextReal
                }
            }
            length = length shl 1
        }
    }

    fun close() {
        listening = false
        worker.execute {
            try { interpreter?.close() } catch (_: Exception) { }
            interpreter = null
        }
        worker.shutdown()
    }

    private fun createDecimatorFilter(): DoubleArray {
        val taps = 63
        val middle = taps / 2
        val cutoff = 1.0 / 6.0 // 8 kHz Nyquist limit for 16 kHz output from 48 kHz input.
        val coefficients = DoubleArray(taps)
        var sum = 0.0
        for (i in 0 until taps) {
            val x = i - middle
            val sinc = if (x == 0) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
            val window = 0.42 - 0.5 * cos(2.0 * PI * i / (taps - 1)) + 0.08 * cos(4.0 * PI * i / (taps - 1))
            coefficients[i] = sinc * window
            sum += coefficients[i]
        }
        for (i in coefficients.indices) coefficients[i] /= sum
        return coefficients
    }
}

private class SoundSessionChartView(context: Context) : View(context) {
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

private class SoundSessionTimelineView(context: Context) : View(context) {
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

private class PianoKeyboardView(
    context: Context,
    private val onKeyChange: (midiNote: Int, pressed: Boolean) -> Unit
) : View(context) {
    private var safeEdgeInsetPx = 0f
    private var transpose = 0
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 245, 240); style = Paint.Style.FILL }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 29, 36); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 78, 90); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 75); style = Paint.Style.FILL }
    private val whiteNotes = (36..96).filter { it % 12 !in listOf(1, 3, 6, 8, 10) } // C2–C7
    private val blackNotes = (36..95).map { note -> if (note % 12 in listOf(1, 3, 6, 8, 10)) note else -1 }
    private val pointerNotes = mutableMapOf<Int, Int>()
    private val activeNotes = mutableSetOf<Int>()

    fun setSafeEdgeInset(insetPx: Float) {
        safeEdgeInsetPx = insetPx.coerceAtLeast(0f)
        invalidate()
    }

    fun setTranspose(semitones: Int) {
        transpose = semitones
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 23, 36))
        if (width <= 0 || height <= 0) return
        val edgeInset = safeEdgeInsetPx.coerceAtMost(width.toFloat() * 0.35f)
        val usableWidth = (width.toFloat() - edgeInset * 2f).coerceAtLeast(1f)
        val whiteWidth = usableWidth / whiteNotes.size
        val top = height * 0.04f
        val bottom = height * 0.96f
        whiteNotes.forEachIndexed { index, note ->
            val left = edgeInset + index * whiteWidth
            val right = edgeInset + (index + 1) * whiteWidth
            canvas.drawRect(left, top, right, bottom, if (note in activeNotes) pressedPaint else whitePaint)
            canvas.drawRect(left, top, right, bottom, linePaint)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = (height * 0.075f).coerceIn(10f, 24f); textAlign = Paint.Align.CENTER }
            canvas.drawText(noteName(note + transpose), (left + right) / 2f, bottom - height * 0.04f, labelPaint)
        }
        val blackWidth = whiteWidth * 0.58f
        val blackHeight = height * 0.60f
        for (note in blackNotes) {
            if (note < 0) continue
            val whiteIndex = whiteNotes.count { it < note }
            val left = edgeInset + whiteIndex * whiteWidth - blackWidth / 2f
            canvas.drawRect(left, top, left + blackWidth, top + blackHeight, if (note in activeNotes) pressedPaint else blackPaint)
            canvas.drawRect(left, top, left + blackWidth, top + blackHeight, linePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        fun updatePointer(pointerIndex: Int) {
            val pointerId = event.getPointerId(pointerIndex)
            val note = noteAt(event.getX(pointerIndex), event.getY(pointerIndex))
            val previous = pointerNotes[pointerId]
            if (note != previous) {
                if (previous != null) {
                    pointerNotes.remove(pointerId)
                    if (pointerNotes.values.none { it == previous }) {
                        activeNotes.remove(previous)
                        onKeyChange(previous, false)
                    }
                }
                if (note != null) {
                    pointerNotes[pointerId] = note
                    val wasActive = note in activeNotes
                    activeNotes.add(note)
                    if (!wasActive) onKeyChange(note, true)
                }
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                updatePointer(event.actionIndex)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) updatePointer(i)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val notes = pointerNotes.values.toSet()
                    pointerNotes.clear()
                    notes.forEach { note ->
                        activeNotes.remove(note)
                        onKeyChange(note, false)
                    }
                } else {
                    val pointerId = event.getPointerId(event.actionIndex)
                    val previous = pointerNotes.remove(pointerId)
                    if (previous != null && pointerNotes.values.none { it == previous }) {
                        activeNotes.remove(previous)
                        onKeyChange(previous, false)
                    }
                }
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun releaseAllPointers() {
        val notes = pointerNotes.values.toSet()
        pointerNotes.clear()
        activeNotes.clear()
        notes.forEach { onKeyChange(it, false) }
        invalidate()
    }

    private fun noteAt(x: Float, y: Float): Int? {
        val edgeInset = safeEdgeInsetPx.coerceAtMost(width.toFloat() * 0.35f)
        if (y < 0 || y > height || x < edgeInset || x > width - edgeInset) return null
        val usableWidth = (width.toFloat() - edgeInset * 2f).coerceAtLeast(1f)
        val whiteWidth = usableWidth / whiteNotes.size
        val blackWidth = whiteWidth * 0.58f
        val blackHeight = height * 0.60f
        if (y <= blackHeight) {
            for (note in blackNotes) {
                if (note < 0) continue
                val whiteIndex = whiteNotes.count { it < note }
                val left = edgeInset + whiteIndex * whiteWidth - blackWidth / 2f
                if (x in left..(left + blackWidth)) return note
            }
        }
        return whiteNotes[((x - edgeInset) / whiteWidth).toInt().coerceIn(0, whiteNotes.lastIndex)]
    }

    private fun noteToFrequency(note: Int): Int = (440.0 * 2.0.pow((note - 69) / 12.0)).roundToInt().coerceIn(20, 20000)
    private fun noteName(note: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[note % 12] + (note / 12 - 1)
}


class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundColor = Color.rgb(15, 23, 36)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(49, 63, 81)
        strokeWidth = 1f
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

    private val peakHoldLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 130, 145)
        textSize = 23f
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
        textSize = 27f
    }

    private val dbLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 27f
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
        textSize = 27f
        textAlign = Paint.Align.RIGHT
    }
    private val dbAxisUnitPaint = Paint(dbLabelPaint).apply { textAlign = Paint.Align.RIGHT }

    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 135)
        textSize = 23f
        style = Paint.Style.FILL
        setShadowLayer(3f, 0f, 1f, Color.rgb(15, 23, 36))
    }

    // Custom frequency-grid divisions requested for the spectrum display.
    private val gridFrequencies = intArrayOf(
        0, 40, 60, 100, 200, 340, 400, 500, 600, 700, 770, 880,
        1000, 2000, 2800, 3200, 4000, 5100, 6200, 7300, 8400,
        9500, 10000, 11000, 12000, 13000, 14000, 15000, 16000,
        17000, 18000, 19000, 20000, 24000, 28000, 32000, 36000, 40000
    )

    private val frequencyLabelTicks = setOf(40, 60, 100, 200, 500, 1000, 2000, 4000, 10000, 20000)

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
    private val spectrogramHistoryLimit = 140
    private var peakHoldEnabled = false
    private var peakHoldMagnitudes = DoubleArray(0)
    private var dbCeiling = 10
    private var dbFloor = -130
    private var dbRangeMode = "DEFAULT"
    private var savedDbRange: Pair<Int, Int>? = null
    private var savedFrequencyRangeForKeys: Pair<Double, Double>? = null
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

    fun setSoundIdPortraitLayoutActive(active: Boolean) {
        if (soundIdPortraitLayoutActive == active) return
        soundIdPortraitLayoutActive = active
        invalidate()
    }

    private fun plotLeftInset(): Float {
        // In portrait Sound ID mode, use the full screen width for the plot.
        // The landscape inset exists to keep the graph clear of the front camera.
        if (soundIdPortraitLayoutActive) return 0f
        val density = resources.displayMetrics.density
        val cameraSafeInsetScreen = if (android.os.Build.VERSION.SDK_INT >= 28) {
            rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
        } else {
            0
        }
        val viewLocation = IntArray(2)
        getLocationOnScreen(viewLocation)
        val cameraSafeInset = (cameraSafeInsetScreen - viewLocation[0]).coerceAtLeast(0)
        // Keep the graph close to the cutout while leaving a small visual safety gap.
        return if (cameraSafeInset > 0) {
            cameraSafeInset + 4f * density
        } else {
            18f * density
        }
    }

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
            while (spectrogramHistory.size > spectrogramHistoryLimit) spectrogramHistory.removeFirst()
        }
        // Keep the vertical axis range at -160 dB to -30 dB after startup and reset.
        // Do not auto-center the graph on the strongest peak, which can make it jump vertically.
        if (initialAutoRangePending) {
            dbFloor = -160
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

    fun clearSpectrum() {
        magnitudes = DoubleArray(0)
        smoothedMagnitudes = DoubleArray(0)
        synchronized(spectrogramHistory) { spectrogramHistory.clear() }
        peakHoldMagnitudes = DoubleArray(0)
        peakFrequency = 0.0
        // Preserve the user's current scale when analysis is stopped/restarted.
        invalidate()
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
                draggingYScale = !spectrogramEnabled && event.x >= width - dbAxisMarginWidth
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

    private fun performClickIfShortPress() {
        if (!longPressTriggered) performClick()
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
            "TONE", "KEYS" -> mode
            else -> "DEFAULT"
        }
        if (nextMode == dbRangeMode) return

        if (dbRangeMode == "DEFAULT" && nextMode != "DEFAULT") {
            savedDbRange = if (initialAutoRangePending) (-160 to -30) else (dbFloor to dbCeiling)
        }

        dbRangeMode = nextMode
        when (nextMode) {
            "TONE" -> { dbFloor = -135; dbCeiling = -30 }
            "KEYS" -> { dbFloor = -140; dbCeiling = -30 }
            else -> {
                val restoredRange = savedDbRange ?: (-160 to -30)
                dbFloor = restoredRange.first
                dbCeiling = restoredRange.second
                savedDbRange = null
            }
        }
        initialAutoRangePending = false
        invalidate()
        notifyControlState()
    }

    fun setKeysFrequencyRangeActive(active: Boolean) {
        if (active) {
            if (savedFrequencyRangeForKeys == null) {
                savedFrequencyRangeForKeys = visibleMinFrequency to visibleMaxFrequency
            }
            visibleMinFrequency = 65.0
            visibleMaxFrequency = 12500.0
        } else {
            val previousRange = savedFrequencyRangeForKeys ?: return
            visibleMinFrequency = previousRange.first
            visibleMaxFrequency = previousRange.second
            savedFrequencyRangeForKeys = null
        }
        invalidate()
        notifyFrequencyRange()
    }

    fun toggleFrequencyAxis() {
        linearFrequencyAxis = !linearFrequencyAxis
        invalidate()
        notifyControlState()
    }

    fun toggleSpectrogram(): Boolean {
        spectrogramEnabled = !spectrogramEnabled
        // Show the selected view on the next animation frame. New FFT frames continue
        // filling the existing 140-frame history while the view is visible.
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
        // The waterfall still updates on every incoming FFT frame.
        val columns = max(1, (right - left).toInt().coerceAtMost(240))
        val rows = history.size
        val cellWidth = (right - left) / columns
        val cellHeight = (bottom - top) / rows
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val highestAnalyzedFrequency = min(sampleRate / 2.0, 20000.0)

        for (row in history.indices) {
            val frame = history[row]
            if (frame.isEmpty()) continue
            for (column in 0 until columns) {
                val ratio = (column + 0.5) / columns.toDouble()
                val frequency = frequencyAtRatio(ratio, minFreq, maxFreq)
                if (frequency < 0.0 || frequency > highestAnalyzedFrequency) {
                    paint.color = backgroundColor
                } else {
                    val bin = (frequency * fftSize / sampleRate).roundToInt().coerceIn(0, frame.lastIndex)
                    val normalizedMagnitude = frame[bin] * 4.0 / fftSize
                    val magnitudeDb = if (normalizedMagnitude > 1e-8) 20.0 * log10(normalizedMagnitude) else -160.0
                    paint.color = spectrogramColor(magnitudeDb)
                }
                val x0 = left + column * cellWidth
                // Oldest frames are at the top; newest frames are at the bottom.
                val y0 = top + row * cellHeight
                canvas.drawRect(x0, y0, x0 + cellWidth + 1f, y0 + cellHeight + 1f, paint)
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
        val right = width.toFloat() - dbAxisMarginWidth - 2f
        if (right <= left || touchX < left || touchX > right) return
        val ratio = ((touchX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        val selected = frequencyAtRatio(ratio, visibleMinFrequency, visibleMaxFrequency)
        selectedFrequency = if (selected in minFrequencyBound..20000.0) selected else null
        invalidate()
    }

    private fun selectSpectrogramAt(touchX: Float, touchY: Float) {
        val left = plotLeftInset()
        val right = width.toFloat() - dbAxisMarginWidth - 2f
        val top = 56f
        val bottom = height.toFloat() - 42f
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

    fun adjustDbCeiling(delta: Int) {
        initialAutoRangePending = false
        dbCeiling = (dbCeiling + delta).coerceIn(-125, 10)
        if (dbCeiling - dbFloor < 20) dbFloor = dbCeiling - 20
        invalidate()
        notifyControlState()
    }

    fun adjustDbFloor(delta: Int) {
        initialAutoRangePending = false
        dbFloor = (dbFloor + delta).coerceIn(-150, -10)
        if (dbCeiling - dbFloor < 20) dbCeiling = dbFloor + 20
        invalidate()
        notifyControlState()
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
            "KEYS" -> -140
            else -> -160
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
        val right = width.toFloat() - dbAxisMarginWidth - 2f
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
        val plotWidth = (width.toFloat() - plotLeftInset() - dbAxisMarginWidth - 2f).coerceAtLeast(1f)
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
        val plotHeight = (height.toFloat() - 80f).coerceAtLeast(1f)
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
            val db = magnitudeToDb(interpolatedMagnitude, fftSize).coerceIn(dbFloor.toDouble(), dbCeiling.toDouble())
            val y = top + ((dbCeiling - db) / (dbCeiling - dbFloor)).toFloat() * (bottom - top)
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

        val candidates = ArrayList<Peak>()
        for (bin in firstBin..lastBin) {
            val magnitude = data[bin]
            if (magnitude >= data[bin - 1] && magnitude > data[bin + 1]) {
                val db = magnitudeToDb(magnitude, fftSize)
                if (db > -95.0) {
                    candidates.add(Peak(bin, bin * binWidth, db))
                }
            }
        }

        candidates.sortByDescending { it.db }
        val selected = ArrayList<Peak>(4)
        for (candidate in candidates) {
            // Prevent adjacent FFT bins belonging to one broad peak being reported separately.
            if (selected.none { kotlin.math.abs(it.bin - candidate.bin) < 5 }) {
                selected.add(candidate)
                if (selected.size == 4) break
            }
        }
        return selected
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(backgroundColor)

        // Leave room at the left edge for the front camera in landscape.
        val left = plotLeftInset()
        val right = w - dbAxisMarginWidth - 2f
        val top = 56f
        val bottom = h - 42f
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
            val axisStart = minFreq
            val axisEnd = maxFreq
            val axisPeriod = maxFrequencyBound
            val firstGridCycle = kotlin.math.floor(axisStart / axisPeriod).toInt() - 1
            val lastGridCycle = kotlin.math.ceil(axisEnd / axisPeriod).toInt() + 1
            for (cycle in firstGridCycle..lastGridCycle) {
                for (frequency in gridFrequencies) {
                    val position = frequency + cycle * axisPeriod
                    if (position < axisStart || position > axisEnd) continue
                    val x = left + (right - left) * ((position - axisStart) / (axisEnd - axisStart)).toFloat()
                    canvas.drawLine(x, top, x, bottom, gridPaint)
                    if (cycle != 0 || frequency <= 0 || frequency !in frequencyLabelTicks) continue
                    val label = when {
                        frequency == 20000 -> "20k"
                        frequency >= 1000 -> "${frequency / 1000}k"
                        else -> frequency.toString()
                    }
                    val labelX = max(left, x - textPaint.measureText(label) / 2f)
                    canvas.drawText(label, labelX, bottom + 30f, textPaint)
                }
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
                    canvas.drawText(label, labelX, bottom + 30f, textPaint)
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

        fun yForDb(db: Double): Float {
            val clampedDb = db.coerceIn(dbFloor.toDouble(), dbCeiling.toDouble())
            return top + ((dbCeiling - clampedDb) / (dbCeiling - dbFloor)).toFloat() * (bottom - top)
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
                        canvas.drawText(label, w - 7f * resources.displayMetrics.density, y + 8f, dbAxisTextPaint)
                    }
                }
            }
            canvas.drawText("TIME", w - 7f * resources.displayMetrics.density, max(10f, top - 26f), dbAxisUnitPaint)
        } else {
            val axisX = right
            val dbTickStep = if (dbCeiling - dbFloor > 160) 20 else 10
            var dbTick = kotlin.math.floor(dbCeiling / dbTickStep.toDouble()).toInt() * dbTickStep
            canvas.drawLine(axisX, top, axisX, bottom, dbAxisPaint)
            while (dbTick >= dbFloor) {
                val y = yForDb(dbTick.toDouble())
                canvas.drawLine(left, y, right, y, gridPaint)
                canvas.drawLine(axisX - 4f, y, axisX + 3f, y, dbAxisPaint)
                canvas.drawText(dbTick.toString(), w - 7f * resources.displayMetrics.density, y + 8f, dbAxisTextPaint)
                dbTick -= dbTickStep
            }
            canvas.drawText("dBFS", w - 7f * resources.displayMetrics.density, top - 26f, dbAxisUnitPaint)
        }

        val data = magnitudes
        if (data.isEmpty()) {
            canvas.drawText("Waiting for audio...", left + 20f, top + 50f, textPaint)
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
            left, right, top, bottom, spectrumPaint, spectrumPath
        )

        if (peakHoldEnabled && peakHoldMagnitudes.isNotEmpty()) {
            drawSpectrumTrace(
                canvas, peakHoldMagnitudes, sourceSampleRate, sourceFftSize, minFreq, maxFreq,
                left, right, top, bottom, peakHoldPaint, peakHoldPath
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
