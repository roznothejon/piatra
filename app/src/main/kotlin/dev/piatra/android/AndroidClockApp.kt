package dev.piatra.android

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.piatra.core.ClockApp
import dev.piatra.core.ClockResult
import java.util.Locale
import dev.piatra.R

// Alarms and timers via the phone's Clock app, with "implicit intents": we describe what we want
// (AlarmClock.ACTION_SET_ALARM + hour and minute) and Android hands it to whichever app handles
// it. EXTRA_SKIP_UI: do it without showing a screen.
// Needs the SET_ALARM permission (install-time). Ported from upstream SetAlarmTool.android.kt /
// SetTimerTool.android.kt.
//
// The catch: this opens an activity in the Clock app, and Android blocks apps from opening
// activities while they're in the background. Upstream uses a companion-device association (its
// ring) to be allowed. From the background, Piatra posts a notification instead: tapping it is the user's action, which Android allows.
class AndroidClockApp(private val context: Context) : ClockApp {

    override fun setAlarm(hour: Int, minute: Int, label: String?): ClockResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val time = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        return send(intent, "Tap to set an alarm for $time" + (label?.let { " ($it)" } ?: ""))
    }

    override fun setTimer(seconds: Int): ClockResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        // A timer from the notification starts when tapped, so it runs the full length from then.
        return send(intent, "Tap to start a ${formatLength(seconds)} timer")
    }

    private fun send(intent: Intent, askText: String): ClockResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // starting from outside an Activity
        check(context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()) {
            "No Clock app on this phone accepts alarms and timers from other apps"
        }
        if (isInForeground()) {
            context.startActivity(intent)
            return ClockResult.SET
        }
        askWithNotification(intent, askText)
        return ClockResult.ASKED_USER
    }

    // Is Piatra on screen? Then Android lets it open the Clock app directly.
    private fun isInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun askWithNotification(intent: Intent, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alarm and timer requests", NotificationManager.IMPORTANCE_HIGH)
        )
        // A new request code each time, so two requests don't replace each other's PendingIntent.
        val requestCode = System.currentTimeMillis().toInt()
        val tap = PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ph_alarm)
            .setContentTitle("Piatra")
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(requestCode, notification)
    }

    private fun formatLength(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} min"
        seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "$seconds s"
    }

    companion object {
        private const val CHANNEL_ID = "clock_requests"
    }
}
