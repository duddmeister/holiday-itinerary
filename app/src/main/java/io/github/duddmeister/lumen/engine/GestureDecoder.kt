package io.github.duddmeister.lumen.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Glide typing: turns a finger path across the keyboard into words.
 *
 * Candidates are words whose first and last letters lie near the start and end
 * of the path. Each candidate's ideal path (through its key centers) is
 * compared with the gesture on three channels:
 *  - alignment: does the gesture pass near every letter, in order?
 *  - location: how far apart are the two paths, point for point?
 *  - shape:    how similar are they after normalising position and size?
 * The geometric cost is then blended with word frequency.
 */
class GestureDecoder(private val lexicon: Lexicon, private val history: UserHistory) {

    private class Bucket(val words: Array<String>, val freqs: IntArray)

    @Volatile private var index: Map<Int, Bucket> = emptyMap()

    /** The user's own words worth gliding, captured where the history is safe to read. */
    fun learnedWords(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        history.forEachWord(SuggestionEngine.LEARNED_THRESHOLD) { e ->
            if (lexicon.lookup(e.display) < 0) out.add(e.display to SuggestionEngine.LEARNED_BASE_FREQ + history.boost(e.display))
        }
        return out
    }

    /**
     * Builds the candidate index from the dictionary plus [learned] words. Takes
     * a moment, so call it off the main thread.
     */
    fun buildIndex(learned: List<Pair<String, Int>> = learnedWords(), minFrequency: Int = INDEX_MIN_FREQUENCY) {
        val words = HashMap<Int, ArrayList<String>>()
        val freqs = HashMap<Int, IntList>()
        fun add(word: String, freq: Int) {
            val letters = pathLetters(word) ?: return
            if (letters.length < 2) return
            val key = bucketKey(letters.first(), letters.last())
            words.getOrPut(key) { ArrayList() }.add(word)
            freqs.getOrPut(key) { IntList() }.add(freq)
        }
        lexicon.forEachWord(minFrequency, ::add)
        for ((word, freq) in learned) add(word, freq)
        index = words.mapValues { (k, list) ->
            val f = freqs.getValue(k)
            Bucket(list.toTypedArray(), IntArray(f.size) { f[it] })
        }
    }

    private class Scored(val word: String, val score: Float)

    /**
     * Decodes [count] gesture points. Returns up to [limit] words, best first, or
     * an empty list when nothing plausible matches.
     */
    fun decode(xs: FloatArray, ys: FloatArray, count: Int, geometry: KeyGeometry, limit: Int = 5): List<Suggestion> {
        if (count < 2 || index.isEmpty()) return emptyList()
        val gx = FloatArray(SAMPLES)
        val gy = FloatArray(SAMPLES)
        resample(xs, ys, count, gx, gy)
        var gestureLength = 0f
        for (i in 1 until count) gestureLength += geometry.distanceInKeys(xs[i - 1], ys[i - 1], xs[i], ys[i])
        if (gestureLength < 0.6f) return emptyList()

        val starts = geometry.nearestLetters(xs[0], ys[0], 3, 1.25f)
        val ends = geometry.nearestLetters(xs[count - 1], ys[count - 1], 3, 1.25f)
        val gShape = normalise(gx, gy)

        val px = FloatArray(MAX_LETTERS)
        val py = FloatArray(MAX_LETTERS)
        val ix = FloatArray(SAMPLES)
        val iy = FloatArray(SAMPLES)
        val dp = FloatArray(SAMPLES)
        val best = ArrayList<Scored>()
        var worstKept = Float.NEGATIVE_INFINITY

        for (s in starts) for (e in ends) {
            val bucket = index[bucketKey(s, e)] ?: continue
            for (w in bucket.words.indices) {
                val word = bucket.words[w]
                val letters = pathLetters(word) ?: continue
                if (letters.length > MAX_LETTERS) continue
                var idealLength = 0f
                var onLayout = true
                for (i in letters.indices) {
                    // A letter on both halves of a split keyboard: the key nearest the path so far.
                    val c = if (i == 0) geometry.center(letters[0], xs[0], ys[0]) else geometry.center(letters[i], px[i - 1], py[i - 1])
                    if (c == null) { onLayout = false; break }
                    px[i] = c[0]; py[i] = c[1]
                    if (i > 0) idealLength += geometry.distanceInKeys(px[i - 1], py[i - 1], px[i], py[i])
                }
                if (!onLayout) continue
                if (idealLength > 1.5f && (gestureLength < idealLength * 0.55f || gestureLength > idealLength * 2.6f + 1.5f)) continue

                val align = alignment(letters.length, px, py, gx, gy, geometry, dp)
                if (align > MAX_ALIGNMENT) continue
                resample(px, py, letters.length, ix, iy)
                var location = 0f
                for (j in 0 until SAMPLES) location += geometry.distanceInKeys(gx[j], gy[j], ix[j], iy[j])
                location /= SAMPLES
                val shape = shapeDistance(gShape, ix, iy)

                val freq = bucket.freqs[w] + history.boost(word)
                val cost = ALIGN_WEIGHT * align + LOCATION_WEIGHT * location + SHAPE_WEIGHT * shape
                val score = -cost + FREQ_WEIGHT * freq
                if (best.size < limit * 3 || score > worstKept) {
                    best.add(Scored(word, score))
                    if (best.size > limit * 6) {
                        best.sortByDescending { it.score }
                        while (best.size > limit * 3) best.removeAt(best.size - 1)
                        worstKept = best.last().score
                    }
                }
            }
        }
        best.sortByDescending { it.score }
        val seen = HashSet<String>()
        return best.asSequence()
            .filter { seen.add(it.word) }
            .take(limit)
            .map { Suggestion(it.word, Suggestion.Kind.CORRECTION, it.score) }
            .toList()
    }

    /** Mean distance (in keys) from each letter to the gesture, matched in order; ends are pinned. */
    private fun alignment(
        letters: Int, px: FloatArray, py: FloatArray,
        gx: FloatArray, gy: FloatArray, geometry: KeyGeometry, dp: FloatArray,
    ): Float {
        val last = SAMPLES - 1
        // Letter 0 is pinned to the first sample; dp[j] = best cost with the previous letter at or before j.
        dp.fill(geometry.distanceInKeys(px[0], py[0], gx[0], gy[0]))
        for (i in 1 until letters) {
            var running = Float.MAX_VALUE
            for (j in 0..last) {
                if (dp[j] < running) running = dp[j]
                dp[j] = running + min(3f, geometry.distanceInKeys(px[i], py[i], gx[j], gy[j]))
            }
        }
        // The last letter is pinned to the last sample.
        val lastLetter = letters - 1
        val endMiss = geometry.distanceInKeys(px[lastLetter], py[lastLetter], gx[last], gy[last])
        val pinned = dp[last] - min(3f, endMiss) + endMiss
        return pinned / letters
    }

    private fun shapeDistance(g: FloatArray, ix: FloatArray, iy: FloatArray): Float {
        val ideal = normalise(ix, iy)
        var sum = 0f
        for (j in 0 until SAMPLES) {
            val dx = g[2 * j] - ideal[2 * j]
            val dy = g[2 * j + 1] - ideal[2 * j + 1]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / SAMPLES
    }

    companion object {
        const val SAMPLES = 48
        private const val MAX_LETTERS = 32
        private const val INDEX_MIN_FREQUENCY = 60
        private const val MAX_ALIGNMENT = 1.6f
        private const val ALIGN_WEIGHT = 1.6f
        private const val LOCATION_WEIGHT = 0.9f
        private const val SHAPE_WEIGHT = 0.8f
        private const val FREQ_WEIGHT = 0.0045f

        private fun bucketKey(first: Char, last: Char) = (first.code shl 16) or last.code

        /** Folded letters a gesture has to visit: "Hello" -> "helo", "don't" -> "dont"; null if not glideable. */
        fun pathLetters(word: String): String? {
            val out = StringBuilder(word.length)
            for (ch in word) {
                val c = CharFolding.fold(ch)
                if (c == '\'' || c == '-' || c == '’') continue
                if (c !in 'a'..'z') return null
                if (out.isEmpty() || out[out.length - 1] != c) out.append(c)
            }
            return out.toString()
        }

        /** Resamples a polyline to [SAMPLES] equidistant points; returns its length in pixels. */
        fun resample(xs: FloatArray, ys: FloatArray, count: Int, outX: FloatArray, outY: FloatArray): Float {
            var total = 0f
            for (i in 1 until count) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            if (total == 0f) {
                outX.fill(xs[0]); outY.fill(ys[0])
                return 0f
            }
            val step = total / (SAMPLES - 1)
            outX[0] = xs[0]; outY[0] = ys[0]
            var written = 1
            var carried = 0f
            var i = 1
            var ax = xs[0]
            var ay = ys[0]
            while (i < count && written < SAMPLES) {
                val bx = xs[i]
                val by = ys[i]
                val seg = hypot(bx - ax, by - ay)
                if (carried + seg >= step && seg > 0f) {
                    val t = (step - carried) / seg
                    ax += (bx - ax) * t
                    ay += (by - ay) * t
                    outX[written] = ax; outY[written] = ay
                    written++
                    carried = 0f
                } else {
                    carried += seg
                    ax = bx; ay = by
                    i++
                }
            }
            while (written < SAMPLES) {
                outX[written] = xs[count - 1]; outY[written] = ys[count - 1]
                written++
            }
            return total
        }

        /** Centers a sampled path on its centroid and scales its larger side to 1. Returns interleaved x,y. */
        private fun normalise(xs: FloatArray, ys: FloatArray): FloatArray {
            var cx = 0f; var cy = 0f
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (j in 0 until SAMPLES) {
                cx += xs[j]; cy += ys[j]
                minX = min(minX, xs[j]); maxX = max(maxX, xs[j])
                minY = min(minY, ys[j]); maxY = max(maxY, ys[j])
            }
            cx /= SAMPLES; cy /= SAMPLES
            val scale = max(max(maxX - minX, maxY - minY), 1f)
            val out = FloatArray(SAMPLES * 2)
            for (j in 0 until SAMPLES) {
                out[2 * j] = (xs[j] - cx) / scale
                out[2 * j + 1] = (ys[j] - cy) / scale
            }
            return out
        }

        private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)
    }
}
