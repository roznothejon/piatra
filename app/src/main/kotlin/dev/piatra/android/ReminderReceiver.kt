package dev.piatra.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// Runs when a reminder's alarm goes off (show the notification), when its Snooze or Done button
// is tapped, or when it's swiped away. ReminderAlerts decides what each one does.
// A BroadcastReceiver is the third kind of Android component (after Activity and Application):
// no screen, started by the OS for one event, declared in the manifest. Piatra may not be
// running; the OS starts the process (PiatraApp, then Koin) and then calls onReceive.
// Ported and trimmed from upstream ReminderReceiver.
class ReminderReceiver : BroadcastReceiver(), KoinComponent {
    // KoinComponent + by inject(): how a class Android creates itself gets Koin objects.
    private val alerts: ReminderAlerts by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1)
        if (reminderId == -1L) return
        // onReceive must return quickly, and the database is slow-ish. goAsync() keeps the
        // receiver alive until finish(), so the work can run in a coroutine.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> alerts.ring(reminderId)
                    ACTION_DONE -> alerts.done(reminderId)
                    ACTION_SNOOZE -> alerts.snooze(reminderId)
                    ACTION_DISMISSED -> alerts.dismissed(reminderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reminder $reminderId: ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        private const val EXTRA_REMINDER_ID = "reminder_id"
        private const val ACTION_FIRE = "dev.piatra.reminders.FIRE"
        private const val ACTION_DONE = "dev.piatra.reminders.DONE"
        private const val ACTION_SNOOZE = "dev.piatra.reminders.SNOOZE"
        private const val ACTION_DISMISSED = "dev.piatra.reminders.DISMISSED"

        // The alarm's envelope. The request code (the reminder id) and action make it unique;
        // extras don't count when Android compares PendingIntents.
        // IMMUTABLE: whoever holds it can't change what's inside.
        fun firePendingIntent(context: Context, reminderId: Long): PendingIntent =
            pendingIntent(context, reminderId, ACTION_FIRE)

        fun donePendingIntent(context: Context, reminderId: Long): PendingIntent =
            pendingIntent(context, reminderId, ACTION_DONE)

        fun snoozePendingIntent(context: Context, reminderId: Long): PendingIntent =
            pendingIntent(context, reminderId, ACTION_SNOOZE)

        fun dismissedPendingIntent(context: Context, reminderId: Long): PendingIntent =
            pendingIntent(context, reminderId, ACTION_DISMISSED)

        private fun pendingIntent(context: Context, reminderId: Long, action: String): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
            return PendingIntent.getBroadcast(
                context, reminderId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
