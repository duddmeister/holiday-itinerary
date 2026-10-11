package io.github.duddmeister.lumen.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomRowTest {
    private fun bottomRow(field: FieldKind, emojiKey: Boolean = false) =
        Layouts.build(KeyboardMode.TEXT, LayoutOptions(field = field, emojiKey = emojiKey)).rows.last()

    /** Where the space bar starts and ends, in key widths from the left. */
    private fun spaceBar(field: FieldKind, emojiKey: Boolean = false): Pair<Float, Float> {
        var x = 0f
        for (k in bottomRow(field, emojiKey).keys) {
            if (k.code == KeyCodes.SPACE) return x to x + k.width
            x += k.width
        }
        error("no space bar")
    }

    /**
     * A thumb that finds the space bar in a message finds it in a search box too. In address
     * fields (the browser's address bar, where searches are typed) "." and ".com" sat where
     * the right of the space bar is, and taps meant for it typed full stops.
     */
    @Test
    fun theSpaceBarKeepsItsPlaceInEveryField() {
        val text = spaceBar(FieldKind.TEXT)
        for (field in FieldKind.entries) assertEquals(field.name, text, spaceBar(field))
        // The emoji key takes from its left end only.
        assertEquals(text.second, spaceBar(FieldKind.TEXT, emojiKey = true).second, 1e-6f)
    }

    @Test
    fun addressesFindDotComUnderTheFullStop() {
        val row = bottomRow(FieldKind.URL)
        val period = row.keys.single { it.label == "." }
        assertEquals(".com", period.popup.first())
        assertTrue(".co.uk" in period.popup)
        assertTrue("no key types more than one character", row.keys.none { it.code == KeyCodes.TEXT })
    }
}
