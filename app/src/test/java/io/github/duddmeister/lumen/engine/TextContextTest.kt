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
    fun jumpsWordByWord() {
        // Back to the start of each word, skipping spaces and punctuation.
        assertEquals(-5, TextContext.wordJump("hello there", "", -1))
        assertEquals(-12, TextContext.wordJump("Hi, how are ", "", -3))
        assertEquals(-5, TextContext.wordJump("I don't", "", -1))
        // Forward to the end of each word.
        assertEquals(3, TextContext.wordJump("", "you doing?", 1))
        assertEquals(10, TextContext.wordJump("", " you doing?", 2))
        // Emoji are skipped whole, never split.
        assertEquals(-6, TextContext.wordJump("hi 😀 ", "", -1))
        // Nowhere to go at the ends of the text.
        assertEquals(0, TextContext.wordJump("", "", -1))
        assertEquals(2, TextContext.wordJump("", "ok", 3))
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
