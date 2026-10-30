package dev.piatra.core.board

import kotlinx.serialization.Serializable

// A connection to the board: commands go out, replies and audio chunks come back in the order
// the board sent them. The Bluetooth side (android/KableBoardLink.kt) implements it; tests use
// a fake. Plain Kotlin, so the steps below (and BoardSync.kt) don't depend on Android.
interface BoardLink {
    suspend fun send(command: Command)

    // The next reply or chunk. Throws a timeout if the board sends nothing for a few seconds.
    suspend fun receive(): Incoming
}

// The next reply, skipping replies from newer firmware this app doesn't know, and audio chunks
// left over from a GET (they can overtake GET_END: see KableBoardLink).
suspend fun BoardLink.nextReply(): Reply {
    while (true) {
        val incoming = receive()
        if (incoming is Reply && incoming !is Reply.Unknown) return incoming
    }
}

// Send one command and wait for its reply.
suspend fun BoardLink.request(command: Command): Reply {
    send(command)
    return nextReply()
}

// The board speaks a protocol version this app doesn't know: "update the app or the firmware".
class UnsupportedBoardException(val version: Int) :
    Exception("The board speaks protocol v$version; this app knows v${BoardProtocol.VERSION}")

// The first two steps of every sync (protocol section 9, steps 3–4): read the board's status,
// then set its clock so new recordings get the right time. Returns the status.
suspend fun BoardLink.checkIn(nowSeconds: Long): DeviceStatus {
    val status = when (val reply = request(Command.GetInfo)) {
        is Reply.Info -> reply.status
        else -> throw BoardProtocolException("GET_INFO answered with $reply")
    }
    if (status.protocolVersion != BoardProtocol.VERSION) throw UnsupportedBoardException(status.protocolVersion)
    val timeReply = request(Command.SetTime(nowSeconds))
    if (timeReply != Reply.TimeSet) throw BoardProtocolException("SET_TIME answered with $timeReply")
    return status
}

// The board's status and when the phone read it, kept so the Device screen can show
// "last seen" without connecting. readAtMillis: the last INFO (automatic sync uses it to
// tell a stale status). seenAtMillis: the last advert that changed something, or
// null; its battery, flags and free space are copied into status.
@Serializable
data class BoardReading(val status: DeviceStatus, val readAtMillis: Long, val seenAtMillis: Long? = null) {
    val lastSeenMillis: Long get() = maxOf(readAtMillis, seenAtMillis ?: 0)

    // The reading with an advert's fresher values, or null when the advert brings nothing new.
    fun withAdvert(advert: AdvertStatus, nowMillis: Long): BoardReading? {
        val total = status.storageTotalKiB
        // The advert only has a percentage: keep the exact KiB unless the percentage moved.
        val freeKiB = if (total == 0L || status.storageFreePercent == advert.storageFreePercent) status.storageFreeKiB
            else total * advert.storageFreePercent / 100
        val updated = status.copy(
            // CHECK_IN only matters in the advert itself.
            flags = advert.flags and (1L shl DeviceFlag.CHECK_IN.bit).inv(),
            pendingCount = advert.pendingCount,
            batteryMillivolts = advert.batteryMillivolts,
            storageFreeKiB = freeKiB,
        )
        return if (updated == status) null else copy(status = updated, seenAtMillis = nowMillis)
    }
}
