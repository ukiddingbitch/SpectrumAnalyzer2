package sk.spectrum.analyzer2

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

import kotlin.math.max
import kotlin.math.roundToInt

/** Coordinates Spectrum, Tone, Keys and full-scale layouts and their existing animations. */
internal fun MainActivity.installToneGeneratorSplit(toggleButton: Button, keysButton: Button) {
    val parent = spectrumView.parent as? ViewGroup ?: return
    fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    val index = parent.indexOfChild(spectrumView)
    val originalParams = spectrumView.layoutParams
    val originalParentTopPadding = parent.paddingTop
    val originalParentBottomPadding = parent.paddingBottom
    val toolbarButtonVerticalInset = dp(2)
    val tonePanelStepDown = dp(18).toFloat()
    val graphHeader = spectrumHeader?.takeIf { it.parent === parent }
    val originalHeaderIndex = graphHeader?.let { parent.indexOfChild(it) } ?: -1
    val originalHeaderLayoutParams = graphHeader?.layoutParams
    val graphHeaderHeight = (originalHeaderLayoutParams?.height ?: -1).takeIf { it > 0 } ?: dp(42)
    parent.removeView(spectrumView)

    val split = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.FILL
        setBackgroundColor(Color.rgb(15, 23, 36))
    }
    val graphColumn = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.FILL
        setBackgroundColor(Color.rgb(15, 23, 36))
    }
    spectrumView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    graphColumn.addView(spectrumView)
    val portraitControlsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    portraitGraphControlsRow = portraitControlsRow
    // The header stays above the graph in normal mode. Tone mode keeps it
    // at the same screen position but confines it to the graph column.
    graphColumn.addView(
        portraitControlsRow,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (42f * resources.displayMetrics.density).roundToInt()
        )
    )
    val toneGraphToolbarRow = android.widget.FrameLayout(this).apply {
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
    }
    val toneModeToolbarItems = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }
    val graphToolbarItems = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }
    toneGraphToolbarRow.addView(toneModeToolbarItems, android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.CENTER_VERTICAL or Gravity.START
    ))
    toneGraphToolbarRow.addView(graphToolbarItems, android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.CENTER_VERTICAL or Gravity.END
    ))
    graphColumn.addView(
        toneGraphToolbarRow,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
    )
    split.addView(graphColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f))
    val panel = createToneGeneratorPanel()
    tonePanel = panel
    // The panel fills the split height; tone mode hides the shared bottom action bar.
    split.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f))
    panel.visibility = View.GONE

    val keyboardSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.FILL
        setPadding(dp(4), dp(4), dp(4), dp(2))
        setBackgroundColor(Color.rgb(15, 23, 36))
    }
    keyboardSectionView = keyboardSection
    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(4))
    }
    keyboardControlsRow = controls
    val volumeCell = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), 0, dp(5), 0)
        background = GradientDrawable().apply {
            setColor(Color.rgb(22, 32, 42))
            cornerRadius = dp(13).toFloat()
        }
        contentDescription = "Piano volume"
    }
    val volumeLabel = TextView(this).apply {
        text = "${(pianoVolume * 100).roundToInt()}%"
        textSize = 10f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.rgb(225, 233, 243))
        gravity = Gravity.CENTER
        includeFontPadding = false
    }
    val volumeIcon = AnalyzerIconButton(
        this,
        if (pianoVolume <= 0f) AnalyzerIconButton.Icon.VOLUME_MUTED else AnalyzerIconButton.Icon.VOLUME
    ).apply {
        background = null
        backgroundTintList = null
        stateListAnimator = null
        minimumWidth = 0
        minimumHeight = 0
        isClickable = true
        isFocusable = true
        contentDescription = if (pianoVolume <= 0f) "Piano muted; tap to restore volume" else "Mute piano"
        setOnClickListener {
            if (pianoVolume > 0f) {
                pianoVolumeBeforeMute = pianoVolume
                pianoVolume = 0f
            } else {
                pianoVolume = pianoVolumeBeforeMute.coerceIn(0.01f, 1f)
            }
            updatePianoVolumeControls()
        }
    }
    val volumeSlider = SeekBar(this).apply {
        max = 100
        progress = (pianoVolume * 100).roundToInt().coerceIn(0, 100)
        contentDescription = "Piano volume level"
        progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(75, 196, 255))
        thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(130, 216, 255))
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(60, 76, 91))
    }
    volumeCell.addView(volumeIcon, LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.MATCH_PARENT))
    volumeCell.addView(volumeLabel, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
    volumeCell.addView(volumeSlider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            val nextVolume = progress.coerceIn(0, 100).toFloat() / 100f
            if (nextVolume == 0f && pianoVolume > 0f) pianoVolumeBeforeMute = pianoVolume
            if (nextVolume > 0f) pianoVolumeBeforeMute = nextVolume
            pianoVolume = nextVolume
            updatePianoVolumeControls()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    })
    pianoVolumeSlider = volumeSlider
    pianoVolumeLabel = volumeLabel
    pianoVolumeIconButton = volumeIcon
    val octaveCell = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        background = null
        contentDescription = "Piano octave"
    }
    val octaveButton = createPianoOctaveButton()
    pianoOctaveButton = octaveButton
    updatePianoOctaveControls()
    octaveCell.addView(octaveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val reverbButton = createPianoReverbButton()
    pianoReverbButton = reverbButton
    val octaveCellWidth = dp(48)
    val reverbCellWidth = dp(48)
    controls.addView(volumeCell, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
        marginEnd = dp(3)
    })
    controls.addView(octaveCell, LinearLayout.LayoutParams(octaveCellWidth, dp(40)).apply {
        marginEnd = dp(3)
    })
    controls.addView(reverbButton, LinearLayout.LayoutParams(reverbCellWidth, dp(40)))
    keyboardSection.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

    val keys = PianoKeyboardView(this) { midiNote, pressed ->
        if (pressed) {
            stopTone()
            playPianoSample(midiNote)
        } else {
            releasePianoSample(midiNote)
        }
    }
    keys.setTranspose(pianoTranspose)
    keyboardPanel = keys
    keyboardSection.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    keyboardSection.visibility = View.GONE
    split.addView(keyboardSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    splitContainer = split
    parent.addView(split, index, originalParams)
    controls.post { applyKeyboardVolumeSafeInset() }

    fun setKeysGroupButtonSize(height: Int, inset: Int) {
        keyboardModeButtonGroup?.let { group ->
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                params.height = height
                params.topMargin = inset
                params.bottomMargin = inset
                child.layoutParams = params
            }
        }
    }

    val toolbarWidthAnimators = mutableListOf<android.animation.ValueAnimator>()
    fun cancelToolbarWidthAnimations() {
        toolbarWidthAnimators.toList().forEach { it.cancel() }
        toolbarWidthAnimators.clear()
    }

    var keysControlsMerged = false
    var toolbarLayoutGeneration = 0
    fun setKeysModeGraphLayout(enabled: Boolean, animateRows: Boolean = false) {
        val mergeControls = keyboardModeOpen
        val fadeKeysControlsIn = animateRows && mergeControls
        val fadeRangeIn = animateRows && !soundModeOpen && !(mergeControls && fullScaleKeyboardOpen)
        if (keysControlsMerged == mergeControls) return
        val layoutGeneration = ++toolbarLayoutGeneration
        // Width animators retain the old LayoutParams object. Stop them before
        // reparenting toolbar controls so they cannot reapply stale row geometry.
        cancelToolbarWidthAnimations()
        val actionBar = bottomActionBar ?: return
        val actionRow = mainActionRow ?: return
        // Keep the outer action row fixed at its Spectrum height. KEYS controls
        // remain 40dp tall and centered inside it, so the bottom edge cannot jump.
        val rowHeight = dp(44)
        val keysControlHeight = dp(40)
        actionRow.animate().cancel()
        controls.animate().cancel()
        rangePickerButton?.animate()?.cancel()
        actionRow.alpha = 1f
        actionRow.translationY = 0f
        controls.alpha = 1f
        controls.translationY = 0f

        fun detach(view: View) {
            (view.parent as? ViewGroup)?.removeView(view)
        }
        actionRow.removeAllViews()
        if (mergeControls) {
            detach(controls)
            controls.visibility = View.VISIBLE
            controls.setPadding(0, 0, 0, 0)
            (volumeCell.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(200)
                height = dp(40)
                weight = 0f
                marginStart = 0
                marginEnd = dp(3)
                topMargin = 0
                bottomMargin = 0
            }.also { volumeCell.layoutParams = it }
            (octaveCell.layoutParams as LinearLayout.LayoutParams).apply {
                width = octaveCellWidth
                height = dp(40)
                weight = 0f
                marginStart = 0
                marginEnd = dp(3)
                topMargin = 0
                bottomMargin = 0
            }.also { octaveCell.layoutParams = it }
            (reverbButton.layoutParams as LinearLayout.LayoutParams).apply {
                width = reverbCellWidth
                height = dp(40)
                weight = 0f
                marginStart = 0
                marginEnd = 0
                topMargin = 0
                bottomMargin = 0
            }.also { reverbButton.layoutParams = it }

            fun addKeysToolbarItem(view: View, width: Int) {
                detach(view)
                actionRow.addView(view, LinearLayout.LayoutParams(width, keysControlHeight).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                })
            }
            addKeysToolbarItem(toggleButton, dp(48))
            keyboardModeButtonGroup?.let { group ->
                detach(group)
                setKeysGroupButtonSize(keysControlHeight, 0)
                actionRow.addView(group, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, keysControlHeight
                ).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            soundIdModeButton?.let { addKeysToolbarItem(it, dp(48)) }
            mainActionsSpacer?.let { spacer ->
                detach(spacer)
                actionRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            }
            axisToggleButton?.let { addKeysToolbarItem(it, dp(48)) }
            spectrogramModeButton?.let { addKeysToolbarItem(it, dp(48)) }
            resetDisplayButton?.let { addKeysToolbarItem(it, dp(48)) }
            controls.alpha = if (fadeKeysControlsIn) 0f else 1f
            actionBar.addView(controls, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, keysControlHeight, Gravity.CENTER
            ))

            graphHeader?.let { header ->
                (header.layoutParams as? ViewGroup.LayoutParams)?.let { params ->
                    params.height = dp(42)
                    header.layoutParams = params
                }
                rangePickerButton?.let { rangeButton ->
                    rangeButton.alpha = if (fadeRangeIn) 0f else 1f
                    detach(rangeButton)
                    rangeButton.visibility = if (fullScaleKeyboardOpen) View.GONE else View.VISIBLE
                    val width = rangeButton.layoutParams?.width?.takeIf { it > 0 }
                        ?: ViewGroup.LayoutParams.WRAP_CONTENT
                    header.addView(rangeButton, android.widget.FrameLayout.LayoutParams(
                        width, dp(42), Gravity.CENTER
                    ))
                }
            }
        } else {
            detach(controls)
            controls.visibility = if (animateRows) View.GONE else View.VISIBLE
            controls.setPadding(0, 0, 0, dp(4))
            (volumeCell.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0
                height = dp(40)
                weight = 1f
                marginStart = 0
                marginEnd = dp(3)
                topMargin = 0
                bottomMargin = 0
            }.also { volumeCell.layoutParams = it }
            setKeysGroupButtonSize(dp(40), dp(2))
            if (toneModeOpen) {
                // KEYS -> TONE: restore only the piano controls. The tone layout
                // takes the buttons directly; do not show an intermediate Spectrum row.
                keyboardSection.addView(controls, 0,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
                keysControlsMerged = false
                return
            }
            actionRow.addView(toggleButton, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
            })
            keyboardModeButtonGroup?.let { group ->
                actionRow.addView(group, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)
                ).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            soundIdModeButton?.let { button ->
                actionRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            mainActionsSpacer?.let { spacer ->
                actionRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            }
            axisToggleButton?.let { button ->
                actionRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            spectrogramModeButton?.let { button ->
                actionRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            resetDisplayButton?.let { button ->
                actionRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            keyboardSection.addView(
                controls,
                0,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            )
            graphHeader?.let { header ->
                (header.layoutParams as? ViewGroup.LayoutParams)?.let { params ->
                    params.height = graphHeaderHeight
                    header.layoutParams = params
                }
                rangePickerButton?.let { rangeButton ->
                    rangeButton.alpha = if (fadeRangeIn) 0f else 1f
                    detach(rangeButton)
                    val width = rangeButton.layoutParams?.width?.takeIf { it > 0 }
                        ?: ViewGroup.LayoutParams.WRAP_CONTENT
                    actionBar.addView(rangeButton, android.widget.FrameLayout.LayoutParams(
                        width, dp(38), Gravity.CENTER
                    ))
                    rangeButton.visibility = if (soundModeOpen) View.GONE else View.VISIBLE
                }
            }
        }
        (actionRow.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
            params.height = rowHeight
            actionRow.layoutParams = params
        }
        detach(actionBar)
        actionBar.visibility = View.VISIBLE
        parent.addView(actionBar, parent.childCount, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
        ))
        keysControlsMerged = mergeControls
        split.requestLayout()
        controls.post {
            if (layoutGeneration != toolbarLayoutGeneration) return@post
            if (fadeKeysControlsIn) {
                val fadeInterpolator = android.view.animation.DecelerateInterpolator(1.4f)
                controls.animate().cancel()
                controls.animate()
                    .alpha(1f)
                    .setDuration(240L)
                    .setInterpolator(fadeInterpolator)
                    .start()
            } else {
                controls.alpha = 1f
            }
            rangePickerButton?.takeIf { it.visibility == View.VISIBLE }?.let { rangeButton ->
                if (fadeRangeIn) {
                    val fadeInterpolator = android.view.animation.DecelerateInterpolator(1.4f)
                    rangeButton.animate().cancel()
                    rangeButton.animate()
                        .alpha(1f)
                        .setDuration(240L)
                        .setInterpolator(fadeInterpolator)
                        .start()
                } else {
                    rangeButton.alpha = 1f
                }
            }
            controls.translationY = 0f
            applyKeyboardVolumeSafeInset()
        }
    }
    switchKeysToolbarLayout = { enabled -> setKeysModeGraphLayout(enabled) }

    fun animateToolbarButtonWidths(
        previousWidths: Map<View, Int>,
        durationMs: Long = 240L,
        targetRangeTextSizeSp: Float? = null
    ) {
        val buttons = listOfNotNull(
            axisToggleButton,
            spectrogramModeButton,
            resetDisplayButton,
            rangePickerButton
        )
        buttons.forEach { button ->
            val params = button.layoutParams ?: return@forEach
            val targetWidth = if (params.width >= 0) {
                params.width
            } else {
                val height = params.height.takeIf { it > 0 } ?: dp(40)
                button.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                button.measuredWidth
            }
            val startWidth = previousWidths[button] ?: return@forEach
            if (startWidth <= 0 || targetWidth <= 0 || startWidth == targetWidth) return@forEach

            params.width = startWidth
            button.layoutParams = params
            val startTextSizePx = if (button === rangePickerButton && targetRangeTextSizeSp != null) {
                button.textSize
            } else {
                0f
            }
            val targetTextSizePx = targetRangeTextSizeSp?.let { it * resources.displayMetrics.scaledDensity } ?: 0f
            val animator = android.animation.ValueAnimator.ofInt(startWidth, targetWidth).apply {
                duration = durationMs
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                addUpdateListener { update ->
                    if (button === rangePickerButton && targetRangeTextSizeSp != null) {
                        val fraction = update.animatedFraction
                        button.setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_PX,
                            startTextSizePx + (targetTextSizePx - startTextSizePx) * fraction
                        )
                    }
                    // RANGE text can change while the frequency axis animates.
                    // Measure after updating the font, using the current label each frame.
                    val animatedWidth = update.animatedValue as Int
                    params.width = if (button === rangePickerButton) {
                        maxOf(animatedWidth, rangeButtonContentWidth(button))
                    } else animatedWidth
                    button.layoutParams = params
                    if (button === rangePickerButton && button.scrollX != 0) button.scrollTo(0, 0)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        (animation as? android.animation.ValueAnimator)?.let(toolbarWidthAnimators::remove)
                    }

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        (animation as? android.animation.ValueAnimator)?.let(toolbarWidthAnimators::remove)
                    }
                })
            }
            toolbarWidthAnimators += animator
            animator.start()
        }
    }

    fun prepareToneExitToolbarResize() {
        cancelToolbarWidthAnimations()
        val buttons = listOfNotNull(
            axisToggleButton,
            spectrogramModeButton,
            resetDisplayButton,
            rangePickerButton
        )
        val previousWidths = mutableMapOf<View, Int>()
        buttons.forEach { button ->
            previousWidths[button] = button.layoutParams?.width?.takeIf { it > 0 } ?: button.width
        }
        fun setTargetWidth(button: View?, width: Int) {
            button ?: return
            button.layoutParams?.let { params ->
                params.width = width
                button.layoutParams = params
            }
        }
        setTargetWidth(axisToggleButton, dp(48))
        setTargetWidth(spectrogramModeButton, dp(48))
        setTargetWidth(resetDisplayButton, dp(48))

        val rangeButton = rangePickerButton
        if (rangeButton != null) {
            val oldTextSizePx = rangeButton.textSize
            rangeButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            rangeButton.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(dp(40), View.MeasureSpec.EXACTLY)
            )
            val targetRangeWidth = rangeButton.measuredWidth
            rangeButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, oldTextSizePx)
            setTargetWidth(rangeButton, targetRangeWidth)
        }
        animateToolbarButtonWidths(previousWidths, durationMs = 320L, targetRangeTextSizeSp = 14f)
    }

    fun setToneModeGraphLayout(enabled: Boolean, animateButtonWidths: Boolean = true) {
        toolbarLayoutGeneration++
        val actionRow = mainActionRow ?: return
        val actionBar = bottomActionBar ?: return
        cancelToolbarWidthAnimations()
        val toolbarButtons = listOfNotNull(
            axisToggleButton,
            spectrogramModeButton,
            resetDisplayButton,
            rangePickerButton
        )
        val previousWidths = mutableMapOf<View, Int>()
        toolbarButtons.forEach { button ->
            previousWidths[button] = button.layoutParams?.width?.takeIf { it > 0 } ?: button.width
        }
        fun detach(view: View?) {
            (view?.parent as? ViewGroup)?.removeView(view)
        }
        panel.translationY = if (enabled) tonePanelStepDown else 0f
        if (enabled) {
            parent.setPadding(parent.paddingLeft, 0, parent.paddingRight, 0)
            (panel.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.topMargin = originalParentTopPadding
                params.bottomMargin = originalParentBottomPadding + toolbarButtonVerticalInset
                panel.layoutParams = params
            }
            (toneGraphToolbarRow.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.bottomMargin = originalParentBottomPadding
                toneGraphToolbarRow.layoutParams = params
            }
            actionRow.removeAllViews()
            graphHeader?.let { header ->
                detach(header)
                // Keep SPL at the same top-left position; peak readout now
                // aligns to the right edge of the graph column.
                graphColumn.addView(header, 0, LinearLayout.LayoutParams(
                    // Match KEYS so the graph top edge does not jump by 16 dp.
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
                ).apply { topMargin = originalParentTopPadding })
            }
            toneModeToolbarItems.removeAllViews()
            graphToolbarItems.removeAllViews()
            fun addToolbarItem(
                row: LinearLayout,
                view: View?,
                width: Int,
                height: Int = dp(40),
                horizontalMargin: Int = dp(3),
                verticalMargin: Int = toolbarButtonVerticalInset
            ) {
                if (view == null) return
                detach(view)
                if (view is AnalyzerIconButton) {
                    view.minWidth = 0
                    view.minimumWidth = 0
                }
                row.addView(view, LinearLayout.LayoutParams(width, height).apply {
                    setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin)
                })
            }
            addToolbarItem(toneModeToolbarItems, toggleButton, dp(48))
            setKeysGroupButtonSize(dp(40), toolbarButtonVerticalInset)
            addToolbarItem(
                toneModeToolbarItems,
                keyboardModeButtonGroup,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                height = dp(44),
                horizontalMargin = dp(3),
                verticalMargin = 0
            )
            addToolbarItem(toneModeToolbarItems, soundIdModeButton, dp(48))
            // From the outside edge inward: Reset, spectrum/spectrogram, LIN/LOG.
            addToolbarItem(graphToolbarItems, axisToggleButton, dp(48))
            addToolbarItem(graphToolbarItems, spectrogramModeButton, dp(48))
            addToolbarItem(graphToolbarItems, resetDisplayButton, dp(48))
            rangePickerButton?.let { rangeButton ->
                detach(rangeButton)
                rangeButton.visibility = View.VISIBLE
                rangeButton.textSize = 12f
                toneGraphToolbarRow.addView(rangeButton, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38), Gravity.CENTER
                ))
            }
            toneGraphToolbarRow.visibility = View.VISIBLE
            actionBar.visibility = View.GONE
        } else {
            (panel.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.topMargin = 0
                params.bottomMargin = 0
                panel.layoutParams = params
            }
            (toneGraphToolbarRow.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.bottomMargin = 0
                toneGraphToolbarRow.layoutParams = params
            }
            parent.setPadding(
                parent.paddingLeft,
                originalParentTopPadding,
                parent.paddingRight,
                originalParentBottomPadding
            )
            toneModeToolbarItems.removeAllViews()
            graphToolbarItems.removeAllViews()
            rangePickerButton?.let { detach(it) }
            toneGraphToolbarRow.visibility = View.GONE
            setKeysGroupButtonSize(dp(40), dp(2))
            graphHeader?.let { header ->
                detach(header)
                val restoreIndex = originalHeaderIndex.coerceIn(0, parent.childCount)
                parent.addView(header, restoreIndex, originalHeaderLayoutParams)
            }
            actionRow.removeAllViews()

            detach(toggleButton)
            actionRow.addView(toggleButton, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
            })
            keyboardModeButtonGroup?.let { group ->
                detach(group)
                actionRow.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                })
            }
            soundIdModeButton?.let { button ->
                detach(button)
                actionRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            mainActionsSpacer?.let { spacer ->
                detach(spacer)
                actionRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            }
            axisToggleButton?.let { button ->
                detach(button)
            actionRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            spectrogramModeButton?.let { button ->
                detach(button)
                actionRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            resetDisplayButton?.let { button ->
                detach(button)
            actionRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            rangePickerButton?.let { rangeButton ->
                detach(rangeButton)
                rangeButton.visibility = View.VISIBLE
                rangeButton.textSize = 14f
                actionBar.addView(rangeButton, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38), Gravity.CENTER
                ))
            }
            actionBar.visibility = View.VISIBLE
        }
        split.requestLayout()
        if (animateButtonWidths) animateToolbarButtonWidths(previousWidths)
    }

    var toneEntryAnimator: android.animation.ValueAnimator? = null
    var keysModeAnimator: android.animation.ValueAnimator? = null
    var fullScaleButtonReveal: android.animation.ValueAnimator? = null
    fun animateFullScaleToolbar(show: Boolean) {
        val button = fullScaleKeysButton ?: return
        if (!show && !button.isShown) return
        fullScaleButtonReveal?.cancel()
        val params = button.layoutParams as? LinearLayout.LayoutParams ?: return
        button.animate().cancel(); button.clipBounds = null; button.translationX = 0f
        button.visibility = View.VISIBLE
        fun applyProgress(progress: Float) {
            params.width = (dp(48) * progress).roundToInt()
            params.leftMargin = (dp(3) * progress).roundToInt()
            params.rightMargin = (dp(3) * progress).roundToInt()
            button.layoutParams = params
            button.alpha = progress
        }
        applyProgress(if (show) 0f else 1f)
        fullScaleButtonReveal = android.animation.ValueAnimator.ofFloat(if (show) 0f else 1f, if (show) 1f else 0f).apply {
            duration = 320L
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    button.visibility = if (show) View.VISIBLE else View.GONE
                    params.width = dp(48); params.leftMargin = dp(3); params.rightMargin = dp(3)
                    button.layoutParams = params; button.alpha = 1f
                    if (fullScaleButtonReveal === animation) fullScaleButtonReveal = null
                }
            })
            start()
        }
    }
    var axisModeAnimator: android.animation.ValueAnimator? = null
    var keysModeExitReady = false
    var modeTransitionLocked = false
    var modeTransitionUnlockRunnable: Runnable? = null
    val transitionControlClickability = mutableMapOf<View, Boolean>()
    fun releaseModeTransitionLock() {
        modeTransitionUnlockRunnable?.let(toggleButton::removeCallbacks)
        modeTransitionUnlockRunnable = null
        modeTransitionLocked = false
        modeTransitionInProgress = false
        transitionControlClickability.forEach { (view, wasClickable) -> view.isClickable = wasClickable }
        transitionControlClickability.clear()
    }
    fun lockModeControlsFor(durationMs: Long) {
        releaseModeTransitionLock()
        val transitionControls = listOfNotNull(
            toggleButton,
            keysButton,
            fullScaleKeysButton,
            soundIdModeButton,
            axisToggleButton,
            spectrogramModeButton,
            resetDisplayButton,
            rangePickerButton,
            headerSplText
        )
        transitionControls.forEach { control ->
            transitionControlClickability[control] = control.isClickable
            control.isClickable = false
        }
        modeTransitionLocked = true
        modeTransitionInProgress = true
        val unlock = object : Runnable {
            override fun run() {
                if (modeTransitionUnlockRunnable !== this) return
                modeTransitionUnlockRunnable = null
                modeTransitionLocked = false
                modeTransitionInProgress = false
                transitionControlClickability.forEach { (view, wasClickable) ->
                    view.isClickable = wasClickable
                }
                transitionControlClickability.clear()
            }
        }
        modeTransitionUnlockRunnable = unlock
        toggleButton.postDelayed(unlock, durationMs + 80L)
    }
    fun setGraphHeaderHeight(height: Int) {
        val header = graphHeader ?: return
        val params = header.layoutParams ?: return
        if (params.height != height) {
            params.height = height
            header.layoutParams = params
        }
        rangePickerButton?.takeIf { it.parent === header }?.let { rangeButton ->
            rangeButton.layoutParams?.let { rangeParams ->
                if (rangeParams.height != height) {
                    rangeParams.height = height
                    rangeButton.layoutParams = rangeParams
                }
            }
        }
    }
    fun applyCurrentWindowGeometry(enteringToneMode: Boolean = false) {
        val compactWindow = isCompactAppWindow()
        val portraitWindow = isAppWindowPortrait()
        val expandedPortrait = !compactWindow && portraitWindow
        val portraitSoundId = soundModeOpen && (compactWindow || portraitWindow)
        val verticalSplit = keyboardModeOpen || expandedPortrait || portraitSoundId || fullScaleKeyboardOpen

        updateAudioRouteButtonPlacement(portraitSoundId)
        spectrumView.setSoundIdPortraitLayoutActive(portraitSoundId)
        split.orientation = if (verticalSplit) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val graphParams = graphColumn.layoutParams as LinearLayout.LayoutParams
        val spectrumParams = spectrumView.layoutParams as LinearLayout.LayoutParams
        val panelParams = panel.layoutParams as LinearLayout.LayoutParams
        val keysParams = keyboardSection.layoutParams as LinearLayout.LayoutParams
        graphParams.width = if (verticalSplit) ViewGroup.LayoutParams.MATCH_PARENT else 0
        graphParams.height = if (verticalSplit) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        graphParams.weight = when {
            fullScaleKeyboardOpen -> 0f
            toneModeOpen && toneExpandFraction > 0f -> (if (verticalSplit) 1f else 1.35f) * (1f - toneExpandFraction)
            enteringToneMode && toneModeOpen -> 2.10f
            verticalSplit -> 1f
            else -> 1.35f
        }
        spectrumParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        spectrumParams.height = 0
        spectrumParams.weight = 1f
        panelParams.width = if (verticalSplit) ViewGroup.LayoutParams.MATCH_PARENT else 0
        panelParams.height = if (verticalSplit) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        panelParams.weight = when {
            !toneModeOpen -> 0f
            toneExpandFraction > 0f -> (if (verticalSplit) 1.15f else 0.75f) +
                (if (verticalSplit) 1f else 1.35f) * toneExpandFraction
            enteringToneMode -> 0f
            verticalSplit -> 1.15f
            else -> 0.75f
        }
        keysParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        keysParams.height = 0
        keysParams.weight = if (keyboardModeOpen || fullScaleKeyboardOpen) 1f else 0f
        graphColumn.layoutParams = graphParams
        spectrumView.layoutParams = spectrumParams
        panel.layoutParams = panelParams
        keyboardSection.layoutParams = keysParams

        (soundIdPanel as? View)?.let { soundPanel ->
            val params = soundPanel.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f)
            params.width = if (verticalSplit) ViewGroup.LayoutParams.MATCH_PARENT else 0
            params.height = if (verticalSplit) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            params.weight = if (soundModeOpen) 1f else 0f
            soundPanel.layoutParams = params
        }
        split.requestLayout()
    }
    adaptiveWindowLayoutUpdate = { applyCurrentWindowGeometry() }
    toggleToneExpanded = toggle@{
        if (!toneModeOpen || modeTransitionInProgress || toneExpansionAnimator?.isRunning == true) return@toggle
        if (!toneExpanded && toneSplTextSizePx == null) {
            headerSplText?.let { text ->
                val size = text.textSize
                toneSplTextSizePx = size
                text.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
            }
        }
        toneExpanded = !toneExpanded
        if (toneExpanded) freezeToneControlRowWidths(true)
        else configureExpandedToneControls?.invoke(false)
        toneExpandButton?.apply {
            isEnabled = false
            contentDescription = if (toneExpanded) "Restore tone generator" else "Expand tone generator to the left"
            invalidate()
        }
        lockModeControlsFor(460L)
        toneExpansionAnimator = android.animation.ValueAnimator.ofFloat(toneExpandFraction, if (toneExpanded) 1f else 0f).apply {
            duration = 460L
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener {
                toneExpandFraction = it.animatedValue as Float
                applyCurrentWindowGeometry()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (toneExpansionAnimator === animation) toneExpansionAnimator = null
                    toneExpandButton?.isEnabled = true
                    if (!toneExpanded) freezeToneControlRowWidths(false)
                    else if (!isFinishing && !isDestroyed) configureExpandedToneControls?.invoke(true)
                    releaseModeTransitionLock()
                }
            })
            start()
        }
    }
    fun showMode(mode: String) {
        // Ignore taps while controls are being reparented or animated. This
        // prevents touch targets from landing on LIN/LOG or Spectrum buttons.
        if (modeTransitionLocked && !keysModeExitReady) return
        // Capture before full-scale exit or layout changes hide the actual keys.
        if (keyboardModeOpen && mode == "tone") animateKeysClosingVisual()
        else clearKeysClosingVisual()
        if (mode != "tone") toneSplTextSizePx = null
        if (mode != "tone" && toneExpanded) {
            toneExpansionAnimator?.cancel(); toneExpansionAnimator = null
            toneExpanded = false; toneExpandFraction = 0f
            configureExpandedToneControls?.invoke(false)
            freezeToneControlRowWidths(false)
            toneExpandButton?.apply {
                contentDescription = "Expand tone generator to the left"; invalidate()
            }
        }
        if (keysModeExitReady) releaseModeTransitionLock()
        if (mode == "tone" && fullScaleKeyboardOpen) {
            // Exit full scale straight into Tone without rebuilding the KEYS toolbar first.
            fullScaleTransitionAnimator?.cancel()
            fullScaleTransitionAnimator = null
            fullScaleKeyboardOpen = false
            (fullScaleKeysButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.FULL_SCALE_OPEN
            fullScaleKeysButton?.isEnabled = true
            graphColumn.visibility = View.VISIBLE
            spectrumView.visibility = View.VISIBLE
        }
        cancelToolbarWidthAnimations()
        // Finish any previous layout transition before starting a new mode change.
        // This prevents cancelled Fade transitions from leaving a keyboard ghost.
        android.transition.TransitionManager.endTransitions(parent)
        axisModeAnimator?.cancel()
        axisModeAnimator = null
        toneEntryAnimator?.cancel()
        toneEntryAnimator = null
        keysModeAnimator?.cancel()
        keysModeAnimator = null
        fullScaleButtonReveal?.cancel()
        fullScaleButtonReveal = null
        panel.animate().cancel()
        mainActionRow?.animate()?.cancel()
        controls.animate().cancel()
        rangePickerButton?.animate()?.cancel()
        keyboardSection.animate().cancel()
        keyboardSection.alpha = 1f
        keyboardSection.scaleY = 1f
        keyboardSection.translationY = 0f
        keyboardSection.visibility = if (keyboardModeOpen) View.VISIBLE else View.GONE

        if (keyboardModeOpen && mode != "keys" && mode != "tone" && !keysModeExitReady) {
            animateFullScaleToolbar(false)
            if (fullScaleKeyboardOpen) leaveFullScaleKeyboard(animate = false)
            // Hide the merged volume/octave/reverb controls as soon as KEYS is exited.
            controls.animate().cancel()
            controls.visibility = View.GONE
            keysModeAnimator?.cancel()
            keysModeAnimator = null
            setKeysModeGraphLayout(false, animateRows = true)
            split.orientation = LinearLayout.VERTICAL
            keyboardSection.visibility = View.VISIBLE
            val graphParams = graphColumn.layoutParams as LinearLayout.LayoutParams
            val keysParams = keyboardSection.layoutParams as LinearLayout.LayoutParams
            graphParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            graphParams.height = 0
            graphParams.weight = 1f
            keysParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            keysParams.height = 0
            keysParams.weight = 1f
            graphColumn.layoutParams = graphParams
            keyboardSection.layoutParams = keysParams
            keyboardSection.alpha = 1f
            keyboardSection.scaleY = 1f
            keyboardSection.translationY = 0f
            split.requestLayout()
            spectrumView.beginKeysFrequencyRangeExitTransition()
            val exitDbRangeStart = spectrumView.getVisibleDbRangeForTransition()
            val exitDbRangeTarget = spectrumView.getDbRangeTargetForMode(mode.uppercase(java.util.Locale.ROOT))
            val exitHeaderHeightStart = graphHeader?.layoutParams?.height ?: graphHeaderHeight
            lockModeControlsFor(360L)
            var wasCancelled = false
            val closingAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 360L
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                addUpdateListener { animator ->
                    val progress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                    val transitionProgress = 1f - progress
                    spectrumView.setKeysFrequencyRangeExitProgress(transitionProgress)
                    spectrumView.setDbRangeForTransition(exitDbRangeStart, exitDbRangeTarget, transitionProgress)
                    setGraphHeaderHeight((exitHeaderHeightStart +
                            (graphHeaderHeight - exitHeaderHeightStart) * transitionProgress).roundToInt())
                    keysParams.weight = progress
                    keyboardSection.layoutParams = keysParams
                    keyboardSection.alpha = progress
                    keyboardSection.translationY = dp(16).toFloat() * (1f - progress)
                    split.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        wasCancelled = true
                        releaseModeTransitionLock()
                        spectrumView.cancelKeysFrequencyRangeExitTransition()
                        spectrumView.setDbRangeForTransition(exitDbRangeStart, exitDbRangeStart, 1f)
                        setGraphHeaderHeight(exitHeaderHeightStart)
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (wasCancelled) {
                            releaseModeTransitionLock()
                            return
                        }
                        releaseModeTransitionLock()
                        keysModeAnimator = null
                        keyboardSection.visibility = View.GONE
                        keyboardSection.alpha = 1f
                        keyboardSection.translationY = 0f
                        controls.alpha = 1f
                        controls.translationY = 0f
                        keysModeExitReady = true
                        showMode(mode)
                        keysModeExitReady = false
                    }
                })
            }
            keysModeAnimator = closingAnimator
            closingAnimator.start()
            return
        }
        val wasSoundModeOpen = soundModeOpen
        val wasKeyboardModeOpen = keyboardModeOpen
        val wasToneModeOpen = toneModeOpen
        val toneToKeysToolbarStarts = if (wasToneModeOpen && mode == "keys") {
            listOfNotNull(toggleButton, keyboardModeButtonGroup, soundIdModeButton,
                axisToggleButton, spectrogramModeButton, resetDisplayButton, rangePickerButton)
                .filter { it.isShown }.associateWith { view ->
                    IntArray(3).also { at ->
                        view.getLocationOnScreen(at)
                        at[2] = view.width
                    }
                }
        } else emptyMap()
        val toneRangeTextSize = rangePickerButton?.textSize
        val enteringToneMode = !wasToneModeOpen && mode == "tone"
        val enteringKeysMode = !wasKeyboardModeOpen && mode == "keys"
        val closingToneMode = wasToneModeOpen && mode == "none"
        val leavingKeysMode = wasKeyboardModeOpen && mode != "keys"
        val transitioningBetweenToneAndKeys =
            (wasToneModeOpen && mode == "keys") || (wasKeyboardModeOpen && mode == "tone")
        spectrumView.animate().cancel()
        val graphStartBounds = if (transitioningBetweenToneAndKeys && spectrumView.width > 0 && spectrumView.height > 0) {
            val at = IntArray(2); spectrumView.getLocationOnScreen(at)
            android.graphics.Rect(at[0], at[1], at[0] + spectrumView.width, at[1] + spectrumView.height)
        } else null
        val expandedPortraitToneLayout = !isCompactAppWindow() && isAppWindowPortrait()
        val toneGraphFinalWeight = if (expandedPortraitToneLayout) 1f else 1.35f
        val tonePanelFinalWeight = if (expandedPortraitToneLayout) 1.15f else 0.75f
        val transitionLockDuration = when {
            transitioningBetweenToneAndKeys -> 460L
            enteringKeysMode -> 360L
            enteringToneMode || closingToneMode -> 380L
            leavingKeysMode -> 360L
            else -> 0L
        }
        if (transitionLockDuration > 0L) lockModeControlsFor(transitionLockDuration)
        val startingTonePanelTranslationY = panel.translationY
        val axisFrequencyStart = if (enteringKeysMode || transitioningBetweenToneAndKeys) {
            spectrumView.getVisibleFrequencyRangeForTransition()
        } else null
        val axisDbStart = if (enteringKeysMode || transitioningBetweenToneAndKeys) {
            spectrumView.getVisibleDbRangeForTransition()
        } else null
        val axisHeaderHeightStart = graphHeader?.layoutParams?.height ?: graphHeaderHeight
        var axisFrequencyTarget: Pair<Double, Double>? = null
        var axisDbTarget: Pair<Int, Int>? = null
        var axisHeaderHeightTarget = axisHeaderHeightStart
        if (transitioningBetweenToneAndKeys) {
            keysModeAnimator?.cancel()
            keysModeAnimator = null
            mainActionRow?.let { row ->
                row.animate().cancel()
                row.alpha = 1f
                row.translationY = 0f
            }
            controls.animate().cancel()
            controls.alpha = 1f
            controls.translationY = 0f
            // Do not use TransitionManager for a hierarchy whose controls are reparented.
            // ChangeBounds suppresses layout on graphColumn/split and can leave the
            // rebuilt toolbar empty or clipped until its animator releases the parent.
            // Geometry settles in this frame; the dedicated axis and panel animators below
            // keep the mode change animated without suppressing toolbar layout.
            listOfNotNull(bottomActionBar, mainActionRow, controls, toneGraphToolbarRow,
                toneModeToolbarItems, graphToolbarItems).forEach { row ->
                row.animate().cancel()
                row.alpha = 1f
                row.translationY = 0f
            }
        }
        if (closingToneMode) prepareToneExitToolbarResize()
        val leavingKeysForTone = wasKeyboardModeOpen && mode == "tone"
        val keysGroup = keyboardModeButtonGroup
        val keysGroupTransition = keysGroup?.layoutTransition
        if (leavingKeysForTone) {
            keysGroup?.layoutTransition = null
            fullScaleKeysButton?.visibility = View.VISIBLE
        }
        toneModeOpen = mode == "tone"
        keyboardModeOpen = mode == "keys"
        soundModeOpen = mode == "sound"
        if (enteringToneMode) {
            panel.animate().cancel()
            panel.alpha = 0f
            panel.scaleX = 0.96f
            panel.scaleY = 0.97f
            panel.translationX = dp(18).toFloat()
            panel.pivotX = 0f
        } else if (!toneModeOpen && !closingToneMode) {
            panel.animate().cancel()
            panel.alpha = 1f
            panel.scaleX = 1f
            panel.scaleY = 1f
            panel.translationX = 0f
        }
        // Collapse Full Scale before moving the group into Tone's toolbar.
        if (wasKeyboardModeOpen && !keyboardModeOpen) {
            setKeysModeGraphLayout(false, animateRows = !transitioningBetweenToneAndKeys)
        }
        if (wasToneModeOpen != toneModeOpen && !closingToneMode) {
            setToneModeGraphLayout(toneModeOpen, animateButtonWidths = !transitioningBetweenToneAndKeys)
        }
        if (enteringKeysMode) {
            setKeysModeGraphLayout(true, animateRows = !transitioningBetweenToneAndKeys)
        }
        if (enteringKeysMode) {
            axisHeaderHeightTarget = graphHeader?.layoutParams?.height ?: axisHeaderHeightStart
            if (!transitioningBetweenToneAndKeys) setGraphHeaderHeight(axisHeaderHeightStart)
        }
        if (transitioningBetweenToneAndKeys) {
            panel.animate().cancel()
            panel.translationY = startingTonePanelTranslationY
            panel.animate()
                .translationY(if (toneModeOpen) tonePanelStepDown else 0f)
                .setDuration(460L)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .start()
        }
        if (leavingKeysForTone) keysGroup?.layoutTransition = keysGroupTransition
        if (keyboardModeOpen) {
            pianoAudioShutdownHandler.removeCallbacks(pianoAudioShutdownRunnable)
            initializePianoSamples()
        } else if (wasKeyboardModeOpen) {
            schedulePianoAudioShutdown()
        }
        requestAnalyzerOrientation(soundId = soundModeOpen)
        fullScaleKeyboardOpen = false
        val animateModeAxes = enteringKeysMode || transitioningBetweenToneAndKeys
        spectrumView.setKeysFrequencyRangeActive(keyboardModeOpen, notify = !animateModeAxes)
        spectrumView.setDbRangeMode(when {
            toneModeOpen -> "TONE"
            keyboardModeOpen -> "KEYS"
              soundModeOpen -> "SOUND"
            else -> "DEFAULT"
        })
        if (animateModeAxes) {
            axisFrequencyTarget = spectrumView.getVisibleFrequencyRangeForTransition()
            axisDbTarget = spectrumView.getVisibleDbRangeForTransition()
            val frequencyStart = axisFrequencyStart
            val frequencyTarget = axisFrequencyTarget
            if (frequencyStart != null && frequencyTarget != null) {
                spectrumView.setFrequencyRangeForTransition(frequencyStart, frequencyTarget, 0f)
            }
            val dbStart = axisDbStart
            val dbTarget = axisDbTarget
            if (dbStart != null && dbTarget != null) {
                spectrumView.setDbRangeForTransition(dbStart, dbTarget, 0f)
            }
        }
        if (transitioningBetweenToneAndKeys && !enteringKeysMode) {
            val frequencyStart = axisFrequencyStart
            val frequencyTarget = axisFrequencyTarget
            val dbStart = axisDbStart
            val dbTarget = axisDbTarget
            if (frequencyStart != null && frequencyTarget != null && dbStart != null && dbTarget != null) {
                var wasCancelled = false
                axisModeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 460L
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        val progress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                        spectrumView.setFrequencyRangeForTransition(frequencyStart, frequencyTarget, progress)
                        spectrumView.setDbRangeForTransition(dbStart, dbTarget, progress)
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            wasCancelled = true
                        }

                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (!wasCancelled) {
                                spectrumView.setFrequencyRangeForTransition(frequencyStart, frequencyTarget, 1f)
                                spectrumView.setDbRangeForTransition(dbStart, dbTarget, 1f)
                                spectrumView.notifyVisibleFrequencyRangeChanged()
                            }
                            if (axisModeAnimator === animation) axisModeAnimator = null
                        }
                    })
                    start()
                }
            }
        }
        panel.visibility = if (toneModeOpen || closingToneMode) View.VISIBLE else View.GONE
        soundIdPanel?.visibility = if (soundModeOpen) View.VISIBLE else View.GONE
        keyboardSection.visibility = if (keyboardModeOpen) View.VISIBLE else View.GONE
        spectrumView.visibility = View.VISIBLE
        val portraitControls = listOfNotNull(spectrogramModeButton, axisToggleButton, resetDisplayButton)
        val landscapeControls = listOfNotNull(axisToggleButton, spectrogramModeButton, resetDisplayButton)
        if (soundModeOpen && !wasSoundModeOpen) {
            portraitControls.forEach { control ->
                (control.parent as? ViewGroup)?.removeView(control)
                portraitGraphControlsRow?.addView(control)
            }
        } else if (!soundModeOpen && wasSoundModeOpen && !toneModeOpen) {
            landscapeControls.forEach { control ->
                (control.parent as? ViewGroup)?.removeView(control)
                mainActionRow?.addView(control)
            }
        }
        portraitGraphControlsRow?.visibility = if (soundModeOpen) View.VISIBLE else View.GONE
        rangePickerButton?.visibility = if (soundModeOpen) View.GONE else View.VISIBLE
        if (keyboardModeOpen && enteringKeysMode) {
            keyboardSection.alpha = 0f
            keyboardSection.scaleY = 0.98f
            keyboardSection.translationY = dp(16).toFloat()
        }
        (panel.layoutParams as? LinearLayout.LayoutParams)?.let { tonePanelParams ->
            if (toneModeOpen || closingToneMode) {
                tonePanelParams.topMargin = originalParentTopPadding
                tonePanelParams.bottomMargin = originalParentBottomPadding + toolbarButtonVerticalInset
            } else {
                tonePanelParams.topMargin = 0
                tonePanelParams.bottomMargin = 0
            }
            panel.layoutParams = tonePanelParams
        }
        applyCurrentWindowGeometry(enteringToneMode && !transitioningBetweenToneAndKeys)
        mainActionRow?.let { row ->
            row.gravity = if (soundModeOpen) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            val routeButtonInPortraitSoundId = soundModeOpen &&
                    (isCompactAppWindow() || isAppWindowPortrait())
            for (index in 0 until row.childCount) {
                val child = row.getChildAt(index)
                child.visibility = if (
                    !soundModeOpen ||
                    child === soundIdModeButton ||
                    (routeButtonInPortraitSoundId && child === audioRoutePickerButton)
                ) View.VISIBLE else View.GONE
            }
        }
        if (keysControlsMerged) {
            controls.visibility = if (soundModeOpen) View.GONE else View.VISIBLE
        }
        toggleButton.text = ""
        toggleButton.contentDescription = "Tone generator"
        styleFlatToolbarButton(toggleButton)
        styleFlatToolbarButton(keysButton)
        soundIdModeButton?.let(this@installToneGeneratorSplit::styleFlatToolbarButton)
        if (!leavingKeysForTone) {
            fullScaleKeysButton?.visibility = if (keyboardModeOpen) View.VISIBLE else View.GONE
        }
        if (!toneModeOpen && !keyboardModeOpen) {
            stopTone()
            resetTonePlayButton()
        } else if (keyboardModeOpen) {
            stopTone()
            resetTonePlayButton()
        }
        split.requestLayout()
        if (enteringKeysMode) {
            val graphParams = graphColumn.layoutParams as LinearLayout.LayoutParams
            val keysParams = keyboardSection.layoutParams as LinearLayout.LayoutParams
            val frequencyStart = axisFrequencyStart
            val frequencyTarget = axisFrequencyTarget
            val dbStart = axisDbStart
            val dbTarget = axisDbTarget
            keysModeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (transitioningBetweenToneAndKeys) 460L else 360L
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                addUpdateListener { animator ->
                    val progress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                    if (!transitioningBetweenToneAndKeys) {
                        graphParams.weight = 1f
                        keysParams.weight = progress
                    }
                    if (frequencyStart != null && frequencyTarget != null) {
                        spectrumView.setFrequencyRangeForTransition(frequencyStart, frequencyTarget, progress)
                    }
                    if (dbStart != null && dbTarget != null) {
                        spectrumView.setDbRangeForTransition(dbStart, dbTarget, progress)
                    }
                    setGraphHeaderHeight((axisHeaderHeightStart +
                            (axisHeaderHeightTarget - axisHeaderHeightStart) * progress).roundToInt())
                    graphColumn.layoutParams = graphParams
                    keyboardSection.layoutParams = keysParams
                    keyboardSection.alpha = progress
                    keyboardSection.scaleY = 0.98f + 0.02f * progress
                    keyboardSection.translationY = dp(16).toFloat() * (1f - progress)
                    split.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        if (frequencyStart != null && frequencyTarget != null) {
                            spectrumView.setFrequencyRangeForTransition(frequencyStart, frequencyTarget, 1f)
                        }
                        if (dbStart != null && dbTarget != null) {
                            spectrumView.setDbRangeForTransition(dbStart, dbTarget, 1f)
                        }
                        setGraphHeaderHeight(axisHeaderHeightTarget)
                        releaseModeTransitionLock()
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (keysModeAnimator === animation) keysModeAnimator = null
                        if (frequencyStart != null && frequencyTarget != null) {
                            spectrumView.setFrequencyRangeForTransition(frequencyStart, frequencyTarget, 1f)
                            spectrumView.notifyVisibleFrequencyRangeChanged()
                        }
                        if (dbStart != null && dbTarget != null) {
                            spectrumView.setDbRangeForTransition(dbStart, dbTarget, 1f)
                        }
                        setGraphHeaderHeight(axisHeaderHeightTarget)
                        keyboardSection.alpha = 1f
                        keyboardSection.scaleY = 1f
                        keyboardSection.translationY = 0f
                        releaseModeTransitionLock()
                    }
                })
                start()
            }
        }
        if (enteringToneMode && !transitioningBetweenToneAndKeys) {
            val graphParams = graphColumn.layoutParams as LinearLayout.LayoutParams
            val panelParams = panel.layoutParams as LinearLayout.LayoutParams
            toneEntryAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380L
                interpolator = android.view.animation.OvershootInterpolator(0.45f)
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    graphParams.weight = 2.10f + (toneGraphFinalWeight - 2.10f) * progress
                    panelParams.weight = tonePanelFinalWeight * progress
                    graphColumn.layoutParams = graphParams
                    panel.layoutParams = panelParams
                    panel.alpha = progress.coerceIn(0f, 1f)
                    panel.scaleX = 0.96f + 0.04f * progress
                    panel.scaleY = 0.97f + 0.03f * progress
                    panel.translationX = dp(18).toFloat() * (1f - progress)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        releaseModeTransitionLock()
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        releaseModeTransitionLock()
                    }
                })
                start()
            }
        } else if (enteringToneMode && transitioningBetweenToneAndKeys) {
            // The graph has its own bounds morph. Animate only Tone content here,
            // leaving the independently managed toolbar and graph geometry untouched.
            toneEntryAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 460L
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    panel.alpha = progress
                    panel.scaleX = 0.96f + 0.04f * progress
                    panel.scaleY = 0.97f + 0.03f * progress
                    panel.translationX = dp(18).toFloat() * (1f - progress)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        panel.alpha = 1f; panel.scaleX = 1f; panel.scaleY = 1f; panel.translationX = 0f
                        if (toneEntryAnimator === animation) toneEntryAnimator = null
                        releaseModeTransitionLock()
                    }
                })
                start()
            }
        } else if (closingToneMode) {
            val graphParams = graphColumn.layoutParams as LinearLayout.LayoutParams
            val panelParams = panel.layoutParams as LinearLayout.LayoutParams
            toneEntryAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380L
                interpolator = android.view.animation.OvershootInterpolator(0.45f)
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    val layoutProgress = progress.coerceIn(0f, 1f)
                    graphParams.weight = toneGraphFinalWeight + (2.10f - toneGraphFinalWeight) * layoutProgress
                    panelParams.weight = tonePanelFinalWeight * (1f - layoutProgress)
                    graphColumn.layoutParams = graphParams
                    panel.layoutParams = panelParams
                    panel.alpha = (1f - progress).coerceIn(0f, 1f)
                    panel.scaleX = 1f - 0.04f * progress
                    panel.scaleY = 1f - 0.03f * progress
                    panel.translationX = dp(18).toFloat() * progress
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var wasCancelled = false

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        wasCancelled = true
                        releaseModeTransitionLock()
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (wasCancelled || toneModeOpen) {
                            releaseModeTransitionLock()
                            return
                        }
                        toneEntryAnimator = null
                        panel.visibility = View.GONE
                        panel.alpha = 1f
                        panel.scaleX = 1f
                        panel.scaleY = 1f
                        panel.translationX = 0f
                        graphParams.weight = toneGraphFinalWeight
                        panelParams.weight = tonePanelFinalWeight
                        graphColumn.layoutParams = graphParams
                        panel.layoutParams = panelParams
                        applyCurrentWindowGeometry()
                        setToneModeGraphLayout(false)
                        split.requestLayout()
                        releaseModeTransitionLock()
                    }
                })
                start()
            }
        }
        if (enteringKeysMode) {
            // This entry fade owns alpha; invalidate the older toolbar-layout post.
            toolbarLayoutGeneration++
            controls.animate().cancel()
            controls.alpha = 0f
            animateFullScaleToolbar(true)
            parent.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (parent.viewTreeObserver.isAlive) parent.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!keyboardModeOpen) return true
                    // Reveal piano controls after the keys finish opening, with a separate fade.
                    controls.animate().cancel()
                    controls.alpha = 0f
                    controls.animate().alpha(1f)
                        .setStartDelay(if (transitioningBetweenToneAndKeys) 460L else 360L).setDuration(260L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
                    return true
                }
            })
        }
        if (leavingKeysForTone) animateFullScaleToolbar(false)
        if (graphStartBounds != null) {
            parent.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (parent.viewTreeObserver.isAlive) parent.viewTreeObserver.removeOnPreDrawListener(this)
                    if (keyboardModeOpen != enteringKeysMode || toneModeOpen != enteringToneMode ||
                        spectrumView.width <= 0 || spectrumView.height <= 0) return true
                    val at = IntArray(2); spectrumView.getLocationOnScreen(at)
                    // Animate only the live graph surface. Toolbar parents keep normal layout.
                    val unclipped = listOf(graphColumn, split, parent).distinct().map { it to it.clipChildren }
                    unclipped.forEach { (view, _) -> view.clipChildren = false }
                    spectrumView.pivotX = 0f; spectrumView.pivotY = 0f
                    spectrumView.scaleX = graphStartBounds.width().toFloat() / spectrumView.width
                    spectrumView.scaleY = graphStartBounds.height().toFloat() / spectrumView.height
                    spectrumView.translationX = (graphStartBounds.left - at[0]).toFloat()
                    spectrumView.translationY = (graphStartBounds.top - at[1]).toFloat()
                    spectrumView.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                        .setDuration(460L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                        .setListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                spectrumView.scaleX = 1f; spectrumView.scaleY = 1f
                                spectrumView.translationX = 0f; spectrumView.translationY = 0f
                                unclipped.forEach { (view, original) -> view.clipChildren = original }
                                spectrumView.animate().setListener(null)
                            }
                        }).start()
                    return true
                }
            })
        }
        if (toneToKeysToolbarStarts.isNotEmpty()) {
            // Same duration, width/text resize and easing as Tone -> Spectrum.
            rangePickerButton?.let { button ->
                toneRangeTextSize?.let { button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it) }
            }
            animateToolbarButtonWidths(toneToKeysToolbarStarts.mapValues { it.value[2] },
                durationMs = 320L, targetRangeTextSizeSp = 14f)
            parent.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (parent.viewTreeObserver.isAlive) parent.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!keyboardModeOpen || toneModeOpen) return true
                    // Animate only the controls, without suppressing layout on their parents.
                    toneToKeysToolbarStarts.forEach { (view, start) ->
                        if (view.isShown && view !== rangePickerButton && view !== soundIdModeButton) {
                            val end = IntArray(2); view.getLocationOnScreen(end)
                            view.animate().cancel()
                            view.translationX += start[0] - end[0]
                            view.translationY += start[1] - end[1]
                            view.animate().translationX(0f).translationY(0f)
                                .setDuration(320L)
                                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
                        }
                    }
                    return true
                }
            })
        }
        spectrumView.invalidate()
        if (keyboardModeOpen) keyboardControlsRow?.post { applyKeyboardVolumeSafeInset() }
        keyboardPanel?.post { applyPianoKeyboardSafeInsets() }
    }
    switchAnalyzerMode = ::showMode
    applyCurrentWindowGeometry()
    toggleButton.setOnClickListener {
        showMode(if (toneModeOpen) "none" else "tone")
    }
    keysButton.setOnClickListener {
        if (fullScaleKeyboardOpen) {
            leaveFullScaleKeyboard(animate = false)
            showMode("none")
            return@setOnClickListener
        }
        showMode(if (keyboardModeOpen) "none" else "keys")
    }
}
