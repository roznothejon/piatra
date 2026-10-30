package dev.piatra.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.piatra.core.board.AdvertStatus
import dev.piatra.core.board.BoardProtocol
import dev.piatra.core.board.DeviceFlag
import dev.piatra.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// Automatic sync, trigger 2 of 2 and the main one. A background scan of our own:
// we give Android a filter (the board's address and service UUID) and a PendingIntent, and
// Android's Bluetooth stack keeps scanning after Piatra's process has ended. Each matching
// advert is delivered to BoardScanReceiver, starting Piatra if needed.
//
// The advert carries the board's short status (protocol section 8), so we only connect when
// recordings are waiting (or the last status is old). Needs BLUETOOTH_SCAN, declared
// "never for location", in the same "Nearby devices" permission as BLUETOOTH_CONNECT.
//
// The scan is lost when the phone restarts or Piatra is updated; PiatraApp starts it again
// whenever the process starts (BootReceiver wakes it for both). Turning Bluetooth off loses
// it too, so start() also restarts it when Bluetooth comes back on.
class BoardScanner(
    private val context: Context,
    private val settings: Settings,
    private val syncManager: BoardSyncManager,
    private val health: BoardHealth,
    private val scope: CoroutineScope,
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var lastStatus: AdvertStatus? = null

    // Called once from PiatraApp: follows the paired board (pair = scan for it, Forget = stop).
    fun start() {
        if (Build.VERSION.SDK_INT < 31) return // older versions would need the location permission
        settings.board
            .map { it?.address }
            .distinctUntilChanged()
            .onEach { restart(it) }
            .launchIn(scope)
        // Bluetooth switched back on: Android dropped our scan when it went off.
        val bluetoothOn = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) == BluetoothAdapter.STATE_ON) refresh()
            }
        }
        context.registerReceiver(bluetoothOn, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    }

    // After the permission is granted on the Device screen, or Bluetooth comes back on.
    fun refresh() {
        if (Build.VERSION.SDK_INT < 31) return
        scope.launch { restart(settings.board.first()?.address) }
    }

    // Whether our scan can run. When it can't, Android's companion watch is the fallback.
    val canScan: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && hasPermission() && adapter?.isEnabled == true

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission()
    private fun restart(address: String?) {
        val scanner: BluetoothLeScanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            Log.i(TAG, "not scanning: Bluetooth is off")
            return
        }
        if (!hasPermission()) {
            Log.i(TAG, "not scanning: no permission to scan (allow it on the Device screen)")
            return
        }
        // Long range: scan for and connect through the board's Coded PHY advert
        // when this phone supports it. Android only knows while Bluetooth is on, so this is
        // checked here, where the scan restarts when Bluetooth comes back.
        longRange = adapter?.isLeCodedPhySupported == true
        Log.i(TAG, "long range (Coded PHY): " + if (longRange) "supported" else "not supported by this phone")
        // The same PendingIntent stands for the same scan, so this stops an old one first.
        runCatching { scanner.stopScan(pendingIntent()) }
        if (address == null) return
        val filter = ScanFilter.Builder()
            .setDeviceAddress(address)
            .setServiceUuid(ParcelUuid.fromString(BoardProtocol.SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            // Listens in short bursts: cheap, and fast enough for a board that keeps advertising
            // until its recordings are fetched.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            // Every advert, not only the first: "first match" is how Android's companion watch
            // scans, and that can go deaf on some phones.
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            // Extended adverts too, on every PHY the phone has: the board's long-range advert
            // is an extended one on the Coded PHY. Ordinary adverts still come through.
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        val error = scanner.startScan(listOf(filter), scanSettings, pendingIntent())
        if (error == 0) Log.i(TAG, "scanning for board $address") else Log.w(TAG, "couldn't start the scan (error $error)")
    }

    // From BoardScanReceiver: adverts heard since the last delivery.
    suspend fun onResults(results: List<ScanResult>) {
        val address = settings.board.first()?.address ?: return
        val result = results.lastOrNull { it.device.address.equals(address, ignoreCase = true) } ?: return
        // The status is in the long-range advert itself, or in the ordinary advert's scan
        // response (Android asks for it, but it may be missing).
        val status = AdvertStatus.decodeOrNull(result.scanRecord?.getManufacturerSpecificData(BoardProtocol.MANUFACTURER_ID))
        if (status != lastStatus) {
            lastStatus = status
            Log.i(TAG, "advert: " + (status?.let {
                "${it.pendingCount} waiting, ${it.batteryMillivolts} mV" + if (it.has(DeviceFlag.CHECK_IN)) ", tapped" else ""
            } ?: "no status") +
                ", signal ${result.rssi} dBm" + if (result.primaryPhy == BluetoothDevice.PHY_LE_CODED) " (long range)" else "")
            // Battery, storage and flags without connecting, and warnings from them.
            status?.let { health.onAdvert(it) }
        }
        syncManager.onBoardSeen(status?.pendingCount, checkInWanted = status?.has(DeviceFlag.CHECK_IN) == true)
    }

    // FLAG_MUTABLE: Android adds the scan results to this intent, so it must be changeable.
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, BoardScanReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TAG = "PiatraBoard"
    }
}

// Receives the scan's results (manifest, exported="false": only our own PendingIntent).
class BoardScanReceiver : BroadcastReceiver(), KoinComponent {
    private val scanner: BoardScanner by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            Log.w("PiatraBoard", "scan error $error")
            return
        }
        val results = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        } ?: return
        // goAsync: keep the receiver alive while the coroutine reads Settings (a few ms).
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                scanner.onResults(results)
            } finally {
                pending.finish()
            }
        }
    }
}
