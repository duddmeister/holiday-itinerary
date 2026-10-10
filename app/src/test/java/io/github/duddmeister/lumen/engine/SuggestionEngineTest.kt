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
