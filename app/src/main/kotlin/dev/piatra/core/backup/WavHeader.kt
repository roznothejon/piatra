package dev.piatra.core.backup

import dev.piatra.core.PcmFormat

// A WAV file is our raw PCM with this 44-byte header in front, saying how to play it
// (16 kHz, 16-bit, mono). Every player understands it; the import only has to skip it again.
// All numbers in it are little-endian, like the samples.
object WavHeader {
    const val SIZE = 44

    fun forPcm(pcmBytes: Long): ByteArray {
        val bytes = ByteArray(SIZE)
        var at = 0
        fun text(s: String) = s.forEach { bytes[at++] = it.code.toByte() }
        fun int32(v: Long) = repeat(4) { bytes[at++] = (v shr (8 * it)).toByte() }
        fun int16(v: Int) = repeat(2) { bytes[at++] = (v shr (8 * it)).toByte() }

        text("RIFF")
        int32(36 + pcmBytes) // the size of everything after these first 8 bytes
        text("WAVE")
        text("fmt ")
        int32(16) // the size of this "fmt " chunk
        int16(1) // 1 = plain PCM, no compression
        int16(PcmFormat.CHANNELS)
        int32(PcmFormat.SAMPLE_RATE_HZ.toLong())
        int32(PcmFormat.BYTES_PER_SECOND.toLong())
        int16(PcmFormat.BYTES_PER_SAMPLE * PcmFormat.CHANNELS) // bytes per sample, all channels
        int16(PcmFormat.BYTES_PER_SAMPLE * 8) // bits per sample
        text("data")
        int32(pcmBytes)
        return bytes
    }

    // True if these 44 bytes are the header forPcm writes (16 kHz, 16-bit, mono). The import
    // only takes back its own WAVs, so it doesn't need to understand every kind of WAV file.
    fun isOurs(header: ByteArray): Boolean {
        if (header.size != SIZE) return false
        val expected = forPcm(0)
        // Skip the two sizes (bytes 4–7 and 40–43); everything else must match.
        return header.indices.all { it in 4..7 || it in 40..43 || header[it] == expected[it] }
    }
}
