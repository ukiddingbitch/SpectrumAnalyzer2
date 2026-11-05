package sk.spectrum.analyzer2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.json.JSONObject

import kotlin.math.roundToInt

/** Personal sound import/export, archives and WAV handling. */
internal fun MainActivity.requestPersonalSoundExportFolder() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }
    try {
        startActivityForResult(intent, personalSoundExportFolderRequestCode)
    } catch (_: Exception) {
        Toast.makeText(this, "Could not choose an export folder.", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.exportPersonalSoundProfiles() {
    if (!personalSoundExportInProgress.compareAndSet(false, true)) {
        Toast.makeText(this, "An export is already running.", Toast.LENGTH_SHORT).show()
        return
    }
    val profiles = soundIdEngine.getPersonalSounds()
    if (profiles.isEmpty()) {
        personalSoundExportInProgress.set(false)
        Toast.makeText(this, "Record and save a personal sound first.", Toast.LENGTH_SHORT).show()
        return
    }
    val preferences = getSharedPreferences("personal_sound_export", Context.MODE_PRIVATE)
    val exportedIds = preferences.getStringSet("exported_profile_ids", emptySet()).orEmpty()
    val newProfiles = profiles.filter { it.id !in exportedIds }
    if (newProfiles.isEmpty()) {
        personalSoundExportInProgress.set(false)
        Toast.makeText(this, "There are no new samples to export.", Toast.LENGTH_SHORT).show()
        return
    }
    val folderString = preferences.getString("folder_uri", null)
    if (folderString.isNullOrBlank()) {
        personalSoundExportInProgress.set(false)
        requestPersonalSoundExportFolder()
        return
    }
    val folderUri = Uri.parse(folderString)
    val hasWritePermission = contentResolver.persistedUriPermissions.any {
        it.uri == folderUri && it.isWritePermission
    }
    if (!hasWritePermission) {
        preferences.edit().remove("folder_uri").apply()
        personalSoundExportInProgress.set(false)
        requestPersonalSoundExportFolder()
        return
    }

    Toast.makeText(this, "Saving new WAV samples…", Toast.LENGTH_SHORT).show()
    Thread({
        var exportedCount = 0
        val failures = mutableListOf<String>()
        val markedExported = exportedIds.toMutableSet()
        try {
            val folderDocumentId = android.provider.DocumentsContract.getTreeDocumentId(folderUri)
            val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(folderUri, folderDocumentId)
            for (profile in newProfiles) {
                try {
                    val audio = soundIdEngine.getPersonalSoundAudio(profile.id)
                        ?: error("Recording is missing for ${profile.name}.")
                    val safeName = profile.name.trim()
                        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
                        .trim('.', '_', '-')
                        .take(48)
                        .ifBlank { "sound" }
                    val fileName = "${safeName}__${profile.id.take(8)}.wav"
                    val documentUri = android.provider.DocumentsContract.createDocument(
                        contentResolver,
                        parentUri,
                        "audio/wav",
                        fileName
                    ) ?: error("Could not create $fileName in the selected folder.")
                    val wav = encodePersonalSoundWav(audio)
                    val output = contentResolver.openOutputStream(documentUri, "w")
                        ?: error("Could not write $fileName.")
                    output.use { it.write(wav) }
                    markedExported.add(profile.id)
                    preferences.edit().putStringSet("exported_profile_ids", markedExported.toSet()).apply()
                    exportedCount++
                } catch (error: Exception) {
                    failures.add(error.message ?: "Could not export ${profile.name}.")
                }
            }
        } catch (error: Exception) {
            failures.add(error.message ?: "Could not access the selected folder.")
        } finally {
            personalSoundExportInProgress.set(false)
            runOnUiThread {
                val message = when {
                    failures.isEmpty() -> "Saved $exportedCount new WAV sample(s) to the selected folder."
                    exportedCount == 0 -> "Could not export samples: ${failures.first()}"
                    else -> "Saved $exportedCount sample(s); skipped ${failures.size}. ${failures.first()}"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }, "PersonalSoundExporter").apply { priority = Thread.NORM_PRIORITY; start() }
}

internal fun MainActivity.importPersonalSoundProfiles() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
            "audio/wav",
            "audio/x-wav",
            "audio/vnd.wave"
        ))
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }
    try {
        startActivityForResult(intent, personalSoundImportRequestCode)
    } catch (_: Exception) {
        Toast.makeText(this, "Could not open sample files.", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.importPersonalSoundFiles(uris: List<Uri>) {
    if (!personalSoundImportInProgress.compareAndSet(false, true)) {
        Toast.makeText(this, "A sample import is already running.", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(this, "Importing sound samples…", Toast.LENGTH_SHORT).show()
    Thread({
        var imported = 0
        val failures = mutableListOf<String>()
        try {
            for (uri in uris) {
                val fileName = getImportedDocumentName(uri)
                try {
                    val bytes = readImportedDocument(uri, 32 * 1024 * 1024)
                    when {
                        isZipArchive(bytes) -> imported += importPersonalSoundArchive(bytes)
                        isWavFile(bytes) -> {
                            val audio = decodePersonalSoundWav(bytes)
                                ?: error("Unsupported WAV format or duration. Use a PCM or 32-bit float WAV between 1 and 30 seconds.")
                            val soundName = fileName.substringBeforeLast('.', fileName)
                                .replace(Regex("__[0-9a-fA-F]{8}$"), "")
                                .trim().take(80).ifBlank { "Imported sound" }
                            val result = arrayOfNulls<String>(1)
                            val finished = java.util.concurrent.CountDownLatch(1)
                            soundIdEngine.createPersonalSoundFromAudio(soundName, audio) { errorMessage ->
                                result[0] = errorMessage
                                finished.countDown()
                            }
                            finished.await()
                            result[0]?.let { error(it) }
                            imported++
                        }
                        else -> error("Choose a My Sound ID ZIP archive or WAV recording.")
                    }
                } catch (error: Exception) {
                    failures.add("$fileName: ${error.message ?: "could not import"}")
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failures.add("Import was interrupted.")
        } finally {
            personalSoundImportInProgress.set(false)
            runOnUiThread {
                personalSoundListRefresh?.invoke()
                val message = when {
                    failures.isEmpty() -> "Imported $imported sound sample(s)."
                    imported == 0 -> "Could not import samples: ${failures.first()}"
                    else -> "Imported $imported sample(s); skipped ${failures.size}. ${failures.first()}"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }, "PersonalSoundImporter").apply { priority = Thread.NORM_PRIORITY; start() }
}

internal fun MainActivity.getImportedDocumentName(uri: Uri): String {
    val name = contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    return name?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "sample"
}

internal fun MainActivity.readImportedDocument(uri: Uri, maxBytes: Int): ByteArray {
    val input = contentResolver.openInputStream(uri) ?: error("Could not read the selected file.")
    return input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "File is too large." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

internal fun MainActivity.isZipArchive(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
            (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())

internal fun MainActivity.isWavFile(bytes: ByteArray): Boolean =
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"

internal fun MainActivity.encodePersonalSoundWav(samples: ShortArray): ByteArray {
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

internal fun MainActivity.importPersonalSoundArchive(bytes: ByteArray): Int {
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

internal fun MainActivity.decodePersonalSoundWav(wav: ByteArray): ShortArray? {
    if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
        String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var format = -1
    var channels = 0
    var sampleRate = 0
    var bitsPerSample = 0
    var blockAlign = 0
    var dataOffset = -1
    var dataSize = 0
    while (offset + 8 <= wav.size) {
        val chunkId = String(wav, offset, 4, Charsets.US_ASCII)
        val chunkSizeLong = buffer.getInt(offset + 4).toLong() and 0xffff_ffffL
        val chunkData = offset + 8
        if (chunkSizeLong > wav.size - chunkData) return null
        val chunkSize = chunkSizeLong.toInt()
        when (chunkId) {
            "fmt " -> {
                if (chunkSize < 16) return null
                format = buffer.getShort(chunkData).toInt() and 0xffff
                if (format == 0xfffe && chunkSize >= 40) {
                    // WAVE_FORMAT_EXTENSIBLE stores the underlying PCM/float format in its GUID.
                    format = buffer.getInt(chunkData + 24)
                }
                channels = buffer.getShort(chunkData + 2).toInt() and 0xffff
                sampleRate = buffer.getInt(chunkData + 4)
                blockAlign = buffer.getShort(chunkData + 12).toInt() and 0xffff
                bitsPerSample = buffer.getShort(chunkData + 14).toInt() and 0xffff
            }
            "data" -> {
                dataOffset = chunkData
                dataSize = chunkSize
            }
        }
        val next = chunkData.toLong() + chunkSizeLong + (chunkSizeLong and 1L)
        if (next > wav.size) break
        offset = next.toInt()
    }
    val bytesPerSample = bitsPerSample / 8
    if ((format != 1 && format != 3) || channels !in 1..8 || sampleRate !in 8_000..192_000 ||
        bitsPerSample !in setOf(8, 16, 24, 32) || (format == 3 && bitsPerSample != 32) ||
        blockAlign != channels * bytesPerSample || dataOffset < 0 || dataSize < blockAlign) return null
    val frameCount = dataSize / blockAlign
    val durationSeconds = frameCount.toDouble() / sampleRate
    if (durationSeconds < 0.975 || durationSeconds > 30.0) return null

    val mono = FloatArray(frameCount) { frame ->
        var mixed = 0.0
        val frameOffset = dataOffset + frame * blockAlign
        for (channel in 0 until channels) {
            val sampleOffset = frameOffset + channel * bytesPerSample
            val sample = when {
                format == 3 -> Float.fromBits(buffer.getInt(sampleOffset)).toDouble()
                    .takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
                bitsPerSample == 8 -> ((wav[sampleOffset].toInt() and 0xff) - 128) / 128.0
                bitsPerSample == 16 -> buffer.getShort(sampleOffset).toInt() / 32768.0
                bitsPerSample == 24 -> {
                    var value = (wav[sampleOffset].toInt() and 0xff) or
                            ((wav[sampleOffset + 1].toInt() and 0xff) shl 8) or
                            ((wav[sampleOffset + 2].toInt() and 0xff) shl 16)
                    if (value and 0x800000 != 0) value = value or -0x1000000
                    value / 8_388_608.0
                }
                else -> buffer.getInt(sampleOffset) / 2_147_483_648.0
            }
            mixed += sample
        }
        (mixed / channels).coerceIn(-1.0, 1.0).toFloat()
    }
    val outputCount = (frameCount.toLong() * 48_000L / sampleRate).toInt()
    if (outputCount < 46_800 || outputCount > 48_000 * 30) return null
    return ShortArray(outputCount) { index ->
        val sourcePosition = index.toDouble() * sampleRate / 48_000.0
        val first = sourcePosition.toInt().coerceIn(0, mono.lastIndex)
        val second = (first + 1).coerceAtMost(mono.lastIndex)
        val fraction = (sourcePosition - first).coerceIn(0.0, 1.0)
        ((mono[first] * (1.0 - fraction) + mono[second] * fraction) * 32767.0)
            .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
