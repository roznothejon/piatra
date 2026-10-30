package dev.piatra.core.board

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncTest {
    private val now = 1_000_000_000L
    private val fresh = now - 60_000 // read a minute ago

    @Test
    fun `recordings waiting start a sync`() {
        assertTrue(shouldAutoSync(pendingCount = 2, checkInWanted = false, lastReadAtMillis = fresh, lastStartMillis = null, nowMillis = now))
        // Unknown counts as "maybe".
        assertTrue(shouldAutoSync(pendingCount = null, checkInWanted = false, lastReadAtMillis = fresh, lastStartMillis = null, nowMillis = now))
    }

    @Test
    fun `adverts right after a start are ignored`() {
        assertFalse(shouldAutoSync(pendingCount = 1, checkInWanted = false, lastReadAtMillis = fresh, lastStartMillis = now - 5_000, nowMillis = now))
        assertTrue(shouldAutoSync(pendingCount = 1, checkInWanted = false, lastReadAtMillis = fresh, lastStartMillis = now - 20_000, nowMillis = now))
    }

    @Test
    fun `a tap asks for a visit even with nothing waiting`() {
        assertTrue(shouldAutoSync(pendingCount = 0, checkInWanted = true, lastReadAtMillis = fresh, lastStartMillis = null, nowMillis = now))
        // But not twice in a row: the flag may still be set in the adverts heard meanwhile.
        assertFalse(shouldAutoSync(pendingCount = 0, checkInWanted = true, lastReadAtMillis = fresh, lastStartMillis = now - 5_000, nowMillis = now))
    }

    @Test
    fun `nothing waiting only syncs when the status is stale`() {
        assertFalse(shouldAutoSync(pendingCount = 0, checkInWanted = false, lastReadAtMillis = fresh, lastStartMillis = null, nowMillis = now))
        assertTrue(shouldAutoSync(pendingCount = 0, checkInWanted = false, lastReadAtMillis = now - 26 * 60_000, lastStartMillis = null, nowMillis = now))
        assertTrue(shouldAutoSync(pendingCount = 0, checkInWanted = false, lastReadAtMillis = null, lastStartMillis = null, nowMillis = now))
    }
}
