package dev.piatra.android

import android.util.Log
import dev.piatra.core.AudioPlayer
import dev.piatra.data.RecordingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Plays one recording at a time, for every screen. It's a Koin `single`, so it outlives each
// screen's ViewModel: the list and the recording screen share it, and starting one recording
// stops whichever was playing.
class RecordingPlayback(
    private val storage: RecordingStorage,
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
) {
    // Id of the recording being played, or null.
    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()

    private var job: Job? = null

    fun play(recording: RecordingEntity) {
        stop()
        _playingId.value = recording.id
        job = scope.launch {
            try {
                storage.readPcm(recording.fileId).inputStream().use { input -> player.play(input) }
            } catch (e: CancellationException) {
                throw e // stopped by stop(), which already updated the state
            } catch (e: Exception) {
                // Log.e writes to logcat (Android's system log): see it with `adb logcat -s RecordingPlayback`.
                Log.e(TAG, "Playback failed", e)
            }
            _playingId.value = null // reached the end, or failed
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _playingId.value = null
    }

    private companion object {
        const val TAG = "RecordingPlayback"
    }
}
