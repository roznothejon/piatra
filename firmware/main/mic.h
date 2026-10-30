// The PDM microphone: 16 kHz, 16-bit mono samples with the DC offset removed and gain applied.
#pragma once

#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

// Starts the mic and throws away its first samples, which are noise while it settles.
esp_err_t mic_start(void);
void mic_stop(void);

// Waits for the next `count` samples and processes them in place. Returns how many it got.
size_t mic_read(int16_t *samples, size_t count);

// Loudest sample since mic_start, before and after gain (0..32767), and how many samples
// were softened by the limiter. For tuning the gain.
typedef struct {
    int peak_raw;
    int peak;
    uint32_t limited;
    uint32_t overflows; // DMA buffers lost because nobody read them in time (should stay 0)
} mic_levels_t;

mic_levels_t mic_levels(void);
