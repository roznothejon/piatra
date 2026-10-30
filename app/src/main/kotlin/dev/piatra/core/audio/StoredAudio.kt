package dev.piatra.core.audio

// How a recording's audio is kept on the phone.
enum class StoredAudio(val extension: String) {
    OPUS("opus"), // Ogg Opus (OggOpus): ~16× smaller
    PCM("pcm"), // raw 16 kHz PCM (PcmFormat): while recording, and if Opus encoding ever fails
}

// 16-bit samples ↔ little-endian bytes (PcmFormat's byte order).
fun ShortArray.toLittleEndian(): ByteArray {
    val bytes = ByteArray(size * 2)
    for (i in indices) {
        bytes[2 * i] = this[i].toInt().toByte()
        bytes[2 * i + 1] = (this[i].toInt() shr 8).toByte()
    }
    return bytes
}
