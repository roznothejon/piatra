package dev.piatra.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.piatra.core.Models
import dev.piatra.core.agent.Agent
import dev.piatra.core.eval.EVAL_CASES
import dev.piatra.core.eval.EvalResult
import dev.piatra.core.agent.ListStore
import dev.piatra.core.agent.ShortcutRuleStore
import dev.piatra.core.eval.EvalRunner
import dev.piatra.core.eval.evalReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.io.File
import java.text.DateFormat
import java.util.Date

// What the agent lab shows.
data class LabState(
    val busy: Boolean = false,
    // (done, total) while the test set runs.
    val progress: Pair<Int, Int>? = null,
    // The last typed phrase's result.
    val tried: EvalResult? = null,
    // The last test-set run.
    val results: List<EvalResult> = emptyList(),
    val reportFile: String? = null,
    val error: String? = null,
)

// The agent lab: dry runs of typed phrases or the whole test set through the real
// pipeline (core/eval/EvalRunner.kt). Nothing is saved; results of a test-set run are also written
// as Markdown to filesDir/eval/agent-eval-latest.md.
class LabViewModel(
    model: Agent,
    private val reportDir: File,
    rules: ShortcutRuleStore,
    lists: ListStore,
    useModelText: suspend () -> Boolean,
) : ViewModel() {
    private val runner = EvalRunner(model, TimeZone.currentSystemDefault(), rules, lists, useModelText)

    private val _state = MutableStateFlow(LabState())
    val state: StateFlow<LabState> = _state.asStateFlow()

    val testSetSize = EVAL_CASES.size

    fun tryPhrase(phrase: String) = work {
        val result = runner.run(phrase.trim())
        _state.update { it.copy(tried = result) }
    }

    fun runTestSet() = work {
        val results = runner.runAll { done, total -> _state.update { it.copy(progress = done to total) } }
        val runAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        val file = File(reportDir, "agent-eval-latest.md")
        withContext(Dispatchers.IO) {
            reportDir.mkdirs()
            file.writeText(evalReport(results, runAt, "${Models.AGENT.name} ${Models.AGENT.version}"))
        }
        Log.i(TAG, "Test set done: report in ${file.absolutePath}")
        _state.update { it.copy(results = results, reportFile = file.absolutePath, progress = null) }
    }

    // One run at a time, on threads meant for heavy work (the model runs on the CPU).
    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the agent model isn't downloaded yet.
                Log.e(TAG, "Lab run failed", e)
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName, progress = null) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        private const val TAG = "AgentLab"
    }
}
