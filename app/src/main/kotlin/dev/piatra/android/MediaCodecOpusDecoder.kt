package dev.piatra.android

import android.media.MediaCodec
import android.media.MediaFormat
import dev.piatra.core.PcmFormat
import dev.piatra.core.audio.Downsampler
import dev.piatra.core.audio.OpusDecoder
import dev.piatra.core.audio.UndecodableAudioException
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Decodes Opus with Android's built-in decoder (MediaCodec, the system's own libopus).
//
// MediaCodec works with buffers it owns: ask for an empty input buffer, fill it with one packet
// and queue it; separately, take filled output buffers (decoded PCM) and give them back. Both
// sides run in one loop here, until the "end of stream" marker comes out the other end.
class MediaCodecOpusDecoder : OpusDecoder {
    override fun decode(packets: List<ByteArray>, out: (ShortArray) -> Unit) {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                configure(opusFormat(), null, null, 0)
                start()
            }
        } catch (e: Exception) {
            throw UndecodableAudioException("No Opus decoder", e)
        }
        try {
            run(codec, packets, out)
        } catch (e: MediaCodec.CodecException) {
            throw UndecodableAudioException("Opus decoder failed: ${e.diagnosticInfo}", e)
        } catch (e: IllegalStateException) {
            throw UndecodableAudioException("Opus decoder failed", e)
        } finally {
            codec.release()
        }
    }

    private fun run(codec: MediaCodec, packets: List<ByteArray>, out: (ShortArray) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var nextPacket = 0
        var inputDone = false
        var downsampler: Downsampler? = null
        var idleRounds = 0
        while (true) {
            // Neither side waits while there's work on the other (timeout 0); only a round
            // where nothing moved waits for the codec (see MediaCodecOpusEncoder).
            var moved = false
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    moved = true
                    if (nextPacket < packets.size) {
                        val packet = packets[nextPacket]
                        codec.getInputBuffer(index)!!.apply { clear(); put(packet) }
                        codec.queueInputBuffer(index, 0, packet.size, nextPacket * FRAME_MICROS, 0)
                        nextPacket++
                    } else {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, if (moved) 0 else WAIT_MICROS)
            if (index < 0) {
                // Nothing ready yet. A decoder that stays silent for seconds is stuck.
                if (!moved && ++idleRounds > MAX_IDLE_ROUNDS) throw UndecodableAudioException("Opus decoder stopped answering")
                continue
            }
            idleRounds = 0
            val buffer = codec.getOutputBuffer(index)!!
            if (info.size > 0) {
                // The output's sample rate is only certain once it's running: 48 kHz on Android.
                val sampler = downsampler ?: Downsampler(factor = outputFactor(codec)).also { downsampler = it }
                out(sampler.process(buffer.toShorts(info)))
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun outputFactor(codec: MediaCodec): Int {
        val format = codec.outputFormat
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (channels != 1 || rate % PcmFormat.SAMPLE_RATE_HZ != 0) {
            throw UndecodableAudioException("Opus decoder gives $rate Hz, $channels channel(s)")
        }
        return rate / PcmFormat.SAMPLE_RATE_HZ
    }

    private fun ByteBuffer.toShorts(info: MediaCodec.BufferInfo): ShortArray {
        position(info.offset)
        limit(info.offset + info.size)
        val shorts = order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(shorts.remaining()).also { shorts.get(it) }
    }

    private companion object {
        const val WAIT_MICROS = 10_000L
        const val MAX_IDLE_ROUNDS = 500 // × 10 ms = 5 s
        const val FRAME_MICROS = 20_000L // each packet is 20 ms

        // The decoder needs a description of the stream before the first packet ("codec-specific
        // data"): csd-0 is the Opus identification header (RFC 7845 section 5.1), csd-1 and csd-2
        // the delays to trim at the start and after a seek, in nanoseconds. Nothing is trimmed:
        // the encoder's few milliseconds of delay don't matter for a voice note.
        fun opusFormat(): MediaFormat {
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray())
                put(1) // version
                put(PcmFormat.CHANNELS.toByte())
                putShort(0) // pre-skip
                putInt(PcmFormat.SAMPLE_RATE_HZ) // the original rate, for information
                putShort(0) // output gain
                put(0) // channel mapping family 0: mono or stereo
                flip()
            }
            return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, PcmFormat.CHANNELS).apply {
                setByteBuffer("csd-0", head)
                setByteBuffer("csd-1", nanos(0))
                setByteBuffer("csd-2", nanos(80_000_000)) // 80 ms, the usual seek pre-roll
            }
        }

        fun nanos(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).flip() as ByteBuffer
    }
}
