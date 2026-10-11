package io.github.duddmeister.lumen.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** English (UK): British spellings are words, preferred, and what typos turn into. */
class SpellingTest {
    private val geometry = KeyGeometry.qwerty()
    private val british = TestAssets.engine(spelling = Spelling.BRITISH)
    private val american = TestAssets.engine()

    private fun word(text: String) = TypedWord().apply { for (c in text) add(c) }

    private fun corrected(engine: SuggestionEngine, text: String) = engine.suggest(word(text), emptyList(), geometry).autoCorrection

    @Test
    fun leavesBritishSpellingsAlone() {
        val words = listOf(
            "colour", "favourite", "realise", "organise", "centre", "travelling", "grey", "theatre", "apologise",
            "neighbour", "programme", "cheque", "licence", "practise", "tyre", "jewellery", "aluminium", "labelled",
            "mum", "maths",
            // Everyday words AOSP's en_GB list leaves out stay words too.
            "connections", "rigorous", "humorous", "practices", "grams", "quarters",
            // And the American forms British English uses as well.
            "program", "meter", "license", "check", "tire", "curb",
        )
        val changed = words.filter { corrected(british, it) != null }.map { "$it -> ${corrected(british, it)}" }
        assertTrue(changed.joinToString(), changed.isEmpty())
    }

    @Test
    fun correctsToBritishSpellings() {
        val cases = mapOf(
            "colout" to "colour", "favourute" to "favourite", "realiae" to "realise", "neighbout" to "neighbour", "grwy" to "grey",
            // American spellings read as British ("gray" stays a name: Gray).
            "color" to "colour", "favorite" to "favourite", "realize" to "realise", "center" to "centre", "colors" to "colours",
        )
        val wrong = cases.filter { (typo, fix) -> corrected(british, typo) != fix }.map { (typo, fix) -> "$typo -> ${corrected(british, typo)} (wanted $fix)" }
        assertTrue(wrong.joinToString(), wrong.isEmpty())
    }

    @Test
    fun suggestsBritishFormsFirst() {
        fun first(engine: SuggestionEngine, prefix: String) = engine.suggest(word(prefix), emptyList(), geometry).candidates.first().text
        assertEquals("colour", first(british, "colo"))
        assertEquals("programme", first(british, "programm"))
        assertEquals("realised", british.predict(listOf("i", "just")).map { it.text }.first { it.startsWith("real") })
        assertEquals("mum", british.predict(listOf("my")).map { it.text }.first { it.startsWith("m") })
    }

    @Test
    fun americanSpellingIsUnchanged() {
        assertEquals("color", corrected(american, "colour"))
        assertNull(corrected(american, "color"))
        assertEquals("favorite", corrected(american, "favourite"))
    }

    @Test
    fun followsThePhonesRegion() {
        assertEquals(Spelling.BRITISH, Spelling.forRegion("GB"))
        assertEquals(Spelling.BRITISH, Spelling.forRegion("AU"))
        assertEquals(Spelling.BRITISH, Spelling.forRegion("IN"))
        assertEquals(Spelling.AMERICAN, Spelling.forRegion("US"))
        assertEquals(Spelling.AMERICAN, Spelling.forRegion("ph"))
        assertEquals(Spelling.AMERICAN, Spelling.forRegion(""))
        assertEquals(Spelling.AMERICAN, Spelling.forRegion(null))
        assertEquals(Spelling.BRITISH, Spelling.of("uk"))
    }
}
