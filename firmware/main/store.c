#include "store.h"

#include <dirent.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h> // truncate
#include "board.h"
#include "clock.h"
#include "driver/sdspi_host.h"
#include "esp_littlefs.h"
#include "esp_log.h"
#include "esp_rom_crc.h"
#include "esp_vfs_fat.h"
#include "sdmmc_cmd.h"
#include "nvs.h"
#include "nvs_flash.h"

static const char *TAG = "store";

// Recordings live on the microSD card when one is in, else in the board's own flash.
#define FLASH_DIR "/flash"
#define PARTITION "storage"
#define CARD_MOUNT "/sd"
#define CARD_DIR "/sd/piatra" // a folder of our own, so the card can hold other things too
#define META_MAGIC 0x43455250 // "PREC", so a damaged .meta isn't trusted

typedef struct {
    uint32_t magic;
    rec_info_t info;
} meta_file_t;

static uint32_t next_id = 1;
static nvs_handle_t nvs;
static bool on_card;
static const char *dir = FLASH_DIR; // where recordings are kept: FLASH_DIR or CARD_DIR

// The recording being written.
static FILE *writing;
static rec_info_t current;

static void path_in(char *out, size_t size, const char *in, uint32_t id, const char *ext) {
    snprintf(out, size, "%s/%lu.%s", in, (unsigned long)id, ext);
}

static void path(char *out, size_t size, uint32_t id, const char *ext) {
    path_in(out, size, dir, id, ext);
}

static bool file_exists(const char *p) {
    FILE *f = fopen(p, "rb");
    if (f) fclose(f);
    return f != NULL;
}

// Audio files are named by their format: <id>.opus or <id>.pcm.
static const char *audio_ext(uint8_t format) {
    return format == FORMAT_OPUS_16K ? "opus" : "pcm";
}

// The format of the audio file recording `id` has in folder `in`, or 0 if there's none.
static uint8_t audio_format_in(const char *in, uint32_t id) {
    static const uint8_t formats[] = { FORMAT_OPUS_16K, FORMAT_PCM_16K };
    for (int i = 0; i < 2; i++) {
        char p[32];
        path_in(p, sizeof(p), in, id, audio_ext(formats[i]));
        if (file_exists(p)) return formats[i];
    }
    return 0;
}

static uint8_t audio_format(uint32_t id) {
    return audio_format_in(dir, id);
}

static bool write_meta(const rec_info_t *info) {
    char p[32];
    path(p, sizeof(p), info->id, "meta");
    meta_file_t meta = { .magic = META_MAGIC, .info = *info };
    FILE *f = fopen(p, "wb");
    if (!f) return false;
    // LittleFS commits a file's new contents only when it's closed, so a power cut leaves
    // either the old file or the new one. FAT (the card) can leave it half written; tidy_up
    // then rebuilds it from the audio.
    bool ok = fwrite(&meta, sizeof(meta), 1, f) == 1;
    return fclose(f) == 0 && ok;
}

bool store_update(const rec_info_t *info) {
    return write_meta(info);
}

bool store_info(uint32_t id, rec_info_t *out) {
    char p[32];
    path(p, sizeof(p), id, "meta");
    FILE *f = fopen(p, "rb");
    if (!f) return false;
    meta_file_t meta;
    bool ok = fread(&meta, sizeof(meta), 1, f) == 1 && meta.magic == META_MAGIC;
    fclose(f);
    if (ok) *out = meta.info;
    return ok;
}

// The id counter lives in NVS (the board's small key-value store, in its own partition):
// saving it there takes a few ms, while rewriting a LittleFS file took far longer.
static void save_next_id(void) {
    nvs_set_u32(nvs, "next_id", next_id);
    nvs_commit(nvs);
}

static esp_err_t open_nvs(void) {
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        // NVS is full or from an older ESP-IDF: wipe it and start again. tidy_up() still
        // keeps ids past every recording in flash.
        nvs_flash_erase();
        err = nvs_flash_init();
    }
    if (err != ESP_OK) return err;
    return nvs_open("store", NVS_READWRITE, &nvs);
}

// How many bytes of an Opus file are whole packets: a power cut can stop it mid-packet.
static long whole_packets(FILE *f, long size) {
    long end = 0;
    uint8_t length[2];
    while (fseek(f, end, SEEK_SET) == 0 && fread(length, 1, 2, f) == 2) {
        long n = length[0] | length[1] << 8;
        if (n == 0 || n > OPUS_MAX_PACKET || end + 2 + n > size) break;
        end += 2 + n;
    }
    return end;
}

// An audio file without a .meta was cut off by a power loss mid-recording. Its audio is
// still good, so finish it with the clock unknown instead of throwing it away.
static void rescue(uint32_t id, uint8_t format) {
    if (format == 0) return;
    char p[32];
    path(p, sizeof(p), id, audio_ext(format));
    struct stat st;
    FILE *f = stat(p, &st) == 0 ? fopen(p, "rb") : NULL;
    if (!f) return;
    long keep = format == FORMAT_OPUS_16K ? whole_packets(f, st.st_size) : st.st_size;
    rewind(f);
    rec_info_t info = { .id = id, .uptime_ms = REC_UPTIME_UNKNOWN, .format = format };
    static uint8_t buffer[1024];
    size_t n;
    while (info.size < keep && (n = fread(buffer, 1, sizeof(buffer), f)) > 0) {
        if (n > keep - info.size) n = keep - info.size;
        info.crc = esp_rom_crc32_le(info.crc, buffer, n);
        info.size += n;
    }
    fclose(f);
    if (keep < st.st_size) truncate(p, keep); // drop the half packet
    if (write_meta(&info)) ESP_LOGW(TAG, "rescued recording %lu (%lu bytes)", (unsigned long)id, (unsigned long)info.size);
}

// A recording with no time yet, made before a power loss: its uptime counted from an earlier
// power-up, so SET_TIME can't place it (protocol section 6, rule 4). An uptime later than
// now also means the RTC timer has restarted since.
static void forget_old_uptime(uint32_t id) {
    rec_info_t info;
    if (!store_info(id, &info)) {
        rescue(id, audio_format(id)); // a damaged .meta: rebuild it, or it would never sync
        return;
    }
    if (info.recorded_at != 0 || info.uptime_ms == REC_UPTIME_UNKNOWN) return;
    if (!clock_power_was_lost() && info.uptime_ms <= clock_uptime_ms()) return;
    info.uptime_ms = REC_UPTIME_UNKNOWN;
    write_meta(&info);
}

// After a power cut: rescue cut-off recordings, drop .meta files whose audio was already
// deleted, and make sure next_id is past every id in use.
static void tidy_up(void) {
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *entry;
    while ((entry = readdir(d)) != NULL) {
        char ext[8] = "";
        unsigned long id;
        if (sscanf(entry->d_name, "%lu.%7s", &id, ext) != 2) continue;
        char other[32];
        if (strcmp(ext, "pcm") == 0 || strcmp(ext, "opus") == 0) {
            path(other, sizeof(other), id, "meta");
            if (!file_exists(other)) rescue(id, strcmp(ext, "opus") == 0 ? FORMAT_OPUS_16K : FORMAT_PCM_16K);
        } else if (strcmp(ext, "meta") == 0) {
            if (audio_format(id) == 0) {
                path(other, sizeof(other), id, "meta");
                remove(other);
                continue;
            }
            forget_old_uptime(id);
        }
        if (id >= next_id) next_id = id + 1;
    }
    closedir(d);
}

// The card shares the SPI pins D8–D10 and uses GPIO21 (the LED's pin) to select it.
static bool mount_card(void) {
    spi_bus_config_t bus = {
        .mosi_io_num = PIN_SD_MOSI,
        .miso_io_num = PIN_SD_MISO,
        .sclk_io_num = PIN_SD_SCK,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 4096,
    };
    if (spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO) != ESP_OK) return false;

    sdmmc_host_t host = SDSPI_HOST_DEFAULT();
    host.slot = SPI2_HOST;
    sdspi_device_config_t slot = SDSPI_DEVICE_CONFIG_DEFAULT();
    slot.gpio_cs = PIN_SD_CS;
    slot.host_id = SPI2_HOST;
    esp_vfs_fat_mount_config_t conf = {
        .format_if_mount_failed = false, // never wipe a card: it may hold unsynced recordings
        .max_files = 4,
    };
    sdmmc_card_t *card;
    esp_err_t err = esp_vfs_fat_sdspi_mount(CARD_MOUNT, &host, &slot, &conf, &card);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "no usable microSD card (%s): recording to flash", esp_err_to_name(err));
        spi_bus_free(SPI2_HOST);
        return false;
    }
    mkdir(CARD_DIR, 0775); // fails harmlessly if it's already there
    sdmmc_card_print_info(stdout, card);
    return true;
}

static bool copy_file(const char *from, const char *to) {
    FILE *in = fopen(from, "rb");
    if (!in) return false;
    FILE *out = fopen(to, "wb");
    bool ok = out != NULL;
    static uint8_t buffer[4096];
    size_t n;
    while (ok && (n = fread(buffer, 1, sizeof(buffer), in)) > 0) {
        ok = fwrite(buffer, 1, n, out) == n;
    }
    fclose(in);
    if (out) ok = fclose(out) == 0 && ok;
    return ok;
}

// Recordings made in flash (before the card went in) move to the card. Each one is copied
// whole before the flash copy is deleted, so a power cut midway just copies it again.
static void move_flash_to_card(void) {
    DIR *d = opendir(FLASH_DIR);
    if (!d) return;
    struct dirent *entry;
    while ((entry = readdir(d)) != NULL) {
        unsigned long id;
        char ext[8] = "";
        if (sscanf(entry->d_name, "%lu.%7s", &id, ext) != 2 || strcmp(ext, "meta") != 0) continue;
        const char *audio = audio_ext(audio_format_in(FLASH_DIR, id));
        char from_audio[32], from_meta[32], to_audio[32], to_meta[32];
        path_in(from_audio, sizeof(from_audio), FLASH_DIR, id, audio);
        path_in(from_meta, sizeof(from_meta), FLASH_DIR, id, "meta");
        path_in(to_audio, sizeof(to_audio), CARD_DIR, id, audio);
        path_in(to_meta, sizeof(to_meta), CARD_DIR, id, "meta");
        if (copy_file(from_audio, to_audio) && copy_file(from_meta, to_meta)) {
            remove(from_audio);
            remove(from_meta);
            ESP_LOGI(TAG, "moved recording %lu from flash to the card", id);
        } else {
            ESP_LOGE(TAG, "couldn't move recording %lu to the card", id);
        }
    }
    closedir(d);
}

esp_err_t store_init(void) {
    esp_vfs_littlefs_conf_t conf = {
        .base_path = FLASH_DIR,
        .partition_label = PARTITION,
        // Needed on first use, when the partition is still blank. A file system too damaged
        // to mount couldn't give its recordings back anyway.
        .format_if_mount_failed = true,
    };
    esp_err_t err = esp_vfs_littlefs_register(&conf);
    if (err != ESP_OK) return err;

    err = open_nvs();
    if (err != ESP_OK) return err;
    nvs_get_u32(nvs, "next_id", &next_id); // stays 1 if it was never saved
    remove(FLASH_DIR "/next_id");           // where the counter lived before NVS
    tidy_up();                              // flash first: rescues cut-off recordings there
    if (USE_SD_CARD && mount_card()) {
        move_flash_to_card();
        on_card = true;
        dir = CARD_DIR;
        tidy_up();
    }
    save_next_id();

    uint32_t total_kb, free_kb;
    store_usage(&total_kb, &free_kb);
    ESP_LOGI(TAG, "storage (%s): %lu of %lu KB free, next id %lu", store_on_card() ? "microSD" : "flash",
             (unsigned long)free_kb, (unsigned long)total_kb, (unsigned long)next_id);
    return ESP_OK;
}

bool store_begin(uint32_t recorded_at, uint64_t uptime_ms, uint8_t format) {
    current = (rec_info_t){
        .id = next_id++,
        .recorded_at = recorded_at,
        .uptime_ms = uptime_ms,
        .format = format,
    };
    save_next_id();

    char p[32];
    path(p, sizeof(p), current.id, audio_ext(format));
    writing = fopen(p, "wb");
    if (!writing) {
        ESP_LOGE(TAG, "can't create %s", p);
        return false;
    }
    setvbuf(writing, NULL, _IOFBF, 4096); // write in 4 KB pieces
    return true;
}

bool store_write(const void *data, size_t bytes) {
    if (!writing) return false;
    // The CRC is kept up to date while recording, so GET_END doesn't have to re-read the
    // file. esp_rom_crc32_le (in the chip's ROM) is the same CRC-32 as zip and
    // java.util.zip.CRC32 when chained from 0.
    current.crc = esp_rom_crc32_le(current.crc, data, bytes);
    current.size += bytes;
    return fwrite(data, 1, bytes, writing) == bytes;
}

bool store_finish(rec_info_t *out) {
    if (!writing) return false;
    bool ok = fclose(writing) == 0;
    writing = NULL;
    ok = ok && write_meta(&current);
    if (ok && out) *out = current;
    return ok;
}

static int compare_ids(const void *a, const void *b) {
    uint32_t x = *(const uint32_t *)a, y = *(const uint32_t *)b;
    return (x > y) - (x < y);
}

int store_list(rec_info_t *out, int max) {
    static uint32_t ids[256];
    int count = 0;
    DIR *d = opendir(dir);
    if (!d) return 0;
    struct dirent *entry;
    while ((entry = readdir(d)) != NULL && count < 256) {
        unsigned long id;
        char ext[8] = "";
        if (sscanf(entry->d_name, "%lu.%7s", &id, ext) == 2 && strcmp(ext, "meta") == 0) {
            ids[count++] = id;
        }
    }
    closedir(d);
    qsort(ids, count, sizeof(ids[0]), compare_ids); // oldest (smallest id) first

    int found = 0;
    for (int i = 0; i < count && found < max; i++) {
        if (store_info(ids[i], &out[found])) found++;
    }
    return found;
}

FILE *store_open(uint32_t id) {
    uint8_t format = audio_format(id);
    if (format == 0) return NULL;
    char p[32];
    path(p, sizeof(p), id, audio_ext(format));
    return fopen(p, "rb");
}

bool store_delete(uint32_t id) {
    char audio[32], meta[32];
    path(audio, sizeof(audio), id, audio_ext(audio_format(id)));
    path(meta, sizeof(meta), id, "meta");
    if (!file_exists(meta)) return false;
    // Audio first: if the power goes in between, tidy_up removes the leftover .meta.
    // The other order would bring a deleted recording back as a "rescued" one.
    remove(audio);
    remove(meta);
    return true;
}

bool store_on_card(void) {
    return on_card;
}

// In KB: a card's size in bytes doesn't fit in 32 bits.
void store_usage(uint32_t *total_kb, uint32_t *free_kb) {
    *total_kb = *free_kb = 0;
    if (store_on_card()) {
        uint64_t total, free_bytes;
        if (esp_vfs_fat_info(CARD_MOUNT, &total, &free_bytes) != ESP_OK) return;
        *total_kb = total / 1024;
        *free_kb = free_bytes / 1024;
    } else {
        size_t total, used;
        if (esp_littlefs_info(PARTITION, &total, &used) != ESP_OK) return;
        *total_kb = total / 1024;
        *free_kb = (total - used) / 1024;
    }
}
