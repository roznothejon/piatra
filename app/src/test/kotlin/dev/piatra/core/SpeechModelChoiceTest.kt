package dev.piatra.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechModelChoiceTest {
    @Test
    fun `stored keys come back as the same choice`() {
        for (choice in SpeechModelChoice.entries) assertEquals(choice, SpeechModelChoice.fromKey(choice.key))
    }

    @Test
    fun `nothing stored or an unknown key means English`() {
        assertEquals(SpeechModelChoice.ENGLISH, SpeechModelChoice.fromKey(null))
        assertEquals(SpeechModelChoice.ENGLISH, SpeechModelChoice.fromKey("whisper"))
    }
}
