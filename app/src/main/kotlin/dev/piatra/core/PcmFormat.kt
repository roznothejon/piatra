package dev.piatra.core

// The one audio format the whole pipeline uses: 16 kHz, 16-bit, mono, raw PCM
// (little-endian, no header). The speech model expects it, and the ESP32 will send it.
object PcmFormat {
    const val SAMPLE_RATE_HZ = 16_000
    const val BYTES_PER_SAMPLE = 2 // 16 bits
    const val CHANNELS = 1 // mono
    const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * CHANNELS // 32,000

    fun durationMillis(sizeBytes: Long): Long = sizeBytes * 1000 / BYTES_PER_SECOND
}
