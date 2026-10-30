package dev.piatra.core

// Speech models mark sounds that aren't words in brackets, e.g. "[noise]" or "(music)".
private val nonSpeech = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")

// Does a transcript contain actual words? Blank text, sound markers alone, or a stray letter
// mean the recording had no speech (e.g. the button was pressed in a pocket).
// Simplified from upstream's validateContainsSpeech (util/.../CactusTranscriptionService.kt).
fun containsSpeech(transcript: String): Boolean =
    transcript.replace(nonSpeech, "").count { it.isLetterOrDigit() } >= 2
