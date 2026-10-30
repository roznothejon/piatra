package dev.piatra.core.backup

import dev.piatra.core.audio.StoredAudio
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupLayoutTest {
    private val zone = TimeZone.UTC
    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0) =
        LocalDateTime(2026, 10, day, hour, minute, second).toInstant(zone).toEpochMilliseconds()

    private val recording = BackupRecording(
        id = 1, fileId = "abc", createdAt = at(30, 14, 5, 12), durationMillis = 7_300,
        status = "DONE", transcript = "Note pockets for water bottles. Remind me to call mom at 5.", boardKey = "1/2/3",
    )
    private val data = BackupData(
        exportedAt = at(30, 20, 0),
        databaseVersion = 10,
        recordings = listOf(recording, recording.copy(id = 2, fileId = "gone", createdAt = at(30, 15, 0), status = "FAILED", error = "no model", transcript = null, boardKey = null)),
        lists = listOf(BackupList(10, "Todo", "todo", 0), BackupList(11, "Hardware: parts", null, 0)),
        items = listOf(
            BackupItem(100, "NOTE", "Pockets for water bottles\nfrom scrap jeans", at(30, 14, 5), recordingId = 1),
            BackupItem(101, "LIST_ITEM", "Call mom", at(30, 14, 5), recordingId = 1, dueAt = at(30, 17, 0), listId = 10),
            BackupItem(102, "LIST_ITEM", "Buy milk", at(29, 9, 0), done = true, listId = 10),
            BackupItem(103, "LIST_ITEM", "Lost one", at(29, 9, 0), listId = null),
            BackupItem(104, "ALARM", "Wake up", at(30, 14, 5), recordingId = 1, dueAt = at(31, 7, 0)),
        ),
        shortcutRules = listOf(BackupShortcutRule(1, "buy\nget", "ADD_TO_LIST", listId = 11)),
        settings = mapOf("theme" to JsonPrimitive("dark"), "snooze_minutes" to JsonPrimitive(10)),
    )
    private val entries = BackupLayout.entries(data, zone) { if (it == "abc") StoredAudio.OPUS else null }
    private fun text(path: String) = (entries.single { it.path == path } as BackupEntry.Text).text

    @Test
    fun `every file has its place`() {
        assertEquals(
            listOf(
                "backup.json",
                "notes/2026-10-30 14-05 Pockets for water bottles.md",
                "lists/Todo.md",
                "lists/Hardware parts.md",
                "lists/No list.md",
                "recordings/2026-10-30 14-05-12.md",
                "recordings/2026-10-30 14-05-12.opus",
                "recordings/2026-10-30 15-00-00.md",
            ),
            entries.map { it.path },
        )
        assertEquals(BackupEntry.Audio("recordings/2026-10-30 14-05-12.opus", "abc"), entries[6])
    }

    @Test
    fun `backup json reads back the same, with the audio names filled in`() {
        val back = backupJson.decodeFromString<BackupData>(text("backup.json"))
        assertEquals("recordings/2026-10-30 14-05-12.opus", back.recordings[0].audio)
        assertNull(back.recordings[1].audio)
        // Defaults are written too: the import must see which format it's reading.
        assertTrue("\"format\": 1" in text("backup.json") && "\"boardKey\": null" in text("backup.json"))
        assertEquals(data, back.copy(recordings = data.recordings))
    }

    @Test
    fun `a list has open items first, with reminder times`() {
        assertEquals("# Todo\n\n- [ ] Call mom 🔔 2026-10-30 17:00\n- [x] Buy milk\n", text("lists/Todo.md"))
        assertEquals("# No list\n\n- [ ] Lost one\n", text("lists/No list.md"))
    }

    @Test
    fun `a note keeps its text and links to its recording`() {
        assertEquals(
            "---\ncreated: 2026-10-30T14:05\n---\n\nPockets for water bottles\nfrom scrap jeans\n\n" +
                "From recording: [2026-10-30 14-05-12](<../recordings/2026-10-30 14-05-12.md>)\n",
            text("notes/2026-10-30 14-05 Pockets for water bottles.md"),
        )
    }

    @Test
    fun `a recording lists its transcript and what it created`() {
        assertEquals(
            """
            # 2026-10-30 14:05:12

            - Length: 0:07
            - From: the board
            - Audio: [2026-10-30 14-05-12.opus](<2026-10-30 14-05-12.opus>)

            ## Transcript

            Note pockets for water bottles. Remind me to call mom at 5.

            ## Created

            - Note: [Pockets for water bottles from scrap jeans](<../notes/2026-10-30 14-05 Pockets for water bottles.md>)
            - On Todo: Call mom 🔔 2026-10-30 17:00
            - Alarm: Wake up (2026-10-31 07:00)

            """.trimIndent(),
            text("recordings/2026-10-30 14-05-12.md"),
        )
        val failed = text("recordings/2026-10-30 15-00-00.md")
        assertTrue("- Failed: no model" in failed && "(none)" in failed && "Audio" !in failed, failed)
    }

    @Test
    fun `names lose characters other systems refuse, and repeats get a number`() {
        assertEquals("a b c", BackupLayout.safeName("a/b:c..."))
        assertEquals("x (3)", BackupLayout.dedupe("x") { it == "x" || it == "x (2)" })
        val twins = BackupLayout.entries(data.copy(recordings = listOf(recording, recording.copy(id = 2))), zone) { StoredAudio.PCM }
        assertTrue(twins.any { it.path == "recordings/2026-10-30 14-05-12 (2).md" })
        // Audio still kept as PCM goes in as WAV.
        assertTrue(twins.any { it.path == "recordings/2026-10-30 14-05-12 (2).wav" })
    }

    @Test
    fun `durations read like a clock`() {
        assertEquals("0:07", BackupLayout.duration(7_900))
        assertEquals("12:34", BackupLayout.duration(754_000))
        assertEquals("1:02:03", BackupLayout.duration(3_723_000))
    }

    @Test
    fun `the wav header describes 16 kHz 16-bit mono`() {
        val h = WavHeader.forPcm(32_000)
        fun int32(at: Int) = (0..3).sumOf { (h[at + it].toInt() and 0xff) shl (8 * it) }
        assertEquals(44, h.size)
        assertEquals("RIFF", h.decodeToString(0, 4))
        assertEquals(36 + 32_000, int32(4))
        assertEquals(16_000, int32(24))
        assertEquals(32_000, int32(28))
        assertEquals("data", h.decodeToString(36, 40))
        assertEquals(32_000, int32(40))
    }
}
