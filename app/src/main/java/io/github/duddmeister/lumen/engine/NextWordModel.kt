package io.github.duddmeister.lumen.engine

import java.io.BufferedReader

/**
 * Next-word predictions: a small hand-written starter set of common phrases
 * blended with the pairs learned in [UserHistory], which quickly dominate.
 */
class NextWordModel(private val seeds: Map<String, List<String>>) {

    /**
     * Predictions after [context] (previous words, most recent last; may start
     * with [SENTENCE_START]). Returns words with scores, best first.
     */
    fun predict(context: List<String>, history: UserHistory?, limit: Int): List<Pair<String, Float>> {
        val scores = LinkedHashMap<String, Float>()
        val display = HashMap<String, String>()

        fun offer(word: String, score: Float) {
            val key = CharFolding.fold(word)
            val old = scores[key]
            if (old == null || score > old) {
                scores[key] = score
                display[key] = word
            }
        }

        val last = context.lastOrNull() ?: SENTENCE_START
        val lastKey = last.lowercase()
        if (context.size >= 2) {
            val prev = context[context.size - 2].lowercase()
            seeds["$prev $lastKey"]?.forEachIndexed { i, w -> offer(w, 120f - 6f * i) }
        }
        seeds[lastKey]?.forEachIndexed { i, w -> offer(w, 90f - 5f * i) }
        if (history != null && last != SENTENCE_START) {
            for ((w, count) in history.nextWords(last, limit * 2)) offer(w, 100f + 25f * minOf(count, 8))
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { display.getValue(it.key) to it.value }
    }

    companion object {
        const val SENTENCE_START = "<s>"

        fun read(reader: BufferedReader): NextWordModel {
            val map = HashMap<String, List<String>>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[line.substring(0, tab)] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
            }
            return NextWordModel(map)
        }
    }
}
