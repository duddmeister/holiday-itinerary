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

/** How keys are surfaced, whatever the theme. */
enum class KeyFinish(val id: String, val title: String) {
    /** A soft lip beneath, a sheen along the top and a gentle gradient. */
    RAISED("raised", "Raised"),
    /** Solid keys, without depth. */
    FLAT("flat", "Flat"),
    /** A hairline around each key, filled only while it's pressed. */
    OUTLINE("outline", "Outline"),
    /** No keys at all: the labels float on the background. */
    BORDERLESS("borderless", "Borderless");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** The keyboard's typeface: its labels, suggestions and panels. */
enum class LabelFont(val id: String, val title: String) {
    REGULAR("regular", "Regular"),
    LIGHT("light", "Light"),
    BOLD("bold", "Bold"),
    CONDENSED("condensed", "Condensed"),
    MONO("mono", "Mono");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: REGULAR
    }
}

/** How large labels are drawn on their keys. */
enum class LetterSize(val id: String, val title: String, val scale: Float) {
    SMALL("small", "Small", 0.88f),
    MEDIUM("medium", "Medium", 1f),
    LARGE("large", "Large", 1.14f);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/** How keys look on any theme: everything about them but the theme's colours. */
data class KeyLook(
    val shape: KeyShape = KeyShape.ROUNDED,
    val finish: KeyFinish = KeyFinish.RAISED,
    val font: LabelFont = LabelFont.REGULAR,
    val letters: LetterSize = LetterSize.MEDIUM,
)

/**
 * Paints keys and keyboard backgrounds. Shared by the live keyboard, theme
 * previews and the settings gallery so they always look identical.
 */
class KeyPainter(private val context: Context) {
    var theme: KeyboardTheme? = null
    var shape: KeyShape = KeyShape.ROUNDED
    var finish: KeyFinish = KeyFinish.RAISED
    /** Draw key shapes; false for the borderless finish, where labels float on the background. */
    val keyBackgrounds get() = finish != KeyFinish.BORDERLESS
    var font: LabelFont = LabelFont.REGULAR
        set(value) {
            if (field != value) {
                field = value
                labelTypeface = typeface(value, medium = false)
                mediumTypeface = typeface(value, medium = true)
            }
        }
    var letters: LetterSize = LetterSize.MEDIUM
    var look: KeyLook
        get() = KeyLook(shape, finish, font, letters)
        set(value) {
            shape = value.shape
            finish = value.finish
            font = value.font
            letters = value.letters
        }
    /** Multiplies all sizes; used for small previews. */
    var scale = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    /**
     * For the background and blooms, whose gradients carry their own alpha. A paint's
     * alpha also scales its shader, so these never share [fill], which keys leave
     * holding translucent colors.
     */
    private val shaded = Paint(Paint.ANTI_ALIAS_FLAG)
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

    var labelTypeface: Typeface = typeface(LabelFont.REGULAR, medium = false)
        private set
    /** A step heavier than [labelTypeface]: functional keys, the space bar and emphasis. */
    var mediumTypeface: Typeface = typeface(LabelFont.REGULAR, medium = true)
        private set

    private fun typeface(font: LabelFont, medium: Boolean): Typeface {
        if (font == LabelFont.MONO) return Typeface.create(Typeface.MONOSPACE, if (medium) Typeface.BOLD else Typeface.NORMAL)
        val family = if (font == LabelFont.CONDENSED) Typeface.create("sans-serif-condensed", Typeface.NORMAL) else Typeface.SANS_SERIF
        if (Build.VERSION.SDK_INT >= 28) {
            val weight = when (font) {
                LabelFont.LIGHT -> if (medium) 400 else 300
                LabelFont.BOLD -> 700
                else -> if (medium) 500 else 450
            }
            return Typeface.create(family, weight, false)
        }
        return when (font) {
            LabelFont.LIGHT -> Typeface.create(if (medium) "sans-serif" else "sans-serif-light", Typeface.NORMAL)
            LabelFont.BOLD -> Typeface.create(family, Typeface.BOLD)
            LabelFont.CONDENSED -> Typeface.create(family, if (medium) Typeface.BOLD else Typeface.NORMAL)
            else -> Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
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
        val raised = finish == KeyFinish.RAISED
        // Outlined keys are a hairline until pressed; the accent key stays solid in every finish.
        val outlined = finish == KeyFinish.OUTLINE && key.style != KeyStyle.ACCENT

        if (drawShape) {
            // Soft lip under opaque keys gives depth; glassy translucent keys skip it.
            if (raised && Color.alpha(t.shadow) > 0 && Color.alpha(base) == 255) {
                fill.shader = null
                fill.color = t.shadow
                val lip = context.dp(1.3f) * scale
                rect.offset(0f, lip)
                canvas.drawRoundRect(rect, radius, radius, fill)
                rect.offset(0f, -lip)
            }
            val shapeAlpha = if ((keyBackgrounds && !outlined) || key.style == KeyStyle.ACCENT) 1f else press
            val gradient = when (key.style) {
                KeyStyle.ACCENT -> accentGradient
                KeyStyle.CHARACTER, KeyStyle.SPACE -> if (press <= 0f && raised) keyGradient else null
                KeyStyle.FUNCTIONAL -> null
            }
            // Flat keys take the middle of a raised key's gradient.
            val rest = if (finish == KeyFinish.FLAT && key.style != KeyStyle.FUNCTIONAL && key.style != KeyStyle.ACCENT) Colors.lerp(t.keyTop, base, 0.5f) else base
            if (gradient != null) {
                // The gradient colors carry their own alpha; the paint must stay opaque.
                place(gradient, rect, diagonal = key.style == KeyStyle.ACCENT)
                fill.shader = gradient
                fill.color = Color.WHITE
            } else {
                fill.shader = null
                fill.color = Colors.fade(Colors.lerp(rest, pressed, press), shapeAlpha)
            }
            if (Color.alpha(fill.color) > 0 || fill.shader != null) canvas.drawRoundRect(rect, radius, radius, fill)
            fill.shader = null
            if (gradient != null && key.style == KeyStyle.ACCENT && press > 0f) {
                fill.color = Colors.withAlpha(Color.WHITE, 0.2f * press)
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            if (outlined && keyBackgrounds) {
                stroke.shader = null
                stroke.color = Colors.withAlpha(if (key.style == KeyStyle.FUNCTIONAL) t.functionalLabel else t.label, if (t.dark) 0.3f else 0.26f)
                stroke.strokeWidth = context.dp(1.2f) * scale
                val inset = stroke.strokeWidth / 2f
                rect.inset(inset, inset)
                canvas.drawRoundRect(rect, radius - inset, radius - inset, stroke)
                rect.inset(-inset, -inset)
            }
            val sheen = sheenGradient
            if (sheen != null && raised && key.style != KeyStyle.ACCENT) {
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
        text.textSize = letters.scale * when {
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
        shaded.shader = LinearGradient(0f, top, 0f, top + height, t.background, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, width, top + height, shaded)
        for (g in t.glows) {
            val r = g.radius * width
            if (r <= 0f) continue
            shaded.shader = RadialGradient(g.x * width, top + g.y * height, r, g.color, Colors.fade(g.color, 0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top, width, top + height, shaded)
        }
        shaded.shader = null
    }

    /** A light bloom that spreads from a pressed key; [strength] fades it out. */
    fun drawBloom(canvas: Canvas, x: Float, y: Float, radius: Float, strength: Float) {
        val t = theme ?: return
        if (strength <= 0f || radius <= 0f) return
        val alpha = if (t.dark) 0.42f else 0.3f
        val c = Colors.withAlpha(t.glow, alpha * strength)
        shaded.shader = RadialGradient(x, y, radius, intArrayOf(c, Colors.fade(c, 0.35f), Colors.fade(c, 0f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, shaded)
        shaded.shader = null
    }
}
