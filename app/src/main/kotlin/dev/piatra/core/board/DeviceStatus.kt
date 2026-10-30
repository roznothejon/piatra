package dev.piatra.core.board

// The board's health report (docs/esp32-protocol.md section 10): the INFO reply, and the
// short form in its advertisement (section 8).

import kotlinx.serialization.Serializable

// @Serializable: saved as JSON in the settings (BoardReading), so the last reading survives a restart.
@Serializable
data class DeviceStatus(
    val protocolVersion: Int,
    val flags: Long,
    val pendingCount: Int,
    val batteryMillivolts: Int, // 0 = can't measure
    val storageTotalKiB: Long, // 0 = unknown
    val storageFreeKiB: Long,
    val missedCount: Int,
    val uptimeSeconds: Long,
    val resetReason: ResetReason,
    val firmwareVersion: String,
) {
    // `flags` packs yes/no values into one number, one bit each. Bits we don't know are ignored.
    fun has(flag: DeviceFlag): Boolean = flags and (1L shl flag.bit) != 0L

    val batteryPercent: Int? get() = batteryPercent(batteryMillivolts)

    val storageFreePercent: Int?
        get() = if (storageTotalKiB == 0L) null else (storageFreeKiB * 100 / storageTotalKiB).toInt()

    companion object {
        fun decode(r: ByteReader) = DeviceStatus(
            protocolVersion = r.u8(),
            flags = r.u32(),
            pendingCount = r.u16(),
            batteryMillivolts = r.u16(),
            storageTotalKiB = r.u32(),
            storageFreeKiB = r.u32(),
            missedCount = r.u16(),
            uptimeSeconds = r.u32(),
            resetReason = ResetReason.of(r.u8()),
            firmwareVersion = r.rest().decodeToString(),
        )
    }
}

enum class DeviceFlag(val bit: Int) {
    CLOCK_SET(0),
    CHARGING(1),
    USB_POWER(2),
    BATTERY_LOW(3),
    STORAGE_LOW(4),
    STORAGE_FULL(5),
    SD_ERROR(6),
    MIC_ERROR(7),
    // A tap on the board asked the phone to connect; cleared by GET_INFO.
    CHECK_IN(8),
}

enum class ResetReason(val code: Int) {
    UNKNOWN(0),
    POWER_ON(1),
    BROWNOUT(2),
    WATCHDOG(3),
    CRASH(4),
    SOFTWARE(5),
    DEEP_SLEEP_WAKE(6);

    // Brownouts, hangs and crashes mean lost recordings or a firmware bug: worth a warning.
    val isProblem: Boolean get() = this == BROWNOUT || this == WATCHDOG || this == CRASH

    companion object {
        fun of(code: Int): ResetReason = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

// The 10-byte status in the scan response's manufacturer data (section 8), so the app can
// update the battery reading without connecting.
data class AdvertStatus(
    val protocolVersion: Int,
    val flags: Long,
    val pendingCount: Int,
    val batteryMillivolts: Int,
    val storageFreePercent: Int,
) {
    fun has(flag: DeviceFlag): Boolean = flags and (1L shl flag.bit) != 0L

    val batteryPercent: Int? get() = batteryPercent(batteryMillivolts)

    companion object {
        // `bytes` is the manufacturer data after the company ID (Android hands it over that way).
        fun decode(bytes: ByteArray): AdvertStatus {
            val r = ByteReader(bytes)
            return AdvertStatus(
                protocolVersion = r.u8(),
                flags = r.u32(),
                pendingCount = r.u16(),
                batteryMillivolts = r.u16(),
                storageFreePercent = r.u8(),
            )
        }

        // The status in an advert, or null if it's missing, cut short, or from another
        // protocol version. Version 0 is a board that hasn't filled it in yet (firmware before
        // the 4.4c-2 fix sent zeros for ~1 s after a restart: "0 mV, 0 % free").
        fun decodeOrNull(bytes: ByteArray?): AdvertStatus? =
            bytes?.let { runCatching { decode(it) }.getOrNull() }
                ?.takeIf { it.protocolVersion == BoardProtocol.VERSION }
    }
}

// A rough LiPo discharge curve: voltage → charge left. The voltage drops fast when full and
// when nearly empty, and slowly in between, so a straight line would be wrong. Between two
// points we draw a straight line. Tune the points once the real battery is measured.
private val LIPO_CURVE = listOf(
    3300 to 0,
    3500 to 10,
    3600 to 20,
    3700 to 50,
    3800 to 65,
    3900 to 75,
    4000 to 85,
    4100 to 93,
    4200 to 100,
)

// Below this the board can't be running on its battery (it browns out around 3.3 V), so a
// lower reading means nothing is connected: an unwired battery pin "floats" and reads random
// voltages (seen 1334–3042 mV), which made "battery low 0 %" come and go.
const val MIN_REAL_BATTERY_MV = 3200

// null = unknown (the board sends 0 until it has a voltage divider soldered on, or reads noise).
fun batteryPercent(millivolts: Int): Int? {
    if (millivolts < MIN_REAL_BATTERY_MV) return null
    if (millivolts <= LIPO_CURVE.first().first) return 0
    if (millivolts >= LIPO_CURVE.last().first) return 100
    val upper = LIPO_CURVE.indexOfFirst { it.first >= millivolts }
    val (v0, p0) = LIPO_CURVE[upper - 1]
    val (v1, p1) = LIPO_CURVE[upper]
    return p0 + (millivolts - v0) * (p1 - p0) / (v1 - v0)
}
