package dev.piatra.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.piatra.core.audio.OggOpus
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

// Runs on the phone: the same 3 s of tones as MediaCodecOpusDecoderTest, encoded by Android's
// Opus encoder, put in an Ogg file, read back and decoded.
@RunWith(AndroidJUnit4::class)
class MediaCodecOpusEncoderTest {
    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private fun ByteArray.toShorts() =
        ShortArray(size / 2) { ((this[2 * it].toInt() and 0xFF) or (this[2 * it + 1].toInt() shl 8)).toShort() }

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
    fun encodesToOggAndBack() {
        val pcm = asset("tones.pcm")
        val packets = MediaCodecOpusEncoder().encode(pcm)
        val file = OggOpus.write(packets, 16_000)
        val decoded = mutableListOf<Short>()
        MediaCodecOpusDecoder().decode(OggOpus.read(file)) { decoded += it.toList() }

        // 3 s at 24 kbps is ~9 KB (PCM: 96 KB); allow for the headers and the encoder's choices.
        assertTrue("file is ${file.size} bytes", file.size in 4_000..16_000)
        assertTrue("${OggOpus.durationMillis(packets)} ms", OggOpus.durationMillis(packets) in 2_950..3_100)
        val original = pcm.toShorts()
        assertTrue("decoded ${decoded.size} samples for ${original.size}", decoded.size in original.size - 800..original.size + 1_600)
        val match = correlation(envelope(original), envelope(decoded.toShortArray()))
        assertTrue("loudness follows the original: $match", match > 0.95)
    }
}
