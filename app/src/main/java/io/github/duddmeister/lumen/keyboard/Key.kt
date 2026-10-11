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
    CLEAR, GIF, MIC,
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

    /** The same key, [width] units wide: a split keyboard resizes keys and repeats the middle ones. */
    fun copy(width: Float = this.width) = Key(code, label, text, icon, width, style, hint, popup, caption, preview)

    override fun toString() = "Key(${if (label.isNotEmpty()) label else code.toString()})"
}

/**
 * [splitAt] is the first key of the right half when the keyboard is split, or -1.
 * When [bridgeLeft] is not negative, the key at [splitAt] (the space bar) spans the
 * opening between the halves instead, with [bridgeLeft] of its units in the left half.
 */
class KeyRow(val keys: List<Key>, val heightFactor: Float = 1f, val splitAt: Int = -1, val bridgeLeft: Float = -1f) {
    val units: Float = keys.sumOf { it.width.toDouble() }.toFloat()

    val bridged: Boolean get() = splitAt >= 0 && bridgeLeft >= 0f

    /** Width of the left half in units, the left part of a bridging key included. */
    val leftUnits: Float
        get() = keys.subList(0, splitAt.coerceAtLeast(0)).sumOf { it.width.toDouble() }.toFloat() + if (bridged) bridgeLeft else 0f

    /** Width of the right half in units. */
    val rightUnits: Float get() = if (splitAt < 0) 0f else units - leftUnits
}

enum class KeyboardMode { TEXT, SYMBOLS, SYMBOLS_MORE, NUMBER, PHONE }

/** Rows of keys plus the geometry computed for the current view size. */
class KeyboardLayout(val mode: KeyboardMode, val rows: List<KeyRow>, val name: String) {
    val keys: List<Key> = rows.flatMap { it.keys }

    /** Grid width in units: the widest row. */
    private val units: Float = rows.maxOf { it.units }

    /** Rows break into two halves with an opening between them (large screens). */
    val isSplit: Boolean = rows.any { it.splitAt > 0 }

    /** Width of each half of a split layout, in units: the widest half of any row. */
    private val halfUnits: Float = rows.filter { it.splitAt > 0 }.maxOfOrNull { maxOf(it.leftUnits, it.rightUnits) } ?: 0f

    /** Units a split layout spans across the view: both halves, the margins and the opening. */
    val splitUnitsAcross: Float get() = 2 * halfUnits + 2 * SPLIT_MARGIN + SPLIT_OPENING

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
     *
     * A split layout with [splitUnit] > 0 has two halves of keys that wide, each
     * [SPLIT_MARGIN] from its side, and opens the rest between them over the middle,
     * where a foldable's hinge is. Rows sit against the opening, so the inner keys
     * line up and a short row leaves its room at the outer edge, as a staggered row
     * does; a bridging space bar runs across. Touch areas reach the sides of the
     * view and meet in the middle of the opening.
     */
    fun layout(width: Int, rowHeight: Float, sidePadding: Float, top: Float, gapX: Float, gapY: Float, splitUnit: Float = 0f) {
        this.width = width
        this.rowHeight = rowHeight
        val split = isSplit && splitUnit > 0f
        // On a narrow screen the keys shrink rather than close up the opening.
        val unit = if (split) minOf(splitUnit, width / (2 * halfUnits + 2 * SPLIT_MARGIN + SPLIT_MIN_OPENING)) else (width - 2 * sidePadding) / units
        keyWidth = unit - gapX
        val leftInner = (SPLIT_MARGIN + halfUnits) * unit
        val rightInner = width - leftInner
        val middle = width / 2f
        var y = top
        for ((rowIndex, row) in rows.withIndex()) {
            val h = rowHeight * row.heightFactor
            val hitTop = if (rowIndex == 0) 0f else y
            val hitBottom = if (rowIndex == rows.lastIndex) Float.MAX_VALUE else y + h
            // Key i spans x until x + w, and takes touches from hitLeft to hitRight.
            fun place(i: Int, x: Float, w: Float, hitLeft: Float, hitRight: Float) {
                row.keys[i].bounds.set(x + gapX / 2f, y + gapY / 2f, x + w - gapX / 2f, y + h - gapY / 2f)
                row.keys[i].hitBounds.set(
                    if (i == 0) 0f else hitLeft, hitTop, if (i == row.keys.lastIndex) width.toFloat() else hitRight, hitBottom,
                )
            }
            if (split && row.splitAt > 0) {
                var x = leftInner - row.leftUnits * unit
                for (i in 0 until row.splitAt) {
                    val w = row.keys[i].width * unit
                    place(i, x, w, x, if (i == row.splitAt - 1 && !row.bridged) middle else x + w)
                    x += w
                }
                var first = row.splitAt
                x = rightInner
                if (row.bridged) {
                    val start = leftInner - row.bridgeLeft * unit
                    x = rightInner + (row.keys[first].width - row.bridgeLeft) * unit
                    place(first, start, x - start, start, x)
                    first++
                }
                for (i in first until row.keys.size) {
                    val w = row.keys[i].width * unit
                    place(i, x, w, if (i == row.splitAt) middle else x, x + w)
                    x += w
                }
            } else {
                var x = (width - row.units * unit) / 2f
                for (i in row.keys.indices) {
                    val w = row.keys[i].width * unit
                    place(i, x, w, x, x + w)
                    x += w
                }
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

    companion object {
        // A split layout's proportions, in units (key widths): Gboard's on an open foldable.

        /** Room between each side of the view and the half next to it. */
        const val SPLIT_MARGIN = 0.58f

        /** Opening between the halves when there is room for it. */
        const val SPLIT_OPENING = 1.74f

        /** The least opening before the keys shrink. */
        const val SPLIT_MIN_OPENING = 1f
    }
}
