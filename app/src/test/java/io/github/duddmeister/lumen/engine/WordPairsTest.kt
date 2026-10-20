package io.github.duddmeister.lumen.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class WordPairsTest {
    private val pairs = WordPairs.read(
        BufferedReader(StringReader("# word, others, then next words and scores\ngood\t-2\tmorning 12 idea 9 luck 10\n<s>\t0\ti 6 thanks 4\n")),
    )

    @Test
    fun scoresInBitsWithTheRestShared() {
        assertEquals(6f, pairs.score("good", "morning")!!, 1e-6f)
        assertEquals(4.5f, pairs.score("Good", "Idea")!!, 1e-6f)
        // A word not listed after "good" gets what the listed ones leave.
        assertEquals(-1f, pairs.score("good", "fridge")!!, 1e-6f)
        assertEquals(3f, pairs.score(WordPairs.START, "I")!!, 1e-6f)
        // Nothing to say after a word that has no line.
        assertNull(pairs.score("fridge", "good"))
        assertNull(WordPairs.EMPTY.score("good", "morning"))
    }

    @Test
    fun theShippedPairsKnowEverydayEnglish() {
        val shipped = TestAssets.pairs(Spelling.BRITISH)
        assertTrue(shipped.score("thank", "you")!! > 3f)
        assertTrue(shipped.score("thank", "you")!! > shipped.score("thank", "to")!!)
        assertTrue(shipped.score(WordPairs.START, "the")!! > shipped.score(WordPairs.START, "of")!!)
    }
}
