package dev.piatra.core.obsidian

import dev.piatra.core.Clock
import dev.piatra.core.agent.HandedOffItem
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObsidianWriterTest {
    private val zone = TimeZone.of("Europe/Bucharest")

    // A vault in memory: file name -> text.
    private class FakeVault(var access: Boolean = true, var failWrites: Boolean = false) : ObsidianVault {
        val files = mutableMapOf<String, String>()
        override suspend fun hasAccess(folder: String) = access
        override suspend fun listMarkdownFiles(folder: String, subfolder: String) =
            files.keys.filter { it.substringBeforeLast('/', "") == subfolder }.map { it.substringAfterLast('/') }
        override suspend fun readFile(folder: String, name: String) = files[name]
        override suspend fun writeFile(folder: String, name: String, content: String): Boolean {
            if (failWrites) return false
            files[name] = content
            return true
        }
    }

    private val vault = FakeVault()
    private var folder: String? = "content://vault"
    private var mode = ObsidianMode.ONE_FILE_PER_NOTE

    // Thursday 22 Oct 2026, 14:05 on the phone.
    private val clock = object : Clock {
        override fun nowMillis() = LocalDateTime(2026, 10, 22, 14, 5).toInstant(zone).toEpochMilliseconds()
    }
    private val writer = ObsidianWriter(vault, { folder }, { mode }, clock, { zone })

    @Test
    fun `each note gets its own file, numbered when the minute is taken`() = runTest {
        writer.write("first")
        writer.write("second")

        assertEquals(setOf("Piatra Inbox/2026-10-22 1405.md", "Piatra Inbox/2026-10-22 1405 (2).md"), vault.files.keys)
        assertEquals("---\ncreated: 2026-10-22T14:05\ntags: [piatra]\n---\n\nsecond\n", vault.files["Piatra Inbox/2026-10-22 1405 (2).md"])
    }

    @Test
    fun `one note keeps what was there and adds to the end`() = runTest {
        mode = ObsidianMode.ONE_NOTE
        vault.files["Piatra.md"] = "# My voice notes\n"
        writer.write("first")
        writer.write("second")

        assertEquals(
            "# My voice notes\n\n## 2026-10-22 14:05\n\nfirst\n\n## 2026-10-22 14:05\n\nsecond\n",
            vault.files["Piatra.md"],
        )
    }

    @Test
    fun `nothing is written without a folder, without access, or when the write fails`() = runTest {
        folder = null
        assertNull(writer.write("a"))
        folder = "content://vault"
        vault.access = false
        assertNull(writer.write("b"))
        vault.access = true
        vault.failWrites = true
        assertNull(writer.write("c"))

        assertEquals(emptyMap(), vault.files)
    }

    @Test
    fun `only notes are handed to Obsidian`() = runTest {
        writer.send(HandedOffItem("list_item", "Eggs", list = "Shopping", dueAt = null))
        writer.send(HandedOffItem("note", "Parking is on level 3", list = null, dueAt = null))

        assertEquals(listOf("Parking is on level 3"), vault.files.values.map { it.substringAfterLast("---\n\n").trim() })
    }
}
