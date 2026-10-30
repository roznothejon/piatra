package dev.piatra.core

// Plain Kotlin, no Android: code that needs the time asks a Clock instead of
// reading the system time directly, so tests can pass in a fake one.
interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
