package dev.piatra.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Saves a note. Name, description and parameter are copied word for word from upstream
// (experimental/.../builtin_servlets/notes/CreateNoteTool.kt): the on-device agent model
// was fine-tuned on them, so rewording could make it worse at picking this tool.
// useModelText: the "Use the model's wording" setting, read for each note (Tool.kt).
class CreateNoteTool(
    private val items: ItemStore,
    private val useModelText: suspend () -> Boolean = { true },
) : Tool {
    override val name = "create_note"
    override val description = "Save a note, idea, or thought for later. Use when the user wants to jot " +
        "down or note something, or remember a fact or piece of information. Not for 'remember to do " +
        "something' requests - those are reminders."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "The text of the note, usually a direct quote from the user")
            }
        }
        putJsonArray("required") { add("text") }
    }

    // @Serializable: the compiler plugin writes the code that turns JSON into this class.
    // text = "": a missing `text` isn't an error; we fall back to the transcript below.
    @Serializable
    private data class Args(val text: String = "")

    override suspend fun call(arguments: JsonObject, context: ToolContext) {
        val args = ToolJson.decodeFromJsonElement<Args>(arguments)
        items.addNote(modelTextOrTranscript(args.text, context.transcript, useModelText() || context.decidedBy == DecidedBy.RULE), context.recordingId)
    }
}
