#include "logbuf.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include "ble.h"
#include "clock.h"
#include "esp_attr.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define RING_SIZE 4096
#define RING_MARKER 0x4C4F4731 // "LOG1"
#define LOG_LINE_MAX 200           // longer log lines are cut
#define LOG_PACKET 0x40        // [0x40][text] on the dev characteristic (protocol section 12)

// A ring buffer: `total` counts every byte ever written, and byte n lives at text[n % RING_SIZE],
// so the newest bytes overwrite the oldest. In RTC memory, like dev mode's state (dev.c).
typedef struct {
    uint32_t marker;
    uint32_t total;
    char text[RING_SIZE];
} ring_t;
RTC_NOINIT_ATTR static ring_t ring;

// Log lines come from every task. A spinlock ("critical section") keeps two of them from
// writing into the ring at once; it's held only for a short copy.
static portMUX_TYPE lock = portMUX_INITIALIZER_UNLOCKED;
static vprintf_like_t usb_vprintf; // where the log went before us
static TaskHandle_t sender;

// The sender's state. `cursor` is the next byte (as a `total` count) to send; it only sends
// while `sending`, and stops at `stop_at` (0 = keep going, live).
static volatile bool sending;
static uint32_t cursor, stop_at;

static void append(const char *text, size_t len) {
    portENTER_CRITICAL(&lock);
    for (size_t i = 0; i < len; i++) ring.text[(ring.total + i) % RING_SIZE] = text[i];
    ring.total += len;
    portEXIT_CRITICAL(&lock);
}

// Called by ESP_LOG for each line, in whichever task logged it. It must never wait on
// Bluetooth (the line may come from NimBLE's own task), so it only copies and wakes the sender.
static int log_vprintf(const char *format, va_list args) {
    va_list copy;
    va_copy(copy, args);
    int printed = usb_vprintf(format, args);
    char line[LOG_LINE_MAX];
    int n = vsnprintf(line, sizeof(line), format, copy);
    va_end(copy);
    if (n > 0) {
        if (n >= (int)sizeof(line)) { // cut: keep the line ending
            n = sizeof(line) - 1;
            line[n - 1] = '\n';
        }
        append(line, n);
        if (sending && sender) xTaskNotifyGive(sender);
    }
    return printed;
}

// Copies up to `max` bytes from `from` on; moves `from` forward past bytes already
// overwritten (*lost counts them). Returns how many were copied.
static size_t read_from(uint32_t *from, uint32_t until, char *out, size_t max, uint32_t *lost) {
    portENTER_CRITICAL(&lock);
    uint32_t oldest = ring.total > RING_SIZE ? ring.total - RING_SIZE : 0;
    if (*from < oldest) {
        *lost += oldest - *from;
        *from = oldest;
    }
    size_t n = until - *from < max ? until - *from : max;
    for (size_t i = 0; i < n; i++) out[i] = ring.text[(*from + i) % RING_SIZE];
    *from += n;
    portEXIT_CRITICAL(&lock);
    return n;
}

static bool send_packet(const char *text, size_t len) {
    uint8_t packet[515];
    packet[0] = LOG_PACKET;
    memcpy(packet + 1, text, len);
    return ble_notify_dev(packet, len + 1);
}

static void sender_task(void *arg) {
    char text[514];
    for (;;) {
        ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(500));
        while (sending) {
            if (!ble_dev_connected()) { // the phone may be connected instead: never send it this
                sending = false;
                break;
            }
            uint32_t lost = 0;
            uint32_t until = stop_at ? stop_at : ring.total;
            size_t room = ble_max_payload() - 1;
            if (room > sizeof(text)) room = sizeof(text);
            size_t n = read_from(&cursor, until, text, room, &lost);
            if (lost) {
                int m = snprintf(text, sizeof(text), "[... %lu bytes of log lost: the laptop was too slow]\n",
                                 (unsigned long)lost);
                send_packet(text, m);
                continue;
            }
            if (n == 0) {
                if (stop_at) { // the history is out: an empty packet says so
                    sending = false;
                    send_packet(text, 0);
                }
                break; // wait for new lines
            }
            // A failed send means the laptop has gone; stop, so the "notify failed" warnings
            // this logs don't keep the sender busy.
            if (!send_packet(text, n)) sending = false;
        }
    }
}

void logbuf_send(bool history, bool live) {
    portENTER_CRITICAL(&lock);
    uint32_t total = ring.total;
    portEXIT_CRITICAL(&lock);
    cursor = history ? (total > RING_SIZE ? total - RING_SIZE : 0) : total;
    stop_at = live ? 0 : total;
    sending = true;
    xTaskNotifyGive(sender);
}

void logbuf_stop(void) {
    sending = false;
}

void logbuf_start(void) {
    if (ring.marker != RING_MARKER || clock_power_was_lost()) {
        ring.marker = RING_MARKER;
        ring.total = 0;
    }
    static const char separator[] = "\n----- start-up -----\n";
    append(separator, sizeof(separator) - 1);
    usb_vprintf = esp_log_set_vprintf(log_vprintf);
    xTaskCreate(sender_task, "logbuf", 3072, NULL, 2, &sender);
}
