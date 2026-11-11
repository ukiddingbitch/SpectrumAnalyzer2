package sk.spectrum.analyzer2

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** SPL controls, reference chart, existing microphone calibration lookup and detail card. */
// Typical A-weighted examples, OSHA Technical Manual III:5, Figure 3.
// These are comparisons of level, not recognition of the sound source.
internal data class SplReference(val dba: Int, val name: String)

internal fun MainActivity.nearestSplReference(level: Double): SplReference = splReferences.minBy { abs(it.dba - level) }

internal fun MainActivity.showSplReferenceChart() {
    fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    val dialog = createAnimatedDialog()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(Color.rgb(14, 21, 31)); cornerRadius = dp(20).toFloat() }
    }
    val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    top.addView(TextView(this).apply { text = "Reference chart"; textSize = 18f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(Button(this).apply {
        text = "×"; contentDescription = "Close reference chart"; setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0
        setOnClickListener { dialog.dismiss() }
    }, LinearLayout.LayoutParams(dp(44), dp(44)))
    content.addView(top)
    val value = splReferenceLevelA
    content.addView(TextView(this).apply {
        text = value?.let { String.format(java.util.Locale.US, "≈ %.1f dBA", it) } ?: "— dBA"
        textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.rgb(255, 213, 90))
        setPadding(0, dp(6), 0, dp(14))
    })
    val selected = value?.let(this@showSplReferenceChart::nearestSplReference)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for (item in splReferences.asReversed()) {
        val active = item == selected
        list.addView(TextView(this).apply {
            text = "${item.dba} dBA   ${item.name}"; textSize = 14f
            setTextColor(if (active) Color.rgb(255, 213, 90) else Color.rgb(178, 192, 208))
            if (active) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), dp(7), dp(6), dp(7))
        })
    }
    val scroll = android.widget.ScrollView(this).apply { addView(list) }
    val safeHeight = availableWindowBounds().height()
    content.addView(scroll, LinearLayout.LayoutParams(-1, minOf(dp(410), (safeHeight - dp(220)).coerceAtLeast(dp(100)))))
    content.addView(TextView(this).apply {
        text = "Typical levels · OSHA Figure 3\nApproximate comparisons, not sound identification."
        textSize = 11f; setTextColor(Color.rgb(143, 158, 175)); setPadding(0, dp(10), 0, 0)
        contentDescription = "Source: OSHA Technical Manual, Section III Chapter 5, Figure 3"
    })
    dialog.setContentView(content)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    centerDialogOnScreen(dialog, dp(340), horizontalOffsetDp = 0)
    dialog.show()
}

internal fun MainActivity.currentSplLevelDbfs(): Double =
    if (splFrequencyWeighting == SplWeighting.A) {
        if (splTimeWeighting == "SLOW") latestSplSlowADbfs else latestSplFastADbfs
    } else {
        splSessionSnapshot?.takeIf { it.generation == splMeterSessionGeneration }
            ?.level(splFrequencyWeighting, splTimeWeighting == "SLOW") ?: -120.0
    }

internal fun MainActivity.resetSplSlowDisplayCache() {
    splSlowDisplayValueDb = null
    splSlowDisplayUpdatedAt = 0L
}

internal fun MainActivity.resetSplCaptureReadings() {
    splHoldEnabled = false
    splHeldValueDb = null
    splMinimumTrackingEnabled = false
    splMaximumTrackingEnabled = false
    splDisplayedExtremumMode = null
    splMinimumDb = null
    splMaximumDb = null
}

internal fun MainActivity.setSplTimeWeighting(weighting: String) {
    splTimeWeighting = if (weighting == "SLOW") "SLOW" else "FAST"
    resetSplSlowDisplayCache()
    resetSplCaptureReadings()
    getSharedPreferences("sound_level_calibration", Context.MODE_PRIVATE)
        .edit().putString("time_weighting", splTimeWeighting).apply()
    updateSplModeButtons()
    updateSplReadout()
}

internal fun MainActivity.updateSplModeButtons() {
    fun style(button: Button?, selected: Boolean, selectedColor: Int) {
        button?.apply {
            background = GradientDrawable().apply {
                cornerRadius = 14f * resources.displayMetrics.density
                setColor(if (selected) selectedColor else Color.rgb(25, 35, 48))
                setStroke(
                    (1f * resources.displayMetrics.density).roundToInt().coerceAtLeast(1),
                    if (selected) Color.rgb(110, 193, 210) else Color.rgb(57, 73, 91)
                )
            }
            setTextColor(if (selected) Color.WHITE else Color.rgb(190, 204, 219))
        }
    }
    style(splResponseButton, true, if (splTimeWeighting == "FAST") Color.rgb(34, 105, 91) else Color.rgb(39, 91, 139))
    splResponseButton?.contentDescription = if (splTimeWeighting == "FAST") {
        "SPL response: FAST, 125 milliseconds. Tap to switch to SLOW, 1 second."
    } else {
        "SPL response: SLOW, 1 second. Tap to switch to FAST, 125 milliseconds."
    }
    style(splHoldButton, splHoldEnabled, Color.rgb(142, 101, 29))
    style(splMinButton, splMinimumTrackingEnabled, Color.rgb(39, 91, 139))
    splMinButton?.contentDescription = if (splMinimumTrackingEnabled) {
        "Minimum tracking active. The displayed SPL updates only when a lower value is measured. Tap to stop."
    } else {
        "Track and display the minimum SPL value."
    }
    style(splMaxButton, splMaximumTrackingEnabled, Color.rgb(96, 72, 136))
    splMaxButton?.contentDescription = if (splMaximumTrackingEnabled) {
        "Maximum tracking active. The displayed SPL updates only when a higher value is measured. Tap to stop."
    } else {
        "Track and display the maximum SPL value."
    }
}

internal fun MainActivity.updateSplReadout() {
    val offset = splCalibrationOffsetDb
    val measuredSpl = if (offset != null && running && splHasCurrentSample) {
        (currentSplLevelDbfs() + offset).takeIf { it.isFinite() }
    } else {
        null
    }
    val now = SystemClock.elapsedRealtime()
    val responseSpl = if (splTimeWeighting == "SLOW") {
        when {
            measuredSpl == null -> {
                resetSplSlowDisplayCache()
                null
            }
            splSlowDisplayValueDb == null || now - splSlowDisplayUpdatedAt >= 1000L -> {
                splSlowDisplayValueDb = measuredSpl
                splSlowDisplayUpdatedAt = now
                measuredSpl
            }
            else -> splSlowDisplayValueDb
        }
    } else {
        resetSplSlowDisplayCache()
        measuredSpl
    }
    splLastResponseValueDb = responseSpl
    if (splMinimumTrackingEnabled && responseSpl != null) {
        splMinimumDb = min(splMinimumDb ?: responseSpl, responseSpl)
    }
    if (splMaximumTrackingEnabled && responseSpl != null) {
        splMaximumDb = max(splMaximumDb ?: responseSpl, responseSpl)
    }
    val selectedSpl = when (splDisplayedExtremumMode) {
        "MIN" -> splMinimumDb
        "MAX" -> splMaximumDb
        else -> responseSpl
    }
    val currentSpl = if (splHoldEnabled) splHeldValueDb else selectedSpl
    val unit = "dB(${splFrequencyWeighting.name})"
    val headerDisplayValue = currentSpl?.let {
        String.format(java.util.Locale.US, "SPL ≈ %.1f %s", it, unit)
    } ?: "SPL -- $unit"
    val detailDisplayValue = currentSpl?.let {
        String.format(java.util.Locale.US, "≈ %.1f %s", it, unit)
    } ?: "≈ -- $unit"
    var deltaOverOneSecond: Double? = null
    if (responseSpl != null) {
        splRecentReadings.addLast(now to responseSpl)
        while (splRecentReadings.peekFirst()?.first?.let { it < now - 2000L } == true) {
            splRecentReadings.removeFirst()
        }
        val reference = splRecentReadings.lastOrNull { it.first <= now - 1000L }
        deltaOverOneSecond = reference?.let { responseSpl - it.second }
    } else {
        splRecentReadings.clear()
    }
    headerSplText?.apply {
        text = headerDisplayValue
        contentDescription = "Show enlarged SPL estimate. Microphone input."
    }
    splDetailValueView?.text = detailDisplayValue
    splDetailDeltaView?.text = deltaOverOneSecond?.let {
        String.format(java.util.Locale.US, "Δ 1 s   %+.1f dB", it)
    } ?: "Δ 1 s   —"
    splScaleMeterView?.setLevel(currentSpl)
    splArcMeterView?.setLevels(currentSpl,
        splTrendBuffer.minimum[splFrequencyWeighting.ordinal].takeIf { it.isFinite() },
        splTrendBuffer.maximum[splFrequencyWeighting.ordinal].takeIf { it.isFinite() })
    val meterSnapshot = splSessionSnapshot?.takeIf { it.generation == splMeterSessionGeneration }
    if (running && splHasCurrentSample && offset != null && meterSnapshot != null) {
        splTrendBuffer.add(meterSnapshot, offset, splTimeWeighting == "SLOW")
    }
    splMeterPanel?.update(running, splHoldEnabled, splFrequencyWeighting, meterSnapshot)
    val referenceA = if (splFrequencyWeighting == SplWeighting.A) currentSpl else
        if (running && splHasCurrentSample && offset != null) {
            (if (splTimeWeighting == "SLOW") latestSplSlowADbfs else latestSplFastADbfs) + offset
        } else null
    if (!splHoldEnabled || splReferenceLevelA == null) splReferenceLevelA = referenceA
    splReferenceLabel?.text = splReferenceLevelA?.let { level ->
        val item = nearestSplReference(level)
        item.name.substringBefore(" ·")
    } ?: "—"
    updateSplModeButtons()
}

internal fun MainActivity.toggleSplHold() {
    if (splHoldEnabled) {
        splHoldEnabled = false
        splHeldValueDb = null
    } else {
        val value = when (splDisplayedExtremumMode) {
            "MIN" -> splMinimumDb
            "MAX" -> splMaximumDb
            else -> splLastResponseValueDb
        } ?: return
        splHoldEnabled = true
        splHeldValueDb = value
    }
    updateSplReadout()
}

internal fun MainActivity.toggleSplMinimum() {
    splHoldEnabled = false
    splHeldValueDb = null
    if (splDisplayedExtremumMode == "MIN") {
        splMinimumTrackingEnabled = false
        splMinimumDb = null
        splDisplayedExtremumMode = null
    } else {
        splMinimumTrackingEnabled = true
        splMaximumTrackingEnabled = false
        splMaximumDb = null
        splMinimumDb = splLastResponseValueDb
        splDisplayedExtremumMode = "MIN"
    }
    updateSplReadout()
}

internal fun MainActivity.toggleSplMaximum() {
    splHoldEnabled = false
    splHeldValueDb = null
    if (splDisplayedExtremumMode == "MAX") {
        splMaximumTrackingEnabled = false
        splMaximumDb = null
        splDisplayedExtremumMode = null
    } else {
        splMaximumTrackingEnabled = true
        splMinimumTrackingEnabled = false
        splMinimumDb = null
        splMaximumDb = splLastResponseValueDb
        splDisplayedExtremumMode = "MAX"
    }
    updateSplReadout()
}

internal fun MainActivity.readSystemSplCalibrationOffset(audioRecord: AudioRecord): Double? {
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

internal fun MainActivity.selectSplWeighting(weighting: SplWeighting) {
    if (splFrequencyWeighting == weighting) return
    splFrequencyWeighting = weighting
    resetSplSlowDisplayCache()
    resetSplCaptureReadings()
    splRecentReadings.clear()
    updateSplReadout()
}

internal fun MainActivity.resetSplMeterSession() {
    splMeterSessionGeneration++
    splSessionSnapshot = null
    splTrendBuffer.clear()
    splRecentReadings.clear()
    resetSplSlowDisplayCache()
    resetSplCaptureReadings()
    updateSplReadout()
}

internal fun MainActivity.createOriginalSplCard(): View {
    fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = rounded(Color.rgb(15, 23, 36))
    }
    splDetailValueView = TextView(this).apply {
        text = "≈ -- dB(A)"; textSize = 34f; gravity = Gravity.CENTER
        contentDescription = "SPL reading. Tap to switch frequency weighting A, C, Z."
        setOnClickListener { selectSplWeighting(SplWeighting.entries[(splFrequencyWeighting.ordinal + 1) % SplWeighting.entries.size]) }
        setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(255, 213, 90))
        includeFontPadding = false; isSingleLine = true
        setAutoSizeTextTypeUniformWithConfiguration(16, 34, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    content.addView(splDetailValueView, LinearLayout.LayoutParams(-1, dp(42)))
    splDetailDeltaView = TextView(this).apply {
        text = "Δ 1 s   —"; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(Color.rgb(178, 192, 208)); includeFontPadding = false; isSingleLine = true
        setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    content.addView(splDetailDeltaView, LinearLayout.LayoutParams(-1, dp(19)))
    splReferenceLabel = TextView(this).apply {
        text = "—"; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(Color.rgb(178, 192, 208)); includeFontPadding = false; isSingleLine = true
        setPadding(dp(8), 0, dp(8), 0)
        setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        background = rounded(Color.rgb(25, 35, 48))
        contentDescription = "Tap to switch frequency weighting A, C, Z. Hold for reference chart."
        setOnLongClickListener { showSplReferenceChart(); true }
        setOnClickListener { selectSplWeighting(SplWeighting.entries[(splFrequencyWeighting.ordinal + 1) % SplWeighting.entries.size]) }
    }
    content.addView(splReferenceLabel, LinearLayout.LayoutParams(-2, dp(30)).apply { topMargin = dp(3) })
    fun textButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; isAllCaps = false; setTextColor(Color.WHITE)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(7), 0, dp(7), 0); stateListAnimator = null
        background = rounded(Color.rgb(25, 35, 48)); setOnClickListener { action() }
    }
    fun iconButton(icon: AnalyzerIconButton.Icon, description: String, action: () -> Unit) = AnalyzerIconButton(this, icon).apply {
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0); setTextColor(Color.WHITE)
        background = rounded(Color.rgb(25, 35, 48)); contentDescription = description
        stateListAnimator = null; setOnClickListener { action() }
    }
    val response = iconButton(AnalyzerIconButton.Icon.SPL_RESPONSE, "Switch FAST / SLOW") {
        setSplTimeWeighting(if (splTimeWeighting == "FAST") "SLOW" else "FAST")
    }
    val hold = iconButton(AnalyzerIconButton.Icon.SPL_HOLD, "Hold reading") { toggleSplHold() }
    val minimum = textButton("MIN") { toggleSplMinimum() }
    val maximum = textButton("MAX") { toggleSplMaximum() }
    splResponseButton = response; splHoldButton = hold; splMinButton = minimum; splMaxButton = maximum
    val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
    for (button in listOf(response, hold, minimum, maximum)) {
        controls.addView(button, LinearLayout.LayoutParams(if (button === response || button === hold) dp(32) else -2, dp(32)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
    }
    content.addView(controls, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(5) })
    val scale = FrameLayout(this)
    val vertical = SplScaleMeterView(this); val dial = SplArcMeterView(this)
    splScaleMeterView = vertical; splArcMeterView = dial
    scale.addView(vertical, FrameLayout.LayoutParams(dp(90), -1, Gravity.CENTER))
    scale.addView(dial, FrameLayout.LayoutParams(-1, -1))
    content.addView(scale, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
    fun showScale() {
        vertical.visibility = if (splUseArcScale) View.GONE else View.VISIBLE
        dial.visibility = if (splUseArcScale) View.VISIBLE else View.GONE
        scale.contentDescription = if (splUseArcScale) "Dial meter. Tap for vertical meter." else "Vertical meter. Tap for dial meter."
    }
    scale.setOnClickListener { splUseArcScale = !splUseArcScale; showScale() }
    showScale()
    return content
}

internal fun MainActivity.interceptSplToolbarTouch(event: MotionEvent): Boolean {
    if (event.actionMasked != MotionEvent.ACTION_UP) return false
    val hit = listOf(toneToggleButton, keyboardToggleButton, soundIdModeButton, fullScaleKeysButton)
        .filterNotNull().firstOrNull { button ->
            val bounds = android.graphics.Rect()
            button.isShown && button.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
        } ?: return false
    hit.isPressed = false; pendingSplToolbarClick = hit; splDetailDialog?.dismiss()
    return true
}

internal fun MainActivity.showSplDetailDialog() {
    fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    if (splDetailDialog?.isShowing == true || splOpenPending) return
    if (toneModeOpen || keyboardModeOpen || fullScaleKeyboardOpen || soundModeOpen || modeTransitionInProgress) {
        splOpenPending = true
        leaveFullScaleKeyboard(animate = false)
        val root = window.decorView
        root.post(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) { splOpenPending = false; return }
                if (modeTransitionInProgress) { root.postOnAnimation(this); return }
                if (toneModeOpen || keyboardModeOpen || soundModeOpen) {
                    switchAnalyzerMode?.invoke("none")
                    root.postOnAnimation(this)
                    return
                }
                // Wait for the spectrum rows to finish layout before taking their bounds.
                root.postOnAnimation {
                    splOpenPending = false
                    showSplDetailDialog()
                }
            }
        })
        return
    }
    val header = spectrumHeader ?: return
    val bottom = bottomActionBar ?: return
    val root = window.decorView
    if (isCompactAppWindow() && (
            isAppWindowPortrait() || root.width <= root.height || bottom.width <= 0 ||
                header.width < bottom.width * 0.70f
        )) {
        // Leaving Sound ID requests landscape asynchronously. Do not size this
        // graph overlay from the old portrait measurements while the window is
        // still rotating or its weighted graph column has not been laid out.
        splOpenPending = true
        val waitStartedAt = SystemClock.elapsedRealtime()
        root.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) {
                    splOpenPending = false
                    return
                }
                val layoutReady = !isAppWindowPortrait() && root.width > root.height &&
                    bottom.width > 0 && header.width >= bottom.width * 0.70f
                if (layoutReady) {
                    splOpenPending = false
                    showSplDetailDialog()
                } else if (SystemClock.elapsedRealtime() - waitStartedAt < 3_000L) {
                    root.postDelayed(this, 32L)
                } else {
                    splOpenPending = false
                    Toast.makeText(this@showSplDetailDialog, "Wait for the landscape layout, then open SPL again.", Toast.LENGTH_SHORT).show()
                }
            }
        }, 32L)
        return
    }
    if (header.width <= 0 || bottom.width <= 0 || bottom.height <= 0) return
    // Overlay exactly the graph area. Spectrum bars keep their original parents and pixels.
    val headerAt = IntArray(2); header.getLocationOnScreen(headerAt)
    val bottomAt = IntArray(2); bottom.getLocationOnScreen(bottomAt)
    val rootAt = IntArray(2); window.decorView.getLocationOnScreen(rootAt)
    val cameraInset = if (Build.VERSION.SDK_INT >= 28) {
        window.decorView.rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
    } else 0
    val safeLeft = rootAt[0] + cameraInset + dp(8)
    // Extend only the left edge by approximately 2 mm; keep the right edge fixed.
    val leftExtension = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_MM, 2f, resources.displayMetrics
    ).roundToInt()
    val areaLeft = (safeLeft - leftExtension).coerceAtLeast(rootAt[0])
        .coerceAtMost(headerAt[0] + header.width - 1)
    val areaWidth = header.width + headerAt[0] - areaLeft
    val areaTop = headerAt[1] + header.height
    val areaHeight = (bottomAt[1] - areaTop).coerceAtLeast(1)
    val dialog = createAnimatedDialog(android.R.style.Theme_Material_NoActionBar)
    val panel = SplMeterPanel(this, splTrendBuffer, createOriginalSplCard())
    splMeterPanel = panel
    data class HiddenControl(val view: View, val visibility: Int, val alpha: Float)
    val hidden = listOfNotNull(axisToggleButton, spectrogramModeButton, resetDisplayButton, rangePickerButton, headerSplText)
        .map { HiddenControl(it, it.visibility, it.alpha) }
    // Reserve the final toolbar geometry before the first visible frame.
    // The replacement controls fade in without a delayed shift when old controls disappear.
    listOfNotNull(axisToggleButton, spectrogramModeButton, resetDisplayButton).forEach {
        it.animate().cancel(); it.visibility = View.GONE
    }
    val graphControls = panel.graphControls
    val spectrumButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.SPECTRUM).apply {
        setCustomIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_spectrum_custom,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }))
        contentDescription = "Spectrum"
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0); setTextColor(Color.WHITE)
        styleFlatToolbarButton(this)
        setOnClickListener { dialog.dismiss() }
    }
    val resetParent = resetDisplayButton?.parent as? LinearLayout
    val spectrumIndex = resetParent?.indexOfChild(resetDisplayButton) ?: -1
    if (spectrumIndex >= 0) resetParent?.addView(spectrumButton, spectrumIndex,
        LinearLayout.LayoutParams(dp(48), dp(40)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
    graphControls.alpha = 0f
    if (spectrumIndex >= 0) resetParent?.addView(graphControls, spectrumIndex,
        LinearLayout.LayoutParams(-2, dp(44)))
    graphControls.postOnAnimation {
        if (splDetailDialog === dialog) graphControls.animate().alpha(1f).setDuration(240L).start()
    }
    dialog.setContentView(panel)
    dialog.setCanceledOnTouchOutside(false)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent); setDimAmount(0f)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Coordinates already include the camera clearance. Do not let WindowManager
        // add the display-cutout inset to the window position a second time.
        if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
        setGravity(Gravity.TOP or Gravity.LEFT)
        decorView.setPadding(0, 0, 0, 0)
        attributes = attributes.apply {
            x = areaLeft; y = areaTop; width = areaWidth; height = areaHeight
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        decorView.systemUiVisibility = window.decorView.systemUiVisibility
    }
    dialog.setOnDismissListener {
        splInfoClickBlockedUntilMs = SystemClock.elapsedRealtime() + 260L
        graphControls.animate().cancel()
        (graphControls.parent as? ViewGroup)?.removeView(graphControls)
        (spectrumButton.parent as? ViewGroup)?.removeView(spectrumButton)
        if (splDetailDialog === dialog) {
            splDetailDialog = null; splMeterPanel = null
            splDetailValueView = null; splDetailDeltaView = null; splScaleMeterView = null; splArcMeterView = null; splReferenceLabel = null
            splResponseButton = null; splHoldButton = null; splMinButton = null; splMaxButton = null
        }
        hidden.forEach { saved ->
            saved.view.animate().cancel(); saved.view.visibility = saved.visibility
            saved.view.alpha = if (saved.visibility == View.VISIBLE) 0f else saved.alpha
            if (saved.visibility == View.VISIBLE) saved.view.animate().alpha(saved.alpha).setDuration(180L).start()
        }
        // SPL may have been opened during the Sound ID -> landscape handoff.
        // Its old GONE state is not authoritative once Sound ID has closed.
        rangePickerButton?.visibility = if (soundModeOpen) View.GONE else View.VISIBLE
        hideSystemBars()
        val navigation = pendingSplToolbarClick; pendingSplToolbarClick = null
        if (!isFinishing && !isDestroyed) navigation?.post { navigation.performClick() }
    }
    splWaveHistory.clear()
    splDetailDialog = dialog
    splInfoClickBlockedUntilMs = SystemClock.elapsedRealtime() + 600L
    // Fill live labels before the opening animation takes its first frame.
    updateSplReadout()
    dialog.show()
    dialog.window?.apply {
        setGravity(Gravity.TOP or Gravity.LEFT)
        // Bounds are already cutout-safe; remove the theme's additional dialog insets.
        decorView.setPadding(0, 0, 0, 0)
        attributes = attributes.apply {
            x = areaLeft; y = areaTop; width = areaWidth; height = areaHeight
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        }
    }
    hidden.filter { it.visibility == View.VISIBLE }.forEach { saved ->
        saved.view.animate().cancel()
        saved.view.animate().alpha(0f).setDuration(160L).withEndAction {
            if (splDetailDialog === dialog) saved.view.visibility = if (saved.view === headerSplText) View.INVISIBLE else View.GONE
        }.start()
    }
    updateSplReadout()
}
