package dev.piatra.core.board

import dev.piatra.core.audio.UndecodableAudioException

// A whole sync (docs/esp32-protocol.md section 9, steps 3–7): check in, list the board's
// recordings, download each one (oldest first), save it, then DELETE it on the board.
// Saving goes through RecordingSink, so this file needs no files, database or Bluetooth.

// Where downloaded recordings go: the app's recordings (android/BoardRecordingSink.kt).
interface RecordingSink {
    // True if a recording with this key was saved before. Happens when the app stopped after
    // saving but before the board's DELETE: the board still has it, the phone has it too.
    suspend fun isSaved(key: String): Boolean

    // A new, empty file to download into, for audio in this format (BoardProtocol.FORMAT_*).
    fun newFile(format: Int): IncomingFile
}

// One recording on its way in. Bytes arrive in order, so they're only ever appended.
interface IncomingFile {
    fun append(bytes: ByteArray)

    // Back to empty (a bad CRC: download again from byte 0).
    fun clear()

    // Every byte is in and checked: make it a recording, queued for transcription.
    // Throws UndecodableAudioException if compressed audio can't be decoded.
    suspend fun keep(createdAtMillis: Long, key: String)

    // Not kept: delete the file. Does nothing after keep().
    fun discard()
}

// Recognises a recording that arrives twice. The id alone isn't enough: ids start again at 1
// if the board's flash is erased, so the time and size go in too.
val Reply.Entry.key: String get() = "$id/$recordedAt/$sizeBytes"

// For the progress notification: recording `index` of `total` (from 1), bytes so far.
data class SyncProgress(val index: Int, val total: Int, val receivedBytes: Long, val sizeBytes: Long) {
    val percent: Int get() = if (sizeBytes == 0L) 100 else (receivedBytes * 100 / sizeBytes).toInt()
}

data class SyncSummary(
    // Fresh status, read after the sync, so "recordings waiting" is up to date.
    val status: DeviceStatus,
    val received: Int,
    // Already on the phone (see RecordingSink.isSaved): only deleted on the board.
    val duplicates: Int = 0,
    // Gave up after 3 tries, or couldn't be decoded; left on the board for the next sync.
    val failed: Int = 0,
    // An audio format this app doesn't know (newer firmware); left on the board.
    val unknownFormat: Int = 0,
)

suspend fun BoardLink.sync(
    nowMillis: Long,
    sink: RecordingSink,
    onProgress: (SyncProgress) -> Unit = {},
    // For the app's log (core/ has no Android Log).
    log: (String) -> Unit = {},
): SyncSummary {
    checkIn(nowSeconds = nowMillis / 1000)
    val entries = list().sortedBy { it.id } // ids count up, so this is oldest first
    var received = 0
    var duplicates = 0
    var failed = 0
    var unknownFormat = 0
    for ((i, entry) in entries.withIndex()) {
        if (entry.format !in BoardProtocol.KNOWN_FORMATS) {
            unknownFormat++
            continue
        }
        if (sink.isSaved(entry.key)) {
            duplicates++
            delete(entry.id)
            continue
        }
        val report = { bytes: Long -> onProgress(SyncProgress(i + 1, entries.size, bytes, entry.sizeBytes)) }
        report(0)
        val file = sink.newFile(entry.format)
        try {
            when (download(entry, file, report, log)) {
                Outcome.COMPLETE -> {
                    // No clock on the board when it was recorded (section 6, rule 4): use now.
                    val createdAt = if (entry.recordedAt == 0L) nowMillis else entry.recordedAt * 1000
                    try {
                        file.keep(createdAt, entry.key)
                        received++
                        // Only after it's saved: if the app stops in between, the key catches the copy.
                        delete(entry.id)
                    } catch (e: UndecodableAudioException) {
                        // The bytes arrived intact (the CRC matched), so it's damaged on the board
                        // or this phone can't decode it: keep it there rather than lose it.
                        log("Recording ${entry.id} couldn't be decoded: ${e.message}")
                        failed++
                    }
                }
                Outcome.GAVE_UP -> failed++
                Outcome.GONE -> Unit
            }
        } finally {
            file.discard()
        }
    }
    val status = (request(Command.GetInfo) as? Reply.Info)?.status
        ?: throw BoardProtocolException("GET_INFO after the sync failed")
    return SyncSummary(status, received, duplicates, failed, unknownFormat)
}

// Syncs, then again while recordings are still waiting: one made during a sync
// isn't in that sync's LIST, and the board keeps advertising for it, so Android won't report the
// board as "appeared" again. Only while a round brings something, so a recording that keeps
// failing can't loop; at most `maxRounds`. The counts add up over the rounds.
suspend fun BoardLink.syncUntilEmpty(
    nowMillis: () -> Long,
    sink: RecordingSink,
    onProgress: (SyncProgress) -> Unit = {},
    log: (String) -> Unit = {},
    maxRounds: Int = 3,
): SyncSummary {
    var total = sync(nowMillis(), sink, onProgress, log)
    var last = total
    var rounds = 1
    while (last.status.pendingCount > 0 && last.received + last.duplicates > 0 && rounds < maxRounds) {
        log("${last.status.pendingCount} more waiting: syncing again")
        last = sync(nowMillis(), sink, onProgress, log)
        total = SyncSummary(
            status = last.status,
            received = total.received + last.received,
            duplicates = total.duplicates + last.duplicates,
            failed = last.failed,
            unknownFormat = last.unknownFormat,
        )
        rounds++
    }
    return total
}

// LIST: one ENTRY per recording, then LIST_END with the count.
suspend fun BoardLink.list(): List<Reply.Entry> {
    send(Command.List)
    val entries = mutableListOf<Reply.Entry>()
    while (true) {
        when (val reply = nextReply()) {
            is Reply.Entry -> entries += reply
            is Reply.ListEnd -> {
                if (reply.count != entries.size) {
                    throw BoardProtocolException("LIST_END says ${reply.count}, got ${entries.size} entries")
                }
                return entries
            }
            else -> throw BoardProtocolException("LIST answered with $reply")
        }
    }
}

private suspend fun BoardLink.delete(id: Long) {
    when (val reply = request(Command.Delete(id))) {
        Reply.Deleted -> Unit
        // Already gone: fine, that's what we wanted.
        is Reply.Error -> if (reply.status != Status.NOT_FOUND) throw BoardProtocolException("DELETE($id) answered with $reply")
        else -> throw BoardProtocolException("DELETE($id) answered with $reply")
    }
}

private enum class Outcome { COMPLETE, GAVE_UP, GONE }

// One recording, with Download (Download.kt) deciding what to keep and when to ask again.
// The CRC is worked out as the bytes arrive, so the file isn't read back.
private suspend fun BoardLink.download(
    entry: Reply.Entry, file: IncomingFile, report: (Long) -> Unit, log: (String) -> Unit,
): Outcome {
    val download = Download(entry.id, entry.sizeBytes)
    var crc = Crc32()
    var command = download.nextCommand()
    while (true) {
        send(command)
        val start = when (val reply = nextReply()) {
            is Reply.GetStart -> reply
            // Deleted on the board since LIST (e.g. by another sync): skip it.
            is Reply.Error -> if (reply.status == Status.NOT_FOUND) return Outcome.GONE else
                throw BoardProtocolException("GET(${entry.id}) answered with $reply")
            else -> throw BoardProtocolException("GET(${entry.id}) answered with $reply")
        }
        download.onStart(start)
        // Chunks until GET_END. `next` is set when this round ends with "ask again".
        var next: Command.Get? = null
        while (next == null) {
            when (val incoming = receive()) {
                is Chunk -> when (val result = download.onChunk(incoming)) {
                    is Download.ChunkResult.Write -> {
                        file.append(result.bytes)
                        crc.update(result.bytes)
                        report(download.received)
                    }
                    Download.ChunkResult.Skip -> Unit
                }
                is Reply.GetEnd -> when (val end = download.onEnd(incoming) { crc.value }.also {
                    log("GET_END ${entry.id}: ${download.received}/${entry.sizeBytes} bytes, " +
                        "crc board ${incoming.crc32} phone ${crc.value} → $it")
                }) {
                    Download.EndResult.Complete -> return Outcome.COMPLETE
                    Download.EndResult.GiveUp -> return Outcome.GAVE_UP
                    is Download.EndResult.Retry -> {
                        if (end.discardFile) {
                            file.clear()
                            crc = Crc32()
                        }
                        next = end.command
                    }
                }
                is Reply.Unknown -> Unit
                else -> throw BoardProtocolException("Unexpected $incoming during GET(${entry.id})")
            }
        }
        command = next
    }
}
