package sk.spectrum.analyzer2

import android.app.Dialog
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

import kotlin.math.hypot
import kotlin.math.roundToInt

/** Shared dialog creation, positioning and drag handling. */
internal fun MainActivity.rememberCardTouch(event: MotionEvent) {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        lastCardTouch = android.graphics.PointF(event.rawX, event.rawY)
    }
}

internal fun MainActivity.createAnimatedDialog(
    theme: Int = 0,
    interceptTouch: (MotionEvent) -> Boolean = { false }
): Dialog = AnimatedCardDialog(
    this, theme,
    { lastCardTouch?.let { android.graphics.PointF(it.x, it.y) } },
    this@createAnimatedDialog::rememberCardTouch, interceptTouch
)

internal fun MainActivity.dismissForLayout(dialog: Dialog?) {
    if (dialog is AnimatedCardDialog) dialog.dismissImmediately() else dialog?.dismiss()
}

internal fun MainActivity.setDialogCardContent(dialog: Dialog, card: View) {
    dialog.setContentView(
        card,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
}

internal fun MainActivity.centerDialogOnScreen(
    dialog: Dialog,
    width: Int,
    horizontalOffsetDp: Int = -19,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
) {
    val window = dialog.window ?: return
    val horizontalOffset = (horizontalOffsetDp * resources.displayMetrics.density).roundToInt()
    val safeBounds = availableWindowBounds()
    val margin = (24f * resources.displayMetrics.density).roundToInt()
    val maxWidth = (safeBounds.width() - margin).coerceAtLeast(1)
    val maxHeight = (safeBounds.height() - margin).coerceAtLeast(1)
    val targetHeight = if (height > 0) minOf(height, maxHeight) else ViewGroup.LayoutParams.WRAP_CONTENT
    val targetWidth = if (width > 0) minOf(width, maxWidth) else width
    fun applyCenter() {
        window.setGravity(Gravity.CENTER)
        window.setLayout(targetWidth, targetHeight)
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

internal fun MainActivity.attachSplDialogDragHandle(
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

internal fun MainActivity.requireExplicitDialogAction(dialog: Dialog) {
    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)
}

@Suppress("DEPRECATION")
internal fun MainActivity.installedAppVersionName(): String = try {
    packageManager.getPackageInfo(packageName, 0).versionName
        ?.takeIf { it.isNotBlank() } ?: "Unknown"
} catch (_: Exception) {
    "Unknown"
}

internal fun MainActivity.showDialogCenteredOnScreen(
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

internal fun MainActivity.attachDialogDragHandle(
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
