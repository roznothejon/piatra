package dev.piatra.core.backup

// The import's checks (backup, part 2), before anything on the phone is touched.
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupCheck {
    // Reads backup.json. Refuses what isn't a Piatra backup, or one from a newer Piatra whose
    // layout this version can't know.
    fun parse(json: String): BackupData {
        val data = try {
            backupJson.decodeFromString<BackupData>(json)
        } catch (e: Exception) {
            throw BackupException("backup.json can't be read: ${e.message?.lineSequence()?.firstOrNull()}", e)
        }
        if (data.format > BackupData.FORMAT) throw BackupException("This backup was made by a newer Piatra (format ${data.format})")
        return data
    }

    // Links that point nowhere become "none", as deleting does in the app: an item whose
    // recording or list isn't in the backup keeps its text, unlinked. A rule needs its list
    // (deleting a list deletes its rules), so one without is dropped. Without this the database
    // would refuse the whole restore (its foreign keys).
    fun tidy(data: BackupData): BackupData {
        val recordingIds = data.recordings.map { it.id }.toSet()
        val listIds = data.lists.map { it.id }.toSet()
        if (recordingIds.size != data.recordings.size || listIds.size != data.lists.size ||
            data.items.map { it.id }.toSet().size != data.items.size ||
            data.shortcutRules.map { it.id }.toSet().size != data.shortcutRules.size
        ) {
            throw BackupException("The backup lists something twice")
        }
        return data.copy(
            items = data.items.map {
                it.copy(
                    recordingId = it.recordingId?.takeIf(recordingIds::contains),
                    listId = it.listId?.takeIf(listIds::contains),
                )
            },
            shortcutRules = data.shortcutRules.filter { it.listId == null || it.listId in listIds },
        )
    }
}
