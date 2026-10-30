package dev.piatra.core

import java.io.InputStream

// Plays audio in PcmFormat. The Android version is android/AndroidAudioPlayer.kt.
interface AudioPlayer {
    // Plays the stream until it ends or the calling coroutine is cancelled.
    suspend fun play(input: InputStream)
}
