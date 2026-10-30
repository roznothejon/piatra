// Deep sleep. In deep sleep almost the whole chip is off: only the RTC part
// keeps running (its timer, the wake-up pins and 8 KB of RTC memory). Waking up is a full
// restart, through the bootloader into app_main, so the code here also measures how long
// that restart takes. The board sleeps by itself whenever nothing keeps it awake,
// and wakes on the button or every 30 min for a check-in.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// The timer every sleep sets: the check-in (sync.c advertises briefly after it), every
// 30 min.
#define SLEEP_CHECK_IN_SECONDS (30 * 60)

// Light sleep while awake: the chip naps whenever every task is waiting, and
// the radio wakes it for its next advert or connection event. Unlike deep sleep, nothing
// restarts: RAM, tasks and the Bluetooth connection carry on. Called early in app_main.
void sleep_light_init(void);

// First thing in app_main (after logbuf_start): notes the time, and logs how the board woke
// up and how long the restart took.
void sleep_note_boot(void);

// The board woke from deep sleep because the button was pressed, or for the 30-min check-in.
bool sleep_woke_by_button(void);
bool sleep_woke_by_timer(void);

// The recorder calls this once the mic is listening: after a button wake, it logs how long
// that took (once per wake).
void sleep_note_listening(void);

// Why the board can't sleep now (NULL: it can).
const char *sleep_refusal(void);

// Deep sleep until the button is pressed, or `seconds` have passed (0 or more than 30 min:
// 30 min, so a broken button wake can never keep the board asleep for good).
// Never returns: the board restarts when it wakes.
void sleep_deep(uint32_t seconds);

// Starts the task that puts the board to sleep once nothing keeps it awake: a recording, the
// writer, dev mode, a connection, an advertising window (a tap, a check-in, recordings
// waiting) or pairing mode, a firmware on trial. Last in app_main.
void sleep_start(void);

// What keeps the board awake, as one line of text (the console's `awake` command).
void sleep_format_state(char *out, size_t size);
