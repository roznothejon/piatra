package dev.piatra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogLineTest {
    @Test
    fun `reads a threadtime line`() {
        val line = parseLogcatLine("10-23 17:56:47.009 18533 18586 I PiatraBoard: GET_END 41: 0/67840 bytes")!!
        assertEquals("10-23 17:56:47.009", line.time)
        assertEquals(LogLevel.INFO, line.level)
        assertEquals("PiatraBoard", line.tag)
        assertEquals("GET_END 41: 0/67840 bytes", line.message)
        assertEquals(LogType.BOARD, line.type)
    }

    @Test
    fun `padded tags, colons in the message and unknown tags`() {
        val line = parseLogcatLine("10-23 17:56:45.865 18533 18553 W BluetoothGatt  : onConnectionUpdated() - a: b")!!
        assertEquals("BluetoothGatt", line.tag)
        assertEquals(LogLevel.WARNING, line.level)
        assertEquals("onConnectionUpdated() - a: b", line.message)
        assertEquals(LogType.SYSTEM, parseLogcatLine("10-23 17:56:45.865 1 2 E OpenGLRenderer: x")!!.type)
    }

    @Test
    fun `lines that aren't entries are skipped`() {
        assertNull(parseLogcatLine("--------- beginning of main"))
    }
}
