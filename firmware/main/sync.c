#include "sync.h"

#include <stdio.h>
#include "battery.h"
#include "board.h"
#include "ble.h"
#include "clock.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sleep.h"
#include "store.h"

// Largest data chunk, header included (see cmd_get).
#define MAX_CHUNK 512

static const char *TAG = "sync";

#define PROTOCOL_VERSION 1
#define FIRMWARE_VERSION "1.0.0"
// Drop a phone that has sent nothing for this long (protocol section 9).
#define IDLE_TIMEOUT_US (30 * 1000 * 1000)
#define MAX_RECORDINGS 256
// Refresh the advertised status this often even if nothing happened, for the battery.
#define STATUS_REFRESH_US (15LL * 60 * 1000 * 1000)
// Advertise briefly this often, so the phone connects and reads the battery and storage
// (protocol section 8). Asleep, the board wakes for it (sleep.c); awake, this timer does it.
#define CHECK_IN_US (SLEEP_CHECK_IN_SECONDS * 1000LL * 1000)
// A little longer than one cycle of the phone's low-power background scan (it listens ~0.5 s
// every ~5.1 s), so a check-in is heard.
#define CHECK_IN_SECONDS 6
// Recordings waiting: stay awake and advertise this long after a new one is saved (or after
// start-up), then only at each check-in, a little longer than an empty one.
#define PENDING_SECONDS 120
#define PENDING_CHECK_IN_SECONDS 15

// Commands (protocol section 3). A reply's opcode is the command's with the top bit set.
enum { CMD_GET_INFO = 0x01, CMD_SET_TIME = 0x02, CMD_LIST = 0x03, CMD_GET = 0x04, CMD_DELETE = 0x05 };
#define REPLY(cmd) (0x80 | (cmd))
#define REPLY_LIST_END 0x93
#define REPLY_GET_END 0x94

// Status codes (protocol section 4). BUSY and STORAGE_ERROR aren't needed yet: reading
// while recording works, and the storage is internal flash, not an SD card.
enum { OK = 0x00, UNKNOWN_COMMAND = 0x01, BAD_LENGTH = 0x02, NOT_FOUND = 0x03, BAD_OFFSET = 0x04, STORAGE_ERROR = 0x06 };

// INFO's flags (protocol section 10). The others stay 0 until the firmware can tell.
#define FLAG_CLOCK_SET (1 << 0)
// No usable microSD card: recordings go to the board's flash (about 3 minutes' worth).
#define FLAG_SD_ERROR (1 << 6)
// A tap asked the phone to check in; cleared by the next GET_INFO.
#define FLAG_CHECK_IN (1 << 8)

static rec_info_t recordings[MAX_RECORDINGS]; // static: 8 KB is too much for a task's stack
static volatile bool status_changed = true; // true: send the first status at start-up
static volatile bool check_in_wanted;
static int64_t last_check_in_us;

// Numbers on the wire are little-endian (lowest byte first). These write one and return
// where the next one goes.
static uint8_t *put_u8(uint8_t *at, uint8_t v) {
    *at = v;
    return at + 1;
}

static uint8_t *put_u16(uint8_t *at, uint16_t v) {
    at[0] = v;
    at[1] = v >> 8;
    return at + 2;
}

static uint8_t *put_u32(uint8_t *at, uint32_t v) {
    at = put_u16(at, v);
    return put_u16(at, v >> 16);
}

static uint32_t get_u32(const uint8_t *at) {
    return at[0] | at[1] << 8 | at[2] << 16 | (uint32_t)at[3] << 24;
}

static void send(const uint8_t *start, const uint8_t *end) {
    ble_notify_control(start, end - start);
}

// A reply with a status and no payload: [reply opcode][status].
static void reply_status(uint8_t cmd, uint8_t status) {
    uint8_t packet[2] = { REPLY(cmd), status };
    send(packet, packet + 2);
}

// ESP-IDF's reset reasons in the protocol's numbering (section 10).
static uint8_t reset_reason(void) {
    switch (esp_reset_reason()) {
    case ESP_RST_POWERON: return 1;
    case ESP_RST_BROWNOUT: return 2;
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT: return 3;
    case ESP_RST_PANIC: return 4;
    case ESP_RST_SW: return 5;
    case ESP_RST_DEEPSLEEP: return 6;
    default: return 0;
    }
}

static uint32_t flags(void) {
    return (clock_is_set() ? FLAG_CLOCK_SET : 0) | (USE_SD_CARD && !store_on_card() ? FLAG_SD_ERROR : 0) |
           (check_in_wanted ? FLAG_CHECK_IN : 0);
}

// The short status for the scan response (protocol section 8):
// [protocolVersion u8][flags u32][pendingCount u16][batteryMillivolts u16][storageFreePercent u8]
static void update_advertised_status(void) {
    uint32_t total_kb, free_kb;
    store_usage(&total_kb, &free_kb);
    int pending = store_list(recordings, MAX_RECORDINGS);
    uint16_t battery = battery_millivolts();

    uint8_t status[BLE_STATUS_LEN];
    uint8_t *p = status;
    p = put_u8(p, PROTOCOL_VERSION);
    p = put_u32(p, flags());
    p = put_u16(p, pending);
    p = put_u16(p, battery);
    p = put_u8(p, total_kb ? (uint64_t)free_kb * 100 / total_kb : 0);
    ble_set_status(status);
    ble_set_pending(pending > 0);
    ESP_LOGI(TAG, "advertised status: %d pending, battery %u mV, %u%% free", pending, battery,
             status[BLE_STATUS_LEN - 1]);
}

void sync_status_changed(void) {
    status_changed = true;
}

void sync_recording_saved(void) {
    status_changed = true;
    ble_advertise_pending(PENDING_SECONDS);
}

// A short advertising window, so the phone can connect and read the status (and sync).
static void check_in(int pending_seconds) {
    last_check_in_us = esp_timer_get_time();
    ble_wake(CHECK_IN_SECONDS);
    ble_advertise_pending(pending_seconds);
}

void sync_ask_for_check_in(void) {
    check_in_wanted = true;
    status_changed = true;
}

static void cmd_get_info(void) {
    if (check_in_wanted) {
        check_in_wanted = false;
        status_changed = true;
    }
    uint32_t total_kb, free_kb;
    store_usage(&total_kb, &free_kb);
    int pending = store_list(recordings, MAX_RECORDINGS);

    uint8_t packet[64];
    uint8_t *p = packet;
    p = put_u8(p, REPLY(CMD_GET_INFO));
    p = put_u8(p, OK);
    p = put_u8(p, PROTOCOL_VERSION);
    p = put_u32(p, flags());
    p = put_u16(p, pending);
    p = put_u16(p, battery_millivolts());
    p = put_u32(p, total_kb);
    p = put_u32(p, free_kb);
    p = put_u16(p, 0); // missedCount: nothing refuses a recording yet
    p = put_u32(p, esp_timer_get_time() / 1000000);
    p = put_u8(p, reset_reason());
    for (const char *c = FIRMWARE_VERSION; *c; c++) p = put_u8(p, *c);
    send(packet, p);
    ESP_LOGI(TAG, "INFO: %d pending, %lu KB free, signal %d dBm", pending, (unsigned long)free_kb, ble_rssi());
}

// Sets the clock, then gives a time to this power-up's recordings that were made before
// the clock was set (protocol section 6, rule 3).
static void cmd_set_time(uint32_t now) {
    clock_set(now);
    uint64_t uptime_now = clock_uptime_ms();
    int count = store_list(recordings, MAX_RECORDINGS);
    for (int i = 0; i < count; i++) {
        rec_info_t *r = &recordings[i];
        if (r->recorded_at != 0 || r->uptime_ms == REC_UPTIME_UNKNOWN) continue;
        r->recorded_at = now - (uptime_now - r->uptime_ms) / 1000;
        store_update(r);
        ESP_LOGI(TAG, "recording %lu: recorded %lu s ago", (unsigned long)r->id, (unsigned long)(now - r->recorded_at));
    }
    reply_status(CMD_SET_TIME, OK);
    status_changed = true; // the CLOCK_SET flag
    ESP_LOGI(TAG, "clock set to %lu", (unsigned long)now);
}

static void cmd_list(void) {
    int count = store_list(recordings, MAX_RECORDINGS);
    for (int i = 0; i < count; i++) {
        uint8_t packet[16];
        uint8_t *p = packet;
        p = put_u8(p, REPLY(CMD_LIST)); // ENTRY
        p = put_u8(p, OK);
        p = put_u32(p, recordings[i].id);
        p = put_u32(p, recordings[i].size);
        p = put_u32(p, recordings[i].recorded_at);
        p = put_u8(p, recordings[i].format);
        send(packet, p);
    }
    uint8_t packet[4];
    uint8_t *p = packet;
    p = put_u8(p, REPLY_LIST_END);
    p = put_u8(p, OK);
    p = put_u16(p, count);
    send(packet, p);
    ESP_LOGI(TAG, "LIST: %d recordings", count);
}

// GET_START, the audio in chunks on `data`, then GET_END (protocol section 5).
static void cmd_get(uint32_t id, uint32_t offset) {
    rec_info_t info;
    if (!store_info(id, &info)) {
        reply_status(CMD_GET, NOT_FOUND);
        return;
    }
    if (offset > info.size) {
        reply_status(CMD_GET, BAD_OFFSET);
        return;
    }
    FILE *f = store_open(id);
    if (!f || fseek(f, offset, SEEK_SET) != 0) {
        if (f) fclose(f);
        reply_status(CMD_GET, STORAGE_ERROR);
        return;
    }

    uint8_t start[14];
    uint8_t *p = start;
    p = put_u8(p, REPLY(CMD_GET)); // GET_START
    p = put_u8(p, OK);
    p = put_u32(p, id);
    p = put_u32(p, offset);
    p = put_u32(p, info.size);
    send(start, p);

    // Each chunk is [offset u32][audio], as big as the MTU allows, but no more than 512 bytes:
    // that's the most an attribute value may hold (Bluetooth Core spec), and Android drops
    // longer notifications (BlueZ doesn't mind, which is why tools/sync.py never noticed).
    static uint8_t chunk[MAX_CHUNK];
    size_t payload = ble_max_payload();
    if (payload > MAX_CHUNK) payload = MAX_CHUNK;
    size_t room = payload - 4;
    int64_t began = esp_timer_get_time();
    uint32_t at = offset;
    while (at < info.size) {
        size_t n = fread(chunk + 4, 1, room, f);
        if (n == 0) break; // the file is shorter than its .meta says; the phone will notice
        put_u32(chunk, at);
        if (!ble_notify_data(chunk, 4 + n)) {
            ESP_LOGW(TAG, "GET %lu stopped at %lu of %lu bytes: phone gone", (unsigned long)id,
                     (unsigned long)at, (unsigned long)info.size);
            fclose(f);
            return;
        }
        at += n;
    }
    fclose(f);

    uint8_t end[10];
    p = end;
    p = put_u8(p, REPLY_GET_END);
    p = put_u8(p, OK);
    p = put_u32(p, id);
    p = put_u32(p, info.crc);
    send(end, p);

    float seconds = (esp_timer_get_time() - began) / 1e6f;
    ESP_LOGI(TAG, "GET %lu from %lu: %lu bytes in %.1f s (%.0f KB/s, %u bytes per chunk)", (unsigned long)id,
             (unsigned long)offset, (unsigned long)(at - offset), seconds,
             seconds > 0 ? (at - offset) / 1024.0f / seconds : 0, room);
}

static void cmd_delete(uint32_t id) {
    bool deleted = store_delete(id);
    reply_status(CMD_DELETE, deleted ? OK : NOT_FOUND);
    // Right away, not on the next loop: if this was the last one, advertising must already be
    // off when the phone disconnects.
    if (deleted) update_advertised_status();
    ESP_LOGI(TAG, "DELETE %lu: %s", (unsigned long)id, deleted ? "done" : "not found");
}

// Payload bytes after the opcode, or -1 for a command we don't know.
static int payload_length(uint8_t cmd) {
    switch (cmd) {
    case CMD_GET_INFO:
    case CMD_LIST: return 0;
    case CMD_SET_TIME:
    case CMD_DELETE: return 4;
    case CMD_GET: return 8;
    default: return -1;
    }
}

static void handle(const ble_command_t *c) {
    if (c->len == 0) return;
    uint8_t cmd = c->bytes[0];
    const uint8_t *payload = c->bytes + 1;
    int expected = payload_length(cmd);
    if (expected < 0) {
        reply_status(cmd, UNKNOWN_COMMAND);
        return;
    }
    if (c->len - 1 != expected) {
        reply_status(cmd, BAD_LENGTH);
        return;
    }

    switch (cmd) {
    case CMD_GET_INFO: cmd_get_info(); break;
    case CMD_SET_TIME: cmd_set_time(get_u32(payload)); break;
    case CMD_LIST: cmd_list(); break;
    case CMD_GET: cmd_get(get_u32(payload), get_u32(payload + 4)); break;
    case CMD_DELETE: cmd_delete(get_u32(payload)); break;
    }
}

static void sync_task(void *arg) {
    QueueHandle_t commands = arg;
    bool was_connected = false;
    int64_t last_command_us = 0;
    int64_t last_status_us = 0;

    for (;;) {
        ble_command_t c;
        // Wake at least once a second to check for an idle phone and a changed status.
        bool got = xQueueReceive(commands, &c, pdMS_TO_TICKS(1000)) == pdTRUE;
        if (status_changed || esp_timer_get_time() - last_status_us > STATUS_REFRESH_US) {
            status_changed = false;
            last_status_us = esp_timer_get_time();
            update_advertised_status();
        }
        if (esp_timer_get_time() - last_check_in_us > CHECK_IN_US) check_in(PENDING_CHECK_IN_SECONDS);
        ble_update_advertising(); // stops it once an awake window or pairing mode runs out
        bool connected = ble_connected();
        if (connected && !was_connected) last_command_us = esp_timer_get_time();
        was_connected = connected;

        if (got) {
            handle(&c);
            last_command_us = esp_timer_get_time(); // counted from the end of a long GET
        } else if (connected && !ble_dev_connected() && esp_timer_get_time() - last_command_us > IDLE_TIMEOUT_US) {
            // (Not the laptop in dev mode: its commands and updates go to dev.c, not here.)
            ESP_LOGI(TAG, "no command for 30 s: disconnecting");
            ble_disconnect();
            last_command_us = esp_timer_get_time();
        }
    }
}

void sync_start(void) {
    QueueHandle_t commands = xQueueCreate(4, sizeof(ble_command_t));
    // Below the recorder and the writer: a sync can wait, the mic can't.
    xTaskCreate(sync_task, "sync", 4096, commands, 5, NULL);
    // The status before the first advert: until then it's all zeros, which the app read as
    // "0 mV, 0 % free" and warned about storage.
    update_advertised_status();
    // The first check-in, before the sleep task starts (main.c). Waking for the 30-min timer,
    // recordings that are still waiting get the short window; otherwise (power-up, the
    // button) the long one.
    check_in(sleep_woke_by_timer() ? PENDING_CHECK_IN_SECONDS : PENDING_SECONDS);
    ble_start(commands);
}
