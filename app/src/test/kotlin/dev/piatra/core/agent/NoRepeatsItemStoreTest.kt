package dev.piatra.core.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NoRepeatsItemStoreTest {
    private val inner = FakeItemStore()
    private val store = NoRepeatsItemStore(inner)

    @Test
    fun `repeats within one recording are skipped`() = runTest {
        store.addListItem("Milk", listId = 2, dueAt = null, recordingId = 1)
        store.addListItem("milk", listId = 2, dueAt = null, recordingId = 1)
        store.addListItem("Milk", listId = 1, dueAt = null, recordingId = 1) // another list: kept
        store.addListItem("Milk", listId = 2, dueAt = null, recordingId = 2) // another recording: kept
        store.addNote("idea", recordingId = null)
        store.addNote("idea", recordingId = null) // typed: never skipped

        assertEquals(listOf(2L to 1L, 1L to 1L, 2L to 2L), inner.listItems.map { it.listId to it.recordingId })
        assertEquals(2, inner.saved.count { it.kind == "note" })
    }

    @Test
    fun `a re-run of the same recording makes its items again`() = runTest {
        store.addNote("idea", recordingId = 1)
        store.deleteForRecording(1) // AgentRunner's clean slate ("Transcribe again")
        store.addNote("idea", recordingId = 1)

        assertEquals(listOf("idea"), inner.notes[1L])
    }
}
