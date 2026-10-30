package dev.piatra.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

// The agent pieces with an in-memory ItemStore and a scripted agent: no model, no phone.
class AgentTest {

    // An agent that always answers with the given calls.
    private class ScriptedAgent(val calls: List<ToolCall>) : Agent {
        override suspend fun chooseToolCalls(transcript: String, tools: List<Tool>) = calls
    }

    private fun note(text: String) = ToolCall("create_note", buildJsonObject { put("text", text) })

    private val store = FakeItemStore()
    private val tools = listOf(CreateNoteTool(store), CreateListItemTool(store, FakeListStore(), { TimeZone.UTC }))

    @Test
    fun `runner saves the note linked to its recording and returns the calls`() = runTest {
        val calls = AgentRunner(ScriptedAgent(listOf(note("buy milk"))), tools, store)
            .run(recordingId = 7, recordedAt = 0, transcript = "Note, buy milk.")

        assertEquals(mapOf<Long?, List<String>>(7L to listOf("buy milk")), store.notes)
        assertEquals(listOf(note("buy milk")), calls)
    }

    @Test
    fun `running again replaces the recording's items instead of duplicating them`() = runTest {
        AgentRunner(ScriptedAgent(listOf(note("buy milk"))), tools, store).run(7, 0, "Note, buy milk.")
        AgentRunner(ScriptedAgent(listOf(note("buy oat milk"))), tools, store).run(7, 0, "Note, buy oat milk.")
        AgentRunner(ScriptedAgent(emptyList()), tools, store).run(8, 0, "Hello there.") // leaves 7 alone

        assertEquals(mapOf<Long?, List<String>>(7L to listOf("buy oat milk")), store.notes)
    }

    @Test
    fun `unknown tool names are skipped but still reported`() = runTest {
        val calls = listOf(ToolCall("launch_rocket", JsonObject(emptyMap())), note("still saved"))
        val returned = AgentRunner(ScriptedAgent(calls), tools, store).run(recordingId = 1, recordedAt = 0, transcript = "Note: still saved")

        assertEquals(mapOf<Long?, List<String>>(1L to listOf("still saved")), store.notes)
        assertEquals(calls, returned)
    }

    @Test
    fun `create_note ignores extra arguments and falls back to the transcript`() = runTest {
        val tool = CreateNoteTool(store)
        val context = ToolContext(recordingId = 1, recordedAt = 0, transcript = "Make a note that the car needs oil.")
        // Upstream's model may send "automatic": skipped, not an error.
        tool.call(buildJsonObject { put("text", " the car needs oil "); put("automatic", true) }, context)
        // No text (or blank): the whole transcript is saved instead.
        tool.call(JsonObject(emptyMap()), context)
        tool.call(buildJsonObject { put("text", "  ") }, context)

        assertEquals(
            listOf("the car needs oil", "Make a note that the car needs oil.", "Make a note that the car needs oil."),
            store.notes.getValue(1),
        )
    }

    @Test
    fun `note keeps the model's text only when it's a word-for-word piece of the transcript`() {
        // A clean extract: kept (capitals and punctuation don't matter for the comparison).
        assertEquals("Buy orange juice", modelTextOrTranscript("Buy orange juice", "Note down to buy orange juice"))
        assertEquals("call the bank", modelTextOrTranscript("call the bank", "Take a note: call the bank, and remind me at 5."))
        // Rewritten or garbled: the whole transcript instead (a real example from the phone).
        val said = "design and sew kind of pockets for uh water bottles from scratch. I want to make them " +
            "from scrap jean fabric and they are gonna go in one of the cupboards in the kitchen. Make a note of that."
        assertEquals(
            said,
            modelTextOrTranscript("Pockets: water bottles from scratch, so are gonna go in one of the cupboards in the kitchen.", said),
        )
        // Whole words only: "car" isn't a piece of "scar".
        assertEquals("a scar on my arm", modelTextOrTranscript("car", "a scar on my arm"))
        // One word out of many: the transcript. One word said on its own: kept.
        assertEquals("Remind me that it's gonna rain.", modelTextOrTranscript("rain", "Remind me that it's gonna rain."))
        assertEquals("Laundry", modelTextOrTranscript("Laundry", "laundry."))
    }

    @Test
    fun `with the model's wording off, a shortcut rule's text is still kept`() = runTest {
        val tool = CreateNoteTool(store, useModelText = { false })
        val said = "Note down to buy orange juice"
        tool.call(buildJsonObject { put("text", "to buy orange juice") }, ToolContext(1, 0, said, DecidedBy.RULE))
        tool.call(buildJsonObject { put("text", "Buy orange juice") }, ToolContext(1, 0, said, DecidedBy.MODEL))

        assertEquals(listOf("to buy orange juice", said), store.notes.getValue(1))
    }

    @Test
    fun `with the model's wording off, the transcript is always saved`() {
        assertEquals("Note down to buy orange juice", modelTextOrTranscript("Buy orange juice", "Note down to buy orange juice ", useModelText = false))
    }
}
