package dev.piatra.core.board

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpusFramesTest {
    private fun framed(vararg packets: ByteArray): ByteArray {
        val w = ByteWriter()
        for (p in packets) {
            w.u16(p.size)
            for (b in p) w.u8(b.toInt())
        }
        return w.toByteArray()
    }

    @Test
    fun `splits packets at their lengths`() {
        val a = ByteArray(40) { 1 }
        val b = ByteArray(300) { 2 } // over 255: the length needs both bytes
        val packets = splitOpusPackets(framed(a, b))
        assertEquals(2, packets.size)
        assertContentEquals(a, packets[0])
        assertContentEquals(b, packets[1])
    }

    @Test
    fun `an empty file has no packets`() {
        assertTrue(splitOpusPackets(ByteArray(0)).isEmpty())
    }

    @Test
    fun `a cut-off file is refused`() {
        val whole = framed(ByteArray(40), ByteArray(40))
        assertFailsWith<BoardProtocolException> { splitOpusPackets(whole.copyOf(whole.size - 1)) }
        assertFailsWith<BoardProtocolException> { splitOpusPackets(whole.copyOf(43)) } // half a length
    }

    @Test
    fun `impossible lengths are refused`() {
        assertFailsWith<BoardProtocolException> { splitOpusPackets(byteArrayOf(0, 0)) }
        assertFailsWith<BoardProtocolException> { splitOpusPackets(framed(ByteArray(1276))) }
    }
}
