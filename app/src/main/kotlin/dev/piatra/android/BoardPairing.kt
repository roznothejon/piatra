package dev.piatra.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import dev.piatra.core.board.BoardProtocol
import dev.piatra.data.PairedBoard
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.awaitClose
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Pairing with the board, in two parts:
// 1. CompanionDeviceManager (CDM): the system scans for the board's service UUID and shows a
//    "Pair with Piatra?" dialog. Saying yes creates an "association" between the app and the
//    board. It also lets the system wake the app when the board comes into range.
// 2. A Bluetooth bond: the board and the phone swap keys, so later links are encrypted. The
//    board only accepts this in pairing mode (protocol section 7).
class BoardPairing(private val context: Context) {
    private val cdm = context.getSystemService(CompanionDeviceManager::class.java)
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    // The callback-based CDM API from Android 13 (API 33). Older versions have a different one;
    // Piatra targets newer phones, so they just get a message.
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 33 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // "Nearby devices": connecting, and scanning for the board's adverts (automatic sync).
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // Shows the system dialog and waits until the user picks the board. `showDialog` gets an
    // IntentSender: a ticket for a screen that belongs to the system, which only an Activity can
    // open, so the UI opens it. Throws PairingException if the user backs out or nothing is found.
    @RequiresApi(33)
    suspend fun associate(showDialog: (IntentSender) -> Unit): PairedBoard {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(BoardProtocol.SERVICE_UUID)).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            // One board in range: the dialog asks "Pair with Piatra?" instead of showing a list.
            .setSingleDevice(true)
            .build()
        // suspendCancellableCoroutine: wait here until one of the callbacks calls resume.
        return suspendCancellableCoroutine { continuation ->
            cdm.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    showDialog(intentSender)
                }

                override fun onAssociationCreated(association: AssociationInfo) {
                    val address = association.deviceMacAddress?.toString()?.uppercase()
                    if (address == null) {
                        continuation.resumeWithException(PairingException("The system didn't say which board was picked."))
                    } else {
                        val name = association.displayName?.toString() ?: BoardProtocol.DEVICE_NAME
                        continuation.resume(PairedBoard(address, name))
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    continuation.resumeWithException(PairingException(error?.toString() ?: "Pairing was cancelled."))
                }
            })
        }
    }

    // Asks Android to bond with the board and waits for the answer: true = bonded. False when
    // the board turned it down (not in pairing mode). Already bonded: true straight away.
    @SuppressLint("MissingPermission") // checked by hasPermission() before pairing starts
    @RequiresApi(33)
    suspend fun bond(address: String): Boolean {
        val device = adapter?.getRemoteDevice(address) ?: return false
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        // Android reports bond changes as broadcasts. callbackFlow turns the receiver into a Flow;
        // first {} waits for the end state, then awaitClose unregisters the receiver.
        return callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (changed?.address != address) return
                    trySend(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
                }
            }
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            if (!device.createBond()) trySend(device.bondState)
            awaitClose { context.unregisterReceiver(receiver) }
        }.first { it != BluetoothDevice.BOND_BONDING } == BluetoothDevice.BOND_BONDED
    }

    // Whether Android still has the association with this board. Without it Android won't let
    // Piatra start a sync from the background (one vanished, cause unknown,
    // and automatic sync stopped without a word).
    fun isAssociated(address: String): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }

    // Removes the association. Android keeps its side of the bond: that's removed in the phone's
    // Bluetooth settings (apps can't do it).
    fun forget(address: String) {
        if (Build.VERSION.SDK_INT < 33) return
        cdm.myAssociations
            .filter { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
            .forEach { cdm.disassociate(it.id) }
    }
}

// Both are in the "Nearby devices" group, so Android asks once for the two.
val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)

class PairingException(message: String) : Exception(message)
