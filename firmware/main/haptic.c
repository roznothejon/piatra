#include "haptic.h"

#include "board.h"
#include "driver/gpio.h"
#include "esp_timer.h"

static esp_timer_handle_t off_timer;

static void turn_off(void *arg) {
    gpio_set_level(PIN_HAPTIC, 0);
}

void haptic_init(void) {
    gpio_config_t config = {
        .pin_bit_mask = 1ULL << PIN_HAPTIC,
        .mode = GPIO_MODE_OUTPUT,
    };
    gpio_config(&config);
    // During light sleep ESP-IDF disconnects every pin by default (less leakage); this one keeps
    // its level, or a buzz would stop the moment the chip naps.
    gpio_sleep_sel_dis(PIN_HAPTIC);
    gpio_set_level(PIN_HAPTIC, 0);

    esp_timer_create_args_t args = { .callback = turn_off, .name = "haptic_off" };
    esp_timer_create(&args, &off_timer);
}

void haptic_buzz(int ms) {
    esp_timer_stop(off_timer); // fine if it wasn't running
    gpio_set_level(PIN_HAPTIC, 1);
    esp_timer_start_once(off_timer, (uint64_t)ms * 1000);
}

bool haptic_busy(void) {
    return esp_timer_is_active(off_timer);
}
