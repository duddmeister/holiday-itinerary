package io.github.duddmeister.lumen.engine

import java.io.BufferedReader

/**
 * Finds emoji by name: every word of the query must start a word the emoji is
 * known by ("hea" finds hearts, "blue hea" the blue heart). Words from the
 * emoji's Unicode name rank above aliases and tags, whole words above
 * prefixes, then Unicode's own order breaks ties. A one-word query that is a
 * suggestion keyword ("lol") leads with that keyword's hand-ranked emoji.
 */
class EmojiSearch private constructor(
    private val entries: List<Entry>,
    private val featured: Map<String, List<String>>,
) {
    private class Entry(val emoji: String, val name: Array<String>, val other: Array<String>)

    val size: Int get() = entries.size

    /** Up to [limit] emoji for [query], best first; empty for a blank query. */
    fun search(query: CharSequence, limit: Int = 60): List<String> {
        val tokens = CharFolding.fold(query).split(SEPARATORS).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Int>>() // score, index
        for ((index, e) in entries.withIndex()) {
            var score = 0
            for (token in tokens) {
                val s = tokenScore(token, e)
                if (s < 0) { score = -1; break }
                score += s
            }
            if (score >= 0) scored.add(score to index)
        }
        scored.sortWith(compareBy({ it.first }, { it.second }))
        val results = LinkedHashSet<String>()
        if (tokens.size == 1) featured[tokens[0]]?.let { results.addAll(it) }
        for ((_, index) in scored) {
            if (results.size >= limit) break
            results.add(entries[index].emoji)
        }
        return results.take(limit)
    }

    /** 0 for a whole name word, 1 for a name prefix or whole other word, 2 for another prefix, -1 for no match. */
    private fun tokenScore(token: String, e: Entry): Int {
        var best = -1
        for (w in e.name) {
            if (w == token) return 0
            if (w.startsWith(token)) best = 1
        }
        for (w in e.other) {
            if (w == token) return 1
            if (best < 0 && w.startsWith(token)) best = 2
        }
        return best
    }

    companion object {
        private val SEPARATORS = Regex("[^a-z0-9']+")

        /**
         * Reads `emoji<TAB>name words<TAB>other words` lines. [featured] maps
         * keywords to hand-ranked emoji (the suggestion keywords).
         */
        fun read(reader: BufferedReader, featured: Map<String, List<String>> = emptyMap()): EmojiSearch {
            val entries = ArrayList<Entry>()
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isEmpty()) continue
                fun words(i: Int) = parts.getOrNull(i)?.split(' ')?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray()
                entries.add(Entry(parts[0], words(1), words(2)))
            }
            return EmojiSearch(entries, featured)
        }
    }
}
