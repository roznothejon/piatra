package dev.piatra.android

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.piatra.core.RecordingProcessingQueue
import dev.piatra.core.ReminderScheduler
import dev.piatra.core.audio.OggOpus
import dev.piatra.core.audio.StoredAudio
import dev.piatra.core.backup.BackupCheck
import dev.piatra.core.backup.BackupData
import dev.piatra.core.backup.BackupException
import dev.piatra.core.backup.BackupLayout
import dev.piatra.core.backup.WavHeader
import dev.piatra.data.BackupDao
import dev.piatra.data.ItemDao
import dev.piatra.data.ItemKind
import dev.piatra.data.RecordingDao
import dev.piatra.data.Settings
import dev.piatra.data.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipInputStream

// How the import is going, for the Settings row and its "Replace everything?" dialog.
sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    // Read and checked, nothing changed yet: waiting for the user to confirm.
    data class Ready(val backup: Counts, val now: Counts, val exportedAt: Long) : ImportState
    data object Restoring : ImportState
    data class Done(val restored: Counts) : ImportState
    // replaced: whether anything was replaced before it failed (false: the phone is as it was).
    data class Failed(val message: String, val replaced: Boolean = false) : ImportState
}

data class Counts(val recordings: Int, val items: Int, val lists: Int)

// Restores a zip made by BackupExport, replacing everything.
// Two halves, so a bad zip can't leave the app half-replaced:
//  1. read: backup.json and every audio file go into a staging folder and are checked. Nothing
//     the app uses is touched; a problem stops here.
//  2. restore (after the user confirms): reminders off, the tables swapped in one transaction,
//     the audio moved into place, settings replaced, then reminders and the queue start again.
class BackupImport(
    private val context: Context,
    private val backupDao: BackupDao,
    private val recordings: RecordingDao,
    private val items: ItemDao,
    private val storage: RecordingStorage,
    private val settings: Settings,
    private val scheduler: ReminderScheduler,
    private val reminders: ReminderAlerts,
    private val queue: RecordingProcessingQueue,
    private val playback: RecordingPlayback,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    private val staging = File(context.filesDir, "restore")
    private var staged: BackupData? = null

    fun read(source: Uri) {
        if (_state.value == ImportState.Reading || _state.value == ImportState.Restoring) return
        _state.value = ImportState.Reading
        scope.launch {
            _state.value = try {
                val data = stage(source)
                staged = data
                ImportState.Ready(
                    backup = Counts(data.recordings.size, data.items.size, data.lists.size),
                    now = Counts(recordings.all().size, items.all().size, backupDao.countLists()),
                    exportedAt = data.exportedAt,
                )
            } catch (e: Exception) {
                Log.w(TAG, "couldn't read the backup", e)
                staging.deleteRecursively()
                ImportState.Failed(if (e is BackupException) e.message!! else "Not a Piatra backup (${e.message ?: e.javaClass.simpleName})")
            }
        }
    }

    // "Cancel" in the dialog, or OK after Done/Failed.
    fun dismiss() {
        if (_state.value == ImportState.Reading || _state.value == ImportState.Restoring) return
        staged = null
        staging.deleteRecursively()
        _state.value = ImportState.Idle
    }

    fun confirm() {
        val data = staged ?: return
        if (_state.value !is ImportState.Ready) return
        _state.value = ImportState.Restoring
        scope.launch {
            var replaced = false
            _state.value = try {
                restore(data) { replaced = true }
                ImportState.Done(Counts(data.recordings.size, data.items.size, data.lists.size))
            } catch (e: Exception) {
                Log.e(TAG, "restore failed", e)
                // The tables are as they were; their alarms were already turned off, so set them again.
                if (!replaced) runCatching { reminders.restore() }
                ImportState.Failed(e.message ?: e.javaClass.simpleName, replaced)
            } finally {
                staged = null
                staging.deleteRecursively()
            }
        }
    }

    // Half 1. Audio entries are saved under a number first (the zip's own names are never used
    // as file names, so a strange name can't write outside the folder), then renamed to their
    // recording's file id once backup.json says which is which.
    private fun stage(source: Uri): BackupData {
        staging.deleteRecursively()
        staging.mkdirs()
        var json: String? = null
        val audioByPath = mutableMapOf<String, File>()
        val input = context.contentResolver.openInputStream(source) ?: throw BackupException("Couldn't open the file")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    name == BackupLayout.DATA_ENTRY -> json = zip.readBytes().decodeToString()
                    name.endsWith(".opus") || name.endsWith(".wav") -> {
                        val file = File(staging, "${audioByPath.size}.part")
                        file.outputStream().use { zip.copyTo(it) }
                        audioByPath[name] = file
                    }
                    // The Markdown files are only for reading.
                }
            }
        }
        val data = BackupCheck.tidy(BackupCheck.parse(json ?: throw BackupException("No backup.json in this zip")))
        for (r in data.recordings) {
            val file = r.audio?.let(audioByPath::remove) ?: continue
            if (r.audio.endsWith(".opus")) {
                // Reads every page and checks its checksum: a damaged file stops the import here.
                OggOpus.read(file.readBytes())
                file.renameTo(File(staging, "${r.fileId}.${StoredAudio.OPUS.extension}"))
            } else {
                val pcm = File(staging, "${r.fileId}.${StoredAudio.PCM.extension}")
                DataInputStream(file.inputStream().buffered()).use { wav ->
                    val header = ByteArray(WavHeader.SIZE)
                    wav.readFully(header) // all 44 bytes, or an exception if the file is shorter
                    if (!WavHeader.isOurs(header)) throw BackupException("${r.audio} isn't a Piatra WAV")
                    pcm.outputStream().use { wav.copyTo(it) }
                }
                file.delete()
            }
        }
        audioByPath.values.forEach { it.delete() } // audio no recording points to
        return data
    }

    // Half 2.
    private suspend fun restore(data: BackupData, onReplaced: () -> Unit) {
        playback.stop()
        // Today's reminders: alarms off and notifications down (their ids may mean other items soon).
        for (item in items.all()) if (item.kind == ItemKind.LIST_ITEM && item.dueAt != null) scheduler.cancel(item.id)

        backupDao.replaceAll(
            recordings = data.recordings.map { it.toEntity() },
            lists = data.lists.map { it.toEntity() },
            items = data.items.map { it.toEntity() },
            rules = data.shortcutRules.map { it.toEntity() },
        )
        onReplaced()
        storage.replaceAllWith(staging)
        settings.restoreBackupValues(data.settings)

        // The restored reminders: alarms set, missed ones ring, rung ones shown again.
        reminders.restore()
        // Anything not finished when the backup was made is processed now.
        queue.resume()
        Log.i(TAG, "restored ${data.recordings.size} recordings, ${data.items.size} items, ${data.lists.size} lists")
    }

    private companion object {
        const val TAG = "PiatraBackup"
    }
}
