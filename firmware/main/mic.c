#include "mic.h"

#include <math.h>
#include <stdlib.h>
#include "board.h"
#include "driver/i2s_pdm.h"
#include "esp_attr.h"

// Gain after the low-cut filter. Tuned with `pull.py`: at ×16 speech from 20 cm
// clipped a little and from 1 m peaked around -7 dBFS, so ×12. In the case, ×12 clipped
// close speech (crunchy), so ×10 with the limiter below .
#define GAIN 10.0f

// Soft limiter: samples above LIMIT_KNEE (half of full scale, -6 dBFS) are bent smoothly
// towards full scale instead of being cut off flat, which is what sounded crunchy.
// Quieter samples pass through unchanged.
#define LIMIT_KNEE 16384.0f
#define LIMIT_ROOM (32767.0f - LIMIT_KNEE)

// The low-cut filter: y[n] = x[n] - x[n-1] + POLE * y[n-1]. It removes the DC offset and
// everything below ~60 Hz (POLE = 1 - 2π·60/16000) and keeps speech, which starts around
// 85 Hz. Needed because the mic's output drifts by up to 1/10 of full scale for the first
// second after it starts (measured), slowly enough to pass a gentler filter.
#define LOW_CUT_POLE 0.976f

// The first few ms after starting are a burst of noise (measured: ~4 ms), so skip 15 ms.
#define WARMUP_SAMPLES (SAMPLE_RATE * 15 / 1000)

// DMA: the I2S hardware fills these buffers on its own while the CPU does other work.
// 50 buffers of 320 samples = 1 s (32 KB of RAM), enough to ride out a slow flash write
// (creating a recording's files was measured at ~260 ms). The driver hands over audio one
// whole buffer at a time, so small buffers mean the first audio arrives sooner (20 ms).
#define DMA_BUFFERS 50
#define DMA_SAMPLES 320

// i2s_channel_read takes its timeout in milliseconds, not FreeRTOS ticks.
#define READ_TIMEOUT_MS 200

static i2s_chan_handle_t channel;
static float last_in, last_out;
static mic_levels_t levels;
static volatile uint32_t overflows;

// Called from an interrupt when a DMA buffer is overwritten before we read it.
static bool IRAM_ATTR on_overflow(i2s_chan_handle_t handle, i2s_event_data_t *event, void *ctx) {
    overflows++;
    return false;
}

// The I2S channel is set up fresh for every recording and deleted afterwards, so nothing
// from the previous recording can be left in its buffers, and nothing runs in between.
esp_err_t mic_start(void) {
    levels = (mic_levels_t){ 0 };
    overflows = 0;

    i2s_chan_config_t chan_config = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_config.dma_desc_num = DMA_BUFFERS;
    chan_config.dma_frame_num = DMA_SAMPLES;
    esp_err_t err = i2s_new_channel(&chan_config, NULL, &channel);
    if (err != ESP_OK) return err;

    // The I2S hardware turns the mic's 1-bit PDM stream into 16-bit samples ("PCM").
    i2s_pdm_rx_config_t pdm_config = {
        .clk_cfg = I2S_PDM_RX_CLK_DEFAULT_CONFIG(SAMPLE_RATE),
        .slot_cfg = I2S_PDM_RX_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
        .gpio_cfg = { .clk = PIN_MIC_CLK, .din = PIN_MIC_DATA },
    };
    i2s_event_callbacks_t callbacks = { .on_recv_q_ovf = on_overflow };
    err = i2s_channel_init_pdm_rx_mode(channel, &pdm_config);
    if (err == ESP_OK) err = i2s_channel_register_event_callback(channel, &callbacks, NULL);
    if (err == ESP_OK) err = i2s_channel_enable(channel);
    if (err != ESP_OK) {
        i2s_del_channel(channel);
        return err;
    }

    static int16_t warmup[WARMUP_SAMPLES];
    size_t bytes = 0;
    i2s_channel_read(channel, warmup, sizeof(warmup), &bytes, READ_TIMEOUT_MS);
    // Start the filter from the mic's current level, so the first samples don't jump.
    last_in = bytes ? warmup[bytes / 2 - 1] : 0;
    last_out = 0;
    return ESP_OK;
}

void mic_stop(void) {
    i2s_channel_disable(channel);
    i2s_del_channel(channel);
}

// Above the knee, tanh bends the rest of the range into the room left below full scale:
// it starts with the same slope as a straight line, so the bend has no corner, and it never
// reaches full scale, so nothing is cut off.
static float limit(float x) {
    float size = fabsf(x);
    if (size <= LIMIT_KNEE) return x;
    levels.limited++;
    float bent = LIMIT_KNEE + LIMIT_ROOM * tanhf((size - LIMIT_KNEE) / LIMIT_ROOM);
    return x < 0 ? -bent : bent;
}

size_t mic_read(int16_t *samples, size_t count) {
    size_t bytes = 0;
    i2s_channel_read(channel, samples, count * 2, &bytes, READ_TIMEOUT_MS);
    size_t got = bytes / 2;

    for (size_t i = 0; i < got; i++) {
        float in = samples[i];
        float out = in - last_in + LOW_CUT_POLE * last_out;
        last_in = in;
        last_out = out;

        int raw = abs((int)out);
        if (raw > levels.peak_raw) levels.peak_raw = raw;

        samples[i] = (int16_t)limit(out * GAIN);

        int peak = abs(samples[i]);
        if (peak > levels.peak) levels.peak = peak;
    }
    return got;
}

mic_levels_t mic_levels(void) {
    levels.overflows = overflows;
    return levels;
}
