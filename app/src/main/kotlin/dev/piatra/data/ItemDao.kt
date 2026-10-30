package dev.piatra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Query("DELETE FROM items WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: Long)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: Long): ItemEntity?

    // List items of one recording that have a reminder alarm, so it can be cancelled.
    @Query("SELECT id FROM items WHERE recordingId = :recordingId AND kind = 'LIST_ITEM' AND dueAt IS NOT NULL")
    suspend fun timedReminderIds(recordingId: Long): List<Long>

    // Reminders still to go off: list items not done, due after `now`.
    @Query("SELECT * FROM items WHERE kind = 'LIST_ITEM' AND done = 0 AND dueAt > :now")
    suspend fun upcomingReminders(now: Long): List<ItemEntity>

    @Query("UPDATE items SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    // Reminders whose time has come: list items not done, due at or before `now`.
    @Query("SELECT * FROM items WHERE kind = 'LIST_ITEM' AND done = 0 AND dueAt <= :now")
    suspend fun dueReminders(now: Long): List<ItemEntity>

    // A new time means it hasn't rung for that time yet.
    @Query("UPDATE items SET dueAt = :dueAt, rang = 0 WHERE id = :id")
    suspend fun updateDue(id: Long, dueAt: Long?)

    // Returns how many rows changed: 1 the first time, 0 if it was already marked. SQLite runs
    // one UPDATE at a time, so two callers can't both get 1.
    @Query("UPDATE items SET rang = 1 WHERE id = :id AND rang = 0")
    suspend fun markRang(id: Long): Int

    @Query("UPDATE items SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    // Every item, oldest first, once (the backup).
    @Query("SELECT * FROM items ORDER BY createdAt ASC")
    suspend fun all(): List<ItemEntity>

    // Every item, oldest first. Sends a fresh list whenever the table changes.
    @Query("SELECT * FROM items ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE recordingId = :recordingId ORDER BY createdAt ASC")
    fun observeForRecording(recordingId: Long): Flow<List<ItemEntity>>

    // Every note, newest first (the Notes tab).
    @Query("SELECT * FROM items WHERE kind = 'NOTE' ORDER BY createdAt DESC")
    fun observeNotes(): Flow<List<ItemEntity>>

    // Every list item, on any list (the Lists tab).
    @Query("SELECT * FROM items WHERE kind = 'LIST_ITEM' ORDER BY createdAt ASC")
    fun observeListItems(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE listId = :listId ORDER BY createdAt ASC")
    fun observeForList(listId: Long): Flow<List<ItemEntity>>
}
