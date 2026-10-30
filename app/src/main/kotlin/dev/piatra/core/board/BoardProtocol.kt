package dev.piatra.core.board

// The board ↔ phone BLE protocol (docs/esp32-protocol.md, version 1) as Kotlin types:
// commands the phone writes are encoded to bytes, replies and audio chunks the board
// notifies are decoded from bytes. No Bluetooth here; KableBoardLink moves the bytes.

object BoardProtocol {
    const val VERSION = 1

    // UUIDs as strings, so this file needs no java.util.UUID (section 2).
    const val SERVICE_UUID = "cdf70001-b272-4e8a-b578-c59339280171"
    const val CONTROL_UUID = "cdf70002-b272-4e8a-b578-c59339280171"
    const val DATA_UUID = "cdf70003-b272-4e8a-b578-c59339280171"

    const val DEVICE_NAME = "Piatra"
    const val MANUFACTURER_ID = 0xFFFF // section 8: reserved for testing and private use

    // Audio formats (section 6).
    const val FORMAT_PCM_16K = 0x01
    const val FORMAT_OPUS_16K = 0x02 // length-prefixed packets, see OpusFrames.kt
    val KNOWN_FORMATS = setOf(FORMAT_PCM_16K, FORMAT_OPUS_16K)

    // Bytes in front of the audio in each data chunk: [offset u32].
    const val CHUNK_HEADER_BYTES = 4
}

// Phone → board, written to `control` (section 3).
sealed class Command(val opcode: Int) {
    data object GetInfo : Command(0x01)
    data class SetTime(val unixSeconds: Long) : Command(0x02)
    data object List : Command(0x03)
    data class Get(val id: Long, val offset: Long) : Command(0x04)
    data class Delete(val id: Long) : Command(0x05)

    fun encode(): ByteArray {
        val w = ByteWriter().u8(opcode)
        when (this) {
            GetInfo, List -> Unit
            is SetTime -> w.u32(unixSeconds)
            is Get -> w.u32(id).u32(offset)
            is Delete -> w.u32(id)
        }
        return w.toByteArray()
    }
}

// Status byte of every reply (section 4). Unknown codes (from newer firmware) become OTHER.
enum class Status(val code: Int) {
    OK(0x00),
    UNKNOWN_COMMAND(0x01),
    BAD_LENGTH(0x02),
    NOT_FOUND(0x03),
    BAD_OFFSET(0x04),
    BUSY(0x05),
    STORAGE_ERROR(0x06),
    OTHER(-1);

    companion object {
        fun of(code: Int): Status = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

// Anything the board notifies: a reply on `control` or an audio chunk on `data`.
sealed interface Incoming

// Board → phone, notified on `control` (section 4).
sealed interface Reply : Incoming {
    data class Info(val status: DeviceStatus) : Reply
    data object TimeSet : Reply
    data class Entry(val id: Long, val sizeBytes: Long, val recordedAt: Long, val format: Int) : Reply
    data class ListEnd(val count: Int) : Reply
    data class GetStart(val id: Long, val offset: Long, val sizeBytes: Long) : Reply
    data class GetEnd(val id: Long, val crc32: Long) : Reply
    data object Deleted : Reply

    // Any reply whose status isn't OK. `opcode` is the reply's opcode, e.g. 0x84 for a
    // failed GET. `code` keeps the raw byte for logs when `status` is OTHER.
    data class Error(val opcode: Int, val status: Status, val code: Int) : Reply

    // An opcode this app doesn't know (newer firmware). Ignored rather than treated as broken.
    data class Unknown(val opcode: Int) : Reply
}

private const val INFO = 0x81
private const val SET_TIME = 0x82
private const val ENTRY = 0x83
private const val LIST_END = 0x93
private const val GET_START = 0x84
private const val GET_END = 0x94
private const val DELETE = 0x85

// Bytes left over at the end are ignored, so a later protocol can add fields at the end.
// Throws BoardProtocolException if the packet is too short.
fun decodeReply(bytes: ByteArray): Reply {
    val r = ByteReader(bytes)
    val opcode = r.u8()
    val statusCode = r.u8()
    if (statusCode != Status.OK.code) return Reply.Error(opcode, Status.of(statusCode), statusCode)
    return when (opcode) {
        INFO -> Reply.Info(DeviceStatus.decode(r))
        SET_TIME -> Reply.TimeSet
        ENTRY -> Reply.Entry(id = r.u32(), sizeBytes = r.u32(), recordedAt = r.u32(), format = r.u8())
        LIST_END -> Reply.ListEnd(count = r.u16())
        GET_START -> Reply.GetStart(id = r.u32(), offset = r.u32(), sizeBytes = r.u32())
        GET_END -> Reply.GetEnd(id = r.u32(), crc32 = r.u32())
        DELETE -> Reply.Deleted
        else -> Reply.Unknown(opcode)
    }
}

// One notification on `data` (section 5): where in the file these bytes go, then the bytes.
class Chunk(val offset: Long, val audio: ByteArray) : Incoming

fun decodeChunk(bytes: ByteArray): Chunk {
    val r = ByteReader(bytes)
    return Chunk(offset = r.u32(), audio = r.rest())
}
