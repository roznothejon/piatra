package dev.piatra.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateReminderToolTest {
    private val zone = TimeZone.of("Europe/Bucharest")

    private val store = FakeItemStore()

    // What was saved, as (text, dueAt, recordingId). Every reminder goes on Todo (id 1).
    private val reminders get() = store.listItems.onEach { assertEquals(1L, it.listId) }.map { Triple(it.text, it.dueAt, it.recordingId) }
    private val tool = CreateReminderTool(store, FakeListStore(), timeZone = { zone })

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    // Spoken on Thursday 22 Oct 2026 at 15:30.
    private val context = ToolContext(recordingId = 4, recordedAt = millis(2026, 10, 22, 15, 30), transcript = "Remind me to call mom tomorrow at 5pm.")

    @Test
    fun `a reminder with a time is due then, counted from when it was said`() = runTest {
        tool.call(buildJsonObject { put("message", "call mom"); put("date_time_human", "tomorrow at 5pm") }, context)

        assertEquals(Triple("Call mom", millis(2026, 10, 23, 17), 4L), reminders.single())
    }

    @Test
    fun `with the model's wording off, the transcript is saved and the model's message isn't searched for a time`() = runTest {
        val transcriptOnly = CreateReminderTool(store, FakeListStore(), timeZone = { zone }, useModelText = { false })
        // The model's time field still counts; its time is cut from the transcript as said.
        transcriptOnly.call(buildJsonObject { put("message", "ring mom"); put("date_time_human", "tomorrow at 5pm") }, context)
        // No time field: the time said is used, not the one the model wrote into its message.
        val spoken = context.copy(transcript = "Remind me to call mom at 5pm.")
        transcriptOnly.call(buildJsonObject { put("message", "call mom at 3pm") }, spoken)

        assertEquals(
            listOf(Triple("Call mom", millis(2026, 10, 23, 17), 4L), Triple("Call mom at 5pm.", millis(2026, 10, 22, 17), 4L)),
            reminders,
        )
    }

    @Test
    fun `the model's time cut short is ignored`() = runTest {
        // Seen: "the day after tomorrow" came as date_time_human "tomorrow".
        val spoken = context.copy(transcript = "Remind me to call mom the day after tomorrow.")
        tool.call(buildJsonObject { put("message", "call mom the day after tomorrow"); put("date_time_human", "tomorrow") }, spoken)

        assertEquals(Triple("Call mom the day after tomorrow", millis(2026, 10, 24, 9), 4L), reminders.single())
    }

    @Test
    fun `duration_human is used when there's no date_time_human`() = runTest {
        val spoken = context.copy(transcript = "Remind me to check the oven in 30 minutes.")
        tool.call(buildJsonObject { put("message", "check the oven"); put("duration_human", "in 30 minutes") }, spoken)

        assertEquals(millis(2026, 10, 22, 16, 0), reminders.single().second)
    }

    @Test
    fun `no time, or a time it can't read, gives a reminder without a time`() = runTest {
        val noTimeSaid = context.copy(transcript = "Remind me to water the plants.")
        tool.call(buildJsonObject { put("message", "water the plants") }, noTimeSaid)
        val unreadable = context.copy(transcript = "Remind me to pay rent whenever.")
        tool.call(buildJsonObject { put("message", "pay rent"); put("date_time_human", "whenever") }, unreadable)

        assertEquals(listOf<Long?>(null, null), reminders.map { it.second })
    }

    @Test
    fun `a missing message falls back to the transcript`() = runTest {
        tool.call(buildJsonObject { put("date_time_human", "at 5pm") }, context)

        // The transcript, minus the time phrase the model sent ("at 5pm") and "Remind me to".
        assertEquals("Call mom tomorrow", reminders.single().first)
    }

    @Test
    fun `a time left inside the message is found, and the message is kept as said`() = runTest {
        // Seen on the phone: the model put "in one minute" in the message, with no time field.
        tool.call(buildJsonObject { put("message", "Test this up in one minute") }, context.copy(transcript = "Remind me to test this up in one minute."))
        // Our guess from the words; the words stay, as they may be part of what to remember.
        val busContext = context.copy(transcript = "Remind me to tell Ana the bus leaves at 5pm.")
        tool.call(buildJsonObject { put("message", "tell Ana the bus leaves at 5pm") }, busContext)

        assertEquals(
            listOf(
                "Test this up in one minute" to millis(2026, 10, 22, 15, 31),
                "Tell Ana the bus leaves at 5pm" to millis(2026, 10, 22, 17),
            ),
            reminders.map { it.first to it.second },
        )
    }

    @Test
    fun `a time in the message that was never said is ignored`() = runTest {
        // Seen: no time said, but the model's message ended with "3pm".
        val spoken = context.copy(transcript = "Remind me to remind you to remind me about setting up the reminders rig regarding my reminder app.")
        tool.call(buildJsonObject { put("message", "Setting up:reminders: 1 hour, reminder: 3pm") }, spoken)

        assertEquals(null, reminders.single().second)
    }

    @Test
    fun `otherwise the time is looked for in the transcript`() = runTest {
        val spoken = context.copy(transcript = "Remind me to do the tests in two minutes.")
        tool.call(buildJsonObject { put("message", "Do the tests") }, spoken)

        assertEquals("Do the tests" to millis(2026, 10, 22, 15, 32), reminders.single().let { it.first to it.second })
    }

    @Test
    fun `the model's time phrase is taken out of the message`() = runTest {
        val spoken = context.copy(transcript = "Remind me to check the cake in 30 seconds so I can serve it.")
        val args = buildJsonObject {
            put("message", "check the cake in 30 seconds so I can serve it")
            put("date_time_human", "in 30 seconds")
        }
        tool.call(args, spoken)

        assertEquals("Check the cake so I can serve it", reminders.single().first)
    }

    @Test
    fun `a message that isn't word for word what was said is replaced by the transcript`() = runTest {
        // Seen on the phone: garbled by the model.
        val said = "Remind me that the meeting starts at nine A.m at ten p.m."
        tool.call(buildJsonObject { put("message", "Meeting starts at 9 A, p.m at 10 p.m.") }, context.copy(transcript = said))
        // Also seen: the model wrote "thirty" as "30", so it isn't word for word either.
        val cake = "Remind me to check if the cake is done in thirty seconds so I can give it to my friend."
        val args = buildJsonObject {
            put("message", "check if the cake is done in 30 seconds so I can give it to my friend")
            put("date_time_human", "in 30 seconds")
        }
        tool.call(args, context.copy(transcript = cake))

        // The time still comes from the transcript (a guess) or the model's field. "Remind me
        // that/to" goes; a guessed time stays in the words, the model's time is cut as said.
        assertEquals(
            listOf(
                "The meeting starts at nine A.m at ten p.m." to millis(2026, 10, 22, 22, 0),
                "Check if the cake is done so I can give it to my friend." to millis(2026, 10, 22, 15, 30) + 30_000,
            ),
            reminders.map { it.first to it.second },
        )
    }

    @Test
    fun `with two times, the one that rings is cut from the transcript and the other stays`() = runTest {
        // Seen on the phone (recording 85): the model merged the two times in its message
        // ("schedule a meeting for Friday at 9am"), so the transcript is saved.
        val heard = context.copy(transcript = "Remind me to schedule a meeting for Friday at eight tomorrow at nine am")
        tool.call(buildJsonObject { put("message", "schedule a meeting for Friday at 9am"); put("date_time_human", "Friday at 8 tomorrow at 9am") }, heard)
        val inOrder = context.copy(transcript = "Remind me tomorrow at nine am, to schedule a meeting for Friday at eight.")
        tool.call(buildJsonObject { put("message", "schedule a meeting for Friday at 8"); put("date_time_human", "tomorrow at 9am") }, inOrder)

        assertEquals(
            listOf(
                "Schedule a meeting for Friday at eight" to millis(2026, 10, 23, 9),
                "Schedule a meeting for Friday at eight." to millis(2026, 10, 23, 9),
            ),
            reminders.map { it.first to it.second },
        )
    }

    @Test
    fun `a time right after remind me is the when, whatever the model sent`() = runTest {
        // Seen on the phone (recording 86): no time field, and a garbled message with Friday 9am.
        val said = context.copy(transcript = "Remind me tomorrow at nine A.M. to schedule a meeting for Friday at eight.")
        tool.call(buildJsonObject { put("message", "schedule a meeting for Friday at 9 A.M. to schedule a meeting for Friday at 8pm") }, said)
        val later = context.copy(transcript = "Remind me in two hours, that the oven is on.")
        tool.call(buildJsonObject { put("message", "the oven is on"); put("date_time_human", "in 2 hours") }, later)
        // Not a time: the usual rules.
        val notTime = context.copy(transcript = "Remind me to call mom to say happy birthday.")
        tool.call(buildJsonObject { put("message", "call mom to say happy birthday") }, notTime)

        assertEquals(
            listOf(
                "Schedule a meeting for Friday at eight." to millis(2026, 10, 23, 9),
                "The oven is on" to millis(2026, 10, 22, 17, 30),
                "Call mom to say happy birthday" to null,
            ),
            reminders.map { it.first to it.second },
        )
    }

    @Test
    fun `a bare hour right after remind me beats the model's pm`() = runTest {
        // Seen on the phone: rang at 21:00, the model's "9pm".
        val said = context.copy(transcript = "Remind me tomorrow at nine to book the Friday meeting.")
        tool.call(buildJsonObject { put("message", "book the Friday meeting"); put("date_time_human", "tomorrow at 9pm") }, said)

        assertEquals("Book the Friday meeting" to millis(2026, 10, 23, 9), reminders.single().let { it.first to it.second })
    }

    @Test
    fun `a time field it can't read falls back to the transcript`() = runTest {
        // Seen on the phone: the model sent just "September".
        val spoken = context.copy(transcript = "Remind me about my birthday on the twenty-first of September.")
        tool.call(buildJsonObject { put("message", "Birthday on the twenty-first of September"); put("date_time_human", "September") }, spoken)

        // Said on 22 Oct 2026, so the next 21 September is in 2027; a date alone means 9:00.
        assertEquals(millis(2027, 9, 21, 9), reminders.single().second)
    }

    @Test
    fun `one word out of a longer sentence gives the sentence instead`() = runTest {
        // Seen: the model sent just "rain".
        val spoken = context.copy(transcript = "Remind me tomorrow that it's gonna rain.")
        tool.call(buildJsonObject { put("message", "rain"); put("date_time_human", "tomorrow at 9am") }, spoken)

        assertEquals(Triple("It's gonna rain.", millis(2026, 10, 23, 9), 4L), reminders.single())
    }
}
