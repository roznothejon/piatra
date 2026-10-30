package dev.piatra.core.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardWarningsTest {
    private fun flags(vararg f: DeviceFlag) = f.fold(0L) { acc, flag -> acc or (1L shl flag.bit) }
    private fun report(battery: Int? = 80, free: Int? = 50, flags: Long = 0, missed: Int = 0, reset: ResetReason? = null, bootedAt: Long? = null) =
        HealthReport(flags, battery, free, missed, reset, bootedAt)

    @Test
    fun `a healthy board shows nothing`() {
        val check = checkWarnings(WarningMemory(), report())
        assertTrue(check.show.isEmpty())
        assertTrue(check.clear.isEmpty())
    }

    @Test
    fun `a missing SD card warns unless the user chose to ignore it`() {
        val noCard = report(flags = flags(DeviceFlag.SD_ERROR))
        val warned = checkWarnings(WarningMemory(), noCard)
        assertEquals(listOf(BoardWarning.SD_ERROR), warned.show.map { it.kind })
        assertTrue(checkWarnings(WarningMemory(), noCard, ignoreSdError = true).show.isEmpty())
        // Turned on while the warning shows: the next reading clears it.
        assertEquals(setOf(BoardWarning.SD_ERROR), checkWarnings(warned.memory, noCard, ignoreSdError = true).clear)
    }

    @Test
    fun `a low battery is shown once, then cleared when charged`() {
        val first = checkWarnings(WarningMemory(), report(battery = 19))
        assertEquals(listOf(BoardWarning.BATTERY_LOW), first.show.map { it.kind })
        // The next advert, a little lower: already shown.
        val second = checkWarnings(first.memory, report(battery = 17))
        assertTrue(second.show.isEmpty())
        val charged = checkWarnings(second.memory, report(battery = 60))
        assertEquals(setOf(BoardWarning.BATTERY_LOW), charged.clear)
        // Low again later: shown again.
        assertEquals(1, checkWarnings(charged.memory, report(battery = 18)).show.size)
    }

    @Test
    fun `20 percent and an unmeasured battery aren't low`() {
        assertTrue(checkWarnings(WarningMemory(), report(battery = 20)).show.isEmpty())
        assertTrue(checkWarnings(WarningMemory(), report(battery = null)).show.isEmpty())
    }

    @Test
    fun `a floating battery pin isn't a low battery`() {
        // The unwired pin's readings jump around; none of them should warn.
        var memory = WarningMemory()
        for (mv in listOf(0, 1712, 3042, 2390, 0, 1390)) {
            val report = AdvertStatus(1, 0, 0, mv, 50).toHealthReport()
            val check = checkWarnings(memory, report)
            assertTrue(check.show.isEmpty(), "warned at $mv mV")
            memory = check.memory
        }
    }

    @Test
    fun `the board's own flag counts, but not while charging`() {
        assertEquals(1, checkWarnings(WarningMemory(), report(flags = flags(DeviceFlag.BATTERY_LOW))).show.size)
        assertTrue(checkWarnings(WarningMemory(), report(battery = 10, flags = flags(DeviceFlag.USB_POWER))).show.isEmpty())
        assertTrue(checkWarnings(WarningMemory(), report(battery = 10, flags = flags(DeviceFlag.CHARGING))).show.isEmpty())
    }

    @Test
    fun `storage low then full updates the one notification`() {
        val low = checkWarnings(WarningMemory(), report(free = 14))
        assertEquals("Board storage low", low.show.single().title)
        assertTrue(checkWarnings(low.memory, report(free = 12)).show.isEmpty())
        val full = checkWarnings(low.memory, report(free = 2, flags = flags(DeviceFlag.STORAGE_FULL)))
        assertEquals("Board storage full", full.show.single().title)
        assertTrue(checkWarnings(full.memory, report(free = 1, flags = flags(DeviceFlag.STORAGE_FULL))).show.isEmpty())
        assertTrue(checkWarnings(WarningMemory(), report(free = 15)).show.isEmpty())
    }

    @Test
    fun `missed recordings are shown every time they're reported`() {
        val check = checkWarnings(WarningMemory(), report(missed = 3))
        assertEquals(BoardWarning.MISSED_RECORDINGS, check.show.single().kind)
        assertTrue("3 button presses" in check.show.single().text)
        // Events aren't remembered as active states.
        assertTrue(check.memory.active.isEmpty())
        assertEquals(1, checkWarnings(check.memory, report(missed = 1)).show.size)
    }

    @Test
    fun `a crash is shown once per boot`() {
        val boot = 1_000_000L
        val first = checkWarnings(WarningMemory(), report(reset = ResetReason.CRASH, bootedAt = boot))
        assertEquals(BoardWarning.PROBLEM_RESET, first.show.single().kind)
        // The same boot read again a bit later (the estimate wobbles by a second or two).
        assertTrue(checkWarnings(first.memory, report(reset = ResetReason.CRASH, bootedAt = boot + 2_000)).show.isEmpty())
        // Adverts don't carry the reset reason.
        assertTrue(checkWarnings(first.memory, report()).show.isEmpty())
        // It crashed again an hour later.
        assertEquals(1, checkWarnings(first.memory, report(reset = ResetReason.CRASH, bootedAt = boot + 3_600_000)).show.size)
    }

    @Test
    fun `a normal power-up isn't a problem`() {
        assertTrue(checkWarnings(WarningMemory(), report(reset = ResetReason.POWER_ON, bootedAt = 5_000)).show.isEmpty())
    }

    @Test
    fun `an advert updates the saved reading`() {
        val status = DeviceStatus(1, 0, 0, 3900, 1024 * 100, 1024 * 50, 0, 60, ResetReason.POWER_ON, "0.1")
        val reading = BoardReading(status, readAtMillis = 1_000)
        // Same values: nothing to save.
        assertNull(reading.withAdvert(AdvertStatus(1, 0, 0, 3900, 50), nowMillis = 2_000))
        // A tap's CHECK_IN alone isn't worth saving.
        assertNull(reading.withAdvert(AdvertStatus(1, flags(DeviceFlag.CHECK_IN), 0, 3900, 50), nowMillis = 2_000))
        val updated = reading.withAdvert(AdvertStatus(1, 0, 2, 3700, 40), nowMillis = 2_000)!!
        assertEquals(3700, updated.status.batteryMillivolts)
        assertEquals(2, updated.status.pendingCount)
        assertEquals(40, updated.status.storageFreePercent)
        assertEquals(1_000, updated.readAtMillis)
        assertEquals(2_000, updated.lastSeenMillis)
    }
}
