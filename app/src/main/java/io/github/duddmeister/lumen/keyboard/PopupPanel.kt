package io.github.duddmeister.lumen.keyboard

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The long-press panel of alternatives. The first option sits right above the
 * finger; on the right edge of the keyboard the panel grows leftwards instead.
 * Coordinates are in keyboard-view space.
 */
class PopupPanel(val key: Key, val options: List<String>) {
    val bounds = RectF()
    val cells = List(options.size) { RectF() }
    var selected = 0
        private set
    var openedAt = 0L
    private var perRow = 1
    private var rows = 1
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var padding = 0f

    fun layout(viewWidth: Float, cellWidth: Float, cellHeight: Float, padding: Float, minTop: Float, maxPerRow: Int) {
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        this.padding = padding
        val fit = max(1, min(options.size, min(maxPerRow, ((viewWidth - 2 * padding) / cellWidth).toInt())))
        rows = ceil(options.size / fit.toFloat()).toInt()
        // Balance wrapped rows (6 + 6 rather than 10 + 2).
        perRow = ceil(options.size / rows.toFloat()).toInt()
        val width = perRow * cellWidth + 2 * padding
        val height = rows * cellHeight + 2 * padding
        val anchor = key.bounds.centerX()
        var left = anchor - padding - cellWidth / 2f
        val mirrored = left + width > viewWidth - padding
        if (mirrored) left = anchor + padding + cellWidth / 2f - width
        left = left.coerceIn(padding / 2f, max(padding / 2f, viewWidth - width - padding / 2f))
        val bottom = key.bounds.top + key.bounds.height() * 0.12f
        val top = max(minTop, bottom - height)
        bounds.set(left, top, left + width, top + height)
        for (i in options.indices) {
            val row = i / perRow
            val col = i % perRow
            val x = if (mirrored) bounds.right - padding - (col + 1) * cellWidth else bounds.left + padding + col * cellWidth
            val y = bounds.bottom - padding - (row + 1) * cellHeight
            cells[i].set(x, y, x + cellWidth, y + cellHeight)
        }
    }

    /** Moves the selection to the cell nearest to the finger. */
    fun select(x: Float, y: Float): Boolean {
        val row = ((bounds.bottom - padding - y) / cellHeight).toInt().coerceIn(0, rows - 1)
        var best = selected
        var bestDistance = Float.MAX_VALUE
        for (i in options.indices) {
            if (i / perRow != row) continue
            val d = kotlin.math.abs(cells[i].centerX() - x)
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        val changed = best != selected
        selected = best
        return changed
    }

    val selectedText: String get() = options[selected]
}
