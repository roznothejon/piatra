package dev.piatra.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// One row of the "lists" table (added in v6): Todo and Shopping are built in, others are made by
// the "create a new list" shortcut. Trimmed from upstream's CachedList.
@Entity(
    tableName = "lists",
    // unique: one list per built-in keyword. SQLite allows many NULLs, so user lists are fine.
    indices = [Index(value = ["seed"], unique = true)],
)
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    // The fixed keyword of a built-in list ("todo", "shopping"); null for lists the user made.
    // Matching falls back to it, so a renamed "Shopping" is still the shopping list.
    val seed: String? = null,
    val createdAt: Long,
)
