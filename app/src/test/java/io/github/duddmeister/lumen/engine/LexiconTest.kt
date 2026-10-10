package io.github.duddmeister.lumen.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTest {
    private val lexicon = Lexicon.Builder()
        .add("the", 222)
        .add("they", 180)
        .add("there", 190)
        .add("then", 170)
        .add("English", 150)
        .add("café", 90)
        .add("don't", 185)
        .add("damn", 120, offensive = true)
        .add("US", 0)
        .add("us", 160)
        .build()

    private fun completions(prefix: String, limit: Int = 10): List<String> {
        val starts = IntList()
        lexicon.findFolded(prefix, false, starts)
        val out = ArrayList<String>()
        lexicon.forEachCompletion(starts, limit) { out.add(lexicon.wordAt(it)); true }
        return out
    }

    @Test
    fun exactLookupKeepsDictionarySpelling() {
        assertEquals("English", lexicon.wordAt(lexicon.lookup("english")))
        assertEquals("café", lexicon.wordAt(lexicon.lookup("cafe")))
        assertEquals("us", lexicon.wordAt(lexicon.lookup("us")))
        assertEquals("US", lexicon.wordAt(lexicon.lookup("US")))
        assertEquals(-1, lexicon.lookup("thee"))
        assertEquals(10, lexicon.wordCount)
    }

    @Test
    fun completionsAreOrderedByFrequency() {
        assertEquals(listOf("the", "there", "they", "then"), completions("th"))
        assertEquals(listOf("there", "they", "then"), completions("the").drop(1))
    }

    @Test
    fun offensiveAndZeroFrequencyWordsAreNeverOffered() {
        assertEquals(emptyList<String>(), completions("da"))
        assertTrue(lexicon.lookup("damn") >= 0)
        assertFalse(lexicon.isSuggestable(lexicon.findExact("US")))
    }

    @Test
    fun fuzzySearchFindsTranspositionsAndMissingApostrophes() {
        val found = HashMap<String, Float>()
        val query = object : FuzzyQuery("teh", 1.5f, 0f, 0) {
            override fun substitutionCost(index: Int, candidate: Char) = 1f
        }
        lexicon.fuzzySearch(query) { node, cost, _ -> found[lexicon.wordAt(node)] = cost }
        assertEquals(Lexicon.TRANSPOSE_COST, found["the"]!!, 1e-4f)

        found.clear()
        val dont = object : FuzzyQuery("dont", 1f, 0f, 0) {
            override fun substitutionCost(index: Int, candidate: Char) = 1f
        }
        lexicon.fuzzySearch(dont) { node, cost, _ -> found[lexicon.wordAt(node)] = cost }
        assertEquals(0.15f, found["don't"]!!, 1e-4f)
    }

    @Test
    fun unsortedInputIsAccepted() {
        val l = Lexicon.Builder().add("zeta", 10).add("alpha", 20).add("alp", 5).build()
        assertTrue(l.findExact("alpha") >= 0)
        assertTrue(l.findExact("alp") >= 0)
        assertTrue(l.findExact("zeta") >= 0)
        assertEquals(3, l.wordCount)
    }
}
