package dev.piatra.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.piatra.core.agent.ShortcutAction

// One row of the "shortcut_rules" table (added in v7): a spoken shortcut the user can edit.
@Entity(
    tableName = "shortcut_rules",
    // CASCADE: deleting a list also deletes the rules that add to it (a rule with no list does nothing).
    foreignKeys = [ForeignKey(entity = ListEntity::class, parentColumns = ["id"], childColumns = ["listId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("listId")],
)
data class ShortcutRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // The phrases, one per line: a list in one text column, so no second table is needed.
    val phrases: String,
    val action: ShortcutAction,
    // The list for ADD_TO_LIST rules; null otherwise.
    val listId: Long? = null,
    val keepPhrase: Boolean = false,
    val enabled: Boolean = true,
)
