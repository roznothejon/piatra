package dev.piatra.android

import android.util.Log
import com.cactus.cactusComplete
import com.cactus.cactusDestroy
import com.cactus.cactusInit
import com.cactus.isCactusSupported
import dev.piatra.core.RecoverableTaskException
import dev.piatra.core.agent.Agent
import dev.piatra.core.agent.Tool
import dev.piatra.core.agent.ToolCall
import dev.piatra.core.agent.ToolCallJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

// The on-device agent: the needle-pebble-ft model reads the transcript and answers with tool
// calls. It's tiny (36 MB), so it's loaded, used and freed for each recording; nothing stays
// in memory between voice notes.
// Ported from upstream experimental/.../agent/IndexAgentCactus.kt.
class CactusAgent(private val modelManager: ModelManager) : Agent {

    override suspend fun chooseToolCalls(transcript: String, tools: List<Tool>): List<ToolCall> {
        if (!isCactusSupported()) throw IllegalStateException("This phone's CPU can't run Cactus")
        val modelPath = modelManager.modelPathOrNull()
            ?: throw RecoverableTaskException("Agent model not downloaded yet", countsAsAttempt = false)

        // Just the user's words, as upstream sends them: no system prompt, no history.
        val messagesJson = buildJsonArray {
            addJsonObject {
                put("role", "user")
                put("content", transcript)
            }
        }.toString()
        val toolsJson = ToolCallJson.toolsJson(tools)

        val resultJson = withContext(Dispatchers.Default) {
            val start = TimeSource.Monotonic.markNow()
            val handle = cactusInit(modelPath)
            try {
                withTimeoutOrNull(1.minutes) {
                    runStoppable(handle) { cactusComplete(handle, messagesJson, OPTIONS_JSON, toolsJson, callback = null) }
                }
            } finally {
                cactusDestroy(handle) // free it straight away, even after an error
                Log.i(TAG, "Agent ran in ${start.elapsedNow()} (load, think, unload)")
            }
        } ?: throw RecoverableTaskException("Agent took too long")

        Log.d(TAG, "Engine result: $resultJson")
        return ToolCallJson.parseFunctionCalls(resultJson)
    }

    companion object {
        private const val TAG = "CactusAgent"

        // Upstream's settings: at most 256 tokens of output; temperature 0 = always the most likely
        // answer, so the same sentence always gives the same calls; tool_rag_top_k 0 = offer the
        // model every tool instead of letting the engine pre-select a few.
        private val OPTIONS_JSON = buildJsonObject {
            put("max_tokens", 256)
            put("temperature", 0.0)
            put("tool_rag_top_k", 0)
        }.toString()
    }
}
