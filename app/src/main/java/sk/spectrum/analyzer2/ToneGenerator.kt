package sk.spectrum.analyzer2

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import kotlin.math.roundToInt

/** Tone generator controls, sweep settings, expansion and compact/expanded panel layout. */
internal fun MainActivity.freezeToneControlRowWidths(freeze: Boolean) {
    if (freeze) {
        if (toneOriginalRowLayouts.isNotEmpty()) return
        toneFixedControlRows.forEach { row ->
            val params = row.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            val owner = row.parent as? View ?: return@forEach
            val width = row.width.takeIf { it > 0 }
                ?: (owner.width - owner.paddingLeft - owner.paddingRight).coerceAtLeast(1)
            toneOriginalRowLayouts[row] = params.width to params.gravity
            params.width = width
            params.gravity = Gravity.CENTER_HORIZONTAL
            row.layoutParams = params
        }
    } else {
        toneOriginalRowLayouts.forEach { (row, original) ->
            (row.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = original.first; params.gravity = original.second
                row.layoutParams = params
            }
        }
        toneOriginalRowLayouts.clear()
    }
}

internal fun MainActivity.isNoiseTone(): Boolean = toneWaveform == "White noise" || toneWaveform == "Pink noise"

internal fun MainActivity.createToneGeneratorPanel(): View {
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
        maxLines = 1
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
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
        contentDescription = "Frequency presets and PEAK tracking settings"
        background = GradientDrawable().apply {
            setColor(Color.rgb(22, 32, 45))
            cornerRadius = dp(11).toFloat()
            setStroke(dp(1), Color.rgb(56, 73, 91))
        }
        stateListAnimator = null
    }
    tonePeakPresetButton = presetsButton
    header.addView(presetsButton, LinearLayout.LayoutParams(dp(38), dp(40)).apply {
        marginEnd = dp(3)
    })
    val valueLabelParams = LinearLayout.LayoutParams(dp(96), dp(40))
    header.addView(valueLabel, valueLabelParams.apply {
        marginEnd = dp(3)
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
        contentDescription = "$toneWaveform selected. Tap to choose a waveform or noise type."
        addView(selectedWaveformIcon, android.widget.FrameLayout.LayoutParams(dp(44), dp(34), Gravity.CENTER))
    }
    toneWaveformButton = waveCell
    header.addView(waveCell, LinearLayout.LayoutParams(dp(50), dp(40)).apply { marginEnd = dp(3) })
    val playButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.PLAY).apply {
        usePlayStopImages = true
        setPlayStopImages(
            decodePlayStopBitmap(R.drawable.ic_tone_play),
            decodePlayStopBitmap(R.drawable.ic_tone_stop)
        )
        contentDescription = "Play selected tone"
        background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        backgroundTintList = null
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
    header.addView(playButton, LinearLayout.LayoutParams(dp(42), dp(42)))
    panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    waveCell.setOnClickListener {
        val waveforms = arrayOf("Sine", "Square", "Triangle", "Sawtooth", "White noise", "Pink noise")
        val picker = createAnimatedDialog()
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
                    addView(ToneWaveformIconView(this@createToneGeneratorPanel, waveform), android.widget.FrameLayout.LayoutParams(dp(50), dp(28), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(2) })
                    addView(TextView(this@createToneGeneratorPanel).apply {
                        text = waveform
                        textSize = 11f
                        setTextColor(Color.rgb(225, 233, 243))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16), Gravity.BOTTOM).apply {
                        leftMargin = dp(2)
                        rightMargin = dp(2)
                        bottomMargin = dp(1)
                    })
                    setOnClickListener {
                        setTonePeakFollowEnabled(false, refreshUi = false)
                        toneWaveform = waveform
                        selectedWaveformIcon.waveform = waveform
                        waveCell.contentDescription = "$waveform selected. Tap to choose a waveform or noise type."
                        toneSweepUiUpdate?.invoke()
                        setTonePlayButtonPlaying(tonePlaying)
                        picker.dismiss()
                    }
                }
                row.addView(option, LinearLayout.LayoutParams(dp(72), dp(56)).apply {
                    if (columnIndex > 0) marginStart = dp(4)
                    if (columnIndex == 0) marginEnd = dp(4)
                })
            }
            pickerContent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply {
                if (pickerContent.childCount > 1) topMargin = dp(5)
            })
        }
        picker.setContentView(pickerContent)
        picker.window?.setBackgroundDrawableResource(android.R.color.transparent)
        centerDialogOnScreen(picker, dp(184))
        picker.show()
    }

    // Keep tone and sweep controls grouped below the selector row.
    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(1), dp(2), 0)
        clipChildren = false
        clipToPadding = false
    }
    // Fill the available panel height so the output row can stay at the bottom.
    panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
        val presetDialog = createAnimatedDialog()
        val presetContent = LinearLayout(this@createToneGeneratorPanel).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 31, 42))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(49, 66, 83))
            }
        }
        val presetGrid = LinearLayout(this@createToneGeneratorPanel).apply {
            orientation = LinearLayout.VERTICAL
        }
        for ((rowIndex, rowValues) in presetItems.chunked(3).withIndex()) {
            val row = LinearLayout(this@createToneGeneratorPanel).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowValues.forEachIndexed { columnIndex, item ->
                val frequency = item.first
                val isCustom = item.second
                val label = frequency?.let {
                    if (it >= 1000 && it % 1000 == 0) "${it / 1000} kHz" else "$it Hz"
                } ?: "PEAK"
                val presetButton = Button(this@createToneGeneratorPanel).apply {
                    text = label
                    textSize = 13f
                    isAllCaps = false
                    minHeight = dp(42)
                    minimumHeight = dp(42)
                    contentDescription = when {
                          frequency == null -> "Choose PEAK detection speed and how long to keep the last frequency."
                        isCustom -> "Set tone frequency to $frequency hertz. Long-press to delete this custom preset."
                        else -> "Set tone frequency to $frequency hertz"
                    }
                    setOnClickListener {
                        cancelToneSweep()
                        if (frequency == null) {
                              showTonePeakSettingsDialog(presetDialog)
                              return@setOnClickListener
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
                row.addView(View(this@createToneGeneratorPanel), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (columnIndex > 0) marginStart = dp(3)
                    if (columnIndex < 2) marginEnd = dp(3)
                })
            }
            presetGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                if (rowIndex > 0) topMargin = dp(5)
            })
        }
        val presetScroll = android.widget.ScrollView(this@createToneGeneratorPanel).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = presetItems.size > 15
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(presetGrid)
        }
        val visibleRows = ((presetItems.size + 2) / 3).coerceAtMost(5)
        val visibleGridHeight = dp(visibleRows * 42 + (visibleRows - 1).coerceAtLeast(0) * 5)
        presetContent.addView(presetScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, visibleGridHeight))
        val addPresetButton = Button(this@createToneGeneratorPanel).apply {
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
        val closeButton = Button(this@createToneGeneratorPanel).apply {
            text = "CANCEL"
            textSize = 11f
            setOnClickListener { presetDialog.dismiss() }
        }
        styleNumericDialogButton(closeButton)
        closeButton.setTextColor(Color.rgb(225, 233, 243))
        presetContent.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            topMargin = dp(7)
        })
        setDialogCardContent(presetDialog, presetContent)
        requireExplicitDialogAction(presetDialog)
        presetDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        centerDialogOnScreen(presetDialog, dp(260), height = dp(360))
        presetDialog.show()
    }
    val sweepButton = utilityIcon(AnalyzerIconButton.Icon.SWEEP, "Play frequency sweep")
    toneSweepButton = sweepButton
    applyToneSweepButtonStyle(active = false)
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
                toneSweepUiUpdate?.invoke()
                toneSweepRangeButton?.isEnabled = false
                toneSweepDurationButton?.isEnabled = false
                toneSweepDirectionButton?.isEnabled = false
                toneSweepScaleButton?.isEnabled = false
                toneDtmfModeButton?.isEnabled = false
                toneWaveformButton?.isEnabled = false
                sweepButton.contentDescription = "Stop frequency sweep"
                applyToneSweepButtonStyle(active = true)
                setTonePlayButtonPlaying(true)
                toneSweepHandler.post(toneSweepRunnable)
            }
        }
    }
    fun formatSweepFrequency(frequency: Int): String = when {
        frequency >= 1000 && frequency % 1000 == 0 -> "${frequency / 1000} kHz"
        frequency >= 1000 -> {
            val roundedKHz = String.format(java.util.Locale.US, "%.1f", frequency / 1000.0)
            "${roundedKHz.removeSuffix(".0")} kHz"
        }
        else -> "$frequency Hz"
    }
    fun styleSweepSettingButton(button: Button) {
        button.isAllCaps = false
        button.textSize = 12f
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = dp(36)
        button.minimumHeight = dp(36)
        button.setPadding(dp(4), 0, dp(4), 0)
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
        background = GradientDrawable().apply {
            setColor(Color.rgb(15, 23, 36))
            cornerRadius = dp(11).toFloat()
            setStroke(dp(1), Color.rgb(49, 66, 83))
        }
    }
    val sweepRangeButton = Button(this).apply {
        text = "${formatSweepFrequency(toneSweepStartFrequency)}→${formatSweepFrequency(toneSweepEndFrequency)}"
        contentDescription = "Frequency sweep from $toneSweepStartFrequency to $toneSweepEndFrequency Hz. Tap to change."
        maxLines = 1
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    styleSweepSettingButton(sweepRangeButton)
    sweepRangeButton.setTextColor(Color.rgb(255, 213, 90))
    sweepRangeButton.minWidth = 0
    sweepRangeButton.minimumWidth = 0
    sweepRangeButton.setPadding(dp(2), 0, dp(2), 0)
    sweepRangeButton.setAutoSizeTextTypeUniformWithConfiguration(
        9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP
    )
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
        val rangeDialog = createAnimatedDialog()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            styleNumericDialogCard(this)
        }
        fun sweepFrequencyInput(initial: Int): EditText = EditText(this).apply {
            setText(initial.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            isSingleLine = true
            showSoftInputOnFocus = false
            isFocusableInTouchMode = true
            isCursorVisible = true
            styleNumericDialogInput(this)
        }
        val sweepStartInput = sweepFrequencyInput(toneSweepStartFrequency)
        val sweepEndInput = sweepFrequencyInput(toneSweepEndFrequency)
        sweepStartInput.contentDescription = "Sweep start frequency in hertz"
        sweepEndInput.contentDescription = "Sweep end frequency in hertz"
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fields.addView(sweepStartInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        fields.addView(TextView(this).apply {
            text = "→"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 213, 90))
        }, LinearLayout.LayoutParams(dp(30), dp(48)))
        fields.addView(sweepEndInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(fields, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        var activeInput = sweepStartInput
        fun updateInputFocus(input: EditText, hasFocus: Boolean) {
            if (hasFocus) activeInput = input
            styleNumericDialogInput(input, focused = hasFocus)
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
            topMargin = dp(5)
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
                    startHz == null || endHz == null -> Toast.makeText(this@createToneGeneratorPanel, "Enter both frequencies.", Toast.LENGTH_SHORT).show()
                    startHz !in 20..19999 || endHz !in 21..20000 -> Toast.makeText(this@createToneGeneratorPanel, "Use frequencies from 20 to 20,000 Hz.", Toast.LENGTH_SHORT).show()
                    startHz >= endHz -> Toast.makeText(this@createToneGeneratorPanel, "The end frequency must be higher than the start.", Toast.LENGTH_SHORT).show()
                    else -> {
                        toneSweepStartFrequency = startHz
                        toneSweepEndFrequency = endHz
                        sweepRangeButton.text = "${formatSweepFrequency(startHz)}→${formatSweepFrequency(endHz)}"
                        sweepRangeButton.contentDescription = "Frequency sweep from $startHz to $endHz Hz. Tap to change."
                        rangeDialog.dismiss()
                    }
                }
            }
        }
        for (button in listOf(cancelButton, applyButton)) {
            styleNumericDialogButton(button)
            button.setTextColor(Color.rgb(255, 213, 90))
            actions.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        setDialogCardContent(rangeDialog, content)
        requireExplicitDialogAction(rangeDialog)
        rangeDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        centerDialogOnScreen(rangeDialog, dp(260), height = dp(360))
        rangeDialog.show()
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
    val sweepScaleButton = Button(this).apply {
        text = if (toneSweepLogarithmic) "LOG" else "LIN"
        contentDescription = if (toneSweepLogarithmic) {
            "Logarithmic sweep spacing. Tap to switch to linear spacing."
        } else {
            "Linear sweep spacing. Tap to switch to logarithmic spacing."
        }
    }
    styleSweepSettingButton(sweepScaleButton)
    sweepScaleButton.textSize = 11f
    sweepScaleButton.setTextColor(Color.rgb(255, 213, 90))
    toneSweepScaleButton = sweepScaleButton
    sweepScaleButton.setOnClickListener {
        if (!toneSweepActive) {
            toneSweepLogarithmic = !toneSweepLogarithmic
            sweepScaleButton.text = if (toneSweepLogarithmic) "LOG" else "LIN"
            sweepScaleButton.contentDescription = if (toneSweepLogarithmic) {
                "Logarithmic sweep spacing. Tap to switch to linear spacing."
            } else {
                "Linear sweep spacing. Tap to switch to logarithmic spacing."
            }
        }
    }
    val sweepRangeGroup = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    sweepRangeGroup.addView(sweepRangeButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
    sweepRangeGroup.addView(sweepDirectionButton, LinearLayout.LayoutParams(dp(32), dp(36)))
    sweepSettingsRow.addView(sweepButton, LinearLayout.LayoutParams(dp(38), dp(36)).apply { marginEnd = dp(4) })
    sweepSettingsRow.addView(sweepRangeGroup, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
    sweepSettingsRow.addView(sweepScaleButton, LinearLayout.LayoutParams(dp(42), dp(36)).apply { marginEnd = dp(4) })
    sweepSettingsRow.addView(sweepDurationButton, LinearLayout.LayoutParams(dp(46), dp(36)))
    controls.addView(sweepSettingsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply {
        topMargin = dp(1)
        bottomMargin = dp(1)
    })
    val signalPreview = ToneSignalPreviewView(this, sampleRate, tonePreviewPcmFrame).apply {
        waveform = toneWaveform
        frequency = toneFrequency
        volume = toneVolume.toFloat()
        playing = tonePlaying
        sweepActive = toneSweepActive
    }
    toneSignalPreview = signalPreview
    // Keep the oscilloscope full width; the timebase selector sits centered beneath it.
    controls.addView(signalPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.25f).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    })
    signalPreview.onVolumeChanged = { selectedVolume ->
        toneVolume = selectedVolume.toDouble()
    }

    var selectedScopeTimebaseMs: Int? = signalPreview.manualTimebaseMs
    val scopeTimebaseButton = Button(this).apply {
        val bitmap = assets.open("icons/scope_time.png").use {
            android.graphics.BitmapFactory.decodeStream(it, null,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
        }
        val icon = android.graphics.drawable.BitmapDrawable(resources, bitmap).apply {
            val w = dp(28)
            val h = if (bitmap != null && bitmap.width > 0) (w * bitmap.height.toFloat() / bitmap.width).roundToInt() else dp(20)
            setBounds(0, 0, w, h)
        }
        setCompoundDrawables(icon, null, null, null)
        compoundDrawablePadding = dp(5)
        setPadding(dp(6), 0, dp(6), 0)
        isSingleLine = true
        text = selectedScopeTimebaseMs?.let { "$it ms" } ?: "AUTO · 3 cyc"
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(28)
        minimumHeight = dp(28)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.rgb(225, 233, 243))
        background = GradientDrawable().apply {
            setColor(Color.rgb(15, 23, 36))
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), Color.rgb(67, 91, 119))
        }
        stateListAnimator = null
        contentDescription = "Automatic oscilloscope time scale, about three cycles. Tap to change."
    }
    fun refreshScopeTimebaseLabel() {
        val manualTimebase = selectedScopeTimebaseMs
        val label = when {
            manualTimebase != null -> "$manualTimebase ms"
            signalPreview.dtmfMode -> "AUTO · 3 cyc"
            isNoiseTone() -> "AUTO · 20 ms"
            else -> "AUTO · 3 cyc"
        }
        if (scopeTimebaseButton.text.toString() != label) scopeTimebaseButton.text = label
        scopeTimebaseButton.contentDescription = when {
            manualTimebase != null -> "Oscilloscope shows $manualTimebase milliseconds across the display. Tap to change."
            signalPreview.dtmfMode -> "Automatic oscilloscope time scale, about three cycles of the higher DTMF tone. Tap to change."
            isNoiseTone() -> "Automatic oscilloscope time scale, 20 milliseconds across the display. Tap to change."
            else -> "Automatic oscilloscope time scale, about three tone cycles. Tap to change."
        }
    }
    refreshScopeTimebaseLabel()
    val scopeTimebaseRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false; clipToPadding = false
    }
    scopeTimebaseRow.addView(scopeTimebaseButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
    val triggerButton = Button(this).apply {
        text = "TRIG"; textSize = 10f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(4), 0, dp(4), 0); setTextColor(Color.rgb(255, 213, 90))
        contentDescription = "Zero crossing trigger on. Tap to switch. Pinch graph to zoom time."
        styleNumericDialogButton(this)
        setOnClickListener {
            signalPreview.triggerEnabled = !signalPreview.triggerEnabled
            setTextColor(if (signalPreview.triggerEnabled) Color.rgb(255, 213, 90) else Color.rgb(143, 158, 175))
            contentDescription = "Zero crossing trigger ${if (signalPreview.triggerEnabled) "on" else "off"}. Tap to switch."
        }
    }
    scopeTimebaseRow.addView(triggerButton, LinearLayout.LayoutParams(dp(48), dp(28)).apply { leftMargin = dp(4) })
    val reconstructionButton = Button(this).apply {
        text = "SINC"; textSize = 10f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        styleNumericDialogButton(this); setPadding(dp(3), 0, dp(3), 0)
        setTextColor(Color.rgb(255, 213, 90))
        contentDescription = "Band-limited reconstruction on. Blue dots are original PCM samples. Tap for raw samples."
        setOnClickListener {
            signalPreview.reconstructionEnabled = !signalPreview.reconstructionEnabled
            text = if (signalPreview.reconstructionEnabled) "SINC" else "PCM"
            setTextColor(if (signalPreview.reconstructionEnabled) Color.rgb(255, 213, 90) else Color.rgb(93, 173, 213))
            contentDescription = if (signalPreview.reconstructionEnabled) "Reconstructed curve and original PCM samples. Tap for raw samples." else "Original PCM samples. Tap for reconstructed curve."
        }
    }
    scopeTimebaseRow.addView(reconstructionButton, LinearLayout.LayoutParams(dp(42), dp(28)).apply { leftMargin = dp(4) })
    val expansionArrow = object : android.graphics.drawable.Drawable() {
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            val cy = bounds.exactCenterY()
            val start = bounds.left + bounds.width() * 0.28f
            val end = bounds.left + bounds.width() * 0.72f
            val tip = if (toneExpanded) end else start
            val tail = if (toneExpanded) start else end
            val shoulder = if (toneExpanded) tip - dp(6) else tip + dp(6)
            canvas.drawLine(tail, cy, tip, cy, arrowPaint)
            canvas.drawLine(shoulder, cy - dp(6), tip, cy, arrowPaint)
            canvas.drawLine(shoulder, cy + dp(6), tip, cy, arrowPaint)
        }
        override fun setAlpha(alpha: Int) { arrowPaint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { arrowPaint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = dp(36)
        override fun getIntrinsicHeight(): Int = dp(28)
    }
    val expandButton = androidx.appcompat.widget.AppCompatImageButton(this).apply {
        setImageDrawable(expansionArrow)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        imageTintList = null
        contentDescription = "Expand tone generator to the left"
        setPadding(0, 0, 0, 0); minimumWidth = 0; minimumHeight = 0
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.rgb(38, 54, 70)),
            android.graphics.drawable.ColorDrawable(Color.rgb(19, 29, 43)), null)
        setOnClickListener { toggleToneExpanded?.invoke() }
    }
    toneExpandButton = expandButton
    scopeTimebaseRow.addView(expandButton, 0, LinearLayout.LayoutParams(dp(36), dp(28)).apply { rightMargin = dp(6) })
    controls.addView(scopeTimebaseRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply {
        topMargin = dp(1)
    })
    scopeTimebaseButton.setOnClickListener {
        val dialog = createAnimatedDialog()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            styleNumericDialogCard(this)
        }
        content.addView(TextView(this).apply {
            text = "Oscilloscope time scale"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(9))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val timebaseGrid = android.widget.GridLayout(this).apply {
            columnCount = 3
            rowCount = 3
            useDefaultMargins = false
        }
        val timebaseOptions = listOf<Int?>(null, 1, 2, 5, 10, 20, 50, 100, 200)
        timebaseOptions.forEachIndexed { index, milliseconds ->
            val label = milliseconds?.let { "$it ms" } ?: "AUTO"
            val optionButton = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(38)
                minimumHeight = dp(38)
                setPadding(dp(3), 0, dp(3), 0)
                setTextColor(Color.rgb(225, 233, 243))
                stateListAnimator = null
                setOnClickListener {
                    selectedScopeTimebaseMs = milliseconds
                    signalPreview.manualTimebaseMs = milliseconds
                    refreshScopeTimebaseLabel()
                    dialog.dismiss()
                }
            }
            styleNumericDialogButton(optionButton)
            if (milliseconds == selectedScopeTimebaseMs) {
                optionButton.background = GradientDrawable().apply {
                    setColor(Color.rgb(29, 105, 145))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.rgb(93, 173, 213))
                }
            }
            timebaseGrid.addView(optionButton, android.widget.GridLayout.LayoutParams().apply {
                width = dp(72)
                height = dp(38)
                setMargins(dp(3), dp(3), dp(3), dp(3))
                rowSpec = android.widget.GridLayout.spec(index / 3)
                columnSpec = android.widget.GridLayout.spec(index % 3)
            })
        }
        content.addView(timebaseGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val closeButton = Button(this).apply {
            text = "CLOSE"
            textSize = 11f
            setTextColor(Color.rgb(225, 233, 243))
            setOnClickListener { dialog.dismiss() }
        }
        styleNumericDialogButton(closeButton)
        content.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(7)
        })
        dialog.setContentView(content)
        requireExplicitDialogAction(dialog)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        centerDialogOnScreen(dialog, dp(280))
        dialog.show()
    }

    var dtmfModeEnabled = false
    val dtmfModeButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.DTMF).apply {
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(38)
        minimumHeight = dp(38)
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
    }
    toneDtmfModeButton = dtmfModeButton

    val outputChannelPreferences = getSharedPreferences("tone_generator", Context.MODE_PRIVATE)
    toneOutputChannel = outputChannelPreferences.getString("output_channel", "Stereo")
        ?.takeIf { it in setOf("Left", "Right", "Stereo") } ?: "Stereo"
    val outputChannelRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
    }
    val outputChannelButtons = linkedMapOf<String, AnalyzerIconButton>()
    fun refreshDtmfModeButton() {
        dtmfModeButton.background = GradientDrawable().apply {
            setColor(if (dtmfModeEnabled) Color.rgb(39, 113, 147) else Color.rgb(22, 32, 45))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), if (dtmfModeEnabled) Color.rgb(93, 173, 213) else Color.rgb(56, 73, 91))
        }
        dtmfModeButton.contentDescription = if (dtmfModeEnabled) {
            "Return to single tone controls"
        } else {
            "Open DTMF two-tone keypad"
        }
    }
    refreshDtmfModeButton()
    outputChannelRow.addView(dtmfModeButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
        marginEnd = dp(4)
    })
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
    listOf(
        "Left" to AnalyzerIconButton.Icon.OUTPUT_LEFT,
        "Right" to AnalyzerIconButton.Icon.OUTPUT_RIGHT,
        "Stereo" to AnalyzerIconButton.Icon.OUTPUT_STEREO
    ).forEachIndexed { index, (channel, channelIcon) ->
        val channelButton = AnalyzerIconButton(this, channelIcon).apply {
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(38)
            minimumHeight = dp(38)
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
            setOnClickListener {
                toneOutputChannel = channel
                outputChannelPreferences.edit().putString("output_channel", channel).apply()
                refreshOutputChannelButtons()
            }
        }
        outputChannelButtons[channel] = channelButton
        outputChannelRow.addView(channelButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            if (index > 0) marginStart = dp(4)
        })
    }
    refreshOutputChannelButtons()

    val dtmfGrid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(0, dp(2), 0, dp(2))
        clipChildren = false
        clipToPadding = false
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
    fun updateDtmfFrequencyCell() {
        valueLabel.text = "DTMF ${toneDtmfSelectedKey} · ${toneDtmfLowFrequency}+${toneDtmfHighFrequency} Hz"
        valueLabel.textSize = 12f
        valueLabel.contentDescription = "DTMF ${toneDtmfSelectedKey}, ${toneDtmfLowFrequency} and ${toneDtmfHighFrequency} hertz"
        signalPreview.dtmfLowFrequency = toneDtmfLowFrequency
        signalPreview.dtmfHighFrequency = toneDtmfHighFrequency
        signalPreview.dtmfMode = dtmfModeEnabled
        refreshScopeTimebaseLabel()
    }
    var refreshDtmfKeySelection: () -> Unit = {}
    dtmfKeys.forEachIndexed { rowIndex, rowKeys ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        rowKeys.forEachIndexed { columnIndex, key ->
            val keyButton = Button(this).apply {
                text = key
                textSize = 16f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(28)
                minimumHeight = dp(28)
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
                    updateDtmfFrequencyCell()
                    refreshDtmfKeySelection()
                }
            }
            keyButton.contentDescription = "DTMF $key: ${dtmfLowFrequencies[rowIndex]} and ${dtmfHighFrequencies[columnIndex]} hertz"
            dtmfKeyButtons[key] = keyButton
            row.addView(keyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(3), dp(1), dp(3), dp(1))
            })
        }
        dtmfGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
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
    refreshDtmfKeySelection()
    controls.addView(dtmfGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // Keep DTMF and output channel buttons at the bottom of the whole panel,
    // outside the DTMF keypad area.
    controls.addView(outputChannelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
        topMargin = dp(6)
        bottomMargin = dp(2)
    })

    val expandedFooter = FrameLayout(this).apply {
        clipChildren = false; clipToPadding = false
    }
    val defaultHeaderParams = LinearLayout.LayoutParams(header.layoutParams as LinearLayout.LayoutParams)
    val defaultArrowParams = LinearLayout.LayoutParams(expandButton.layoutParams as LinearLayout.LayoutParams)
    var dockExpanded = false
    configureExpandedToneControls = configure@{ expanded ->
        if (expanded == dockExpanded) return@configure
        dockExpanded = expanded
        val moving = listOf<View>(header, signalPreview, scopeTimebaseButton, triggerButton, reconstructionButton, expandButton)
        val oldBounds = moving.associateWith { view ->
            val at = IntArray(2); view.getLocationOnScreen(at)
            android.graphics.RectF(at[0].toFloat(), at[1].toFloat(),
                at[0] + view.width * view.scaleX, at[1] + view.height * view.scaleY)
        }
        moving.forEach { it.animate().cancel() }
        fun detach(view: View) { (view.parent as? ViewGroup)?.removeView(view) }
        val headerWidth = header.layoutParams.width.takeIf { it > 0 } ?: header.width.coerceAtLeast(1)
        detach(header); detach(expandButton)
        if (expanded) {
            sweepSettingsRow.visibility = View.GONE
            outputChannelRow.visibility = View.GONE
            dtmfGrid.visibility = View.GONE
            val headerHeight = header.height.coerceAtLeast(dp(42))
            val availableWidth = controls.width - controls.paddingLeft - controls.paddingRight
            // On narrow screens the corner arrow gets a separate line to avoid overlap.
            val footerHeight = headerHeight + if (availableWidth - headerWidth < dp(84)) dp(30) else 0
            expandedFooter.addView(header, FrameLayout.LayoutParams(headerWidth, headerHeight,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            expandedFooter.addView(expandButton, FrameLayout.LayoutParams(dp(36), dp(28),
                Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(2) })
            val timeRowIndex = controls.indexOfChild(scopeTimebaseRow)
            controls.addView(expandedFooter, timeRowIndex + 1,
                LinearLayout.LayoutParams(-1, footerHeight).apply { topMargin = dp(3) })
        } else {
            detach(expandedFooter)
            panel.addView(header, 0, LinearLayout.LayoutParams(defaultHeaderParams).apply {
                width = headerWidth; gravity = Gravity.CENTER_HORIZONTAL
            })
            scopeTimebaseRow.addView(expandButton, 0, LinearLayout.LayoutParams(defaultArrowParams))
            sweepSettingsRow.visibility = if (dtmfModeEnabled) View.GONE else View.VISIBLE
            outputChannelRow.visibility = View.VISIBLE
            dtmfGrid.visibility = if (dtmfModeEnabled) View.VISIBLE else View.GONE
        }
        toneExpandButton?.isEnabled = false
        panel.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (panel.viewTreeObserver.isAlive) panel.viewTreeObserver.removeOnPreDrawListener(this)
                moving.forEach { view ->
                    if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@forEach
                    val old = oldBounds.getValue(view)
                    val at = IntArray(2); view.getLocationOnScreen(at)
                    view.pivotX = 0f; view.pivotY = 0f
                    view.translationX = old.left - at[0]; view.translationY = old.top - at[1]
                    if (view === signalPreview) {
                        view.scaleX = old.width() / view.width
                        view.scaleY = old.height() / view.height
                    }
                    view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(320L).setStartDelay(0L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                        .setListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                view.translationX = 0f; view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
                                view.animate().setListener(null)
                                if (view === signalPreview && toneExpansionAnimator?.isRunning != true) toneExpandButton?.isEnabled = true
                            }
                        }).start()
                }
                return true
            }
        })
        panel.requestLayout()
    }

    // Only control rows keep their compact width; the oscilloscope fills the panel.
    toneFixedControlRows.clear()
    toneFixedControlRows.addAll(listOf(header, sweepSettingsRow, dtmfGrid, outputChannelRow))

    fun updateDtmfMode(enabled: Boolean) {
        if (enabled == dtmfModeEnabled) return
        if (enabled) setTonePeakFollowEnabled(false, refreshUi = false)
        if (toneSweepActive) cancelToneSweep()
        if (tonePlaying) stopTone()
        dtmfModeEnabled = enabled
        toneDtmfActive = enabled
        valueLabelParams.width = if (enabled) dp(180) else dp(96)
        if (enabled) updateDtmfFrequencyCell() else {
            valueLabel.text = "$toneFrequency Hz"
            valueLabel.textSize = 18f
            valueLabel.contentDescription = if (tonePeakFollowEnabled) {
                "Peak hold: $toneFrequency hertz. The strongest detected tonal peak refreshes every five seconds and remains selected if it disappears."
            } else "Select tone frequency"
        }
        valueLabel.requestLayout()
        valueLabel.isClickable = !enabled
        valueLabel.isEnabled = true
        presetsButton.visibility = if (enabled) View.GONE else View.VISIBLE
        waveCell.visibility = if (enabled) View.GONE else View.VISIBLE
        sweepSettingsRow.visibility = if (enabled || dockExpanded) View.GONE else View.VISIBLE
        dtmfGrid.visibility = if (enabled && !dockExpanded) View.VISIBLE else View.GONE
        refreshDtmfModeButton()
        resetTonePlayButton()
        toneSweepUiUpdate?.invoke()
    }
    dtmfModeButton.setOnClickListener { updateDtmfMode(!dtmfModeEnabled) }

    fun updateToneControlsFromScope() {
        signalPreview.waveform = toneWaveform
        signalPreview.frequency = toneFrequency
        signalPreview.volume = toneVolume.toFloat()
        signalPreview.playing = tonePlaying
        signalPreview.sweepActive = toneSweepActive
        signalPreview.dtmfMode = dtmfModeEnabled
        signalPreview.dtmfLowFrequency = toneDtmfLowFrequency
        signalPreview.dtmfHighFrequency = toneDtmfHighFrequency
        refreshScopeTimebaseLabel()
        val low = toneMinFrequency.coerceIn(20, 19999)
        val high = toneMaxFrequency.coerceIn(low + 1, 20000)
        signalPreview.minimumFrequency = low
        signalPreview.maximumFrequency = high
        val noiseSelected = isNoiseTone()
        val selectedToneControlsEnabled = !noiseSelected && !toneSweepActive && !dtmfModeEnabled
        signalPreview.frequencyControlEnabled = selectedToneControlsEnabled
        // The scope remains touchable in every mode so vertical drags can adjust volume.
        signalPreview.isEnabled = true
        if (dtmfModeEnabled) {
            valueLabel.isEnabled = true
            presetsButton.isEnabled = false
            sweepButton.isEnabled = false
            toneWaveformButton?.isEnabled = false
            toneSweepRangeButton?.isEnabled = false
            toneSweepDurationButton?.isEnabled = false
            toneSweepDirectionButton?.isEnabled = false
            toneSweepScaleButton?.isEnabled = false
            toneDtmfModeButton?.isEnabled = !toneSweepActive
            return
        }
        valueLabel.isEnabled = !noiseSelected
        presetsButton.isEnabled = !noiseSelected
        sweepButton.isEnabled = !noiseSelected
        toneWaveformButton?.isEnabled = !toneSweepActive
        toneSweepRangeButton?.isEnabled = selectedToneControlsEnabled
        toneSweepDurationButton?.isEnabled = selectedToneControlsEnabled
        toneSweepDirectionButton?.isEnabled = selectedToneControlsEnabled
        toneSweepScaleButton?.isEnabled = selectedToneControlsEnabled
        toneDtmfModeButton?.isEnabled = !toneSweepActive
        if (noiseSelected) {
            valueLabel.text = if (toneWaveform == "White noise") "WHITE" else "PINK"
            valueLabel.textSize = 14f
            valueLabel.contentDescription = "$toneWaveform selected; tone frequency controls are unavailable."
            if (!tonePlaying) tonePlayButton?.contentDescription = "Play ${toneWaveform.lowercase()}"
            return
        }
        toneFrequency = toneFrequency.coerceIn(low, high)
        valueLabel.text = "$toneFrequency Hz"
        valueLabel.textSize = 18f
        valueLabel.contentDescription = if (tonePeakFollowEnabled) {
            "Peak hold: $toneFrequency hertz. The strongest detected tonal peak refreshes every five seconds and remains selected if it disappears."
        } else "Select tone frequency"
        if (!tonePlaying) tonePlayButton?.contentDescription = "Play selected tone"
    }
    toneSweepUiUpdate = ::updateToneControlsFromScope
    signalPreview.onFrequencyChanged = { selectedFrequency ->
        setTonePeakFollowEnabled(false, refreshUi = false)
        toneFrequency = selectedFrequency
        updateToneControlsFromScope()
    }
    updateToneControlsFromScope()
    valueLabel.setOnClickListener {
        // Use the same custom numeric keypad as the Spectrum Analyzer.
        // Confirming a value only updates the frequency; it never starts playback.
        val dialog = createAnimatedDialog()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            styleNumericDialogCard(this)
        }
        val display = EditText(this).apply {
            setText(toneFrequency.toString())
            minWidth = dp(160)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            showSoftInputOnFocus = false
            isFocusableInTouchMode = true
            isCursorVisible = true
            setSelection(text.length)
            styleNumericDialogInput(this)
        }
        content.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

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
            topMargin = dp(5)
            bottomMargin = dp(5)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        val cancelButton = Button(this).apply {
            text = "CANCEL"
            textSize = 11f
            minHeight = dp(36)
            minimumHeight = dp(36)
            includeFontPadding = false
            setOnClickListener { dialog.dismiss() }
        }
        val doneButton = Button(this).apply {
            text = "DONE"
            textSize = 11f
            minHeight = dp(36)
            minimumHeight = dp(36)
            includeFontPadding = false
            setOnClickListener {
                val frequency = display.text.toString().toIntOrNull()
                if (frequency == null || frequency !in 20..20000) {
                    display.error = "Enter 20–20000 Hz"
                } else {
                    setTonePeakFollowEnabled(false, refreshUi = false)
                    toneFrequency = frequency
                    updateToneControlsFromScope()
                    dialog.dismiss()
                }
            }
        }
        for (button in listOf(cancelButton, doneButton)) {
            styleNumericDialogButton(button)
            button.setTextColor(Color.rgb(255, 213, 90))
            actions.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                setMargins(dp(3), dp(2), dp(3), 0)
            })
        }
        content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        setDialogCardContent(dialog, content)
        requireExplicitDialogAction(dialog)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        centerDialogOnScreen(dialog, dp(260), height = dp(360))
        dialog.show()
        display.requestFocus()
        display.setSelection(display.text.length)
    }
    return panel
}

internal fun MainActivity.decodePlayStopBitmap(resourceId: Int): android.graphics.Bitmap {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeResource(resources, resourceId, bounds)
    val targetPixels = (50f * resources.displayMetrics.density).roundToInt().coerceAtLeast(96)
    val longestSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sampleSize = 1
    while (longestSide / (sampleSize * 2) >= targetPixels) sampleSize *= 2
    val options = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return checkNotNull(android.graphics.BitmapFactory.decodeResource(resources, resourceId, options)) {
        "Could not load tone transport icon."
    }
}

internal fun MainActivity.setTonePlayButtonPlaying(playing: Boolean) {
    toneSignalPreview?.playing = playing
    toneSignalPreview?.sweepActive = toneSweepActive
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
    if (!button.usePlayStopImages) {
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (playing) Color.rgb(150, 40, 45) else Color.rgb(29, 125, 65)
        )
    }
}

internal fun MainActivity.resetTonePlayButton() {
    setTonePlayButtonPlaying(false)
}

internal fun MainActivity.applyToneSweepButtonStyle(active: Boolean) {
    toneSignalPreview?.sweepActive = active
    val button = toneSweepButton ?: return
    val density = resources.displayMetrics.density
    button.backgroundTintList = null
    button.background = GradientDrawable().apply {
        setColor(if (active) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 45))
        cornerRadius = 11f * density
        setStroke(
            (2f * density).roundToInt(),
            if (active) Color.rgb(137, 207, 238) else Color.rgb(103, 128, 153)
        )
    }
}

internal fun MainActivity.cancelToneSweep() {
    toneSweepActive = false
    toneSweepHandler.removeCallbacks(toneSweepRunnable)
    toneSweepButton?.contentDescription = "Play frequency sweep"
    applyToneSweepButtonStyle(active = false)
    toneSweepRangeButton?.isEnabled = true
    toneSweepDurationButton?.isEnabled = true
    toneSweepDirectionButton?.isEnabled = true
    toneSweepScaleButton?.isEnabled = true
    toneDtmfModeButton?.isEnabled = true
    toneWaveformButton?.isEnabled = true
    toneSweepUiUpdate?.invoke()
}
