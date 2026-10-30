package dev.piatra.core

// Makes the phone wake us at a set time. Android does it with AlarmManager
// (android/AndroidReminderScheduler.kt); an iOS port would use local notifications.
// Both calls also take down the reminder's notification if it's showing: it rang for a time
// that no longer holds (moved, snoozed), or the reminder is done or deleted.
interface ReminderScheduler {
    // Schedules (or moves) the alarm for this reminder. A time in the past fires right away.
    fun schedule(reminderId: Long, atMillis: Long)

    fun cancel(reminderId: Long)
}
