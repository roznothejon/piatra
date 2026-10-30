package dev.piatra.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// The JSON a tool-calling language model speaks: our tools going in, its tool calls coming out.
// This is the common "function calling" format (the engine and many chat APIs use it).
// Upstream: IndexAgentCactus.prepareTools and its parsing of "function_calls".
object ToolCallJson {
    // [{"type":"function","function":{"name":..., "description":..., "parameters":{json schema}}}, ...]
    fun toolsJson(tools: List<Tool>): String = buildJsonArray {
        for (tool in tools) {
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                }
            }
        }
    }.toString()

    // Reads the model's answer: {"response": "...", "function_calls": [{"name":..., "arguments":...}], ...}.
    // "arguments" usually is a JSON object, but can come back as a JSON *string* holding one
    // (upstream's tests handle both; its agent only handled objects).
    fun parseFunctionCalls(resultJson: String): List<ToolCall> {
        val result = ToolJson.parseToJsonElement(resultJson).jsonObject
        val calls = result["function_calls"] as? JsonArray ?: return emptyList()
        return calls.mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val name = call["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ToolCall(name, argumentsOf(call["arguments"]))
        }
    }

    private fun argumentsOf(element: JsonElement?): JsonObject = when (element) {
        is JsonObject -> element
        // A string: try to read it as JSON. Anything unreadable becomes "no arguments".
        is JsonPrimitive -> runCatching { ToolJson.parseToJsonElement(element.content).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        else -> JsonObject(emptyMap())
    }

    // Stored on the recording (agentResult): what was decided, and by whom,
    // as [{"name":..., "arguments":{...}, "by":"RULE"}]. "by" is left out for the model.
    fun encode(calls: List<ToolCall>): String = buildJsonArray {
        for (call in calls) {
            addJsonObject {
                put("name", call.name)
                put("arguments", call.arguments)
                if (call.decidedBy != DecidedBy.MODEL) put("by", call.decidedBy.name)
            }
        }
    }.toString()

    // Results stored before 2.4 have no "by": they were all the model's.
    fun decode(json: String): List<ToolCall> = ToolJson.parseToJsonElement(json).jsonArray.map { element ->
        val call = element.jsonObject
        val by = call["by"]?.jsonPrimitive?.content?.let { name -> DecidedBy.entries.firstOrNull { it.name == name } }
        ToolCall(call.getValue("name").jsonPrimitive.content, call.getValue("arguments").jsonObject, by ?: DecidedBy.MODEL)
    }

    // One short line per call, for showing on screen:
    // create_reminder · message: call mom · date_time_human: at 5pm
    // A rule's list_id is left out: list_name already says which list, in words.
    fun describe(call: ToolCall): String =
        (listOf(call.name) + call.arguments.filterKeys { it != "list_id" }.map { (key, value) -> "$key: ${plainText(value)}" })
            .joinToString(" · ")

    // A JSON string shows without its quotes; numbers and anything else as written.
    private fun plainText(value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
}
