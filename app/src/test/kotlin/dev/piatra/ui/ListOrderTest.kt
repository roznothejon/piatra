package dev.piatra.ui

import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ListOrderTest {
    private fun item(id: Long, createdAt: Long, dueAt: Long? = null, done: Boolean = false) =
        ItemEntity(id = id, kind = ItemKind.LIST_ITEM, text = "item $id", createdAt = createdAt, recordingId = null, dueAt = dueAt, done = done)

    @Test
    fun remindersComeFirstSoonestFirstThenTheRestInOrderAdded() {
        val items = listOf(
            item(1, createdAt = 10),
            item(2, createdAt = 20, dueAt = 500),
            item(3, createdAt = 30),
            item(4, createdAt = 40, dueAt = 100),
        )
        val (open, done) = listOrder(items)
        assertEquals(listOf(4L, 2L, 1L, 3L), open.map { it.id })
        assertEquals(emptyList(), done)
    }

    @Test
    fun doneItemsAreSplitOffNewestFirst() {
        val items = listOf(
            item(1, createdAt = 10, done = true),
            item(2, createdAt = 20),
            item(3, createdAt = 30, dueAt = 100, done = true),
        )
        val (open, done) = listOrder(items)
        assertEquals(listOf(2L), open.map { it.id })
        assertEquals(listOf(3L, 1L), done.map { it.id })
    }
}
