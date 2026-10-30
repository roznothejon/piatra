package dev.piatra.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCheckTest {
    private val data = BackupData(
        exportedAt = 1, databaseVersion = 10,
        recordings = listOf(BackupRecording(1, "a", 0, 1000, "DONE")),
        lists = listOf(BackupList(10, "Todo", "todo", 0)),
        items = listOf(
            BackupItem(100, "LIST_ITEM", "ok", 0, recordingId = 1, listId = 10),
            BackupItem(101, "LIST_ITEM", "orphan", 0, recordingId = 7, listId = 77),
        ),
        shortcutRules = listOf(BackupShortcutRule(1, "buy", "ADD_TO_LIST", listId = 10), BackupShortcutRule(2, "x", "ADD_TO_LIST", listId = 77)),
        settings = emptyMap(),
    )

    @Test
    fun `an export reads back`() {
        assertEquals(data, BackupCheck.parse(backupJson.encodeToString(data)))
    }

    @Test
    fun `a newer format or something else entirely is refused`() {
        val newer = assertFailsWith<BackupException> { BackupCheck.parse(backupJson.encodeToString(data.copy(format = 2))) }
        assertTrue("newer Piatra" in newer.message!!)
        assertFailsWith<BackupException> { BackupCheck.parse("{\"hello\": 1}") }
        assertFailsWith<BackupException> { BackupCheck.parse("not json") }
    }

    @Test
    fun `links to rows that aren't there are cleared, and rules without their list dropped`() {
        val tidy = BackupCheck.tidy(data)
        assertEquals(1L, tidy.items[0].recordingId)
        assertNull(tidy.items[1].recordingId)
        assertNull(tidy.items[1].listId)
        assertEquals(listOf(1L), tidy.shortcutRules.map { it.id })
    }

    @Test
    fun `the same id twice is refused`() {
        assertFailsWith<BackupException> { BackupCheck.tidy(data.copy(lists = data.lists + data.lists)) }
    }

    @Test
    fun `only our own wav header is taken back`() {
        assertTrue(WavHeader.isOurs(WavHeader.forPcm(12_345)))
        assertTrue(!WavHeader.isOurs(WavHeader.forPcm(1).also { it[24] = 0x44 })) // 44.1 kHz-ish
        assertTrue(!WavHeader.isOurs(ByteArray(10)))
    }
}
