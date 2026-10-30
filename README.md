# Piatra

A pocket voice-note button and the Android app that goes with it. Hold the button, speak,
and let go. The note is stored on the button and synced to your phone over Bluetooth when
the phone is near. The phone transcribes it and an on-device agent turns it into a reminder,
a list item, a note, an alarm or a timer.

Everything runs on the phone. There's no cloud service, no account and no sign-in. The app
needs the network once, to download its two models from Hugging Face, and never again.

The idea comes straight from the [Pebble Index 01](https://repebble.com/), Core Devices'
voice-note ring: press, speak, and the phone turns it into a reminder or a note. Piatra is a
do-it-yourself take on the same idea, built around an ESP32 board, with its own Bluetooth
protocol and firmware. Its app reuses parts of Core Devices' open-source mobile app (see
[License](#license)). It isn't affiliated with Core Devices or Pebble.

## What it does

- **Record anywhere.** The board records from its microphone while you hold the button,
  compresses the note with Opus and keeps it in its flash: about 40 minutes in total, up to
  5 minutes per note. It buzzes when it starts listening. A press shorter than a second is a
  tap, not a recording: one tap makes the phone check in, five taps pair a new phone.
- **Sync by itself.** When the board has notes it advertises over Bluetooth LE. The app
  notices in the background, connects (encrypted, bonded to one phone), downloads each note
  and tells the board to delete it. Long-range Bluetooth (Coded PHY) is used where the phone
  supports it.
- **Transcribe on the phone** with Parakeet (English, or 25 European languages).
- **Act on it.** A small on-device tool-calling model, helped by editable shortcut phrases,
  turns "remind me to call the dentist at 5" into a real reminder with an exact alarm.
  It also handles lists ("add milk to shopping"), notes, alarms and timers (through the
  phone's Clock app).
- **Hand off** notes and reminders to [Tasker](https://tasker.joaoapps.com/), or write notes
  as Markdown into an Obsidian vault folder.
- **Keep track of the board:** battery and storage warnings, a battery history, and firmware
  updates over Bluetooth from a laptop.
- **Back up and restore** everything to a single zip, with notes and lists as Markdown and
  recordings as audio files.

The phone's own microphone works as a recorder too, so you can try the app without a board.

## Hardware

- [Seeed Studio XIAO ESP32S3 Sense](https://wiki.seeedstudio.com/xiao_esp32s3_getting_started/)
  (ESP32-S3 with Bluetooth 5, a PDM microphone and an optional microSD slot)
- A push button between D1 and GND
- A small vibration motor on D3, switched by an NPN transistor (BC337, 470 Ω base resistor)
- A LiPo cell on the BAT pads (200 mAh lasts about three weeks of daily use), with a voltage
  divider (2 × 470 kΩ, 100 nF) from the battery to D0 so the board can report its charge

The firmware is written with ESP-IDF in C (`firmware/`).

## The phone

An Android phone with a 64-bit ARM CPU (arm64-v8a), Android 13 or newer for pairing with the
board, and about 500 MB free for the models. The speech model runs comfortably on a
recent mid-range phone.

## Documentation

| Guide | What's in it |
|---|---|
| [`docs/building.md`](docs/building.md) | Building and installing the app, tests, signed releases, building the firmware |
| [`firmware/README.md`](firmware/README.md) | Using the board, flashing, updates over Bluetooth, the laptop tools, pins |
| [`docs/architecture.md`](docs/architecture.md) | How the pieces fit together, and where to change what |
| [`docs/agent-rules.md`](docs/agent-rules.md) | Every rule between "you spoke" and "something happened" |
| [`docs/agent-eval.md`](docs/agent-eval.md) | The Agent lab's test set and what it showed about the model |
| [`docs/esp32-protocol.md`](docs/esp32-protocol.md) | The Bluetooth protocol between board and phone |
| [`docs/tasker.md`](docs/tasker.md) | Sending notes and reminders on to Tasker |
| [`CLAUDE.md`](CLAUDE.md) | Rules for contributors (people and coding agents) |

## Repository layout

| Path | What's there |
|---|---|
| `app/` | The Android app (Kotlin, Jetpack Compose, Room). Under `src/main/kotlin/dev/piatra/`: `core/` is plain Kotlin with no Android APIs (agent, tools, board protocol, audio, time parsing, backup), `android/` holds the platform pieces (Bluetooth, notifications, alarms, files), `data/` the Room database and `ui/` the screens. |
| `cactus/` | The Cactus on-device inference engine (prebuilt) and its Kotlin bindings. Runs both models. |
| `firmware/` | The board's firmware (ESP-IDF) and Python tools for a laptop in `firmware/tools/`. |
| `tools/` | Build helpers: `release.sh` makes a signed release APK. |
| `docs/` | Guides. |

## License

Piatra is free software under the GNU General Public License, version 3 or later
([`LICENSE`](LICENSE)). Parts of the app are adapted from Core Devices'
[open-source mobile app](https://github.com/coredevices/mobileapp), and it bundles the Cactus
engine, which has its own license that limits commercial use. See
[`THIRD_PARTY.md`](THIRD_PARTY.md) for everything that comes from elsewhere.

Changes per release are in [`CHANGELOG.md`](CHANGELOG.md).
