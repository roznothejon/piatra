package dev.piatra.core.agent

import dev.piatra.core.time.findReminderTime
import dev.piatra.core.time.resolveReminderTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant

// Creates a reminder: an item on the Todo list; with a time, the phone also notifies you then. Definition copied word for word
// from upstream (experimental/.../builtin_servlets/reminders/ReminderTool.kt), as the agent model
// was fine-tuned on it. Its call logic is a trimmed port of upstream's ReminderTool.call.
class CreateReminderTool(
    private val items: ItemStore,
    private val lists: ListStore,
    // Read when a reminder is created, so a change of time zone or clock setting is picked up.
    private val timeZone: () -> TimeZone,
    // The "Use the model's wording" setting (Tool.kt). Off: the transcript is saved, and no time
    // is read out of the model's message (its time fields still count).
    private val useModelText: suspend () -> Boolean = { true },
) : Tool {
    override val name = "create_reminder"
    override val description = "Set a reminder optionally for a future time. Use when the user requests a " +
        "reminder, asks to remember to do something ('remind me to...', 'remember to...'), or wants to be " +
        "reminded at a specific time or date."
    override val parameters = toolSchema(
        required = listOf("message"),
        "date_time_human" to ("string" to "If provided by the user, the date and/or time to remind the user " +
            "in human readable format. Must be in English — translate it if the user spoke another language. " +
            "e.g. 'tomorrow at 13:00', 'next Monday at 9am', 'on July 5th at 14:30', 'at 3pm'"),
        "duration_human" to ("string" to "If provided by the user, the duration from now to remind the user " +
            "in human readable format. Must be in English — translate it if the user spoke another language. " +
            "e.g. 'in 2 hours', 'in 30 minutes', 'in 1 day and 3 hours'"),
        "notification_hours_before" to ("number" to "If the user requests to be reminded before the reminder " +
            "time, set the number of hours here, for example 'before Monday' would be 24 hours notice, but " +
            "'before 3pm' would be 1 hour notice."),
        "message" to ("string" to "The message to remind the user e.g. 'Buy more milk'"),
    )

    // @SerialName: the JSON key, when it differs from the Kotlin name (Kotlin style is camelCase).
    // notification_hours_before isn't read: early heads-up notifications aren't built.
    @Serializable
    private data class Args(
        val message: String = "",
        @SerialName("date_time_human") val dateTimeHuman: String? = null,
        @SerialName("duration_human") val durationHuman: String? = null,
    )

    override suspend fun call(arguments: JsonObject, context: ToolContext) {
        val args = ToolJson.decodeFromJsonElement<Args>(arguments)
        // The model's message only if it's word for word what was said; else the transcript.
        val useModelText = useModelText() || context.decidedBy == DecidedBy.RULE
        var message = modelTextOrTranscript(args.message, context.transcript, useModelText)
        val savingTranscript = message == context.transcript.trim()
        val anchor = Instant.fromEpochMilliseconds(context.recordedAt)
        val timeZone = timeZone()

        // 0. A time said right after "remind me" is the when, never part of the what: "Remind me
        // tomorrow at nine to book the Friday meeting". It beats the model, which merged such
        // times. The text is then what follows "to/that/about".
        val lead = leadTime(context.transcript, anchor, timeZone)

        // 1. The model's time field, if there is one and it can be read.
        // Except when it's cut short: "the day after tomorrow" sent as "tomorrow".
        val timeWords = (args.dateTimeHuman ?: args.durationHuman)?.takeIf { it.isNotBlank() }
            ?.takeUnless { DAY_AFTER_TOMORROW.containsMatchIn(context.transcript) && !DAY_AFTER_TOMORROW.containsMatchIn(it) }
        val fromModel = timeWords?.let { resolveReminderTime(it, anchor, timeZone) }
        if (timeWords != null && fromModel == null) println("CreateReminderTool: couldn't understand the time '$timeWords'")

        val dueAt = if (lead != null) {
            if (savingTranscript) message = lead.second else if (timeWords != null) message = withoutPhrase(message, timeWords)
            lead.first
        } else if (fromModel != null) {
            // The model often repeats the time in the message too: "check the cake in 30 seconds".
            message = withoutPhrase(message, timeWords!!)
            // When saving the transcript, also cut the time as said, if it's the time that rings:
            // "Remind me to book Friday at eight tomorrow at nine am" -> "...book Friday at eight".
            if (savingTranscript) {
                val said = findReminderTime(message, anchor, timeZone)
                if (said?.at == fromModel) said.restAsSaid?.let { message = it }
            }
            fromModel
        } else {
            // 2. No usable time field (none, or e.g. just "September"). The model often leaves the
            // time inside its message ("Test this in two minutes"), so look there, then in the
            // whole transcript. A reminder whose time can't be found is saved without one.
            // This time is our guess, so the message is kept exactly as it was: the phrase may
            // be part of what to remember ("tell Ana the bus leaves at 5"), and the text then
            // shows what the guess was based on.
            // Search the model's own message first, even when we don't save it: it often writes the
            // times more cleanly ("nine" -> "9"), and with two times in a sentence ("the meeting
            // starts at nine at ten pm") it tends to keep the one meant. Then the transcript.
            // But only if a time was said at all: the model also makes times up ("...reminder:
            // 3pm" for a sentence with no time). Not with the model's
            // wording switched off.
            val said = findReminderTime(context.transcript, anchor, timeZone)?.at
            val inModelMessage = if (useModelText) findReminderTime(args.message, anchor, timeZone)?.at else null
            said?.let { inModelMessage ?: it }
        }
        // "Remind me to call mom" -> "call mom": the request isn't part of what to remember.
        if (savingTranscript) message = withoutRemindMe(message)
        items.addListItem(
            message.replaceFirstChar { it.uppercase() },
            lists.idForSeed(DefaultLists.TODO),
            dueAt?.toEpochMilliseconds(),
            context.recordingId,
        )
    }

    private companion object {
        val DAY_AFTER_TOMORROW = Regex("""\bday\s+after\s+tomorrow\b""", RegexOption.IGNORE_CASE)
        val LEAD_TIME = Regex("""^\s*(?:please\s+)?remind\s+me\s+(.+?)[\s,]+(?:to|that|about|of)\b(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val LEFTOVER_CLOCK = Regex("""at \d{1,2}(:\d\d)?""")
        val REMIND_ME = Regex("""^\s*(please\s+)?remind\s+me\b[\s,]*((to|that|about|of)\b)?[\s,]*""", RegexOption.IGNORE_CASE)
    }

    // "Remind me tomorrow at nine, to call Ana" -> (tomorrow 9:00, "call Ana"). Null unless all
    // the words between "remind me" and "to/that/about/of" are one time phrase.
    private fun leadTime(transcript: String, anchor: Instant, timeZone: TimeZone): Pair<Instant, String>? {
        val match = LEAD_TIME.find(transcript) ?: return null
        val (words, what) = match.destructured
        val text = what.trim().takeIf { w -> w.any { it.isLetter() } } ?: return null
        val found = findReminderTime(words, anchor, timeZone) ?: return null
        if (!found.rest.any { it.isLetter() }) return found.at to text
        // Inside a sentence the parser takes only "tomorrow" from "tomorrow at nine", leaving
        // "at 9" (and the model's "9pm" won, seen on the phone). Read them as one.
        if (!LEFTOVER_CLOCK.matches(found.rest)) return null // more than a time: "to call mom"
        return resolveReminderTime(words, anchor, timeZone)?.let { it to text }
    }

    // Keeps the message as it was if nothing but "remind me" is left.
    private fun withoutRemindMe(message: String): String =
        message.replaceFirst(REMIND_ME, "").takeIf { rest -> rest.any { it.isLetter() } } ?: message

    // "Check the cake in 30 seconds" minus "in 30 seconds" -> "Check the cake". Keeps the message
    // as it was if the phrase isn't found word for word or nothing's left.
    private fun withoutPhrase(message: String, phrase: String): String {
        if (!message.contains(phrase, ignoreCase = true)) return message
        val rest = message.replace(phrase, "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd(',', '.', ' ')
        return rest.ifEmpty { message }
    }
}
