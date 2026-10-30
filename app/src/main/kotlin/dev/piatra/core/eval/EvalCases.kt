package dev.piatra.core.eval

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds

// The agent test set: phrases with the result we'd want, run by the agent lab.
// Every phrase is treated as said at the same moment, ANCHOR, so expected times can be written
// down and runs are repeatable. Expectations are what a person would mean, not what Piatra does
// today: a failing case is a known weakness, not a typo. Results: docs/agent.md.

// Thursday 22 October 2026, 14:00, in the phone's time zone.
val ANCHOR = LocalDateTime(2026, 10, 22, 14, 0)

class EvalCase(
    val phrase: String,
    val group: String,
    // The tools the agent model alone may pick; empty: it should pick none; null: not scored
    // (e.g. "create a new list", a tool the model isn't offered). Scored separately from the result.
    val modelTools: Set<String>?,
    // What should exist afterwards. Empty: nothing.
    val expected: List<Expect>,
)

// One thing that should be created. `words`: all must appear in its text (ignoring capitals);
// `absent`: none may. `at`: its time; null means it must have none. checkTime = false: don't look at the time.
data class Expect(
    val kind: Kind,
    val words: List<String> = emptyList(),
    val list: String? = null,
    val at: LocalDateTime? = null,
    val checkTime: Boolean = true,
    val absent: List<String> = emptyList(),
) {
    enum class Kind { NOTE, LIST_ITEM, ALARM, TIMER, NEW_LIST }
}

private fun note(vararg words: String) = Expect(Expect.Kind.NOTE, words.toList())
private fun todo(vararg words: String, at: LocalDateTime? = null) = Expect(Expect.Kind.LIST_ITEM, words.toList(), "Todo", at)
private fun onList(list: String, vararg words: String, at: LocalDateTime? = null) = Expect(Expect.Kind.LIST_ITEM, words.toList(), list, at)
private fun alarm(at: LocalDateTime?) = Expect(Expect.Kind.ALARM, at = at, checkTime = at != null)
private fun timer(minutes: Int = 0, seconds: Int = 0) = Expect(Expect.Kind.TIMER, at = plus(ANCHOR, minutes * 60 + seconds))
private fun newList(name: String) = Expect(Expect.Kind.NEW_LIST, listOf(name))

private fun day(day: Int, hour: Int, minute: Int = 0) = LocalDateTime(2026, 10, day, hour, minute)
// Wall-clock arithmetic: via UTC, which has no daylight-saving jumps.
private fun plus(time: LocalDateTime, seconds: Int): LocalDateTime =
    (time.toInstant(TimeZone.UTC) + seconds.seconds).toLocalDateTime(TimeZone.UTC)

private val REMINDER = setOf("create_reminder")
private val LIST_ITEM = setOf("create_list_item")
private val NOTE = setOf("create_note")
private val ALARM = setOf("set_alarm")
private val TIMER = setOf("set_timer")
private val TODO = setOf("create_reminder", "create_list_item") // either is a fine reading of "to do …"
private val NOTHING = emptySet<String>()

val EVAL_CASES: List<EvalCase> = listOf(
    // Reminders: everyday phrasings, plus cases seen in real use.
    EvalCase("Remind me to call mom.", "Reminders", REMINDER, listOf(todo("call mom"))),
    EvalCase("Remind me to call mom tomorrow.", "Reminders", REMINDER, listOf(todo("call mom", at = day(23, 9)))),
    EvalCase("Remind me to call mom the day after tomorrow.", "Reminders", REMINDER, listOf(todo("call mom", at = day(24, 9)))),
    EvalCase("Remind me to call mom tomorrow at 5pm.", "Reminders", REMINDER, listOf(todo("call mom", at = day(23, 17)))),
    EvalCase("Remind me tomorrow to call mom.", "Reminders", REMINDER, listOf(todo("call mom", at = day(23, 9)))),
    EvalCase("Remind me in two hours to check the oven.", "Reminders", REMINDER, listOf(todo("check the oven", at = day(22, 16)))),
    EvalCase("Remind me to check the oven in twenty minutes.", "Reminders", REMINDER, listOf(todo("check the oven", at = day(22, 14, 20)))),
    EvalCase("Remind me on Friday to pay the rent.", "Reminders", REMINDER, listOf(todo("pay the rent", at = day(23, 9)))),
    EvalCase("Remind me next Monday at nine to call the dentist.", "Reminders", REMINDER, listOf(todo("dentist", at = day(26, 9)))),
    EvalCase(
        "Remind me about my birthday on the twenty first of September next year.", "Reminders", REMINDER,
        listOf(todo("birthday", at = LocalDateTime(2027, 9, 21, 9, 0))),
    ),
    EvalCase("Remind me to buy orange juice.", "Reminders", REMINDER, listOf(todo("orange juice"))),
    EvalCase("Remind me that the meeting starts at nine at ten pm.", "Reminders", REMINDER, listOf(todo("meeting", at = day(22, 22)))),
    EvalCase("Remember to water the plants.", "Reminders", REMINDER, listOf(todo("water the plants"))),
    EvalCase("Remember to take the bins out tomorrow evening.", "Reminders", REMINDER, listOf(todo("bins", at = day(23, 19)))),

    // Lists.
    EvalCase("Add eggs to my shopping list.", "Lists", LIST_ITEM, listOf(onList("Shopping", "eggs"))),
    EvalCase("Add call the bank to my to-do list.", "Lists", LIST_ITEM, listOf(todo("call the bank"))),
    EvalCase("Add sunscreen to my packing list.", "Lists", LIST_ITEM, listOf(todo("sunscreen"))), // no packing list: Todo
    EvalCase(
        "Milk, eggs, butter.", "Lists", LIST_ITEM,
        listOf(onList("Shopping", "milk"), onList("Shopping", "eggs"), onList("Shopping", "butter")),
    ),
    EvalCase("Buy oranges.", "Lists", LIST_ITEM, listOf(onList("Shopping", "oranges"))),
    EvalCase("Buy orange juice tomorrow at nine am.", "Lists", LIST_ITEM, listOf(onList("Shopping", "orange juice", at = day(23, 9)))),
    EvalCase("Get milk.", "Lists", LIST_ITEM, listOf(onList("Shopping", "milk"))),
    EvalCase("We need more toilet paper.", "Lists", LIST_ITEM, listOf(onList("Shopping", "toilet paper"))),
    EvalCase("Create a new list called packing.", "Lists", null, listOf(newList("Packing"))), // model not offered create_list

    // Todo shortcut.
    EvalCase("To do, call mom.", "Todo", TODO, listOf(todo("call mom"))),
    EvalCase("Too do, call mom.", "Todo", TODO, listOf(todo("call mom"))),
    EvalCase("To do book the car service tomorrow at nine am.", "Todo", TODO, listOf(todo("book the car service", at = day(23, 9)))),

    // Notes.
    EvalCase("Note to self: the wifi password is on the router.", "Notes", NOTE, listOf(note("wifi password", "router"))),
    EvalCase("Make a note that the car needs oil.", "Notes", NOTE, listOf(note("car needs oil"))),
    EvalCase("Note down that the gate code is 4512.", "Notes", NOTE, listOf(note("gate code", "4512"))),
    EvalCase("I have an idea for an app that turns voice notes into reminders.", "Notes", NOTE, listOf(note("idea", "app"))),
    EvalCase(
        "Design and sew pockets for water bottles from scrap jean fabric, make a note of that.", "Notes", NOTE,
        listOf(note("pockets", "jean fabric")),
    ),

    // Alarms and timers.
    EvalCase("Set an alarm for two twenty seven.", "Alarms & timers", ALARM, listOf(alarm(day(22, 14, 27)))),
    EvalCase("Set an alarm for seven thirty tomorrow morning.", "Alarms & timers", ALARM, listOf(alarm(day(23, 7, 30)))),
    EvalCase("Wake me up at six.", "Alarms & timers", ALARM, listOf(alarm(day(23, 6)))), // "wake me up": morning
    EvalCase("Set an alarm to go off in one minute.", "Alarms & timers", ALARM, listOf(alarm(day(22, 14, 1)))),
    EvalCase("Set a timer for ten minutes.", "Alarms & timers", TIMER, listOf(timer(minutes = 10))),
    EvalCase("Set the timer to go off in thirty five seconds.", "Alarms & timers", TIMER, listOf(timer(seconds = 35))),
    EvalCase("Start a timer for an hour and a half.", "Alarms & timers", TIMER, listOf(timer(minutes = 90))),

    // Not requests: the model alone should pick nothing; Piatra keeps them as notes.
    EvalCase("The weather is nice today.", "Nothing", NOTHING, listOf(note("weather is nice"))),
    EvalCase("Thank you.", "Nothing", NOTHING, listOf(note("thank you"))),
)
