package dev.piatra.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.piatra.MainActivity
import dev.piatra.data.ItemEntity
import dev.piatra.R

// The notification a reminder shows when it goes off: "Reminder: Call mom", with Snooze and Done.
// Ported and trimmed from upstream ReminderReceiver.makeNotification.
object ReminderNotifications {
    private const val CHANNEL_ID = "reminders"

    // A channel is a category of notifications the user can tune in system Settings (sound,
    // importance). HIGH: pops up on screen. Creating an existing channel again does nothing,
    // so this is safe to call at every app start (PiatraApp).
    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // Without the notification permission this silently shows nothing (the screen warns about it).
    // keep: "Keep reminders until done" (persistent reminders): it stays until ticked off.
    // silent: shown again (swiped away, or after a restart) without a sound or a pop-up.
    fun show(context: Context, reminder: ItemEntity, keep: Boolean, silent: Boolean) {
        val snooze = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ph_alarm),
            "Snooze",
            ReminderReceiver.snoozePendingIntent(context, reminder.id),
        ).build()
        val done = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.checkbox_on_background),
            "Done",
            ReminderReceiver.donePendingIntent(context, reminder.id),
        ).build()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ph_bell)
            .setContentTitle("Reminder")
            .setContentText(reminder.text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openList(context, reminder))
            .addAction(snooze)
            .addAction(done)
        if (keep) {
            // Ongoing: no swipe before Android 14; from 14 on it can be swiped, and the delete
            // intent (sent when the user dismisses it) puts it straight back. Tapping it leaves it.
            builder.setOngoing(true)
                .setDeleteIntent(ReminderReceiver.dismissedPendingIntent(context, reminder.id))
        } else {
            builder.setAutoCancel(true) // tapping it dismisses it
        }
        if (silent) {
            // Android has no "silent" switch on Notification.Builder (NotificationCompat's
            // setSilent does this same trick): in a group whose summary does the alerting, a
            // notification never alerts itself, and there is no summary.
            builder.setGroup("silent").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        }
        context.getSystemService(NotificationManager::class.java).notify(notificationId(reminder.id), builder.build())
    }

    fun dismiss(context: Context, reminderId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(reminderId))
    }

    // A tap opens the reminder's list. SINGLE_TOP + CLEAR_TOP: reuse Piatra's screen if it's open
    // (MainActivity.onNewIntent) instead of stacking a second copy on top. The request code (the
    // reminder id) keeps each reminder's envelope apart, since extras don't count when comparing.
    private fun openList(context: Context, reminder: ItemEntity): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        reminder.listId?.let { intent.putExtra(MainActivity.EXTRA_LIST_ID, it) }
        return PendingIntent.getActivity(
            context, reminder.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // One notification per reminder: posting with the same id replaces it.
    private fun notificationId(reminderId: Long) = reminderId.toInt()
}
