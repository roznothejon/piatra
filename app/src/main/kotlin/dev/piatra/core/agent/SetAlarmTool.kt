package dev.piatra.core.agent

import dev.piatra.core.ClockApp
import dev.piatra.core.time.findReminderTime
import dev.piatra.core.time.nextTimeOfDay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.math.roundToInt
import kotlin.time.Instant

// Sets an alarm in the phone's Clock app. Definition copied word for word from upstream
// (experimental/.../builtin_servlets/clock/SetAlarmTool.kt).
class SetAlarmTool(
    private val clockApp: ClockApp,
    private val items: ItemStore,
    private val timeZone: () -> TimeZone,
) : Tool {
    override val name = "set_alarm"
    override val description = "Set an alarm for a specific clock time like 8am or 16:30."
    override val parameters = toolSchema(
        required = listOf("time_hours", "time_minutes"),
        "time_hours" to ("number" to "The hour component of the alarm time (24 hour time)"),
        "time_minutes" to ("number" to "The minute component of the alarm time (0-59)"),
        "label" to ("string" to "An optional label or title for the alarm"),
    )

    // Doubles: the model may write 7 or 7.0.
    @Serializable
    private data class Args(
        @SerialName("time_hours") val hours: Double? = null,
        @SerialName("time_minutes") val minutes: Double? = null,
        val label: String? = null,
    )

    override suspend fun call(arguments: JsonObject, context: ToolContext) {
        val args = ToolJson.decodeFromJsonElement<Args>(arguments)
        val zone = timeZone()
        val spokenAt = Instant.fromEpochMilliseconds(context.recordedAt)
        // The time from the user's own words when the parser can read them, else the model's
        // numbers. The model read "set an alarm to go off in one minute" as 1:00 and
        // "two twenty seven" as 2:20; the parser gets both right.
        // "An alarm for 7:30" means at 7:30, but the parser only reads "at", so try that wording
        // too, unless a duration follows ("for ten minutes" stays a duration). That wording goes
        // first: in "an alarm for six AM tomorrow" the words as said only give "tomorrow", which
        // means 9:00 (seen on the phone).
        val asAt = context.transcript.replace(FOR_A_TIME, "at")
        // "Wake me up at six": the morning, not whichever six comes first (18:00 if said at 14:00).
        val morning = WAKE_ME.containsMatchIn(context.transcript)
        val fromWords = findReminderTime(asAt, spokenAt, zone, morning)
            ?: findReminderTime(context.transcript, spokenAt, zone, morning)
        // Words that name only a day ("tomorrow") mean 9:00 to the parser. For an alarm that
        // almost always means the parser missed the time, so then the model's hour and minute,
        // on that day, go first.
        val onTheDay = fromWords?.takeIf { isDayOnly(it.phrase) }?.let { modelTimeOn(args, it.at, spokenAt, zone) }
        val at = onTheDay
            ?: fromWords?.at
            ?: modelTime(args, spokenAt, zone, morning)
            ?: throw IllegalArgumentException("No alarm time in \"${context.transcript}\"")
        // The Clock app takes just hour and minute: the next time the clock shows it.
        val wallClock = at.toLocalDateTime(zone)
        val label = args.label?.trim().orEmpty()
        clockApp.setAlarm(wallClock.hour, wallClock.minute, label.ifEmpty { null })
        items.addAlarm(label, at.toEpochMilliseconds(), context.recordingId)
    }

    // The model's hour and minute as the next moment the clock shows them. The model just
    // echoes the spoken hour ("two" -> 2), so the same am/pm rule applies as for spoken times.
    private fun modelTime(args: Args, spokenAt: Instant, zone: TimeZone, morning: Boolean): Instant? {
        val hour = args.hours?.roundToInt()?.takeIf { it in 0..23 } ?: return null
        val minute = args.minutes?.roundToInt()?.takeIf { it in 0..59 } ?: 0
        return nextTimeOfDay(LocalTime(hour, minute), amPmSaid = false, spokenAt, zone, bareHourIsMorning = morning)
    }

    // The model's hour and minute, taken as given (24-hour), on the day of `day`; null if the
    // model sent no hour or that moment has passed.
    private fun modelTimeOn(args: Args, day: Instant, spokenAt: Instant, zone: TimeZone): Instant? {
        val hour = args.hours?.roundToInt()?.takeIf { it in 0..23 } ?: return null
        val minute = args.minutes?.roundToInt()?.takeIf { it in 0..59 } ?: 0
        val at = LocalDateTime(day.toLocalDateTime(zone).date, LocalTime(hour, minute)).toInstant(zone)
        return at.takeIf { it >= spokenAt }
    }

    companion object {
        // A phrase made only of day words: "tomorrow", "on Friday", "the day after tomorrow".
        private val DAY_WORDS = setOf(
            "today", "tomorrow", "the", "day", "after", "on", "next", "this",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        )

        private fun isDayOnly(phrase: String): Boolean {
            val words = phrase.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            return words.isNotEmpty() && words.all { it in DAY_WORDS }
        }

        private val WAKE_ME = Regex("""\bwake\s+me\b""", RegexOption.IGNORE_CASE)

        // "for", unless what follows is a duration ("for ten minutes", "for 2 hours").
        private val FOR_A_TIME = Regex("""\bfor\b(?!\s+\S+\s+(?:seconds?|minutes?|hours?)\b)""", RegexOption.IGNORE_CASE)
    }
}
