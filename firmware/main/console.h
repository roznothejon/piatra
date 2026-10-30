// Text commands for development: list, copy and delete recordings, battery, motor and
// Bluetooth state. They run over the USB cable (tools/pull.py) or, since the board is in its
// case, over Bluetooth in dev mode (tools/dev.py, protocol section 12).
//   ls           REC <id> <size> <recordedAt> <crc> <format> lines, then LS_END <count>
//   get <id>     BEGIN <id> <size> <crc>, D <base64> lines, then END
//   rm <id>      OK or ERR
//   df           DF <totalKB> <freeKB> <sd|flash>
//   bat          BAT <millivolts> (0 = no battery)
//   buzz [ms]    buzzes the motor (default 300 ms), then OK
//   adv          prints the advertising state, then AWAKE <what keeps the board awake>
//   crash        panics on purpose (the board restarts; tests the app's crash warning)
//   sleep [s]    OK, then deep sleep until the button or s seconds (max/default 30 min; ERR on trial)
#pragma once

#include <stddef.h>

// Where a command's output goes: the USB port, or the laptop over Bluetooth (dev.c).
// Called with (NULL, 0) when the output should go out now (the command has finished).
typedef void (*console_out_t)(const char *text, size_t len);

// Runs one command line (without its line ending). Commands run one at a time.
void console_run(const char *line, console_out_t out);

// The USB console: a task that reads lines from the port.
void console_start(void);
