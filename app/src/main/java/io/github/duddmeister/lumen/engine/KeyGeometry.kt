package io.github.duddmeister.lumen.engine

import kotlin.math.sqrt

/**
 * Where each letter sits on the current layout, in pixels. Shared by the tap
 * spatial model (autocorrect) and the glide-typing decoder.
 */
class KeyGeometry(
    val keyWidth: Float,
    val keyHeight: Float,
    private val centers: Map<Char, FloatArray>,
) {
    /** Center of the key that types [c] (folded), or null when the layout has none. */
    fun center(c: Char): FloatArray? = centers[CharFolding.fold(c)]

    /** Distance between two points measured in key widths (rows weighted by key aspect). */
    fun distanceInKeys(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = (x1 - x2) / keyWidth
        val dy = (y1 - y2) / keyHeight
        return sqrt(dx * dx + dy * dy)
    }

    /** The letters closest to a point, nearest first. */
    fun nearestLetters(x: Float, y: Float, maxCount: Int, maxDistanceKeys: Float): List<Char> {
        val scored = ArrayList<Pair<Char, Float>>()
        for ((c, p) in centers) {
            val d = distanceInKeys(x, y, p[0], p[1])
            if (d <= maxDistanceKeys) scored.add(c to d)
        }
        scored.sortBy { it.second }
        return scored.take(maxCount).map { it.first }
    }

    companion object {
        /** A plain QWERTY grid, handy for tests and as a fallback before layout. */
        fun qwerty(keyWidth: Float = 100f, keyHeight: Float = 140f): KeyGeometry {
            val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val offsets = floatArrayOf(0f, 0.5f, 1.5f)
            val map = HashMap<Char, FloatArray>()
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { i, c ->
                    map[c] = floatArrayOf((offsets[r] + i + 0.5f) * keyWidth, (r + 0.5f) * keyHeight)
                }
            }
            return KeyGeometry(keyWidth, keyHeight, map)
        }
    }
}

/** A word as it is being typed: characters plus where each one was tapped. */
class TypedWord {
    private val text = StringBuilder()
    private var xs = FloatArray(16)
    private var ys = FloatArray(16)

    val length: Int get() = text.length
    val isEmpty: Boolean get() = text.isEmpty()
    val isNotEmpty: Boolean get() = text.isNotEmpty()
    val chars: CharSequence get() = text

    override fun toString(): String = text.toString()

    /** Appends a character; pass NaN coordinates when there was no tap (e.g. restored words). */
    fun add(c: Char, x: Float = Float.NaN, y: Float = Float.NaN) {
        if (text.length == xs.size) {
            xs = xs.copyOf(xs.size * 2)
            ys = ys.copyOf(ys.size * 2)
        }
        xs[text.length] = x
        ys[text.length] = y
        text.append(c)
    }

    fun set(word: CharSequence) {
        clear()
        for (c in word) add(c)
    }

    fun removeLast(count: Int = 1) {
        text.setLength(maxOf(0, text.length - count))
    }

    fun clear() {
        text.setLength(0)
    }

    fun tapX(index: Int): Float = xs[index]
    fun tapY(index: Int): Float = ys[index]

    val isCapitalized: Boolean get() = text.isNotEmpty() && text[0].isUpperCase()

    val isAllCaps: Boolean
        get() {
            var letters = 0
            for (c in text) {
                if (c.isLetter()) {
                    if (!c.isUpperCase()) return false
                    letters++
                }
            }
            return letters > 1
        }
}

/**
 * Tap spatial model: substituting a typed letter with a neighbour is cheap when
 * the tap landed near that neighbour, and expensive when it was dead center on
 * the typed key or the neighbour is far away.
 */
class SpatialQuery(
    private val word: TypedWord,
    private val geometry: KeyGeometry?,
    input: String,
    maxCost: Float,
    completionCost: Float,
    completionLimit: Int,
) : FuzzyQuery(input, maxCost, completionCost, completionLimit) {

    override fun substitutionCost(index: Int, candidate: Char): Float {
        val g = geometry ?: return 1f
        val target = g.center(candidate) ?: return 1f
        val typed = g.center(input[index]) ?: return 1f
        val tx = word.tapX(index)
        val ty = word.tapY(index)
        return if (!tx.isNaN() && !ty.isNaN()) {
            val excess = g.distanceInKeys(tx, ty, target[0], target[1]) - g.distanceInKeys(tx, ty, typed[0], typed[1])
            (0.2f + 0.55f * excess).coerceIn(0.2f, 1f)
        } else {
            val d = g.distanceInKeys(typed[0], typed[1], target[0], target[1])
            if (d < 1.6f) 0.55f + 0.2f * (d - 1f).coerceAtLeast(0f) else 1f
        }
    }
}
