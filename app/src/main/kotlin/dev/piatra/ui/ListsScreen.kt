package dev.piatra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.data.ItemEntity
import org.koin.compose.viewmodel.koinViewModel
import dev.piatra.R

// How many open items each list shows on the Lists tab; the rest are behind "›".
private const val PREVIEW_ITEMS = 3

// The Lists tab: every list with its first few open items. Tap a list (or an item) to see all
// of it and edit there; "+ New list" makes one.
@Composable
fun ListsScreen(onOpenList: (Long) -> Unit, viewModel: ListsViewModel = koinViewModel()) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        item { Text("Lists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)) }
        // key: lets Compose tell the lists apart when one is added, so rows don't get mixed up.
        items(lists, key = { it.list.id }) { summary ->
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenList(summary.list.id) }.padding(vertical = 12.dp),
                ) {
                    Text(summary.list.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    ForwardText("${summary.open.size}", color = MaterialTheme.colorScheme.primary)
                }
                for (item in summary.open.take(PREVIEW_ITEMS)) {
                    ListItemRow(item, onToggle = { viewModel.toggleDone(item) }, onOpen = { onOpenList(summary.list.id) })
                }
                val more = summary.open.size - PREVIEW_ITEMS
                if (more > 0) {
                    Text(
                        "+ $more more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onOpenList(summary.list.id) }.padding(start = 48.dp, bottom = 4.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
        item {
            TextButton(onClick = { creating = true }, modifier = Modifier.padding(vertical = 8.dp)) { Text("+ New list") }
        }
    }

    if (creating) {
        ListNameDialog(
            "New list", "", "Create",
            onDismiss = { creating = false },
            onConfirm = { title ->
                creating = false
                viewModel.createList(title, onCreated = onOpenList)
            },
        )
    }
}

// One list item: a checkbox to tick it off, its text and reminder time, and (on a list's own
// screen) a ✕ to remove it. onOpen: tapping the text opens the recording it came from; null when
// that recording was deleted.
@Composable
fun ListItemRow(item: ItemEntity, onToggle: () -> Unit, onOpen: (() -> Unit)?, onRemove: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = item.done, onCheckedChange = { onToggle() })
        Column(
            modifier = Modifier.weight(1f)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(vertical = 4.dp),
        ) {
            Text(
                item.text,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            item.dueAt?.let {
                IconText(
                    R.drawable.ph_bell, formatDateTime(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 14.dp,
                )
            }
        }
        if (onRemove != null) {
            RemoveButton(onRemove)
        }
    }
}
