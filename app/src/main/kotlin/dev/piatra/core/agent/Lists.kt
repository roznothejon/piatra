package dev.piatra.core.agent

// A list: Todo and Shopping are built in; others are made with "Create a new list …".
// seed: the fixed keyword of a built-in list ("todo"), so it's still found after a rename.
data class ListRef(val id: Long, val title: String, val seed: String?)

// The built-in lists (upstream: DefaultListsBootstrap). Every reminder goes on Todo; timed ones
// also ring (as upstream does with its Reminders list).
object DefaultLists {
    const val TODO = "todo"
    const val SHOPPING = "shopping"

    fun titleFor(seed: String): String = when (seed) {
        TODO -> "Todo"
        SHOPPING -> "Shopping"
        else -> seed.replaceFirstChar { it.uppercase() }
    }
}

// Lists, apart from Room (like ItemStore). The app uses ListRepository.
interface ListStore {
    suspend fun all(): List<ListRef>

    // The id of a built-in list, creating it if it's missing.
    suspend fun idForSeed(seed: String): Long

    suspend fun create(title: String): Long
}

// Finds the list the model means by its hint ("shopping", "my grocery list", "todo").
// Best title match first, then the built-in keyword (seed), then synonyms. No match -> null:
// the caller puts the item on Todo rather than creating a list from a possibly misheard word.
// Ported from upstream ListTool.matchListIdByHint and Integration.kt fuzzyFilter.
fun matchList(lists: List<ListRef>, hint: String): ListRef? {
    if (hint.isBlank()) return null
    return bestMatch(lists, hint)
        ?: hintSynonyms.firstNotNullOfOrNull { (word, alternates) ->
            if (word in hint.lowercase()) alternates.firstNotNullOfOrNull { bestMatch(lists, it) } else null
        }
}

// Spoken names that don't literally match a list's title or keyword.
// Upstream has the grocery ones; the to-do ones are Piatra's.
private val hintSynonyms = listOf(
    "groceries" to listOf("grocery", "shopping"),
    "grocery" to listOf("groceries", "shopping"),
    "to do" to listOf("todo"),
    "to-do" to listOf("todo"),
    "tasks" to listOf("todo"),
)

private fun bestMatch(lists: List<ListRef>, hint: String): ListRef? =
    fuzzyFilter(lists, hint) { it.title }.firstOrNull()
        ?: fuzzyFilter(lists, hint) { it.seed }.firstOrNull()

// Ranks lists by how well `key` (title or seed) matches the query, best first; non-matches dropped:
// 0 exact, 1 starts with it, 2 a word starts with it, 3 the title appears whole inside a longer
// query ("my grocery list" contains "grocery").
private fun fuzzyFilter(lists: List<ListRef>, query: String, key: (ListRef) -> String?): List<ListRef> {
    val q = cleanUpTitle(query).lowercase()
    if (q.isEmpty()) return emptyList()
    return lists.mapNotNull { list ->
        val t = cleanUpTitle(key(list) ?: return@mapNotNull null).lowercase()
        val idx = t.indexOf(q)
        val score = when {
            t == q -> 0
            idx == 0 -> 1
            idx > 0 && t[idx - 1] == ' ' -> 2
            q.containsPhrase(t) -> 3
            else -> return@mapNotNull null // no match, or only mid-word
        }
        // A title contained in the query takes up more of it the longer it is, so it ranks the other way.
        Triple(list, score, if (score == 3) -t.length else t.length)
    }.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
}

// "my shopping list" -> "my shopping": a trailing or leading "list" says nothing.
private fun cleanUpTitle(title: String): String =
    title.replace(Regex("(?i)^list\\s+|\\s+list$"), "").trim()

// True when `phrase` occurs in this string as whole words.
private fun String.containsPhrase(phrase: String): Boolean {
    if (phrase.isEmpty()) return false
    var from = 0
    while (true) {
        val i = indexOf(phrase, from)
        if (i < 0) return false
        val end = i + phrase.length
        if ((i == 0 || this[i - 1] == ' ') && (end == length || this[end] == ' ')) return true
        from = i + 1
    }
}
