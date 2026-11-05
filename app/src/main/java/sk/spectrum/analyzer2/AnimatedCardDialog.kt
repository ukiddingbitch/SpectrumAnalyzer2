package sk.spectrum.analyzer2

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout

/** Shared opening/closing card animation implementation. */
/** Snapshot zoom: the real dialog never changes its size or position during a transition. */
internal class AnimatedCardDialog(
    private val activity: Activity,
    theme: Int = 0,
    private val originProvider: () -> PointF?,
    private val touchObserver: (MotionEvent) -> Unit,
    private val interceptTouch: (MotionEvent) -> Boolean
) : Dialog(activity, theme) {
    private data class Visual(
        val layer: Dialog,
        val image: android.widget.ImageView,
        val actual: View,
        val screenX: Int,
        val screenY: Int,
        var targetX: Float = 0f,
        var targetY: Float = 0f,
        var sourceX: Float = 0f,
        var sourceY: Float = 0f,
        var ready: Boolean = false
    )
    private var origin: PointF? = null
    private var transition: AnimatorSet? = null
    private var visual: Visual? = null
    private var closing = false
    private var immediate = false
    private val immersiveFlags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

    private fun keepImmersive(target: android.view.Window?) {
        target ?: return
        target.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        target.decorView.systemUiVisibility = immersiveFlags
        if (Build.VERSION.SDK_INT >= 30) {
            target.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            target.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) keepImmersive(window)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        touchObserver(event)
        if (closing || visual != null) return true
        if (interceptTouch(event)) return true
        return super.dispatchTouchEvent(event)
    }
    override fun show() {
        origin = originProvider(); closing = false; cancelOnFinish = false
        val enabled = android.animation.ValueAnimator.areAnimatorsEnabled()
        window?.setWindowAnimations(0)
        keepImmersive(window)
        window?.decorView?.alpha = if (enabled) 0f else 1f
        super.show()
        keepImmersive(window)
        if (!enabled) return
        val actual = window?.decorView ?: return
        actual.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            private var textLayoutReady = false
            override fun onPreDraw(): Boolean {
                if (!isShowing || closing || !actual.isAttachedToWindow) {
                    if (actual.viewTreeObserver.isAlive) actual.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                if (!textLayoutReady) {
                    // EditText registers its own pre-draw work during measure, after this listener.
                    // Let that pass finish so initial values and selections exist in the snapshot.
                    textLayoutReady = true
                    actual.postInvalidateOnAnimation()
                    return false
                }
                if (actual.viewTreeObserver.isAlive) actual.viewTreeObserver.removeOnPreDrawListener(this)
                prepareVisual(actual, opening = true)
                return true
            }
        })
        actual.invalidate()
    }

    private fun prepareVisual(actual: View, opening: Boolean) {
        if (actual.width <= 0 || actual.height <= 0) {
            if (opening) actual.alpha = 1f else finishDismiss(false)
            return
        }
        val bitmap = try {
            android.graphics.Bitmap.createBitmap(actual.width, actual.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                // Capture fully opaque content, including default values in editable fields.
                // Restore alpha before this pre-draw returns, so the real window cannot flash.
                val previousAlpha = actual.alpha
                try {
                    actual.alpha = 1f
                    actual.draw(Canvas(it))
                } finally {
                    actual.alpha = previousAlpha
                }
            }
        } catch (_: OutOfMemoryError) {
            if (opening) actual.alpha = 1f else finishDismiss(false)
            return
        }
        val at = IntArray(2); actual.getLocationOnScreen(at)
        val image = android.widget.ImageView(context).apply {
            setImageBitmap(bitmap); scaleType = android.widget.ImageView.ScaleType.FIT_XY; alpha = 0f
        }
        val surface = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false; setBackgroundColor(Color.TRANSPARENT)
            addView(image, FrameLayout.LayoutParams(bitmap.width, bitmap.height))
        }
        // This non-focusable, non-touchable layer is above nested dialogs but cannot alter IME focus.
        val layer = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        layer.setContentView(surface)
        layer.window?.apply {
            setWindowAnimations(0); setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setGravity(Gravity.TOP or Gravity.LEFT)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        keepImmersive(layer.window)
        val saved = Visual(layer, image, actual, at[0], at[1])
        visual = saved
        layer.show()
        layer.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        keepImmersive(layer.window)
        surface.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (surface.viewTreeObserver.isAlive) surface.viewTreeObserver.removeOnPreDrawListener(this)
                if (visual !== saved || !isShowing) return true
                val base = IntArray(2); surface.getLocationOnScreen(base)
                saved.targetX = (saved.screenX - base[0]).toFloat()
                saved.targetY = (saved.screenY - base[1]).toFloat()
                val source = origin ?: PointF(saved.screenX + image.width / 2f, saved.screenY + image.height / 2f)
                saved.sourceX = source.x - base[0] - image.width / 2f
                saved.sourceY = source.y - base[1] - image.height / 2f
                saved.ready = true
                actual.alpha = 0f
                animate(saved, opening)
                return true
            }
        })
    }

    private fun animate(saved: Visual, opening: Boolean) {
        transition?.removeAllListeners(); transition?.cancel()
        val image = saved.image
        image.animate().cancel()
        val sx = (32f * context.resources.displayMetrics.density / image.width).coerceIn(0.025f, 0.5f)
        val sy = (22f * context.resources.displayMetrics.density / image.height).coerceIn(0.025f, 0.5f)
        if (opening) {
            image.scaleX = sx; image.scaleY = sy
            image.translationX = saved.sourceX; image.translationY = saved.sourceY; image.alpha = 0.6f
        } else if (transition == null) {
            image.scaleX = 1f; image.scaleY = 1f
            image.translationX = saved.targetX; image.translationY = saved.targetY; image.alpha = 1f
        }
        transition = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(image, View.SCALE_X, image.scaleX, if (opening) 1f else sx),
                ObjectAnimator.ofFloat(image, View.SCALE_Y, image.scaleY, if (opening) 1f else sy),
                ObjectAnimator.ofFloat(image, View.TRANSLATION_X, image.translationX, if (opening) saved.targetX else saved.sourceX),
                ObjectAnimator.ofFloat(image, View.TRANSLATION_Y, image.translationY, if (opening) saved.targetY else saved.sourceY),
                ObjectAnimator.ofFloat(image, View.ALPHA, image.alpha, if (opening) 1f else 0f)
            )
            duration = if (opening) 420L else 340L
            interpolator = PathInterpolator(0.22f, 0.61f, 0.36f, 1f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    transition = null
                    if (opening && !closing) {
                        saved.actual.alpha = 1f
                        // Blend into live values instead of swapping a 420 ms old snapshot in one frame.
                        saved.image.animate().alpha(0f).setDuration(100L).withEndAction {
                            if (!closing && visual === saved) releaseVisual(saved)
                        }.start()
                    } else if (!opening) finishDismiss(false)
                }
            })
            start()
        }
    }
    private fun releaseVisual(saved: Visual) {
        saved.image.animate().cancel()
        saved.layer.dismiss(); saved.image.setImageDrawable(null)
        if (visual === saved) visual = null
    }
    private var cancelOnFinish = false
    private fun close(cancel: Boolean) {
        if (closing) return
        closing = true; cancelOnFinish = cancel
        val saved = visual
        if (activity.isFinishing || activity.isDestroyed || !isShowing || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            finishDismiss(cancel); return
        }
        if (saved != null) {
            if (saved.ready) animate(saved, opening = false) else finishDismiss(cancel)
        } else {
            val actual = window?.decorView
            if (actual?.isAttachedToWindow == true) prepareVisual(actual, opening = false) else finishDismiss(cancel)
        }
    }
    private fun finishDismiss(cancel: Boolean) {
        transition?.removeAllListeners(); transition?.cancel(); transition = null
        keepImmersive(activity.window)
        window?.decorView?.alpha = 0f
        immediate = true
        if (cancel || cancelOnFinish) super.cancel() else super.dismiss()
        visual?.let(::releaseVisual)
        immediate = false
        activity.window.decorView.post { if (!activity.isFinishing && !activity.isDestroyed) keepImmersive(activity.window) }
    }
    override fun dismiss() { if (immediate) super.dismiss() else close(false) }
    override fun cancel() { if (immediate) super.cancel() else close(true) }
    fun dismissImmediately() { closing = true; finishDismiss(false) }
}
