package sk.spectrum.analyzer2

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.tensorflow.lite.Interpreter
import org.json.JSONArray
import org.json.JSONObject
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
import android.graphics.pdf.PdfDocument
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import androidx.appcompat.widget.AppCompatButton
import android.widget.EditText
import android.text.InputFilter
import android.text.InputType
import android.app.AlertDialog
import android.app.Dialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

@android.annotation.TargetApi(26)
private class AnalyzerIconButton(
    context: Context,
    icon: Icon
) : AppCompatButton(context) {
    enum class Icon { PLAY, STOP, RESET, TONE, PRESETS, SWEEP, KEYS, SOUND, AXIS, LIN, LOG, IMPORT, EXPORT, FULL_SCALE_OPEN, FULL_SCALE_CLOSE, SPECTRUM, SPECTROGRAM }

    var icon: Icon = icon
        set(value) {
            field = value
            invalidate()
            contentDescription = when (value) {
                Icon.PLAY -> "Start analysis"
                Icon.STOP -> "Stop analysis"
                Icon.RESET -> "Reset display"
                Icon.TONE -> "Tone generator"
                Icon.PRESETS -> "Frequency presets"
                Icon.SWEEP -> "Start frequency sweep"
                Icon.KEYS -> "Piano keyboard"
                Icon.SOUND -> "Sound identification"
                Icon.AXIS -> "Change frequency scale"
                Icon.LIN -> "Switch to linear frequency scale"
                Icon.LOG -> "Switch to logarithmic frequency scale"
                Icon.IMPORT -> "Import My Sound ID recordings"
                Icon.EXPORT -> "Export My Sound ID recordings"
                Icon.FULL_SCALE_OPEN -> "Expand piano keyboard"
                Icon.FULL_SCALE_CLOSE -> "Return from full-screen keyboard"
                Icon.SPECTRUM -> "Show spectrum"
                Icon.SPECTROGRAM -> "Show spectrogram"
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
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
            Icon.TONE -> "Tone generator"
            Icon.PRESETS -> "Frequency presets"
            Icon.SWEEP -> "Start frequency sweep"
            Icon.KEYS -> "Piano keyboard"
            Icon.SOUND -> "Sound identification"
            Icon.AXIS -> "Change frequency scale"
            Icon.LIN -> "Switch to linear frequency scale"
            Icon.LOG -> "Switch to logarithmic frequency scale"
            Icon.IMPORT -> "Import My Sound ID recordings"
            Icon.EXPORT -> "Export My Sound ID recordings"
            Icon.FULL_SCALE_OPEN -> "Expand piano keyboard"
            Icon.FULL_SCALE_CLOSE -> "Return from full-screen keyboard"
            Icon.SPECTRUM -> "Show spectrum"
            Icon.SPECTROGRAM -> "Show spectrogram"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // Play and stop use the same compact visual bounds and have no outline.
        val iconSize = minOf(width, height) * 0.30f
        val path = Path()
        fillPaint.color = Color.WHITE
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
            Icon.TONE, Icon.PRESETS, Icon.SWEEP, Icon.SOUND, Icon.SPECTRUM, Icon.SPECTROGRAM, Icon.AXIS, Icon.KEYS, Icon.LIN, Icon.LOG, Icon.IMPORT, Icon.EXPORT, Icon.FULL_SCALE_OPEN, Icon.FULL_SCALE_CLOSE -> {
                val s = minOf(width, height) * when (icon) {
                    Icon.KEYS -> 0.48f
                    Icon.TONE, Icon.SOUND, Icon.SPECTRUM, Icon.SPECTROGRAM -> 0.40f
                    Icon.IMPORT, Icon.EXPORT -> 0.48f
                    else -> 0.34f
                }
                val l = cx - s / 2f
                val t = cy - s / 2f
                val r = cx + s / 2f
                val b = cy + s / 2f
                when (icon) {
                    Icon.TONE -> {
                        val p = Path().apply {
                            moveTo(l, cy)
                            cubicTo(l + s * .16f, cy - s * .55f, l + s * .34f, cy + s * .55f, cx, cy)
                            cubicTo(cx + s * .16f, cy - s * .55f, cx + s * .34f, cy + s * .55f, r, cy)
                        }
                        canvas.drawPath(p, symbolPaint)
                    }
                    Icon.PRESETS -> {
                        for (row in 0..2) {
                            val y = t + s * (0.24f + row * 0.26f)
                            canvas.drawLine(l + s * 0.08f, y, r - s * 0.08f, y, symbolPaint)
                            val knobX = l + s * floatArrayOf(0.34f, 0.68f, 0.48f)[row]
                            canvas.drawCircle(knobX, y, s * 0.075f, fillPaint)
                        }
                    }
                    Icon.SWEEP -> {
                        val sweep = Path().apply {
                            moveTo(l + s * 0.08f, b - s * 0.10f)
                            cubicTo(l + s * 0.38f, b - s * 0.08f, l + s * 0.36f, t + s * 0.18f, r - s * 0.18f, t + s * 0.12f)
                        }
                        canvas.drawPath(sweep, symbolPaint)
                        canvas.drawLine(r - s * 0.38f, t + s * 0.12f, r - s * 0.16f, t + s * 0.12f, symbolPaint)
                        canvas.drawLine(r - s * 0.16f, t + s * 0.12f, r - s * 0.16f, t + s * 0.34f, symbolPaint)
                    }
                    Icon.SOUND -> {
                        val wave = Path().apply {
                            moveTo(l, cy + s * .08f)
                            lineTo(l + s * .15f, cy + s * .08f)
                            lineTo(l + s * .27f, cy - s * .28f)
                            lineTo(l + s * .39f, cy + s * .30f)
                            lineTo(l + s * .51f, cy - s * .48f)
                            lineTo(l + s * .62f, cy + s * .08f)
                            lineTo(l + s * .69f, cy - s * .08f)
                        }
                        canvas.drawPath(wave, symbolPaint)
                        val lensX = l + s * .75f
                        val lensY = cy + s * .10f
                        canvas.drawCircle(lensX, lensY, s * .20f, symbolPaint)
                        canvas.drawLine(lensX + s * .14f, lensY + s * .14f, r, b, symbolPaint)
                    }
                    Icon.KEYS -> {
                        canvas.drawRoundRect(l, t + s * .12f, r, b - s * .12f, s * .05f, s * .05f, symbolPaint)
                        for (i in 1..4) canvas.drawLine(l + s * i / 5f, t + s * .12f, l + s * i / 5f, b - s * .12f, symbolPaint)
                        for (i in 1..3) canvas.drawRoundRect(l + s * (i * 1.25f - .27f), t + s * .12f, l + s * (i * 1.25f + .04f), cy, s * .03f, s * .03f, fillPaint)
                    }
                    Icon.AXIS -> {
                        canvas.drawLine(l, b, l, t, symbolPaint)
                        canvas.drawLine(l, b, r, b, symbolPaint)
                        canvas.drawLine(l + s * .18f, b - s * .25f, r - s * .1f, t + s * .2f, symbolPaint)
                    }
                    Icon.LIN, Icon.LOG -> {
                        // Keep the axis glyph legible in the wider LIN/LOG cells.
                        // Its horizontal and vertical drawing bounds are doubled with the cell width.
                        val tickSize = width * .36f
                        val tickLeft = cx - tickSize / 2f
                        val tickRight = cx + tickSize / 2f
                        val baseline = cy + tickSize * .20f
                        val startX = tickLeft + tickSize * .06f
                        val endX = tickRight - tickSize * .06f
                        canvas.drawLine(startX, baseline, endX, baseline, symbolPaint)
                        val ticks = if (icon == Icon.LIN) {
                            floatArrayOf(.08f, .22f, .43f, .68f, .92f)
                        } else {
                            floatArrayOf(.08f, .36f, .64f, .92f)
                        }
                        ticks.forEach { fraction ->
                            val x = startX + (endX - startX) * fraction
                            canvas.drawLine(x, baseline, x, baseline - tickSize * .58f, symbolPaint)
                        }
                    }
                    Icon.IMPORT, Icon.EXPORT -> {
                        val trayY = b - s * .18f
                        val tray = Path().apply {
                            moveTo(l + s * .12f, trayY - s * .12f)
                            lineTo(l + s * .12f, trayY + s * .08f)
                            lineTo(r - s * .12f, trayY + s * .08f)
                            lineTo(r - s * .12f, trayY - s * .12f)
                        }
                        canvas.drawPath(tray, symbolPaint)
                        val arrowX = cx
                        if (icon == Icon.IMPORT) {
                            canvas.drawLine(arrowX, t + s * .12f, arrowX, cy + s * .08f, symbolPaint)
                            canvas.drawLine(arrowX, cy + s * .08f, arrowX - s * .20f, cy - s * .10f, symbolPaint)
                            canvas.drawLine(arrowX, cy + s * .08f, arrowX + s * .20f, cy - s * .10f, symbolPaint)
                        } else {
                            canvas.drawLine(arrowX, cy + s * .08f, arrowX, t + s * .12f, symbolPaint)
                            canvas.drawLine(arrowX, t + s * .12f, arrowX - s * .20f, t + s * .30f, symbolPaint)
                            canvas.drawLine(arrowX, t + s * .12f, arrowX + s * .20f, t + s * .30f, symbolPaint)
                        }
                    }
                    Icon.FULL_SCALE_OPEN, Icon.FULL_SCALE_CLOSE -> {
                        canvas.save()
                        canvas.rotate(if (icon == Icon.FULL_SCALE_OPEN) -45f else 135f, cx, cy)
                        canvas.drawLine(l + s * .18f, cy, r - s * .18f, cy, symbolPaint)
                        val p = Path().apply {
                            moveTo(r - s * .18f, cy)
                            lineTo(r - s * .42f, cy - s * .24f)
                            moveTo(r - s * .18f, cy)
                            lineTo(r - s * .42f, cy + s * .24f)
                        }
                        canvas.drawPath(p, symbolPaint)
                        canvas.restore()
                    }
                    Icon.SPECTRUM -> {
                        val p = Path().apply {
                            moveTo(l, b - s * .10f)
                            lineTo(l + s * .12f, b - s * .10f)
                            cubicTo(l + s * .25f, b - s * .10f, l + s * .28f, cy, l + s * .38f, cy)
                            cubicTo(l + s * .47f, cy, l + s * .48f, b - s * .10f, l + s * .55f, b - s * .10f)
                            cubicTo(l + s * .66f, b - s * .10f, l + s * .72f, t, l + s * .80f, t)
                            cubicTo(l + s * .89f, t, l + s * .90f, b - s * .10f, r, b - s * .10f)
                        }
                        canvas.drawPath(p, symbolPaint)
                        canvas.drawLine(l, b - s * .10f, r, b - s * .10f, symbolPaint)
                    }
                    Icon.SPECTROGRAM -> {
                        symbolPaint.color = Color.rgb(190, 126, 255)
                        canvas.drawLine(l + s * .08f, t, l + s * .08f, b - s * .08f, symbolPaint)
                        canvas.drawLine(l + s * .08f, b - s * .08f, r, b - s * .08f, symbolPaint)
                        for (row in 0..2) {
                            val baseY = b - s * (.22f + row * .22f)
                            val wave = Path().apply {
                                moveTo(l + s * .18f, baseY)
                                cubicTo(l + s * .38f, baseY - s * .02f, l + s * .35f, baseY - s * .56f, l + s * .58f, baseY - s * .45f)
                                cubicTo(l + s * .78f, baseY - s * .34f, l + s * .80f, baseY + s * .12f, r - s * .04f, baseY - s * .58f)
                            }
                            canvas.drawPath(wave, symbolPaint)
                        }
                        symbolPaint.color = Color.WHITE
                    }
                    else -> Unit
                }
            }
        }
    }
}

@android.annotation.TargetApi(26)
class MainActivity : Activity() {

    private lateinit var spectrumView: SpectrumView
    private lateinit var statusText: TextView
    private lateinit var peakText: TextView
    private lateinit var startButton: Button
    private var headerPeakFrequencyText: TextView? = null
    private var headerPeakDbText: TextView? = null
    private var spectrumHeader: android.widget.FrameLayout? = null
    private var rangePickerButton: Button? = null
    private var keyboardControlsRow: LinearLayout? = null
    private var headerTitleText: TextView? = null
    private var splitContainer: LinearLayout? = null
    private var tonePanel: View? = null
    private var keyboardPanel: View? = null
    private var keyboardToggleButton: Button? = null
    private var fullScaleKeysButton: Button? = null
    private var fullScaleKeysDialog: Dialog? = null
    private var keyboardSectionView: View? = null
    private var fullScaleKeyboardOpen = false
    private var soundIdPanel: View? = null
    private var soundModeOpen = false
    private var switchAnalyzerMode: ((String) -> Unit)? = null
    private var toneModeOpen = false
    private var keyboardModeOpen = false
    private var toneToggleButton: Button? = null
    private var axisToggleButton: Button? = null
    @Volatile private var soundIdActive = false
    private var soundIdBestSoundView: TextView? = null
    private var soundIdScoreView: TextView? = null
    private var soundIdAlternativesView: TextView? = null
    private var soundIdPersonalButton: Button? = null
    private var soundIdDurationView: TextView? = null
    private var soundIdToggleButton: Button? = null
    private var soundIdModeButton: Button? = null
    private var soundIdDialog: AlertDialog? = null
    private var soundIdLivePanel: LinearLayout? = null
    private var soundIdSummaryPanel: LinearLayout? = null
    private var soundIdSummaryChart: SoundSessionChartView? = null
    private var soundIdPdfButton: Button? = null
    private var soundIdLastSummary: SoundSessionSummary? = null
    private var personalSoundListRefresh: (() -> Unit)? = null
    private var pendingSoundIdPdf: ByteArray? = null
    private var pendingPersonalSoundExport: ByteArray? = null
    @Volatile private var personalPreviewTrack: AudioTrack? = null
    private var personalPreviewKey: String? = null
    private var personalPreviewButton: Button? = null
    private var personalPreviewPaused = false
    private var soundIdListeningStartedAt = 0L
    private val soundIdTimerHandler = Handler(Looper.getMainLooper())
    private val soundIdTimerRunnable = object : Runnable {
        override fun run() {
            if (soundIdActive) {
                val seconds = ((SystemClock.elapsedRealtime() - soundIdListeningStartedAt) / 1000L).coerceAtLeast(0L)
                soundIdDurationView?.text = "Listening · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                soundIdTimerHandler.postDelayed(this, 1000L)
            }
        }
    }
    private val soundIdEngine by lazy {
        SoundIdEngine(applicationContext) { result ->
            runOnUiThread {
                soundIdBestSoundView?.text = result.bestSound
                soundIdScoreView?.text = if (result.modelScorePercent > 0) {
                    "${if (result.isPersonalMatch) "SAVED SOUND SIMILARITY" else "MODEL SCORE"} · ${result.modelScorePercent}%"
                } else ""
                soundIdAlternativesView?.text = result.alternatives
                result.sessionSummary?.let(::showSoundIdSummary)
            }
        }
    }
    private val soundIdExportRequestCode = 72
    private val personalSoundExportRequestCode = 73
    private val personalSoundImportRequestCode = 74
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
    @Volatile private var pianoSamplesInitialized = false
    private val pianoLoadedCount = AtomicInteger(0)
    private val pianoLoadFailedCount = AtomicInteger(0)
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
        if (pianoSamplesLoading || pianoSamplesInitialized) return
        pianoSamplesInitialized = true
        pianoSamplesLoading = true
        startPianoAudioEngine()
        val names = arrayOf("c", "cs", "d", "ds", "e", "f", "fs", "g", "gs", "a", "as", "b")
        // Decode the middle octave first, where most users begin playing, then fill
        // outward. A few independent decoders make common keys available sooner.
        val firstNotes = (60..71).toList()
        val noteOrder = firstNotes + (36..96).filterNot { it in firstNotes }.sortedBy { abs(it - 65) }
        val nextNote = AtomicInteger(0)
        val missing = AtomicInteger(0)
        val workersRemaining = AtomicInteger(4)
        repeat(4) { workerIndex ->
            Thread({
                while (true) {
                    val index = nextNote.getAndIncrement()
                    if (index >= noteOrder.size) break
                    val note = noteOrder[index]
                    val resourceName = "piano_${names[note % 12]}${note / 12 - 1}"
                    val resourceId = resources.getIdentifier(resourceName, "raw", packageName)
                    if (resourceId == 0) {
                        missing.incrementAndGet()
                        Log.w(PIANO_LOAD_TAG, "Missing raw piano resource: $resourceName")
                        continue
                    }
                    try {
                        val decoded = decodePianoOgg(resourceId)
                        pianoSamples[note] = decoded
                        pianoLoadedCount.incrementAndGet()
                        runOnUiThread {
                            if (pianoHeldNotes.contains(note)) startPianoSample(note)
                        }
                    } catch (e: Exception) {
                        pianoLoadFailedCount.incrementAndGet()
                        Log.e(PIANO_LOAD_TAG, "Failed to decode $resourceName", e)
                    }
                }
                if (workersRemaining.decrementAndGet() == 0) {
                    pianoSamplesLoading = false
                    Log.i(PIANO_LOAD_TAG, "Piano ready: ${pianoLoadedCount.get()} decoded, ${pianoLoadFailedCount.get()} failed, ${missing.get()} missing")
                }
            }, "PianoSampleDecoder-$workerIndex").apply { priority = Thread.NORM_PRIORITY; start() }
        }
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
    @Volatile private var toneSweepActive = false
    private var toneSweepStartedAt = 0L
    private val toneSweepDurationMs = 8000L
    private val toneSweepHandler = Handler(Looper.getMainLooper())
    private var toneSweepUiUpdate: (() -> Unit)? = null
    private var toneSweepButton: AnalyzerIconButton? = null
    private val toneSweepRunnable = object : Runnable {
        override fun run() {
            if (!toneSweepActive) return
            val low = toneMinFrequency.coerceAtLeast(20)
            val high = toneMaxFrequency.coerceAtMost(20000).coerceAtLeast(low + 1)
            val progress = ((SystemClock.elapsedRealtime() - toneSweepStartedAt).toDouble() / toneSweepDurationMs)
                .coerceIn(0.0, 1.0)
            toneFrequency = (low * exp(progress * ln(high.toDouble() / low))).roundToInt().coerceIn(low, high)
            toneSweepUiUpdate?.invoke()
            if (progress >= 1.0) {
                toneSweepActive = false
                stopTone()
                resetTonePlayButton()
            } else {
                toneSweepHandler.postDelayed(this, 30L)
            }
        }
    }

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
        hideSystemBars()

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
            axisToggleButton?.apply {
                text = ""
                (this as? AnalyzerIconButton)?.icon = if (axisMode == "LINEAR") {
                    AnalyzerIconButton.Icon.LOG
                } else {
                    AnalyzerIconButton.Icon.LIN
                }
            }
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
                    dp(42)
                )
            }

            fun configureActionButton(button: Button, primary: Boolean = false) {
                button.textSize = 14f
                button.minHeight = dp(38)
                button.minimumHeight = dp(38)
                button.setPadding(if (button is AnalyzerIconButton) 0 else dp(10), dp(2), if (button is AnalyzerIconButton) 0 else dp(10), dp(2))
                button.maxLines = 1
                button.includeFontPadding = false
                button.backgroundTintList = null
                button.setTextColor(Color.WHITE)
                val iconButton = button is AnalyzerIconButton || button.contentDescription == "Piano keyboard"
                if (iconButton) {
                    button.background = GradientDrawable().apply {
                        setColor(if (primary) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.rgb(56, 73, 91))
                    }
                    button.stateListAnimator = null
                } else {
                    styleXButton(button, primary = primary)
                }
                val buttonWidth = when {
                    button is AnalyzerIconButton && (button.icon == AnalyzerIconButton.Icon.LIN || button.icon == AnalyzerIconButton.Icon.LOG) -> dp(84)
                    button is AnalyzerIconButton && (button.icon == AnalyzerIconButton.Icon.TONE || button.icon == AnalyzerIconButton.Icon.SOUND || button.icon == AnalyzerIconButton.Icon.SPECTRUM || button.icon == AnalyzerIconButton.Icon.SPECTROGRAM) -> dp(46)
                    iconButton -> dp(42)
                    else -> ViewGroup.LayoutParams.WRAP_CONTENT
                }
                actionRow.addView(button, LinearLayout.LayoutParams(buttonWidth, dp(38)).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }

            // Reset remains the rightmost graph control after analysis start/stop is removed.
            val resetButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.RESET)
            configureActionButton(resetButton)
            resetButton.layoutParams = (resetButton.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(39)
                setMargins(dp(3), dp(2), dp(3), dp(2))
            }
            resetButton.contentDescription = "Reset display"
            resetButton.setOnClickListener {
                leaveFullScaleKeyboard()
                spectrumView.resetView(useKeysFrequencyRange = keyboardModeOpen)
            }

            val spectrogramButton = AnalyzerIconButton(this, if (spectrumView.isSpectrogramEnabled()) AnalyzerIconButton.Icon.SPECTRUM else AnalyzerIconButton.Icon.SPECTROGRAM).apply {
                // Show the mode this button will switch TO, not the current mode.
                setOnClickListener {
                    leaveFullScaleKeyboard()
                    val enabled = spectrumView.toggleSpectrogram()
                    icon = if (enabled) AnalyzerIconButton.Icon.SPECTRUM else AnalyzerIconButton.Icon.SPECTROGRAM
                }
            }
            configureActionButton(spectrogramButton)

            val axisButton = AnalyzerIconButton(this, if (spectrumView.isLinearFrequencyAxis()) AnalyzerIconButton.Icon.LOG else AnalyzerIconButton.Icon.LIN).apply {
                // Show the axis mode this button will switch TO.
                setOnClickListener {
                    leaveFullScaleKeyboard()
                    spectrumView.toggleFrequencyAxis()
                    icon = if (spectrumView.isLinearFrequencyAxis()) AnalyzerIconButton.Icon.LOG else AnalyzerIconButton.Icon.LIN
                }
            }
            axisToggleButton = axisButton
            configureActionButton(axisButton)

            // Flexible spacer pins the graph controls to the far right.
            val controlsSpacer = View(this)
            actionRow.addView(controlsSpacer, LinearLayout.LayoutParams(0, 1, 1f))

            val keysButton = Button(this).apply {
                text = "🎹"
                textSize = 20f
                contentDescription = "Piano keyboard"
            }
            keyboardToggleButton = keysButton
            configureActionButton(keysButton)
            keysButton.layoutParams = (keysButton.layoutParams as LinearLayout.LayoutParams).apply { width = dp(42) }
            keysButton.textSize = 20f

            val fullScaleButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.FULL_SCALE_OPEN).apply {
                visibility = View.GONE
                contentDescription = "Open full-screen piano keyboard"
                setOnClickListener {
                    if (fullScaleKeyboardOpen) leaveFullScaleKeyboard() else showFullScaleKeyboard()
                }
            }
            fullScaleKeysButton = fullScaleButton
            configureActionButton(fullScaleButton)

            val soundIdButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.SOUND).apply {
                setOnClickListener { showSoundIdDialog() }
            }
            soundIdModeButton = soundIdButton
            configureActionButton(soundIdButton)

            val toneButton = AnalyzerIconButton(this, AnalyzerIconButton.Icon.TONE)
            toneToggleButton = toneButton
            configureActionButton(toneButton)

            // Arrange the controls from left to right, with graph actions anchored at the right edge.
            actionRow.removeAllViews()
            actionRow.addView(toneButton)
            val keysGroup = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(3), 0, dp(3), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(17, 25, 36))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.rgb(49, 66, 83))
                }
                layoutTransition = android.animation.LayoutTransition()
            }
            (keysButton.parent as? ViewGroup)?.removeView(keysButton)
            (fullScaleButton.parent as? ViewGroup)?.removeView(fullScaleButton)
            keysGroup.addView(keysButton)
            keysGroup.addView(fullScaleButton)
            actionRow.addView(keysGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
            })
            actionRow.addView(soundIdButton)
            actionRow.addView(controlsSpacer, LinearLayout.LayoutParams(0, 1, 1f))
            actionRow.addView(axisButton)
            actionRow.addView(spectrogramButton)
            actionRow.addView(resetButton)
            originalParams.height = dp(42)
            startParent.addView(actionRow, startIndex, originalParams)
            startButton.visibility = View.GONE
            installToneGeneratorSplit(toneButton, keysButton)
        }
        updateStartButtonAppearance()

        // Start the live graph automatically when the app opens.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAnalysis()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
        }

    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
        spectrumView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f)
        split.addView(spectrumView)
        val panel = createToneGeneratorPanel()
        tonePanel = panel
        split.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.65f))
        panel.visibility = View.GONE

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

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
        volumeParams.marginStart = 0
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
            soundModeOpen = mode == "sound"
            fullScaleKeyboardOpen = false
            spectrumView.setKeysFrequencyRangeActive(keyboardModeOpen)
            spectrumView.setDbRangeMode(when {
                toneModeOpen -> "TONE"
                keyboardModeOpen -> "KEYS"
                else -> "DEFAULT"
            })
            panel.visibility = if (toneModeOpen) View.VISIBLE else View.GONE
            soundIdPanel?.visibility = if (soundModeOpen) View.VISIBLE else View.GONE
            keyboardSection.visibility = if (keyboardModeOpen) View.VISIBLE else View.GONE
            spectrumView.visibility = View.VISIBLE
            if (keyboardModeOpen) {
                split.orientation = LinearLayout.VERTICAL
                spectrumView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                keyboardSection.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                split.orientation = LinearLayout.HORIZONTAL
                spectrumView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f)
                panel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.65f)
                keyboardSection.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            toggleButton.text = ""
            toggleButton.contentDescription = "Tone generator"
            toggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (toneModeOpen) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42)
            )
            keysButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (keyboardModeOpen) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42)
            )
            soundIdModeButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (soundModeOpen) Color.rgb(29, 105, 145) else Color.rgb(22, 32, 42)
            )
            fullScaleKeysButton?.visibility = if (keyboardModeOpen) View.VISIBLE else View.GONE
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
        switchAnalyzerMode = ::showMode
        toggleButton.setOnClickListener {
            leaveFullScaleKeyboard()
            showMode(if (toneModeOpen) "none" else "tone")
        }
        keysButton.setOnClickListener {
            if (fullScaleKeyboardOpen) {
                leaveFullScaleKeyboard()
                showMode("none")
                return@setOnClickListener
            }
            showMode(if (keyboardModeOpen) "none" else "keys")
        }
    }

    private fun createToneGeneratorPanel(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(19, 29, 43))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(49, 66, 83))
            }
            elevation = dp(2).toFloat()
        }

        val valueLabel = TextView(this).apply {
            text = "$toneFrequency Hz"
            textSize = 18f
            setTextColor(Color.rgb(255, 213, 90))
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5))
            contentDescription = "Select tone frequency"
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 23, 36))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(67, 91, 119))
            }
        }

        // Keep the compact frequency and waveform cells together, centered in the panel.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        header.addView(valueLabel, LinearLayout.LayoutParams(dp(112), dp(42)).apply {
            marginEnd = dp(8)
        })
        val waveSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Sine", "Square", "Triangle", "Sawtooth"))
            setSelection(arrayOf("Sine", "Square", "Triangle", "Sawtooth").indexOf(toneWaveform).coerceAtLeast(0))
            background = null
            setPadding(dp(8), 0, dp(24), 0)
        }
        val waveCell = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 23, 36))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(67, 91, 119))
            }
            addView(waveSpinner, android.widget.FrameLayout.LayoutParams(dp(132), dp(42)))
            addView(TextView(this@MainActivity).apply {
                text = "▾"
                textSize = 16f
                setTextColor(Color.rgb(225, 233, 243))
                gravity = Gravity.CENTER
                isClickable = false
            }, android.widget.FrameLayout.LayoutParams(dp(28), dp(42), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        header.addView(waveCell, LinearLayout.LayoutParams(dp(132), dp(42)))
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        waveSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                toneWaveform = arrayOf("Sine", "Square", "Triangle", "Sawtooth")[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Keep the sliders and actions centered directly beneath the selector row.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(1), dp(2), 0)
        }
        panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val slider = SeekBar(this).apply {
            max = 1000
            progress = ((kotlin.math.ln(toneFrequency / toneMinFrequency.toDouble()) / kotlin.math.ln(toneMaxFrequency / toneMinFrequency.toDouble())) * max).roundToInt().coerceIn(0, max)
        }
        controls.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(2), dp(5), dp(2), 0)
        })
        val limits = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        limits.addView(TextView(this).apply { text = "$toneMinFrequency Hz"; textSize = 10f; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        limits.addView(TextView(this).apply { text = "${String.format(java.util.Locale.US, "%,d", toneMaxFrequency)} Hz"; textSize = 10f; gravity = Gravity.END; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(limits, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(1))
        }
        fun utilityIcon(icon: AnalyzerIconButton.Icon, description: String) = AnalyzerIconButton(this, icon).apply {
            contentDescription = description
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 32, 45))
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(56, 73, 91))
            }
            stateListAnimator = null
        }
        val presetsButton = utilityIcon(AnalyzerIconButton.Icon.PRESETS, "Frequency presets")
        presetsButton.setOnClickListener {
            val low = toneMinFrequency.coerceAtLeast(20)
            val high = toneMaxFrequency.coerceAtMost(20000)
            val presets = listOf(20, 50, 60, 100, 120, 250, 440, 500, 1000, 2000, 5000, 10000, 15000, 20000)
                .filter { it in low..high }
            if (presets.isEmpty()) {
                Toast.makeText(this, "No presets in the selected tone range.", Toast.LENGTH_SHORT).show()
            } else {
                val presetDialog = Dialog(this@MainActivity)
                val presetContent = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(24, 31, 42))
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(1), Color.rgb(49, 66, 83))
                    }
                }
                presetContent.addView(TextView(this@MainActivity).apply {
                    text = "FREQUENCY PRESETS"
                    textSize = 13f
                    setTextColor(Color.rgb(225, 233, 243))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(8))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val presetGrid = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                for ((rowIndex, rowValues) in presets.chunked(3).withIndex()) {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    rowValues.forEachIndexed { columnIndex, frequency ->
                        val label = if (frequency >= 1000) "${frequency / 1000} kHz" else "$frequency Hz"
                        val presetButton = Button(this@MainActivity).apply {
                            text = label
                            textSize = 13f
                            isAllCaps = false
                            minHeight = dp(42)
                            minimumHeight = dp(42)
                            setOnClickListener {
                                cancelToneSweep()
                                toneFrequency = frequency
                                toneSweepUiUpdate?.invoke()
                                presetDialog.dismiss()
                            }
                        }
                        styleNumericDialogButton(presetButton)
                        presetButton.setTextColor(Color.rgb(255, 213, 90))
                        row.addView(presetButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            if (columnIndex > 0) marginStart = dp(3)
                            if (columnIndex < 2) marginEnd = dp(3)
                        })
                    }
                    repeat(3 - rowValues.size) { index ->
                        val columnIndex = rowValues.size + index
                        row.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            if (columnIndex > 0) marginStart = dp(3)
                            if (columnIndex < 2) marginEnd = dp(3)
                        })
                    }
                    presetGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                        if (rowIndex > 0) topMargin = dp(5)
                    })
                }
                presetContent.addView(presetGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val closeButton = Button(this@MainActivity).apply {
                    text = "CANCEL"
                    textSize = 11f
                    setOnClickListener { presetDialog.dismiss() }
                }
                styleNumericDialogButton(closeButton)
                closeButton.setTextColor(Color.rgb(225, 233, 243))
                presetContent.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                    topMargin = dp(8)
                })
                presetDialog.setContentView(presetContent)
                presetDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                centerDialogOnScreen(presetDialog, dp(260))
                presetDialog.show()
            }
        }
        val sweepButton = utilityIcon(AnalyzerIconButton.Icon.SWEEP, "Play frequency sweep")
        toneSweepButton = sweepButton
        sweepButton.setOnClickListener {
            if (toneSweepActive) {
                cancelToneSweep()
                stopTone()
                resetTonePlayButton()
            } else {
                toneFrequency = toneMinFrequency.coerceAtLeast(20)
                toneSweepUiUpdate?.invoke()
                if (!tonePlaying) startTone()
                if (tonePlaying) {
                    toneSweepStartedAt = SystemClock.elapsedRealtime()
                    toneSweepActive = true
                    sweepButton.contentDescription = "Stop frequency sweep"
                    sweepButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(29, 105, 145))
                    tonePlayButton?.apply {
                        text = "STOP TONE"
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(150, 40, 45))
                    }
                    toneSweepHandler.post(toneSweepRunnable)
                }
            }
        }
        utilityRow.addView(presetsButton, LinearLayout.LayoutParams(dp(44), dp(38)).apply { marginEnd = dp(8) })
        utilityRow.addView(sweepButton, LinearLayout.LayoutParams(dp(44), dp(38)))
        controls.addView(utilityRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
            isAllCaps = true
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.rgb(29, 125, 65))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.rgb(67, 91, 86))
            }
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
        toneSweepUiUpdate = ::updateToneSliderFromFrequency
        valueLabel.setOnClickListener {
            // Use the same custom numeric keypad as the Spectrum Analyzer.
            // Confirming a value only updates the frequency; it never starts playback.
            val dialog = android.app.Dialog(this)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(24, 31, 42))
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.rgb(49, 66, 83))
                }
            }
            content.addView(TextView(this).apply {
                text = "Frequency (Hz)"
                textSize = 14f
                setTextColor(Color.rgb(225, 233, 243))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val display = EditText(this).apply {
                setText(toneFrequency.toString())
                textSize = 22f
                setTextColor(Color.rgb(255, 213, 90))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(8))
                minWidth = dp(160)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
                showSoftInputOnFocus = false
                isFocusableInTouchMode = true
                isCursorVisible = true
                setSelection(text.length)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(15, 23, 36))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.rgb(67, 91, 119))
                }
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
                    isFocusable = false
                    setPadding(0, 0, 0, 0)
                    setTextColor(Color.rgb(255, 213, 90))
                    setOnClickListener { action() }
                }
                styleNumericDialogButton(button)
                keypad.addView(button, android.widget.GridLayout.LayoutParams().apply {
                    width = dp(52)
                    height = dp(42)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            fun appendDigit(digit: String) {
                val editable = display.text
                val start = display.selectionStart.coerceAtLeast(0)
                val end = display.selectionEnd.coerceAtLeast(start)
                if (editable.length - (end - start) < 5) {
                    editable.replace(start, end, digit)
                    display.setSelection(start + digit.length)
                }
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
            key("⌫") {
                val editable = display.text
                val start = display.selectionStart.coerceAtLeast(0)
                val end = display.selectionEnd.coerceAtLeast(start)
                when {
                    end > start -> editable.delete(start, end)
                    start > 0 -> editable.delete(start - 1, start)
                }
                val cursor = if (end > start) start else (start - 1).coerceAtLeast(0)
                display.setSelection(cursor.coerceAtMost(editable.length))
            }
            key("0") { appendDigit("0") }
            key("CLR") { display.setText(""); display.setSelection(0) }
            content.addView(keypad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })

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
                        display.error = "Enter 20–20000 Hz"
                    } else {
                        toneFrequency = frequency
                        updateToneSliderFromFrequency()
                        dialog.dismiss()
                    }
                }
            }
            for (button in listOf(cancelButton, doneButton)) {
                styleNumericDialogButton(button)
                button.setTextColor(Color.rgb(255, 213, 90))
                actions.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(3), dp(5), dp(3), 0)
                })
            }
            content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setContentView(content)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            centerDialogOnScreen(dialog, dp(220))
            dialog.show()
            display.requestFocus()
            display.setSelection(display.text.length)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val low = toneMinFrequency.coerceAtLeast(20)
                    val high = toneMaxFrequency.coerceAtMost(20000).coerceAtLeast(low + 1)
                    toneFrequency = (low * kotlin.math.exp(progress.toDouble() / slider.max * kotlin.math.ln(high.toDouble() / low))).roundToInt().coerceIn(low, high)
                }
                valueLabel.text = "$toneFrequency Hz"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        return panel
    }

    private fun centerDialogOnScreen(dialog: Dialog, width: Int, horizontalOffsetDp: Int = -19) {
        val window = dialog.window ?: return
        val horizontalOffset = (horizontalOffsetDp * resources.displayMetrics.density).roundToInt()
        fun applyCenter() {
            window.setGravity(Gravity.CENTER)
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        cancelToneSweep()
        if (!tonePlaying) {
            resetTonePlayButton()
            return
        }
        toneStopRequested = true
        // The audio thread performs a short fade-out before releasing AudioTrack.
    }

    private fun cancelToneSweep() {
        toneSweepActive = false
        toneSweepHandler.removeCallbacks(toneSweepRunnable)
        toneSweepButton?.contentDescription = "Play frequency sweep"
        toneSweepButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(22, 32, 45))
    }

    private fun styleXButton(button: Button, primary: Boolean) {
        val density = resources.displayMetrics.density
        val background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 13f * density
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

    private fun styleNumericDialogButton(button: Button) {
        val density = resources.displayMetrics.density
        button.background = GradientDrawable().apply {
            setColor(Color.rgb(49, 63, 81))
            cornerRadius = 10f * density
            setStroke((density).roundToInt().coerceAtLeast(1), Color.rgb(72, 91, 111))
        }
        button.backgroundTintList = null
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
        spectrumHeader = header
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
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            showSoftInputOnFocus = false
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(17, 25, 36))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(90, 105, 124))
            }
        }

        val minFrequencyInput = frequencyInput("20", "Minimum frequency in Hz")
        val maxFrequencyInput = frequencyInput("20000", "Maximum frequency in Hz")
        minFrequencyInput.hint = "Min Hz"
        maxFrequencyInput.hint = "Max Hz"

        var selectedRangeInput: EditText = minFrequencyInput
        fun selectRangeInput(input: EditText) {
            selectedRangeInput = input
            listOf(minFrequencyInput, maxFrequencyInput).forEach { field ->
                field.background = GradientDrawable().apply {
                    setColor(Color.rgb(17, 25, 36))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (field === input) 2 else 1), if (field === input) Color.rgb(255, 213, 90) else Color.rgb(90, 105, 124))
                }
            }
            input.requestFocus()
        }
        fun attachRangeFieldTouch(input: EditText) {
            input.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    selectRangeInput(input)
                    val cursor = input.getOffsetForPosition(event.x, event.y)
                        .coerceIn(0, input.text.length)
                    input.setSelection(cursor)
                }
                // Let EditText finish its normal tap handling after we activate it.
                false
            }
        }
        attachRangeFieldTouch(minFrequencyInput)
        attachRangeFieldTouch(maxFrequencyInput)

        val rangeDialog = android.app.Dialog(this)
        val rangeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 31, 42))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(49, 66, 83))
            }
        }

        val rangeFields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addRangeField(input: EditText) {
            val field = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            field.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            rangeFields.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addRangeField(minFrequencyInput)
        rangeFields.addView(TextView(this).apply {
            text = "–"
            textSize = 18f
            setTextColor(Color.rgb(225, 233, 243))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), dp(60)))
        addRangeField(maxFrequencyInput)
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
                isFocusable = false
                setPadding(0, 0, 0, 0)
                setTextColor(Color.rgb(255, 213, 90))
                setOnClickListener { action() }
            }
            styleNumericDialogButton(keyButton)
            rangeKeypad.addView(keyButton, android.widget.GridLayout.LayoutParams().apply {
                width = dp(52)
                height = dp(42)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        fun appendRangeDigit(digit: String) {
            val editable = selectedRangeInput.text
            val start = selectedRangeInput.selectionStart.coerceAtLeast(0)
            val end = selectedRangeInput.selectionEnd.coerceAtLeast(start)
            if (editable.length - (end - start) < 5) {
                editable.replace(start, end, digit)
                selectedRangeInput.setSelection(start + digit.length)
            }
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
            val editable = selectedRangeInput.text
            val start = selectedRangeInput.selectionStart.coerceAtLeast(0)
            val end = selectedRangeInput.selectionEnd.coerceAtLeast(start)
            when {
                end > start -> editable.delete(start, end)
                start > 0 -> editable.delete(start - 1, start)
            }
            val newCursor = if (end > start) start else (start - 1).coerceAtLeast(0)
            selectedRangeInput.setSelection(newCursor.coerceAtMost(editable.length))
        }
        addRangeKey("0") { appendRangeDigit("0") }
        addRangeKey("CLR") { selectedRangeInput.setText(""); selectedRangeInput.setSelection(0) }
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
            background = GradientDrawable().apply {
                setColor(Color.rgb(49, 63, 81))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(72, 91, 111))
            }
            contentDescription = "Frequency range information"
            setOnClickListener {
                val infoDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Frequency range")
                    .setMessage("Enter the minimum and maximum frequencies in Hz, from 0 to 20000. The minimum must be lower than the maximum. Press DONE to apply the range. The sampling rate remains 48 kHz; analysis is limited to 20 kHz.")
                    .setPositiveButton("OK", null)
                    .create()
                infoDialog.setOnShowListener {
                    infoDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let(::styleNumericDialogButton)
                }
                infoDialog.show()
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
            styleNumericDialogButton(button)
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
                centerDialogOnScreen(rangeDialog, dp(300))
                rangeDialog.show()
            }
        }
        rangePickerButton = rangeButton
        styleXButton(rangeButton, primary = false)
        rangeButton.isAllCaps = false
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

    private fun showSoundIdDialog() {
        leaveFullScaleKeyboard()
        if (soundIdPanel != null) {
            switchAnalyzerMode?.invoke(if (soundModeOpen) "none" else "sound")
            return
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.roundToInt()).toFloat()
        }

        val background = Color.rgb(12, 18, 27)
        val cardColor = Color.rgb(22, 31, 43)
        val accent = Color.rgb(75, 196, 255)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(background)
        }

        val livePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            this.background = roundedBackground(cardColor, 20f)
        }
        val duration = label("", 12f, Color.LTGRAY)
        val bestSound = label("Tap Listen to identify a sound", 20f, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(12), 0, dp(6))
        }
        val score = label("", 12f, accent, true).apply { letterSpacing = 0.06f }
        val alternatives = label("", 12f, Color.LTGRAY).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        val personalSounds = Button(this).apply {
            text = "＋ 〰"
            isAllCaps = false
            textSize = 19f
            setTextColor(Color.WHITE)
            minHeight = dp(42)
            minWidth = dp(58)
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "My Sound ID: add or manage personal sounds"
            this.background = roundedBackground(Color.rgb(42, 57, 75), 15f)
            setOnClickListener { showMySoundIdsDialog() }
        }
        val toggle = Button(this).apply {
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 15f
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener {
                val start = !soundIdActive
                soundIdActive = start
                soundIdToggleButton?.text = if (start) "Stop listening" else "Listen again"
                soundIdToggleButton?.background = roundedBackground(
                    if (start) Color.rgb(164, 69, 80) else Color.rgb(27, 121, 157), 14f
                )
                if (start) {
                    soundIdLastSummary = null
                    soundIdLivePanel?.visibility = View.VISIBLE
                    soundIdSummaryPanel?.visibility = View.GONE
                    soundIdPdfButton?.visibility = View.GONE
                    bestSound.text = "Listening for sounds…"
                    score.text = ""
                    alternatives.text = ""
                    duration.text = "Listening · 0:00"
                    soundIdListeningStartedAt = SystemClock.elapsedRealtime()
                    soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
                    soundIdTimerHandler.post(soundIdTimerRunnable)
                    soundIdEngine.setListening(true)
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        if (!running) startAnalysis()
                    } else {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
                    }
                } else {
                    soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
                    duration.text = ""
                    soundIdEngine.setListening(false)
                }
            }
        }
        soundIdDurationView = duration
        soundIdBestSoundView = bestSound
        soundIdScoreView = score
        soundIdAlternativesView = alternatives
        soundIdToggleButton = toggle
        livePanel.addView(duration, LinearLayout.LayoutParams(-1, -2))
        livePanel.addView(bestSound)
        livePanel.addView(score)
        livePanel.addView(alternatives)
        livePanel.addView(personalSounds, LinearLayout.LayoutParams(-2, dp(42)).apply {
            topMargin = dp(9)
            gravity = Gravity.END
        })

        val summaryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            this.background = roundedBackground(cardColor, 20f)
            visibility = View.GONE
        }
        summaryPanel.addView(label("SESSION SUMMARY", 12f, accent, true).apply { letterSpacing = 0.08f })
        summaryPanel.addView(label("Weighted share of analyzed time", 12f, Color.LTGRAY).apply {
            setPadding(0, dp(3), 0, dp(5))
        })
        val chart = SoundSessionChartView(this)
        soundIdSummaryChart = chart
        summaryPanel.addView(chart, LinearLayout.LayoutParams(-1, dp(132)))
        val summaryPersonalSounds = Button(this).apply {
            text = "＋ 〰"
            isAllCaps = false
            textSize = 19f
            setTextColor(Color.WHITE)
            minHeight = dp(42)
            minWidth = dp(58)
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "My Sound ID: add or manage personal sounds"
            this.background = roundedBackground(Color.rgb(42, 57, 75), 15f)
            setOnClickListener { showMySoundIdsDialog() }
        }
        summaryPanel.addView(summaryPersonalSounds, LinearLayout.LayoutParams(-2, dp(42)).apply {
            topMargin = dp(7)
            gravity = Gravity.END
        })
        val export = Button(this).apply {
            text = "↓  PDF"
            isAllCaps = false
            textSize = 14f
            setPadding(dp(18), 0, dp(18), 0)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            contentDescription = "Save sound session as PDF"
            this.background = roundedBackground(Color.rgb(40, 83, 111), 18f)
            setOnClickListener { exportSoundIdSummary() }
        }
        val panels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(livePanel, LinearLayout.LayoutParams(-1, -2))
            addView(summaryPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
        val panelScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(panels)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(toggle, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(export, LinearLayout.LayoutParams(-2, dp(46)).apply { marginStart = dp(7) })
        }
        root.addView(actionRow, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(panelScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(7) })

        soundIdLivePanel = livePanel
        soundIdSummaryPanel = summaryPanel
        soundIdPdfButton = export
        updateSoundIdToggleStyle()
        if (soundIdActive) {
            livePanel.visibility = View.VISIBLE
            summaryPanel.visibility = View.GONE
            toggle.text = "Stop listening"
        } else if (soundIdLastSummary != null) {
            showSoundIdSummary(soundIdLastSummary!!)
        } else {
            toggle.text = "Start listening"
        }

        soundIdPanel = root
        val split = splitContainer ?: return
        split.addView(root, 2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f))
        root.visibility = View.GONE
        switchAnalyzerMode?.invoke("sound")
    }

    private fun showDialogCenteredOnScreen(
        dialog: Dialog,
        dragHandle: View? = null,
        widthFraction: Float? = null
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
                attributes.y = ((screenHeight - dialogHeight) / 2)
                    .coerceIn(margin, (screenHeight - dialogHeight - margin).coerceAtLeast(margin))
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

    private fun attachDialogDragHandle(
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

    private fun showPersonalSoundExportInfo() {
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
            text = "Export creates a ZIP containing the saved WAV recordings, their names, and the matching sound profiles. You can listen to each WAV and check that its label is correct before sharing the archive."
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
        val dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        done.setOnClickListener { dialog.dismiss() }
        showDialogCenteredOnScreen(dialog)
    }

    private fun showDeletePersonalSoundDialog(profile: PersonalSoundProfile, onDeleted: () -> Unit) {
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
            text = "Delete recording?"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Remove “${profile.name}” and its saved WAV sample from this phone?"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(14))
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val dialog = Dialog(this)
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
                if (personalPreviewKey == "saved:${profile.id}") stopPersonalSoundPreview()
                soundIdEngine.removePersonalSound(profile.id)
                onDeleted()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        content.addView(actions)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        showDialogCenteredOnScreen(dialog)
    }

    private fun showMySoundIdsDialog() {
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
            text = "Saved samples"
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
            if (profiles.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "No personal sounds yet. Record a short sample, then give it a name."
                    textSize = 14f
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(10))
                })
            } else profiles.asReversed().forEach { profile ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(5), dp(7), dp(5))
                    background = panel(Color.rgb(30, 41, 55), 13)
                }
                row.addView(TextView(this).apply {
                    text = "〰   ${profile.name}"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -1, 1f))
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
                }, LinearLayout.LayoutParams(dp(42), dp(40)))
                row.addView(Button(this).apply {
                    text = "×"
                    isAllCaps = false
                    textSize = 18f
                    setTextColor(Color.LTGRAY)
                    background = panel(Color.TRANSPARENT, 12)
                    contentDescription = "Delete ${profile.name}"
                    setOnClickListener {
                        showDeletePersonalSoundDialog(profile) { refreshList() }
                    }
                }, LinearLayout.LayoutParams(dp(42), dp(40)))
                list.addView(row, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) })
            }
            val visibleRows = if (profiles.isEmpty()) 1 else minOf(profiles.size, 3)
            listScroll.layoutParams = LinearLayout.LayoutParams(-1, dp(visibleRows * 57))
            listScroll.isVerticalScrollBarEnabled = profiles.size > 3
        }
        content.addView(listScroll)
        content.addView(Button(this).apply {
            text = "＋  Add a sound"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = panel(Color.rgb(39, 113, 147), 16)
            setOnClickListener { showPersonalSoundRecorderDialog { refreshList() } }
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
        managerDialog = Dialog(this)
        managerDialog.setContentView(content)
        managerDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        managerDialog.setOnDismissListener {
            if (personalSoundListRefresh === refreshCallback) personalSoundListRefresh = null
        }
        showDialogCenteredOnScreen(managerDialog, title)
    }

    private fun exportPersonalSoundProfiles() {
        val profiles = soundIdEngine.getPersonalSounds()
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Record and save a personal sound first.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val manifestProfiles = JSONArray()
            val output = ByteArrayOutputStream()
            val zip = java.util.zip.ZipOutputStream(output)
            profiles.forEach { profile ->
                val audio = soundIdEngine.getPersonalSoundAudio(profile.id) ?: return@forEach
                val audioPath = "samples/${profile.id}.wav"
                zip.putNextEntry(java.util.zip.ZipEntry(audioPath))
                zip.write(encodePersonalSoundWav(audio))
                zip.closeEntry()
                val vector = JSONArray()
                profile.embedding.forEach { value -> vector.put(value.toDouble()) }
                manifestProfiles.put(JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("audio", audioPath)
                    .put("sample_rate", 48_000)
                    .put("embedding", vector))
            }
            if (manifestProfiles.length() == 0) {
                zip.close()
                Toast.makeText(this, "No recordings are available to export.", Toast.LENGTH_LONG).show()
                return
            }
            val manifest = JSONObject()
                .put("format", "spectrum-analyzer-personal-sound-samples")
                .put("format_version", 1)
                .put("profile_model", "yamnet-audioset-plus-log-spectrum-v1")
                .put("sample_count", manifestProfiles.length())
                .put("samples", manifestProfiles)
                .toString(2)
                .toByteArray(Charsets.UTF_8)
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            zip.close()
            pendingPersonalSoundExport = output.toByteArray()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "my-sound-id-samples.zip")
            }
            startActivityForResult(intent, personalSoundExportRequestCode)
        } catch (_: Exception) {
            pendingPersonalSoundExport = null
            Toast.makeText(this, "Could not prepare the sample archive.", Toast.LENGTH_LONG).show()
        }
    }

    private fun importPersonalSoundProfiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        try {
            startActivityForResult(intent, personalSoundImportRequestCode)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open a sample archive.", Toast.LENGTH_LONG).show()
        }
    }

    private fun encodePersonalSoundWav(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(48_000)
            putInt(96_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
            samples.forEach { putShort(it) }
        }.array()
    }

    private fun importPersonalSoundArchive(bytes: ByteArray): Int {
        require(bytes.size <= 32 * 1024 * 1024) { "Archive is too large." }
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var totalBytes = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val content = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count <= 0) break
                        totalBytes += count
                        require(totalBytes <= 32 * 1024 * 1024) { "Archive contents are too large." }
                        content.write(buffer, 0, count)
                    }
                    entries[entry.name] = content.toByteArray()
                }
                zip.closeEntry()
            }
        }
        val manifestBytes = entries["manifest.json"] ?: error("Archive is missing manifest.json")
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.optString("format") == "spectrum-analyzer-personal-sound-samples") {
            "This is not a My Sound ID sample archive."
        }
        require(manifest.optInt("format_version") == 1) { "This sample archive version is not supported." }
        val samples = manifest.optJSONArray("samples") ?: error("Archive has no sample list.")
        var imported = 0
        for (index in 0 until samples.length()) {
            val item = samples.optJSONObject(index) ?: continue
            val name = item.optString("name").trim().take(80)
            val vector = item.optJSONArray("embedding") ?: continue
            val audioPath = item.optString("audio")
            val wav = entries[audioPath] ?: continue
            if (name.isBlank() || vector.length() != PERSONAL_SOUND_FEATURE_COUNT) continue
            val audio = decodePersonalSoundWav(wav) ?: continue
            val embedding = FloatArray(PERSONAL_SOUND_FEATURE_COUNT) { vector.optDouble(it).toFloat() }
            if (embedding.any { !it.isFinite() }) continue
            soundIdEngine.savePersonalSound(name, embedding, audio)
            imported++
        }
        return imported
    }

    private fun decodePersonalSoundWav(wav: ByteArray): ShortArray? {
        if (wav.size < 44) return null
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE" ||
            String(wav, 36, 4, Charsets.US_ASCII) != "data") return null
        if (buffer.getShort(20).toInt() != 1 || buffer.getShort(22).toInt() != 1 ||
            buffer.getInt(24) != 48_000 || buffer.getShort(34).toInt() != 16) return null
        val dataSize = buffer.getInt(40)
        if (dataSize <= 0 || dataSize % 2 != 0 || dataSize > wav.size - 44) return null
        buffer.position(44)
        return ShortArray(dataSize / 2) { buffer.short }
    }

    private fun showPersonalSoundRecorderDialog(onSaved: () -> Unit) {
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
        header.addView(Button(this).apply {
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
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(header)
        val instruction = TextView(this).apply {
            text = "After the countdown, play the sound near your phone for 10 seconds. YAMNet analyzes overlapping windows."
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
        content.addView(instruction)
        content.addView(countdown, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        content.addView(record, LinearLayout.LayoutParams(-1, dp(50)))
        dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        val handler = Handler(Looper.getMainLooper())
        var cancelled = false
        var countdownRemaining = 3
        var recordingRemaining = 10
        var recordingStarted = false
        lateinit var captureTimeout: Runnable
        captureTimeout = Runnable {
            if (dialog.isShowing && !cancelled) {
                soundIdEngine.cancelPersonalSoundCapture()
                countdown.text = "No usable sample captured. Try again."
                record.isEnabled = true
                record.text = "●  Record again"
            }
        }
        lateinit var ticker: Runnable
        ticker = object : Runnable {
            override fun run() {
                if (cancelled || !dialog.isShowing) return
                when {
                    countdownRemaining > 0 -> {
                        countdown.text = "Recording starts in $countdownRemaining…"
                        countdownRemaining--
                        handler.postDelayed(this, 1000L)
                    }
                    !recordingStarted -> {
                        recordingStarted = true
                        countdown.text = "Recording · 10 seconds remaining"
                        if (!soundIdActive) soundIdToggleButton?.performClick()
                        soundIdEngine.beginPersonalSoundCapture()
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
            record.isEnabled = false
            countdownRemaining = 3
            recordingRemaining = 10
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
                    dialog.dismiss()
                    showPersonalSoundNamingDialog(embedding, audio, onSaved)
                }
            }
        }
        dialog.setOnDismissListener {
            cancelled = true
            handler.removeCallbacks(ticker)
            handler.removeCallbacks(captureTimeout)
            soundIdEngine.cancelPersonalSoundCapture()
            soundIdEngine.setPersonalSoundCapturedListener(null)
        }
        showDialogCenteredOnScreen(dialog)
    }

    private fun showPersonalSoundNamingDialog(embedding: FloatArray, audio: ShortArray, onSaved: () -> Unit) {
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
        header.addView(Button(this).apply {
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
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(header)
        content.addView(TextView(this).apply {
            text = "Listen, name it, and save."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(7))
        })
        val input = EditText(this).apply {
            hint = "e.g. Elephant"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setShowSoftInputOnFocus(false)
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

        val keyboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(5), dp(3), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 29, 40))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.rgb(42, 57, 73))
            }
        }
        var shiftOn = true
        var symbolMode = false
        lateinit var renderKeyboard: () -> Unit
        fun insertAtCursor(value: String) {
            val text = input.text
            val start = input.selectionStart.coerceIn(0, text.length)
            val end = input.selectionEnd.coerceIn(0, text.length)
            val from = min(start, end)
            val to = max(start, end)
            text.replace(from, to, value)
            input.setSelection((from + value.length).coerceIn(0, input.text.length))
        }
        fun handleKeyboardKey(key: String) {
            when (key) {
                "⇧" -> {
                    shiftOn = !shiftOn
                    renderKeyboard()
                }
                "⌫" -> {
                    val start = input.selectionStart.coerceIn(0, input.text.length)
                    val end = input.selectionEnd.coerceIn(0, input.text.length)
                    if (start != end) insertAtCursor("")
                    else if (start > 0) {
                        input.text.delete(start - 1, start)
                        input.setSelection(start - 1)
                    }
                }
                "?123" -> {
                    symbolMode = true
                    renderKeyboard()
                }
                "ABC" -> {
                    symbolMode = false
                    renderKeyboard()
                }
                "Space" -> insertAtCursor(" ")
                else -> {
                    val character = if (key.length == 1 && key[0].isLetter()) {
                        if (shiftOn) key.uppercase() else key.lowercase()
                    } else key
                    insertAtCursor(character)
                    if (shiftOn && key.length == 1 && key[0].isLetter()) {
                        shiftOn = false
                        renderKeyboard()
                    }
                }
            }
        }
        fun addKeyboardRow(keys: List<Pair<String, Float>>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            keys.forEach { (key, weight) ->
                val special = key in setOf("⇧", "⌫", "?123", "ABC")
                val button = Button(this).apply {
                    text = when {
                        key.length == 1 && key[0].isLetter() ->
                            if (shiftOn) key.uppercase() else key.lowercase()
                        key == "Space" -> "space"
                        else -> key
                    }
                    isAllCaps = false
                    textSize = if (key == "Space") 11f else 14f
                    setTextColor(Color.WHITE)
                    minWidth = 0
                    minHeight = 0
                    setPadding(0, 0, 0, 0)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    stateListAnimator = null
                    background = GradientDrawable().apply {
                        setColor(if (special) Color.rgb(52, 75, 96) else Color.rgb(35, 47, 62))
                        cornerRadius = dp(9).toFloat()
                        setStroke(dp(1), Color.rgb(58, 73, 91))
                    }
                    setOnClickListener { handleKeyboardKey(key) }
                }
                row.addView(button, LinearLayout.LayoutParams(0, dp(38), weight).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            keyboard.addView(row, LinearLayout.LayoutParams(-1, dp(42)))
        }
        renderKeyboard = {
            keyboard.removeAllViews()
            if (symbolMode) {
                addKeyboardRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { it to 1f })
                addKeyboardRow(listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/").map { it to 1f })
                addKeyboardRow(listOf("á", "č", "ď", "é", "í", "ľ", "ň", "ó", "š", "⌫").map { it to 1f })
                addKeyboardRow(listOf("ABC" to 1.4f, "ť" to 1f, "ú" to 1f, "ý" to 1f, "ž" to 1f, "Space" to 2.7f, "," to 0.8f, "." to 0.8f))
            } else {
                addKeyboardRow("qwertyuiop".map { it.toString() to 1f })
                addKeyboardRow("asdfghjkl".map { it.toString() to 1f })
                addKeyboardRow(listOf("⇧" to 1.35f) + "zxcvbnm".map { it.toString() to 1f } + listOf("⌫" to 1.35f))
                addKeyboardRow(listOf("?123" to 1.5f, "," to 1f, "Space" to 4.5f, "." to 1f))
            }
        }
        renderKeyboard()
        content.addView(keyboard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        fun saveSample() {
            val soundName = input.text.toString().trim()
            if (soundName.isEmpty()) {
                input.error = "Enter a name"
                input.requestFocus()
            } else {
                soundIdEngine.savePersonalSound(soundName, embedding, audio)
                Toast.makeText(this, "Saved $soundName", Toast.LENGTH_SHORT).show()
                onSaved()
                dialog.dismiss()
            }
        }
        save.setOnClickListener { saveSample() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveSample()
                true
            } else false
        }
        dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnShowListener { input.requestFocus() }
        showDialogCenteredOnScreen(dialog, widthFraction = 0.98f)
    }

    private fun togglePersonalSoundPreview(id: String, name: String, button: Button) {
        val samples = soundIdEngine.getPersonalSoundAudio(id)
        if (samples == null) {
            Toast.makeText(this, "Recording for $name is unavailable.", Toast.LENGTH_SHORT).show()
            return
        }
        togglePersonalSoundPreview("saved:$id", samples, name, button)
    }

    private fun togglePersonalSoundPreview(samples: ShortArray, name: String, button: Button) {
        togglePersonalSoundPreview("pending:${System.identityHashCode(samples)}", samples, name, button)
    }

    private fun togglePersonalSoundPreview(key: String, samples: ShortArray, name: String, button: Button) {
        if (samples.isEmpty()) {
            Toast.makeText(this, "No audio sample is available.", Toast.LENGTH_SHORT).show()
            return
        }
        if (personalPreviewKey == key && personalPreviewTrack != null) {
            val track = personalPreviewTrack ?: return
            if (personalPreviewPaused) {
                track.play()
                personalPreviewPaused = false
                button.text = "Ⅱ"
                button.contentDescription = "Pause recording: $name"
            } else {
                track.pause()
                personalPreviewPaused = true
                button.text = "▶"
                button.contentDescription = "Resume recording: $name"
            }
            return
        }
        try {
            stopPersonalSoundPreview()
            val bufferSize = AudioTrack.getMinBufferSize(
                48_000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                48_000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                Toast.makeText(this, "Could not play this recording.", Toast.LENGTH_SHORT).show()
                return
            }
            personalPreviewTrack = track
            personalPreviewKey = key
            personalPreviewButton = button
            personalPreviewPaused = false
            button.text = "Ⅱ"
            button.contentDescription = "Pause recording: $name"
            track.play()
            Thread {
                try {
                    var offset = 0
                    while (offset < samples.size && personalPreviewTrack === track) {
                        val written = track.write(
                            samples,
                            offset,
                            minOf(4096, samples.size - offset),
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        offset += written
                    }
                } catch (_: Exception) {
                } finally {
                    runOnUiThread {
                        if (personalPreviewTrack === track) {
                            try { track.stop() } catch (_: Exception) {}
                            track.release()
                            personalPreviewTrack = null
                            personalPreviewKey = null
                            personalPreviewButton?.text = "▶"
                            personalPreviewButton?.contentDescription = "Play recording: $name"
                            personalPreviewButton = null
                            personalPreviewPaused = false
                        }
                    }
                }
            }.start()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not play $name.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPersonalSoundPreview() {
        val track = personalPreviewTrack
        personalPreviewTrack = null
        personalPreviewKey = null
        personalPreviewPaused = false
        personalPreviewButton?.text = "▶"
        personalPreviewButton?.contentDescription = "Play recording"
        personalPreviewButton = null
        if (track != null) {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun updateSoundIdToggleStyle() {
        val density = resources.displayMetrics.density
        soundIdToggleButton?.background = GradientDrawable().apply {
            setColor(if (soundIdActive) Color.rgb(164, 69, 80) else Color.rgb(27, 121, 157))
            cornerRadius = 14f * density
        }
        soundIdToggleButton?.text = if (soundIdActive) "Stop listening" else "Start listening"
    }

    private fun showSoundIdSummary(summary: SoundSessionSummary) {
        soundIdLastSummary = summary
        soundIdActive = false
        soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
        soundIdLivePanel?.visibility = View.GONE
        soundIdSummaryPanel?.visibility = View.VISIBLE
        soundIdPdfButton?.visibility = View.VISIBLE
        soundIdDurationView?.text = "${formatSoundDuration(summary.totalSeconds)} analyzed"
        soundIdBestSoundView?.text = ""
        soundIdScoreView?.text = ""
        soundIdAlternativesView?.text = ""
        soundIdSummaryChart?.setSummary(summary)
        updateSoundIdToggleStyle()
    }

    private fun formatSoundDuration(seconds: Double): String {
        val wholeSeconds = seconds.roundToInt().coerceAtLeast(0)
        return "${wholeSeconds / 60}:${(wholeSeconds % 60).toString().padStart(2, '0')}"
    }

    private fun exportSoundIdSummary() {
        val summary = soundIdLastSummary ?: return
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(22, 34, 50)
            textSize = 25f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(44, 56, 70); textSize = 15f }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(105, 117, 132); textSize = 12f }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 75, 210); style = Paint.Style.FILL }
        canvas.drawColor(Color.WHITE)
        canvas.drawText("Sound session summary", 42f, 62f, titlePaint)
        canvas.drawText("Analyzed ${formatSoundDuration(summary.totalSeconds)}", 42f, 91f, smallPaint)
        canvas.drawLine(42f, 111f, 553f, 111f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = 1f })
        var y = 151f
        summary.entries.take(10).forEachIndexed { index, entry ->
            val label = entry.label.take(42)
            textPaint.typeface = if (index == 0) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            canvas.drawText(label, 42f, y, textPaint)
            canvas.drawText(String.format(java.util.Locale.US, "%.1f%% · %.1f s", entry.percent, entry.seconds), 553f, y, textPaint.apply { textAlign = Paint.Align.RIGHT })
            textPaint.textAlign = Paint.Align.LEFT
            barPaint.color = if (index == 0) Color.rgb(126, 75, 210) else Color.rgb(117, 160, 205)
            val barWidth = (entry.percent.coerceAtLeast(0.0) / 100.0 * 470.0).toFloat().coerceIn(0f, 470f)
            canvas.drawRoundRect(42f, y + 10f, 42f + barWidth, y + 17f, 3f, 3f, barPaint)
            y += 56f
        }
        canvas.drawText("Shares are weighted within each top-3 result; they are not calibrated probabilities.", 42f, 790f, smallPaint)
        pdf.finishPage(page)
        val output = java.io.ByteArrayOutputStream()
        try {
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
        pendingSoundIdPdf = output.toByteArray()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "sound-id-session.pdf")
        }
        try {
            startActivityForResult(intent, soundIdExportRequestCode)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open a location to save the summary.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFullScaleKeyboard() {
        if (!keyboardModeOpen || splitContainer == null) return
        moveKeyboardControlsToHeader()
        fullScaleKeyboardOpen = true
        (fullScaleKeysButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.FULL_SCALE_CLOSE
        toneModeOpen = false
        soundModeOpen = false
        spectrumView.setKeysFrequencyRangeActive(true)
        spectrumView.setDbRangeMode("KEYS")
        tonePanel?.visibility = View.GONE
        soundIdPanel?.visibility = View.GONE
        spectrumView.visibility = View.GONE
        keyboardSectionView?.visibility = View.VISIBLE
        splitContainer?.orientation = LinearLayout.VERTICAL
        keyboardSectionView?.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val pianoView = keyboardPanel as? PianoKeyboardView
        if (pianoView != null) {
            val cutoutSafeLeft = if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.decorView.rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
            } else {
                0
            }
            val viewLocation = IntArray(2)
            pianoView.getLocationInWindow(viewLocation)
            val localCutoutInset = (cutoutSafeLeft - viewLocation[0]).coerceAtLeast(0)
            val smallGap = (6f * resources.displayMetrics.density).roundToInt()
            pianoView.setSafeEdgeInset((localCutoutInset + smallGap).toFloat())
        }
        fullScaleKeysButton?.text = ""
        splitContainer?.requestLayout()
    }

    private fun moveKeyboardControlsToHeader() {
        val controls = keyboardControlsRow ?: return
        val header = spectrumHeader ?: return
        (controls.parent as? ViewGroup)?.removeView(controls)
        rangePickerButton?.visibility = View.GONE
        val density = resources.displayMetrics.density
        val peakReadoutSpace = (100f * density).roundToInt()
        val availableWidth = (header.width - peakReadoutSpace * 2 - (12f * density).roundToInt())
            .coerceAtLeast((180f * density).roundToInt())
        val targetWidth = minOf(availableWidth, (360f * density).roundToInt())
        header.addView(
            controls,
            android.widget.FrameLayout.LayoutParams(targetWidth, (42f * density).roundToInt(), Gravity.CENTER)
        )
    }

    private fun restoreKeyboardControls() {
        val controls = keyboardControlsRow ?: return
        val keyboardSection = keyboardSectionView as? ViewGroup ?: return
        (controls.parent as? ViewGroup)?.removeView(controls)
        keyboardSection.addView(
            controls,
            0,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40f * resources.displayMetrics.density).roundToInt())
        )
        rangePickerButton?.visibility = View.VISIBLE
        (keyboardPanel as? PianoKeyboardView)?.setSafeEdgeInset(0f)
    }

    private fun leaveFullScaleKeyboard() {
        if (!fullScaleKeyboardOpen) return
        fullScaleKeyboardOpen = false
        (fullScaleKeysButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.FULL_SCALE_OPEN
        restoreKeyboardControls()
        fullScaleKeysButton?.text = ""
        spectrumView.visibility = View.VISIBLE
        if (keyboardModeOpen) {
            splitContainer?.orientation = LinearLayout.VERTICAL
            spectrumView.layoutParams = LinearLayout.LayoutParams( ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            keyboardSectionView?.visibility = View.VISIBLE
            keyboardSectionView?.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            soundIdPanel?.visibility = View.GONE
            tonePanel?.visibility = View.GONE
        }
        splitContainer?.requestLayout()
    }

    @Suppress("unused")
    private fun showFullScaleKeyboardDialogLegacy() {
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

        val dialog = Dialog(this)
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
            pianoVolumeButton?.text = "VOLUME ${(pianoVolume * 100).roundToInt()}%"
            button.text = "VOLUME ${(pianoVolume * 100).roundToInt()}%"
        }
        val octave = control(when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }) { button ->
            pianoTranspose = when (pianoTranspose) { 0 -> 12; 12 -> -12; else -> 0 }
            pianoOctaveButton?.text = when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }
            button.text = when (pianoTranspose) { 12 -> "OCTAVE +1"; -12 -> "OCTAVE -1"; else -> "OCTAVE 0" }
        }
        val reverb = control(if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF") { button ->
            pianoReverbEnabled = !pianoReverbEnabled
            pianoReverbButton?.apply {
                text = if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF"
                styleXButton(this, primary = pianoReverbEnabled)
            }
            button.text = if (pianoReverbEnabled) "REVERB ON" else "REVERB OFF"
        }
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
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            keys.releaseAllPointers()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            soundIdExportRequestCode -> {
                val pdf = pendingSoundIdPdf
                pendingSoundIdPdf = null
                if (resultCode != RESULT_OK || uri == null || pdf == null) return
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Could not open the selected destination")
                    output.use { it.write(pdf) }
                    Toast.makeText(this, "Sound summary saved.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not save the summary.", Toast.LENGTH_LONG).show()
                }
            }
            personalSoundExportRequestCode -> {
                val archive = pendingPersonalSoundExport
                pendingPersonalSoundExport = null
                if (resultCode != RESULT_OK || uri == null || archive == null) return
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Could not open the selected destination")
                    output.use { it.write(archive) }
                    Toast.makeText(this, "WAV samples and labels exported.", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not export the samples.", Toast.LENGTH_LONG).show()
                }
            }
            personalSoundImportRequestCode -> {
                if (resultCode != RESULT_OK || uri == null) return
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("Could not read the selected file")
                    val count = importPersonalSoundArchive(bytes)
                    personalSoundListRefresh?.invoke()
                    Toast.makeText(this, "Imported $count sound sample(s).", Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    Toast.makeText(this, "Could not import samples: ${error.message ?: "invalid archive"}", Toast.LENGTH_LONG).show()
                }
            }
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

            // Sound ID consumes the same microphone samples as the spectrum graph.
            // It never opens a second AudioRecord session.
            if (soundIdActive) soundIdEngine.offerAudio(readBuffer, count)

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
        if (soundIdActive) {
            soundIdActive = false
            if (soundIdPanel != null) {
                soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
                updateSoundIdToggleStyle()
            }
            soundIdEngine.setListening(false)
        }

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
        fullScaleKeysDialog?.dismiss()
        soundIdTimerHandler.removeCallbacks(soundIdTimerRunnable)
        stopPersonalSoundPreview()
        stopTone()
        stopAnalysis()
        if (soundIdPanel != null) soundIdEngine.close()
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

private data class SoundSessionEntry(
    val label: String,
    val seconds: Double,
    val percent: Double
)

private data class SoundCandidate(val label: String, val scorePercent: Int)

private data class SoundSessionSummary(
    val totalSeconds: Double,
    val entries: List<SoundSessionEntry>
)

private data class SoundIdResult(
    val status: String,
    val bestSound: String,
    val modelScorePercent: Int = 0,
    val alternatives: String = "",
    val sessionSummary: SoundSessionSummary? = null,
    val isPersonalMatch: Boolean = false
)

private const val PERSONAL_SOUND_FEATURE_COUNT = 585
private const val PERSONAL_SOUND_SAMPLE_SECONDS = 10
private const val PERSONAL_SOUND_PROFILE_WINDOWS = 19
private const val PERSONAL_SOUND_MATCH_THRESHOLD = 0.72f
private const val PERSONAL_SOUND_NOTE_THRESHOLD = 0.55f

private data class PersonalSoundProfile(val id: String, val name: String, val embedding: FloatArray)

/**
 * YAMNet bridge for the analyzer's existing 48 kHz mono microphone stream.
 * Capture is supplied by MainActivity; this class only resamples and classifies
 * bounded, overlapping windows on one background worker.
 */
private class SoundIdEngine(
    private val context: Context,
    private val onResult: (SoundIdResult) -> Unit
) {
    companion object {
        private const val MODEL_RATE = 16_000
        private const val MODEL_SAMPLES = 15_600 // 0.975 s at 16 kHz
        private const val WINDOW_SAMPLES = MODEL_SAMPLES * 3
        private const val HOP_SAMPLES = 23_040 // 0.48 s at 48 kHz
        private const val CLASS_COUNT = 521
        private const val PERSONAL_SPECTRAL_BANDS = 64
        private const val PERSONAL_FEATURE_COUNT = PERSONAL_SOUND_FEATURE_COUNT
        private const val QUIET_DBFS = -60.0
        private const val MIN_MODEL_SCORE = 0.14f
        private const val MIN_CHILD_SCORE = 0.12f
        private const val MIN_CHILD_PARENT_RATIO = 0.30f
        private const val CLASSIFIED_SECONDS_PER_WINDOW = HOP_SAMPLES / 48_000.0
    }

    @Volatile private var listening = false
    @Volatile private var ready = false
    private val loadStarted = AtomicBoolean(false)
    private val inferencePending = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SoundId-YAMNet").apply { priority = Thread.NORM_PRIORITY }
    }

    // offerAudio() is called only from the single AudioRecord consumer thread.
    private val inputRing = ShortArray(WINDOW_SAMPLES)
    private var writeIndex = 0
    private var samplesSeen = 0L
    private var samplesSinceWindow = 0
    private var wasListening = false

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var childClassIndices: Array<IntArray> = emptyArray()
    private val smoothedScores = FloatArray(CLASS_COUNT)
    private var hasSmoothedScores = false
    private val personalSoundLock = Any()
    private val personalSounds = loadPersonalSounds()
    @Volatile private var personalCaptureName: String? = null
    private var personalCaptureSum = FloatArray(PERSONAL_FEATURE_COUNT)
    private var personalCaptureWindows = 0
    private var personalCaptureWarmup = true
    @Volatile private var personalSoundCapturedListener: ((FloatArray, ShortArray) -> Unit)? = null
    private var personalCapturePcm = ShortArray(0)
    private var personalCapturePcmCount = 0
    private var personalCapturePcmActive = false
    private val secondsByLabel = linkedMapOf<String, Double>()
    private var analyzedSeconds = 0.0
    private val filter = createDecimatorFilter()

    fun setListening(value: Boolean) {
        listening = value
        if (!value) {
            worker.execute {
                onResult(SoundIdResult("SESSION COMPLETE", "", sessionSummary = createSessionSummary()))
            }
            return
        }
        onResult(SoundIdResult("LOADING MODEL", "Waiting for the first sound…"))
        worker.execute {
            secondsByLabel.clear()
            analyzedSeconds = 0.0
            smoothedScores.fill(0f)
            hasSmoothedScores = false
            if (loadStarted.compareAndSet(false, true)) loadModel()
            else if (ready) onResult(SoundIdResult("LISTENING", "Listening for sounds…"))
        }
    }

    fun getPersonalSounds(): List<PersonalSoundProfile> = synchronized(personalSoundLock) {
        personalSounds.map { PersonalSoundProfile(it.id, it.name, it.embedding.copyOf()) }
    }

    fun removePersonalSound(id: String) {
        synchronized(personalSoundLock) {
            personalSounds.removeAll { profile ->
                if (profile.id == id) {
                    File(context.filesDir, "personal_sound_samples/${profile.id}.pcm").delete()
                    true
                } else false
            }
            persistPersonalSoundsLocked()
        }
    }

    fun beginPersonalSoundCapture() {
        synchronized(personalSoundLock) {
            personalCaptureName = "pending"
            personalCaptureSum = FloatArray(PERSONAL_FEATURE_COUNT)
            personalCaptureWindows = 0
            personalCaptureWarmup = true
            personalCapturePcm = ShortArray(MODEL_RATE * 3 * PERSONAL_SOUND_SAMPLE_SECONDS)
            personalCapturePcmCount = 0
            personalCapturePcmActive = true
        }
    }

    fun cancelPersonalSoundCapture() {
        synchronized(personalSoundLock) {
            personalCaptureName = null
            personalCaptureSum = FloatArray(PERSONAL_FEATURE_COUNT)
            personalCaptureWindows = 0
            personalCaptureWarmup = true
            personalCapturePcm = ShortArray(0)
            personalCapturePcmCount = 0
            personalCapturePcmActive = false
        }
    }

    fun setPersonalSoundCapturedListener(listener: ((FloatArray, ShortArray) -> Unit)?) {
        personalSoundCapturedListener = listener
    }

    fun getPersonalSoundAudio(id: String): ShortArray? {
        val file = File(context.filesDir, "personal_sound_samples/$id.pcm")
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 2 || bytes.size % 2 != 0) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buffer.short }
    }

    fun savePersonalSound(name: String, embedding: FloatArray, audio: ShortArray) {
        val normalized = normalizeEmbedding(embedding)
        synchronized(personalSoundLock) {
            val id = java.util.UUID.randomUUID().toString()
            val directory = File(context.filesDir, "personal_sound_samples")
            directory.mkdirs()
            val pcm = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            audio.forEach { sample -> pcm.putShort(sample) }
            File(directory, "$id.pcm").writeBytes(pcm.array())
            personalSounds.add(PersonalSoundProfile(id, name, normalized))
            persistPersonalSoundsLocked()
        }
    }

    fun offerAudio(samples: ShortArray, count: Int) {
        if (!listening) {
            if (wasListening) {
                writeIndex = 0
                samplesSeen = 0L
                samplesSinceWindow = 0
                wasListening = false
            }
            return
        }
        if (!wasListening) {
            writeIndex = 0
            samplesSeen = 0L
            samplesSinceWindow = 0
            wasListening = true
        }
        synchronized(personalSoundLock) {
            if (personalCapturePcmActive && personalCapturePcmCount < personalCapturePcm.size) {
                val copyCount = minOf(count, personalCapturePcm.size - personalCapturePcmCount)
                samples.copyInto(personalCapturePcm, personalCapturePcmCount, 0, copyCount)
                personalCapturePcmCount += copyCount
                if (personalCapturePcmCount >= personalCapturePcm.size) personalCapturePcmActive = false
            }
        }
        for (i in 0 until count) {
            inputRing[writeIndex] = samples[i]
            writeIndex = (writeIndex + 1) % inputRing.size
        }
        samplesSeen += count
        samplesSinceWindow += count
        if (samplesSeen < WINDOW_SAMPLES || samplesSinceWindow < HOP_SAMPLES) return
        samplesSinceWindow = 0
        if (!ready || !inferencePending.compareAndSet(false, true)) return

        val frame = ShortArray(WINDOW_SAMPLES)
        var index = writeIndex
        for (i in frame.indices) {
            frame[i] = inputRing[index]
            index = (index + 1) % inputRing.size
        }
        worker.execute {
            try {
                classify(frame)
            } catch (error: Exception) {
                onResult(SoundIdResult(
                    "ANALYSIS ERROR",
                    error.message ?: "Could not analyze this audio."
                ))
            } finally {
                inferencePending.set(false)
            }
        }
    }

    private fun loadModel() {
        try {
            val modelBytes = context.assets.open("yamnet.tflite").use { it.readBytes() }
            require(modelBytes.isNotEmpty()) { "yamnet.tflite is empty" }
            val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            model.put(modelBytes)
            model.rewind()
            val classRows = context.assets.open("yamnet_class_map.csv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("index,")) null
                    else {
                        val fields = trimmed.split(',', limit = 3)
                        if (fields.size < 3) null else {
                            val classId = fields[1].trim()
                            val name = fields[2].trim().removeSurrounding("\"")
                            classId to name
                        }
                    }
                }.toList()
            }
            val classNames = classRows.map { it.second }
            require(classNames.size == CLASS_COUNT) {
                "Expected 521 YAMNet labels, found ${classNames.size}"
            }
            val classIndexById = classRows.mapIndexed { index, row -> row.first to index }.toMap()
            val children = Array(CLASS_COUNT) { mutableListOf<Int>() }
            val ontology = context.assets.open("audioset_ontology.json").bufferedReader().use { reader ->
                JSONArray(reader.readText())
            }
            for (nodeIndex in 0 until ontology.length()) {
                val node = ontology.getJSONObject(nodeIndex)
                val parentIndex = classIndexById[node.optString("id")] ?: continue
                val childIds = node.optJSONArray("child_ids") ?: continue
                for (childIndex in 0 until childIds.length()) {
                    val modelChildIndex = classIndexById[childIds.optString(childIndex)] ?: continue
                    children[parentIndex].add(modelChildIndex)
                }
            }
            childClassIndices = Array(CLASS_COUNT) { children[it].distinct().toIntArray() }
            val options = Interpreter.Options().setNumThreads(2)
            interpreter = Interpreter(model, options)
            labels = classNames
            ready = true
            onResult(SoundIdResult("LISTENING", "Listening for sounds…"))
        } catch (error: Exception) {
            ready = false
            onResult(SoundIdResult(
                "MODEL COULD NOT LOAD",
                error.message ?: "Check that the sound model is included in the app."
            ))
        }
    }

    private fun classify(source48k: ShortArray) {
        val audio16k = resample48kTo16k(source48k)
        val rms = sqrt(audio16k.sumOf { it.toDouble() * it.toDouble() } / audio16k.size)
        val rmsDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
        if (rmsDb < QUIET_DBFS) {
            recordWinner("Unclear / quiet")
            onResult(SoundIdResult("LISTENING", "No clear sound", 0))
            return
        }

        val output = Array(1) { FloatArray(CLASS_COUNT) }
        interpreter?.run(arrayOf(audio16k), output)
            ?: error("The YAMNet interpreter is not ready")
        val scores = output[0]
        val personalEmbedding = createPersonalEmbedding(scores, audio16k)
        val capturedEmbedding = synchronized(personalSoundLock) {
            if (personalCaptureName == null) null else if (personalCaptureWarmup) {
                personalCaptureWarmup = false
                null
            } else {
                for (index in personalEmbedding.indices) personalCaptureSum[index] += personalEmbedding[index]
                personalCaptureWindows++
                if (personalCaptureWindows >= PERSONAL_SOUND_PROFILE_WINDOWS) {
                    val averaged = FloatArray(PERSONAL_FEATURE_COUNT) { personalCaptureSum[it] / personalCaptureWindows }
                    personalCaptureName = null
                    personalCaptureSum = FloatArray(PERSONAL_FEATURE_COUNT)
                    personalCaptureWindows = 0
                    averaged
                } else null
            }
        }
        capturedEmbedding?.let { embedding ->
            val pcm = synchronized(personalSoundLock) {
                val captured = personalCapturePcm.copyOf(personalCapturePcmCount)
                personalCapturePcm = ShortArray(0)
                personalCapturePcmCount = 0
                personalCapturePcmActive = false
                captured
            }
            personalSoundCapturedListener?.invoke(embedding, pcm)
        }
        if (!hasSmoothedScores) {
            scores.copyInto(smoothedScores)
            hasSmoothedScores = true
        } else {
            for (i in scores.indices) smoothedScores[i] = smoothedScores[i] * 0.55f + scores[i] * 0.45f
        }
        val ranked = smoothedScores.indices
            .asSequence()
            .filter { smoothedScores[it] >= 0.05f }
            .map { mostSpecificSupportedClass(it, smoothedScores) }
            .distinct()
            .sortedByDescending { smoothedScores[it] }
            .take(3)
            .toList()
        val personalMatch = findPersonalSoundMatch(personalEmbedding)
        val customCandidate = personalMatch?.takeIf { it.second >= PERSONAL_SOUND_MATCH_THRESHOLD }?.let { (profile, similarity) ->
            SoundCandidate(profile.name, (similarity * 100f).roundToInt())
        }
        val best = ranked.firstOrNull() ?: -1
        if (best < 0 || smoothedScores[best] < MIN_MODEL_SCORE) {
            if (customCandidate != null) {
                recordWinner(customCandidate.label)
                onResult(SoundIdResult(
                    "LISTENING · SAVED SOUND MATCH",
                    customCandidate.label,
                    customCandidate.scorePercent,
                    "Similarity to your saved sample",
                    isPersonalMatch = true
                ))
            } else {
                recordWinner("Unclear / mixed")
                onResult(SoundIdResult("LISTENING", "No clear sound", 0))
            }
            return
        }
        val bestScore = smoothedScores[best].coerceIn(0f, 1f)
        val candidates = ranked.mapNotNull { index ->
            val score = smoothedScores[index].coerceIn(0f, 1f)
            if (score < 0.05f) null else SoundCandidate(
                labels.getOrElse(index) { "Unknown sound" },
                (score * 100f).roundToInt()
            )
        }
        if (candidates.isEmpty()) {
            if (customCandidate != null) {
                recordWinner(customCandidate.label)
                onResult(SoundIdResult("LISTENING · SAVED SOUND MATCH", customCandidate.label,
                    customCandidate.scorePercent, "Similarity to your saved sample", isPersonalMatch = true))
            } else {
                recordWinner("Unclear / mixed")
                onResult(SoundIdResult("LISTENING", "No clear sound", 0))
            }
            return
        }
        val allCandidates = (listOfNotNull(customCandidate) + candidates).distinctBy { it.label }.take(3)
        recordWindow(allCandidates)
        val label = allCandidates.first().label
        val alternatives = allCandidates.drop(1).takeIf { it.isNotEmpty() }?.let { others ->
            "Also possible: " + others.joinToString("   ·   ") { "${it.label} ${it.scorePercent}%" }
        }.orEmpty()
        val displayedScore = if (customCandidate != null) customCandidate.scorePercent else (bestScore * 100f).roundToInt()
        val status = when {
            customCandidate != null -> "LISTENING · SAVED SOUND MATCH"
            displayedScore >= 35 -> "LISTENING · CLEAR MATCH"
            else -> "LISTENING · LOW MATCH"
        }
        val personalNote = personalMatch?.takeIf { it.second >= PERSONAL_SOUND_NOTE_THRESHOLD }?.let { (profile, similarity) ->
            "Nearest saved sample: ${profile.name} · ${(similarity * 100f).roundToInt()}% similarity"
        }.orEmpty()
        val fullAlternatives = listOf(personalNote, alternatives).filter { it.isNotBlank() }.joinToString("\n")
        onResult(SoundIdResult(status, label, displayedScore, fullAlternatives, isPersonalMatch = customCandidate != null))
    }

    private fun createPersonalEmbedding(scores: FloatArray, audio: FloatArray): FloatArray {
        val classifierVector = normalizeEmbedding(scores)
        val spectralBands = DoubleArray(PERSONAL_SPECTRAL_BANDS)
        val bandCounts = IntArray(PERSONAL_SPECTRAL_BANDS)
        val fftSize = 2048
        val minHz = 45.0
        val maxHz = MODEL_RATE / 2.0
        val logSpan = ln(maxHz / minHz)
        var frameCount = 0
        var frameStart = 0
        while (frameStart + fftSize <= audio.size) {
            val real = DoubleArray(fftSize)
            val imaginary = DoubleArray(fftSize)
            for (sampleIndex in 0 until fftSize) {
                val hann = 0.5 * (1.0 - cos(2.0 * PI * sampleIndex / (fftSize - 1)))
                real[sampleIndex] = audio[frameStart + sampleIndex] * hann
            }
            fft(real, imaginary)
            for (bin in 1 until fftSize / 2) {
                val frequency = bin * MODEL_RATE.toDouble() / fftSize
                if (frequency < minHz) continue
                val band = (ln(frequency / minHz) / logSpan * PERSONAL_SPECTRAL_BANDS)
                    .toInt().coerceIn(0, PERSONAL_SPECTRAL_BANDS - 1)
                spectralBands[band] += hypot(real[bin], imaginary[bin])
                bandCounts[band]++
            }
            frameCount++
            frameStart += fftSize
        }
        val spectralVector = FloatArray(PERSONAL_SPECTRAL_BANDS) { band ->
            if (bandCounts[band] == 0 || frameCount == 0) 0f
            else (spectralBands[band] / bandCounts[band] / frameCount).toFloat()
        }
        val normalizedSpectrum = normalizeEmbedding(spectralVector)
        val modelWeight = 0.70710677f
        val spectrumWeight = 0.70710677f
        return FloatArray(PERSONAL_FEATURE_COUNT).also { combined ->
            for (index in classifierVector.indices) combined[index] = classifierVector[index] * modelWeight
            for (index in normalizedSpectrum.indices) {
                combined[CLASS_COUNT + index] = normalizedSpectrum[index] * spectrumWeight
            }
        }
    }

    private fun findPersonalSoundMatch(embedding: FloatArray): Pair<PersonalSoundProfile, Float>? {
        val normalized = normalizeEmbedding(embedding)
        val profiles = getPersonalSounds()
        return profiles.mapNotNull { profile ->
            if (profile.embedding.size != normalized.size) return@mapNotNull null
            val similarity = profile.embedding.indices.sumOf { index ->
                profile.embedding[index].toDouble() * normalized[index]
            }.toFloat().coerceIn(-1f, 1f)
            profile to similarity
        }.maxByOrNull { it.second }
    }

    private fun normalizeEmbedding(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        return if (norm <= 1e-8f) values.copyOf() else FloatArray(values.size) { values[it] / norm }
    }

    private fun loadPersonalSounds(): MutableList<PersonalSoundProfile> {
        val profiles = mutableListOf<PersonalSoundProfile>()
        val stored = context.getSharedPreferences("personal_sound_ids", Context.MODE_PRIVATE)
            .getString("profiles", null) ?: return profiles
        return try {
            val array = JSONArray(stored)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val vector = item.optJSONArray("embedding") ?: continue
                if (name.isBlank() || vector.length() != PERSONAL_FEATURE_COUNT) continue
                val id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                profiles.add(PersonalSoundProfile(id, name, FloatArray(PERSONAL_FEATURE_COUNT) { vector.optDouble(it).toFloat() }))
            }
            profiles
        } catch (_: Exception) {
            profiles
        }
    }

    private fun persistPersonalSoundsLocked() {
        val array = JSONArray()
        personalSounds.forEach { profile ->
            val vector = JSONArray()
            profile.embedding.forEach { value -> vector.put(value.toDouble()) }
            array.put(JSONObject().put("id", profile.id).put("name", profile.name).put("embedding", vector))
        }
        context.getSharedPreferences("personal_sound_ids", Context.MODE_PRIVATE)
            .edit().putString("profiles", array.toString()).apply()
    }

    /**
     * YAMNet's AudioSet outputs include broad and specific classes independently.
     * Prefer a supported child class (e.g. Dog over Animal) only when that child
     * has a meaningful score of its own; otherwise retain the broader prediction.
     */
    private fun mostSpecificSupportedClass(startIndex: Int, scores: FloatArray): Int {
        var currentIndex = startIndex
        val visited = HashSet<Int>()
        while (visited.add(currentIndex)) {
            val parentScore = scores.getOrElse(currentIndex) { 0f }
            val supportedChild = childClassIndices.getOrNull(currentIndex)
                ?.asSequence()
                ?.filter { child ->
                    val childScore = scores.getOrElse(child) { 0f }
                    childScore >= MIN_CHILD_SCORE && childScore >= parentScore * MIN_CHILD_PARENT_RATIO
                }
                ?.maxByOrNull { scores.getOrElse(it) { 0f } }
                ?: return currentIndex
            currentIndex = supportedChild
        }
        return currentIndex
    }

    private fun recordWinner(label: String) = recordWindow(listOf(SoundCandidate(label, 100)))

    private fun recordWindow(candidates: List<SoundCandidate>) {
        val seconds = CLASSIFIED_SECONDS_PER_WINDOW
        analyzedSeconds += seconds
        val uniqueCandidates = candidates.distinctBy { it.label }
        if (uniqueCandidates.isEmpty()) return
        val scoreTotal = uniqueCandidates.sumOf { it.scorePercent.coerceAtLeast(0) }.takeIf { it > 0 }
        uniqueCandidates.forEach { candidate ->
            val share = if (scoreTotal == null) 1.0 / uniqueCandidates.size else candidate.scorePercent.coerceAtLeast(0).toDouble() / scoreTotal
            secondsByLabel[candidate.label] = (secondsByLabel[candidate.label] ?: 0.0) + seconds * share
        }
    }

    private fun createSessionSummary(): SoundSessionSummary {
        if (analyzedSeconds <= 0.0) return SoundSessionSummary(0.0, emptyList())
        val ranked = secondsByLabel.entries.sortedByDescending { it.value }
        val entries = ranked.take(5).map { (label, seconds) ->
            SoundSessionEntry(label, seconds, seconds / analyzedSeconds * 100.0)
        }
        return SoundSessionSummary(analyzedSeconds, entries)
    }

    /** Windowed-sinc low-pass followed by 3:1 decimation; this is not rate reinterpretation. */
    private fun resample48kTo16k(source: ShortArray): FloatArray {
        val output = FloatArray(MODEL_SAMPLES)
        val centerOffset = filter.size / 2
        for (outIndex in output.indices) {
            val center = outIndex * 3
            var sum = 0.0
            for (tap in filter.indices) {
                val sourceIndex = center + tap - centerOffset
                if (sourceIndex in source.indices) sum += source[sourceIndex].toDouble() / 32768.0 * filter[tap]
            }
            output[outIndex] = sum.toFloat().coerceIn(-1f, 1f)
        }
        return output
    }

    private fun measureAcoustics(audio: FloatArray, rmsDb: Double): String {
        val n = 4096
        val real = DoubleArray(n)
        val imaginary = DoubleArray(n)
        val start = audio.size - n
        for (i in 0 until n) {
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            real[i] = audio[start + i] * window
        }
        fft(real, imaginary)
        val magnitudes = DoubleArray(n / 2)
        var total = 0.0
        var weighted = 0.0
        var logSum = 0.0
        var arithmeticSum = 0.0
        var peakBin = 1
        for (bin in 1 until magnitudes.size) {
            val magnitude = hypot(real[bin], imaginary[bin])
            magnitudes[bin] = magnitude
            if (magnitude > magnitudes[peakBin]) peakBin = bin
            val power = magnitude * magnitude
            total += power
            weighted += (bin * MODEL_RATE.toDouble() / n) * power
            val safe = max(magnitude, 1e-12)
            logSum += ln(safe)
            arithmeticSum += safe
        }
        val centroid = if (total > 0.0) weighted / total else 0.0
        val flatness = if (arithmeticSum > 0.0) exp(logSum / magnitudes.size) / (arithmeticSum / magnitudes.size) else 0.0
        val dominantHz = peakBin * MODEL_RATE.toDouble() / n
        var harmonicEnergy = 0.0
        for (harmonic in 1..5) {
            val bin = peakBin * harmonic
            if (bin >= magnitudes.lastIndex) break
            for (near in max(1, bin - 1)..min(magnitudes.lastIndex, bin + 1)) {
                harmonicEnergy += magnitudes[near] * magnitudes[near]
            }
        }
        val harmonicRatio = if (total > 0.0) (harmonicEnergy / total).coerceIn(0.0, 1.0) else 0.0
        val texture = when {
            flatness < 0.08 && harmonicRatio > 0.25 -> "tonal / harmonic"
            flatness > 0.28 -> "noise-like / broadband"
            else -> "mixed spectrum"
        }
        fun bandDb(firstHz: Double, lastHz: Double): String {
            val first = max(1, (firstHz * n / MODEL_RATE).toInt())
            val last = min(magnitudes.lastIndex, (lastHz * n / MODEL_RATE).toInt())
            var power = 0.0
            for (i in first..last) power += magnitudes[i] * magnitudes[i]
            // Correct the Hann-windowed, unnormalised FFT magnitude to mean-square level.
            val meanSquare = 16.0 * power / (3.0 * n * n)
            val level = sqrt(meanSquare)
            val db = if (level > 0.0) 20.0 * log10(level) else -120.0
            return String.format(java.util.Locale.US, "%.0f dBFS", db)
        }
        val peakText = if (dominantHz >= 1000.0) String.format(java.util.Locale.US, "%.2f kHz", dominantHz / 1000.0)
        else String.format(java.util.Locale.US, "%.0f Hz", dominantHz)
        return String.format(
            java.util.Locale.US,
            "RMS %.1f dBFS · dominant %s · centroid %.0f Hz · flatness %.2f · harmonic energy %.0f%% (%s)\nBands (low/mid/high/top): %s / %s / %s / %s",
            rmsDb, peakText, centroid, flatness, harmonicRatio * 100.0, texture,
            bandDb(20.0, 250.0), bandDb(250.0, 1000.0), bandDb(1000.0, 4000.0), bandDb(4000.0, 8000.0)
        )
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            for (start in 0 until n step length) {
                var wReal = 1.0
                var wImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImaginary
                    val oddImaginary = real[odd] * wImaginary + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = wReal * stepReal - wImaginary * stepImaginary
                    wImaginary = wReal * stepImaginary + wImaginary * stepReal
                    wReal = nextReal
                }
            }
            length = length shl 1
        }
    }

    fun close() {
        listening = false
        worker.execute {
            try { interpreter?.close() } catch (_: Exception) { }
            interpreter = null
        }
        worker.shutdown()
    }

    private fun createDecimatorFilter(): DoubleArray {
        val taps = 63
        val middle = taps / 2
        val cutoff = 1.0 / 6.0 // 8 kHz Nyquist limit for 16 kHz output from 48 kHz input.
        val coefficients = DoubleArray(taps)
        var sum = 0.0
        for (i in 0 until taps) {
            val x = i - middle
            val sinc = if (x == 0) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
            val window = 0.42 - 0.5 * cos(2.0 * PI * i / (taps - 1)) + 0.08 * cos(4.0 * PI * i / (taps - 1))
            coefficients[i] = sinc * window
            sum += coefficients[i]
        }
        for (i in coefficients.indices) coefficients[i] /= sum
        return coefficients
    }
}

private class SoundSessionChartView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 207, 222)
        textSize = 12f * density
        textAlign = Paint.Align.RIGHT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(43, 55, 70) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 14f * density
    }
    private var rows: List<SoundSessionEntry> = emptyList()

    fun setSummary(summary: SoundSessionSummary) {
        rows = summary.entries
        contentDescription = if (rows.isEmpty()) "No sound was analyzed" else rows.joinToString { "${it.label}: ${it.percent.roundToInt()} percent" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) {
            canvas.drawText("No sound was analyzed in this session.", 0f, height * 0.5f, mutedPaint)
            return
        }
        val rowHeight = height.toFloat() / rows.size
        val labelBaselineOffset = 15f * density
        val trackHeight = 5f * density
        val valueSpace = 48f * density
        val barRight = (width - valueSpace).coerceAtLeast(0f)
        val labelLimit = barRight - 8f * density
        val colors = intArrayOf(
            Color.rgb(75, 196, 255), Color.rgb(80, 220, 176), Color.rgb(255, 190, 86),
            Color.rgb(183, 139, 255), Color.rgb(255, 124, 145), Color.rgb(136, 158, 181)
        )
        rows.forEachIndexed { index, row ->
            val top = index * rowHeight
            val baseline = top + labelBaselineOffset
            val label = ellipsize(row.label, textPaint, labelLimit)
            canvas.drawText(label, 0f, baseline, textPaint)
            percentPaint.color = Color.rgb(190, 207, 222)
            canvas.drawText("${row.percent.roundToInt()}%", width.toFloat(), baseline, percentPaint)
            val trackTop = top + 23f * density
            canvas.drawRoundRect(0f, trackTop, barRight, trackTop + trackHeight, trackHeight, trackHeight, trackPaint)
            val fillRight = (barRight * row.percent / 100.0).toFloat().coerceIn(0f, barRight)
            barPaint.color = if (row.label.startsWith("Unclear")) Color.rgb(136, 158, 181) else colors[index % colors.size]
            canvas.drawRoundRect(0f, trackTop, fillRight, trackTop + trackHeight, trackHeight, trackHeight, barPaint)
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }
}

private class PianoKeyboardView(
    context: Context,
    private val onKeyChange: (midiNote: Int, pressed: Boolean) -> Unit
) : View(context) {
    private var safeEdgeInsetPx = 0f
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 245, 240); style = Paint.Style.FILL }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 29, 36); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 78, 90); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 75); style = Paint.Style.FILL }
    private val whiteNotes = (36..96).filter { it % 12 !in listOf(1, 3, 6, 8, 10) } // C2–C7
    private val blackNotes = (36..95).map { note -> if (note % 12 in listOf(1, 3, 6, 8, 10)) note else -1 }
    private val pointerNotes = mutableMapOf<Int, Int>()
    private val activeNotes = mutableSetOf<Int>()

    fun setSafeEdgeInset(insetPx: Float) {
        safeEdgeInsetPx = insetPx.coerceAtLeast(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 23, 36))
        if (width <= 0 || height <= 0) return
        val edgeInset = safeEdgeInsetPx.coerceAtMost(width.toFloat() * 0.35f)
        val usableWidth = (width.toFloat() - edgeInset * 2f).coerceAtLeast(1f)
        val whiteWidth = usableWidth / whiteNotes.size
        val top = height * 0.04f
        val bottom = height * 0.96f
        whiteNotes.forEachIndexed { index, note ->
            val left = edgeInset + index * whiteWidth
            val right = edgeInset + (index + 1) * whiteWidth
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
            val left = edgeInset + whiteIndex * whiteWidth - blackWidth / 2f
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
        val edgeInset = safeEdgeInsetPx.coerceAtMost(width.toFloat() * 0.35f)
        if (y < 0 || y > height || x < edgeInset || x > width - edgeInset) return null
        val usableWidth = (width.toFloat() - edgeInset * 2f).coerceAtLeast(1f)
        val whiteWidth = usableWidth / whiteNotes.size
        val blackWidth = whiteWidth * 0.58f
        val blackHeight = height * 0.60f
        if (y <= blackHeight) {
            for (note in blackNotes) {
                if (note < 0) continue
                val whiteIndex = whiteNotes.count { it < note }
                val left = edgeInset + whiteIndex * whiteWidth - blackWidth / 2f
                if (x in left..(left + blackWidth)) return note
            }
        }
        return whiteNotes[((x - edgeInset) / whiteWidth).toInt().coerceIn(0, whiteNotes.lastIndex)]
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

    private val dbAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(106, 127, 149)
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val dbAxisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 233, 243)
        textSize = 27f
        textAlign = Paint.Align.RIGHT
    }
    private val dbAxisUnitPaint = Paint(dbLabelPaint).apply { textAlign = Paint.Align.RIGHT }

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
    private var spectrogramPanOffsetY = 0f
    private var yScaleStartY = 0f
    private var yScaleStartRange = 80f
    private var yScaleStartCenter = -90f
    // Reserve the right edge for the dB axis and make that axis itself draggable for zoom.
    private val dbAxisMarginWidth = 40f * resources.displayMetrics.density
    private var initialAutoRangePending = true
    @Volatile
    private var resetGeneration = 0
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private fun plotLeftInset(): Float {
        val density = resources.displayMetrics.density
        val cameraSafeInsetScreen = if (android.os.Build.VERSION.SDK_INT >= 28) {
            rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
        } else {
            0
        }
        val viewLocation = IntArray(2)
        getLocationOnScreen(viewLocation)
        val cameraSafeInset = (cameraSafeInsetScreen - viewLocation[0]).coerceAtLeast(0)
        // Keep a conservative landscape camera margin even when Android reports no cutout inset.
        return max(54f * density, cameraSafeInset + 10f * density)
    }

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
                // Dragging the dB-axis margin on the right changes the vertical range.
                draggingYScale = !spectrogramEnabled && event.x >= width - dbAxisMarginWidth
                if (draggingYScale) parent?.requestDisallowInterceptTouchEvent(true)
                yScaleStartY = event.y
                yScaleStartRange = (dbCeiling - dbFloor).toFloat()
                yScaleStartCenter = (dbCeiling + dbFloor) / 2f
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
                    // Scale from the gesture's starting point, using a low, density-independent
                    // sensitivity so short drags make small, predictable dB-range changes.
                    val totalDyDp = (event.y - yScaleStartY) / resources.displayMetrics.density
                    val newRange = (yScaleStartRange * kotlin.math.exp(totalDyDp.toDouble() * 0.0018))
                        .roundToInt().coerceIn(20, 160)
                    // Keep the starting midpoint fixed so zooming does not shift the graph.
                    var newFloor = kotlin.math.round(yScaleStartCenter.toDouble() - newRange / 2.0).toInt()
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
                if (frequency < 0.0 || frequency > highestAnalyzedFrequency) {
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

    fun getVisibleFrequencyRangeHz(): Pair<Int, Int> =
        visibleMinFrequency.roundToInt().coerceAtLeast(0) to visibleMaxFrequency.roundToInt().coerceAtLeast(0)

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
        val left = plotLeftInset()
        val right = width.toFloat() - dbAxisMarginWidth - 2f
        if (right <= left || touchX < left || touchX > right) return
        val ratio = ((touchX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        val selected = frequencyAtRatio(ratio, visibleMinFrequency, visibleMaxFrequency)
        selectedFrequency = if (selected in minFrequencyBound..20000.0) selected else null
        invalidate()
    }

    private fun selectSpectrogramAt(touchX: Float, touchY: Float) {
        val left = plotLeftInset()
        val right = width.toFloat() - dbAxisMarginWidth - 2f
        val top = 56f
        val bottom = height.toFloat() - 42f
        if (right <= left || bottom <= top || touchX < left || touchX > right || touchY < top || touchY > bottom) return
        val frequencyRatio = ((touchX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        val selectedHz = frequencyAtRatio(frequencyRatio, visibleMinFrequency, visibleMaxFrequency)
        if (selectedHz !in minFrequencyBound..20000.0) {
            selectedSpectrogramXRatio = null
            selectedSpectrogramYRatio = null
            invalidate()
            return
        }
        selectedSpectrogramXRatio = frequencyRatio
        val historyRatio = (touchY - top - spectrogramPanOffsetY) / (bottom - top)
        if (historyRatio !in 0f..1f) return
        selectedSpectrogramYRatio = historyRatio.toDouble()
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
        selectedSpectrogramXRatio = null
        selectedSpectrogramYRatio = null
        spectrogramPanOffsetY = 0f
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
        if (frequency < customAxisBreaks.first()) {
            val firstSpan = customAxisBreaks[1] - customAxisBreaks[0]
            return (frequency - customAxisBreaks[0]) / firstSpan * customAxisWeights[0]
        }
        if (frequency > customAxisBreaks.last()) {
            val last = customAxisBreaks.lastIndex
            val lastSpan = customAxisBreaks[last] - customAxisBreaks[last - 1]
            return customAxisCumulative.last() +
                    (frequency - customAxisBreaks.last()) / lastSpan * customAxisWeights.last()
        }
        val f = frequency
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
        if (position < 0.0) {
            return customAxisBreaks[0] + position / customAxisWeights[0] *
                    (customAxisBreaks[1] - customAxisBreaks[0])
        }
        if (position > customAxisCumulative.last()) {
            val last = customAxisBreaks.lastIndex
            return customAxisBreaks.last() + (position - customAxisCumulative.last()) /
                    customAxisWeights.last() * (customAxisBreaks[last] - customAxisBreaks[last - 1])
        }
        val p = position
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
        if (linearFrequencyAxis) return (frequency - minFreq) / (maxFreq - minFreq)
        val low = customAxisPosition(minFreq)
        val high = customAxisPosition(maxFreq)
        return (customAxisPosition(frequency) - low) / (high - low).coerceAtLeast(1e-12)
    }

    private fun frequencyAtRatio(ratio: Double, minFreq: Double, maxFreq: Double): Double {
        if (linearFrequencyAxis) return minFreq + ratio * (maxFreq - minFreq)
        val low = customAxisPosition(minFreq)
        val high = customAxisPosition(maxFreq)
        return frequencyAtCustomAxisPosition(low + ratio.coerceIn(0.0, 1.0) * (high - low))
            .coerceIn(minFreq, maxFreq)
    }

    private fun zoomAt(focusX: Float, scaleFactor: Float) {
        if (spectrogramEnabled) return
        if (!scaleFactor.isFinite() || scaleFactor <= 0f || width <= 0) return
        val left = plotLeftInset()
        val right = width.toFloat() - dbAxisMarginWidth - 2f
        if (right <= left) return
        val focusRatio = ((focusX - left) / (right - left)).coerceIn(0f, 1f).toDouble()
        val oldMin = visibleMinFrequency
        val oldMax = visibleMaxFrequency
        val anchorFrequency = frequencyAtRatio(focusRatio, oldMin, oldMax)
        val oldSpan = if (linearFrequencyAxis) oldMax - oldMin else customAxisPosition(oldMax) - customAxisPosition(oldMin)
        val minSpan = if (linearFrequencyAxis) 10.0 else minLogSpan
        val maxSpan = if (linearFrequencyAxis) maxFrequencyBound - minFrequencyBound else customAxisCumulative.last()
        val newSpan = (oldSpan / scaleFactor.toDouble()).coerceIn(minSpan, maxSpan)
        var newMin: Double
        var newMax: Double
        if (linearFrequencyAxis) {
            // Keep the frequency below the pinch midpoint at that same screen position.
            newMin = anchorFrequency - focusRatio * newSpan
            newMax = newMin + newSpan
        } else {
            val anchorPosition = customAxisPosition(anchorFrequency)
            val lowPosition = anchorPosition - focusRatio * newSpan
            newMin = frequencyAtCustomAxisPosition(lowPosition)
            newMax = frequencyAtCustomAxisPosition(lowPosition + newSpan)
        }
        visibleMinFrequency = newMin
        visibleMaxFrequency = newMax
        notifyFrequencyRange()
        invalidate()
    }

    private fun panBy(dx: Float, dy: Float) {
        // Waterfall grid stays fixed; only RANGE and LIN/LOG controls change its scale.
        if (spectrogramEnabled) {
            invalidate()
            return
        }
        // Horizontal dragging pans the frequency range; vertical dragging pans the
        // visible dB window without changing its height (dB span).
        val plotWidth = (width - plotLeftInset() - dbAxisMarginWidth - 2f).coerceAtLeast(1f)
        val fraction = -dx / plotWidth
        val frequencySpan = if (linearFrequencyAxis) visibleMaxFrequency - visibleMinFrequency
        else customAxisPosition(visibleMaxFrequency) - customAxisPosition(visibleMinFrequency)
        val frequencyShift = fraction * frequencySpan
        if (linearFrequencyAxis) {
            visibleMinFrequency += frequencyShift
            visibleMaxFrequency += frequencyShift
        } else {
            visibleMinFrequency = frequencyAtCustomAxisPosition(customAxisPosition(visibleMinFrequency) + frequencyShift)
            visibleMaxFrequency = frequencyAtCustomAxisPosition(customAxisPosition(visibleMaxFrequency) + frequencyShift)
        }

        // Keep the current dB range size fixed while moving it vertically.
        // The default/reset floor remains -130 dB, but panning can move the
        // visible window below that value so a zoomed trace can be repositioned.
        val plotHeight = (height - 80f).coerceAtLeast(1f)
        val dbSpan = (dbCeiling - dbFloor).coerceAtLeast(20)
        val dbShift = (dy / plotHeight * dbSpan).roundToInt()
        if (dbShift != 0) {
            val newFloor = dbFloor + dbShift
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
        val left = plotLeftInset()
        val right = w - dbAxisMarginWidth - 2f
        val top = 56f
        val bottom = h - 42f
        if (right <= left || bottom <= top) return

        // Keep the X-axis line out of the graph: it should not remain fixed when
        // the spectrum is panned vertically beyond the plot viewport.

        val minFreq = visibleMinFrequency
        val maxFreq = visibleMaxFrequency
        if (maxFreq <= minFreq) return

        fun xForFrequency(freq: Double): Float {
            val ratio = frequencyRatio(freq, minFreq, maxFreq)
            return left + (right - left) * ratio.toFloat()
        }

        val axisStart = if (linearFrequencyAxis) minFreq else customAxisPosition(minFreq)
        val axisEnd = if (linearFrequencyAxis) maxFreq else customAxisPosition(maxFreq)
        val axisPeriod = if (linearFrequencyAxis) maxFrequencyBound else customAxisCumulative.last()
        val firstGridCycle = kotlin.math.floor(axisStart / axisPeriod).toInt() - 1
        val lastGridCycle = kotlin.math.ceil(axisEnd / axisPeriod).toInt() + 1
        for (cycle in firstGridCycle..lastGridCycle) {
            for (frequency in gridFrequencies) {
                val basePosition = if (linearFrequencyAxis) frequency.toDouble() else customAxisPosition(frequency.toDouble())
                val position = basePosition + cycle * axisPeriod
                if (position < axisStart || position > axisEnd) continue
                val x = left + (right - left) * ((position - axisStart) / (axisEnd - axisStart)).toFloat()
                canvas.drawLine(x, top, x, bottom, gridPaint)
                if (cycle != 0 || frequency <= 0 || frequency !in frequencyLabelTicks) continue
                val label = when {
                    frequency == 20000 -> "20k"
                    frequency >= 1000 -> "${frequency / 1000}k"
                    else -> frequency.toString()
                }
                val labelX = max(left, x - textPaint.measureText(label) / 2f)
                canvas.drawText(label, labelX, bottom + 30f, textPaint)
            }
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
            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            canvas.translate(0f, spectrogramPanOffsetY)
            drawSpectrogram(
                canvas, historySnapshot, sourceSampleRate, sourceFftSize,
                minFreq, maxFreq, left, right, top, bottom
            )
            canvas.restore()
            val plotHeight = bottom - top
            for (i in 0..4) {
                val ratio = i / 4f
                val originalY = top + plotHeight * ratio + spectrogramPanOffsetY
                val firstCycle = kotlin.math.floor((top - originalY) / plotHeight).toInt() - 1
                val lastCycle = kotlin.math.ceil((bottom - originalY) / plotHeight).toInt() + 1
                for (cycle in firstCycle..lastCycle) {
                    val y = originalY + cycle * plotHeight
                    if (y !in top..bottom) continue
                    canvas.drawLine(left, y, right, y, gridPaint)
                    if (cycle == 0) {
                        val secondsAgo = historySeconds * (1.0 - ratio)
                        val label = if (i == 4) "0 s" else String.format(java.util.Locale.US, "%.1f s", secondsAgo)
                        canvas.drawText(label, w - 7f * resources.displayMetrics.density, y + 8f, dbAxisTextPaint)
                    }
                }
            }
            canvas.drawText("TIME", w - 7f * resources.displayMetrics.density, max(10f, top - 26f), dbAxisUnitPaint)
        } else {
            val axisX = right
            var dbTick = kotlin.math.floor(dbCeiling / 10.0).toInt() * 10
            canvas.drawLine(axisX, top, axisX, bottom, dbAxisPaint)
            while (dbTick >= dbFloor) {
                val y = yForDb(dbTick.toDouble())
                canvas.drawLine(left, y, right, y, gridPaint)
                canvas.drawLine(axisX - 4f, y, axisX + 3f, y, dbAxisPaint)
                canvas.drawText(dbTick.toString(), w - 7f * resources.displayMetrics.density, y + 8f, dbAxisTextPaint)
                dbTick -= 10
            }
            canvas.drawText("dB", w - 7f * resources.displayMetrics.density, top - 26f, dbAxisUnitPaint)
        }

        val data = magnitudes
        if (data.isEmpty()) {
            canvas.drawText("Waiting for audio...", left + 20f, top + 50f, textPaint)
            return
        }

        if (spectrogramEnabled) {
            // Draw the frequency/time grid over the waterfall so it remains readable.
            // Tapping the waterfall selects a frequency and time. Show a vertical
            // cursor so the selected frequency is easy to follow across the timeline.
            val selectedXRatio = selectedSpectrogramXRatio
            val selectedYRatio = selectedSpectrogramYRatio
            if (selectedXRatio != null && selectedYRatio != null && historySnapshot.isNotEmpty()) {
                val cursorY = top + (bottom - top) * selectedYRatio.toFloat() + spectrogramPanOffsetY
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
