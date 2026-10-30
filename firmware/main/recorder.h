// Hold to record: turns button events into recordings in the store, and counts taps.
#pragma once

#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

// Starts the recorder task, which reads button_event_t from `events`.
void recorder_start(QueueHandle_t events);

// A press is being handled (recording, or the dev gesture's hold), or taps are being counted:
// the board mustn't sleep.
bool recorder_busy(void);
