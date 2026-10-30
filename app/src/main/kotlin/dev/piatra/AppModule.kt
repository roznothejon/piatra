package dev.piatra

import androidx.room.Room
import dev.piatra.android.AndroidAudioPlayer
import dev.piatra.android.AndroidAudioRecorder
import dev.piatra.android.AndroidClockApp
import dev.piatra.android.CactusAgent
import dev.piatra.android.CactusTranscriptionService
import dev.piatra.android.AndroidReminderScheduler
import dev.piatra.android.ReminderAlerts
import dev.piatra.android.ModelManager
import dev.piatra.android.RecordingPlayback
import dev.piatra.android.RecordingStorage
import dev.piatra.android.SpeechModels
import dev.piatra.android.AndroidObsidianVault
import dev.piatra.android.BoardPairing
import dev.piatra.android.BoardPresence
import dev.piatra.android.BoardScanner
import dev.piatra.android.BoardRecordingSink
import dev.piatra.android.MediaCodecOpusDecoder
import dev.piatra.android.BoardHealth
import dev.piatra.android.BoardSyncManager
import dev.piatra.android.TaskerHandoff
import dev.piatra.android.BackupExport
import dev.piatra.android.BackupImport
import dev.piatra.android.MediaCodecOpusEncoder
import dev.piatra.core.AudioPlayer
import dev.piatra.core.AudioRecorder
import dev.piatra.core.ClockApp
import dev.piatra.core.Clock
import dev.piatra.core.ModelInfo
import dev.piatra.core.Models
import dev.piatra.core.SpeechModelChoice
import dev.piatra.core.RecordingProcessingQueue
import dev.piatra.core.ReminderScheduler
import dev.piatra.core.SystemClock
import dev.piatra.core.TranscriptionService
import dev.piatra.core.agent.AgentRunner
import dev.piatra.core.agent.HandoffItemStore
import dev.piatra.core.agent.ItemStore
import dev.piatra.core.agent.ListStore
import dev.piatra.core.agent.NoRepeatsItemStore
import dev.piatra.core.agent.ShortcutRuleStore
import dev.piatra.core.agent.ShortcutsFirstAgent
import dev.piatra.core.agent.piatraTools
import dev.piatra.core.agent.ToolCallJson
import dev.piatra.data.BatteryHistory
import dev.piatra.data.ItemRepository
import dev.piatra.data.ListRepository
import dev.piatra.data.ShortcutRuleRepository
import dev.piatra.data.PiatraDatabase
import dev.piatra.data.RecordingRepository
import dev.piatra.core.obsidian.ObsidianWriter
import dev.piatra.data.Settings
import kotlinx.datetime.TimeZone
import dev.piatra.ui.DeviceViewModel
import dev.piatra.ui.LabViewModel
import dev.piatra.ui.LogsViewModel
import dev.piatra.ui.ShortcutRulesViewModel
import dev.piatra.ui.ListDetailViewModel
import dev.piatra.ui.ListsViewModel
import dev.piatra.ui.MainViewModel
import dev.piatra.ui.NoteDetailViewModel
import dev.piatra.ui.NotesViewModel
import dev.piatra.ui.RecordingDetailViewModel
import dev.piatra.ui.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.io.File

// The agent model's ModelManager. Koin normally finds things by type; a "qualifier" names one
// so it can't be mixed up with others of the same type: get<ModelManager>(AGENT_MODEL).
// (The speech models' managers live inside SpeechModels.)
val AGENT_MODEL = named("agentModel")
// The ItemStore that also sends to Tasker and Obsidian (the plain one is ItemRepository).
val HANDOFF_ITEMS = named("handoffItems")

// Koin's recipes: how to build each object the app needs.
val appModule = module {
    // single: one shared instance. Asking for a Clock gives SystemClock.
    single<Clock> { SystemClock }
    // Registered under the interface, so the rest of the app doesn't know it's the Android one.
    single<AudioRecorder> { AndroidAudioRecorder() }
    single<AudioPlayer> { AndroidAudioPlayer(androidContext()) }
    // androidContext(): the Context passed to startKoin in PiatraApp.
    // Recordings are kept as Opus; the storage encodes and decodes with Android's own codecs.
    single { RecordingStorage(androidContext(), MediaCodecOpusEncoder(), MediaCodecOpusDecoder()) }
    // One player for every screen (only one recording plays at a time).
    single { RecordingPlayback(get(), get(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
    // One database for the whole app, stored as databases/piatra.db in app-private storage.
    single { Room.databaseBuilder(androidContext(), PiatraDatabase::class.java, "piatra.db").build() }
    single { get<PiatraDatabase>().recordingDao() }
    single { RecordingRepository(get(), get()) }
    single { get<PiatraDatabase>().itemDao() }
    single<ReminderScheduler> { AndroidReminderScheduler(androidContext()) }
    single { ItemRepository(get(), get(), get()) }
    single { ReminderAlerts(androidContext(), get(), get()) }
    // Tools ask for an ItemStore; they get the same ItemRepository the screen reads from.
    single<ItemStore> { get<ItemRepository>() }
    single { get<PiatraDatabase>().listDao() }
    single { ListRepository(get(), get(), get()) }
    single<ListStore> { get<ListRepository>() }
    single { get<PiatraDatabase>().shortcutRuleDao() }
    single { get<PiatraDatabase>().batteryReadingDao() }
    single { BatteryHistory(get(), get()) }
    single { ShortcutRuleRepository(get(), get(), get()) }
    single<ShortcutRuleStore> { get<ShortcutRuleRepository>() }
    single<ClockApp> { AndroidClockApp(androidContext()) }
    single { Settings(androidContext()) }
    single { TaskerHandoff(androidContext()) }
    single { AndroidObsidianVault(androidContext()) }
    single {
        val settings = get<Settings>()
        ObsidianWriter(
            vault = get<AndroidObsidianVault>(),
            folder = { settings.activeObsidianFolderKey() },
            mode = { settings.currentObsidianMode() },
            clock = get(),
            timeZone = { TimeZone.currentSystemDefault() },
        )
    }
    // The agent and the tools it may use. Shortcut rules (core/agent/ShortcutRules.kt) are tried
    // first; only if none matches does the agent model choose.
    single { CactusAgent(get(AGENT_MODEL)) }
    single<ItemStore>(HANDOFF_ITEMS) {
        val settings = get<Settings>()
        // The tools save through this store: it also hands notes and list items to Tasker while
        // "Send to Tasker" is on, and notes to the Obsidian vault once a folder is
        // chosen and "Send to Obsidian" is on. Two stores, one wrapped in the other: each adds one destination.
        // Notes typed in the app are saved through it too.
        val toTasker = HandoffItemStore(
            inner = get(),
            lists = get(),
            handoff = get<TaskerHandoff>(),
            isEnabled = { settings.isSendToTaskerOn() },
        )
        HandoffItemStore(
            inner = toTasker,
            lists = get(),
            handoff = get<ObsidianWriter>(),
            isEnabled = { settings.activeObsidianFolderKey() != null },
        )
    }
    single {
        // The runner and the tools share one NoRepeatsItemStore: the runner's clean-slate delete
        // goes through it, so "Transcribe again" starts its memory afresh too.
        val items = NoRepeatsItemStore(get(HANDOFF_ITEMS))
        val settings = get<Settings>()
        AgentRunner(
            agent = ShortcutsFirstAgent(model = get<CactusAgent>(), lists = get(), rules = get()),
            tools = piatraTools(
                items, get(), get(), get(),
                timeZone = { TimeZone.currentSystemDefault() },
                useModelText = { settings.isModelTextOn() },
            ),
            items = items,
        )
    }
    // The on-device model. Registered under the interface: the queue doesn't know which one it gets.
    single<TranscriptionService> {
        CactusTranscriptionService(get(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
    single {
        val agentRunner = get<AgentRunner>()
        RecordingProcessingQueue(
            store = get<RecordingRepository>(),
            transcriptionService = get(),
            // A lambda: run the agent, and hand back its tool calls as JSON for the recording's row.
            act = { recordingId, recordedAt, transcript ->
                ToolCallJson.encode(agentRunner.run(recordingId, recordedAt, transcript))
            },
            // App-wide scope: lives as long as the process. Default = threads for CPU-heavy work.
            // SupervisorJob: a failing coroutine doesn't cancel the others in the scope.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    // One ModelManager per model (see modelManager below). associateWith: a map from each
    // choice to a manager for its model.
    single { SpeechModels(SpeechModelChoice.entries.associateWith { modelManager(it.model) }, get()) }
    single(AGENT_MODEL) { modelManager(Models.AGENT) }
    // The agent lab's dry runs use the same agent model (and, for typed phrases, the user's
    // shortcut rules and lists); reports go to filesDir/eval/.
    viewModel {
        val settings = get<Settings>()
        LabViewModel(
            get<CactusAgent>(), File(androidContext().filesDir, "eval"), rules = get(), lists = get(),
            useModelText = { settings.isModelTextOn() },
        )
    }
    viewModel { ShortcutRulesViewModel(get(), get()) }
    single { BoardPairing(androidContext()) }
    single { BoardRecordingSink(get(), get(), get(), MediaCodecOpusDecoder()) }
    single {
        BackupExport(
            androidContext(), get(), get(), get(), get(), get(), get(), get(), get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
    single { get<PiatraDatabase>().backupDao() }
    single {
        BackupImport(
            androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
    single { BoardHealth(androidContext(), get(), get(), get()) }
    single { BoardSyncManager(androidContext(), get(), get(), get(), get(), get(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    single { BoardScanner(androidContext(), get(), get(), get(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    single { BoardPresence(androidContext(), get(), get(), get(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    viewModel { LogsViewModel() }
    viewModel { DeviceViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    // get() here also hands over the SavedStateHandle, which holds the screen's route.
    viewModel { RecordingDetailViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { ListDetailViewModel(get(), get(), get(), get(HANDOFF_ITEMS)) }
    viewModel { ListsViewModel(get(), get()) }
    viewModel { NotesViewModel(get()) }
    viewModel {
        SettingsViewModel(get(), agentModel = get(AGENT_MODEL), settings = get(), tasker = get(), obsidianVault = get(), backupExport = get(), backupImport = get())
    }
    // Its own scope: the last save runs after the screen (and its viewModelScope) is gone.
    viewModel { NoteDetailViewModel(get(), get(), get(HANDOFF_ITEMS), saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
    // viewModel: built by Koin, kept in the ViewModelStore (survives rotation).
    // Each get() asks Koin for one constructor parameter, matched by type (or by name).
    viewModel {
        MainViewModel(
            get(), get(), get(), get(), get(), get(),
            speechModels = get(),
            agentModel = get(AGENT_MODEL),
            items = get(),
            lists = get(),
            settings = get(),
            boardSync = get(),
        )
    }
}

// Models live in app-private storage, filesDir/models/<name>/.
// Scope: what a Koin recipe runs in; it's where get() and androidContext() come from.
private fun Scope.modelManager(model: ModelInfo) = ModelManager(
    model = model,
    modelsDir = File(androidContext().filesDir, "models"),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)
