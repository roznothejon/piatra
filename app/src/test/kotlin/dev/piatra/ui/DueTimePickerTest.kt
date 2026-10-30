package dev.piatra.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

// The date picker works in "midnight UTC of the chosen day"; these check the round trip and
// that the picked day stays the same day in timezones on both sides of UTC.
class DueTimePickerTest {
    private val day = LocalDate(2026, 10, 23)

    @Test
    fun datePickerMillisRoundTrip() = assertEquals(day, dateFromPicker(datePickerMillis(day)))

    @Test
    fun combineKeepsTheDayWestOfUtc() = assertPicked(TimeZone.of("America/Los_Angeles"), hour = 23, minute = 30)

    @Test
    fun combineKeepsTheDayEastOfUtc() = assertPicked(TimeZone.of("Europe/Bucharest"), hour = 0, minute = 15)

    private fun assertPicked(zone: TimeZone, hour: Int, minute: Int) {
        val millis = combine(dateFromPicker(datePickerMillis(day)), hour, minute, zone)
        assertEquals(LocalDateTime(2026, 10, 23, hour, minute), Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone))
    }
}
