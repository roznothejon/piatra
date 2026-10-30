package dev.piatra.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// When a list item's reminder alarm should be set (ItemRepository.setDone / updateDue).
class AlarmTimeTest {
    private val now = 1_000L

    private fun item(dueAt: Long?, done: Boolean = false, kind: ItemKind = ItemKind.LIST_ITEM) =
        ItemEntity(id = 1, kind = kind, text = "call mom", createdAt = 0, recordingId = null, dueAt = dueAt, done = done)

    @Test
    fun aTimeStillToComeRings() = assertEquals(5_000L, item(dueAt = 5_000).alarmTime(now))

    @Test
    fun noTimeOrAClearedTimeDoesNot() = assertNull(item(dueAt = null).alarmTime(now))

    @Test
    fun aTimeInThePastDoesNot() = assertNull(item(dueAt = 500).alarmTime(now))

    @Test
    fun aTickedOffItemDoesNot() = assertNull(item(dueAt = 5_000, done = true).alarmTime(now))

    @Test
    fun alarmsAndTimersBelongToTheClockApp() = assertNull(item(dueAt = 5_000, kind = ItemKind.ALARM).alarmTime(now))
}
