package io.github.duddmeister.lumen.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSearchTest {
    private val search by lazy {
        val keywords = TestAssets.reader("emoji/keywords.txt").use { SuggestionEngine.readEmojiKeywords(it) }
        TestAssets.reader("emoji/search.txt").use { EmojiSearch.read(it, keywords) }
    }

    @Test
    fun indexesEveryEmoji() {
        val emoji = TestAssets.reader("emoji/emoji.txt").useLines { lines -> lines.count { it.isNotEmpty() && !it.startsWith("@") } }
        assertEquals(emoji, search.size)
    }

    @Test
    fun findsByNameFirst() {
        assertEquals("🍕", search.search("pizza").first())
        assertEquals("🐈", search.search("cat").first { it == "🐈" })
        assertTrue("every heart colour", search.search("heart").containsAll(listOf("❤️", "💙", "💚", "💛", "💜", "🖤", "💔")))
        assertEquals(listOf("💙"), search.search("blue heart").take(1))
    }

    @Test
    fun matchesPrefixesAndEverydayWords() {
        assertTrue(search.search("piz").contains("🍕"))
        assertEquals("😂", search.search("lol").first())
        assertTrue(search.search("happy").isNotEmpty())
        // Accents and case do not matter.
        assertTrue(search.search("Piñata").contains("🪅"))
        assertTrue(search.search("FLAG united").contains("🇺🇸"))
    }

    @Test
    fun needsEveryWord() {
        assertTrue(search.search("blue heart").none { it == "❤️" })
        assertTrue(search.search("zzzz").isEmpty())
        assertTrue(search.search("   ").isEmpty())
        assertEquals(5, search.search("face", limit = 5).size)
    }
}
