package dev.piatra.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.core.LogLevel
import dev.piatra.core.LogLine
import dev.piatra.core.LogType
import org.koin.compose.viewmodel.koinViewModel
import dev.piatra.R
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size

// ☰ → Logs: what the app has been writing to Android's log, newest at the bottom.
// Chips pick the types; Copy puts the lines shown on the clipboard (to paste into a chat).
@Composable
fun LogsScreen(onBack: () -> Unit, viewModel: LogsViewModel = koinViewModel()) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val types by viewModel.types.collectAsStateWithLifecycle()
    val warningsOnly by viewModel.warningsOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // Follow new lines, like `adb logcat` does.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            BackButton(onBack)
            Text("Logs", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { copy(context, lines) }, enabled = lines.isNotEmpty()) { Text("Copy") }
        }
        // One chip per type, side by side; swipe sideways for more.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = warningsOnly, onClick = viewModel::toggleWarningsOnly, label = { Text("Warnings") },
                leadingIcon = { Icon(painterResource(R.drawable.ph_warning), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
            for (type in LogType.entries) {
                FilterChip(selected = type in types, onClick = { viewModel.toggle(type) }, label = { Text(type.label) })
            }
        }
        if (lines.isEmpty()) {
            Text(
                "Nothing yet. Only lines since the app last started are kept.",
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(lines) { LogRow(it) }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        LogLevel.VERBOSE, LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.INFO -> Color.Unspecified
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            "${line.time.substringAfter(' ')}  ${line.tag}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(line.message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun copy(context: Context, lines: List<LogLine>) {
    val text = lines.joinToString("\n") { "${it.time} ${it.level.name.first()} ${it.tag}: ${it.message}" }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Piatra logs", text))
    Toast.makeText(context, "Copied ${lines.size} lines", Toast.LENGTH_SHORT).show()
}
