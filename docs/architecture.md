# Architecture

How Piatra fits together, from a button press to a reminder on the phone, and where to look
when you want to change something. Paths in the app are under
`app/src/main/kotlin/dev/piatra/` unless they say otherwise.

## The big picture

```
 board (firmware/)                        phone (app/)
 ─────────────────                        ────────────────────────────────────────────────
 button → mic → Opus → flash              BoardScanner hears an advert
          │                                     │
          └── BLE advert (status) ──────────►  BoardSyncService (foreground) → BoardSync
                                                │   GET each recording, check CRC, DELETE
              ◄── GET / DELETE over GATT ───    ▼
                                          RecordingStorage (Ogg Opus file) + Room row
 phone mic (MainScreen) ───────────────►        │
                                                ▼
                                          RecordingProcessingQueue (one at a time)
                                                │  1. transcribe (Parakeet, via Cactus)
                                                │  2. AgentRunner: rules → model → corrections
                                                ▼
                                          tools: note, list item / reminder, new list,
                                                 alarm, timer (Clock app)
                                                │
                                                ├─► Room (items, lists) → UI
                                                ├─► exact alarm → reminder notification
                                                └─► Tasker / Obsidian (optional hand-offs)
```

Nothing in this path uses the network. The network is only used by `android/ModelManager.kt`
to download the two models once.

## The app's layers

The app is one Gradle module (`app/`) plus `cactus/` (the inference engine). Inside `app/`
the code is split by how much it depends on Android:

| Package | What's in it | Rule |
|---|---|---|
| `core/` | The logic: the recording queue, the agent and its tools, shortcut rules, time parsing, the board protocol and sync, Opus framing, backup format, the agent test set | **Plain Kotlin, no Android APIs.** Unit-tested on the laptop. |
| `android/` | Platform pieces behind `core/` interfaces: Bluetooth (Kable), the sync service, background scanning, notifications, exact alarms, the Clock app, MediaCodec Opus, files, Tasker, the Obsidian folder, model downloads, Cactus | One file per platform job. |
| `data/` | Room (entities, DAOs, repositories) and `Settings` (DataStore) | Repositories implement `core/` interfaces such as `ItemStore` and `RecordingQueueStore`. |
| `ui/` | Jetpack Compose screens, each with a ViewModel | ViewModels talk to `core/`, `data/` and `android/`; screens only to their ViewModel. |

Keeping `core/` free of Android has two payoffs: its tests run in seconds without a phone
(`./gradlew testDebugUnitTest`), and the logic could move to Kotlin Multiplatform later
(for an iOS app) mostly by moving files. When you add logic, put it in `core/` behind a small
interface, and the Android side in its own file under `android/`.

**Wiring.** `AppModule.kt` builds every shared object with [Koin](https://insert-koin.io/)
(`single { … }` makes one instance; `viewModel { … }` one per screen). It's the place to see
which implementation stands behind each interface, e.g. `Agent` →
`ShortcutsFirstAgent(CactusAgent)`, `ItemStore` → `ItemRepository`. `PiatraApp.kt` starts Koin
and the background pieces (reminder alarms, the board scan) when the process starts.

## Following a recording

### 1. Getting the audio

- **From the board:** the board advertises only when it has a reason, with a short status
  (notes waiting, battery, storage) in the advert. `android/BoardScanner.kt` keeps a
  low-power scan running in Android's Bluetooth stack, even when Piatra isn't running;
  `core/board/AutoSync.kt` decides whether an advert is worth a sync. `android/BoardPresence.kt`
  (Android's companion-device watch) is a fallback trigger.
  `android/BoardSyncManager.kt` starts `BoardSyncService`, a foreground service that keeps
  the app alive during the sync, and runs `core/board/BoardSync.kt` over
  `android/KableBoardLink.kt`. The protocol is in [`esp32-protocol.md`](esp32-protocol.md).
  Each downloaded recording goes through `android/BoardRecordingSink.kt`, and only once it's
  saved and queued is the board told to delete it.
- **From the phone mic:** `ui/MainScreen.kt` / `MainViewModel` record through
  `core/AudioRecorder.kt` (`android/AndroidAudioRecorder.kt`). Recordings shorter than 1 s
  are dropped.

Either way, `android/RecordingStorage.kt` keeps the file (`filesDir/recordings/<id>.opus`,
Ogg Opus) and `data/RecordingRepository.kt` adds a row to the `recordings` table.

### 2. The queue

`core/RecordingProcessingQueue.kt` processes recordings one at a time. The recording's row
*is* the job: its `status` (`RecordingStatus` in `data/RecordingEntity.kt`) moves
`PENDING` → `TRANSCRIBING` → `ACTING` → `DONE` or `FAILED`, and `attempts` counts tries, so work survives crashes
and restarts. A failure that may pass (`RecoverableTaskException`, e.g. no model yet) is retried; others
mark the recording failed with a message.

### 3. Transcription

`android/CactusTranscriptionService.kt` asks `RecordingStorage` for 16 kHz 16-bit mono PCM
(the pipeline's input format; `core/PcmFormat.kt`), then runs Parakeet through the Cactus
engine. `core/SpeechText.kt` rejects transcripts without speech. The model (English or
multilingual) is chosen in Settings (`core/SpeechModelChoice.kt`, `android/SpeechModels.kt`).

### 4. The agent

`core/agent/Agent.kt` (`AgentRunner`) runs the agent on the transcript and carries out its
tool calls. The agent is layered (`core/agent/ShortcutsFirstAgent.kt`):

1. **Shortcut rules** (`ShortcutRules.kt`, editable in Settings, stored in Room): "to do …",
   "buy …", "note …" decide alone.
2. **The model** (`android/CactusAgent.kt`, `needle-pebble-ft`): picks tools and arguments.
3. **Corrections** of the model's known mistakes.

The tools (`CreateNoteTool`, `CreateReminderTool`, `CreateListItemTool`, `CreateListTool`,
`SetAlarmTool`, `SetTimerTool`, all listed in `PiatraTools.kt`) decide the final text and
time, using `core/time/ReminderTime.kt` on top of the parser in `HumanDateTimeParser.kt`.
**Every rule is written down in [`agent-rules.md`](agent-rules.md)**, and the Agent lab's
test set ([`agent-eval.md`](agent-eval.md)) checks them.

Each created item remembers its recording (`items.recordingId`), so a recording's detail
screen shows what it made, and "Transcribe again" can undo it first.

### 5. What happens next

- **Items and lists** live in Room (`data/ItemRepository.kt`, `ListRepository.kt`). A
  reminder is an item on the Todo list with a due time.
- **Reminders ring** through an exact alarm: `android/AndroidReminderScheduler.kt` →
  `ReminderReceiver.kt` → `ReminderNotifications.kt`. `BootReceiver.kt` sets them again
  after a restart or update.
- **Alarms and timers** go to the phone's Clock app (`android/AndroidClockApp.kt`), or to a
  notification to tap when Android won't let a background app open it.
- **Hand-offs:** `core/agent/ItemHandoff.kt` wraps the item store so new notes and items also
  go to Tasker ([`tasker.md`](tasker.md)) or into an Obsidian folder (`core/obsidian/`).

## The board's health

Every `INFO` reply and advert carries battery, storage and problem flags.
`core/board/DeviceStatus.kt` parses them, `core/board/BoardWarnings.kt` decides which
warnings to post (`android/BoardHealth.kt` posts them), and `data/BatteryHistory.kt` keeps a
battery reading per wake-up for the Device screen's trend.

## Backup and restore

`android/BackupExport.kt` writes one zip: `backup.json` (every row and setting, in the plain
format of `core/backup/BackupData.kt`) plus notes and lists as Markdown and recordings as
WAV. `android/BackupImport.kt` checks a zip in a staging folder (`core/backup/BackupCheck.kt`),
then replaces everything in one database transaction. Models, the board pairing and the
Obsidian folder belong to the phone and aren't included.

## Database

Room, in `data/PiatraDatabase.kt`. Tables: `recordings`, `items`, `lists`, `shortcut_rules`,
`battery_readings`. Each schema version is exported to `app/schemas/` and committed.
**Changing an entity means raising `version` and adding a migration** (usually an
`AutoMigration`), or existing installs can't open their database. Add the new fields to
`BackupData` too, with a default, so older backups still restore.

## The firmware

See [`firmware/README.md`](../firmware/README.md). In short: FreeRTOS tasks for the button
and recorder, a writer task that saves to flash, and the NimBLE GATT server with a sync
task. The board deep-sleeps whenever nothing keeps it awake and naps (light sleep) while
awake.

## Where to change what

| You want to… | Start here |
|---|---|
| Add or change a voice command | `core/agent/` (a tool, a rule, a correction), then `docs/agent-rules.md` and a case in `core/eval/EvalCases.kt` |
| Change a default shortcut phrase | `defaultShortcutRules` in `core/agent/ShortcutRules.kt` |
| Understand a new time phrase | `core/time/ReminderTime.kt`, with a test in `ReminderTimeTest.kt` |
| Send items somewhere new | A new `ItemHandoff` (like `android/TaskerHandoff.kt`), wired in `AppModule.kt` |
| Add a screen | A `Screen` + `ViewModel` in `ui/`, a route in `Routes.kt` / `PiatraNavHost.kt`, the ViewModel in `AppModule.kt` |
| Add a setting | `data/Settings.kt`, `ui/SettingsScreen.kt` / `SettingsViewModel.kt`, and the backup (`core/backup/`) |
| Change the board protocol | `docs/esp32-protocol.md` first, then `firmware/main/sync.c` and `core/board/BoardProtocol.kt` (with tests), and `firmware/tools/sync.py` |
| Change what the board does with the button | `firmware/main/recorder.c` |
| Use another model | `core/ModelInfo.kt` (the `Models` list) and `android/ModelManager.kt` |
| Use different hardware pins | `firmware/main/board.h` |
