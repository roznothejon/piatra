package dev.piatra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.piatra.android.BoardSyncManager
import dev.piatra.android.ModelManager
import dev.piatra.android.SyncState
import dev.piatra.android.RecordingPlayback
import dev.piatra.android.RecordingStorage
import dev.piatra.android.SpeechModels
import dev.piatra.core.AudioRecorder
import dev.piatra.core.Clock
import dev.piatra.core.ModelState
import dev.piatra.core.PcmFormat
import dev.piatra.core.RecordingProcessingQueue
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemRepository
import dev.piatra.data.ListRepository
import dev.piatra.data.RecordingEntity
import dev.piatra.data.RecordingRepository
import dev.piatra.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Every state the screen can be in. "sealed" means these are the only options,
// so a `when` over them must handle each one (the compiler checks).
sealed interface RecordingUiState {
    data object Idle : RecordingUiState
    // data class: unlike Idle, each Recording carries its own value.
    data class Recording(val startedAtMillis: Long) : RecordingUiState
}

// What happened to the most recent recording, shown under the button.
sealed interface LastRecording {
    data class Saved(val durationMillis: Long, val sizeBytes: Long) : LastRecording
    data class TooShort(val durationMillis: Long) : LastRecording
    data class Failed(val message: String) : LastRecording
}

// A ViewModel outlives the Activity: Android keeps it when the screen rotates
// and only clears it when the user really leaves the screen.
// It declares what it needs and Koin passes it in; see AppModule.kt.
class MainViewModel(
    private val clock: Clock,
    private val recorder: AudioRecorder,
    private val playback: RecordingPlayback,
    private val storage: RecordingStorage,
    private val repository: RecordingRepository,
    private val queue: RecordingProcessingQueue,
    speechModels: SpeechModels,
    agentModel: ModelManager,
    private val items: ItemRepository,
    lists: ListRepository,
    settings: Settings,
    private val boardSync: BoardSyncManager,
) : ViewModel() {
    // Private and writable inside; public and read-only outside.
    private val _state = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    private val _lastRecording = MutableStateFlow<LastRecording?>(null)
    val lastRecording: StateFlow<LastRecording?> = _lastRecording.asStateFlow()

    // All recordings, newest first. Room sends a new list whenever the table changes;
    // stateIn turns that Flow into a StateFlow (empty until the first result arrives).
    // WhileSubscribed(5_000): run the query while the screen watches, plus 5 s so a
    // rotation doesn't restart it.
    val recordings: StateFlow<List<RecordingEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // What the agent created, per recording: recording id -> its items.
    val itemsByRecording: StateFlow<Map<Long, List<ItemEntity>>> = items.observeByRecording()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // List names by id, to label list items ("Todo", "Shopping").
    val listTitles: StateFlow<Map<Long, String>> = lists.observeAll()
        .map { all -> all.associate { it.id to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // A line pointing to Settings while the chosen speech model or the agent model isn't ready
    // (the download rows themselves are on the settings screen). null: all ready.
    val modelsNotice: StateFlow<String?> =
        combine(speechModels.activeState, agentModel.state) { speech, agent -> modelsNotice(listOf(speech, agent)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The Sync button shows once a board is paired.
    val hasBoard: StateFlow<Boolean> = settings.board.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val sync: StateFlow<SyncState> = boardSync.state

    fun syncNow() = boardSync.start()
    fun dismissSync() = boardSync.dismiss()

    // Handle to the running recording coroutine, so it can be cancelled.
    private var recordingJob: Job? = null

    fun startRecording() {
        if (_state.value is RecordingUiState.Recording) return // already recording: nothing to do
        playback.stop() // don't let the speaker leak into the mic (the player is shared with the recording screen)
        val fileId = storage.newRecordingId()
        val startedAt = clock.nowMillis()
        _state.value = RecordingUiState.Recording(startedAtMillis = startedAt)

        // launch: run in the background. viewModelScope cancels it if the ViewModel is cleared.
        // Dispatchers.IO: file and mic work stays off the main (UI) thread.
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val error = try {
                // use { } closes the file when the block ends, even after an exception.
                storage.openForWriting(fileId).use { output ->
                    recorder.record { chunk, size -> output.write(chunk, 0, size) }
                }
                null
            } catch (e: CancellationException) {
                // Cancelling is how stopRecording() ends a recording, so here it means success.
                // (Usually you rethrow CancellationException; the work below handles it instead.)
                null
            } catch (e: Exception) {
                e
            }
            // This coroutine is cancelled by now, and a cancelled coroutine's suspend calls
            // throw straight away. NonCancellable lets this cleanup (a database insert) run anyway.
            withContext(NonCancellable) {
                _lastRecording.value = if (error == null) keepOrDiscard(fileId, startedAt) else {
                    storage.delete(fileId)
                    LastRecording.Failed(error.message ?: "Recording failed")
                }
                _state.value = RecordingUiState.Idle
            }
        }
    }

    fun stopRecording() {
        // Ask the recording coroutine to stop. It finishes up (closes the file, saves it) by itself.
        recordingJob?.cancel()
        recordingJob = null
    }

    // Recordings shorter than a second are accidental taps, not voice notes (same rule as upstream).
    // Longer ones are compressed to Opus, get a row in the database, which makes them appear in
    // the list, and are queued for transcription.
    private suspend fun keepOrDiscard(fileId: String, createdAt: Long): LastRecording {
        val durationMillis = PcmFormat.durationMillis(storage.pcmSizeBytes(fileId))
        return if (durationMillis < MIN_DURATION_MILLIS) {
            storage.delete(fileId)
            LastRecording.TooShort(durationMillis)
        } else {
            storage.compress(fileId)
            val id = repository.add(fileId, createdAt, durationMillis)
            queue.queueLocalAudioProcessing(id)
            LastRecording.Saved(durationMillis, storage.storedSizeBytes(fileId))
        }
    }

    // companion object: things that belong to the class itself, not each instance (like Java's static).
    companion object {
        const val MIN_DURATION_MILLIS = 1_000L
    }
}

internal fun modelsNotice(states: List<ModelState>): String? = when {
    states.all { it == ModelState.Ready } -> null
    states.any { it is ModelState.Downloading || it == ModelState.Unpacking } -> "Downloading models…"
    states.any { it is ModelState.Failed } -> "A model download failed."
    else -> "Download the models to transcribe recordings."
}
