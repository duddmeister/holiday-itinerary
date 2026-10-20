package io.github.duddmeister.lumen.engine

import kotlin.math.sqrt

/**
 * Where each letter sits on the current layout, in pixels, for the tap spatial
 * model (autocorrect). A letter can have more than one key: a split keyboard
 * puts the middle keys on both halves.
 */
class KeyGeometry(
    val keyWidth: Float,
    val keyHeight: Float,
    private val centers: Map<Char, List<FloatArray>>,
    /** The space bar's left and right edges and its top, when the layout has one. */
    private val space: FloatArray? = null,
) {
    /**
     * What it costs to read a tap at ([x], [y]) that typed [c] as a space instead: a tap on a
     * letter just above the space bar, low on its key, often meant the bar ("sincebthe").
     * Null when the space bar isn't under that letter, the tap is not known, or it landed too
     * near the middle of the key to be anything but that letter ("innit").
     */
    fun spaceCost(c: Char, x: Float, y: Float): Float? {
        val s = space ?: return null
        if (x.isNaN() || y.isNaN() || x < s[0] || x > s[1]) return null
        val key = center(c, x, y) ?: return null
        // Only the row resting on the bar: its keys' centres are within a row of the bar's top.
        if (s[2] - key[1] > keyHeight) return null
        val below = (y - key[1]) / keyHeight
        if (below < SPACE_MIN_DROP) return null
        return (SPACE_CENTRE_COST - SPACE_COST_SLOPE * below).coerceIn(SPACE_EDGE_COST, SPACE_CENTRE_COST)
    }

    /**
     * Center of a key that types [c] (folded), or null when the layout has none. With
     * a point, the key nearest it; otherwise the first.
     */
    fun center(c: Char, x: Float = Float.NaN, y: Float = Float.NaN): FloatArray? {
        val all = centers[CharFolding.fold(c)] ?: return null
        if (all.size == 1 || x.isNaN() || y.isNaN()) return all[0]
        return all.minBy { distanceInKeys(x, y, it[0], it[1]) }
    }

    /** Distance, in key widths, between the nearest keys typing [a] and [b], or null when either is missing. */
    fun keysApart(a: Char, b: Char): Float? {
        val from = centers[CharFolding.fold(a)] ?: return null
        val to = centers[CharFolding.fold(b)] ?: return null
        return from.minOf { p -> to.minOf { q -> distanceInKeys(p[0], p[1], q[0], q[1]) } }
    }

    /** Distance between two points measured in key widths (rows weighted by key aspect). */
    fun distanceInKeys(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = (x1 - x2) / keyWidth
        val dy = (y1 - y2) / keyHeight
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** A tap on a letter above the space bar may be a space if this far below its centre, in key heights... */
        const val SPACE_MIN_DROP = 0.15f
        /** ...costing this, less the slope times how far below the centre it is... */
        const val SPACE_CENTRE_COST = 1.2f
        const val SPACE_COST_SLOPE = 1.6f
        /** ...down to this at the key's edge with the bar. */
        const val SPACE_EDGE_COST = 0.45f

        /** A plain QWERTY grid with the space bar under C to N, handy for tests. */
        fun qwerty(keyWidth: Float = 100f, keyHeight: Float = 140f): KeyGeometry {
            val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val offsets = floatArrayOf(0f, 0.5f, 1.5f)
            val map = HashMap<Char, List<FloatArray>>()
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { i, c ->
                    map[c] = listOf(floatArrayOf((offsets[r] + i + 0.5f) * keyWidth, (r + 0.5f) * keyHeight))
                }
            }
            return KeyGeometry(keyWidth, keyHeight, map, floatArrayOf(3.5f * keyWidth, 7.5f * keyWidth, 3f * keyHeight))
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

    /** Letters [from] until [to], with their taps. */
    fun slice(from: Int, to: Int): TypedWord = TypedWord().also { for (i in from until to) it.add(text[i], xs[i], ys[i]) }

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
        val tx = word.tapX(index)
        val ty = word.tapY(index)
        return if (!tx.isNaN() && !ty.isNaN()) {
            // The keys nearest the tap, for letters on both halves of a split keyboard.
            val target = g.center(candidate, tx, ty) ?: return 1f
            val typed = g.center(input[index], tx, ty) ?: return 1f
            val excess = g.distanceInKeys(tx, ty, target[0], target[1]) - g.distanceInKeys(tx, ty, typed[0], typed[1])
            (0.2f + 0.55f * excess).coerceIn(0.2f, MAX_SUBSTITUTION_COST)
        } else {
            val d = g.keysApart(input[index], candidate) ?: return 1f
            if (d < 1.6f) 0.55f + 0.2f * (d - 1f).coerceAtLeast(0f) else 1f
        }
    }

    private companion object {
        /**
         * A tap read as a key well away from it costs up to this: more than a letter left out
         * or one too many, so "lovk" is "lock" rather than "love", and "lonk" "link" not "long".
         */
        const val MAX_SUBSTITUTION_COST = 1.5f
    }
}
