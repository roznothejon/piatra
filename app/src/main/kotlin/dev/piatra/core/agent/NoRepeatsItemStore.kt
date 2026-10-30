package dev.piatra.core.agent

// A safety net around the tools' ItemStore: one recording never creates the same item twice.
// Seen: "Remind me to buy milk, eggs and butter" became three identical Todo items when
// two rules, each fine alone, met three model calls. If the agent repeats itself, keep one.
// Only remembers the recording being worked on; typed items (no recording) pass straight through.
// AgentRunner must delete through this same store (AppModule, EvalRunner), so a re-run of a
// recording forgets what the last run made.
class NoRepeatsItemStore(private val inner: ItemStore) : ItemStore by inner {
    private var recordingId: Long? = null
    private val made = mutableMapOf<List<Any?>, Long>()

    override suspend fun addNote(text: String, recordingId: Long?) =
        once(recordingId, listOf("note", text)) { inner.addNote(text, recordingId) }

    override suspend fun addListItem(text: String, listId: Long, dueAt: Long?, recordingId: Long?) =
        once(recordingId, listOf("list item", text.lowercase(), listId, dueAt)) { inner.addListItem(text, listId, dueAt, recordingId) }

    override suspend fun deleteForRecording(recordingId: Long) {
        if (recordingId == this.recordingId) made.clear()
        inner.deleteForRecording(recordingId)
    }

    // `by inner` (delegation): every ItemStore function not written out above goes to inner as is.
    private suspend fun once(recordingId: Long?, key: List<Any?>, add: suspend () -> Long): Long {
        if (recordingId == null) return add()
        if (recordingId != this.recordingId) {
            this.recordingId = recordingId
            made.clear()
        }
        return made[key] ?: add().also { made[key] = it }
    }
}
