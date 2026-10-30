package dev.piatra.core

// Which speech model transcribes recordings. The settings store `key`, not the
// enum's name, so renaming an entry later doesn't lose the choice.
enum class SpeechModelChoice(val key: String, val model: ModelInfo) {
    ENGLISH("en", Models.SPEECH_ENGLISH),
    MULTILINGUAL("multi", Models.SPEECH_MULTILINGUAL);

    companion object {
        // English when nothing is stored yet, or the stored key is one we no longer know.
        fun fromKey(key: String?): SpeechModelChoice = entries.firstOrNull { it.key == key } ?: ENGLISH
    }
}
