package dev.piatra.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Rules first, the model second, corrections after:
//   1. a shortcut rule (ShortcutRules.kt) that matches decides alone; the model isn't run.
//      The rules are read for every recording, so an edit in Settings counts from the next one;
//   2. otherwise the agent model chooses;
//   3. then known model mistakes are corrected.
// An Agent wrapping another Agent, so AgentRunner and the queue don't change.
class ShortcutsFirstAgent(
    private val model: Agent,
    private val lists: ListStore,
    private val rules: ShortcutRuleStore,
) : Agent {
    override suspend fun chooseToolCalls(transcript: String, tools: List<Tool>): List<ToolCall> {
        val allLists = lists.all()
        matchShortcut(transcript, rules.all(), allLists)?.let { return listOf(it) }
        // The model only sees the tools it was trained on (not create_list).
        val calls = model.chooseToolCalls(transcript, tools.filter { it.offeredToModel })
        return correctModelCalls(calls, transcript, allLists)
    }
}

// Known model mistakes, fixed (step 3; also used by the agent lab). The model always calls a
// tool, even for "Thank you", and such voice notes are kept as notes.
fun correctModelCalls(calls: List<ToolCall>, transcript: String, lists: List<ListRef>): List<ToolCall> {
    // Nothing chosen: keep it as a note, like any other voice note that isn't a request.
    if (calls.isEmpty()) return listOf(noteOf(transcript))
    return calls.map { call ->
        if (call.name != "create_list_item") return@map call
        val message = call.arguments.text("message")
        when {
            // Reminders without a time go to a list ("Remind me to call mom" -> shopping: call
            // mom). If the user said "remind", make it a reminder after all; the reminder
            // tool then looks for a time in the sentence. Only for a single call: several list
            // items mean the model split a list ("Remind me to buy milk, eggs and butter" ->
            // three Shopping items), and that's kept.
            calls.size == 1 && REMIND.containsMatchIn(transcript) ->
                ToolCall("create_reminder", buildJsonObject { put("message", message) }, DecidedBy.CORRECTION)
            // Not a request: the whole sentence as a list item, with nothing in it about a list
            // ("Thank you" -> shopping: Thank you). Save it as a note instead.
            calls.size == 1 && isWholeSentence(message, transcript) && !hasListCue(transcript, lists) -> noteOf(transcript)
            else -> correctListName(call, transcript, lists)
        }
    }
}

// The user named a list ("my cat list") and the model wrote another one, often "shopping"
// ("add puka to my cat list" with a Cats list). The list the user said wins:
// - it exists: use its title, so the item lands there;
// - it doesn't: pass on the name as said, so the tool finds no such list and puts the item on Todo.
private fun correctListName(call: ToolCall, transcript: String, lists: List<ListRef>): ToolCall {
    val said = NAMED_LIST.findAll(transcript).map { it.groupValues[1].lowercase() }
        .firstOrNull { it !in NOT_A_LIST_NAME } ?: return call
    val modelName = call.arguments.text("list_name")
    val saidList = matchList(lists, said)
    val agrees = if (saidList != null) matchList(lists, modelName)?.id == saidList.id else modelName.equals(said, ignoreCase = true)
    if (agrees) return call
    val arguments = JsonObject(call.arguments + ("list_name" to JsonPrimitive(saidList?.title ?: said)))
    return ToolCall(call.name, arguments, DecidedBy.CORRECTION)
}

// True when the item is (nearly) everything that was said: at most one word left over.
private fun isWholeSentence(item: String, transcript: String): Boolean {
    val itemWords = words(item)
    return itemWords.isNotEmpty() && itemWords.size >= words(transcript).size - 1
}

// Words that say "this goes on a list": "list", buying words, or one of the user's list names.
private fun hasListCue(transcript: String, lists: List<ListRef>): Boolean {
    val said = words(transcript)
    val listWords = lists.flatMap { words(it.title) + listOfNotNull(it.seed) }
    return said.any { it in LIST_CUES || it in listWords }
}

private fun words(text: String): List<String> = Regex("""[\p{L}\p{N}']+""").findAll(text.lowercase()).map { it.value }.toList()

private fun noteOf(transcript: String) =
    ToolCall("create_note", buildJsonObject { put("text", transcript.trim()) }, DecidedBy.CORRECTION)

private val REMIND = Regex("""\bremind(er)?\b""", RegexOption.IGNORE_CASE)
private val LIST_CUES = setOf("list", "buy", "get", "need", "add", "shopping", "grocery", "groceries", "todo")
// "<word> list": the word before "list", unless it's one of these.
private val NAMED_LIST = Regex("""([\p{L}-]+)\s+list\b""", RegexOption.IGNORE_CASE)
private val NOT_A_LIST_NAME = setOf("my", "the", "a", "this", "that", "our", "new", "do", "to-do", "todo")

private fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.content.orEmpty()
