package dev.piatra.core.eval

import dev.piatra.core.Clock
import dev.piatra.core.ClockApp
import dev.piatra.core.ClockResult
import dev.piatra.core.agent.DefaultLists
import dev.piatra.core.agent.ItemStore
import dev.piatra.core.agent.ListRef
import dev.piatra.core.agent.ListStore

// In-memory stand-ins for the stores and the Clock app. The agent lab runs the real tools against
// these, so nothing is saved and no alarm is set (a "dry run"); the unit tests use them too.
// This works because tools only know the interfaces (ItemStore, ListStore, ClockApp).

// Everything a tool saved, in order.
class InMemoryItemStore : ItemStore {
    data class Saved(val kind: String, val text: String, val recordingId: Long?, val listId: Long? = null, val dueAt: Long? = null)

    val saved = mutableListOf<Saved>()

    // Shortcuts for assertions.
    val notes get() = saved.filter { it.kind == "note" }.groupBy({ it.recordingId }, { it.text })
    val listItems get() = saved.filter { it.kind == "list item" }

    override suspend fun addNote(text: String, recordingId: Long?) = add(Saved("note", text, recordingId))
    override suspend fun addListItem(text: String, listId: Long, dueAt: Long?, recordingId: Long?) =
        add(Saved("list item", text, recordingId, listId, dueAt))
    override suspend fun addAlarm(label: String, at: Long, recordingId: Long) = add(Saved("alarm", label, recordingId, dueAt = at))
    override suspend fun addTimer(label: String, endsAt: Long, recordingId: Long) = add(Saved("timer", label, recordingId, dueAt = endsAt))
    override suspend fun deleteForRecording(recordingId: Long) {
        saved.removeAll { it.recordingId == recordingId }
    }

    private fun add(item: Saved): Long {
        saved += item
        return saved.size.toLong()
    }
}

// Todo (id 1) and Shopping (id 2) exist, unless other lists are given; created lists get the next ids.
class InMemoryListStore(
    initial: List<ListRef> = listOf(ListRef(1, "Todo", DefaultLists.TODO), ListRef(2, "Shopping", DefaultLists.SHOPPING)),
) : ListStore {
    val lists = initial.toMutableList()

    override suspend fun all() = lists.toList()
    override suspend fun idForSeed(seed: String) =
        lists.firstOrNull { it.seed == seed }?.id
            ?: ((lists.maxOfOrNull { it.id } ?: 0) + 1).also { lists += ListRef(it, DefaultLists.titleFor(seed), seed) }
    override suspend fun create(title: String): Long {
        val id = (lists.maxOfOrNull { it.id } ?: 0) + 1
        lists += ListRef(id, title, seed = null)
        return id
    }
}

// Records what the Clock app was asked to do.
class RecordingClockApp : ClockApp {
    val alarms = mutableListOf<Triple<Int, Int, String?>>()
    val timers = mutableListOf<Int>()
    override fun setAlarm(hour: Int, minute: Int, label: String?): ClockResult {
        alarms += Triple(hour, minute, label)
        return ClockResult.SET
    }
    override fun setTimer(seconds: Int): ClockResult {
        timers += seconds
        return ClockResult.SET
    }
}

class FixedClock(var now: Long) : Clock {
    override fun nowMillis() = now
}
