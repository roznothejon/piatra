package dev.piatra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.piatra.android.AppLogs
import dev.piatra.core.LogLevel
import dev.piatra.core.LogLine
import dev.piatra.core.LogType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

// The Logs screen (☰ → Logs): the app's own log lines, filtered by type and level.
class LogsViewModel : ViewModel() {
    // Android's own lines (System) are hidden at first: there are many and they're rarely useful.
    private val _types = MutableStateFlow(LogType.entries.toSet() - LogType.SYSTEM)
    val types: StateFlow<Set<LogType>> = _types

    private val _warningsOnly = MutableStateFlow(false)
    val warningsOnly: StateFlow<Boolean> = _warningsOnly

    // Read again every 2 s while the screen is open (WhileSubscribed stops the loop after).
    private val all = flow {
        while (true) {
            emit(AppLogs.read())
            delay(2_000)
        }
    }

    val lines: StateFlow<List<LogLine>> = combine(all, _types, _warningsOnly) { lines, types, warningsOnly ->
        lines.filter { it.type in types && (!warningsOnly || it.level >= LogLevel.WARNING) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(type: LogType) {
        _types.value = if (type in _types.value) _types.value - type else _types.value + type
    }

    fun toggleWarningsOnly() {
        _warningsOnly.value = !_warningsOnly.value
    }
}
