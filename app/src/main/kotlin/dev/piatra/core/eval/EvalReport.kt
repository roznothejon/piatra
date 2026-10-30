package dev.piatra.core.eval

import dev.piatra.core.agent.DecidedBy

// The test set's results as Markdown: scores per group, then every case. The lab saves it to a
// file (filesDir/eval/agent-eval-latest.md) so it can be pulled with adb.
fun evalReport(results: List<EvalResult>, runAt: String, modelName: String): String = buildString {
    appendLine("## Agent test set: $runAt")
    appendLine()
    appendLine("Model: $modelName. Each phrase as if said on $ANCHOR. **Model** = the agent model alone picked an acceptable tool; **Result** = after rules, corrections and tools, exactly the expected things were created.")
    appendLine()
    appendLine("| Group | Model | Result |")
    appendLine("|---|---|---|")
    val groups = results.groupBy { it.case?.group ?: "?" }
    for ((group, inGroup) in groups) appendLine("| $group | ${score(inGroup) { it.modelPass }} | ${score(inGroup) { it.fullPass }} |")
    appendLine("| **All** | **${score(results) { it.modelPass }}** | **${score(results) { it.fullPass }}** |")
    appendLine()
    appendLine("| | Phrase | By | Model chose | Created | Problems |")
    appendLine("|---|---|---|---|---|---|")
    for (r in results) {
        val mark = when (r.fullPass) { true -> "✅"; false -> "❌"; null -> "" } + if (r.modelPass == false) " (🤖✗)" else ""
        val by = when (r.decidedBy) { DecidedBy.RULE -> "📏"; DecidedBy.CORRECTION -> "🔧"; DecidedBy.MODEL -> "🤖"; null -> "" }
        appendLine(
            "| $mark | ${cell(r.phrase)} | $by | ${cell(r.modelSummary())} | " +
                "${cell(r.created.joinToString("<br>") { it.describe() }.ifEmpty { "(nothing)" })} | ${cell(r.problems.orEmpty().joinToString("<br>"))} |"
        )
    }
}

// "12/14": passes out of the cases that are scored on this.
private fun score(results: List<EvalResult>, pass: (EvalResult) -> Boolean?): String {
    val scored = results.mapNotNull(pass)
    return "${scored.count { it }}/${scored.size}"
}

// A table cell can't contain "|" or line breaks.
private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")
