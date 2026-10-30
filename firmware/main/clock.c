#include "clock.h"

#include <sys/time.h>
#include <time.h>
#include "esp_attr.h"
#include "esp_rtc_time.h"
#include "esp_system.h"

// The clock counts as set once it's past 2025 (it starts at 1970 after power-up).
#define CLOCK_VALID_AFTER 1735689600
#define POWERED_MARKER 0x50525452 // "PRTR"

// RTC memory keeps its contents through resets and deep sleep, but not without power.
// RTC_NOINIT_ATTR: start-up leaves it as it was, so the marker tells if power was lost.
RTC_NOINIT_ATTR static uint32_t powered_marker;

uint32_t clock_now_or_zero(void) {
    time_t now = time(NULL);
    return now > CLOCK_VALID_AFTER ? (uint32_t)now : 0;
}

bool clock_is_set(void) {
    return clock_now_or_zero() != 0;
}

void clock_set(uint32_t unix_seconds) {
    struct timeval tv = { .tv_sec = unix_seconds };
    settimeofday(&tv, NULL);
}

uint64_t clock_uptime_ms(void) {
    return esp_rtc_get_time_us() / 1000;
}

bool clock_power_was_lost(void) {
    static int lost = -1; // worked out once, at the first call
    if (lost < 0) {
        esp_reset_reason_t reason = esp_reset_reason();
        lost = powered_marker != POWERED_MARKER || reason == ESP_RST_POWERON || reason == ESP_RST_BROWNOUT;
        powered_marker = POWERED_MARKER;
    }
    return lost;
}
