package dev.piatra.core.obsidian

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

// Ported from upstream's ObsidianNoteFormatterTest (the modes and names Piatra keeps).
class ObsidianNoteFormatterTest {
    private val at = LocalDateTime(2026, 6, 8, 14, 5)

    @Test
    fun `one file per note goes in the inbox folder, zero-padded, with frontmatter`() {
        val write = ObsidianNoteFormatter.plan(ObsidianMode.ONE_FILE_PER_NOTE, "buy milk\n\n", at)

        assertEquals(
            ObsidianWrite.NewFile(
                "Piatra Inbox/2026-06-08 1405.md",
                "---\ncreated: 2026-06-08T14:05\ntags: [piatra]\n---\n\nbuy milk\n",
            ),
            write,
        )
    }

    @Test
    fun `one note gets a dated heading per note`() {
        val write = ObsidianNoteFormatter.plan(ObsidianMode.ONE_NOTE, "note text", at)

        assertEquals(ObsidianWrite.Append("Piatra.md", "## 2026-06-08 14:05\n\nnote text\n"), write)
    }

    @Test
    fun `appending leaves one blank line after the existing text`() {
        assertEquals("# Title\nold\n\n## new\n", ObsidianNoteFormatter.mergeAppend("# Title\nold\n\n\n", "## new\n"))
        assertEquals("## new\n", ObsidianNoteFormatter.mergeAppend(null, "## new\n"))
        assertEquals("## new\n", ObsidianNoteFormatter.mergeAppend("", "## new\n"))
    }

    @Test
    fun `a taken name gets the next free number`() {
        val taken = setOf("Piatra Inbox/2026-06-08 1405.md", "Piatra Inbox/2026-06-08 1405 (2).md")

        assertEquals("Piatra Inbox/2026-06-08 1405 (3).md", ObsidianNoteFormatter.dedupeName("Piatra Inbox/2026-06-08 1405.md") { it in taken })
        assertEquals("a.md", ObsidianNoteFormatter.dedupeName("a.md") { false })
    }
}
