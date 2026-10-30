# Piatra board firmware

ESP-IDF firmware (C) for the Seeed XIAO ESP32S3 Sense. Hold the button to record. Notes are
compressed with Opus and kept in the board's flash (about 40 minutes in total) until the
phone, or a laptop with `tools/sync.py`, has downloaded them over Bluetooth. Between uses the
board is in deep sleep.

The Bluetooth protocol is in [`../docs/esp32-protocol.md`](../docs/esp32-protocol.md).
Wiring and parts are in the [main README](../README.md#hardware).

## Using the board

| You do | The board does |
|---|---|
| **Hold** the button (1 s or longer) | Records, up to 5 minutes. A short buzz (and the LED, if no card is fitted) says it's listening, ~125 ms after a press that woke it from sleep. Let go to save. |
| **Tap** it (a press under 1 s) | Nothing is recorded. Bluetooth wakes for 30 s and the board asks the phone to check in, so the app's battery and storage readings update. |
| **Tap exactly 5 times** | Pairing mode for 60 s: long buzz, LED blinks slowly. The first phone to pair replaces the old one (short buzz). A board with no bonds is always open to pairing. |
| **Tap 8 times, then hold 2 s** | Dev mode for a laptop (below). Long buzz; nothing is recorded. |

Taps count once they stop, about 1 s after the last one.

The board advertises only when it has a reason: notes waiting (for 2 min after each one),
pairing mode, a tap, dev mode, or a short check-in every 30 minutes. Otherwise it sleeps, and
the phone can't reach it. While recording it doesn't advertise at all, because each +20 dBm
transmission clicks in the microphone. Awake, it naps between radio events (light sleep).
Battery use is about 0.2 mA on average: a 200 mAh cell lasts about three weeks of daily use.

## Set-up (once)

- **ESP-IDF v6.0.3**, for example in `~/esp/esp-idf-v6.0.3`:

  ```sh
  git clone -b v6.0.3 --recursive https://github.com/espressif/esp-idf.git ~/esp/esp-idf-v6.0.3
  cd ~/esp/esp-idf-v6.0.3 && ./install.sh esp32s3
  ```

  If your system has no `cmake`, also run `python tools/idf_tools.py install cmake` there.
- **Serial port access** (Linux, for the first flash): `sudo usermod -aG dialout $USER`, then
  log out and in.
- **Laptop tools** (Python 3, from `firmware/`):
  `python3 -m venv tools/.venv && tools/.venv/bin/pip install pyserial bleak`. The Bluetooth
  tools use BlueZ, so they run on Linux.
- **The dev-mode code:** put 6 digits of your choice in `firmware/dev.secret`. It's
  git-ignored, and the build stops without it. A laptop types it to pair in dev mode.

## Build and flash

```sh
. ~/esp/esp-idf-v6.0.3/export.sh   # once per terminal: puts idf.py on PATH
cd firmware
idf.py build                       # first build ~2 min (downloads libopus and LittleFS), then seconds
idf.py flash monitor               # upload over USB, then show the log (Ctrl+] quits)
```

If flashing can't connect: hold BOOT, tap RESET, release BOOT (download mode), flash, then
tap RESET to run.

The firmware version the board reports is `FIRMWARE_VERSION` in `main/sync.c`.

## Updating over Bluetooth

After the first USB flash, everything can be done over Bluetooth, so the board can live in
a closed case. USB is only needed again to change the flash layout (`partitions.csv`) or to
rescue an interrupted bootloader update.

1. On the board: **8 taps, then hold 2 s** (long buzz) opens dev mode.
2. Within 2 minutes: `tools/.venv/bin/python tools/dev.py pair` pairs with the code in
   `dev.secret`. This doesn't touch the phone's pairing.
3. `tools/.venv/bin/python tools/dev.py flash` builds, uploads (~30 s), restarts the board,
   reconnects, confirms the new firmware, then puts the board to sleep for 5 s and checks it
   comes back.

Turn the phone's Bluetooth off while flashing: the board takes one connection at a time, and
the phone would grab it. The laptop stays paired: later, any `dev.py` command opens dev mode
again whenever the board is awake (tap it once, or run `dev.py wait` for its next check-in).

**Safety net.** The board has two program slots. A new firmware is **on trial** until
`dev.py flash` reconnects and confirms it. If it crashes first, or 5 minutes pass, the
bootloader goes back to the previous one. A confirmed firmware must also come back after a
deep sleep: if the laptop can't reconnect within 3 minutes of that wake, the board switches
back to the other slot. So a firmware that can't take the next update never sticks.

**The bootloader** (`dev.py bootloader`, after flashing the same build) is the one risky
update. The board checks the new bootloader in spare flash, then copies it over the old one
in under a second. If the power goes during that copy, the board can't start until it's
flashed over USB.

### `dev.py` commands

All run as `tools/.venv/bin/python tools/dev.py <command>`. Only `flash` and `bootloader`
build, so only they need the `export.sh` terminal (`--no-build` uploads the last build).

| Command | What it does |
|---|---|
| `pair` | Forget any old bond with the board, then pair in dev mode |
| `wait` | Wait for the board to advertise (its check-in), then connect |
| `info` | Running version, slot, on trial or confirmed |
| `flash` | Build, upload, restart, confirm, prove it survives a deep sleep |
| `rollback` | Go back to the other slot's firmware |
| `bootloader` | Replace the bootloader (see above) |
| `boot` | Which bootloader the board has |
| `done` | End dev mode now (the laptop stays paired) |
| `monitor` | The board's log: the last 4 KB, then live (Ctrl+C) |
| `log` | Only the last 4 KB, kept through restarts and crashes (not a power cut) |
| `pm` | Time in each power mode since start-up, and what kept the board awake |
| `ls`, `df`, `bat`, `adv` | Recordings, storage, battery millivolts, why it is or isn't advertising |
| `get ID [--play]`, `last` | Copy a recording to `tools/recordings/` as `.opus` and `.wav` (needs ffmpeg) |
| `rm ID`, `buzz [MS]`, `crash` | Delete a recording, buzz the motor, test a crash reset |
| `sleep [S]` | Deep sleep until the button, or S seconds; then shows how long the wake-up took |

Dev mode ends by itself 5 minutes after the laptop disconnects. While the laptop is
connected, the phone can't sync.

**If the laptop can't connect** ("Page Timeout", `le-connection-abort-by-local`): close
GNOME Settings' Bluetooth panel. While it's open it keeps scanning, and BlueZ then can't
connect. `dev.py` warns about it.

## Other laptop tools

**`tools/sync.py`** syncs like the phone does, which is handy for testing the protocol. It
pairs as an ordinary "Just Works" device, so it **replaces the phone's pairing** (pair the
phone again afterwards with 5 taps).

```sh
tools/.venv/bin/python tools/sync.py scan          # signal strength + advertised status (Ctrl+C)
tools/.venv/bin/python tools/sync.py pair          # once, in pairing mode (5 taps)
tools/.venv/bin/python tools/sync.py info          # status report
tools/.venv/bin/python tools/sync.py ls
tools/.venv/bin/python tools/sync.py get 3 --play
tools/.venv/bin/python tools/sync.py sync          # INFO, SET_TIME, LIST, then GET + DELETE each
tools/.venv/bin/python tools/sync.py sync --keep   # the same without deleting
```

`pair` brings its own BlueZ agent, because "Just Works" pairing still needs someone to say
yes and the desktop's agent may not. If the laptop forgot the board but the board still
knows the laptop, the board refuses until you do 5 taps. To start over on the laptop:
`bluetoothctl remove <address>`.

**`tools/pull.py`** does the same over USB (close `idf.py monitor` first): `ls`,
`last --play`, `rm ID`, and the console commands. It prints the peak and RMS level of each
recording: use that to tune `GAIN` in `main/mic.c`.

**`tools/range.py [S]`** shows the board's signal strength at the laptop every 2 s, for range
tests (phone's Bluetooth off). **`tools/opus_test.py`** and **`tools/opus_frame.py`** compare
Opus settings on a raw recording and write the board's Opus file layout.

## microSD card (optional)

Off by default: set `USE_SD_CARD 1` in `main/board.h` to use the Sense board's slot. FAT32,
up to 32 GB. Recordings go to a `piatra/` folder on it; ones made in flash before the card
went in move there at the next start-up. The card is only looked for at start-up and is
never formatted by the firmware. Looking for it costs ~40 ms of every wake-up. The card
shares its select pin with the LED, so the LED stays off while a card is in. Without a card,
the board records to flash and reports `SD_ERROR`.

## Pins

| What | GPIO |
|---|---|
| Record button (to GND, low = pressed) | 2 (D1) |
| Vibration motor (BC337 driver, high = on) | 4 (D3) |
| Battery divider, 2 × 470 kΩ + 100 nF (ADC) | 1 (D0) |
| BOOT button (strapping pin; not used) | 0 |
| User LED (orange, low = on); also the microSD card's CS | 21 |
| microSD SPI: SCK / MISO / MOSI (Sense board, pads J3 joined) | 7 (D8) / 8 (D9) / 9 (D10) |
| PDM mic clock / data (Sense board) | 42 / 41 |

All pins and fixed numbers are in `main/board.h`.

## Files

- `main/main.c`: start-up; starts the tasks.
- `main/button.c`: debounced button events (also wakes the board from a nap).
- `main/recorder.c`: hold to record, taps, the pairing and dev gestures; encodes each 20 ms block.
- `main/mic.c`: the PDM mic, DC filter, gain with a soft limiter, levels.
- `main/encoder.c`: Opus (libopus, 16 kbps, from the `78/esp-opus` component), or raw PCM with
  `RECORD_OPUS 0` in `board.h`.
- `main/writer.c`: saves the encoded audio in its own task, behind a ~3 s queue, so flash
  writes never stall the mic.
- `main/store.c`: recordings in flash (LittleFS, `/flash/<id>.opus` + `.meta`) or on the
  microSD card (FAT, `/sd/piatra/…`); the id counter in NVS; recovery after a power cut.
- `main/ble.c`: NimBLE: the GATT service, the two adverts (ordinary 1M and long-range Coded
  PHY, both with the status), notifications with flow control, bonding and pairing mode,
  quiet mode while recording.
- `main/sync.c`: the protocol's commands (INFO, SET_TIME, LIST, GET, DELETE), in their own
  task; when to advertise.
- `main/dev.c`: dev mode, firmware and bootloader updates over Bluetooth, trial and proof.
- `main/sleep.c`: deep sleep (when nothing keeps the board awake) and light sleep (naps).
- `main/console.c`: text commands, over USB (`tools/pull.py`) or Bluetooth in dev mode.
- `main/logbuf.c`: keeps every log line in a 4 KB ring buffer in RTC memory and sends it to
  the laptop.
- `main/clock.c`: the clock the phone sets, uptime, and whether power was lost.
- `main/haptic.c`, `main/battery.c`, `main/led.c`: the motor, the battery voltage, the LED.
- `partitions.csv`: the flash layout (two 1.5 MB program slots, ~4.9 MB for recordings).
- `sdkconfig.defaults`: build settings (Bluetooth, power management, transmit power).
