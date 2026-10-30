package dev.piatra.core.agent

// Decides what to do with a transcript: which tools to call, with which arguments.
// The app uses CactusAgent (the on-device agent model, set in AppModule); tests use scripted fakes.
interface Agent {
    // An empty list means "nothing to do": the voice note is kept as just a transcript.
    suspend fun chooseToolCalls(transcript: String, tools: List<Tool>): List<ToolCall>
}

// Things tools create, kept apart from Room so tools and tests don't depend on the database.
// The app uses ItemRepository. Times are epoch milliseconds.
interface ItemStore {
    // recordingId: null for a note or list item typed in the app.
    suspend fun addNote(text: String, recordingId: Long?): Long

    // An item on a list. Reminders are items on Todo; with a dueAt the store also schedules
    // its alarm, so a timed item is never saved without one.
    suspend fun addListItem(text: String, listId: Long, dueAt: Long?, recordingId: Long?): Long

    // Alarms and timers live in the Clock app; these only record what was set, for the screen.
    suspend fun addAlarm(label: String, at: Long, recordingId: Long): Long
    suspend fun addTimer(label: String, endsAt: Long, recordingId: Long): Long

    // Also cancels the alarms of timed items it deletes. (Alarms and timers already handed to
    // the Clock app stay there.)
    suspend fun deleteForRecording(recordingId: Long)
}

// Runs the agent on one recording's transcript and carries out the tool calls it chooses.
// Returns those calls, so the queue can store what was decided (RecordingEntity.agentResult).
class AgentRunner(
    private val agent: Agent,
    private val tools: List<Tool>,
    private val items: ItemStore,
) {
    suspend fun run(recordingId: Long, recordedAt: Long, transcript: String): List<ToolCall> {
        // Start from a clean slate: a re-run ("Transcribe again", or a retry after a crash
        // part-way) replaces what this recording created last time instead of duplicating it.
        items.deleteForRecording(recordingId)
        val context = ToolContext(recordingId, recordedAt, transcript)
        val calls = agent.chooseToolCalls(transcript, tools)
        for (call in calls) {
            val tool = tools.firstOrNull { it.name == call.name }
            if (tool == null) {
                // A model can make up a tool name. Skip it rather than fail the whole note.
                println("AgentRunner: unknown tool ${call.name}, skipped")
                continue
            }
            tool.call(call.arguments, context.copy(decidedBy = call.decidedBy))
        }
        return calls
    }
}
