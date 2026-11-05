package sk.spectrum.analyzer2

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Piano sample decoding, voices, playback engine and reverb. */
private const val PIANO_LOAD_TAG = "SpectrumPianoLoad"

internal class PianoSample(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int
)

internal data class PianoVoice(
    val note: Int,
    val sample: PianoSample,
    var position: Double = 0.0,
    var volume: Float,
    val pitchRatio: Double = 1.0,
    var releaseRemainingFrames: Int = 0,
    var releaseTotalFrames: Int = 0
)

internal class PianoReverbProcessor(private val sampleRate: Int, private val stereoOffset: Int = 0) {
    private class CombFilter(size: Int) {
        private val buffer = FloatArray(size.coerceAtLeast(1))
        private var index = 0
        private var filtered = 0f

        fun process(input: Float): Float {
            val output = buffer[index]
            filtered = output * 0.78f + filtered * 0.22f
            buffer[index] = input + filtered * 0.72f
            index = (index + 1) % buffer.size
            return output
        }
    }

    private class AllPassFilter(size: Int) {
        private val buffer = FloatArray(size.coerceAtLeast(1))
        private var index = 0

        fun process(input: Float): Float {
            val delayed = buffer[index]
            val output = delayed - input
            buffer[index] = input + delayed * 0.5f
            index = (index + 1) % buffer.size
            return output
        }
    }

    private fun scaledSize(baseSize: Int): Int =
        (baseSize * sampleRate / 44100f).roundToInt() + stereoOffset

    private val combFilters = intArrayOf(1116, 1188, 1277, 1356)
        .map { CombFilter(scaledSize(it)) }
    private val allPassFilters = intArrayOf(556, 441)
        .map { AllPassFilter(scaledSize(it)) }

    fun process(input: Float): Float {
        var output = 0f
        for (filter in combFilters) output += filter.process(input)
        output *= 0.25f
        for (filter in allPassFilters) output = filter.process(output)
        return output
    }
}

/** Decode the middle octave first, then the remaining OGG samples off the UI thread. */
internal fun MainActivity.initializePianoSamples() {
    if (pianoSamplesLoading || pianoSamplesInitialized) return
    pianoSamplesInitialized = true
    pianoSamplesLoading = true
    val names = arrayOf("c", "cs", "d", "ds", "e", "f", "fs", "g", "gs", "a", "as", "b")
    // Decode the middle octave first, where most users begin playing, then fill
    // outward. A few independent decoders make common keys available sooner.
    val firstNotes = (60..71).toList()
    val noteOrder = firstNotes + (36..96).filterNot { it in firstNotes }.sortedBy { abs(it - 65) }
    val nextNote = AtomicInteger(0)
    val missing = AtomicInteger(0)
    val workersRemaining = AtomicInteger(2)
    repeat(2) { workerIndex ->
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
                        pianoHeldNotes.filter { it.coerceIn(36, 96) == note }
                            .forEach(this@initializePianoSamples::startPianoSample)
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

internal fun MainActivity.startPianoAudioEngine() {
    if (pianoEngineRunning || pianoAudioTrack?.state == AudioTrack.STATE_INITIALIZED) return
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
    applyPreferredOutputRoute(track)
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
                        voice.position += sample.sampleRate.toDouble() / pianoOutputRate * voice.pitchRatio
                    }
                    if (voice.releaseTotalFrames > 0 && voice.releaseRemainingFrames <= 0) iterator.remove()
                }
            }
            val reverbEnabled = pianoReverbEnabled
            for (frame in 0 until pianoBlockFrames) {
                val outIndex = frame * 2
                val dryLeft = output[outIndex].toFloat() / 32768f
                val dryRight = output[outIndex + 1].toFloat() / 32768f
                val wetLeft = pianoReverbLeft.process(if (reverbEnabled) dryLeft else 0f)
                val wetRight = pianoReverbRight.process(if (reverbEnabled) dryRight else 0f)
                if (reverbEnabled) {
                    output[outIndex] = ((dryLeft + wetLeft * 0.22f) * 32767f)
                        .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    output[outIndex + 1] = ((dryRight + wetRight * 0.22f) * 32767f)
                        .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            val written = track.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0 && pianoEngineRunning) Log.e(PIANO_LOAD_TAG, "AudioTrack write failed: $written")
        }
    }, "PianoAudioEngine").apply { start() }
}

internal fun MainActivity.schedulePianoAudioShutdown() {
    pianoAudioShutdownHandler.removeCallbacks(pianoAudioShutdownRunnable)
    pianoAudioShutdownHandler.postDelayed(pianoAudioShutdownRunnable, 12_000L)
}

internal fun MainActivity.stopPianoAudioEngine() {
    pianoAudioShutdownHandler.removeCallbacks(pianoAudioShutdownRunnable)
    pianoEngineRunning = false
    val track = pianoAudioTrack
    try { track?.pause() } catch (_: Exception) {}
    try { track?.stop() } catch (_: Exception) {}
    try { pianoAudioThread?.join(350) } catch (_: InterruptedException) {}
    pianoAudioThread = null
    try { track?.flush() } catch (_: Exception) {}
    try { track?.release() } catch (_: Exception) {}
    pianoAudioTrack = null
    synchronized(pianoAudioLock) { pianoVoices.clear() }
    pianoHeldNotes.clear()
    pianoPressedTargets.clear()
}

/** Decode an OGG resource into interleaved PCM16 without blocking the UI thread. */
internal fun MainActivity.decodePianoOgg(resourceId: Int): PianoSample {
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

internal fun MainActivity.playPianoSample(midiNote: Int) {
    pianoAudioShutdownHandler.removeCallbacks(pianoAudioShutdownRunnable)
    initializePianoSamples()
    startPianoAudioEngine()
    val playedNote = midiNote + pianoTranspose
    pianoPressedTargets[midiNote] = playedNote
    pianoHeldNotes.add(playedNote)
    startPianoSample(playedNote)
}

internal fun MainActivity.startPianoSample(playedNote: Int) {
    val sourceNote = playedNote.coerceIn(36, 96)
    val sample = pianoSamples[sourceNote] ?: return
    val pitchRatio = 2.0.pow((playedNote - sourceNote) / 12.0)
    synchronized(pianoAudioLock) {
        // Retrigger this note without creating an unbounded number of voices.
        pianoVoices.removeAll { it.note == playedNote }
        pianoVoices.add(PianoVoice(playedNote, sample, volume = pianoVolume, pitchRatio = pitchRatio))
    }
}

internal fun MainActivity.releasePianoSample(midiNote: Int) {
    val playedNote = pianoPressedTargets.remove(midiNote) ?: (midiNote + pianoTranspose)
    pianoHeldNotes.remove(playedNote)
    if (pianoReverbEnabled) {
        schedulePianoAudioShutdown()
        return
    }
    synchronized(pianoAudioLock) {
        for (voice in pianoVoices) {
            if (voice.note == playedNote && voice.releaseTotalFrames == 0) {
                voice.releaseTotalFrames = (pianoOutputRate * 0.42f).toInt()
                voice.releaseRemainingFrames = voice.releaseTotalFrames
            }
        }
    }
    schedulePianoAudioShutdown()
}
