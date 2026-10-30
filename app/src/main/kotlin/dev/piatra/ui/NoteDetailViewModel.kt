package dev.piatra.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dev.piatra.core.agent.ItemStore
import dev.piatra.data.ItemRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// What the note screen shows besides the text.
sealed interface NoteState {
    data object Loading : NoteState
    data object Missing : NoteState
    // createdAt null: a new note not saved yet. recordingId: the voice note it came from, if any.
    data class Shown(val createdAt: Long?, val recordingId: Long?) : NoteState
}

// The note screen: always editable, no Save button (like upstream's ObjectItemDetail).
// The text being typed (draft) lives here, not in Room. It's read from Room once; after that,
// saves only go one way (draft → Room), so a save never moves the cursor.
// save() runs when the app goes to the background and when the screen closes.
class NoteDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val items: ItemRepository,
    // New notes are added through this one, so they also go to Tasker/Obsidian (once).
    private val newNotes: ItemStore,
    private val saveScope: CoroutineScope,
) : ViewModel() {
    // null until a new note is first saved.
    private var noteId: Long? = savedStateHandle.toRoute<NoteDetail>().itemId
    // What Room has, so an unchanged note isn't written again.
    private var savedText = ""
    private var deleted = false
    // Saves run one at a time: a new note must be added once, not twice.
    private val saveLock = Mutex()

    // mutableStateOf in a ViewModel: Compose redraws when it changes, and it survives rotation.
    var draft by mutableStateOf("")
        private set
    var state by mutableStateOf<NoteState>(NoteState.Loading)
        private set

    val isNew get() = noteId == null

    init {
        val id = noteId
        if (id == null) {
            state = NoteState.Shown(createdAt = null, recordingId = null)
        } else {
            viewModelScope.launch {
                val note = items.get(id)
                if (note == null) {
                    state = NoteState.Missing
                } else {
                    draft = note.text
                    savedText = note.text
                    state = NoteState.Shown(note.createdAt, note.recordingId)
                }
            }
        }
    }

    fun edit(text: String) {
        draft = text
    }

    // leaving: the screen is closing. Only then does an emptied note get deleted; in the
    // background it's kept, in case you cleared it to start over.
    fun save(leaving: Boolean = false) {
        if (deleted || state !is NoteState.Shown) return
        val text = draft
        saveScope.launch {
            saveLock.withLock {
                val id = noteId
                when {
                    text == savedText -> {}
                    id == null && text.isNotBlank() -> {
                        noteId = newNotes.addNote(text.trim(), recordingId = null)
                        savedText = text
                    }
                    id == null -> {}
                    text.isBlank() && leaving -> items.delete(id)
                    text.isNotBlank() -> {
                        items.updateText(id, text.trim())
                        savedText = text
                    }
                }
            }
        }
    }

    fun delete() {
        deleted = true
        saveScope.launch { saveLock.withLock { noteId?.let { items.delete(it) } } }
    }

    // Called when the screen is closed for good (not on rotation).
    override fun onCleared() {
        save(leaving = true)
    }
}
