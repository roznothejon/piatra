package dev.piatra.data

import dev.piatra.core.Clock
import dev.piatra.core.ReminderScheduler
import dev.piatra.core.agent.DefaultLists
import dev.piatra.core.agent.ListRef
import dev.piatra.core.agent.ListStore
import kotlinx.coroutines.flow.Flow

// The lists. Tools use it as a ListStore; the screens read observeAll and observe.
class ListRepository(
    private val dao: ListDao,
    private val clock: Clock,
    private val scheduler: ReminderScheduler,
) : ListStore {
    override suspend fun all(): List<ListRef> = dao.all().map { ListRef(it.id, it.title, it.seed) }

    // Built-in lists are made on first use, so a fresh install needs no setup step.
    // (The v5 -> v6 migration also makes them, to move old reminders onto Todo.)
    override suspend fun idForSeed(seed: String): Long =
        dao.bySeed(seed)?.id
            ?: dao.insert(ListEntity(title = DefaultLists.titleFor(seed), seed = seed, createdAt = clock.nowMillis()))

    override suspend fun create(title: String): Long =
        dao.insert(ListEntity(title = title, createdAt = clock.nowMillis()))

    suspend fun rename(id: Long, title: String) = dao.rename(id, title)

    // Deleting a list: its items either move to Todo (alarms and all) or go with it (alarms
    // cancelled). Never left on no list, where no screen would show them.
    suspend fun delete(id: Long, moveItemsToTodo: Boolean) {
        if (moveItemsToTodo) {
            dao.deleteMovingItems(id, toListId = idForSeed(DefaultLists.TODO))
        } else {
            dao.timedItemIds(id).forEach(scheduler::cancel)
            dao.deleteWithItems(id)
        }
    }

    fun observeAll(): Flow<List<ListEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<ListEntity?> = dao.observe(id)
}
