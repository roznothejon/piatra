package dev.piatra.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.android.Counts
import dev.piatra.android.ExportState
import dev.piatra.android.ImportState
import dev.piatra.core.AppFont
import dev.piatra.core.Appearance
import dev.piatra.core.ModelInfo
import dev.piatra.core.ModelState
import dev.piatra.core.ThemeMode
import dev.piatra.core.obsidian.ObsidianMode
import dev.piatra.core.obsidian.ObsidianNoteFormatter
import dev.piatra.data.SNOOZE_CHOICES
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

// The settings screen, opened from the ☰ menu on the Recordings tab. The model rows and the
// Tasker/Obsidian rows used to sit on the Recordings tab; they're unchanged apart from the
// speech model choice.
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onOpenLab: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()) {
    val speechRows by viewModel.speechRows.collectAsStateWithLifecycle()
    val agentRow by viewModel.agentRow.collectAsStateWithLifecycle()
    val sendToTasker by viewModel.sendToTasker.collectAsStateWithLifecycle()
    val obsidian by viewModel.obsidian.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val useModelText by viewModel.useModelText.collectAsStateWithLifecycle()
    val keepReminders by viewModel.keepReminders.collectAsStateWithLifecycle()
    val snoozeMinutes by viewModel.snoozeMinutes.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    // Re-checked on ON_RESUME too, in case Tasker was installed while Piatra was in the background.
    var taskerInstalled by remember { mutableStateOf(viewModel.isTaskerInstalled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        taskerInstalled = viewModel.isTaskerInstalled()
        viewModel.checkObsidianAccess()
    }

    // The system folder picker, for the Obsidian vault. Gives back the folder's URI, or null
    // if the user backed out.
    val obsidianFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { folder ->
        folder?.let(viewModel::chooseObsidianFolder)
    }

    // Android's "Save as" dialog: the user picks a place and a name (we suggest one); it gives
    // back a Uri the app may write that one file to, or null if they backed out.
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { file ->
        file?.let(viewModel::export)
    }

    // The "Open" dialog, for a backup zip. Some file apps label zips differently, so any file
    // can be picked; the import refuses what isn't a Piatra backup.
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file ->
        file?.let(viewModel::readBackup)
    }

    // verticalScroll: a plain Column that scrolls. Fine for a short, fixed set of rows
    // (a LazyColumn is for long lists).
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            BackButton(onBack)
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        SectionTitle("Speech model")
        Text(
            "Turns recordings into text. English is the most accurate for English; Multilingual " +
                "knows 25 European languages. The agent only understands English.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        for (row in speechRows) {
            ModelSection(
                row = row,
                cactusSupported = viewModel.cactusSupported,
                onChoose = { viewModel.chooseSpeechModel(row.model) },
                onDownload = { viewModel.downloadModel(row.model) },
                onCancel = { viewModel.cancelModelDownload(row.model) },
                onDelete = { viewModel.deleteModel(row.model) },
            )
        }
        SectionTitle("Agent model")
        ModelSection(
            row = agentRow,
            cactusSupported = viewModel.cactusSupported,
            onDownload = { viewModel.downloadModel(agentRow.model) },
            onCancel = { viewModel.cancelModelDownload(agentRow.model) },
            onDelete = { viewModel.deleteModel(agentRow.model) },
        )
        HorizontalDivider()

        SectionTitle("Agent")
        ListItem(
            modifier = Modifier.clickable(onClick = onOpenShortcuts),
            headlineContent = { ForwardText("Shortcut phrases") },
            supportingContent = { Text("\"To do …\", \"Buy …\", \"Note …\": what each one does") },
        )
        ListItem(
            headlineContent = { Text("Use the model's wording") },
            supportingContent = {
                Text(
                    if (useModelText) "Notes and reminders keep the model's shorter text when it's word for word what you said"
                    else "Notes and reminders save exactly what you said. List items are still split by the model."
                )
            },
            trailingContent = { Switch(checked = useModelText, onCheckedChange = viewModel::setUseModelText) },
        )
        HorizontalDivider()

        ReminderRows(keepReminders, snoozeMinutes, viewModel::setKeepReminders, viewModel::setSnoozeMinutes)
        HorizontalDivider()

        SectionTitle("Also send to")
        TaskerRow(installed = taskerInstalled, on = sendToTasker, onChange = viewModel::setSendToTasker)
        ObsidianRows(
            obsidian,
            onChoose = { obsidianFolderPicker.launch(null) },
            onForget = viewModel::stopObsidian,
            onOnChange = viewModel::setSendToObsidian,
            onOneNoteChange = viewModel::setObsidianOneNote,
        )
        HorizontalDivider()

        AppearanceRows(appearance, viewModel::setTheme, viewModel::setWallpaperColors, viewModel::setFont)
        HorizontalDivider()

        BackupRows(exportState, onExport = { exportPicker.launch("piatra-${java.time.LocalDate.now()}.zip") })
        RestoreRow(importState, onRestore = { importPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) })
        RestoreDialog(importState, onConfirm = viewModel::confirmRestore, onDismiss = viewModel::dismissRestore)
        HorizontalDivider()

        SectionTitle("Developer")
        ListItem(
            modifier = Modifier.clickable(onClick = onOpenLab),
            headlineContent = { ForwardText("Agent lab") },
            supportingContent = { Text("Try phrases on the agent, or run the test set") },
        )
    }
}

// Persistent reminders, and how long Snooze puts one off (segmented buttons, like the theme).
@Composable
private fun ReminderRows(keep: Boolean, snoozeMinutes: Int, onKeep: (Boolean) -> Unit, onSnooze: (Int) -> Unit) {
    SectionTitle("Reminders")
    ListItem(
        headlineContent = { Text("Keep reminders until done") },
        supportingContent = {
            Text(
                if (keep) "A reminder rings once, then stays in your notifications until you tick it off"
                else "A reminder rings once; tapping or swiping its notification removes it"
            )
        },
        trailingContent = { Switch(checked = keep, onCheckedChange = onKeep) },
    )
    Text(
        "Snooze for (minutes)",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SNOOZE_CHOICES.forEachIndexed { index, minutes ->
            SegmentedButton(
                selected = snoozeMinutes == minutes,
                onClick = { onSnooze(minutes) },
                shape = SegmentedButtonDefaults.itemShape(index, SNOOZE_CHOICES.size),
                icon = {}, // no tick: five buttons are narrow
            ) { Text("$minutes", maxLines = 1) }
        }
    }
}

// Theme (segmented buttons: one row of joined buttons, one chosen), wallpaper colours, and the
// font, each option written in its own font so they can be compared.
@Composable
private fun AppearanceRows(
    appearance: Appearance,
    onTheme: (ThemeMode) -> Unit,
    onWallpaperColors: (Boolean) -> Unit,
    onFont: (AppFont) -> Unit,
) {
    SectionTitle("Appearance")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        ThemeMode.entries.forEachIndexed { index, theme ->
            SegmentedButton(
                selected = appearance.theme == theme,
                onClick = { onTheme(theme) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
            ) { Text(theme.label, maxLines = 1) }
        }
    }
    // Wallpaper colours need Android 12 (API 31).
    if (Build.VERSION.SDK_INT >= 31) {
        ListItem(
            headlineContent = { Text("Wallpaper colours") },
            supportingContent = { Text(if (appearance.wallpaperColors) "Colours from your wallpaper" else "Piatra's own stone colours") },
            trailingContent = { Switch(checked = appearance.wallpaperColors, onCheckedChange = onWallpaperColors) },
        )
    }
    for (font in AppFont.entries) {
        ListItem(
            modifier = Modifier.clickable { onFont(font) },
            leadingContent = { RadioButton(selected = appearance.font == font, onClick = { onFont(font) }) },
            headlineContent = { Text(font.label, fontFamily = fontFamilyFor(font)) },
            supportingContent = { Text("Remind me to call mom at 5 · 0:07.3", fontFamily = fontFamilyFor(font)) },
        )
    }
}

// Export and backup: one row that starts the export and shows how it's going.
@Composable
private fun BackupRows(state: ExportState, onExport: () -> Unit) {
    SectionTitle("Backup")
    val running = state is ExportState.Running
    ListItem(
        modifier = Modifier.clickable(enabled = !running, onClick = onExport),
        headlineContent = { Text("Export everything") },
        supportingContent = {
            Column {
                Text(
                    when (state) {
                        ExportState.Idle -> "One zip: recordings (as WAV), transcripts, notes and lists as Markdown, " +
                            "and a backup file to restore from. Not included: models, the paired board, the Obsidian folder."
                        is ExportState.Running -> if (state.total == 0) "Reading the database…" else "Exporting… ${state.done} of ${state.total} files"
                        is ExportState.Done -> "Saved: ${state.recordings} recordings, ${state.items} items"
                        is ExportState.Failed -> "Export failed: ${state.message}"
                    },
                    color = if (state is ExportState.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                if (state is ExportState.Running && state.total > 0) {
                    // keepScreenOn: the phone doesn't go to sleep while the progress bar shows.
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).keepScreenOn(),
                    )
                }
            }
        },
    )
}

// Restore from a backup: replaces everything (backup, part 2).
@Composable
private fun RestoreRow(state: ImportState, onRestore: () -> Unit) {
    val busy = state == ImportState.Reading || state == ImportState.Restoring
    ListItem(
        modifier = Modifier.clickable(enabled = !busy, onClick = onRestore),
        headlineContent = { Text("Restore from a backup") },
        supportingContent = {
            Text(
                when (state) {
                    ImportState.Reading -> "Reading the backup…"
                    ImportState.Restoring -> "Restoring…"
                    else -> "Replaces all recordings, notes, lists and settings with the backup's"
                }
            )
        },
    )
}

// Asks before replacing anything, says what came of it, or why it couldn't.
@Composable
private fun RestoreDialog(state: ImportState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        is ImportState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Replace everything?") },
            text = {
                val made = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.exportedAt))
                Text(
                    "The backup from $made has ${counts(state.backup)}.\n\n" +
                        "It replaces what's on this phone now: ${counts(state.now)}. This can't be undone; " +
                        "export first if you might want today's data back.\n\nThe paired board and the Obsidian folder stay as they are."
                )
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Replace", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is ImportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Restored") },
            text = { Text("Now on this phone: ${counts(state.restored)}.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Couldn't restore") },
            text = {
                Text(
                    state.message + "\n\n" +
                        if (state.replaced) "The backup was only partly restored: try restoring it again." else "Nothing was changed."
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        else -> {}
    }
}

private fun counts(c: Counts) =
    "${c.recordings} recordings, ${c.items} items and ${c.lists} lists"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

// The "Send to Tasker" switch. Greyed out while Tasker isn't installed.
@Composable
fun TaskerRow(installed: Boolean, on: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text("Send to Tasker") },
        supportingContent = {
            Text(if (installed) "Notes, reminders and list items also go to Tasker" else "Tasker isn't installed")
        },
        trailingContent = { Switch(checked = on && installed, onCheckedChange = onChange, enabled = installed) },
    )
}

// Obsidian: pick the vault folder, then a switch to send notes there or not, the
// folder (change or forget it) and where in it notes go.
@Composable
fun ObsidianRows(
    obsidian: ObsidianUi?,
    onChoose: () -> Unit,
    onForget: () -> Unit,
    onOnChange: (Boolean) -> Unit,
    onOneNoteChange: (Boolean) -> Unit,
) {
    if (obsidian == null) {
        ListItem(
            headlineContent = { Text("Send to Obsidian") },
            supportingContent = { Text("Also save notes into your vault as Markdown") },
            trailingContent = { TextButton(onClick = onChoose) { Text("Choose folder") } },
        )
        return
    }
    val where = when (obsidian.mode) {
        ObsidianMode.ONE_FILE_PER_NOTE -> "${ObsidianNoteFormatter.INBOX_FOLDER}, one file per note"
        ObsidianMode.ONE_NOTE -> ObsidianNoteFormatter.ONE_NOTE_NAME
    }
    ListItem(
        headlineContent = { Text("Send to Obsidian") },
        supportingContent = {
            when {
                !obsidian.on -> Text("Off: notes stay in Piatra only")
                obsidian.hasAccess -> Text("Notes also go to ${obsidian.folderName}/$where")
                else -> Text("Can't reach ${obsidian.folderName}. Choose the folder again.", color = MaterialTheme.colorScheme.error)
            }
        },
        trailingContent = { Switch(checked = obsidian.on, onCheckedChange = onOnChange) },
    )
    if (!obsidian.on) return
    ListItem(
        headlineContent = { Text("Vault folder") },
        supportingContent = { Text(obsidian.folderName) },
        trailingContent = {
            Row {
                TextButton(onClick = onChoose) { Text("Change") }
                TextButton(onClick = onForget) { Text("Forget") }
            }
        },
    )
    ListItem(
        headlineContent = { Text("One note for all") },
        supportingContent = { Text("Add each note to ${ObsidianNoteFormatter.ONE_NOTE_NAME}") },
        trailingContent = {
            Switch(checked = obsidian.mode == ObsidianMode.ONE_NOTE, onCheckedChange = onOneNoteChange)
        },
    )
}


// One model: its download state and the button for the next action. A speech model also gets a
// radio button for choosing it (onChoose); row.chosen is null for the agent model.
@Composable
fun ModelSection(
    row: ModelRow,
    cactusSupported: Boolean,
    onChoose: () -> Unit = {},
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val model = row.model
    val state = row.state
    ListItem(
        // The download only runs while the app is open, so don't let the screen turn off.
        modifier = if (state is ModelState.Downloading) Modifier.keepScreenOn() else Modifier,
        leadingContent = row.chosen?.let { chosen -> { RadioButton(selected = chosen, onClick = onChoose) } },
        headlineContent = { Text("${model.label}, ${formatMegabytes(model.sizeBytes)}") },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    modelStatusLabel(model, state),
                    color = if (state is ModelState.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                when (state) {
                    // progress: a lambda, so only the bar redraws as it moves.
                    is ModelState.Downloading -> LinearProgressIndicator(
                        progress = { state.downloadedBytes.toFloat() / state.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // No progress given: an "indeterminate" bar that just animates.
                    ModelState.Unpacking -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else -> {}
                }
                if (!cactusSupported) {
                    Text(
                        "This phone's CPU can't run Cactus, so on-device models won't work.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            when (state) {
                is ModelState.NotDownloaded -> TextButton(onClick = onDownload) {
                    Text(if (state.downloadedBytes > 0) "Resume" else "Download")
                }
                is ModelState.Downloading -> TextButton(onClick = onCancel) { Text("Pause") }
                ModelState.Unpacking -> {}
                ModelState.Ready -> TextButton(onClick = onDelete) { Text("Delete") }
                is ModelState.Failed -> TextButton(onClick = onDownload) { Text("Retry") }
            }
        },
    )
}


private fun modelStatusLabel(model: ModelInfo, state: ModelState): String = when (state) {
    is ModelState.NotDownloaded ->
        if (state.downloadedBytes == 0L) "Not downloaded. Keep Piatra open while it downloads."
        else "Paused at ${formatProgress(state.downloadedBytes, model.sizeBytes)}"
    is ModelState.Downloading -> "Downloading… ${formatProgress(state.downloadedBytes, state.totalBytes)}"
    ModelState.Unpacking -> "Unpacking…"
    ModelState.Ready -> "Ready"
    is ModelState.Failed -> "Download failed: ${state.message}"
}


// 367_108_112 -> "367 MB" (decimal megabytes, as download sizes usually are).
private fun formatMegabytes(bytes: Long): String = "${bytes / 1_000_000} MB"

// "43% (158 of 367 MB)"
private fun formatProgress(done: Long, total: Long): String =
    "${done * 100 / total}% (${done / 1_000_000} of ${formatMegabytes(total)})"

