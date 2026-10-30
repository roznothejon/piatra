#include "dev.h"

#include <stdint.h>
#include <string.h>
#include "ble.h"
#include "clock.h"
#include "console.h"
#include "esp_app_desc.h"
#include "esp_attr.h"
#include "esp_flash_partitions.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "haptic.h"
#include "logbuf.h"

static const char *TAG = "dev";

#define PAIRING_WINDOW_MS (2 * 60 * 1000) // after the gesture, for the laptop to pair
#define GRACE_MS (5 * 60 * 1000)          // after the laptop disconnects, for it to come back
#define TRIAL_MS (5 * 60 * 1000)          // for a new firmware to be confirmed
// After a wake from deep sleep, for the laptop to reconnect and prove a newly confirmed
// firmware (below). Shorter than GRACE_MS, so the firmware it goes back to still has dev mode on.
#define PROOF_MS (3 * 60 * 1000)
#define PROOF_MARKER 0x50524631           // "PRF1"
#define STATE_MARKER 0x44455631           // "DEV1"
// Only firmware for this project is accepted (the `project()` name in firmware/CMakeLists.txt).
#define PROJECT_NAME "piatra-board"
// A new bootloader is received into free flash between phy_init and ota_0
// (partitions.csv), checked there, and only then copied over the real one at 0x0.
#define BOOTLOADER_STAGING 0x12000
// What OTA_BEGIN's optional last byte asks for.
enum { TARGET_FIRMWARE = 0, TARGET_BOOTLOADER = 1 };

// Commands on the dev characteristic (protocol section 12). Replies, like on `control`, are
// [command | 0x80][status][payload].
enum { CMD_INFO = 0x01, CMD_CONFIRM = 0x02, CMD_OTA_BEGIN = 0x03, CMD_OTA_DATA = 0x04,
       CMD_OTA_END = 0x05, CMD_ROLLBACK = 0x06, CMD_DONE = 0x07, CMD_CONSOLE = 0x08, CMD_LOG = 0x09 };
// Text from the board, sent unasked: log lines (logbuf.c), and a console command's output.
#define CONSOLE_PACKET 0x41
#define REPLY(cmd) (0x80 | (cmd))
enum { OK = 0x00, UNKNOWN_COMMAND = 0x01, BAD_LENGTH = 0x02, NOT_FOUND = 0x03, BAD_OFFSET = 0x04,
       BUSY = 0x05, STORAGE_ERROR = 0x06, BAD_IMAGE = 0x07 };

// Everything that changes dev mode goes through one queue to the dev task, so only that
// task touches the state below.
typedef enum { MSG_COMMAND, MSG_GESTURE, MSG_LINK_UP, MSG_LINK_DOWN } msg_kind_t;
typedef struct {
    msg_kind_t kind;
    uint16_t len;
    uint8_t bytes[65]; // a console command line is the longest
} msg_t;

// In RTC memory, so dev mode survives the restart after a firmware update (a power cut ends
// it). Times are clock_uptime_ms(), which keeps counting through restarts; 0 = not set.
typedef struct {
    uint32_t marker;
    bool on;                   // a laptop has paired with the code
    uint64_t pairing_until_ms; // the gesture's pairing window
    uint64_t grace_until_ms;   // the laptop has gone: dev mode ends then
    // Added later, after the fields above, so an older firmware (which only knows
    // those) leaves them alone; their own marker tells if they're set.
    uint32_t proof_marker;
    bool proof_pending;        // confirmed, but not yet proven to come back after a deep sleep
    uint64_t proof_until_ms;   // woke from deep sleep while unproven: the laptop must be back by then
} dev_state_t;
RTC_NOINIT_ATTR static dev_state_t state;

// Copies for other tasks (NimBLE's), which only read them.
static volatile bool active, pairing_open;
static QueueHandle_t messages;
static uint64_t trial_until_ms; // the running firmware is on trial until then; 0 = confirmed

// The update being received. OTA_DATA is written from NimBLE's task (it comes too fast for
// the queue), BEGIN and END here; the laptop waits for each reply, so they never overlap.
static esp_ota_handle_t ota;
static const esp_partition_t *volatile ota_slot; // NULL: no update under way
static volatile uint32_t ota_size, ota_written;
static volatile bool ota_failed;
// For a bootloader update: the two areas, which aren't in the partition table, registered
// while it's under way so the OTA functions can work on them (NULL otherwise).
static const esp_partition_t *boot_primary, *boot_staging;
// Copying a new bootloader failed: the board still runs, but couldn't start again, so it
// stays awake (no deep sleep: waking is a restart) for the laptop to try again.
static bool bootloader_broken;

static uint32_t get_u32(const uint8_t *at) {
    return at[0] | at[1] << 8 | at[2] << 16 | (uint32_t)at[3] << 24;
}

static void reply(uint8_t cmd, uint8_t status) {
    uint8_t packet[2] = { REPLY(cmd), status };
    ble_notify_dev(packet, sizeof(packet));
}

// After a change to `state`: update the copies, and tell Bluetooth (pairing, advertising).
static void publish(void) {
    pairing_open = state.pairing_until_ms > clock_uptime_ms();
    // Waiting for the laptop to prove a firmware counts too: awake and advertising.
    active = state.on || pairing_open || state.proof_until_ms || bootloader_broken;
    ble_dev_mode_changed();
}

static void end_dev_mode(const char *why) {
    bool was_active = active;
    state.on = false;
    state.pairing_until_ms = 0;
    state.grace_until_ms = 0;
    publish();
    // The laptop's bond stays: it can open dev mode again by connecting (ble.c).
    if (ble_dev_connected()) ble_disconnect();
    if (was_active) {
        haptic_buzz(200);
        ESP_LOGI(TAG, "dev mode off: %s", why);
    }
}

// Lets the reply go out and the laptop see the disconnect, then restarts.
static void restart_soon(void) {
    vTaskDelay(pdMS_TO_TICKS(300));
    ble_disconnect();
    vTaskDelay(pdMS_TO_TICKS(300));
    esp_restart();
}

// [reply][status][onTrial u8][slot u8][maxWrite u16][elfSha256 32 bytes][version, UTF-8]
static void cmd_info(void) {
    const esp_app_desc_t *app = esp_app_get_description();
    const esp_partition_t *running = esp_ota_get_running_partition();
    size_t max_write = ble_max_payload();
    uint8_t packet[6 + 32 + sizeof(app->version)];
    uint8_t *p = packet;
    *p++ = REPLY(CMD_INFO);
    *p++ = OK;
    *p++ = trial_until_ms != 0;
    *p++ = running->subtype - ESP_PARTITION_SUBTYPE_APP_OTA_0;
    *p++ = max_write;
    *p++ = max_write >> 8;
    memcpy(p, app->app_elf_sha256, 32); // the build's fingerprint: tools/dev.py compares it
    p += 32;
    size_t n = strnlen(app->version, sizeof(app->version));
    memcpy(p, app->version, n);
    ble_notify_dev(packet, p + n - packet);
}

static void cmd_confirm(void) {
    if (trial_until_ms) {
        esp_ota_mark_app_valid_cancel_rollback();
        trial_until_ms = 0;
        // The bootloader's trial can't include a deep sleep (waking up is a restart, which
        // would roll it back), so a second, our own: the firmware stays only once the laptop
        // has come back after a real wake from deep sleep (tools/dev.py does `sleep 5`).
        // A firmware that sleeps but can't be reached afterwards could never be replaced.
        state.proof_marker = PROOF_MARKER;
        state.proof_pending = true;
        state.proof_until_ms = 0;
        ESP_LOGI(TAG, "firmware confirmed; it stays once the laptop is back after a deep sleep");
    }
    reply(CMD_CONFIRM, OK);
}

static void forget_bootloader_areas(void) {
    if (boot_staging) esp_partition_deregister_external(boot_staging);
    if (boot_primary) esp_partition_deregister_external(boot_primary);
    boot_staging = boot_primary = NULL;
}

// Like a firmware update, but into the staging area. Only from a firmware that is confirmed
// and proven (it can take the next update), so a bad moment can always be retried.
static void begin_bootloader(uint32_t size) {
    if (trial_until_ms || state.proof_pending) {
        reply(CMD_OTA_BEGIN, BUSY);
        return;
    }
    if (size == 0 || size > ESP_BOOTLOADER_SIZE) {
        reply(CMD_OTA_BEGIN, BAD_LENGTH);
        return;
    }
    esp_err_t err = esp_partition_register_external(NULL, ESP_PRIMARY_BOOTLOADER_OFFSET, ESP_BOOTLOADER_SIZE,
        "bootloader", ESP_PARTITION_TYPE_BOOTLOADER, ESP_PARTITION_SUBTYPE_BOOTLOADER_PRIMARY, &boot_primary);
    if (err == ESP_OK) {
        err = esp_partition_register_external(NULL, BOOTLOADER_STAGING, ESP_BOOTLOADER_SIZE, "new bootloader",
            ESP_PARTITION_TYPE_BOOTLOADER, ESP_PARTITION_SUBTYPE_BOOTLOADER_OTA, &boot_staging);
    }
    // Size 0 erases all of the staging area, so afterwards it holds the new image and nothing else.
    if (err == ESP_OK) err = esp_ota_begin(boot_staging, 0, &ota);
    if (err == ESP_OK) {
        // false: we copy it ourselves (cmd_ota_end), after our own checks.
        err = esp_ota_set_final_partition(ota, boot_primary, false);
        if (err != ESP_OK) esp_ota_abort(ota);
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "bootloader update didn't start: %s", esp_err_to_name(err));
        forget_bootloader_areas();
        reply(CMD_OTA_BEGIN, STORAGE_ERROR);
        return;
    }
    ota_size = size;
    ota_written = 0;
    ota_failed = false;
    ota_slot = boot_staging;
    reply(CMD_OTA_BEGIN, OK);
    ESP_LOGI(TAG, "receiving a new bootloader: %lu bytes", (unsigned long)size);
}

static void cmd_ota_begin(uint32_t size, uint8_t target) {
    if (ota_slot) {
        esp_ota_abort(ota); // an earlier upload that never finished
        ota_slot = NULL;
        forget_bootloader_areas();
    }
    if (target == TARGET_BOOTLOADER) {
        begin_bootloader(size);
        return;
    }
    if (target != TARGET_FIRMWARE) {
        reply(CMD_OTA_BEGIN, UNKNOWN_COMMAND);
        return;
    }
    const esp_partition_t *slot = esp_ota_get_next_update_partition(NULL);
    if (!slot || size == 0 || size > slot->size) {
        reply(CMD_OTA_BEGIN, BAD_LENGTH);
        return;
    }
    // Erases as much of the other slot as the new firmware needs (about a second).
    esp_err_t err = esp_ota_begin(slot, size, &ota);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "update didn't start: %s", esp_err_to_name(err));
        reply(CMD_OTA_BEGIN, STORAGE_ERROR);
        return;
    }
    ota_size = size;
    ota_written = 0;
    ota_failed = false;
    ota_slot = slot;
    reply(CMD_OTA_BEGIN, OK);
    ESP_LOGI(TAG, "receiving a new firmware: %lu bytes into %s", (unsigned long)size, slot->label);
}

// [offset u32][bytes]. Runs on NimBLE's task. Writes arrive in order; a gap means a lost one.
static bool ota_data(const uint8_t *p, size_t len) {
    if (!ota_slot || ota_failed || len <= 4) return false;
    uint32_t offset = get_u32(p);
    size_t n = len - 4;
    if (offset != ota_written || offset + n > ota_size) {
        ESP_LOGW(TAG, "update: got bytes at %lu, expected %lu", (unsigned long)offset, (unsigned long)ota_written);
        ota_failed = true;
        return false;
    }
    if (esp_ota_write(ota, p + 4, n) != ESP_OK) {
        ota_failed = true;
        return false;
    }
    ota_written += n;
    return true;
}

static bool same_contents(const esp_partition_t *a, const esp_partition_t *b) {
    static uint8_t in_a[512], in_b[512];
    for (size_t at = 0; at < a->size; at += sizeof(in_a)) {
        if (esp_partition_read(a, at, in_a, sizeof(in_a)) != ESP_OK ||
            esp_partition_read(b, at, in_b, sizeof(in_b)) != ESP_OK ||
            memcmp(in_a, in_b, sizeof(in_a)) != 0) {
            return false;
        }
    }
    return true;
}

// Checks the received bootloader, then copies it over the real one. Returns OK (restart
// into it), BAD_IMAGE (refused: nothing was changed) or STORAGE_ERROR (copy failed).
static uint8_t finish_bootloader(void) {
    // esp_ota_end checks the image (its checksum and SHA-256). Then it must be built like the
    // one it replaces: the same ESP-IDF, and the same flash settings, which the chip's ROM
    // reads from header bytes 2 and 3 (mode; size and speed): wrong ones and nothing starts.
    esp_bootloader_desc_t new_desc, old_desc;
    uint8_t new_head[4], old_head[4];
    esp_err_t err = esp_ota_end(ota);
    if (err == ESP_OK) err = esp_ota_get_bootloader_description(boot_staging, &new_desc);
    if (err == ESP_OK) err = esp_ota_get_bootloader_description(boot_primary, &old_desc);
    if (err == ESP_OK) err = esp_partition_read(boot_staging, 0, new_head, sizeof(new_head));
    if (err == ESP_OK) err = esp_partition_read(boot_primary, 0, old_head, sizeof(old_head));
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "new bootloader rejected: %s", esp_err_to_name(err));
        return BAD_IMAGE;
    }
    if (strcmp(new_desc.idf_ver, old_desc.idf_ver) != 0 || memcmp(new_head + 2, old_head + 2, 2) != 0) {
        ESP_LOGW(TAG, "new bootloader rejected: ESP-IDF %s, flash %02x %02x (this one: %s, %02x %02x)",
                 new_desc.idf_ver, new_head[2], new_head[3], old_desc.idf_ver, old_head[2], old_head[3]);
        return BAD_IMAGE;
    }
    // The only risky moment: from here until the copy is done (well under a second), a power
    // cut or a restart leaves a board that can't start, and the case has to come open for USB.
    ESP_LOGW(TAG, "writing the new bootloader (built %s)", new_desc.date_time);
    for (int attempt = 1; attempt <= 3; attempt++) {
        err = esp_partition_copy(boot_primary, 0, boot_staging, 0, SIZE_MAX);
        if (err == ESP_OK && same_contents(boot_primary, boot_staging)) {
            ESP_LOGI(TAG, "new bootloader written and read back");
            return OK;
        }
        ESP_LOGE(TAG, "bootloader copy %d failed: %s", attempt, err != ESP_OK ? esp_err_to_name(err) : "differs");
    }
    bootloader_broken = true;
    publish();
    ESP_LOGE(TAG, "the bootloader is broken: don't let the board restart; try again");
    return STORAGE_ERROR;
}

static void cmd_ota_end(void) {
    const esp_partition_t *slot = ota_slot;
    if (!slot) {
        reply(CMD_OTA_END, NOT_FOUND);
        return;
    }
    ota_slot = NULL;
    if (ota_failed || ota_written != ota_size) {
        esp_ota_abort(ota);
        forget_bootloader_areas();
        ESP_LOGW(TAG, "update incomplete: %lu of %lu bytes", (unsigned long)ota_written, (unsigned long)ota_size);
        reply(CMD_OTA_END, BAD_OFFSET);
        return;
    }
    if (slot == boot_staging) {
        uint8_t status = finish_bootloader();
        forget_bootloader_areas();
        reply(CMD_OTA_END, status);
        if (status == OK) {
            bootloader_broken = false;
            ESP_LOGI(TAG, "restarting through the new bootloader");
            restart_soon();
        }
        return;
    }
    // esp_ota_end checks the image (its SHA-256 checksum); then make sure it's ours.
    esp_app_desc_t desc;
    esp_err_t err = esp_ota_end(ota);
    if (err == ESP_OK) err = esp_ota_get_partition_description(slot, &desc);
    if (err != ESP_OK || strcmp(desc.project_name, PROJECT_NAME) != 0) {
        ESP_LOGW(TAG, "update rejected: %s", err != ESP_OK ? esp_err_to_name(err) : desc.project_name);
        reply(CMD_OTA_END, BAD_IMAGE);
        return;
    }
    if (esp_ota_set_boot_partition(slot) != ESP_OK) {
        reply(CMD_OTA_END, STORAGE_ERROR);
        return;
    }
    reply(CMD_OTA_END, OK);
    ESP_LOGI(TAG, "new firmware %s in %s: restarting into it (on trial)", desc.version, slot->label);
    restart_soon();
}

// Makes the firmware in the other slot the one to start (on trial: tools/dev.py confirms
// it). Returns OK, NOT_FOUND (empty, or a firmware that failed its trial) or STORAGE_ERROR.
static uint8_t switch_to_other_slot(void) {
    const esp_partition_t *other = esp_ota_get_next_update_partition(NULL);
    esp_app_desc_t desc;
    esp_ota_img_states_t other_state = ESP_OTA_IMG_UNDEFINED;
    if (other) esp_ota_get_state_partition(other, &other_state);
    if (!other || esp_ota_get_partition_description(other, &desc) != ESP_OK ||
        other_state == ESP_OTA_IMG_INVALID || other_state == ESP_OTA_IMG_ABORTED) {
        return NOT_FOUND;
    }
    if (esp_ota_set_boot_partition(other) != ESP_OK) return STORAGE_ERROR;
    ESP_LOGI(TAG, "going back to %s in %s", desc.version, other->label);
    state.proof_pending = false; // that one was proven (or is from before the proof)
    state.proof_until_ms = 0;
    return OK;
}

// Back to the firmware in the other slot. A firmware still on trial goes back the way a
// failed one would; a confirmed one starts the other slot on trial (tools/dev.py confirms).
static void cmd_rollback(void) {
    if (trial_until_ms) {
        reply(CMD_ROLLBACK, OK);
        ESP_LOGI(TAG, "rolling back: this firmware is marked bad");
        vTaskDelay(pdMS_TO_TICKS(300));
        esp_ota_mark_app_invalid_rollback_and_reboot();
        return;
    }
    uint8_t status = switch_to_other_slot();
    reply(CMD_ROLLBACK, status);
    if (status == OK) restart_soon();
}

// A console command's output goes to the laptop in [0x41][text] packets, as full as the MTU
// allows; the CONSOLE reply follows once it has all gone.
static uint8_t console_packet[515];
static size_t console_len;

static void console_to_laptop(const char *text, size_t len) {
    size_t room = ble_max_payload();
    if (room > sizeof(console_packet)) room = sizeof(console_packet);
    console_packet[0] = CONSOLE_PACKET;
    if (console_len == 0) console_len = 1;
    while (len > 0) {
        size_t n = room - console_len < len ? room - console_len : len;
        memcpy(console_packet + console_len, text, n);
        console_len += n;
        text += n;
        len -= n;
        if (console_len == room) {
            ble_notify_dev(console_packet, console_len);
            console_len = 1;
        }
    }
    if (!text && console_len > 1) { // (NULL, 0): send what's left
        ble_notify_dev(console_packet, console_len);
        console_len = 1;
    }
}

// [CONSOLE][command line, UTF-8]: runs it like on the USB console (console.h).
static void cmd_console(const msg_t *m) {
    char line[sizeof(m->bytes)];
    memcpy(line, m->bytes + 1, m->len - 1);
    line[m->len - 1] = 0;
    console_len = 0;
    console_run(line, console_to_laptop);
    reply(CMD_CONSOLE, OK);
}

// [LOG][flags u8]: bit 0 = the history kept in the buffer, bit 1 = then new lines as they come.
static void cmd_log(uint8_t flags) {
    reply(CMD_LOG, OK);
    logbuf_send(flags & 1, flags & 2);
}

static void handle_command(const msg_t *m) {
    if (m->len == 0) return;
    uint8_t cmd = m->bytes[0];
    if (cmd < CMD_INFO || cmd > CMD_LOG || cmd == CMD_OTA_DATA) {
        reply(cmd, UNKNOWN_COMMAND);
        return;
    }
    if (cmd == CMD_CONSOLE) {
        if (m->len < 2 || m->len > sizeof(m->bytes)) {
            reply(cmd, BAD_LENGTH);
        } else {
            cmd_console(m);
        }
        return;
    }
    // OTA_BEGIN: [size u32], plus [target u8] for a bootloader.
    size_t expected = cmd == CMD_OTA_BEGIN ? 5 : cmd == CMD_LOG ? 2 : 1;
    if (m->len != expected && !(cmd == CMD_OTA_BEGIN && m->len == 6)) {
        reply(cmd, BAD_LENGTH);
        return;
    }
    switch (cmd) {
    case CMD_INFO: cmd_info(); break;
    case CMD_CONFIRM: cmd_confirm(); break;
    case CMD_OTA_BEGIN: cmd_ota_begin(get_u32(m->bytes + 1), m->len == 6 ? m->bytes[5] : TARGET_FIRMWARE); break;
    case CMD_OTA_END: cmd_ota_end(); break;
    case CMD_ROLLBACK: cmd_rollback(); break;
    case CMD_LOG: cmd_log(m->bytes[1]); break;
    case CMD_DONE:
        reply(CMD_DONE, OK);
        vTaskDelay(pdMS_TO_TICKS(300));
        end_dev_mode("tools/dev.py done");
        break;
    }
}

static void check_deadlines(void) {
    uint64_t now = clock_uptime_ms();
    if (state.pairing_until_ms && now >= state.pairing_until_ms) {
        state.pairing_until_ms = 0;
        if (state.on) {
            publish();
        } else {
            end_dev_mode("no laptop paired within 2 min");
        }
    }
    if (state.on && state.grace_until_ms && now >= state.grace_until_ms) {
        end_dev_mode("the laptop didn't come back within 5 min");
    }
    if (trial_until_ms && now >= trial_until_ms) {
        ESP_LOGE(TAG, "new firmware not confirmed within 5 min: going back to the previous one");
        esp_ota_mark_app_invalid_rollback_and_reboot();
    }
    if (state.proof_until_ms && now >= state.proof_until_ms) {
        ESP_LOGE(TAG, "the laptop didn't come back after a deep sleep: going back to the previous firmware");
        if (switch_to_other_slot() == OK) {
            restart_soon();
        } else {
            state.proof_until_ms = 0; // nothing to go back to: keep this one
        }
    }
}

static void dev_task(void *arg) {
    for (;;) {
        msg_t m;
        // Wake at least once a second for the deadlines.
        if (xQueueReceive(messages, &m, pdMS_TO_TICKS(1000)) == pdTRUE) {
            switch (m.kind) {
            case MSG_COMMAND: handle_command(&m); break;
            case MSG_GESTURE:
                state.pairing_until_ms = clock_uptime_ms() + PAIRING_WINDOW_MS;
                publish();
                ESP_LOGI(TAG, "dev mode: a laptop may pair with the code for 2 min (tools/dev.py pair)");
                break;
            case MSG_LINK_UP:
                if (state.proof_until_ms) {
                    state.proof_pending = false;
                    state.proof_until_ms = 0;
                    ESP_LOGI(TAG, "the laptop is back after a deep sleep: this firmware is proven");
                }
                state.on = true;
                state.pairing_until_ms = 0;
                state.grace_until_ms = 0;
                publish();
                ESP_LOGI(TAG, "laptop connected (dev mode on)");
                break;
            case MSG_LINK_DOWN:
                logbuf_stop();
                if (state.on) {
                    state.grace_until_ms = clock_uptime_ms() + GRACE_MS;
                    ESP_LOGI(TAG, "laptop gone: dev mode ends in 5 min unless it comes back");
                }
                break;
            }
        }
        check_deadlines();
    }
}

static void send_message(msg_kind_t kind) {
    msg_t m = { .kind = kind };
    xQueueSend(messages, &m, 0);
}

void dev_gesture(void) {
    send_message(MSG_GESTURE);
}

void dev_link_up(void) {
    send_message(MSG_LINK_UP);
}

void dev_link_down(void) {
    send_message(MSG_LINK_DOWN);
}

bool dev_mode_active(void) {
    return active;
}

bool dev_pairing_open(void) {
    return pairing_open;
}

bool dev_write(const uint8_t *bytes, size_t len) {
    if (len == 0) return false;
    if (bytes[0] == CMD_OTA_DATA) return ota_data(bytes + 1, len - 1);
    if (len > sizeof(((msg_t *)0)->bytes)) return false; // too long for any command
    msg_t m = { .kind = MSG_COMMAND, .len = len };
    memcpy(m.bytes, bytes, len);
    return xQueueSend(messages, &m, 0) == pdTRUE;
}

void dev_start(void) {
    if (state.marker != STATE_MARKER || clock_power_was_lost()) {
        state = (dev_state_t){ .marker = STATE_MARKER, .proof_marker = PROOF_MARKER };
    }
    if (state.proof_marker != PROOF_MARKER) {
        state.proof_marker = PROOF_MARKER; // written by an older firmware: not set
        state.proof_pending = false;
        state.proof_until_ms = 0;
    }
    if (state.on) {
        // Restarted in dev mode (after an update): the laptop has 5 min to reconnect.
        state.grace_until_ms = clock_uptime_ms() + GRACE_MS;
        ESP_LOGI(TAG, "dev mode still on after the restart");
    }
    if (state.proof_pending && esp_reset_reason() == ESP_RST_DEEPSLEEP) {
        state.proof_until_ms = clock_uptime_ms() + PROOF_MS;
        ESP_LOGW(TAG, "woke from deep sleep with a firmware not yet proven: the laptop has 3 min to "
                 "reconnect, or the board goes back to the previous firmware");
    }
    publish();

    const esp_app_desc_t *app = esp_app_get_description();
    const esp_partition_t *running = esp_ota_get_running_partition();
    esp_ota_img_states_t img_state;
    if (esp_ota_get_state_partition(running, &img_state) == ESP_OK && img_state == ESP_OTA_IMG_PENDING_VERIFY) {
        trial_until_ms = clock_uptime_ms() + TRIAL_MS;
        ESP_LOGW(TAG, "firmware %s in %s is on trial: tools/dev.py confirms it, or in 5 min the board goes "
                 "back to the previous one", app->version, running->label);
    } else {
        ESP_LOGI(TAG, "firmware %s in %s", app->version, running->label);
    }

    messages = xQueueCreate(8, sizeof(msg_t));
    xTaskCreate(dev_task, "dev", 6144, NULL, 4, NULL); // room for the console's commands
}
