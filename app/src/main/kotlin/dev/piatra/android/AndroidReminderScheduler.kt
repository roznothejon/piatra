package dev.piatra.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.piatra.MainActivity
import dev.piatra.core.ReminderScheduler

// Reminder alarms with AlarmManager. Piatra isn't running at 5pm, so we hand the OS a
// PendingIntent: a sealed envelope saying "start ReminderReceiver with reminder 42", which the
// OS opens at the set time.
// setAlarmClock is the most reliable exact alarm: it fires even in deep battery saving (Doze)
// and shows the alarm icon in the status bar. It needs USE_EXACT_ALARM (see the manifest).
// Ported from upstream AndroidBuiltInReminderIntegration.scheduleAlarm / cancelAlarm.
class AndroidReminderScheduler(private val context: Context) : ReminderScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(reminderId: Long, atMillis: Long) {
        // What opens when you tap the alarm icon's details: the app.
        val showApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showApp), ReminderReceiver.firePendingIntent(context, reminderId))
        ReminderNotifications.dismiss(context, reminderId)
    }

    override fun cancel(reminderId: Long) {
        // An equal PendingIntent (same receiver, action and request code) identifies the alarm.
        alarmManager.cancel(ReminderReceiver.firePendingIntent(context, reminderId))
        ReminderNotifications.dismiss(context, reminderId)
    }
}
