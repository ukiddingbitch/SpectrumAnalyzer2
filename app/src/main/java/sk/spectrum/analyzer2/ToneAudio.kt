package sk.spectrum.analyzer2

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.widget.Toast

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Tone PCM generation, AudioTrack output and playback-clock publication. */
internal fun MainActivity.startTone() {
    if (tonePlaying) return
    val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) {
        Toast.makeText(this, "Audio output is unavailable.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, 4096), AudioTrack.MODE_STREAM
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            Toast.makeText(this, "Audio output is unavailable.", Toast.LENGTH_SHORT).show()
            return
        }
        applyPreferredOutputRoute(track)
        toneTrack = track
        toneStopRequested = false
        tonePlaying = true
        tonePreviewPcmFrame.clear()
        track.play()
        toneThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(2048)
            val previewSamples = ShortArray(buffer.size / 2)
            val scopeTimestamp = android.media.AudioTimestamp()
            var scopeClockCheckedAt = Long.MIN_VALUE
            fun updateScopeClock() {
                val now = System.nanoTime()
                if (scopeClockCheckedAt != Long.MIN_VALUE && now - scopeClockCheckedAt < 100_000_000L) return
                scopeClockCheckedAt = now
                val hasTimestamp = try { track.getTimestamp(scopeTimestamp) } catch (_: RuntimeException) { false }
                if (hasTimestamp) {
                    tonePreviewPcmFrame.updatePlaybackPosition(scopeTimestamp.framePosition, scopeTimestamp.nanoTime, sampleRate)
                } else {
                    val head = try { track.playbackHeadPosition } catch (_: RuntimeException) { return }
                    tonePreviewPcmFrame.updatePlaybackPosition(head.toLong() and 0xffffffffL, System.nanoTime(), sampleRate)
                }
            }
            var phase = 0.0
            var secondPhase = 0.0
            var noiseState = System.nanoTime().toInt().let { if (it == 0) 1 else it }
            var pink0 = 0.0
            var pink1 = 0.0
            var pink2 = 0.0
            var pink3 = 0.0
            var pink4 = 0.0
            var pink5 = 0.0
            var pink6 = 0.0
            fun nextWhiteNoise(): Double {
                noiseState = noiseState xor (noiseState shl 13)
                noiseState = noiseState xor (noiseState ushr 17)
                noiseState = noiseState xor (noiseState shl 5)
                return (noiseState.toDouble() / Int.MAX_VALUE.toDouble()).coerceIn(-1.0, 1.0)
            }
            fun nextPinkNoise(): Double {
                val white = nextWhiteNoise()
                pink0 = 0.99886 * pink0 + white * 0.0555179
                pink1 = 0.99332 * pink1 + white * 0.0750759
                pink2 = 0.96900 * pink2 + white * 0.1538520
                pink3 = 0.86650 * pink3 + white * 0.3104856
                pink4 = 0.55000 * pink4 + white * 0.5329522
                pink5 = -0.7616 * pink5 - white * 0.0168980
                val pink = pink0 + pink1 + pink2 + pink3 + pink4 + pink5 + pink6 + white * 0.5362
                pink6 = white * 0.115926
                return (pink * 0.20).coerceIn(-1.0, 1.0)
            }
            var fadeRemaining = -1
            val fadeSamples = (sampleRate * 0.04).toInt()
            toneLoop@ while (!Thread.currentThread().isInterrupted) {
                if (toneStopRequested && fadeRemaining < 0) fadeRemaining = fadeSamples
                if (!tonePlaying && fadeRemaining <= 0) break
                val dtmf = toneDtmfActive
                val frequency = (if (dtmf) toneDtmfLowFrequency else toneFrequency).toDouble().coerceIn(20.0, 20000.0)
                val step = 2.0 * PI * frequency / sampleRate
                val secondFrequency = toneDtmfHighFrequency.toDouble().coerceIn(20.0, 20000.0)
                val secondStep = 2.0 * PI * secondFrequency / sampleRate
                val waveform = toneWaveform
                val volume = toneVolume
                val outputChannel = toneOutputChannel
                for (frameIndex in 0 until buffer.size / 2) {
                    val normalized = if (dtmf) {
                        (sin(phase) + sin(secondPhase)) * 0.5
                    } else when (waveform) {
                        "White noise" -> nextWhiteNoise()
                        "Pink noise" -> nextPinkNoise()
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
                    val sample = (normalized.coerceIn(-1.0, 1.0) * 12000.0 * volume * envelope).toInt().toShort()
                    previewSamples[frameIndex] = sample
                    val leftIndex = frameIndex * 2
                    val rightIndex = leftIndex + 1
                    when (outputChannel) {
                        "Left" -> {
                            buffer[leftIndex] = sample
                            buffer[rightIndex] = 0
                        }
                        "Right" -> {
                            buffer[leftIndex] = 0
                            buffer[rightIndex] = sample
                        }
                        else -> {
                            buffer[leftIndex] = sample
                            buffer[rightIndex] = sample
                        }
                    }
                    phase += step
                    if (phase >= 2.0 * PI) phase -= 2.0 * PI
                    secondPhase += secondStep
                    if (secondPhase >= 2.0 * PI) secondPhase -= 2.0 * PI
                }
                try {
                    var offset = 0
                    while (offset < buffer.size) {
                        val writtenShorts = track.write(buffer, offset, buffer.size - offset)
                        if (writtenShorts <= 0 || writtenShorts % 2 != 0) break@toneLoop
                        if (toneTrack === track) {
                            tonePreviewPcmFrame.publish(previewSamples, writtenShorts / 2, offset / 2)
                            updateScopeClock()
                        }
                        offset += writtenShorts
                    }
                } catch (_: Exception) {
                    break
                }
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

internal fun MainActivity.stopTone() {
    cancelToneSweep()
    if (!tonePlaying) {
        resetTonePlayButton()
        return
    }
    toneStopRequested = true
    // The audio thread performs a short fade-out before releasing AudioTrack.
}
