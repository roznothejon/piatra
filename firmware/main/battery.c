#include "battery.h"

#include "board.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_log.h"

static const char *TAG = "battery";

#define CHANNEL ADC_CHANNEL_0 // GPIO1
#define SAMPLES 16            // averaged, to smooth out noise
// Below this the pin is just the divider's resistor to GND: no battery.
#define NO_BATTERY_MV 1000

static adc_oneshot_unit_handle_t adc;
static adc_cali_handle_t cali;

void battery_init(void) {
    adc_oneshot_unit_init_cfg_t unit = { .unit_id = ADC_UNIT_1 };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&unit, &adc));

    // 12 dB attenuation lets the pin measure up to ~3.1 V; a full battery gives 2.1 V here.
    adc_oneshot_chan_cfg_t channel = { .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_DEFAULT };
    ESP_ERROR_CHECK(adc_oneshot_config_channel(adc, CHANNEL, &channel));

    // Every chip's ADC is a little off; the factory measured this one, and the calibration
    // scheme uses those numbers to turn raw readings into millivolts.
    adc_cali_curve_fitting_config_t config = {
        .unit_id = ADC_UNIT_1, .chan = CHANNEL, .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    if (adc_cali_create_scheme_curve_fitting(&config, &cali) != ESP_OK) {
        ESP_LOGW(TAG, "no ADC calibration; battery readings will be rough");
        cali = NULL;
    }
}

uint16_t battery_millivolts(void) {
    int total = 0;
    for (int i = 0; i < SAMPLES; i++) {
        int raw = 0, mv = 0;
        adc_oneshot_read(adc, CHANNEL, &raw);
        if (cali) adc_cali_raw_to_voltage(cali, raw, &mv);
        else mv = raw * 3100 / 4095;
        total += mv;
    }
    int battery = total / SAMPLES * BATTERY_DIVIDER;
    return battery < NO_BATTERY_MV ? 0 : (uint16_t)battery;
}
