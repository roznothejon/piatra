package dev.piatra.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.piatra.core.audio.UndecodableAudioException
import dev.piatra.core.board.splitOpusPackets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

// Runs on the phone: decodes a clip that the laptop encoded like the board will
// (firmware/tools/opus_frame.py tones.pcm tones.opus) and compares it with the original.
// tones.pcm is 3 s of made-up sound: tone bursts and a sweep with silence between them.
@RunWith(AndroidJUnit4::class)
class MediaCodecOpusDecoderTest {
    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private fun ByteArray.toShorts() =
        ShortArray(size / 2) { ((this[2 * it].toInt() and 0xFF) or (this[2 * it + 1].toInt() shl 8)).toShort() }

    // Loudness in 50 ms steps: lossy audio changes the wave, but not where the sound is.
    private fun envelope(samples: ShortArray) = samples.toList().chunked(800).map { piece ->
        sqrt(piece.sumOf { it.toDouble() * it } / piece.size)
    }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
        val n = minOf(a.size, b.size)
        val ma = a.take(n).average()
        val mb = b.take(n).average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) {
            ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb)
        }
        return ab / sqrt(aa * bb)
    }

    @Test
    fun decodesTheBoardsFormatTo16kHz() {
        val original = asset("tones.pcm").toShorts()
        val decoded = mutableListOf<Short>()
        MediaCodecOpusDecoder().decode(splitOpusPackets(asset("tones.opus"))) { decoded += it.toList() }

        // 151 packets × 20 ms; the encoder pads the last one and adds a few ms of delay.
        assertTrue("decoded ${decoded.size} samples for ${original.size}", decoded.size in 48_000..48_640)
        val match = correlation(envelope(original), envelope(decoded.toShortArray()))
        assertTrue("loudness follows the original: $match", match > 0.95)
    }

    @Test(expected = UndecodableAudioException::class)
    fun garbageIsRefused() {
        // Packets that aren't Opus at all (the TOC byte 0xFF announces a frame layout they don't have).
        MediaCodecOpusDecoder().decode(List(5) { ByteArray(3) { 0xFF.toByte() } }) {}
    }

    @Test
    fun noPacketsGiveNoSamples() {
        var count = 0
        MediaCodecOpusDecoder().decode(emptyList()) { count += it.size }
        assertEquals(0, count)
    }
}
