package io.github.duddmeister.lumen.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class GestureDecoderTest {
    private val geometry = KeyGeometry.qwerty()

    /**
     * A finger-like path through the word's keys: noisy key hits joined by smooth curves.
     * [keys] gives where a letter's keys are, for letters with more than one (a split keyboard).
     */
    private fun gesture(
        word: String, random: Random, noise: Float = 0.2f, geometry: KeyGeometry = this.geometry,
        keys: (Char) -> List<FloatArray> = { listOf(geometry.center(it)!!) },
    ): Pair<FloatArray, FloatArray> {
        val letters = GestureDecoder.pathLetters(word)!!
        val kx = FloatArray(letters.length)
        val ky = FloatArray(letters.length)
        for (i in letters.indices) {
            // A finger takes the nearer of a letter's keys.
            val c = keys(letters[i]).minBy { if (i == 0) 0f else hypot(it[0] - kx[i - 1], it[1] - ky[i - 1]) }
            kx[i] = c[0] + (random.nextFloat() * 2 - 1 + random.nextFloat() * 2 - 1) * noise * geometry.keyWidth
            ky[i] = c[1] + (random.nextFloat() * 2 - 1 + random.nextFloat() * 2 - 1) * noise * geometry.keyHeight
        }
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        fun at(i: Int, a: FloatArray) = a[i.coerceIn(0, letters.length - 1)]
        for (i in 0 until letters.length - 1) {
            val steps = 6 + random.nextInt(8)
            for (s in 0 until steps) {
                // Ease in/out: fingers slow down near keys.
                val u = s.toFloat() / steps
                val t = u * u * (3 - 2 * u)
                for ((a, out) in listOf(kx to xs, ky to ys)) {
                    val p0 = at(i - 1, a); val p1 = at(i, a); val p2 = at(i + 1, a); val p3 = at(i + 2, a)
                    val t2 = t * t; val t3 = t2 * t
                    out.add(0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3))
                }
            }
        }
        xs.add(kx.last()); ys.add(ky.last())
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun accuracy(
        words: List<String>, decoder: GestureDecoder, seed: Int,
        geometry: KeyGeometry = this.geometry, keys: (Char) -> List<FloatArray> = { listOf(geometry.center(it)!!) },
    ): Pair<Float, Float> {
        val random = Random(seed)
        var top1 = 0
        var top3 = 0
        val misses = ArrayList<String>()
        for (w in words) {
            val (xs, ys) = gesture(w, random, geometry = geometry, keys = keys)
            val result = decoder.decode(xs, ys, xs.size, geometry).map { it.text }
            val path = GestureDecoder.pathLetters(w)
            // Words with identical key paths ("to"/"too") cannot be told apart by shape alone.
            fun same(candidate: String) = candidate == w || GestureDecoder.pathLetters(candidate) == path
            if (result.isNotEmpty() && same(result[0])) top1++ else misses.add("$w->${result.take(3)}")
            if (result.take(3).any(::same)) top3++
        }
        println("misses: " + misses.take(40))
        return top1.toFloat() / words.size to top3.toFloat() / words.size
    }

    private fun topWords(count: Int, minLength: Int): List<String> {
        val all = ArrayList<Pair<String, Int>>()
        TestAssets.lexicon.forEachWord(60) { w, f ->
            val p = GestureDecoder.pathLetters(w)
            if (p != null && p.length >= 2 && w.length >= minLength && w.all { it.isLowerCase() || it == '\'' }) all.add(w to f)
        }
        return all.sortedByDescending { it.second }.take(count).map { it.first }
    }

    @Test
    fun decodesCommonWords() {
        val decoder = GestureDecoder(TestAssets.lexicon, UserHistory()).apply { buildIndex() }
        val common = topWords(400, 2)
        val (top1, top3) = accuracy(common, decoder, 7)
        println("common words: top1=%.3f top3=%.3f".format(top1, top3))
        val longer = topWords(3000, 6).shuffled(Random(3)).take(400)
        val (l1, l3) = accuracy(longer, decoder, 11)
        println("longer words: top1=%.3f top3=%.3f".format(l1, l3))
        assertTrue("common top1 $top1", top1 >= 0.80f)
        assertTrue("common top3 $top3", top3 >= 0.93f)
        assertTrue("longer top1 $l1", l1 >= 0.80f)
        assertTrue("longer top3 $l3", l3 >= 0.93f)
    }

    @Test
    fun decodesOnASplitKeyboard() {
        val decoder = GestureDecoder(TestAssets.lexicon, UserHistory()).apply { buildIndex() }
        val centers = SplitQwerty.centers()
        val split = KeyGeometry(100f, 140f, centers)
        val keys = { c: Char -> centers.getValue(c) }
        val (top1, top3) = accuracy(topWords(400, 2), decoder, 7, split, keys)
        println("split keyboard: top1=%.3f top3=%.3f".format(top1, top3))
        assertTrue("split top1 $top1", top1 >= 0.80f)
        assertTrue("split top3 $top3", top3 >= 0.93f)
        // Through the G beside the H on the right half, and the one beside the D on the left.
        for (word in listOf("high", "edge")) {
            val (xs, ys) = gesture(word, Random(1), noise = 0.1f, geometry = split, keys = keys)
            assertEquals(word, decoder.decode(xs, ys, xs.size, split).first().text)
        }
    }

    @Test
    fun decodesAStraightForwardWord() {
        val decoder = GestureDecoder(TestAssets.lexicon, UserHistory()).apply { buildIndex() }
        val (xs, ys) = gesture("hello", Random(1), noise = 0.1f)
        assertEquals("hello", decoder.decode(xs, ys, xs.size, geometry).first().text)
    }

    @Test
    fun isFastEnough() {
        val decoder = GestureDecoder(TestAssets.lexicon, UserHistory()).apply { buildIndex() }
        val random = Random(5)
        val samples = listOf("something", "the", "keyboard", "beautiful", "probably", "and").map { gesture(it, random) }
        repeat(5) { for ((xs, ys) in samples) decoder.decode(xs, ys, xs.size, geometry) }
        val start = System.nanoTime()
        repeat(10) { for ((xs, ys) in samples) decoder.decode(xs, ys, xs.size, geometry) }
        val ms = (System.nanoTime() - start) / 1e6 / (10 * samples.size)
        println("decode(): %.2f ms per gesture".format(ms))
        assertTrue("decode took $ms ms", ms < 40)
    }
}
