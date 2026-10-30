package dev.piatra.ui

import dev.piatra.android.SyncState
import dev.piatra.core.agent.DecidedBy
import dev.piatra.core.agent.ToolCallJson
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemKind
import dev.piatra.data.RecordingEntity
import dev.piatra.data.RecordingStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Text shared by the main list and the recording detail screen.
// internal: visible anywhere in this app module, but not outside it.

internal fun statusLabel(recording: RecordingEntity): String = when (recording.status) {
    // An error on a PENDING recording means a retryable failure: it will be tried again.
    RecordingStatus.PENDING ->
        if (recording.error == null) "Waiting to transcribe" else "Will retry: ${recording.error}"
    RecordingStatus.TRANSCRIBING -> "Transcribing…"
    RecordingStatus.ACTING ->
        if (recording.error == null) "Acting on it…" else "Will retry acting on it: ${recording.error}"
    RecordingStatus.DONE -> "Done"
    RecordingStatus.FAILED -> "Failed: ${recording.error}"
}

// The agent's stored tool calls; none if it hasn't run yet (or the JSON is unreadable).
internal fun agentCalls(recording: RecordingEntity) =
    recording.agentResult?.let { runCatching { ToolCallJson.decode(it) }.getOrNull() }.orEmpty()

// What an item is, in words: "Created note", "Created reminder", "Created list item",
// "Set alarm", "Started timer". A list item with a time is a reminder.
internal fun itemKindLabel(item: ItemEntity): String = when (item.kind) {
    ItemKind.NOTE -> "Created note"
    ItemKind.LIST_ITEM -> if (item.dueAt != null) "Created reminder" else "Created list item"
    ItemKind.ALARM -> "Set alarm"
    ItemKind.TIMER -> "Started timer"
}

// The item itself: "Buy milk", "Call mom · Todo · 23/10/2026, 17:00", "Eggs · Shopping",
// "07:30 · Gym", "10 minutes · ends 15:42"
internal fun itemText(item: ItemEntity, listTitles: Map<Long, String>): String = when (item.kind) {
    ItemKind.NOTE -> item.text
    ItemKind.LIST_ITEM -> buildString {
        append(item.text)
        item.listId?.let { listTitles[it] }?.let { append(" · $it") }
        item.dueAt?.let { append(" · ${formatDateTime(it)}") }
    }
    ItemKind.ALARM -> item.dueAt?.let(::formatTime).orEmpty() + item.text.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    ItemKind.TIMER -> item.text + item.dueAt?.let { " · ends ${formatTime(it)}" }.orEmpty()
}

internal fun deciderIcon(decidedBy: DecidedBy): String = when (decidedBy) {
    DecidedBy.MODEL -> "🤖"
    DecidedBy.RULE -> "📏"
    DecidedBy.CORRECTION -> "🔧"
}

// Short date and time in the phone's locale, e.g. "21/10/2026, 13:42".
internal fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

// Time of day in the phone's locale, e.g. "17:00".
internal fun formatTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

// 4200 -> "4.2 s"
internal fun formatSeconds(millis: Long): String =
    String.format(Locale.getDefault(), "%.1f s", millis / 1000.0)

// The board sync's state as one line (Recordings and Device screens), or null when idle.
internal fun syncLabel(state: SyncState): String? = when (state) {
    SyncState.Idle -> null
    is SyncState.Running -> state.message
    is SyncState.Failed -> state.message
    is SyncState.Done -> with(state.summary) {
        val parts = buildList {
            add(
                when (received) {
                    0 -> "Nothing new on the board"
                    1 -> "1 recording received"
                    else -> "$received recordings received"
                }
            )
            if (failed > 0) add("$failed couldn't be downloaded (still on the board)")
            if (unknownFormat > 0) add("$unknownFormat in a format this app doesn't know")
        }
        parts.joinToString(", ") + "."
    }
}
