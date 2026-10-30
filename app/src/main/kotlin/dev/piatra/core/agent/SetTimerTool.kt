package dev.piatra.core.agent

import dev.piatra.core.Clock
import dev.piatra.core.ClockApp
import dev.piatra.core.time.findReminderTime
import dev.piatra.core.time.resolveReminderTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant

// Starts a countdown timer in the phone's Clock app. Definition copied word for word from
// upstream (experimental/.../builtin_servlets/clock/SetTimerTool.kt); the call is a trimmed port.
class SetTimerTool(
    private val clockApp: ClockApp,
    private val items: ItemStore,
    private val clock: Clock,
    private val timeZone: () -> TimeZone,
) : Tool {
    override val name = "set_timer"
    override val description = "Set a timer for the specified duration or end time"
    override val parameters = toolSchema(
        required = listOf("time_human"),
        "time_human" to ("string" to "The duration or target end time to set the timer for in human " +
            "readable format e.g. '1 hour and 30 minutes', '45 minutes', 'for 1:50pm', 'at 13:30'"),
    )

    @Serializable
    private data class Args(@SerialName("time_human") val timeHuman: String = "")

    override suspend fun call(arguments: JsonObject, context: ToolContext) {
        val words = ToolJson.decodeFromJsonElement<Args>(arguments).timeHuman.trim()
        val zone = timeZone()
        val spokenAt = Instant.fromEpochMilliseconds(context.recordedAt)
        // "10 minutes" on its own isn't a time phrase the parser reads, so try "in 10 minutes" too,
        // then the transcript. Counted from when it was said.
        val endsAt = sequenceOf(words, "in $words")
            .filter { it.isNotBlank() && it != "in " }
            .firstNotNullOfOrNull { resolveReminderTime(it, spokenAt, zone) }
            ?: findReminderTime(context.transcript, spokenAt, zone)?.at
            ?: throw IllegalArgumentException("No timer length in \"${context.transcript}\"")
        // Upstream's rule: the timer ends when it would have if set the moment
        // the user spoke, so processing time comes off what's left.
        val secondsLeft = (endsAt.toEpochMilliseconds() - clock.nowMillis() + 999) / 1000
        require(secondsLeft > 0) { "The timer would already have ended" }
        clockApp.setTimer(secondsLeft.toInt())
        items.addTimer(words, endsAt.toEpochMilliseconds(), context.recordingId)
    }
}
