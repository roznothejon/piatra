package dev.piatra.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Piatra's additions to the ported parser: dates said day first, "next year", "the day after
// tomorrow" and "and a half".
// Upstream's own tests stay untouched in HumanDateTimeParserTest.kt.
class HumanDateTimeParserPiatraTest {
    // 22 Oct 2026: 21 September has passed this year, so a plain "21 September" is in 2027.
    private val parser = HumanDateTimeParser(
        object : Clock { override fun now() = Instant.parse("2026-10-22T12:00:00Z") },
        TimeZone.of("Europe/Bucharest"),
    )
    private val sep21 = InterpretedDateTime.AbsoluteDate(LocalDate(2027, 9, 21))

    @Test
    fun `day before month is understood`() {
        for (phrase in listOf(
            "on the 21st of September", "the 21st of September", "21st of September", "on 21 September",
            "on the twenty-first of September", "on the twenty first of September", "21 september",
        )) {
            assertEquals(sep21, parser.parse(phrase), phrase)
        }
    }

    @Test
    fun `next year after a date means the year after this one`() {
        assertEquals(sep21, parser.parse("September 21st next year"))
        assertEquals(sep21, parser.parse("on the twenty first of September next year"))
        // Even when the date is still to come this year: 1 December 2026 hasn't passed yet.
        assertEquals(InterpretedDateTime.AbsoluteDate(LocalDate(2027, 12, 1)), parser.parse("the 1st of December next year"))
    }

    @Test
    fun `dates are found inside a sentence`() {
        assertEquals(sep21, parser.parseFromMessage("Remind me about my birthday on the twenty-first of September.")?.dateTime)
        assertEquals(sep21, parser.parseFromMessage("Birthday on the twenty first of September next year")?.dateTime)
    }

    @Test
    fun `the day after tomorrow is two days on, not tomorrow`() {
        val oct24 = LocalDate(2026, 10, 24)
        assertEquals(InterpretedDateTime.AbsoluteDate(oct24), parser.parse("the day after tomorrow"))
        assertEquals(InterpretedDateTime.AbsoluteDateTime(LocalDateTime(oct24, LocalTime(17, 0))), parser.parse("day after tomorrow at 5pm"))
        assertEquals(InterpretedDateTime.AbsoluteDate(oct24), parser.parseFromMessage("Remind me to call mom the day after tomorrow.")?.dateTime)
    }

    @Test
    fun `and a half adds half a unit`() {
        for (phrase in listOf("an hour and a half", "in an hour and a half", "1 hour and a half", "one and a half hours")) {
            assertEquals(InterpretedDateTime.Relative(90.minutes), parser.parse(phrase), phrase)
        }
        assertEquals(InterpretedDateTime.Relative(150.minutes), parser.parse("in two and a half hours"))
        assertEquals(InterpretedDateTime.Relative(90.seconds), parser.parse("a minute and a half"))
        assertEquals(InterpretedDateTime.Relative(90.minutes), parser.parseFromMessage("Check the oven in an hour and a half.")?.dateTime)
    }

    @Test
    fun `may the verb isn't a month`() {
        // "2 may" without "the" or "of" stays as it was said.
        assertNull(parser.parseFromMessage("those 2 may come later"))
        assertEquals(InterpretedDateTime.AbsoluteDate(LocalDate(2027, 5, 2)), parser.parse("the 2nd of May"))
    }
}
