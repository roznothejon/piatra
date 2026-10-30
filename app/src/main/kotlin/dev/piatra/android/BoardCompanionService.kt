package dev.piatra.android

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// The system binds this service while the paired board is in range (see BoardPresence), and
// tells it when the board appears or goes away. Declared in the manifest with
// BIND_COMPANION_DEVICE_SERVICE, so only the system can bind it.
// Ported from upstream's RingCompanionDeviceService.
@RequiresApi(33)
class BoardCompanionService : CompanionDeviceService(), KoinComponent {
    private val presence: BoardPresence by inject()

    // Android 16 (API 36) and later.
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val address = addressOf(event.associationId) ?: return
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> presence.onAppeared(address)
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> presence.onDisappeared(address)
        }
    }

    // Android 13 to 15. Android 16 and later call these as well as onDevicePresenceEvent, so
    // they're ignored there (otherwise each event would arrive twice).
    @Deprecated("Replaced by onDevicePresenceEvent in API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 36) return
        associationInfo.deviceMacAddress?.toString()?.uppercase()?.let(presence::onAppeared)
    }

    @Deprecated("Replaced by onDevicePresenceEvent in API 36")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 36) return
        associationInfo.deviceMacAddress?.toString()?.uppercase()?.let(presence::onDisappeared)
    }

    private fun addressOf(associationId: Int): String? =
        getSystemService(CompanionDeviceManager::class.java)
            .myAssociations
            .firstOrNull { it.id == associationId }
            ?.deviceMacAddress
            ?.toString()
            ?.uppercase()
}
