// Recordings in flash (LittleFS, mounted at /flash):
//   /flash/<id>.opus  Opus audio (or <id>.pcm, raw), exactly what the phone receives
//   /flash/<id>.meta  a rec_info_t, written when the recording is finished
// The id counter is in NVS; ids are never reused, even after a delete.
// A recording only exists once its .meta is written, so a half-written one never shows up.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "esp_err.h"

#define FORMAT_PCM_16K 0x01  // protocol section 6
#define FORMAT_OPUS_16K 0x02 // [length u16][packet] per 20 ms frame, no header
#define OPUS_MAX_PACKET 1275 // the largest packet Opus makes

typedef struct {
    uint32_t id;
    uint32_t size;        // bytes
    uint32_t crc;         // CRC-32 of the whole audio file
    uint32_t recorded_at; // Unix seconds, 0 = clock wasn't set
    uint64_t uptime_ms;   // time since power-up when recording started (protocol section 6)
    uint8_t format;
} rec_info_t;

// uptime_ms of a recording from an earlier power-up: SET_TIME can't work out when it was made.
#define REC_UPTIME_UNKNOWN UINT64_MAX

// Mounts the flash file system (formats it on first use) and the microSD card if one is in,
// moves recordings from flash to the card, and tidies up after a power cut.
esp_err_t store_init(void);

// True when recordings go to the microSD card, false when to flash (no card, or it failed).
// The card uses the LED's pin, so the LED stays off while it's in.
bool store_on_card(void);

// Writing a new recording (one at a time). store_begin takes the next id.
bool store_begin(uint32_t recorded_at, uint64_t uptime_ms, uint8_t format);
bool store_write(const void *data, size_t bytes);
bool store_finish(rec_info_t *out);

// Up to `max` finished recordings, oldest first. Returns how many.
int store_list(rec_info_t *out, int max);
bool store_info(uint32_t id, rec_info_t *out);
bool store_update(const rec_info_t *info); // rewrites the .meta, e.g. with recorded_at fixed up
FILE *store_open(uint32_t id); // the audio file, for reading; fclose it when done
bool store_delete(uint32_t id);
void store_usage(uint32_t *total_kb, uint32_t *free_kb);
