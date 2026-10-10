package io.github.duddmeister.lumen.keyboard

import android.graphics.RectF

/** Codes for keys that do not type a character. Character keys use their code point. */
object KeyCodes {
    const val SHIFT = -1
    const val SYMBOLS = -2
    const val ALPHABET = -3
    const val SYMBOLS_MORE = -4
    const val DELETE = -5
    const val EMOJI = -6
    const val TEXT = -7
    const val ENTER = 10
    const val SPACE = 32
}

enum class KeyStyle { CHARACTER, FUNCTIONAL, ACCENT, SPACE }

enum class Icon {
    SHIFT, SHIFT_ON, CAPS_LOCK, DELETE, ENTER, SEARCH, SEND, GO, NEXT, PREVIOUS, DONE, EMOJI,
    SPACE, SETTINGS, CLIPBOARD, PALETTE, KEYBOARD_HIDE, SPARKLE, CLOCK, PIN, TRASH, ARROW_LEFT, ARROW_RIGHT,
}

/**
 * One key. Labels are stored in their unshifted form; the view uppercases
 * letters when shift is active. Geometry is filled in by [KeyboardLayout].
 */
class Key(
    val code: Int,
    val label: String = "",
    /** Text committed instead of [label] (e.g. ".com"). */
    val text: String? = null,
    val icon: Icon? = null,
    /** Width in grid units; a standard letter key is 1. */
    val width: Float = 1f,
    val style: KeyStyle = KeyStyle.CHARACTER,
    /** Small hint drawn in the corner, usually the first long-press alternative. */
    val hint: String? = null,
    /** Alternatives offered on long press. */
    val popup: List<String> = emptyList(),
    /** Secondary caption under the label (phone pad letters). */
    val caption: String? = null,
    /** Shows the key preview bubble when pressed. */
    val preview: Boolean = style == KeyStyle.CHARACTER,
) {
    /** Visible key shape. */
    val bounds = RectF()

    /** Touch area: the shape plus half of each surrounding gap. */
    val hitBounds = RectF()

    val isLetter: Boolean get() = code > 0 && Character.isLetter(code)

    val isCharacter: Boolean get() = code > 0 && code != KeyCodes.ENTER && code != KeyCodes.SPACE

    // Animation state, owned by the view.
    internal var pressedAt = 0L
    internal var releasedAt = 0L
    internal var pressed = false

    override fun toString() = "Key(${if (label.isNotEmpty()) label else code.toString()})"
}

class KeyRow(val keys: List<Key>, val heightFactor: Float = 1f)

enum class KeyboardMode { TEXT, SYMBOLS, SYMBOLS_MORE, NUMBER, PHONE }

/** Rows of keys plus the geometry computed for the current view size. */
class KeyboardLayout(val mode: KeyboardMode, val rows: List<KeyRow>, val name: String) {
    val keys: List<Key> = rows.flatMap { it.keys }

    /** Grid width in units: the widest row. */
    private val units: Float = rows.maxOf { r -> r.keys.sumOf { it.width.toDouble() }.toFloat() }

    var width = 0
        private set
    var height = 0
        private set
    var keyWidth = 0f
        private set
    var rowHeight = 0f
        private set

    fun totalHeight(rowHeight: Float, paddingTop: Float, paddingBottom: Float): Int =
        (paddingTop + paddingBottom + rows.sumOf { (it.heightFactor * rowHeight).toDouble() }).toInt()

    /**
     * Positions keys inside a [width] x [height] area. Rows narrower than the grid
     * are centered and their edge keys absorb the margin as touch area.
     */
    fun layout(width: Int, rowHeight: Float, sidePadding: Float, top: Float, gapX: Float, gapY: Float) {
        this.width = width
        this.rowHeight = rowHeight
        val usable = width - 2 * sidePadding
        val unit = usable / units
        keyWidth = unit - gapX
        var y = top
        for ((rowIndex, row) in rows.withIndex()) {
            val h = rowHeight * row.heightFactor
            val rowUnits = row.keys.sumOf { it.width.toDouble() }.toFloat()
            var x = sidePadding + (units - rowUnits) * unit / 2f
            for ((i, key) in row.keys.withIndex()) {
                val w = key.width * unit
                key.bounds.set(x + gapX / 2f, y + gapY / 2f, x + w - gapX / 2f, y + h - gapY / 2f)
                key.hitBounds.set(
                    if (i == 0) 0f else x,
                    if (rowIndex == 0) 0f else y,
                    if (i == row.keys.lastIndex) width.toFloat() else x + w,
                    if (rowIndex == rows.lastIndex) Float.MAX_VALUE else y + h,
                )
                x += w
            }
            y += h
        }
        height = y.toInt()
    }

    /** The key under a point; falls back to the nearest key so no touch is ever lost. */
    fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestDistance = Float.MAX_VALUE
        for (key in keys) {
            if (key.hitBounds.contains(x, y)) return key
            val dx = x - key.bounds.centerX()
            val dy = y - key.bounds.centerY()
            val d = dx * dx + dy * dy
            if (d < bestDistance) { bestDistance = d; best = key }
        }
        return best
    }
}
