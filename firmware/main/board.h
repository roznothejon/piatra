// Pins and fixed numbers of the Seeed XIAO ESP32S3 Sense, in one place.
#pragma once

// The record button, between D1 and GND (pressed = low, internal pull-up). GPIO2 is an RTC
// pin, so it can wake the board from deep sleep. (The BOOT button, GPIO0, is a "strapping"
// pin: held down at power-up it starts the firmware-download mode, so it's not used.)
#define PIN_BUTTON 2 // D1

// The vibration motor, switched by a BC337 transistor (D3 → 470 Ω → base). High = buzz.
#define PIN_HAPTIC 4 // D3

// Battery voltage through a 470k/470k divider (half the battery voltage, 100 nF across the
// lower resistor) on an ADC pin.
// Reads ~0 while no battery is connected.
#define PIN_BATTERY 1 // D0, ADC1 channel 0
#define BATTERY_DIVIDER 2

// The orange user LED. Active low: writing 0 turns it on. The same pin selects the microSD
// card, so the LED is only used when there's no card (store_on_card).
#define PIN_LED 21

// Look for a microSD card at start-up. 0 for a board in a case, where no
// card fits: looking costs ~40 ms of every wake-up, and the board doesn't
// report SD_ERROR. Set to 1 for a board that has a card.
#define USE_SD_CARD 0

// The Sense board's microSD slot, on the SPI pins D8–D10 (solder pads J3 joined, as shipped).
#define PIN_SD_SCK 7  // D8
#define PIN_SD_MISO 8 // D9
#define PIN_SD_MOSI 9 // D10
#define PIN_SD_CS 21  // shared with the LED

// The Sense board's PDM microphone: a clock the chip drives, and the 1-bit data line.
#define PIN_MIC_CLK 42
#define PIN_MIC_DATA 41

// The app's pipeline format (protocol section 6): 16 kHz, 16-bit, mono.
#define SAMPLE_RATE 16000
#define BYTES_PER_SECOND (SAMPLE_RATE * 2)

// Save recordings as Opus: 16× smaller than raw, so flash holds ~40 min
// instead of ~2.5 and transfers are short. 0 saves raw PCM, e.g. to compare the two.
#define RECORD_OPUS 1
