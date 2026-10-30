package dev.piatra.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.koin.compose.viewmodel.koinViewModel

// One note, on its own screen. The whole screen is the text: tap anywhere in it and type.
// Saving is automatic (see NoteDetailViewModel).
@Composable
fun NoteDetailScreen(
    onBack: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    viewModel: NoteDetailViewModel = koinViewModel(),
) {
    val state = viewModel.state
    var confirmDelete by remember { mutableStateOf(false) }

    // ON_STOP: the app goes to the background (or the screen is left). Save now: Android may
    // stop a background app without warning.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.save() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            if (state is NoteState.Shown) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        when (state) {
            NoteState.Loading -> {}
            NoteState.Missing -> Text("This note was deleted.")
            is NoteState.Shown -> {
                Row {
                    Text(
                        state.createdAt?.let { formatDateTime(it) } ?: "New note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    state.recordingId?.let { id ->
                        TextButton(onClick = { onOpenRecording(id) }) { ForwardText("From recording") }
                    }
                }
                NoteText(viewModel.draft, viewModel::edit, focusNow = viewModel.isNew)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete note?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// BasicTextField: a text field with no box, label or underline, so it reads like a page.
// It fills the rest of the screen and scrolls itself once the text is longer than that.
@Composable
private fun NoteText(text: String, onChange: (String) -> Unit, focusNow: Boolean) {
    // A new note opens with the cursor in it and the keyboard up.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusNow) focus.requestFocus() }

    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = text,
        onValueChange = onChange,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxSize().focusRequester(focus).padding(bottom = 16.dp),
        // decorationBox: what's drawn around the text. Here, a grey hint while it's empty.
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth()) {
                if (text.isEmpty()) {
                    Text("Note", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                innerTextField()
            }
        },
    )
}
