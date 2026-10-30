package dev.piatra.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.core.agent.ShortcutAction
import dev.piatra.core.agent.ShortcutRule
import dev.piatra.core.agent.parsePhrases
import dev.piatra.data.ListEntity
import org.koin.compose.viewmodel.koinViewModel

// Settings -> Shortcut phrases: the spoken shortcuts, each with an on/off switch.
// Tap one to edit it in a sheet; "+ New shortcut" adds one; Reset puts the defaults back.
@Composable
fun ShortcutRulesScreen(onBack: () -> Unit, viewModel: ShortcutRulesViewModel = koinViewModel()) {
    val rules by viewModel.all.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    // The rule open in the sheet: a saved one, or a new one (id 0). null = no sheet.
    var editing by remember { mutableStateOf<ShortcutRule?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            BackButton(onBack)
            Text("Shortcut phrases", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmReset = true }) { Text("Reset") }
        }
        Text(
            "When a recording starts with one of these phrases, the shortcut decides what happens " +
                "and the agent model isn't asked. Longer phrases are tried first.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(rules, key = { it.id }) { rule ->
                ListItem(
                    modifier = Modifier.clickable { editing = rule },
                    headlineContent = { Text(rule.phrases.joinToString(" · ")) },
                    supportingContent = { Text(ruleOutcome(rule, lists)) },
                    trailingContent = { Switch(checked = rule.enabled, onCheckedChange = { viewModel.setEnabled(rule, it) }) },
                )
            }
            item {
                TextButton(
                    onClick = { editing = ShortcutRule(phrases = emptyList(), action = ShortcutAction.NOTE) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("+ New shortcut") }
            }
        }
    }

    editing?.let { rule ->
        RuleSheet(
            rule = rule,
            lists = lists,
            onClose = { editing = null },
            onSave = { changed, showError ->
                viewModel.save(changed) { error -> if (error == null) editing = null else showError(error) }
            },
            onDelete = {
                viewModel.delete(rule)
                editing = null
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset shortcuts?") },
            text = { Text("Your own shortcuts and changes are removed, and the built-in ones come back.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefaults()
                    confirmReset = false
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

// "→ Todo", "→ Note", "→ New list", plus "keeps the phrase" when it does.
private fun ruleOutcome(rule: ShortcutRule, lists: List<ListEntity>): String {
    val target = when (rule.action) {
        ShortcutAction.ADD_TO_LIST -> lists.firstOrNull { it.id == rule.listId }?.title ?: "a list"
        ShortcutAction.NOTE -> "Note"
        ShortcutAction.NEW_LIST -> "New list"
    }
    return "→ $target" + if (rule.keepPhrase) " · keeps the phrase" else ""
}

// One rule, edited in a bottom sheet. Unlike a list item, it's only saved with Save: a rule can
// be refused (no phrases, or a phrase another rule has), and the sheet then says why.
// onSave(rule, showError): showError is called if it was refused.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleSheet(
    rule: ShortcutRule,
    lists: List<ListEntity>,
    onClose: () -> Unit,
    onSave: (ShortcutRule, showError: (String) -> Unit) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var phrases by remember { mutableStateOf(rule.phrases.joinToString(", ")) }
    var action by remember { mutableStateOf(rule.action) }
    var listId by remember { mutableStateOf(rule.listId) }
    var keepPhrase by remember { mutableStateOf(rule.keepPhrase) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        // Scrolls: with many lists, the choices can be taller than the sheet.
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = phrases,
                onValueChange = {
                    phrases = it
                    error = null
                },
                label = { Text("Phrases, separated by commas") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )

            Text("Does", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            // One radio row per choice: Note, New list, then "Add to" each list.
            ChoiceRow("Save a note", action == ShortcutAction.NOTE) { action = ShortcutAction.NOTE }
            ChoiceRow("Make a new list, named by the rest", action == ShortcutAction.NEW_LIST) { action = ShortcutAction.NEW_LIST }
            for (list in lists) {
                val chosen = action == ShortcutAction.ADD_TO_LIST && listId == list.id
                ChoiceRow("Add to ${list.title}", chosen) {
                    action = ShortcutAction.ADD_TO_LIST
                    listId = list.id
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { keepPhrase = !keepPhrase }.padding(vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep the phrase in the text")
                    Text(
                        "\"Call the dentist\" instead of \"the dentist\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepPhrase, onCheckedChange = { keepPhrase = it })
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (rule.id != 0L) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cancel") }
                TextButton(onClick = {
                    val changed = rule.copy(
                        phrases = parsePhrases(phrases),
                        action = action,
                        listId = listId.takeIf { action == ShortcutAction.ADD_TO_LIST },
                        keepPhrase = keepPhrase,
                    )
                    onSave(changed) { error = it }
                }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
