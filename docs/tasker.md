# Tasker hand-off

[Tasker](https://tasker.joaoapps.com/) is an Android automation app. When **Send to Tasker** is on (Settings; greyed out while Tasker isn't installed), every new note and list item is also sent to Tasker, whether the agent made it from a voice note or you typed it. Reminders are included, since they're Todo items with a due time. Piatra keeps its own copy as usual. Alarms and timers aren't sent; they already went to the Clock app. The Agent lab's dry runs never send anything.

## What Piatra sends

A broadcast intent, delivered only to Tasker (`net.dinglisch.android.taskerm`):

- **Action:** `dev.piatra.action.NEW_ITEM`
- **Extras** (each becomes a Tasker local variable):

| Extra | Tasker variable | Example |
|---|---|---|
| `kind` | `%kind` | `note` or `list_item` |
| `text` | `%text` | `Call mom` |
| `list` | `%list` | `Todo` (empty for notes) |
| `due` | `%due` | `2026-10-22T17:00`, the phone's local time (empty if none) |

Sending is fire-and-forget: if it fails, the item is still saved in Piatra. "Transcribe again" sends the recording's items again.

## Set up a profile in Tasker

1. **Profiles → +** → **Event** → **System** → **Intent Received**.
2. **Action:** `dev.piatra.action.NEW_ITEM`. Leave the other fields empty. Back out.
3. Create a new task, e.g. **Alert → Flash**, text: `%kind: %text %list %due`.
4. Make sure Tasker is enabled (the switch at the top) and allowed to run in the background.

Then route it wherever you like: for example add `If %kind ~ note` around an action that appends `%text` to a file.

## Where it is in the code

Under `app/src/main/kotlin/dev/piatra/`: `core/agent/ItemHandoff.kt` (`HandoffItemStore` wraps the item store, so every saved item is also handed off) and `android/TaskerHandoff.kt` (builds and sends the intent). To send another field, add a `putExtra` there and a row to the table above.
