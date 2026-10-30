package dev.piatra.core.board

// An Opus recording from the board (format 0x02, docs/esp32-protocol.md section 6) is a row of
// packets, one per 20 ms of audio, each stored as [length u16][packet bytes]. Opus packets
// don't say how long they are themselves, so the length goes in front.

// Opus's own limit for one packet (RFC 6716); anything bigger means the file is damaged.
const val MAX_OPUS_PACKET_BYTES = 1275

fun splitOpusPackets(file: ByteArray): List<ByteArray> {
    val packets = mutableListOf<ByteArray>()
    val r = ByteReader(file)
    while (r.remaining > 0) {
        if (r.remaining < 2) throw BoardProtocolException("Opus file ends inside a packet length")
        val length = r.u16()
        if (length == 0 || length > MAX_OPUS_PACKET_BYTES) {
            throw BoardProtocolException("Opus packet of $length bytes at ${r.position - 2}")
        }
        if (r.remaining < length) throw BoardProtocolException("Opus file ends inside a packet")
        packets += file.copyOfRange(r.position, r.position + length)
        repeat(length) { r.u8() }
    }
    return packets
}
