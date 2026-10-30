# Piatra

A pocket voice-note button and the Android app that goes with it. Press the button, speak,
and let go. The note is stored on the button and synced to your phone over Bluetooth when
the phone is near. The phone transcribes it and an on-device agent turns it into a reminder,
a list item, a note, an alarm or a timer.

Everything runs on the phone. There's no cloud service, no account and no sign-in. The app
needs the network once, to download its two models from Hugging Face, and never again.

## What it does

- **Record anywhere.** The board records from its microphone while you hold the button (or
  until you press again), compresses the note with Opus and keeps it in its flash: about 40
  minutes in total, up to 5 minutes per note. It buzzes when it starts listening.
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

## Getting started

[`docs/building.md`](docs/building.md) explains how to build and install the app, make a
signed release, and build and flash the firmware.

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
