// The board's log, kept for the laptop. Every ESP_LOG line still goes to USB
// (nobody listens there since the case), and is also copied into a 4 KB ring buffer in RTC
// memory, which survives restarts, crashes and deep sleep (not a power cut). `tools/dev.py
// log` reads it; `tools/dev.py monitor` also streams new lines as they come.
#pragma once

#include <stdbool.h>

// First thing in app_main: hooks the log, and keeps what the buffer held before this start-up.
void logbuf_start(void);

// Over the dev link (dev.c, LOG command): send the buffer's history, then new lines while
// `live`. Without `live`, an empty log packet marks the end of the history.
void logbuf_send(bool history, bool live);
// The laptop has gone: stop sending.
void logbuf_stop(void);
