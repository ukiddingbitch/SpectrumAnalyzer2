package sk.spectrum.analyzer2

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

import kotlin.math.roundToInt

/** Tone frequency presets and their dialogs. */
internal fun MainActivity.loadCustomToneFrequencies(): List<Int> = getSharedPreferences(
    "tone_generator", Context.MODE_PRIVATE
).getStringSet("custom_frequency_presets", emptySet()).orEmpty()
    .mapNotNull { it.toIntOrNull() }
    .filter { it in 20..20000 }
    .distinct()
    .sorted()

internal fun MainActivity.saveCustomToneFrequencies(frequencies: Collection<Int>) {
    getSharedPreferences("tone_generator", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("custom_frequency_presets", frequencies.map { it.toString() }.toSet())
        .apply()
}

internal fun MainActivity.showAddTonePresetDialog(
    minFrequency: Int,
    maxFrequency: Int,
    existingFrequencies: Set<Int>,
    onAdded: () -> Unit
) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        styleNumericDialogCard(this)
    }
    content.addView(TextView(this).apply {
        text = "Add frequency preset"
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.rgb(225, 233, 243))
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(4))
    })
    content.addView(TextView(this).apply {
        text = "Enter a value from $minFrequency to $maxFrequency Hz."
        textSize = 11f
        setTextColor(Color.rgb(150, 166, 184))
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(8))
    })
    val input = EditText(this).apply {
        setText(toneFrequency.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(5))
        isSingleLine = true
        isCursorVisible = true
        isFocusableInTouchMode = true
        showSoftInputOnFocus = false
        setSelection(text.length)
        styleNumericDialogInput(this)
    }
    content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

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
        bottomMargin = dp(5)
    })

    lateinit var dialog: Dialog
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(5), 0, 0)
    }
    val cancel = Button(this).apply {
        text = "Cancel"
        textSize = 11f
        setOnClickListener { dialog.dismiss() }
    }
    styleNumericDialogButton(cancel)
    cancel.setTextColor(Color.rgb(255, 213, 90))
    actions.addView(cancel, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
    val add = Button(this).apply {
        text = "Add"
        textSize = 11f
    }
    add.setOnClickListener {
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
    styleNumericDialogButton(add)
    add.setTextColor(Color.rgb(255, 213, 90))
    actions.addView(add, LinearLayout.LayoutParams(0, dp(42), 1f))
    content.addView(actions)
    dialog = createAnimatedDialog()
    setDialogCardContent(dialog, content)
    requireExplicitDialogAction(dialog)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
    }
    centerDialogOnScreen(dialog, dp(260), height = dp(360))
    dialog.show()
    input.requestFocus()
    input.setSelection(input.text.length)
}

internal fun MainActivity.showDeleteTonePresetDialog(frequency: Int, onDelete: () -> Unit) {
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
    dialog = createAnimatedDialog()
    dialog.setContentView(content)
    requireExplicitDialogAction(dialog)
    centerDialogOnScreen(dialog, dp(320))
    dialog.show()
}
