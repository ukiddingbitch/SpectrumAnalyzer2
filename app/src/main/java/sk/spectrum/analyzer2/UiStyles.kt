package sk.spectrum.analyzer2

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

import kotlin.math.roundToInt

/** Existing shared button, input and card styles. */
internal fun MainActivity.styleXButton(button: Button, primary: Boolean) {
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

internal fun MainActivity.styleFlatToolbarButton(button: Button) {
    button.background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(Color.rgb(38, 54, 70)),
        android.graphics.drawable.ColorDrawable(Color.rgb(15, 23, 36)),
        null
    )
    button.backgroundTintList = null
    button.stateListAnimator = null
}

internal fun MainActivity.styleNumericDialogButton(button: Button) {
    val density = resources.displayMetrics.density
    button.background = GradientDrawable().apply {
        setColor(Color.rgb(49, 63, 81))
        cornerRadius = 12f * density
        setStroke((density).roundToInt().coerceAtLeast(1), Color.rgb(72, 91, 111))
    }
    button.backgroundTintList = null
    button.stateListAnimator = null
}

internal fun MainActivity.styleNumericDialogCard(card: LinearLayout) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    card.setPadding(dp(12), dp(10), dp(12), dp(10))
    card.background = GradientDrawable().apply {
        setColor(Color.rgb(24, 31, 42))
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.rgb(49, 66, 83))
    }
}

internal fun MainActivity.styleNumericDialogInput(
    input: EditText,
    textSizeSp: Float = 20f,
    focused: Boolean = false,
    verticalPaddingDp: Int = 2
) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    input.textSize = textSizeSp
    input.setTextColor(Color.rgb(255, 213, 90))
    input.gravity = Gravity.CENTER
    input.setPadding(dp(8), dp(verticalPaddingDp), dp(8), dp(verticalPaddingDp))
    input.backgroundTintList = null
    input.background = GradientDrawable().apply {
        setColor(Color.rgb(15, 23, 36))
        cornerRadius = dp(12).toFloat()
        setStroke(dp(if (focused) 2 else 1), if (focused) Color.rgb(255, 213, 90) else Color.rgb(67, 91, 119))
    }
}
