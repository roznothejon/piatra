package dev.piatra.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// One row of the "recordings" table: everything about a recording except the audio,
// which stays in a file (RecordingStorage). Trimmed from upstream's LocalRecording +
// RecordingEntryEntity, which split a note from its audio clips; we have one clip per note.
// The row is also the processing queue's job: see RecordingProcessingQueue.
@Entity(tableName = "recordings")
data class RecordingEntity(
    // autoGenerate: SQLite picks the next free number when the row is inserted (pass 0).
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Name of the audio file: filesDir/recordings/<fileId>.pcm
    val fileId: String,
    // When recording started, in epoch milliseconds.
    val createdAt: Long,
    val durationMillis: Long,
    // Room stores an enum as its name, e.g. "PENDING".
    val status: RecordingStatus = RecordingStatus.PENDING,
    // Nullable types become columns that can be NULL.
    val transcript: String? = null,
    val error: String? = null,
    // How many times the queue has started processing this recording (added in schema v2).
    // defaultValue: what the migration fills in for rows that existed before the column did.
    @ColumnInfo(defaultValue = "0")
    val attempts: Int = 0,
    // What the agent decided, as JSON: [{"name":"create_note","arguments":{...}}] (added in v4).
    // Kept even for tools that don't do anything yet, so the model's choices can be checked.
    val agentResult: String? = null,
    // Recordings from the board (added in v8): which board recording this is ("id/time/size",
    // core/board/BoardSync.kt), so one that arrives twice is recognised. Null for the phone mic.
    val boardKey: String? = null,
)

// The queue's state machine: PENDING → TRANSCRIBING → ACTING → DONE, or FAILED.
// A retryable error sends TRANSCRIBING back to PENDING; ACTING stays ACTING and is retried.
enum class RecordingStatus {
    PENDING, // saved, waiting to be transcribed
    TRANSCRIBING, // being transcribed now (still set after a crash: the job was interrupted)
    ACTING, // transcript saved; the agent is (or will be) acting on it.
    DONE, // transcribed and acted on
    FAILED, // gave up; `error` says why (the transcript is kept if there was one)
}
