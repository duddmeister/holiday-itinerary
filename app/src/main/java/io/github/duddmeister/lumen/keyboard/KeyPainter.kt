package io.github.duddmeister.lumen.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import io.github.duddmeister.lumen.theme.KeyboardTheme
import io.github.duddmeister.lumen.ui.Colors
import io.github.duddmeister.lumen.ui.dp

/** Corner styles offered in settings. */
enum class KeyShape(val id: String, val title: String, val radiusDp: Float) {
    ROUNDED("rounded", "Rounded", 9f),
    SOFT("soft", "Soft", 14f),
    PILL("pill", "Pill", 100f),
    SQUARE("square", "Crisp", 4f);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: ROUNDED
    }
}

/**
 * Paints keys and keyboard backgrounds. Shared by the live keyboard, theme
 * previews and the settings gallery so they always look identical.
 */
class KeyPainter(private val context: Context) {
    var theme: KeyboardTheme? = null
    var shape: KeyShape = KeyShape.ROUNDED
    /** Draw key shapes; when false, labels float on the background (borderless look). */
    var keyBackgrounds = true
    /** Multiplies all sizes; used for small previews. */
    var scale = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bounds = Rect()
    private val matrix = Matrix()
    private var keyGradient: LinearGradient? = null
    private var sheenGradient: LinearGradient? = null
    private var accentGradient: LinearGradient? = null
    private var gradientTheme: KeyboardTheme? = null

    val labelTypeface: Typeface = if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(Typeface.SANS_SERIF, 450, false)
    } else {
        Typeface.create("sans-serif", Typeface.NORMAL)
    }
    val mediumTypeface: Typeface = if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(Typeface.SANS_SERIF, 500, false)
    } else {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private companion object {
        val SMALL_GLYPHS = setOf(".", ",", ";", ":", "'", "\"", "-")
    }

    fun radiusFor(height: Float): Float = minOf(context.dp(shape.radiusDp) * scale, height / 2f)

    private fun ensureGradients(t: KeyboardTheme) {
        if (gradientTheme === t) return
        gradientTheme = t
        keyGradient = if (t.keyTop != t.key) {
            LinearGradient(0f, 0f, 0f, 1f, t.keyTop, t.key, Shader.TileMode.CLAMP)
        } else null
        sheenGradient = if (Color.alpha(t.highlight) > 0) {
            LinearGradient(0f, 0f, 0f, 1f, intArrayOf(t.highlight, Colors.fade(t.highlight, 0.25f), 0), floatArrayOf(0f, 0.35f, 0.6f), Shader.TileMode.CLAMP)
        } else null
        accentGradient = if (t.accent != t.accentEnd) {
            LinearGradient(0f, 0f, 1f, 1f, t.accent, t.accentEnd, Shader.TileMode.CLAMP)
        } else null
    }

    /** Positions a unit gradient over [r]. */
    private fun place(shader: Shader, r: RectF, diagonal: Boolean = false) {
        matrix.setScale(if (diagonal) r.width() else 1f, r.height())
        matrix.postTranslate(r.left, r.top)
        shader.setLocalMatrix(matrix)
    }

    /**
     * Draws one key. [press] is the animated press amount (0..1), [shifted] picks
     * uppercase letters, [icon]/[label] override what the key shows.
     */
    fun drawKey(
        canvas: Canvas,
        key: Key,
        press: Float,
        shifted: Boolean,
        icon: Icon? = key.icon,
        label: String? = null,
        showHint: Boolean = true,
        spaceLabel: String? = null,
    ) {
        val t = theme ?: return
        ensureGradients(t)
        val b = key.bounds
        val squash = 1f - 0.045f * press
        rect.set(b)
        if (press != 0f) {
            val dx = b.width() * (1f - squash) / 2f
            val dy = b.height() * (1f - squash) / 2f
            rect.inset(dx, dy)
        }
        val radius = radiusFor(rect.height())
        val base: Int
        val pressed: Int
        when (key.style) {
            KeyStyle.FUNCTIONAL -> { base = t.functional; pressed = t.functionalPressed }
            KeyStyle.ACCENT -> { base = t.accent; pressed = t.accentPressed }
            else -> { base = t.key; pressed = t.keyPressed }
        }
        val drawShape = keyBackgrounds || key.style == KeyStyle.ACCENT || press > 0f

        if (drawShape) {
            // Soft lip under opaque keys gives depth; glassy translucent keys skip it.
            if (Color.alpha(t.shadow) > 0 && Color.alpha(base) == 255 && keyBackgrounds) {
                fill.shader = null
                fill.color = t.shadow
                val lip = context.dp(1.3f) * scale
                rect.offset(0f, lip)
                canvas.drawRoundRect(rect, radius, radius, fill)
                rect.offset(0f, -lip)
            }
            val shapeAlpha = if (keyBackgrounds || key.style == KeyStyle.ACCENT) 1f else press
            val gradient = when (key.style) {
                KeyStyle.ACCENT -> accentGradient
                KeyStyle.CHARACTER, KeyStyle.SPACE -> if (press <= 0f && keyBackgrounds) keyGradient else null
                KeyStyle.FUNCTIONAL -> null
            }
            if (gradient != null) {
                // The gradient colors carry their own alpha; the paint must stay opaque.
                place(gradient, rect, diagonal = key.style == KeyStyle.ACCENT)
                fill.shader = gradient
                fill.color = Color.WHITE
            } else {
                fill.shader = null
                fill.color = Colors.fade(Colors.lerp(base, pressed, press), shapeAlpha)
            }
            canvas.drawRoundRect(rect, radius, radius, fill)
            fill.shader = null
            if (gradient != null && key.style == KeyStyle.ACCENT && press > 0f) {
                fill.color = Colors.withAlpha(Color.WHITE, 0.2f * press)
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            val sheen = sheenGradient
            if (sheen != null && keyBackgrounds && key.style != KeyStyle.ACCENT) {
                place(sheen, rect)
                stroke.shader = sheen
                stroke.strokeWidth = context.dp(1f) * scale
                val inset = stroke.strokeWidth / 2f
                rect.inset(inset, inset)
                canvas.drawRoundRect(rect, radius - inset, radius - inset, stroke)
                rect.inset(-inset, -inset)
                stroke.shader = null
            }
        }

        val labelColor = when (key.style) {
            KeyStyle.ACCENT -> t.onAccent
            KeyStyle.FUNCTIONAL -> t.functionalLabel
            else -> t.label
        }
        val cx = rect.centerX()
        val cy = rect.centerY()
        val h = b.height()
        if (icon != null) {
            Icons.draw(canvas, icon, cx, cy, minOf(h * 0.5f, b.width() * 0.62f), labelColor, iconPaint)
            return
        }
        if (key.code == KeyCodes.SPACE) {
            if (spaceLabel != null) {
                text.typeface = mediumTypeface
                text.textSize = h * 0.235f
                text.color = t.hint
                drawCentered(canvas, spaceLabel, cx, cy, useOwnBounds = false)
            }
            return
        }
        var display = label ?: key.label
        if (shifted && key.isLetter) display = display.uppercase()
        val functionalText = key.style == KeyStyle.FUNCTIONAL && display.length > 1
        text.color = labelColor
        text.typeface = if (functionalText) mediumTypeface else labelTypeface
        text.textSize = when {
            functionalText -> h * 0.3f
            key.caption != null -> h * 0.42f
            display.length > 1 -> h * 0.36f
            display in SMALL_GLYPHS -> h * 0.62f
            else -> minOf(h * 0.48f, b.width() * 0.68f)
        }
        val captionGap = if (key.caption != null) h * 0.1f else 0f
        // "_" keeps its baseline position; centred on its own ink it would read as a dash.
        drawCentered(canvas, display, cx, cy - captionGap, useOwnBounds = !key.isLetter && display != "_")
        key.caption?.let {
            text.typeface = mediumTypeface
            text.textSize = h * 0.17f
            text.color = t.hint
            drawCentered(canvas, it, cx, cy + h * 0.24f, useOwnBounds = false)
        }
        if (showHint && key.hint != null) {
            text.typeface = mediumTypeface
            text.textSize = h * 0.2f
            text.color = t.hint
            val pad = minOf(context.dp(5f) * scale, b.width() * 0.15f)
            text.textAlign = Paint.Align.RIGHT
            // Hints share a baseline so "_" sits low and "'" sits high, as in print.
            text.getTextBounds("H", 0, 1, bounds)
            canvas.drawText(key.hint, rect.right - pad, rect.top + pad - bounds.top, text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    /** Draws [s] centered on (cx, cy). Letters share a baseline; symbols center on their own ink. */
    fun drawCentered(canvas: Canvas, s: String, cx: Float, cy: Float, useOwnBounds: Boolean, paint: Paint = text) {
        val baseline = if (useOwnBounds) {
            paint.getTextBounds(s, 0, s.length, bounds)
            cy - (bounds.top + bounds.bottom) / 2f
        } else {
            val probe = if (s.any { it.isUpperCase() || it.isDigit() }) "H" else "x"
            paint.getTextBounds(probe, 0, 1, bounds)
            cy - (bounds.top + bounds.bottom) / 2f
        }
        canvas.drawText(s, cx, baseline, paint)
    }

    /** Paints the theme background with its ambient glows across a [width] x [height] area. */
    fun drawBackground(canvas: Canvas, width: Float, height: Float, top: Float = 0f) {
        val t = theme ?: return
        fill.shader = LinearGradient(0f, top, 0f, top + height, t.background, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, width, top + height, fill)
        for (g in t.glows) {
            val r = g.radius * width
            if (r <= 0f) continue
            fill.shader = RadialGradient(g.x * width, top + g.y * height, r, g.color, Colors.fade(g.color, 0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top, width, top + height, fill)
        }
        fill.shader = null
    }

    /** A light bloom that spreads from a pressed key; [strength] fades it out. */
    fun drawBloom(canvas: Canvas, x: Float, y: Float, radius: Float, strength: Float) {
        val t = theme ?: return
        if (strength <= 0f || radius <= 0f) return
        val alpha = if (t.dark) 0.42f else 0.3f
        val c = Colors.withAlpha(t.glow, alpha * strength)
        fill.shader = RadialGradient(x, y, radius, intArrayOf(c, Colors.fade(c, 0.35f), Colors.fade(c, 0f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, fill)
        fill.shader = null
    }
}
