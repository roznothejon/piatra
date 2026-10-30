package dev.piatra.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals

class OggOpusTest {
    // TOC 0x48 = SILK, 20 ms, one frame (what the board's 16 kbps voip encoder makes).
    private fun packet(size: Int, toc: Int = 0x48) = ByteArray(size) { if (it == 0) toc.toByte() else (it * 7).toByte() }

    @Test
    fun `packets read back unchanged, across many pages`() {
        val packets = List(400) { packet(10 + it % 300) }
        val file = OggOpus.write(packets, 16_000)
        assertEquals(packets.size, OggOpus.read(file).size)
        OggOpus.read(file).zip(packets).forEach { (a, b) -> assertContentEquals(b, a) }
    }

    @Test
    fun `a packet of exactly 255 bytes gets an ending zero`() {
        val packets = listOf(packet(255), packet(510), packet(1))
        OggOpus.read(OggOpus.write(packets, 16_000)).zip(packets).forEach { (a, b) -> assertContentEquals(b, a) }
    }

    @Test
    fun `the file starts like any Ogg Opus file and ends its stream`() {
        val file = OggOpus.write(listOf(packet(40)), 16_000)
        assertEquals("OggS", file.decodeToString(0, 4))
        assertEquals(0x02, file[5].toInt()) // first page
        assertEquals("OpusHead", file.decodeToString(28, 36))
        // The last page has the end flag and the granule position (960 samples at 48 kHz = 20 ms).
        val last = file.size - (27 + 1 + 40)
        assertEquals(0x04, file[last + 5].toInt())
        assertEquals(960, (file[last + 6].toInt() and 0xFF) or ((file[last + 7].toInt() and 0xFF) shl 8))
    }

    @Test
    fun `a damaged page is refused`() {
        val file = OggOpus.write(List(5) { packet(40) }, 16_000)
        file[file.size - 3] = (file[file.size - 3] + 1).toByte()
        assertFailsWith<UndecodableAudioException> { OggOpus.read(file) }
        assertFailsWith<UndecodableAudioException> { OggOpus.read("not ogg at all, nope".encodeToByteArray()) }
    }

    @Test
    fun `no sound is still a valid file`() {
        assertEquals(emptyList(), OggOpus.read(OggOpus.write(emptyList(), 16_000)))
    }

    @Test
    fun `packet lengths come from the TOC byte`() {
        assertEquals(960, OggOpus.samples48k(packet(3, 0x48))) // SILK 20 ms
        assertEquals(2880, OggOpus.samples48k(packet(3, 0x58))) // SILK 60 ms
        assertEquals(1920, OggOpus.samples48k(packet(3, 0x49))) // SILK 20 ms, two frames
        assertEquals(960, OggOpus.samples48k(packet(3, 0xF8))) // CELT 20 ms
        assertEquals(120 * 5, OggOpus.samples48k(byteArrayOf(0x83.toByte(), 5))) // CELT 2.5 ms × 5 frames
        assertEquals(2_000, OggOpus.durationMillis(List(100) { packet(3) }))
    }
}
