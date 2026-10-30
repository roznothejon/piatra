package dev.piatra.android

import android.util.Log
import com.cactus.cactusDestroy
import com.cactus.cactusInit
import com.cactus.cactusTranscribe
import com.cactus.isCactusSupported
import dev.piatra.core.PcmFormat
import dev.piatra.core.RecoverableTaskException
import dev.piatra.core.TranscriptionService
import dev.piatra.core.containsSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Speech to text with the on-device Cactus engine and the downloaded Parakeet model.
// The model (~400 MB of memory) is loaded on the first recording and unloaded again once
// nothing has needed it for `unloadAfter`, so the app doesn't hold on to it all the time.
// If the speech model is switched in Settings, the next recording unloads the old model and
// loads the new one (upstream does the same check, lastInitedModel in initIfNeeded).
// Trimmed from upstream util/.../transcription/CactusTranscriptionService.kt.
class CactusTranscriptionService(
    private val speechModels: SpeechModels,
    // For the unload timer. Lives as long as the app.
    private val scope: CoroutineScope,
    private val unloadAfter: Duration = 60.seconds,
) : TranscriptionService {
    // The loaded model, or 0 when none is loaded. Only touched while holding `lock`,
    // so the unload timer can never free the model while a transcription is using it.
    private var handle = 0L
    // The folder of the model in `handle`, to notice when another one has been chosen.
    private var loadedPath: String? = null
    private val lock = Mutex()
    private var unloadJob: Job? = null

    override suspend fun transcribe(pcm: ByteArray): String {
        if (!isCactusSupported()) throw IllegalStateException("This phone's CPU can't run Cactus")
        val modelPath = speechModels.current().modelPathOrNull()
            ?: throw RecoverableTaskException("Speech model not downloaded yet", countsAsAttempt = false)

        unloadJob?.cancel() // in use again: don't unload
        val audioDuration = PcmFormat.durationMillis(pcm.size.toLong()).milliseconds
        // Dispatchers.Default: threads meant for heavy CPU work. The engine blocks its thread
        // until it's done, so this must stay off the main (UI) thread.
        val text = withContext(Dispatchers.Default) {
            lock.withLock {
                if (handle != 0L && loadedPath != modelPath) unload("another speech model was chosen")
                if (handle == 0L) {
                    handle = load(modelPath)
                    loadedPath = modelPath
                }
                // A generous limit, so a stuck engine can't block the queue forever.
                withTimeoutOrNull(1.minutes + audioDuration) {
                    transcribeLoaded(handle, pcm, audioDuration)
                }
            }
        }
        scheduleUnload()
        if (text == null) throw RecoverableTaskException("Transcription took too long")
        if (!containsSpeech(text)) throw IllegalStateException("No speech heard")
        return text.trim()
    }

    private fun load(modelPath: String): Long {
        val start = TimeSource.Monotonic.markNow()
        val newHandle = cactusInit(modelPath)
        Log.i(TAG, "Loaded speech model in ${start.elapsedNow()}")
        return newHandle
    }

    private suspend fun transcribeLoaded(handle: Long, pcm: ByteArray, audioDuration: Duration): String {
        val start = TimeSource.Monotonic.markNow()
        // pcmData: our raw 16 kHz 16-bit mono audio, passed straight in (no WAV file needed).
        val json = runStoppable(handle) {
            cactusTranscribe(handle, audioPath = null, prompt = "", optionsJson = null, callback = null, pcmData = pcm)
        }
        val took = start.elapsedNow()
        // Real-time factor: seconds of work per second of audio. Below 1 is faster than real time.
        val realTimeFactor = took / audioDuration
        Log.i(TAG, "Transcribed $audioDuration of audio in $took (real-time factor %.2f)".format(realTimeFactor))
        Log.d(TAG, "Engine result: $json")
        // The engine answers with JSON; the text is in "response".
        return JSONObject(json).optString("response")
    }

    // Starts (or restarts) the countdown to unloading the model.
    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(unloadAfter)
            lock.withLock {
                if (handle != 0L) unload("$unloadAfter unused")
            }
        }
    }

    // Only call while holding `lock`.
    private fun unload(reason: String) {
        cactusDestroy(handle)
        handle = 0L
        loadedPath = null
        Log.i(TAG, "Unloaded speech model: $reason")
    }

    companion object {
        private const val TAG = "CactusTranscription"
    }
}
