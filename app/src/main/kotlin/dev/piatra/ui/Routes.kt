package dev.piatra.ui

import kotlinx.serialization.Serializable

// The app's screens. Each one is a route: a name plus the arguments the screen needs.
// @Serializable lets Navigation save a route (and its arguments) and restore it later,
// e.g. after rotation or after Android has stopped the app in the background.

// The Recordings tab (the start screen): record button and the list of recordings.
@Serializable
data object Home

// The settings screen. Not called Settings: that name is taken by the DataStore class
// and by Android's own android.provider.Settings.
@Serializable
data object AppSettings

// The Device screen: pairing and the board's status, opened from the ☰ menu.
@Serializable
data object Device

// The app's log lines (☰ → Logs), filtered by type.
@Serializable
data object Logs

// The shortcut-rule editor, opened from the settings screen.
@Serializable
data object ShortcutRules

// The agent lab, opened from the settings screen.
@Serializable
data object Lab

// One recording, opened from the list.
@Serializable
data class RecordingDetail(val recordingId: Long)

// The Lists tab: every list, Todo first.
@Serializable
data object Lists

// The Notes tab: every note, newest first.
@Serializable
data object Notes

// One list, opened from the Lists tab.
@Serializable
data class ListDetail(val listId: Long)

// One note, opened from the Notes tab. itemId null: a new note, typed in.
@Serializable
data class NoteDetail(val itemId: Long? = null)
