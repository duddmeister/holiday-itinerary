package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.github.duddmeister.lumen.data.ClipboardHistory
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.KeyPainter
import kotlin.math.ceil

/** Clipboard history: tap a card to paste it, long-press to pin or unpin. */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(context: Context, painter: KeyPainter, private val history: ClipboardHistory) :
    PanelView(context, painter) {

    var onPaste: ((String) -> Unit)? = null
    private var note: String? = null
    private var noteUntil = 0L

    private var clips = emptyList<ClipboardHistory.Clip>()
    private val cardHeight = context.dp(78f)
    private val gap = context.dp(8f)
    private val margin = context.dp(10f)
    private var pressedIndex = -1
    private val layouts = HashMap<String, StaticLayout>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun refresh() {
        clips = history.items()
        val rows = ceil(clips.size / 2f).toInt()
        contentHeight = margin + rows * (cardHeight + gap)
        scroll = scroll.coerceIn(0f, maxScroll())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layouts.clear()
        refresh()
    }

    private fun cardBounds(i: Int, out: RectF) {
        val w = (width - 2 * margin - gap) / 2f
        val col = i % 2
        val row = i / 2
        val left = margin + col * (w + gap)
        val top = margin / 2f + row * (cardHeight + gap)
        out.set(left, top, left + w, top + cardHeight)
    }

    override fun drawHeader(canvas: Canvas, bounds: RectF) {
        val t = painter.theme ?: return
        title.color = t.stripText
        title.typeface = painter.mediumTypeface
        title.textSize = context.dp(15.5f)
        val now = android.os.SystemClock.uptimeMillis()
        val heading = note?.takeIf { now < noteUntil } ?: "Clipboard"
        canvas.drawText(heading, context.dp(16f), bounds.centerY() + context.dp(5.5f), title)
        if (clips.any { !it.pinned }) {
            Icons.draw(canvas, Icon.TRASH, bounds.right - context.dp(28f), bounds.centerY(), context.dp(21f), t.stripSecondary, paint)
        }
    }

    override fun onHeaderTap(x: Float, y: Float) {
        if (x > width - context.dp(56f) && clips.any { !it.pinned }) {
            history.clearUnpinned()
            refresh()
            flash("Cleared — pinned clips stay")
        }
    }

    override fun drawContent(canvas: Canvas, top: Float, bottom: Float) {
        val t = painter.theme ?: return
        if (clips.isEmpty()) {
            val cy = top + content.height() / 2f
            Icons.draw(canvas, Icon.CLIPBOARD, width / 2f, cy - context.dp(22f), context.dp(30f), t.stripSecondary, paint)
            title.color = t.stripText
            title.typeface = painter.mediumTypeface
            title.textSize = context.dp(15f)
            title.textAlign = Paint.Align.CENTER
            canvas.drawText("Copied text shows up here", width / 2f, cy + context.dp(16f), title)
            title.color = t.stripSecondary
            title.typeface = painter.labelTypeface
            title.textSize = context.dp(13f)
            canvas.drawText("Long-press a clip to pin it", width / 2f, cy + context.dp(38f), title)
            title.textAlign = Paint.Align.LEFT
            return
        }
        val cardColor = if (t.dark) Colors.withAlpha(0xFFFFFFFF.toInt(), 0.08f) else Colors.withAlpha(0xFFFFFFFF.toInt(), 0.85f)
        for ((i, clip) in clips.withIndex()) {
            cardBounds(i, rect)
            if (rect.bottom < top || rect.top > bottom) continue
            paint.color = if (i == pressedIndex) Colors.lerp(cardColor, t.keyPressed, 0.6f) else cardColor
            canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), paint)
            if (clip.pinned) {
                Icons.draw(canvas, Icon.PIN, rect.right - context.dp(15f), rect.top + context.dp(15f), context.dp(15f), t.accent, paint)
            }
            val inner = (rect.width() - context.dp(24f) - if (clip.pinned) context.dp(14f) else 0f).toInt().coerceAtLeast(1)
            val layout = layouts.getOrPut("${t.stripText}|${clip.pinned}|${clip.text}") { build(clip.text, inner, t.stripText) }
            canvas.save()
            canvas.translate(rect.left + context.dp(12f), rect.top + context.dp(11f))
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun build(text: String, width: Int, color: Int): StaticLayout {
        body.color = color
        body.textSize = context.dp(13.5f)
        body.typeface = painter.labelTypeface
        val clean = text.trim().replace(Regex("\\s+"), " ")
        return StaticLayout.Builder.obtain(clean, 0, clean.length, body, width)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .build()
    }

    private fun indexAt(x: Float, y: Float): Int {
        for (i in clips.indices) {
            cardBounds(i, rect)
            if (rect.contains(x, y)) return i
        }
        return -1
    }

    override fun onContentPress(x: Float, y: Float) {
        pressedIndex = if (x < 0) -1 else indexAt(x, y)
        if (pressedIndex >= 0) host?.onPanelFeedback()
        invalidate()
    }

    override fun onContentTap(x: Float, y: Float) {
        val i = indexAt(x, y)
        if (i >= 0) onPaste?.invoke(clips[i].text)
    }

    override fun onContentLongPress(x: Float, y: Float): Boolean {
        val i = indexAt(x, y)
        if (i < 0) return false
        val clip = clips[i]
        history.togglePin(clip)
        layouts.clear()
        host?.onPanelFeedback()
        flash(if (clip.pinned) "Pinned" else "Unpinned")
        refresh()
        return true
    }

    private fun flash(message: String) {
        note = message
        noteUntil = android.os.SystemClock.uptimeMillis() + 1500
        invalidate()
        postDelayed({ invalidate() }, 1550)
    }

    override fun onLongPressUp(x: Float, y: Float) {
        pressedIndex = -1
        invalidate()
    }
}
