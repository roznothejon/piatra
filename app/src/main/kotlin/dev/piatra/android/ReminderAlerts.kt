package dev.piatra.android

import android.content.Context
import dev.piatra.data.ItemRepository
import dev.piatra.data.Settings
import kotlinx.coroutines.flow.first

// What happens to a reminder once its time comes (persistent reminders): it rings
// once, then stays in the notification shade, silent, until it's ticked off or snoozed. Nothing
// rings again by itself. ReminderReceiver (alarm and buttons), BootReceiver and PiatraApp call this.
class ReminderAlerts(
    private val context: Context,
    private val items: ItemRepository,
    private val settings: Settings,
) {
    // Its alarm went off: ring, unless it was deleted, ticked off, or has rung already.
    suspend fun ring(reminderId: Long) {
        val reminder = items.get(reminderId) ?: return
        if (reminder.done || !items.markRang(reminderId)) return
        ReminderNotifications.show(context, reminder, keep = settings.isKeepRemindersOn(), silent = false)
    }

    // The user swiped it away: put it back, silently, while it's still due and not done.
    suspend fun dismissed(reminderId: Long) {
        if (!settings.isKeepRemindersOn()) return
        val reminder = items.get(reminderId) ?: return
        val due = reminder.dueAt ?: return
        if (!reminder.done && reminder.rang && due <= System.currentTimeMillis()) {
            ReminderNotifications.show(context, reminder, keep = true, silent = true)
        }
    }

    // Ticking off and snoozing both take the notification down (ItemRepository → ReminderScheduler).
    suspend fun done(reminderId: Long) = items.setDone(reminderId, done = true)

    suspend fun snooze(reminderId: Long) = items.snooze(reminderId, settings.snoozeMinutes.first())

    // After the phone restarts (or Piatra is updated, force-stopped or killed): alarms and
    // notifications are gone. Set the alarms again; show again, silently, every reminder that
    // already rang and isn't ticked off; ring the ones that came due while nothing was running.
    // Running this twice is harmless: showing again replaces, and ring() rings only once.
    suspend fun restore() {
        items.rescheduleUpcoming()
        val keep = settings.isKeepRemindersOn()
        for (reminder in items.dueReminders()) {
            if (!reminder.rang) ring(reminder.id)
            else if (keep) ReminderNotifications.show(context, reminder, keep = true, silent = true)
        }
    }
}
