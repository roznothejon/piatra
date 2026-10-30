package dev.piatra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piatra.core.agent.DecidedBy
import dev.piatra.core.eval.EvalResult
import org.koin.compose.viewmodel.koinViewModel

// The agent lab: type what you'd say and see what Piatra would do, or run the whole test set.
// Everything is a dry run (nothing saved, no alarms set), as if said on Thu 22 Oct 2026, 14:00.
@Composable
fun LabScreen(onBack: () -> Unit, viewModel: LabViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // rememberSaveable: the typed text survives rotation.
    var phrase by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text("Agent lab", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Dry run: nothing is saved and no alarms are set. Every phrase is treated as said on " +
                "Thu 22 Oct 2026, 14:00.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            label = { Text("What you'd say") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.tryPhrase(phrase) }, enabled = !state.busy && phrase.isNotBlank()) { Text("Run") }
            OutlinedButton(onClick = viewModel::runTestSet, enabled = !state.busy) {
                Text("Run test set (${viewModel.testSetSize})")
            }
        }
        state.progress?.let { (done, total) ->
            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            Text("$done / $total", style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tried?.let { tried -> item { ResultBlock(tried) } }
            if (state.results.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        "Model alone: ${score(state.results) { it.modelPass }} · Result: ${score(state.results) { it.fullPass }}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.reportFile?.let { Text("Saved to $it", style = MaterialTheme.typography.bodySmall) }
                }
                items(state.results) { ResultBlock(it) }
            }
        }
    }
}

// One phrase: pass/fail, what decided, what the model alone chose, what was created, any problems.
@Composable
private fun ResultBlock(result: EvalResult) {
    Column {
        val mark = when (result.fullPass) {
            true -> "✅ "
            false -> "❌ "
            null -> ""
        }
        Text(mark + result.phrase, style = MaterialTheme.typography.bodyLarge)
        val decided = when (result.decidedBy) {
            DecidedBy.RULE -> "📏 a shortcut rule decided"
            DecidedBy.CORRECTION -> "🔧 the model, corrected"
            DecidedBy.MODEL -> "🤖 the model decided"
            null -> "nothing to do"
        }
        Text(decided, style = MaterialTheme.typography.bodySmall)
        Text(
            "Model alone: ${result.modelSummary()}" + if (result.modelPass == false) "  ✗" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (created in result.created) Text(created.describe(), color = MaterialTheme.colorScheme.primary)
        if (result.created.isEmpty()) Text("(nothing created)", color = MaterialTheme.colorScheme.primary)
        for (problem in result.problems.orEmpty()) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun score(results: List<EvalResult>, pass: (EvalResult) -> Boolean?): String {
    val scored = results.mapNotNull(pass)
    return "${scored.count { it }}/${scored.size}"
}
