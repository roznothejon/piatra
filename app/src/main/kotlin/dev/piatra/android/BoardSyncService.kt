package dev.piatra.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.piatra.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import dev.piatra.R

// A foreground service: while it runs, Android shows its notification and keeps the
// app's process alive, even if you leave the app or lock the phone mid-sync. Its type,
// connectedDevice, is declared in the manifest. It does one sync, then stops itself.
// Upstream runs its ring the same way (PebbleService, also connectedDevice).
class BoardSyncService : Service(), KoinComponent {
    // KoinComponent + inject(): Android creates services itself, so Koin can't pass them in.
    private val manager: BoardSyncManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // Nothing binds to this service; it's only started.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createChannel()
            // The type argument exists from Android 10; before that the manifest's type is enough.
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification("Connecting…"))
            }
        } catch (e: Exception) {
            // E.g. SecurityException: the connectedDevice type needs the Bluetooth permission.
            Log.w(TAG, "couldn't start the sync service", e)
            manager.failed(boardErrorMessage(e))
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            job = scope.launch {
                // Keep the notification's text in step with the sync.
                val updates = manager.state
                    .onEach { if (it is SyncState.Running) notify(it.message) }
                    .launchIn(this)
                try {
                    manager.run()
                } finally {
                    updates.cancel()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        // NOT_STICKY: if Android kills the process mid-sync, don't restart the service by
        // itself. The next sync picks up where the board is.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        // LOW: shown in the shade, no sound, no pop-up.
        val channel = NotificationChannel(CHANNEL_ID, "Board sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ph_download_simple)
            .setContentTitle("Syncing with the board")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    // Posting with the same id replaces the notification.
    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private companion object {
        const val TAG = "PiatraBoard"
        const val CHANNEL_ID = "board_sync"
        const val NOTIFICATION_ID = 4301
    }
}
