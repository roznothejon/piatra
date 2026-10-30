package dev.piatra.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cactus.isCactusSupported
import dev.piatra.android.AndroidObsidianVault
import dev.piatra.android.BackupExport
import dev.piatra.android.BackupImport
import dev.piatra.android.ImportState
import dev.piatra.android.ExportState
import dev.piatra.android.ModelManager
import dev.piatra.android.SpeechModels
import dev.piatra.android.TaskerHandoff
import dev.piatra.core.AppFont
import dev.piatra.core.Appearance
import dev.piatra.core.ModelInfo
import dev.piatra.core.ModelState
import dev.piatra.core.SpeechModelChoice
import dev.piatra.core.ThemeMode
import dev.piatra.core.obsidian.ObsidianMode
import dev.piatra.data.DEFAULT_SNOOZE_MINUTES
import dev.piatra.data.ObsidianFolder
import dev.piatra.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// One model on screen: what it is and how far its download has got.
// chosen: for speech models, whether it's the one in use; null for the agent model (no choice).
data class ModelRow(val model: ModelInfo, val state: ModelState, val chosen: Boolean? = null)

// The Obsidian row, once a vault folder is chosen. hasAccess turns false if the folder is
// deleted or Android takes the access away.
// on: the "Send to Obsidian" switch.
data class ObsidianUi(val folderName: String, val on: Boolean, val mode: ObsidianMode, val hasAccess: Boolean)

// The settings screen: models, where items are also sent, and the agent lab.
// The Tasker and Obsidian parts moved here unchanged from MainViewModel.
class SettingsViewModel(
    private val speechModels: SpeechModels,
    private val agentModel: ModelManager,
    private val settings: Settings,
    private val tasker: TaskerHandoff,
    private val obsidianVault: AndroidObsidianVault,
    private val backupExport: BackupExport,
    private val backupImport: BackupImport,
) : ViewModel() {
    // Each speech model with its download state, and which one is chosen. combine: one list
    // that updates whenever the choice or any model's state changes.
    val speechRows: StateFlow<List<ModelRow>> =
        combine(speechModels.managers.map { (choice, manager) -> manager.state.map { choice to it } }) { it.toList() }
            .combine(settings.speechModel) { states, chosen ->
                states.map { (choice, state) -> ModelRow(choice.model, state, chosen = choice == chosen) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val agentRow: StateFlow<ModelRow> = agentModel.state
        .map { ModelRow(agentModel.model, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelRow(agentModel.model, agentModel.state.value))

    // Whether this phone's CPU can run the Cactus engine (asks the CPU through our C code).
    val cactusSupported: Boolean = isCactusSupported()

    fun chooseSpeechModel(model: ModelInfo) {
        val choice = SpeechModelChoice.entries.first { it.model == model }
        viewModelScope.launch { settings.setSpeechModel(choice) }
    }

    fun downloadModel(model: ModelInfo) = managerFor(model).download()
    fun cancelModelDownload(model: ModelInfo) = managerFor(model).cancel()
    fun deleteModel(model: ModelInfo) = managerFor(model).delete()

    private fun managerFor(model: ModelInfo): ModelManager =
        (speechModels.managers.values + agentModel).first { it.model == model }

    // Theme, colours and font. MainActivity follows the same setting, so a change
    // shows straight away.
    val appearance: StateFlow<Appearance> = settings.appearance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Appearance())

    fun setTheme(theme: ThemeMode) {
        viewModelScope.launch { settings.setTheme(theme) }
    }

    fun setWallpaperColors(on: Boolean) {
        viewModelScope.launch { settings.setWallpaperColors(on) }
    }

    fun setFont(font: AppFont) {
        viewModelScope.launch { settings.setFont(font) }
    }

    // "Use the model's wording": notes and reminders keep the model's text, or always the transcript.
    val useModelText: StateFlow<Boolean> = settings.useModelText
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setUseModelText(on: Boolean) {
        viewModelScope.launch { settings.setUseModelText(on) }
    }

    // Reminders: "Keep reminders until done" and the Snooze length.
    val keepReminders: StateFlow<Boolean> = settings.keepReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val snoozeMinutes: StateFlow<Int> = settings.snoozeMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SNOOZE_MINUTES)

    fun setKeepReminders(on: Boolean) {
        viewModelScope.launch { settings.setKeepReminders(on) }
    }

    fun setSnoozeMinutes(minutes: Int) {
        viewModelScope.launch { settings.setSnoozeMinutes(minutes) }
    }

    // The "Send to Tasker" switch.
    val sendToTasker: StateFlow<Boolean> = settings.sendToTasker
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setSendToTasker(on: Boolean) {
        viewModelScope.launch { settings.setSendToTasker(on) }
    }

    // Asked again each time the screen comes back, in case Tasker was installed meanwhile.
    fun isTaskerInstalled(): Boolean = tasker.isInstalled()

    // Obsidian. Access is re-checked when the screen comes back (checkObsidianAccess).
    private val obsidianAccess = MutableStateFlow(true)
    val obsidian: StateFlow<ObsidianUi?> =
        combine(settings.obsidianFolder, settings.sendToObsidian, settings.obsidianMode, obsidianAccess) { folder, on, mode, access ->
            folder?.let { ObsidianUi(it.name, on, mode, access) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun checkObsidianAccess() {
        viewModelScope.launch {
            obsidianAccess.value = settings.obsidianFolderKey()?.let { obsidianVault.hasAccess(it) } ?: true
        }
    }

    // The folder the system picker returned. Choosing another one gives the old one's key back.
    fun chooseObsidianFolder(folder: Uri) {
        viewModelScope.launch {
            val name = obsidianVault.takeAccess(folder) ?: return@launch
            val old = settings.obsidianFolderKey()
            if (old != null && old != folder.toString()) obsidianVault.releaseAccess(old)
            settings.setObsidianFolder(ObsidianFolder(folder.toString(), name))
            checkObsidianAccess()
        }
    }

    fun stopObsidian() {
        viewModelScope.launch {
            settings.obsidianFolderKey()?.let { obsidianVault.releaseAccess(it) }
            settings.setObsidianFolder(null)
        }
    }

    fun setSendToObsidian(on: Boolean) {
        viewModelScope.launch { settings.setSendToObsidian(on) }
    }

    fun setObsidianOneNote(on: Boolean) {
        viewModelScope.launch { settings.setObsidianOneNote(on) }
    }

    // Export and backup: the zip goes to the file the "Save as" dialog returned.
    val exportState: StateFlow<ExportState> = backupExport.state

    fun export(target: Uri) = backupExport.start(target)

    // Restore: read the zip the "Open" dialog returned, then replace everything once confirmed.
    val importState: StateFlow<ImportState> = backupImport.state

    fun readBackup(source: Uri) = backupImport.read(source)
    fun confirmRestore() = backupImport.confirm()
    fun dismissRestore() = backupImport.dismiss()
}
