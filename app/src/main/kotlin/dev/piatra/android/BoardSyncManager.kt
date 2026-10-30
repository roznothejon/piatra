package dev.piatra.android

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.piatra.core.Clock
import dev.piatra.core.board.SyncSummary
import dev.piatra.core.board.UnsupportedBoardException
import dev.piatra.core.board.shouldAutoSync
import dev.piatra.core.board.syncUntilEmpty
import dev.piatra.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

// What the board sync is doing. Shown on the Recordings and Device screens and in the
// notification.
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String) : SyncState
    data class Done(val summary: SyncSummary) : SyncState
    data class Failed(val message: String) : SyncState
}

// Runs board syncs, one at a time. start() asks Android for BoardSyncService, which
// keeps the app alive while it calls run(). One shared instance (Koin single), so every screen
// sees the same state.
class BoardSyncManager(
    private val context: Context,
    private val settings: Settings,
    private val sink: BoardRecordingSink,
    private val health: BoardHealth,
    private val pairing: BoardPairing,
    private val clock: Clock,
    // For clearing the result line after a while; lives as long as the app.
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private var clearLater: Job? = null

    val isRunning: Boolean get() = _state.value is SyncState.Running

    fun start() {
        if (isRunning) return
        clearLater?.cancel()
        _state.value = SyncState.Running("Connecting…")
        // A foreground service must call startForeground() within a few seconds; ours does it first.
        context.startForegroundService(Intent(context, BoardSyncService::class.java))
    }

    private val autoLock = Mutex()
    private var lastAutoStart: Long? = null

    // Automatic sync: the board was heard, by our own scan (BoardScanner, with the
    // advert's pendingCount) or by Android's companion watch (BoardPresence, null = unknown).
    // Adverts arrive several times a second, so shouldAutoSync() lets only a few through.
    suspend fun onBoardSeen(pendingCount: Int?, checkInWanted: Boolean = false) = autoLock.withLock {
        if (isRunning) return@withLock
        val now = clock.nowMillis()
        val readAt = settings.boardReading.first()?.readAtMillis
        if (!shouldAutoSync(pendingCount, checkInWanted, readAt, lastAutoStart, now)) return@withLock
        lastAutoStart = now
        Log.i(TAG, "automatic sync: " + when {
            checkInWanted -> "the board was tapped"
            pendingCount != null -> "$pendingCount waiting"
            else -> "board appeared"
        })
        try {
            start()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't start an automatic sync", e)
            // Android only lets a background app start a foreground service for a reason; ours
            // is the companion association (REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND).
            // The class is from Android 12, so check the version before naming it.
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                val address = settings.board.first()?.address
                val linkMissing = address != null && !pairing.isAssociated(address)
                health.warnBackgroundSyncOff(linkMissing)
                failed(if (linkMissing) "Automatic sync is off: press Restore on the Device screen." else "Android didn't allow a sync in the background.")
            } else {
                failed(boardErrorMessage(e))
            }
        }
    }

    // Hide the last result (the ✕ on the Recordings screen).
    fun dismiss() {
        if (!isRunning) _state.value = SyncState.Idle
    }

    // The service couldn't start (e.g. the Bluetooth permission was taken away).
    fun failed(message: String) = finish(SyncState.Failed(message))

    // Show the result, then hide it by itself:
    // a success after a few seconds, an error after longer, so there's time to read it.
    private fun finish(result: SyncState) {
        _state.value = result
        clearLater?.cancel()
        clearLater = scope.launch {
            delay(if (result is SyncState.Failed) ERROR_SHOWN else RESULT_SHOWN)
            // Only if nothing has replaced it since (e.g. a new sync).
            _state.compareAndSet(result, SyncState.Idle)
        }
    }

    // Called by BoardSyncService. Connect, sync, save the board's fresh status (and warn about it).
    suspend fun run() {
        val board = settings.board.first()
        if (board == null) {
            finish(SyncState.Failed("No board is paired."))
            return
        }
        _state.value = SyncState.Running("Connecting…")
        try {
            val summary = talkToBoard(board.address) { link ->
                link.syncUntilEmpty(
                    clock::nowMillis, sink,
                    onProgress = { p -> _state.value = SyncState.Running("Receiving ${p.index} of ${p.total} · ${p.percent} %") },
                    log = { Log.i(TAG, it) },
                )
            }
            health.onInfo(summary.status)
            Log.i(TAG, "sync done: $summary")
            finish(SyncState.Done(summary))
        } catch (e: TimeoutCancellationException) {
            // Caught before CancellationException: a timeout is one too, but it means
            // "the board went quiet", not "stop".
            finish(SyncState.Failed(boardErrorMessage(e)))
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            finish(SyncState.Failed(boardErrorMessage(e)))
        }
    }

    private companion object {
        const val TAG = "PiatraBoard"
        val RESULT_SHOWN = 5.seconds
        val ERROR_SHOWN = 30.seconds
    }
}

// What went wrong talking to the board, in words for the screen. Shared with the Device
// screen's "Check now".
fun boardErrorMessage(e: Exception): String = when (e) {
    is TimeoutCancellationException -> "Couldn't reach the board. Tap its button to wake it, then try again."
    is UnsupportedBoardException -> "The board's firmware speaks protocol v${e.version}; update the app or the firmware."
    is SecurityException -> "Piatra needs the \"Nearby devices\" permission. Allow it on the Device screen."
    // Usually the board doesn't know this phone's key any more (it bonded with another device),
    // so the encrypted link fails.
    else -> "Couldn't talk to the board (${e.message ?: e::class.simpleName}). " +
        "If it was paired with another device since, forget it here and in Bluetooth settings, then pair again."
}
