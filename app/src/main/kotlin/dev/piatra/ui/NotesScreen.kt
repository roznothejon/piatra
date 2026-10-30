package dev.piatra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.data.ItemEntity
import org.koin.compose.viewmodel.koinViewModel

// The Notes tab: every note, newest first. Tap one to open it; + writes a new one.
@Composable
fun NotesScreen(onOpenNote: (Long?) -> Unit, viewModel: NotesViewModel = koinViewModel()) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var toRemove by remember { mutableStateOf<ItemEntity?>(null) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        item { Text("Notes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)) }
        if (notes.isEmpty()) {
            item {
                Text(
                    "No notes yet. Start a recording with \"note …\", or tap + to type one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(notes, key = { it.id }) { note ->
            Column(Modifier.animateItem()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f).clickable { onOpenNote(note.id) }.padding(vertical = 8.dp),
                    ) {
                        // A long note shows its first lines; the note screen has the rest.
                        Text(
                            note.text,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatDateTime(note.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RemoveButton({ toRemove = note })
                }
                HorizontalDivider()
            }
        }
        // Room at the bottom, so the last note isn't hidden behind the + button.
        item { Spacer(Modifier.height(88.dp)) }
    }
    // A floating action button: the screen's main action, floating above the list.
    FloatingActionButton(
        onClick = { onOpenNote(null) },
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
    }

    toRemove?.let { note ->
        RemoveItemDialog(
            note, emptyMap(),
            onDismiss = { toRemove = null },
            onRemove = {
                viewModel.removeNote(note)
                toRemove = null
            },
        )
    }
}
