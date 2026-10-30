#include "writer.h"

#include <string.h>
#include "board.h"
#include "encoder.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "store.h"
#include "sync.h"

static const char *TAG = "writer";

#define QUEUE_ITEMS 150 // one 20 ms frame each: 3 s of audio

typedef enum { WRITE_BEGIN, WRITE_AUDIO, WRITE_END } write_kind_t;

// One message from the recorder. A single queue carries all three kinds, so they arrive
// in the order they were sent.
typedef struct {
    write_kind_t kind;
    uint16_t count;       // WRITE_AUDIO: bytes used in `bytes`
    uint8_t format;       // WRITE_BEGIN
    uint32_t recorded_at; // WRITE_BEGIN
    uint64_t uptime_ms;   // WRITE_BEGIN
    uint8_t bytes[ENCODER_MAX_BYTES];
} write_item_t;

static QueueHandle_t queue;
static TaskHandle_t task;
static volatile bool working; // an item is being handled
static volatile bool rec_open; // a recording is under way (between BEGIN and END)

// Copying a whole item into the queue is fine: a few hundred bytes every 20 ms.
static bool send(const write_item_t *item) {
    return xQueueSend(queue, item, 0) == pdTRUE;
}

bool writer_begin(uint32_t recorded_at, uint64_t uptime_ms, uint8_t format) {
    write_item_t item = { .kind = WRITE_BEGIN, .format = format, .recorded_at = recorded_at, .uptime_ms = uptime_ms };
    return send(&item);
}

bool writer_frame(const uint8_t *bytes, size_t count) {
    static write_item_t item = { .kind = WRITE_AUDIO }; // static: too big for the caller's stack
    memcpy(item.bytes, bytes, count);
    item.count = count;
    return send(&item);
}

bool writer_end(void) {
    write_item_t item = { .kind = WRITE_END };
    // The end must not get lost, or the recording would never be finished: wait for room.
    return xQueueSend(queue, &item, pdMS_TO_TICKS(5000)) == pdTRUE;
}

static void writer_task(void *arg) {
    static write_item_t item;
    bool failed = false;
    int64_t slowest_us = 0;
    uint32_t frames = 0; // in this recording

    // A press that woke the board starts recording before the store is mounted (main.c);
    // the queue holds the audio until then.
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);

    for (;;) {
        xQueueReceive(queue, &item, portMAX_DELAY);
        working = true;
        int64_t start = esp_timer_get_time();

        switch (item.kind) {
        case WRITE_BEGIN:
            rec_open = store_begin(item.recorded_at, item.uptime_ms, item.format);
            failed = !rec_open;
            slowest_us = 0;
            frames = 0;
            break;
        case WRITE_AUDIO:
            if (rec_open && !failed) failed = !store_write(item.bytes, item.count);
            frames++;
            break;
        case WRITE_END: {
            if (!rec_open) break;
            rec_open = false;
            rec_info_t info;
            if (!store_finish(&info) || failed) {
                ESP_LOGE(TAG, "couldn't save the recording (storage full?)");
                break;
            }
            ESP_LOGI(TAG, "saved recording %lu: %.1f s, %lu KB, slowest flash write %lld ms",
                     (unsigned long)info.id, frames * 0.02f, (unsigned long)info.size / 1024,
                     slowest_us / 1000);
            sync_recording_saved();
            break;
        }
        }

        int64_t took = esp_timer_get_time() - start;
        if (took > slowest_us) slowest_us = took;
        working = false;
    }
}

void writer_storage_ready(void) {
    xTaskNotifyGive(task);
}

bool writer_idle(void) {
    return !working && !rec_open && uxQueueMessagesWaiting(queue) == 0;
}

void writer_start(void) {
    queue = xQueueCreate(QUEUE_ITEMS, sizeof(write_item_t));
    // Lower priority than the recorder: the mic always comes first.
    xTaskCreate(writer_task, "writer", 4096, NULL, 8, &task);
}
