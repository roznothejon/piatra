package dev.piatra.core

import dev.piatra.data.RecordingEntity
import dev.piatra.data.RecordingStatus
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

// Unit tests: plain JVM, no phone. Run with `./gradlew test`.
// runTest uses virtual time, so the queue's 1-minute retry delay passes instantly:
//   runCurrent()      runs everything that's ready now, without moving the clock
//   advanceTimeBy(x)  moves the clock forward by x, running whatever was due on the way
// (Not advanceUntilIdle(): it ignores coroutines in backgroundScope, where the queue runs.)
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingProcessingQueueTest {

    // In-memory stand-in for the database and audio files.
    private class FakeStore : RecordingQueueStore {
        val rows = mutableMapOf<Long, RecordingEntity>()

        fun add(id: Long, status: RecordingStatus = RecordingStatus.PENDING, attempts: Int = 0) {
            rows[id] = RecordingEntity(
                id = id, fileId = "file$id", createdAt = id, durationMillis = 1000,
                status = status, attempts = attempts,
            )
        }

        override suspend fun get(id: Long) = rows[id]

        override suspend fun idsWithStatus(status: RecordingStatus) =
            rows.values.filter { it.status == status }.sortedBy { it.createdAt }.map { it.id }

        // Like Room's @Update: does nothing if the row was deleted.
        override suspend fun update(recording: RecordingEntity) {
            if (recording.id in rows) rows[recording.id] = recording
        }

        override suspend fun readAudio(fileId: String): ByteArray =
            if (fileId == "missing") throw FileNotFoundException(fileId) else ByteArray(32_000)
    }

    // A transcriber whose behaviour each test decides. `answer` gets the call number (1, 2, …).
    private class ScriptedTranscriptionService(val answer: (call: Int) -> String) : TranscriptionService {
        var calls = 0
        override suspend fun transcribe(pcm: ByteArray): String = answer(++calls)
    }

    private val store = FakeStore()

    // The agent step's stand-in: records what it was asked to act on, and answers with a
    // made-up record of what it decided. A test can make it throw.
    private val acted = mutableListOf<Pair<Long, String>>()
    private var actError: Exception? = null
    private val act: suspend (Long, Long, String) -> String? = { id, _, transcript ->
        actError?.let { throw it }
        acted += id to transcript
        "decided for $id"
    }

    // backgroundScope: cancelled automatically when the test ends, which stops the queue's
    // never-ending worker loop.
    private fun TestScope.queue(transcriber: TranscriptionService) =
        RecordingProcessingQueue(store, transcriber, act, scope = backgroundScope, retryDelay = 1.minutes, maxAttempts = 3)

    // Runs all queued work, including retries, by moving the clock far ahead.
    private fun TestScope.finishQueuedWork() {
        advanceTimeBy(1.hours)
        runCurrent()
    }

    @Test
    fun `pending recording is transcribed`() = runTest {
        store.add(1)
        queue(ScriptedTranscriptionService { "hello" }).queueLocalAudioProcessing(1)
        finishQueuedWork()

        val row = store.rows.getValue(1)
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals("hello", row.transcript)
        assertEquals(1, row.attempts)
        assertEquals(listOf(1L to "hello"), acted) // the agent got the transcript
        assertEquals("decided for 1", row.agentResult) // and what it decided is stored
    }

    @Test
    fun `permanent error fails without retrying`() = runTest {
        store.add(1)
        val transcriber = ScriptedTranscriptionService { error("bad audio") }
        queue(transcriber).queueLocalAudioProcessing(1)
        finishQueuedWork() // would include any retry

        val row = store.rows.getValue(1)
        assertEquals(RecordingStatus.FAILED, row.status)
        assertEquals("bad audio", row.error)
        assertEquals(1, transcriber.calls)
    }

    @Test
    fun `missing audio file fails`() = runTest {
        store.rows[1] = RecordingEntity(id = 1, fileId = "missing", createdAt = 1, durationMillis = 1000)
        queue(ScriptedTranscriptionService { "never" }).queueLocalAudioProcessing(1)
        finishQueuedWork()

        assertEquals(RecordingStatus.FAILED, store.rows.getValue(1).status)
    }

    @Test
    fun `retryable error goes back to pending and is retried after the delay`() = runTest {
        store.add(1)
        val transcriber = ScriptedTranscriptionService { call ->
            if (call == 1) throw RecoverableTaskException("model not ready") else "hello"
        }
        queue(transcriber).queueLocalAudioProcessing(1)

        runCurrent() // first attempt only
        assertEquals(RecordingStatus.PENDING, store.rows.getValue(1).status)
        assertEquals("model not ready", store.rows.getValue(1).error)

        advanceTimeBy(1.minutes)
        runCurrent() // the retry
        val row = store.rows.getValue(1)
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals("hello", row.transcript)
        assertEquals(null, row.error)
        assertEquals(2, transcriber.calls)
    }

    @Test
    fun `gives up after max attempts`() = runTest {
        store.add(1)
        val transcriber = ScriptedTranscriptionService { throw RecoverableTaskException("still not ready") }
        queue(transcriber).queueLocalAudioProcessing(1)
        finishQueuedWork()

        val row = store.rows.getValue(1)
        assertEquals(RecordingStatus.FAILED, row.status)
        assertEquals("Gave up after 3 attempts", row.error)
        assertEquals(3, transcriber.calls)
    }

    @Test
    fun `resume picks up interrupted and pending recordings`() = runTest {
        store.add(1, status = RecordingStatus.TRANSCRIBING, attempts = 1) // app died mid-job
        store.add(2, status = RecordingStatus.PENDING)
        store.add(3, status = RecordingStatus.DONE)
        val transcriber = ScriptedTranscriptionService { "hello" }
        queue(transcriber).resume()
        finishQueuedWork()

        assertEquals(RecordingStatus.DONE, store.rows.getValue(1).status)
        assertEquals(2, store.rows.getValue(1).attempts) // the interrupted try still counts
        assertEquals(RecordingStatus.DONE, store.rows.getValue(2).status)
        assertEquals(2, transcriber.calls) // recording 3 was already done
    }

    @Test
    fun `recording deleted while queued is skipped`() = runTest {
        store.add(1)
        val transcriber = ScriptedTranscriptionService { "hello" }
        queue(transcriber).queueLocalAudioProcessing(1)
        store.rows.remove(1) // deleted before the worker got to it
        finishQueuedWork()

        assertEquals(0, transcriber.calls)
    }

    @Test
    fun `not-ready error doesn't use up an attempt`() = runTest {
        store.add(1)
        // The model "downloads" after 5 tries; with counted attempts this would fail after 3.
        val transcriber = ScriptedTranscriptionService { call ->
            if (call <= 5) throw RecoverableTaskException("model not downloaded", countsAsAttempt = false) else "hello"
        }
        queue(transcriber).queueLocalAudioProcessing(1)
        finishQueuedWork()

        val row = store.rows.getValue(1)
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals(1, row.attempts) // only the try that ran counts
    }

    @Test
    fun `retryPending runs waiting recordings without waiting for the delay`() = runTest {
        store.add(1)
        var modelReady = false
        val transcriber = ScriptedTranscriptionService {
            if (!modelReady) throw RecoverableTaskException("model not downloaded", countsAsAttempt = false) else "hello"
        }
        val queue = queue(transcriber)
        queue.queueLocalAudioProcessing(1)
        runCurrent()
        assertEquals(RecordingStatus.PENDING, store.rows.getValue(1).status)

        modelReady = true
        queue.retryPending()
        runCurrent() // no clock movement: the 1-minute retry hasn't come yet
        assertEquals(RecordingStatus.DONE, store.rows.getValue(1).status)
    }

    @Test
    fun `transcribeAgain starts a finished recording over`() = runTest {
        store.add(1)
        val transcriber = ScriptedTranscriptionService { call -> "take $call" }
        val queue = queue(transcriber)
        queue.queueLocalAudioProcessing(1)
        finishQueuedWork()
        assertEquals("take 1", store.rows.getValue(1).transcript)

        queue.transcribeAgain(1)
        finishQueuedWork()

        val row = store.rows.getValue(1)
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals("take 2", row.transcript)
        assertEquals(1, row.attempts) // reset, then one try
    }

    @Test
    fun `transcribeAgain ignores a recording that's still waiting`() = runTest {
        store.add(1, status = RecordingStatus.PENDING)
        val transcriber = ScriptedTranscriptionService { "hello" }
        queue(transcriber).transcribeAgain(1)
        finishQueuedWork()

        assertEquals(0, transcriber.calls) // left for the normal queue (not queued here)
    }

    @Test
    fun `interrupted agent step reruns the agent only`() = runTest {
        // The app stopped after saving the transcript, while the agent was working.
        store.add(1, status = RecordingStatus.ACTING, attempts = 1)
        store.rows[1] = store.rows.getValue(1).copy(transcript = "note buy milk")
        val transcriber = ScriptedTranscriptionService { "should not be called" }
        queue(transcriber).resume()
        finishQueuedWork()

        assertEquals(RecordingStatus.DONE, store.rows.getValue(1).status)
        assertEquals(0, transcriber.calls)
        assertEquals(listOf(1L to "note buy milk"), acted)
    }

    @Test
    fun `agent failure keeps the transcript`() = runTest {
        store.add(1)
        actError = IllegalArgumentException("bad tool arguments")
        queue(ScriptedTranscriptionService { "hello" }).queueLocalAudioProcessing(1)
        finishQueuedWork()

        val row = store.rows.getValue(1)
        assertEquals(RecordingStatus.FAILED, row.status)
        assertEquals("hello", row.transcript)
        assertEquals("bad tool arguments", row.error)
    }

    @Test
    fun `retryable agent error waits in ACTING and doesn't transcribe again`() = runTest {
        store.add(1)
        actError = RecoverableTaskException("agent model not downloaded", countsAsAttempt = false)
        val transcriber = ScriptedTranscriptionService { "hello" }
        val queue = queue(transcriber)
        queue.queueLocalAudioProcessing(1)
        runCurrent()
        assertEquals(RecordingStatus.ACTING, store.rows.getValue(1).status)

        actError = null // the model "arrives"
        queue.retryPending()
        runCurrent()

        assertEquals(RecordingStatus.DONE, store.rows.getValue(1).status)
        assertEquals(1, transcriber.calls)
    }

    @Test
    fun `transcribeAgain clears the old agent result`() = runTest {
        store.add(1, status = RecordingStatus.DONE)
        store.rows[1] = store.rows.getValue(1).copy(transcript = "old", agentResult = "old decision")
        var answer = "new"
        val queue = queue(ScriptedTranscriptionService { answer })
        actError = RecoverableTaskException("agent model not downloaded", countsAsAttempt = false)
        queue.transcribeAgain(1)
        runCurrent()

        // Waiting for the agent: new transcript, and no stale decision from last time.
        val row = store.rows.getValue(1)
        assertEquals(RecordingStatus.ACTING, row.status)
        assertEquals("new", row.transcript)
        assertEquals(null, row.agentResult)
    }
}
