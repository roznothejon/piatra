package dev.piatra.core.board

// Little-endian reading and writing for the board protocol (docs/esp32-protocol.md).
// Little-endian = lowest byte first: 32000 (0x00007D00) is sent as 00 7D 00 00.
// Plain Kotlin instead of java.nio.ByteBuffer, so this file would also work on iOS.

class BoardProtocolException(message: String) : Exception(message)

class ByteReader(private val bytes: ByteArray, start: Int = 0) {
    var position = start
        private set

    val remaining: Int get() = bytes.size - position

    // Kotlin's Byte is signed (0x84 is -124), so mask it to get 0..255.
    fun u8(): Int {
        need(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    // u32 can be bigger than Int allows (up to 4,294,967,295), so it's a Long.
    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)

    // Everything left, e.g. the firmware version text at the end of INFO.
    fun rest(): ByteArray = bytes.copyOfRange(position, bytes.size).also { position = bytes.size }

    private fun need(count: Int) {
        if (remaining < count) {
            throw BoardProtocolException("Packet too short: needed $count more byte(s) at position $position")
        }
    }
}

class ByteWriter {
    private val out = ArrayList<Byte>()

    fun u8(value: Int) = apply { out.add((value and 0xFF).toByte()) }

    fun u16(value: Int) = apply { u8(value).u8(value shr 8) }

    fun u32(value: Long) = apply { u16((value and 0xFFFF).toInt()).u16(((value shr 16) and 0xFFFF).toInt()) }

    fun bytes(value: ByteArray) = apply { value.forEach { out.add(it) } }

    fun toByteArray(): ByteArray = out.toByteArray()
}
