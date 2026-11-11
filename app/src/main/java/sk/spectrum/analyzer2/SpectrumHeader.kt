package sk.spectrum.analyzer2

import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Spectrum header readouts and frequency-range control. */
internal fun MainActivity.rangeButtonContentWidth(button: TextView): Int {
    val textWidth = kotlin.math.ceil(button.paint.measureText(button.text.toString()).toDouble()).toInt()
    val safety = (4f * resources.displayMetrics.density).roundToInt()
    return textWidth + button.compoundPaddingLeft + button.compoundPaddingRight + safety
}

internal fun MainActivity.updateSpectrumHeaderWidths() {
    val splText = headerSplText ?: return
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val safeWidth = spectrumHeader?.width?.takeIf { it > 0 } ?: availableWindowBounds().width().coerceAtLeast(1)
    val routeButtonInHeader = audioRoutePickerButton?.parent === audioRouteHeaderGroup
    val fixedLeftControls = dp((if (routeButtonInHeader) 30 else 0) + 4)
    val peakReserve = maxOf(headerPeakReadout?.width ?: 0, dp(148))
    val splWidth = (safeWidth - fixedLeftControls - peakReserve - dp(10)).coerceAtLeast(dp(48))
    // Audio updates call this frequently. Do not invalidate text layout each sample.
    if (splText.maxWidth != splWidth) splText.maxWidth = splWidth
    if (splText.maxLines != 1) splText.maxLines = 1
    if (splText.ellipsize != android.text.TextUtils.TruncateAt.END) {
        splText.ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val preservedToneSize = toneSplTextSizePx?.takeIf { toneModeOpen }
    if (preservedToneSize != null) {
        // The collapsing graph column can become zero pixels wide. It must not
        // change the SPL font chosen before expanding the generator.
        if (splText.autoSizeTextType != TextView.AUTO_SIZE_TEXT_TYPE_NONE) {
            splText.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        }
        if (kotlin.math.abs(splText.textSize - preservedToneSize) > 0.1f) {
            splText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, preservedToneSize)
        }
    } else if (splText.autoSizeTextType != TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM) {
        splText.setAutoSizeTextTypeUniformWithConfiguration(
            10, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP
        )
    }
}

internal fun MainActivity.configureSpectrumHeader() {
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
    parent.removeView(title)
    headerTitleText = null
    title.visibility = View.GONE

    val header = android.widget.FrameLayout(this).apply {
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.rgb(15, 23, 36))
    }
    spectrumHeader = header
    // Graph bounds animation may draw outside graphColumn; readouts stay above it.
    header.translationZ = dp(2).toFloat()
    header.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) updateSpectrumHeaderWidths()
    }
    // The title is intentionally removed; the peak readout remains in this header.

    fun frequencyInput(initialValue: String, description: String): EditText = EditText(this).apply {
        setText(initialValue)
        contentDescription = description
        setHintTextColor(Color.rgb(150, 160, 175))
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(5))
        setSingleLine(true)
        isFocusable = true
        isFocusableInTouchMode = true
        isCursorVisible = true
        showSoftInputOnFocus = false
        styleNumericDialogInput(this, textSizeSp = 13f, verticalPaddingDp = 0)
    }

    val minFrequencyInput = frequencyInput("20", "Minimum frequency in Hz")
    val maxFrequencyInput = frequencyInput("20000", "Maximum frequency in Hz")
    minFrequencyInput.hint = "Min Hz"
    maxFrequencyInput.hint = "Max Hz"

    var selectedRangeInput: EditText = minFrequencyInput
    fun selectRangeInput(input: EditText) {
        selectedRangeInput = input
        listOf(minFrequencyInput, maxFrequencyInput).forEach { field ->
            styleNumericDialogInput(field, textSizeSp = 13f, focused = field === input, verticalPaddingDp = 0)
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

    val rangeDialog = createAnimatedDialog()
    val rangeContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        styleNumericDialogCard(this)
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
    }, LinearLayout.LayoutParams(dp(24), dp(48)))
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
    rangeContent.addView(rangeKeypad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, dp(5), 0, dp(5))
    })

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
    val rangeInfoIcon = android.graphics.BitmapFactory.decodeResource(
        resources,
        R.drawable.ic_information_white,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
    )
    val rangeInfoButton = android.widget.ImageButton(this).apply {
        setImageBitmap(rangeInfoIcon)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = android.graphics.drawable.ColorDrawable(Color.rgb(24, 31, 42))
        backgroundTintList = null
        stateListAnimator = null
        minimumWidth = 0
        minimumHeight = 0
        contentDescription = "Frequency range information"
        setOnClickListener {
            val infoDialog = createAnimatedDialog()
            val infoCard = LinearLayout(this@configureSpectrumHeader).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                styleNumericDialogCard(this)
                setPadding(dp(18), dp(16), dp(18), dp(14))
            }
            infoCard.addView(TextView(this@configureSpectrumHeader).apply {
                text = "Enter frequencies in Hz from 0 to 20,000. The minimum must be lower than the maximum. Analysis is limited to 20 kHz with the 48 kHz sampling rate."
                textSize = 14f
                setTextColor(Color.rgb(210, 220, 232))
                gravity = Gravity.START
                justificationMode = android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD
                setLineSpacing(dp(3).toFloat(), 1f)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val infoOkButton = Button(this@configureSpectrumHeader).apply {
                text = "OK"
                textSize = 13f
                setTextColor(Color.rgb(255, 213, 90))
                setOnClickListener { infoDialog.dismiss() }
            }
            styleNumericDialogButton(infoOkButton)
            infoCard.addView(infoOkButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
            ).apply { topMargin = dp(16) })
            setDialogCardContent(infoDialog, infoCard)
            requireExplicitDialogAction(infoDialog)
            infoDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val infoDialogWidth = minOf(dp(360), (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(220)))
            centerDialogOnScreen(infoDialog, infoDialogWidth)
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
                minValue == null || maxValue == null -> Toast.makeText(this@configureSpectrumHeader, "Enter both frequencies.", Toast.LENGTH_SHORT).show()
                minValue !in 0..20000 || maxValue !in 0..20000 -> Toast.makeText(this@configureSpectrumHeader, "Frequencies must be between 0 and 20000 Hz.", Toast.LENGTH_SHORT).show()
                minValue >= maxValue -> Toast.makeText(this@configureSpectrumHeader, "The minimum frequency must be lower than the maximum.", Toast.LENGTH_SHORT).show()
                maxValue <= 20 -> Toast.makeText(this@configureSpectrumHeader, "The maximum frequency must be above 20 Hz.", Toast.LENGTH_SHORT).show()
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
    setDialogCardContent(rangeDialog, rangeContent)
    requireExplicitDialogAction(rangeDialog)
    rangeDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    val initialRange = spectrumView.getVisibleFrequencyRangeHz()
    val rangeButton = Button(this).apply {
        text = "(${initialRange.first}–${initialRange.second} Hz)"
        textSize = 14f
        setSingleLine(true)
        maxLines = 1
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
            centerDialogOnScreen(rangeDialog, dp(260), height = dp(360))
            rangeDialog.show()
        }
    }
    rangePickerButton = rangeButton
    styleXButton(rangeButton, primary = false)
    styleFlatToolbarButton(rangeButton)
    rangeButton.isAllCaps = false
    rangeButton.setTextColor(Color.rgb(255, 213, 90))
    rangeButton.textSize = 14f
    val rangeButtonWidth = rangeButtonContentWidth(rangeButton)
    rangeButton.layoutParams = android.widget.FrameLayout.LayoutParams(rangeButtonWidth, dp(42), Gravity.CENTER)
    spectrumView.setFrequencyRangeListener { minHz, maxHz ->
        rangeButton.text = "($minHz–$maxHz Hz)"
        val width = rangeButtonContentWidth(rangeButton)
        if (rangeButton.scrollX != 0) rangeButton.scrollTo(0, 0)
        val params = rangeButton.layoutParams
        params.width = width
        when (params) {
            is android.widget.FrameLayout.LayoutParams -> params.gravity = Gravity.CENTER
            is LinearLayout.LayoutParams -> params.gravity = Gravity.CENTER_VERTICAL
        }
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
        setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now < splInfoClickBlockedUntilMs || splOpenPending || modeTransitionInProgress) return@setOnClickListener
            // Opening zoom + live-frame blend + the same 80 ms margin as mode switches.
            splInfoClickBlockedUntilMs = now + 600L
            if (splDetailDialog?.isShowing == true) splDetailDialog?.dismiss() else showSplDetailDialog()
        }
    }
    val appMenuButton = android.widget.ImageButton(this).apply {
        setImageResource(R.drawable.ic_menu)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = android.graphics.drawable.ColorDrawable(Color.rgb(15, 23, 36))
        backgroundTintList = null
        stateListAnimator = null
        minimumWidth = 0
        minimumHeight = 0
        contentDescription = "Open menu"
        setOnClickListener { showAudioRouteDialog() }
    }
    audioRoutePickerButton = appMenuButton
    updateSplReadout()
    peakReadout.addView(headerPeakFrequencyText)
    peakReadout.addView(headerPeakDbText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(7)
    })
    val splHeaderGroup = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }
    audioRouteHeaderGroup = splHeaderGroup
    splHeaderGroup.addView(appMenuButton, LinearLayout.LayoutParams(dp(30), dp(26)).apply {
        marginEnd = dp(3)
    })
    splHeaderGroup.addView(headerSplText, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight
    ))
    // Anchor the readouts to the top edge. KEYS gives the header extra height
    // for the centered RANGE button; this keeps SPL and peak text from shifting.
    header.addView(splHeaderGroup, android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight, Gravity.TOP or Gravity.START
    ))
    header.addView(peakReadout, android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight, Gravity.TOP or Gravity.END
    ))
    headerPeakReadout = peakReadout

    // Reserve the same header space at launch as in TONE and KEYS.
    // Keep the readout cells at headerHeight so their text stays anchored to the top.
    oldParams.height = maxOf(headerHeight, dp(42))
    parent.addView(header, index, oldParams)
    updateSpectrumHeaderWidths()
}
