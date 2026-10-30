package dev.piatra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.piatra.core.agent.ShortcutAction
import dev.piatra.core.agent.ShortcutRule
import dev.piatra.core.agent.phraseTakenByAnotherRule
import dev.piatra.data.ListEntity
import dev.piatra.data.ListRepository
import dev.piatra.data.ShortcutRuleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// The shortcut-rule editor.
class ShortcutRulesViewModel(
    private val rules: ShortcutRuleRepository,
    lists: ListRepository,
) : ViewModel() {
    val all: StateFlow<List<ShortcutRule>> =
        rules.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // For the "Does" choices and the list names on each row.
    val lists: StateFlow<List<ListEntity>> =
        lists.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Checks the rule, then saves it. Null means saved; otherwise the reason it wasn't, to show
    // in the sheet (which then stays open, so nothing typed is lost).
    fun save(rule: ShortcutRule, onDone: (error: String?) -> Unit) {
        val error = when {
            rule.phrases.isEmpty() -> "Add at least one phrase."
            rule.action == ShortcutAction.ADD_TO_LIST && rule.listId == null -> "Pick a list."
            else -> phraseTakenByAnotherRule(rule, all.value)?.let { "\"$it\" is already in another shortcut." }
        }
        if (error != null) return onDone(error)
        viewModelScope.launch {
            rules.save(rule)
            onDone(null)
        }
    }

    fun setEnabled(rule: ShortcutRule, enabled: Boolean) {
        viewModelScope.launch { rules.setEnabled(rule.id, enabled) }
    }

    fun delete(rule: ShortcutRule) {
        viewModelScope.launch { rules.delete(rule.id) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { rules.resetToDefaults() }
    }
}
