package dev.piatra.core.eval

import dev.piatra.core.agent.Agent
import dev.piatra.core.agent.AgentRunner
import dev.piatra.core.agent.DecidedBy
import dev.piatra.core.agent.ListStore
import dev.piatra.core.agent.NoRepeatsItemStore
import dev.piatra.core.agent.ShortcutRuleStore
import dev.piatra.core.agent.Tool
import dev.piatra.core.agent.ToolCall
import dev.piatra.core.agent.ToolCallJson
import dev.piatra.core.agent.correctModelCalls
import dev.piatra.core.agent.defaultShortcutRules
import dev.piatra.core.agent.matchShortcut
import dev.piatra.core.agent.piatraTools
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Instant

// Runs phrases through the whole agent pipeline as a dry run: shortcut rules -> model ->
// corrections -> the real tools, against in-memory stores, as if said at ANCHOR. Nothing is saved
// and no alarm is set. The model is always asked too, even when a rule decides, so it can be
// scored on its own. Used by the agent lab screen (ui/LabScreen.kt).
// Typed phrases use the user's own shortcut rules and lists (copied, so nothing changes) when
// `rules` and `lists` are given; the test set always uses the default rules and the two built-in
// lists, so its score doesn't move when the rules are edited. The same goes for the "Use the
// model's wording" setting (`useModelText`): typed phrases follow it, the test set keeps it on.
class EvalRunner(
    private val model: Agent,
    private val timeZone: TimeZone,
    private val rules: ShortcutRuleStore? = null,
    private val lists: ListStore? = null,
    private val useModelText: suspend () -> Boolean = { true },
) {
    private val anchorMillis = ANCHOR.toInstant(timeZone).toEpochMilliseconds()

    // One phrase, with or without expectations (the lab's text box has none).
    suspend fun run(phrase: String, case: EvalCase? = null): EvalResult {
        val items = InMemoryItemStore()
        val useOwn = case == null && rules != null && this.lists != null
        val lists = if (useOwn) InMemoryListStore(this.lists!!.all()) else InMemoryListStore()
        val shortcutRules = if (useOwn) rules!!.all() else defaultShortcutRules(todoId = 1, shoppingId = 2)
        val defaultListCount = lists.lists.size
        val once = NoRepeatsItemStore(items)
        val modelText = if (case == null) useModelText else ({ true })
        val tools = piatraTools(once, lists, RecordingClockApp(), FixedClock(anchorMillis), { timeZone }, modelText)

        val modelCalls = model.chooseToolCalls(phrase, tools.filter { it.offeredToModel })
        val rule = matchShortcut(phrase, shortcutRules, lists.all())
        val calls = if (rule != null) listOf(rule) else correctModelCalls(modelCalls, phrase, lists.all())
        // AgentRunner as in the app, with an "agent" that answers with the calls decided above.
        val decided = object : Agent {
            override suspend fun chooseToolCalls(transcript: String, tools: List<Tool>) = calls
        }
        val toolError = runCatching { AgentRunner(decided, tools, once).run(recordingId = 1, recordedAt = anchorMillis, transcript = phrase) }
            .exceptionOrNull()?.message

        val created = items.saved.map { saved ->
            Created(
                kind = when (saved.kind) {
                    "note" -> Expect.Kind.NOTE
                    "list item" -> Expect.Kind.LIST_ITEM
                    "alarm" -> Expect.Kind.ALARM
                    else -> Expect.Kind.TIMER
                },
                text = saved.text,
                list = saved.listId?.let { id -> lists.lists.firstOrNull { it.id == id }?.title },
                at = saved.dueAt?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(timeZone) },
            )
        } + lists.lists.drop(defaultListCount).map { Created(Expect.Kind.NEW_LIST, it.title, null, null) }

        return EvalResult(phrase, case, modelCalls, calls, created, toolError)
    }

    // The whole test set, one case at a time; onProgress(done, total) after each.
    suspend fun runAll(cases: List<EvalCase> = EVAL_CASES, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<EvalResult> =
        cases.mapIndexed { index, case -> run(case.phrase, case).also { onProgress(index + 1, cases.size) } }
}

// Something the dry run created, in comparable form.
data class Created(val kind: Expect.Kind, val text: String, val list: String?, val at: LocalDateTime?) {
    // "☐ Call mom · Todo · 🔔 2026-10-23 09:00", like the main screen.
    fun describe(): String = when (kind) {
        Expect.Kind.NOTE -> "📝 $text"
        Expect.Kind.LIST_ITEM -> "☐ $text" + (list?.let { " · $it" } ?: "") + (at?.let { " · 🔔 ${show(it)}" } ?: "")
        Expect.Kind.ALARM -> "⏰ Alarm ${at?.let(::show) ?: ""}" + (if (text.isNotEmpty()) " · $text" else "")
        Expect.Kind.TIMER -> "⏱ Timer $text · ends ${at?.let(::show) ?: "?"}"
        Expect.Kind.NEW_LIST -> "🗂 New list $text"
    }
}

class EvalResult(
    val phrase: String,
    val case: EvalCase?,
    val modelCalls: List<ToolCall>,
    val calls: List<ToolCall>,
    val created: List<Created>,
    val toolError: String?,
) {
    val decidedBy: DecidedBy? = calls.firstOrNull()?.decidedBy

    // Model alone: picked only tools from the case's acceptable set (or none when it should pick none).
    val modelPass: Boolean? = case?.modelTools?.let { allowed ->
        val picked = modelCalls.map { it.name }.toSet()
        if (allowed.isEmpty()) picked.isEmpty() else picked.isNotEmpty() && allowed.containsAll(picked)
    }

    // Whole pipeline: what was created matches what was expected, one to one. Null without a case.
    val problems: List<String>? = case?.let { problemsFor(it.expected, created, toolError) }
    val fullPass: Boolean? = problems?.isEmpty()

    fun modelSummary(): String = modelCalls.joinToString("; ") { ToolCallJson.describe(it) }.ifEmpty { "(nothing)" }
}

// Each expectation needs its own created thing (kind, list, words, time); nothing extra allowed.
private fun problemsFor(expected: List<Expect>, created: List<Created>, toolError: String?): List<String> {
    val problems = mutableListOf<String>()
    toolError?.let { problems += "tool failed: $it" }
    val unused = created.toMutableList()
    for (expect in expected) {
        val match = unused.firstOrNull { matches(expect, it) }
        if (match != null) {
            unused.remove(match)
            continue
        }
        // Something of the right kind but wrong in detail is reported once, as what we got instead.
        val nearMiss = unused.firstOrNull { it.kind == expect.kind }
        problems += "expected ${describe(expect)}" + (nearMiss?.let { ", got ${it.describe()}" } ?: "")
        if (nearMiss != null) unused.remove(nearMiss)
    }
    unused.forEach { problems += "unexpected ${it.describe()}" }
    return problems
}

private fun matches(expect: Expect, created: Created): Boolean =
    created.kind == expect.kind &&
        (expect.list == null || created.list.equals(expect.list, ignoreCase = true)) &&
        expect.words.all { created.text.contains(it, ignoreCase = true) } &&
        expect.absent.none { created.text.contains(it, ignoreCase = true) } &&
        (!expect.checkTime || sameTime(expect.at, created.at))

// Within a minute; both null (no time) also counts.
private fun sameTime(a: LocalDateTime?, b: LocalDateTime?): Boolean {
    if (a == null || b == null) return a == b
    return abs(a.toInstant(TimeZone.UTC).toEpochMilliseconds() - b.toInstant(TimeZone.UTC).toEpochMilliseconds()) <= 60_000
}

private fun describe(expect: Expect): String {
    val time = when {
        !expect.checkTime -> ""
        expect.at == null -> " · no time"
        else -> " · ${show(expect.at)}"
    }
    return "${expect.kind.name.lowercase().replace('_', ' ')} [${expect.words.joinToString(", ")}]" +
        (if (expect.absent.isNotEmpty()) " without [${expect.absent.joinToString(", ")}]" else "") +
        (expect.list?.let { " · $it" } ?: "") + time
}

// "2026-10-23 09:00"
private fun show(time: LocalDateTime): String =
    "${time.date} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
