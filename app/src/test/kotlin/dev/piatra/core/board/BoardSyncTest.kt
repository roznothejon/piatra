package dev.piatra.core.board

import dev.piatra.core.audio.UndecodableAudioException
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NOW_MILLIS = 1_761_177_600_000L
private const val CHUNK = 510

private class BoardRecording(val audio: ByteArray, val recordedAt: Long, val format: Int = BoardProtocol.FORMAT_PCM_16K)

// A pretend board: answers each command the way the firmware does, with a few ways to misbehave.
private class FakeBoard(vararg recordings: Pair<Long, BoardRecording>) : BoardLink {
    val recordings = mutableMapOf(*recordings)
    val sent = mutableListOf<Command>()
    private val out = ArrayDeque<Incoming>()

    // Leave out the chunk at this offset, once.
    var dropChunkAt: Long? = null
    // Send a wrong CRC this many times.
    var badCrcTimes = 0
    // Send GET_END ahead of the last chunk, once (notifications overtaking each other).
    var endBeforeLastChunk = false
    // Runs after each DELETE (e.g. the button is held during a sync).
    var afterDelete: () -> Unit = {}

    override suspend fun send(command: Command) {
        sent += command
        when (command) {
            Command.GetInfo -> out += Reply.Info(status(pending = recordings.size))
            is Command.SetTime -> out += Reply.TimeSet
            Command.List -> {
                for ((id, r) in recordings) out += Reply.Entry(id, r.audio.size.toLong(), r.recordedAt, r.format)
                out += Reply.ListEnd(recordings.size)
            }
            is Command.Get -> get(command)
            is Command.Delete -> {
                out += if (recordings.remove(command.id) != null) Reply.Deleted
                    else Reply.Error(0x85, Status.NOT_FOUND, 0x03)
                afterDelete()
            }
        }
    }

    private fun get(command: Command.Get) {
        val r = recordings[command.id] ?: return run { out += Reply.Error(0x84, Status.NOT_FOUND, 0x03) }
        out += Reply.GetStart(command.id, command.offset, r.audio.size.toLong())
        val chunks = (command.offset until r.audio.size step CHUNK.toLong()).map { offset ->
            Chunk(offset, r.audio.copyOfRange(offset.toInt(), minOf(offset.toInt() + CHUNK, r.audio.size)))
        }.filter { chunk ->
            val drop = chunk.offset == dropChunkAt
            if (drop) dropChunkAt = null
            !drop
        }
        val crc = if (badCrcTimes-- > 0) 0L else Crc32.of(r.audio)
        val end = Reply.GetEnd(command.id, crc)
        if (endBeforeLastChunk && chunks.isNotEmpty()) {
            endBeforeLastChunk = false
            out += chunks.dropLast(1)
            out += end
            out += chunks.last()
        } else {
            out += chunks
            out += end
        }
    }

    override suspend fun receive(): Incoming = out.removeFirst()
}

private fun status(pending: Int) = DeviceStatus(
    protocolVersion = BoardProtocol.VERSION, flags = 0, pendingCount = pending, batteryMillivolts = 3900,
    storageTotalKiB = 5632, storageFreeKiB = 5000, missedCount = 0, uptimeSeconds = 60,
    resetReason = ResetReason.POWER_ON, firmwareVersion = "0.1",
)

private class Kept(val audio: ByteArray, val createdAtMillis: Long, val key: String, val format: Int)

private class FakeSink(vararg savedKeys: String) : RecordingSink {
    val savedKeys = savedKeys.toMutableSet()
    val kept = mutableListOf<Kept>()
    var discarded = 0
    // keep() fails like an Opus file that can't be decoded.
    var undecodable = false

    override suspend fun isSaved(key: String) = key in savedKeys

    override fun newFile(format: Int) = object : IncomingFile {
        val bytes = ByteArrayOutputStream()
        var done = false
        override fun append(bytes: ByteArray) = this.bytes.write(bytes)
        override fun clear() = bytes.reset()
        override suspend fun keep(createdAtMillis: Long, key: String) {
            if (undecodable) throw UndecodableAudioException("test")
            kept += Kept(bytes.toByteArray(), createdAtMillis, key, format)
            done = true
        }
        override fun discard() {
            if (!done) discarded++
        }
    }
}

// Audio that's easy to check: byte i is i % 251.
private fun audio(size: Int) = ByteArray(size) { (it % 251).toByte() }

class BoardSyncTest {
    @Test
    fun `downloads oldest first, saves, then deletes`() = runTest {
        val board = FakeBoard(
            7L to BoardRecording(audio(2000), recordedAt = 1_761_170_000),
            3L to BoardRecording(audio(1200), recordedAt = 1_761_160_000),
        )
        val sink = FakeSink()
        val summary = board.sync(NOW_MILLIS, sink)

        assertEquals(2, summary.received)
        assertEquals(0, summary.status.pendingCount) // read again after the sync
        assertEquals(listOf(1_761_160_000_000L, 1_761_170_000_000L), sink.kept.map { it.createdAtMillis })
        assertContentEquals(audio(1200), sink.kept[0].audio)
        assertEquals("3/1761160000/1200", sink.kept[0].key)
        assertTrue(board.recordings.isEmpty())
        // Each DELETE comes after that recording's GET_END.
        val commands = board.sent.filter { it is Command.Get || it is Command.Delete }
        assertEquals(listOf(Command.Get(3, 0), Command.Delete(3), Command.Get(7, 0), Command.Delete(7)), commands)
    }

    @Test
    fun `a recording without a time gets the time it arrived`() = runTest {
        val sink = FakeSink()
        FakeBoard(1L to BoardRecording(audio(100), recordedAt = 0)).sync(NOW_MILLIS, sink)
        assertEquals(NOW_MILLIS, sink.kept.single().createdAtMillis)
    }

    @Test
    fun `a missed chunk is asked for again from where it stopped`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(2000), recordedAt = 1)).apply { dropChunkAt = 1020 }
        val sink = FakeSink()
        board.sync(NOW_MILLIS, sink)
        assertTrue(Command.Get(1, 1020) in board.sent)
        assertContentEquals(audio(2000), sink.kept.single().audio)
    }

    @Test
    fun `a bad CRC starts the file again`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(2000), recordedAt = 1)).apply { badCrcTimes = 1 }
        val sink = FakeSink()
        board.sync(NOW_MILLIS, sink)
        assertEquals(2, board.sent.count { it == Command.Get(1, 0) })
        assertContentEquals(audio(2000), sink.kept.single().audio)
    }

    @Test
    fun `after three bad CRCs the recording stays on the board`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(2000), recordedAt = 1)).apply { badCrcTimes = 3 }
        val sink = FakeSink()
        val summary = board.sync(NOW_MILLIS, sink)
        assertEquals(1, summary.failed)
        assertEquals(0, summary.received)
        assertTrue(sink.kept.isEmpty())
        assertEquals(1, sink.discarded)
        assertTrue(1L in board.recordings)
    }

    @Test
    fun `a recording already on the phone is only deleted`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(2000), recordedAt = 5))
        val sink = FakeSink("1/5/2000")
        val summary = board.sync(NOW_MILLIS, sink)
        assertEquals(1, summary.duplicates)
        assertTrue(board.sent.none { it is Command.Get })
        assertTrue(board.recordings.isEmpty())
    }

    @Test
    fun `an unknown audio format is left on the board`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(100), recordedAt = 5, format = 0x7F))
        val summary = board.sync(NOW_MILLIS, FakeSink())
        assertEquals(1, summary.unknownFormat)
        assertTrue(1L in board.recordings)
    }

    @Test
    fun `an Opus recording is handed to the sink as Opus`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(300), recordedAt = 5, format = BoardProtocol.FORMAT_OPUS_16K))
        val sink = FakeSink()
        val summary = board.sync(NOW_MILLIS, sink)
        assertEquals(1, summary.received)
        assertEquals(BoardProtocol.FORMAT_OPUS_16K, sink.kept.single().format)
        assertTrue(board.recordings.isEmpty())
    }

    @Test
    fun `a recording that can't be decoded is left on the board`() = runTest {
        val board = FakeBoard(
            1L to BoardRecording(audio(300), recordedAt = 5, format = BoardProtocol.FORMAT_OPUS_16K),
            2L to BoardRecording(audio(300), recordedAt = 6),
        )
        val sink = FakeSink().apply { undecodable = true }
        val summary = board.sync(NOW_MILLIS, sink)
        assertEquals(2, summary.failed)
        assertEquals(0, summary.received)
        assertEquals(2, sink.discarded)
        assertEquals(setOf(1L, 2L), board.recordings.keys)
    }

    @Test
    fun `GET_END overtaking the last chunk costs one more GET`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(2000), recordedAt = 1)).apply { endBeforeLastChunk = true }
        val sink = FakeSink()
        board.sync(NOW_MILLIS, sink)
        assertTrue(Command.Get(1, 1530) in board.sent)
        assertContentEquals(audio(2000), sink.kept.single().audio)
    }

    @Test
    fun `a recording made during the sync is fetched in a second round`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(500), recordedAt = 1))
        board.afterDelete = {
            board.afterDelete = {}
            board.recordings[2L] = BoardRecording(audio(300), recordedAt = 2)
        }
        val sink = FakeSink()
        val summary = board.syncUntilEmpty({ NOW_MILLIS }, sink)
        assertEquals(2, summary.received)
        assertEquals(0, summary.status.pendingCount)
        assertEquals(2, sink.kept.size)
    }

    @Test
    fun `no second round when nothing came through`() = runTest {
        val board = FakeBoard(1L to BoardRecording(audio(2000), recordedAt = 1)).apply { badCrcTimes = 3 }
        val summary = board.syncUntilEmpty({ NOW_MILLIS }, FakeSink())
        assertEquals(1, summary.failed)
        assertEquals(1, board.sent.count { it == Command.List })
    }
}
