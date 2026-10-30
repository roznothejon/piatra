// Turns 20 ms blocks of audio into what's saved in the recording file (protocol section 6):
// an Opus packet as [length u16][packet] (RECORD_OPUS in board.h), or the raw samples.
// Runs in the recorder task, so only small packets go through the writer's queue.
#pragma once

#include <stddef.h>
#include <stdint.h>
#include "board.h"

#define ENCODER_FRAME 320 // samples per call: 20 ms

// The most bytes one frame can become. Opus packets are capped at 160 bytes (64 kbps for
// that frame; at 16 kbps they're ~40).
#define ENCODER_MAX_BYTES (RECORD_OPUS ? 2 + 160 : ENCODER_FRAME * 2)

void encoder_init(void);

// Before each recording: forgets the previous one.
void encoder_begin(void);

// Encodes ENCODER_FRAME samples into `out` (room for ENCODER_MAX_BYTES).
// Returns how many bytes it wrote, or 0 if the encoder failed.
size_t encoder_frame(const int16_t *samples, uint8_t *out);

uint8_t encoder_format(void); // FORMAT_OPUS_16K or FORMAT_PCM_16K

// For the log after a recording: how long encoding a frame took.
void encoder_log_stats(void);
