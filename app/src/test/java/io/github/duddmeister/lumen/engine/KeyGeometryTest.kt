package io.github.duddmeister.lumen.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** QWERTY split as on an open foldable: G and V on both halves, with an opening between them. */
object SplitQwerty {
    private const val HALF = 4 + 4f / 3
    private const val LEFT_INNER = 0.58f + HALF
    private const val RIGHT_INNER = LEFT_INNER + 1.74f

    fun geometry(keyWidth: Float = 100f, keyHeight: Float = 140f) = KeyGeometry(keyWidth, keyHeight, centers(keyWidth, keyHeight))

    /** Every key's centre, by letter. */
    fun centers(keyWidth: Float = 100f, keyHeight: Float = 140f): Map<Char, List<FloatArray>> {
        val rows = listOf("qwert" to "yuiop", "asdfg" to "ghjkl", "zxcv" to "vbnm")
        val map = HashMap<Char, MutableList<FloatArray>>()
        rows.forEachIndexed { r, (left, right) ->
            // The top row stretches across its half; the others keep whole keys against the opening.
            val w = if (r == 0) HALF / 5 else 1f
            val y = (r + 0.5f) * keyHeight
            left.forEachIndexed { i, c ->
                map.getOrPut(c) { ArrayList() }.add(floatArrayOf((LEFT_INNER - (left.length - i - 0.5f) * w) * keyWidth, y))
            }
            right.forEachIndexed { i, c ->
                map.getOrPut(c) { ArrayList() }.add(floatArrayOf((RIGHT_INNER + (i + 0.5f) * w) * keyWidth, y))
            }
        }
        return map
    }
}

class KeyGeometryTest {
    private val split = SplitQwerty.geometry()
    private val leftG = split.center('g', 0f, 0f)!!
    private val rightG = split.center('g', 10_000f, 0f)!!

    @Test
    fun findsTheNearestKeyForALetterOnBothHalves() {
        assertEquals("the two Gs are apart", 2.74f, split.distanceInKeys(leftG[0], leftG[1], rightG[0], rightG[1]), 1e-3f)
        assertSame(leftG, split.center('g'))
        assertSame(rightG, split.center('g', rightG[0] + 30f, rightG[1]))
        assertEquals(1f, split.keysApart('g', 'h')!!, 1e-3f)
        assertEquals(1f, split.keysApart('f', 'g')!!, 1e-3f)
    }

    private fun costOf(candidate: Char, typed: Char, at: FloatArray): Float {
        val word = TypedWord().apply { add(typed, at[0], at[1]) }
        return SpatialQuery(word, split, typed.toString(), 2f, 1f, 10).substitutionCost(0, candidate)
    }

    @Test
    fun autocorrectReadsTapsOnEitherHalf() {
        // A tap dead centre on either G: its neighbours on that side are near, the others far.
        assertEquals(0.75f, costOf('f', 'g', leftG), 1e-3f)
        assertEquals(1f, costOf('h', 'g', leftG), 1e-3f)
        assertEquals(0.75f, costOf('h', 'g', rightG), 1e-3f)
        assertEquals(1f, costOf('f', 'g', rightG), 1e-3f)
        // Without a tap, letters count as neighbours if any of their keys are.
        val restored = TypedWord().apply { add('g') }
        assertEquals(0.55f, SpatialQuery(restored, split, "g", 2f, 1f, 10).substitutionCost(0, 'h'), 1e-3f)
    }
}
