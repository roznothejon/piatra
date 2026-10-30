package dev.piatra.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

// The database: which tables it has and which DAOs give access to them.
// Changing an entity means bumping `version` and adding a migration (e.g. AutoMigration),
// or the app refuses to open the old database. exportSchema saves each version to app/schemas/.
@Database(
    entities = [RecordingEntity::class, ItemEntity::class, ListEntity::class, ShortcutRuleEntity::class, BatteryReadingEntity::class],
    version = 10,
    exportSchema = true,
    autoMigrations = [
        // v2: recordings.attempts. Room compares schemas/1.json and 2.json and writes the SQL.
        AutoMigration(from = 1, to = 2),
        // v3: the items table (and the ACTING status, which needs no migration: enums are text).
        AutoMigration(from = 2, to = 3),
        // v4: recordings.agentResult.
        AutoMigration(from = 3, to = 4),
        // v5: items.dueAt and items.done (reminders).
        AutoMigration(from = 4, to = 5),
        // v6: the lists table, items.listId, and REMINDER rows moved onto Todo (see the spec below).
        AutoMigration(from = 5, to = 6, spec = Migration5To6::class),
        // v7: the shortcut_rules table. Filled with the defaults on first use, not here.
        AutoMigration(from = 6, to = 7),
        // v8: recordings.boardKey.
        AutoMigration(from = 7, to = 8),
        // v9: the battery_readings table.
        AutoMigration(from = 8, to = 9),
        // v10: items.rang (persistent reminders); reminders already past count as rung.
        AutoMigration(from = 9, to = 10, spec = Migration9To10::class),
    ],
)
abstract class PiatraDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun itemDao(): ItemDao
    abstract fun listDao(): ListDao
    abstract fun shortcutRuleDao(): ShortcutRuleDao
    abstract fun batteryReadingDao(): BatteryReadingDao
    abstract fun backupDao(): BackupDao
}

// Room works out the table changes for v6 itself; a spec adds what it can't guess: moving data.
// onPostMigrate runs after the new tables and columns exist.
class Migration5To6 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO lists (title, seed, createdAt) VALUES ('Todo', 'todo', $now), ('Shopping', 'shopping', $now)")
        // Every reminder becomes an item on Todo; timed ones keep ringing.
        db.execSQL("UPDATE items SET kind = 'LIST_ITEM', listId = (SELECT id FROM lists WHERE seed = 'todo') WHERE kind = 'REMINDER'")
    }
}

// Reminders due before the update have rung already (or were skipped while the phone was off,
// as before v10); without this they'd all ring at once on the first start.
class Migration9To10 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL("UPDATE items SET rang = 1 WHERE kind = 'LIST_ITEM' AND dueAt <= $now")
    }
}
