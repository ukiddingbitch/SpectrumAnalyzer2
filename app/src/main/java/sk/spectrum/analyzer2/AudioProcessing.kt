package sk.spectrum.analyzer2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shared PCM conversion and FFT/audio-window helpers. */
internal class PcmBlock(val samples: ShortArray, val count: Int)

/** Streaming windowed-sinc converter used only when the device cannot open 48 kHz capture. */
internal class StreamingPcmResampler(sourceRate: Int, private val outputRate: Int) {
    private val inputRate = sourceRate
    private val halfTaps = 31
    private val ring = ShortArray(32768)
    private var totalInputSamples = 0L
    private var nextOutputSample = 0L
    private val phaseCount: Int
    private val phaseCoefficients: Array<DoubleArray>

    init {
        require(sourceRate > 0 && outputRate > 0)
        var left = this.inputRate
        var right = outputRate
        while (right != 0) {
            val remainder = left % right
            left = right
            right = remainder
        }
        phaseCount = (outputRate / left.coerceAtLeast(1)).coerceAtMost(1024).coerceAtLeast(1)
        val cutoff = minOf(1.0, outputRate.toDouble() / this.inputRate)
        phaseCoefficients = Array(phaseCount) { phase ->
            val fraction = phase.toDouble() / phaseCount
            val coefficients = DoubleArray(halfTaps * 2 + 1)
            var sum = 0.0
            for (tap in coefficients.indices) {
                val distance = tap - halfTaps - fraction
                val windowPosition = distance / (halfTaps + 1.0)
                val window = if (abs(windowPosition) > 1.0) 0.0 else
                    0.42 + 0.5 * cos(PI * windowPosition) + 0.08 * cos(2.0 * PI * windowPosition)
                val argument = distance * cutoff
                val sinc = if (abs(argument) < 1e-12) 1.0 else sin(PI * argument) / (PI * argument)
                val coefficient = cutoff * sinc * window
                coefficients[tap] = coefficient
                sum += coefficient
            }
            if (abs(sum) > 1e-12) {
                for (tap in coefficients.indices) coefficients[tap] /= sum
            }
            coefficients
        }
    }

    fun process(input: ShortArray, count: Int): PcmBlock {
        val safeCount = count.coerceIn(0, input.size)
        if (safeCount == 0) return PcmBlock(ShortArray(0), 0)
        if (this.inputRate == outputRate) return PcmBlock(input.copyOf(safeCount), safeCount)
        require(safeCount < ring.size - halfTaps) { "Audio input block is too large for the resampler." }
        for (index in 0 until safeCount) {
            ring[((totalInputSamples + index) % ring.size).toInt()] = input[index]
        }
        totalInputSamples += safeCount

        val estimate = ceil((safeCount + halfTaps * 2.0) * outputRate / this.inputRate).toInt() + 4
        val output = ShortArray(estimate.coerceAtLeast(1))
        var outputCount = 0
        while (outputCount < output.size) {
            val sourcePosition = nextOutputSample.toDouble() * this.inputRate / outputRate
            val center = floor(sourcePosition).toLong()
            if (center + halfTaps >= totalInputSamples) break
            val fraction = sourcePosition - center
            val phase = (fraction * phaseCount).toInt().coerceIn(0, phaseCount - 1)
            val coefficients = phaseCoefficients[phase]
            var value = 0.0
            for (tap in coefficients.indices) {
                val sourceIndex = (center + tap - halfTaps).coerceAtLeast(0L)
                val sample = ring[(sourceIndex % ring.size).toInt()].toDouble()
                value += sample * coefficients[tap]
            }
            output[outputCount++] = value.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            nextOutputSample++
        }
        // Callers honor count, so unused capacity can stay in this array and we
        // avoid a second allocation on the real-time capture thread.
        return PcmBlock(output, outputCount)
    }
}

internal fun countCompleteAudioWindows(sampleCount: Int, windowSize: Int, hopSize: Int): Int {
    if (sampleCount < windowSize || windowSize <= 0 || hopSize <= 0) return 0
    return 1 + (sampleCount - windowSize) / hopSize
}

/** Portion of this overlapping audio window not already represented by an earlier result. */
internal fun newlyCoveredAudioSamples(
    frameEndSample: Long,
    windowSize: Int,
    previouslyCoveredEndSample: Long
): Long {
    if (frameEndSample <= 0L || windowSize <= 0) return 0L
    val frameStartSample = (frameEndSample - windowSize).coerceAtLeast(0L)
    val firstUncoveredSample = maxOf(frameStartSample, previouslyCoveredEndSample.coerceAtLeast(0L))
    return (frameEndSample - firstUncoveredSample).coerceAtLeast(0L)
}

internal fun spectrogramBinRange(
    firstFrequency: Double,
    lastFrequency: Double,
    sampleRate: Int,
    fftSize: Int,
    maximumFrequency: Double
): IntRange? {
    if (sampleRate <= 0 || fftSize <= 0 || maximumFrequency < 0.0) return null
    val low = maxOf(0.0, minOf(firstFrequency, lastFrequency))
    val high = minOf(maximumFrequency, maxOf(firstFrequency, lastFrequency))
    if (high < low) return null
    val binWidth = sampleRate.toDouble() / fftSize
    val firstBin = ceil(low / binWidth).toInt().coerceAtLeast(0)
    val lastBin = floor(high / binWidth).toInt().coerceAtMost(fftSize / 2 - 1)
    if (firstBin <= lastBin) return firstBin..lastBin
    val centerFrequency = (low + high) * 0.5
    if (centerFrequency > maximumFrequency) return null
    val nearest = (centerFrequency / binWidth).roundToInt().coerceIn(0, fftSize / 2 - 1)
    return nearest..nearest
}

internal fun fft(real: DoubleArray, imaginary: DoubleArray) {
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
