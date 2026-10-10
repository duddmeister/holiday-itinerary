package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import io.github.duddmeister.lumen.data.EmojiData
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.Key
import io.github.duddmeister.lumen.keyboard.KeyCodes
import io.github.duddmeister.lumen.keyboard.KeyPainter
import io.github.duddmeister.lumen.keyboard.KeyStyle
import kotlin.math.ceil

/**
 * Emoji keyboard: category tabs, recents, a flinging grid and long-press skin
 * tones that are remembered per emoji.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context, painter: KeyPainter) : PanelView(context, painter) {

    var onEmoji: ((String) -> Unit)? = null
    var data: EmojiData? = null
        set(value) { field = value; rebuild() }

    private class Section(val title: String, val icon: String?, val items: List<String>) {
        var top = 0f
        var gridTop = 0f
    }

    private var sections = emptyList<Section>()
    private var columns = 8
    private var cell = 0f
    private var rowHeight = 0f
    private var activeSection = 0
    private var pressed: Pair<Int, Int>? = null

    private class TonePicker(val options: List<String>) {
        val bounds = RectF()
        var selected = 0
    }
    private var picker: TonePicker? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        alpha = 150
    }
    private val rect = RectF()
    private val bounds = android.graphics.Rect()

    override val footerKeys: List<Key> = listOf(
        Key(KeyCodes.ALPHABET, "ABC", width = 1.5f, style = KeyStyle.FUNCTIONAL, preview = false),
        Key(KeyCodes.SPACE, " ", width = 7f, style = KeyStyle.SPACE, preview = false),
        Key(KeyCodes.DELETE, icon = Icon.DELETE, width = 1.5f, style = KeyStyle.FUNCTIONAL, preview = false),
    )

    /** Rebuilds sections, e.g. after the data loaded or recents changed. */
    fun rebuild() {
        val d = data
        sections = if (d == null) emptyList() else buildList {
            val recents = d.recents()
            if (recents.isNotEmpty()) add(Section("Recently used", null, recents))
            for (c in d.categories) add(Section(c.name, c.icon, c.emoji.map { d.display(it.base) }))
        }
        layoutContent()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutContent()
    }

    private fun layoutContent() {
        if (width == 0) return
        columns = maxOf(7, (width / context.dp(46f)).toInt())
        cell = width / columns.toFloat()
        rowHeight = minOf(cell * 0.94f, context.dp(52f))
        var y = context.dp(2f)
        for (s in sections) {
            s.top = y
            y += context.dp(30f)
            s.gridTop = y
            y += ceil(s.items.size / columns.toFloat()) * rowHeight
        }
        contentHeight = y + context.dp(8f)
        scroll = scroll.coerceIn(0f, maxScroll())
        onScrollChanged()
    }

    override fun onScrollChanged() {
        var active = 0
        for ((i, s) in sections.withIndex()) if (s.top <= scroll + context.dp(40f)) active = i
        activeSection = active
    }

    override fun drawHeader(canvas: Canvas, bounds: RectF) {
        val t = painter.theme ?: return
        if (sections.isEmpty()) return
        val w = bounds.width() / sections.size
        for ((i, s) in sections.withIndex()) {
            val cx = w * (i + 0.5f)
            val cy = bounds.centerY()
            val active = i == activeSection
            if (s.icon == null) {
                Icons.draw(canvas, Icon.CLOCK, cx, cy, context.dp(20f), if (active) t.stripText else t.stripSecondary, paint)
            } else {
                val p = if (active) text else muted
                p.textSize = context.dp(19f)
                p.getTextBounds(s.icon, 0, s.icon.length, this.bounds)
                canvas.drawText(s.icon, cx, cy - this.bounds.exactCenterY(), p)
            }
            if (active) {
                paint.color = t.accent
                rect.set(cx - context.dp(9f), bounds.bottom - context.dp(5f), cx + context.dp(9f), bounds.bottom - context.dp(2.5f))
                canvas.drawRoundRect(rect, rect.height(), rect.height(), paint)
            }
        }
    }

    override fun onHeaderTap(x: Float, y: Float) {
        if (sections.isEmpty()) return
        val i = (x / (width / sections.size.toFloat())).toInt().coerceIn(0, sections.lastIndex)
        host?.onPanelFeedback()
        scrollToContent(sections[i].top)
    }

    override fun drawContent(canvas: Canvas, top: Float, bottom: Float) {
        val t = painter.theme ?: return
        if (sections.isEmpty()) {
            text.color = t.stripSecondary
            text.textSize = context.dp(14f)
            canvas.drawText("Loading emoji…", width / 2f, top + content.height() / 2f, text)
            return
        }
        text.textSize = rowHeight * 0.6f
        for ((si, s) in sections.withIndex()) {
            val end = s.gridTop + ceil(s.items.size / columns.toFloat()) * rowHeight
            if (end < top || s.top > bottom) continue
            text.color = t.stripSecondary
            val titleSize = text.textSize
            text.textSize = context.dp(12.5f)
            text.typeface = painter.mediumTypeface
            text.textAlign = Paint.Align.LEFT
            text.letterSpacing = 0.04f
            canvas.drawText(s.title, context.dp(14f), s.top + context.dp(21f), text)
            text.letterSpacing = 0f
            text.textAlign = Paint.Align.CENTER
            text.typeface = null
            text.textSize = titleSize
            text.color = 0xFF000000.toInt()
            for ((i, e) in s.items.withIndex()) {
                val row = i / columns
                val y = s.gridTop + row * rowHeight
                if (y + rowHeight < top || y > bottom) continue
                val cx = (i % columns + 0.5f) * cell
                val cy = y + rowHeight / 2f
                if (pressed?.first == si && pressed?.second == i) {
                    paint.color = Colors.withAlpha(t.stripText, 0.1f)
                    canvas.drawCircle(cx, cy, rowHeight * 0.46f, paint)
                }
                text.getTextBounds("😀", 0, 2, bounds)
                canvas.drawText(e, cx, cy - bounds.exactCenterY(), text)
            }
        }
    }

    private fun itemAt(x: Float, y: Float): Pair<Int, Int>? {
        for ((si, s) in sections.withIndex()) {
            val rows = ceil(s.items.size / columns.toFloat()).toInt()
            if (y < s.gridTop || y >= s.gridTop + rows * rowHeight) continue
            val i = ((y - s.gridTop) / rowHeight).toInt() * columns + (x / cell).toInt().coerceIn(0, columns - 1)
            return if (i < s.items.size) si to i else null
        }
        return null
    }

    override fun onContentPress(x: Float, y: Float) {
        pressed = if (x < 0) null else itemAt(x, y)
        if (pressed != null) host?.onPanelFeedback()
        invalidate()
    }

    override fun onContentTap(x: Float, y: Float) {
        val (si, i) = itemAt(x, y) ?: return
        commit(sections[si].items[i])
    }

    private fun commit(emoji: String) {
        onEmoji?.invoke(emoji)
        data?.recordUse(emoji)
    }

    override fun onContentLongPress(x: Float, y: Float): Boolean {
        val (si, i) = itemAt(x, y) ?: return false
        val shown = sections[si].items[i]
        val e = data?.variantsOf(shown) ?: return false
        if (e.variants.isEmpty()) return false
        val options = listOf(e.base) + e.variants
        val cx = (i % columns + 0.5f) * cell
        val cy = sections[si].gridTop + (i / columns) * rowHeight - scroll + content.top
        val p = TonePicker(options)
        val w = options.size * cell * 0.95f + context.dp(12f)
        val left = (cx - w / 2f).coerceIn(context.dp(4f), width - w - context.dp(4f))
        val h = rowHeight + context.dp(12f)
        val pickerTop = maxOf(context.dp(2f), cy - h - context.dp(4f))
        p.bounds.set(left, pickerTop, left + w, pickerTop + h)
        p.selected = options.indexOf(shown).coerceAtLeast(0)
        picker = p
        pressed = null
        host?.onPanelFeedback()
        invalidate()
        return true
    }

    override fun onLongPressMove(x: Float, y: Float) {
        val p = picker ?: return
        val inner = p.bounds.width() - context.dp(12f)
        val i = (((x - p.bounds.left - context.dp(6f)) / inner) * p.options.size).toInt().coerceIn(0, p.options.lastIndex)
        if (i != p.selected) {
            p.selected = i
            invalidate()
        }
    }

    override fun onLongPressUp(x: Float, y: Float) {
        val p = picker ?: return
        picker = null
        if (x >= 0) {
            commit(p.options[p.selected])
            rebuild()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = picker ?: return
        val t = painter.theme ?: return
        paint.color = Colors.withAlpha(0xFF000000.toInt(), if (t.dark) 0.3f else 0.12f)
        rect.set(p.bounds)
        rect.offset(0f, context.dp(2f))
        canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), paint)
        paint.color = Colors.opaque(t.popup)
        canvas.drawRoundRect(p.bounds, context.dp(14f), context.dp(14f), paint)
        val cw = (p.bounds.width() - context.dp(12f)) / p.options.size
        text.textSize = rowHeight * 0.58f
        for ((i, e) in p.options.withIndex()) {
            val cx = p.bounds.left + context.dp(6f) + cw * (i + 0.5f)
            if (i == p.selected) {
                paint.color = t.accent
                rect.set(cx - cw / 2f + context.dp(1f), p.bounds.top + context.dp(4f), cx + cw / 2f - context.dp(1f), p.bounds.bottom - context.dp(4f))
                canvas.drawRoundRect(rect, context.dp(10f), context.dp(10f), paint)
            }
            text.getTextBounds("😀", 0, 2, bounds)
            canvas.drawText(e, cx, p.bounds.centerY() - bounds.exactCenterY(), text)
        }
    }
}
