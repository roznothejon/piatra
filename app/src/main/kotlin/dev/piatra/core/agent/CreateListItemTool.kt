package dev.piatra.core.agent

import dev.piatra.core.time.findReminderTime
import dev.piatra.core.time.resolveReminderTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant

// Adds an item to a list: "add eggs to my shopping list". Definition copied word for word from
// upstream (experimental/.../builtin_servlets/reminders/ListTool.kt); the call is a trimmed port.
class CreateListItemTool(
    private val items: ItemStore,
    private val lists: ListStore,
    private val timeZone: () -> TimeZone,
) : Tool {
    override val name = "create_list_item"
    override val description = "Add an item to a shopping list, grocery list, or to-do list. Use when the user " +
        "names a list or wants to buy groceries, food, or household supplies."
    override val parameters = toolSchema(
        required = listOf("list_name", "message"),
        "list_name" to ("string" to "The name of the list to add the item to e.g. 'shopping', 'todo'. " +
            "Use a short search term keyword, e.g. 'shopping' instead of 'my shopping list' to improve " +
            "matching with existing lists."),
        "message" to ("string" to "The text of the list item to add"),
        "reminder_date_time_human" to ("string" to "If provided by the user, the date and/or time to remind " +
            "the user of the list item in human readable format. Must be in English — translate it if the " +
            "user spoke another language. e.g. 'tomorrow at 13:00'"),
    )

    @Serializable
    private data class Args(
        @SerialName("list_name") val listName: String = "",
        val message: String = "",
        @SerialName("reminder_date_time_human") val reminderTime: String? = null,
        // Not offered to the model: shortcut rules name their list exactly.
        @SerialName("list_id") val listId: String? = null,
    )

    override suspend fun call(arguments: JsonObject, context: ToolContext) {
        val args = ToolJson.decodeFromJsonElement<Args>(arguments)
        // An unknown list name goes on Todo instead of making a list from a misheard word.
        // The 🤖 line still shows the name the model asked for.
        val all = lists.all()
        val listId = all.firstOrNull { it.id.toString() == args.listId }?.id
            ?: matchList(all, args.listName)?.id
            ?: lists.idForSeed(DefaultLists.TODO)
        // For lists, keep the model's item: it's usually short and right ("eggs"), and "Milk, eggs,
        // butter." arrives as one call per item, which the whole transcript couldn't express.
        var text = args.message.trim().trimEnd('.').ifEmpty { context.transcript.trim() }
        val spokenAt = Instant.fromEpochMilliseconds(context.recordedAt)
        var dueAt = args.reminderTime?.takeIf { it.isNotBlank() }?.let { resolveReminderTime(it, spokenAt, timeZone()) }
        if (dueAt == null) {
            // A time inside the item ("call mom tomorrow at nine am"): shortcut rules pass the rest of
            // the sentence as it was said, and the model sometimes leaves it there too. Make it the
            // reminder time and cut it out of the item (unlike reminders, whose text is kept as said:
            // list items are short, and the time shows next to them anyway).
            findReminderTime(text, spokenAt, timeZone())?.let { found ->
                dueAt = found.at
                if (found.rest.isNotEmpty()) text = found.rest
            }
        }
        items.addListItem(text.replaceFirstChar { it.uppercase() }, listId, dueAt?.toEpochMilliseconds(), context.recordingId)
    }
}
