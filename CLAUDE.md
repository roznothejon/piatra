# Piatra: notes for contributors

A pocket voice-note button (an ESP32-S3 board) and its Android app. The board records, the
phone syncs over Bluetooth, transcribes on-device and an on-device agent turns each note into
reminders, list items, notes, alarms or timers. Start with [`README.md`](README.md), then
[`docs/architecture.md`](docs/architecture.md). This file holds the rules for changing the
code. It's written for people and coding agents alike.

## Principles

- **Fully local.** No cloud services, no accounts, no analytics, no Firebase. The only
  network use is downloading the models once from Hugging Face (`android/ModelManager.kt`).
  Don't add network calls without making them opt-in and documenting them.
- **Small and understandable.** Build only what the app needs. Prefer the simplest version
  that works, and add structure only when something needs it.
- **Readable over clever.** Comments may explain *concepts* (what a foreground service is, why
  Room needs a migration), because readers may be new to Android or embedded work. Keep them
  short, and say *why*, not what the next line does.
- **Android only, but portable logic.** The app is plain Kotlin with Jetpack Compose. Logic
  goes in `core/` with no Android APIs, behind small interfaces; platform code goes in its
  own file in `android/`. That keeps `core/` unit-testable without a phone and leaves an iOS
  port possible (mostly by moving files to Kotlin Multiplatform). Don't let this make code
  harder to read: it's insurance, not a requirement.

## Layout

| Path | What |
|---|---|
| `app/src/main/kotlin/dev/piatra/` | `core/` (plain Kotlin), `android/` (platform), `data/` (Room, settings), `ui/` (Compose); `AppModule.kt` wires it all with Koin |
| `app/src/test/` | Unit tests for `core/` and `data/` logic (fakes in `Fakes.kt`) |
| `app/src/androidTest/` | Tests that need a phone (MediaCodec Opus) |
| `app/src/debug/` | Debug-only code: the Agent lab's adb hook |
| `app/schemas/` | Room schema per database version (committed) |
| `cactus/` | The Cactus inference engine (prebuilt `.so`) and its Kotlin bindings |
| `firmware/` | ESP-IDF firmware (`main/`) and laptop tools (`tools/`) |
| `docs/` | Guides: building, architecture, protocol, agent rules and evaluation, Tasker |
| `tools/` | `release.sh` (signed release APK) |

## Commands

```sh
./gradlew testDebugUnitTest            # unit tests, no phone needed: run before every commit
./gradlew assembleDebug                # build
./gradlew installDebug                 # build and install over adb
```

Full set-up (JDK 17, SDK, NDK), release builds and firmware builds are in
[`docs/building.md`](docs/building.md) and [`firmware/README.md`](firmware/README.md).

**Never run `./gradlew connectedDebugAndroidTest`.** It uninstalls the app afterwards, which
deletes its recordings, models and board pairing. Run instrumented tests with
`am instrument` (see `docs/building.md`).

## Keep these in sync

- **Agent behaviour:** every routing rule, correction, text rule and time rule is listed in
  [`docs/agent-rules.md`](docs/agent-rules.md). When one changes in code, change the doc in
  the same commit, and add or update a case in `core/eval/EvalCases.kt`. Re-run the test set
  in the Agent lab ([`docs/agent-eval.md`](docs/agent-eval.md)) when you have a phone.
- **Board protocol:** [`docs/esp32-protocol.md`](docs/esp32-protocol.md) is the contract
  between `firmware/main/` and `core/board/`. Change the doc first, then both sides and
  `firmware/tools/sync.py`. An incompatible change bumps the protocol version on both sides.
- **Database:** changing a Room entity means raising `version` in `data/PiatraDatabase.kt`,
  adding a migration, and committing the new schema file in `app/schemas/`. New fields also
  go into the backup format (`core/backup/BackupData.kt`) with a default, so old backups
  still restore.
- **Settings:** a new setting goes into the backup too.
- **User-visible changes** go into `CHANGELOG.md`.

## Code that came from elsewhere

Parts of the app are adapted from Core Devices' open-source mobile app
([github.com/coredevices/mobileapp](https://github.com/coredevices/mobileapp), GPL-3.0). Comments
saying "upstream" mean that app, and each adapted file says what it came from. Piatra is
GPL-3.0-or-later because of it. If you bring in more code, note it in the file and in
`THIRD_PARTY.md`. Don't name anything in Piatra "Pebble" (a trademark); the board's protocol
is Piatra's own, not the Index 01 ring's.

The Cactus engine (`cactus/src/main/jniLibs/`) has its own license that limits commercial use;
see `THIRD_PARTY.md`.

## Firmware notes

- ESP-IDF v6.0.3, target `esp32s3`. All pins and fixed numbers are in `firmware/main/board.h`.
- After the first USB flash, the firmware updates over Bluetooth (`firmware/tools/dev.py
  flash`), with a trial period and automatic rollback. Assume the board may be sealed in a
  case without USB: never land a firmware change that can't take the next update over
  Bluetooth.
- `firmware/dev.secret` (the dev-mode pairing code) is git-ignored and must stay so.
- The board must stay frugal: it deep-sleeps whenever nothing keeps it awake. Anything that
  keeps it awake longer costs battery days; measure with `dev.py pm`.

## Secrets and personal data

Never commit `local.properties`, keystores, `keystore.properties`, `firmware/dev.secret`, or
recordings. Keep personal details (names, addresses, phone numbers, real voice notes) out of
code, tests and docs: test phrases are made up.

## Commits

Small, focused commits with plain, descriptive messages, on `main`. Run the unit tests first.
