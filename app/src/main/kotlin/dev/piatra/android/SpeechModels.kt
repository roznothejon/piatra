package dev.piatra.android

import dev.piatra.core.ModelState
import dev.piatra.core.SpeechModelChoice
import dev.piatra.data.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

// The speech models, one ModelManager each, and which one the settings pick.
// Each keeps its own folder, so both can be downloaded and switching is instant.
class SpeechModels(
    val managers: Map<SpeechModelChoice, ModelManager>,
    private val settings: Settings,
) {
    // The chosen model's manager. Sends a new one whenever the setting changes.
    val active: Flow<ModelManager> = settings.speechModel.map { managers.getValue(it) }

    suspend fun current(): ModelManager = active.first()

    // The chosen model's download state. flatMapLatest: follow the current manager's state,
    // and switch to the other manager's state as soon as the choice changes.
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeState: Flow<ModelState> = active.flatMapLatest { it.state }
}
