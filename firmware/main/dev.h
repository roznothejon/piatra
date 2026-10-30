// Development over Bluetooth (docs/esp32-protocol.md section 12): dev mode, opened by
// 8 taps + a 2 s hold, lets a laptop pair with a 6-digit code and update the firmware
// (tools/dev.py). The phone's bond is never touched. The laptop's bond is kept afterwards, so
// it can open dev mode again by connecting whenever the board advertises (the safety net for
// a board that sleeps and has no USB).
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// At start-up, before Bluetooth: picks up dev mode after a restart, and starts the
// 5-min trial if this firmware is new.
void dev_start(void);

// The gesture (recorder.c): a laptop may pair with the code for the next 2 minutes.
void dev_gesture(void);

// Dev mode is on, its pairing window is open, or the board waits for the laptop to prove a
// new firmware after a deep sleep: the board stays awake and advertises.
bool dev_mode_active(void);
// The 2-min window after the gesture: pairing uses the code.
bool dev_pairing_open(void);

// From ble.c: a laptop connected over a code-paired link, or that link has gone.
void dev_link_up(void);
void dev_link_down(void);

// A write to the dev characteristic (from NimBLE's task). False: refuse it.
bool dev_write(const uint8_t *bytes, size_t len);
