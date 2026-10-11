package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import io.github.duddmeister.lumen.data.Gif
import io.github.duddmeister.lumen.data.GifImages
import io.github.duddmeister.lumen.data.GifPage
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.KeyPainter

/**
 * GIF panel: trending GIFs, or the results of a search, as a staggered grid of
 * looping previews. Tap one to send it.
 */
@SuppressLint("ViewConstructor")
class GifPanelView(context: Context, painter: KeyPainter, private val source: Source) : PanelView(context, painter) {

    interface Source {
        /** Why GIFs cannot load (no API key, turned off), or null when they can. */
        val unavailable: String?

        /** Credit for the GIF service, shown in the header. */
        val attribution: String

        /** Loads a page: trending when [query] is null. [done] runs on the main thread with the page or an error. */
        fun load(query: String?, pos: String?, done: (GifPage?, String?) -> Unit)

        /** The preview for [gif], or null while it loads; [ready] fires when it arrives. */
        fun preview(gif: Gif, ready: () -> Unit): Drawable?
    }

    var onGif: ((Gif) -> Unit)? = null

    /** The search shown, or null for trending. */
    var query: String? = null
        private set
    private val gifs = ArrayList<Gif>()
    private val tiles = ArrayList<RectF>()
    private var next: String? = null
    private var loading = false
    private var error: String? = null
    private var generation = 0
    private var columns = 2
    private var pressed = -1
    private var sending: Gif? = null
    private var notice: String? = null
    private var noticeUntil = 0L

    private val redraw: () -> Unit = { invalidate() }
    private val running = HashSet<Drawable>()
    private val drawnNow = HashSet<Drawable>()
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = android.graphics.Rect()
    private val searchWidth = context.dp(50f)
    private val gap = context.dp(6f)
    private val radius = context.dp(10f)

    /** Shows trending GIFs, or the results for [query]. */
    fun show(query: String?) {
        this.query = query?.trim()?.takeIf { it.isNotEmpty() }
        gifs.clear()
        next = null
        error = null
        pressed = -1
        sending = null
        generation++
        scrollToContent(0f, animate = false)
        loading = false
        if (source.unavailable == null) load()
        layoutTiles()
        invalidate()
    }

    /** Marks [gif] as being sent (dims it), or clears the mark. */
    fun setSending(gif: Gif?) {
        sending = gif
        invalidate()
    }

    /** Shows [message] over the grid for a moment. */
    fun notice(message: String) {
        notice = message
        noticeUntil = android.os.SystemClock.uptimeMillis() + NOTICE_MS
        invalidate()
        postDelayed({ invalidate() }, NOTICE_MS + 50)
    }

    private fun load() {
        if (loading) return
        loading = true
        val gen = generation
        source.load(query, next) { page, message ->
            if (gen != generation) return@load
            loading = false
            if (page != null) {
                gifs.addAll(page.gifs)
                next = page.next
            } else {
                error = message ?: "GIFs could not load."
            }
            layoutTiles()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutTiles()
    }

    /** Staggered columns: each GIF goes under the shortest column so far. */
    private fun layoutTiles() {
        tiles.clear()
        if (width == 0) return
        columns = maxOf(2, (width / context.dp(160f)).toInt())
        val columnWidth = (width - gap * (columns + 1)) / columns
        val heights = FloatArray(columns) { gap }
        for (g in gifs) {
            var c = 0
            for (i in 1 until columns) if (heights[i] < heights[c]) c = i
            val h = (columnWidth * g.previewHeight / g.previewWidth).coerceIn(columnWidth * 0.45f, columnWidth * 1.6f)
            val x = gap + c * (columnWidth + gap)
            tiles.add(RectF(x, heights[c], x + columnWidth, heights[c] + h))
            heights[c] += h + gap
        }
        contentHeight = (heights.maxOrNull() ?: 0f) + if (next != null) context.dp(48f) else context.dp(8f)
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    override fun onScrollChanged() {
        // Fetch the next page before the end comes into view.
        if (next != null && error == null && scroll + content.height() * 2.5f > contentHeight) load()
    }

    override fun drawHeader(canvas: Canvas, bounds: RectF) {
        val t = painter.theme ?: return
        Icons.draw(canvas, Icon.SEARCH, searchWidth / 2f + context.dp(2f), bounds.centerY(), context.dp(21f), t.stripSecondary, paint)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = context.dp(11.5f)
        text.typeface = painter.mediumTypeface
        text.color = t.stripSecondary
        text.letterSpacing = 0.04f
        text.getTextBounds("Hx", 0, 2, tmp)
        val attribution = source.attribution
        canvas.drawText(attribution, bounds.right - context.dp(14f), bounds.centerY() - tmp.exactCenterY(), text)
        val attributionWidth = text.measureText(attribution)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT
        text.textSize = context.dp(15f)
        text.color = t.stripText
        text.getTextBounds("Hx", 0, 2, tmp)
        val title = query?.let { "“$it”" } ?: "Trending"
        val avail = bounds.width() - searchWidth - attributionWidth - context.dp(30f)
        canvas.drawText(TextUtils.ellipsize(title, text, avail, TextUtils.TruncateAt.END).toString(), searchWidth + context.dp(4f), bounds.centerY() - tmp.exactCenterY(), text)
    }

    override fun onHeaderTap(x: Float, y: Float) {
        host?.onPanelFeedback()
        when {
            x < searchWidth -> host?.onPanelSearch()
            query != null -> show(null) // back to trending
        }
    }

    override fun drawContent(canvas: Canvas, top: Float, bottom: Float) {
        val t = painter.theme ?: return
        val message = source.unavailable ?: error ?: when {
            gifs.isEmpty() && loading -> "Loading GIFs…"
            gifs.isEmpty() && query != null -> "No GIFs for “$query”"
            else -> null
        }
        if (message != null) {
            drawMessage(canvas, message, top + content.height() / 2f, t.stripSecondary)
            stopUnused()
            return
        }
        drawnNow.clear()
        for ((i, tile) in tiles.withIndex()) {
            if (tile.bottom < top || tile.top > bottom) continue
            val gif = gifs[i]
            clip.reset()
            clip.addRoundRect(tile, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            paint.color = Colors.withAlpha(t.stripText, 0.07f)
            canvas.drawRect(tile, paint)
            val d = source.preview(gif, redraw)
            if (d != null) {
                fitCover(d, tile)
                d.callback = this
                drawnNow.add(d)
                GifImages.animate(d, true)
                d.draw(canvas)
            }
            if (i == pressed || gif === sending) {
                paint.color = Colors.withAlpha(0xFF000000.toInt(), if (gif === sending) 0.45f else 0.18f)
                canvas.drawRect(tile, paint)
            }
            canvas.restore()
            if (gif === sending) drawMessage(canvas, "Sending…", tile.centerY(), 0xFFFFFFFF.toInt(), tile.centerX(), tile.width())
        }
        stopUnused()
        if (loading && gifs.isNotEmpty()) drawMessage(canvas, "Loading more…", contentHeight - context.dp(24f), t.stripSecondary)
        val n = notice
        if (n != null && android.os.SystemClock.uptimeMillis() < noticeUntil) {
            val cy = bottom - context.dp(28f)
            text.textSize = context.dp(14f)
            val w = minOf(text.measureText(n) + context.dp(36f), width - context.dp(32f))
            pill.set(width / 2f - w / 2f, cy - context.dp(17f), width / 2f + w / 2f, cy + context.dp(17f))
            paint.color = Colors.opaque(t.popup)
            canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, paint)
            drawMessage(canvas, n, cy, t.popupLabel, maxWidth = w - context.dp(24f))
        } else {
            notice = null
        }
    }

    private val pill = RectF()

    /** Previews scrolled out of view stop animating. */
    private fun stopUnused() {
        for (d in running) if (d !in drawnNow) GifImages.animate(d, false)
        running.clear()
        running.addAll(drawnNow)
        drawnNow.clear()
    }

    private fun fitCover(d: Drawable, tile: RectF) {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: tile.width().toInt()
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: tile.height().toInt()
        val scale = maxOf(tile.width() / w, tile.height() / h)
        val dw = w * scale
        val dh = h * scale
        val left = tile.centerX() - dw / 2f
        val top = tile.centerY() - dh / 2f
        d.setBounds(left.toInt(), top.toInt(), (left + dw).toInt() + 1, (top + dh).toInt() + 1)
    }

    private fun drawMessage(canvas: Canvas, message: String, cy: Float, color: Int, cx: Float = width / 2f, maxWidth: Float = width - context.dp(48f)) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = context.dp(14f)
        text.typeface = painter.labelTypeface
        text.color = color
        text.getTextBounds("Hx", 0, 2, tmp)
        val lines = wrap(message, maxWidth)
        val lineHeight = context.dp(20f)
        var y = cy - (lines.size - 1) * lineHeight / 2f - tmp.exactCenterY()
        for (line in lines) {
            canvas.drawText(line, cx, y, text)
            y += lineHeight
        }
    }

    private fun wrap(message: String, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in message.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && text.measureText(candidate) > maxWidth) {
                lines.add(line)
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines
    }

    private fun tileAt(x: Float, y: Float): Int = tiles.indexOfFirst { it.contains(x, y) }

    override fun onContentPress(x: Float, y: Float) {
        pressed = if (x < 0 || sending != null) -1 else tileAt(x, y)
        if (pressed >= 0) host?.onPanelFeedback()
        invalidate()
    }

    override fun onContentTap(x: Float, y: Float) {
        if (sending != null) return
        if (error != null) {
            // Tap the message to try again.
            error = null
            load()
            invalidate()
            return
        }
        val i = tileAt(x, y)
        if (i >= 0) onGif?.invoke(gifs[i])
    }

    override fun verifyDrawable(who: Drawable): Boolean = who in running || super.verifyDrawable(who)

    override fun invalidateDrawable(drawable: Drawable) {
        if (drawable in running) invalidate() else super.invalidateDrawable(drawable)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            for (d in running) GifImages.animate(d, false)
            running.clear()
        }
    }

    override fun onDetachedFromWindow() {
        for (d in running) GifImages.animate(d, false)
        running.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NOTICE_MS = 2200L
    }
}
