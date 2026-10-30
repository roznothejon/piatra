package dev.piatra.data

import dev.piatra.core.agent.ShortcutAction
import dev.piatra.core.backup.BackupException
import dev.piatra.core.backup.BackupItem
import dev.piatra.core.backup.BackupList
import dev.piatra.core.backup.BackupRecording
import dev.piatra.core.backup.BackupShortcutRule

// Room rows ↔ the backup's plain copies (core/backup/BackupData.kt), both ways.

fun RecordingEntity.toBackup() =
    BackupRecording(id, fileId, createdAt, durationMillis, status.name, transcript, error, attempts, agentResult, boardKey)

fun ListEntity.toBackup() = BackupList(id, title, seed, createdAt)

fun ItemEntity.toBackup() = BackupItem(id, kind.name, text, createdAt, recordingId, dueAt, done, listId, rang)

fun ShortcutRuleEntity.toBackup() = BackupShortcutRule(id, phrases, action.name, listId, keepPhrase, enabled)

fun BackupRecording.toEntity() =
    RecordingEntity(id, fileId, createdAt, durationMillis, enumOf<RecordingStatus>(status), transcript, error, attempts, agentResult, boardKey)

fun BackupList.toEntity() = ListEntity(id, title, seed, createdAt)

fun BackupItem.toEntity() = ItemEntity(id, enumOf<ItemKind>(kind), text, createdAt, recordingId, dueAt, done, listId, rang)

fun BackupShortcutRule.toEntity() = ShortcutRuleEntity(id, phrases, enumOf<ShortcutAction>(action), listId, keepPhrase, enabled)

// enumValueOf with a readable error: a name this version doesn't know means a backup it can't restore.
private inline fun <reified T : Enum<T>> enumOf(name: String): T =
    enumValues<T>().firstOrNull { it.name == name } ?: throw BackupException("Unknown ${T::class.simpleName} \"$name\" in the backup")
