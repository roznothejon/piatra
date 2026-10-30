package dev.piatra.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.piatra.core.AppFont
import dev.piatra.core.Appearance
import dev.piatra.core.SpeechModelChoice
import dev.piatra.core.ThemeMode
import dev.piatra.core.board.BoardProtocol
import dev.piatra.core.board.BoardReading
import dev.piatra.core.board.WarningMemory
import dev.piatra.core.obsidian.ObsidianMode
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull

// One DataStore per app: a small key-value file (files/datastore/settings.preferences_pb).
// preferencesDataStore makes sure only one instance opens the file.
private val Context.dataStore by preferencesDataStore(name = "settings")

// The app's settings. DataStore reads and writes off the main thread and sends a new value
// whenever a setting changes, like Room does for tables.
class Settings(context: Context) {
    private val store = context.dataStore

    // Theme, colours and font.
    val appearance: Flow<Appearance> = store.data.map {
        Appearance(
            theme = ThemeMode.fromKey(it[THEME]),
            wallpaperColors = it[WALLPAPER_COLORS] ?: false,
            font = AppFont.fromKey(it[FONT]),
        )
    }

    suspend fun setTheme(theme: ThemeMode) {
        store.edit { it[THEME] = theme.key }
    }

    suspend fun setWallpaperColors(on: Boolean) {
        store.edit { it[WALLPAPER_COLORS] = on }
    }

    suspend fun setFont(font: AppFont) {
        store.edit { it[FONT] = font.key }
    }

    // Which speech model transcribes recordings. English until changed.
    val speechModel: Flow<SpeechModelChoice> = store.data.map { SpeechModelChoice.fromKey(it[SPEECH_MODEL]) }

    suspend fun setSpeechModel(choice: SpeechModelChoice) {
        store.edit { it[SPEECH_MODEL] = choice.key }
    }

    // "Use the model's wording". On by default: notes and reminders
    // keep the model's text when it's word for word what was said. Off: always the transcript.
    val useModelText: Flow<Boolean> = store.data.map { it[USE_MODEL_TEXT] ?: true }

    suspend fun isModelTextOn(): Boolean = useModelText.first()

    suspend fun setUseModelText(on: Boolean) {
        store.edit { it[USE_MODEL_TEXT] = on }
    }

    // Persistent reminders: on by default. A reminder that rang stays in the notification shade
    // until it's ticked off, and comes back if swiped away. Off: it goes when tapped or swiped.
    val keepReminders: Flow<Boolean> = store.data.map { it[KEEP_REMINDERS] ?: true }

    suspend fun isKeepRemindersOn(): Boolean = keepReminders.first()

    suspend fun setKeepReminders(on: Boolean) {
        store.edit { it[KEEP_REMINDERS] = on }
    }

    // How long the notification's Snooze button puts a reminder off, in minutes.
    val snoozeMinutes: Flow<Int> = store.data.map { it[SNOOZE_MINUTES] ?: DEFAULT_SNOOZE_MINUTES }

    suspend fun setSnoozeMinutes(minutes: Int) {
        store.edit { it[SNOOZE_MINUTES] = minutes }
    }

    // Off by default. Only silences the app: the board still records to a card if one is in.
    val ignoreSdCardWarning: Flow<Boolean> = store.data.map { it[IGNORE_SD_CARD_WARNING] ?: false }

    suspend fun setIgnoreSdCardWarning(on: Boolean) {
        store.edit { it[IGNORE_SD_CARD_WARNING] = on }
    }

    // Off by default.
    val sendToTasker: Flow<Boolean> = store.data.map { it[SEND_TO_TASKER] ?: false }

    suspend fun isSendToTaskerOn(): Boolean = sendToTasker.first()

    suspend fun setSendToTasker(on: Boolean) {
        store.edit { it[SEND_TO_TASKER] = on }
    }

    // The Obsidian vault folder: null until one is chosen.
    val obsidianFolder: Flow<ObsidianFolder?> = store.data.map { prefs ->
        prefs[OBSIDIAN_FOLDER]?.let { ObsidianFolder(it, prefs[OBSIDIAN_FOLDER_NAME] ?: "Obsidian vault") }
    }

    // One file per note unless "One note for all" is on.
    val obsidianMode: Flow<ObsidianMode> = store.data.map {
        if (it[OBSIDIAN_ONE_NOTE] == true) ObsidianMode.ONE_NOTE else ObsidianMode.ONE_FILE_PER_NOTE
    }

    // The "Send to Obsidian" switch. On by default once a folder is chosen; off keeps the folder.
    val sendToObsidian: Flow<Boolean> = store.data.map { it[SEND_TO_OBSIDIAN] ?: true }

    suspend fun obsidianFolderKey(): String? = obsidianFolder.first()?.key

    // True once the default shortcut rules were put in the database, so deleting
    // them all doesn't bring them back on the next start.
    suspend fun shortcutsSeeded(): Boolean = store.data.first()[SHORTCUTS_SEEDED] ?: false

    suspend fun setShortcutsSeeded() {
        store.edit { it[SHORTCUTS_SEEDED] = true }
    }

    // Where notes should go right now: the folder, if one is chosen and the switch is on.
    suspend fun activeObsidianFolderKey(): String? = obsidianFolderKey()?.takeIf { sendToObsidian.first() }

    suspend fun setSendToObsidian(on: Boolean) {
        store.edit { it[SEND_TO_OBSIDIAN] = on }
    }

    suspend fun currentObsidianMode(): ObsidianMode = obsidianMode.first()

    suspend fun setObsidianFolder(folder: ObsidianFolder?) {
        store.edit {
            if (folder == null) {
                it.remove(OBSIDIAN_FOLDER)
                it.remove(OBSIDIAN_FOLDER_NAME)
            } else {
                it[OBSIDIAN_FOLDER] = folder.key
                it[OBSIDIAN_FOLDER_NAME] = folder.name
                it[SEND_TO_OBSIDIAN] = true // choosing a folder means "send there"
            }
        }
    }

    suspend fun setObsidianOneNote(on: Boolean) {
        store.edit { it[OBSIDIAN_ONE_NOTE] = on }
    }

    // The paired board: its Bluetooth address and name, or null before pairing.
    val board: Flow<PairedBoard?> = store.data.map { prefs ->
        prefs[BOARD_ADDRESS]?.let { PairedBoard(it, prefs[BOARD_NAME] ?: BoardProtocol.DEVICE_NAME) }
    }

    suspend fun setBoard(board: PairedBoard?) {
        store.edit {
            if (board == null) {
                it.remove(BOARD_ADDRESS)
                it.remove(BOARD_NAME)
                it.remove(BOARD_READING)
                it.remove(BOARD_WARNINGS)
            } else {
                it[BOARD_ADDRESS] = board.address
                it[BOARD_NAME] = board.name
            }
        }
    }

    // The board's last status report, stored as JSON. A reading that no longer parses (the
    // fields changed in an update) counts as none.
    val boardReading: Flow<BoardReading?> = store.data.map { prefs ->
        prefs[BOARD_READING]?.let { runCatching { Json.decodeFromString<BoardReading>(it) }.getOrNull() }
    }

    suspend fun setBoardReading(reading: BoardReading) {
        store.edit { it[BOARD_READING] = Json.encodeToString(reading) }
    }

    // Which board warnings are showing, so a restart doesn't repeat them.
    val boardWarnings: Flow<WarningMemory> = store.data.map { prefs ->
        prefs[BOARD_WARNINGS]?.let { runCatching { Json.decodeFromString<WarningMemory>(it) }.getOrNull() } ?: WarningMemory()
    }

    suspend fun setBoardWarnings(memory: WarningMemory) {
        store.edit { it[BOARD_WARNINGS] = Json.encodeToString(memory) }
    }

    // The settings a backup carries, by key name. Left out: the paired board and its readings (a
    // Bluetooth bond can't move to another phone) and the Obsidian folder (Android forgets the
    // access on reinstall, so it has to be picked again).
    suspend fun backupValues(): Map<String, JsonPrimitive> =
        store.data.first().asMap()
            .filterKeys { it.name !in NOT_BACKED_UP }
            .mapNotNull { (key, value) ->
                when (value) {
                    is Boolean -> key.name to JsonPrimitive(value)
                    is Number -> key.name to JsonPrimitive(value)
                    is String -> key.name to JsonPrimitive(value)
                    else -> null
                }
            }
            .toMap()

    // The import: the backed-up settings replace this phone's (the board and Obsidian folder
    // stay as they are). DataStore keys are typed, so each value's type picks its kind of key.
    suspend fun restoreBackupValues(values: Map<String, JsonPrimitive>) {
        store.edit { prefs ->
            for (key in prefs.asMap().keys) if (key.name !in NOT_BACKED_UP) prefs.remove(key)
            for ((name, value) in values) {
                if (name in NOT_BACKED_UP) continue
                when {
                    value.isString -> prefs[stringPreferencesKey(name)] = value.content
                    value.booleanOrNull != null -> prefs[booleanPreferencesKey(name)] = value.boolean
                    value.intOrNull != null -> prefs[intPreferencesKey(name)] = value.int
                }
            }
        }
    }

    private companion object {
        val KEEP_REMINDERS = booleanPreferencesKey("keep_reminders")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val SPEECH_MODEL = stringPreferencesKey("speech_model")
        val THEME = stringPreferencesKey("theme")
        val WALLPAPER_COLORS = booleanPreferencesKey("wallpaper_colors")
        val FONT = stringPreferencesKey("font")
        val SEND_TO_TASKER = booleanPreferencesKey("send_to_tasker")
        val IGNORE_SD_CARD_WARNING = booleanPreferencesKey("ignore_sd_card_warning")
        val OBSIDIAN_FOLDER = stringPreferencesKey("obsidian_folder")
        val OBSIDIAN_FOLDER_NAME = stringPreferencesKey("obsidian_folder_name")
        val OBSIDIAN_ONE_NOTE = booleanPreferencesKey("obsidian_one_note")
        val SEND_TO_OBSIDIAN = booleanPreferencesKey("send_to_obsidian")
        val SHORTCUTS_SEEDED = booleanPreferencesKey("shortcuts_seeded")
        val USE_MODEL_TEXT = booleanPreferencesKey("use_model_text")
        val BOARD_ADDRESS = stringPreferencesKey("board_address")
        val BOARD_NAME = stringPreferencesKey("board_name")
        val BOARD_READING = stringPreferencesKey("board_reading")
        val BOARD_WARNINGS = stringPreferencesKey("board_warnings")
        val NOT_BACKED_UP = setOf(BOARD_ADDRESS, BOARD_NAME, BOARD_READING, BOARD_WARNINGS, OBSIDIAN_FOLDER, OBSIDIAN_FOLDER_NAME)
            .map { it.name }
    }
}

// The Snooze lengths Settings offers, in minutes.
val SNOOZE_CHOICES = listOf(5, 10, 15, 30, 60)
const val DEFAULT_SNOOZE_MINUTES = 10

// A chosen folder: key is the content:// tree URI as text, name is the folder's name for the screen.
data class ObsidianFolder(val key: String, val name: String)

// address: the board's Bluetooth address, e.g. "34:85:18:00:11:22".
data class PairedBoard(val address: String, val name: String)
