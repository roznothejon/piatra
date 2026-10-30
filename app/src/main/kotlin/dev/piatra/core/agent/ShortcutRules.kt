package dev.piatra.core.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Spoken shortcuts: if a transcript starts with one of a rule's phrases, that rule decides what
// to do and the agent model isn't asked. The model misreads some everyday phrasings ("To do, call
// mom." became a note), and rules are 100% predictable.
//
// Since 3.5 the rules are data in the database, edited in Settings -> Shortcut phrases.
// Matching ignores capitals and the punctuation after the phrase ("To do, call mom." -> "call mom").
// Longer phrases are tried before shorter ones ("make a note" before "note"), whichever rule they're in.

// What a rule does with the rest of the sentence.
enum class ShortcutAction {
    ADD_TO_LIST, // an item on the rule's list (listId)
    NOTE,
    NEW_LIST, // a list named by the rest of the sentence
}

// One shortcut. id 0 = not saved yet.
// keepPhrase: the saved text starts with the phrase as said ("Call the dentist" instead of
// "the dentist"), for rules whose phrase is part of the item and not just a command.
data class ShortcutRule(
    val id: Long = 0,
    val phrases: List<String>,
    val action: ShortcutAction,
    val listId: Long? = null,
    val keepPhrase: Boolean = false,
    val enabled: Boolean = true,
)

// Shortcut rules, apart from Room (like ListStore). The app uses ShortcutRuleRepository.
interface ShortcutRuleStore {
    suspend fun all(): List<ShortcutRule>
}

// The rules a fresh install starts with (and "Reset to defaults" brings back): the ones from 2.4/2.5b.
fun defaultShortcutRules(todoId: Long, shoppingId: Long): List<ShortcutRule> = listOf(
    // "too do": a common mishearing of "to do" by the transcriber.
    ShortcutRule(phrases = listOf("to do", "to-do", "todo", "too do"), action = ShortcutAction.ADD_TO_LIST, listId = todoId),
    // "remember to …" is a reminder; the model read it as a note.
    ShortcutRule(phrases = listOf("remember to"), action = ShortcutAction.ADD_TO_LIST, listId = todoId),
    // "that" and "to self" say nothing, so they're part of the phrase: "Note to self: X" saves X.
    ShortcutRule(
        phrases = listOf("make a note that", "note down that", "note that", "note to self", "make a note", "note down", "note"),
        action = ShortcutAction.NOTE,
    ),
    ShortcutRule(phrases = listOf("buy"), action = ShortcutAction.ADD_TO_LIST, listId = shoppingId),
    // Only rules make lists: the agent model wasn't trained on create_list.
    ShortcutRule(phrases = listOf("create a new list called", "create a new list"), action = ShortcutAction.NEW_LIST),
)

// The rule call for a transcript, or null if no rule matches (or nothing follows the phrase).
// Switched-off rules, and list rules whose list is gone, are skipped.
fun matchShortcut(transcript: String, rules: List<ShortcutRule>, lists: List<ListRef>): ToolCall? {
    val candidates = rules.filter { it.enabled }
        .flatMap { rule -> rule.phrases.map { it to rule } }
        .sortedByDescending { it.first.length }
    for ((phrase, rule) in candidates) {
        // At the very start, as whole words (\b: "note" doesn't match "notebook"), then any punctuation.
        val prefix = Regex("""^\s*${Regex.escape(phrase)}\b[\s,.:;!-]*""", RegexOption.IGNORE_CASE)
        val match = prefix.find(transcript) ?: continue
        val rest = transcript.substring(match.range.last + 1).trim().trimEnd('.', '!').trim()
        if (rest.isEmpty()) continue
        val text = if (rule.keepPhrase) transcript.trim().trimEnd('.', '!').trim() else rest
        val call = when (rule.action) {
            ShortcutAction.ADD_TO_LIST -> {
                val list = lists.firstOrNull { it.id == rule.listId } ?: continue
                // list_id picks the exact list; list_name is only there to read on screen.
                call("create_list_item", "list_name" to list.title, "list_id" to list.id.toString(), "message" to text)
            }
            ShortcutAction.NOTE -> call("create_note", "text" to text)
            ShortcutAction.NEW_LIST -> call("create_list", "list_name" to text)
        }
        return call.copy(decidedBy = DecidedBy.RULE)
    }
    return null
}

// "to do, Todo , ,buy" -> ["to do", "buy"]: lower case, no stray spaces or punctuation, no repeats.
fun parsePhrases(typed: String): List<String> =
    typed.split(',')
        .map { it.trim().trim('.', '!', '?', ':', ';').trim().lowercase().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotEmpty() }
        .distinct()

// A phrase this rule shares with another rule, or null. Two rules can't both own a phrase.
fun phraseTakenByAnotherRule(rule: ShortcutRule, others: List<ShortcutRule>): String? {
    // Skip the rule itself when it's being edited; a new rule (id 0) is compared with all.
    val taken = others.filter { rule.id == 0L || it.id != rule.id }.flatMap { it.phrases }.toSet()
    return rule.phrases.firstOrNull { it in taken }
}

private fun call(tool: String, vararg arguments: Pair<String, String>) =
    ToolCall(tool, buildJsonObject { arguments.forEach { (key, value) -> put(key, value) } })
