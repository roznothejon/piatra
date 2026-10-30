package dev.piatra.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListsAndRulesTest {
    private val todo = ListRef(1, "Todo", DefaultLists.TODO)
    private val shopping = ListRef(2, "Shopping", DefaultLists.SHOPPING)
    private val packing = ListRef(3, "Packing", seed = null)
    private val lists = listOf(todo, shopping, packing)

    @Test
    fun `list hints find the right list`() {
        assertEquals(shopping, matchList(lists, "shopping"))
        assertEquals(shopping, matchList(lists, "my shopping list"))
        assertEquals(shopping, matchList(lists, "groceries"))
        assertEquals(todo, matchList(lists, "todo"))
        assertEquals(todo, matchList(lists, "to-do"))
        assertEquals(packing, matchList(lists, "packing list"))
        assertNull(matchList(lists, "camping")) // unknown: the tool puts it on Todo
    }

    @Test
    fun `a renamed built-in list is still found by its keyword`() {
        val renamed = listOf(todo, shopping.copy(title = "Groceries"))
        assertEquals(2L, matchList(renamed, "shopping")?.id)
    }

    private val defaultRules = defaultShortcutRules(todoId = 1, shoppingId = 2)

    // The rule call for a transcript, as (tool, arguments) for easy comparison.
    private fun rule(transcript: String, rules: List<ShortcutRule> = defaultRules) =
        matchShortcut(transcript, rules, lists)?.let { it.name to it.arguments.mapValues { (_, v) -> v.jsonPrimitive.content } }

    @Test
    fun `shortcut rules match everyday phrases`() {
        val todoCallMom = "create_list_item" to mapOf("list_name" to "Todo", "list_id" to "1", "message" to "call mom")
        assertEquals(todoCallMom, rule("To do, call mom."))
        assertEquals(todoCallMom, rule("Too do, call mom.")) // the transcriber's mishearing
        assertEquals(todoCallMom, rule("todo call mom"))
        assertEquals("create_note" to mapOf("text" to "the car needs oil"), rule("Make a note that the car needs oil."))
        assertEquals("create_note" to mapOf("text" to "the gate code is 4512"), rule("Note down that the gate code is 4512."))
        assertEquals("create_note" to mapOf("text" to "the wifi password is on the router"), rule("Note to self: the wifi password is on the router."))
        assertEquals("create_list_item" to mapOf("list_name" to "Todo", "list_id" to "1", "message" to "water the plants"), rule("Remember to water the plants."))
        assertEquals("create_note" to mapOf("text" to "the wifi password is fish"), rule("Note down: the wifi password is fish"))
        assertEquals("create_note" to mapOf("text" to "buy milk"), rule("Note, buy milk."))
        assertEquals("create_list_item" to mapOf("list_name" to "Shopping", "list_id" to "2", "message" to "eggs and milk"), rule("Buy eggs and milk."))
        assertEquals("create_list" to mapOf("list_name" to "packing"), rule("Create a new list called packing."))
        assertEquals(DecidedBy.RULE, matchShortcut("Buy eggs", defaultRules, lists)?.decidedBy)
    }

    @Test
    fun `the user's own rules`() {
        val call = ShortcutRule(id = 9, phrases = listOf("call", "ring"), action = ShortcutAction.ADD_TO_LIST, listId = 3, keepPhrase = true)
        val pack = ShortcutRule(id = 10, phrases = listOf("pack"), action = ShortcutAction.ADD_TO_LIST, listId = 3)
        val rules = defaultRules + call + pack
        // keepPhrase: the phrase as said stays in the text.
        assertEquals("create_list_item" to mapOf("list_name" to "Packing", "list_id" to "3", "message" to "Ring the dentist"), rule("Ring the dentist.", rules))
        assertEquals("create_list_item" to mapOf("list_name" to "Packing", "list_id" to "3", "message" to "socks"), rule("Pack, socks.", rules))
        // Switched off: no match, the model decides.
        assertNull(rule("Buy eggs", defaultRules.map { it.copy(enabled = false) }))
        // A rule whose list is gone is skipped.
        assertNull(rule("Pack socks", listOf(pack.copy(listId = 99))))
    }

    @Test
    fun `typed phrases are cleaned up and can't be in two rules`() {
        assertEquals(listOf("to do", "todo", "buy"), parsePhrases(" To  do, todo. , ,Buy,to do"))
        assertEquals(listOf("note to self"), parsePhrases("Note to self:"))
        val clash = ShortcutRule(phrases = listOf("pack", "buy"), action = ShortcutAction.NOTE)
        assertEquals("buy", phraseTakenByAnotherRule(clash, defaultRules))
        // Editing a rule doesn't clash with itself.
        val buy = defaultRules[3].copy(id = 4)
        assertNull(phraseTakenByAnotherRule(buy, listOf(buy)))
    }

    @Test
    fun `everything else goes to the model`() {
        assertNull(rule("Notebook is on the table.")) // whole words only
        assertNull(rule("Remind me to buy eggs.")) // only at the start
        assertNull(rule("To do.")) // nothing after the phrase
        assertNull(rule("Add call my mom to my to-do list."))
    }

    // A model that records what it was offered and always answers the same.
    private class ScriptedModel(val answer: List<ToolCall>) : Agent {
        var offered: List<String>? = null
        override suspend fun chooseToolCalls(transcript: String, tools: List<Tool>): List<ToolCall> {
            offered = tools.map { it.name }
            return answer
        }
    }

    private val store = FakeItemStore()
    private val allTools = listOf(CreateNoteTool(store), CreateListTool(FakeListStore()))

    @Test
    fun `a matching rule means the model isn't asked`() = runTest {
        val model = ScriptedModel(emptyList())
        val calls = ShortcutsFirstAgent(model, FakeListStore(), FakeRuleStore).chooseToolCalls("To do, call mom.", allTools)

        assertEquals("create_list_item", calls.single().name)
        assertNull(model.offered)
    }

    @Test
    fun `the model isn't offered rule-only tools`() = runTest {
        val model = ScriptedModel(emptyList())
        ShortcutsFirstAgent(model, FakeListStore(), FakeRuleStore).chooseToolCalls("The weather is nice.", allTools)

        assertEquals(listOf("create_note"), model.offered)
    }

    @Test
    fun `remind plus a list item is corrected to a reminder`() = runTest {
        // Seen: "Remind me to buy orange juice tomorrow" -> shopping: orange juice.
        val listItem = ToolCall("create_list_item", buildJsonObject { put("list_name", "shopping"); put("message", "orange juice") })
        val calls = ShortcutsFirstAgent(ScriptedModel(listOf(listItem)), FakeListStore(), FakeRuleStore).chooseToolCalls("Remind me to buy orange juice tomorrow.", allTools)

        assertEquals(ToolCall("create_reminder", buildJsonObject { put("message", "orange juice") }, DecidedBy.CORRECTION), calls.single())
        // Without "remind", a list item stays a list item.
        val kept = ShortcutsFirstAgent(ScriptedModel(listOf(listItem)), FakeListStore(), FakeRuleStore).chooseToolCalls("Add orange juice to shopping.", allTools)
        assertEquals(listItem, kept.single())
    }

    private fun listItem(list: String, message: String) =
        ToolCall("create_list_item", buildJsonObject { put("list_name", list); put("message", message) })
    private fun note(text: String) = ToolCall("create_note", buildJsonObject { put("text", text) }, DecidedBy.CORRECTION)

    @Test
    fun `a sentence that isn't a request is kept as a note`() {
        // Seen: "Thank you" -> shopping: Thank you.
        assertEquals(listOf(note("Thank you.")), correctModelCalls(listOf(listItem("shopping", "Thank you")), "Thank you.", lists))
        assertEquals(listOf(note("Thank you.")), correctModelCalls(emptyList(), "Thank you.", lists))
        // A list cue, or only part of the sentence as the item: a real list item.
        for ((said, item) in listOf("Get milk." to "milk", "We need more toilet paper." to "toilet paper", "Packing: tent." to "tent")) {
            assertEquals(listOf(listItem("shopping", item)), correctModelCalls(listOf(listItem("shopping", item)), said, lists), said)
        }
        assertEquals(listItem("shopping", "Bread"), correctModelCalls(listOf(listItem("shopping", "Bread")), "Shopping bread", lists).single())
    }

    @Test
    fun `an unknown list the user named replaces the model's guess`() {
        // Seen: "my packing list" (no such list) -> the model wrote "shopping".
        val noPacking = listOf(todo, shopping)
        val corrected = correctModelCalls(listOf(listItem("shopping", "sunscreen")), "Add sunscreen to my packing list.", noPacking).single()
        assertEquals(ToolCall("create_list_item", buildJsonObject { put("list_name", "packing"); put("message", "sunscreen") }, DecidedBy.CORRECTION), corrected)
        // The model already chose the list the user said, or "my to-do list": left alone.
        for (said in listOf("Add sunscreen to my shopping list.", "Add sunscreen to my to do list.")) {
            val call = listItem("shopping", "sunscreen")
            assertEquals(call, correctModelCalls(listOf(call), said, lists).single(), said)
        }
        assertEquals(listItem("packing", "sunscreen"), correctModelCalls(listOf(listItem("packing", "sunscreen")), "Add sunscreen to my packing list.", lists).single())
    }

    @Test
    fun `a list the user named that exists replaces the model's guess`() {
        // Seen: a Cats list, "add puka to my cat list" -> the model wrote "shopping".
        val withCats = lists + ListRef(4, "Cats", seed = null)
        for (said in listOf("Add puka to my cat list.", "Add puka to my cats list.")) {
            val corrected = correctModelCalls(listOf(listItem("shopping", "puka")), said, withCats).single()
            assertEquals(ToolCall("create_list_item", buildJsonObject { put("list_name", "Cats"); put("message", "puka") }, DecidedBy.CORRECTION), corrected, said)
        }
    }

    @Test
    fun `list items land on the matching list, unknown names on Todo`() = runTest {
        val listStore = FakeListStore()
        val tool = CreateListItemTool(store, listStore, { TimeZone.UTC })
        val context = ToolContext(recordingId = 1, recordedAt = 0, transcript = "Add eggs to my shopping list.")
        tool.call(buildJsonObject { put("list_name", "shopping"); put("message", "eggs") }, context)
        tool.call(buildJsonObject { put("list_name", "camping"); put("message", "tent.") }, context)

        assertEquals(listOf("Eggs" to 2L, "Tent" to 1L), store.listItems.map { it.text to it.listId })
    }

    @Test
    fun `creating a list twice makes one list`() = runTest {
        val listStore = FakeListStore()
        val tool = CreateListTool(listStore)
        val context = ToolContext(recordingId = 1, recordedAt = 0, transcript = "Create a new list called packing.")
        tool.call(buildJsonObject { put("list_name", "packing") }, context)
        tool.call(buildJsonObject { put("list_name", "Packing.") }, context)

        assertEquals(listOf("Todo", "Shopping", "Packing"), listStore.lists.map { it.title })
    }

    @Test
    fun `a time inside a list item becomes its reminder time`() = runTest {
        // Seen on the phone: shortcut rules pass "call mom tomorrow at nine am" as the item.
        val zone = TimeZone.of("Europe/Bucharest")
        val spokenAt = LocalDateTime(2026, 10, 22, 14, 33).toInstant(zone).toEpochMilliseconds()
        val tomorrowNine = LocalDateTime(2026, 10, 23, 9, 0).toInstant(zone).toEpochMilliseconds()
        val tool = CreateListItemTool(store, FakeListStore(), { zone })
        for (spoken in listOf("To do call mom tomorrow at nine am", "Buy orange juice tomorrow at nine am", "Buy eggs")) {
            tool.call(matchShortcut(spoken, defaultRules, lists)!!.arguments, ToolContext(recordingId = 1, recordedAt = spokenAt, transcript = spoken))
        }

        assertEquals(
            listOf(
                Triple("Call mom", 1L, tomorrowNine),
                Triple("Orange juice", 2L, tomorrowNine),
                Triple("Eggs", 2L, null), // no time: left as it is
            ),
            store.listItems.map { Triple(it.text, it.listId, it.dueAt) },
        )
    }
}
