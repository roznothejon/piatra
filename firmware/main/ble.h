// Bluetooth LE: the Piatra GATT service and advertising (docs/esp32-protocol.md sections 2
// and 8). This file only moves bytes; what the commands mean is in sync.c.
//
// NimBLE (ESP-IDF's BLE stack) runs in its own task and calls us back when something
// happens (a phone connects, writes a command, ...). Those callbacks must return quickly,
// so commands are copied into a queue and handled by the sync task.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

// One write to the `control` characteristic. Commands are at most 9 bytes; a longer write
// keeps its real length in `len`, so it can be answered with BAD_LENGTH.
typedef struct {
    uint16_t len;
    uint8_t bytes[16];
} ble_command_t;

// Starts Bluetooth and advertising. Commands arrive in `commands` (ble_command_t).
void ble_start(QueueHandle_t commands);

// The status in the scan response (protocol section 8): what a phone sees without connecting.
#define BLE_STATUS_LEN 10
void ble_set_status(const uint8_t status[BLE_STATUS_LEN]);

// Opens pairing mode for 60 s (the 5-tap gesture, protocol section 7). The first device to
// pair in that time replaces the old bonds. With no bonds at all, pairing is always open.
void ble_pairing_mode(void);

// Advertising (protocol section 8) only while there's a reason: recordings waiting, pairing
// mode, or a short "awake" window (a tap on the button, the 30-min check-in). A phone can only
// connect while the board advertises.
void ble_set_pending(bool pending);
void ble_wake(int seconds);
// Recordings waiting keep the board awake (and advertising) only for a while: until `seconds`
// from now (a new recording, a check-in), not until they're synced.
void ble_advertise_pending(int seconds);
// Connected, in an advertising window (above), or in pairing mode: the board mustn't sleep.
bool ble_keeps_awake(void);
// Checks the reasons again (e.g. an awake window or pairing mode has run out).
void ble_update_advertising(void);
// The advertising state as one line of text (the console's `adv` command), for debugging.
void ble_format_state(char *out, size_t size);

// While recording: the radio keeps quiet. Each time the board transmits (+20 dBm, ~335 mA)
// the mic picks up a click, so a connection would click every 30 ms. Quiet asks the
// phone for "peripheral latency" (the board may skip connection events) and stops advertising.
void ble_quiet(bool on);

// Signal strength of the connection in dBm (about −50 close by, −90 at the edge); 0 if none.
int ble_rssi(void);

bool ble_connected(void);
void ble_disconnect(void);

// The most bytes one notification can carry on this connection (MTU − 3).
size_t ble_max_payload(void);

// Send a reply on `control` or a chunk on `data`. These wait while the stack's buffers are
// full, and return false if the phone has gone.
bool ble_notify_control(const uint8_t *bytes, size_t len);
bool ble_notify_data(const uint8_t *bytes, size_t len);
// A reply on the dev characteristic (dev.c, protocol section 12).
bool ble_notify_dev(const uint8_t *bytes, size_t len);

// Development (dev.c). Connected to a laptop paired with the dev code:
bool ble_dev_connected(void);
// Dev mode or its pairing window opened or closed: check pairing and advertising again.
// (The laptop's bond stays when dev mode ends: connecting again opens it again.)
void ble_dev_mode_changed(void);
