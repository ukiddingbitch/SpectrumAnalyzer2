package sk.spectrum.analyzer2

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.view.View

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Microphone setup, capture loop and spectrum analysis lifecycle. */
internal fun MainActivity.updateStartButtonAppearance() {
    if (!isStartButtonInitialized) return
    val color = if (running) Color.rgb(120, 25, 35) else Color.rgb(30, 125, 65)
    startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    startButton.setTextColor(Color.WHITE)
    (startButton as? AnalyzerIconButton)?.icon =
        if (running) AnalyzerIconButton.Icon.STOP else AnalyzerIconButton.Icon.PLAY
}

internal fun MainActivity.createAudioRecordWithFallback(): AudioRecord? {
    for (captureRate in intArrayOf(sampleRate, 44_100).distinct()) {
        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                captureRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Exception) {
            continue
        }
        if (minBuffer <= 0) continue

        val candidate = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                captureRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, fftSize * 2)
            )
        } catch (error: SecurityException) {
            throw error
        } catch (_: IllegalArgumentException) {
            continue
        }
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            continue
        }
        val inputDevices = availableAudioDevices(input = true)
        val selectedInput = preferredInputDeviceId?.let { id -> inputDevices.firstOrNull { it.id == id } }
        val fallbackInput = if (preferBuiltInMicrophone && selectedInput == null) {
            inputDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
        } else {
            null
        }
        val preferredInput = selectedInput ?: fallbackInput
        if (preferredInput != null) {
            try {
                candidate.setPreferredDevice(preferredInput)
            } catch (_: RuntimeException) {
                // Some audio HALs ignore preferred routes; default routing remains available.
            }
        }
        try {
            candidate.startRecording()
            if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) return candidate
        } catch (error: SecurityException) {
            candidate.release()
            throw error
        } catch (_: IllegalStateException) {
            // Try the next standard capture rate.
        }
        candidate.release()
    }
    return null
}

internal fun MainActivity.startAnalysis(preserveGraph: Boolean = false) {
    if (running) return
    ensureAudioDeviceCallbackRegistered()

    // Clear old spectrum data while preserving the selected frequency range and axis mode.
    if (!preserveGraph) spectrumView.resetView()

    if (
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        showStatus("Microphone permission is required.")
        return
    }

    val audioRecord = try {
        createAudioRecordWithFallback()
    } catch (e: SecurityException) {
        showStatus("Microphone permission denied.")
        return
    }
    if (audioRecord == null) {
        showStatus("Audio recording is not supported on this device.")
        return
    }

    recorder = audioRecord
    splSystemCalibrationOffsetDb = readSystemSplCalibrationOffset(audioRecord)
    splCalibrationOffsetDb = splSystemCalibrationOffsetDb ?: genericSplEstimateOffsetDb
    resetSplMeterSession()
    latestSplFastADbfs = -120.0
    latestSplSlowADbfs = -120.0
    splRecentReadings.clear()
    resetSplSlowDisplayCache()
    splLastResponseValueDb = null
    resetSplCaptureReadings()
    splHasCurrentSample = false
    running = true
    updateSplReadout()

    (startButton as? AnalyzerIconButton)?.icon = AnalyzerIconButton.Icon.STOP
    updateStartButtonAppearance()
    statusText.text = ""
    statusText.visibility = View.GONE

    val captureGeneration = ++audioCaptureGeneration
    audioThread = Thread {
        processAudio(audioRecord, captureGeneration)
    }.also {
        it.start()
    }
}

internal fun MainActivity.processAudio(audioRecord: AudioRecord, captureGeneration: Long) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

    // Keep a rolling buffer large enough for the maximum supported FFT size.
    val ringBuffer = ShortArray(16384)
    val readBuffer = ShortArray(2048)
    val inputResampler = audioRecord.sampleRate.takeIf { it != sampleRate }
        ?.let { StreamingPcmResampler(it, sampleRate) }
    var writeIndex = 0
    var samplesSeen = 0L
    var samplesSinceFft = 0
    var activeFftSize = 8192
    var activeHopSize = activeFftSize / 4
    var samples = ShortArray(activeFftSize)
    var real = DoubleArray(activeFftSize)
    var imaginary = DoubleArray(activeFftSize)
    var magnitudes = DoubleArray(activeFftSize / 2)
    val splAWeightingFilter = SplAWeightingFilter(sampleRate)
    val splSessionAnalyzer = SplSessionAnalyzer(sampleRate)
    val fastPowerAlpha = exp(-1.0 / (0.125 * sampleRate))
    val slowPowerAlpha = exp(-1.0 / (1.0 * sampleRate))
    var fastAWeightedPower = 0.0
    var slowAWeightedPower = 0.0
    var splPowerPrimed = false
    silentCaptureStartedAtMs = 0L
    var silenceRecoveryRequested = false

    fun powerToDbfs(power: Double): Double =
        if (power > 1e-12 && power.isFinite()) 10.0 * log10(power) else -120.0

    while (running && captureGeneration == audioCaptureGeneration) {
        // Frames started before RESET must not be allowed to restore old graph state.
        val frameResetGeneration = spectrumView.getResetGeneration()
        val readCount = try {
            audioRecord.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } catch (_: Exception) {
            -1
        }
        if (!running || captureGeneration != audioCaptureGeneration) break
        if (readCount < 0) {
            if (!running) break
            scheduleAudioRouteRestart(expectedCaptureGeneration = captureGeneration)
            break
        }
        if (readCount == 0) {
            if (!running) break
            try {
                Thread.sleep(8L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            continue
        }

        val converted = inputResampler?.process(readBuffer, readCount)
        val audioSamples = converted?.samples ?: readBuffer
        val count = converted?.count ?: readCount
        if (count <= 0) continue

        // Measurement sweep reuses this single AudioRecord stream; never open a
        // competing microphone session while the analyzer is already listening.
        sweepMeasurementCapture?.append(audioSamples, count)

        // Sound ID consumes the same microphone samples as the spectrum graph.
        // It never opens a second AudioRecord session.
        if (soundIdActive) soundIdEngine.offerAudio(audioSamples, count)

        splSessionAnalyzer.beginBlock(splMeterSessionGeneration)
        var blockHasSignal = false
        for (i in 0 until count) {
            val normalizedSample = audioSamples[i].toDouble() / 32768.0
            if (abs(audioSamples[i].toInt()) > 1) blockHasSignal = true
            val aWeightedSample = splAWeightingFilter.process(normalizedSample)
            val aWeightedPower = aWeightedSample * aWeightedSample
            if (!splPowerPrimed) {
                fastAWeightedPower = aWeightedPower
                slowAWeightedPower = aWeightedPower
                splPowerPrimed = true
            } else {
                fastAWeightedPower = fastPowerAlpha * fastAWeightedPower + (1.0 - fastPowerAlpha) * aWeightedPower
                slowAWeightedPower = slowPowerAlpha * slowAWeightedPower + (1.0 - slowPowerAlpha) * aWeightedPower
            }
            splSessionAnalyzer.process(normalizedSample, aWeightedPower, fastAWeightedPower, slowAWeightedPower)
            ringBuffer[writeIndex] = audioSamples[i]
            writeIndex = (writeIndex + 1) % ringBuffer.size
        }
        if (blockHasSignal) {
            silentCaptureStartedAtMs = 0L
            silenceRecoveryRequested = false
            silenceFallbackAttempted = false
        } else if (!silenceRecoveryRequested) {
            val now = SystemClock.elapsedRealtime()
            if (silentCaptureStartedAtMs == 0L) silentCaptureStartedAtMs = now
            if (now - silentCaptureStartedAtMs >= 1800L && !silenceFallbackAttempted) {
                // A USB-C audio adapter can make Android switch capture to an
                // input endpoint that returns digital zero. Retry explicitly
                // on the handset mic once; real quiet input remains valid.
                silenceRecoveryRequested = true
                silenceFallbackAttempted = true
                scheduleAudioRouteRestart(
                    forceBuiltInMicrophone = true,
                    expectedCaptureGeneration = captureGeneration
                )
                break
            }
        }
        if (!running || captureGeneration != audioCaptureGeneration) break
        splSessionSnapshot = splSessionAnalyzer.snapshot()
        if (splMeterPanel != null) splWaveHistory.append(audioSamples, count, sampleRate)
        latestSplFastADbfs = powerToDbfs(fastAWeightedPower)
        latestSplSlowADbfs = powerToDbfs(slowAWeightedPower)
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

        val rms = kotlin.math.sqrt(sumSquares / activeFftSize)
        val hasUsableSignal = rms > 1e-7 && peakMagnitude > 1e-7
        val peakFrequency = if (hasUsableSignal) {
            (peakBin + peakOffset) * sampleRate / activeFftSize
        } else {
            0.0
        }
        val levelDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
        val spectrumSnapshot = magnitudes.copyOf()
        val currentFftSize = activeFftSize
        val peakDb = if (hasUsableSignal) {
            20.0 * log10(peakMagnitude * 4.0 / currentFftSize)
        } else {
            -160.0
        }

        runOnUiThread {
            if (running && captureGeneration == audioCaptureGeneration &&
                frameResetGeneration == spectrumView.getResetGeneration()
            ) {
                spectrumView.setSpectrum(spectrumSnapshot, sampleRate, currentFftSize, peakFrequency)
                splMeterPanel?.setSignal(spectrumSnapshot, sampleRate, currentFftSize, splWaveHistory.snapshot())
                latestAnalyzedPeakFrequency = peakFrequency
                latestAnalyzedPeakDb = peakDb
                latestAnalyzedRmsDb = levelDb
                splHasCurrentSample = true
                headerPeakFrequencyText?.text = if (peakFrequency <= 0.0) {
                    "— Hz"
                } else if (peakFrequency >= 1000.0) {
                    String.format(java.util.Locale.US, "%.2f kHz", peakFrequency / 1000.0)
                } else {
                    String.format(java.util.Locale.US, "%.1f Hz", peakFrequency)
                }
                headerPeakDbText?.text = String.format(java.util.Locale.US, "%.1f dBFS", peakDb)
                updateSplReadout()
                if (peakFrequency > 0.0) updateTonePeakFollow(peakFrequency, peakDb, levelDb)
                peakText.text = if (peakFrequency <= 0.0) {
                    "Peak: —    Level: %.1f dBFS    FFT: %d".format(java.util.Locale.US, levelDb, currentFftSize)
                } else {
                    String.format(
                        java.util.Locale.US,
                        "Peak: %.1f Hz    Level: %.1f dBFS    FFT: %d",
                        peakFrequency,
                        levelDb,
                        currentFftSize
                    )
                }
            }
        }
    }
}

internal fun MainActivity.stopAnalysis(preserveSoundId: Boolean = false, preserveGraph: Boolean = false) {
    pendingAudioRouteRestart?.let(audioRouteHandler::removeCallbacks)
    pendingAudioRouteRestart = null
    audioCaptureGeneration++
    running = false
    splHasCurrentSample = false
    splRecentReadings.clear()
    resetSplSlowDisplayCache()
    splLastResponseValueDb = null
    resetSplCaptureReadings()
    updateSplReadout()
    if (soundIdActive && !preserveSoundId) {
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

    if (!preserveGraph) spectrumView.resetView()
}
