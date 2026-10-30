package dev.piatra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// The queries we can run on the recordings table. We only write the interface and the SQL;
// Room (via KSP) generates the implementation at build time and checks the SQL against the table.
@Dao
interface RecordingDao {
    // Returns the id SQLite gave the new row.
    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    // Writes every column of the row with the same id. Does nothing if that row is gone.
    @Update
    suspend fun update(recording: RecordingEntity)

    // Not suspend: returns a Flow that sends a fresh list every time the table changes.
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    // :id is filled in from the parameter with the same name.
    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    // One recording, sent again whenever it changes; null once it's deleted.
    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<RecordingEntity?>

    // Oldest first, so the queue works through recordings in the order they were made.
    @Query("SELECT id FROM recordings WHERE status = :status ORDER BY createdAt ASC")
    suspend fun idsWithStatus(status: RecordingStatus): List<Long>

    // EXISTS gives 1 or 0, which Room turns into true or false.
    @Query("SELECT EXISTS(SELECT 1 FROM recordings WHERE boardKey = :key)")
    suspend fun hasBoardKey(key: String): Boolean

    // Every recording, oldest first (the backup).
    @Query("SELECT * FROM recordings ORDER BY createdAt ASC")
    suspend fun all(): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)
}
