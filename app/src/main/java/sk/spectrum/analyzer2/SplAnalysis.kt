package sk.spectrum.analyzer2

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.tan

/** SPL weighting filters, session calculations and bounded waveform/trend history. */
internal enum class SplWeighting { A, C, Z }

internal data class SplSessionSnapshot(
    val generation: Long,
    val samples: Long,
    val sampleRate: Int,
    val fast: List<Double>,
    val slow: List<Double>,
    val meanPower: List<Double>
) {
    val seconds: Double get() = samples.toDouble() / sampleRate
    fun level(weighting: SplWeighting, slowResponse: Boolean): Double =
        (if (slowResponse) slow else fast)[weighting.ordinal]
    fun leq(weighting: SplWeighting): Double = splPowerDb(meanPower[weighting.ordinal])
}

internal fun splPowerDb(power: Double): Double =
    if (power > 1e-12 && power.isFinite()) 10.0 * log10(power) else -120.0

/** Audio-thread owned. The existing A-weighting and reference offset remain in MainActivity. */
internal class SplSessionAnalyzer(private val sampleRate: Int) {
    private class Section(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var s1 = 0.0
        private var s2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            return y
        }
        fun magnitude(w: Double): Double = hypot(b0 + b1 * cos(w) + b2 * cos(2 * w), -b1 * sin(w) - b2 * sin(2 * w)) /
            hypot(1 + a1 * cos(w) + a2 * cos(2 * w), -a1 * sin(w) - a2 * sin(2 * w))
    }

    private val cSections: List<Section>
    private val fastAlpha = exp(-1.0 / (0.125 * sampleRate))
    private val slowAlpha = exp(-1.0 / sampleRate)
    private val fast = DoubleArray(3)
    private val slow = DoubleArray(3)
    private val energy = DoubleArray(3)
    private var count = 0L
    private var generation = -1L
    private var primed = false

    init {
        val scale = 2.0 * PI * 1000 / tan(PI * 1000 / sampleRate)
        fun pole(f: Double): Double = (2 * PI * f - scale) / (2 * PI * f + scale)
        val low = pole(20.598997)
        val high = pole(12194.217)
        val first = Section(1.0, -2.0, 1.0, 2 * low, low * low)
        val last = Section(1.0, 2.0, 1.0, 2 * high, high * high)
        val normalization = 1.0 / (first.magnitude(2 * PI * 1000 / sampleRate) * last.magnitude(2 * PI * 1000 / sampleRate))
        cSections = listOf(Section(normalization, -2 * normalization, normalization, 2 * low, low * low), last)
    }

    fun beginBlock(requestedGeneration: Long) {
        if (generation == requestedGeneration) return
        generation = requestedGeneration
        energy.fill(0.0)
        count = 0
        // Do not reset weighting filters or response powers on a session-statistics reset.
    }

    fun process(input: Double, aPower: Double, fastAPower: Double, slowAPower: Double) {
        var c = input
        for (section in cSections) c = section.process(c)
        val cp = c * c
        val zp = input * input
        fast[0] = fastAPower
        slow[0] = slowAPower
        if (!primed) {
            fast[1] = cp; slow[1] = cp; fast[2] = zp; slow[2] = zp
            primed = true
        } else {
            fast[1] = fastAlpha * fast[1] + (1 - fastAlpha) * cp
            slow[1] = slowAlpha * slow[1] + (1 - slowAlpha) * cp
            fast[2] = fastAlpha * fast[2] + (1 - fastAlpha) * zp
            slow[2] = slowAlpha * slow[2] + (1 - slowAlpha) * zp
        }
        energy[0] += aPower
        energy[1] += cp
        energy[2] += zp
        count++
    }

    fun snapshot(): SplSessionSnapshot = SplSessionSnapshot(
        generation, count, sampleRate,
        fast.map(::splPowerDb), slow.map(::splPowerDb),
        energy.map { if (count > 0) it / count else 0.0 }
    )
}

internal data class SplLogPoint(val seconds: Double, val levels: List<Double>, val leq: List<Double>)

/** UI-thread owned bounded transient plot buffer; Leq itself uses every audio sample. */
internal class SplTrendBuffer {
    val points = java.util.ArrayDeque<SplLogPoint>()
    val minimum = DoubleArray(3) { Double.POSITIVE_INFINITY }
    val maximum = DoubleArray(3) { Double.NEGATIVE_INFINITY }
    var generation = -1L
    var lastSeconds = -1.0
    var sampleInterval = 0.1
    var slowResponse: Boolean? = null
    fun clear() {
        points.clear(); generation = -1; lastSeconds = -1.0; sampleInterval = 0.1; slowResponse = null
        minimum.fill(Double.POSITIVE_INFINITY); maximum.fill(Double.NEGATIVE_INFINITY)
    }
    fun add(snapshot: SplSessionSnapshot, offset: Double, slow: Boolean) {
        if (generation != snapshot.generation || (slowResponse != null && slowResponse != slow)) clear()
        generation = snapshot.generation
        slowResponse = slow
        if (snapshot.seconds - lastSeconds < sampleInterval) return
        if (points.size >= 7200) {
            val compact = points.filterIndexed { index, _ -> index % 2 == 0 }
            points.clear(); points.addAll(compact); sampleInterval *= 2
        }
        val levels = SplWeighting.entries.map { snapshot.level(it, slow) + offset }
        for (i in 0..2) {
            minimum[i] = minOf(minimum[i], levels[i]); maximum[i] = maxOf(maximum[i], levels[i])
        }
        points.addLast(SplLogPoint(snapshot.seconds, levels,
            SplWeighting.entries.map { snapshot.leq(it) + offset }))
        lastSeconds = snapshot.seconds
    }

}

/** Five seconds of real PCM extrema, reduced in 1 ms bins without dropping impulses. */
internal class SplWaveHistory {
    private val minima = FloatArray(5000)
    private val maxima = FloatArray(5000)
    private var next = 0
    private var rate = 0
    private var partialCount = 0
    private var partialMin = 1f
    private var partialMax = -1f
    @Synchronized fun clear() {
        minima.fill(0f); maxima.fill(0f); next = 0; rate = 0
        partialCount = 0; partialMin = 1f; partialMax = -1f
    }
    @Synchronized fun append(samples: ShortArray, count: Int, sampleRate: Int) {
        if (sampleRate <= 0) return
        if (rate != sampleRate) { clear(); rate = sampleRate }
        val binSamples = (sampleRate / 1000).coerceAtLeast(1)
        for (i in 0 until count.coerceIn(0, samples.size)) {
            val value = samples[i] / 32768f
            partialMin = minOf(partialMin, value); partialMax = maxOf(partialMax, value)
            if (++partialCount >= binSamples) {
                minima[next] = partialMin; maxima[next] = partialMax
                next = (next + 1) % minima.size
                partialCount = 0; partialMin = 1f; partialMax = -1f
            }
        }
    }
    @Synchronized fun snapshot(): FloatArray = FloatArray(minima.size * 2).also { result ->
        for (i in minima.indices) {
            val index = (next + i) % minima.size
            result[i * 2] = minima[index]; result[i * 2 + 1] = maxima[index]
        }
    }
}

private data class SplBiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
)

private class SplBiquadFilter(private val coefficients: SplBiquadCoefficients) {
    private var state1 = 0.0
    private var state2 = 0.0

    fun process(input: Double): Double {
        val output = coefficients.b0 * input + state1
        state1 = coefficients.b1 * input - coefficients.a1 * output + state2
        state2 = coefficients.b2 * input - coefficients.a2 * output
        return output
    }
}

/** IEC-style A-weighting derived from the analog poles with a prewarped bilinear transform. */
internal class SplAWeightingFilter(sampleRate: Int) {
    private val stages: List<SplBiquadFilter>

    init {
        val referenceFrequency = 1000.0
        val bilinearScale = 2.0 * PI * referenceFrequency /
                tan(PI * referenceFrequency / sampleRate.coerceAtLeast(1))
        val poleFrequencies = doubleArrayOf(
            20.598997, 20.598997,
            107.65265,
            737.86223,
            12194.217, 12194.217
        )
        val digitalPoles = poleFrequencies.map { frequency ->
            val analogPole = 2.0 * PI * frequency
            (analogPole - bilinearScale) / (analogPole + bilinearScale)
        }
        val sections = listOf(
            SplBiquadCoefficients(1.0, -2.0, 1.0, digitalPoles[0] + digitalPoles[1], digitalPoles[0] * digitalPoles[1]),
            SplBiquadCoefficients(1.0, -2.0, 1.0, digitalPoles[2] + digitalPoles[3], digitalPoles[2] * digitalPoles[3]),
            SplBiquadCoefficients(1.0, 2.0, 1.0, digitalPoles[4] + digitalPoles[5], digitalPoles[4] * digitalPoles[5])
        )
        val omega = 2.0 * PI * referenceFrequency / sampleRate.coerceAtLeast(1)
        val responseAtReference = sections.fold(1.0) { product, section ->
            product * responseMagnitude(section, omega)
        }.coerceAtLeast(1e-30)
        val normalization = 1.0 / responseAtReference
        stages = sections.mapIndexed { index, section ->
            SplBiquadFilter(
                if (index == 0) section.copy(
                    b0 = section.b0 * normalization,
                    b1 = section.b1 * normalization,
                    b2 = section.b2 * normalization
                ) else section
            )
        }
    }

    fun process(input: Double): Double {
        var value = input
        for (stage in stages) value = stage.process(value)
        return value
    }

    private fun responseMagnitude(section: SplBiquadCoefficients, omega: Double): Double {
        val cos1 = cos(omega)
        val sin1 = sin(omega)
        val cos2 = cos(2.0 * omega)
        val sin2 = sin(2.0 * omega)
        val numeratorReal = section.b0 + section.b1 * cos1 + section.b2 * cos2
        val numeratorImaginary = -section.b1 * sin1 - section.b2 * sin2
        val denominatorReal = 1.0 + section.a1 * cos1 + section.a2 * cos2
        val denominatorImaginary = -section.a1 * sin1 - section.a2 * sin2
        return hypot(numeratorReal, numeratorImaginary) /
                hypot(denominatorReal, denominatorImaginary).coerceAtLeast(1e-30)
    }
}
