package sk.spectrum.analyzer2

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

import kotlin.math.roundToInt

/** Piano rendering, controls, full-scale layout and keyboard transitions. */
internal class PianoKeyboardView(
    context: Context,
    private val onKeyChange: (midiNote: Int, pressed: Boolean) -> Unit
) : View(context) {
    private var safeLeftInsetPx = 0f
    private var safeRightInsetPx = 0f
    private var transpose = 0
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 245, 240); style = Paint.Style.FILL }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 29, 36); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 78, 90); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 75); style = Paint.Style.FILL }
    private val whiteNotes = (36..96).filter { it % 12 !in listOf(1, 3, 6, 8, 10) } // C2–C7
    private val blackNotes = (36..95).map { note -> if (note % 12 in listOf(1, 3, 6, 8, 10)) note else -1 }
    private val pointerNotes = mutableMapOf<Int, Int>()
    private val activeNotes = mutableSetOf<Int>()

    fun setSafeEdgeInsets(leftPx: Float, rightPx: Float) {
        safeLeftInsetPx = leftPx.coerceAtLeast(0f)
        safeRightInsetPx = rightPx.coerceAtLeast(0f)
        invalidate()
    }

    fun setTranspose(semitones: Int) {
        transpose = semitones
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 23, 36))
        if (width <= 0 || height <= 0) return
        val leftInset = safeLeftInsetPx.coerceAtMost(width.toFloat() * 0.35f)
        val rightInset = safeRightInsetPx.coerceAtMost(width.toFloat() * 0.35f)
        val usableWidth = (width.toFloat() - leftInset - rightInset).coerceAtLeast(1f)
        val whiteWidth = usableWidth / whiteNotes.size
        val top = height * 0.04f
        val bottom = height * 0.96f
        whiteNotes.forEachIndexed { index, note ->
            val left = leftInset + index * whiteWidth
            val right = leftInset + (index + 1) * whiteWidth
            canvas.drawRect(left, top, right, bottom, if (note in activeNotes) pressedPaint else whitePaint)
            canvas.drawRect(left, top, right, bottom, linePaint)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = (height * 0.075f).coerceIn(10f, 24f); textAlign = Paint.Align.CENTER }
            canvas.drawText(noteName(note + transpose), (left + right) / 2f, bottom - height * 0.04f, labelPaint)
        }
        val blackWidth = whiteWidth * 0.58f
        val blackHeight = height * 0.60f
        for (note in blackNotes) {
            if (note < 0) continue
            val whiteIndex = whiteNotes.count { it < note }
            val left = leftInset + whiteIndex * whiteWidth - blackWidth / 2f
            canvas.drawRect(left, top, left + blackWidth, top + blackHeight, if (note in activeNotes) pressedPaint else blackPaint)
            canvas.drawRect(left, top, left + blackWidth, top + blackHeight, linePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        fun updatePointer(pointerIndex: Int) {
            val pointerId = event.getPointerId(pointerIndex)
            val note = noteAt(event.getX(pointerIndex), event.getY(pointerIndex))
            val previous = pointerNotes[pointerId]
            if (note != previous) {
                if (previous != null) {
                    pointerNotes.remove(pointerId)
                    if (pointerNotes.values.none { it == previous }) {
                        activeNotes.remove(previous)
                        onKeyChange(previous, false)
                    }
                }
                if (note != null) {
                    pointerNotes[pointerId] = note
                    val wasActive = note in activeNotes
                    activeNotes.add(note)
                    if (!wasActive) onKeyChange(note, true)
                }
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                updatePointer(event.actionIndex)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) updatePointer(i)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val notes = pointerNotes.values.toSet()
                    pointerNotes.clear()
                    notes.forEach { note ->
                        activeNotes.remove(note)
                        onKeyChange(note, false)
                    }
                } else {
                    val pointerId = event.getPointerId(event.actionIndex)
                    val previous = pointerNotes.remove(pointerId)
                    if (previous != null && pointerNotes.values.none { it == previous }) {
                        activeNotes.remove(previous)
                        onKeyChange(previous, false)
                    }
                }
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun releaseAllPointers() {
        val notes = pointerNotes.values.toSet()
        pointerNotes.clear()
        activeNotes.clear()
        notes.forEach { onKeyChange(it, false) }
        invalidate()
    }

    private fun noteAt(x: Float, y: Float): Int? {
        val leftInset = safeLeftInsetPx.coerceAtMost(width.toFloat() * 0.35f)
        val rightInset = safeRightInsetPx.coerceAtMost(width.toFloat() * 0.35f)
        if (y < 0 || y > height || x < leftInset || x > width - rightInset) return null
        val usableWidth = (width.toFloat() - leftInset - rightInset).coerceAtLeast(1f)
        val whiteWidth = usableWidth / whiteNotes.size
        val blackWidth = whiteWidth * 0.58f
        val blackHeight = height * 0.60f
        if (y <= blackHeight) {
            for (note in blackNotes) {
                if (note < 0) continue
                val whiteIndex = whiteNotes.count { it < note }
                val left = leftInset + whiteIndex * whiteWidth - blackWidth / 2f
                if (x in left..(left + blackWidth)) return note
            }
        }
        return whiteNotes[((x - leftInset) / whiteWidth).toInt().coerceIn(0, whiteNotes.lastIndex)]
    }

    private fun noteName(note: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[note % 12] + (note / 12 - 1)
}

internal fun MainActivity.clearKeysClosingVisual() {
    val image = keysClosingVisual ?: return
    keysClosingVisual = null
    image.animate().setListener(null).cancel()
    keysClosingVisualRoot?.overlay?.remove(image)
    keysClosingVisualRoot = null
    image.setImageDrawable(null)
}

internal fun MainActivity.animateKeysClosingVisual() {
    clearKeysClosingVisual()
    val keys = keyboardPanel ?: return
    val root = window.decorView as? ViewGroup ?: return
    if (!keys.isShown || keys.width <= 0 || keys.height <= 0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
    val bitmap = try {
        android.graphics.Bitmap.createBitmap(keys.width, keys.height, android.graphics.Bitmap.Config.ARGB_8888).also {
            keys.draw(Canvas(it))
        }
    } catch (_: OutOfMemoryError) { return }
    val sourceAt = IntArray(2); keys.getLocationOnScreen(sourceAt)
    val rootAt = IntArray(2); root.getLocationOnScreen(rootAt)
    val image = android.widget.ImageView(this).apply {
        setImageBitmap(bitmap); scaleType = android.widget.ImageView.ScaleType.FIT_XY
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layout(sourceAt[0] - rootAt[0], sourceAt[1] - rootAt[1],
            sourceAt[0] - rootAt[0] + keys.width, sourceAt[1] - rootAt[1] + keys.height)
        pivotX = width / 2f; pivotY = height.toFloat()
    }
    keysClosingVisual = image; keysClosingVisualRoot = root
    root.overlay.add(image)
    image.animate().scaleY(0f).alpha(0f)
        .setDuration(460L).setStartDelay(0L)
        .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
        .setListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (keysClosingVisual === image) clearKeysClosingVisual()
            }
        }).start()
}

internal fun MainActivity.updatePianoVolumeControls() {
    val percent = (pianoVolume * 100).roundToInt().coerceIn(0, 100)
    pianoVolumeLabel?.text = "$percent%"
    pianoVolumeSlider?.let { if (it.progress != percent) it.progress = percent }
    pianoVolumeIconButton?.apply {
        icon = if (percent == 0) AnalyzerIconButton.Icon.VOLUME_MUTED else AnalyzerIconButton.Icon.VOLUME
        contentDescription = if (percent == 0) "Piano muted; tap to restore volume" else "Mute piano"
    }
    synchronized(pianoAudioLock) { pianoVoices.forEach { it.volume = pianoVolume } }
}

internal fun MainActivity.updatePianoOctaveControls() {
    val (icon, description) = when (pianoTranspose) {
        12 -> R.drawable.ic_octave_plus_1_custom to "Piano octave plus one"
        -12 -> R.drawable.ic_octave_minus_1_custom to "Piano octave minus one"
        else -> R.drawable.ic_octave_zero_custom to "Piano base octave"
    }
    pianoOctaveButton?.let { button ->
        button.setImageResource(icon)
        button.contentDescription = description
    }
}

internal fun MainActivity.createPianoOctaveButton(): ImageButton = ImageButton(this).apply {
    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    val padding = (1f * resources.displayMetrics.density).roundToInt()
    setPadding(padding, padding, padding, padding)
    setMinimumWidth(0)
    setMinimumHeight(0)
    backgroundTintList = null
    imageTintList = null
    stateListAnimator = null
    background = GradientDrawable().apply {
        cornerRadius = 13f * resources.displayMetrics.density
        setColor(Color.rgb(22, 32, 42))
    }
    updatePianoOctaveControls()
    setOnClickListener { cyclePianoOctave() }
}

internal fun MainActivity.cyclePianoOctave() {
    pianoTranspose = when (pianoTranspose) {
        0 -> 12
        12 -> -12
        else -> 0
    }
    updatePianoOctaveControls()
    (keyboardPanel as? PianoKeyboardView)?.setTranspose(pianoTranspose)
}

internal fun MainActivity.applyKeyboardVolumeSafeInset() {
    val row = keyboardControlsRow ?: return
    if (row.childCount == 0) return
    if (row.parent === mainActionRow || row.parent === bottomActionBar) {
        // The KEYS toolbar itself is inset around display cutouts.
        // The centered control cluster must not receive a second inset.
        applyPianoKeyboardSafeInsets()
        return
    }
    val (leftInset, rightInset) = cutoutInsetsForView(row, gapDp = 6f)
    val volumeCell = row.getChildAt(0)
    (volumeCell.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
        if (params.marginStart != leftInset) {
            params.marginStart = leftInset
            volumeCell.layoutParams = params
        }
    }
    val lastControl = row.getChildAt(row.childCount - 1)
    (lastControl.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
        val baseEndMargin = (3f * resources.displayMetrics.density).roundToInt()
        val targetMargin = baseEndMargin + rightInset
        if (params.marginEnd != targetMargin) {
            params.marginEnd = targetMargin
            lastControl.layoutParams = params
        }
    }
    applyPianoKeyboardSafeInsets()
}

internal fun MainActivity.applyPianoKeyboardSafeInsets() {
    val pianoView = keyboardPanel as? PianoKeyboardView ?: return
    if (pianoView.width <= 0) return
    val (leftInset, rightInset) = cutoutInsetsForView(pianoView, gapDp = 8f)
    pianoView.setSafeEdgeInsets(leftInset.toFloat(), rightInset.toFloat())
    val actionBar = bottomActionBar ?: return
    val actionBarParams = actionBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    // Keep toolbar buttons at the same horizontal positions in Spectrum and KEYS.
    // Only the piano keys themselves need to avoid a display cutout.
    if (actionBarParams.marginStart != 0 || actionBarParams.marginEnd != 0) {
        actionBarParams.marginStart = 0
        actionBarParams.marginEnd = 0
        actionBar.layoutParams = actionBarParams
    }
}

internal fun MainActivity.createPianoReverbButton(): ImageButton = ImageButton(this).apply {
    val density = resources.displayMetrics.density
    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    setPadding((6f * density).roundToInt(), (6f * density).roundToInt(), (6f * density).roundToInt(), (6f * density).roundToInt())
    setMinimumWidth(0)
    setMinimumHeight(0)
    backgroundTintList = null
    imageTintList = null
    stateListAnimator = null
    updatePianoReverbButtonAppearance(this)
    setOnClickListener {
        pianoReverbEnabled = !pianoReverbEnabled
        updatePianoReverbButtons()
    }
}

internal fun MainActivity.updatePianoReverbButtons() {
    pianoReverbButton?.let(this@updatePianoReverbButtons::updatePianoReverbButtonAppearance)
    fullScalePianoReverbButton?.let(this@updatePianoReverbButtons::updatePianoReverbButtonAppearance)
}

internal fun MainActivity.updatePianoReverbButtonAppearance(button: ImageButton) {
    button.setImageResource(
        if (pianoReverbEnabled) R.drawable.ic_reverb_on_custom
        else R.drawable.ic_reverb_off_custom
    )
    button.contentDescription = if (pianoReverbEnabled) {
        "Reverb on; tap to turn off"
    } else {
        "Reverb off; tap to turn on"
    }
    button.background = GradientDrawable().apply {
        cornerRadius = 13f * resources.displayMetrics.density
        setColor(if (pianoReverbEnabled) Color.rgb(29, 155, 240) else Color.rgb(22, 32, 42))
    }
}

internal fun MainActivity.showFullScaleKeyboard() {
    if (!keyboardModeOpen || fullScaleTransitionAnimator?.isRunning == true) return
    val split = splitContainer ?: return
    val graphColumn = spectrumView.parent as? LinearLayout ?: return
    val keyboardSection = keyboardSectionView as? LinearLayout ?: return
    fullScaleTransitionAnimator?.cancel()
    switchKeysToolbarLayout?.invoke(false)
    rangePickerButton?.visibility = View.GONE
    fullScaleKeyboardOpen = true
    fullScaleKeysButton?.isEnabled = false
    (fullScaleKeysButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.FULL_SCALE_CLOSE
    toneModeOpen = false
    soundModeOpen = false
    spectrumView.setKeysFrequencyRangeActive(true)
    spectrumView.setDbRangeMode("KEYS")
    tonePanel?.visibility = View.GONE
    soundIdPanel?.visibility = View.GONE

    split.orientation = LinearLayout.VERTICAL
    graphColumn.visibility = View.VISIBLE
    spectrumView.visibility = View.VISIBLE
    keyboardSection.visibility = View.VISIBLE
    val graphParams = (graphColumn.layoutParams as LinearLayout.LayoutParams).apply {
        width = ViewGroup.LayoutParams.MATCH_PARENT
        height = 0
        weight = 1f
    }
    val keysParams = (keyboardSection.layoutParams as LinearLayout.LayoutParams).apply {
        width = ViewGroup.LayoutParams.MATCH_PARENT
        height = 0
        weight = 1f
    }
    graphColumn.layoutParams = graphParams
    keyboardSection.layoutParams = keysParams
    spectrumView.alpha = 1f
    spectrumView.translationY = 0f
    keyboardSection.alpha = 1f
    keyboardSection.translationY = 0f
    keyboardSection.scaleY = 1f
    split.requestLayout()
    keyboardPanel?.post { applyPianoKeyboardSafeInsets() }

    val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400L
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        var wasCancelled = false
        addUpdateListener { update ->
            val progress = (update.animatedValue as Float).coerceIn(0f, 1f)
            graphParams.weight = 1f - progress
            graphColumn.layoutParams = graphParams
            split.requestLayout()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                wasCancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (wasCancelled || fullScaleTransitionAnimator !== this@apply) return
                graphParams.height = 0
                graphParams.weight = 0f
                graphColumn.layoutParams = graphParams
                graphColumn.visibility = View.GONE
                spectrumView.visibility = View.GONE
                spectrumView.alpha = 1f
                spectrumView.translationY = 0f
                keyboardSection.alpha = 1f
                keyboardSection.translationY = 0f
                keyboardSection.scaleY = 1f
                fullScaleKeysButton?.isEnabled = true
                fullScaleTransitionAnimator = null
                split.requestLayout()
                keyboardPanel?.post { applyPianoKeyboardSafeInsets() }
            }
        })
    }
    fullScaleTransitionAnimator = animator
    animator.start()
}

internal fun MainActivity.leaveFullScaleKeyboard(animate: Boolean = true) {
    if (!fullScaleKeyboardOpen) return
    fullScaleTransitionAnimator?.cancel()
    fullScaleTransitionAnimator = null
    fullScaleKeyboardOpen = false
    val split = splitContainer
    val graphColumn = spectrumView.parent as? LinearLayout
    val keyboardSection = keyboardSectionView as? LinearLayout
    (fullScaleKeysButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.FULL_SCALE_OPEN
    fullScaleKeysButton?.isEnabled = !animate
    fullScaleKeysButton?.text = ""

    if (!keyboardModeOpen || split == null || graphColumn == null || keyboardSection == null) {
        spectrumView.visibility = View.VISIBLE
        if (keyboardModeOpen) switchKeysToolbarLayout?.invoke(true)
        rangePickerButton?.visibility = if (keyboardModeOpen && !soundModeOpen) View.VISIBLE else View.GONE
        fullScaleKeysButton?.isEnabled = true
        split?.requestLayout()
        return
    }
    split.orientation = LinearLayout.VERTICAL
    graphColumn.visibility = View.VISIBLE
    spectrumView.visibility = View.VISIBLE
    keyboardSection.visibility = View.VISIBLE
    val graphParams = (graphColumn.layoutParams as LinearLayout.LayoutParams).apply {
        width = ViewGroup.LayoutParams.MATCH_PARENT
        height = 0
        weight = 0f
    }
    val keysParams = (keyboardSection.layoutParams as LinearLayout.LayoutParams).apply {
        width = ViewGroup.LayoutParams.MATCH_PARENT
        height = 0
        weight = 1f
    }
    graphColumn.layoutParams = graphParams
    keyboardSection.layoutParams = keysParams
    spectrumView.alpha = 1f
    spectrumView.translationY = 0f
    keyboardSection.alpha = 1f
    keyboardSection.translationY = 0f
    keyboardSection.scaleY = 1f
    split.requestLayout()

    fun finishExit() {
        graphColumn.visibility = View.VISIBLE
        graphParams.height = 0
        graphParams.weight = 1f
        keysParams.height = 0
        keysParams.weight = 1f
        graphColumn.layoutParams = graphParams
        keyboardSection.layoutParams = keysParams
        spectrumView.alpha = 1f
        spectrumView.translationY = 0f
        keyboardSection.alpha = 1f
        keyboardSection.translationY = 0f
        keyboardSection.scaleY = 1f
        soundIdPanel?.visibility = View.GONE
        tonePanel?.visibility = View.GONE
        switchKeysToolbarLayout?.invoke(true)
        rangePickerButton?.visibility = View.VISIBLE
        fullScaleKeysButton?.isEnabled = true
        split.requestLayout()
        keyboardControlsRow?.post { applyKeyboardVolumeSafeInset() }
        keyboardPanel?.post { applyPianoKeyboardSafeInsets() }
    }

    if (!animate) {
        finishExit()
        return
    }
    val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400L
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        var wasCancelled = false
        addUpdateListener { update ->
            val progress = (update.animatedValue as Float).coerceIn(0f, 1f)
            graphParams.weight = progress
            graphColumn.layoutParams = graphParams
            split.requestLayout()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                wasCancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (wasCancelled || fullScaleTransitionAnimator !== this@apply) return
                fullScaleTransitionAnimator = null
                finishExit()
            }
        })
    }
    fullScaleTransitionAnimator = animator
    animator.start()
}

@Suppress("unused")
internal fun MainActivity.showFullScaleKeyboardDialogLegacy() {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    fun control(text: String, action: (Button) -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(42)
        minimumHeight = dp(42)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.WHITE)
        styleXButton(this, primary = false)
        setOnClickListener { action(this) }
    }

    val dialog = createAnimatedDialog()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setBackgroundColor(Color.rgb(15, 23, 36))
    }
    val topRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    topRow.addView(TextView(this).apply {
        text = "FULL KEYBOARD"
        textSize = 15f
        setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.05f
    }, LinearLayout.LayoutParams(0, dp(44), 1f))
    topRow.addView(control("DONE") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(90), dp(42)))
    root.addView(topRow)

    val controlRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    val volume = control("VOLUME ${(pianoVolume * 100).roundToInt()}%") { button ->
        pianoVolume = if (pianoVolume >= 0.99f) 0.2f else (pianoVolume + 0.2f).coerceAtMost(1f)
        updatePianoVolumeControls()
        button.text = "VOLUME ${(pianoVolume * 100).roundToInt()}%"
    }
    val octave = control(when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }) { button ->
        pianoTranspose = when (pianoTranspose) { 0 -> 12; 12 -> -12; else -> 0 }
        updatePianoOctaveControls()
        button.text = when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }
    }
    val reverb = createPianoReverbButton()
    fullScalePianoReverbButton = reverb
    controlRow.addView(volume, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
    controlRow.addView(octave, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
    controlRow.addView(reverb, LinearLayout.LayoutParams(0, dp(42), 1f))
    root.addView(controlRow, LinearLayout.LayoutParams(-1, dp(48)))

    val keys = PianoKeyboardView(this) { midiNote, pressed ->
        if (pressed) {
            stopTone()
            playPianoSample(midiNote)
        } else {
            releasePianoSample(midiNote)
        }
    }
    root.addView(keys, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
    dialog.setContentView(root)
    requireExplicitDialogAction(dialog)
    dialog.setOnDismissListener {
        keys.releaseAllPointers()
        if (fullScalePianoReverbButton === reverb) fullScalePianoReverbButton = null
        if (fullScaleKeysDialog === dialog) fullScaleKeysDialog = null
    }
    dialog.show()
    fullScaleKeysDialog = dialog
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        decorView.systemUiVisibility = window.decorView.systemUiVisibility
        addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }
}
