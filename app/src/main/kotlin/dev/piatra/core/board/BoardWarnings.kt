package dev.piatra.core.board

// Warning notifications about the board. The board only reports how it is, on
// every advert and every INFO; the app decides when that's worth a notification.
//
// Two kinds of warning:
// - states (battery low, storage low): true for hours, and reported about 5 times a second
//   while the board advertises. Shown once when they start, removed when they end, and shown
//   again only if they come back.
// - events (missed recordings, a crash): each new one is shown once.

import kotlinx.serialization.Serializable
import kotlin.math.abs

enum class BoardWarning {
    // States
    BATTERY_LOW,
    STORAGE, // low or full: one notification, the text says which
    SD_ERROR,
    MIC_ERROR,
    // Events
    MISSED_RECORDINGS,
    PROBLEM_RESET,
}

// The parts of a report that matter for warnings. An advert carries less than INFO, so the
// INFO-only parts are null there.
data class HealthReport(
    val flags: Long,
    val batteryPercent: Int?, // null = not measured
    val storageFreePercent: Int?, // null = no storage
    val missedCount: Int = 0,
    val resetReason: ResetReason? = null,
    // When the board last powered up or reset, worked out as "read at - uptime".
    val bootedAtMillis: Long? = null,
) {
    fun has(flag: DeviceFlag): Boolean = flags and (1L shl flag.bit) != 0L
}

fun DeviceStatus.toHealthReport(readAtMillis: Long) = HealthReport(
    flags = flags,
    batteryPercent = batteryPercent,
    storageFreePercent = storageFreePercent,
    missedCount = missedCount,
    resetReason = resetReason,
    bootedAtMillis = readAtMillis - uptimeSeconds * 1000,
)

fun AdvertStatus.toHealthReport() = HealthReport(
    flags = flags,
    batteryPercent = batteryPercent,
    storageFreePercent = storageFreePercent,
)

// What the app remembers between reports, saved in the settings so a restart doesn't repeat
// warnings. active: the states shown right now, with their titles. warnedBootAtMillis: the boot whose crash we
// already warned about.
@Serializable
data class WarningMemory(
    val active: Map<BoardWarning, String> = emptyMap(),
    val warnedBootAtMillis: Long? = null,
)

data class WarningNotice(val kind: BoardWarning, val title: String, val text: String)

// show: notifications to post (a new one replaces an old one of the same kind).
// clear: states that ended, whose notifications should go.
data class WarningCheck(val memory: WarningMemory, val show: List<WarningNotice>, val clear: Set<BoardWarning>)

// Warn when the battery is under 20 % or storage under 15 % free.
const val BATTERY_LOW_PERCENT = 20
const val STORAGE_LOW_PERCENT = 15

// The boot time is only known to the second and the phone reads it a moment late, so two
// estimates of the same boot can differ a little.
private const val SAME_BOOT_MILLIS = 60_000L

// ignoreSdError: the user runs the board without a card on purpose (Device screen switch).
fun checkWarnings(memory: WarningMemory, report: HealthReport, ignoreSdError: Boolean = false): WarningCheck {
    val states = currentStates(report, ignoreSdError)
    // Only the states that just started, or whose text changed (storage low → full). The
    // ones already shown stay as they are.
    val show = states.values.filter { memory.active[it.kind] != it.title }.toMutableList()
    val clear = memory.active.keys - states.keys

    if (report.missedCount > 0) {
        val n = report.missedCount
        show += WarningNotice(
            BoardWarning.MISSED_RECORDINGS,
            "Recordings missed",
            "$n button ${if (n == 1) "press" else "presses"} couldn't be recorded (storage full or battery too low).",
        )
    }

    var warnedBoot = memory.warnedBootAtMillis
    val reason = report.resetReason
    val bootedAt = report.bootedAtMillis
    if (reason != null && reason.isProblem && bootedAt != null &&
        (warnedBoot == null || abs(bootedAt - warnedBoot) > SAME_BOOT_MILLIS)
    ) {
        warnedBoot = bootedAt
        show += WarningNotice(BoardWarning.PROBLEM_RESET, "The board restarted", resetText(reason))
    }

    return WarningCheck(WarningMemory(states.mapValues { it.value.title }, warnedBoot), show, clear)
}

// The states true in this report, with their notification text.
private fun currentStates(r: HealthReport, ignoreSdError: Boolean): Map<BoardWarning, WarningNotice> = buildMap {
    // While charging or on USB, a low battery is already being dealt with.
    val charging = r.has(DeviceFlag.CHARGING) || r.has(DeviceFlag.USB_POWER)
    val battery = r.batteryPercent
    if (!charging && (r.has(DeviceFlag.BATTERY_LOW) || (battery != null && battery < BATTERY_LOW_PERCENT))) {
        put(
            BoardWarning.BATTERY_LOW,
            WarningNotice(BoardWarning.BATTERY_LOW, "Board battery low", battery?.let { "$it % left. Time to charge it." } ?: "Time to charge it."),
        )
    }
    val free = r.storageFreePercent
    if (r.has(DeviceFlag.STORAGE_FULL)) {
        put(
            BoardWarning.STORAGE,
            WarningNotice(BoardWarning.STORAGE, "Board storage full", "New recordings are refused. Sync it to make room."),
        )
    } else if (r.has(DeviceFlag.STORAGE_LOW) || (free != null && free < STORAGE_LOW_PERCENT)) {
        put(
            BoardWarning.STORAGE,
            WarningNotice(BoardWarning.STORAGE, "Board storage low", (free?.let { "$it % free. " } ?: "") + "Sync it to make room."),
        )
    }
    if (r.has(DeviceFlag.SD_ERROR) && !ignoreSdError) {
        put(BoardWarning.SD_ERROR, WarningNotice(BoardWarning.SD_ERROR, "Board SD card error", "The SD card is missing or can't be read. Recording to the board's own memory (about 3 minutes)."))
    }
    if (r.has(DeviceFlag.MIC_ERROR)) {
        put(BoardWarning.MIC_ERROR, WarningNotice(BoardWarning.MIC_ERROR, "Board microphone error", "The microphone didn't start."))
    }
}

private fun resetText(reason: ResetReason): String = when (reason) {
    ResetReason.BROWNOUT -> "Its battery sagged too low (brownout). A recording may have been lost."
    ResetReason.WATCHDOG -> "The firmware hung and was restarted. A recording may have been lost."
    else -> "The firmware crashed. A recording may have been lost."
}
