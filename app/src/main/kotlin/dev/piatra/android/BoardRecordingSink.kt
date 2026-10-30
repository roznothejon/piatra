package dev.piatra.android

import dev.piatra.core.PcmFormat
import dev.piatra.core.RecordingProcessingQueue
import dev.piatra.core.audio.OpusDecoder
import dev.piatra.core.audio.UndecodableAudioException
import dev.piatra.core.board.BoardProtocol
import dev.piatra.core.board.BoardProtocolException
import dev.piatra.core.board.IncomingFile
import dev.piatra.core.board.RecordingSink
import dev.piatra.core.board.splitOpusPackets
import dev.piatra.data.RecordingRepository
import java.io.ByteArrayOutputStream
import java.io.OutputStream

// Board recordings become ordinary recordings: the same file, row and queue as the phone mic
// (MainViewModel.keepOrDiscard), plus the board key that catches a copy arriving twice.
// Raw PCM goes straight into the recording file and is compressed when it's kept. Opus is
// collected in memory (a minute is only ~120 KB) and stored as it came, as an Ogg Opus file;
// decoding it once on the way in proves it's sound and gives its exact length.
class BoardRecordingSink(
    private val storage: RecordingStorage,
    private val repository: RecordingRepository,
    private val queue: RecordingProcessingQueue,
    private val opusDecoder: OpusDecoder,
) : RecordingSink {
    override suspend fun isSaved(key: String): Boolean = repository.hasBoardRecording(key)

    override fun newFile(format: Int): IncomingFile = object : IncomingFile {
        val fileId = storage.newRecordingId()
        val opus = if (format == BoardProtocol.FORMAT_OPUS_16K) ByteArrayOutputStream() else null
        var out: OutputStream = opus ?: storage.openForWriting(fileId, append = false)
        var kept = false

        override fun append(bytes: ByteArray) = out.write(bytes)

        override fun clear() {
            out.close()
            out = opus?.apply { reset() } ?: storage.openForWriting(fileId, append = false)
        }

        override suspend fun keep(createdAtMillis: Long, key: String) {
            out.close()
            val durationMillis = if (opus != null) saveOpus(opus.toByteArray()) else {
                PcmFormat.durationMillis(storage.pcmSizeBytes(fileId)).also { storage.compress(fileId) }
            }
            val id = repository.add(fileId, createdAtMillis, durationMillis, boardKey = key)
            kept = true
            queue.queueLocalAudioProcessing(id)
        }

        // Returns the length in milliseconds.
        private fun saveOpus(file: ByteArray): Long {
            val packets = try {
                splitOpusPackets(file)
            } catch (e: BoardProtocolException) {
                throw UndecodableAudioException(e.message ?: "Bad Opus file", e)
            }
            var samples = 0L
            opusDecoder.decode(packets) { samples += it.size }
            storage.writeOpus(fileId, packets)
            return samples * 1000 / PcmFormat.SAMPLE_RATE_HZ
        }

        override fun discard() {
            if (kept) return
            runCatching { out.close() }
            storage.delete(fileId)
        }
    }
}
