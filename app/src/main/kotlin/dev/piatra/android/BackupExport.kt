package dev.piatra.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.piatra.core.Clock
import dev.piatra.core.backup.BackupData
import dev.piatra.core.backup.BackupEntry
import dev.piatra.core.backup.BackupLayout
import dev.piatra.core.backup.WavHeader
import dev.piatra.data.ItemDao
import dev.piatra.data.ListDao
import dev.piatra.data.PiatraDatabase
import dev.piatra.data.RecordingDao
import dev.piatra.data.Settings
import dev.piatra.data.ShortcutRuleDao
import dev.piatra.data.toBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// How the export is going, for the Settings row.
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val done: Int, val total: Int) : ExportState
    data class Done(val recordings: Int, val items: Int) : ExportState
    data class Failed(val message: String) : ExportState
}

// Writes everything into one zip at a place the user picked in Android's "Save as" dialog
// (export and backup). The layout is BackupLayout's; this class only reads the database and
// writes bytes. It runs in its own scope, so leaving the Settings screen doesn't stop it halfway.
class BackupExport(
    private val context: Context,
    private val database: PiatraDatabase,
    private val recordings: RecordingDao,
    private val items: ItemDao,
    private val lists: ListDao,
    private val rules: ShortcutRuleDao,
    private val storage: RecordingStorage,
    private val settings: Settings,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state

    fun start(target: Uri) {
        if (_state.value is ExportState.Running) return
        _state.value = ExportState.Running(0, 0)
        scope.launch {
            _state.value = try {
                export(target)
            } catch (e: Exception) {
                Log.w(TAG, "export failed", e)
                // Don't leave half a zip behind (best effort: some places can't delete).
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                ExportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun export(target: Uri): ExportState.Done {
        val data = BackupData(
            exportedAt = clock.nowMillis(),
            databaseVersion = database.openHelper.readableDatabase.version,
            recordings = recordings.all().map { it.toBackup() },
            lists = lists.all().map { it.toBackup() },
            items = items.all().map { it.toBackup() },
            shortcutRules = rules.all().map { it.toBackup() },
            settings = settings.backupValues(),
        )
        val entries = BackupLayout.entries(data, TimeZone.currentSystemDefault(), storage::format)

        // "w": write, starting from an empty file. The ContentResolver hands out a stream to
        // wherever the picked Uri points (Downloads, a USB stick, a cloud app...).
        val output = context.contentResolver.openOutputStream(target, "w") ?: error("Couldn't open the file")
        ZipOutputStream(output.buffered()).use { zip ->
            entries.forEachIndexed { index, entry ->
                zip.putNextEntry(ZipEntry(entry.path))
                when (entry) {
                    is BackupEntry.Text -> zip.write(entry.text.encodeToByteArray())
                    is BackupEntry.Audio -> {
                        if (entry.path.endsWith(".wav")) zip.write(WavHeader.forPcm(storage.pcmSizeBytes(entry.fileId)))
                        // copyTo: reads and writes in 8 KB chunks, so a long recording never sits in memory.
                        storage.openStored(entry.fileId).use { it.copyTo(zip) }
                    }
                }
                zip.closeEntry()
                _state.value = ExportState.Running(index + 1, entries.size)
            }
        }
        Log.i(TAG, "exported ${data.recordings.size} recordings, ${data.items.size} items, ${entries.size} files")
        return ExportState.Done(data.recordings.size, data.items.size)
    }

    private companion object {
        const val TAG = "PiatraBackup"
    }
}
