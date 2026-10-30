package dev.piatra.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.piatra.core.agent.HandedOffItem
import dev.piatra.core.agent.ItemHandoff
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Hands saved items to Tasker as a broadcast: a message to other apps that opens no screen, so
// it also works while Piatra runs in the background. In Tasker, a profile with the event
// "Intent Received" (action dev.piatra.action.NEW_ITEM) catches it, and each extra becomes a
// local variable: %kind, %text, %list, %due. Set-up: docs/tasker.md.
//
// Upstream (experimental/.../tasker/TaskerEndpoint.kt) opens Tasker with a "share" intent
// instead, which Android may block from the background.
class TaskerHandoff(private val context: Context) : ItemHandoff {

    // Needs the <queries> entry in the manifest; without it Tasker always looks missing.
    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TASKER_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override suspend fun send(item: HandedOffItem) {
        if (!isInstalled()) return
        val intent = Intent(ACTION_NEW_ITEM).apply {
            // Only Tasker receives it, not every app listening for this action.
            setPackage(TASKER_PACKAGE)
            putExtra("kind", item.kind)
            putExtra("text", item.text)
            putExtra("list", item.list.orEmpty())
            // Phone-local time, e.g. 2026-10-22T17:00. Empty when there's no time.
            putExtra("due", item.dueAt?.let { localTime(it) }.orEmpty())
        }
        context.sendBroadcast(intent)
        Log.i("TaskerHandoff", "Sent ${item.kind} to Tasker (${item.text.length} chars)")
    }

    private fun localTime(epochMillis: Long): String =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault()).toString()

    companion object {
        const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
        const val ACTION_NEW_ITEM = "dev.piatra.action.NEW_ITEM"
    }
}
