package dev.piatra.android

import android.content.Context
import android.util.Log
import dev.piatra.core.PcmFormat
import dev.piatra.core.audio.OggOpus
import dev.piatra.core.audio.OpusDecoder
import dev.piatra.core.audio.OpusEncoder
import dev.piatra.core.audio.StoredAudio
import dev.piatra.core.audio.toLittleEndian
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.abs

// Recording files in app-private storage: filesDir/recordings/<id>.opus (or .pcm).
// Only this app can read them, and they're deleted when the app is uninstalled.
//
// A recording is written as raw PCM while it's being made (the mic hands over PCM in small
// pieces), then compressed to Ogg Opus once it's kept. Board recordings arrive as Opus and are
// stored as they came. Everything that needs the sound (transcription, playback) asks for PCM
// through readPcm, so only this class knows how files are kept.
class RecordingStorage(
    context: Context,
    private val encoder: OpusEncoder,
    private val decoder: OpusDecoder,
) {
    private val directory = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newRecordingId(): String = UUID.randomUUID().toString()

    // A PCM file that's empty again each time it's opened (append = false), or grows (append = true).
    // `buffered()` collects small writes in memory and writes them to disk in bigger blocks.
    fun openForWriting(id: String, append: Boolean = false): OutputStream =
        FileOutputStream(fileFor(id, StoredAudio.PCM), append).buffered()

    // The PCM file's size, while it's still PCM (gives the recording's length).
    fun pcmSizeBytes(id: String): Long = fileFor(id, StoredAudio.PCM).length()

    // How this recording is kept, or null if its file is gone. Opus wins if both exist (a
    // conversion that stopped between writing the new file and deleting the old one).
    fun format(id: String): StoredAudio? = when {
        fileFor(id, StoredAudio.OPUS).exists() -> StoredAudio.OPUS
        fileFor(id, StoredAudio.PCM).exists() -> StoredAudio.PCM
        else -> null
    }

    fun storedSizeBytes(id: String): Long = format(id)?.let { fileFor(id, it).length() } ?: 0

    // The file as it is on disk (the backup copies .opus files unchanged).
    fun openStored(id: String): InputStream = fileFor(id, format(id) ?: StoredAudio.PCM).inputStream()

    // The sound as 16 kHz PCM, whichever way it's kept. A minute is about 1.9 MB.
    fun readPcm(id: String): ByteArray = when (format(id)) {
        StoredAudio.OPUS -> {
            val pcm = ByteArrayOutputStream()
            decoder.decode(OggOpus.read(fileFor(id, StoredAudio.OPUS).readBytes())) { pcm.write(it.toLittleEndian()) }
            pcm.toByteArray()
        }
        else -> fileFor(id, StoredAudio.PCM).readBytes()
    }

    // Board recordings: their Opus packets as they came, no re-encoding.
    fun writeOpus(id: String, packets: List<ByteArray>) {
        val temp = File(directory, "$id.opus.tmp")
        temp.writeBytes(OggOpus.write(packets, PcmFormat.SAMPLE_RATE_HZ))
        // A rename is all or nothing: a half-written .opus never exists under the real name.
        if (!temp.renameTo(fileFor(id, StoredAudio.OPUS))) error("Couldn't save $id.opus")
    }

    // Turns a kept .pcm into .opus. Checks the new file decodes to about the same length before
    // deleting the PCM; on any problem the PCM stays, and readPcm works the same.
    fun compress(id: String): Boolean {
        val pcmFile = fileFor(id, StoredAudio.PCM)
        if (!pcmFile.exists()) return false
        return try {
            val pcm = pcmFile.readBytes()
            val packets = encoder.encode(pcm)
            var samples = 0L
            decoder.decode(packets) { samples += it.size }
            val expected = pcm.size / PcmFormat.BYTES_PER_SAMPLE
            if (abs(samples - expected) > PcmFormat.SAMPLE_RATE_HZ / 10) error("decodes to $samples samples, not $expected")
            writeOpus(id, packets)
            // Already gone: the recording was deleted while this ran, so the new file goes too.
            if (!pcmFile.delete()) {
                fileFor(id, StoredAudio.OPUS).delete()
                return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't compress $id, keeping PCM", e)
            File(directory, "$id.opus.tmp").delete()
            false
        }
    }

    // The one-time conversion of recordings kept as PCM before Opus storage. Only ids that have
    // a database row, so a recording still being written is never touched.
    fun compressAll(ids: List<String>): Int = ids.count { format(it) == StoredAudio.PCM && compress(it) }

    // The import: the staged files (named "<id>.opus" or "<id>.pcm") become the recordings, and
    // every other recording file goes. A rename within the same storage is instant.
    fun replaceAllWith(staging: File) {
        val incoming = staging.listFiles().orEmpty()
        for (file in incoming) {
            if (!file.renameTo(File(directory, file.name))) error("Couldn't move ${file.name} into place")
        }
        val keep = incoming.map { it.name }.toSet()
        directory.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
    }

    fun delete(id: String) {
        for (format in StoredAudio.entries) fileFor(id, format).delete()
    }

    private fun fileFor(id: String, format: StoredAudio) = File(directory, "$id.${format.extension}")

    private companion object {
        const val TAG = "RecordingStorage"
    }
}
