#include "led.h"

#include "board.h"
#include "driver/gpio.h"
#include "esp_timer.h"

static esp_timer_handle_t blink_timer;
static int blink_toggles_left;
static bool lit;
static bool ready; // false while a microSD card owns the pin (see main.c): then all calls do nothing

void led_set(bool on) {
    if (!ready) return;
    lit = on;
    gpio_set_level(PIN_LED, on ? 0 : 1); // active low
}

// Runs on ESP-IDF's timer task every half period while blinking.
static void on_blink(void *arg) {
    if (--blink_toggles_left <= 0) {
        esp_timer_stop(blink_timer);
        led_set(false);
        return;
    }
    led_set(!lit);
}

void led_init(void) {
    gpio_config_t config = {
        .pin_bit_mask = 1ULL << PIN_LED,
        .mode = GPIO_MODE_OUTPUT,
    };
    gpio_config(&config);
    // During light sleep ESP-IDF disconnects every pin by default (less leakage); this one keeps
    // its level, or a lit or blinking LED would go out the moment the chip naps.
    gpio_sleep_sel_dis(PIN_LED);
    ready = true;
    led_set(false);

    esp_timer_create_args_t args = { .callback = on_blink, .name = "led_blink" };
    esp_timer_create(&args, &blink_timer);
}

void led_blink(int period_ms, int duration_ms) {
    if (!ready) return;
    esp_timer_stop(blink_timer); // fine if it wasn't running
    blink_toggles_left = 2 * duration_ms / period_ms;
    led_set(true);
    esp_timer_start_periodic(blink_timer, (uint64_t)period_ms * 1000 / 2);
}
