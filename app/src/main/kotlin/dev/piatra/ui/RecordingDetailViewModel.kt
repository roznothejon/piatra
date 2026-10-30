package dev.piatra.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dev.piatra.android.RecordingPlayback
import dev.piatra.core.RecordingProcessingQueue
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemRepository
import dev.piatra.data.ListRepository
import dev.piatra.data.RecordingEntity
import dev.piatra.data.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What the recording screen shows. Deleted: the recording went away while the screen was open.
sealed interface RecordingDetailState {
    data object Loading : RecordingDetailState
    data object Deleted : RecordingDetailState
    data class Shown(val recording: RecordingEntity, val items: List<ItemEntity>) : RecordingDetailState
}

class RecordingDetailViewModel(
    // SavedStateHandle: the screen's saved arguments. Navigation puts the route in it.
    savedStateHandle: SavedStateHandle,
    private val recordings: RecordingRepository,
    private val items: ItemRepository,
    lists: ListRepository,
    private val playback: RecordingPlayback,
    private val queue: RecordingProcessingQueue,
) : ViewModel() {
    // toRoute() reads the RecordingDetail the screen was opened with.
    private val recordingId = savedStateHandle.toRoute<RecordingDetail>().recordingId

    // combine: a new state whenever the recording or its items change.
    val state: StateFlow<RecordingDetailState> =
        combine(recordings.observe(recordingId), items.observeForRecording(recordingId)) { recording, created ->
            if (recording == null) RecordingDetailState.Deleted else RecordingDetailState.Shown(recording, created)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingDetailState.Loading)

    val listTitles: StateFlow<Map<Long, String>> = lists.observeAll()
        .map { all -> all.associate { it.id to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Same player as the list's (see RecordingPlayback).
    val playingId: StateFlow<Long?> = playback.playingId

    fun togglePlayback(recording: RecordingEntity) {
        if (playingId.value == recording.id) playback.stop() else playback.play(recording)
    }

    fun stopPlayback() = playback.stop()

    // "Transcribe again" / "Try again": starts the recording over (see the queue's transcribeAgain).
    fun transcribeAgain() = queue.transcribeAgain(recordingId)

    // The screen closes straight after this, which clears the ViewModel and cancels its
    // coroutines. NonCancellable lets the delete finish anyway.
    fun delete(recording: RecordingEntity, alsoItems: Boolean) {
        if (playingId.value == recording.id) playback.stop()
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                if (alsoItems) items.deleteForRecording(recording.id)
                recordings.delete(recording)
            }
        }
    }

    // The ✕ next to an item (after the dialog).
    fun removeItem(item: ItemEntity) {
        viewModelScope.launch { items.delete(item.id) }
    }
}
