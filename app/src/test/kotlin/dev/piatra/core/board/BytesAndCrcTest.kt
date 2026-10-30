package dev.piatra.core.board

import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BytesAndCrcTest {
    @Test
    fun `numbers are written lowest byte first`() {
        val bytes = ByteWriter().u8(0x84).u16(0x1234).u32(32000).toByteArray()
        assertContentEquals(byteArrayOf(0x84.toByte(), 0x34, 0x12, 0x00, 0x7D, 0x00, 0x00), bytes)
    }

    @Test
    fun `high bytes read back as unsigned`() {
        val bytes = ByteWriter().u8(0xFF).u16(0xFFFF).u32(0xFFFFFFFFL).toByteArray()
        val r = ByteReader(bytes)
        assertEquals(255, r.u8())
        assertEquals(65535, r.u16())
        assertEquals(4_294_967_295L, r.u32())
        assertEquals(0, r.remaining)
    }

    @Test
    fun `reading past the end throws`() {
        assertFailsWith<BoardProtocolException> { ByteReader(byteArrayOf(1, 2, 3)).u32() }
    }

    @Test
    fun `crc matches the common check value`() {
        // The standard test: CRC-32 of the ASCII text "123456789" is CBF43926.
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0L, Crc32.of(ByteArray(0)))
    }

    @Test
    fun `crc matches java's on random data, in one go or in pieces`() {
        val data = Random(42).nextBytes(100_000)
        val java = CRC32().apply { update(data) }.value
        assertEquals(java, Crc32.of(data))

        val pieces = Crc32()
        pieces.update(data, 0, 777)
        pieces.update(data, 777, data.size - 777)
        assertEquals(java, pieces.value)
    }
}
