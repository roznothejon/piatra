# Changelog

## 1.0 (app) / 1.0.0 (firmware)

First release.

- **Board:** press the button to record and release to stop. Notes are stored as Opus in the
  board's flash (about 40 minutes in total, up to 5 minutes per note) and synced to the phone
  over encrypted Bluetooth LE, with Coded PHY for longer range. The board deep-sleeps between
  uses, checks in every 30 minutes, and can be updated over Bluetooth from a laptop.
- **Phone:** automatic background sync, on-device transcription (Parakeet, English or
  multilingual), and an on-device agent that turns voice notes into reminders, list items,
  notes, alarms and timers. It also has editable shortcut phrases, an Agent lab test bench,
  Tasker and Obsidian hand-offs, board battery and storage warnings, battery history, and
  backup and restore to a single zip.
