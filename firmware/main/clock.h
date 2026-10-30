// The board's sense of time (protocol section 6). There's no clock battery: after a power
// loss the clock starts at 1970, until the phone sends SET_TIME.
#pragma once

#include <stdbool.h>
#include <stdint.h>

// Unix seconds, or 0 while the clock hasn't been set since power-up.
uint32_t clock_now_or_zero(void);
bool clock_is_set(void);
void clock_set(uint32_t unix_seconds);

// Milliseconds since power-up. Kept by the RTC timer, which keeps counting through deep
// sleep and resets only on a power loss, so it tells recordings of this power-up apart.
uint64_t clock_uptime_ms(void);

// True if the power was off before this start-up (not just a reset or a wake from deep
// sleep): the uptime of earlier recordings then counts from a different power-up.
bool clock_power_was_lost(void);
