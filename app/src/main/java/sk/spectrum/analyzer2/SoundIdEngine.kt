package sk.spectrum.analyzer2

import android.content.Context
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Existing offline sound classification, embeddings and model processing. */
/**
 * YAMNet bridge for the analyzer's existing 48 kHz mono microphone stream.
 * Capture is supplied by MainActivity; this class only resamples and classifies
 * bounded, overlapping windows on one background worker.
 */
internal class SoundIdEngine(
    private val context: Context,
    private val onResult: (SoundIdResult) -> Unit
) {
    companion object {
        private const val MODEL_RATE = 16_000
        private const val MODEL_SAMPLES = 15_600 // 0.975 s at 16 kHz
        private const val WINDOW_SAMPLES = MODEL_SAMPLES * 3
        private const val HOP_SAMPLES = 23_040 // 0.48 s at 48 kHz
        private const val CLASS_COUNT = 521
        private const val EFFICIENTAT_CLASS_COUNT = 527
        // EfficientAT's released model was trained on 2.925 s AudioSet clips.
        // Keep its longer context separate from YAMNet's shorter rolling window.
        private const val EFFICIENTAT_SOURCE_SAMPLE_COUNT = WINDOW_SAMPLES * 3
        private const val EFFICIENTAT_SAMPLE_COUNT = EFFICIENTAT_SOURCE_SAMPLE_COUNT * 32_000 / 48_000
        private const val EFFICIENTAT_FFT_SIZE = 1024
        private const val EFFICIENTAT_WINDOW_SIZE = 800
        private const val EFFICIENTAT_HOP_SIZE = 320
        private const val EFFICIENTAT_MEL_COUNT = 128
        private const val EFFICIENTAT_FRAME_COUNT = 293
        private const val EFFICIENTAT_INTERVAL_SAMPLES = HOP_SAMPLES * 6
        private const val EFFICIENTAT_MAX_LATENCY_MS = 1_000L
        private const val MIN_EFFICIENTAT_SCORE = 0.14f
        private const val PERSONAL_SPECTRAL_BANDS = 64
        private const val PERSONAL_FEATURE_COUNT = PERSONAL_SOUND_FEATURE_COUNT
        private const val QUIET_DBFS = -60.0
        private const val MIN_MODEL_SCORE = 0.14f
        private const val MIN_CHILD_SCORE = 0.12f
        private const val MIN_CHILD_PARENT_RATIO = 0.30f
    }

    @Volatile private var listening = false
    @Volatile private var ready = false
    @Volatile private var modelLoadError: String? = null
    private val loadStarted = AtomicBoolean(false)
    private val inferencePending = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SoundId-YAMNet").apply { priority = Thread.NORM_PRIORITY }
    }

    // offerAudio() is called only from the single AudioRecord consumer thread.
    // Keep one hop beyond a full model window so a frame can end on the exact
    // sample-clock boundary even when AudioRecord delivers a larger block.
    private val inputRing = ShortArray(maxOf(WINDOW_SAMPLES, EFFICIENTAT_SOURCE_SAMPLE_COUNT) + HOP_SAMPLES)
    private var writeIndex = 0
    private var samplesSeen = 0L
    private var nextWindowEndSample = WINDOW_SAMPLES.toLong()
    private var wasListening = false

    private var interpreter: Interpreter? = null
    @Volatile private var efficientAtInterpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var efficientAtLabels: List<String> = emptyList()
    private var efficientAtYamnetIndices = IntArray(EFFICIENTAT_CLASS_COUNT) { -1 }
    private var childClassIndices: Array<IntArray> = emptyArray()
    private val smoothedScores = FloatArray(CLASS_COUNT)
    private var hasSmoothedScores = false
    private val efficientAtSmoothedScores = FloatArray(EFFICIENTAT_CLASS_COUNT)
    private var hasEfficientAtSmoothedScores = false
    private var efficientAtSlowRunCount = 0
    @Volatile private var nextEfficientAtSample = 0L
    private var efficientAtLatestPrediction: EfficientAtPrediction? = null
    private val efficientAtInputBuffer = ByteBuffer
        .allocateDirect(EFFICIENTAT_MEL_COUNT * EFFICIENTAT_FRAME_COUNT * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val efficientAtOutput = Array(1) { FloatArray(EFFICIENTAT_CLASS_COUNT) }
    private val efficientAtMelBasis = FloatArray(EFFICIENTAT_MEL_COUNT * (EFFICIENTAT_FFT_SIZE / 2 + 1))
    private val efficientAtLogMel = FloatArray(EFFICIENTAT_MEL_COUNT * EFFICIENTAT_FRAME_COUNT)
    private val efficientAtHann = FloatArray(EFFICIENTAT_WINDOW_SIZE) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (EFFICIENTAT_WINDOW_SIZE - 1))).toFloat()
    }
    private val efficientAtReal = DoubleArray(EFFICIENTAT_FFT_SIZE)
    private val efficientAtImaginary = DoubleArray(EFFICIENTAT_FFT_SIZE)
    private val efficientAtResampleWindow = createEfficientAtResampleWindow()
    private val personalSoundLock = Any()
    private val personalSounds = loadPersonalSounds()
    private var personalCaptureGeneration = 0
    @Volatile private var personalSoundCapturedListener: ((FloatArray, ShortArray) -> Unit)? = null
    private var personalCapturePcm = ShortArray(0)
    private var personalCapturePcmCount = 0
    private var personalCapturePcmActive = false
    private val secondsByLabel = linkedMapOf<String, Double>()
    private val timelineLabels = mutableListOf<String>()
    private var analyzedSeconds = 0.0
    private var lastClassifiedAudioEndSample = 0L
    @Volatile private var listeningStartedAtMs = 0L
    private val filter = createDecimatorFilter()

    @Synchronized
    fun setListening(value: Boolean) {
        if (value) {
            if (listening) return
            listening = true
            listeningStartedAtMs = SystemClock.elapsedRealtime()
            writeIndex = 0
            samplesSeen = 0L
            nextWindowEndSample = WINDOW_SAMPLES.toLong()
            wasListening = false
            inputRing.fill(0)
            onResult(SoundIdResult("LOADING MODEL", "Waiting for the first sound…"))
            worker.execute {
                secondsByLabel.clear()
                timelineLabels.clear()
                analyzedSeconds = 0.0
                lastClassifiedAudioEndSample = 0L
                smoothedScores.fill(0f)
                hasSmoothedScores = false
                efficientAtSmoothedScores.fill(0f)
                hasEfficientAtSmoothedScores = false
                nextEfficientAtSample = 0L
                efficientAtLatestPrediction = null
                if (loadStarted.compareAndSet(false, true)) loadModel()
                else if (ready) onResult(SoundIdResult("LISTENING", "Listening for sounds…"))
            }
            return
        }
        if (!listening) return
        listening = false
        val startedAt = listeningStartedAtMs
        val elapsedSeconds = if (startedAt > 0L) {
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) / 1000.0
        } else {
            0.0
        }
        listeningStartedAtMs = 0L
        worker.execute {
            onResult(SoundIdResult("SESSION COMPLETE", "", sessionSummary = createSessionSummary(elapsedSeconds)))
        }
    }

    fun getPersonalSounds(): List<PersonalSoundProfile> = synchronized(personalSoundLock) {
        personalSounds.map { PersonalSoundProfile(it.id, it.name, it.embedding.copyOf()) }
    }

    fun suggestPersonalSoundName(): String = synchronized(personalSoundLock) {
        var index = 1
        while (personalSounds.any { it.name.equals("My Sound $index", ignoreCase = true) }) index++
        "My Sound $index"
    }

    fun renamePersonalSounds(existingName: String, newName: String): Set<String> = synchronized(personalSoundLock) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return@synchronized emptySet()
        val matching = personalSounds.filter { it.name.equals(existingName.trim(), ignoreCase = true) }
        if (matching.isEmpty()) return@synchronized emptySet()
        val ids = matching.mapTo(linkedSetOf<String>()) { it.id }
        for (index in personalSounds.indices) {
            if (personalSounds[index].id in ids) personalSounds[index] = personalSounds[index].copy(name = cleanName)
        }
        persistPersonalSoundsLocked()
        ids
    }

    fun removePersonalSounds(name: String): Set<String> = synchronized(personalSoundLock) {
        val matchingIds = personalSounds.filter { it.name.equals(name.trim(), ignoreCase = true) }
            .mapTo(linkedSetOf<String>()) { it.id }
        if (matchingIds.isEmpty()) return@synchronized emptySet()
        personalSounds.removeAll { profile ->
            if (profile.id in matchingIds) {
                File(context.filesDir, "personal_sound_samples/${profile.id}.pcm").delete()
                true
            } else false
        }
        persistPersonalSoundsLocked()
        matchingIds
    }

    fun beginPersonalSoundCapture(durationSeconds: Int = PERSONAL_SOUND_SAMPLE_SECONDS) {
        val sampleDurationSeconds = durationSeconds.coerceIn(5, 10)
        synchronized(personalSoundLock) {
            personalCaptureGeneration++
            personalCapturePcm = ShortArray(MODEL_RATE * 3 * sampleDurationSeconds)
            personalCapturePcmCount = 0
            personalCapturePcmActive = true
        }
    }

    fun cancelPersonalSoundCapture() {
        synchronized(personalSoundLock) {
            personalCaptureGeneration++
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

    fun createPersonalSoundFromAudio(
        name: String,
        source48k: ShortArray,
        onComplete: (String?) -> Unit
    ) {
        worker.execute {
            val failure = try {
                val cleanName = name.trim().take(80)
                require(cleanName.isNotEmpty()) { "The sample needs a name." }
                val averaged = buildPersonalEmbeddingFromAudio(source48k)
                savePersonalSound(cleanName, averaged, source48k)
                null
            } catch (error: Exception) {
                error.message ?: "Could not create a profile from this WAV."
            }
            onComplete(failure)
        }
    }

    private fun buildPersonalEmbeddingFromAudio(source48k: ShortArray): FloatArray {
        require(source48k.size in WINDOW_SAMPLES..(48_000 * 30)) {
            "Audio must contain between 1 and 30 seconds of sound."
        }
        if (!ready) {
            loadStarted.set(true)
            loadModel(announceState = false)
        }
        check(ready) { modelLoadError ?: "The sound model could not be loaded." }
        val model = interpreter ?: error("The sound model is not ready.")
        val windowCount = countCompleteAudioWindows(source48k.size, WINDOW_SAMPLES, HOP_SAMPLES)
        val embeddingSum = DoubleArray(PERSONAL_FEATURE_COUNT)
        var usableWindows = 0
        for (windowIndex in 0 until windowCount) {
            val start = windowIndex * HOP_SAMPLES
            val frame = source48k.copyOfRange(start, start + WINDOW_SAMPLES)
            val audio16k = resample48kTo16k(frame)
            val rms = sqrt(audio16k.sumOf { it.toDouble() * it.toDouble() } / audio16k.size)
            val rmsDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
            if (rmsDb < QUIET_DBFS) continue
            val output = Array(1) { FloatArray(CLASS_COUNT) }
            model.run(arrayOf(audio16k), output)
            val embedding = createPersonalEmbedding(output[0], audio16k)
            for (index in embedding.indices) embeddingSum[index] += embedding[index]
            usableWindows++
        }
        require(usableWindows > 0) { "No clear sound was found in this audio." }
        return FloatArray(PERSONAL_FEATURE_COUNT) { index ->
            (embeddingSum[index] / usableWindows).toFloat()
        }
    }

    @Synchronized
    fun offerAudio(samples: ShortArray, count: Int) {
        if (!listening) {
            if (wasListening) {
                writeIndex = 0
                samplesSeen = 0L
                nextWindowEndSample = WINDOW_SAMPLES.toLong()
                wasListening = false
            }
            return
        }
        if (!wasListening) {
            writeIndex = 0
            samplesSeen = 0L
            nextWindowEndSample = WINDOW_SAMPLES.toLong()
            wasListening = true
        }
        val completedCapture = synchronized(personalSoundLock) {
            if (personalCapturePcmActive && personalCapturePcmCount < personalCapturePcm.size) {
                val copyCount = minOf(count, personalCapturePcm.size - personalCapturePcmCount)
                samples.copyInto(personalCapturePcm, personalCapturePcmCount, 0, copyCount)
                personalCapturePcmCount += copyCount
                if (personalCapturePcmCount >= personalCapturePcm.size) {
                    personalCapturePcmActive = false
                    val completedPcm = personalCapturePcm
                    personalCapturePcm = ShortArray(0)
                    personalCapturePcmCount = 0
                    personalCaptureGeneration to completedPcm
                } else null
            } else null
        }
        completedCapture?.let { (generation, capturedPcm) ->
            worker.execute {
                val embedding = try {
                    buildPersonalEmbeddingFromAudio(capturedPcm)
                } catch (_: Exception) {
                    null
                }
                val stillCurrent = synchronized(personalSoundLock) {
                    generation == personalCaptureGeneration
                }
                if (stillCurrent && embedding != null) {
                    personalSoundCapturedListener?.invoke(embedding, capturedPcm)
                }
            }
        }
        for (i in 0 until count) {
            inputRing[writeIndex] = samples[i]
            writeIndex = (writeIndex + 1) % inputRing.size
        }
        samplesSeen += count
        if (samplesSeen < nextWindowEndSample) return

        // Keep the model cadence locked to the 48 kHz sample clock instead of
        // restarting the hop timer at each AudioRecord block boundary. If a
        // block spans multiple deadlines, use only the newest due window.
        val elapsedHops = (samplesSeen - nextWindowEndSample) / HOP_SAMPLES
        val frameEndSample = nextWindowEndSample + elapsedHops * HOP_SAMPLES
        nextWindowEndSample = frameEndSample + HOP_SAMPLES
        if (!ready || !inferencePending.compareAndSet(false, true)) return

        val frame = ShortArray(WINDOW_SAMPLES)
        val samplesAfterFrame = (samplesSeen - frameEndSample).toInt()
        val frameEndIndex = (writeIndex - samplesAfterFrame + inputRing.size) % inputRing.size
        var index = (frameEndIndex - WINDOW_SAMPLES + inputRing.size) % inputRing.size
        for (i in frame.indices) {
            frame[i] = inputRing[index]
            index = (index + 1) % inputRing.size
        }
        val efficientAtFrame = if (
            efficientAtInterpreter != null &&
            frameEndSample >= EFFICIENTAT_SOURCE_SAMPLE_COUNT &&
            frameEndSample >= nextEfficientAtSample
        ) {
            ShortArray(EFFICIENTAT_SOURCE_SAMPLE_COUNT).also { longFrame ->
                var longIndex = (frameEndIndex - longFrame.size + inputRing.size) % inputRing.size
                for (i in longFrame.indices) {
                    longFrame[i] = inputRing[longIndex]
                    longIndex = (longIndex + 1) % inputRing.size
                }
            }
        } else null
        worker.execute {
            try {
                classify(frame, efficientAtFrame, frameEndSample)
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

    private fun loadModel(announceState: Boolean = true) {
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
            loadEfficientAtModel(classIndexById)
            ready = true
            modelLoadError = null
            if (announceState) onResult(SoundIdResult("LISTENING", "Listening for sounds…"))
        } catch (error: Exception) {
            ready = false
            modelLoadError = error.message ?: "Check that the sound model is included in the app."
            if (announceState) onResult(SoundIdResult("MODEL COULD NOT LOAD", modelLoadError!!))
        }
    }

    private fun loadEfficientAtModel(yamnetIndexById: Map<String, Int>) {
        var loaded: Interpreter? = null
        try {
            val efficientClassRows = context.assets.open("efficientat_class_map.csv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("index,")) null
                    else {
                        val fields = trimmed.split(',', limit = 3)
                        if (fields.size < 3) null else {
                            fields[1].trim() to fields[2].trim().removeSurrounding("\"")
                        }
                    }
                }.toList()
            }
            require(efficientClassRows.size == EFFICIENTAT_CLASS_COUNT) {
                "Expected 527 EfficientAT labels, found ${efficientClassRows.size}"
            }
            val melBytes = context.assets.open("efficientat_mel_basis.f32").use { it.readBytes() }
            require(melBytes.size == efficientAtMelBasis.size * Float.SIZE_BYTES) {
                "EfficientAT mel filter bank has an invalid size."
            }
            ByteBuffer.wrap(melBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(efficientAtMelBasis)

            val modelBytes = context.assets.open("efficientat_mn04_as.tflite").use { it.readBytes() }
            require(modelBytes.isNotEmpty()) { "efficientat_mn04_as.tflite is empty" }
            val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            model.put(modelBytes)
            model.rewind()
            val options = Interpreter.Options().setNumThreads(2)
            val modelInterpreter = Interpreter(model, options)
            loaded = modelInterpreter
            require(modelInterpreter.getInputTensor(0).shape().contentEquals(
                intArrayOf(1, EFFICIENTAT_MEL_COUNT, EFFICIENTAT_FRAME_COUNT, 1)
            )) {
                "EfficientAT input shape is not supported by this app."
            }
            require(modelInterpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, EFFICIENTAT_CLASS_COUNT))) {
                "EfficientAT output shape is not supported by this app."
            }
            efficientAtLabels = efficientClassRows.map { it.second }
            efficientAtYamnetIndices = efficientClassRows.map { row -> yamnetIndexById[row.first] ?: -1 }.toIntArray()
            efficientAtInterpreter = modelInterpreter
            efficientAtSlowRunCount = 0
            Log.i("SoundId", "EfficientAT mn04_as loaded (${efficientAtLabels.size} classes)")
        } catch (error: OutOfMemoryError) {
            disableEfficientAt("EfficientAT ran out of memory; continuing with YAMNet only", loaded, error)
        } catch (error: Exception) {
            disableEfficientAt("EfficientAT unavailable; continuing with YAMNet only", loaded, error)
        }
    }

    private fun disableEfficientAt(reason: String, model: Interpreter?, error: Throwable? = null) {
        try { model?.close() } catch (_: Exception) { }
        efficientAtInterpreter = null
        efficientAtLabels = emptyList()
        efficientAtLatestPrediction = null
        efficientAtSmoothedScores.fill(0f)
        hasEfficientAtSmoothedScores = false
        efficientAtSlowRunCount = 0
        nextEfficientAtSample = Long.MAX_VALUE
        if (error == null) Log.w("SoundId", reason) else Log.w("SoundId", reason, error)
    }

    private fun classify(source48k: ShortArray, efficientAtSource48k: ShortArray?, frameEndSample: Long) {
        val audio16k = resample48kTo16k(source48k)
        val rms = sqrt(audio16k.sumOf { it.toDouble() * it.toDouble() } / audio16k.size)
        val rmsDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
        if (rmsDb < QUIET_DBFS) {
            efficientAtLatestPrediction = null
            nextEfficientAtSample = 0L
            efficientAtSmoothedScores.fill(0f)
            hasEfficientAtSmoothedScores = false
            recordWinner("Unclear / quiet", frameEndSample)
            onResult(SoundIdResult("LISTENING", "No clear sound", 0))
            return
        }

        val output = Array(1) { FloatArray(CLASS_COUNT) }
        interpreter?.run(arrayOf(audio16k), output)
            ?: error("The YAMNet interpreter is not ready")
        val scores = output[0]
        val needsPersonalEmbedding = synchronized(personalSoundLock) { personalSounds.isNotEmpty() }
        val personalEmbedding = if (needsPersonalEmbedding) createPersonalEmbedding(scores, audio16k) else null
        if (!hasSmoothedScores) {
            scores.copyInto(smoothedScores)
            hasSmoothedScores = true
        } else {
            for (i in scores.indices) smoothedScores[i] = smoothedScores[i] * 0.55f + scores[i] * 0.45f
        }
        val efficientPrediction = runEfficientAtIfDue(efficientAtSource48k, frameEndSample)
        val ranked = smoothedScores.indices
            .asSequence()
            .filter { smoothedScores[it] >= 0.05f }
            .map { mostSpecificSupportedClass(it, smoothedScores) }
            .distinct()
            .sortedByDescending { smoothedScores[it] }
            .take(3)
            .toList()
        val personalMatch = personalEmbedding?.let(::findPersonalSoundMatch)
        val customCandidate = personalMatch?.takeIf { it.second >= PERSONAL_SOUND_MATCH_THRESHOLD }?.let { (profile, similarity) ->
            SoundCandidate(profile.name, (similarity * 100f).roundToInt())
        }
        val best = ranked.firstOrNull() ?: -1
        if (best < 0 || smoothedScores[best] < MIN_MODEL_SCORE) {
            if (customCandidate != null) {
                recordWinner(customCandidate.label, frameEndSample)
                onResult(SoundIdResult(
                    "LISTENING · SAVED SOUND MATCH",
                    customCandidate.label,
                    customCandidate.scorePercent,
                    "Similarity to your saved sample",
                    isPersonalMatch = true
                ))
            } else if (efficientPrediction != null) {
                recordWinner(efficientPrediction.label, frameEndSample)
                onResult(SoundIdResult(
                    "LISTENING · EFFICIENTAT MATCH",
                    efficientPrediction.label,
                    efficientPrediction.scorePercent,
                    "EfficientAT second-model match"
                ))
            } else {
                recordWinner("Unclear / mixed", frameEndSample)
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
                recordWinner(customCandidate.label, frameEndSample)
                onResult(SoundIdResult("LISTENING · SAVED SOUND MATCH", customCandidate.label,
                    customCandidate.scorePercent, "Similarity to your saved sample", isPersonalMatch = true))
            } else {
                recordWinner("Unclear / mixed", frameEndSample)
                onResult(SoundIdResult("LISTENING", "No clear sound", 0))
            }
            return
        }
        val yamnetBestCandidate = candidates.first()
        val efficientRefinement = efficientPrediction?.takeIf { prediction ->
            prediction.scorePercent / 100f >= MIN_EFFICIENTAT_SCORE &&
                prediction.yamnetClassIndex >= 0 &&
                isSupportedDescendant(prediction.yamnetClassIndex, best)
        }?.let { SoundCandidate(it.label, it.scorePercent) }
        val classifierCandidates = if (efficientRefinement != null) {
            listOf(efficientRefinement) + candidates.filterNot { it.label == yamnetBestCandidate.label }.take(2)
        } else candidates
        val allCandidates = (listOfNotNull(customCandidate) + classifierCandidates).distinctBy { it.label }.take(3)
        recordWindow(allCandidates, frameEndSample)
        val label = allCandidates.first().label
        val alternatives = allCandidates.drop(1).takeIf { it.isNotEmpty() }?.let { others ->
            "Also possible: " + others.joinToString("   ·   ") { "${it.label} ${it.scorePercent}%" }
        }.orEmpty()
        val displayedScore = when {
            customCandidate != null -> customCandidate.scorePercent
            efficientRefinement != null -> efficientRefinement.scorePercent
            else -> (bestScore * 100f).roundToInt()
        }
        val status = when {
            customCandidate != null -> "LISTENING · SAVED SOUND MATCH"
            efficientRefinement != null -> "LISTENING · MORE SPECIFIC MATCH"
            efficientPrediction?.label == yamnetBestCandidate.label -> "LISTENING · TWO MODELS AGREE"
            displayedScore >= 35 -> "LISTENING · CLEAR MATCH"
            else -> "LISTENING · LOW MATCH"
        }
        val personalNote = personalMatch?.takeIf { it.second >= PERSONAL_SOUND_NOTE_THRESHOLD }?.let { (profile, similarity) ->
            "Nearest saved sample: ${profile.name} · ${(similarity * 100f).roundToInt()}% similarity"
        }.orEmpty()
        val modelCrossCheck = when {
            customCandidate != null -> null
            efficientRefinement != null -> "YAMNet: ${yamnetBestCandidate.label} ${yamnetBestCandidate.scorePercent}%"
            efficientPrediction != null && efficientPrediction.label != label ->
                "EfficientAT: ${efficientPrediction.label} ${efficientPrediction.scorePercent}%"
            else -> null
        }.orEmpty()
        val fullAlternatives = listOf(personalNote, modelCrossCheck, alternatives)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        onResult(SoundIdResult(status, label, displayedScore, fullAlternatives, isPersonalMatch = customCandidate != null))
    }

    private fun runEfficientAtIfDue(source48k: ShortArray?, frameEndSample: Long): EfficientAtPrediction? {
        val model = efficientAtInterpreter ?: return null
        if (source48k == null) return efficientAtLatestPrediction
        if (frameEndSample < nextEfficientAtSample) return efficientAtLatestPrediction
        nextEfficientAtSample = frameEndSample + EFFICIENTAT_INTERVAL_SAMPLES
        val startedAtMs = SystemClock.elapsedRealtime()
        return try {
            val audio32k = resample48kTo32k(source48k)
            fillEfficientAtLogMel(audio32k)
            efficientAtInputBuffer.clear()
            for (value in efficientAtLogMel) efficientAtInputBuffer.putFloat(value)
            efficientAtInputBuffer.rewind()
            model.run(efficientAtInputBuffer, efficientAtOutput)

            val scores = efficientAtOutput[0]
            if (!hasEfficientAtSmoothedScores) {
                scores.copyInto(efficientAtSmoothedScores)
                hasEfficientAtSmoothedScores = true
            } else {
                for (index in scores.indices) {
                    efficientAtSmoothedScores[index] =
                        efficientAtSmoothedScores[index] * 0.55f + scores[index] * 0.45f
                }
            }
            val bestIndex = efficientAtSmoothedScores.indices.maxByOrNull { efficientAtSmoothedScores[it] } ?: -1
            val bestScore = efficientAtSmoothedScores.getOrElse(bestIndex) { 0f }.coerceIn(0f, 1f)
            efficientAtLatestPrediction = if (bestIndex >= 0 && bestScore >= MIN_EFFICIENTAT_SCORE) {
                EfficientAtPrediction(
                    efficientAtLabels.getOrElse(bestIndex) { "Unknown sound" },
                    (bestScore * 100f).roundToInt(),
                    efficientAtYamnetIndices.getOrElse(bestIndex) { -1 }
                )
            } else null
            if (SystemClock.elapsedRealtime() - startedAtMs > EFFICIENTAT_MAX_LATENCY_MS) {
                efficientAtSlowRunCount++
                if (efficientAtSlowRunCount >= 2) {
                    disableEfficientAt(
                        "EfficientAT is too slow on this device; continuing with YAMNet only",
                        model
                    )
                    return null
                }
            } else {
                efficientAtSlowRunCount = 0
            }
            efficientAtLatestPrediction
        } catch (error: OutOfMemoryError) {
            disableEfficientAt("EfficientAT ran out of memory during inference; continuing with YAMNet only", model, error)
            null
        } catch (error: Exception) {
            disableEfficientAt("EfficientAT inference failed; YAMNet remains active", model, error)
            null
        }
    }

    private fun fillEfficientAtLogMel(audio32k: FloatArray) {
        require(audio32k.size == EFFICIENTAT_SAMPLE_COUNT) { "Unexpected EfficientAT audio window size." }
        val preEmphasis = FloatArray(audio32k.size - 1)
        for (index in preEmphasis.indices) {
            preEmphasis[index] = audio32k[index + 1] - 0.97f * audio32k[index]
        }
        val expectedFrames = preEmphasis.size / EFFICIENTAT_HOP_SIZE + 1
        require(expectedFrames == EFFICIENTAT_FRAME_COUNT) { "Unexpected EfficientAT spectrogram length." }
        val fftOffset = (EFFICIENTAT_FFT_SIZE - EFFICIENTAT_WINDOW_SIZE) / 2
        val firstWindowSample = EFFICIENTAT_FFT_SIZE / 2 - fftOffset
        val melStride = EFFICIENTAT_FFT_SIZE / 2 + 1

        for (frame in 0 until EFFICIENTAT_FRAME_COUNT) {
            efficientAtReal.fill(0.0)
            efficientAtImaginary.fill(0.0)
            val center = frame * EFFICIENTAT_HOP_SIZE
            for (windowIndex in 0 until EFFICIENTAT_WINDOW_SIZE) {
                val sampleIndex = reflectAudioIndex(center - firstWindowSample + windowIndex, preEmphasis.size)
                efficientAtReal[fftOffset + windowIndex] =
                    preEmphasis[sampleIndex].toDouble() * efficientAtHann[windowIndex]
            }
            fft(efficientAtReal, efficientAtImaginary)

            for (mel in 0 until EFFICIENTAT_MEL_COUNT) {
                val melBase = mel * melStride
                var melPower = 0.0
                for (bin in 0 until melStride) {
                    val real = efficientAtReal[bin]
                    val imaginary = efficientAtImaginary[bin]
                    val power = real * real + imaginary * imaginary
                    melPower += efficientAtMelBasis[melBase + bin] * power
                }
                efficientAtLogMel[mel * EFFICIENTAT_FRAME_COUNT + frame] =
                    ((ln(melPower + 1e-5) + 4.5) / 5.0).toFloat()
            }
        }
    }

    private fun reflectAudioIndex(index: Int, size: Int): Int {
        if (size <= 1) return 0
        var reflected = index
        while (reflected < 0 || reflected >= size) {
            reflected = if (reflected < 0) -reflected else 2 * size - 2 - reflected
        }
        return reflected
    }

    private fun isSupportedDescendant(candidateIndex: Int, ancestorIndex: Int): Boolean {
        if (candidateIndex == ancestorIndex) return false
        val visited = BooleanArray(CLASS_COUNT)
        val pending = ArrayDeque<Int>()
        childClassIndices.getOrNull(ancestorIndex)?.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current == candidateIndex) return true
            if (current !in visited.indices || visited[current]) continue
            visited[current] = true
            childClassIndices.getOrNull(current)?.forEach(pending::addLast)
        }
        return false
    }

    private fun resample48kTo32k(source: ShortArray): FloatArray {
        require(source.size == EFFICIENTAT_SOURCE_SAMPLE_COUNT) { "Unexpected EfficientAT source window size." }
        val output = FloatArray(EFFICIENTAT_SAMPLE_COUNT)
        val middle = efficientAtResampleWindow.size / 2
        for (outputIndex in output.indices) {
            val sourcePosition = outputIndex * 1.5
            val center = floor(sourcePosition).toInt()
            val fraction = sourcePosition - center
            var sum = 0.0
            var weightSum = 0.0
            for (tap in efficientAtResampleWindow.indices) {
                val offset = tap - middle - fraction
                val coefficient = (2.0 / 3.0) * sincResample(2.0 / 3.0 * offset) * efficientAtResampleWindow[tap]
                val inputIndex = center + tap - middle
                val reflectedIndex = reflectAudioIndex(inputIndex, source.size)
                sum += source[reflectedIndex].toDouble() / 32768.0 * coefficient
                weightSum += coefficient
            }
            output[outputIndex] = if (abs(weightSum) > 1e-12) (sum / weightSum).toFloat() else 0f
        }
        return output
    }

    private fun sincResample(value: Double): Double =
        if (abs(value) < 1e-12) 1.0 else sin(PI * value) / (PI * value)

    private fun createEfficientAtResampleWindow(): DoubleArray {
        val taps = 63
        return DoubleArray(taps) { tap ->
            0.42 - 0.5 * cos(2.0 * PI * tap / (taps - 1)) +
                0.08 * cos(4.0 * PI * tap / (taps - 1))
        }
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

    private fun recordWinner(label: String, frameEndSample: Long) =
        recordWindow(listOf(SoundCandidate(label, 100)), frameEndSample)

    private fun recordWindow(candidates: List<SoundCandidate>, frameEndSample: Long) {
        val newlyCoveredSamples = newlyCoveredAudioSamples(
            frameEndSample,
            WINDOW_SAMPLES,
            lastClassifiedAudioEndSample
        )
        lastClassifiedAudioEndSample = maxOf(lastClassifiedAudioEndSample, frameEndSample)
        val seconds = newlyCoveredSamples / 48_000.0
        analyzedSeconds += seconds
        val uniqueCandidates = candidates.distinctBy { it.label }
        if (uniqueCandidates.isEmpty()) return
        uniqueCandidates.maxByOrNull { it.scorePercent }?.label?.let(timelineLabels::add)
        val scoreTotal = uniqueCandidates.sumOf { it.scorePercent.coerceAtLeast(0) }.takeIf { it > 0 }
        uniqueCandidates.forEach { candidate ->
            val share = if (scoreTotal == null) 1.0 / uniqueCandidates.size else candidate.scorePercent.coerceAtLeast(0).toDouble() / scoreTotal
            secondsByLabel[candidate.label] = (secondsByLabel[candidate.label] ?: 0.0) + seconds * share
        }
    }

    private fun createSessionSummary(sessionDurationSeconds: Double): SoundSessionSummary {
        val totalSeconds = maxOf(analyzedSeconds, sessionDurationSeconds).coerceAtLeast(0.0)
        if (totalSeconds <= 0.0) return SoundSessionSummary(0.0, emptyList(), emptyList())

        val timeByLabel = LinkedHashMap(secondsByLabel)
        val unclassifiedSeconds = (totalSeconds - analyzedSeconds).coerceAtLeast(0.0)
        if (unclassifiedSeconds > 0.01) {
            timeByLabel["Unclear / unclassified"] = unclassifiedSeconds
        }
        val ranked = timeByLabel.entries.sortedByDescending { it.value }
        val selected = ranked.take(5).toMutableList()
        val unclassifiedEntry = ranked.firstOrNull { it.key == "Unclear / unclassified" }
        if (unclassifiedEntry != null && selected.none { it.key == unclassifiedEntry.key }) {
            if (selected.size == 5) selected.removeAt(selected.lastIndex)
            selected.add(unclassifiedEntry)
        }
        val entries = selected.map { (label, seconds) ->
            SoundSessionEntry(label, seconds, seconds / totalSeconds * 100.0)
        }
        return SoundSessionSummary(totalSeconds, entries, timelineLabels.toList())
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
            try { efficientAtInterpreter?.close() } catch (_: Exception) { }
            efficientAtInterpreter = null
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
