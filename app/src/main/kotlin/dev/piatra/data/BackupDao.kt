package dev.piatra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

// The import's one big write (backup, part 2): every table the backup holds, replaced at once.
@Dao
interface BackupDao {
    @Query("SELECT COUNT(*) FROM lists")
    suspend fun countLists(): Int

    @Query("DELETE FROM items")
    suspend fun deleteItems()

    @Query("DELETE FROM shortcut_rules")
    suspend fun deleteRules()

    @Query("DELETE FROM lists")
    suspend fun deleteLists()

    @Query("DELETE FROM recordings")
    suspend fun deleteRecordings()

    // Rows keep the ids they had: a non-zero id is used as it is, not generated.
    @Insert
    suspend fun insertRecordings(rows: List<RecordingEntity>)

    @Insert
    suspend fun insertLists(rows: List<ListEntity>)

    @Insert
    suspend fun insertItems(rows: List<ItemEntity>)

    @Insert
    suspend fun insertRules(rows: List<ShortcutRuleEntity>)

    // One transaction: if any insert fails (say, a link to a row that isn't there), SQLite
    // undoes the deletes too, and the old data is still all there. Children are deleted before
    // the rows they point to, and parents inserted before their children.
    @Transaction
    suspend fun replaceAll(recordings: List<RecordingEntity>, lists: List<ListEntity>, items: List<ItemEntity>, rules: List<ShortcutRuleEntity>) {
        deleteItems()
        deleteRules()
        deleteLists()
        deleteRecordings()
        insertRecordings(recordings)
        insertLists(lists)
        insertItems(items)
        insertRules(rules)
    }
}
