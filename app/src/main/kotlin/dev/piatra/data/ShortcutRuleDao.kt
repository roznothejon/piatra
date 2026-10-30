package dev.piatra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortcutRuleDao {
    @Query("SELECT * FROM shortcut_rules ORDER BY id ASC")
    suspend fun all(): List<ShortcutRuleEntity>

    @Query("SELECT * FROM shortcut_rules ORDER BY id ASC")
    fun observeAll(): Flow<List<ShortcutRuleEntity>>

    // Upsert: insert a new rule (id 0) or update the one with that id.
    @Upsert
    suspend fun upsert(rule: ShortcutRuleEntity): Long

    @Insert
    suspend fun insertAll(rules: List<ShortcutRuleEntity>)

    @Query("UPDATE shortcut_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM shortcut_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM shortcut_rules")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(rules: List<ShortcutRuleEntity>) {
        deleteAll()
        insertAll(rules)
    }
}
