package sk.spectrum.analyzer2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog

import kotlin.math.roundToInt

/** Input/output device selection, route changes and route dialogs. */
private data class LaunchScreenChoice(val key: String, val label: String)

private val launchScreenChoices = listOf(
    LaunchScreenChoice("tone", "Tone generator"),
    LaunchScreenChoice("tone_full", "Tone generator · Full scale"),
    LaunchScreenChoice("keys", "Keys"),
    LaunchScreenChoice("keys_full", "Keys · Full scale"),
    LaunchScreenChoice("spectrum", "Spectrum"),
    LaunchScreenChoice("spectrogram", "Spectrogram"),
    LaunchScreenChoice("spl", "SPL"),
    LaunchScreenChoice("sound", "Sound ID")
)

internal fun MainActivity.defaultLaunchScreenKey(): String =
    getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        .getString("default_launch_screen", "spectrum")
        ?.takeIf { key -> launchScreenChoices.any { it.key == key } } ?: "spectrum"

private fun MainActivity.launchScreenLabel(key: String): String =
    launchScreenChoices.firstOrNull { it.key == key }?.label ?: "Spectrum"

internal fun MainActivity.ensureAudioDeviceCallbackRegistered() {
    if (audioDeviceCallback != null) return
    val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val callback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            val inputWasAdded = addedDevices.any { it.isSource }
            val outputWasAdded = addedDevices.any { it.isSink }
            audioRouteDialogRefresh?.invoke()
            if (outputWasAdded) {
                listOfNotNull(toneTrack, sweepMeasurementTrack, pianoAudioTrack, personalPreviewTrack)
                    .forEach { applyPreferredOutputRoute(it) }
            }
            if (inputWasAdded) scheduleAudioRouteRestart(clearBuiltInPreference = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
            val inputWasRemoved = removedDevices.any { it.isSource }
            val outputWasRemoved = removedDevices.any { it.isSink }
            if (preferredInputDeviceId in removedDevices.map { it.id }) preferredInputDeviceId = null
            if (preferredOutputDeviceId in removedDevices.map { it.id }) preferredOutputDeviceId = null
            audioRouteDialogRefresh?.invoke()
            if (outputWasRemoved) {
                listOfNotNull(toneTrack, sweepMeasurementTrack, pianoAudioTrack, personalPreviewTrack)
                    .forEach { applyPreferredOutputRoute(it) }
            }
            if (inputWasRemoved) scheduleAudioRouteRestart(clearBuiltInPreference = true)
        }
    }
    try {
        manager.registerAudioDeviceCallback(callback, audioRouteHandler)
        audioDeviceCallback = callback
    } catch (_: RuntimeException) {
        // Keep normal capture working if the platform refuses route callbacks.
    }
}

internal fun MainActivity.unregisterAudioDeviceCallback() {
    val callback = audioDeviceCallback ?: return
    val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    try {
        manager?.unregisterAudioDeviceCallback(callback)
    } catch (_: RuntimeException) {
    }
    audioDeviceCallback = null
    audioRouteDialogRefresh = null
    audioRouteDialog = null
    pendingAudioRouteRestart?.let(audioRouteHandler::removeCallbacks)
    pendingAudioRouteRestart = null
}

/** Restart capture after the Android audio route has changed, without dropping a Sound ID session. */
internal fun MainActivity.scheduleAudioRouteRestart(
    forceBuiltInMicrophone: Boolean = false,
    clearBuiltInPreference: Boolean = false,
    expectedCaptureGeneration: Long? = null
) {
    if (forceBuiltInMicrophone) preferBuiltInMicrophone = true
    if (clearBuiltInPreference) preferBuiltInMicrophone = false
    audioRouteHandler.post {
        if (!running || isFinishing ||
            (expectedCaptureGeneration != null && expectedCaptureGeneration != audioCaptureGeneration)
        ) return@post
        pendingAudioRouteRestart?.let(audioRouteHandler::removeCallbacks)
        val restart = Runnable {
            pendingAudioRouteRestart = null
            if (!running || isFinishing || audioRestartInProgress ||
                (expectedCaptureGeneration != null && expectedCaptureGeneration != audioCaptureGeneration)
            ) return@Runnable
            audioRestartInProgress = true
            val oldThread = audioThread
            stopAnalysis(preserveSoundId = soundIdActive, preserveGraph = true)
            Thread({
                try {
                    oldThread?.join(1500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                runOnUiThread {
                    audioRestartInProgress = false
                    if (!isFinishing && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startAnalysis(preserveGraph = true)
                    }
                    audioRouteDialogRefresh?.invoke()
                }
            }, "AudioRouteRecovery").start()
        }
        pendingAudioRouteRestart = restart
        audioRouteHandler.postDelayed(restart, 450L)
    }
}

internal fun MainActivity.updateAudioRouteButtonPlacement(inPortraitSoundId: Boolean) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val routeButton = audioRoutePickerButton ?: return
    val headerGroup = audioRouteHeaderGroup ?: return
    val actionRow = mainActionRow ?: return
    val targetParent: ViewGroup = if (inPortraitSoundId) actionRow else headerGroup
    if (routeButton.parent === targetParent) return

    (routeButton.parent as? ViewGroup)?.removeView(routeButton)
    val routeParams = LinearLayout.LayoutParams(dp(30), dp(26))
    if (inPortraitSoundId) {
        routeParams.setMargins(dp(8), 0, 0, 0)
        val soundButtonIndex = actionRow.indexOfChild(soundIdModeButton)
        val insertionIndex = if (soundButtonIndex >= 0) soundButtonIndex + 1 else actionRow.childCount
        actionRow.addView(routeButton, insertionIndex.coerceAtMost(actionRow.childCount), routeParams)
    } else {
        routeParams.setMargins(0, 0, dp(3), 0)
        // The menu now owns both app information and audio routing options.
        headerGroup.addView(routeButton, 0.coerceAtMost(headerGroup.childCount), routeParams)
    }
    updateSpectrumHeaderWidths()
}

internal fun MainActivity.availableAudioDevices(input: Boolean): List<android.media.AudioDeviceInfo> {
    val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
    val flag = if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
    return try {
        manager.getDevices(flag).filter { if (input) it.isSource else it.isSink }
            .sortedWith(compareBy<android.media.AudioDeviceInfo>({ audioDeviceTypeLabel(it, input) }, { it.productName.toString() }))
    } catch (_: RuntimeException) {
        emptyList()
    }
}

internal fun MainActivity.audioDeviceTypeLabel(device: android.media.AudioDeviceInfo, input: Boolean): String = when (device.type) {
    android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> if (input) "Wired headset microphone" else "Wired headset"
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
    android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
    android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> if (input) "USB audio input" else "USB audio output"
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> if (input) "Bluetooth microphone" else "Bluetooth headset"
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth speaker"
    else -> if (input) "Audio input" else "Audio output"
}

internal fun MainActivity.audioDeviceDisplayName(device: android.media.AudioDeviceInfo, input: Boolean): String {
    val product = try { device.productName?.toString()?.trim().orEmpty() } catch (_: RuntimeException) { "" }
    val fallback = audioDeviceTypeLabel(device, input)
    return if (product.isBlank() || product.equals("unknown", ignoreCase = true) || product.equals(fallback, ignoreCase = true)) {
        fallback
    } else {
        "$product · $fallback"
    }
}

internal fun MainActivity.currentInputRouteName(): String {
    val active = try {
        recorder?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }?.routedDevice
    } catch (_: RuntimeException) {
        null
    }
    val selected = active ?: preferredInputDeviceId?.let { id -> availableAudioDevices(input = true).firstOrNull { it.id == id } }
    return selected?.let { audioDeviceDisplayName(it, input = true) } ?: "System default"
}

internal fun MainActivity.currentOutputRouteName(): String {
    val tracks = listOfNotNull(toneTrack, sweepMeasurementTrack, pianoAudioTrack, personalPreviewTrack)
    val active = tracks.firstNotNullOfOrNull { track ->
        try {
            track.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.routedDevice
        } catch (_: RuntimeException) {
            null
        }
    }
    val selected = active ?: preferredOutputDeviceId?.let { id -> availableAudioDevices(input = false).firstOrNull { it.id == id } }
    return selected?.let { audioDeviceDisplayName(it, input = false) } ?: "System default"
}

internal fun MainActivity.applyPreferredOutputRoute(track: AudioTrack?): Boolean {
    track ?: return true
    val device = preferredOutputDeviceId?.let { id -> availableAudioDevices(input = false).firstOrNull { it.id == id } }
    if (preferredOutputDeviceId != null && device == null) return false
    return try {
        track.setPreferredDevice(device)
    } catch (_: RuntimeException) {
        false
    }
}

internal fun MainActivity.showAudioRouteDialog() {
    audioRouteDialog?.takeIf { it.isShowing }?.let { return }
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val safeBounds = availableWindowBounds()
    val dialog = createAnimatedDialog()
    audioRouteDialog = dialog
    val sizingPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    var widestLinePx = 0f
    fun measureLine(text: String, sp: Float, bold: Boolean = false) {
        sizingPaint.textSize = sp * resources.displayMetrics.scaledDensity
        sizingPaint.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        widestLinePx = maxOf(widestLinePx, sizingPaint.measureText(text))
    }
    measureLine("Spectrum Analyzer 2 · ${installedAppVersionName()}", 16f, bold = true)
    measureLine("RESET TO DEFAULT", 14f)
    measureLine("Default screen on launch", 14f)
    (availableAudioDevices(true).map { audioDeviceDisplayName(it, true) } +
        availableAudioDevices(false).map { audioDeviceDisplayName(it, false) } +
        "System default" + launchScreenChoices.map { it.label }).forEach { measureLine(it, 13f) }
    val desiredWidth = widestLinePx + dp(44)
    val width = minOf(dp(400), maxOf(dp(260), desiredWidth.roundToInt()),
        (safeBounds.width() - dp(28)).coerceAtLeast(dp(1)))
    val cardHeight = dp(390)
    lateinit var mainCard: LinearLayout

    fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        styleNumericDialogCard(this)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = ViewGroup.LayoutParams(width, cardHeight)
    }

    fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.rgb(255, 213, 90))
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    fun action(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = if (label == "RESET TO DEFAULT") 11f else 14f
        setTextColor(Color.rgb(255, 213, 90))
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        background = GradientDrawable().apply {
            setColor(Color.rgb(39, 54, 72)); cornerRadius = dp(11).toFloat()
        }
        setOnClickListener { onClick() }
    }

    fun showSubcard(content: (LinearLayout, Dialog) -> Unit) {
        val subCard = makeCard()
        content(subCard, dialog)
        dialog.setContentView(subCard)
        centerDialogOnScreen(dialog, width, height = cardHeight)
    }

    fun returnToMenu(subDialog: Dialog) {
        val defaultRow = (mainCard.getChildAt(1) as? LinearLayout)?.getChildAt(0) as? LinearLayout
        (defaultRow?.getChildAt(1) as? TextView)?.text = launchScreenLabel(defaultLaunchScreenKey())
        subDialog.setContentView(mainCard)
        centerDialogOnScreen(subDialog, width, height = cardHeight)
    }

    mainCard = makeCard()
    mainCard.addView(title("Spectrum Analyzer 2 · ${installedAppVersionName()}"),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
    val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun addMenuOption(label: String, detail: String, action: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 23, 36)); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(56, 73, 91))
            }
            setOnClickListener { action() }
        }
        item.addView(TextView(this).apply {
            text = label; textSize = 14f; setTextColor(Color.rgb(255, 213, 90)); includeFontPadding = false
        })
        item.addView(TextView(this).apply {
            text = detail; textSize = 11f; setTextColor(Color.rgb(153, 169, 188)); includeFontPadding = false
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        options.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(7)
        })
    }

    addMenuOption("Default screen on launch", launchScreenLabel(defaultLaunchScreenKey())) {
        showSubcard { subCard, subDialog ->
            subCard.addView(title("Default screen on launch"), LinearLayout.LayoutParams(-1, dp(30)))
            val choicesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            launchScreenChoices.forEach { choice ->
                val option = TextView(this).apply {
                    text = choice.label
                    textSize = 13f
                    setTextColor(if (defaultLaunchScreenKey() == choice.key) Color.rgb(255, 213, 90) else Color.rgb(220, 228, 237))
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    minHeight = dp(30)
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                    contentDescription = choice.label
                    background = GradientDrawable().apply {
                        setColor(if (defaultLaunchScreenKey() == choice.key) Color.rgb(39, 54, 72) else Color.rgb(15, 23, 36))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.rgb(56, 73, 91))
                    }
                    setOnClickListener { selectLaunchChoice(choice.key, choice.label, choicesList) }
                }
                choicesList.addView(option, LinearLayout.LayoutParams(-1, dp(30)).apply {
                    setMargins(dp(2), dp(1), dp(2), dp(1))
                })
            }
            subCard.addView(choicesList, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2) })
            subCard.addView(action("DONE") { returnToMenu(subDialog) }, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(3) })
        }
    }
    addMenuOption("Input (Microphone)", currentInputRouteName()) {
        showSubcard { subCard, subDialog -> buildAudioDeviceSubcard(subCard, subDialog, input = true, returnToMenu = ::returnToMenu) }
    }
    addMenuOption("Output (Speakers)", currentOutputRouteName()) {
        showSubcard { subCard, subDialog -> buildAudioDeviceSubcard(subCard, subDialog, input = false, returnToMenu = ::returnToMenu) }
    }
    mainCard.addView(options, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
    val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    footer.addView(action("RESET TO DEFAULT") {
        getSharedPreferences("app_preferences", Context.MODE_PRIVATE).edit()
            .putString("default_launch_screen", "spectrum").apply()
        preferredInputDeviceId = null
        preferredOutputDeviceId = null
        preferBuiltInMicrophone = false
        try { recorder?.setPreferredDevice(null) } catch (_: RuntimeException) {}
        listOfNotNull(toneTrack, sweepMeasurementTrack, pianoAudioTrack, personalPreviewTrack)
            .forEach(this@showAudioRouteDialog::applyPreferredOutputRoute)
        dialog.dismiss()
        window.decorView.post { if (!isFinishing && !isDestroyed) showAudioRouteDialog() }
    }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
    footer.addView(action("DONE") { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
    mainCard.addView(footer, LinearLayout.LayoutParams(-1, dp(40)).apply {
        topMargin = dp(8)
        bottomMargin = dp(6)
    })
    dialog.setContentView(mainCard)
    requireExplicitDialogAction(dialog)
    dialog.setOnDismissListener { if (audioRouteDialog === dialog) audioRouteDialog = null }
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.window?.setDimAmount(0.38f)
    centerDialogOnScreen(dialog, width, height = cardHeight)
    dialog.show()
}

private fun MainActivity.selectLaunchChoice(key: String, label: String, grid: ViewGroup) {
    getSharedPreferences("app_preferences", Context.MODE_PRIVATE).edit()
        .putString("default_launch_screen", key).apply()
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    for (rowIndex in 0 until grid.childCount) {
        val tile = grid.getChildAt(rowIndex)
        val selected = tile.contentDescription == label
        tile.setBackground(GradientDrawable().apply {
            setColor(if (selected) Color.rgb(39, 54, 72) else Color.rgb(15, 23, 36))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(56, 73, 91))
        })
        if (tile is TextView) {
            tile.setTextColor(if (selected) Color.rgb(255, 213, 90) else Color.rgb(220, 228, 237))
        }
    }
}

private fun MainActivity.buildAudioDeviceSubcard(
    card: LinearLayout,
    dialog: Dialog,
    input: Boolean,
    returnToMenu: (Dialog) -> Unit
) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    card.addView(TextView(this).apply {
        text = if (input) "Input (Microphone)" else "Output (Speakers)"
        textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.rgb(255, 213, 90)); gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(-1, dp(34)))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { addView(list) }
    val devices = availableAudioDevices(input)
    val selectedId = if (input) preferredInputDeviceId else preferredOutputDeviceId
    val choices = listOf<android.media.AudioDeviceInfo?>(null) + devices
    choices.forEach { device ->
        val selected = device?.id == selectedId || (device == null && selectedId == null)
        val label = device?.let { audioDeviceDisplayName(it, input) } ?: "System default"
        val item = TextView(this).apply {
            text = (if (selected) "✓  " else "") + label
            textSize = 13f; setTextColor(if (selected) Color.rgb(255, 213, 90) else Color.rgb(220, 228, 237))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; maxLines = 1
            setPadding(dp(12), 0, dp(8), 0)
            background = GradientDrawable().apply {
                setColor(if (selected) Color.rgb(39, 54, 72) else Color.rgb(15, 23, 36))
                cornerRadius = dp(11).toFloat(); setStroke(dp(1), Color.rgb(56, 73, 91))
            }
            setOnClickListener {
                if (input) {
                    preferredInputDeviceId = device?.id
                    preferBuiltInMicrophone = device?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
                    try { recorder?.setPreferredDevice(device) } catch (_: RuntimeException) {}
                    if (running) scheduleAudioRouteRestart(forceBuiltInMicrophone = preferBuiltInMicrophone)
                } else {
                    preferredOutputDeviceId = device?.id
                    val applied = listOfNotNull(toneTrack, sweepMeasurementTrack, pianoAudioTrack, personalPreviewTrack)
                        .all(this@buildAudioDeviceSubcard::applyPreferredOutputRoute)
                    if (!applied) Toast.makeText(this@buildAudioDeviceSubcard, "Android could not select that audio output.", Toast.LENGTH_SHORT).show()
                }
                returnToMenu(dialog)
            }
        }
        list.addView(item, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(5) })
    }
    card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
    val done = TextView(this).apply {
        text = "DONE"; gravity = Gravity.CENTER; setTextColor(Color.rgb(255, 213, 90))
        background = GradientDrawable().apply { setColor(Color.rgb(39, 54, 72)); cornerRadius = dp(11).toFloat() }
        setOnClickListener { returnToMenu(dialog) }
    }
    card.addView(done, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(7) })
}
