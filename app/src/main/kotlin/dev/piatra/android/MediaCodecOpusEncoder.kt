package dev.piatra.android

import android.media.MediaCodec
import android.media.MediaFormat
import dev.piatra.core.PcmFormat
import dev.piatra.core.audio.OpusEncoder
import dev.piatra.core.audio.UndecodableAudioException

// Encodes Opus with Android's built-in encoder (MediaCodec, Android 10+), the mirror image of
// MediaCodecOpusDecoder: PCM goes into input buffers, Opus packets come out of output buffers,
// one per 20 ms. The first output is the encoder's own description of the stream ("codec
// config"), which an Ogg file doesn't need from it (OggOpus writes its own OpusHead).
class MediaCodecOpusEncoder(private val bitrate: Int = PHONE_BITRATE) : OpusEncoder {
    override fun encode(pcm: ByteArray): List<ByteArray> {
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, PcmFormat.SAMPLE_RATE_HZ, PcmFormat.CHANNELS)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            throw UndecodableAudioException("No Opus encoder", e)
        }
        try {
            return run(codec, pcm)
        } catch (e: MediaCodec.CodecException) {
            throw UndecodableAudioException("Opus encoder failed: ${e.diagnosticInfo}", e)
        } catch (e: IllegalStateException) {
            throw UndecodableAudioException("Opus encoder failed", e)
        } finally {
            codec.release()
        }
    }

    private fun run(codec: MediaCodec, pcm: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val info = MediaCodec.BufferInfo()
        var offset = 0
        var inputDone = false
        var idleRounds = 0
        while (true) {
            // Neither side waits while there's work on the other (timeout 0); only a round
            // where nothing moved waits for the codec. Waiting every round made a 4 s note take
            // over a second.
            var moved = false
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    moved = true
                    val buffer = codec.getInputBuffer(index)!!.apply { clear() }
                    // Whole samples only (2 bytes each), as much as the buffer takes.
                    val size = minOf(buffer.remaining(), pcm.size - offset) and 1.inv()
                    if (size > 0) {
                        buffer.put(pcm, offset, size)
                        val micros = offset.toLong() / PcmFormat.BYTES_PER_SAMPLE * 1_000_000 / PcmFormat.SAMPLE_RATE_HZ
                        codec.queueInputBuffer(index, 0, size, micros, 0)
                        offset += size
                    } else {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, if (moved) 0 else WAIT_MICROS)
            if (index < 0) {
                if (!moved && ++idleRounds > MAX_IDLE_ROUNDS) throw UndecodableAudioException("Opus encoder stopped answering")
                continue
            }
            idleRounds = 0
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val buffer = codec.getOutputBuffer(index)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                packets += ByteArray(info.size).also { buffer.get(it) }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return packets
        }
    }

    companion object {
        // Phone-mic notes: 24 kbps (the board uses 16 kbps to save flash and battery; the phone
        // can afford a little more). About 3 KB per second instead of PCM's 32 KB.
        const val PHONE_BITRATE = 24_000
        private const val WAIT_MICROS = 10_000L
        private const val MAX_IDLE_ROUNDS = 500 // × 10 ms = 5 s
    }
}
