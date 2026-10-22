package sk.spectrum.analyzer2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Process
import android.os.Handler
import android.os.Looper
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
import android.widget.LinearLayout
import android.widget.TextView
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

class MainActivity : Activity() {

    private lateinit var spectrumView: SpectrumView
    private lateinit var statusText: TextView
    private lateinit var peakText: TextView
    private lateinit var startButton: Button
    private var headerPeakFrequencyText: TextView? = null
    private var headerPeakDbText: TextView? = null

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
        setContentView(R.layout.activity_main)

        spectrumView = findViewById(R.id.spectrumView)
        statusText = findViewById(R.id.statusText)
        peakText = findViewById(R.id.peakText)
        startButton = findViewById(R.id.startButton)
        configureSpectrumHeader()
        statusText.visibility = View.GONE

        // Keep the spectrum area as large as possible and place Reset beside Start/Stop.
        peakText.visibility = View.GONE
        statusText.visibility = View.GONE

        val startParent = startButton.parent as? ViewGroup
        if (startParent != null) {
            val startIndex = startParent.indexOfChild(startButton)
            val originalParams = startButton.layoutParams
            startParent.removeView(startButton)

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            startButton.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 2, 4, 2)
            }
            startButton.textSize = 14f
            startButton.minHeight = 48
            startButton.minimumHeight = 48
            actionRow.addView(startButton)

            val resetButton = Button(this).apply {
                text = "RESET GRAPH"
                textSize = 14f
                minHeight = 48
                minimumHeight = 48
                setPadding(4, 0, 4, 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(49, 63, 81))
                setTextColor(Color.WHITE)
                setOnClickListener { spectrumView.resetView() }
            }
            actionRow.addView(resetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 2, 4, 2)
            })
            startParent.addView(actionRow, startIndex, originalParams)
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

        val index = parent.indexOfChild(title)
        val oldParams = title.layoutParams
        parent.removeView(title)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 4, 12, 4)
        }
        title.text = "SPECTRUM ANALYZER"
        title.textSize = 17f
        title.setTextColor(Color.rgb(225, 233, 243))
        title.maxLines = 1
        title.setPadding(0, 0, 8, 0)
        header.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val peakColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        val peakFrequencyView = TextView(this).apply {
            text = "— Hz"
            textSize = 25f
            setTextColor(Color.rgb(255, 190, 70))
            gravity = Gravity.END
            maxLines = 1
        }
        val peakDbView = TextView(this).apply {
            text = "— dB"
            textSize = 12f
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.END
            maxLines = 1
        }
        headerPeakFrequencyText = peakFrequencyView
        headerPeakDbText = peakDbView
        peakColumn.addView(peakFrequencyView)
        peakColumn.addView(peakDbView)
        header.addView(peakColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        parent.addView(header, index, oldParams)
    }

    private fun updateStartButtonAppearance() {
        if (!::startButton.isInitialized) return
        val color = if (running) Color.rgb(120, 25, 35) else Color.rgb(30, 125, 65)
        startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        startButton.setTextColor(Color.WHITE)
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

        // Start each analysis from the full frequency range and a centered automatic dB range.
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

        startButton.text = "STOP ANALYSIS"
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
                if (i > 0 && magnitude > peakMagnitude) {
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

        startButton.text = "START ANALYSIS"
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

        spectrumView.clearSpectrum()
    }

    override fun onDestroy() {
        stopAnalysis()
        super.onDestroy()
    }
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

    // Frozen spectrum is cyan so it can be compared with the live white spectrum.
    private val frozenSpectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(65, 210, 255)
        strokeWidth = 2.5f
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 27f
    }

    private val dbLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 18f
        style = Paint.Style.FILL
    }

    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 135)
        textSize = 23f
        style = Paint.Style.FILL
        setShadowLayer(3f, 0f, 1f, Color.rgb(15, 23, 36))
    }

    private val frozenLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 220, 255)
        textSize = 23f
        style = Paint.Style.FILL
        setShadowLayer(3f, 0f, 1f, Color.rgb(15, 23, 36))
    }

    // Custom frequency-grid divisions requested for the spectrum display.
    private val gridFrequencies = intArrayOf(
        0, 40, 60, 100, 200, 340, 400, 500, 600, 700, 770, 880,
        1000, 2000, 2800, 3200, 4000, 5100, 6200, 7300, 8400,
        9500, 10000, 11000, 12000, 13000, 14000, 15000, 16000,
        17000, 18000, 19000, 20000
    )

    private val frequencyLabelTicks = setOf(40, 60, 100, 200, 500, 1000, 2000, 4000, 10000, 20000)

    // Each band is assigned a relative width, independent of its Hz span.
    // This creates the intentionally non-linear, custom-spaced X axis.
    private val customAxisBreaks = doubleArrayOf(
        0.0, 40.0, 60.0, 100.0, 200.0, 500.0,
        1000.0, 2000.0, 4000.0, 10000.0, 20000.0
    )
    private val customAxisWeights = doubleArrayOf(
        1.0, 1.0, 1.5, 1.75, 2.25, 1.75, 1.75, 1.75, 2.0, 1.75
    )
    private val customAxisCumulative = DoubleArray(customAxisBreaks.size).also { cumulative ->
        for (i in customAxisWeights.indices) cumulative[i + 1] = cumulative[i] + customAxisWeights[i]
    }

    private val spectrumPath = Path()
    private val frozenPath = Path()
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
    private var peakHoldEnabled = false
    private var peakHoldMagnitudes = DoubleArray(0)
    private var dbCeiling = 10
    private var dbFloor = -130
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

    // Snapshot is displayed as a second trace while the live measurement continues.
    @Volatile
    private var frozenMagnitudes: DoubleArray? = null
    @Volatile
    private var frozenSampleRate = 48000
    @Volatile
    private var frozenFftSize = 8192

    @Volatile
    private var sourceSampleRate = 48000

    @Volatile
    private var sourceFftSize = 8192

    @Volatile
    private var peakFrequency = 0.0

    private var visibleMinFrequency = 20.0
    private var visibleMaxFrequency = 20000.0

    private val minFrequencyBound = 20.0
    private val maxFrequencyBound = 20000.0
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
            toggleFrozenSpectrum()
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
            smoothedMagnitudes = data.copyOf()
        } else {
            for (i in data.indices) {
                smoothedMagnitudes[i] =
                    averagingAlpha * data[i] +
                            (1.0 - averagingAlpha) * smoothedMagnitudes[i]
            }
        }

        magnitudes = smoothedMagnitudes.copyOf()
        // Keep the vertical axis range at -160 dB to -30 dB after startup and reset.
        // Do not auto-center the graph on the strongest peak, which can make it jump vertically.
        if (initialAutoRangePending) {
            dbFloor = -160
            dbCeiling = -30
            initialAutoRangePending = false
            notifyControlState()
        }
        if (peakHoldEnabled) {
            if (peakHoldMagnitudes.size != magnitudes.size) {
                peakHoldMagnitudes = magnitudes.copyOf()
            } else {
                for (i in magnitudes.indices) {
                    if (magnitudes[i] > peakHoldMagnitudes[i]) peakHoldMagnitudes[i] = magnitudes[i]
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
        peakHoldMagnitudes = DoubleArray(0)
        peakFrequency = 0.0
        frozenMagnitudes = null
        // Preserve the user's current scale when analysis is stopped/restarted.
        invalidate()
    }

    private fun toggleFrozenSpectrum() {
        val existingSnapshot = frozenMagnitudes
        if (existingSnapshot == null) {
            val current = magnitudes
            if (current.isNotEmpty()) {
                frozenMagnitudes = current.copyOf()
                frozenSampleRate = sourceSampleRate
                frozenFftSize = sourceFftSize
                Toast.makeText(context, "Spectrum snapshot saved", Toast.LENGTH_SHORT).show()
            }
        } else {
            frozenMagnitudes = null
            Toast.makeText(context, "Spectrum snapshot removed", Toast.LENGTH_SHORT).show()
        }
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
                if (!longPressTriggered && !dragging && !draggingYScale && !wasMultiTouch && event.actionMasked != MotionEvent.ACTION_CANCEL) performClick()
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

    private fun notifyControlState() {
        controlStateListener?.invoke(if (linearFrequencyAxis) "LINEAR" else "LOG", peakHoldEnabled, dbCeiling, dbFloor)
    }

    fun toggleFrequencyAxis() {
        linearFrequencyAxis = !linearFrequencyAxis
        invalidate()
        notifyControlState()
    }

    fun togglePeakHold() {
        peakHoldEnabled = !peakHoldEnabled
        if (peakHoldEnabled) {
            peakHoldMagnitudes = magnitudes.copyOf()
        } else {
            peakHoldMagnitudes = DoubleArray(0)
        }
        invalidate()
        notifyControlState()
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

    fun resetView() {
        // Invalidate any FFT frame that was already being calculated before reset.
        resetGeneration++
        visibleMinFrequency = minFrequencyBound
        visibleMaxFrequency = maxFrequencyBound
        linearFrequencyAxis = false
        peakHoldEnabled = false
        peakHoldMagnitudes = DoubleArray(0)
        frozenMagnitudes = null
        dbCeiling = -30
        dbFloor = -160
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
        val left = 65f
        val right = width.toFloat() - yScaleStripWidth - 2f
        if (right <= left) return
        val focusRatio = ((focusX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
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
        invalidate()
    }

    private fun panBy(dx: Float, dy: Float) {
        // Horizontal dragging pans the frequency range; vertical dragging pans the
        // visible dB window without changing its height (dB span).
        val plotWidth = (width - 80f - yScaleStripWidth).coerceAtLeast(1f)
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
        if (data.isEmpty() || right <= left) return
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
        val lastBin = min(data.lastIndex - 1, kotlin.math.floor(maxFreq / binWidth).toInt())
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

        val left = 65f
        val right = w - yScaleStripWidth - 2f
        val top = 12f
        val bottom = h - 42f
        if (right <= left || bottom <= top) return

        val absoluteMaxFrequency = min(maxFrequencyBound, sourceSampleRate / 2.0)
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

        var dbTick = kotlin.math.floor(dbCeiling / 10.0).toInt() * 10
        while (dbTick >= dbFloor) {
            val y = yForDb(dbTick.toDouble())
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(dbTick.toString(), 5f, y + 8f, textPaint)
            dbTick -= 10
        }
        // Place the dB unit above and to the right of the top tick label to avoid overlap.
        canvas.drawText("dB", 35f, max(10f, top - 13f), dbLabelPaint)

        for (frequency in gridFrequencies) {
            // The signal-processing range currently starts at 20 Hz. The 0 Hz grid
            // division is therefore represented at the left edge of the full-range view.
            if (frequency == 0) {
                if (minFreq <= minFrequencyBound && maxFreq >= 20000.0) {
                    canvas.drawLine(left, top, left, bottom, gridPaint)
                }
                continue
            }
            if (frequency < minFreq || frequency > maxFreq) continue
            val x = xForFrequency(frequency.toDouble())
            canvas.drawLine(x, top, x, bottom, gridPaint)
            if (frequency !in frequencyLabelTicks) continue
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

        val data = magnitudes
        if (data.isEmpty()) {
            canvas.drawText("Waiting for audio...", left + 20f, top + 50f, textPaint)
            return
        }

        // The live spectrum is white. The saved trace remains visible in cyan while live analysis continues.
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

        val frozen = frozenMagnitudes
        if (frozen != null) {
            val frozenMaxFreq = min(maxFreq, frozenSampleRate / 2.0)
            if (frozenMaxFreq > minFreq) {
                drawSpectrumTrace(
                    canvas, frozen, frozenSampleRate, frozenFftSize, minFreq, frozenMaxFreq,
                    left, right, top, bottom, frozenSpectrumPaint, frozenPath
                )
            }
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
        if (peakHz in minFreq..maxFreq && peakHz > 0.0) {
            val peakX = xForFrequency(peakHz)
            canvas.drawLine(peakX, top, peakX, bottom, peakLinePaint)
        }
    }
}
