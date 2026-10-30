package dev.piatra.data

import dev.piatra.core.Clock
import dev.piatra.core.board.BATTERY_LOG_KEEP_MILLIS
import dev.piatra.core.board.BatteryPoint
import dev.piatra.core.board.shouldLogBattery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The board's battery over time, fed by BoardHealth from adverts and syncs.
class BatteryHistory(private val dao: BatteryReadingDao, private val clock: Clock) {
    suspend fun record(millivolts: Int, source: String) {
        val now = clock.nowMillis()
        if (!shouldLogBattery(dao.latest()?.readAtMillis, now, millivolts)) return
        dao.insert(BatteryReadingEntity(readAtMillis = now, millivolts = millivolts, source = source))
        dao.deleteBefore(now - BATTERY_LOG_KEEP_MILLIS)
    }

    // Newest first.
    fun observeSince(sinceMillis: Long): Flow<List<BatteryPoint>> =
        dao.observeSince(sinceMillis).map { rows -> rows.map { BatteryPoint(it.readAtMillis, it.millivolts) } }
}
