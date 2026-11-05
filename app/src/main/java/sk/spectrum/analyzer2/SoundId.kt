package sk.spectrum.analyzer2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import java.io.ByteArrayOutputStream

import kotlin.math.roundToInt

/** Sound identification screen, session summaries and reports. */
internal fun MainActivity.showSoundIdDialog() {
    leaveFullScaleKeyboard(animate = false)
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
        usePlayStopImages = true
        setPlayStopImages(
            decodePlayStopBitmap(R.drawable.ic_tone_play),
            decodePlayStopBitmap(R.drawable.ic_tone_stop)
        )
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

internal fun MainActivity.setLiveSoundPredictionsVisible(visible: Boolean) {
    val visibility = if (visible) View.VISIBLE else View.GONE
    soundIdBestSoundView?.visibility = visibility
    soundIdScoreView?.visibility = visibility
    soundIdAlternativesView?.visibility = visibility
}

internal fun MainActivity.updateSoundIdToggleStyle() {
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

internal fun MainActivity.showSoundIdSummary(summary: SoundSessionSummary) {
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

internal fun MainActivity.formatSoundDuration(seconds: Double): String {
    val wholeSeconds = seconds.roundToInt().coerceAtLeast(0)
    return "${wholeSeconds / 60}:${(wholeSeconds % 60).toString().padStart(2, '0')}"
}

internal fun MainActivity.exportSoundIdSummary() {
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
