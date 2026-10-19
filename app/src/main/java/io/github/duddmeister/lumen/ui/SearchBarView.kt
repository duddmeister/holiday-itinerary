package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.KeyPainter
import kotlin.math.abs

/**
 * Takes the strip's place while searching emoji, GIFs or the web: the keys
 * below type into this bar instead of the app. Emoji search adds a second row
 * of matches that fills in as you type.
 */
@SuppressLint("ViewConstructor")
class SearchBarView(context: Context, private val painter: KeyPainter) : View(context) {

    enum class Kind(val icon: Icon, val hint: String) {
        EMOJI(Icon.EMOJI, "Search emoji"),
        GIF(Icon.GIF, "Search GIFs"),
        WEB(Icon.SEARCH, "Search the web"),
    }

    interface Listener {
        fun onSearchBack()
        fun onSearchClear()
        fun onSearchEmoji(emoji: String)
    }

    var listener: Listener? = null
    var kind = Kind.WEB
        private set
    var query = ""
        private set
    private var hint = kind.hint
    private var results: List<String> = emptyList()
    private var resultsScroll = 0f
    private var typedAt = 0L

    private val rowHeight = context.dp(SuggestionStripView.HEIGHT_DP.toFloat())
    private val resultsHeight = context.dp(52f)
    private val buttonSize = context.dp(44f)
    private val cell = context.dp(48f)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val field = RectF()
    private val tmp = android.graphics.Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private enum class Target { BACK, CLEAR, FIELD, RESULT }
    private var pressed: Target? = null
    private var pressedResult = -1
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false

    /** Starts a fresh search of [kind], with [hint] in the empty field. */
    fun start(kind: Kind, initial: String = "", hint: String = kind.hint) {
        val resize = (kind == Kind.EMOJI) != (this.kind == Kind.EMOJI)
        this.kind = kind
        this.hint = hint
        query = initial
        results = emptyList()
        resultsScroll = 0f
        typedAt = SystemClock.uptimeMillis()
        if (resize) requestLayout()
        invalidate()
    }

    fun setQuery(q: String) {
        query = q
        typedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun setResults(r: List<String>) {
        results = r
        resultsScroll = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = rowHeight + if (kind == Kind.EMOJI) resultsHeight else 0f
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val t = painter.theme ?: return
        val cy = rowHeight / 2f
        // Back
        if (pressed == Target.BACK) {
            paint.color = Colors.withAlpha(t.stripText, 0.09f)
            canvas.drawCircle(buttonSize / 2f + context.dp(4f), cy, context.dp(16f), paint)
        }
        Icons.draw(canvas, Icon.ARROW_LEFT, buttonSize / 2f + context.dp(4f), cy, context.dp(22f), t.stripSecondary, paint)

        // Field
        field.set(buttonSize + context.dp(6f), cy - context.dp(15f), width - context.dp(10f), cy + context.dp(15f))
        paint.color = Colors.withAlpha(t.stripText, 0.08f)
        canvas.drawRoundRect(field, field.height() / 2f, field.height() / 2f, paint)
        Icons.draw(canvas, kind.icon, field.left + context.dp(19f), cy, context.dp(18f), t.accent, paint)
        val textLeft = field.left + context.dp(36f)
        val clearSpace = if (query.isNotEmpty()) context.dp(36f) else context.dp(12f)
        val avail = field.right - clearSpace - textLeft
        text.textSize = context.dp(16f)
        text.typeface = painter.labelTypeface
        text.textAlign = Paint.Align.LEFT
        text.getTextBounds("Hx", 0, 2, tmp)
        val baseline = cy - tmp.exactCenterY()
        if (query.isEmpty()) {
            text.color = t.stripSecondary
            canvas.drawText(TextUtils.ellipsize(hint, text, avail, TextUtils.TruncateAt.END).toString(), textLeft, baseline, text)
        } else {
            text.color = t.stripText
            // Long queries keep their end in view, where the typing happens.
            val shown = TextUtils.ellipsize(query, text, avail, TextUtils.TruncateAt.START).toString()
            canvas.drawText(shown, textLeft, baseline, text)
            val caretX = textLeft + text.measureText(shown) + context.dp(1.5f)
            drawCaret(canvas, caretX, cy, t.accent)
            if (pressed == Target.CLEAR) {
                paint.color = Colors.withAlpha(t.stripText, 0.12f)
                canvas.drawCircle(field.right - context.dp(18f), cy, context.dp(14f), paint)
            }
            Icons.draw(canvas, Icon.CLEAR, field.right - context.dp(18f), cy, context.dp(18f), t.stripSecondary, paint)
        }
        if (query.isEmpty()) drawCaret(canvas, textLeft - context.dp(2f), cy, t.accent)

        if (kind == Kind.EMOJI) drawResults(canvas)
    }

    private fun drawCaret(canvas: Canvas, x: Float, cy: Float, color: Int) {
        // Solid while typing, then blinking.
        val since = SystemClock.uptimeMillis() - typedAt
        if (since < 500 || (since / 530) % 2 == 0L) {
            paint.color = color
            canvas.drawRoundRect(x, cy - context.dp(9f), x + context.dp(2f), cy + context.dp(9f), context.dp(1f), context.dp(1f), paint)
        }
        if (isShown) postInvalidateDelayed(530 - since % 530)
    }

    private fun drawResults(canvas: Canvas) {
        val t = painter.theme ?: return
        val top = rowHeight
        val cy = top + resultsHeight / 2f
        if (results.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.color = t.stripSecondary
            text.textSize = context.dp(14f)
            text.typeface = painter.labelTypeface
            text.getTextBounds("Hx", 0, 2, tmp)
            val message = if (query.isBlank()) "Type to find emoji" else "No emoji for “${query.trim()}”"
            canvas.drawText(TextUtils.ellipsize(message, text, width - context.dp(32f), TextUtils.TruncateAt.END).toString(), width / 2f, cy - tmp.exactCenterY(), text)
            return
        }
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), top + resultsHeight)
        text.textAlign = Paint.Align.CENTER
        text.textSize = context.dp(27f)
        text.color = 0xFF000000.toInt()
        text.getTextBounds("😀", 0, 2, tmp)
        val first = (resultsScroll / cell).toInt().coerceAtLeast(0)
        val last = minOf(results.lastIndex, ((resultsScroll + width) / cell).toInt() + 1)
        for (i in first..last) {
            val cx = context.dp(8f) + cell * (i + 0.5f) - resultsScroll
            if (i == pressedResult) {
                paint.color = Colors.withAlpha(t.stripText, 0.1f)
                canvas.drawCircle(cx, cy, context.dp(22f), paint)
            }
            canvas.drawText(results[i], cx, cy - tmp.exactCenterY(), text)
        }
        canvas.restore()
    }

    private fun maxScroll() = maxOf(0f, context.dp(16f) + cell * results.size - width)

    private fun resultAt(x: Float): Int {
        val i = ((x + resultsScroll - context.dp(8f)) / cell).toInt()
        return if (x >= context.dp(8f) - resultsScroll && i in results.indices) i else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                dragging = false
                pressedResult = -1
                pressed = when {
                    event.y >= rowHeight && kind == Kind.EMOJI -> {
                        pressedResult = resultAt(event.x)
                        Target.RESULT
                    }
                    event.x < buttonSize + context.dp(4f) -> Target.BACK
                    query.isNotEmpty() && event.x > field.right - context.dp(36f) -> Target.CLEAR
                    else -> Target.FIELD
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pressed == Target.RESULT) {
                if (!dragging && abs(event.x - downX) > touchSlop) {
                    dragging = true
                    pressedResult = -1
                }
                if (dragging) {
                    resultsScroll = (resultsScroll - (event.x - lastX)).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> {
                val target = pressed
                val result = pressedResult
                pressed = null
                pressedResult = -1
                invalidate()
                when (target) {
                    Target.BACK -> listener?.onSearchBack()
                    Target.CLEAR -> listener?.onSearchClear()
                    Target.RESULT -> if (!dragging && result >= 0) results.getOrNull(result)?.let { listener?.onSearchEmoji(it) }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                pressedResult = -1
                invalidate()
            }
        }
        return true
    }
}
