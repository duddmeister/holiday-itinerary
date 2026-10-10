package io.github.duddmeister.lumen.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextContextTest {
    @Test
    fun extractsWordsAroundTheCursor() {
        assertEquals("world", TextContext.wordBeforeCursor("hello world"))
        assertEquals("", TextContext.wordBeforeCursor("hello "))
        assertEquals("don't", TextContext.wordBeforeCursor("I don't"))
        assertEquals("quote", TextContext.wordBeforeCursor("a 'quote"))
    }

    @Test
    fun buildsPredictionContext() {
        assertEquals(listOf("I", "think"), TextContext.contextWords("I think "))
        assertEquals(listOf(NextWordModel.SENTENCE_START, "How"), TextContext.contextWords("Hi there. How "))
        assertEquals(listOf(NextWordModel.SENTENCE_START), TextContext.contextWords(""))
        assertEquals(listOf(NextWordModel.SENTENCE_START), TextContext.contextWords("Done! "))
        assertEquals(listOf(NextWordModel.SENTENCE_START, "Thanks"), TextContext.contextWords("Thanks "))
    }

    @Test
    fun detectsSentenceStarts() {
        assertTrue(TextContext.atSentenceStart(""))
        assertTrue(TextContext.atSentenceStart("Hello. "))
        assertTrue(TextContext.atSentenceStart("He said \"hi.\" "))
        assertTrue(TextContext.atSentenceStart("Line one\n"))
        assertFalse(TextContext.atSentenceStart("Hello."))
        assertFalse(TextContext.atSentenceStart("Hello "))
        assertFalse(TextContext.atSentenceStart("3.5"))
    }
}
