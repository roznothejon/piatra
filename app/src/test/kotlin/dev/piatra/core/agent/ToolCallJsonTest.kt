package dev.piatra.core.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

// The JSON going to and coming from the agent model.
class ToolCallJsonTest {

    @Test
    fun `tools are sent in the function-calling format`() {
        val json = ToolJson.parseToJsonElement(ToolCallJson.toolsJson(listOf(CreateListItemTool(FakeItemStore(), FakeListStore(), { kotlinx.datetime.TimeZone.UTC })))).jsonArray
        val function = json.single().jsonObject.getValue("function").jsonObject

        assertEquals("function", json.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("create_list_item", function.getValue("name").jsonPrimitive.content)
        assertEquals(
            listOf("list_name", "message"),
            function.getValue("parameters").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `reads calls whose arguments are an object or a JSON string`() {
        val result = """
            {"success":true,"response":"","function_calls":[
              {"name":"create_note","arguments":{"text":"buy milk"}},
              {"name":"set_alarm","arguments":"{\"time_hours\":7,\"time_minutes\":30}"},
              {"name":"set_timer","arguments":"not json"},
              {"arguments":{"text":"no name, skipped"}}
            ]}
        """.trimIndent()

        val calls = ToolCallJson.parseFunctionCalls(result)

        assertEquals(listOf("create_note", "set_alarm", "set_timer"), calls.map { it.name })
        assertEquals("buy milk", calls[0].arguments.getValue("text").jsonPrimitive.content)
        assertEquals("7", calls[1].arguments.getValue("time_hours").jsonPrimitive.content)
        assertEquals(emptyMap(), calls[2].arguments) // unreadable arguments: none
    }

    @Test
    fun `no function calls means nothing to do`() {
        assertEquals(emptyList(), ToolCallJson.parseFunctionCalls("""{"success":true,"response":"Hi!"}"""))
        assertEquals(emptyList(), ToolCallJson.parseFunctionCalls("""{"function_calls":[]}"""))
    }

    @Test
    fun `stored calls round-trip and read well on screen`() {
        val calls = listOf(
            ToolCall("create_reminder", buildJsonObject { put("message", "call mom"); put("date_time_human", "at 5pm") }),
            ToolCall("set_alarm", buildJsonObject { put("time_hours", 7); put("time_minutes", 30) }),
        )

        assertEquals(calls, ToolCallJson.decode(ToolCallJson.encode(calls)))
        assertEquals("create_reminder · message: call mom · date_time_human: at 5pm", ToolCallJson.describe(calls[0]))
        assertEquals("set_alarm · time_hours: 7 · time_minutes: 30", ToolCallJson.describe(calls[1]))
    }

    @Test
    fun `who decided is stored, and old results read as the model's`() {
        val rule = ToolCall("create_note", buildJsonObject { put("text", "hi") }, DecidedBy.RULE)
        assertEquals(listOf(rule), ToolCallJson.decode(ToolCallJson.encode(listOf(rule))))
        assertEquals(DecidedBy.MODEL, ToolCallJson.decode("""[{"name":"create_note","arguments":{}}]""").single().decidedBy)
    }
}
