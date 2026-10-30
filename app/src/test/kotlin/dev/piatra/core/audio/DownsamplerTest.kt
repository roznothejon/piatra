package dev.piatra.core.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownsamplerTest {
    private fun tone(hz: Int, samples: Int, rate: Int = 48_000) =
        ShortArray(samples) { (10_000 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    // Loudness, skipping the filter's warm-up at the start.
    private fun rms(samples: ShortArray) =
        sqrt(samples.drop(100).sumOf { it.toDouble() * it } / (samples.size - 100))

    @Test
    fun `48 kHz becomes a third as many samples`() {
        assertEquals(16_000, Downsampler(3).process(ShortArray(48_000)).size)
    }

    @Test
    fun `speech frequencies pass, high ones are removed`() {
        val speech = rms(Downsampler(3).process(tone(1_000, 48_000)))
        val high = rms(Downsampler(3).process(tone(12_000, 48_000)))
        assertTrue(speech > 6_500, "1 kHz came out at $speech") // in: ~7071
        assertTrue(high < 50, "12 kHz came out at $high")
    }

    @Test
    fun `pieces of any size give the same result as one go`() {
        val input = tone(1_000, 4_801)
        val whole = Downsampler(3).process(input)
        val pieces = Downsampler(3).let { d ->
            listOf(0..6, 7..999, 1000..4800).flatMap { d.process(input.sliceArray(it)).toList() }
        }
        assertContentEquals(whole, pieces.toShortArray())
    }
}
