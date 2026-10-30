package dev.piatra.core.obsidian

import dev.piatra.core.Clock
import dev.piatra.core.agent.HandedOffItem
import dev.piatra.core.agent.ItemHandoff
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Writes saved notes into the Obsidian vault as Markdown files. It's a hand-off, like
// Tasker: Piatra keeps its own copy, and a failed write never fails the recording.
// Ported from upstream's ObsidianIntegration.
//
// folder: the saved vault folder, or null if none was chosen. mode: where in it notes go.
class ObsidianWriter(
    private val vault: ObsidianVault,
    private val folder: suspend () -> String?,
    private val mode: suspend () -> ObsidianMode,
    private val clock: Clock,
    private val timeZone: () -> TimeZone,
) : ItemHandoff {
    // One write at a time: two notes in the same minute could otherwise both pick the same
    // free file name, or both read Piatra.md and the second write lose the first one's note.
    private val mutex = Mutex()

    // Notes only; lists aren't written to the vault.
    override suspend fun send(item: HandedOffItem) {
        if (item.kind != "note") return
        val written = write(item.text)
        if (written != null) println("ObsidianWriter: wrote $written")
    }

    // Returns the file written, or null if nothing was (no folder, no access, or the write failed).
    suspend fun write(content: String): String? = mutex.withLock {
        val folder = folder() ?: return null
        if (!vault.hasAccess(folder)) {
            println("ObsidianWriter: no access to the vault folder; note not written")
            return null
        }
        val now = Instant.fromEpochMilliseconds(clock.nowMillis()).toLocalDateTime(timeZone())
        val name = when (val plan = ObsidianNoteFormatter.plan(mode(), content, now)) {
            is ObsidianWrite.NewFile -> {
                val dir = plan.fileName.substringBeforeLast('/', "")
                val taken = vault.listMarkdownFiles(folder, dir).map { if (dir.isEmpty()) it else "$dir/$it" }.toSet()
                val free = ObsidianNoteFormatter.dedupeName(plan.fileName) { it in taken }
                free.takeIf { vault.writeFile(folder, it, plan.content) }
            }
            is ObsidianWrite.Append -> {
                // Read, add, write back: SAF has no "append" for documents.
                val merged = ObsidianNoteFormatter.mergeAppend(vault.readFile(folder, plan.fileName), plan.block)
                plan.fileName.takeIf { vault.writeFile(folder, it, merged) }
            }
        }
        if (name == null) println("ObsidianWriter: couldn't write the note")
        name
    }
}
