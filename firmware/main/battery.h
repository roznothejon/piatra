// Battery voltage, measured through the divider on PIN_BATTERY.
#pragma once

#include <stdint.h>

void battery_init(void);

// The battery's voltage in millivolts (protocol section 10: 0 = unknown, i.e. no battery).
uint16_t battery_millivolts(void);
