package dev.piatra.core.agent

import dev.piatra.core.Clock
import dev.piatra.core.ClockApp
import kotlinx.datetime.TimeZone

// Every tool, wired to the stores it writes to. One place, so the app (AppModule) and the agent
// lab's dry runs (core/eval) use exactly the same set.
fun piatraTools(
    items: ItemStore,
    lists: ListStore,
    clockApp: ClockApp,
    clock: Clock,
    timeZone: () -> TimeZone,
    // The "Use the model's wording" setting, for notes and reminders (Tool.kt).
    useModelText: suspend () -> Boolean = { true },
): List<Tool> = listOf(
    CreateNoteTool(items, useModelText),
    CreateReminderTool(items, lists, timeZone, useModelText),
    CreateListItemTool(items, lists, timeZone),
    SetAlarmTool(clockApp, items, timeZone),
    SetTimerTool(clockApp, items, clock, timeZone),
    CreateListTool(lists),
)
