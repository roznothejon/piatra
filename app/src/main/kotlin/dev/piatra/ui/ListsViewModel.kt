package dev.piatra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.piatra.core.agent.DefaultLists
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemRepository
import dev.piatra.data.ListEntity
import dev.piatra.data.ListRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// One list on the Lists tab, with its open items in screen order.
data class ListSummary(val list: ListEntity, val open: List<ItemEntity>)

class ListsViewModel(
    private val items: ItemRepository,
    private val listRepository: ListRepository,
) : ViewModel() {
    init {
        // The built-in lists are normally made on first use; make them now, so the tab never
        // starts empty. runCatching: if the agent makes one at the same moment, the unique
        // seed makes our insert fail, which is fine (the list exists either way).
        viewModelScope.launch {
            runCatching { listRepository.idForSeed(DefaultLists.TODO) }
            runCatching { listRepository.idForSeed(DefaultLists.SHOPPING) }
        }
    }

    val lists: StateFlow<List<ListSummary>> =
        combine(listRepository.observeAll(), items.observeListItems()) { allLists, allItems ->
            val byList = allItems.groupBy { it.listId }
            allLists.sortedBy { listRank(it) }.map { list ->
                ListSummary(list, listOrder(byList[list.id].orEmpty()).first)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleDone(item: ItemEntity) {
        viewModelScope.launch { items.setDone(item.id, !item.done) }
    }

    // "+ New list". A name you already have (ignoring capitals) opens that list instead of
    // making a second one, as the "create a new list" shortcut does.
    fun createList(title: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val existing = listRepository.all().firstOrNull { it.title.equals(title, ignoreCase = true) }
            onCreated(existing?.id ?: listRepository.create(title))
        }
    }
}

// Todo first, then Shopping, then the lists you made (sortedBy keeps their oldest-first order).
private fun listRank(list: ListEntity): Int = when (list.seed) {
    DefaultLists.TODO -> 0
    DefaultLists.SHOPPING -> 1
    else -> 2
}

// A list's items split into (open, done). Open: reminders first, soonest first, then the rest
// in the order they were added. Done: most recently added first.
internal fun listOrder(items: List<ItemEntity>): Pair<List<ItemEntity>, List<ItemEntity>> {
    val (done, open) = items.partition { it.done }
    // compareBy with several selectors: sort by the first, break ties with the next.
    // false sorts before true, so timed items (dueAt != null) come first.
    val openSorted = open.sortedWith(compareBy({ it.dueAt == null }, { it.dueAt }, { it.createdAt }))
    return openSorted to done.sortedByDescending { it.createdAt }
}
