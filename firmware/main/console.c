#include "console.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "driver/usb_serial_jtag.h"
#include "driver/usb_serial_jtag_vfs.h"
#include "esp_ota_ops.h"
#include "esp_pm.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "battery.h"
#include "ble.h"
#include "haptic.h"
#include "sleep.h"
#include "store.h"

// The output of the command running now (commands run one at a time, under `busy`).
static console_out_t out;
static SemaphoreHandle_t busy;

static void say(const char *format, ...) {
    char text[600];
    va_list args;
    va_start(args, format);
    int n = vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    if (n > 0) out(text, n < (int)sizeof(text) ? n : sizeof(text) - 1);
}

static const char BASE64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

// Base64 turns 3 bytes into 4 printable characters, so audio can travel in text lines
// alongside the log.
static void print_base64(const uint8_t *in, size_t len) {
    char text[4 * 128 + 1];
    size_t o = 0;
    for (size_t i = 0; i < len; i += 3) {
        uint32_t v = in[i] << 16;
        if (i + 1 < len) v |= in[i + 1] << 8;
        if (i + 2 < len) v |= in[i + 2];
        text[o++] = BASE64[(v >> 18) & 63];
        text[o++] = BASE64[(v >> 12) & 63];
        text[o++] = i + 1 < len ? BASE64[(v >> 6) & 63] : '=';
        text[o++] = i + 2 < len ? BASE64[v & 63] : '=';
    }
    text[o] = 0;
    say("D %s\n", text);
}

static void cmd_ls(void) {
    static rec_info_t list[256];
    int count = store_list(list, 256);
    for (int i = 0; i < count; i++) {
        say("REC %lu %lu %lu %08lx %u\n", (unsigned long)list[i].id, (unsigned long)list[i].size,
            (unsigned long)list[i].recorded_at, (unsigned long)list[i].crc, list[i].format);
    }
    say("LS_END %d\n", count);
}

static void cmd_get(uint32_t id) {
    rec_info_t info;
    FILE *f = store_info(id, &info) ? store_open(id) : NULL;
    if (!f) {
        say("ERR not found\n");
        return;
    }
    say("BEGIN %lu %lu %08lx\n", (unsigned long)id, (unsigned long)info.size, (unsigned long)info.crc);
    static uint8_t buffer[3 * 128]; // a multiple of 3, so lines need no '=' padding mid-file
    size_t n;
    while ((n = fread(buffer, 1, sizeof(buffer), f)) > 0) print_base64(buffer, n);
    fclose(f);
    say("END\n");
}

void console_run(const char *line, console_out_t output) {
    xSemaphoreTake(busy, portMAX_DELAY);
    out = output;
    unsigned long id;
    if (strcmp(line, "ls") == 0) {
        cmd_ls();
    } else if (sscanf(line, "get %lu", &id) == 1) {
        cmd_get(id);
    } else if (sscanf(line, "rm %lu", &id) == 1) {
        say(store_delete(id) ? "OK\n" : "ERR not found\n");
    } else if (strcmp(line, "df") == 0) {
        uint32_t total_kb, free_kb;
        store_usage(&total_kb, &free_kb);
        say("DF %lu %lu %s\n", (unsigned long)total_kb, (unsigned long)free_kb, store_on_card() ? "sd" : "flash");
    } else if (strcmp(line, "bat") == 0) {
        say("BAT %u\n", battery_millivolts());
    } else if (sscanf(line, "buzz %lu", &id) == 1 || strcmp(line, "buzz") == 0) {
        haptic_buzz(strcmp(line, "buzz") == 0 ? 300 : (int)id); // id holds the ms here
        say("OK\n");
    } else if (strcmp(line, "boot") == 0) {
        // Which bootloader is on the board (tools/dev.py bootloader compares it).
        esp_bootloader_desc_t desc;
        if (esp_ota_get_bootloader_description(NULL, &desc) == ESP_OK) {
            say("BOOT %s, built %s\n", desc.idf_ver, desc.date_time);
        } else {
            say("ERR no bootloader description\n");
        }
    } else if (strcmp(line, "adv") == 0) {
        char state[200];
        ble_format_state(state, sizeof(state));
        say("%s\n", state);
        sleep_format_state(state, sizeof(state));
        say("%s\n", state);
    } else if (strcmp(line, "pm") == 0) {
        // Light sleep at work: ESP-IDF's own report of the time spent in each
        // power mode since the start ("SLEEP" is napping) and which drivers kept it awake.
        static char report[1536];
        FILE *f = fmemopen(report, sizeof(report) - 1, "w");
        if (f) {
            esp_pm_dump_locks(f);
            long n = ftell(f);
            fclose(f);
            report[n] = 0;
            for (char *l = strtok(report, "\n"); l; l = strtok(NULL, "\n")) say("PM %s\n", l);
        }
        say("PM_END\n");
    } else if (strcmp(line, "crash") == 0) {
        // A deliberate panic: the board restarts with reset reason "crash", for testing
        // the app's warning.
        say("OK crashing\n");
        out(NULL, 0);
        fflush(stdout);
        vTaskDelay(pdMS_TO_TICKS(300)); // lets the reply go out over Bluetooth
        abort();
    } else if (sscanf(line, "sleep %lu", &id) == 1 || strcmp(line, "sleep") == 0) {
        // Deep sleep until the button, or `id` seconds (for measuring the wake-up).
        const char *refusal = sleep_refusal();
        if (refusal) {
            say("ERR %s\n", refusal);
        } else {
            say("OK sleeping\n");
            out(NULL, 0);
            fflush(stdout);
            vTaskDelay(pdMS_TO_TICKS(300)); // lets the reply go out over Bluetooth
            sleep_deep(strcmp(line, "sleep") == 0 ? 0 : id);
        }
    } else if (line[0]) {
        say("ERR commands: ls, get <id>, rm <id>, df, bat, buzz, adv, pm, boot, crash, sleep [s]\n");
    }
    out(NULL, 0);
    xSemaphoreGive(busy);
}

static void to_usb(const char *text, size_t len) {
    if (text) fwrite(text, 1, len, stdout);
}

static void console_task(void *arg) {
    char line[64];
    for (;;) {
        if (!fgets(line, sizeof(line), stdin)) {
            vTaskDelay(pdMS_TO_TICKS(50));
            continue;
        }
        line[strcspn(line, "\r\n")] = 0;
        console_run(line, to_usb);
        fflush(stdout);
    }
}

void console_start(void) {
    // Without the driver, reading from the USB port doesn't wait for input. With it,
    // stdin/stdout become a normal blocking serial port.
    usb_serial_jtag_driver_config_t config = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
    usb_serial_jtag_driver_install(&config);
    usb_serial_jtag_vfs_use_driver();
    setvbuf(stdin, NULL, _IONBF, 0);

    busy = xSemaphoreCreateMutex();
    xTaskCreate(console_task, "console", 4096, NULL, 3, NULL);
}
