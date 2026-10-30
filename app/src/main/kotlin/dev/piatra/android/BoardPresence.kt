package dev.piatra.android

import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.piatra.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// Automatic sync, trigger 1 of 2. Asks Android's companion-device watch to look out
// for the paired board: the system runs its own low-power Bluetooth scan, and when the board
// starts advertising Android starts Piatra if needed and calls BoardCompanionService.
// On some phones (seen on Android 17) this goes deaf after a few syncs, so BoardScanner
// is the main trigger; this one only acts when that scan can't run.
// Ported from upstream's RingPresenceObserver (which only logs the events).
class BoardPresence(
    private val context: Context,
    private val settings: Settings,
    private val syncManager: BoardSyncManager,
    private val scanner: BoardScanner,
    private val scope: CoroutineScope,
) {
    private val cdm = context.getSystemService(CompanionDeviceManager::class.java)
    private var observed: Observation? = null

    // Android 16 (API 36) watches by association id; before that, by address.
    private class Observation(val address: String, val request: ObservingDevicePresenceRequest?)

    // Called once from PiatraApp. Follows the paired board: pairing starts watching, Forget stops.
    fun start() {
        if (Build.VERSION.SDK_INT < 33) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) return
        settings.board
            .map { it?.address }
            .distinctUntilChanged()
            .onEach { observe(it) }
            .launchIn(scope)
    }

    // After Restore on the Device screen: the association is new, so watch it again.
    fun refresh() {
        if (Build.VERSION.SDK_INT < 33) return
        scope.launch { observe(settings.board.first()?.address) }
    }

    // From BoardCompanionService. The board started advertising, so it has something for us
    // (or it's a check-in, which refreshes the battery reading). A sync already running is left be.
    fun onAppeared(address: String) {
        Log.i(TAG, "board $address appeared")
        // Our own scan reads the advert and knows whether anything is waiting; this event
        // doesn't, so it only acts when that scan can't run.
        // Also restart that scan: Android may have dropped it (e.g. Bluetooth was off for a
        // while) without the app knowing.
        if (scanner.canScan) scanner.refresh() else scope.launch { syncManager.onBoardSeen(pendingCount = null) }
    }

    fun onDisappeared(address: String) {
        Log.i(TAG, "board $address disappeared")
        // Watch again from scratch. Seen on Android 17: after a sync Android gives
        // the board 10 s to show up again, then calls it "likely out of range", and from then on
        // its scan never reports the board, even while it advertises. A fresh watch does.
        observe(address)
    }

    @Suppress("DEPRECATION")
    private fun observe(address: String?) {
        observed?.let { old ->
            // Fails if Forget already removed the association, which stops the watch anyway.
            runCatching {
                if (old.request != null && Build.VERSION.SDK_INT >= 36) cdm.stopObservingDevicePresence(old.request)
                else cdm.stopObservingDevicePresence(old.address)
            }
            observed = null
            Log.i(TAG, "stopped watching for ${old.address}")
        }
        if (address == null) return
        try {
            observed = if (Build.VERSION.SDK_INT >= 36) {
                val association = cdm.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
                    ?: error("$address isn't associated with Piatra; pair it again")
                val request = ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build()
                // Android keeps watching after the app's process ends, and a second start is a
                // no-op that keeps its old scan. Stop first, so every start is a fresh watch.
                runCatching { cdm.stopObservingDevicePresence(request) }
                cdm.startObservingDevicePresence(request)
                Observation(address, request)
            } else {
                runCatching { cdm.stopObservingDevicePresence(address) }
                cdm.startObservingDevicePresence(address)
                Observation(address, null)
            }
            Log.i(TAG, "watching for board $address")
        } catch (e: Exception) {
            Log.w(TAG, "can't watch for board $address", e)
        }
    }

    private companion object {
        const val TAG = "PiatraBoard"
    }
}
