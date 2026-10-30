package dev.piatra.core

// One line of the app's log, as shown on the Logs screen. Android keeps the log itself
// (logcat); android/AppLogs.kt reads it, this file only makes sense of the text.

enum class LogLevel { VERBOSE, DEBUG, INFO, WARNING, ERROR }

// What a line is about, from its tag. Each tag the app logs under belongs to one type;
// everything else (Android's own lines inside our process) is SYSTEM.
enum class LogType(val label: String, val tags: Set<String>) {
    BOARD("Board", setOf("PiatraBoard", "BluetoothGatt")),
    TRANSCRIPTION("Transcription", setOf("CactusTranscription")),
    AGENT("Agent", setOf("CactusAgent", "AgentLab", "PiatraLab")),
    MODELS("Models", setOf("ModelManager")),
    REMINDERS("Reminders", setOf("ReminderReceiver", "BootReceiver")),
    HANDOFF("Tasker & Obsidian", setOf("TaskerHandoff", "AndroidObsidianVault")),
    PLAYBACK("Playback", setOf("RecordingPlayback")),
    SYSTEM("System", emptySet());

    companion object {
        fun of(tag: String): LogType = entries.firstOrNull { tag in it.tags } ?: SYSTEM
    }
}

data class LogLine(val time: String, val level: LogLevel, val tag: String, val message: String) {
    val type: LogType get() = LogType.of(tag)
}

// logcat's "threadtime" format:
//   10-23 17:56:47.009 18533 18586 I PiatraBoard: GET_END 41: 67840/67840 bytes …
// date, time, process id, thread id, level letter, tag, then the message.
private val THREADTIME = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.*?)\s*: (.*)$""")

// null for lines that aren't log entries, like "--------- beginning of main".
fun parseLogcatLine(line: String): LogLine? {
    val m = THREADTIME.matchEntire(line) ?: return null
    val (time, letter, tag, message) = m.destructured
    val level = when (letter) {
        "V" -> LogLevel.VERBOSE
        "D" -> LogLevel.DEBUG
        "I" -> LogLevel.INFO
        "W" -> LogLevel.WARNING
        else -> LogLevel.ERROR // E and F (fatal)
    }
    return LogLine(time, level, tag, message)
}
