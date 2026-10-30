package dev.piatra.android

import android.util.Log
import com.juul.kable.AndroidPeripheral
import com.juul.kable.NotConnectedException
import com.juul.kable.Peripheral
import com.juul.kable.Phy
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import dev.piatra.core.board.BoardLink
import dev.piatra.core.board.BoardProtocol
import dev.piatra.core.board.Command
import dev.piatra.core.board.Incoming
import dev.piatra.core.board.decodeChunk
import dev.piatra.core.board.decodeReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "PiatraBoard"
private val CONTROL = characteristicOf(Uuid.parse(BoardProtocol.SERVICE_UUID), Uuid.parse(BoardProtocol.CONTROL_UUID))
private val DATA = characteristicOf(Uuid.parse(BoardProtocol.SERVICE_UUID), Uuid.parse(BoardProtocol.DATA_UUID))

// How long to wait for the board to answer (protocol section 9). Connecting gets longer: Android
// may need a few seconds to find the board and encrypt the link.
private val CONNECT_TIMEOUT = 20.seconds
private val REPLY_TIMEOUT = 5.seconds

// Long range: connect over the Coded PHY, which reaches several times further
// at 125 kbps. Set at start-up by BoardScanner, from whether this phone supports it.
var longRange = false

// One conversation with the board at a time. Android shares one link per device, so two at once
// (e.g. Check now while an automatic sync starts) make their writes collide ("WriteRequestBusy").
// A Mutex is a lock for coroutines: the second caller waits (suspends) until the first is done.
private val oneAtATime = Mutex()

// Connects to the board at `address`, runs `block` with a BoardLink, then disconnects, whether
// `block` finished or failed. Waits while another call is talking to the board. Kable turns
// Android's callback-based Bluetooth API into suspend functions and Flows. Needs the
// BLUETOOTH_CONNECT permission.
suspend fun <T> talkToBoard(address: String, block: suspend (BoardLink) -> T): T = oneAtATime.withLock {
    var started = false
    try {
        connectOnce(address) { link ->
            started = true
            block(link)
        }
    } catch (e: NotConnectedException) {
        // The link sometimes drops while it's being set up (seen once: a supervision
        // timeout while notifications were turned on). Nothing was sent to the board yet, so
        // trying again is safe. Once `block` has started, the error is the caller's.
        if (started) throw e
        Log.i(TAG, "the link dropped while connecting; trying once more")
        connectOnce(address, block)
    }
}

private suspend fun <T> connectOnce(address: String, block: suspend (BoardLink) -> T): T {
    // A Peripheral is Kable's handle on one device. Nothing happens until connect().
    val peripheral = Peripheral(address) { if (longRange) phy = Phy.LeCoded }
    try {
        withTimeout(CONNECT_TIMEOUT) { peripheral.connect() }
        // A bigger MTU: up to 514 bytes per notification instead of 20 (section 2), so each
        // audio chunk carries 510 bytes.
        val mtu = (peripheral as AndroidPeripheral).requestMtu(517)
        Log.i(TAG, "connected to $address, MTU $mtu" + if (longRange) ", long range" else "")

        // Faster connection interval while we're connected: audio moves several times quicker.
        peripheral.requestConnectionPriority(AndroidPeripheral.Priority.High)

        // coroutineScope: the listeners launched below are cancelled when this block ends.
        return coroutineScope {
            // Replies (on `control`) and audio chunks (on `data`) arrive as notifications, on
            // Kable's own thread. A Channel is a queue between coroutines: the listeners put
            // each one in, receive() takes them out.
            // Unconfined: each listener handles a notification right away, on the thread that
            // delivered it, so chunks and replies enter the queue in the order the board sent
            // them. (Two separate listeners could otherwise let GET_END overtake the last
            // chunk; BoardSync copes with that too, at the cost of one extra GET.)
            val incoming = Channel<Incoming>(Channel.UNLIMITED)
            val controlOn = CompletableDeferred<Unit>()
            val dataOn = CompletableDeferred<Unit>()
            val listeners = listOf(
                // observe() turns notifications on (writes the CCCD) when collected; the lambda
                // runs once they're on. The board's characteristics need an encrypted link, so
                // this is also where Android encrypts with the saved bond.
                launch(Dispatchers.Unconfined) {
                    peripheral.observe(CONTROL) { controlOn.complete(Unit) }
                        .collect { incoming.send(decodeReply(it)) }
                },
                launch(Dispatchers.Unconfined) {
                    peripheral.observe(DATA) { dataOn.complete(Unit) }
                        .collect { incoming.send(decodeChunk(it)) }
                },
            )
            withTimeout(REPLY_TIMEOUT) {
                controlOn.await()
                dataOn.await()
            }

            val link = object : BoardLink {
                override suspend fun send(command: Command) {
                    peripheral.write(CONTROL, command.encode(), WriteType.WithResponse)
                }

                // Also the "no chunk for 5 s" rule during a GET (protocol section 9).
                override suspend fun receive(): Incoming = withTimeout(REPLY_TIMEOUT) { incoming.receive() }
            }
            block(link).also { listeners.forEach { it.cancel() } }
        }
    } finally {
        // NonCancellable: disconnect even when we got here because the caller was cancelled.
        withContext(NonCancellable) {
            runCatching { peripheral.disconnect() }
            peripheral.close()
        }
    }
}
