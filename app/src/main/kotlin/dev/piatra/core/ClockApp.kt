package dev.piatra.core

// The phone's Clock app: alarms and timers are handed to it rather than built into Piatra
// (CLAUDE.md). Android: android/AndroidClockApp.kt. iOS has no API for this (a known gap).
interface ClockApp {
    fun setAlarm(hour: Int, minute: Int, label: String?): ClockResult

    fun setTimer(seconds: Int): ClockResult
}

enum class ClockResult {
    // Done directly.
    SET,

    // Android didn't let us open the Clock app from the background, so a notification asks
    // the user to tap it, which does it.
    ASKED_USER,
}
