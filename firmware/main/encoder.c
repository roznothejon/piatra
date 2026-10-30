#include "encoder.h"

#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "opus.h"
#include "store.h"

static const char *TAG = "encoder";

// Opus settings, chosen on a laptop with tools/opus_test.py: 16 kbps, "voip"
// mode (tuned for speech), 20 ms frames. Complexity 0–10 is how hard the encoder tries;
// the laptop test heard no difference, so the lowest saves the CPU (at 3, a 20 ms frame
// took ~10 ms to encode at 160 MHz, half of one core).
#define OPUS_BITRATE 16000
#define OPUS_COMPLEXITY 0

// The encoder keeps a little of the audio before each frame (its "state"), so frames join
// smoothly; it's reset at the start of every recording.
static OpusEncoder *encoder;
static int64_t total_us, slowest_us;
static uint32_t frames;

void encoder_init(void) {
    if (!RECORD_OPUS) return;
    int err;
    encoder = opus_encoder_create(SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP, &err);
    if (!encoder) {
        ESP_LOGE(TAG, "opus_encoder_create failed: %d", err);
        return;
    }
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(OPUS_BITRATE));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(OPUS_COMPLEXITY));
    ESP_LOGI(TAG, "Opus encoder ready (%d bytes)", opus_encoder_get_size(1));
}

void encoder_begin(void) {
    if (encoder) opus_encoder_ctl(encoder, OPUS_RESET_STATE);
    total_us = slowest_us = 0;
    frames = 0;
}

uint8_t encoder_format(void) {
    return RECORD_OPUS ? FORMAT_OPUS_16K : FORMAT_PCM_16K;
}

size_t encoder_frame(const int16_t *samples, uint8_t *out) {
    if (!RECORD_OPUS) {
        memcpy(out, samples, ENCODER_FRAME * 2);
        return ENCODER_FRAME * 2;
    }
    if (!encoder) return 0;
    int64_t start = esp_timer_get_time();
    int n = opus_encode(encoder, samples, ENCODER_FRAME, out + 2, ENCODER_MAX_BYTES - 2);
    int64_t took = esp_timer_get_time() - start;
    total_us += took;
    if (took > slowest_us) slowest_us = took;
    frames++;
    if (n <= 0) {
        ESP_LOGE(TAG, "opus_encode failed: %d", n);
        return 0;
    }
    out[0] = n & 0xff; // little-endian, like every number in the protocol
    out[1] = n >> 8;
    return 2 + n;
}

void encoder_log_stats(void) {
    if (frames == 0) return;
    // The CPU load: a frame holds 20 ms of audio, so 2 ms of encoding is 10 %.
    ESP_LOGI(TAG, "opus: %lu frames, %.1f ms average, %.1f ms slowest", (unsigned long)frames,
             total_us / 1000.0f / frames, slowest_us / 1000.0f);
}
