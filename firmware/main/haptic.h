// The vibration motor: short buzzes as feedback.
#pragma once

#include <stdbool.h>

void haptic_init(void);

// Buzzes for `ms` milliseconds without waiting (a timer turns it off).
void haptic_buzz(int ms);

// True while a buzz is still running.
bool haptic_busy(void);
