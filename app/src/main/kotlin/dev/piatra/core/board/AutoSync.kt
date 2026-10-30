package dev.piatra.core.board

// Automatic sync: should a sign of the board start a sync? The board advertises
// only when it has a reason (protocol section 8), and the phone hears every advert, about 5 a
// second, so most sightings must be ignored.
//
// pendingCount: from the advert's status, or null when unknown (no status in the advert, or
// Android's companion watch, which only says "appeared"). checkInWanted: the advert's CHECK_IN
// flag (a tap on the board).
fun shouldAutoSync(
    pendingCount: Int?,
    checkInWanted: Boolean,
    lastReadAtMillis: Long?,
    lastStartMillis: Long?,
    nowMillis: Long,
): Boolean {
    // Just started one: the adverts heard during and right after it are about the same recordings.
    if (lastStartMillis != null && nowMillis - lastStartMillis < MIN_GAP_MILLIS) return false
    if (pendingCount == null || pendingCount > 0 || checkInWanted) return true
    // Nothing to fetch, but a stale status is worth a short visit (the board's 30-min check-in):
    // the Device screen shows battery and storage from it.
    return lastReadAtMillis == null || nowMillis - lastReadAtMillis > STATUS_MAX_AGE_MILLIS
}

const val MIN_GAP_MILLIS = 15_000L
const val STATUS_MAX_AGE_MILLIS = 25 * 60_000L // a little under the board's 30-min check-in
