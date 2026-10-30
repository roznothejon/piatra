package dev.piatra.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.piatra.android.CactusAgent
import dev.piatra.core.Models
import dev.piatra.core.agent.ListStore
import dev.piatra.core.agent.ShortcutRuleStore
import dev.piatra.core.agent.ToolCallJson
import dev.piatra.core.eval.EvalRunner
import dev.piatra.core.eval.evalReport
import dev.piatra.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

// The agent lab over adb, for Claude to test agent changes without touching the screen
// (debug builds only: lives in src/debug). The same dry run as the lab: nothing is saved.
//   adb shell am broadcast -p dev.piatra -a dev.piatra.debug.TRY --es phrase "add puka to my cat list"
//     (add --ez model_text false/true to override the "Use the model's wording" setting)
//   adb shell am start -n dev.piatra/.MainActivity   (the test set needs the app in front)
//   adb shell am broadcast -p dev.piatra -a dev.piatra.debug.TEST_SET
//   adb logcat -s PiatraLab
// TRY logs the decision and what would be created; TEST_SET writes files/eval/agent-eval-latest.md
// (like the lab's button) and logs the score. -p: Android only delivers broadcasts addressed to
// an app to receivers declared in its manifest.
class LabHookReceiver : BroadcastReceiver(), KoinComponent {
    private val model: CactusAgent by inject()
    private val rules: ShortcutRuleStore by inject()
    private val lists: ListStore by inject()
    private val settings: Settings by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val reportDir = File(context.filesDir, "eval")
        // The work outlives onReceive (the model takes seconds), so it runs in a scope of its own.
        // Android freezes apps in the background, and that froze the work mid-run.
        // goAsync: "this broadcast isn't finished yet", which keeps the app awake
        // until finish(). Android allows that for about a minute: plenty for one phrase, too
        // short for the test set (~100 s), which is why the test-set command opens the app first.
        val pending = if (intent.action == ACTION_TRY) goAsync() else null
        scope.launch {
            try {
                running.withLock {
                    runCatching {
                        val modelText = if (intent.hasExtra("model_text")) intent.getBooleanExtra("model_text", true) else null
                        val runner = EvalRunner(model, TimeZone.currentSystemDefault(), rules, lists) {
                            modelText ?: settings.isModelTextOn()
                        }
                        when (intent.action) {
                            ACTION_TRY -> tryPhrase(runner, intent.getStringExtra("phrase").orEmpty())
                            ACTION_TEST_SET -> testSet(runner, reportDir)
                        }
                    }.onFailure { Log.e(TAG, "Failed", it) }
                }
            } finally {
                pending?.finish()
            }
        }
    }

    private suspend fun tryPhrase(runner: EvalRunner, phrase: String) {
        if (phrase.isBlank()) {
            Log.w(TAG, "No phrase: add --es phrase \"...\"")
            return
        }
        val result = runner.run(phrase.trim())
        Log.i(TAG, "PHRASE  $phrase")
        Log.i(TAG, "MODEL   ${result.modelSummary()}")
        Log.i(TAG, "DECIDED ${result.decidedBy} · ${result.calls.joinToString("; ") { ToolCallJson.describe(it) }}")
        result.created.forEach { Log.i(TAG, "CREATED ${it.describe()}") }
        result.toolError?.let { Log.i(TAG, "ERROR   $it") }
        Log.i(TAG, "END")
    }

    private suspend fun testSet(runner: EvalRunner, reportDir: File) {
        val results = runner.runAll { done, total -> Log.i(TAG, "Test set $done/$total") }
        reportDir.mkdirs()
        val file = File(reportDir, "agent-eval-latest.md")
        file.writeText(evalReport(results, "adb", "${Models.AGENT.name} ${Models.AGENT.version}"))
        Log.i(TAG, "TEST SET ${results.count { it.fullPass == true }}/${results.size} pass, model alone ${results.count { it.modelPass == true }}/${results.size}; report in ${file.absolutePath}")
        Log.i(TAG, "END")
    }

    companion object {
        private const val TAG = "PiatraLab"
        private const val ACTION_TRY = "dev.piatra.debug.TRY"
        private const val ACTION_TEST_SET = "dev.piatra.debug.TEST_SET"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // One run at a time: the model can only do one thing at once.
        private val running = Mutex()
    }
}
