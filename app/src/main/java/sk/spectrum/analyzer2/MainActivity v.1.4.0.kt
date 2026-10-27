package sk.spectrum.analyzer2

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Path
import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import androidx.appcompat.widget.AppCompatButton
import android.widget.EditText
import android.text.InputFilter
import android.text.InputType
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.pow

private class AnalyzerIconButton(
    context: Context,
    icon: Icon
) : AppCompatButton(context) {
    enum class Icon { PLAY, STOP, RESET }

    var icon: Icon = icon
        set(value) {
            field = value
            invalidate()
            contentDescription = when (value) {
                Icon.PLAY -> "Start analysis"
                Icon.STOP -> "Stop analysis"
                Icon.RESET -> "Reset display"
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val resetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    init {
        text = ""
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        contentDescription = when (icon) {
            Icon.PLAY -> "Start analysis"
            Icon.STOP -> "Stop analysis"
            Icon.RESET -> "Reset display"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // Play and stop use the same compact visual bounds and have no outline.
        val iconSize = minOf(width, height) * 0.30f
        val path = Path()
        when (icon) {
            Icon.PLAY -> {
                path.moveTo(cx - iconSize * 0.5f, cy - iconSize * 0.5f)
                path.lineTo(cx + iconSize * 0.5f, cy)
                path.lineTo(cx - iconSize * 0.5f, cy + iconSize * 0.5f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            Icon.STOP -> {
                val half = iconSize * 0.5f
                path.addRect(cx - half, cy - half, cx + half, cy + half, Path.Direction.CW)
                canvas.drawPath(path, fillPaint)
            }
            Icon.RESET -> {
                // Material-style refresh symbol: a clean circular arrow, drawn as one filled shape.
                val iconSize = minOf(width, height) * 0.48f
                val scale = iconSize / 24f
                canvas.save()
                canvas.translate(cx - iconSize / 2f, cy - iconSize / 2f)
                canvas.scale(scale, scale)
                val refreshPath = Path().apply {
                    moveTo(17.65f, 6.35f)
                    cubicTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                    cubicTo(7.58f, 4f, 4f, 7.58f, 4f, 12f)
                    cubicTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
                    cubicTo(16.08f, 20f, 19.44f, 16.95f, 19.93f, 13f)
                    lineTo(17.91f, 13f)
                    cubicTo(17.43f, 15.83f, 14.97f, 18f, 12f, 18f)
                    cubicTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
                    cubicTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
                    cubicTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
                    lineTo(13f, 11f)
                    lineTo(20f, 11f)
                    lineTo(20f, 4f)
                    close()
                }
                fillPaint.color = Color.WHITE
                canvas.drawPath(refreshPath, fillPaint)
                fillPaint.color = Color.BLACK
                canvas.restore()
            }
        }
    }
}

class MainActivity : Activity() {

    private lateinit var spectrumView: SpectrumView
    private lateinit var statusText: TextView
    private lateinit var peakText: TextView
    private lateinit var startButton: Button
    private var headerPeakFrequencyText: TextView? = null
    private var headerPeakDbText: TextView? = null
    private var headerTitleText: TextView? = null
    private var splitContainer: LinearLayout? = null
    private var tonePanel: View? = null
    private var keyboardPanel: View? = null
    private var keyboardToggleButton: Button? = null
    private var toneModeOpen = false
    private var keyboardModeOpen = false
    private var toneToggleButton: Button? = null
    private var axisToggleButton: Button? = null
    @Volatile private var tonePlaying = false
    @Volatile private var toneFrequency = 440
    private data class PianoSample(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int
    )

    private data class PianoVoice(
        val note: Int,
        val sample: PianoSample,
        var position: Double = 0.0,
        var volume: Float,
        var releaseRemainingFrames: Int = 0,
        var releaseTotalFrames: Int = 0
    )

    private val pianoSamples = ConcurrentHashMap<Int, PianoSample>()
    private val pianoHeldNotes = mutableSetOf<Int>()
    private val pianoAudioLock = Any()
    private val pianoVoices = mutableListOf<PianoVoice>()
    private var pianoAudioTrack: AudioTrack? = null
    private var pianoAudioThread: Thread? = null
    @Volatile private var pianoEngineRunning = false
    @Volatile private var pianoSamplesLoading = false
    @Volatile private var pianoLoadedCount = 0
    @Volatile private var pianoLoadFailedCount = 0
    private var pianoVolume = 0.8f
    private var pianoReverbEnabled = true
    private var pianoReverbButton: Button? = null
    private var pianoTranspose = 0
    private var pianoVolumeButton: Button? = null
    private var pianoOctaveButton: Button? = null

    private val pianoOutputRate = 44100
    private val pianoBlockFrames = 256

    /** Starts a single streaming audio engine and decodes the original OGG samples off the UI thread. */
    private fun initializePianoSamples() {
        if (pianoSamplesLoading || pianoAudioTrack != null) return
        pianoSamplesLoading = true
        startPianoAudioEngine()
        Thread({
            val names = arrayOf("c", "cs", "d", "ds", "e", "f", "fs", "g", "gs", "a", "as", "b")
            var missing = 0
            for (note in 36..96) {
                val resourceName = "piano_${names[note % 12]}${note / 12 - 1}"
                val resourceId = resources.getIdentifier(resourceName, "raw", packageName)
                if (resourceId == 0) {
                    missing++
                    Log.w(PIANO_LOAD_TAG, "Missing raw piano resource: $resourceName")
                    continue
                }
                try {
                    val decoded = decodePianoOgg(resourceId)
                    pianoSamples[note] = decoded
                    pianoLoadedCount++
                    runOnUiThread {
                        if (pianoHeldNotes.contains(note)) startPianoSample(note)
                    }
                } catch (e: Exception) {
                    pianoLoadFailedCount++
                    Log.e(PIANO_LOAD_TAG, "Failed to decode $resourceName", e)
                }
            }
            pianoSamplesLoading = false
            Log.i(PIANO_LOAD_TAG, "Custom piano engine ready: $pianoLoadedCount decoded, $pianoLoadFailedCount failed, $missing missing")
        }, "PianoSampleDecoder").apply { priority = Thread.NORM_PRIORITY; start() }
    }

    private fun startPianoAudioEngine() {
        val minBuffer = AudioTrack.getMinBufferSize(
            pianoOutputRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(PIANO_LOAD_TAG, "AudioTrack does not support the requested output format: $minBuffer")
            return
        }
        val bufferBytes = max(minBuffer, pianoBlockFrames * 2 * 2 * 4)
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            pianoOutputRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
            AudioTrack.MODE_STREAM
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            Log.e(PIANO_LOAD_TAG, "Could not initialize piano AudioTrack")
            return
        }
        pianoAudioTrack = track
        pianoEngineRunning = true
        track.play()
        pianoAudioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val output = ShortArray(pianoBlockFrames * 2)
            while (pianoEngineRunning) {
                java.util.Arrays.fill(output, 0)
                synchronized(pianoAudioLock) {
                    val iterator = pianoVoices.iterator()
                    while (iterator.hasNext()) {
                        val voice = iterator.next()
                        val sample = voice.sample
                        val frameCount = sample.samples.size / sample.channels
                        if (frameCount <= 1 || voice.position >= frameCount - 1) {
                            iterator.remove()
                            continue
                        }
                        for (frame in 0 until pianoBlockFrames) {
                            val index = voice.position.toInt()
                            if (index >= frameCount - 1) break
                            val fraction = (voice.position - index).toFloat()
                            val leftA = sample.samples[index * sample.channels].toFloat()
                            val leftB = sample.samples[(index + 1) * sample.channels].toFloat()
                            val left = leftA + (leftB - leftA) * fraction
                            val right = if (sample.channels > 1) {
                                val rightA = sample.samples[index * sample.channels + 1].toFloat()
                                val rightB = sample.samples[(index + 1) * sample.channels + 1].toFloat()
                                rightA + (rightB - rightA) * fraction
                            } else left
                            var gain = voice.volume
                            if (voice.releaseRemainingFrames > 0) {
                                gain *= voice.releaseRemainingFrames.toFloat() / voice.releaseTotalFrames.coerceAtLeast(1)
                                voice.releaseRemainingFrames--
                            }
                            val outIndex = frame * 2
                            output[outIndex] = (output[outIndex].toInt() + (left * gain).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            output[outIndex + 1] = (output[outIndex + 1].toInt() + (right * gain).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            voice.position += sample.sampleRate.toDouble() / pianoOutputRate
                        }
                        if (voice.releaseTotalFrames > 0 && voice.releaseRemainingFrames <= 0) iterator.remove()
                    }
                }
                val written = track.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) Log.e(PIANO_LOAD_TAG, "AudioTrack write failed: $written")
            }
        }, "PianoAudioEngine").apply { start() }
    }

    /** Decode an OGG resource into interleaved PCM16 without blocking the UI thread. */
    private fun decodePianoOgg(resourceId: Int): PianoSample {
        val extractor = MediaExtractor()
        val outputBytes = ByteArrayOutputStream()
        var codec: MediaCodec? = null
        var afd: android.content.res.AssetFileDescriptor? = null
        var outputRate = pianoOutputRate
        var outputChannels = 1
        try {
            afd = resources.openRawResourceFd(resourceId)
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) throw IllegalStateException("No audio track in resource")
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Missing audio MIME type")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw IllegalStateException("Missing decoder input buffer")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                            throw IllegalStateException("Decoder did not output PCM16")
                        }
                    }
                    outputIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            outputBytes.write(chunk)
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
            try { afd?.close() } catch (_: Exception) {}
        }
        val raw = outputBytes.toByteArray()
        val shorts = ShortArray(raw.size / 2)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = buffer.short
        return PianoSample(shorts, outputRate, outputChannels.coerceIn(1, 2))
    }

    private fun playPianoSample(midiNote: Int) {
        initializePianoSamples()
        val playedNote = (midiNote + pianoTranspose).coerceIn(36, 96)
        pianoHeldNotes.add(playedNote)
        startPianoSample(playedNote)
    }

    private fun startPianoSample(playedNote: Int) {
        val sample = pianoSamples[playedNote] ?: return
        synchronized(pianoAudioLock) {
            // Retrigger this note without creating an unbounded number of voices.
            pianoVoices.removeAll { it.note == playedNote }
            pianoVoices.add(PianoVoice(playedNote, sample, volume = pianoVolume))
        }
    }

    private fun releasePianoSample(midiNote: Int) {
        val playedNote = (midiNote + pianoTranspose).coerceIn(36, 96)
        pianoHeldNotes.remove(playedNote)
        if (pianoReverbEnabled) return
        synchronized(pianoAudioLock) {
            for (voice in pianoVoices) {
                if (voice.note == playedNote && voice.releaseTotalFrames == 0) {
                    voice.releaseTotalFrames = (pianoOutputRate * 0.42f).toInt()
                    voice.releaseRemainingFrames = voice.releaseTotalFrames
                }
            }
        }
    }

    private var toneMinFrequency = 20
    private var toneMaxFrequency = 20000
    @Volatile private var toneVolume = 0.5
    @Volatile private var toneWaveform = "Sine"
    @Volatile private var toneStopRequested = false
    private var toneTrack: AudioTrack? = null
    private var toneThread: Thread? = null
    private var tonePlayButton: Button? = null

    @Volatile
    private var running = false

    private var recorder: AudioRecord? = null
    private var audioThread: Thread? = null

    private val sampleRate = 48000

    // FFT configuration
    private val fftSize = 8192

    // 75% overlap: process a new FFT every 2048 samples.
    private val hopSize = fftSize / 4

    private val permissionRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)

        spectrumView = findViewById(R.id.spectrumView)
        statusText = findViewById(R.id.statusText)
        peakText = findViewById(R.id.peakText)
        startButton = findViewById(R.id.startButton)
        configureSpectrumHeader()
        // Preload piano samples during launch so the keyboard is ready before the first tap.
        initializePianoSamples()
        spectrumView.setControlStateListener { axisMode, _, _, _ ->
            headerTitleText?.text = if (axisMode == "LINEAR") {
                "SPECTRUM ANALYZER (LINEAR)"
            } else {
                "SPECTRUM ANALYZER (LOGARITHMIC)"
            }
            axisToggleButton?.text = if (axisMode == "LINEAR") "LOG" else "LIN"
        }
        statusText.visibility = View.GONE

        // Keep the bottom controls in equal, fixed-size cells. Reset Graph is removed.
        peakText.visibility = View.GONE
        statusText.visibility = View.GONE

        val startParent = startButton.parent as? ViewGroup
        if (startParent != null) {
            val startIndex = startParent.indexOfChild(startButton)
            val originalParams = startButton.layoutParams
            startParent.removeView(startButton)

            fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(56)
                )
            }

            fun configureActionButton(button: Button, primary: Boolean = false) {
                button.textSize = 14f
                button.minHeight = dp(52)
                button.minimumHeight = dp(52)
                button.setPadding(dp(14), dp(4), dp(14), dp(4))
                button.maxLines = 1
                button.includeFontPadding = false
                button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(22, 32, 42))
                button.setTextColor(Color.WHITE)
                styleXButton(button, primary = primary)
                actionRow.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }

            // Use custom-drawn icons while keeping the existing button background and click behavior.
            val startIconButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.PLAY)
            startIconButton.layoutParams = startButton.layoutParams
            startButton = startIconButton
            configureActionButton(startButton, primary = true)
            startButton.layoutParams = (startButton.layoutParams as LinearLayout.LayoutParams).apply {
                val labelWidth = maxOf(
                    startButton.paint.measureText("START"),
                    startButton.paint.measureText("STOP")
                ).roundToInt()
                width = labelWidth + startButton.paddingLeft + startButton.paddingRight + dp(4)
                setMargins(dp(3), dp(2), dp(3), dp(2))
            }

            // Reset is placed immediately beside Start/Stop.
            val resetButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.RESET)
            configureActionButton(resetButton)
            resetButton.layoutParams = (resetButton.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(52)
                setMargins(dp(3), dp(2), dp(3), dp(2))
            }
            resetButton.contentDescription = "Reset display"
            resetButton.setOnClickListener { spectrumView.resetView(useKeysFrequencyRange = keyboardModeOpen) }

            val spectrogramButton = Button(this).apply {
                // Show the mode this button will switch TO, not the current mode.
                text = if (spectrumView.isSpectrogramEnabled()) "SPECTRUM" else "SPECTROGRAM"
                setOnClickListener {
                    val enabled = spectrumView.toggleSpectrogram()
                    text = if (enabled) "SPECTRUM" else "SPECTROGRAM"
                }
            }
            configureActionButton(spectrogramButton)
            // Keep this button wide enough for the longer SPECTROGRAM label in both states.
            spectrogramButton.post {
                val labelWidth = spectrogramButton.paint.measureText("SPECTROGRAM").roundToInt()
                val fixedWidth = labelWidth + spectrogramButton.paddingLeft + spectrogramButton.paddingRight + dp(4)
                spectrogramButton.layoutParams = (spectrogramButton.layoutParams as LinearLayout.LayoutParams).apply {
                    width = fixedWidth
                }
            }

            val axisButton = Button(this).apply {
                // Show the axis mode this button will switch TO.
                text = if (spectrumView.isLinearFrequencyAxis()) "LOG" else "LIN"
                setOnClickListener {
                    spectrumView.toggleFrequencyAxis()
                    text = if (spectrumView.isLinearFrequencyAxis()) "LOG" else "LIN"
                }
            }
            axisToggleButton = axisButton
            configureActionButton(axisButton)

            // Flexible spacer separates the left-side tone controls from the graph controls.
            // Flexible spacer keeps KEYS and TONE GENERATOR together at the far right.
            val controlsSpacer = View(this)
            actionRow.addView(controlsSpacer, LinearLayout.LayoutParams(0, 1, 1f))

            val keysButton = Button(this).apply {
                text = "KEYS"
            }
            keyboardToggleButton = keysButton
            configureActionButton(keysButton)

            val toneButton = Button(this).apply {
                text = "TONE GENERATOR"
            }
            toneToggleButton = toneButton
            configureActionButton(toneButton)
            // Keep the cell sized for the full label in both ON and OFF states.
            toneButton.post {
                val labelWidth = toneButton.paint.measureText("TONE GENERATOR").roundToInt()
                val fixedWidth = labelWidth + toneButton.paddingLeft + toneButton.paddingRight + dp(4)
                toneButton.layoutParams = (toneButton.layoutParams as LinearLayout.LayoutParams).apply {
                    width = fixedWidth
                }
            }

            // Arrange controls from left to right: tone, keys, axis, view, reset, start/stop.
            actionRow.removeAllViews()
            actionRow.addView(toneButton)
            actionRow.addView(keysButton)
            actionRow.addView(controlsSpacer, LinearLayout.LayoutParams(0, 1, 1f))
            actionRow.addView(axisButton)
            actionRow.addView(spectrogramButton)
            actionRow.addView(resetButton)
            actionRow.addView(startButton)
            startParent.addView(actionRow, startIndex, originalParams)
            installToneGeneratorSplit(toneButton, keysButton)
        }
        updateStartButtonAppearance()

        startButton.setOnClickListener {
            if (running) {
                stopAnalysis()
            } else if (
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startAnalysis()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    permissionRequest
                )
            }
        }
        // Start the live graph automatically when the app opens.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAnalysis()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
        }

    }

    private fun installToneGeneratorSplit(toggleButton: Button, keysButton: Button) {
        val parent = spectrumView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(spectrumView)
        val originalParams = spectrumView.layoutParams
        parent.removeView(spectrumView)

        val split = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.FILL
            setBackgroundColor(Color.rgb(15, 23, 36))
        }
        spectrumView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f)
        split.addView(spectrumView)
        val panel = createToneGeneratorPanel()
        tonePanel = panel
        split.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f))
        panel.visibility = View.GONE

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

        val keyboardSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.FILL
            setPadding(dp(4), dp(4), dp(4), dp(2))
            setBackgroundColor(Color.rgb(15, 23, 36))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        fun controlButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minHeight = dp(34)
            minimumHeight = dp(34)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(3), 0, dp(3), 0)
            setTextColor(Color.WHITE)
            styleXButton(this, primary = false)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = dp(3)
            }
            setOnClickListener { action() }
        }
        val volumeButton = controlButton("VOLUME 80%") {
            pianoVolume = if (pianoVolume >= 0.99f) 0.2f else (pianoVolume + 0.2f).coerceAtMost(1f)
            pianoVolumeButton?.text = "VOLUME ${(pianoVolume * 100).roundToInt()}%"
        }
        val volumeParams = volumeButton.layoutParams as LinearLayout.LayoutParams
        volumeParams.marginStart = (65f / resources.displayMetrics.density).roundToInt()
        volumeButton.layoutParams = volumeParams
        volumeButton.gravity = Gravity.CENTER
        volumeButton.textAlignment = View.TEXT_ALIGNMENT_CENTER
        val octaveButton = controlButton("OCTAVE 0") {
            pianoTranspose = when (pianoTranspose) { 0 -> 12; 12 -> -12; else -> 0 }
            pianoOctaveButton?.text = when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }
        }
        val reverbButton = controlButton(if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF") {
            pianoReverbEnabled = !pianoReverbEnabled
            pianoReverbButton?.apply {
                text = if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF"
                styleXButton(this, primary = pianoReverbEnabled)
            }
        }
        pianoVolumeButton = volumeButton
        pianoOctaveButton = octaveButton
        pianoReverbButton = reverbButton
        styleXButton(reverbButton, primary = pianoReverbEnabled)
        controls.addView(volumeButton)
        controls.addView(octaveButton)
        controls.addView(reverbButton)
        keyboardSection.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val keys = PianoKeyboardView(this) { midiNote, pressed ->
            if (pressed) {
                stopTone()
                playPianoSample(midiNote)
            } else {
                releasePianoSample(midiNote)
            }
        }
        keyboardPanel = keys
        keyboardSection.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyboardSection.visibility = View.GONE
        split.addView(keyboardSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        splitContainer = split
        parent.addView(split, index, originalParams)

        fun showMode(mode: String) {
            toneModeOpen = mode == "tone"
            keyboardModeOpen = mode == "keys"
            spectrumView.setKeysFrequencyRangeActive(keyboardModeOpen)
            spectrumView.setDbRangeMode(when {
                toneModeOpen -> "TONE"
                keyboardModeOpen -> "KEYS"
                else -> "DEFAULT"
            })
            panel.visibility = if (toneModeOpen) View.VISIBLE else View.GONE
            keyboardSection.visibility = if (keyboardModeOpen) View.VISIBLE else View.GONE
            spectrumView.visibility = View.VISIBLE
            if (keyboardModeOpen) {
                split.orientation = LinearLayout.VERTICAL
                spectrumView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                keyboardSection.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                split.orientation = LinearLayout.HORIZONTAL
                spectrumView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f)
                panel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f)
                keyboardSection.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            toggleButton.text = "TONE GENERATOR"
            toggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (toneModeOpen) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42)
            )
            keysButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (keyboardModeOpen) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42)
            )
            if (!toneModeOpen && !keyboardModeOpen) {
                stopTone()
                resetTonePlayButton()
            } else if (keyboardModeOpen) {
                stopTone()
                resetTonePlayButton()
            }
            split.requestLayout()
            spectrumView.invalidate()
        }
        toggleButton.setOnClickListener { showMode(if (toneModeOpen) "none" else "tone") }
        keysButton.setOnClickListener { showMode(if (keyboardModeOpen) "none" else "keys") }
    }

    private fun createToneGeneratorPanel(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(Color.rgb(19, 29, 43))
            // Shift the complete Tone Generator section left by about 2 cm on screen.
            translationX = -dp(20).toFloat()
        }

        // Compact header: title, waveform label and spinner share one centered row.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        header.addView(TextView(this).apply {
            text = "Waveform"
            textSize = 12f
            setTextColor(Color.rgb(150, 166, 184))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(3), 0)
            maxLines = 1
        })
        val waveSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Sine", "Square", "Triangle", "Sawtooth"))
            setSelection(arrayOf("Sine", "Square", "Triangle", "Sawtooth").indexOf(toneWaveform).coerceAtLeast(0))
        }
        header.addView(waveSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        waveSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                toneWaveform = arrayOf("Sine", "Square", "Triangle", "Sawtooth")[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Keep all tone controls centered directly beneath the header.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(1), dp(2), 0)
        }
        panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val valueLabel = TextView(this).apply {
            text = "$toneFrequency Hz"
            textSize = 23f
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
        controls.addView(valueLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val slider = SeekBar(this).apply {
            max = 1000
            progress = ((kotlin.math.ln(toneFrequency / toneMinFrequency.toDouble()) / kotlin.math.ln(toneMaxFrequency / toneMinFrequency.toDouble())) * max).roundToInt().coerceIn(0, max)
        }
        controls.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 0)
        })
        val limits = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        limits.addView(TextView(this).apply { text = "$toneMinFrequency Hz"; textSize = 10f; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        limits.addView(TextView(this).apply { text = "${String.format(java.util.Locale.US, "%,d", toneMaxFrequency)} Hz"; textSize = 10f; gravity = Gravity.END; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(limits, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val volumeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, 0)
        }
        volumeHeader.addView(TextView(this).apply {
            text = "Volume"
            textSize = 11f
            setTextColor(Color.rgb(150, 166, 184))
            gravity = Gravity.CENTER
        })
        val volumeLabel = TextView(this).apply {
            text = "${(toneVolume * 100).roundToInt()}%"
            textSize = 12f
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, 0, 0)
        }
        volumeHeader.addView(volumeLabel)
        controls.addView(volumeHeader)
        val volumeSlider = SeekBar(this).apply { max = 100; progress = (toneVolume * 100).roundToInt() }
        controls.addView(volumeSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 0) })
        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                toneVolume = progress / 100.0
                volumeLabel.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val playButton = Button(this).apply {
            text = "PLAY TONE"
            textSize = 13f
            minHeight = dp(38)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(29, 125, 65))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (tonePlaying) {
                    stopTone()
                    resetTonePlayButton()
                } else {
                    startTone()
                    if (tonePlaying) {
                        text = "STOP TONE"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(150, 40, 45))
                    }
                }
            }
        }
        tonePlayButton = playButton
        controls.addView(playButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(1), 0, 0)
        })
        fun updateToneSliderFromFrequency() {
            val low = toneMinFrequency.coerceAtLeast(20)
            val high = toneMaxFrequency.coerceAtMost(20000).coerceAtLeast(low + 1)
            toneFrequency = toneFrequency.coerceIn(low, high)
            slider.progress = ((kotlin.math.ln(toneFrequency / low.toDouble()) / kotlin.math.ln(high / low.toDouble())) * slider.max).roundToInt().coerceIn(0, slider.max)
            valueLabel.text = "$toneFrequency Hz"
            limits.getChildAt(0).let { (it as TextView).text = "$low Hz" }
            limits.getChildAt(1).let { (it as TextView).text = "${String.format(java.util.Locale.US, "%,d", high)} Hz" }
        }
        valueLabel.setOnClickListener {
            // Use the same custom numeric keypad as the Spectrum Analyzer.
            // Confirming a value only updates the frequency; it never starts playback.
            val dialog = android.app.Dialog(this)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.rgb(24, 31, 42))
            }
            content.addView(TextView(this).apply {
                text = "Frequency (Hz)"
                textSize = 14f
                setTextColor(Color.rgb(225, 233, 243))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val display = TextView(this).apply {
                text = toneFrequency.toString()
                textSize = 22f
                setTextColor(Color.rgb(255, 213, 90))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(8))
                minWidth = dp(160)
            }
            content.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val keypad = android.widget.GridLayout(this).apply {
                columnCount = 3
                rowCount = 4
            }
            fun key(label: String, action: () -> Unit) {
                val button = Button(this).apply {
                    text = label
                    textSize = 16f
                    minWidth = dp(48)
                    minimumWidth = dp(48)
                    minHeight = dp(42)
                    minimumHeight = dp(42)
                    setPadding(0, 0, 0, 0)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(49, 63, 81))
                    setTextColor(Color.rgb(255, 213, 90))
                    setOnClickListener { action() }
                }
                keypad.addView(button, android.widget.GridLayout.LayoutParams().apply {
                    width = dp(52)
                    height = dp(42)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            fun appendDigit(digit: String) {
                val current = display.text.toString()
                if (current.length < 5) display.text = current + digit
            }
            key("1") { appendDigit("1") }
            key("2") { appendDigit("2") }
            key("3") { appendDigit("3") }
            key("4") { appendDigit("4") }
            key("5") { appendDigit("5") }
            key("6") { appendDigit("6") }
            key("7") { appendDigit("7") }
            key("8") { appendDigit("8") }
            key("9") { appendDigit("9") }
            key("⌫") { if (display.text.isNotEmpty()) display.text = display.text.dropLast(1) }
            key("0") { appendDigit("0") }
            key("CLR") { display.text = "" }
            content.addView(keypad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val cancelButton = Button(this).apply {
                text = "CANCEL"
                textSize = 11f
                setOnClickListener { dialog.dismiss() }
            }
            val doneButton = Button(this).apply {
                text = "DONE"
                textSize = 11f
                setOnClickListener {
                    val frequency = display.text.toString().toIntOrNull()
                    if (frequency == null || frequency !in 20..20000) {
                        display.text = "20–20000 Hz"
                    } else {
                        toneFrequency = frequency
                        updateToneSliderFromFrequency()
                        dialog.dismiss()
                    }
                }
            }
            for (button in listOf(cancelButton, doneButton)) {
                button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(49, 63, 81))
                button.setTextColor(Color.rgb(255, 213, 90))
                actions.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(3), dp(5), dp(3), 0)
                })
            }
            content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setContentView(content)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            dialog.window?.setLayout(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val low = toneMinFrequency.coerceAtLeast(20)
                val high = toneMaxFrequency.coerceAtMost(20000).coerceAtLeast(low + 1)
                toneFrequency = (low * kotlin.math.exp(progress.toDouble() / slider.max * kotlin.math.ln(high.toDouble() / low))).roundToInt().coerceIn(low, high)
                valueLabel.text = "$toneFrequency Hz"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        return panel
    }

    private fun resetTonePlayButton() {
        tonePlayButton?.apply {
            text = "PLAY TONE"
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(29, 125, 65))
        }
    }

    private fun startTone() {
        if (tonePlaying) return
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Toast.makeText(this, "Audio output is unavailable.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, 4096), AudioTrack.MODE_STREAM
            )
            toneTrack = track
            toneStopRequested = false
            tonePlaying = true
            track.play()
            toneThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val buffer = ShortArray(1024)
                var phase = 0.0
                var fadeRemaining = -1
                val fadeSamples = (sampleRate * 0.04).toInt()
                while (!Thread.currentThread().isInterrupted) {
                    if (toneStopRequested && fadeRemaining < 0) fadeRemaining = fadeSamples
                    if (!tonePlaying && fadeRemaining <= 0) break
                    val frequency = toneFrequency.toDouble().coerceIn(20.0, 20000.0)
                    val step = 2.0 * PI * frequency / sampleRate
                    val waveform = toneWaveform
                    val volume = toneVolume
                    for (i in buffer.indices) {
                        val normalized = when (waveform) {
                            "Square" -> if (sin(phase) >= 0.0) 1.0 else -1.0
                            "Triangle" -> 2.0 / PI * kotlin.math.asin(sin(phase))
                            "Sawtooth" -> 2.0 * (phase / (2.0 * PI)) - 1.0
                            else -> sin(phase)
                        }
                        var envelope = 1.0
                        if (fadeRemaining >= 0) {
                            envelope = (fadeRemaining.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                            fadeRemaining--
                        }
                        buffer[i] = (normalized * 12000.0 * volume * envelope).toInt().toShort()
                        phase += step
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI
                    }
                    try { track.write(buffer, 0, buffer.size) } catch (_: Exception) { break }
                    if (toneStopRequested && fadeRemaining <= 0) break
                }
                try { track.pause(); track.flush(); track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                if (toneTrack === track) toneTrack = null
                tonePlaying = false
                toneThread = null
                toneStopRequested = false
                runOnUiThread { resetTonePlayButton() }
            }.apply { name = "ToneGeneratorAudio"; start() }
        } catch (_: Exception) {
            tonePlaying = false
            try { toneTrack?.release() } catch (_: Exception) {}
            toneTrack = null
            resetTonePlayButton()
            Toast.makeText(this, "Could not start tone output.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopTone() {
        if (!tonePlaying) {
            resetTonePlayButton()
            return
        }
        toneStopRequested = true
        // The audio thread performs a short fade-out before releasing AudioTrack.
    }

    private fun styleXButton(button: Button, primary: Boolean) {
        val density = resources.displayMetrics.density
        val background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 999f * density
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

    private fun configureSpectrumHeader() {
        fun findTitle(view: View): TextView? {
            if (view is TextView && view.text.toString().trim().equals("SPECTRUM ANALYZER", ignoreCase = true)) {
                return view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val found = findTitle(view.getChildAt(i))
                    if (found != null) return found
                }
            }
            return null
        }

        val title = findTitle(findViewById(android.R.id.content))
        val parent = title?.parent as? ViewGroup
        if (title == null || parent == null) return

        fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

        val index = parent.indexOfChild(title)
        val oldParams = title.layoutParams
        parent.removeView(title)
        headerTitleText = null
        title.visibility = View.GONE

        val header = android.widget.FrameLayout(this).apply {
            setPadding(0, 0, 0, 0)
        }
        // The title is intentionally removed. Frequency range controls will be centered in this row.

        fun frequencyInput(initialValue: String, description: String): EditText = EditText(this).apply {
            setText(initialValue)
            contentDescription = description
            textSize = 13f
            setTextColor(Color.rgb(255, 213, 90))
            setHintTextColor(Color.rgb(150, 160, 175))
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            setSingleLine(true)
            isFocusable = false
            isFocusableInTouchMode = false
            isCursorVisible = false
            setPadding(0, 0, 0, 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 213, 90))
        }

        val minFrequencyInput = frequencyInput("20", "Minimum frequency in Hz")
        val maxFrequencyInput = frequencyInput("20000", "Maximum frequency in Hz")

        var selectedRangeInput: EditText = minFrequencyInput
        fun selectRangeInput(input: EditText) {
            selectedRangeInput = input
            minFrequencyInput.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (input === minFrequencyInput) Color.rgb(255, 213, 90) else Color.rgb(100, 115, 132)
            )
            maxFrequencyInput.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (input === maxFrequencyInput) Color.rgb(255, 213, 90) else Color.rgb(100, 115, 132)
            )
        }
        minFrequencyInput.setOnClickListener { selectRangeInput(minFrequencyInput) }
        maxFrequencyInput.setOnClickListener { selectRangeInput(maxFrequencyInput) }

        val rangeDialog = android.app.Dialog(this)
        val rangeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(24, 31, 42))
        }
        rangeContent.addView(TextView(this).apply {
            text = "FREQUENCY RANGE (Hz)"
            textSize = 14f
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rangeFields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addRangeField(label: String, input: EditText) {
            val field = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            field.addView(TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(Color.rgb(180, 190, 205))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
            field.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            rangeFields.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addRangeField("FROM", minFrequencyInput)
        rangeFields.addView(TextView(this).apply {
            text = "–"
            textSize = 18f
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), dp(60)))
        addRangeField("TO", maxFrequencyInput)
        rangeContent.addView(rangeFields, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rangeKeypad = android.widget.GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
        }
        fun addRangeKey(label: String, action: () -> Unit) {
            val keyButton = Button(this).apply {
                text = label
                textSize = 16f
                minWidth = dp(48)
                minimumWidth = dp(48)
                minHeight = dp(42)
                minimumHeight = dp(42)
                setPadding(0, 0, 0, 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(49, 63, 81))
                setTextColor(Color.rgb(255, 213, 90))
                setOnClickListener { action() }
            }
            rangeKeypad.addView(keyButton, android.widget.GridLayout.LayoutParams().apply {
                width = dp(52)
                height = dp(42)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        fun appendRangeDigit(digit: String) {
            val current = selectedRangeInput.text.toString()
            if (current.length < 5) selectedRangeInput.setText(current + digit)
        }
        addRangeKey("1") { appendRangeDigit("1") }
        addRangeKey("2") { appendRangeDigit("2") }
        addRangeKey("3") { appendRangeDigit("3") }
        addRangeKey("4") { appendRangeDigit("4") }
        addRangeKey("5") { appendRangeDigit("5") }
        addRangeKey("6") { appendRangeDigit("6") }
        addRangeKey("7") { appendRangeDigit("7") }
        addRangeKey("8") { appendRangeDigit("8") }
        addRangeKey("9") { appendRangeDigit("9") }
        addRangeKey("⌫") {
            val current = selectedRangeInput.text.toString()
            if (current.isNotEmpty()) selectedRangeInput.setText(current.dropLast(1))
        }
        addRangeKey("0") { appendRangeDigit("0") }
        addRangeKey("CLR") { selectedRangeInput.setText("") }
        rangeContent.addView(rangeKeypad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) })

        val rangeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val cancelRangeButton = Button(this).apply {
            text = "CANCEL"
            textSize = 11f
            setOnClickListener { rangeDialog.dismiss() }
        }
        val rangeInfoButton = TextView(this).apply {
            text = "ⓘ"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 213, 90))
            contentDescription = "Frequency range information"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Frequency range")
                    .setMessage("Enter the minimum and maximum frequencies in Hz, from 0 to 20000. The minimum must be lower than the maximum. Press DONE to apply the range. The sampling rate remains 48 kHz; analysis is limited to 20 kHz.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        val applyRangeButton = Button(this).apply {
            text = "DONE"
            textSize = 12f
            setOnClickListener {
                val minValue = minFrequencyInput.text.toString().trim().toIntOrNull()
                val maxValue = maxFrequencyInput.text.toString().trim().toIntOrNull()
                when {
                    minValue == null || maxValue == null -> Toast.makeText(this@MainActivity, "Enter both frequencies.", Toast.LENGTH_SHORT).show()
                    minValue !in 0..20000 || maxValue !in 0..20000 -> Toast.makeText(this@MainActivity, "Frequencies must be between 0 and 20000 Hz.", Toast.LENGTH_SHORT).show()
                    minValue >= maxValue -> Toast.makeText(this@MainActivity, "The minimum frequency must be lower than the maximum.", Toast.LENGTH_SHORT).show()
                    maxValue <= 20 -> Toast.makeText(this@MainActivity, "The maximum frequency must be above 20 Hz.", Toast.LENGTH_SHORT).show()
                    else -> {
                        spectrumView.setFrequencyRange(minValue, maxValue)
                        rangeDialog.dismiss()
                    }
                }
            }
        }
        for (button in listOf(cancelRangeButton, applyRangeButton)) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(49, 63, 81))
            button.setTextColor(Color.rgb(255, 213, 90))
        }
        rangeActions.addView(cancelRangeButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        rangeActions.addView(rangeInfoButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        rangeActions.addView(applyRangeButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        rangeContent.addView(rangeActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rangeDialog.setContentView(rangeContent)
        rangeDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val rangeButton = Button(this).apply {
            text = "RANGE"
            textSize = 14f
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(14), 0, dp(14), 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(49, 63, 81))
            setTextColor(Color.rgb(255, 213, 90))
            contentDescription = "Choose graph frequency range"
            setOnClickListener {
                val (minHz, maxHz) = spectrumView.getVisibleFrequencyRangeHz()
                minFrequencyInput.setText(minHz.toString())
                maxFrequencyInput.setText(maxHz.toString())
                selectRangeInput(minFrequencyInput)
                rangeDialog.show()
                rangeDialog.window?.setGravity(Gravity.CENTER)
                rangeDialog.window?.setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        styleXButton(rangeButton, primary = false)
        rangeButton.setTextColor(Color.rgb(255, 213, 90))
        rangeButton.textSize = 14f
        val rangeButtonWidth = rangeButton.paint.measureText("RANGE").roundToInt() +
                rangeButton.paddingLeft + rangeButton.paddingRight + dp(4)
        header.addView(rangeButton, android.widget.FrameLayout.LayoutParams(rangeButtonWidth, dp(42), Gravity.CENTER))
        spectrumView.setFrequencyRangeListener { minHz, maxHz ->
            rangeButton.text = "RANGE ($minHz–$maxHz Hz)"
            val width = rangeButton.paint.measureText(rangeButton.text.toString()).roundToInt() +
                    rangeButton.paddingLeft + rangeButton.paddingRight + dp(4)
            val params = rangeButton.layoutParams as android.widget.FrameLayout.LayoutParams
            params.width = width
            params.gravity = Gravity.CENTER
            rangeButton.layoutParams = params
        }
        // Peak readout in the upper-right corner of the frequency-selection row.
        val peakReadout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(6), 0, dp(8), 0)
        }
        headerPeakFrequencyText = TextView(this).apply {
            text = "-- Hz"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.END
            includeFontPadding = false
        }
        headerPeakDbText = TextView(this).apply {
            text = "-- dB"
            textSize = 11f
            setTextColor(Color.rgb(210, 220, 232))
            gravity = Gravity.END
            includeFontPadding = false
        }
        peakReadout.addView(headerPeakFrequencyText)
        peakReadout.addView(headerPeakDbText)
        header.addView(peakReadout, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.CENTER_VERTICAL or Gravity.END))

        parent.addView(header, index, oldParams)
    }

    private fun updateStartButtonAppearance() {
        if (!::startButton.isInitialized) return
        val color = if (running) Color.rgb(120, 25, 35) else Color.rgb(30, 125, 65)
        startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        startButton.setTextColor(Color.WHITE)
        (startButton as? AnalyzerIconButton)?.icon =
            if (running) AnalyzerIconButton.Icon.STOP else AnalyzerIconButton.Icon.PLAY
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == permissionRequest &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startAnalysis()
        } else if (requestCode == permissionRequest) {
            Toast.makeText(
                this,
                "Microphone permission is required.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    private fun startAnalysis() {
        if (running) return

        // Clear old spectrum data while preserving the selected frequency range and axis mode.
        spectrumView.resetView()

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showStatus("Microphone permission is required.")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            showStatus("Audio recording is not supported.")
            return
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, fftSize * 2)
            )
        } catch (e: SecurityException) {
            showStatus("Microphone permission denied.")
            return
        } catch (e: IllegalArgumentException) {
            showStatus("Invalid audio configuration.")
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            showStatus("Microphone initialization failed.")
            return
        }

        try {
            audioRecord.startRecording()
        } catch (e: SecurityException) {
            audioRecord.release()
            showStatus("Microphone permission denied.")
            return
        } catch (e: IllegalStateException) {
            audioRecord.release()
            showStatus("Unable to start recording.")
            return
        }

        recorder = audioRecord
        running = true

        (startButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.STOP
        updateStartButtonAppearance()
        statusText.text = ""
        statusText.visibility = View.GONE

        audioThread = Thread {
            processAudio(audioRecord)
        }.also {
            it.start()
        }
    }

    private fun processAudio(audioRecord: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Keep a rolling buffer large enough for the maximum supported FFT size.
        val ringBuffer = ShortArray(16384)
        val readBuffer = ShortArray(2048)
        var writeIndex = 0
        var samplesSeen = 0L
        var samplesSinceFft = 0
        var activeFftSize = 8192
        var activeHopSize = activeFftSize / 4
        var samples = ShortArray(activeFftSize)
        var real = DoubleArray(activeFftSize)
        var imaginary = DoubleArray(activeFftSize)
        var magnitudes = DoubleArray(activeFftSize / 2)

        while (running) {
            // Frames started before RESET must not be allowed to restore old graph state.
            val frameResetGeneration = spectrumView.getResetGeneration()
            val count = try {
                audioRecord.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) {
                -1
            }
            if (count <= 0) {
                if (!running) break
                continue
            }

            for (i in 0 until count) {
                ringBuffer[writeIndex] = readBuffer[i]
                writeIndex = (writeIndex + 1) % ringBuffer.size
            }
            samplesSeen += count
            samplesSinceFft += count

            // Increase FFT resolution automatically when the user zooms into a narrower band.
            val requestedFftSize = spectrumView.getRecommendedFftSize()
            if (requestedFftSize != activeFftSize) {
                activeFftSize = requestedFftSize
                activeHopSize = activeFftSize / 4
                samples = ShortArray(activeFftSize)
                real = DoubleArray(activeFftSize)
                imaginary = DoubleArray(activeFftSize)
                magnitudes = DoubleArray(activeFftSize / 2)
                samplesSinceFft = 0
            }

            if (samplesSeen < activeFftSize || samplesSinceFft < activeHopSize) continue
            samplesSinceFft = 0

            // Copy the newest FFT window from the circular buffer in chronological order.
            var readIndex = (writeIndex - activeFftSize + ringBuffer.size) % ringBuffer.size
            for (i in 0 until activeFftSize) {
                samples[i] = ringBuffer[readIndex]
                readIndex = (readIndex + 1) % ringBuffer.size
            }

            var sumSquares = 0.0
            for (i in 0 until activeFftSize) {
                val value = samples[i].toDouble() / 32768.0
                sumSquares += value * value
                val window = 0.5 * (1.0 - cos(2.0 * PI * i / (activeFftSize - 1)))
                real[i] = value * window
                imaginary[i] = 0.0
            }

            fft(real, imaginary)

            var peakBin = 1
            var peakMagnitude = 0.0
            for (i in magnitudes.indices) {
                val magnitude = hypot(real[i], imaginary[i])
                magnitudes[i] = magnitude
                val binFrequency = i * sampleRate.toDouble() / activeFftSize
                // Keep peak detection within the intended 20 kHz analysis range.
                if (i > 0 && binFrequency <= 20000.0 && magnitude > peakMagnitude) {
                    peakMagnitude = magnitude
                    peakBin = i
                }
            }

            // Parabolic interpolation improves the displayed peak frequency estimate.
            var peakOffset = 0.0
            if (peakBin > 1 && peakBin < magnitudes.size - 1) {
                val leftMagnitude = ln(max(magnitudes[peakBin - 1], 1e-12))
                val centerMagnitude = ln(max(magnitudes[peakBin], 1e-12))
                val rightMagnitude = ln(max(magnitudes[peakBin + 1], 1e-12))
                val denominator = leftMagnitude - 2.0 * centerMagnitude + rightMagnitude
                if (denominator != 0.0) {
                    peakOffset = (0.5 * (leftMagnitude - rightMagnitude) / denominator).coerceIn(-0.5, 0.5)
                }
            }

            val peakFrequency = (peakBin + peakOffset) * sampleRate / activeFftSize
            val rms = kotlin.math.sqrt(sumSquares / activeFftSize)
            val levelDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
            val spectrumSnapshot = magnitudes.copyOf()
            val currentFftSize = activeFftSize

            runOnUiThread {
                if (running && frameResetGeneration == spectrumView.getResetGeneration()) {
                    spectrumView.setSpectrum(spectrumSnapshot, sampleRate, currentFftSize, peakFrequency)
                    val peakDb = if (peakMagnitude > 0.0) {
                        20.0 * log10(peakMagnitude * 4.0 / currentFftSize)
                    } else {
                        -120.0
                    }
                    headerPeakFrequencyText?.text = if (peakFrequency >= 1000.0) {
                        String.format(java.util.Locale.US, "%.2f kHz", peakFrequency / 1000.0)
                    } else {
                        String.format(java.util.Locale.US, "%.1f Hz", peakFrequency)
                    }
                    headerPeakDbText?.text = String.format(java.util.Locale.US, "%.1f dB", peakDb)
                    peakText.text = String.format(
                        java.util.Locale.US,
                        "Peak: %.1f Hz    Level: %.1f dB    FFT: %d",
                        peakFrequency,
                        levelDb,
                        currentFftSize
                    )
                }
            }
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0

        // Bit-reversal permutation.
        for (i in 1 until n) {
            var bit = n shr 1

            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }

            j = j xor bit

            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal

                val tempImaginary = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = tempImaginary
            }
        }

        var length = 2

        while (length <= n) {
            val angle = -2.0 * PI / length

            val wLengthReal = cos(angle)
            val wLengthImaginary = sin(angle)

            val half = length / 2
            var start = 0

            while (start < n) {
                var wReal = 1.0
                var wImaginary = 0.0

                for (k in 0 until half) {
                    val even = start + k
                    val odd = even + half

                    val oddReal =
                        real[odd] * wReal -
                                imaginary[odd] * wImaginary

                    val oddImaginary =
                        real[odd] * wImaginary +
                                imaginary[odd] * wReal

                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary

                    real[even] += oddReal
                    imaginary[even] += oddImaginary

                    val nextWReal =
                        wReal * wLengthReal -
                                wImaginary * wLengthImaginary

                    wImaginary =
                        wReal * wLengthImaginary +
                                wImaginary * wLengthReal

                    wReal = nextWReal
                }

                start += length
            }

            length = length shl 1
        }
    }

    private fun stopAnalysis() {
        running = false

        (startButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.PLAY
        updateStartButtonAppearance()
        statusText.text = ""
        statusText.visibility = View.GONE

        val audioRecord = recorder
        recorder = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        audioRecord?.release()

        spectrumView.resetView()
    }

    override fun onDestroy() {
        stopTone()
        stopAnalysis()
        pianoEngineRunning = false
        try { pianoAudioThread?.join(300) } catch (_: InterruptedException) {}
        pianoAudioThread = null
        try { pianoAudioTrack?.pause() } catch (_: Exception) {}
        try { pianoAudioTrack?.flush() } catch (_: Exception) {}
        pianoAudioTrack?.release()
        pianoAudioTrack = null
        synchronized(pianoAudioLock) { pianoVoices.clear() }
        pianoSamples.clear()
        pianoHeldNotes.clear()
        super.onDestroy()
    }

    companion object {
        private const val PIANO_LOAD_TAG = "SpectrumPianoLoad"
    }
}

private class PianoKeyboardView(
    context: Context,
    private val onKeyChange: (midiNote: Int, pressed: Boolean) -> Unit
) : View(context) {
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 245, 240); style = Paint.Style.FILL }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 29, 36); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 78, 90); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 75); style = Paint.Style.FILL }
    private val whiteNotes = (36..96).filter { it % 12 !in listOf(1, 3, 6, 8, 10) } // C2–C7
    private val blackNotes = (36..95).map { note -> if (note % 12 in listOf(1, 3, 6, 8, 10)) note else -1 }
    private val pointerNotes = mutableMapOf<Int, Int>()
    private val activeNotes = mutableSetOf<Int>()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 23, 36))
        if (width <= 0 || height <= 0) return
        val whiteWidth = width.toFloat() / whiteNotes.size
        val top = height * 0.04f
        val bottom = height * 0.96f
        whiteNotes.forEachIndexed { index, note ->
            val left = index * whiteWidth
            val right = (index + 1) * whiteWidth
            canvas.drawRect(left, top, right, bottom, if (note in activeNotes) pressedPaint else whitePaint)
            canvas.drawRect(left, top, right, bottom, linePaint)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = (height * 0.075f).coerceIn(10f, 24f); textAlign = Paint.Align.CENTER }
            canvas.drawText(noteName(note), (left + right) / 2f, bottom - height * 0.04f, labelPaint)
        }
        val blackWidth = whiteWidth * 0.58f
        val blackHeight = height * 0.60f
        for (note in blackNotes) {
            if (note < 0) continue
            val whiteIndex = whiteNotes.count { it < note }
            val left = whiteIndex * whiteWidth - blackWidth / 2f
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

    private fun noteAt(x: Float, y: Float): Int? {
        if (y < 0 || y > height) return null
        val whiteWidth = width.toFloat() / whiteNotes.size
        val blackWidth = whiteWidth * 0.58f
        val blackHeight = height * 0.60f
        if (y <= blackHeight) {
            for (note in blackNotes) {
                if (note < 0) continue
                val whiteIndex = whiteNotes.count { it < note }
                val left = whiteIndex * whiteWidth - blackWidth / 2f
                if (x in left..(left + blackWidth)) return note
            }
        }
        return whiteNotes[(x / whiteWidth).toInt().coerceIn(0, whiteNotes.lastIndex)]
    }

    private fun noteToFrequency(note: Int): Int = (440.0 * 2.0.pow((note - 69) / 12.0)).roundToInt().coerceIn(20, 20000)
    private fun noteName(note: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[note % 12] + (note / 12 - 1)
}


class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundColor = Color.rgb(15, 23, 36)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(49, 63, 81)
        strokeWidth = 1f
    }

    // Live spectrum is white for strong contrast against the dark blue background.
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val peakHoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 100, 120)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val peakHoldLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 130, 145)
        textSize = 23f
    }

    private val peakLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 70)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val peakPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 70)
        style = Paint.Style.FILL
    }

    private val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val cursorPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 255)
        style = Paint.Style.FILL
    }

    private val cursorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(125, 235, 255)
        // Match the 14sp text size used by the RESET GRAPH button.
        textSize = 14f * resources.displayMetrics.scaledDensity
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val cursorInfoBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 36)
        style = Paint.Style.FILL
    }

    private val cursorInfoBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 27f
    }

    private val dbLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 27f
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 135)
        textSize = 23f
        style = Paint.Style.FILL
        setShadowLayer(3f, 0f, 1f, Color.rgb(15, 23, 36))
    }

    // Custom frequency-grid divisions requested for the spectrum display.
    private val gridFrequencies = intArrayOf(
        0, 40, 60, 100, 200, 340, 400, 500, 600, 700, 770, 880,
        1000, 2000, 2800, 3200, 4000, 5100, 6200, 7300, 8400,
        9500, 10000, 11000, 12000, 13000, 14000, 15000, 16000,
        17000, 18000, 19000, 20000, 24000, 28000, 32000, 36000, 40000
    )

    private val frequencyLabelTicks = setOf(40, 60, 100, 200, 500, 1000, 2000, 4000, 10000, 20000)

    // Each band is assigned a relative width, independent of its Hz span.
    // This creates the intentionally non-linear, custom-spaced X axis.
    private val customAxisBreaks = doubleArrayOf(
        0.0, 40.0, 60.0, 100.0, 200.0, 500.0,
        1000.0, 2000.0, 4000.0, 10000.0, 20000.0, 40000.0
    )
    private val customAxisWeights = doubleArrayOf(
        1.0, 1.0, 1.5, 1.75, 2.25, 1.75, 1.75, 1.75, 2.0, 1.75, 1.75
    )
    private val customAxisCumulative = DoubleArray(customAxisBreaks.size).also { cumulative ->
        for (i in customAxisWeights.indices) cumulative[i + 1] = cumulative[i] + customAxisWeights[i]
    }

    private val spectrumPath = Path()
    private val peakHoldPath = Path()
    private val handler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private var multiTouchInProgress = false
    private val longPressTimeoutMs = 650L

    @Volatile
    private var magnitudes = DoubleArray(0)

    private var smoothedMagnitudes = DoubleArray(0)
    private val averagingAlpha = 0.28

    private var linearFrequencyAxis = false
    @Volatile
    private var spectrogramEnabled = false
    private val spectrogramHistory = java.util.ArrayDeque<DoubleArray>()
    private val spectrogramHistoryLimit = 140
    private var peakHoldEnabled = false
    private var peakHoldMagnitudes = DoubleArray(0)
    private var dbCeiling = 10
    private var dbFloor = -130
    private var dbRangeMode = "DEFAULT"
    private var savedDbRange: Pair<Int, Int>? = null
    private var savedFrequencyRangeForKeys: Pair<Double, Double>? = null
    private var panStartX = 0f
    private var panStartY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var draggingYScale = false
    private var yScaleStartY = 0f
    private var yScaleStartRange = 80f
    // Approximately 5 mm wide on screen, with a full-width touch target.
    private val yScaleStripWidth = 32f * resources.displayMetrics.density
    private var initialAutoRangePending = true
    @Volatile
    private var resetGeneration = 0
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    fun getResetGeneration(): Int = resetGeneration
    private var controlStateListener: ((String, Boolean, Int, Int) -> Unit)? = null
    private var frequencyRangeListener: ((Int, Int) -> Unit)? = null

    @Volatile
    private var sourceSampleRate = 48000

    @Volatile
    private var sourceFftSize = 8192

    @Volatile
    private var peakFrequency = 0.0

    private var selectedFrequency: Double? = null
    private var selectedSpectrogramXRatio: Double? = null
    private var selectedSpectrogramYRatio: Double? = null

    private var visibleMinFrequency = 20.0
    private var visibleMaxFrequency = 20000.0

    private val minFrequencyBound = 20.0
    private val maxFrequencyBound = 40000.0
    private val minLogSpan = ln(1.5)
    private val maxLogSpan = ln(maxFrequencyBound / minFrequencyBound)

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAt(detector.focusX, detector.scaleFactor)
                return true
            }
        }
    )

    private val longPressRunnable = Runnable {
        if (!multiTouchInProgress) {
            longPressTriggered = true
            togglePeakHold()
        }
    }

    // Keep 8192 samples for the full view and switch to 16384 when zoomed in.
    // Hysteresis prevents rapid FFT-size switching around the zoom threshold,
    // which can make the spectrum appear to jump vertically during pinch zoom.
    @Volatile
    private var highResolutionFftEnabled = false

    fun getRecommendedFftSize(): Int {
        val fullSpan = ln(maxFrequencyBound / minFrequencyBound)
        val visibleSpan = ln(visibleMaxFrequency / visibleMinFrequency)
        val zoomFactor = fullSpan / visibleSpan.coerceAtLeast(1e-9)

        if (!highResolutionFftEnabled && zoomFactor >= 1.6) {
            highResolutionFftEnabled = true
        } else if (highResolutionFftEnabled && zoomFactor <= 1.2) {
            highResolutionFftEnabled = false
        }

        return if (highResolutionFftEnabled) 16384 else 8192
    }

    fun setSpectrum(
        data: DoubleArray,
        sampleRate: Int,
        fftSize: Int,
        peakFrequency: Double
    ) {
        if (smoothedMagnitudes.size != data.size) {
            synchronized(spectrogramHistory) { spectrogramHistory.clear() }
            smoothedMagnitudes = data.copyOf()
        } else {
            for (i in data.indices) {
                smoothedMagnitudes[i] =
                    averagingAlpha * data[i] +
                            (1.0 - averagingAlpha) * smoothedMagnitudes[i]
            }
        }

        magnitudes = smoothedMagnitudes.copyOf()
        synchronized(spectrogramHistory) {
            spectrogramHistory.addLast(magnitudes.copyOf())
            while (spectrogramHistory.size > spectrogramHistoryLimit) spectrogramHistory.removeFirst()
        }
        // Keep the vertical axis range at -160 dB to -30 dB after startup and reset.
        // Do not auto-center the graph on the strongest peak, which can make it jump vertically.
        if (initialAutoRangePending) {
            dbFloor = -160
            dbCeiling = -30
            initialAutoRangePending = false
            notifyControlState()
        }
        if (peakHoldEnabled) {
            val binWidth = sampleRate.toDouble() / fftSize
            if (peakHoldMagnitudes.size != magnitudes.size) {
                peakHoldMagnitudes = copySpectrumUpTo20kHz(magnitudes, sampleRate, fftSize)
            } else {
                for (i in magnitudes.indices) {
                    if (i * binWidth > 20000.0) {
                        peakHoldMagnitudes[i] = 0.0
                    } else if (magnitudes[i] > peakHoldMagnitudes[i]) {
                        peakHoldMagnitudes[i] = magnitudes[i]
                    }
                }
            }
        }
        sourceSampleRate = sampleRate
        sourceFftSize = fftSize
        this.peakFrequency = peakFrequency
        postInvalidateOnAnimation()
    }

    fun clearSpectrum() {
        magnitudes = DoubleArray(0)
        smoothedMagnitudes = DoubleArray(0)
        synchronized(spectrogramHistory) { spectrogramHistory.clear() }
        peakHoldMagnitudes = DoubleArray(0)
        peakFrequency = 0.0
        // Preserve the user's current scale when analysis is stopped/restarted.
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Keep parent containers (for example, a ScrollView) from interpreting
        // the vertical movement of a pinch gesture as a request to scroll the UI.
        if (event.pointerCount > 1 || scaleGestureDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                multiTouchInProgress = false
                dragging = false
                // Reserve the full 5 mm right-hand strip for vertical zoom.
                // Its touch target reaches the screen edge for easier use.
                draggingYScale = event.x >= width - yScaleStripWidth
                yScaleStartY = event.y
                yScaleStartRange = (dbCeiling - dbFloor).toFloat()
                panStartX = event.x
                panStartY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                handler.removeCallbacks(longPressRunnable)
                if (!draggingYScale) handler.postDelayed(longPressRunnable, longPressTimeoutMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Lock the gesture to the graph for the entire multi-touch sequence.
                parent?.requestDisallowInterceptTouchEvent(true)
                multiTouchInProgress = true
                dragging = false
                draggingYScale = false
                handler.removeCallbacks(longPressRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    multiTouchInProgress = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    handler.removeCallbacks(longPressRunnable)
                } else if (multiTouchInProgress) {
                    // After a pinch, ignore the remaining finger until the whole
                    // gesture ends; otherwise its last movement can pan the graph.
                    handler.removeCallbacks(longPressRunnable)
                } else if (draggingYScale && !longPressTriggered) {
                    initialAutoRangePending = false
                    handler.removeCallbacks(longPressRunnable)
                    val totalDy = event.y - yScaleStartY
                    val newRange = (yScaleStartRange * kotlin.math.exp(totalDy.toDouble() * 0.008)).toInt().coerceIn(20, 160)
                    // Change only the dB span; keep its midpoint fixed so the spectrum does not drift vertically.
                    val center = (dbCeiling + dbFloor) / 2.0
                    var newFloor = kotlin.math.round(center - newRange / 2.0).toInt()
                    var newCeiling = newFloor + newRange
                    if (newFloor < -160) {
                        newFloor = -160
                        newCeiling = newFloor + newRange
                    }
                    if (newCeiling > 10) {
                        newCeiling = 10
                        newFloor = newCeiling - newRange
                    }
                    dbFloor = newFloor
                    dbCeiling = newCeiling
                    notifyControlState()
                    invalidate()
                } else if (!longPressTriggered) {
                    val totalDx = event.x - panStartX
                    val totalDy = event.y - panStartY
                    if (!dragging && hypot(totalDx, totalDy) > touchSlop) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging && !scaleGestureDetector.isInProgress) {
                        initialAutoRangePending = false
                        panBy(event.x - lastTouchX, event.y - lastTouchY)
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Keep the parent from intercepting while the last finger is lifted.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val wasMultiTouch = multiTouchInProgress
                multiTouchInProgress = false
                // Allow the parent to handle normal scrolling again after the full gesture ends.
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!longPressTriggered && !dragging && !draggingYScale && !wasMultiTouch && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    performClick()
                    if (spectrogramEnabled) {
                        selectSpectrogramAt(event.x, event.y)
                    } else {
                        selectedSpectrogramXRatio = null
                        selectedSpectrogramYRatio = null
                        selectFrequencyAt(event.x)
                    }
                }
                draggingYScale = false
                dragging = false
            }
        }
        return true
    }

    private fun performClickIfShortPress() {
        if (!longPressTriggered) performClick()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setControlStateListener(listener: (String, Boolean, Int, Int) -> Unit) {
        controlStateListener = listener
        notifyControlState()
    }

    fun setFrequencyRangeListener(listener: (Int, Int) -> Unit) {
        frequencyRangeListener = listener
        notifyFrequencyRange()
    }

    private fun notifyFrequencyRange() {
        val (minHz, maxHz) = getVisibleFrequencyRangeHz()
        frequencyRangeListener?.invoke(minHz, maxHz)
    }

    private fun notifyControlState() {
        controlStateListener?.invoke(if (linearFrequencyAxis) "LINEAR" else "LOG", peakHoldEnabled, dbCeiling, dbFloor)
    }

    fun isLinearFrequencyAxis(): Boolean = linearFrequencyAxis

    fun isSpectrogramEnabled(): Boolean = spectrogramEnabled

    fun setDbRangeMode(mode: String) {
        val nextMode = when (mode) {
            "TONE", "KEYS" -> mode
            else -> "DEFAULT"
        }
        if (nextMode == dbRangeMode) return

        if (dbRangeMode == "DEFAULT" && nextMode != "DEFAULT") {
            savedDbRange = if (initialAutoRangePending) (-160 to -30) else (dbFloor to dbCeiling)
        }

        dbRangeMode = nextMode
        when (nextMode) {
            "TONE" -> { dbFloor = -135; dbCeiling = -30 }
            "KEYS" -> { dbFloor = -140; dbCeiling = -30 }
            else -> {
                val restoredRange = savedDbRange ?: (-160 to -30)
                dbFloor = restoredRange.first
                dbCeiling = restoredRange.second
                savedDbRange = null
            }
        }
        initialAutoRangePending = false
        invalidate()
        notifyControlState()
    }

    fun setKeysFrequencyRangeActive(active: Boolean) {
        if (active) {
            if (savedFrequencyRangeForKeys == null) {
                savedFrequencyRangeForKeys = visibleMinFrequency to visibleMaxFrequency
            }
            visibleMinFrequency = 65.0
            visibleMaxFrequency = 12500.0
        } else {
            val previousRange = savedFrequencyRangeForKeys ?: return
            visibleMinFrequency = previousRange.first
            visibleMaxFrequency = previousRange.second
            savedFrequencyRangeForKeys = null
        }
        invalidate()
        notifyFrequencyRange()
    }

    fun toggleFrequencyAxis() {
        linearFrequencyAxis = !linearFrequencyAxis
        invalidate()
        notifyControlState()
    }

    fun toggleSpectrogram(): Boolean {
        spectrogramEnabled = !spectrogramEnabled
        // Show the selected view on the next animation frame. New FFT frames continue
        // filling the existing 140-frame history while the view is visible.
        postInvalidateOnAnimation()
        return spectrogramEnabled
    }

    private fun spectrogramColor(db: Double): Int {
        val normalized = ((db - dbFloor) / (dbCeiling - dbFloor).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        // Near-black for weak signals, purple for stronger signals, and occasional white for the strongest.
        return when {
            normalized < 0.38 -> {
                val t = normalized / 0.38
                Color.rgb((8 + 34 * t).toInt(), (2 + 4 * t).toInt(), (14 + 48 * t).toInt())
            }
            normalized < 0.68 -> {
                val t = (normalized - 0.38) / 0.30
                Color.rgb((42 + 105 * t).toInt(), (6 + 18 * t).toInt(), (62 + 150 * t).toInt())
            }
            normalized < 0.88 -> {
                val t = (normalized - 0.68) / 0.20
                Color.rgb((147 + 82 * t).toInt(), (24 + 105 * t).toInt(), (212 + 35 * t).toInt())
            }
            else -> {
                val t = (normalized - 0.88) / 0.12
                Color.rgb((229 + 26 * t).toInt(), (129 + 126 * t).toInt(), (247 + 8 * t).toInt())
            }
        }
    }

    private fun drawSpectrogram(
        canvas: Canvas,
        history: List<DoubleArray>,
        sampleRate: Int,
        fftSize: Int,
        minFreq: Double,
        maxFreq: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float
    ) {
        if (history.isEmpty() || right <= left || bottom <= top) return
        // Limit horizontal raster columns to avoid thousands of tiny draw calls per frame.
        // The waterfall still updates on every incoming FFT frame.
        val columns = max(1, (right - left).toInt().coerceAtMost(240))
        val rows = history.size
        val cellWidth = (right - left) / columns
        val cellHeight = (bottom - top) / rows
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val highestAnalyzedFrequency = min(sampleRate / 2.0, 20000.0)

        for (row in history.indices) {
            val frame = history[row]
            if (frame.isEmpty()) continue
            for (column in 0 until columns) {
                val ratio = (column + 0.5) / columns.toDouble()
                val frequency = frequencyAtRatio(ratio, minFreq, maxFreq)
                if (frequency > highestAnalyzedFrequency) {
                    paint.color = backgroundColor
                } else {
                    val bin = (frequency * fftSize / sampleRate).roundToInt().coerceIn(0, frame.lastIndex)
                    val normalizedMagnitude = frame[bin] * 4.0 / fftSize
                    val magnitudeDb = if (normalizedMagnitude > 1e-8) 20.0 * log10(normalizedMagnitude) else -160.0
                    paint.color = spectrogramColor(magnitudeDb)
                }
                val x0 = left + column * cellWidth
                // Oldest frames are at the top; newest frames are at the bottom.
                val y0 = top + row * cellHeight
                canvas.drawRect(x0, y0, x0 + cellWidth + 1f, y0 + cellHeight + 1f, paint)
            }
        }
    }

    fun togglePeakHold() {
        peakHoldEnabled = !peakHoldEnabled
        if (peakHoldEnabled) {
            peakHoldMagnitudes = copySpectrumUpTo20kHz(magnitudes, sourceSampleRate, sourceFftSize)
            Toast.makeText(context, "Peak Hold enabled", Toast.LENGTH_SHORT).show()
        } else {
            peakHoldMagnitudes = DoubleArray(0)
            Toast.makeText(context, "Peak Hold cleared", Toast.LENGTH_SHORT).show()
        }
        invalidate()
        notifyControlState()
    }

    fun getVisibleFrequencyRangeHz(): Pair<Int, Int> = visibleMinFrequency.roundToInt() to visibleMaxFrequency.roundToInt()

    fun setFrequencyRange(minFrequencyHz: Int, maxFrequencyHz: Int) {
        val minHz = minFrequencyHz.coerceIn(0, 20000).toDouble().coerceAtLeast(minFrequencyBound)
        val maxHz = maxFrequencyHz.coerceIn(0, 20000).toDouble()
        if (maxHz <= minHz) {
            Toast.makeText(context, "The selected range must be wider than 20 Hz.", Toast.LENGTH_SHORT).show()
            return
        }
        visibleMinFrequency = minHz
        visibleMaxFrequency = maxHz
        notifyFrequencyRange()
        selectedFrequency = null
        selectedSpectrogramXRatio = null
        selectedSpectrogramYRatio = null
        highResolutionFftEnabled = false
        invalidate()
    }

    private fun selectFrequencyAt(touchX: Float) {
        val left = 160f
        val right = width.toFloat() - yScaleStripWidth - 2f
        if (right <= left || touchX < left || touchX > right) return
        val ratio = ((touchX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        val selected = frequencyAtRatio(ratio, visibleMinFrequency, visibleMaxFrequency)
        selectedFrequency = selected.coerceIn(minFrequencyBound, 20000.0)
        invalidate()
    }

    private fun selectSpectrogramAt(touchX: Float, touchY: Float) {
        val left = 160f
        val right = width.toFloat() - yScaleStripWidth - 2f
        val top = 56f
        val bottom = height.toFloat() - 42f
        if (right <= left || bottom <= top || touchX < left || touchX > right || touchY < top || touchY > bottom) return
        selectedSpectrogramXRatio = ((touchX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        selectedSpectrogramYRatio = ((touchY - top) / (bottom - top)).coerceIn(0f, 1f).toDouble()
        selectedFrequency = null
        invalidate()
    }

    fun adjustDbCeiling(delta: Int) {
        initialAutoRangePending = false
        dbCeiling = (dbCeiling + delta).coerceIn(-125, 10)
        if (dbCeiling - dbFloor < 20) dbFloor = dbCeiling - 20
        invalidate()
        notifyControlState()
    }

    fun adjustDbFloor(delta: Int) {
        initialAutoRangePending = false
        dbFloor = (dbFloor + delta).coerceIn(-150, -10)
        if (dbCeiling - dbFloor < 20) dbCeiling = dbFloor + 20
        invalidate()
        notifyControlState()
    }

    fun resetView(preserveFrequencyRange: Boolean = false, useKeysFrequencyRange: Boolean = false) {
        // Invalidate any FFT frame that was already being calculated before reset.
        resetGeneration++
        if (!preserveFrequencyRange) {
            if (useKeysFrequencyRange && dbRangeMode == "KEYS") {
                visibleMinFrequency = 65.0
                visibleMaxFrequency = 12500.0
            } else {
                visibleMinFrequency = minFrequencyBound
                visibleMaxFrequency = 20000.0
            }
            linearFrequencyAxis = false
        }
        peakHoldEnabled = false
        peakHoldMagnitudes = DoubleArray(0)
        synchronized(spectrogramHistory) { spectrogramHistory.clear() }
        selectedFrequency = null
        dbCeiling = -30
        dbFloor = when (dbRangeMode) {
            "TONE" -> -135
            "KEYS" -> -140
            else -> -160
        }
        highResolutionFftEnabled = false
        sourceFftSize = 8192
        peakFrequency = 0.0

        // Discard the previous smoothed spectrum so the next FFT frame sets the
        // automatic dB range without blending in the previous graph's peak level.
        smoothedMagnitudes = DoubleArray(0)
        magnitudes = DoubleArray(0)
        peakFrequency = 0.0

        // Keep the same fixed vertical range as a fresh analysis session.
        initialAutoRangePending = false
        invalidate()
        notifyControlState()
        notifyFrequencyRange()
    }

    private fun customAxisPosition(frequency: Double): Double {
        val f = frequency.coerceIn(customAxisBreaks.first(), customAxisBreaks.last())
        for (i in customAxisWeights.indices) {
            val start = customAxisBreaks[i]
            val end = customAxisBreaks[i + 1]
            if (f <= end) {
                val part = ((f - start) / (end - start)).coerceIn(0.0, 1.0)
                return customAxisCumulative[i] + part * customAxisWeights[i]
            }
        }
        return customAxisCumulative.last()
    }

    private fun frequencyAtCustomAxisPosition(position: Double): Double {
        val p = position.coerceIn(0.0, customAxisCumulative.last())
        for (i in customAxisWeights.indices) {
            if (p <= customAxisCumulative[i + 1]) {
                val part = ((p - customAxisCumulative[i]) / customAxisWeights[i]).coerceIn(0.0, 1.0)
                return customAxisBreaks[i] + part * (customAxisBreaks[i + 1] - customAxisBreaks[i])
            }
        }
        return customAxisBreaks.last()
    }

    // The default axis uses custom band widths; the optional linear mode remains available.
    private fun frequencyRatio(frequency: Double, minFreq: Double, maxFreq: Double): Double {
        if (linearFrequencyAxis) return ((frequency - minFreq) / (maxFreq - minFreq)).coerceIn(0.0, 1.0)
        val low = customAxisPosition(minFreq)
        val high = customAxisPosition(maxFreq)
        return ((customAxisPosition(frequency) - low) / (high - low).coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
    }

    private fun frequencyAtRatio(ratio: Double, minFreq: Double, maxFreq: Double): Double {
        if (linearFrequencyAxis) return minFreq + ratio * (maxFreq - minFreq)
        val low = customAxisPosition(minFreq)
        val high = customAxisPosition(maxFreq)
        return frequencyAtCustomAxisPosition(low + ratio.coerceIn(0.0, 1.0) * (high - low))
            .coerceIn(minFreq, maxFreq)
    }

    private fun zoomAt(focusX: Float, scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f || width <= 0) return
        val left = 160f
        val right = width.toFloat() - yScaleStripWidth - 2f
        if (right <= left) return
        val focusRatio = ((focusX - left) / (right - left)).toDouble()
        val oldMin = visibleMinFrequency
        val oldMax = visibleMaxFrequency
        val anchorFrequency = frequencyAtRatio(focusRatio, oldMin, oldMax)
        val oldSpan = if (linearFrequencyAxis) oldMax - oldMin else ln(oldMax / oldMin)
        val minSpan = if (linearFrequencyAxis) 10.0 else minLogSpan
        val maxSpan = if (linearFrequencyAxis) maxFrequencyBound - minFrequencyBound else maxLogSpan
        val newSpan = (oldSpan / scaleFactor.toDouble()).coerceIn(minSpan, maxSpan)
        var newMin: Double
        var newMax: Double
        if (linearFrequencyAxis) {
            newMin = anchorFrequency - focusRatio * newSpan
            newMax = newMin + newSpan
            if (newMin < minFrequencyBound) { newMin = minFrequencyBound; newMax = newMin + newSpan }
            if (newMax > maxFrequencyBound) { newMax = maxFrequencyBound; newMin = newMax - newSpan }
        } else {
            val anchorLog = ln(anchorFrequency)
            var logMin = anchorLog - focusRatio * newSpan
            var logMax = logMin + newSpan
            val absoluteMin = ln(minFrequencyBound)
            val absoluteMax = ln(maxFrequencyBound)
            if (logMin < absoluteMin) { logMin = absoluteMin; logMax = logMin + newSpan }
            if (logMax > absoluteMax) { logMax = absoluteMax; logMin = logMax - newSpan }
            newMin = kotlin.math.exp(logMin)
            newMax = kotlin.math.exp(logMax)
        }
        visibleMinFrequency = newMin.coerceAtLeast(minFrequencyBound)
        visibleMaxFrequency = newMax.coerceAtMost(maxFrequencyBound)
        notifyFrequencyRange()
        invalidate()
    }

    private fun panBy(dx: Float, dy: Float) {
        // Horizontal dragging pans the frequency range; vertical dragging pans the
        // visible dB window without changing its height (dB span).
        val plotWidth = (width - 162f - yScaleStripWidth).coerceAtLeast(1f)
        val fraction = -dx / plotWidth
        val frequencySpan = if (linearFrequencyAxis) {
            visibleMaxFrequency - visibleMinFrequency
        } else {
            ln(visibleMaxFrequency / visibleMinFrequency)
        }
        val frequencyShift = fraction * frequencySpan
        if (linearFrequencyAxis) {
            var newMin = visibleMinFrequency + frequencyShift
            var newMax = visibleMaxFrequency + frequencyShift
            if (newMin < minFrequencyBound) { newMax += minFrequencyBound - newMin; newMin = minFrequencyBound }
            if (newMax > maxFrequencyBound) { newMin -= newMax - maxFrequencyBound; newMax = maxFrequencyBound }
            visibleMinFrequency = newMin.coerceAtLeast(minFrequencyBound)
            visibleMaxFrequency = newMax.coerceAtMost(maxFrequencyBound)
        } else {
            var newLogMin = ln(visibleMinFrequency) + frequencyShift
            var newLogMax = ln(visibleMaxFrequency) + frequencyShift
            val boundMin = ln(minFrequencyBound)
            val boundMax = ln(maxFrequencyBound)
            if (newLogMin < boundMin) { newLogMax += boundMin - newLogMin; newLogMin = boundMin }
            if (newLogMax > boundMax) { newLogMin -= newLogMax - boundMax; newLogMax = boundMax }
            visibleMinFrequency = kotlin.math.exp(newLogMin)
            visibleMaxFrequency = kotlin.math.exp(newLogMax)
        }

        // Keep the current dB range size fixed while moving it vertically.
        // The default/reset floor remains -130 dB, but panning can move the
        // visible window below that value so a zoomed trace can be repositioned.
        val plotHeight = (height - 80f).coerceAtLeast(1f)
        val dbSpan = (dbCeiling - dbFloor).coerceAtLeast(20)
        val dbShift = (dy / plotHeight * dbSpan).roundToInt()
        if (dbShift != 0) {
            val minPanFloor = -250
            val maxPanCeiling = 10
            val newFloor = (dbFloor + dbShift).coerceIn(minPanFloor, maxPanCeiling - dbSpan)
            dbFloor = newFloor
            dbCeiling = newFloor + dbSpan
            notifyControlState()
        }
        invalidate()
        notifyFrequencyRange()
    }

    private data class Peak(
        val bin: Int,
        val frequency: Double,
        val db: Double
    )

    private fun magnitudeToDb(magnitude: Double, fftSize: Int): Double {
        val normalized = magnitude * 4.0 / fftSize
        return if (normalized > 1e-12) 20.0 * log10(normalized) else -100.0
    }

    // Keep only spectrum bins at or below 20 kHz in stored traces.
    private fun copySpectrumUpTo20kHz(data: DoubleArray, sampleRate: Int, fftSize: Int): DoubleArray {
        val copy = data.copyOf()
        val binWidth = sampleRate.toDouble() / fftSize
        for (bin in copy.indices) {
            if (bin * binWidth > 20000.0) copy[bin] = 0.0
        }
        return copy
    }

    private fun drawSpectrumTrace(
        canvas: Canvas,
        data: DoubleArray,
        sampleRate: Int,
        fftSize: Int,
        minFreq: Double,
        maxFreq: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        paint: Paint,
        path: Path
    ) {
        if (data.isEmpty() || right <= left || minFreq >= 20000.0) return
        val logMin = ln(minFreq)
        val logMax = ln(maxFreq)
        val logSpan = logMax - logMin
        val binWidth = sampleRate.toDouble() / fftSize
        val pixels = max(1, (right - left).toInt())
        path.reset()
        var started = false

        for (pixel in 0..pixels) {
            val x = left + (right - left) * pixel.toFloat() / pixels
            val ratio = (x - left) / (right - left)
            val frequency = frequencyAtRatio(ratio.toDouble(), minFreq, maxFreq)
            // Stop at 20 kHz. The remaining 20–40 kHz area is display-only.
            if (frequency > 20000.0) break
            val binPosition = frequency / binWidth
            val lowerBin = binPosition.toInt()
            if (lowerBin <= 0 || lowerBin >= data.lastIndex) continue
            val fraction = (binPosition - lowerBin).coerceIn(0.0, 1.0)
            // Interpolate magnitudes between bins to avoid a blocky, stair-stepped trace when zoomed.
            val interpolatedMagnitude = data[lowerBin] * (1.0 - fraction) + data[lowerBin + 1] * fraction
            val db = magnitudeToDb(interpolatedMagnitude, fftSize).coerceIn(dbFloor.toDouble(), dbCeiling.toDouble())
            val y = top + ((dbCeiling - db) / (dbCeiling - dbFloor)).toFloat() * (bottom - top)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        if (started) canvas.drawPath(path, paint)
    }

    private fun findTopPeaks(
        data: DoubleArray,
        sampleRate: Int,
        fftSize: Int,
        minFreq: Double,
        maxFreq: Double
    ): List<Peak> {
        if (data.size < 5) return emptyList()
        val binWidth = sampleRate.toDouble() / fftSize
        val firstBin = max(2, kotlin.math.ceil(minFreq / binWidth).toInt())
        // Do not report peaks above 20 kHz, even though the display extends to 40 kHz.
        val lastBin = min(data.lastIndex - 1, kotlin.math.floor(min(maxFreq, 20000.0) / binWidth).toInt())
        if (lastBin <= firstBin) return emptyList()

        val candidates = ArrayList<Peak>()
        for (bin in firstBin..lastBin) {
            val magnitude = data[bin]
            if (magnitude >= data[bin - 1] && magnitude > data[bin + 1]) {
                val db = magnitudeToDb(magnitude, fftSize)
                if (db > -95.0) {
                    candidates.add(Peak(bin, bin * binWidth, db))
                }
            }
        }

        candidates.sortByDescending { it.db }
        val selected = ArrayList<Peak>(4)
        for (candidate in candidates) {
            // Prevent adjacent FFT bins belonging to one broad peak being reported separately.
            if (selected.none { kotlin.math.abs(it.bin - candidate.bin) < 5 }) {
                selected.add(candidate)
                if (selected.size == 4) break
            }
        }
        return selected
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(backgroundColor)

        // Leave room at the left edge for the front camera in landscape.
        val left = 160f
        val right = w - yScaleStripWidth - 2f
        val top = 56f
        val bottom = h - 42f
        if (right <= left || bottom <= top) return

        // Extend the bottom X-axis to the left edge of the view.
        canvas.drawLine(0f, bottom, left, bottom, gridPaint)

        val absoluteMaxFrequency = maxFrequencyBound
        val minFreq = visibleMinFrequency.coerceAtLeast(minFrequencyBound)
        val maxFreq = visibleMaxFrequency.coerceAtMost(absoluteMaxFrequency)
        if (maxFreq <= minFreq) return

        val logMin = ln(minFreq)
        val logMax = ln(maxFreq)
        val logSpan = logMax - logMin

        fun xForFrequency(freq: Double): Float {
            val bounded = freq.coerceIn(minFreq, maxFreq)
            val ratio = frequencyRatio(bounded, minFreq, maxFreq)
            return left + (right - left) * ratio.toFloat()
        }

        fun yForDb(db: Double): Float {
            val clampedDb = db.coerceIn(dbFloor.toDouble(), dbCeiling.toDouble())
            return top + ((dbCeiling - clampedDb) / (dbCeiling - dbFloor)).toFloat() * (bottom - top)
        }

        val historySnapshot = if (spectrogramEnabled) {
            synchronized(spectrogramHistory) { spectrogramHistory.toList() }
        } else {
            emptyList()
        }

        if (spectrogramEnabled) {
            // In waterfall mode, vertical position represents time rather than dB level.
            val historySeconds = historySnapshot.size * (sourceFftSize.toDouble() / 4.0 / sourceSampleRate.coerceAtLeast(1))
            for (i in 0..4) {
                val ratio = i / 4f
                val y = top + (bottom - top) * ratio
                canvas.drawLine(left, y, right, y, gridPaint)
                val secondsAgo = historySeconds * (1.0 - ratio)
                val label = if (i == 4) "0 s" else String.format(java.util.Locale.US, "%.1f s", secondsAgo)
                canvas.drawText(label, 69f, y + 8f, textPaint)
            }
            canvas.drawText("TIME", 73f, max(10f, top - 26f), dbLabelPaint)
        } else {
            var dbTick = kotlin.math.floor(dbCeiling / 10.0).toInt() * 10
            while (dbTick >= dbFloor) {
                val y = yForDb(dbTick.toDouble())
                canvas.drawLine(left, y, right, y, gridPaint)
                canvas.drawText(dbTick.toString(), 70f, y + 8f, textPaint)
                dbTick -= 10
            }
            // Place the unit above the Y-axis tick labels, with a clear gap above the grid.
            canvas.drawText("dB", 70f, top - 26f, dbLabelPaint)
        }
        for (frequency in gridFrequencies) {
            // The signal-processing range currently starts at 20 Hz. The 0 Hz grid
            // division is therefore represented at the left edge of the full-range view.
            if (frequency == 0) {
                if (minFreq <= minFrequencyBound && maxFreq >= 40000.0) {
                    canvas.drawLine(left, top, left, bottom, gridPaint)
                }
                continue
            }
            if (frequency < minFreq || frequency > maxFreq) continue
            val x = xForFrequency(frequency.toDouble())
            canvas.drawLine(x, top, x, bottom, gridPaint)
            if (frequency > 20000 || frequency !in frequencyLabelTicks) continue
            val label = when {
                frequency == 20000 -> "20k"
                frequency >= 1000 -> "${frequency / 1000}k"
                else -> frequency.toString()
            }
            canvas.drawText(label, x - textPaint.measureText(label) / 2f, bottom + 30f, textPaint)
        }

        // Visible draggable dB scale strip on the far right. Dragging it changes only vertical zoom.
        val stripLeft = w - yScaleStripWidth
        val scaleX = stripLeft + yScaleStripWidth * 0.52f
        val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(27, 37, 50)
            style = Paint.Style.FILL
        }
        val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(135, 160, 190)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val scaleTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(135, 160, 190)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val scaleHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(95, 190, 255)
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        if (!spectrogramEnabled) {
            canvas.drawRect(stripLeft, top, w, bottom, stripPaint)
            canvas.drawLine(scaleX, top + 10f, scaleX, bottom - 10f, scalePaint)
            for (i in 0..8) {
                val tickY = top + 10f + (bottom - top - 20f) * i / 8f
                val tickLength = if (i == 4) 10f else 6f
                canvas.drawLine(scaleX - tickLength, tickY, scaleX + 2f, tickY, scaleTickPaint)
            }
            canvas.drawLine(scaleX - 7f, (top + bottom) / 2f, scaleX + 7f, (top + bottom) / 2f, scaleHandlePaint)
            canvas.drawLine(scaleX - 5f, (top + bottom) / 2f - 7f, scaleX + 5f, (top + bottom) / 2f - 7f, scaleHandlePaint)
            canvas.drawLine(scaleX - 5f, (top + bottom) / 2f + 7f, scaleX + 5f, (top + bottom) / 2f + 7f, scaleHandlePaint)
        }

        val data = magnitudes
        if (data.isEmpty()) {
            canvas.drawText("Waiting for audio...", left + 20f, top + 50f, textPaint)
            return
        }

        if (spectrogramEnabled) {
            drawSpectrogram(
                canvas, historySnapshot, sourceSampleRate, sourceFftSize,
                minFreq, maxFreq, left, right, top, bottom
            )
            // Draw the frequency/time grid over the waterfall so it remains readable.
            for (frequency in gridFrequencies) {
                if (frequency <= 0 || frequency < minFreq || frequency > maxFreq) continue
                val x = xForFrequency(frequency.toDouble())
                canvas.drawLine(x, top, x, bottom, gridPaint)
            }
            for (i in 0..4) {
                val y = top + (bottom - top) * (i / 4f)
                canvas.drawLine(left, y, right, y, gridPaint)
            }
            // Tapping the waterfall selects a frequency and time. Show a vertical
            // cursor so the selected frequency is easy to follow across the timeline.
            val selectedXRatio = selectedSpectrogramXRatio
            val selectedYRatio = selectedSpectrogramYRatio
            if (selectedXRatio != null && selectedYRatio != null && historySnapshot.isNotEmpty()) {
                val cursorY = top + (bottom - top) * selectedYRatio.toFloat()
                val cursorX = left + (right - left) * selectedXRatio.toFloat()
                canvas.drawLine(cursorX, top, cursorX, bottom, cursorLinePaint)
                canvas.drawCircle(cursorX, cursorY, 4f, cursorPointPaint)

                val row = (selectedYRatio * (historySnapshot.size - 1)).roundToInt().coerceIn(0, historySnapshot.lastIndex)
                val frame = historySnapshot[row]
                val selectedHz = frequencyAtRatio(selectedXRatio, minFreq, maxFreq).coerceIn(20.0, 20000.0)
                val bin = (selectedHz * sourceFftSize / sourceSampleRate.coerceAtLeast(1)).roundToInt().coerceIn(0, frame.lastIndex.coerceAtLeast(0))
                val magnitude = if (frame.isNotEmpty()) frame[bin] * 4.0 / sourceFftSize else 0.0
                val levelDb = if (magnitude > 1e-8) 20.0 * log10(magnitude) else -160.0
                val historySeconds = historySnapshot.size * (sourceFftSize.toDouble() / 4.0 / sourceSampleRate.coerceAtLeast(1))
                val secondsAgo = historySeconds * (1.0 - selectedYRatio)
                val frequencyLabel = if (selectedHz >= 1000.0) {
                    String.format(java.util.Locale.US, "%.2f kHz", selectedHz / 1000.0)
                } else {
                    String.format(java.util.Locale.US, "%.1f Hz", selectedHz)
                }
                val label = "$frequencyLabel   ${String.format(java.util.Locale.US, "%.1f", levelDb)} dB   ${String.format(java.util.Locale.US, "%.1f", secondsAgo)} s"
                val horizontalPadding = 10f
                val verticalPadding = 7f
                val labelWidth = cursorTextPaint.measureText(label)
                val boxWidth = labelWidth + horizontalPadding * 2f
                val boxHeight = cursorTextPaint.textSize + verticalPadding * 2f
                val boxLeft = (left - 12f).coerceAtMost(max(0f, right - boxWidth)).coerceAtLeast(0f)
                val boxTop = max(0f, top - 14f)
                val infoRect = android.graphics.RectF(boxLeft, boxTop, min(right, boxLeft + boxWidth), boxTop + boxHeight)
                canvas.drawRoundRect(infoRect, 6f, 6f, cursorInfoBackgroundPaint)
                canvas.drawRoundRect(infoRect, 6f, 6f, cursorInfoBorderPaint)
                canvas.drawText(label, boxLeft + horizontalPadding, boxTop + verticalPadding + cursorTextPaint.textSize * 0.82f, cursorTextPaint)
            }
            return
        }

        // The live spectrum is white; the permanent Peak Hold trace is red.
        drawSpectrumTrace(
            canvas, data, sourceSampleRate, sourceFftSize, minFreq, maxFreq,
            left, right, top, bottom, spectrumPaint, spectrumPath
        )

        if (peakHoldEnabled && peakHoldMagnitudes.isNotEmpty()) {
            drawSpectrumTrace(
                canvas, peakHoldMagnitudes, sourceSampleRate, sourceFftSize, minFreq, maxFreq,
                left, right, top, bottom, peakHoldPaint, peakHoldPath
            )
        }

        // Mark and label the four strongest distinct peaks in the visible frequency range.
        val peaks = findTopPeaks(data, sourceSampleRate, sourceFftSize, minFreq, maxFreq)
        val placedLabels = ArrayList<Pair<Float, Float>>()
        for ((index, peak) in peaks.withIndex()) {
            val peakX = xForFrequency(peak.frequency)
            val peakY = yForDb(peak.db)
            canvas.drawCircle(peakX, peakY, 5.5f, peakPointPaint)

            val frequencyLabel = if (peak.frequency >= 1000.0) {
                String.format(java.util.Locale.US, "%.2f kHz", peak.frequency / 1000.0)
            } else {
                String.format(java.util.Locale.US, "%.1f Hz", peak.frequency)
            }
            val label = "$frequencyLabel  ${String.format(java.util.Locale.US, "%.1f", peak.db)} dB"
            val labelWidth = peakTextPaint.measureText(label)
            val labelX = (peakX - labelWidth / 2f).coerceIn(left, max(left, right - labelWidth))
            var labelY = max(top + 18f, peakY - 13f - index * 2f)

            // Keep nearby peak labels from occupying exactly the same position.
            for ((previousX, previousY) in placedLabels) {
                if (kotlin.math.abs(previousX - labelX) < labelWidth && kotlin.math.abs(previousY - labelY) < 24f) {
                    labelY = max(top + 18f, labelY - 25f)
                }
            }
            placedLabels.add(labelX to labelY)
            canvas.drawText(label, labelX, labelY, peakTextPaint)
        }

        // Keep the interpolated dominant peak frequency visible as a vertical guide.
        val peakHz = peakFrequency
        if (peakHz in minFreq..min(maxFreq, 20000.0) && peakHz > 0.0) {
            val peakX = xForFrequency(peakHz)
            canvas.drawLine(peakX, top, peakX, bottom, peakLinePaint)
        }

        // A short tap pins a cursor to a frequency; its level follows the live spectrum.
        val cursorHz = selectedFrequency
        if (cursorHz != null && cursorHz in minFreq..min(maxFreq, 20000.0)) {
            val binWidth = sourceSampleRate.toDouble() / sourceFftSize
            val binPosition = cursorHz / binWidth
            val lowerBin = binPosition.toInt().coerceIn(0, data.lastIndex)
            val upperBin = (lowerBin + 1).coerceAtMost(data.lastIndex)
            val fraction = (binPosition - lowerBin).coerceIn(0.0, 1.0)
            val magnitude = data[lowerBin] * (1.0 - fraction) + data[upperBin] * fraction
            val levelDb = magnitudeToDb(magnitude, sourceFftSize)
            val cursorX = xForFrequency(cursorHz)
            val cursorY = yForDb(levelDb)
            canvas.drawLine(cursorX, top, cursorX, bottom, cursorLinePaint)
            canvas.drawCircle(cursorX, cursorY, 6f, cursorPointPaint)
            val frequencyLabel = if (cursorHz >= 1000.0) {
                String.format(java.util.Locale.US, "%.2f kHz", cursorHz / 1000.0)
            } else {
                String.format(java.util.Locale.US, "%.1f Hz", cursorHz)
            }
            val label = "$frequencyLabel   ${String.format(java.util.Locale.US, "%.1f", levelDb)} dB"
            val horizontalPadding = 10f
            val verticalPadding = 7f
            val labelWidth = cursorTextPaint.measureText(label)
            val boxWidth = labelWidth + horizontalPadding * 2f
            val boxHeight = cursorTextPaint.textSize + verticalPadding * 2f

            // Keep the selected frequency and level in a fixed, high-contrast panel
            // aligned with the top edge of the graph grid.
            val boxLeft = (left - 12f).coerceAtMost(max(0f, right - boxWidth)).coerceAtLeast(0f)
            val boxTop = max(0f, top - 14f)
            val boxRight = min(right, boxLeft + boxWidth)
            val boxBottom = boxTop + boxHeight
            val infoRect = android.graphics.RectF(boxLeft, boxTop, boxRight, boxBottom)
            canvas.drawRoundRect(infoRect, 6f, 6f, cursorInfoBackgroundPaint)
            canvas.drawRoundRect(infoRect, 6f, 6f, cursorInfoBorderPaint)
            canvas.drawText(
                label,
                boxLeft + horizontalPadding,
                boxTop + verticalPadding + cursorTextPaint.textSize * 0.82f,
                cursorTextPaint
            )
        }
    }
}