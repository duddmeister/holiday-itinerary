package io.github.duddmeister.lumen.engine

import java.io.BufferedReader

/**
 * How much likelier one word is right after another than in general, learned from a few
 * million words of everyday English (see tools/build_bigrams.py): after "thank", "tou" is
 * "you" rather than "to", and after "I", "jeep" is "keep".
 *
 * Words are interned once; each first word keeps its next words sorted by id for lookup.
 */
class WordPairs private constructor(
    private val ids: HashMap<String, Int>,
    private val lines: Array<Line?>,
) {
    private class Line(val others: Byte, val next: IntArray, val scores: ByteArray)

    /**
     * log2 of how much likelier [next] is after [first] than in general, in bits; null when
     * [first] isn't known well enough to say. Both are matched in lower case.
     */
    fun score(first: String, next: String): Float? {
        val line = ids[first.lowercase()]?.let { lines[it] } ?: return null
        val id = ids[next.lowercase()] ?: return line.others / 2f
        val i = line.next.binarySearch(id)
        return (if (i >= 0) line.scores[i] else line.others) / 2f
    }

    companion object {
        /** The first word standing for the start of a sentence. */
        const val START = "<s>"

        val EMPTY = WordPairs(HashMap(), emptyArray())

        /** Reads "word<TAB>others<TAB>next score next score ..." lines; "#" starts a comment. */
        fun read(reader: BufferedReader): WordPairs {
            val ids = HashMap<String, Int>()
            fun id(word: String) = ids.getOrPut(word) { ids.size }
            val firsts = IntList()
            val read = ArrayList<Line>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || line[0] == '#') continue
                val t1 = line.indexOf('\t')
                val t2 = line.indexOf('\t', t1 + 1)
                if (t1 <= 0 || t2 < 0) continue
                val others = line.substring(t1 + 1, t2).toIntOrNull() ?: continue
                val parts = line.substring(t2 + 1).split(' ')
                val count = parts.size / 2
                val pairs = LongArray(count)
                for (k in 0 until count) {
                    val score = parts[2 * k + 1].toIntOrNull() ?: 0
                    pairs[k] = (id(parts[2 * k]).toLong() shl 8) or (score.toLong() and 0xFF)
                }
                pairs.sort()
                firsts.add(id(line.substring(0, t1)))
                read.add(Line(others.toByte(), IntArray(count) { (pairs[it] shr 8).toInt() }, ByteArray(count) { pairs[it].toByte() }))
            }
            val lines = arrayOfNulls<Line>(ids.size)
            for (k in 0 until firsts.size) lines[firsts[k]] = read[k]
            return WordPairs(ids, lines)
        }
    }
}
