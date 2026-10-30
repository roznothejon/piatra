package dev.piatra.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

// Makes a new, empty list. Only the "create a new list …" shortcut rule calls it: the agent model
// wasn't trained on it (upstream only allows it when the transcript has that exact phrase), so
// it isn't offered to the model. Definition from upstream reminders/CreateListTool.kt.
class CreateListTool(private val lists: ListStore) : Tool {
    override val name = "create_list"
    override val description = "Create a new, empty list. ONLY use this when the user's message contains the " +
        "exact phrase 'Create a new list', e.g. 'Create a new list travel' or 'Create a new list called travel'. " +
        "Never use it for any other phrasing, and never use it to add items to a list."
    override val parameters = toolSchema(
        required = listOf("list_name"),
        "list_name" to ("string" to "The name of the new list, e.g. 'travel' for 'Create a new list called travel'"),
    )
    override val offeredToModel = false

    @Serializable
    private data class Args(@SerialName("list_name") val listName: String = "")

    override suspend fun call(arguments: JsonObject, context: ToolContext) {
        val title = ToolJson.decodeFromJsonElement<Args>(arguments).listName.trim().trimEnd('.')
            .replaceFirstChar { it.uppercase() }
        require(title.isNotEmpty()) { "No name for the new list" }
        // Saying it twice shouldn't make two lists with the same name.
        if (lists.all().none { it.title.equals(title, ignoreCase = true) }) lists.create(title)
    }
}
