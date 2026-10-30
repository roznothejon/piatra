package dev.piatra.data

import dev.piatra.core.Clock
import dev.piatra.core.ReminderScheduler
import dev.piatra.core.agent.ItemStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The items the agent creates. Tools write through ItemStore; the screens read the observe… flows.
// A reminder is a row plus an alarm; like RecordingRepository (row + file), this class keeps
// the two in step, so no alarm fires for a deleted reminder and no reminder lacks its alarm.
class ItemRepository(
    private val dao: ItemDao,
    private val clock: Clock,
    private val scheduler: ReminderScheduler,
) : ItemStore {
    override suspend fun addNote(text: String, recordingId: Long?): Long =
        dao.insert(ItemEntity(kind = ItemKind.NOTE, text = text, createdAt = clock.nowMillis(), recordingId = recordingId))

    override suspend fun addListItem(text: String, listId: Long, dueAt: Long?, recordingId: Long?): Long {
        val id = dao.insert(
            ItemEntity(
                kind = ItemKind.LIST_ITEM, text = text, createdAt = clock.nowMillis(), recordingId = recordingId,
                dueAt = dueAt, listId = listId,
            )
        )
        // The row first: the alarm carries only the id, and the receiver looks the rest up.
        if (dueAt != null) scheduler.schedule(id, dueAt)
        return id
    }

    override suspend fun addAlarm(label: String, at: Long, recordingId: Long): Long =
        dao.insert(ItemEntity(kind = ItemKind.ALARM, text = label, createdAt = clock.nowMillis(), recordingId = recordingId, dueAt = at))

    override suspend fun addTimer(label: String, endsAt: Long, recordingId: Long): Long =
        dao.insert(ItemEntity(kind = ItemKind.TIMER, text = label, createdAt = clock.nowMillis(), recordingId = recordingId, dueAt = endsAt))

    override suspend fun deleteForRecording(recordingId: Long) {
        dao.timedReminderIds(recordingId).forEach(scheduler::cancel)
        dao.deleteForRecording(recordingId)
    }

    suspend fun get(id: Long): ItemEntity? = dao.get(id)

    // The ✕ on the recording screen. A reminder's alarm goes with it.
    suspend fun delete(id: Long) {
        val item = dao.get(id) ?: return
        if (item.kind == ItemKind.LIST_ITEM && item.dueAt != null) scheduler.cancel(id)
        dao.delete(id)
    }

    // Editing by typing. A reminder's alarm looks the text up when it rings, so it needs no change.
    suspend fun updateText(id: Long, text: String) = dao.updateText(id, text)

    // The notification's Done button, or a tap on screen. Ticking off cancels a reminder's alarm
    // (in case it hasn't gone off yet) and takes down its notification; unticking sets the alarm
    // again if its time is still to come.
    suspend fun setDone(id: Long, done: Boolean) {
        dao.setDone(id, done)
        syncAlarm(id)
    }

    // The reminder time picked on screen; null clears it (and its alarm).
    suspend fun updateDue(id: Long, dueAt: Long?) {
        dao.updateDue(id, dueAt)
        syncAlarm(id)
    }

    // The notification's Snooze button: ring again in `minutes`. It's a new time like any other.
    suspend fun snooze(id: Long, minutes: Int) = updateDue(id, clock.nowMillis() + minutes * 60_000L)

    // True only for the first caller, so a reminder never rings twice for the same time (its
    // alarm and the restart check can both find it due).
    suspend fun markRang(id: Long): Boolean = dao.markRang(id) == 1

    // Reminders whose time has passed and that aren't ticked off.
    suspend fun dueReminders(): List<ItemEntity> = dao.dueReminders(clock.nowMillis())

    // Make the alarm match the row: set if it should ring (see alarmTime), cancelled otherwise.
    // Cancelling an alarm that isn't set does nothing.
    private suspend fun syncAlarm(id: Long) {
        val item = dao.get(id) ?: return
        if (item.kind != ItemKind.LIST_ITEM) return
        val at = item.alarmTime(clock.nowMillis())
        if (at != null) scheduler.schedule(id, at) else scheduler.cancel(id)
    }

    // Alarms are wiped when the phone restarts (and when the app is force-stopped or updated).
    // Setting them again is harmless: scheduling the same reminder replaces its alarm.
    suspend fun rescheduleUpcoming() {
        for (reminder in dao.upcomingReminders(clock.nowMillis())) {
            scheduler.schedule(reminder.id, checkNotNull(reminder.dueAt))
        }
    }

    // What one recording created.
    fun observeForRecording(recordingId: Long): Flow<List<ItemEntity>> = dao.observeForRecording(recordingId)

    fun observeNotes(): Flow<List<ItemEntity>> = dao.observeNotes()

    fun observeListItems(): Flow<List<ItemEntity>> = dao.observeListItems()

    fun observeForList(listId: Long): Flow<List<ItemEntity>> = dao.observeForList(listId)

    // Items grouped by the recording they came from: recording id -> its items.
    // Items whose recording was deleted (null id) are left out; the Notes and Lists tabs show them.
    fun observeByRecording(): Flow<Map<Long, List<ItemEntity>>> =
        dao.observeAll().map { items ->
            items.filter { it.recordingId != null }.groupBy { it.recordingId!! }
        }
}

// When a list item's alarm should ring: its time, if it has one, isn't done, and is still to come.
// null: no alarm.
fun ItemEntity.alarmTime(now: Long): Long? = dueAt?.takeIf { kind == ItemKind.LIST_ITEM && !done && it > now }
