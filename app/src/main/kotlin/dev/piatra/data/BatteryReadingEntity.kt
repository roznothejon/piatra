package dev.piatra.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// One row of the "battery_readings" table (added in v9): the board's battery at
// one wake-up. Rules for which readings are kept: core/board/BatteryLog.kt.
@Entity(tableName = "battery_readings", indices = [Index("readAtMillis")])
data class BatteryReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readAtMillis: Long,
    val millivolts: Int,
    // Where it came from: "advert" (heard without connecting, e.g. a check-in) or "info" (a sync
    // or Check now, read while connected, so the radio was busier).
    val source: String,
)
