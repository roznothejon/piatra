package dev.piatra.core.board

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

class BoardProtocolTest {
    @Test
    fun `commands encode as in the spec`() {
        assertContentEquals(bytes(0x01), Command.GetInfo.encode())
        assertContentEquals(bytes(0x03), Command.List.encode())
        // 1,761,177,600 = 0x68F97000
        assertContentEquals(bytes(0x02, 0x00, 0x70, 0xF9, 0x68), Command.SetTime(1_761_177_600).encode())
        assertContentEquals(bytes(0x04, 7, 0, 0, 0, 0x00, 0x7D, 0, 0), Command.Get(id = 7, offset = 32000).encode())
        assertContentEquals(bytes(0x05, 0x2A, 0x01, 0, 0), Command.Delete(id = 298).encode())
    }

    @Test
    fun `simple replies decode`() {
        assertEquals(Reply.TimeSet, decodeReply(bytes(0x82, 0x00)))
        assertEquals(Reply.Deleted, decodeReply(bytes(0x85, 0x00)))
        assertEquals(Reply.ListEnd(count = 3), decodeReply(bytes(0x93, 0x00, 3, 0)))
    }

    @Test
    fun `list entry decodes`() {
        val packet = ByteWriter().u8(0x83).u8(0).u32(12).u32(64_000).u32(1_761_177_600).u8(0x01).toByteArray()
        assertEquals(
            Reply.Entry(id = 12, sizeBytes = 64_000, recordedAt = 1_761_177_600, format = BoardProtocol.FORMAT_PCM_16K),
            decodeReply(packet),
        )
    }

    @Test
    fun `transfer start and end decode`() {
        assertEquals(
            Reply.GetStart(id = 7, offset = 0, sizeBytes = 32000),
            decodeReply(bytes(0x84, 0x00, 7, 0, 0, 0, 0, 0, 0, 0, 0x00, 0x7D, 0, 0)),
        )
        assertEquals(
            Reply.GetEnd(id = 7, crc32 = 0xCBF43926L),
            decodeReply(bytes(0x94, 0x00, 7, 0, 0, 0, 0x26, 0x39, 0xF4, 0xCB)),
        )
    }

    @Test
    fun `an error status becomes Error whatever the opcode, with no payload`() {
        assertEquals(Reply.Error(0x84, Status.NOT_FOUND, 0x03), decodeReply(bytes(0x84, 0x03)))
        assertEquals(Reply.Error(0x83, Status.STORAGE_ERROR, 0x06), decodeReply(bytes(0x83, 0x06)))
        assertEquals(Reply.Error(0x81, Status.UNKNOWN_COMMAND, 0x01), decodeReply(bytes(0x81, 0x01)))
        assertEquals(Reply.Error(0x85, Status.OTHER, 0x42), decodeReply(bytes(0x85, 0x42)))
    }

    @Test
    fun `unknown opcodes and extra bytes are tolerated`() {
        assertEquals(Reply.Unknown(0x99), decodeReply(bytes(0x99, 0x00, 1, 2, 3)))
        assertEquals(Reply.ListEnd(count = 1), decodeReply(bytes(0x93, 0x00, 1, 0, 0xEE, 0xEE)))
    }

    @Test
    fun `short packets throw`() {
        assertFailsWith<BoardProtocolException> { decodeReply(bytes()) }
        assertFailsWith<BoardProtocolException> { decodeReply(bytes(0x93)) }
        assertFailsWith<BoardProtocolException> { decodeReply(bytes(0x84, 0x00, 7, 0, 0, 0)) }
        assertFailsWith<BoardProtocolException> { decodeChunk(bytes(1, 2)) }
    }

    @Test
    fun `chunks carry their offset then the audio`() {
        val chunk = decodeChunk(bytes(0x00, 0x02, 0, 0, 0xAA, 0xBB, 0xCC))
        assertEquals(512L, chunk.offset)
        assertContentEquals(bytes(0xAA, 0xBB, 0xCC), chunk.audio)
    }

    @Test
    fun `info decodes every field`() {
        val packet = ByteWriter()
            .u8(0x81).u8(0x00)
            .u8(1) // protocolVersion
            .u32((1L shl 0) or (1L shl 2) or (1L shl 4) or (1L shl 20)) // CLOCK_SET, USB_POWER, STORAGE_LOW + an unknown bit
            .u16(4) // pendingCount
            .u16(3850) // battery mV
            .u32(31_000_000) // storage total KiB
            .u32(3_100_000) // storage free KiB
            .u16(2) // missedCount
            .u32(86_400) // uptime
            .u8(2) // brownout
            .bytes("0.1.0".encodeToByteArray())
            .toByteArray()
        assertEquals(26 + 5, packet.size) // the spec's "26 bytes plus the version text"

        val info = (decodeReply(packet) as Reply.Info).status
        assertEquals(1, info.protocolVersion)
        assertTrue(info.has(DeviceFlag.CLOCK_SET))
        assertTrue(info.has(DeviceFlag.USB_POWER))
        assertTrue(info.has(DeviceFlag.STORAGE_LOW))
        assertEquals(false, info.has(DeviceFlag.CHARGING))
        assertEquals(4, info.pendingCount)
        assertEquals(3850, info.batteryMillivolts)
        assertEquals(10, info.storageFreePercent)
        assertEquals(2, info.missedCount)
        assertEquals(86_400L, info.uptimeSeconds)
        assertEquals(ResetReason.BROWNOUT, info.resetReason)
        assertTrue(info.resetReason.isProblem)
        assertEquals("0.1.0", info.firmwareVersion)
    }

    @Test
    fun `info with no card, no battery reading and an unknown reset reason`() {
        val packet = ByteWriter().u8(0x81).u8(0).u8(1).u32(0).u16(0).u16(0).u32(0).u32(0).u16(0).u32(5).u8(77)
            .toByteArray()
        val info = (decodeReply(packet) as Reply.Info).status
        assertNull(info.storageFreePercent)
        assertNull(info.batteryPercent)
        assertEquals(ResetReason.UNKNOWN, info.resetReason)
        assertEquals("", info.firmwareVersion)
    }

    @Test
    fun `advertisement status decodes`() {
        val data = ByteWriter().u8(1).u32(1L shl 5).u16(3).u16(4200).u8(0).toByteArray()
        assertEquals(10, data.size)
        val status = AdvertStatus.decode(data)
        assertEquals(3, status.pendingCount)
        assertTrue(status.has(DeviceFlag.STORAGE_FULL))
        assertEquals(100, status.batteryPercent)
        assertEquals(0, status.storageFreePercent)
    }

    @Test
    fun `an unfilled or unknown advertisement status is ignored`() {
        val good = ByteWriter().u8(1).u32(0).u16(0).u16(3800).u8(97).toByteArray()
        assertEquals(97, AdvertStatus.decodeOrNull(good)?.storageFreePercent)
        // What the board sent for ~1 s after a restart: all zeros, version 0.
        assertNull(AdvertStatus.decodeOrNull(ByteArray(10)))
        assertNull(AdvertStatus.decodeOrNull(ByteWriter().u8(2).u32(0).u16(0).u16(3800).u8(97).toByteArray()))
        assertNull(AdvertStatus.decodeOrNull(good.copyOf(5))) // cut short
        assertNull(AdvertStatus.decodeOrNull(null)) // no scan response
    }

    @Test
    fun `battery curve`() {
        assertNull(batteryPercent(0))
        assertNull(batteryPercent(3000)) // a floating pin, not a battery
        assertNull(batteryPercent(1334))
        assertEquals(0, batteryPercent(3200))
        assertEquals(0, batteryPercent(3300))
        assertEquals(50, batteryPercent(3700))
        assertEquals(15, batteryPercent(3550)) // halfway between 3500 (10%) and 3600 (20%)
        assertEquals(100, batteryPercent(4200))
        assertEquals(100, batteryPercent(4350))
    }
}
