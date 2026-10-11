package io.github.duddmeister.lumen.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLayoutTest {
    private fun split(options: LayoutOptions = LayoutOptions(split = true), mode: KeyboardMode = KeyboardMode.TEXT) =
        Layouts.build(mode, options)

    private fun name(key: Key) = when (key.code) {
        KeyCodes.SHIFT -> "⇧"
        KeyCodes.DELETE -> "⌫"
        KeyCodes.ENTER -> "⏎"
        KeyCodes.SPACE -> "_"
        KeyCodes.EMOJI -> "☺"
        else -> key.label
    }

    /** The row's halves; a bridging space bar is shown on both. */
    private fun halves(row: KeyRow): Pair<String, String> {
        val left = row.keys.subList(0, row.splitAt) + if (row.bridged) listOf(row.keys[row.splitAt]) else emptyList()
        return left.joinToString("") { name(it) } to row.keys.subList(row.splitAt, row.keys.size).joinToString("") { name(it) }
    }

    @Test
    fun repeatsTheMiddleKeyOfOddRowsOnBothHalves() {
        val qwerty = split().rows
        assertEquals("qwert" to "yuiop", halves(qwerty[0]))
        assertEquals("asdfg" to "ghjkl", halves(qwerty[1]))
        assertEquals("⇧zxcv" to "vbnm⌫", halves(qwerty[2]))
        assertEquals("?123,☺_" to "_.⏎", halves(qwerty[3]))

        val azerty = split(LayoutOptions(letters = LetterLayout.AZERTY, split = true)).rows
        assertEquals("qsdfg" to "hjklm", halves(azerty[1]))
        assertEquals("⇧wxcv" to "vbn'⌫", halves(azerty[2]))

        val colemak = split(LayoutOptions(letters = LetterLayout.COLEMAK, split = true)).rows
        assertEquals("qwfpg" to "gjluy", halves(colemak[0]))
        assertEquals("arstd" to "hneio", halves(colemak[1]))

        val withNumbers = split(LayoutOptions(numberRow = true, split = true)).rows
        assertEquals("12345" to "67890", halves(withNumbers[0]))

        val symbols = split(mode = KeyboardMode.SYMBOLS).rows
        assertEquals("@#\$_&" to "-+()/", halves(symbols[1]))
        assertEquals("=\\<*\"':" to ":;!?⌫", halves(symbols[2]))
    }

    @Test
    fun repeatedKeysTypeAndOfferTheSameAsTheOriginal() {
        val row = split().rows[1]
        val (left, right) = row.keys[row.splitAt - 1] to row.keys[row.splitAt]
        assertTrue("two keys, not one key twice", left !== right)
        assertEquals(left.code, right.code)
        assertEquals(left.hint, right.hint)
        assertEquals(left.popup, right.popup)
        assertEquals(left.width, right.width, 1e-6f)
    }

    @Test
    fun halvesMatchAndLineUpAtTheOpening() {
        val rows = split().rows
        val half = 4 + 4f / 3
        // Shift and delete are 4/3 of a key, so the halves are 5⅓ keys wide.
        assertEquals(4f / 3, rows[2].keys.first().width, 1e-6f)
        assertEquals(4f / 3, rows[2].keys.last().width, 1e-6f)
        for (i in listOf(0, 2, 3)) {
            assertEquals("row $i left", half, rows[i].leftUnits, 1e-4f)
            assertEquals("row $i right", half, rows[i].rightUnits, 1e-4f)
        }
        // The top row stretches to fill its halves...
        assertTrue(rows[0].keys.all { kotlin.math.abs(it.width - half / 5) < 1e-4f })
        // ...while the shorter second row keeps whole keys, leaving room at the outer edges.
        assertTrue(rows[1].keys.all { it.width == 1f })
        assertEquals(5f, rows[1].leftUnits, 1e-6f)
        assertEquals(5f, rows[1].rightUnits, 1e-6f)
    }

    @Test
    fun oneSpaceBarSpansTheOpening() {
        val bottom = split().rows.last()
        assertEquals(1, bottom.keys.count { it.code == KeyCodes.SPACE })
        assertTrue(bottom.bridged)
        val space = bottom.keys[bottom.splitAt]
        assertEquals(KeyCodes.SPACE, space.code)
        // As in Gboard: from the C column on the left to the N column on the right.
        assertEquals(2f, bottom.bridgeLeft, 1e-4f)
        assertEquals(5f, space.width, 1e-4f)

        // Without the emoji key it reaches as far into either half.
        val email = split(LayoutOptions(field = FieldKind.EMAIL, split = true)).rows.last()
        assertEquals(3f, email.bridgeLeft, 1e-4f)
        assertEquals(6f, email.keys[email.splitAt].width, 1e-4f)

        // So do address fields, with "/" for "@" (".com" is held on the full stop).
        val url = split(LayoutOptions(field = FieldKind.URL, split = true)).rows.last()
        assertEquals("?123/_" to "_.⏎", halves(url))
        assertEquals(3f, url.bridgeLeft, 1e-4f)
        assertEquals(6f, url.keys[url.splitAt].width, 1e-4f)
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
    fun keepsEveryKeyInOrder() {
        for (letters in LetterLayout.entries) {
            for (mode in listOf(KeyboardMode.TEXT, KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_MORE)) {
                val whole = Layouts.build(mode, LayoutOptions(letters = letters))
                val halves = Layouts.build(mode, LayoutOptions(letters = letters, split = true))
                val grid = whole.rows.maxOf { it.units }
                for ((a, b) in whole.rows.zip(halves.rows)) {
                    val codes = b.keys.map { it.code }.toMutableList()
                    // A repeated middle key is the only addition.
                    if (!b.bridged && codes[b.splitAt - 1] == codes[b.splitAt]) codes.removeAt(b.splitAt)
                    assertEquals("${letters.id} $mode", a.keys.map { it.code }, codes)
                    assertTrue("${letters.id} $mode: both halves have keys", b.splitAt in 1 until b.keys.size)
                    if (a.units > grid - 1e-3f) assertEquals("${letters.id} $mode: matching halves", b.leftUnits, b.rightUnits, 1e-4f)
                }
            }
        }
    }
}
