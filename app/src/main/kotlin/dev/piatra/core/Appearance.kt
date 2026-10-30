package dev.piatra.core

// How the app looks. Stored in settings by `key`, like SpeechModelChoice.

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Follow system"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class AppFont(val key: String, val label: String) {
    JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono"),
    IBM_PLEX_MONO("ibm_plex_mono", "IBM Plex Mono"),
    FIRA_MONO("fira_mono", "Fira Mono"),
    DM_MONO("dm_mono", "DM Mono"),
    SPACE_MONO("space_mono", "Space Mono"),
    COURIER_PRIME("courier_prime", "Courier Prime"),
    INTER("inter", "Inter"),
    IBM_PLEX_SANS("ibm_plex_sans", "IBM Plex Sans"),
    ATKINSON_HYPERLEGIBLE("atkinson_hyperlegible", "Atkinson Hyperlegible"),
    LORA("lora", "Lora"),
    SYSTEM("system", "System font");

    companion object {
        fun fromKey(key: String?): AppFont = entries.firstOrNull { it.key == key } ?: JETBRAINS_MONO
    }
}

// Everything the theme needs, read together so the app starts with all of it at once.
data class Appearance(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    // Colours from the wallpaper (Android 12+). Off: Piatra's own "stone" colours.
    val wallpaperColors: Boolean = false,
    val font: AppFont = AppFont.JETBRAINS_MONO,
)
