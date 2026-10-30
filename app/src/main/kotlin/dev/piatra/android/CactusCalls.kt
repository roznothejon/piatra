package dev.piatra.android

import com.cactus.cactusStop
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Runs a blocking engine call (cactusTranscribe, cactusComplete) on the loaded model `handle`.
// If the coroutine is cancelled meanwhile (e.g. a timeout), a watcher calls cactusStop, which
// makes the engine return early. A coroutine can't interrupt C++ code by itself.
// Upstream: CactusTranscriptionService.withCactusStopOnCancel. Shared by both Cactus services.
internal suspend fun <T> runStoppable(handle: Long, block: () -> T): T = coroutineScope {
    // UNDISPATCHED: start the watcher right now, before block() takes over this thread.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation() // suspends until cancelled
        } finally {
            // Cancelled because the call is over (below), or because the outer work was?
            if (!this@coroutineScope.isActive) cactusStop(handle)
        }
    }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}
