package dev.piatra.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dev.piatra.core.agent.ItemStore
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemRepository
import dev.piatra.data.ListEntity
import dev.piatra.data.ListRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// What a list's screen shows. Deleted: the list went away while the screen was open.
sealed interface ListDetailState {
    data object Loading : ListDetailState
    data object Deleted : ListDetailState
    data class Shown(val list: ListEntity, val open: List<ItemEntity>, val done: List<ItemEntity>) : ListDetailState
}

class ListDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val items: ItemRepository,
    private val lists: ListRepository,
    // Typed items are added through this one, so they also go to Tasker.
    private val newItems: ItemStore,
) : ViewModel() {
    private val listId = savedStateHandle.toRoute<ListDetail>().listId

    val state: StateFlow<ListDetailState> =
        combine(lists.observe(listId), items.observeForList(listId)) { list, listItems ->
            if (list == null) {
                ListDetailState.Deleted
            } else {
                val (open, done) = listOrder(listItems)
                ListDetailState.Shown(list, open, done)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDetailState.Loading)

    fun toggleDone(item: ItemEntity) {
        viewModelScope.launch { items.setDone(item.id, !item.done) }
    }

    // The ✕ next to an item (after the dialog). Cancels its reminder alarm, if any.
    fun removeItem(item: ItemEntity) {
        viewModelScope.launch { items.delete(item.id) }
    }

    // The "Add item" field. No time: set one from the item's sheet.
    fun addItem(text: String) {
        viewModelScope.launch { newItems.addListItem(text, listId, dueAt = null, recordingId = null) }
    }

    fun updateText(item: ItemEntity, text: String) {
        viewModelScope.launch { items.updateText(item.id, text) }
    }

    // Sets, moves or (null) clears the reminder time; the alarm follows.
    fun updateDue(item: ItemEntity, dueAt: Long?) {
        viewModelScope.launch { items.updateDue(item.id, dueAt) }
    }

    fun rename(title: String) {
        viewModelScope.launch { lists.rename(listId, title) }
    }

    // The screen goes back straight after.
    fun deleteList(moveItemsToTodo: Boolean) {
        viewModelScope.launch { lists.delete(listId, moveItemsToTodo) }
    }
}
