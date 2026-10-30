# Third-party code, assets and models

Piatra is licensed under the GNU General Public License, version 3 or later (`LICENSE`).
Copyright © 2026 The Piatra authors.

Some of it comes from other projects, listed below with their licenses. License texts that
aren't already in the files themselves are in `licenses/`.

## Code adapted from other projects

| What | Where in Piatra | Source | License |
|---|---|---|---|
| Recording queue, agent tools (reminders, lists, notes, alarms, timers), time parsing, Obsidian writer, reminder scheduling and notifications, companion-device presence, the Cactus transcription and agent wrappers, Room entities | `app/src/main/kotlin/dev/piatra/` (each file says what it was adapted from) | Core Devices' Pebble/Index mobile app, [github.com/coredevices/mobileapp](https://github.com/coredevices/mobileapp) | GPL-3.0 (or Core Devices' commercial license) |
| Kotlin bindings for the Cactus engine | `cactus/src/main/kotlin/com/cactus/Cactus.kt` | The same app's `cactus` module | GPL-3.0 |

Copyright 2026 Core Devices LLC. Core Devices licenses its app under either the GPL-3.0 or a
commercial license; Piatra uses it under the GPL-3.0. The code was taken from commit
`90c7756647c7ee7ab2b15905286d709f9922b2c0` (20 Oct 2026) and then trimmed and changed. Piatra
is GPL-3.0 because it includes this code. Where a comment in Piatra's code says "upstream", it
means this app.

Pebble is a trademark of its owners. Piatra isn't affiliated with or endorsed by Core Devices
or the Pebble brand, and it doesn't use the name. "Pebble" appears only in the README (which credits the Pebble Index 01 as
the inspiration), in the name of a third-party model (`needle-pebble-ft`) and in comments that
point to upstream source files.

## Bundled binaries

| What | Where | Source | License |
|---|---|---|---|
| Cactus on-device inference engine (`libcactus_engine.so`, arm64-v8a, prebuilt) | `cactus/src/main/jniLibs/arm64-v8a/` | Cactus Compute, [github.com/cactus-compute/cactus](https://github.com/cactus-compute/cactus) (taken from the Core Devices app's build) | Cactus license: a modified MIT license, free for individuals (personal, educational, research or non-commercial use) and for small organisations; others need a commercial license from Cactus Compute. See the source repository's `LICENSE`. |

The Cactus license limits commercial use, which the GPL doesn't allow for code distributed
with GPL code. That's no problem for personal use. Check its terms before distributing a
build.

## Libraries (fetched at build time)

**Android app**, from Google Maven and Maven Central:

| Library | License |
|---|---|
| AndroidX (Activity, Compose UI, Material 3, Lifecycle, Navigation, Room, DataStore, DocumentFile, Test) | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime | Apache-2.0 |
| Koin (dependency injection) | Apache-2.0 |
| Kable (Bluetooth LE), JUUL Labs | Apache-2.0 |

**Firmware**, from the ESP-IDF component registry:

| Component | License |
|---|---|
| ESP-IDF v6.0.3, including the NimBLE Bluetooth stack (Espressif, Apache Mynewt) | Apache-2.0 |
| `78/esp-opus` (libopus, Xiph.Org) | BSD-3-Clause |
| `joltwallet/littlefs` (LittleFS) | MIT (wrapper), BSD-3-Clause (LittleFS) |

**Laptop tools** (`firmware/tools/`, installed with pip): `bleak` and `dbus-fast` (MIT) and
`pyserial` (BSD-3-Clause). `opus_test.py` also uses `numpy` (BSD-3-Clause), `onnx-asr` (MIT)
and the system's libopus (BSD-3-Clause).

## Fonts and icons

| What | Where | License |
|---|---|---|
| Atkinson Hyperlegible, Courier Prime, DM Mono, Fira Mono, IBM Plex Mono, IBM Plex Sans, Inter, JetBrains Mono, Lora, Space Mono | `app/src/main/res/font/` | SIL Open Font License 1.1: `licenses/OFL-*.txt` |
| Phosphor Icons (regular weight, converted to Android vector drawables by `tools/phosphor.py`) | `app/src/main/res/drawable/` | MIT: `licenses/MIT-Phosphor.txt` |

## Models (downloaded by the app, not in this repository)

The app downloads these once from Hugging Face. They aren't part of Piatra and come with their
own licenses.

| Model | Used for | License |
|---|---|---|
| [`Cactus-Compute/parakeet-tdt-0.6b-v2`](https://huggingface.co/Cactus-Compute/parakeet-tdt-0.6b-v2) and [`-v3`](https://huggingface.co/Cactus-Compute/parakeet-tdt-0.6b-v3), converted from NVIDIA's Parakeet TDT 0.6B | Speech to text (English / multilingual) | CC-BY-4.0 |
| [`Cactus-Compute/needle-pebble-ft`](https://huggingface.co/Cactus-Compute/needle-pebble-ft), tag `v2.0.1` | Picks the tool for each voice note | No license is stated on its page. It's a fine-tune of [`Cactus-Compute/needle`](https://huggingface.co/Cactus-Compute/needle), which is MIT. |
