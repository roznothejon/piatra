package dev.piatra.core.audio

// Turns Opus packets (16 kHz mono, as the board records them) back into 16 kHz PCM samples.
// Android uses its built-in decoder (android/MediaCodecOpusDecoder.kt); an iOS port would
// bring its own.
interface OpusDecoder {
    // Calls `out` with the samples in order, in pieces of any size.
    // Throws UndecodableAudioException if the packets can't be decoded.
    fun decode(packets: List<ByteArray>, out: (ShortArray) -> Unit)
}

class UndecodableAudioException(message: String, cause: Throwable? = null) : Exception(message, cause)
