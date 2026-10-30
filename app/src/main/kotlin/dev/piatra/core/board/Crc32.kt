package dev.piatra.core.board

// CRC-32 (IEEE 802.3), the same checksum as zip and java.util.zip.CRC32: a 32-bit
// fingerprint of a file. The board sends it at the end of each transfer (GET_END), and the
// phone compares it with its own, so a corrupted download is caught.
// Written out instead of using java.util.zip.CRC32, so core/ stays free of Java-only APIs.
// The tests check it against java.util.zip.CRC32.
class Crc32 {
    private var crc = 0xFFFFFFFF.toInt()

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        for (i in offset until offset + length) {
            crc = TABLE[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
    }

    // As an unsigned number, like the protocol's u32.
    val value: Long get() = (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL

    companion object {
        private const val POLYNOMIAL = 0xEDB88320.toInt()

        // The result of running each possible byte through 8 shift-and-mix steps, worked out
        // once, so update() handles a whole byte per step (a 10-minute recording is 19 MB).
        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) {
                // Shift right; if the bit that fell off was 1, mix in the polynomial.
                c = if (c and 1 != 0) (c ushr 1) xor POLYNOMIAL else c ushr 1
            }
            c
        }

        fun of(bytes: ByteArray): Long = Crc32().apply { update(bytes) }.value
    }
}
