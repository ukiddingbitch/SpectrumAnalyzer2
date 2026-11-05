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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import kotlin.math.roundToInt

/** Input/output device selection, route changes and route dialogs. */
internal fun MainActivity.ensureAudioDeviceCallbackRegistered() {
    if (audioDeviceCallback != null) return
    val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val callback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            val inputWasAdded = addedDevices.any { it.isSource }
            val outputWasAdded = addedDevices.any { it.isSink }
            audioRouteDialogRefresh?.invoke()
            if (outputWasAdded) {
                listOfNotNull(toneTrack, pianoAudioTrack, personalPreviewTrack)
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
                listOfNotNull(toneTrack, pianoAudioTrack, personalPreviewTrack)
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
    val routeParams = LinearLayout.LayoutParams(dp(58), dp(26))
    if (inPortraitSoundId) {
        routeParams.setMargins(dp(8), 0, 0, 0)
        val soundButtonIndex = actionRow.indexOfChild(soundIdModeButton)
        val insertionIndex = if (soundButtonIndex >= 0) soundButtonIndex + 1 else actionRow.childCount
        actionRow.addView(routeButton, insertionIndex.coerceAtMost(actionRow.childCount), routeParams)
    } else {
        routeParams.setMargins(0, 0, dp(2), 0)
        // Keep the route control after the app info icon and before the SPL readout.
        headerGroup.addView(routeButton, minOf(1, headerGroup.childCount), routeParams)
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
    val tracks = listOfNotNull(toneTrack, pianoAudioTrack, personalPreviewTrack)
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
    val narrowWindow = safeBounds.width() < dp(500)
    val safeHeightDp = (safeBounds.height() / density).roundToInt().coerceAtLeast(1)
    val reservedDialogHeightDp = if (narrowWindow) 220 else 130
    val visibleColumns = if (narrowWindow) 2 else 1
    val maxListHeightDp = ((safeHeightDp - reservedDialogHeightDp) / visibleColumns)
        .coerceAtLeast(36).coerceAtMost(148)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        styleNumericDialogCard(this)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    card.addView(TextView(this).apply {
        text = "Audio devices"
        textSize = 17f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.rgb(255, 213, 90))
        gravity = Gravity.CENTER
        includeFontPadding = false
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))

    val columns = LinearLayout(this).apply {
        orientation = if (narrowWindow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
    }
    card.addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    })

    val dialog = createAnimatedDialog()
    audioRouteDialog = dialog
    fun deviceColumn(
        title: String,
        input: Boolean,
        maxListHeight: Int
    ): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        column.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(210, 220, 232))
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        val activeRoute = if (input) currentInputRouteName() else currentOutputRouteName()
        column.addView(TextView(this).apply {
            text = "Active: $activeRoute"
            textSize = 10f
            setTextColor(Color.rgb(153, 169, 188))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight))

        fun addChoice(label: String, selected: Boolean, contentDescription: String, choose: () -> Unit) {
            val choice = TextView(this).apply {
                text = (if (selected) "✓  " else "      ") + label
                textSize = 12f
                setTextColor(if (selected) Color.rgb(255, 213, 90) else Color.rgb(220, 228, 237))
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(dp(9), dp(5), dp(7), dp(5))
                minHeight = dp(42)
                isClickable = true
                isFocusable = true
                this.contentDescription = contentDescription
                background = GradientDrawable().apply {
                    setColor(if (selected) Color.rgb(39, 54, 72) else Color.rgb(15, 23, 36))
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(if (selected) 1 else 0), if (selected) Color.rgb(86, 118, 149) else Color.TRANSPARENT)
                }
                setOnClickListener { choose() }
            }
            list.addView(choice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                bottomMargin = dp(4)
            })
        }

        val selectedId = if (input) preferredInputDeviceId else preferredOutputDeviceId
        addChoice("System default", selectedId == null, "Use Android's default ${if (input) "microphone" else "speaker"} route") {
            if (input) {
                preferredInputDeviceId = null
                preferBuiltInMicrophone = false
                try { recorder?.setPreferredDevice(null) } catch (_: RuntimeException) {}
                if (running) scheduleAudioRouteRestart()
            } else {
                preferredOutputDeviceId = null
                listOfNotNull(toneTrack, pianoAudioTrack, personalPreviewTrack).forEach(this@showAudioRouteDialog::applyPreferredOutputRoute)
            }
            audioRouteDialogRefresh?.invoke()
        }
        availableAudioDevices(input).forEach { device ->
            addChoice(
                audioDeviceDisplayName(device, input),
                selectedId == device.id,
                "Route ${if (input) "microphone" else "playback"} to ${audioDeviceDisplayName(device, input)}"
            ) {
                if (input) {
                    preferredInputDeviceId = device.id
                    preferBuiltInMicrophone = device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
                    try { recorder?.setPreferredDevice(device) } catch (_: RuntimeException) {}
                    if (running) scheduleAudioRouteRestart(forceBuiltInMicrophone = preferBuiltInMicrophone)
                } else {
                    preferredOutputDeviceId = device.id
                    val applied = listOfNotNull(toneTrack, pianoAudioTrack, personalPreviewTrack)
                        .all(this@showAudioRouteDialog::applyPreferredOutputRoute)
                    if (!applied) Toast.makeText(this@showAudioRouteDialog, "Android could not select that audio output.", Toast.LENGTH_SHORT).show()
                }
                audioRouteDialogRefresh?.invoke()
            }
        }
        return column
    }

    fun refreshDeviceColumns() {
        columns.removeAllViews()
        val inputColumn = deviceColumn("Microphones", input = true, maxListHeight = dp(maxListHeightDp))
        val outputColumn = deviceColumn("Speakers", input = false, maxListHeight = dp(maxListHeightDp))
        val columnParams = if (narrowWindow) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        columns.addView(inputColumn, columnParams)
        columns.addView(outputColumn, if (narrowWindow) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        } else {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }
    refreshDeviceColumns()
    audioRouteDialogRefresh = ::refreshDeviceColumns

    val done = Button(this).apply {
        text = "DONE"
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.rgb(255, 213, 90))
        setOnClickListener { dialog.dismiss() }
    }
    styleNumericDialogButton(done)
    card.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
        topMargin = dp(8)
    })
    dialog.setContentView(card)
    requireExplicitDialogAction(dialog)
    dialog.setOnDismissListener {
        if (audioRouteDialog === dialog) {
            audioRouteDialog = null
            audioRouteDialogRefresh = null
        }
    }
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.window?.setDimAmount(0.38f)
    val maxWidth = (safeBounds.width() - dp(24)).coerceAtLeast(dp(1))
    val preferredWidth = if (narrowWindow) dp(380) else dp(620)
    centerDialogOnScreen(dialog, minOf(preferredWidth, maxWidth), horizontalOffsetDp = 0)
    dialog.show()
}
