package dev.piatra.core.eval

import dev.piatra.core.agent.Agent
import dev.piatra.core.agent.Tool
import dev.piatra.core.agent.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The lab's scorer, with a scripted "model" (no engine needed).
class EvalRunnerTest {
    private val zone = TimeZone.of("Europe/Bucharest")

    private class Always(val calls: List<ToolCall>) : Agent {
        override suspend fun chooseToolCalls(transcript: String, tools: List<Tool>) = calls
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall(name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    private fun case(phrase: String, modelTools: Set<String>?, vararg expected: Expect) =
        EvalCase(phrase, "Test", modelTools, expected.toList())

    @Test
    fun `a rule can pass the result while the model alone fails`() = runTest {
        // The model would have made a note; the "to do" rule decides instead.
        val runner = EvalRunner(Always(listOf(call("create_note", "text" to "To do, call mom."))), zone)
        val result = runner.run(
            "To do, call mom.",
            case("To do, call mom.", setOf("create_list_item", "create_reminder"), Expect(Expect.Kind.LIST_ITEM, listOf("call mom"), "Todo")),
        )

        assertEquals(false, result.modelPass)
        assertEquals(true, result.fullPass)
    }

    @Test
    fun `a list the model split stays on its list, even after remind me`() = runTest {
        // Seen: this made three identical "Buy milk, eggs and butter" Todo items.
        val said = "remind me to buy milk, eggs and butter"
        val items = listOf("milk", "eggs", "butter").map { call("create_list_item", "list_name" to "shopping", "message" to it) }
        val result = EvalRunner(Always(items), zone).run(said)

        assertEquals(listOf("☐ Milk · Shopping", "☐ Eggs · Shopping", "☐ Butter · Shopping"), result.created.map { it.describe() })
    }

    @Test
    fun `one recording never makes the same item twice`() = runTest {
        val twice = call("create_note", "text" to "the gate code is 4512")
        val result = EvalRunner(Always(listOf(twice, twice)), zone).run("The gate code is 4512.")

        assertEquals(1, result.created.size)
    }

    @Test
    fun `times are checked against the fixed moment of speaking`() = runTest {
        val runner = EvalRunner(Always(listOf(call("create_reminder", "message" to "call mom", "date_time_human" to "tomorrow"))), zone)
        val tomorrowNine = LocalDateTime(2026, 10, 23, 9, 0)
        val result = runner.run(
            "Remind me to call mom tomorrow.",
            case("Remind me to call mom tomorrow.", setOf("create_reminder"), Expect(Expect.Kind.LIST_ITEM, listOf("call mom"), "Todo", tomorrowNine)),
        )

        assertEquals(true, result.modelPass)
        assertEquals(true, result.fullPass)
    }

    @Test
    fun `a wrong time, an extra item or a wrong tool are reported`() = runTest {
        val runner = EvalRunner(
            Always(listOf(call("create_reminder", "message" to "call mom", "date_time_human" to "tomorrow"), call("create_note", "text" to "call mom"))),
            zone,
        )
        val result = runner.run(
            "Remind me to call mom.",
            case("Remind me to call mom.", setOf("create_reminder"), Expect(Expect.Kind.LIST_ITEM, listOf("call mom"), "Todo", at = null)),
        )

        assertEquals(false, result.modelPass) // it also picked create_note
        assertEquals(false, result.fullPass)
        assertEquals(2, result.problems!!.size) // the reminder has a time it shouldn't; an extra note
        assertTrue(result.problems!!.first().startsWith("expected list item [call mom] · Todo · no time, got ☐ Call mom"))
    }

    @Test
    fun `when the model picks nothing, the voice note is kept as a note`() = runTest {
        val expected = Expect(Expect.Kind.NOTE, listOf("thank you"))
        val result = EvalRunner(Always(emptyList()), zone).run("Thank you.", case("Thank you.", emptySet(), expected))

        assertEquals(true, result.modelPass)
        assertEquals(true, result.fullPass)
    }

    @Test
    fun `absent words fail a match`() = runTest {
        val model = Always(listOf(call("create_list_item", "list_name" to "shopping", "message" to "oranges please")))
        val expected = Expect(Expect.Kind.LIST_ITEM, listOf("oranges"), "Shopping", absent = listOf("please"))
        val result = EvalRunner(model, zone).run("Add oranges please to the shopping list.", case("Add oranges please to the shopping list.", null, expected))

        assertEquals(false, result.fullPass)
    }

    @Test
    fun `the test set's expected times are sane`() {
        // Every timed expectation falls on or after the moment of speaking.
        for (case in EVAL_CASES) for (expect in case.expected) {
            expect.at?.let { assertTrue(it >= ANCHOR, "${case.phrase}: $it is before $ANCHOR") }
        }
    }
}
