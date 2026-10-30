package dev.piatra.core

// Records audio in PcmFormat. The Android version is android/AndroidAudioRecorder.kt.
interface AudioRecorder {
    // Records until the calling coroutine is cancelled, handing each chunk of audio
    // to onChunk (only the first `size` bytes of the array are valid).
    // Throws if the microphone can't be opened or stops working.
    suspend fun record(onChunk: (chunk: ByteArray, size: Int) -> Unit)
}
