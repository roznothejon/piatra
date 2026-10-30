package dev.piatra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(private val items: ItemRepository) : ViewModel() {
    // Newest first, including notes whose recording was deleted.
    val notes: StateFlow<List<ItemEntity>> = items.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeNote(note: ItemEntity) {
        viewModelScope.launch { items.delete(note.id) }
    }
}
