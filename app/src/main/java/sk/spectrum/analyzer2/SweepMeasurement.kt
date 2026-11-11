package sk.spectrum.analyzer2

import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class SweepResponsePoint(val frequencyHz: Double, val splDb: Double)

internal data class SweepMeasurementResult(
    val points: List<SweepResponsePoint>,
    val durationSeconds: Int,
    val sampleCount: Int,
    val qualityPercent: Int,
    val startFrequencyHz: Int,
    val endFrequencyHz: Int,
    val logarithmic: Boolean,
    val ascending: Boolean,
    val outputChannel: String
)

internal data class SweepMeasurementExport(
    val fileNameStem: String,
    val csv: ByteArray,
    val pdf: ByteArray
)

/** Bounded PCM collector fed by the analyzer's existing AudioRecord thread. */
internal class SweepMeasurementCapture(sampleRate: Int, durationMs: Long) {
    private val samples = ShortArray(
        ((durationMs + 2_500L) * sampleRate / 1_000L).coerceIn(sampleRate.toLong(), sampleRate * 36L).toInt()
    )
    private var count = 0

    @Synchronized
    fun append(source: ShortArray, sourceCount: Int) {
        val copyCount = sourceCount.coerceAtMost(samples.size - count)
        if (copyCount <= 0) return
        source.copyInto(samples, count, 0, copyCount)
        count += copyCount
    }

    @Synchronized
    fun snapshot(): ShortArray = samples.copyOf(count)
}

internal fun MainActivity.updateSweepMeasurementButton() {
    val button = toneMeasurementButton ?: return
    button.icon = if (toneMeasurementActive) AnalyzerIconButton.Icon.STOP else AnalyzerIconButton.Icon.MEASURE
    button.contentDescription = if (toneMeasurementActive) {
        "Stop sweep response measurement"
    } else {
        "Measure speaker and room response with a sweep"
    }
}

internal fun MainActivity.toggleSweepMeasurement() {
    if (toneMeasurementActive) {
        cancelSweepMeasurement(showMessage = false)
        Toast.makeText(this, "Sweep measurement cancelled.", Toast.LENGTH_SHORT).show()
        return
    }
    if (sweepMeasurementThread?.isAlive == true) {
        Toast.makeText(this, "Finishing the previous measurement…", Toast.LENGTH_SHORT).show()
        return
    }
    if (!running || recorder?.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) {
        Toast.makeText(this, "Start microphone analysis before measuring.", Toast.LENGTH_LONG).show()
        return
    }
    if (toneDtmfActive) {
        Toast.makeText(this, "Exit DTMF mode before measuring.", Toast.LENGTH_SHORT).show()
        return
    }
    if (toneVolume <= 0.01) {
        Toast.makeText(this, "Raise tone volume before measuring.", Toast.LENGTH_SHORT).show()
        return
    }

    setTonePeakFollowEnabled(false, refreshUi = false)
    if (tonePlaying) stopTone()
    val captureGeneration = audioCaptureGeneration
    val startHz = toneSweepStartFrequency.coerceIn(20, 19_999)
    val endHz = toneSweepEndFrequency.coerceIn(startHz + 1, 20_000)
    val durationMs = toneSweepDurationMs.coerceIn(4_000L, 32_000L)
    val logarithmic = toneSweepLogarithmic
    val ascending = toneSweepAscending
    val outputChannel = toneOutputChannel
    val outputDeviceId = preferredOutputDeviceId
    val level = toneVolume.coerceIn(0.01, 1.0)
    val micCalibrationOffsetDb = splCalibrationOffsetDb ?: genericSplEstimateOffsetDb
    val sessionId = ++sweepMeasurementSessionId

    toneMeasurementActive = true
    toneSweepUiUpdate?.invoke()
    updateSweepMeasurementButton()
    Toast.makeText(this, "Measuring speaker and room response…", Toast.LENGTH_SHORT).show()

    lateinit var worker: Thread
    worker = Thread({
        var track: AudioTrack? = null
        var failure: String? = null
        var result: SweepMeasurementResult? = null
        try {
            val waitStartedAt = SystemClock.elapsedRealtime()
            while (tonePlaying && toneMeasurementActive && SystemClock.elapsedRealtime() - waitStartedAt < 1_500L) {
                Thread.sleep(20L)
            }
            if (!toneMeasurementActive) return@Thread
            if (tonePlaying) throw IllegalStateException("Tone playback did not stop; try again.")
            if (!running || captureGeneration != audioCaptureGeneration) {
                throw IllegalStateException("Microphone input changed. Start analysis and try again.")
            }

            val capture = SweepMeasurementCapture(sampleRate, durationMs)
            sweepMeasurementCapture = capture
            val minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferBytes <= 0) throw IllegalStateException("Audio output is unavailable.")
            val createdTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(max(minBufferBytes, 8_192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (createdTrack.state != AudioTrack.STATE_INITIALIZED) {
                createdTrack.release()
                throw IllegalStateException("Audio output could not be initialized.")
            }
            track = createdTrack
            sweepMeasurementTrack = createdTrack
            if (!applyPreferredOutputRoute(createdTrack)) {
                throw IllegalStateException("Android could not select the chosen audio output.")
            }
            createdTrack.play()

            val framesPerBlock = 1_024
            val output = ShortArray(framesPerBlock * 2)
            val preview = ShortArray(framesPerBlock)
            val timestamp = android.media.AudioTimestamp()
            var lastClockCheck = 0L
            var phase = 0.0
            val sweepFrames = (durationMs * sampleRate / 1_000L).toInt()
            // Keep the exact digital excitation. Its spectrum is the reference
            // for calculating the speaker/room/microphone transfer response.
            val sweepReference = ShortArray(sweepFrames)
            fun writeFrames(frameCount: Int, isSweep: Boolean, firstSweepFrame: Long = 0L) {
                var frameOffset = 0
                while (frameOffset < frameCount) {
                    if (!toneMeasurementActive) throw InterruptedException("Measurement cancelled")
                    if (!running || captureGeneration != audioCaptureGeneration || outputDeviceId != preferredOutputDeviceId) {
                        throw IllegalStateException("Audio route changed during measurement. Try again.")
                    }
                    val blockFrames = min(framesPerBlock, frameCount - frameOffset)
                    if (!isSweep) {
                        java.util.Arrays.fill(output, 0, blockFrames * 2, 0.toShort())
                        java.util.Arrays.fill(preview, 0, blockFrames, 0.toShort())
                    } else {
                        for (i in 0 until blockFrames) {
                            val sweepFrame = firstSweepFrame + frameOffset + i
                            val progress = (sweepFrame.toDouble() / (durationMs * sampleRate / 1_000.0)).coerceIn(0.0, 1.0)
                            val low = if (ascending) startHz else endHz
                            val high = if (ascending) endHz else startHz
                            val frequency = if (logarithmic) {
                                low * exp(progress * ln(high.toDouble() / low))
                            } else {
                                low + (high - low) * progress
                            }
                            val fadeSamples = (sampleRate * 0.03).toLong().coerceAtLeast(1L)
                            val envelope = min(
                                1.0,
                                min((sweepFrame.toDouble() / fadeSamples).coerceIn(0.0, 1.0),
                                    ((durationMs * sampleRate / 1_000L - sweepFrame).toDouble() / fadeSamples).coerceIn(0.0, 1.0))
                            )
                            val sample = (sin(phase) * 12_000.0 * level * envelope).toInt().coerceIn(-32_768, 32_767).toShort()
                            phase += 2.0 * PI * frequency / sampleRate
                            if (phase >= 2.0 * PI) phase -= 2.0 * PI
                            sweepReference[sweepFrame.toInt()] = sample
                            preview[i] = sample
                            when (outputChannel) {
                                "Left" -> { output[i * 2] = sample; output[i * 2 + 1] = 0 }
                                "Right" -> { output[i * 2] = 0; output[i * 2 + 1] = sample }
                                else -> { output[i * 2] = sample; output[i * 2 + 1] = sample }
                            }
                        }
                    }
                    var shortOffset = 0
                    val totalShorts = blockFrames * 2
                    while (shortOffset < totalShorts) {
                        if (!toneMeasurementActive) throw InterruptedException("Measurement cancelled")
                        val written = createdTrack.write(output, shortOffset, totalShorts - shortOffset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0 || written % 2 != 0) throw IllegalStateException("Audio output stopped unexpectedly.")
                        if (isSweep) {
                            val previewOffset = shortOffset / 2
                            val previewCount = written / 2
                            tonePreviewPcmFrame.publish(preview, previewCount, previewOffset)
                            val now = System.nanoTime()
                            if (lastClockCheck == 0L || now - lastClockCheck >= 100_000_000L) {
                                lastClockCheck = now
                                val hasTimestamp = try { createdTrack.getTimestamp(timestamp) } catch (_: RuntimeException) { false }
                                if (hasTimestamp) {
                                    tonePreviewPcmFrame.updatePlaybackPosition(timestamp.framePosition, timestamp.nanoTime, sampleRate)
                                } else {
                                    val head = try { createdTrack.playbackHeadPosition.toLong() and 0xffffffffL } catch (_: RuntimeException) { -1L }
                                    if (head >= 0L) tonePreviewPcmFrame.updatePlaybackPosition(head, now, sampleRate)
                                }
                            }
                        }
                        shortOffset += written
                    }
                    frameOffset += blockFrames
                }
            }

            // Give the capture stream a short quiet lead-in, then play the actual
            // continuous log/linear sine sweep and a tail for room decay.
            val preRollFrames = (sampleRate * 0.35).toInt()
            writeFrames(preRollFrames, isSweep = false)
            var sweepOffset = 0L
            while (sweepOffset < sweepFrames) {
                val block = min(framesPerBlock.toLong(), sweepFrames - sweepOffset).toInt()
                writeFrames(block, isSweep = true, firstSweepFrame = sweepOffset)
                sweepOffset += block
            }
            writeFrames((sampleRate * 0.55).toInt(), isSweep = false)
            Thread.sleep(180L)
            if (!running || captureGeneration != audioCaptureGeneration || outputDeviceId != preferredOutputDeviceId) {
                throw IllegalStateException("Microphone input changed during measurement. Try again.")
            }
            val pcm = capture.snapshot()
            result = analyzeSweepRecording(
                pcm = pcm,
                referencePcm = sweepReference,
                sampleRate = sampleRate,
                micCalibrationOffsetDb = micCalibrationOffsetDb,
                minFrequency = startHz,
                maxFrequency = endHz,
                durationSeconds = (durationMs / 1_000L).toInt(),
                startFrequencyHz = startHz,
                endFrequencyHz = endHz,
                logarithmic = logarithmic,
                ascending = ascending,
                outputChannel = outputChannel
            )
        } catch (_: InterruptedException) {
            if (toneMeasurementActive) failure = "Sweep measurement was interrupted."
        } catch (_: OutOfMemoryError) {
            failure = "Not enough memory to analyze this sweep. Try a shorter sweep."
        } catch (error: Exception) {
            failure = error.message ?: "Sweep measurement failed."
        } finally {
            try { track?.pause() } catch (_: Exception) {}
            try { track?.flush() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
        runOnUiThread {
            if (sweepMeasurementSessionId != sessionId) {
                if (sweepMeasurementThread === worker) sweepMeasurementThread = null
                return@runOnUiThread
            }
            if (sweepMeasurementTrack === track) sweepMeasurementTrack = null
            sweepMeasurementCapture = null
            if (sweepMeasurementThread === worker) sweepMeasurementThread = null
            val wasActive = toneMeasurementActive
            toneMeasurementActive = false
            updateSweepMeasurementButton()
            toneSignalPreview?.sweepActive = toneSweepActive
            toneSignalPreview?.playing = tonePlaying
            toneSweepUiUpdate?.invoke()
            if (!wasActive) return@runOnUiThread
            if (result != null) showSweepMeasurementResultDialog(result!!)
            else if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
        }
    }, "SweepResponseMeasurement")
    worker.priority = Thread.NORM_PRIORITY
    sweepMeasurementThread = worker
    worker.start()
}

internal fun MainActivity.cancelSweepMeasurement(showMessage: Boolean = false) {
    val wasActive = toneMeasurementActive
    sweepMeasurementSessionId++
    toneMeasurementActive = false
    sweepMeasurementCapture = null
    sweepMeasurementThread?.interrupt()
    val track = sweepMeasurementTrack
    sweepMeasurementTrack = null
    try { track?.pause() } catch (_: Exception) {}
    try { track?.stop() } catch (_: Exception) {}
    try { track?.release() } catch (_: Exception) {}
    updateSweepMeasurementButton()
    toneSignalPreview?.sweepActive = toneSweepActive
    toneSignalPreview?.playing = tonePlaying
    toneSweepUiUpdate?.invoke()
    if (showMessage && wasActive) Toast.makeText(this, "Sweep measurement cancelled.", Toast.LENGTH_SHORT).show()
}

private fun locateSweepStartInCapture(
    pcm: ShortArray,
    referencePcm: ShortArray,
    sampleRate: Int,
    minFrequency: Int,
    maxFrequency: Int,
    logarithmic: Boolean
): Int {
    if (pcm.size <= referencePcm.size / 2 || referencePcm.size < sampleRate) {
        throw IllegalStateException("The microphone recording is too short to synchronize the sweep.")
    }

    // Match a short, distinctive section near the sweep midpoint frequency.
    // The audio input and output start on separate Android clocks, so the
    // capture's leading silence cannot be treated as a fixed offset.
    val markerFrequency = (if (logarithmic) {
        sqrt(minFrequency.toDouble() * maxFrequency)
    } else {
        (minFrequency + maxFrequency) / 2.0
    }).coerceIn(minFrequency.toDouble(), maxFrequency.toDouble())
    val markerDurationSeconds = (48.0 / markerFrequency).coerceIn(0.008, 0.25)
    val markerLength = (markerDurationSeconds * sampleRate).roundToInt()
        .coerceIn(64, referencePcm.size / 3)
    val markerCenter = referencePcm.size / 2
    val markerStart = (markerCenter - markerLength / 2).coerceIn(0, referencePcm.size - markerLength)
    val decimation = (sampleRate / (markerFrequency * 4.0)).toInt().coerceAtLeast(1)
    val templateCount = (markerLength + decimation - 1) / decimation
    if (templateCount < 12) {
        throw IllegalStateException("Could not synchronize the sweep. Try a wider frequency range.")
    }

    val template = DoubleArray(templateCount)
    var templateMean = 0.0
    for (i in 0 until templateCount) {
        val value = referencePcm[markerStart + i * decimation].toDouble()
        template[i] = value
        templateMean += value
    }
    templateMean /= templateCount
    var templatePower = 0.0
    for (i in template.indices) {
        template[i] -= templateMean
        templatePower += template[i] * template[i]
    }
    if (templatePower <= 1.0) {
        throw IllegalStateException("The sweep reference was too quiet to synchronize.")
    }

    val maxSweepStart = min((sampleRate * 1.5).toInt(), pcm.size - markerStart - markerLength)
    if (maxSweepStart <= 0) {
        throw IllegalStateException("The microphone recording ended before the sweep could be synchronized.")
    }
    fun score(sweepStart: Int): Double {
        var mean = 0.0
        for (i in 0 until templateCount) mean += pcm[sweepStart + markerStart + i * decimation].toDouble()
        mean /= templateCount
        var dot = 0.0
        var capturePower = 0.0
        for (i in 0 until templateCount) {
            val value = pcm[sweepStart + markerStart + i * decimation].toDouble() - mean
            dot += template[i] * value
            capturePower += value * value
        }
        if (capturePower <= 1.0) return 0.0
        return kotlin.math.abs(dot) / sqrt(templatePower * capturePower)
    }

    val coarseStep = (decimation / 2).coerceAtLeast(1)
    var bestStart = 0
    var bestScore = 0.0
    var candidate = 0
    while (candidate <= maxSweepStart) {
        val value = score(candidate)
        if (value > bestScore) {
            bestScore = value
            bestStart = candidate
        }
        candidate += coarseStep
    }
    val refineStart = (bestStart - coarseStep).coerceAtLeast(0)
    val refineEnd = min(maxSweepStart, bestStart + coarseStep)
    for (offset in refineStart..refineEnd) {
        val value = score(offset)
        if (value > bestScore) {
            bestScore = value
            bestStart = offset
        }
    }
    if (bestScore < 0.025) {
        throw IllegalStateException("Could not lock onto the sweep in the microphone recording. Raise playback volume and try again.")
    }
    return bestStart
}

private fun analyzeSweepRecording(
    pcm: ShortArray,
    referencePcm: ShortArray,
    sampleRate: Int,
    micCalibrationOffsetDb: Double,
    minFrequency: Int,
    maxFrequency: Int,
    durationSeconds: Int,
    startFrequencyHz: Int,
    endFrequencyHz: Int,
    logarithmic: Boolean,
    ascending: Boolean,
    outputChannel: String
): SweepMeasurementResult {
    if (pcm.isEmpty() || referencePcm.isEmpty()) throw IllegalStateException("Sweep recording was empty.")
    if (sampleRate <= 0 || minFrequency <= 0 || maxFrequency <= minFrequency) {
        throw IllegalStateException("Sweep frequency range is invalid.")
    }

    val sweepStart = locateSweepStartInCapture(pcm, referencePcm, sampleRate, minFrequency, maxFrequency, logarithmic)
    val alignedCaptureSize = pcm.size - sweepStart
    val tailRequired = (sampleRate * 0.15).toInt()
    if (alignedCaptureSize < referencePcm.size + tailRequired) {
        throw IllegalStateException("The microphone recording ended before the sweep response tail was captured. Try again.")
    }

    // Keep the existing memory bound. A 32-second sweep at 48 kHz fits in a
    // 2^21 FFT; the extra tail makes the spectral division represent linear,
    // rather than circular, convolution.
    var fftSize = 1
    val requiredFftSize = max(alignedCaptureSize, referencePcm.size + tailRequired)
    while (fftSize < requiredFftSize) {
        if (fftSize >= (1 shl 21)) throw IllegalStateException("Sweep recording is too long to analyze on this device.")
        fftSize = fftSize shl 1
    }
    if (fftSize > (1 shl 21)) throw IllegalStateException("Sweep recording is too long to analyze on this device.")

    val real = DoubleArray(fftSize)
    val imaginary = DoubleArray(fftSize)
    val referenceReal = FloatArray(fftSize / 2 + 1)
    val referenceImaginary = FloatArray(fftSize / 2 + 1)

    var referenceMean = 0.0
    for (sample in referencePcm) referenceMean += sample.toDouble() / 32768.0
    referenceMean /= referencePcm.size
    for (i in referencePcm.indices) real[i] = referencePcm[i].toDouble() / 32768.0 - referenceMean
    fft(real, imaginary)
    for (bin in referenceReal.indices) {
        referenceReal[bin] = real[bin].toFloat()
        referenceImaginary[bin] = imaginary[bin].toFloat()
    }

    java.util.Arrays.fill(real, 0.0)
    java.util.Arrays.fill(imaginary, 0.0)
    var captureMean = 0.0
    var clippedSamples = 0
    for (i in 0 until alignedCaptureSize) {
        val sample = pcm[sweepStart + i].toInt()
        captureMean += sample.toDouble() / 32768.0
        if (kotlin.math.abs(sample) >= 32760) clippedSamples++
    }
    captureMean /= alignedCaptureSize
    if (clippedSamples.toDouble() / alignedCaptureSize > 0.001) {
        throw IllegalStateException("The microphone input clipped during the sweep. Lower the playback level and try again.")
    }
    for (i in 0 until alignedCaptureSize) real[i] = pcm[sweepStart + i].toDouble() / 32768.0 - captureMean
    fft(real, imaginary)

    val nyquistBin = fftSize / 2
    val minBin = ceil(minFrequency.toDouble() * fftSize / sampleRate).toInt().coerceAtLeast(1)
    val maxBinExclusive = ceil(maxFrequency.toDouble() * fftSize / sampleRate).toInt()
        .coerceAtMost(nyquistBin)
    if (maxBinExclusive <= minBin) throw IllegalStateException("Sweep frequency range is too narrow to analyze.")

    // Estimate H1 from averaged cross/auto spectra inside narrow log bands:
    // H1 = Gxy/Gxx. This rejects output-only noise better than dividing two
    // independent magnitude spectra. Bands are 1/48 octave, matching the
    // frequency detail supported by the long sweep and the FFT record.
    val bandsPerOctave = 48.0
    val bucketCount = ceil(log2(maxFrequency.toDouble() / minFrequency) * bandsPerOctave)
        .toInt().coerceIn(1, 720)
    val fftPowerScale = 2.0 / (fftSize.toDouble() * fftSize.toDouble())
    val points = ArrayList<SweepResponsePoint>(bucketCount)
    var excitedBands = 0

    for (index in 0 until bucketCount) {
        val bandLow = minFrequency * 2.0.pow(index / bandsPerOctave)
        val bandHigh = min(maxFrequency.toDouble(), minFrequency * 2.0.pow((index + 1) / bandsPerOctave))
        val firstBin = ceil(bandLow * fftSize / sampleRate).toInt().coerceAtLeast(minBin)
        val endBin = ceil(bandHigh * fftSize / sampleRate).toInt().coerceAtMost(maxBinExclusive)
        if (endBin <= firstBin) continue

        var crossReal = 0.0
        var crossImaginary = 0.0
        var inputPower = 0.0
        var outputPower = 0.0
        for (bin in firstBin until endBin) {
            val xr = referenceReal[bin].toDouble()
            val xi = referenceImaginary[bin].toDouble()
            val yr = real[bin]
            val yi = imaginary[bin]
            inputPower += xr * xr + xi * xi
            outputPower += yr * yr + yi * yi
            // conj(X) * Y
            crossReal += xr * yr + xi * yi
            crossImaginary += xr * yi - xi * yr
        }
        if (inputPower <= 1e-16 || outputPower <= 1e-20) continue
        excitedBands++

        val crossPower = crossReal * crossReal + crossImaginary * crossImaginary
        val coherence = (crossPower / (inputPower * outputPower)).coerceIn(0.0, 1.0)
        // This is a conservative band-coherence floor, not a substitute for
        // repeated-frame coherence: these spectra come from one synchronized sweep.
        if (!coherence.isFinite() || coherence < 0.005) continue
        val transferMagnitude = sqrt(crossPower) / inputPower
        if (!transferMagnitude.isFinite() || transferMagnitude <= 1e-8) continue
        val referenceBandPower = inputPower * fftPowerScale
        val outputBandDbfs = 10.0 * log10(referenceBandPower) + 20.0 * log10(transferMagnitude)
        val estimatedSplDb = outputBandDbfs + micCalibrationOffsetDb
        if (!estimatedSplDb.isFinite()) continue

        val centerFrequency = minFrequency * 2.0.pow((index + 0.5) / bandsPerOctave)
        points += SweepResponsePoint(centerFrequency.coerceAtMost(maxFrequency.toDouble()), estimatedSplDb)
    }

    val minimumUsablePoints = min(16, bucketCount).coerceAtLeast(1)
    if (points.size < minimumUsablePoints) {
        val reason = if (excitedBands < minimumUsablePoints) "The sweep did not provide enough usable energy across the selected range." else "The recording was too noisy or unstable to produce a reliable response curve."
        throw IllegalStateException("$reason Raise playback volume, reduce background noise, or use a longer sweep.")
    }
    val coverage = (points.size * 100.0 / bucketCount).roundToInt().coerceIn(0, 100)
    return SweepMeasurementResult(
        points = points,
        durationSeconds = durationSeconds,
        sampleCount = pcm.size,
        qualityPercent = coverage,
        startFrequencyHz = startFrequencyHz,
        endFrequencyHz = endFrequencyHz,
        logarithmic = logarithmic,
        ascending = ascending,
        outputChannel = outputChannel
    )
}

private class SweepResponseChartView(
    context: android.content.Context,
    private val points: List<SweepResponsePoint>,
    private val minFrequency: Int,
    private val maxFrequency: Int
) : View(context) {
    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 63, 81)
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 90)
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(177, 190, 207)
        textSize = 10f * density
        typeface = Typeface.MONOSPACE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 36)
        style = Paint.Style.FILL
    }
    private val responsePath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 44f * density
        val top = 26f * density
        val right = width - 10f * density
        val bottom = height - 30f * density
        if (right <= left || bottom <= top) return
        canvas.drawRoundRect(left, top, right, bottom, 8f * density, 8f * density, fillPaint)

        val dataMin = points.minOf { it.splDb }
        val dataMax = points.maxOf { it.splDb }
        var yMinDb = floor(dataMin / 10.0).toInt() * 10
        var yMaxDb = ceil(dataMax / 10.0).toInt() * 10
        if (yMaxDb - yMinDb < 40) {
            val centerDb = (dataMin + dataMax) / 2.0
            yMinDb = floor((centerDb - 20.0) / 10.0).toInt() * 10
            yMaxDb = yMinDb + 40
        }
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("dB SPL (est.)", left, 18f * density, textPaint)
        for (db in yMinDb..yMaxDb step 10) {
            val y = top + (yMaxDb - db).toFloat() / (yMaxDb - yMinDb) * (bottom - top)
            canvas.drawLine(left, y, right, y, gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("$db", left - 5f * density, y + 3.5f * density, textPaint)
        }

        // Match the analyzer's logarithmic frequency grid, rather than showing
        // only five widely spaced sweep labels.
        val logGrid = listOf(
            20, 30, 40, 50, 70, 100, 200, 300, 400, 500, 600, 800,
            1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 8_000, 10_000, 20_000
        )
        val xFrequencies = (logGrid + minFrequency + maxFrequency).distinct()
            .filter { it in minFrequency..maxFrequency }
        xFrequencies.forEach { frequency ->
            val x = left + (ln(frequency / minFrequency.toDouble()) / ln(maxFrequency / minFrequency.toDouble())).toFloat() * (right - left)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val labelFrequency = frequency in setOf(20, 30, 40, 50, 70, 100, 200, 300, 400, 600, 800, 1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 8_000, 10_000, 20_000)
            if (!labelFrequency) return@forEach
            textPaint.textAlign = when {
                x < left + 20f * density -> Paint.Align.LEFT
                x > right - 32f * density -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val label = when {
                frequency >= 1_000 && frequency % 1_000 == 0 -> "${frequency / 1_000}k"
                else -> frequency.toString()
            }
            canvas.drawText(label, x, height - 8f * density, textPaint)
        }

        responsePath.reset()
        points.forEachIndexed { index, point ->
            val x = left + (ln(point.frequencyHz / minFrequency) / ln(maxFrequency.toDouble() / minFrequency)).toFloat() * (right - left)
            val y = top + ((yMaxDb - point.splDb.coerceIn(yMinDb.toDouble(), yMaxDb.toDouble())) /
                (yMaxDb - yMinDb)).toFloat() * (bottom - top)
            if (index == 0) responsePath.moveTo(x, y) else responsePath.lineTo(x, y)
        }
        canvas.drawPath(responsePath, tracePaint)
    }
}

private fun MainActivity.showSweepMeasurementResultDialog(result: SweepMeasurementResult) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        styleNumericDialogCard(this)
        setBackgroundColor(Color.rgb(15, 23, 36))
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    card.addView(TextView(this).apply {
        text = "SWEEP RESPONSE"
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(255, 213, 90))
        gravity = Gravity.CENTER
        includeFontPadding = false
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))
    card.addView(TextView(this).apply {
        text = "Estimated dB SPL · speaker + room + phone microphone"
        textSize = 11f
        setTextColor(Color.rgb(176, 190, 207))
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).apply { topMargin = dp(2) })
    card.addView(SweepResponseChartView(this, result.points,
        minOf(result.startFrequencyHz, result.endFrequencyHz),
        maxOf(result.startFrequencyHz, result.endFrequencyHz)),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
            bottomMargin = dp(7)
        })
    card.addView(TextView(this).apply {
        text = "${result.points.size} frequency bands · ${result.qualityPercent}% coverage · ${result.durationSeconds}s sweep\nSPL is estimated from the phone microphone calibration."
        textSize = 10f
        setTextColor(Color.rgb(153, 169, 188))
        gravity = Gravity.CENTER
        maxLines = 2
        includeFontPadding = false
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))

    val dialog = createAnimatedDialog()
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val download = AnalyzerIconButton(this, AnalyzerIconButton.Icon.EXPORT).apply {
        contentDescription = "Save sweep response PDF chart and CSV data"
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { saveSweepMeasurement(result) }
    }
    styleNumericDialogButton(download)
    actions.addView(download, LinearLayout.LayoutParams(dp(48), dp(40)).apply { marginEnd = dp(8) })
    val done = Button(this).apply {
        text = "DONE"
        textSize = 12f
        setTextColor(Color.rgb(255, 213, 90))
        setOnClickListener { dialog.dismiss() }
    }
    styleNumericDialogButton(done)
    actions.addView(done, LinearLayout.LayoutParams(0, dp(40), 1f))
    card.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(7) })
    setDialogCardContent(dialog, card)
    requireExplicitDialogAction(dialog)
    fun fillDisplayIncludingCutout() {
        val window = dialog.window ?: return
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setGravity(Gravity.CENTER)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = attributes
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
    fillDisplayIncludingCutout()
    dialog.setOnShowListener { fillDisplayIncludingCutout() }
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
}

internal fun MainActivity.saveSweepMeasurement(result: SweepMeasurementResult) {
    if (pendingSweepMeasurementExport != null || sweepMeasurementExportInProgress.get()) {
        Toast.makeText(this, "A sweep export is already in progress.", Toast.LENGTH_SHORT).show()
        return
    }
    val now = Date()
    val fileNameStem = "sweep_response_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(now)}"
    val measuredAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
    val csvText = buildString {
        appendLine("# SpectrumAnalyzer2 sweep response")
        appendLine("# Measured at: $measuredAt")
        appendLine("# Start frequency (Hz): ${result.startFrequencyHz}")
        appendLine("# End frequency (Hz): ${result.endFrequencyHz}")
        appendLine("# Sweep direction: ${if (result.ascending) "ascending" else "descending"}")
        appendLine("# Sweep scale: ${if (result.logarithmic) "logarithmic" else "linear"}")
        appendLine("# Duration (s): ${result.durationSeconds}")
        appendLine("# Output channel: ${result.outputChannel}")
        appendLine("# Coverage: ${result.qualityPercent}%")
        appendLine("# Estimated dB SPL; uses the app's microphone calibration offset and includes speaker, room, microphone and device processing.")
        appendLine("# Raw microphone audio is not included.")
        appendLine("frequency_hz,estimated_spl_db")
        result.points.forEach { point ->
            append(String.format(Locale.US, "%.3f,%.2f%n", point.frequencyHz, point.splDb))
        }
    }
    val export = try {
        SweepMeasurementExport(
            fileNameStem = fileNameStem,
            csv = csvText.toByteArray(Charsets.UTF_8),
            pdf = createSweepMeasurementPdf(result, measuredAt)
        )
    } catch (error: Exception) {
        Toast.makeText(this, "Could not prepare the sweep report: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        return
    }
    pendingSweepMeasurementExport = export
    val preferences = getSharedPreferences("sweep_measurement_export", android.content.Context.MODE_PRIVATE)
    val storedFolder = preferences.getString("folder_uri", null)
    val treeUri = storedFolder?.let(Uri::parse)
    val hasWritePermission = treeUri != null && contentResolver.persistedUriPermissions.any {
        it.uri == treeUri && it.isWritePermission
    }
    if (!hasWritePermission) {
        preferences.edit().remove("folder_uri").apply()
        requestSweepMeasurementExportFolder()
    } else {
        exportPendingSweepMeasurement()
    }
}

private fun MainActivity.createSweepMeasurementPdf(result: SweepMeasurementResult, measuredAt: String): ByteArray {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(842, 595, 1).create())
    val canvas = page.canvas
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 38, 54)
        textSize = 25f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(61, 75, 92); textSize = 13f }
    val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(102, 115, 130); textSize = 11f }
    canvas.drawColor(Color.WHITE)
    canvas.drawText("Sweep response", 36f, 42f, titlePaint)
    canvas.drawText("Measured $measuredAt · ${result.durationSeconds}s · ${result.qualityPercent}% coverage", 36f, 68f, bodyPaint)
    canvas.drawText(
        "${result.startFrequencyHz} Hz → ${result.endFrequencyHz} Hz · ${if (result.logarithmic) "logarithmic" else "linear"} · ${if (result.ascending) "ascending" else "descending"} · ${result.outputChannel}",
        36f, 89f, bodyPaint
    )
    val chartWidth = 770
    val chartHeight = 390
    val chart = SweepResponseChartView(
        this,
        result.points,
        minOf(result.startFrequencyHz, result.endFrequencyHz),
        maxOf(result.startFrequencyHz, result.endFrequencyHz)
    )
    chart.measure(
        View.MeasureSpec.makeMeasureSpec(chartWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(chartHeight, View.MeasureSpec.EXACTLY)
    )
    chart.layout(0, 0, chartWidth, chartHeight)
    val bitmap = android.graphics.Bitmap.createBitmap(chartWidth, chartHeight, android.graphics.Bitmap.Config.ARGB_8888)
    try {
        chart.draw(Canvas(bitmap))
        canvas.drawBitmap(bitmap, null, RectF(36f, 105f, 806f, 495f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    } finally {
        bitmap.recycle()
    }
    canvas.drawText("Estimated dB SPL from the app's microphone calibration; phone processing affects accuracy.", 36f, 526f, notePaint)
    canvas.drawText("CSV file contains the frequency-band data used to draw this chart.", 36f, 544f, notePaint)
    document.finishPage(page)
    val output = ByteArrayOutputStream()
    try {
        document.writeTo(output)
        return output.toByteArray()
    } finally {
        document.close()
    }
}

internal fun MainActivity.requestSweepMeasurementExportFolder() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }
    Toast.makeText(this, "Choose a parent folder. The app will create SpectrumAnalyzer2/SweepMeasurements inside it.", Toast.LENGTH_LONG).show()
    try {
        startActivityForResult(intent, sweepMeasurementFolderRequestCode)
    } catch (_: Exception) {
        pendingSweepMeasurementExport = null
        Toast.makeText(this, "Could not open a folder picker.", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.exportPendingSweepMeasurement() {
    val export = pendingSweepMeasurementExport ?: return
    if (!sweepMeasurementExportInProgress.compareAndSet(false, true)) return
    val preferences = getSharedPreferences("sweep_measurement_export", android.content.Context.MODE_PRIVATE)
    val treeUri = preferences.getString("folder_uri", null)?.let(Uri::parse)
    val hasWritePermission = treeUri != null && contentResolver.persistedUriPermissions.any {
        it.uri == treeUri && it.isWritePermission
    }
    if (!hasWritePermission) {
        sweepMeasurementExportInProgress.set(false)
        requestSweepMeasurementExportFolder()
        return
    }
    Toast.makeText(this, "Saving sweep report…", Toast.LENGTH_SHORT).show()
    Thread({
        var pdfSaved = false
        var csvSaved = false
        var failure: String? = null
        try {
            val tree = treeUri!!
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            var folder = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            folder = findOrCreateSweepFolder(tree, folder, "SpectrumAnalyzer2")
            folder = findOrCreateSweepFolder(tree, folder, "SweepMeasurements")
            try {
                val pdfUri = DocumentsContract.createDocument(
                    contentResolver, folder, "application/pdf", "${export.fileNameStem}.pdf"
                ) ?: throw java.io.IOException("Could not create the PDF report.")
                contentResolver.openOutputStream(pdfUri, "w")?.use { it.write(export.pdf) }
                    ?: throw java.io.IOException("Could not write the PDF report.")
                pdfSaved = true
            } catch (error: Exception) {
                failure = error.message ?: "Could not save the PDF report."
            }
            try {
                val csvUri = DocumentsContract.createDocument(
                    contentResolver, folder, "text/csv", "${export.fileNameStem}.csv"
                ) ?: throw java.io.IOException("Could not create the CSV data file.")
                contentResolver.openOutputStream(csvUri, "w")?.use { it.write(export.csv) }
                    ?: throw java.io.IOException("Could not write the CSV data file.")
                csvSaved = true
            } catch (error: Exception) {
                failure = listOfNotNull(failure, error.message).joinToString("; ")
            }
        } catch (error: Exception) {
            failure = error.message ?: "Could not create the sweep export folder."
        } finally {
            sweepMeasurementExportInProgress.set(false)
            pendingSweepMeasurementExport = null
            runOnUiThread {
                val message = when {
                    pdfSaved && csvSaved -> "Saved PDF chart and CSV data in Documents/SpectrumAnalyzer2/SweepMeasurements."
                    pdfSaved || csvSaved -> "Saved ${if (pdfSaved) "PDF" else "CSV"}; the other file failed: ${failure ?: "unknown error"}"
                    else -> "Could not save sweep report: ${failure ?: "unknown error"}"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }, "SweepMeasurementExporter").apply { priority = Thread.NORM_PRIORITY; start() }
}

private fun MainActivity.findOrCreateSweepFolder(treeUri: Uri, parentUri: Uri, name: String): Uri {
    val parentId = DocumentsContract.getDocumentId(parentUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            if (nameIndex >= 0 && mimeIndex >= 0 && idIndex >= 0 &&
                cursor.getString(nameIndex) == name &&
                cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR
            ) {
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
            }
        }
    }
    return DocumentsContract.createDocument(
        contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
    ) ?: throw java.io.IOException("Could not create folder $name.")
}
