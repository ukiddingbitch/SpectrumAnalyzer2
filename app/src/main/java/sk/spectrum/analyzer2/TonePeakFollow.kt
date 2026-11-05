package sk.spectrum.analyzer2

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

import kotlin.math.roundToInt

/** Following detected spectrum peaks and the related tone settings. */
internal fun MainActivity.isUsableTonePeak(frequency: Double, peakDb: Double, rmsDb: Double): Boolean {
    return frequency.isFinite() && frequency in 20.0..20000.0 &&
            peakDb > -85.0 && rmsDb > -90.0 && peakDb - rmsDb >= 1.5
}

internal fun MainActivity.setTonePeakFollowEnabled(enabled: Boolean, refreshUi: Boolean = true) {
    tonePeakFollowEnabled = enabled
    val now = SystemClock.elapsedRealtime()
    tonePeakFollowWindowStartedAt = if (enabled) now else 0L
    tonePeakLastAcceptedAt = 0L
    tonePeakBestFrequency = 0.0
    tonePeakBestDb = -120.0
    tonePeakPresetButton?.backgroundTintList = if (enabled) {
        android.content.res.ColorStateList.valueOf(Color.rgb(29, 105, 145))
    } else null
    tonePeakPresetButton?.contentDescription = if (enabled) {
        val holdDescription = if (tonePeakHoldDurationMs == 0L) "indefinitely" else "for ${tonePeakHoldDurationMs / 1000.0} seconds"
        "Peak tracking active; scans every ${tonePeakDetectionWindowMs} milliseconds and keeps the last frequency $holdDescription."
    } else "Frequency presets"
    if (refreshUi) toneSweepUiUpdate?.invoke()
}

internal fun MainActivity.updateTonePeakFollow(frequency: Double, peakDb: Double, rmsDb: Double) {
    if (!tonePeakFollowEnabled) return

    if (isUsableTonePeak(frequency, peakDb, rmsDb) && peakDb > tonePeakBestDb) {
        tonePeakBestFrequency = frequency
        tonePeakBestDb = peakDb
    }

    val now = SystemClock.elapsedRealtime()
    if (now - tonePeakFollowWindowStartedAt < tonePeakDetectionWindowMs) {
        if (tonePeakBestFrequency <= 0.0 && tonePeakLastAcceptedAt > 0L && tonePeakHoldDurationMs > 0L &&
            now - tonePeakLastAcceptedAt >= tonePeakHoldDurationMs
        ) {
            setTonePeakFollowEnabled(false)
        }
        return
    }

    if (tonePeakBestFrequency > 0.0) {
        toneFrequency = tonePeakBestFrequency.roundToInt().coerceIn(20, 20000)
        tonePeakLastAcceptedAt = now
        toneSweepUiUpdate?.invoke()
    } else if (tonePeakLastAcceptedAt > 0L && tonePeakHoldDurationMs > 0L &&
        now - tonePeakLastAcceptedAt >= tonePeakHoldDurationMs
    ) {
        setTonePeakFollowEnabled(false)
        return
    }

    tonePeakFollowWindowStartedAt = now
    tonePeakBestFrequency = 0.0
    tonePeakBestDb = -120.0
    if (isUsableTonePeak(frequency, peakDb, rmsDb)) {
        tonePeakBestFrequency = frequency
        tonePeakBestDb = peakDb
    }
}

internal fun MainActivity.showTonePeakSettingsDialog(presetDialog: Dialog) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    var selectedDetectionMs = tonePeakDetectionWindowMs
    var selectedHoldMs = tonePeakHoldDurationMs
    val detectionButtons = mutableListOf<Pair<Long, Button>>()
    val holdButtons = mutableListOf<Pair<Long, Button>>()
    lateinit var refreshOptions: () -> Unit

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        styleNumericDialogCard(this)
    }
    content.addView(TextView(this).apply {
        text = "PEAK TRACKING"
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.rgb(225, 233, 243))
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(4))
    }, LinearLayout.LayoutParams(-1, -2))
    content.addView(TextView(this).apply {
        text = "Choose how often PEAK updates and how long it stays."
        textSize = 11f
        setTextColor(Color.rgb(166, 182, 199))
        gravity = Gravity.CENTER
        setPadding(dp(4), 0, dp(4), dp(9))
    }, LinearLayout.LayoutParams(-1, -2))

    refreshOptions = {
        for ((value, button) in detectionButtons) {
            val selected = value == selectedDetectionMs
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (selected) Color.rgb(39, 112, 147) else Color.rgb(35, 47, 62)
            )
            button.setTextColor(if (selected) Color.WHITE else Color.rgb(188, 201, 215))
        }
        for ((value, button) in holdButtons) {
            val selected = value == selectedHoldMs
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (selected) Color.rgb(39, 112, 147) else Color.rgb(35, 47, 62)
            )
            button.setTextColor(if (selected) Color.WHITE else Color.rgb(188, 201, 215))
        }
    }

    fun addChoiceRow(
        title: String,
        options: List<Pair<Long, String>>,
        selectedValue: () -> Long,
        onSelected: (Long) -> Unit,
        target: MutableList<Pair<Long, Button>>
    ) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(153, 174, 194))
            setPadding(dp(2), dp(5), dp(2), dp(4))
        }, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((value, label) in options) {
            val button = Button(this).apply {
                text = label
                textSize = if (label == "∞") 18f else 10f
                isAllCaps = false
                minHeight = dp(36)
                minimumHeight = dp(36)
                setPadding(dp(1), 0, dp(1), 0)
                background = rounded(Color.rgb(35, 47, 62), 10)
                stateListAnimator = null
                if (label == "∞") setTypeface(typeface, android.graphics.Typeface.BOLD)
                contentDescription = if (label == "∞") "$title unlimited" else "$title $label"
                setOnClickListener {
                    onSelected(value)
                    refreshOptions()
                }
            }
            target.add(value to button)
            row.addView(button, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (row.childCount > 0) marginStart = dp(3)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
        if (selectedValue() !in options.map { it.first }) onSelected(options.first().first)
    }

    addChoiceRow(
        "DETECTION WINDOW",
        listOf(250L to "0.25 s", 500L to "0.5 s", 1000L to "1 s", 2000L to "2 s", 5000L to "5 s"),
        { selectedDetectionMs },
        { selectedDetectionMs = it },
        detectionButtons
    )
    addChoiceRow(
        "KEEP LAST FREQUENCY",
        listOf(1000L to "1 s", 2000L to "2 s", 5000L to "5 s", 10000L to "10 s", 0L to "∞"),
        { selectedHoldMs },
        { selectedHoldMs = it },
        holdButtons
    )

    val dialog = createAnimatedDialog()
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    val cancel = Button(this).apply {
        text = "CANCEL"
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.rgb(205, 216, 227))
        background = rounded(Color.rgb(38, 49, 64), 12)
        setOnClickListener { dialog.dismiss() }
    }
    val apply = Button(this).apply {
        text = "USE PEAK"
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(39, 113, 147), 12)
        setOnClickListener {
            tonePeakDetectionWindowMs = selectedDetectionMs
            tonePeakHoldDurationMs = selectedHoldMs
            getSharedPreferences("tone_peak_follow", Context.MODE_PRIVATE).edit()
                .putLong("detection_window_ms", tonePeakDetectionWindowMs)
                .putLong("hold_duration_ms", tonePeakHoldDurationMs)
                .apply()
            setTonePeakFollowEnabled(true)
            presetDialog.dismiss()
            dialog.dismiss()
        }
    }
    actions.addView(cancel, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
    actions.addView(apply, LinearLayout.LayoutParams(0, dp(40), 1f))
    content.addView(actions, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(9) })

    setDialogCardContent(dialog, content)
    requireExplicitDialogAction(dialog)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    refreshOptions()
    centerDialogOnScreen(dialog, dp(260), height = dp(360))
    dialog.show()
}
