package dev.piatra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.data.ItemEntity
import org.koin.compose.viewmodel.koinViewModel
import dev.piatra.R

// One list on its own screen: an "Add item" field, the open items, then the done ones folded
// away under "Done (n)". Tap an item to edit it in a sheet; ⋮ renames or deletes the list.
@Composable
fun ListDetailScreen(onBack: () -> Unit, onOpenRecording: (Long) -> Unit, viewModel: ListDetailViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var toRemove by remember { mutableStateOf<ItemEntity?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    // The item open in the sheet, by id: the sheet then always shows the row as it is now.
    var sheetItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        val current = state
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                if (current is ListDetailState.Shown) {
                    Text(current.list.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    // ⋮ opens a small menu anchored to it (the Box).
                    Box {
                        IconOnlyButton(R.drawable.ph_dots_three_vertical, "List menu", { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename list") }, onClick = { menuOpen = false; renaming = true })
                            // Todo and Shopping stay: the agent would make them again on the next "buy …".
                            if (current.list.seed == null) {
                                DropdownMenuItem(
                                    text = { Text("Delete list", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; deleting = true },
                                )
                            }
                        }
                    }
                }
            }
        }
        when (current) {
            ListDetailState.Loading -> {}
            ListDetailState.Deleted -> item { Text("This list was deleted.") }
            is ListDetailState.Shown -> {
                item(key = "add") { AddItemField(viewModel::addItem) }
                if (current.open.isEmpty()) {
                    item {
                        Text(
                            "Nothing to do here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(current.open, key = { it.id }) { item ->
                    Row(Modifier.animateItem()) { ItemRow(item, viewModel, onOpen = { sheetItemId = item.id }) { toRemove = item } }
                }
                if (current.done.isNotEmpty()) {
                    item(key = "done-header") {
                        IconText(
                            if (showDone) R.drawable.ph_caret_down else R.drawable.ph_caret_right,
                            "Done (${current.done.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().animateItem().clickable { showDone = !showDone }
                                .padding(vertical = 12.dp),
                        )
                    }
                    if (showDone) {
                        items(current.done, key = { it.id }) { item ->
                            Row(Modifier.animateItem()) { ItemRow(item, viewModel, onOpen = { sheetItemId = item.id }) { toRemove = item } }
                        }
                    }
                }
            }
        }
    }

    val shown = state as? ListDetailState.Shown
    // The sheet's item, if it still exists (null after it was deleted).
    val sheetItem = shown?.let { (it.open + it.done).firstOrNull { item -> item.id == sheetItemId } }
    if (sheetItem != null) {
        ItemSheet(
            sheetItem,
            onClose = { sheetItemId = null },
            onSaveText = { viewModel.updateText(sheetItem, it) },
            onSetDue = { viewModel.updateDue(sheetItem, it) },
            onDelete = { toRemove = sheetItem },
            onOpenRecording = onOpenRecording,
        )
    }

    if (renaming && shown != null) {
        ListNameDialog(
            "Rename list", shown.list.title, "Rename",
            onDismiss = { renaming = false },
            onConfirm = {
                viewModel.rename(it)
                renaming = false
            },
        )
    }
    if (deleting && shown != null) {
        DeleteListDialog(
            shown.list.title, shown.open.size + shown.done.size,
            onDismiss = { deleting = false },
            onDelete = { moveItemsToTodo ->
                deleting = false
                viewModel.deleteList(moveItemsToTodo)
                onBack()
            },
        )
    }

    toRemove?.let { item ->
        RemoveItemDialog(
            item, emptyMap(),
            onDismiss = { toRemove = null },
            onRemove = {
                viewModel.removeItem(item)
                toRemove = null
            },
        )
    }
}

@Composable
private fun ItemRow(item: ItemEntity, viewModel: ListDetailViewModel, onOpen: () -> Unit, onRemove: () -> Unit) {
    ListItemRow(item, onToggle = { viewModel.toggleDone(item) }, onOpen = onOpen, onRemove = onRemove)
}

// "Add item": type, press Enter, and it's on the list. The field empties and keeps the keyboard,
// ready for the next one.
@Composable
private fun AddItemField(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        if (text.isNotBlank()) onAdd(text.trim())
        text = ""
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Add item") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        // Our own onDone replaces the default (closing the keyboard).
        keyboardActions = KeyboardActions(onDone = { add() }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}
