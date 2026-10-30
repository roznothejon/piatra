package dev.piatra.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import dev.piatra.android.PERMISSIONS
import dev.piatra.android.SyncState
import dev.piatra.core.board.BatteryPoint
import dev.piatra.core.board.BoardReading
import dev.piatra.core.board.batteryTrend
import dev.piatra.core.board.DeviceFlag
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

// The Device screen, opened from the ☰ menu: pair with the board and see its
// battery, storage and when it was last seen.
@Composable
fun DeviceScreen(onBack: () -> Unit, viewModel: DeviceViewModel = koinViewModel()) {
    val board by viewModel.board.collectAsStateWithLifecycle()
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val ignoreSdCardWarning by viewModel.ignoreSdCardWarning.collectAsStateWithLifecycle()
    val linked by viewModel.linked.collectAsStateWithLifecycle()
    val batteryHistory by viewModel.batteryHistory.collectAsStateWithLifecycle()
    var bluetoothOn by remember { mutableStateOf(viewModel.isBluetoothOn) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        bluetoothOn = viewModel.isBluetoothOn
        viewModel.refreshLink()
    }

    // Opens the system's pairing dialog. Backing out of it gives RESULT_CANCELED.
    val pairingDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) viewModel.cancelPairing()
    }
    val startPairing = {
        viewModel.pair { sender -> pairingDialog.launch(IntentSenderRequest.Builder(sender).build()) }
    }
    val startRestore = {
        viewModel.restore { sender -> pairingDialog.launch(IntentSenderRequest.Builder(sender).build()) }
    }
    // What to do once the Bluetooth permission is granted: pair, or connect.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.values.all { it }
        permissionDenied = !granted
        if (granted) {
            viewModel.onPermissionGranted()
            afterPermission?.invoke()
        }
        afterPermission = null
    }
    // Runs `next` now if the permission is there, otherwise after the dialog says yes.
    val withPermission = { next: () -> Unit ->
        if (viewModel.hasPermission()) {
            next()
        } else if (Build.VERSION.SDK_INT >= 31) {
            afterPermission = next
            permissionLauncher.launch(PERMISSIONS)
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            BackButton(onBack)
            Text("Device", style = MaterialTheme.typography.titleLarge)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !viewModel.pairingSupported -> Text("Pairing needs Android 13 or newer.")
                !bluetoothOn -> Text("Bluetooth is off. Turn it on to pair or connect.", color = MaterialTheme.colorScheme.error)
                permissionDenied -> Text(
                    "Piatra needs the \"Nearby devices\" permission to talk to the board. " +
                        "If no dialog appears, allow it in the app's settings.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val busy = action is DeviceAction.Busy || sync is SyncState.Running
            val current = board
            if (current == null) {
                Text(
                    "Pair your Piatra board. If it was paired with another device before, tap its button " +
                        "5 times first (a long buzz, then the light blinks slowly)."
                )
                Button(onClick = { withPermission(startPairing) }, enabled = !busy && bluetoothOn && viewModel.pairingSupported) {
                    Text("Pair")
                }
            } else {
                Text("${current.name}  ·  ${current.address}", style = MaterialTheme.typography.titleMedium)
                // Android lost the association: syncs still work with the app
                // open, but not from the background.
                if (!linked && viewModel.pairingSupported) {
                    Text(
                        "Automatic sync is off: Android forgot that Piatra and the board belong together. " +
                            "Tap the board's button once, then press Restore and pick it in Android's dialog.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { withPermission(startRestore) }, enabled = !busy && bluetoothOn) { Text("Restore") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { withPermission(viewModel::syncNow) }, enabled = !busy && bluetoothOn) { Text("Sync now") }
                    OutlinedButton(onClick = { withPermission(viewModel::connect) }, enabled = !busy && bluetoothOn) { Text("Check now") }
                    OutlinedButton(onClick = viewModel::forget, enabled = sync !is SyncState.Running) { Text("Forget") }
                }
                // Android keeps its own keys for the board, and apps can't delete them.
                Text(
                    "After Forget, also remove Piatra in the phone's Bluetooth settings before pairing again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            syncLabel(sync)?.let {
                Text(it, color = if (sync is SyncState.Failed) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
            when (val a = action) {
                is DeviceAction.Busy -> Text(a.message)
                is DeviceAction.Failed -> Text(a.message, color = MaterialTheme.colorScheme.error)
                DeviceAction.Idle -> Unit
            }
        }
        val r = reading
        if (board != null && r != null) StatusRows(r, ignoreSdCardWarning)
        if (board != null) BatteryHistoryRows(batteryHistory, viewModel.nowMillis())
        if (board != null) {
            ListItem(
                headlineContent = { Text("Ignore SD card warning") },
                supportingContent = {
                    Text(
                        "For running the board without a card on purpose. Only hides the warning: " +
                            "the board still records to a card whenever one is in."
                    )
                },
                trailingContent = { Switch(checked = ignoreSdCardWarning, onCheckedChange = viewModel::setIgnoreSdCardWarning) },
            )
        }
    }
}

// The board's last status report, one row per value.
@Composable
private fun StatusRows(reading: BoardReading, ignoreSdCardWarning: Boolean) {
    val s = reading.status
    ListItem(headlineContent = { Text("Last seen") }, supportingContent = { Text(formatDateTime(reading.lastSeenMillis)) })
    ListItem(
        headlineContent = { Text("Battery") },
        supportingContent = {
            val volts = String.format(Locale.getDefault(), "%.2f V", s.batteryMillivolts / 1000.0)
            val power = when {
                s.has(DeviceFlag.CHARGING) -> ", charging"
                s.has(DeviceFlag.USB_POWER) -> ", on USB"
                else -> ""
            }
            Text(s.batteryPercent?.let { "$it % ($volts$power)" } ?: "Not measured$power")
        },
    )
    ListItem(
        headlineContent = { Text("Storage") },
        supportingContent = {
            // Without a card the board records to its own flash (SD_ERROR set).
            val where = if (s.has(DeviceFlag.SD_ERROR)) "board memory, no SD card" else "SD card"
            Text(
                s.storageFreePercent?.let { "$it % free (${s.storageFreeKiB / 1024} of ${s.storageTotalKiB / 1024} MB, $where)" }
                    ?: "No storage"
            )
        },
    )
    ListItem(
        headlineContent = { Text("Recordings waiting") },
        supportingContent = { Text("${s.pendingCount}") },
    )
    val problems = buildList {
        if (s.missedCount > 0) add("${s.missedCount} recordings missed (storage full)")
        if (s.has(DeviceFlag.BATTERY_LOW)) add("battery low")
        if (s.has(DeviceFlag.STORAGE_FULL)) add("storage full") else if (s.has(DeviceFlag.STORAGE_LOW)) add("storage low")
        if (s.has(DeviceFlag.SD_ERROR) && !ignoreSdCardWarning) add("SD card error")
        if (s.has(DeviceFlag.MIC_ERROR)) add("microphone error")
        if (s.resetReason.isProblem) add("last restart: ${s.resetReason.name.lowercase().replace('_', ' ')}")
    }
    if (problems.isNotEmpty()) {
        ListItem(
            headlineContent = { Text("Problems", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text(problems.joinToString(", ").replaceFirstChar { it.uppercase() }) },
        )
    }
    ListItem(
        headlineContent = { Text("Firmware") },
        supportingContent = {
            Text("${s.firmwareVersion.ifEmpty { "unknown" }}, protocol v${s.protocolVersion}, " +
                "up ${formatUptime(s.uptimeSeconds)} (${s.resetReason.name.lowercase().replace('_', ' ')})")
        },
    )
}

// The battery over the last days: how fast it falls, and the readings themselves
// (one per wake-up: check-ins every 30 min, taps, recordings), collapsed to begin with.
@Composable
private fun BatteryHistoryRows(points: List<BatteryPoint>, nowMillis: Long) {
    var open by remember { mutableStateOf(false) }
    val day = batteryTrend(points, nowMillis, windowMillis = 24 * 3_600_000L)
    val all = batteryTrend(points, nowMillis, windowMillis = Long.MAX_VALUE / 2)
    ListItem(
        modifier = Modifier.clickable { open = !open },
        headlineContent = { Text("Battery history") },
        supportingContent = {
            Text(
                when {
                    points.isEmpty() -> "No readings yet: one is saved each time the board wakes up."
                    day == null && all == null -> "${points.size} readings, too few for a trend yet."
                    else -> listOfNotNull(
                        day?.let { "Last 24 h: ${formatTrend(it.millivoltsPerHour)} (${it.readings} readings)" },
                        all?.takeIf { it.hours > 25 }?.let {
                            "Last ${(it.hours / 24).toInt()} days: ${formatTrend(it.millivoltsPerHour)}"
                        },
                    ).joinToString("\n") + if (open) "" else "\nTap to see the readings."
                }
            )
        },
    )
    if (open) {
        // Newest first, with the change from the reading before.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            points.forEachIndexed { i, p ->
                val change = points.getOrNull(i + 1)?.let { p.millivolts - it.millivolts }
                Text(
                    "${formatDateTime(p.atMillis)}   ${p.millivolts} mV" + (change?.let { "   %+d".format(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// -2.04 -> "−2.0 mV per hour"
private fun formatTrend(mvPerHour: Double): String =
    String.format(Locale.getDefault(), "%+.1f mV per hour", mvPerHour).replace('-', '−')

// 3725 -> "1 h 2 min"
private fun formatUptime(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    else -> "${seconds / 86_400} d ${seconds % 86_400 / 3600} h"
}
