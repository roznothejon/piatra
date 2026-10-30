package dev.piatra

import android.app.Application
import android.util.Log
import dev.piatra.android.BoardHealth
import dev.piatra.android.BoardPresence
import dev.piatra.android.BoardScanner
import dev.piatra.android.ModelManager
import dev.piatra.android.RecordingStorage
import dev.piatra.android.ReminderNotifications
import dev.piatra.android.SpeechModels
import dev.piatra.core.ModelState
import dev.piatra.core.RecordingProcessingQueue
import dev.piatra.android.ReminderAlerts
import dev.piatra.data.RecordingDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

// Created once per process, before any Activity or Service (registered in the manifest).
class PiatraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PiatraApp)
            modules(appModule)
        }
        ReminderNotifications.createChannel(this)
        BoardHealth.createChannel(this)
        // Alarms and notifications are lost if the app was force-stopped; setting them up again
        // is harmless.
        MainScope().launch { get<ReminderAlerts>().restore() }
        // Recordings kept as raw PCM (from older versions, or one whose compression
        // failed) become Opus, in the background. Usually there's nothing to do.
        CoroutineScope(Dispatchers.IO).launch {
            val converted = get<RecordingStorage>().compressAll(get<RecordingDao>().all().map { it.fileId })
            if (converted > 0) Log.i("RecordingStorage", "compressed $converted recordings to Opus")
        }
        // Finish any transcriptions left over from last time (crash, app killed, etc.).
        val queue = get<RecordingProcessingQueue>()
        queue.resume()
        // Automatic sync: listen for the paired board's adverts, two ways.
        get<BoardScanner>().start()
        get<BoardPresence>().start()
        // When a model finishes downloading, process the recordings that were waiting for it
        // right away, instead of at their next retry. onEach runs for every new state;
        // launchIn collects the flow in a coroutine that lives as long as the app.
        // The speech model's state follows the Settings choice, so switching to a model that's
        // already downloaded counts too.
        for (state in listOf(get<SpeechModels>().activeState, get<ModelManager>(AGENT_MODEL).state)) {
            state
                .onEach { if (it == ModelState.Ready) queue.retryPending() }
                .launchIn(MainScope())
        }
    }
}
