package dev.piatra.core.board

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// A board that answers from a list, and remembers what it was sent.
private class FakeLink(vararg replies: Reply) : BoardLink {
    private val replies = ArrayDeque(replies.toList())
    val sent = mutableListOf<Command>()

    override suspend fun send(command: Command) {
        sent += command
    }

    override suspend fun receive(): Incoming = replies.removeFirst()
}

private fun status(version: Int = BoardProtocol.VERSION) = DeviceStatus(
    protocolVersion = version, flags = 0, pendingCount = 2, batteryMillivolts = 3900,
    storageTotalKiB = 5632, storageFreeKiB = 5000, missedCount = 0, uptimeSeconds = 60,
    resetReason = ResetReason.POWER_ON, firmwareVersion = "0.1",
)

class CheckInTest {
    @Test
    fun `reads the status, then sets the clock`() = runTest {
        val link = FakeLink(Reply.Info(status()), Reply.TimeSet)
        assertEquals(status(), link.checkIn(nowSeconds = 1_761_177_600))
        assertEquals(listOf(Command.GetInfo, Command.SetTime(1_761_177_600)), link.sent)
    }

    @Test
    fun `an unknown protocol version stops before setting the clock`() = runTest {
        val link = FakeLink(Reply.Info(status(version = 2)))
        val e = assertFailsWith<UnsupportedBoardException> { link.checkIn(nowSeconds = 0) }
        assertEquals(2, e.version)
        assertEquals(listOf<Command>(Command.GetInfo), link.sent)
    }

    @Test
    fun `an error reply fails the check-in`() = runTest {
        val link = FakeLink(Reply.Info(status()), Reply.Error(opcode = 0x82, status = Status.BAD_LENGTH, code = 0x02))
        assertFailsWith<BoardProtocolException> { link.checkIn(nowSeconds = 0) }
    }
}
