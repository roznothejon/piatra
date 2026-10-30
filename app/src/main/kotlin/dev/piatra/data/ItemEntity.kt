package dev.piatra.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// One row of the "items" table: something the agent created from a voice note.
// One table for every kind (notes, list items, alarms, timers), so "what did this
// voice note do?" is one query. Trimmed from upstream's CachedItem (data/entity/room/indexfeed/).
@Entity(
    tableName = "items",
    // A foreign key: recordingId must be the id of an existing row in "recordings".
    // SET_NULL: deleting the recording keeps its items and just clears the link, so deleting
    // the audio never loses what you dictated.
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        // The list it's on (list items only). Deleting a list keeps its items, unlisted.
        ForeignKey(
            entity = ListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    // An index makes "items of this recording" fast, and Room asks for one on foreign keys.
    indices = [Index("recordingId"), Index("listId")],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ItemKind,
    val text: String,
    // Epoch milliseconds.
    val createdAt: Long,
    // The recording it came from; null once that recording is deleted.
    val recordingId: Long?,
    // When it goes off (epoch ms): a list item's reminder time (null: no time), or when an
    // alarm or timer rings (added in v5).
    val dueAt: Long? = null,
    // Ticked off: the notification's Done button, or tapping it on screen (added in v5).
    @ColumnInfo(defaultValue = "0")
    val done: Boolean = false,
    // LIST_ITEM: the list it's on (added in v6).
    val listId: Long? = null,
    // A reminder has gone off for its current dueAt (added in v10). After a restart, one that
    // rang is shown again silently; one that came due while the phone was off rings. A new time
    // clears it.
    @ColumnInfo(defaultValue = "0")
    val rang: Boolean = false,
)

// v6 merged REMINDER into LIST_ITEM: a reminder is an item on the Todo list (the migration moved
// the old rows). Room stores the name, so a kind is never renamed without a migration.
enum class ItemKind {
    NOTE,
    LIST_ITEM, // on a list; with a dueAt it rings (a reminder)
    ALARM, // set in the Clock app; text = its label
    TIMER, // set in the Clock app; text = its length as said
}
