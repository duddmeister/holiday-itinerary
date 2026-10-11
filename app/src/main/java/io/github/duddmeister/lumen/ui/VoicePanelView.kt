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
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.Key
import io.github.duddmeister.lumen.keyboard.KeyCodes
import io.github.duddmeister.lumen.keyboard.KeyPainter
import io.github.duddmeister.lumen.keyboard.KeyStyle
import kotlin.math.hypot

/** Voice typing: a big microphone that swells with your voice, and the words as they're heard. */
@SuppressLint("ViewConstructor")
class VoicePanelView(context: Context, painter: KeyPainter) : PanelView(context, painter) {

    /** Tapped the microphone: start or stop listening. */
    var onMic: (() -> Unit)? = null

    /** Which language it listens for, shown in the header. */
    var language = ""
        set(value) { field = value; invalidate() }

    /** Listening now (the microphone is lit). */
    var listening = false
        set(value) { field = value; invalidate() }

    /** How loud the voice is, 0 to 1. */
    var level = 0f
        set(value) { field = value; invalidate() }

    /** The words heard so far, or a note when there are none. */
    var text = ""
        set(value) { field = value; invalidate() }

    /** Whether [text] is what was said rather than a note about it. */
    var heard = false
        set(value) { field = value; invalidate() }

    override val footerKeys: List<Key> = listOf(
        Key(KeyCodes.ALPHABET, "ABC", width = 1.5f, style = KeyStyle.FUNCTIONAL, preview = false),
        Key(KeyCodes.SPACE, " ", width = 7f, style = KeyStyle.SPACE, preview = false),
        Key(KeyCodes.DELETE, icon = Icon.DELETE, width = 1.5f, style = KeyStyle.FUNCTIONAL, preview = false),
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 0f

    private val micRadius get() = minOf(context.dp(36f), content.height() * 0.2f)
    private val micY get() = content.height() * 0.4f

    override fun drawHeader(canvas: Canvas, bounds: RectF) {
        val t = painter.theme ?: return
        title.color = t.stripText
        title.typeface = painter.mediumTypeface
        title.textSize = context.dp(15.5f)
        title.textAlign = Paint.Align.LEFT
        canvas.drawText("Voice typing", context.dp(16f), bounds.centerY() + context.dp(5.5f), title)
        title.color = t.stripSecondary
        title.typeface = painter.labelTypeface
        title.textSize = context.dp(13.5f)
        title.textAlign = Paint.Align.RIGHT
        canvas.drawText(language, bounds.right - context.dp(16f), bounds.centerY() + context.dp(5f), title)
        title.textAlign = Paint.Align.LEFT
    }

    override fun drawContent(canvas: Canvas, top: Float, bottom: Float) {
        val t = painter.theme ?: return
        val cx = width / 2f
        val cy = micY
        val r = micRadius
        // Ease the swell towards the voice level so it breathes rather than flickers.
        val target = if (listening) level else 0f
        shown += (target - shown) * 0.3f
        if (listening || shown > 0.01f) postInvalidateOnAnimation()
        if (listening) {
            paint.color = Colors.withAlpha(t.accent, 0.16f)
            canvas.drawCircle(cx, cy, r * (1.18f + 0.55f * shown), paint)
            paint.color = Colors.withAlpha(t.accent, 0.22f)
            canvas.drawCircle(cx, cy, r * (1.06f + 0.22f * shown), paint)
            paint.color = t.accent
        } else {
            paint.color = Colors.withAlpha(t.stripText, 0.1f)
        }
        canvas.drawCircle(cx, cy, r, paint)
        Icons.draw(canvas, Icon.MIC, cx, cy, r * 1.05f, if (listening) 0xFFFFFFFF.toInt() else t.accent, paint)

        val words = text
        if (words.isEmpty()) return
        body.color = if (heard) t.stripText else t.stripSecondary
        body.typeface = if (heard) painter.mediumTypeface else painter.labelTypeface
        body.textSize = context.dp(if (heard) 17f else 14.5f)
        val avail = (width - context.dp(48f)).toInt().coerceAtLeast(1)
        // Long dictation shows its latest words.
        val tail = if (words.length > 90) "…" + words.takeLast(89) else words
        val layout = StaticLayout.Builder.obtain(tail, 0, tail.length, body, avail)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(context.dp(24f), cy + r * 1.75f)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun onContentTap(x: Float, y: Float) {
        if (hypot(x - width / 2f, y - micY) <= micRadius * 1.7f) onMic?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        contentHeight = content.height()
    }
}
