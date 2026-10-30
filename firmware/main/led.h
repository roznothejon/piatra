// The user LED: on while recording, blinking for gestures.
#pragma once

#include <stdbool.h>

void led_init(void);
void led_set(bool on);

// Blinks for `duration_ms` (on and off every `period_ms`), then turns the LED off.
void led_blink(int period_ms, int duration_ms);
