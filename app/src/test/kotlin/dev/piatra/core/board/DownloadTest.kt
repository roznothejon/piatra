package dev.piatra.core.board

import dev.piatra.core.board.Download.ChunkResult
import dev.piatra.core.board.Download.EndResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

// Plays the board's side of a GET over a fake "file" and collects what the phone writes.
private class FakeTransfer(val audio: ByteArray, val chunkSize: Int = 100) {
    val written = ByteArray(audio.size)
    val crc = Crc32.of(audio)

    // Runs one GET round. `drop` = chunk offsets lost in the air.
    fun round(d: Download, drop: Set<Long> = emptySet(), badCrc: Boolean = false): EndResult {
        val get = d.nextCommand()
        d.onStart(Reply.GetStart(get.id, get.offset, audio.size.toLong()))
        var offset = get.offset.toInt()
        while (offset < audio.size) {
            val end = minOf(offset + chunkSize, audio.size)
            if (offset.toLong() !in drop) {
                val result = d.onChunk(Chunk(offset.toLong(), audio.copyOfRange(offset, end)))
                if (result is ChunkResult.Write) result.bytes.copyInto(written, result.offset.toInt())
            }
            offset = end
        }
        return d.onEnd(Reply.GetEnd(get.id, crc)) { if (badCrc) crc xor 1 else Crc32.of(written) }
    }
}

class DownloadTest {
    private val audio = Random(1).nextBytes(1000)

    @Test
    fun `a clean transfer completes`() {
        val t = FakeTransfer(audio)
        val d = Download(id = 3, sizeBytes = 1000)
        assertEquals(Command.Get(3, 0), d.nextCommand())
        assertEquals(EndResult.Complete, t.round(d))
        assertContentEquals(audio, t.written)
    }

    @Test
    fun `a missed chunk resumes from the gap`() {
        val t = FakeTransfer(audio)
        val d = Download(id = 3, sizeBytes = 1000)
        val first = t.round(d, drop = setOf(400))
        assertEquals(EndResult.Retry(Command.Get(3, 400), discardFile = false), first)
        assertEquals(0, d.tries) // progress was made, so it's not a failed try
        assertEquals(EndResult.Complete, t.round(d))
        assertContentEquals(audio, t.written)
    }

    @Test
    fun `chunks after a gap are skipped`() {
        val d = Download(id = 3, sizeBytes = 1000)
        d.onStart(Reply.GetStart(3, 0, 1000))
        assertIs<ChunkResult.Write>(d.onChunk(Chunk(0, ByteArray(100))))
        assertEquals(ChunkResult.Skip, d.onChunk(Chunk(200, ByteArray(100))))
        // Even the chunk we wanted is no longer trusted in this round.
        assertEquals(ChunkResult.Skip, d.onChunk(Chunk(100, ByteArray(100))))
        assertEquals(100L, d.received)
    }

    @Test
    fun `resuming a saved download starts at the saved offset`() {
        val d = Download(id = 3, sizeBytes = 1000, resumeFrom = 600)
        assertEquals(Command.Get(3, 600), d.nextCommand())
    }

    @Test
    fun `a bad crc starts over and discards the file, then gives up after three`() {
        val t = FakeTransfer(audio)
        val d = Download(id = 3, sizeBytes = 1000)
        assertEquals(EndResult.Retry(Command.Get(3, 0), discardFile = true), t.round(d, badCrc = true))
        assertEquals(EndResult.Retry(Command.Get(3, 0), discardFile = true), t.round(d, badCrc = true))
        assertEquals(EndResult.GiveUp, t.round(d, badCrc = true))
    }

    @Test
    fun `rounds with no progress count as tries`() {
        val t = FakeTransfer(audio)
        val d = Download(id = 3, sizeBytes = 1000)
        repeat(2) { assertIs<EndResult.Retry>(t.round(d, drop = setOf(0))) }
        assertEquals(EndResult.GiveUp, t.round(d, drop = setOf(0)))
    }

    @Test
    fun `mismatched replies are protocol errors`() {
        val d = Download(id = 3, sizeBytes = 1000)
        assertFailsWith<BoardProtocolException> { d.onStart(Reply.GetStart(4, 0, 1000)) }
        assertFailsWith<BoardProtocolException> { d.onStart(Reply.GetStart(3, 0, 999)) }
        d.onStart(Reply.GetStart(3, 0, 1000))
        assertFailsWith<BoardProtocolException> { d.onChunk(Chunk(0, ByteArray(1001))) }
        assertFailsWith<BoardProtocolException> { d.onEnd(Reply.GetEnd(4, 0)) { 0 } }
    }
}
