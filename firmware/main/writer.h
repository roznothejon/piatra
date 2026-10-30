// The writer task: saves recordings to flash without ever holding up the mic.
// The recorder hands audio over through a queue (producer/consumer), so a slow flash write
// (file-system bookkeeping was measured at 260–400 ms, sometimes over 1 s) only delays the
// writer, while the recorder keeps reading the mic. The queue holds 3 s of audio, already
// encoded (encoder.h): with Opus each item is a small packet, so it takes ~27 KB of RAM.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// Starts the writer. It can take audio straight away (a press that woke the board), but only
// writes once writer_storage_ready() says the store is mounted.
void writer_start(void);
void writer_storage_ready(void);

// Nothing queued or being saved: the board may sleep.
bool writer_idle(void);

// Called by the recorder, in this order: begin, audio (any number of times), end.
// Each returns false if the queue was full, i.e. that audio couldn't be kept.
bool writer_begin(uint32_t recorded_at, uint64_t uptime_ms, uint8_t format);
bool writer_frame(const uint8_t *bytes, size_t count); // one encoded 20 ms frame
bool writer_end(void);
