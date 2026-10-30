package dev.piatra.core.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryLogTest {
    private val hour = 3_600_000L

    @Test
    fun firstReadingIsKept() = assertTrue(shouldLogBattery(null, 1_000, 3700))

    @Test
    fun moreAdvertsFromTheSameWakeUpAreSkipped() {
        assertFalse(shouldLogBattery(lastAtMillis = 0, nowMillis = 1_000, millivolts = 3690))
        assertFalse(shouldLogBattery(0, BATTERY_LOG_GAP_MILLIS - 1, 3690))
        assertTrue(shouldLogBattery(0, BATTERY_LOG_GAP_MILLIS, 3690))
    }

    @Test
    fun notMeasuredIsSkipped() = assertFalse(shouldLogBattery(null, 1_000, 0))

    @Test
    fun trendIsTheChangePerHour() {
        val points = listOf(BatteryPoint(0, 3700), BatteryPoint(5 * hour, 3690), BatteryPoint(10 * hour, 3650))
        val trend = batteryTrend(points, nowMillis = 10 * hour, windowMillis = 24 * hour)!!
        assertEquals(-5.0, trend.millivoltsPerHour, 1e-9)
        assertEquals(10.0, trend.hours, 1e-9)
        assertEquals(3, trend.readings)
    }

    @Test
    fun trendOnlyCountsTheWindow() {
        val points = listOf(BatteryPoint(0, 4100), BatteryPoint(20 * hour, 3700), BatteryPoint(22 * hour, 3690))
        assertEquals(-5.0, batteryTrend(points, 22 * hour, 4 * hour)!!.millivoltsPerHour, 1e-9)
    }

    @Test
    fun noTrendFromTooLittle() {
        assertNull(batteryTrend(listOf(BatteryPoint(0, 3700)), hour, 24 * hour))
        assertNull(batteryTrend(listOf(BatteryPoint(0, 3700), BatteryPoint(hour / 2, 3699)), hour, 24 * hour))
    }
}
