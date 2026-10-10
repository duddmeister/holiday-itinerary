package io.github.duddmeister.lumen.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import io.github.duddmeister.lumen.theme.KeyboardTheme
import io.github.duddmeister.lumen.ui.dp

/**
 * A miniature, non-interactive keyboard used for theme previews. It uses the
 * same painter as the real keyboard, scaled down, so previews never lie.
 */
class KeyboardPreview(private val context: Context) {
    private val painter = KeyPainter(context)
    private val layout = Layouts.build(KeyboardMode.TEXT, LayoutOptions(symbolHints = false))
    private val clip = Path()
    private var laidOut = RectF()

    /**
     * Draws [theme] filling [bounds], clipped to rounded corners of [corner] px.
     * [pressed] names a key label to show pressed by [press] (0..1), with its light bloom.
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        theme: KeyboardTheme,
        shape: KeyShape,
        corner: Float,
        keyBackgrounds: Boolean = true,
        pressed: String? = null,
        press: Float = 0f,
    ) {
        val scale = bounds.width() / context.dp(393f)
        painter.theme = theme
        painter.shape = shape
        painter.scale = scale
        painter.keyBackgrounds = keyBackgrounds
        if (laidOut.width() != bounds.width() || laidOut.height() != bounds.height()) {
            laidOut.set(0f, 0f, bounds.width(), bounds.height())
            val top = context.dp(5f) * scale
            val bottom = context.dp(6f) * scale
            val rowHeight = (bounds.height() - top - bottom) / layout.rows.size
            layout.layout(bounds.width().toInt(), rowHeight, context.dp(4f) * scale, top, context.dp(5.5f) * scale, rowHeight / 6f)
        }
        canvas.save()
        clip.reset()
        clip.addRoundRect(bounds, corner, corner, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.translate(bounds.left, bounds.top)
        painter.drawBackground(canvas, bounds.width(), bounds.height())
        val active = if (pressed != null && press > 0f) layout.keys.firstOrNull { it.label == pressed } else null
        if (active != null) {
            painter.drawBloom(canvas, active.bounds.centerX(), active.bounds.centerY(), layout.keyWidth * 1.8f, press)
        }
        for (key in layout.keys) {
            painter.drawKey(canvas, key, if (key === active) press else 0f, false, showHint = false, spaceLabel = null)
        }
        canvas.restore()
    }
}
