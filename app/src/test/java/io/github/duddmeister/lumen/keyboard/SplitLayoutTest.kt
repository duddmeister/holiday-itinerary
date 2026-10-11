package io.github.duddmeister.lumen.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLayoutTest {
    private fun split(options: LayoutOptions = LayoutOptions(split = true), mode: KeyboardMode = KeyboardMode.TEXT) =
        Layouts.build(mode, options)

    private fun halves(row: KeyRow): Pair<String, String> {
        fun names(keys: List<Key>) = keys.joinToString("") {
            when (it.code) {
                KeyCodes.SHIFT -> "⇧"
                KeyCodes.DELETE -> "⌫"
                KeyCodes.SPACE -> "_"
                else -> it.label
            }
        }
        return names(row.keys.subList(0, row.splitAt)) to names(row.keys.subList(row.splitAt, row.keys.size))
    }

    @Test
    fun breaksRowsWhereThumbsMeet() {
        val qwerty = split().rows
        assertEquals("qwert" to "yuiop", halves(qwerty[0]))
        assertEquals("asdfg" to "hjkl", halves(qwerty[1]))
        assertEquals("⇧zxcv" to "bnm⌫", halves(qwerty[2]))

        val colemak = split(LayoutOptions(letters = LetterLayout.COLEMAK, split = true)).rows
        assertEquals("qwfpg" to "jluy", halves(colemak[0]))
        assertEquals("arstd" to "hneio", halves(colemak[1]))

        val withNumbers = split(LayoutOptions(numberRow = true, split = true)).rows
        assertEquals("12345" to "67890", halves(withNumbers[0]))
    }

    @Test
    fun givesEachThumbASpaceBar() {
        // Emoji key on the left: the 4-unit space bar is cut 2 + 2 so both halves match.
        val bottom = split().rows.last()
        assertEquals(2, bottom.keys.count { it.code == KeyCodes.SPACE })
        val (left, right) = halves(bottom)
        assertTrue(left.endsWith("_"))
        assertTrue(right.startsWith("_"))
        val spaces = bottom.keys.filter { it.code == KeyCodes.SPACE }.map { it.width }
        assertEquals(listOf(2f, 2f), spaces)

        // Without the emoji key the 5-unit bar is cut down the middle.
        val email = split(LayoutOptions(field = FieldKind.EMAIL, split = true)).rows.last()
        assertEquals(listOf(2.5f, 2.5f), email.keys.filter { it.code == KeyCodes.SPACE }.map { it.width })
    }

    @Test
    fun keepsANarrowSpaceBarWhole() {
        // URL fields have a 3.5-unit space bar: too short to cut, so the row breaks after it.
        val url = split(LayoutOptions(field = FieldKind.URL, split = true)).rows.last()
        assertEquals(1, url.keys.count { it.code == KeyCodes.SPACE })
        assertEquals(KeyCodes.SPACE, url.keys[url.splitAt - 1].code)
    }

    @Test
    fun splitsSymbolsButNotPads() {
        val symbols = split(mode = KeyboardMode.SYMBOLS)
        assertTrue(symbols.isSplit)
        assertTrue(symbols.rows.all { it.splitAt > 0 })
        assertTrue(split(mode = KeyboardMode.SYMBOLS_MORE).isSplit)
        assertFalse(split(mode = KeyboardMode.NUMBER).isSplit)
        assertFalse(split(mode = KeyboardMode.PHONE).isSplit)
        assertFalse(Layouts.build(KeyboardMode.TEXT, LayoutOptions()).isSplit)
    }

    @Test
    fun keepsEveryKeyAndTheGridWidth() {
        for (letters in LetterLayout.entries) {
            val whole = Layouts.build(KeyboardMode.TEXT, LayoutOptions(letters = letters))
            val halves = Layouts.build(KeyboardMode.TEXT, LayoutOptions(letters = letters, split = true))
            for ((a, b) in whole.rows.zip(halves.rows)) {
                assertEquals(a.keys.sumOf { it.width.toDouble() }, b.keys.sumOf { it.width.toDouble() }, 1e-6)
                assertEquals(a.keys.filter { it.code != KeyCodes.SPACE }.map { it.code }, b.keys.filter { it.code != KeyCodes.SPACE }.map { it.code })
                assertTrue("${letters.id}: both halves have keys", b.splitAt in 1 until b.keys.size)
            }
        }
    }

    @Test
    fun plansFromWidthsAlone() {
        // Ten equal keys break exactly in the middle.
        assertEquals(5, SplitPlan.plan(FloatArray(10) { 1f }, 10f).index)
        // Nine keys offset by half a unit: G and H tie, and the left half takes G.
        assertEquals(5, SplitPlan.plan(FloatArray(9) { 1f }, 10f).index)
        // A single key cannot split.
        assertEquals(-1, SplitPlan.plan(floatArrayOf(4f), 10f).index)
        // A space bar across the middle is cut at the middle when both parts stay wide enough.
        val cut = SplitPlan.plan(floatArrayOf(1.5f, 1f, 5f, 1f, 1.5f), 10f, spaceIndex = 2)
        assertEquals(2, cut.cutKey)
        assertEquals(2.5f, cut.leftWidth, 1e-6f)
    }
}
