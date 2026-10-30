package dev.piatra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemKind

// A dialog is an ordinary composable: it's on screen while the caller's code draws it, and gone
// once a state change stops drawing it. onDismiss: tapped outside, Back, or Cancel.

// "Delete recording?". If the recording created items, it offers two choices (as upstream does):
// keep the items, or delete them too. onDelete gets alsoItems.
@Composable
fun DeleteRecordingDialog(itemCount: Int, onDismiss: () -> Unit, onDelete: (alsoItems: Boolean) -> Unit) {
    if (itemCount == 0) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete recording?") },
            text = { Text("The audio and its transcript are deleted for good.") },
            confirmButton = {
                TextButton(onClick = { onDelete(false) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    } else {
        val itemWord = if (itemCount == 1) "item" else "items"
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete recording?") },
            text = {
                // The two choices as full-width rows: AlertDialog's button slots only fit two buttons.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This recording created $itemCount $itemWord.")
                    DeleteChoice("Delete recording only", "Keep the $itemWord") { onDelete(false) }
                    DeleteChoice("Delete recording and $itemWord", "Reminders won't ring") { onDelete(true) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeleteChoice(label: String, detail: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

// "Remove …?" for one item the agent created.
@Composable
fun RemoveItemDialog(item: ItemEntity, listTitles: Map<Long, String>, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemLine(item, listTitles)
                // Android gives apps no reliable way to delete an alarm or timer from the Clock app.
                if (item.kind == ItemKind.ALARM || item.kind == ItemKind.TIMER) {
                    Text("This only removes it from Piatra. Turn it off in the Clock app too.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// A list's name: "New list" on the Lists tab, "Rename list" on a list's screen.
@Composable
fun ListNameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// "Delete list?". With items, two choices (like DeleteRecordingDialog): move them to Todo, or
// delete them too. onDelete gets moveItemsToTodo.
@Composable
fun DeleteListDialog(listTitle: String, itemCount: Int, onDismiss: () -> Unit, onDelete: (moveItemsToTodo: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $listTitle?") },
        text = {
            if (itemCount == 0) {
                Text("The list is empty.")
            } else {
                val itemWord = if (itemCount == 1) "item" else "items"
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("It has $itemCount $itemWord.")
                    DeleteChoice("Delete list, move $itemWord to Todo", "Reminders still ring") { onDelete(true) }
                    DeleteChoice("Delete list and $itemWord", "Reminders won't ring") { onDelete(false) }
                }
            }
        },
        confirmButton = {
            if (itemCount == 0) {
                TextButton(onClick = { onDelete(false) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
