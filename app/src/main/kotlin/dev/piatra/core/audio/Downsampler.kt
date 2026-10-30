package dev.piatra.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Lowers the sample rate by a whole factor, e.g. 48 kHz → 16 kHz with factor 3 (Android's Opus
// decoder always gives 48 kHz). Keeping every 3rd sample alone would fold sounds above the new
// limit (8 kHz) back down as noise ("aliasing"), so a low-pass filter removes them first.
//
// The filter is a windowed sinc: each output sample is a weighted average of the last `taps`
// input samples. It's fed in pieces (whatever the decoder hands over) and remembers the
// samples it needs between calls.
class Downsampler(private val factor: Int, private val taps: Int = 63) {
    private val weights = lowPass(cutoff = 0.45 / factor, taps)

    // The last `taps` samples, each stored twice (at i and i + taps), so the newest `taps`
    // samples are always one unbroken run starting at `next`.
    private val history = DoubleArray(2 * taps)
    private var next = 0
    private var phase = 0

    fun process(input: ShortArray): ShortArray {
        val out = ShortArray((phase + input.size) / factor)
        var count = 0
        for (sample in input) {
            history[next] = sample.toDouble()
            history[next + taps] = sample.toDouble()
            next = (next + 1) % taps
            if (++phase == factor) {
                phase = 0
                var sum = 0.0
                for (k in 0 until taps) sum += weights[k] * history[next + k]
                out[count++] = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return out
    }

    private companion object {
        // `cutoff` as a fraction of the input sample rate (0.5 = the highest frequency it can hold).
        fun lowPass(cutoff: Double, taps: Int): DoubleArray {
            val middle = (taps - 1) / 2.0
            val w = DoubleArray(taps) { i ->
                val x = i - middle
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val blackman = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
                sinc * blackman
            }
            val total = w.sum() // so a steady signal comes out at the same level
            return DoubleArray(taps) { w[it] / total }
        }
    }
}
