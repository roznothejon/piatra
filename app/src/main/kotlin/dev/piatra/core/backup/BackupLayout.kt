package dev.piatra.core.backup

import dev.piatra.core.audio.StoredAudio
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// One file in the zip: text written as it is, or a recording's audio (its .opus copied as it is,
// or a leftover .pcm written as WAV).
sealed interface BackupEntry {
    val path: String

    data class Text(override val path: String, val text: String) : BackupEntry
    data class Audio(override val path: String, val fileId: String) : BackupEntry
}

// What goes where in the zip (pure text work, no files, so it's easy to test):
//   backup.json                         everything, for the import
//   notes/2026-10-28 14-05 Pockets.md   one per note
//   lists/Todo.md                       one per list: - [ ] open, then - [x] done
//   recordings/2026-10-30 14-05-12.md   date, length, transcript, what it created
//   recordings/2026-10-30 14-05-12.opus its audio (.wav if it's still PCM), stored only here
//                                       (backup.json points to it)
// Times are written in the phone's time zone, as the app shows them.
object BackupLayout {
    const val DATA_ENTRY = "backup.json"
    const val NO_LIST_NAME = "No list"

    // audioFormat: how a recording's audio is kept, or null if the file is gone (e.g. a failed sync).
    fun entries(data: BackupData, zone: TimeZone, audioFormat: (fileId: String) -> StoredAudio?): List<BackupEntry> {
        val taken = mutableSetOf<String>()
        fun claim(stem: String, ext: String): String {
            val free = dedupe(stem) { "$it.$ext" in taken }
            taken += "$free.$ext"
            return free
        }
        fun local(millis: Long) = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)

        // Recordings first: notes link back to them, and backup.json needs their audio names.
        val recordingStems = data.recordings.associate { it.id to claim("recordings/${stamp(local(it.createdAt), seconds = true)}", "md") }
        val recordings = data.recordings.map { r ->
            val extension = when (audioFormat(r.fileId)) {
                StoredAudio.OPUS -> "opus"
                StoredAudio.PCM -> "wav"
                null -> null
            }
            r.copy(audio = extension?.let { "${recordingStems.getValue(r.id)}.$it" })
        }
        val noteStems = data.items.filter { it.kind == "NOTE" }.associate { note ->
            val words = safeName(note.text.lineSequence().firstOrNull().orEmpty()).take(40).trimEnd()
            note.id to claim("notes/${stamp(local(note.createdAt), seconds = false)} $words".trimEnd(), "md")
        }

        val entries = mutableListOf<BackupEntry>()
        entries += BackupEntry.Text(DATA_ENTRY, backupJson.encodeToString(data.copy(recordings = recordings)))

        for (note in data.items.filter { it.kind == "NOTE" }) {
            val from = note.recordingId?.let(recordingStems::get)
            val text = buildString {
                append("---\ncreated: ${iso(local(note.createdAt))}\n---\n\n")
                append(note.text.trimEnd()).append('\n')
                if (from != null) append("\nFrom recording: [${from.substringAfter('/')}](<../$from.md>)\n")
            }
            entries += BackupEntry.Text("${noteStems.getValue(note.id)}.md", text)
        }

        val listItems = data.items.filter { it.kind == "LIST_ITEM" }
        val listIds = data.lists.map { it.id }.toSet()
        val unlisted = listItems.filter { it.listId !in listIds }
        val lists = data.lists.map { it.title to listItems.filter { item -> item.listId == it.id } } +
            if (unlisted.isEmpty()) emptyList() else listOf(NO_LIST_NAME to unlisted)
        for ((title, items) in lists) {
            val stem = claim("lists/${safeName(title).ifEmpty { "List" }}", "md")
            val text = buildString {
                append("# $title\n\n")
                // Open items first, then done ones, each oldest first (as on the list screen).
                for (item in items.sortedBy { it.done }) append(checkboxLine(item, ::local)).append('\n')
            }
            entries += BackupEntry.Text("$stem.md", text)
        }

        val listTitles = data.lists.associate { it.id to it.title }
        for (r in recordings) {
            val stem = recordingStems.getValue(r.id)
            val created = data.items.filter { it.recordingId == r.id }.sortedBy { it.createdAt }
            entries += BackupEntry.Text("$stem.md", recordingText(r, created, listTitles, noteStems, ::local))
            if (r.audio != null) entries += BackupEntry.Audio(r.audio, r.fileId)
        }
        return entries
    }

    private fun recordingText(
        r: BackupRecording,
        created: List<BackupItem>,
        listTitles: Map<Long, String>,
        noteStems: Map<Long, String>,
        local: (Long) -> LocalDateTime,
    ): String = buildString {
        append("# ${readable(local(r.createdAt))}\n\n")
        append("- Length: ${duration(r.durationMillis)}\n")
        append("- From: ${if (r.boardKey != null) "the board" else "the phone's mic"}\n")
        if (r.audio != null) append("- Audio: [${r.audio.substringAfter('/')}](<${r.audio.substringAfter('/')}>)\n")
        when (r.status) {
            "DONE" -> {}
            "FAILED" -> append("- Failed: ${r.error ?: "unknown error"}\n")
            else -> append("- Not finished processing (${r.status.lowercase()})\n")
        }
        append("\n## Transcript\n\n")
        append(r.transcript?.trim()?.ifEmpty { null } ?: "(none)").append('\n')
        if (created.isNotEmpty()) {
            append("\n## Created\n\n")
            for (item in created) {
                val text = oneLine(item.text)
                val line = when (item.kind) {
                    "NOTE" -> "Note: " + (noteStems[item.id]?.let { "[$text](<../$it.md>)" } ?: text)
                    "LIST_ITEM" -> "On ${item.listId?.let(listTitles::get) ?: NO_LIST_NAME}: " +
                        checkboxLine(item, local).removePrefix("- [ ] ").removePrefix("- [x] ") + if (item.done) " (done)" else ""
                    "ALARM" -> "Alarm: $text" + (item.dueAt?.let { " (${readable(local(it), seconds = false)})" } ?: "")
                    "TIMER" -> "Timer: $text"
                    else -> "${item.kind.lowercase()}: $text"
                }
                append("- $line\n")
            }
        }
    }

    // "- [ ] call mom 🔔 2026-10-31 17:00" or "- [x] buy milk".
    private fun checkboxLine(item: BackupItem, local: (Long) -> LocalDateTime): String {
        val box = if (item.done) "[x]" else "[ ]"
        val due = item.dueAt?.let { " 🔔 ${readable(local(it), seconds = false)}" } ?: ""
        return "- $box ${oneLine(item.text)}$due"
    }

    // Characters Windows, macOS or Android refuse in file names become spaces; runs of spaces
    // become one. Trailing dots are dropped (Windows refuses them too).
    fun safeName(text: String): String =
        text.map { if (it in "/\\:*?\"<>|" || it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')

    // "notes/x" → "notes/x (2)", (3), ... until isTaken says it's free.
    fun dedupe(stem: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(stem)) return stem
        var n = 2
        while (isTaken("$stem ($n)")) n++
        return "$stem ($n)"
    }

    private fun oneLine(text: String) = text.replace(Regex("\\s*\n\\s*"), " ").trim()

    // File-name form: "2026-10-30 14-05-12" (no colons, which some systems refuse).
    private fun stamp(t: LocalDateTime, seconds: Boolean) =
        "${date(t)} ${pad2(t.hour)}-${pad2(t.minute)}" + if (seconds) "-${pad2(t.second)}" else ""

    // Text form: "2026-10-30 14:05:12".
    private fun readable(t: LocalDateTime, seconds: Boolean = true) =
        "${date(t)} ${pad2(t.hour)}:${pad2(t.minute)}" + if (seconds) ":${pad2(t.second)}" else ""

    private fun iso(t: LocalDateTime) = "${date(t)}T${pad2(t.hour)}:${pad2(t.minute)}"

    private fun date(t: LocalDateTime) = "${t.year}-${pad2(t.month.ordinal + 1)}-${pad2(t.day)}"

    // "0:07", "12:34", "1:02:03".
    fun duration(millis: Long): String {
        val total = millis / 1000
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "$h:${pad2(m.toInt())}:${pad2(s.toInt())}" else "$m:${pad2(s.toInt())}"
    }

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')
}
