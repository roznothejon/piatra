package dev.piatra.android

import android.os.Process
import dev.piatra.core.LogLine
import dev.piatra.core.parseLogcatLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Reads this app's own lines from Android's log, the same lines `adb logcat` shows.
// An app may read its own process's lines without any permission (--pid). Android keeps only
// the most recent lines, and nothing from before the app last started. Lines from other
// processes aren't visible, e.g. the Bluetooth stack's own messages (those need adb).
object AppLogs {
    suspend fun read(maxLines: Int = 2000): List<LogLine> = withContext(Dispatchers.IO) {
        // -d: print what's there and exit; -t: only the last N lines; threadtime: the format
        // parseLogcatLine understands.
        val command = arrayOf("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", "$maxLines")
        val process = Runtime.getRuntime().exec(command)
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.mapNotNull(::parseLogcatLine).toList() }
        } finally {
            process.destroy()
        }
    }
}
