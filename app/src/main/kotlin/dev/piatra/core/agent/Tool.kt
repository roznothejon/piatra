package dev.piatra.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// One action the agent can take, described so a language model can read it:
// a name, a plain-English description and its parameters as a JSON schema.
// The model never runs anything itself; it answers with ToolCalls, and AgentRunner calls `call`.
// (Upstream builds these on the MCP SDK: mcp/.../McpTool.kt. Ours is the same idea, minus
// remote tool servers.)
interface Tool {
    val name: String
    val description: String
    // JSON schema of the arguments, e.g. {"type":"object","properties":{"text":{...}},"required":["text"]}
    val parameters: JsonObject

    // False for tools only shortcut rules use: the agent model wasn't trained on them
    // (create_list), so offering them would only confuse it.
    val offeredToModel: Boolean get() = true

    suspend fun call(arguments: JsonObject, context: ToolContext)
}

// What a tool knows about the voice note it's acting on.
data class ToolContext(
    // Items the tool creates are linked to this recording (upstream: ItemSource).
    val recordingId: Long,
    // When the user spoke, in epoch milliseconds: "in 2 hours" counts from here (upstream: timeBase).
    val recordedAt: Long,
    val transcript: String,
    // Who chose this call. A shortcut rule's text is ours, not the model's, so the "Use the
    // model's wording" switch leaves it alone.
    val decidedBy: DecidedBy = DecidedBy.MODEL,
)

// The agent's answer: "run this tool with these arguments", and who decided it.
data class ToolCall(val name: String, val arguments: JsonObject, val decidedBy: DecidedBy = DecidedBy.MODEL)

enum class DecidedBy {
    MODEL, // the agent model chose it
    RULE, // a shortcut rule matched (ShortcutRules.kt); the model didn't run
    CORRECTION, // the model chose something else and a correction changed it (ShortcutsFirstAgent)
}

// Shared JSON settings for reading tool arguments. ignoreUnknownKeys: a model may send
// extra fields (upstream's create_note has an "automatic" flag); skip them instead of failing.
val ToolJson = Json { ignoreUnknownKeys = true }

// A tool's parameters as a JSON schema, from (name to (type to description)) pairs, so tool
// definitions stay readable. vararg: any number of property arguments, received as an array.
fun toolSchema(required: List<String>, vararg properties: Pair<String, Pair<String, String>>): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, typeAndDescription) in properties) {
                putJsonObject(name) {
                    put("type", typeAndDescription.first)
                    put("description", typeAndDescription.second)
                }
            }
        }
        putJsonArray("required") { required.forEach { add(it) } }
    }

// Which text to save: the model's, only if it's a word-for-word piece of what was said
// (e.g. "Note down to buy orange juice" -> "Buy orange juice"); otherwise the whole transcript.
// A 13 MB model rewriting a sentence garbles it: it turned "design and sew pockets for water
// bottles from scrap jean fabric…" into "Pockets: water bottles from scratch, so are…", and
// "the meeting starts at nine A.m at ten p.m." into "Meeting starts at 9 A, p.m at 10 p.m.".
// Upstream always saves the whole transcript; we keep the model's clean extracts because one
// sentence can make two calls ("note X and remind me Y"). Used by create_note and
// create_reminder. useModelText = false is the "Use the model's wording" switch turned off
// (Settings): always the transcript, as upstream does.
// Note: the model writes numbers as digits ("nine" -> "9"), which then doesn't count as word for
// word, so those fall back to the transcript.
// A single word out of a longer sentence isn't trusted either: "Remind me tomorrow that it's
// gonna rain" came back as just "rain".
internal fun modelTextOrTranscript(modelText: String, transcript: String, useModelText: Boolean = true): String {
    if (!useModelText) return transcript.trim()
    val text = modelText.trim()
    val modelWords = words(text)
    val saidWords = words(transcript)
    val isPieceOfTranscript = modelWords.isNotEmpty() && " $saidWords ".contains(" $modelWords ")
    val isOneWordOfMany = ' ' !in modelWords && ' ' in saidWords
    return if (isPieceOfTranscript && !isOneWordOfMany) text else transcript.trim()
}

// "Note, buy Milk!" -> "note buy milk": lower case, punctuation gone, single spaces. Used only to
// compare; the saved text keeps its capitals and punctuation.
private fun words(text: String): String =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }.joinToString(" ")
