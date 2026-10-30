package dev.piatra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatteryReadingDao {
    @Query("SELECT * FROM battery_readings ORDER BY readAtMillis DESC LIMIT 1")
    suspend fun latest(): BatteryReadingEntity?

    // Newest first.
    @Query("SELECT * FROM battery_readings WHERE readAtMillis >= :sinceMillis ORDER BY readAtMillis DESC")
    fun observeSince(sinceMillis: Long): Flow<List<BatteryReadingEntity>>

    @Insert
    suspend fun insert(reading: BatteryReadingEntity)

    @Query("DELETE FROM battery_readings WHERE readAtMillis < :beforeMillis")
    suspend fun deleteBefore(beforeMillis: Long)
}
