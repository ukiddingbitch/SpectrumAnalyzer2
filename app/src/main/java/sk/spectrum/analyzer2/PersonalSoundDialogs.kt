package sk.spectrum.analyzer2

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import kotlin.math.roundToInt

/** Personal sound profile dialogs, recording and naming. */
internal fun MainActivity.showPersonalSoundExportInfo() {
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
        text = "Export recordings to review or share"
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })
    content.addView(TextView(this).apply {
        text = "Choose an export folder the first time. New recordings are saved there as individual WAV files named after their sound. Already exported samples are skipped, so each export adds only new recordings."
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(10), 0, dp(14))
    })
    val done = Button(this).apply {
        text = "Got it"
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(39, 113, 147))
            cornerRadius = dp(14).toFloat()
        }
    }
    content.addView(done, LinearLayout.LayoutParams(-1, dp(44)))
    val dialog = createAnimatedDialog()
    dialog.setContentView(content)
    requireExplicitDialogAction(dialog)
    done.setOnClickListener { dialog.dismiss() }
    showDialogCenteredOnScreen(dialog)
}

internal fun MainActivity.showDeletePersonalSoundDialog(
    profile: PersonalSoundProfile,
    sampleIds: Set<String>,
    onDeleted: () -> Unit
) {
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
        text = "Delete saved sound?"
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })
    content.addView(TextView(this).apply {
        val sampleCount = sampleIds.size
        text = if (sampleCount == 1) "Remove “${profile.name}” and its saved recording from this phone?"
        else "Remove “${profile.name}” and all $sampleCount saved recordings from this phone?"
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(8), 0, dp(14))
    })
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    val dialog = createAnimatedDialog()
    actions.addView(Button(this).apply {
        text = "Cancel"
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(42, 57, 75))
            cornerRadius = dp(14).toFloat()
        }
        setOnClickListener { dialog.dismiss() }
    }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
    actions.addView(Button(this).apply {
        text = "Delete"
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(164, 69, 80))
            cornerRadius = dp(14).toFloat()
        }
        setOnClickListener {
            if (personalPreviewKey?.removePrefix("saved:")?.let { it in sampleIds } == true) stopPersonalSoundPreview()
            soundIdEngine.removePersonalSounds(profile.name)
            onDeleted()
            dialog.dismiss()
        }
    }, LinearLayout.LayoutParams(-2, dp(44)))
    content.addView(actions)
    dialog.setContentView(content)
    requireExplicitDialogAction(dialog)
    showDialogCenteredOnScreen(dialog)
}

internal fun MainActivity.showMySoundIdsDialog() {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    fun panel(color: Int, radius: Int = 15) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = panel(Color.rgb(12, 18, 27), 20)
    }
    lateinit var managerDialog: Dialog
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val title = TextView(this).apply {
        text = "My Sound ID"
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    }
    header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(Button(this).apply {
        text = "Done"
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = panel(Color.rgb(39, 113, 147), 14)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { managerDialog.dismiss() }
    }, LinearLayout.LayoutParams(-2, dp(40)))
    content.addView(header)
    content.addView(TextView(this).apply {
        text = "Saved sounds · recordings are grouped by name"
        textSize = 12f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(10), 0, dp(4))
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val listScroll = android.widget.ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(list)
    }
    fun refreshList() {
        list.removeAllViews()
        val profiles = soundIdEngine.getPersonalSounds()
        val rowHeightDp = maxOf(54, (22f * resources.configuration.fontScale + 8f).roundToInt())
        if (profiles.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No personal sounds yet. Record a short sample, then give it a name."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(10))
            })
        } else profiles.asReversed()
            .groupBy { it.name.trim().lowercase(java.util.Locale.ROOT) }
            .values
            .forEach { samples ->
            val profile = samples.first()
            val groupIds = samples.map { it.id }.toSet()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(rowHeightDp)
                clipChildren = false
                clipToPadding = false
                setPadding(dp(12), dp(2), dp(7), dp(2))
                background = panel(Color.rgb(30, 41, 55), 13)
            }
            row.addView(TextView(this).apply {
                text = "〰   ${profile.name}\n${samples.size} sample${if (samples.size == 1) "" else "s"}"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = true
                setPadding(0, dp(2), 0, dp(10))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "▶"
                isAllCaps = false
                textSize = 15f
                setTextColor(Color.WHITE)
                background = panel(Color.TRANSPARENT, 12)
                contentDescription = "Play saved recording: ${profile.name}"
                setOnClickListener { view ->
                    togglePersonalSoundPreview(profile.id, profile.name, view as Button)
                }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.addView(Button(this).apply {
                text = "+"
                isAllCaps = false
                textSize = 19f
                setTextColor(Color.rgb(105, 220, 185))
                background = panel(Color.TRANSPARENT, 12)
                contentDescription = "Add another sample for ${profile.name}"
                setOnClickListener {
                    showPersonalSoundRecorderDialog({ refreshList() }, profile.name)
                }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.addView(Button(this).apply {
                text = "✎"
                isAllCaps = false
                textSize = 17f
                setTextColor(Color.rgb(135, 205, 255))
                background = panel(Color.TRANSPARENT, 12)
                contentDescription = "Rename ${profile.name}"
                setOnClickListener { showRenamePersonalSoundDialog(profile, samples.size) { refreshList() } }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.addView(Button(this).apply {
                text = "×"
                isAllCaps = false
                textSize = 18f
                setTextColor(Color.LTGRAY)
                background = panel(Color.TRANSPARENT, 12)
                contentDescription = "Delete ${profile.name}"
                setOnClickListener {
                    showDeletePersonalSoundDialog(profile, groupIds) { refreshList() }
                }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
        }
        val soundCount = profiles.asReversed().groupBy { it.name.trim().lowercase(java.util.Locale.ROOT) }.size
        val visibleRows = if (profiles.isEmpty()) 1 else minOf(soundCount, 3)
        listScroll.layoutParams = LinearLayout.LayoutParams(-1, dp(visibleRows * (rowHeightDp + 7)))
        listScroll.isVerticalScrollBarEnabled = soundCount > 3
    }
    content.addView(listScroll)
    content.addView(Button(this).apply {
        text = "＋  Add a sound"
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = panel(Color.rgb(39, 113, 147), 16)
        setOnClickListener { showPersonalSoundRecorderDialog(onSaved = { refreshList() }) }
    }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
    val fileActions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, 0)
    }
    fileActions.addView(AnalyzerIconButton(this, AnalyzerIconButton.Icon.IMPORT).apply {
        background = panel(Color.rgb(42, 57, 75), 13)
        setOnClickListener { importPersonalSoundProfiles() }
    }, LinearLayout.LayoutParams(dp(46), dp(42)).apply { marginEnd = dp(7) })
    fileActions.addView(AnalyzerIconButton(this, AnalyzerIconButton.Icon.EXPORT).apply {
        background = panel(Color.rgb(42, 57, 75), 13)
        setOnClickListener { exportPersonalSoundProfiles() }
    }, LinearLayout.LayoutParams(dp(46), dp(42)).apply { marginEnd = dp(7) })
    fileActions.addView(Button(this).apply {
        text = "i"
        isAllCaps = false
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(Color.WHITE)
        contentDescription = "Information about exporting recordings"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(42, 57, 75))
            setStroke(dp(1), Color.rgb(94, 116, 139))
        }
        setOnClickListener { showPersonalSoundExportInfo() }
    }, LinearLayout.LayoutParams(dp(42), dp(42)))
    content.addView(fileActions)
    refreshList()
    val refreshCallback: () -> Unit = { refreshList() }
    personalSoundListRefresh = refreshCallback
    managerDialog = createAnimatedDialog()
    managerDialog.setContentView(content)
    managerDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    managerDialog.setCancelable(false)
    managerDialog.setCanceledOnTouchOutside(false)
    managerDialog.setOnDismissListener {
        if (personalSoundListRefresh === refreshCallback) personalSoundListRefresh = null
    }
    showDialogCenteredOnScreen(managerDialog, title, anchorTop = soundIdPanel)
}

internal fun MainActivity.showRenamePersonalSoundDialog(profile: PersonalSoundProfile, sampleCount: Int, onRenamed: () -> Unit) {
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
        text = "Rename saved sound"
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })
    val nameInput = EditText(this).apply {
        setText(profile.name)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        textSize = 16f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        background = GradientDrawable().apply {
            setColor(Color.rgb(30, 41, 55))
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(14), 0, dp(14), 0)
    }
    content.addView(nameInput, LinearLayout.LayoutParams(-1, dp(48)).apply {
        topMargin = dp(12)
        bottomMargin = dp(if (sampleCount > 1) 5 else 12)
    })
    if (sampleCount > 1) content.addView(TextView(this).apply {
        text = "This name applies to all $sampleCount samples."
        textSize = 12f
        setTextColor(Color.LTGRAY)
        setPadding(dp(2), 0, 0, dp(10))
    })
    val dialog = createAnimatedDialog()
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
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
    val saveName: () -> Unit = {
        val name = nameInput.text.toString().trim()
        if (name.isBlank()) {
            nameInput.error = "Enter a name"
        } else {
            val renamedIds = soundIdEngine.renamePersonalSounds(profile.name, name)
            if (renamedIds.isNotEmpty()) {
                if (personalPreviewKey?.removePrefix("saved:")?.let { it in renamedIds } == true) {
                    personalPreviewButton?.contentDescription = "Play saved recording: $name"
                }
                onRenamed()
                dialog.dismiss()
                Toast.makeText(this, "Renamed to $name", Toast.LENGTH_SHORT).show()
            }
        }
    }
    actions.addView(Button(this).apply {
        text = "Save"
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(39, 132, 166))
            cornerRadius = dp(14).toFloat()
        }
        setOnClickListener { saveName() }
    }, LinearLayout.LayoutParams(0, dp(44), 1f))
    content.addView(actions)
    dialog.setContentView(content)
    requireExplicitDialogAction(dialog)
    val renameDialogWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(260))
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        setGravity(Gravity.CENTER)
        setLayout(renameDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.setOnShowListener {
        nameInput.requestFocus()
        nameInput.selectAll()
        nameInput.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(nameInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
    nameInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
            saveName()
            true
        } else false
    }
    dialog.show()
    dialog.window?.setLayout(renameDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
}

internal fun MainActivity.showPersonalSoundRecorderDialog(onSaved: () -> Unit, initialName: String? = null) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.rgb(14, 21, 31))
            cornerRadius = dp(22).toFloat()
        }
    }
    lateinit var dialog: Dialog
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(TextView(this).apply {
        text = "Record a sound"
        textSize = 19f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    val closeButton = Button(this).apply {
        text = "×"
        isAllCaps = false
        textSize = 21f
        setTextColor(Color.LTGRAY)
        background = GradientDrawable().apply {
            setColor(Color.rgb(35, 46, 61))
            cornerRadius = dp(14).toFloat()
        }
        contentDescription = "Close"
        setOnClickListener { dialog.dismiss() }
    }
    header.addView(closeButton, LinearLayout.LayoutParams(dp(42), dp(42)))
    content.addView(header)
    val instruction = TextView(this).apply {
        text = "Choose a length, then play a clear example of the sound near your phone."
        textSize = 13f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(8), 0, dp(12))
    }
    val countdown = TextView(this).apply {
        text = "Ready · 10 seconds"
        textSize = 19f
        setTextColor(Color.rgb(75, 196, 255))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.rgb(25, 36, 51))
            cornerRadius = dp(16).toFloat()
        }
        setPadding(dp(8), dp(15), dp(8), dp(15))
    }
    var selectedRecordingSeconds = PERSONAL_SOUND_SAMPLE_SECONDS
    fun durationButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minHeight = dp(42)
        minimumHeight = dp(42)
        setPadding(dp(5), 0, dp(5), 0)
        setTextColor(Color.WHITE)
    }
    val fiveSecondButton = durationButton("5 s · Quick")
    val tenSecondButton = durationButton("10 s · Recommended")
    fun updateDurationChoices() {
        fiveSecondButton.background = GradientDrawable().apply {
            setColor(if (selectedRecordingSeconds == 5) Color.rgb(39, 113, 147) else Color.rgb(30, 41, 55))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), if (selectedRecordingSeconds == 5) Color.rgb(75, 196, 255) else Color.rgb(56, 73, 91))
        }
        tenSecondButton.background = GradientDrawable().apply {
            setColor(if (selectedRecordingSeconds == 10) Color.rgb(39, 113, 147) else Color.rgb(30, 41, 55))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), if (selectedRecordingSeconds == 10) Color.rgb(75, 196, 255) else Color.rgb(56, 73, 91))
        }
    }
    val durationChoices = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    durationChoices.addView(fiveSecondButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
    durationChoices.addView(tenSecondButton, LinearLayout.LayoutParams(0, dp(42), 1.25f))
    updateDurationChoices()
    val record = Button(this).apply {
        text = "●   Start recording"
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(39, 113, 147))
            cornerRadius = dp(17).toFloat()
        }
    }
    fiveSecondButton.setOnClickListener {
        if (record.isEnabled) {
            selectedRecordingSeconds = 5
            updateDurationChoices()
            countdown.text = "Ready · 5 seconds"
        }
    }
    tenSecondButton.setOnClickListener {
        if (record.isEnabled) {
            selectedRecordingSeconds = 10
            updateDurationChoices()
            countdown.text = "Ready · 10 seconds"
        }
    }
    content.addView(instruction)
    content.addView(durationChoices, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })
    content.addView(countdown, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    content.addView(record, LinearLayout.LayoutParams(-1, dp(50)))
    dialog = createAnimatedDialog()
    dialog.setContentView(content)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    requireExplicitDialogAction(dialog)
    val handler = Handler(Looper.getMainLooper())
    var cancelled = false
    var handingOffToNaming = false
    var countdownEndsAtElapsedRealtime = 0L
    var recordingRemaining = 10
    var recordingStarted = false
    lateinit var captureTimeout: Runnable
    captureTimeout = Runnable {
        if (dialog.isShowing && !cancelled) {
            soundIdEngine.cancelPersonalSoundCapture()
            setLiveSoundPredictionsVisible(true)
            countdown.text = "No usable sample captured. Try again."
            record.isEnabled = true
            fiveSecondButton.isEnabled = true
            tenSecondButton.isEnabled = true
            record.text = "●  Record again"
        }
    }
    lateinit var ticker: Runnable
    ticker = object : Runnable {
        override fun run() {
            if (cancelled || !dialog.isShowing) return
            val now = SystemClock.elapsedRealtime()
            when {
                now < countdownEndsAtElapsedRealtime -> {
                    val remainingMs = countdownEndsAtElapsedRealtime - now
                    val remainingHundredths = (remainingMs + 9L) / 10L
                    countdown.text = String.format(
                        java.util.Locale.US,
                        "Starts in %d.%02d s",
                        remainingHundredths / 100L,
                        remainingHundredths % 100L
                    )
                    handler.postDelayed(this, 10L)
                }
                !recordingStarted -> {
                    recordingStarted = true
                    countdown.text = "Recording · $selectedRecordingSeconds seconds remaining"
                    if (!soundIdActive) soundIdToggleButton?.performClick()
                    soundIdEngine.beginPersonalSoundCapture(selectedRecordingSeconds)
                    handler.postDelayed(captureTimeout, 20_000L)
                    handler.postDelayed(this, 1000L)
                }
                recordingRemaining > 0 -> {
                    recordingRemaining--
                    countdown.text = "Recording · $recordingRemaining seconds remaining"
                    handler.postDelayed(this, 1000L)
                }
                else -> countdown.text = "Finishing sample…"
            }
        }
    }
    record.setOnClickListener {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
        }
        setLiveSoundPredictionsVisible(false)
        record.isEnabled = false
        fiveSecondButton.isEnabled = false
        tenSecondButton.isEnabled = false
        countdownEndsAtElapsedRealtime = SystemClock.elapsedRealtime() + 3_000L
        recordingRemaining = selectedRecordingSeconds
        recordingStarted = false
        handler.post(ticker)
    }
    soundIdEngine.setPersonalSoundCapturedListener { embedding, audio ->
        runOnUiThread {
            if (dialog.isShowing) {
                cancelled = true
                handler.removeCallbacks(ticker)
                handler.removeCallbacks(captureTimeout)
                soundIdEngine.setPersonalSoundCapturedListener(null)
                handingOffToNaming = true
                dialog.dismiss()
                showPersonalSoundNamingDialog(embedding, audio, onSaved, initialName)
            }
        }
    }
    dialog.setOnDismissListener {
        cancelled = true
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(captureTimeout)
        soundIdEngine.cancelPersonalSoundCapture()
        soundIdEngine.setPersonalSoundCapturedListener(null)
        if (!handingOffToNaming) setLiveSoundPredictionsVisible(true)
    }
    showDialogCenteredOnScreen(dialog)
}

internal fun MainActivity.showPersonalSoundNamingDialog(
    embedding: FloatArray,
    audio: ShortArray,
    onSaved: () -> Unit,
    initialName: String? = null
) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(14, 21, 31))
            cornerRadius = dp(22).toFloat()
        }
    }
    lateinit var dialog: Dialog
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(TextView(this).apply {
        text = "Name this sound"
        textSize = 19f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    val closeButton = Button(this).apply {
        text = "×"
        isAllCaps = false
        textSize = 21f
        setTextColor(Color.LTGRAY)
        background = GradientDrawable().apply {
            setColor(Color.rgb(35, 46, 61))
            cornerRadius = dp(14).toFloat()
        }
        contentDescription = "Close"
    }
    header.addView(closeButton, LinearLayout.LayoutParams(dp(42), dp(42)))
    content.addView(header)
    content.addView(TextView(this).apply {
        text = "Listen, name it, and save."
        textSize = 12f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(4), 0, dp(7))
    })
    val input = EditText(this).apply {
        hint = "e.g. Elephant"
        setText(initialName?.takeIf { it.isNotBlank() } ?: soundIdEngine.suggestPersonalSoundName())
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        textSize = 16f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        background = GradientDrawable().apply {
            setColor(Color.rgb(30, 41, 55))
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(14), 0, dp(14), 0)
    }
    val preview = Button(this).apply {
        text = "▶   Play sample"
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(42, 57, 75))
            cornerRadius = dp(14).toFloat()
        }
        setOnClickListener { view ->
            togglePersonalSoundPreview(audio, "recorded sample", view as Button)
        }
    }
    content.addView(preview, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(7) })
    val inputRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    inputRow.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(7) })
    val save = Button(this).apply {
        text = "✓"
        isAllCaps = false
        textSize = 22f
        setTextColor(Color.WHITE)
        contentDescription = "Save sound sample"
        background = GradientDrawable().apply {
            setColor(Color.rgb(39, 132, 166))
            cornerRadius = dp(16).toFloat()
        }
    }
    inputRow.addView(save, LinearLayout.LayoutParams(dp(48), dp(46)))
    content.addView(inputRow)

    fun saveSample() {
        val soundName = input.text.toString().trim()
        if (soundName.isEmpty()) {
            input.error = "Enter a name"
            input.requestFocus()
        } else {
            soundIdEngine.savePersonalSound(soundName, embedding, audio)
            Toast.makeText(this, "Saved $soundName", Toast.LENGTH_SHORT).show()
            onSaved()
            setLiveSoundPredictionsVisible(true)
            dialog.dismiss()
        }
    }
    closeButton.setOnClickListener {
        val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        input.clearFocus()
        inputManager?.hideSoftInputFromWindow(input.windowToken, 0)

        val confirmationContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 21, 31))
                cornerRadius = dp(20).toFloat()
            }
        }
        confirmationContent.addView(TextView(this).apply {
            text = "Discard this recording?"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        confirmationContent.addView(TextView(this).apply {
            text = "Are you sure you want to leave naming? The unsaved recording will be lost."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(14))
        })
        val confirmation = createAnimatedDialog()
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(Button(this).apply {
            text = "Keep editing"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 57, 75))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                confirmation.dismiss()
                input.requestFocus()
                input.post { inputManager?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        actions.addView(Button(this).apply {
            text = "Discard"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(164, 69, 80))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                if (personalPreviewKey == "pending:${System.identityHashCode(audio)}") {
                    stopPersonalSoundPreview()
                }
                setLiveSoundPredictionsVisible(true)
                confirmation.dismiss()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(0, dp(44), 0.75f))
        confirmationContent.addView(actions)
        confirmation.setContentView(confirmationContent)
        confirmation.setCanceledOnTouchOutside(false)
        confirmation.setCancelable(false)
        showDialogCenteredOnScreen(confirmation, widthFraction = 0.92f)
    }
    save.setOnClickListener { saveSample() }
    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
            saveSample()
            true
        } else false
    }
    dialog = createAnimatedDialog()
    dialog.setContentView(content)
    val namingDialogWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(260))
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        setGravity(Gravity.CENTER)
        setLayout(namingDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.setCanceledOnTouchOutside(false)
    dialog.setCancelable(false)
    dialog.setOnShowListener {
        input.requestFocus()
        input.setSelection(0, input.text.length)
        input.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
    dialog.show()
    dialog.window?.setLayout(namingDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
}
