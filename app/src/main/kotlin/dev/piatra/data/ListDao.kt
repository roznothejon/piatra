package dev.piatra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {
    @Insert
    suspend fun insert(list: ListEntity): Long

    @Query("SELECT * FROM lists ORDER BY createdAt ASC")
    suspend fun all(): List<ListEntity>

    @Query("SELECT * FROM lists WHERE seed = :seed")
    suspend fun bySeed(seed: String): ListEntity?

    @Query("SELECT * FROM lists ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun observe(id: Long): Flow<ListEntity?>

    @Query("UPDATE lists SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    // A DAO can query any table; these touch items because they belong to deleting a list.
    @Query("SELECT id FROM items WHERE listId = :listId AND kind = 'LIST_ITEM' AND dueAt IS NOT NULL")
    suspend fun timedItemIds(listId: Long): List<Long>

    @Query("UPDATE items SET listId = :toListId WHERE listId = :listId")
    suspend fun moveItems(listId: Long, toListId: Long)

    @Query("DELETE FROM items WHERE listId = :listId")
    suspend fun deleteItems(listId: Long)

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun delete(id: Long)

    // @Transaction: Room runs the whole function as one database transaction, so either every
    // step happens or none does (no list deleted with its items left behind on no list).
    @Transaction
    suspend fun deleteMovingItems(id: Long, toListId: Long) {
        moveItems(id, toListId)
        delete(id)
    }

    @Transaction
    suspend fun deleteWithItems(id: Long) {
        deleteItems(id)
        delete(id)
    }
}
