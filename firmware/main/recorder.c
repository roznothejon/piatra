#include "recorder.h"

#include <math.h>
#include <string.h>
#include "board.h"
#include "button.h"
#include "clock.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/task.h"
#include "haptic.h"
#include "ble.h"
#include "dev.h"
#include "encoder.h"
#include "led.h"
#include "mic.h"
#include "sleep.h"
#include "sync.h"
#include "writer.h"

static const char *TAG = "recorder";

// Shorter than this is a tap, not a recording (the same rule as the phone mic in the app).
#define MIN_SAMPLES (SAMPLE_RATE * 1)
#define MAX_SAMPLES (SAMPLE_RATE * 300) // 5 minutes (Opus: ~600 KB)
#define BLOCK_SAMPLES ENCODER_FRAME     // 20 ms
#define FIRST_SECOND_FRAMES (MIN_SAMPLES / BLOCK_SAMPLES)

// Taps are counted once they stop (no tap for TAP_GAP_US):
//   exactly 5 taps                   → pairing mode (protocol section 7)
//   8 taps, then press and hold 2 s  → dev mode (dev.c); nothing is recorded
#define TAP_GAP_US (1000 * 1000)
#define PAIRING_TAPS 5
#define DEV_TAPS 8
#define DEV_HOLD_MS 2000

// The first second stays in RAM (encoded) until we know it's a recording and not a tap,
// so taps never touch flash or use up an id.
static uint8_t first_second[FIRST_SECOND_FRAMES][ENCODER_MAX_BYTES];
static size_t first_second_bytes[FIRST_SECOND_FRAMES];
static int16_t block[BLOCK_SAMPLES];
static uint8_t encoded[ENCODER_MAX_BYTES];

static float dbfs(int peak) {
    return peak > 0 ? 20.0f * log10f(peak / 32767.0f) : -99.0f;
}

#define TAP_AWAKE_SECONDS 30

static volatile int taps; // taps so far in this series (read by the sleep task too)
static volatile bool busy; // handling a press
static int64_t last_tap_us;

static void count_tap(void) {
    taps++;
    last_tap_us = esp_timer_get_time();
    ESP_LOGI(TAG, "tap %d", taps);
    // A tap also wakes the board's Bluetooth for a while and asks the phone to check in, so
    // the app connects by itself and shows a fresh status (Check now works too).
    sync_ask_for_check_in();
    ble_wake(TAP_AWAKE_SECONDS);
}

// Once the taps have stopped, the count is final.
static void settle_taps(void) {
    if (taps == 0 || esp_timer_get_time() - last_tap_us < TAP_GAP_US) return;
    if (taps == PAIRING_TAPS) {
        ESP_LOGI(TAG, "pairing gesture");
        haptic_buzz(600);
        ble_pairing_mode(); // blinks the LED while it's open
    }
    taps = 0;
}

// The press after 8 taps: held for 2 s, it opens dev mode instead of recording.
static void hold_for_dev_mode(QueueHandle_t events) {
    button_event_t event;
    if (xQueueReceive(events, &event, pdMS_TO_TICKS(DEV_HOLD_MS)) == pdTRUE) {
        count_tap(); // let go too soon: just a 9th tap
        return;
    }
    taps = 0;
    ESP_LOGI(TAG, "dev gesture");
    haptic_buzz(1000);
    dev_gesture();
    while (xQueueReceive(events, &event, portMAX_DELAY) == pdTRUE && event != BUTTON_RELEASED) {
    }
}

// Records while the button is held. Returns when it's released or the maximum is reached.
static void record_while_held(QueueHandle_t events) {
    uint64_t uptime_ms = clock_uptime_ms();
    uint32_t recorded_at = clock_now_or_zero();

    ble_quiet(true); // before the mic: the phone takes a few connection events to agree
    esp_err_t err = mic_start();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "mic didn't start: %s", esp_err_to_name(err));
        ble_quiet(false);
        return;
    }
    // The mic is listening from here on (~65 ms after the press, ~250 ms after a press that
    // woke the board): tell the user.
    led_set(true);
    haptic_buzz(60);
    sleep_note_listening(); // after a wake-up: how long from the press

    size_t total = 0;
    bool saving = false; // true once past the first second, handing audio to the writer
    bool lost = false;   // the writer's queue was full, so some audio couldn't be kept
    bool failed = false; // the encoder failed
    encoder_begin();

    for (;;) {
        size_t got = mic_read(block, BLOCK_SAMPLES);
        // Frames are always whole: a short read (only after a mic error) is filled with silence.
        if (got < BLOCK_SAMPLES) memset(block + got, 0, (BLOCK_SAMPLES - got) * 2);
        size_t n = encoder_frame(block, encoded);
        failed |= n == 0;
        int frame = total / BLOCK_SAMPLES;
        total += BLOCK_SAMPLES;

        if (!saving) {
            memcpy(first_second[frame], encoded, n);
            first_second_bytes[frame] = n;
            if (frame == FIRST_SECOND_FRAMES - 1) {
                // Held for a full second: it's a recording. Hand it to the writer.
                saving = true;
                lost = !writer_begin(recorded_at, uptime_ms, encoder_format());
                for (int i = 0; i < FIRST_SECOND_FRAMES; i++) {
                    lost |= !writer_frame(first_second[i], first_second_bytes[i]);
                }
            }
        } else {
            lost |= !writer_frame(encoded, n);
        }

        button_event_t event;
        if (xQueueReceive(events, &event, 0) == pdTRUE && event == BUTTON_RELEASED) break;
        if (total >= MAX_SAMPLES) {
            ESP_LOGW(TAG, "maximum length reached, stopping");
            break; // the coming release is ignored by recorder_task
        }
    }

    mic_stop();
    ble_quiet(false);
    led_set(false);

    if (!saving) {
        count_tap();
        return;
    }
    writer_end(); // the writer logs "saved recording N" once it's in flash
    mic_levels_t levels = mic_levels();
    ESP_LOGI(TAG, "recorded %.1f s: peak %.1f dBFS (%.1f before gain), %lu limited, %lu overflows%s%s",
             total / (float)SAMPLE_RATE, dbfs(levels.peak), dbfs(levels.peak_raw),
             (unsigned long)levels.limited, (unsigned long)levels.overflows,
             lost ? ", AUDIO LOST (writer queue full)" : "", failed ? ", ENCODER FAILED" : "");
    encoder_log_stats();
    ESP_LOGI(TAG, "recorder stack: %lu bytes never used", (unsigned long)uxTaskGetStackHighWaterMark(NULL));
}

static void recorder_task(void *arg) {
    QueueHandle_t events = arg;
    for (;;) {
        button_event_t event;
        // While taps are being counted, look every 100 ms whether they've stopped.
        TickType_t wait = taps ? pdMS_TO_TICKS(100) : portMAX_DELAY;
        if (xQueueReceive(events, &event, wait) == pdTRUE && event == BUTTON_PRESSED) {
            busy = true;
            if (taps == DEV_TAPS) {
                hold_for_dev_mode(events);
            } else {
                record_while_held(events);
            }
            busy = false;
        }
        settle_taps();
    }
}

bool recorder_busy(void) {
    return busy || taps > 0;
}

// libopus does its work in arrays on the stack, so the recorder needs a big one with Opus
// (~23 KB used at complexity 3, measured).
#define RECORDER_STACK (RECORD_OPUS ? 28 * 1024 : 4096)

void recorder_start(QueueHandle_t events) {
    encoder_init();
    xTaskCreate(recorder_task, "recorder", RECORDER_STACK, events, 10, NULL);
}
