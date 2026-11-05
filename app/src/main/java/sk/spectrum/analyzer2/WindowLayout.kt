package sk.spectrum.analyzer2

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View

import kotlin.math.roundToInt

/** Orientation, safe window bounds, immersive mode and display-cutout helpers. */
/** Returns the cutout-safe horizontal overlap of a view, plus a small visual gap. */
internal fun MainActivity.cutoutInsetsForView(view: View, gapDp: Float = 4f): Pair<Int, Int> {
    if (android.os.Build.VERSION.SDK_INT < 28) return 0 to 0
    val cutout = window.decorView.rootWindowInsets?.displayCutout ?: return 0 to 0
    val root = window.decorView.rootView
    if (root.width <= 0 || view.width <= 0) return 0 to 0
    val rootLocation = IntArray(2)
    val viewLocation = IntArray(2)
    root.getLocationOnScreen(rootLocation)
    view.getLocationOnScreen(viewLocation)
    val rootLeft = rootLocation[0].toFloat()
    val rootRight = rootLeft + root.width
    val rootTop = rootLocation[1].toFloat()
    val viewLeft = viewLocation[0].toFloat()
    val viewRight = viewLeft + view.width
    val viewTop = viewLocation[1].toFloat()
    val viewBottom = viewTop + view.height
    var leftOverlap = (rootLeft + cutout.safeInsetLeft - viewLeft).coerceAtLeast(0f)
    var rightOverlap = (viewRight - (rootRight - cutout.safeInsetRight)).coerceAtLeast(0f)
    for (bounds in cutout.boundingRects) {
        val cutoutTop = rootTop + bounds.top
        val cutoutBottom = rootTop + bounds.bottom
        if (cutoutBottom <= viewTop || cutoutTop >= viewBottom) continue
        val cutoutLeft = rootLeft + bounds.left
        val cutoutRight = rootLeft + bounds.right
        if (bounds.left <= cutout.safeInsetLeft + 2 && cutoutRight > viewLeft) {
            leftOverlap = maxOf(leftOverlap, (cutoutRight - viewLeft).coerceAtLeast(0f))
        }
        if (bounds.right >= root.width - cutout.safeInsetRight - 2 && cutoutLeft < viewRight) {
            rightOverlap = maxOf(rightOverlap, (viewRight - cutoutLeft).coerceAtLeast(0f))
        }
    }
    val gap = (gapDp * resources.displayMetrics.density).roundToInt()
    return (if (leftOverlap > 0f) leftOverlap.roundToInt() + gap else 0) to
            (if (rightOverlap > 0f) rightOverlap.roundToInt() + gap else 0)
}

@Suppress("DEPRECATION")
internal fun MainActivity.hideSystemBars() {
    window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
}

internal fun MainActivity.isCompactAppWindow(): Boolean {
    val configuration = resources.configuration
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    return widthDp <= 0 || heightDp <= 0 || minOf(widthDp, heightDp) < 600
}

internal fun MainActivity.isAppWindowPortrait(): Boolean {
    val configuration = resources.configuration
    return configuration.screenWidthDp > 0 && configuration.screenHeightDp > configuration.screenWidthDp
}

internal fun MainActivity.requestAnalyzerOrientation(soundId: Boolean) {
    requestedOrientation = if (isCompactAppWindow()) {
        if (soundId) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

internal fun MainActivity.availableWindowBounds(): android.graphics.Rect {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        val metrics = windowManager.currentWindowMetrics
        val bounds = android.graphics.Rect(metrics.bounds)
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
        )
        // Adjust edges directly to avoid Rect.inset(Insets), which is not
        // available on every Android version supported by this app.
        bounds.left += insets.left
        bounds.top += insets.top
        bounds.right -= insets.right
        bounds.bottom -= insets.bottom
        if (!bounds.isEmpty) return bounds
    }

    val displaySize = android.graphics.Point()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getRealSize(displaySize)
    val bounds = android.graphics.Rect(0, 0, displaySize.x, displaySize.y)
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        val cutout = window.decorView.rootWindowInsets?.displayCutout
        if (cutout != null) {
            bounds.left += cutout.safeInsetLeft
            bounds.top += cutout.safeInsetTop
            bounds.right -= cutout.safeInsetRight
            bounds.bottom -= cutout.safeInsetBottom
        }
    }
    if (bounds.isEmpty) {
        bounds.set(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }
    return bounds
}
