package dev.piatra.core.agent

import kotlinx.coroutines.CancellationException

// Something the agent saved, as handed to another app (Tasker).
// kind: "note" or "list_item". list: the list's title, for list items. dueAt: epoch millis, or null.
data class HandedOffItem(val kind: String, val text: String, val list: String?, val dueAt: Long?)

// Passes saved items on to another app: TaskerHandoff (android/) or ObsidianWriter (core/obsidian/).
// suspend: writing a file into the Obsidian vault waits for the disk.
interface ItemHandoff {
    suspend fun send(item: HandedOffItem)
}

// An ItemStore that saves as usual, then also hands notes and list items (reminders included)
// to [handoff] while [isEnabled] says so. Alarms and timers aren't handed off: they already
// went to the Clock app.
//
// "ItemStore by inner": Kotlin writes every ItemStore function for us, each one just calling
// inner's. We only override the two that should do something extra.
//
// Only the app's tools get this store (AppModule), so the agent lab's dry runs never hand off.
class HandoffItemStore(
    private val inner: ItemStore,
    private val lists: ListStore,
    private val handoff: ItemHandoff,
    private val isEnabled: suspend () -> Boolean,
) : ItemStore by inner {
    override suspend fun addNote(text: String, recordingId: Long?): Long {
        val id = inner.addNote(text, recordingId)
        handOff { HandedOffItem(kind = "note", text = text, list = null, dueAt = null) }
        return id
    }

    override suspend fun addListItem(text: String, listId: Long, dueAt: Long?, recordingId: Long?): Long {
        val id = inner.addListItem(text, listId, dueAt, recordingId)
        handOff {
            val title = lists.all().firstOrNull { it.id == listId }?.title
            HandedOffItem(kind = "list_item", text = text, list = title, dueAt = dueAt)
        }
        return id
    }

    // Fire and forget: the item is already saved in Piatra, so a failed hand-off is only logged
    // and never fails the recording.
    private suspend fun handOff(item: suspend () -> HandedOffItem) {
        try {
            if (isEnabled()) handoff.send(item())
        } catch (e: Exception) {
            if (e is CancellationException) throw e // stopping is not failing
            println("HandoffItemStore: hand-off failed: $e")
        }
    }
}
