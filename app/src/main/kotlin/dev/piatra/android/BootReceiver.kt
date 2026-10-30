package dev.piatra.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// Alarms and notifications don't survive a restart, so after the phone boots (or Piatra is
// updated) set them up again from the database (ReminderAlerts.restore). PiatraApp does the
// same at every start; doing it twice is harmless.
// Ported and trimmed from upstream ReminderBootReceiver.
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val alerts: ReminderAlerts by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                alerts.restore()
            } catch (e: Exception) {
                Log.e("BootReceiver", "Couldn't reschedule reminders", e)
            } finally {
                pending.finish()
            }
        }
    }
}
