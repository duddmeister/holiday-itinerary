package io.github.duddmeister.lumen.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {
    private val geometry = KeyGeometry.qwerty()

    private fun word(text: String) = TypedWord().apply { for (c in text) add(c) }

    private fun result(text: String, context: List<String> = emptyList(), engine: SuggestionEngine = shared) =
        engine.suggest(word(text), context, geometry)

    private fun corrected(text: String) = result(text).autoCorrection

    /**
     * [text] typed tap by tap, each squarely on its key, except the tap at [slipAt], which
     * lands [lean] of the way (0 to 1) from its key's centre towards [towards].
     */
    private fun tapped(text: String, slipAt: Int = -1, towards: Char = ' ', lean: Float = 0f) = TypedWord().apply {
        for ((i, c) in text.withIndex()) {
            val p = geometry.center(c)!!
            if (i == slipAt) {
                val q = geometry.center(towards)!!
                add(c, p[0] + lean * (q[0] - p[0]), p[1] + lean * (q[1] - p[1]))
            } else {
                add(c, p[0], p[1])
            }
        }
    }

    private fun corrected(word: TypedWord, engine: SuggestionEngine = shared) = engine.suggest(word, emptyList(), geometry).autoCorrection

    @Test
    fun correctsCommonTypos() {
        val cases = mapOf(
            "teh" to "the",
            "thw" to "the",
            "recieve" to "receive",
            "becuase" to "because",
            "definately" to "definitely",
            "wierd" to "weird",
            "somethign" to "something",
            "accomodate" to "accommodate",
            "occured" to "occurred",
            "tommorow" to "tomorrow",
            "untill" to "until",
            "beleive" to "believe",
            "freind" to "friend",
            "goverment" to "government",
            "probaly" to "probably",
        )
        val failures = cases.filter { (typo, fix) -> corrected(typo) != fix }
            .map { (typo, fix) -> "$typo -> ${corrected(typo)} (wanted $fix; ${result(typo).candidates.take(3)})" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun addsApostrophesAndFixesCapitalisation() {
        assertEquals("don't", corrected("dont"))
        assertEquals("you're", corrected("youre"))
        assertEquals("that's", corrected("thats"))
        assertEquals("I'm", corrected("im"))
        assertEquals("I", corrected("i"))
        assertEquals("a lot", corrected("alot"))
        assertEquals("Monday", corrected("monday"))
        assertEquals("English", corrected("english"))
    }

    @Test
    fun keepsTheUsersCase() {
        assertEquals("The", corrected("Teh"))
        assertEquals("THE", corrected("TEH"))
    }

    @Test
    fun leavesRealWordsAlone() {
        for (w in listOf("cat", "form", "its", "were", "ill", "lets", "id", "ok", "lol", "hmm", "cafe", "us", "pm")) {
            assertNull("$w was changed to ${corrected(w)}", corrected(w))
        }
    }

    /** A slip that lands on a rare word is put right, as one that lands on no word is. */
    @Test
    fun correctsSlipsOntoRareWords() {
        // The tap on R leaned towards T, the one on I towards O.
        assertEquals("but", corrected(tapped("bur", 2, 't', 0.4f)))
        assertEquals("not", corrected(tapped("nit", 1, 'o', 0.4f)))
        // Squarely on R: "bur" is rare enough, and "but" common enough, to correct anyway.
        assertEquals("but", corrected(tapped("bur")))
        // Two letters swapped.
        assertEquals("any", corrected(tapped("nay")))
        // A rare name is no excuse: "tue" meant "the". Names still get their capitals.
        assertEquals("the", corrected(tapped("tue", 1, 'h', 0.4f)))
        assertEquals("Monday", corrected(tapped("monday")))
    }

    /** Rare words typed on purpose, each tap on its key, stay; so do common ones, however the taps lean. */
    @Test
    fun keepsRealWordsTypedOnPurpose() {
        for (w in listOf("vet", "carer", "lime", "hut", "nook", "partied", "covet", "ream", "goof", "sob", "wee", "swat")) {
            assertNull("$w was changed to ${corrected(tapped(w))}", corrected(tapped(w)))
        }
        assertNull(corrected(tapped("bit", 1, 'u', 0.45f)))
        assertNull(corrected(tapped("form", 2, 'o', 0.45f)))
        assertNull(corrected(tapped("lime", 2, 'k', 0.45f)))
        // Without the taps to go on (a word picked up again at the cursor), a real word stays.
        assertNull(corrected("bur"))
    }

    /** A rare word the user has typed before, or put back after a correction, is theirs. */
    @Test
    fun keepsRareWordsTheUserUses() {
        val now = System.currentTimeMillis()
        val typedIt = TestAssets.engine()
        typedIt.learn("bur", null, now)
        assertNull(corrected(tapped("bur", 2, 't', 0.4f), typedIt))
        val undidIt = TestAssets.engine()
        undidIt.rejectAutoCorrection("nit", now)
        assertNull(corrected(tapped("nit", 1, 'o', 0.4f), undidIt))
    }

    @Test
    fun offersCompletionsWhileTyping() {
        val words = result("beau").candidates.map { it.text }
        assertTrue(words.toString(), "beautiful" in words)
        val th = result("th").candidates.map { it.text }
        assertTrue(th.toString(), "the" in th)
        assertNull(result("th").autoCorrection)
    }

    @Test
    fun suggestsShortcutsForRealWords() {
        val words = result("ill").candidates.map { it.text }
        assertTrue(words.toString(), "I'll" in words.take(2))
    }

    @Test
    fun predictsNextWords() {
        val afterI = shared.predict(listOf("I")).map { it.text }
        assertTrue(afterI.toString(), "am" in afterI)
        val start = shared.predict(listOf(NextWordModel.SENTENCE_START)).map { it.text }
        assertTrue(start.toString(), "I" in start)
    }

    @Test
    fun learnsNewWordsAndStopsCorrectingThem() {
        val engine = TestAssets.engine()
        val now = System.currentTimeMillis()
        engine.learn("Duddmeister", null, now)
        engine.learn("Duddmeister", null, now)
        assertNull(result("duddmeister", engine = engine).autoCorrection)
        val completions = result("dudd", engine = engine).candidates.map { it.text }
        assertTrue(completions.toString(), "Duddmeister" in completions)
    }

    @Test
    fun undoingAnAutocorrectionIsRemembered() {
        val engine = TestAssets.engine()
        val typo = "teh"
        assertEquals("the", result(typo, engine = engine).autoCorrection)
        engine.rejectAutoCorrection(typo, System.currentTimeMillis())
        assertNull(result(typo, engine = engine).autoCorrection)
    }

    @Test
    fun usesContext() {
        val result = result("yo", listOf("thank"))
        assertTrue(result.candidates.toString(), result.candidates.first().text == "you")
    }

    @Test
    fun offersEmoji() {
        assertTrue(result("pizza").emoji.contains("🍕"))
    }

    @Test
    fun isFastEnoughForEveryKeystroke() {
        val words = listOf("t", "th", "tha", "than", "thank", "thanks", "definately", "acommodation", "x")
        repeat(3) { for (w in words) result(w) } // warm up
        val start = System.nanoTime()
        val rounds = 20
        repeat(rounds) { for (w in words) result(w) }
        val perCall = (System.nanoTime() - start) / 1e6 / (rounds * words.size)
        println("suggest(): %.2f ms per call".format(perCall))
        assertTrue("suggest() took $perCall ms", perCall < 25)
    }

    companion object {
        val shared: SuggestionEngine by lazy { TestAssets.engine() }
    }
}
