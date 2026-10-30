package dev.piatra.core.board

// Battery history: the board's battery voltage over days, to see how fast it
// drains. Plain Kotlin; the table is data/BatteryReadingEntity.kt.

// One stored reading.
data class BatteryPoint(val atMillis: Long, val millivolts: Int)

// A wake-up sends several adverts within seconds (the reading is refreshed after ~1 s), so
// only the first reading in this time is kept: one per wake-up, taken at the same moment of
// each wake-up, which keeps them comparable.
const val BATTERY_LOG_GAP_MILLIS = 2 * 60_000L

// Older readings are deleted (a check-in every 30 min is ~1,500 rows a month).
const val BATTERY_LOG_KEEP_MILLIS = 30 * 24 * 3_600_000L

// 0 mV means "not measured" (no battery, or the board had just restarted).
fun shouldLogBattery(lastAtMillis: Long?, nowMillis: Long, millivolts: Int): Boolean =
    millivolts > 0 && (lastAtMillis == null || nowMillis - lastAtMillis >= BATTERY_LOG_GAP_MILLIS)

// How fast it's draining: the change in mV per hour from the first to the last reading of
// the last `windowMillis`. Null with fewer than two readings or under an hour between them.
data class BatteryTrend(val millivoltsPerHour: Double, val hours: Double, val readings: Int)

fun batteryTrend(points: List<BatteryPoint>, nowMillis: Long, windowMillis: Long): BatteryTrend? {
    val recent = points.filter { it.atMillis >= nowMillis - windowMillis }.sortedBy { it.atMillis }
    if (recent.size < 2) return null
    val hours = (recent.last().atMillis - recent.first().atMillis) / 3_600_000.0
    if (hours < 1.0) return null
    val change = recent.last().millivolts - recent.first().millivolts
    return BatteryTrend(change / hours, hours, recent.size)
}
