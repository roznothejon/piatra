package dev.piatra.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.piatra.MainActivity
import dev.piatra.core.Clock
import dev.piatra.core.board.AdvertStatus
import dev.piatra.core.board.BoardReading
import dev.piatra.core.board.BoardWarning
import dev.piatra.core.board.DeviceStatus
import dev.piatra.core.board.HealthReport
import dev.piatra.core.board.checkWarnings
import dev.piatra.core.board.toHealthReport
import dev.piatra.data.BatteryHistory
import dev.piatra.data.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.piatra.R

// Every report from the board comes through here: an INFO from a sync or
// Check now, or a changed advert. It saves the reading for the Device screen and posts or
// removes warning notifications (the rules are in core/board/BoardWarnings.kt), and adds the
// battery to its history.
class BoardHealth(
    private val context: Context,
    private val settings: Settings,
    private val clock: Clock,
    private val batteryHistory: BatteryHistory,
) {
    // Adverts and a sync can report at the same moment; one at a time, so neither undoes
    // the other's saved state.
    private val lock = Mutex()

    suspend fun onInfo(status: DeviceStatus) = lock.withLock {
        val now = clock.nowMillis()
        settings.setBoardReading(BoardReading(status, readAtMillis = now))
        batteryHistory.record(status.batteryMillivolts, source = "info")
        warn(status.toHealthReport(readAtMillis = now))
    }

    // Before the first INFO there's no reading to update, but the warnings still count.
    suspend fun onAdvert(advert: AdvertStatus) = lock.withLock {
        settings.boardReading.first()?.withAdvert(advert, clock.nowMillis())?.let { settings.setBoardReading(it) }
        batteryHistory.record(advert.batteryMillivolts, source = "advert")
        warn(advert.toHealthReport())
    }

    // The "Ignore SD card warning" switch was turned on: take down a warning already showing.
    // The saved memory catches up at the next reading.
    fun clear(kind: BoardWarning) {
        manager().cancel(TAG, kind.ordinal)
    }

    // Forget on the Device screen: a new board starts with no warnings.
    fun clearAll() {
        for (kind in BoardWarning.entries) manager().cancel(TAG, kind.ordinal)
        clearBackgroundSyncOff()
    }

    // Automatic sync was refused by Android. Not a board warning, so not in
    // checkWarnings(): it's the phone's side. linkMissing: the association is gone (the usual
    // cause), so the fix is Restore. Posted once until cleared, as adverts keep coming.
    private var backgroundSyncWarned = false

    fun warnBackgroundSyncOff(linkMissing: Boolean) {
        if (backgroundSyncWarned) return
        backgroundSyncWarned = true
        val (title, text) = if (linkMissing) {
            "Automatic sync is off" to "Android forgot that Piatra and the board belong together, so Piatra " +
                "can't sync in the background. Open ☰ → Device and press Restore."
        } else {
            "Automatic sync was refused" to "Android didn't let Piatra sync in the background. Open Piatra and press Sync."
        }
        Log.w(LOG_TAG, "warning: $title")
        show(BACKGROUND_TAG, 0, title, text)
    }

    // After Restore, a new pairing, or Forget.
    fun clearBackgroundSyncOff() {
        backgroundSyncWarned = false
        manager().cancel(BACKGROUND_TAG, 0)
    }

    private suspend fun warn(report: HealthReport) {
        val check = checkWarnings(settings.boardWarnings.first(), report, settings.ignoreSdCardWarning.first())
        if (check.memory != settings.boardWarnings.first()) settings.setBoardWarnings(check.memory)
        for (kind in check.clear) manager().cancel(TAG, kind.ordinal)
        for (notice in check.show) {
            Log.w(LOG_TAG, "warning: ${notice.title}: ${notice.text}")
            // The tag keeps these ids apart from the reminders' (which use the reminder's id).
            // One notification per kind: a new one replaces the old one.
            show(TAG, notice.kind.ordinal, notice.title, notice.text)
        }
    }

    // Without the notification permission this silently shows nothing.
    private fun show(tag: String, id: Int, title: String, text: String) {
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ph_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)) // long texts wrap
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager().notify(tag, id, notification)
    }

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "board_warnings"
        private const val TAG = "board_warning"
        private const val BACKGROUND_TAG = "background_sync"
        private const val LOG_TAG = "PiatraBoard"

        // Its own channel, so these can be muted in system Settings without muting reminders.
        // DEFAULT: makes a sound but doesn't pop up over what you're doing.
        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Board warnings", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
