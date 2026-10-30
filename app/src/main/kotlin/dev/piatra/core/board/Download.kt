package dev.piatra.core.board

// Bookkeeping for downloading one recording with GET (docs/esp32-protocol.md section 5):
// which offset comes next, whether a chunk was missed, and what to do at GET_END.
// It doesn't touch files: the caller writes the bytes it's told to write, and hands over
// the file's CRC at the end. BoardSync drives it from the BLE callbacks.
//
// One Download lives across retries of the same recording:
//   val d = Download(entry.id, entry.sizeBytes)
//   send(d.nextCommand())          // GET(id, 0), or the saved offset when resuming
//   onStart(GET_START) → onChunk(...) for each chunk → onEnd(GET_END) → Complete / Retry / GiveUp
class Download(
    val id: Long,
    val sizeBytes: Long,
    resumeFrom: Long = 0,
    private val maxTries: Int = 3,
) {
    // Bytes saved so far, all in order from byte 0.
    var received: Long = resumeFrom
        private set

    // Failed rounds so far: a CRC mismatch, or a GET that brought no new bytes.
    var tries = 0
        private set

    // Set when a chunk arrives at the wrong offset: the rest of this round is ignored.
    private var gap = false
    private var receivedAtRoundStart = received

    val isComplete: Boolean get() = received == sizeBytes

    fun nextCommand(): Command.Get = Command.Get(id, received)

    fun onStart(reply: Reply.GetStart) {
        if (reply.id != id || reply.offset != received || reply.sizeBytes != sizeBytes) {
            throw BoardProtocolException("Unexpected $reply for GET($id, $received) of $sizeBytes bytes")
        }
        gap = false
        receivedAtRoundStart = received
    }

    sealed interface ChunkResult {
        // Write these bytes at this offset of the temporary file.
        class Write(val offset: Long, val bytes: ByteArray) : ChunkResult

        // Missed chunk earlier in this round: ignore this one, ask again after GET_END.
        data object Skip : ChunkResult
    }

    fun onChunk(chunk: Chunk): ChunkResult {
        if (gap || chunk.offset != received) {
            gap = true
            return ChunkResult.Skip
        }
        if (received + chunk.audio.size > sizeBytes) {
            throw BoardProtocolException("Chunk at ${chunk.offset} runs past the end ($sizeBytes bytes)")
        }
        received += chunk.audio.size
        return ChunkResult.Write(chunk.offset, chunk.audio)
    }

    sealed interface EndResult {
        // Every byte is in and the CRC matches: save the recording, then DELETE it on the board.
        data object Complete : EndResult

        // Send `command` next. With discardFile, delete the temporary file first (bad CRC).
        data class Retry(val command: Command.Get, val discardFile: Boolean) : EndResult

        // Too many failed tries: keep the recording on the board and show an error.
        data object GiveUp : EndResult
    }

    // `crcOfFile` is only called once every byte has arrived.
    fun onEnd(reply: Reply.GetEnd, crcOfFile: () -> Long): EndResult {
        if (reply.id != id) throw BoardProtocolException("GET_END for ${reply.id}, expected $id")

        if (!isComplete) {
            // Missed a chunk: ask for the rest. Only a round with no progress counts as a try,
            // so a weak link that keeps dropping chunks still gets there.
            if (received == receivedAtRoundStart) tries++
            return if (tries >= maxTries) EndResult.GiveUp else EndResult.Retry(nextCommand(), discardFile = false)
        }

        if (crcOfFile() == reply.crc32) return EndResult.Complete

        tries++
        received = 0
        return if (tries >= maxTries) EndResult.GiveUp else EndResult.Retry(nextCommand(), discardFile = true)
    }
}
