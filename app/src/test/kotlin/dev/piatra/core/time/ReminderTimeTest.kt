package dev.piatra.core.time

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// Piatra's rules on top of the parser, with a fixed "moment of speaking" and a real time zone.
// 22 Oct 2026 is a Thursday.
class ReminderTimeTest {
    private val zone = TimeZone.of("Europe/Bucharest")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone)

    private fun resolve(words: String, spokenAt: Instant) = resolveReminderTime(words, spokenAt, zone)

    private val thursdayAfternoon = at(2026, 10, 22, 15, 30)

    @Test
    fun `a date and time is taken as said`() {
        assertEquals(at(2026, 10, 23, 17), resolve("tomorrow at 5pm", thursdayAfternoon))
    }

    @Test
    fun `a time of day means its next occurrence`() {
        assertEquals(at(2026, 10, 22, 17), resolve("at 5pm", thursdayAfternoon)) // still to come today
        assertEquals(at(2026, 10, 23, 9), resolve("at 9am", thursdayAfternoon)) // passed: tomorrow
    }

    @Test
    fun `a bare hour means whichever of am and pm comes first`() {
        // Seen on the phone: "two twenty seven" said at 14:26 meant 14:27, not 02:27 tomorrow.
        val justBefore = at(2026, 10, 22, 14, 26)
        assertEquals(at(2026, 10, 22, 14, 27), resolve("at two twenty seven", justBefore))
        val lunchtime = at(2026, 10, 22, 13, 30)
        assertEquals(at(2026, 10, 22, 15), resolve("at 3", lunchtime))
        // Late evening: 7 in the morning comes before 7 in the evening.
        assertEquals(at(2026, 10, 23, 7), resolve("at seven", at(2026, 10, 22, 23, 0)))
        // "at 12" from mid-morning: noon, not midnight.
        assertEquals(at(2026, 10, 22, 12), resolve("at 12", at(2026, 10, 22, 10, 0)))
    }

    @Test
    fun `a bare hour on another day means waking hours`() {
        // Seen while fixing 9pm: "on Friday at three" was 03:00.
        assertEquals(at(2026, 10, 23, 15), resolve("on Friday at three", thursdayAfternoon))
        assertEquals(at(2026, 10, 23, 9), resolve("tomorrow at nine", thursdayAfternoon))
        assertEquals(at(2026, 10, 26, 7), resolve("next Monday at seven", thursdayAfternoon))
        assertEquals(at(2026, 10, 23, 12), resolve("tomorrow at 12", thursdayAfternoon))
        assertEquals(at(2026, 11, 5, 18, 30), resolve("November 5th at 6:30", thursdayAfternoon))
        // Said, so exact: am/pm, a part of the day, a 24-hour or leading-zero time.
        assertEquals(at(2026, 10, 23, 3), resolve("on Friday at 3am", thursdayAfternoon))
        assertEquals(at(2026, 10, 23, 3), resolve("tomorrow morning at 3", thursdayAfternoon))
        assertEquals(at(2026, 10, 23, 2, 30), resolve("tomorrow at 02:30", thursdayAfternoon))
        assertEquals(at(2026, 10, 23, 21), resolve("tomorrow at 9pm", thursdayAfternoon))
        // However am/pm is written or transcribed.
        val am = listOf("3am", "3 am", "3 a m", "3a.m.", "3 a.m.", "3 a. m.", "3 AM", "3 A M", "3 A.M.", "three am", "three AM", "three A M", "three a.m.")
        for (time in am) {
            assertEquals(at(2026, 10, 23, 3), resolve("on Friday at $time", thursdayAfternoon), time)
            assertEquals(at(2026, 10, 23, 3), resolve("tomorrow at $time", thursdayAfternoon), time)
        }
        for (time in am.map { it.replace('a', 'p').replace('A', 'P') }) {
            assertEquals(at(2026, 10, 23, 15), resolve("on Friday at $time", thursdayAfternoon), time)
        }
        // Today: whichever comes first, as for a time alone.
        assertEquals(at(2026, 10, 22, 21), resolve("today at nine", thursdayAfternoon))
    }

    @Test
    fun `an explicit am or pm, or a 24-hour time, is taken exactly`() {
        val lunchtime = at(2026, 10, 22, 13, 30)
        assertEquals(at(2026, 10, 23, 3), resolve("at 3am", lunchtime))
        assertEquals(at(2026, 10, 23, 2, 27), resolve("at 02:27", lunchtime))
        assertEquals(at(2026, 10, 22, 15), resolve("at 15:00", lunchtime))
    }

    @Test
    fun `relative times count from when it was said`() {
        assertEquals(thursdayAfternoon + 2.hours, resolve("in 2 hours", thursdayAfternoon))
    }

    @Test
    fun `a day without a time means 9 in the morning`() {
        assertEquals(at(2026, 10, 23, 9), resolve("on Friday", thursdayAfternoon))
    }

    @Test
    fun `days are exact hours, months keep the clock time, across a daylight saving change`() {
        // Clocks go back an hour in Romania on 25 Oct 2026: that day is 25 hours long.
        val saturdayNoon = at(2026, 10, 24, 12)
        // "in 1 day" = exactly 24 hours, which on this day ends at 11:00 on the clock.
        assertEquals(saturdayNoon + 24.hours, resolve("in 1 day", saturdayNoon))
        assertEquals(at(2026, 10, 25, 11), resolve("in 1 day", saturdayNoon))
        // "in 1 month" moves the calendar date and keeps 12:00, whatever the clocks did meanwhile.
        assertEquals(at(2026, 11, 24, 12), resolve("in 1 month", saturdayNoon))
    }

    @Test
    fun `words it can't read give no time`() {
        assertNull(resolve("whenever the moon is blue", thursdayAfternoon))
    }
}
