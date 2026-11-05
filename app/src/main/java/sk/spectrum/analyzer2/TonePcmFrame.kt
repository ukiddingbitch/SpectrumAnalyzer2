package sk.spectrum.analyzer2

import android.media.AudioTrack

import kotlin.math.floor

/** Thread-safe PCM history and playback-position alignment. */
/** Map a wrapping AudioTrack counter into the current written-frame epoch. */
internal fun unwrapTonePlaybackFrame(raw: Long, written: Long): Long {
    val span = 0x100000000L
    var position = (written and -span) + (raw and 0xffffffffL)
    if (position > written && position >= span) position -= span
    return position.coerceIn(0L, written)
}

internal class TonePcmFrame(sampleCount: Int) {
    private val lock = Any()
    private val samples = ShortArray(sampleCount.coerceAtLeast(1))
    private var writeIndex = 0
    private var availableSamples = 0
    private var writtenFrames = 0L
    private var clockFrame = 0L
    private var clockNanos = 0L
    private var clockRate = 0

    fun publish(source: ShortArray, frameCount: Int, sourceOffset: Int = 0) {
        val offset = sourceOffset.coerceIn(0, source.size)
        val sourceCount = frameCount.coerceIn(0, source.size - offset)
        val copyCount = minOf(sourceCount, samples.size)
        if (copyCount <= 0) return
        val sourceStart = offset + sourceCount - copyCount
        synchronized(lock) {
            for (index in 0 until copyCount) {
                samples[writeIndex] = source[sourceStart + index]
                writeIndex = (writeIndex + 1) % samples.size
            }
            availableSamples = (availableSamples + copyCount).coerceAtMost(samples.size)
            writtenFrames += sourceCount
        }
    }

    fun updatePlaybackPosition(rawFrame: Long, nanoTime: Long, sampleRate: Int) = synchronized(lock) {
        clockFrame = unwrapTonePlaybackFrame(rawFrame, writtenFrames)
        clockNanos = nanoTime
        clockRate = sampleRate.coerceAtLeast(0)
    }

    private fun copyEndingAt(destination: ShortArray, count: Int, end: Long): Boolean {
        if (count <= 0 || count > destination.size || count > samples.size ||
            end > writtenFrames || end - count < writtenFrames - availableSamples) return false
        val behind = writtenFrames - end
        val startIndex = ((writeIndex - behind - count) % samples.size + samples.size).toInt() % samples.size
        val firstCount = minOf(count, samples.size - startIndex)
        samples.copyInto(destination, 0, startIndex, startIndex + firstCount)
        if (count > firstCount) samples.copyInto(destination, firstCount, 0, count - firstCount)
        return true
    }

    fun copyForPlaybackTo(destination: ShortArray, count: Int, lookAhead: Int = 0, nowNanos: Long = System.nanoTime()): Boolean = synchronized(lock) {
        if (clockRate <= 0 || nowNanos - clockNanos > 250_000_000L || clockNanos - nowNanos > 1_000_000_000L) return@synchronized false
        val estimated = clockFrame + kotlin.math.floor((nowNanos - clockNanos) * (clockRate / 1_000_000_000.0)).toLong()
        val end = (estimated.coerceIn(0L, writtenFrames) + lookAhead.coerceAtLeast(0)).coerceAtMost(writtenFrames)
        copyEndingAt(destination, count, end)
    }

    fun copyLatestTo(destination: ShortArray, count: Int): Boolean = synchronized(lock) {
        copyEndingAt(destination, count, writtenFrames)
    }

    fun clear() = synchronized(lock) {
        samples.fill(0); writeIndex = 0; availableSamples = 0; writtenFrames = 0
        clockFrame = 0; clockNanos = 0; clockRate = 0
    }
}
