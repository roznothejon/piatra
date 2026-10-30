package dev.piatra.data

import dev.piatra.core.agent.DefaultLists
import dev.piatra.core.agent.ListStore
import dev.piatra.core.agent.ShortcutRule
import dev.piatra.core.agent.ShortcutRuleStore
import dev.piatra.core.agent.defaultShortcutRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The shortcut rules. The agent reads them as a ShortcutRuleStore; the editor
// screen observes and changes them.
class ShortcutRuleRepository(
    private val dao: ShortcutRuleDao,
    private val lists: ListStore,
    private val settings: Settings,
) : ShortcutRuleStore {
    // The agent and the screen may both ask first; the lock stops them seeding twice.
    private val seeding = Mutex()

    override suspend fun all(): List<ShortcutRule> {
        seedOnce()
        return dao.all().map { it.toRule() }
    }

    fun observeAll(): Flow<List<ShortcutRule>> =
        dao.observeAll().onStart { seedOnce() }.map { rows -> rows.map { it.toRule() } }

    suspend fun save(rule: ShortcutRule) {
        dao.upsert(rule.toEntity())
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: Long) = dao.delete(id)

    // Throws away every rule, the user's own ones too, and puts the defaults back.
    suspend fun resetToDefaults() {
        dao.replaceAll(defaults().map { it.toEntity() })
    }

    // The first time ever, the defaults go in. A Settings flag (not "the table is empty")
    // remembers it, so a user who deletes every rule keeps it that way.
    private suspend fun seedOnce() = seeding.withLock {
        if (settings.shortcutsSeeded()) return@withLock
        if (dao.all().isEmpty()) dao.insertAll(defaults().map { it.toEntity() })
        settings.setShortcutsSeeded()
    }

    private suspend fun defaults() =
        defaultShortcutRules(todoId = lists.idForSeed(DefaultLists.TODO), shoppingId = lists.idForSeed(DefaultLists.SHOPPING))
}

private fun ShortcutRuleEntity.toRule() =
    ShortcutRule(id, phrases.split('\n').filter { it.isNotBlank() }, action, listId, keepPhrase, enabled)

private fun ShortcutRule.toEntity() =
    ShortcutRuleEntity(id, phrases.joinToString("\n"), action, listId, keepPhrase, enabled)
