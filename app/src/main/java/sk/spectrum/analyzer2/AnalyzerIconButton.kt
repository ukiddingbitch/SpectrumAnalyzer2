package sk.spectrum.analyzer2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity

import androidx.appcompat.widget.AppCompatButton

/** Shared analyzer icon button drawing. */
internal class AnalyzerIconButton(
    context: Context,
    icon: Icon
) : AppCompatButton(context) {
    enum class Icon { PLAY, STOP, MIC, RESET, TONE, PRESETS, SWEEP, KEYS, SOUND, AXIS, LIN, LOG, IMPORT, EXPORT, FULL_SCALE_OPEN, FULL_SCALE_CLOSE, SPECTRUM, SPECTROGRAM, SPL_RESPONSE, SPL_HOLD, SPL_MIN_MAX, DTMF, VOLUME, VOLUME_MUTED, OUTPUT_LEFT, OUTPUT_RIGHT, OUTPUT_STEREO }

    constructor(context: Context) : this(context, Icon.PLAY)

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
                Icon.SPL_RESPONSE -> "Switch SPL response time"
                Icon.SPL_HOLD -> "Hold SPL reading"
                Icon.SPL_MIN_MAX -> "Show minimum and maximum SPL"
                Icon.VOLUME -> "Tone generator volume"
                Icon.VOLUME_MUTED -> "Piano muted; tap to restore volume"
                Icon.OUTPUT_LEFT -> "Left audio output"
                Icon.OUTPUT_RIGHT -> "Right audio output"
                Icon.OUTPUT_STEREO -> "Stereo audio output"
                Icon.DTMF -> "DTMF keypad mode"
            }
        }

    var playStopIconScale = 0.30f
        set(value) {
            field = value.coerceIn(0.30f, 0.80f)
            invalidate()
        }

    var roundedPlayStopGlyphs = false

    var usePlayStopImages = false
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val playStopRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var playButtonBitmap: android.graphics.Bitmap? = null
    private var stopButtonBitmap: android.graphics.Bitmap? = null
    private var customIconBitmap: android.graphics.Bitmap? = null
    private val playStopBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val playStopBitmapBounds = android.graphics.RectF()

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
            Icon.SPL_RESPONSE -> "Switch SPL response time"
            Icon.SPL_HOLD -> "Hold SPL reading"
            Icon.SPL_MIN_MAX -> "Show minimum and maximum SPL"
            Icon.VOLUME -> "Tone generator volume"
            Icon.VOLUME_MUTED -> "Piano muted; tap to restore volume"
            Icon.OUTPUT_LEFT -> "Left audio output"
            Icon.OUTPUT_RIGHT -> "Right audio output"
            Icon.OUTPUT_STEREO -> "Stereo audio output"
            Icon.DTMF -> "DTMF keypad mode"
        }
    }

    fun setPlayStopImages(play: android.graphics.Bitmap, stop: android.graphics.Bitmap) {
        playButtonBitmap = play
        stopButtonBitmap = stop
        invalidate()
    }

    fun setCustomIcon(bitmap: android.graphics.Bitmap?) {
        customIconBitmap = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (usePlayStopImages && (icon == Icon.PLAY || icon == Icon.STOP)) {
            val bitmap = if (icon == Icon.PLAY) playButtonBitmap else stopButtonBitmap
            if (bitmap != null && width > 0 && height > 0) {
                playStopBitmapBounds.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(bitmap, null, playStopBitmapBounds, playStopBitmapPaint)
                return
            }
        }
        customIconBitmap?.let { bitmap ->
            if (width > 0 && height > 0) {
                if (icon == Icon.SOUND) {
                    val scale = minOf(width * 0.88f / bitmap.width, height * 0.82f / bitmap.height)
                    val iconWidth = bitmap.width * scale
                    val iconHeight = bitmap.height * scale
                    val insetX = (width - iconWidth) * 0.5f
                    val insetY = (height - iconHeight) * 0.5f
                    playStopBitmapBounds.set(insetX, insetY, insetX + iconWidth, insetY + iconHeight)
                } else {
                    val iconSize = minOf(width, height) * 0.84f
                    val insetX = (width - iconSize) * 0.5f
                    val insetY = (height - iconSize) * 0.5f
                    playStopBitmapBounds.set(insetX, insetY, insetX + iconSize, insetY + iconSize)
                }
                canvas.drawBitmap(bitmap, null, playStopBitmapBounds, playStopBitmapPaint)
                return
            }
        }
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
            Icon.TONE, Icon.PRESETS, Icon.SWEEP, Icon.MIC, Icon.SOUND, Icon.SPECTRUM, Icon.SPECTROGRAM, Icon.AXIS, Icon.KEYS, Icon.LIN, Icon.LOG, Icon.IMPORT, Icon.EXPORT, Icon.FULL_SCALE_OPEN, Icon.FULL_SCALE_CLOSE, Icon.SPL_RESPONSE, Icon.SPL_HOLD, Icon.SPL_MIN_MAX, Icon.DTMF, Icon.VOLUME, Icon.VOLUME_MUTED, Icon.OUTPUT_LEFT, Icon.OUTPUT_RIGHT, Icon.OUTPUT_STEREO -> {
                val s = minOf(width, height) * when (icon) {
                    Icon.KEYS -> 0.48f
                    Icon.MIC -> 0.62f
                    Icon.TONE, Icon.SOUND, Icon.SPECTRUM, Icon.SPECTROGRAM -> 0.40f
                    Icon.PRESETS, Icon.SWEEP, Icon.IMPORT, Icon.EXPORT -> 0.48f
                    Icon.SPL_RESPONSE, Icon.SPL_HOLD, Icon.SPL_MIN_MAX -> 0.52f
                    Icon.DTMF -> 0.68f
                    Icon.VOLUME, Icon.VOLUME_MUTED -> 0.72f
                    Icon.OUTPUT_LEFT, Icon.OUTPUT_RIGHT, Icon.OUTPUT_STEREO -> 0.90f
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
                    Icon.VOLUME, Icon.VOLUME_MUTED -> {
                        val speaker = Path().apply {
                            moveTo(l + s * 0.08f, cy - s * 0.16f)
                            lineTo(l + s * 0.30f, cy - s * 0.16f)
                            lineTo(l + s * 0.55f, cy - s * 0.36f)
                            lineTo(l + s * 0.55f, cy + s * 0.36f)
                            lineTo(l + s * 0.30f, cy + s * 0.16f)
                            lineTo(l + s * 0.08f, cy + s * 0.16f)
                            close()
                        }
                        canvas.drawPath(speaker, fillPaint)
                        canvas.drawArc(l + s * 0.39f, cy - s * 0.25f, l + s * 0.83f, cy + s * 0.25f, -58f, 116f, false, symbolPaint)
                        canvas.drawArc(l + s * 0.30f, cy - s * 0.42f, l + s * 1.03f, cy + s * 0.42f, -58f, 116f, false, symbolPaint)
                        if (icon == Icon.VOLUME_MUTED) {
                            canvas.drawLine(l + s * 0.38f, cy - s * 0.42f, l + s * 1.02f, cy + s * 0.42f, symbolPaint)
                        }
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
                    Icon.SPL_RESPONSE -> {
                        canvas.drawCircle(cx, cy, s * 0.34f, symbolPaint)
                        canvas.drawLine(cx, cy, cx + s * 0.15f, cy - s * 0.18f, symbolPaint)
                        canvas.drawLine(cx, cy, cx, cy - s * 0.23f, symbolPaint)
                        canvas.drawCircle(cx, cy, s * 0.035f, fillPaint)
                    }
                    Icon.SPL_HOLD -> {
                        canvas.drawRoundRect(cx - s * 0.22f, cy - s * 0.28f, cx - s * 0.07f, cy + s * 0.28f, s * 0.04f, s * 0.04f, fillPaint)
                        canvas.drawRoundRect(cx + s * 0.07f, cy - s * 0.28f, cx + s * 0.22f, cy + s * 0.28f, s * 0.04f, s * 0.04f, fillPaint)
                    }
                    Icon.SPL_MIN_MAX -> {
                        val trace = Path().apply {
                            moveTo(l + s * 0.08f, cy + s * 0.10f)
                            lineTo(l + s * 0.31f, cy + s * 0.10f)
                            lineTo(l + s * 0.45f, cy - s * 0.24f)
                            lineTo(l + s * 0.58f, cy + s * 0.22f)
                            lineTo(l + s * 0.73f, cy - s * 0.02f)
                            lineTo(r - s * 0.08f, cy - s * 0.02f)
                        }
                        canvas.drawPath(trace, symbolPaint)
                    }
                    Icon.DTMF -> {
                        val keyWidth = s * 0.22f
                        val keyHeight = s * 0.15f
                        val gapX = s * 0.07f
                        val gapY = s * 0.06f
                        val gridWidth = keyWidth * 3f + gapX * 2f
                        val gridHeight = keyHeight * 4f + gapY * 3f
                        val startX = cx - gridWidth / 2f
                        val startY = cy - gridHeight / 2f
                        for (row in 0..3) {
                            for (column in 0..2) {
                                val keyLeft = startX + column * (keyWidth + gapX)
                                val keyTop = startY + row * (keyHeight + gapY)
                                canvas.drawRoundRect(
                                    keyLeft, keyTop, keyLeft + keyWidth, keyTop + keyHeight,
                                    s * 0.035f, s * 0.035f, symbolPaint
                                )
                            }
                        }
                    }
                    Icon.OUTPUT_LEFT, Icon.OUTPUT_RIGHT, Icon.OUTPUT_STEREO -> {
                        val normalStrokeWidth = symbolPaint.strokeWidth
                        val normalSymbolColor = symbolPaint.color
                        val normalFillColor = fillPaint.color
                        val cabinetStroke = resources.displayMetrics.density * 1.35f
                        symbolPaint.strokeWidth = cabinetStroke
                        fun drawSpeakerCabinet(centerX: Float, active: Boolean) {
                            val channelColor = if (active) Color.WHITE else Color.rgb(105, 121, 139)
                            val cabinetWidth = s * 0.38f
                            val cabinetHeight = s * 0.88f
                            val cabinetLeft = centerX - cabinetWidth / 2f
                            val cabinetTop = cy - cabinetHeight / 2f
                            symbolPaint.color = channelColor
                            fillPaint.color = channelColor
                            canvas.drawRoundRect(
                                cabinetLeft,
                                cabinetTop,
                                cabinetLeft + cabinetWidth,
                                cabinetTop + cabinetHeight,
                                s * 0.045f,
                                s * 0.045f,
                                symbolPaint
                            )

                            val tweeterY = cy - s * 0.20f
                            val tweeterRadius = s * 0.075f
                            canvas.drawCircle(centerX, tweeterY, tweeterRadius, symbolPaint)
                            canvas.drawCircle(centerX, tweeterY, tweeterRadius * 0.34f, fillPaint)

                            val wooferY = cy + s * 0.17f
                            val wooferRadius = s * 0.145f
                            canvas.drawCircle(centerX, wooferY, wooferRadius, symbolPaint)
                            canvas.drawCircle(centerX, wooferY, wooferRadius * 0.66f, symbolPaint)
                            canvas.drawCircle(centerX, wooferY, wooferRadius * 0.23f, fillPaint)
                        }
                        drawSpeakerCabinet(
                            centerX = cx - s * 0.23f,
                            active = icon == Icon.OUTPUT_LEFT || icon == Icon.OUTPUT_STEREO
                        )
                        drawSpeakerCabinet(
                            centerX = cx + s * 0.23f,
                            active = icon == Icon.OUTPUT_RIGHT || icon == Icon.OUTPUT_STEREO
                        )
                        symbolPaint.color = normalSymbolColor
                        symbolPaint.strokeWidth = normalStrokeWidth
                        fillPaint.color = normalFillColor
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
