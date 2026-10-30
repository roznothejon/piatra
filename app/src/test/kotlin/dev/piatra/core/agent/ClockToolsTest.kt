package dev.piatra.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClockToolsTest {
    private val zone = TimeZone.of("Europe/Bucharest")
    private fun millis(day: Int, hour: Int, minute: Int) = LocalDateTime(2026, 10, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private val clockApp = FakeClockApp()
    private val store = FakeItemStore()
    // Spoken on 22 Oct 2026 at 15:30.
    private val spokenAt = millis(22, 15, 30)
    private fun context(transcript: String) = ToolContext(recordingId = 1, recordedAt = spokenAt, transcript = transcript)

    private val alarm = SetAlarmTool(clockApp, store, { zone })

    @Test
    fun `an alarm in N minutes counts from when it was said, not the model's numbers`() = runTest {
        // Seen: the model read "in one minute" as 1:00.
        alarm.call(buildJsonObject { put("time_hours", 1); put("time_minutes", 0) }, context("Set an alarm to go off in one minute."))

        assertEquals(Triple(15, 31, null), clockApp.alarms.single())
        assertEquals(millis(22, 15, 31), store.saved.single().dueAt)
    }

    @Test
    fun `an alarm uses the model's time when the words don't give one, with its label`() = runTest {
        alarm.call(
            buildJsonObject { put("time_hours", 7.0); put("time_minutes", 30); put("label", "Gym") },
            context("Gym alarm, seven thirty."),
        )

        // 7:30 has passed at 15:30, and 19:30 comes before 07:30 tomorrow (whichever comes first).
        assertEquals(Triple(19, 30, "Gym"), clockApp.alarms.single())
        assertEquals(millis(22, 19, 30), store.saved.single().dueAt)
    }

    @Test
    fun `wake me up means the morning`() = runTest {
        // Whichever comes first would be 18:00 today; waking up means 06:00 tomorrow.
        alarm.call(buildJsonObject { put("time_hours", 6); put("time_minutes", 0) }, context("Wake me up at six."))
        // A duration stays a duration.
        alarm.call(buildJsonObject {}, context("Wake me up in two hours."))

        assertEquals(listOf<Triple<Int, Int, String?>>(Triple(6, 0, null), Triple(17, 30, null)), clockApp.alarms)
        assertEquals(millis(23, 6, 0), store.saved.first().dueAt)
    }

    @Test
    fun `an alarm for a time tomorrow uses that time`() = runTest {
        // Seen on the phone: rang at 9:00, "tomorrow"'s default time.
        alarm.call(buildJsonObject { put("time_hours", 6); put("time_minutes", 0) }, context("Set an alarm for six AM tomorrow."))

        assertEquals(Triple(6, 0, null), clockApp.alarms.single())
        assertEquals(millis(23, 6, 0), store.saved.single().dueAt)
    }

    @Test
    fun `words that only name a day take the model's time on that day`() = runTest {
        // The parser reads no time in "at sunrise", only "tomorrow" (09:00 by default).
        alarm.call(buildJsonObject { put("time_hours", 6); put("time_minutes", 15) }, context("Set an alarm tomorrow at sunrise."))
        // Without the model's numbers, the day's default stays.
        alarm.call(buildJsonObject {}, context("Set an alarm tomorrow."))

        assertEquals(listOf<Triple<Int, Int, String?>>(Triple(6, 15, null), Triple(9, 0, null)), clockApp.alarms)
        assertEquals(millis(23, 6, 15), store.saved.first().dueAt)
    }

    @Test
    fun `an alarm with no time anywhere fails`() = runTest {
        assertFailsWith<IllegalArgumentException> { alarm.call(buildJsonObject {}, context("Set an alarm.")) }
    }

    @Test
    fun `a timer ends when it would have if set the moment it was said`() = runTest {
        // Processed 5 seconds after speaking: 10 minutes minus those 5 seconds are left.
        val clock = FixedClock(spokenAt + 5_000)
        val timer = SetTimerTool(clockApp, store, clock, { zone })
        timer.call(buildJsonObject { put("time_human", "10 minutes") }, context("Set a timer for ten minutes."))

        assertEquals(listOf(595), clockApp.timers)
        assertEquals(spokenAt + 600_000, store.saved.single().dueAt)
    }

    @Test
    fun `a timer that would already have ended fails`() = runTest {
        val clock = FixedClock(spokenAt + 120_000)
        val timer = SetTimerTool(clockApp, store, clock, { zone })

        assertFailsWith<IllegalArgumentException> {
            timer.call(buildJsonObject { put("time_human", "1 minute") }, context("Set a timer for one minute."))
        }
    }

    @Test
    fun `an alarm for a spoken time uses the words, read the 12-hour way`() = runTest {
        // Seen on the phone at 14:26: "two twenty seven" -> the model sent 2:20; meant 14:27.
        val justBefore = millis(22, 14, 26)
        alarm.call(
            buildJsonObject { put("time_hours", 2); put("time_minutes", 20) },
            ToolContext(recordingId = 1, recordedAt = justBefore, transcript = "Stet an alarm for two twenty seven."),
        )

        assertEquals(Triple(14, 27, null), clockApp.alarms.single())
    }

    @Test
    fun `an alarm for a duration isn't read as a clock time`() = runTest {
        // "for ten minutes" must not become "at ten": it's ten minutes from now.
        alarm.call(buildJsonObject {}, context("Set an alarm for ten minutes from now, in ten minutes."))

        assertEquals(Triple(15, 40, null), clockApp.alarms.single())
    }
}
