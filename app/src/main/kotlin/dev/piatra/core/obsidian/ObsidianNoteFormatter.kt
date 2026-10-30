package dev.piatra.core.obsidian

import kotlinx.datetime.LocalDateTime

// Where notes go inside the chosen vault folder.
enum class ObsidianMode {
    // One Markdown file per note: "Piatra Inbox/2026-10-22 1405.md". The default.
    ONE_FILE_PER_NOTE,

    // Every note added to the end of one note, "Piatra.md", under a "## 2026-10-22 14:05" heading.
    ONE_NOTE,
}

// What to do in the vault for one note. fileName is relative to the vault folder.
sealed interface ObsidianWrite {
    // Create a new file with this content.
    data class NewFile(val fileName: String, val content: String) : ObsidianWrite

    // Add block to the end of the file (creating it if it isn't there).
    data class Append(val fileName: String, val block: String) : ObsidianWrite
}

// Pure text work, no files: easy to test. Ported from upstream's ObsidianNoteFormatter, minus its
// "named note" mode, custom tags and user-chosen subfolder.
object ObsidianNoteFormatter {
    const val INBOX_FOLDER = "Piatra Inbox"
    const val ONE_NOTE_NAME = "Piatra.md"

    fun plan(mode: ObsidianMode, content: String, at: LocalDateTime): ObsidianWrite {
        val date = "${at.year}-${pad2(at.month.ordinal + 1)}-${pad2(at.day)}"
        val time = "${pad2(at.hour)}:${pad2(at.minute)}"
        return when (mode) {
            ObsidianMode.ONE_FILE_PER_NOTE -> ObsidianWrite.NewFile(
                fileName = "$INBOX_FOLDER/$date ${time.replace(":", "")}.md",
                // The "---" block on top is frontmatter: properties Obsidian shows and can search.
                content = "---\ncreated: ${date}T$time\ntags: [piatra]\n---\n\n${content.trimEnd()}\n",
            )
            ObsidianMode.ONE_NOTE -> ObsidianWrite.Append(
                fileName = ONE_NOTE_NAME,
                block = "## $date $time\n\n${content.trimEnd()}\n",
            )
        }
    }

    // The existing text, one blank line, then the new block.
    fun mergeAppend(existing: String?, block: String): String {
        val base = existing?.trimEnd()
        return if (base.isNullOrEmpty()) block else "$base\n\n$block"
    }

    // "2026-10-22 1405.md" -> "2026-10-22 1405 (2).md", (3), ... until isTaken says it's free.
    // Two notes in the same minute would otherwise get the same name.
    fun dedupeName(fileName: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(fileName)) return fileName
        val stem = fileName.removeSuffix(".md")
        var n = 2
        while (isTaken("$stem ($n).md")) n++
        return "$stem ($n).md"
    }

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')
}
