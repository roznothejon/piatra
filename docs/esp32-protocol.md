# Piatra board ↔ phone protocol (BLE)

> Version **1**. Bump the version byte (`PROTOCOL_VERSION` in `firmware/main/sync.c`, `VERSION` in `core/board/BoardProtocol.kt`) for every incompatible change. Additions that old peers can ignore (a new audio format, a new flag bit) need no new version.

The board (Seeed XIAO ESP32S3 Sense) records voice notes into its flash (or onto a microSD card, if the firmware is built to use one). When the phone is in range, the phone downloads each pending recording, saves it, and then tells the board to delete it.

The firmware side is in `firmware/main/` (`ble.c` for advertising, pairing and the GATT server, `sync.c` for the commands), the app side in `app/src/main/kotlin/dev/piatra/core/board/` (parsing and the sync, plain Kotlin and unit-tested) and `android/KableBoardLink.kt` (the Bluetooth connection). `firmware/tools/sync.py` speaks the same protocol from a laptop.

Words used here:
- **Board:** the BLE *peripheral* (it advertises, the phone connects to it).
- **Phone:** the BLE *central* (it scans and connects).
- **Recording:** one finished voice note stored on the board.

All numbers are **little-endian** (the native order of both the ESP32 and ARM phones). `u8`/`u16`/`u32` are unsigned integers of 1/2/4 bytes.

## 1. Rules that the design guarantees

1. **A recording is never lost.** The board deletes a recording only when the phone sends `DELETE`, and the phone sends `DELETE` only after the audio is saved in the app's database and queued for processing. At worst, a recording arrives twice (e.g. the app crashes between saving and `DELETE`).
2. **Transfers resume.** If the link drops halfway through a recording, the phone keeps the bytes it has and asks for the rest with `GET(id, offset)`.
3. **Every recording knows when it was recorded**, so "remind me in 2 hours" counts from when you spoke, not from when it synced (see section 6).
4. **Corruption is caught.** Each recording ends with a CRC-32 of the whole file; the phone discards the file on a mismatch and downloads it again.

## 2. GATT layout

One primary service with three characteristics (the third, `dev`, only for development). The UUIDs were generated once for Piatra; only the first block differs.

| Name | UUID | Properties | Used for |
|---|---|---|---|
| Piatra service | `cdf70001-b272-4e8a-b578-c59339280171` | | |
| `control` | `cdf70002-b272-4e8a-b578-c59339280171` | write, notify | phone → board commands; board → phone replies |
| `data` | `cdf70003-b272-4e8a-b578-c59339280171` | notify | board → phone audio chunks |
| `dev` | `cdf70004-b272-4e8a-b578-c59339280171` | write, write without response, notify | laptop only, in dev mode: firmware updates (section 12). The app never uses it. |

`control` and `data` require an **encrypted link** (see section 7). The phone must enable notifications on both (by writing the CCCD descriptor) before sending commands.

**MTU:** the phone requests an MTU of 517 right after connecting. A notification can carry up to `MTU − 3` bytes, but **never more than 512**: that's the most an attribute value may hold (Bluetooth Core spec), and Android silently discards longer notifications (`GATT_MAX_ATTR_LEN`; BlueZ on Linux doesn't check, so a laptop test won't catch it). The board must work with any MTU the phone ends up with, down to the default of 23.

**Speed hints (phone side, optional):** request the 2M PHY and `CONNECTION_PRIORITY_HIGH` during a sync; set it back to balanced afterwards.

## 3. Commands (phone writes to `control`)

Every command is `[opcode u8][payload]`. The phone sends one command at a time and waits for its reply before sending the next.

| Opcode | Command | Payload | Reply |
|---|---|---|---|
| `0x01` | `GET_INFO` | none | `INFO` |
| `0x02` | `SET_TIME` | `unixSeconds u32` | `OK` |
| `0x03` | `LIST` | none | zero or more `ENTRY`, then `LIST_END` |
| `0x04` | `GET` | `id u32`, `offset u32` | `GET_START`, chunks on `data`, then `GET_END` |
| `0x05` | `DELETE` | `id u32` | `OK` |

## 4. Replies (board notifies on `control`)

Every reply is `[opcode u8][status u8][payload]`. The reply opcode is the command's opcode with the top bit set (`0x80 | command`), except for the extra reply types listed below. If `status` isn't `OK`, there is no payload.

**Status codes:**

| Code | Name | Meaning |
|---|---|---|
| `0x00` | `OK` | |
| `0x01` | `UNKNOWN_COMMAND` | opcode not known (e.g. newer app, older firmware) |
| `0x02` | `BAD_LENGTH` | payload too short or too long |
| `0x03` | `NOT_FOUND` | no recording with that id |
| `0x04` | `BAD_OFFSET` | `offset` is past the end of the recording |
| `0x05` | `BUSY` | the board is recording right now; try again in a moment |
| `0x06` | `STORAGE_ERROR` | the storage can't be read or written |

**Reply types:**

| Opcode | Reply | Payload |
|---|---|---|
| `0x81` | `INFO` | see section 10 |
| `0x82` | `SET_TIME` result | none |
| `0x83` | `ENTRY` | `id u32`, `sizeBytes u32`, `recordedAt u32` (Unix seconds, 0 = unknown), `format u8` |
| `0x93` | `LIST_END` | `count u16` (number of `ENTRY` replies sent) |
| `0x84` | `GET_START` | `id u32`, `offset u32`, `sizeBytes u32` |
| `0x94` | `GET_END` | `id u32`, `crc32 u32` (of the **whole** file, from byte 0) |
| `0x85` | `DELETE` result | none |

`INFO` is the board's status report (battery, storage, problems); its layout is in section 10.

`ENTRY`, `LIST_END`, `GET_START` and `GET_END` always carry `status = OK`; an error during `LIST` or `GET` is sent as the command's normal reply (`0x83`/`0x84`) with an error status.

**Recording ids** are a counter that the board keeps in NVS (its small key-value store). Each new recording gets the next number, and numbers are never reused, even after a delete. `LIST` sends recordings oldest first.

## 5. Transfer (`GET`)

1. Phone writes `GET(id, offset)`. `offset` is 0 for a new download, or the number of bytes already received when resuming.
2. Board notifies `GET_START(id, offset, sizeBytes)` on `control`.
3. Board notifies chunks on `data` as fast as the BLE stack allows, until the end of the file. Each chunk is `[offset u32][bytes…]`: the position of these bytes in the file, then up to `min(MTU − 3, 512) − 4` bytes of audio (508 at the full MTU).
4. Board notifies `GET_END(id, crc32)` on `control`.

All notifications travel over the same link in order, so `GET_END` always arrives after the last chunk.

**Phone side:** write each chunk at its offset in a temporary file. If a chunk's offset isn't the one expected (a gap), stop trusting the stream: wait for `GET_END`, then send `GET(id, expectedOffset)`. When `GET_END` arrives with every byte present, compute the CRC-32 of the file: on a match, save it as a recording; on a mismatch, delete the temporary file and `GET(id, 0)` again (give up on that recording after 3 tries, keep it on the board, and show an error).

**CRC-32** is the common one (IEEE 802.3, the same as zip and `java.util.zip.CRC32`): polynomial `0xEDB88320` (reflected), initial value `0xFFFFFFFF`, final XOR `0xFFFFFFFF`. The firmware computes it while recording and stores it next to the file, so `GET_END` doesn't have to re-read the file.

## 6. Audio and time

**Audio format** (`ENTRY.format`):

| Value | Format |
|---|---|
| `0x01` | Raw PCM, 16 kHz, 16-bit signed little-endian, mono. Exactly the app's pipeline input; no header in the file. |
| `0x02` | Opus: 16 kHz mono, 20 ms frames, 16 kbps, voip mode (`firmware/tools/opus_test.py` showed transcripts stay the same at a 16th of the size). No file header: the file is one packet per frame, each as `[length u16][packet]`, length 1–1275. The app stores it as Ogg Opus and decodes it to format `0x01` for transcription. `firmware/tools/opus_frame.py` writes this layout from a raw file. This is what the board records (libopus, complexity 0; the last frame padded with silence). |

Other values are reserved. The phone skips entries with a format it doesn't know and leaves them on the board, so adding a format needs no version change. A recording the phone can't decode (a damaged packet) also stays on the board, counted as failed.

The duration of raw PCM follows from the size: `sizeBytes / 32000` seconds; for Opus it's the number of packets × 20 ms. The firmware removes the PDM mic's DC offset (a constant shift in the samples) and applies gain before encoding (`firmware/main/mic.c`).

**Recording time.** The board has no clock battery, so after power-up it doesn't know the time. The rules:
1. On every connection the phone sends `SET_TIME` (after `GET_INFO`, before `LIST`). The board keeps counting from there; the ESP32's RTC keeps running through deep sleep.
2. When a recording **starts**, the board stamps it with the current Unix time if its clock is set. If not, it stores `recordedAt = 0` plus its uptime (milliseconds since power-up).
3. When `SET_TIME` arrives, the board fixes up recordings from **this power-up** that have `recordedAt = 0`: `recordedAt = newTime − (uptimeNow − uptimeAtRecording) / 1000`.
4. Only a recording made with the clock unset *and* followed by another power loss before any sync stays at `recordedAt = 0`. The phone then uses the time it received the recording, and the app may mark it "time unknown".

## 7. Security: pair once

- The board uses **LE Secure Connections with "Just Works" bonding** (it has no screen or keypad for a PIN). After pairing once, the link is encrypted and only the bonded phone can read the characteristics.
- **Pairing mode:** the board accepts a new bond only in pairing mode, i.e. when it has no bonds yet, or for 60 s after **exactly 5 taps** (taps count once they stop, ~1 s after the last; then a long buzz, then the LED blinks slowly). The first device to bond in that window replaces all old bonds (short buzz); if none does, the old bonds stay, so an accidental gesture costs nothing. Outside pairing mode, a pairing attempt is refused and the connection dropped; an unpaired phone that doesn't try to pair can't use the characteristics and is dropped by the 30 s idle timeout. (Holding the button would clash with hold-to-record, so the gesture is taps.)
- **Which side asks:** the board doesn't send a security request. The phone pairs (first time) or encrypts with its saved key when the board answers "insufficient authentication/encryption" to its first write; Android and BlueZ both do this by themselves.
- **One phone bond at a time:** a new Just Works bond replaces the old one, so a phone and a laptop using `firmware/tools/sync.py` take turns (5 taps each time you switch). A laptop in dev mode pairs with a code instead and doesn't replace the phone (section 12).
- Weakness: "Just Works" can't stop someone who intercepts the pairing moment itself. That's acceptable for a personal device.

## 8. Advertising

- **Name:** `Piatra`. The advertisement includes the service UUID, so the phone can filter its scan by it.
- **Device type:** Appearance `0x0240` (Keyring), in the advertisement and in the GAP service, so scanners show a device type. The phone doesn't rely on it.
- **Two adverts**, both connectable, from the same address: the ordinary one on the 1M PHY (the two packets below), and a **long-range** one on the Coded PHY (an extended advert, Bluetooth 5: 125 kbps, each bit sent 8 times, so a ~4× weaker signal still decodes). Extended adverts can't have a scan response, so the long-range one carries flags, service UUID, appearance, name and status in one packet (47 bytes), every 300–350 ms. A phone that supports the Coded PHY scans for both and connects over Coded; others (and the laptop) use the ordinary advert. The status is the same in both. Once connected, the board stops both adverts.
- **Packets (ordinary advert):** an advertisement holds only 31 bytes, and a 128-bit UUID takes 18 of them. So the advertisement carries the flags, the service UUID and the appearance (25 bytes), and the **scan response** (a second 31-byte packet the phone asks for while scanning; Android does this by default) carries the name and the status below.
- **Status** (manufacturer data, company ID `0xFFFF`, which Bluetooth reserves for testing and private use): `[protocolVersion u8][flags u32][pendingCount u16][batteryMillivolts u16][storageFreePercent u8]`, 10 bytes. This is a short form of `INFO` (section 10): the fields mean the same, and `storageFreePercent` is worked out from the two storage fields. With it, the phone can tell whether connecting is worth it, and can update the battery level without connecting.
- **When** (every ~200 ms; the board is in deep sleep the rest of the time, and only advertises while awake):
  - in pairing mode;
  - for **2 min** after a recording is saved, and after start-up, while it has pending recordings;
  - for 30 s after a tap on the button (a press under 1 s), so the phone can connect when nothing is waiting;
  - at each **30-min check-in** (the board wakes from deep sleep for it): ~6 s (a little longer than one cycle of Android's low-power scan, which listens ~0.5 s every ~5 s), or ~15 s while recordings are pending, so the app's battery and storage readings stay fresh and old recordings get another chance;
  - in dev mode (section 12);
  - while awake for any other reason, if recordings are pending.

  Otherwise it doesn't advertise (and sleeps), to save battery. A phone connection keeps it awake.

  **Never while recording:** each transmission at +20 dBm puts a click in the mic's audio, so the board stops advertising when a recording starts and picks up again when it ends. A phone already connected is asked for **peripheral latency 29** (the board may skip up to 29 connection events; it still sends at once when it has something), keeping the phone's interval and a supervision timeout of at least 5 s; latency goes back to 0 when the recording ends. The phone may refuse or override it; nothing else in the protocol depends on it.

## 9. A sync, start to finish (phone side)

1. Scan for the service UUID (the bonded board); connect.
2. Request MTU 517 (optionally 2M PHY and high priority); discover services; enable notifications on `control` and `data`.
3. `GET_INFO`. If `protocolVersion` isn't one the app knows, disconnect and show "update the app/firmware". Otherwise save the status (section 10) with the time it was read.
4. `SET_TIME(now)`.
5. `LIST` → collect `ENTRY` replies until `LIST_END`.
6. For each entry with a known format, oldest first:
   1. `GET(id, 0)` (or the saved offset when resuming), receive, check the CRC.
   2. Save it through `RecordingStorage`; `RecordingRepository.add(fileId, createdAt, durationMillis)` with `createdAt = recordedAt × 1000` (or now, if 0); `queueLocalAudioProcessing(id)`.
   3. `DELETE(id)`.
7. Disconnect. With nothing pending, the board stops advertising.

**Timeouts:** the phone waits up to 5 s for a reply and gives up on a `GET` if no chunk arrives for 5 s; then it disconnects and retries on the next scan. The board drops a connection that has sent no command for 30 s.

## 10. Device status: battery, storage, problems

The board reports its health in `INFO` (on every sync) and, in short form, in its advertisement (section 8). The app keeps the latest report with the time it was received, so it can show "battery 64% · storage 12% used · seen 5 min ago" even when the board is out of range.

**`INFO` payload** (after `[0x81][status]`):

| Field | Type | Meaning |
|---|---|---|
| `protocolVersion` | `u8` | 1 |
| `flags` | `u32` | problems and states, see below |
| `pendingCount` | `u16` | recordings waiting to sync |
| `batteryMillivolts` | `u16` | battery voltage; 0 = can't measure |
| `storageTotalKiB` | `u32` | size of the storage in use, in KiB (1 KiB = 1024 bytes): the board's flash, or the microSD card when one is used; 0 = unknown |
| `storageFreeKiB` | `u32` | free space in KiB |
| `missedCount` | `u16` | button presses that couldn't be recorded since the last report (storage full, battery too low, SD error). The current firmware always sends 0. |
| `uptimeSeconds` | `u32` | time since the board last powered up or reset |
| `resetReason` | `u8` | why it last reset, see below |
| `firmwareVersion` | UTF-8 | the rest of the packet, e.g. `1.0.0` |

With its 2-byte header, the reply is 26 bytes plus the version text. That's more than the default MTU allows (20 bytes per packet), so the phone must negotiate a larger MTU before `GET_INFO`; it always does (section 9).

**`flags` bits** (a bit is 0 when the board doesn't know):

| Bit | Name | Meaning |
|---|---|---|
| 0 | `CLOCK_SET` | the clock has been set since power-up (section 6) |
| 1 | `CHARGING` | the battery is charging |
| 2 | `USB_POWER` | the board is powered over USB |
| 3 | `BATTERY_LOW` | below the firmware's "low" threshold: time to charge |
| 4 | `STORAGE_LOW` | less than ~10% (or ~30 minutes of audio) free |
| 5 | `STORAGE_FULL` | new recordings are refused |
| 6 | `SD_ERROR` | a firmware built for a microSD card found none (or couldn't read it); the board records to its own flash instead |
| 7 | `MIC_ERROR` | the mic didn't start |
| 8 | `CHECK_IN` | a tap on the button asked the phone to connect; cleared by the next `GET_INFO` (in the advert, it makes the app connect even with nothing waiting) |

**The current firmware sets only `CLOCK_SET`, `SD_ERROR` and `CHECK_IN`.** The others are defined so the app already handles them; until the firmware sets them, the app judges battery and storage from the numbers (below). The rest are reserved (0). A `u32` leaves room for future problems without a version change; the app ignores bits it doesn't know.

**`resetReason` values:** 0 unknown, 1 power-on (battery inserted or USB plugged in), 2 brownout (the battery sagged too low), 3 watchdog (the firmware hung), 4 crash (panic), 5 software restart, 6 woke from deep sleep. Brownouts and crashes are worth a warning in the app, since they mean lost recordings or a firmware bug.

**Battery percentage:** the board sends millivolts, and the app converts them with a LiPo discharge curve (about 4.2 V = 100%, 3.7 V ≈ 50%, 3.3 V = empty). Readings under 3.2 V count as "not measured": the board can't run that low, so they come from a battery pin with nothing connected, which floats and reads noise. That keeps the board simple, and the curve can be tuned in the app without reflashing. This conversion is plain Kotlin in `core/`.

**What the board does about it** (current firmware): a recording stops at **5 minutes** (about 600 KB as Opus) and is saved as normal, so a stuck button can't fill the storage. If the storage is full, the recording can't be saved and is lost (logged on the board). Unsynced recordings are never deleted to make room. Ideas for later: refuse recordings below a critical battery voltage and count them in `missedCount`, and set `BATTERY_LOW`, `STORAGE_LOW` and `STORAGE_FULL`.

**Measuring the battery** needs a voltage divider from the battery to an analog pin (two 470 kΩ resistors to D0, with 100 nF from D0 to ground). Without one the pin floats, and the app shows "battery: unknown" for readings under 3.2 V. `CHARGING` and `USB_POWER` stay 0: the board can't detect them.

**What the app does with it:** shows battery, storage and "last seen" on the Device screen (updated from adverts too), and sends a notification for battery low (under 20 %, or `BATTERY_LOW`, not while charging), storage low (under 15 % free, or `STORAGE_LOW`) or full, SD or mic errors, missed recordings, and a brownout, watchdog or crash reset. A state notifies once when it starts and is removed when it ends; the app keeps that memory, not the board.

## 11. Duplicates and waking the phone

- **Duplicates:** the app stores `id/recordedAt/sizeBytes` with each board recording (`recordings.boardKey`). A recording that arrives again (the app was killed between saving and `DELETE`) is only deleted on the board, not saved twice.
- **Waking the phone:** the app runs its own low-power background scan for the board's adverts (`android/BoardScanner.kt`), with Android's `CompanionDeviceManager` presence events as a fallback. Android only lets the app start its sync service from the background while the board's companion-device association exists; the Device screen warns and offers to restore it if it's gone.

## 12. Development over Bluetooth (laptop only)

So the firmware can be updated without the USB port (`firmware/tools/dev.py`; see `firmware/README.md`). The phone app doesn't use any of this.

**Dev mode.** **8 taps, then press and hold 2 s** (long buzz; the hold isn't recorded) opens a 2-min window in which a laptop may pair. During it the board offers **passkey entry** instead of Just Works: it claims a display with a fixed 6-digit code (from the git-ignored `firmware/dev.secret`, built into the firmware), and the laptop types the same code. The resulting bond is **authenticated** (a Just Works bond never is); that's how the board tells the laptop's bond from the phone's. A new laptop bond replaces only an old laptop bond. Dev mode stays on while the laptop is connected and for **5 min** after it disconnects, or until `DONE`. Dev mode survives a software restart and deep sleep (RTC memory) but not a power cut. The board advertises (and stays awake) while dev mode is on. **The laptop's bond is kept when dev mode ends:** when the paired laptop connects again, whenever the board advertises (a tap, recordings waiting, the 30-min check-in), dev mode opens again without the gesture. That's the safety net for a board that sleeps and has no USB; the gesture and the code are only needed to pair a new laptop. One connection at a time, so the phone can't sync while the laptop is connected.

**The `dev` characteristic** needs an authenticated link (which turns dev mode on). Commands are writes; replies are notifications `[command | 0x80][status][payload]`, with the status codes of section 4 plus `0x07 BAD_IMAGE`.

| Opcode | Command | Payload | Reply payload |
|---|---|---|---|
| `0x01` | `INFO` | none | `onTrial u8`, `slot u8` (0/1), `maxWrite u16` (MTU − 3), `elfSha256` (32 bytes, the build's fingerprint), `version` (UTF-8, `git describe`) |
| `0x02` | `CONFIRM` | none | none. Marks the running firmware good; it still has to come back after a deep sleep (below). |
| `0x03` | `OTA_BEGIN` | `size u32`, optional `target u8` (0 = firmware, 1 = bootloader) | none, once the other slot (or, for a bootloader, the staging area at `0x12000`) is erased (~1 s). For a bootloader: `BUSY` while the firmware is on trial or unproven. |
| `0x04` | `OTA_DATA` | `offset u32`, bytes | **no reply**. Usually written without response; offsets must follow on exactly, or the update fails. Every 16th write is with response, so the laptop can't run far ahead. |
| `0x05` | `OTA_END` | none | none; then the board restarts into the new firmware. `BAD_OFFSET`: bytes missing; `BAD_IMAGE`: checksum wrong or not a `piatra-board` firmware. For a bootloader: checked (checksum, same ESP-IDF version and flash header bytes as the old one; else `BAD_IMAGE`, nothing changed), copied to `0x0`, read back, then the board restarts; `STORAGE_ERROR`: the copy failed, the board can't start again, so it stays awake for another try. |
| `0x06` | `ROLLBACK` | none | none; then restarts into the other slot. `NOT_FOUND`: no usable firmware there. |
| `0x07` | `DONE` | none | none; then dev mode ends (the laptop's bond stays) and the board may sleep. |
| `0x08` | `CONSOLE` | a command line (UTF-8, at most 64 bytes) | none, after the command's output has gone out as `0x41` packets. The commands and their output are the USB console's (`firmware/main/console.h`): `ls`, `get <id>`, `rm <id>`, `df`, `bat`, `buzz [ms]`, `adv` (also says what keeps the board awake), `boot` (the bootloader's ESP-IDF version and build time), `crash`, `sleep [s]`. |
| `0x09` | `LOG` | `flags u8`: bit 0 = send the history, bit 1 = then keep sending new lines | none, then `0x40` packets. Without bit 1, an empty `0x40` packet marks the end of the history. Live sending stops when the laptop disconnects. |

**Text from the board** comes unasked on the same characteristic, so the laptop sorts notifications by their first byte: `0x40` + log text (`ESP_LOG` lines) and `0x41` + console output, each as long as the MTU allows, and cut anywhere (not at line ends): join them up. The board keeps the **last 4 KB of log lines** in RTC memory, which survives restarts, crashes and deep sleep but not a power cut; a `----- start-up -----` line marks each start. If the laptop can't keep up, the oldest unsent lines are dropped and a `[... N bytes of log lost ...]` line says so; the board never waits for the log.

**Trial and rollback.** Two program slots (`ota_0`, `ota_1`). A new firmware (or one switched to with `ROLLBACK`) starts **on trial**. If it restarts for any reason before `CONFIRM`, the bootloader goes back to the previous firmware; if nobody confirms within **5 min**, the board marks it bad and restarts itself. `dev.py flash` confirms only after reconnecting over the dev link and seeing the new build's `elfSha256`, so a confirmed firmware is always one that can take the next update.

**Proof after a deep sleep.** The bootloader's trial can't include a deep sleep, because waking up is a restart, which would roll it back. So a confirmed firmware is "unproven" until the laptop has reconnected after a real wake from deep sleep: `dev.py flash` (and `rollback`) has the board `sleep 5`, then reconnects. Woken from deep sleep while unproven, the board stays awake and advertises for **3 min**; if the laptop hasn't connected by then, it switches to the other slot (which starts on trial, with dev mode still on, so `dev.py` confirms it). Kept in RTC memory, so a power cut forgets it.
