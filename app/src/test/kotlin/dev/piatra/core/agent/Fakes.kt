package dev.piatra.core.agent

// The tests' stand-ins are the agent lab's in-memory stores (core/eval/InMemoryStores.kt),
// under the names the tests have always used.
typealias FakeItemStore = dev.piatra.core.eval.InMemoryItemStore
typealias FakeListStore = dev.piatra.core.eval.InMemoryListStore
typealias FakeClockApp = dev.piatra.core.eval.RecordingClockApp
typealias FixedClock = dev.piatra.core.eval.FixedClock

// The default shortcut rules, pointing at the fake lists' Todo (1) and Shopping (2).
object FakeRuleStore : ShortcutRuleStore {
    override suspend fun all() = defaultShortcutRules(todoId = 1, shoppingId = 2)
}
