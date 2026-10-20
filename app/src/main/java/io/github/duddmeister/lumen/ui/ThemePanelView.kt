package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.KeyPainter
import io.github.duddmeister.lumen.keyboard.KeyShape
import io.github.duddmeister.lumen.keyboard.KeyboardPreview
import io.github.duddmeister.lumen.theme.KeyboardTheme
import io.github.duddmeister.lumen.theme.Themes
import kotlin.math.ceil

/** In-keyboard theme switcher: live miniature previews, applied instantly. */
@SuppressLint("ViewConstructor")
class ThemePanelView(
    context: Context,
    painter: KeyPainter,
    private val resolve: (String) -> KeyboardTheme,
) : PanelView(context, painter) {

    var onTheme: ((String) -> Unit)? = null
    var onShape: ((KeyShape) -> Unit)? = null
    var selectedId: String = Themes.DEFAULT
        set(value) { field = value; invalidate() }
    var shape: KeyShape = KeyShape.ROUNDED
        set(value) { field = value; invalidate() }

    private val ids = Themes.catalogue
    private val previews = HashMap<String, KeyboardPreview>()
    private val columns = 3
    private val margin = context.dp(12f)
    private val gap = context.dp(10f)
    private var cardWidth = 0f
    private var previewHeight = 0f
    private var cardHeight = 0f
    private var pressed = -1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cardWidth = (w - 2 * margin - (columns - 1) * gap) / columns
        previewHeight = cardWidth / 1.72f
        cardHeight = previewHeight + context.dp(28f)
        contentHeight = margin / 2f + ceil(ids.size / columns.toFloat()) * (cardHeight + gap)
    }

    private fun cardBounds(i: Int, out: RectF) {
        val col = i % columns
        val row = i / columns
        val left = margin + col * (cardWidth + gap)
        val top = margin / 2f + row * (cardHeight + gap)
        out.set(left, top, left + cardWidth, top + previewHeight)
    }

    override fun drawHeader(canvas: Canvas, bounds: RectF) {
        val t = painter.theme ?: return
        text.color = t.stripText
        text.typeface = painter.mediumTypeface
        text.textSize = context.dp(15.5f)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("Themes", context.dp(16f), bounds.centerY() + context.dp(5.5f), text)
        // Key shape chip: tap to cycle through shapes.
        text.textSize = context.dp(13.5f)
        text.typeface = painter.labelTypeface
        val label = "Keys: ${shape.title}"
        val w = text.measureText(label) + context.dp(28f)
        rect.set(bounds.right - w - context.dp(12f), bounds.centerY() - context.dp(15f), bounds.right - context.dp(12f), bounds.centerY() + context.dp(15f))
        paint.color = Colors.withAlpha(t.stripText, 0.09f)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        text.color = t.stripText
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(label, rect.centerX(), rect.centerY() + context.dp(4.8f), text)
    }

    override fun onHeaderTap(x: Float, y: Float) {
        if (x > width / 2f) {
            val next = KeyShape.entries[(shape.ordinal + 1) % KeyShape.entries.size]
            shape = next
            host?.onPanelFeedback()
            onShape?.invoke(next)
        }
    }

    override fun drawContent(canvas: Canvas, top: Float, bottom: Float) {
        val t = painter.theme ?: return
        val look = painter.look.copy(shape = shape)
        for ((i, id) in ids.withIndex()) {
            cardBounds(i, rect)
            if (rect.bottom + context.dp(28f) < top || rect.top > bottom) continue
            val theme = resolve(id)
            val preview = previews.getOrPut(id) { KeyboardPreview(context) }
            val corner = context.dp(12f)
            if (i == pressed) {
                canvas.save()
                canvas.scale(0.96f, 0.96f, rect.centerX(), rect.centerY())
            }
            preview.draw(canvas, rect, theme, look, corner)
            if (id == selectedId) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dp(2.5f)
                paint.color = t.accent
                val inset = -context.dp(3f)
                val ring = RectF(rect).apply { inset(inset, inset) }
                canvas.drawRoundRect(ring, corner + 3f, corner + 3f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(rect.right - context.dp(4f), rect.top + context.dp(4f), context.dp(10f), paint)
                Icons.draw(canvas, Icon.DONE, rect.right - context.dp(4f), rect.top + context.dp(4f), context.dp(13f), t.onAccent, paint)
            }
            if (i == pressed) canvas.restore()
            text.color = if (id == selectedId) t.stripText else t.stripSecondary
            text.typeface = if (id == selectedId) painter.mediumTypeface else painter.labelTypeface
            text.textSize = context.dp(12.5f)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(theme.name, rect.centerX(), rect.bottom + context.dp(19f), text)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        for (i in ids.indices) {
            cardBounds(i, rect)
            rect.bottom += context.dp(24f)
            if (rect.contains(x, y)) return i
        }
        return -1
    }

    override fun onContentPress(x: Float, y: Float) {
        pressed = if (x < 0) -1 else indexAt(x, y)
        if (pressed >= 0) host?.onPanelFeedback()
        invalidate()
    }

    override fun onContentTap(x: Float, y: Float) {
        val i = indexAt(x, y)
        if (i >= 0) {
            selectedId = ids[i]
            onTheme?.invoke(ids[i])
        }
    }
}
