package io.github.duddmeister.lumen.engine

import java.io.BufferedReader
import java.io.Writer
import kotlin.math.ln

/**
 * Everything Lumen learns from typing, kept on the device only: how often
 * words are used (including words the dictionary does not know) and which
 * words tend to follow each other.
 */
class UserHistory {
    class WordEntry(var display: String, var count: Int, var lastUsed: Long)
    class PairEntry(var next: String, var count: Int, var lastUsed: Long)

    private val words = HashMap<String, WordEntry>()
    private val pairs = HashMap<String, PairEntry>()

    /** Set whenever something changed since the last [write]. */
    var dirty = false
        private set

    val wordCount: Int get() = words.size

    fun entry(word: CharSequence): WordEntry? = words[CharFolding.fold(word)]

    fun count(word: CharSequence): Int = entry(word)?.count ?: 0

    /** Frequency bonus (0..60) for words the user types often. */
    fun boost(word: CharSequence): Int {
        val c = count(word)
        return if (c == 0) 0 else minOf(60, (16 * ln(1.0 + c)).toInt())
    }

    /** Records a committed word, optionally with the word typed before it. */
    fun record(word: String, previous: String?, now: Long, weight: Int = 1) {
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return
        val key = CharFolding.fold(word)
        val e = words[key]
        if (e == null) {
            words[key] = WordEntry(word, weight, now)
        } else {
            e.count += weight
            e.lastUsed = now
            // Keep the user's capitalisation unless it was just sentence-start casing.
            if (word != e.display && !(word[0].isUpperCase() && word.substring(1) == e.display.substring(1))) {
                e.display = word
            }
        }
        if (previous != null && previous.isNotEmpty()) {
            val pk = CharFolding.fold(previous) + SEPARATOR + key
            val p = pairs[pk]
            if (p == null) pairs[pk] = PairEntry(word, weight, now) else { p.count += weight; p.lastUsed = now; p.next = word }
        }
        dirty = true
        if (words.size > MAX_WORDS || pairs.size > MAX_PAIRS) prune()
    }

    /** Words most often typed after [previous], best first. */
    fun nextWords(previous: String, limit: Int): List<Pair<String, Int>> {
        val prefix = CharFolding.fold(previous) + SEPARATOR
        val result = ArrayList<Pair<String, Int>>()
        for ((k, v) in pairs) if (k.startsWith(prefix)) result.add(v.next to v.count)
        result.sortByDescending { it.second }
        return if (result.size > limit) result.subList(0, limit) else result
    }

    fun forEachWord(minCount: Int, block: (WordEntry) -> Unit) {
        for (e in words.values) if (e.count >= minCount) block(e)
    }

    fun forget(word: String) {
        val key = CharFolding.fold(word)
        if (words.remove(key) != null) dirty = true
        val iterator = pairs.entries.iterator()
        while (iterator.hasNext()) {
            val (k, _) = iterator.next()
            if (k.endsWith(SEPARATOR + key) || k.startsWith(key + SEPARATOR)) { iterator.remove(); dirty = true }
        }
    }

    fun clear() {
        words.clear()
        pairs.clear()
        dirty = true
    }

    /**
     * Adds what [other] learned. A word or pair known to both keeps the higher count, so
     * adding the same words twice changes nothing.
     */
    fun merge(other: UserHistory) {
        for ((k, e) in other.words) {
            val mine = words[k]
            if (mine == null) {
                words[k] = WordEntry(e.display, e.count, e.lastUsed)
            } else {
                mine.count = maxOf(mine.count, e.count)
                if (e.lastUsed > mine.lastUsed) {
                    mine.lastUsed = e.lastUsed
                    mine.display = e.display
                }
            }
        }
        for ((k, p) in other.pairs) {
            val mine = pairs[k]
            if (mine == null) {
                pairs[k] = PairEntry(p.next, p.count, p.lastUsed)
            } else {
                mine.count = maxOf(mine.count, p.count)
                if (p.lastUsed > mine.lastUsed) {
                    mine.lastUsed = p.lastUsed
                    mine.next = p.next
                }
            }
        }
        dirty = true
        if (words.size > MAX_WORDS || pairs.size > MAX_PAIRS) prune()
    }

    private fun prune() {
        fun <T> trim(map: HashMap<String, T>, max: Int, count: (T) -> Int, last: (T) -> Long) {
            if (map.size <= max) return
            val keep = map.entries
                .sortedWith(compareByDescending<Map.Entry<String, T>> { count(it.value) }.thenByDescending { last(it.value) })
                .take(max * 3 / 4)
                .map { it.key }
                .toHashSet()
            map.keys.retainAll(keep)
        }
        trim(words, MAX_WORDS, { it.count }, { it.lastUsed })
        trim(pairs, MAX_PAIRS, { it.count }, { it.lastUsed })
    }

    /** Writes everything learned; [saved] marks it as saved (a backup copy is not). */
    fun write(out: Writer, saved: Boolean = true) {
        out.write("# lumen user history v1\n")
        for ((_, e) in words) out.write("w\t${e.display}\t${e.count}\t${e.lastUsed}\n")
        for ((k, p) in pairs) {
            val prev = k.substringBefore(SEPARATOR)
            out.write("p\t$prev\t${p.next}\t${p.count}\t${p.lastUsed}\n")
        }
        if (saved) dirty = false
    }

    companion object {
        private const val SEPARATOR = '\u0001'
        private const val MAX_WORD_LENGTH = 40
        const val MAX_WORDS = 12_000
        const val MAX_PAIRS = 30_000

        fun read(reader: BufferedReader): UserHistory {
            val h = UserHistory()
            while (true) {
                val line = reader.readLine() ?: break
                val f = line.split('\t')
                try {
                    when (f[0]) {
                        "w" -> h.words[CharFolding.fold(f[1])] = WordEntry(f[1], f[2].toInt(), f[3].toLong())
                        "p" -> h.pairs[CharFolding.fold(f[1]) + SEPARATOR + CharFolding.fold(f[2])] =
                            PairEntry(f[2], f[3].toInt(), f[4].toLong())
                    }
                } catch (_: RuntimeException) {
                    // Skip malformed lines rather than losing the whole history.
                }
            }
            return h
        }
    }
}
