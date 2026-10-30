package dev.piatra.core.agent

import dev.piatra.core.eval.InMemoryItemStore
import dev.piatra.core.eval.InMemoryListStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HandoffItemStoreTest {
    private class RecordingHandoff(val fail: Boolean = false) : ItemHandoff {
        val sent = mutableListOf<HandedOffItem>()
        override suspend fun send(item: HandedOffItem) {
            if (fail) error("Tasker went away")
            sent += item
        }
    }

    private val inner = InMemoryItemStore()
    private val lists = InMemoryListStore()

    @Test
    fun `notes and list items are saved and handed off`() = runTest {
        val handoff = RecordingHandoff()
        val store = HandoffItemStore(inner, lists, handoff, isEnabled = { true })

        store.addNote("Parking is on level 3", recordingId = 1)
        store.addListItem("Call mom", listId = 1, dueAt = 1_000L, recordingId = 1)
        store.addListItem("Eggs", listId = 2, dueAt = null, recordingId = 1)

        assertEquals(3, inner.saved.size)
        assertEquals(
            listOf(
                HandedOffItem("note", "Parking is on level 3", list = null, dueAt = null),
                HandedOffItem("list_item", "Call mom", list = "Todo", dueAt = 1_000L),
                HandedOffItem("list_item", "Eggs", list = "Shopping", dueAt = null),
            ),
            handoff.sent,
        )
    }

    @Test
    fun `nothing is handed off while switched off`() = runTest {
        val handoff = RecordingHandoff()
        val store = HandoffItemStore(inner, lists, handoff, isEnabled = { false })

        store.addNote("A thought", recordingId = 1)

        assertEquals(1, inner.saved.size)
        assertEquals(emptyList(), handoff.sent)
    }

    @Test
    fun `alarms and timers are not handed off`() = runTest {
        val handoff = RecordingHandoff()
        val store = HandoffItemStore(inner, lists, handoff, isEnabled = { true })

        store.addAlarm("Wake up", at = 1_000L, recordingId = 1)
        store.addTimer("Pasta", endsAt = 2_000L, recordingId = 1)

        assertEquals(2, inner.saved.size)
        assertEquals(emptyList(), handoff.sent)
    }

    @Test
    fun `a failed hand-off keeps the item`() = runTest {
        val store = HandoffItemStore(inner, lists, RecordingHandoff(fail = true), isEnabled = { true })

        store.addNote("Still saved", recordingId = 1)

        assertEquals("Still saved", inner.saved.single().text)
    }
}
