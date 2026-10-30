package dev.piatra.core

import dev.piatra.data.RecordingEntity
import dev.piatra.data.RecordingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// What the queue needs from storage. The app uses RecordingRepository (Room + files);
// tests use an in-memory fake, so the queue's logic can be tested without a phone.
interface RecordingQueueStore {
    suspend fun get(id: Long): RecordingEntity?
    suspend fun idsWithStatus(status: RecordingStatus): List<Long> // oldest first
    suspend fun update(recording: RecordingEntity)
    suspend fun readAudio(fileId: String): ByteArray
}

// Processes saved recordings one at a time, in the background: transcribe, then let the agent
// act on the transcript. Makes sure each one ends up DONE or FAILED, even across crashes and
// restarts. The recording's row is the job: its `status` is the state machine (see
// RecordingStatus) and `attempts` counts tries.
// Ported and trimmed from upstream's RecordingProcessingQueue + PersistentQueueScheduler.
class RecordingProcessingQueue(
    private val store: RecordingQueueStore,
    private val transcriptionService: TranscriptionService,
    // The agent step: given a recording (id, when it was recorded) and its transcript, act on it,
    // and return a record of what was decided (stored as agentResult). A function type, so the
    // queue doesn't need to know what an agent is; the app passes AgentRunner.run (see AppModule).
    private val act: suspend (recordingId: Long, recordedAt: Long, transcript: String) -> String?,
    // Lives as long as the app (not a screen), so work carries on when you leave the screen.
    private val scope: CoroutineScope,
    private val retryDelay: Duration = 1.minutes,
    private val maxAttempts: Int = 3,
) {
    // Ids of recordings waiting to be processed. A Channel is a pipe between coroutines:
    // queueLocalAudioProcessing() sends into it, the worker below receives in order.
    private val waiting = Channel<Long>(Channel.UNLIMITED)

    init {
        // The worker: one loop, so one job at a time (the speech model is heavy).
        // `for` suspends while the channel is empty and wakes when an id arrives.
        scope.launch {
            for (id in waiting) {
                try {
                    process(id)
                } catch (e: CancellationException) {
                    throw e // the app is shutting down
                } catch (e: Exception) {
                    // E.g. a database error. Don't let one job stop the worker for good;
                    // the row keeps its status, and resume() looks at it again next launch.
                    println("RecordingProcessingQueue: job $id hit an unexpected error: $e")
                }
            }
        }
    }

    // Entry point: queue a saved recording for processing. Same name as upstream's.
    fun queueLocalAudioProcessing(recordingId: Long) {
        waiting.trySend(recordingId)
    }

    // Call once at app start. Picks up work left over from last time.
    fun resume() {
        scope.launch {
            // Still TRANSCRIBING means the app stopped mid-job. The attempt was already counted,
            // so a recording that crashes the app every time is given up on after maxAttempts.
            // (Upstream fails these straight away instead; ours are often harmless interruptions.)
            // A row left ACTING keeps its status: its transcript is saved, so only the agent reruns.
            for (id in store.idsWithStatus(RecordingStatus.TRANSCRIBING)) {
                val recording = store.get(id) ?: continue
                store.update(recording.copy(status = RecordingStatus.PENDING))
            }
            retryPendingNow()
        }
    }

    // Try every waiting recording now instead of after its retry delay,
    // e.g. when the speech model has just finished downloading.
    fun retryPending() {
        scope.launch { retryPendingNow() }
    }

    // Everything with work left: PENDING (needs transcribing) and ACTING (needs the agent).
    // Queuing an id twice is harmless: process() skips a row that has moved on.
    private suspend fun retryPendingNow() {
        store.idsWithStatus(RecordingStatus.PENDING).forEach(::queueLocalAudioProcessing)
        store.idsWithStatus(RecordingStatus.ACTING).forEach(::queueLocalAudioProcessing)
    }

    // Transcribe a finished or failed recording again, e.g. to compare models.
    // Starts it over: no transcript, no error, attempts back to 0. The agent then replaces
    // what this recording created last time (see AgentRunner).
    fun transcribeAgain(recordingId: Long) {
        scope.launch {
            val recording = store.get(recordingId) ?: return@launch
            // Still in progress: nothing to do.
            if (recording.status != RecordingStatus.DONE && recording.status != RecordingStatus.FAILED) {
                return@launch
            }
            store.update(
                recording.copy(
                    status = RecordingStatus.PENDING, attempts = 0, transcript = null, error = null, agentResult = null,
                )
            )
            queueLocalAudioProcessing(recordingId)
        }
    }

    // One step of a recording's job: PENDING -> transcribe, then act; ACTING -> act only
    // (the transcript was saved last time, before the app stopped or the agent hit a snag).
    private suspend fun process(id: Long) {
        val recording = store.get(id) ?: return // deleted while it waited
        // Anything else was already handled (e.g. queued twice).
        if (recording.status != RecordingStatus.PENDING && recording.status != RecordingStatus.ACTING) return
        if (recording.attempts >= maxAttempts) {
            store.update(
                recording.copy(status = RecordingStatus.FAILED, error = "Gave up after $maxAttempts attempts")
            )
            return
        }

        // copy(): a new RecordingEntity with these fields changed and the rest the same.
        // Saved before the work starts, so a crash part-way still counts as an attempt.
        val needsTranscript = recording.status == RecordingStatus.PENDING
        var current = recording.copy(
            status = if (needsTranscript) RecordingStatus.TRANSCRIBING else RecordingStatus.ACTING,
            attempts = recording.attempts + 1,
            error = null,
        )
        store.update(current)
        try {
            if (needsTranscript) {
                val transcript = transcriptionService.transcribe(store.readAudio(recording.fileId))
                // Saved before the agent runs, so the transcript survives whatever happens next.
                current = current.copy(status = RecordingStatus.ACTING, transcript = transcript)
                store.update(current)
            }
            val agentResult = act(id, current.createdAt, checkNotNull(current.transcript) { "No transcript to act on" })
            store.update(current.copy(status = RecordingStatus.DONE, agentResult = agentResult))
        } catch (e: CancellationException) {
            throw e // shutting down: the row stays TRANSCRIBING/ACTING for resume() to find
        } catch (e: RecoverableTaskException) {
            // Might work later: wait at the stage that failed (transcribing -> PENDING, the agent
            // -> ACTING) and try again after retryDelay. If it couldn't even start, give the attempt back.
            val waitingStatus =
                if (current.status == RecordingStatus.TRANSCRIBING) RecordingStatus.PENDING else RecordingStatus.ACTING
            val attempts = if (e.countsAsAttempt) current.attempts else recording.attempts
            store.update(current.copy(status = waitingStatus, attempts = attempts, error = e.message))
            scope.launch {
                delay(retryDelay)
                queueLocalAudioProcessing(id)
            }
        } catch (e: Exception) {
            // Retrying won't help. `current` keeps the transcript if there is one.
            store.update(
                current.copy(status = RecordingStatus.FAILED, error = e.message ?: e::class.simpleName)
            )
        }
    }
}
