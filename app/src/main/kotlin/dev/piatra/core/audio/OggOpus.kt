package dev.piatra.core.audio

import java.io.ByteArrayOutputStream

// Ogg Opus (RFC 7845): the standard ".opus" file, which VLC, browsers and phones play.
// Opus itself is only packets (one per 20 ms of sound); Ogg is the box around them: a row of
// "pages", each with a small header saying which packets it holds, how far into the sound they
// reach (the "granule position", counted in 48 kHz samples) and a checksum.
// The first two packets aren't sound: OpusHead (channels, rates) and OpusTags (who wrote it).
//
// Plain Kotlin, no Android, so both directions are unit-tested.
object OggOpus {
    // Opus counts time at 48 kHz, whatever rate it was recorded at.
    private const val GRANULE_RATE = 48_000
    private const val SERIAL = 0x50495452 // any number; one stream per file ("PITR")
    private const val VENDOR = "Piatra"

    fun write(packets: List<ByteArray>, inputSampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var sequence = 0
        fun page(content: List<ByteArray>, granule: Long, flags: Int) = out.write(page(content, granule, flags, sequence++))

        page(listOf(opusHead(inputSampleRate)), 0, FLAG_FIRST)
        page(listOf(opusTags()), 0, 0)

        // Audio pages: as many whole packets as fit in one page's 255 lacing values.
        var granule = 0L
        var pending = mutableListOf<ByteArray>()
        var lacing = 0
        packets.forEachIndexed { index, packet ->
            val needed = packet.size / 255 + 1
            if (lacing + needed > 255) {
                page(pending, granule, 0)
                pending = mutableListOf()
                lacing = 0
            }
            pending += packet
            lacing += needed
            granule += samples48k(packet)
            if (index == packets.lastIndex) page(pending, granule, FLAG_LAST)
        }
        // No sound at all: the last page still has to say "end of stream".
        if (packets.isEmpty()) page(emptyList(), 0, FLAG_LAST)
        return out.toByteArray()
    }

    // The sound packets, in order (OpusHead and OpusTags left out).
    fun read(file: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val current = ByteArrayOutputStream()
        var at = 0
        while (at < file.size) {
            if (file.size - at < 27 || file.decodeToString(at, at + 4) != "OggS") throw UndecodableAudioException("Not an Ogg page at byte $at")
            val segments = file[at + 26].toInt() and 0xFF
            val headerSize = 27 + segments
            if (file.size - at < headerSize) throw UndecodableAudioException("Ogg file ends inside a page header")
            val bodySize = (0 until segments).sumOf { file[at + 27 + it].toInt() and 0xFF }
            val end = at + headerSize + bodySize
            if (end > file.size) throw UndecodableAudioException("Ogg file ends inside a page")
            val stored = int32(file, at + 22)
            val copy = file.copyOfRange(at, end).also { for (i in 22..25) it[i] = 0 }
            if (crc(copy) != stored) throw UndecodableAudioException("Ogg page at byte $at is damaged")

            // Lacing: a packet is a run of 255s ended by a value under 255. A run that reaches
            // the page's end carries on into the next page.
            var body = at + headerSize
            for (i in 0 until segments) {
                val length = file[at + 27 + i].toInt() and 0xFF
                current.write(file, body, length)
                body += length
                if (length < 255) {
                    packets += current.toByteArray()
                    current.reset()
                }
            }
            at = end
        }
        if (packets.size < 2 || packets[0].decodeToString(0, minOf(8, packets[0].size)) != "OpusHead") {
            throw UndecodableAudioException("Not an Ogg Opus file")
        }
        return packets.drop(2)
    }

    // How much sound one packet holds, in 48 kHz samples, from its first ("TOC") byte (RFC 6716
    // section 3.1): bits 3–7 pick the mode and frame length, bits 0–1 how many frames there are.
    fun samples48k(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4] // SILK: 10, 20, 40, 60 ms
            config < 16 -> intArrayOf(480, 960)[config % 2] // hybrid: 10, 20 ms
            else -> intArrayOf(120, 240, 480, 960)[config % 4] // CELT: 2.5, 5, 10, 20 ms
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 0
        }
        return frame * frames
    }

    // How long a file's sound is, from its packets.
    fun durationMillis(packets: List<ByteArray>): Long = packets.sumOf { samples48k(it).toLong() } * 1000 / GRANULE_RATE

    private fun opusHead(inputSampleRate: Int): ByteArray {
        val head = ByteArrayOutputStream()
        head.write("OpusHead".encodeToByteArray())
        head.write(1) // version
        head.write(1) // channels: mono
        head.write(le(0, 2)) // pre-skip: nothing trimmed at the start (a few ms, as the decoder does)
        head.write(le(inputSampleRate.toLong(), 4)) // the rate it was recorded at, for information
        head.write(le(0, 2)) // output gain
        head.write(0) // channel mapping family 0: mono or stereo
        return head.toByteArray()
    }

    private fun opusTags(): ByteArray {
        val tags = ByteArrayOutputStream()
        tags.write("OpusTags".encodeToByteArray())
        tags.write(le(VENDOR.length.toLong(), 4))
        tags.write(VENDOR.encodeToByteArray())
        tags.write(le(0, 4)) // no comments
        return tags.toByteArray()
    }

    private fun page(packets: List<ByteArray>, granule: Long, flags: Int, sequence: Int): ByteArray {
        val lacing = ByteArrayOutputStream()
        for (p in packets) {
            repeat(p.size / 255) { lacing.write(255) }
            lacing.write(p.size % 255)
        }
        val page = ByteArrayOutputStream()
        page.write("OggS".encodeToByteArray())
        page.write(0) // version
        page.write(flags)
        page.write(le(granule, 8))
        page.write(le(SERIAL.toLong(), 4))
        page.write(le(sequence.toLong(), 4))
        page.write(le(0, 4)) // the checksum, filled in below
        page.write(lacing.size())
        page.write(lacing.toByteArray())
        for (p in packets) page.write(p)
        val bytes = page.toByteArray()
        le(crc(bytes).toLong(), 4).copyInto(bytes, 22)
        return bytes
    }

    private const val FLAG_FIRST = 0x02
    private const val FLAG_LAST = 0x04

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value shr (8 * it)).toByte() }

    private fun int32(b: ByteArray, at: Int) = (0..3).fold(0) { acc, i -> acc or ((b[at + i].toInt() and 0xFF) shl (8 * i)) }

    // Ogg's checksum: CRC-32 with polynomial 0x04C11DB7, start 0, bits not reversed (unlike zip's).
    private val crcTable = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun crc(bytes: ByteArray): Int {
        var c = 0
        for (b in bytes) c = (c shl 8) xor crcTable[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }
}
