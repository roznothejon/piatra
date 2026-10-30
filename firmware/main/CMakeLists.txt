idf_component_register(
    SRCS "main.c" "button.c" "led.c" "mic.c" "recorder.c" "store.c" "console.c" "haptic.c" "battery.c" "writer.c" "clock.c" "ble.c" "sync.c" "dev.c" "logbuf.c" "sleep.c" "encoder.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES esp_driver_gpio esp_adc esp_driver_i2s esp_driver_usb_serial_jtag esp_timer vfs nvs_flash bt fatfs sdmmc esp_driver_sdspi esp_driver_spi app_update esp_app_format 78__esp-opus
)

# The 6-digit code a laptop types to pair for development. It lives in
# firmware/dev.secret, which is git-ignored: each builder picks their own.
if(NOT CMAKE_BUILD_EARLY_EXPANSION)
    idf_build_get_property(project_dir PROJECT_DIR)
    set(secret_file "${project_dir}/dev.secret")
    if(NOT EXISTS "${secret_file}")
        message(FATAL_ERROR "firmware/dev.secret is missing: put a 6-digit code of your choice in it, e.g. `echo 482913 > dev.secret`")
    endif()
    file(STRINGS "${secret_file}" dev_code LIMIT_COUNT 1)
    string(STRIP "${dev_code}" dev_code)
    if(NOT dev_code MATCHES "^[0-9][0-9][0-9][0-9][0-9][0-9]$")
        message(FATAL_ERROR "firmware/dev.secret must hold exactly 6 digits")
    endif()
    string(REGEX REPLACE "^0+([0-9])" "\\1" dev_code "${dev_code}") # 012345 would be octal in C
    target_compile_definitions(${COMPONENT_LIB} PRIVATE DEV_PASSKEY=${dev_code})
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${secret_file}")
endif()
