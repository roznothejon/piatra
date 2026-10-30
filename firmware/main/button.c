#include "button.h"

#include <stdbool.h>
#include "board.h"
#include "driver/gpio.h"
#include "driver/rtc_io.h"
#include "esp_log.h"
#include "esp_sleep.h"
#include "freertos/task.h"
#include "sleep.h"

static const char *TAG = "button";

static volatile bool held; // the debounced state, for the sleep task
static TaskHandle_t task;

#define POLL_MS 10
// A mechanical button "bounces" (flickers on and off) for a few ms when pressed. A change
// only counts once the pin has read the same 3 times in a row (30 ms).
#define STABLE_POLLS 3

// Runs the moment the pin goes low (an interrupt, like a callback from the hardware), also
// waking the chip from light sleep. It only wakes the task, which polls from then on; the
// interrupt stays off until the button is up again, or it would fire over and over.
static void on_press(void *arg) {
    gpio_intr_disable(PIN_BUTTON);
    BaseType_t woken = pdFALSE;
    vTaskNotifyGiveFromISR(task, &woken);
    portYIELD_FROM_ISR(woken);
}

static void button_task(void *arg) {
    QueueHandle_t events = arg;
    bool pressed = false;
    int same_count = 0;

    if (sleep_woke_by_button()) {
        // The press that woke the board is real: no debounce, it goes out at once. If the
        // button is already up again (a tap shorter than the ~230 ms restart), it still counts
        // as a press and a release, i.e. a tap: the first of 5 or 8 taps from sleep.
        button_event_t event = BUTTON_PRESSED;
        xQueueSend(events, &event, 0);
        pressed = gpio_get_level(PIN_BUTTON) == 0;
        held = pressed;
        if (!pressed) {
            event = BUTTON_RELEASED;
            xQueueSend(events, &event, 0);
        }
        ESP_LOGI(TAG, "woken by the button (%s)", pressed ? "still held" : "already let go: a tap");
    }

    for (;;) {
        // Up and settled: wait for the interrupt instead of polling, so the chip can nap
        // (a 10 ms poll would wake it 100 times a second). If the button is
        // already down, the interrupt fires at once.
        if (!pressed && same_count == 0) {
            gpio_intr_enable(PIN_BUTTON);
            ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
        }
        bool now = gpio_get_level(PIN_BUTTON) == 0; // pressed pulls the pin low
        if (now == pressed) {
            same_count = 0;
        } else if (++same_count >= STABLE_POLLS) {
            pressed = now;
            held = now;
            same_count = 0;
            button_event_t event = pressed ? BUTTON_PRESSED : BUTTON_RELEASED;
            xQueueSend(events, &event, 0);
        }
        vTaskDelay(pdMS_TO_TICKS(POLL_MS));
    }
}

bool button_held(void) {
    return held;
}

void button_start(QueueHandle_t events) {
    // After a wake-up the pin is still run by the RTC part (sleep.c); hand it back.
    if (rtc_gpio_is_valid_gpio(PIN_BUTTON)) rtc_gpio_deinit(PIN_BUTTON);
    gpio_config_t config = {
        .pin_bit_mask = 1ULL << PIN_BUTTON,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE, // the board has its own pull-up too; harmless
    };
    gpio_config(&config);
    // Let a press wake the chip from light sleep. This also keeps the pin and its pull-up
    // connected while the chip naps (ESP-IDF disconnects other pins then).
    gpio_wakeup_enable(PIN_BUTTON, GPIO_INTR_LOW_LEVEL);
    esp_sleep_enable_gpio_wakeup();
    xTaskCreate(button_task, "button", 2048, events, 5, &task);
    gpio_install_isr_service(0);
    gpio_isr_handler_add(PIN_BUTTON, on_press, NULL);
}
