package dev.piatra.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechTextTest {
    @Test
    fun `words count as speech`() {
        assertTrue(containsSpeech("Remind me to call mom at five."))
        assertTrue(containsSpeech("ok"))
    }

    @Test
    fun `blank text and stray letters are not speech`() {
        assertFalse(containsSpeech(""))
        assertFalse(containsSpeech("   "))
        assertFalse(containsSpeech("a."))
    }

    @Test
    fun `sound markers alone are not speech`() {
        assertFalse(containsSpeech("[noise]"))
        assertFalse(containsSpeech("(music) [silence]"))
        assertTrue(containsSpeech("[noise] buy milk"))
    }
}
