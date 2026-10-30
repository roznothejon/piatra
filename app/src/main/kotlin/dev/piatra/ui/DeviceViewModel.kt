package dev.piatra.ui

import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.piatra.android.BoardHealth
import dev.piatra.android.BoardPairing
import dev.piatra.android.BoardPresence
import dev.piatra.android.BoardScanner
import dev.piatra.android.BoardSyncManager
import dev.piatra.android.SyncState
import dev.piatra.android.boardErrorMessage
import dev.piatra.android.PairingException
import dev.piatra.android.talkToBoard
import dev.piatra.core.Clock
import dev.piatra.core.board.BatteryPoint
import dev.piatra.core.board.BoardReading
import dev.piatra.core.board.BoardWarning
import dev.piatra.core.board.checkIn
import dev.piatra.data.BatteryHistory
import dev.piatra.data.PairedBoard
import dev.piatra.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// What the Device screen is doing right now.
sealed interface DeviceAction {
    data object Idle : DeviceAction
    data class Busy(val message: String) : DeviceAction
    data class Failed(val message: String) : DeviceAction
}

// The Device screen: pair with the board, read its status, sync, forget it.
class DeviceViewModel(
    private val settings: Settings,
    private val pairing: BoardPairing,
    private val clock: Clock,
    private val boardSync: BoardSyncManager,
    private val scanner: BoardScanner,
    private val presence: BoardPresence,
    private val health: BoardHealth,
    batteryHistory: BatteryHistory,
) : ViewModel() {
    val board: StateFlow<PairedBoard?> = settings.board
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val reading: StateFlow<BoardReading?> = settings.boardReading
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sync: StateFlow<SyncState> = boardSync.state

    // The last 7 days of battery readings, newest first.
    val batteryHistory: StateFlow<List<BatteryPoint>> = batteryHistory.observeSince(clock.nowMillis() - 7 * DAY_MILLIS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun nowMillis(): Long = clock.nowMillis()

    val ignoreSdCardWarning: StateFlow<Boolean> = settings.ignoreSdCardWarning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setIgnoreSdCardWarning(on: Boolean) {
        viewModelScope.launch { settings.setIgnoreSdCardWarning(on) }
        if (on) health.clear(BoardWarning.SD_ERROR)
    }

    // Whether Android still has the companion association with the paired board, which
    // automatic sync needs. Checked when the screen opens or comes back.
    private val _linked = MutableStateFlow(true)
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    fun refreshLink() {
        viewModelScope.launch {
            _linked.value = settings.board.first()?.let { pairing.isAssociated(it.address) } ?: true
        }
    }

    private val _action = MutableStateFlow<DeviceAction>(DeviceAction.Idle)
    val action: StateFlow<DeviceAction> = _action.asStateFlow()

    val pairingSupported: Boolean get() = pairing.isSupported
    val isBluetoothOn: Boolean get() = pairing.isBluetoothOn
    fun hasPermission(): Boolean = pairing.hasPermission()

    // The scan for adverts can only start once the permission is there.
    fun onPermissionGranted() = scanner.refresh()

    private var job: Job? = null

    // showDialog: opens the system's pairing dialog (only the screen can do that).
    fun pair(showDialog: (IntentSender) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return
        run("Looking for the board…") {
            val board = try {
                pairing.associate(showDialog)
            } catch (e: PairingException) {
                Log.i(TAG, "association failed: ${e.message}")
                _action.value = DeviceAction.Failed("No board was paired. ${TAP_HINT}")
                return@run
            }
            _action.value = DeviceAction.Busy("Pairing…")
            if (!pairing.bond(board.address)) {
                pairing.forget(board.address)
                _action.value = DeviceAction.Failed("The board turned down pairing. $TAP_HINT")
                return@run
            }
            settings.setBoard(board)
            health.clearBackgroundSyncOff()
            refreshLink()
            checkIn(board)
        }
    }

    // Makes a new association with the paired board when Android has lost it. Only the system
    // dialog: the Bluetooth bond (the keys) is still there, so the board needs no pairing mode.
    fun restore(showDialog: (IntentSender) -> Unit) {
        val current = board.value ?: return
        if (Build.VERSION.SDK_INT < 33) return
        run("Looking for the board…") {
            val found = try {
                pairing.associate(showDialog)
            } catch (e: PairingException) {
                Log.i(TAG, "restore failed: ${e.message}")
                _action.value = DeviceAction.Failed("Not restored. $WAKE_HINT")
                return@run
            }
            if (!found.address.equals(current.address, ignoreCase = true)) {
                pairing.forget(found.address)
                _action.value = DeviceAction.Failed("That was a different board (${found.address}). $WAKE_HINT")
                return@run
            }
            Log.i(TAG, "association with ${current.address} restored")
            health.clearBackgroundSyncOff()
            presence.refresh()
            refreshLink()
            _action.value = DeviceAction.Idle
        }
    }

    // The user closed the pairing dialog without choosing.
    fun cancelPairing() {
        job?.cancel()
        _action.value = DeviceAction.Idle
    }

    fun connect() {
        val board = board.value ?: return
        run("Connecting…") { checkIn(board) }
    }

    // Runs in BoardSyncService, so it carries on if you leave the screen.
    fun syncNow() {
        if (board.value == null) return
        _action.value = DeviceAction.Idle
        boardSync.start()
    }

    fun forget() {
        val board = board.value ?: return
        job?.cancel()
        pairing.forget(board.address)
        viewModelScope.launch { settings.setBoard(null) }
        health.clearAll()
        _linked.value = true
        _action.value = DeviceAction.Idle
    }

    // One thing at a time: a new action replaces the one running.
    private fun run(message: String, block: suspend () -> Unit) {
        job?.cancel()
        _action.value = DeviceAction.Busy(message)
        job = viewModelScope.launch { block() }
    }

    // Connect, read the status, set the clock, save the reading (and warn about it).
    private suspend fun checkIn(board: PairedBoard) {
        _action.value = DeviceAction.Busy("Connecting…")
        try {
            val status = talkToBoard(board.address) { it.checkIn(nowSeconds = clock.nowMillis() / 1000) }
            health.onInfo(status)
            _action.value = DeviceAction.Idle
        } catch (e: CancellationException) {
            // A timeout is a CancellationException too, but it means "the board didn't answer".
            // Any other cancellation (screen closed, new action) must be passed on.
            if (e !is TimeoutCancellationException) throw e
            _action.value = DeviceAction.Failed(boardErrorMessage(e))
        } catch (e: Exception) {
            Log.w(TAG, "check-in failed", e)
            _action.value = DeviceAction.Failed(boardErrorMessage(e))
        }
    }

    private companion object {
        const val TAG = "PiatraBoard"
        const val WAKE_HINT = "Tap the board's button once so it shows up, then press Restore and pick it in Android's dialog."
        const val TAP_HINT = "Tap the board's button 5 times (a long buzz), then press Pair within a minute."
    }
}

private const val DAY_MILLIS = 24 * 3_600_000L
