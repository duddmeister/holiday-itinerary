package io.github.duddmeister.lumen.keyboard

import kotlin.math.abs

/**
 * Where each row breaks when the keyboard splits in two for thumb typing on a
 * large screen. Pure arithmetic on key widths in grid units, so it can be unit
 * tested without Android.
 */
object SplitPlan {
    /** A space bar is only cut in two when each part can be at least this wide. */
    const val MIN_SPACE_PART = 2f

    private const val EPSILON = 1e-3f

    /**
     * [index] is the first key of the right half. When [cutKey] is a key index,
     * that key (the space bar) is cut in two instead: [leftWidth] units stay on
     * the left and the rest starts the right half.
     */
    class Row(val index: Int, val cutKey: Int = -1, val leftWidth: Float = 0f)

    /**
     * Plans one row of keys with [widths], centred in a grid [units] wide.
     * [spaceIndex] is the row's space bar, or -1.
     */
    fun plan(widths: FloatArray, units: Float, spaceIndex: Int = -1): Row {
        val middle = units / 2f
        val offset = (units - widths.sum()) / 2f
        if (spaceIndex >= 0) {
            var start = offset
            for (i in 0 until spaceIndex) start += widths[i]
            val end = start + widths[spaceIndex]
            // A wide space bar across the middle gives each thumb its own half.
            if (start < middle - EPSILON && end > middle + EPSILON && end - start >= 2 * MIN_SPACE_PART - EPSILON) {
                val cut = middle.coerceIn(start + MIN_SPACE_PART, end - MIN_SPACE_PART)
                return Row(spaceIndex + 1, spaceIndex, cut - start)
            }
        }
        // Otherwise break at the key edge nearest the middle. On a tie the left
        // half takes the extra key, as touch typists do with G and V.
        var best = -1
        var bestDistance = Float.MAX_VALUE
        var edge = offset
        for (i in 1 until widths.size) {
            edge += widths[i - 1]
            val distance = abs(edge - middle)
            if (distance <= bestDistance + EPSILON) {
                best = i
                bestDistance = minOf(distance, bestDistance)
            }
        }
        return Row(best)
    }
}
