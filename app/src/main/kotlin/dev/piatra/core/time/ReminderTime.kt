package dev.piatra.core.time

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

// When should a reminder fire? Turns the model's time words ("tomorrow at 5pm", "in 2 hours")
// into a moment. Returns null if the words can't be understood.
//   anchor: when the user *spoke* (the recording's start), not when we got round to processing
//           it. "In 2 hours" counts from then, and "today" means the day it was said.
// Three kinds of time, as the parser reports them (rules ported from upstream ReminderTool.call):
//   - Instant:   moment on the timeline, the same everywhere ("1792…ms after 1970").
//   - LocalDateTime: what a wall clock shows ("23 Oct, 17:00"); needs a TimeZone to become an Instant.
//   - LocalTime / LocalDate: just a time of day, or just a day.
// bareHourIsMorning: an hour said without am/pm is the morning one ("wake me up at six"), instead
// of whichever comes first (see nextTimeOfDay).
fun resolveReminderTime(words: String, anchor: Instant, timeZone: TimeZone, bareHourIsMorning: Boolean = false): Instant? {
    val parsed = HumanDateTimeParser(frozenClock(anchor), timeZone).parse(words) ?: return null
    val spokenAt = anchor.toLocalDateTime(timeZone)

    val wallClock: LocalDateTime = when (parsed) {
        // "on Friday": a day without a time means 9:00 that morning.
        is InterpretedDateTime.AbsoluteDate -> LocalDateTime(parsed.date, LocalTime(9, 0))
        // "tomorrow at 5pm": exactly that. A bare hour on a day: see onADay.
        is InterpretedDateTime.AbsoluteDateTime -> {
            val exact = bareHourIsMorning || AM_PM_SAID.containsMatchIn(words) || LEADING_ZERO_HOUR.containsMatchIn(words)
            if (exact) parsed.dateTime else return onADay(parsed.dateTime, anchor, timeZone)
        }
        // "at 5pm", "at 3": the next time the clock shows it (see nextTimeOfDay for "at 3").
        // A leading zero ("02:27") is a 24-hour time too: exact, like a said am/pm.
        is InterpretedDateTime.AbsoluteTime -> {
            val exact = parsed.amPmExplicit || LEADING_ZERO_HOUR.containsMatchIn(words)
            return nextTimeOfDay(parsed.time, amPmSaid = exact, anchor, timeZone, bareHourIsMorning)
        }
        // "in 2 hours", "in 3 days", "in 1 month": counted from the anchor.
        is InterpretedDateTime.Relative -> {
            // The parser gives minutes, hours and days as an exact duration ("in 1 day" = 24 h),
            // but months and years as a calendar period: "in 1 month" means the same clock time
            // on that date, even if the clocks changed meanwhile.
            val period = parsed.period ?: return anchor + parsed.duration
            val sameTimeLater = LocalDateTime(spokenAt.date.plus(period), spokenAt.time)
            return sameTimeLater.toInstant(timeZone) + parsed.duration
        }
    }
    return wallClock.toInstant(timeZone)
}

private val LEADING_ZERO_HOUR = Regex("""\b0\d[:.]\d\d""")
// am/pm said, or a part of the day that settles it ("tomorrow morning at 3").
private val AM_PM_SAID = Regex("""(?<![a-z])[ap]\.?\s*m\b|morning|afternoon|evening|night|noon|midnight""", RegexOption.IGNORE_CASE)

// A day with an hour from 1 to 12 and no am/pm ("on Friday at three"). The parser reads it as the
// morning (Friday 03:00), and "whichever comes first" would always pick that too, so instead:
// on the day it was said, whichever comes first (see nextTimeOfDay); on another day, waking
// hours: 1-6 is the afternoon (15:00), 7-12 the morning or noon ("tomorrow at nine" is 09:00).
// (Seen: "on Friday at three" rang at 03:00.)
private fun onADay(said: LocalDateTime, anchor: Instant, timeZone: TimeZone): Instant {
    if (said.hour !in 1..12) return said.toInstant(timeZone)
    if (said.date == anchor.toLocalDateTime(timeZone).date) return nextTimeOfDay(said.time, amPmSaid = false, anchor, timeZone)
    val hour = if (said.hour <= 6) said.hour + 12 else said.hour
    return LocalDateTime(said.date, LocalTime(hour, said.minute)).toInstant(timeZone)
}

// A time phrase found inside free text, and the moment it means.
//   rest: the text with the phrase cut out ("call mom tomorrow at nine am" -> "call mom"). If the
//         parser had to normalise the text to read it, `rest` is in that lowercased, normalised
//         form ("nine" -> "9"); fine for short things like list items.
//   restAsSaid: the same cut made in the words as said, capitals and all ("Call Ana tomorrow at
//         nine am" -> "Call Ana"), or null if the phrase couldn't be lined up with those words.
data class FoundTime(val at: Instant, val phrase: String, val rest: String, val restAsSaid: String? = null)

// Looks for a time phrase anywhere in a sentence: "Test this in two minutes" -> (in two minutes
// from the anchor, "in two minutes"). For when the model leaves the time inside its message
// instead of the time field. Uses the parser's parseFromMessage (upstream).
fun findReminderTime(text: String, anchor: Instant, timeZone: TimeZone, bareHourIsMorning: Boolean = false): FoundTime? {
    val parser = HumanDateTimeParser(frozenClock(anchor), timeZone)
    val found = parser.parseFromMessage(text) ?: return null
    val at = resolveReminderTime(found.matchedText, anchor, timeZone, bareHourIsMorning) ?: return null
    val rest = parser.matchSource(text).removeRange(found.range).replace(Regex("\\s+"), " ").trim().trimEnd(',', '.')
    return FoundTime(at, found.matchedText, rest, cutAsSaid(text, found.matchedText, parser))
}

// Cuts `phrase` (found in the normalised text, "tomorrow at 9 am") out of `text` as said
// ("tomorrow at nine am"): tries runs of the original words until one normalises to the phrase.
// A run can be a few words longer or shorter than the phrase ("twenty one" -> "21").
private fun cutAsSaid(text: String, phrase: String, parser: HumanDateTimeParser): String? {
    val target = plainWords(phrase)
    val said = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    for (start in said.indices) {
        for (end in start + 1..minOf(said.size, start + target.size + 4)) {
            val run = said.subList(start, end).joinToString(" ")
            if (plainWords(parser.matchSource(run)) != target) continue
            val rest = (said.subList(0, start) + said.subList(end, said.size)).joinToString(" ")
                .replace(Regex("\\s+([,.!?])"), "$1") // "Call Ana ." -> "Call Ana."
                .trim().trimStart(',', '.', ' ')
            return rest.takeIf { r -> r.any { it.isLetter() } }
        }
    }
    return null
}

// "Tomorrow at 9 A.M.," -> [tomorrow, at, 9, a, m]: only for comparing.
private fun plainWords(text: String): List<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

// A clock stuck at the anchor, so the parser's "today", "tomorrow" and "now" mean the moment of speaking.
private fun frozenClock(anchor: Instant) = object : Clock {
    override fun now(): Instant = anchor
}

// The next moment the clock shows a time of day, counted from `anchor`.
// People say times the 12-hour way ("two twenty seven") whatever their phone shows, so with no
// am/pm said, an hour from 1 to 12 means whichever comes first: "two twenty seven" said at 14:26
// is 14:27, and "seven" said at 23:00 is 07:00. An explicit am/pm, or a 24-hour time ("15:00",
// "02:27"), is taken exactly. (Piatra's rule; upstream instead read
// "at 3" as 03:00 on 24-hour phones.) With bareHourIsMorning, a bare hour is taken as said: the
// morning (hours 1-11) or noon (12), today or tomorrow ("wake me up at six").
fun nextTimeOfDay(time: LocalTime, amPmSaid: Boolean, anchor: Instant, timeZone: TimeZone, bareHourIsMorning: Boolean = false): Instant {
    val candidates = if (!amPmSaid && !bareHourIsMorning && time.hour in 1..12) {
        listOf(time, LocalTime((time.hour + 12) % 24, time.minute, time.second)) // 12 -> 0: midnight
    } else {
        listOf(time)
    }
    return candidates.minOf { nextOccurrence(it, anchor, timeZone) }
}

// The first time on or after `anchor` that the clock shows `time`: today, or else tomorrow.
private fun nextOccurrence(time: LocalTime, anchor: Instant, timeZone: TimeZone): Instant {
    val today = anchor.toLocalDateTime(timeZone).date
    val todayAt = LocalDateTime(today, time).toInstant(timeZone)
    return if (todayAt >= anchor) todayAt else LocalDateTime(today.plus(DatePeriod(days = 1)), time).toInstant(timeZone)
}
