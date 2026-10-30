// The record button, debounced, as a stream of press/release events.
#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

typedef enum { BUTTON_PRESSED, BUTTON_RELEASED } button_event_t;

// Starts a task that watches the button and sends a button_event_t to `events` on each change.
// After a wake by the button, that press is sent straight away (and its release, if it's over).
void button_start(QueueHandle_t events);

// The button is down. The board stays awake meanwhile: asleep, a held button would wake it
// again at once (e.g. still held after a recording's 2-min maximum).
bool button_held(void);
