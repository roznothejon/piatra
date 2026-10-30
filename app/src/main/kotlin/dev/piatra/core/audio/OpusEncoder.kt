package dev.piatra.core.audio

// Turns 16 kHz PCM samples into Opus packets (one per 20 ms), for phone-mic recordings and the
// one-time conversion of old .pcm files. Android uses its built-in encoder
// (android/MediaCodecOpusEncoder.kt); an iOS port would bring its own.
interface OpusEncoder {
    // pcm: 16-bit little-endian mono samples (PcmFormat). Throws UndecodableAudioException if
    // the phone has no Opus encoder or it fails.
    fun encode(pcm: ByteArray): List<ByteArray>
}
