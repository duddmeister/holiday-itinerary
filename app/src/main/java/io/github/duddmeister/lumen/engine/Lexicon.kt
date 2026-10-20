package io.github.duddmeister.lumen.engine

import java.io.BufferedReader

/**
 * Compact, array-backed dictionary trie.
 *
 * Words are stored as paths of their display spelling ("English", "don't") while
 * all matching happens on folded characters, so lookups are case- and
 * accent-insensitive but results keep the dictionary's own spelling. Each node
 * also records the best suggestable frequency in its subtree, which turns
 * prefix completion into a cheap best-first walk.
 */
class Lexicon private constructor(
    private val chars: CharArray,
    private val folded: CharArray,
    private val parent: IntArray,
    private val firstChild: IntArray,
    private val nextSibling: IntArray,
    /** Word frequency 0..255 for word-end nodes, -1 elsewhere. */
    private val freq: ShortArray,
    private val offensive: BooleanArray,
    /** Highest suggestable frequency at or below a node, -1 when there is none. */
    private val bestBelow: ShortArray,
    /** Number of words in the lexicon. */
    val wordCount: Int,
) {
    val nodeCount: Int get() = chars.size

    fun frequency(node: Int): Int = freq[node].toInt()

    fun isOffensive(node: Int): Boolean = offensive[node]

    /** Words with frequency 0 or flagged as offensive are valid but never offered. */
    fun isSuggestable(node: Int): Boolean = freq[node] > 0 && !offensive[node]

    fun wordAt(node: Int): String {
        var depth = 0
        var n = node
        while (n > 0) { depth++; n = parent[n] }
        val out = CharArray(depth)
        n = node
        while (n > 0) { out[--depth] = chars[n]; n = parent[n] }
        return String(out)
    }

    /** Word-end node for the exact spelling, or -1. */
    fun findExact(word: CharSequence): Int {
        var node = 0
        for (c in word) {
            var child = firstChild[node]
            while (child != -1 && chars[child] != c) child = nextSibling[child]
            if (child == -1) return -1
            node = child
        }
        return if (node > 0 && freq[node] >= 0) node else -1
    }

    /** All nodes whose folded spelling equals the folded [word]; word ends only if [wordEnds]. */
    fun findFolded(word: CharSequence, wordEnds: Boolean, out: IntList) {
        if (word.isEmpty()) return
        collectFolded(0, word, 0, wordEnds, out)
    }

    private fun collectFolded(node: Int, word: CharSequence, depth: Int, wordEnds: Boolean, out: IntList) {
        if (depth == word.length) {
            if (!wordEnds || freq[node] >= 0) out.add(node)
            return
        }
        val target = CharFolding.fold(word[depth])
        var child = firstChild[node]
        while (child != -1) {
            if (folded[child] == target) collectFolded(child, word, depth + 1, wordEnds, out)
            child = nextSibling[child]
        }
    }

    /**
     * Best entry for [word] ignoring case and accents: the exact spelling when it
     * exists, otherwise the most frequent variant ("monday" finds "Monday").
     */
    fun lookup(word: CharSequence): Int {
        val exact = findExact(word)
        if (exact >= 0) return exact
        val nodes = IntList(4)
        findFolded(word, true, nodes)
        var best = -1
        for (i in 0 until nodes.size) {
            val n = nodes[i]
            if (best == -1 || freq[n] > freq[best]) best = n
        }
        return best
    }

    /**
     * Visits suggestable words below [starts] in descending frequency order
     * (start nodes themselves included) until [visit] returns false or
     * [limit] words were produced.
     */
    fun forEachCompletion(starts: IntList, limit: Int, visit: (node: Int) -> Boolean) {
        val heap = LongMaxHeap()
        for (i in 0 until starts.size) {
            val s = starts[i]
            if (bestBelow[s] >= 0) heap.push(pack(bestBelow[s].toInt(), false, s))
        }
        var produced = 0
        while (!heap.isEmpty() && produced < limit) {
            val item = heap.pop()
            val node = (item and 0xFFFFFFFFL).toInt()
            if ((item ushr 32) and 1L == 1L) {
                produced++
                if (!visit(node)) return
                continue
            }
            if (isSuggestable(node)) heap.push(pack(freq[node].toInt(), true, node))
            var child = firstChild[node]
            while (child != -1) {
                if (bestBelow[child] >= 0) heap.push(pack(bestBelow[child].toInt(), false, child))
                child = nextSibling[child]
            }
        }
    }

    private fun pack(priority: Int, isWord: Boolean, node: Int): Long =
        (priority.toLong() shl 33) or (if (isWord) 1L shl 32 else 0L) or node.toLong()

    /** Calls [block] for each suggestable word with frequency >= [minFrequency]. */
    fun forEachWord(minFrequency: Int, block: (word: String, frequency: Int) -> Unit) {
        for (node in 1 until nodeCount) {
            if (freq[node] >= minFrequency && isSuggestable(node)) block(wordAt(node), freq[node].toInt())
        }
    }

    /**
     * Weighted Damerau-Levenshtein search over the trie. Reports every word whose
     * distance to the query is within [FuzzyQuery.maxCost], and — at the first
     * node of each branch whose prefix is within [FuzzyQuery.completionCost] —
     * the best completions below it.
     */
    fun fuzzySearch(query: FuzzyQuery, collector: (node: Int, cost: Float, isCompletion: Boolean) -> Unit) {
        val n = query.input.length
        if (n == 0) return
        val rows = Array(MAX_DEPTH + 1) { FloatArray(n + 1) }
        val root = rows[0]
        root[0] = 0f
        for (i in 1..n) root[i] = root[i - 1] + query.deleteCost(i - 1)
        visit(0, 0, query, rows, collector, false)
    }

    private fun visit(
        node: Int,
        depth: Int,
        q: FuzzyQuery,
        rows: Array<FloatArray>,
        collector: (Int, Float, Boolean) -> Unit,
        completionsTaken: Boolean,
    ) {
        val n = q.input.length
        val row = rows[depth]
        var taken = completionsTaken
        if (depth > 0) {
            val cost = row[n]
            if (freq[node] >= 0 && cost <= q.maxCost) collector(node, cost, false)
            if (!taken && depth >= n && cost <= q.completionCost && bestBelow[node] >= 0) {
                taken = true
                val starts = IntList(1).apply { add(node) }
                forEachCompletion(starts, q.completionLimit + 1) { c ->
                    if (c != node) collector(c, cost, true)
                    true
                }
            }
        }
        if (depth == MAX_DEPTH) return
        val next = rows[depth + 1]
        val prevChar = if (depth > 0) folded[node] else '\u0000'
        var child = firstChild[node]
        while (child != -1) {
            val c = folded[child]
            val insert = q.insertCost(c, prevChar)
            next[0] = row[0] + insert
            var min = next[0]
            for (i in 1..n) {
                val t = q.input[i - 1]
                var v = row[i - 1] + if (t == c) 0f else q.substitutionCost(i - 1, c)
                val ins = row[i] + insert
                if (ins < v) v = ins
                val del = next[i - 1] + q.deleteCost(i - 1)
                if (del < v) v = del
                if (i > 1 && depth > 0 && t == prevChar && q.input[i - 2] == c && t != c) {
                    val tr = rows[depth - 1][i - 2] + TRANSPOSE_COST
                    if (tr < v) v = tr
                }
                next[i] = v
                if (v < min) min = v
            }
            if (min <= q.maxCost || swapWithin(c, row, q)) visit(child, depth + 1, q, rows, collector, taken)
            child = nextSibling[child]
        }
    }

    /**
     * Whether swapping [c] with the letter after it could still come in under the budget,
     * though everything else costs too much. That swap is only counted a letter later, so
     * without this "ot" would never reach "to".
     */
    private fun swapWithin(c: Char, row: FloatArray, q: FuzzyQuery): Boolean {
        for (i in 2..q.input.length) {
            if (q.input[i - 1] == c && row[i - 2] + TRANSPOSE_COST <= q.maxCost) return true
        }
        return false
    }

    companion object {
        private const val MAX_DEPTH = 48
        const val TRANSPOSE_COST = 0.6f
        /** A letter missed out of a word (measured on simulated typing; see [FuzzyQuery.insertCost]). */
        const val DROPPED_COST = 0.75f

        /** Reads "word<TAB>frequency[<TAB>flags]" lines; '#' starts a comment line. */
        fun read(reader: BufferedReader): Lexicon {
            val builder = Builder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || line[0] == '#') continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val tab2 = line.indexOf('\t', tab + 1)
                val f = (if (tab2 < 0) line.substring(tab + 1) else line.substring(tab + 1, tab2)).toIntOrNull()
                    ?: continue
                val flags = if (tab2 < 0) "" else line.substring(tab2 + 1)
                builder.add(line.substring(0, tab), f, flags.contains('o'))
            }
            return builder.build()
        }
    }

    /** Collects words in any order and builds the trie in one pass over the sorted list. */
    class Builder {
        private val words = ArrayList<String>()
        private val freqs = IntList()
        private val flags = ArrayList<Boolean>()

        fun add(word: String, frequency: Int, offensive: Boolean = false): Builder {
            if (word.isEmpty() || word.length > MAX_DEPTH) return this
            words.add(word)
            freqs.add(frequency.coerceIn(0, 255))
            flags.add(offensive)
            return this
        }

        fun build(): Lexicon {
            var sorted = true
            for (i in 1 until words.size) {
                if (words[i - 1] > words[i]) { sorted = false; break }
            }
            val order = if (sorted) IntArray(words.size) { it } else words.indices.sortedBy { words[it] }.toIntArray()

            var cap = 1024
            var chars = CharArray(cap)
            var parent = IntArray(cap)
            var firstChild = IntArray(cap)
            var nextSibling = IntArray(cap)
            var lastChild = IntArray(cap)
            var freq = ShortArray(cap)
            var offensive = BooleanArray(cap)
            var count = 1
            parent[0] = -1; firstChild[0] = -1; nextSibling[0] = -1; lastChild[0] = -1; freq[0] = -1

            val path = IntArray(MAX_DEPTH + 1)
            var previous = ""
            var wordCount = 0
            for (index in order) {
                val word = words[index]
                var common = 0
                val limit = minOf(previous.length, word.length)
                while (common < limit && previous[common] == word[common]) common++
                for (i in common until word.length) {
                    if (count == cap) {
                        cap *= 2
                        chars = chars.copyOf(cap); parent = parent.copyOf(cap)
                        firstChild = firstChild.copyOf(cap); nextSibling = nextSibling.copyOf(cap)
                        lastChild = lastChild.copyOf(cap); freq = freq.copyOf(cap)
                        offensive = offensive.copyOf(cap)
                    }
                    val p = path[i]
                    val node = count++
                    chars[node] = word[i]
                    parent[node] = p
                    firstChild[node] = -1; nextSibling[node] = -1; lastChild[node] = -1; freq[node] = -1
                    if (lastChild[p] == -1) firstChild[p] = node else nextSibling[lastChild[p]] = node
                    lastChild[p] = node
                    path[i + 1] = node
                }
                val end = path[word.length]
                val f = freqs[index]
                if (freq[end] < 0) wordCount++
                if (f > freq[end]) freq[end] = f.toShort()
                if (flags[index]) offensive[end] = true
                previous = word
            }

            val folded = CharArray(count) { CharFolding.fold(chars[it]) }
            val best = ShortArray(count) { n -> if (freq[n] > 0 && !offensive[n]) freq[n] else -1 }
            for (n in count - 1 downTo 1) {
                val p = parent[n]
                if (best[n] > best[p]) best[p] = best[n]
            }
            return Lexicon(
                chars.copyOf(count), folded, parent.copyOf(count), firstChild.copyOf(count),
                nextSibling.copyOf(count), freq.copyOf(count), offensive.copyOf(count), best, wordCount,
            )
        }
    }
}

/**
 * A fuzzy lookup: [input] holds folded typed characters; costs come from the
 * spatial model so that neighbouring keys are cheap to confuse.
 */
abstract class FuzzyQuery(
    val input: String,
    val maxCost: Float,
    val completionCost: Float,
    val completionLimit: Int,
) {
    /** Cost of reading typed character [index] as [candidate] (already known to differ). */
    abstract fun substitutionCost(index: Int, candidate: Char): Float

    /** Cost of a dictionary character the user did not type. */
    open fun insertCost(c: Char, previous: Char): Float = when {
        CharFolding.isConnector(c) -> 0.15f
        c == previous -> 0.5f
        // A tap that never registered is commoner than a stray one ("wich" is "which", "yu" is
        // "you"), so a letter left out costs less than one too many.
        input.length >= 2 -> Lexicon.DROPPED_COST
        else -> 1f
    }

    /** Cost of a typed character that is not in the dictionary word. */
    open fun deleteCost(index: Int): Float {
        val c = input[index]
        return when {
            CharFolding.isConnector(c) -> 0.3f
            index > 0 && input[index - 1] == c -> 0.5f
            else -> 1f
        }
    }
}
