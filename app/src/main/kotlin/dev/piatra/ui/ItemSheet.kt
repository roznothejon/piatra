package dev.piatra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.piatra.data.ItemEntity
import kotlinx.coroutines.launch
import dev.piatra.R

// One list item, edited in a bottom sheet: a panel that slides up over the list. Less than a
// whole screen, for something that's often two words.
// item: the live row (so a new reminder time shows straight away). The text is a draft, saved
// when the sheet closes (swiped down, tapped outside, Back, or Done on the keyboard).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(
    item: ItemEntity,
    onClose: () -> Unit,
    onSaveText: (String) -> Unit,
    onSetDue: (Long?) -> Unit,
    // Delete: the caller asks "Remove this?" first, as the ✕ does.
    onDelete: () -> Unit,
    onOpenRecording: ((Long) -> Unit)?,
    now: () -> Long = System::currentTimeMillis,
) {
    // skipPartiallyExpanded: open fully at once; the sheet is short anyway.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var draft by remember(item.id) { mutableStateOf(item.text) }
    var picking by remember { mutableStateOf(false) }

    // An emptied text is ignored rather than deleting the item: Delete is the button below.
    val close = {
        val text = draft.trim()
        if (text.isNotEmpty() && text != item.text) onSaveText(text)
        onClose()
    }
    // Slide the sheet away first, then close (the swipe does this by itself).
    val hideThenClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { close() } }

    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { hideThenClose() }),
            )
            // The reminder row: tap to pick a time; Clear removes it (and its alarm).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 12.dp),
            ) {
                val due = item.dueAt
                IconText(
                    R.drawable.ph_bell,
                    if (due != null) formatDateTime(due) else "Add a reminder time",
                    color = if (due != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (due != null) TextButton(onClick = { onSetDue(null) }) { Text("Clear") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val recordingId = item.recordingId
                if (recordingId != null && onOpenRecording != null) {
                    TextButton(onClick = { close(); onOpenRecording(recordingId) }) { ForwardText("From recording") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onClose(); onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (picking) {
        DueTimePicker(
            // No time yet: start from the next full hour.
            initial = item.dueAt ?: (now() / HOUR + 1) * HOUR,
            onDismiss = { picking = false },
            onPicked = {
                picking = false
                onSetDue(it)
            },
        )
    }
}

private const val HOUR = 60 * 60 * 1000L
