package dev.piatra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.data.ItemEntity
import dev.piatra.data.ItemKind
import dev.piatra.data.RecordingStatus
import org.koin.compose.viewmodel.koinViewModel

// One recording on its own screen: when, how long, its status, the transcript and what it
// led to. Play it, delete it, try it again, open or remove one of the things it created.
@Composable
fun RecordingDetailScreen(
    onBack: () -> Unit,
    onOpenNote: (itemId: Long) -> Unit,
    onOpenList: (listId: Long) -> Unit,
    viewModel: RecordingDetailViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listTitles by viewModel.listTitles.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    // Which dialog is open, if any.
    var askDelete by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<ItemEntity?>(null) }

    // ON_STOP: this screen is left (Back) or the app goes to the background. Stop talking.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.stopPlayback()
    }

    Column(
        // verticalScroll: a long transcript can scroll.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text("Recording", style = MaterialTheme.typography.titleLarge)
            // Delete sits up here, as on the note screen: three buttons don't fit in the row below.
            Spacer(Modifier.weight(1f))
            if (state is RecordingDetailState.Shown) {
                TextButton(onClick = { askDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        // A local val, so the `when` can smart-cast it (state is a delegated property).
        when (val current = state) {
            RecordingDetailState.Loading -> {}
            RecordingDetailState.Deleted -> Text("This recording was deleted.")
            is RecordingDetailState.Shown -> {
                val recording = current.recording
                Text(formatDateTime(recording.createdAt), style = MaterialTheme.typography.titleMedium)
                Text("${formatSeconds(recording.durationMillis)} · ${statusLabel(recording)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.togglePlayback(recording) }) {
                        Text(if (playingId == recording.id) "Stop" else "Play")
                    }
                    // Finished one way or the other: offer a fresh try. "Try again" for a failure.
                    when (recording.status) {
                        RecordingStatus.FAILED ->
                            OutlinedButton(onClick = viewModel::transcribeAgain) { Text("Try again") }
                        RecordingStatus.DONE ->
                            OutlinedButton(onClick = viewModel::transcribeAgain) { Text("Transcribe again") }
                        else -> {}
                    }
                }
                recording.transcript?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                RecordingOutcome(
                    recording, current.items, listTitles,
                    onItemClick = { item ->
                        when (item.kind) {
                            ItemKind.NOTE -> onOpenNote(item.id)
                            else -> item.listId?.let(onOpenList)
                        }
                    },
                    onRemoveItem = { toRemove = it },
                    showDecisions = true,
                )

                if (askDelete) {
                    DeleteRecordingDialog(
                        itemCount = current.items.size,
                        onDismiss = { askDelete = false },
                        onDelete = { alsoItems ->
                            viewModel.delete(recording, alsoItems)
                            askDelete = false
                            onBack()
                        },
                    )
                }
                toRemove?.let { item ->
                    RemoveItemDialog(
                        item, listTitles,
                        onDismiss = { toRemove = null },
                        onRemove = {
                            viewModel.removeItem(item)
                            toRemove = null
                        },
                    )
                }
            }
        }
    }
}
