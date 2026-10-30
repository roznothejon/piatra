package dev.piatra.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

// Everything a backup holds, as it's written to backup.json (export and backup). Plain copies of
// the Room rows, so this file doesn't depend on Room and the import step can read old backups even
// after the tables change. Enums are kept as their names, the way Room stores them.
// Ids are the database's own, so the links between rows (item → recording, item → list) survive.
@Serializable
data class BackupData(
    // Bumped when this layout changes in a way an older import can't read.
    val format: Int = FORMAT,
    val exportedAt: Long,
    // The Room schema version the rows came from (PiatraDatabase.version).
    val databaseVersion: Int,
    val recordings: List<BackupRecording>,
    val lists: List<BackupList>,
    val items: List<BackupItem>,
    val shortcutRules: List<BackupShortcutRule>,
    // The settings worth carrying over, by their DataStore key: true, 10 or "dark".
    val settings: Map<String, JsonPrimitive>,
) {
    companion object {
        const val FORMAT = 1
    }
}

@Serializable
data class BackupRecording(
    val id: Long,
    val fileId: String,
    val createdAt: Long,
    val durationMillis: Long,
    val status: String,
    val transcript: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val agentResult: String? = null,
    val boardKey: String? = null,
    // Where its audio is in the zip ("recordings/2026-10-30 14-05-12.wav"); null if the file was missing.
    val audio: String? = null,
)

@Serializable
data class BackupList(val id: Long, val title: String, val seed: String? = null, val createdAt: Long)

@Serializable
data class BackupItem(
    val id: Long,
    val kind: String,
    val text: String,
    val createdAt: Long,
    val recordingId: Long? = null,
    val dueAt: Long? = null,
    val done: Boolean = false,
    val listId: Long? = null,
    val rang: Boolean = false,
)

@Serializable
data class BackupShortcutRule(
    val id: Long,
    val phrases: String,
    val action: String,
    val listId: Long? = null,
    val keepPhrase: Boolean = false,
    val enabled: Boolean = true,
)

// prettyPrint: one value per line, so backup.json can be read (and diffed) by a person too.
// encodeDefaults: write every field, even one that has its default value (kotlinx.serialization
// leaves those out otherwise, and then "format": 1 would be missing).
// ignoreUnknownKeys: a newer backup with an extra field still imports.
val backupJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}
