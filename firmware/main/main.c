// Piatra board firmware. app_main runs once at start-up (like Application.onCreate), sets
// up the hardware and starts the tasks; after that everything happens in the tasks:
//   button   → watches the record button (D1), sends press/release events
//   recorder → hold to record: mic → writer; short presses count as taps
//   writer   → saves the audio to the microSD card (or flash), so slow writes never hold up the mic
//   console  → text commands for the laptop, over USB (tools/pull.py) or dev mode (tools/dev.py)
//   sync     → answers the phone over Bluetooth (NimBLE runs in a task of its own)
//   dev      → dev mode: firmware updates, console and log for a laptop over Bluetooth (tools/dev.py)
//   logbuf   → keeps the last 4 KB of log lines and sends them to the laptop
//   sleep    → deep sleep once nothing keeps the board awake (wakes on the button or in 30 min)
#include "battery.h"
#include "board.h"
#include "button.h"
#include "clock.h"
#include "console.h"
#include "dev.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_system.h"
#include "haptic.h"
#include "led.h"
#include "logbuf.h"
#include "recorder.h"
#include "sleep.h"
#include "store.h"
#include "sync.h"
#include "writer.h"

static const char *TAG = "main";

void app_main(void) {
    logbuf_start(); // first, so the buffer has every line from here on
    sleep_note_boot(); // how it woke up, and how long the restart took
    sleep_light_init(); // nap between tasks from here on
    ESP_LOGI(TAG, "Piatra board starting (reset reason %d, RTC uptime %llu ms, power was %s)",
             esp_reset_reason(), clock_uptime_ms(), clock_power_was_lost() ? "off" : "on");

    haptic_init();
    if (!USE_SD_CARD) led_init(); // with a card in, its pin belongs to the card (below)
    dev_start(); // before the recorder (the gesture) and Bluetooth (sync_start) use it

    // The mic first: after a wake by the button, the user is already speaking. The writer
    // keeps the audio in its queue until the store is mounted.
    // A queue is how tasks hand each other data, like a Kotlin Channel.
    QueueHandle_t button_events = xQueueCreate(16, sizeof(button_event_t));
    writer_start();
    button_start(button_events);
    recorder_start(button_events);

    battery_init();
    ESP_ERROR_CHECK(store_init());
    writer_storage_ready();
    if (USE_SD_CARD && !store_on_card()) led_init();
    console_start();
    sync_start();
    sleep_start(); // last: everything that keeps the board awake is running

    ESP_LOGI(TAG, "ready: hold the button (D1) to record; battery %u mV (0 = none)", battery_millivolts());
    // The mic takes ~32 KB of this (its DMA buffers) at every press, so keep an eye on it.
    ESP_LOGI(TAG, "free RAM: %u KB, largest block %u KB", heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024,
             heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL) / 1024);
}
