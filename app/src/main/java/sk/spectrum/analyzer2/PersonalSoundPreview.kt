package sk.spectrum.analyzer2

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.widget.Button
import android.widget.Toast

/** Playback of saved and recorded personal sounds. */
internal fun MainActivity.togglePersonalSoundPreview(id: String, name: String, button: Button) {
    val samples = soundIdEngine.getPersonalSoundAudio(id)
    if (samples == null) {
        Toast.makeText(this, "Recording for $name is unavailable.", Toast.LENGTH_SHORT).show()
        return
    }
    togglePersonalSoundPreview("saved:$id", samples, name, button)
}

internal fun MainActivity.togglePersonalSoundPreview(samples: ShortArray, name: String, button: Button) {
    togglePersonalSoundPreview("pending:${System.identityHashCode(samples)}", samples, name, button)
}

internal fun MainActivity.togglePersonalSoundPreview(key: String, samples: ShortArray, name: String, button: Button) {
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
        applyPreferredOutputRoute(track)
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

internal fun MainActivity.stopPersonalSoundPreview() {
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
