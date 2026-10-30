#include "sleep.h"

#include <stdio.h>

#include "ble.h"
#include "board.h"
#include "button.h"
#include "clock.h"
#include "dev.h"
#include "driver/rtc_io.h"
#include "esp_attr.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_pm.h"
#include "esp_rtc_time.h"
#include "esp_sleep.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "haptic.h"
#include "recorder.h"
#include "writer.h"

static const char *TAG = "sleep";

#define SLEEP_MARKER 0x534C5031 // "SLP1"
// Checked this often; the board sleeps once nothing has kept it awake for two checks in a row,
// so 0.1–0.2 s after the last thing that did.
#define WATCH_MS 100

// What the board knew when it went to sleep, kept in RTC memory for the wake-up.
// The RTC clock (clock_uptime_ms) keeps counting through sleep and restarts.
typedef struct {
    uint32_t marker;
    uint64_t slept_at_us; // RTC time when it went to sleep
    uint64_t timer_us;    // the timer it set
    uint32_t boot_ms;     // the last restart measured on a timer wake (0 = not yet)
} sleep_state_t;
RTC_NOINIT_ATTR static sleep_state_t state;

static uint64_t boot_us;    // RTC time when app_main started
static bool by_button, by_timer;
static bool listening_noted;

void sleep_note_boot(void) {
    boot_us = esp_rtc_get_time_us(); // first, before anything else takes time
    if (state.marker != SLEEP_MARKER || clock_power_was_lost()) {
        state = (sleep_state_t){ .marker = SLEEP_MARKER };
    }

    uint32_t causes = esp_sleep_get_wakeup_causes();
    by_button = causes & (1 << ESP_SLEEP_WAKEUP_EXT1);
    by_timer = causes & (1 << ESP_SLEEP_WAKEUP_TIMER);
    uint32_t asleep_ms = (boot_us - state.slept_at_us) / 1000;

    if (by_timer && state.timer_us) {
        // The timer's moment is known, so the restart can be measured: from the wake-up to
        // here. (Both use the same RTC clock, so its few-% drift mostly cancels out.)
        state.boot_ms = (boot_us - state.slept_at_us - state.timer_us) / 1000;
        ESP_LOGI(TAG, "woke up (timer) after %lu ms asleep: the restart took %lu ms",
                 (unsigned long)asleep_ms, (unsigned long)state.boot_ms);
    } else if (by_button) {
        ESP_LOGI(TAG, "woke up (button) after %lu ms asleep", (unsigned long)asleep_ms);
    }
}

bool sleep_woke_by_button(void) {
    return by_button;
}

bool sleep_woke_by_timer(void) {
    return by_timer;
}

void sleep_note_listening(void) {
    if (!by_button || listening_noted) return;
    listening_noted = true;
    uint32_t since_boot_ms = (esp_rtc_get_time_us() - boot_us) / 1000;
    // When the button woke the board isn't known; the restart measured on a timer wake fills in.
    if (state.boot_ms) {
        ESP_LOGI(TAG, "listening %lu ms after app_main: about %lu ms after the press",
                 (unsigned long)since_boot_ms, (unsigned long)(state.boot_ms + since_boot_ms));
    } else {
        ESP_LOGI(TAG, "listening %lu ms after app_main (+ the restart: `sleep 5` measures it)",
                 (unsigned long)since_boot_ms);
    }
}

void sleep_light_init(void) {
    // min = max: the CPU never slows down (frequency scaling would only raise it while some
    // code holds a "lock", and nothing here takes one). It only naps when every task waits.
    // Drivers that need a steady clock keep it awake by themselves: the mic while recording.
    esp_pm_config_t config = { .max_freq_mhz = 160, .min_freq_mhz = 160, .light_sleep_enable = true };
    esp_err_t err = esp_pm_configure(&config);
    if (err != ESP_OK) ESP_LOGE(TAG, "light sleep not switched on: %s", esp_err_to_name(err));
}

const char *sleep_refusal(void) {
    // Waking up is a restart, and a firmware on trial goes back to the previous one when it
    // restarts before it's confirmed (dev.c).
    esp_ota_img_states_t img_state;
    if (esp_ota_get_state_partition(esp_ota_get_running_partition(), &img_state) == ESP_OK &&
        img_state == ESP_OTA_IMG_PENDING_VERIFY) {
        return "the firmware is on trial (tools/dev.py confirms it)";
    }
    return NULL;
}

void sleep_deep(uint32_t seconds) {
    // The button pulls D1 low. While asleep, the pin is run by the RTC part, so its pull-up
    // has to be set there, and that part kept powered (a few µA).
    esp_sleep_pd_config(ESP_PD_DOMAIN_RTC_PERIPH, ESP_PD_OPTION_ON);
    rtc_gpio_pullup_en(PIN_BUTTON);
    rtc_gpio_pulldown_dis(PIN_BUTTON);
    esp_sleep_enable_ext1_wakeup_io(1ULL << PIN_BUTTON, ESP_EXT1_WAKEUP_ANY_LOW);
    // The button's light-sleep wake (button.c) is for naps only; deep sleep uses ext1 above.
    esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_GPIO);

    // Always a timer too: if the button couldn't wake it, nothing else could (no USB, no
    // reset button reachable), and the board would sleep for good.
    if (seconds == 0 || seconds > SLEEP_CHECK_IN_SECONDS) seconds = SLEEP_CHECK_IN_SECONDS;
    state.timer_us = (uint64_t)seconds * 1000000;
    esp_sleep_enable_timer_wakeup(state.timer_us);

    ESP_LOGI(TAG, "deep sleep until the button or %lu s", (unsigned long)seconds);
    state.slept_at_us = esp_rtc_get_time_us(); // last, so the log line above isn't counted
    esp_deep_sleep_start();
}

// What keeps the board awake right now (NULL: nothing, it may sleep).
static const char *awake_reason(void) {
    if (recorder_busy()) return "recording or counting taps";
    if (button_held()) return "the button is held";
    if (haptic_busy()) return "buzzing"; // deep sleep would cut it off
    if (!writer_idle()) return "saving a recording";
    if (dev_mode_active()) return "dev mode";
    if (ble_keeps_awake()) return "Bluetooth (connected, advertising for a while, or pairing mode)";
    return sleep_refusal();
}

static void sleep_task(void *arg) {
    int quiet = 0;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(WATCH_MS));
        quiet = awake_reason() ? 0 : quiet + 1;
        if (quiet >= 2) {
            ESP_LOGI(TAG, "nothing to do: going to sleep");
            sleep_deep(0);
        }
    }
}

void sleep_start(void) {
    xTaskCreate(sleep_task, "sleep", 3072, NULL, 2, NULL);
}

void sleep_format_state(char *out, size_t size) {
    const char *why = awake_reason();
    snprintf(out, size, "AWAKE %s", why ? why : "for nothing: sleeping within a second");
}
