package io.github.duddmeister.lumen.theme

import android.content.Context
import android.os.Build
import io.github.duddmeister.lumen.ui.Colors

/** A soft radial light painted behind the keys. Coordinates and radius are relative to the width. */
class Glow(val x: Float, val y: Float, val radius: Float, val color: Int)

/** Every color the keyboard paints. Themes are plain data so new ones are cheap to add. */
class KeyboardTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    /** Vertical background gradient, top to bottom. */
    val background: IntArray,
    val glows: List<Glow> = emptyList(),
    val key: Int,
    val keyTop: Int = key,
    val keyPressed: Int,
    val functional: Int,
    val functionalPressed: Int,
    val accent: Int,
    val accentEnd: Int = accent,
    val accentPressed: Int,
    val onAccent: Int,
    val label: Int,
    val functionalLabel: Int = label,
    val hint: Int,
    /** Hairline sheen along the top edge of keys; transparent to disable. */
    val highlight: Int = 0,
    /** The soft "lip" under each key; transparent to disable. */
    val shadow: Int = 0,
    val stripText: Int = label,
    val stripSecondary: Int = hint,
    val divider: Int,
    val popup: Int,
    val popupLabel: Int = label,
    /** Color of the light bloom that spreads from a pressed key. */
    val glow: Int,
    val trailStart: Int = accent,
    val trailEnd: Int = accentEnd,
    /** Wants the app behind to show through, blurred (Android 12+). */
    val translucent: Boolean = false,
) {
    /** A copy with an opaque background, for when window blur is unavailable. */
    fun opaqueFallback(): KeyboardTheme = if (!translucent) this else KeyboardTheme(
        id, name, dark, background.map { Colors.opaque(Colors.lerp(0xFF0F111A.toInt(), Colors.opaque(it), 0.55f)) }.toIntArray(),
        glows, key, keyTop, keyPressed, functional, functionalPressed, accent, accentEnd, accentPressed, onAccent,
        label, functionalLabel, hint, highlight, shadow, stripText, stripSecondary, divider, popup, popupLabel,
        glow, trailStart, trailEnd, translucent = false,
    )

    /**
     * This theme in [choice]'s colours instead of its own: the enter key, highlights, the
     * light that blooms from a pressed key and the glide trail. [Accent.THEME] keeps its own.
     */
    fun withAccent(choice: Accent): KeyboardTheme {
        if (choice == Accent.THEME) return this
        val start = choice.start(dark)
        val end = choice.end(dark)
        return KeyboardTheme(
            id, name, dark, background, glows, key, keyTop, keyPressed, functional, functionalPressed,
            accent = start, accentEnd = end,
            accentPressed = Colors.lerp(start, if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.16f),
            onAccent = choice.on(dark),
            label = label, functionalLabel = functionalLabel, hint = hint, highlight = highlight, shadow = shadow,
            stripText = stripText, stripSecondary = stripSecondary, divider = divider, popup = popup, popupLabel = popupLabel,
            glow = start, trailStart = start, trailEnd = end, translucent = translucent,
        )
    }
}

/**
 * Accent colours that can stand in for a theme's own, each a gradient [from] one colour
 * [to] another with [ink] for the icon on the enter key. Mono is ink on light themes and
 * white on dark ones.
 */
enum class Accent(val id: String, val title: String, private val from: Long, private val to: Long, private val ink: Long) {
    THEME("theme", "Theme", 0, 0, 0),
    VIOLET("violet", "Violet", 0xFF7C5CFF, 0xFFC084FC, 0xFFFFFFFF),
    BLUE("blue", "Blue", 0xFF2563EB, 0xFF38BDF8, 0xFFFFFFFF),
    TEAL("teal", "Teal", 0xFF14B8A6, 0xFF5EEAD4, 0xFF042F2E),
    GREEN("green", "Green", 0xFF22C55E, 0xFFA3E635, 0xFF052E16),
    AMBER("amber", "Amber", 0xFFF59E0B, 0xFFFDE047, 0xFF2B1600),
    CORAL("coral", "Coral", 0xFFFF5A5F, 0xFFFF9A62, 0xFFFFFFFF),
    PINK("pink", "Pink", 0xFFEC4899, 0xFFA855F7, 0xFFFFFFFF),
    MONO("mono", "Mono", 0, 0, 0);

    fun start(dark: Boolean): Int = if (this == MONO) (if (dark) 0xFFFAFAFA else 0xFF18181B).toInt() else from.toInt()
    fun end(dark: Boolean): Int = if (this == MONO) (if (dark) 0xFFD4D4D8 else 0xFF3F3F46).toInt() else to.toInt()
    fun on(dark: Boolean): Int = if (this == MONO) (if (dark) 0xFF0B0B0C else 0xFFFFFFFF).toInt() else ink.toInt()

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: THEME
    }
}

/** The built-in theme catalogue. */
object Themes {
    const val DEFAULT = "aurora"
    const val DYNAMIC = "dynamic"
    const val GLASS = "glass"

    private fun c(v: Long) = v.toInt()

    val aurora = KeyboardTheme(
        id = "aurora", name = "Aurora", dark = true,
        background = intArrayOf(c(0xFF151B3D), c(0xFF0C1027), c(0xFF070A1A)),
        glows = listOf(
            Glow(0.10f, 0.02f, 0.80f, c(0x5C4F3BF0)),
            Glow(0.90f, 0.12f, 0.70f, c(0x4212B5AC)),
            Glow(0.55f, 1.10f, 0.90f, c(0x38C026D3)),
        ),
        key = c(0x17FFFFFF), keyTop = c(0x24FFFFFF), keyPressed = c(0x40FFFFFF),
        functional = c(0x0DFFFFFF), functionalPressed = c(0x2EFFFFFF),
        accent = c(0xFF7C5CFF), accentEnd = c(0xFF2DD4BF), accentPressed = c(0xFF9F87FF), onAccent = c(0xFFFFFFFF),
        label = c(0xFFF4F6FF), functionalLabel = c(0xFFC7CCF2), hint = c(0x99B8BEF0),
        highlight = c(0x30FFFFFF), shadow = c(0x59000000),
        stripText = c(0xFFEEF0FF), stripSecondary = c(0xFFA3A9DA), divider = c(0x1FFFFFFF),
        popup = c(0xFF242B5E), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFF8B7CFF), trailStart = c(0xFF9D8BFF), trailEnd = c(0xFF2DD4BF),
    )

    val glass = KeyboardTheme(
        id = GLASS, name = "Glass", dark = true,
        background = intArrayOf(c(0x8C141824), c(0xA60C0E16)),
        key = c(0x21FFFFFF), keyTop = c(0x30FFFFFF), keyPressed = c(0x4DFFFFFF),
        functional = c(0x12FFFFFF), functionalPressed = c(0x33FFFFFF),
        accent = c(0xF2FFFFFF), accentPressed = c(0xFFFFFFFF), onAccent = c(0xFF0C0E16),
        label = c(0xFFFFFFFF), functionalLabel = c(0xFFE6E8EF), hint = c(0x99FFFFFF),
        highlight = c(0x3DFFFFFF), shadow = c(0x2E000000),
        stripText = c(0xFFFFFFFF), stripSecondary = c(0xB3FFFFFF), divider = c(0x26FFFFFF),
        popup = c(0xF2262A36), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFFFFFFFF), trailStart = c(0xFFFFFFFF), trailEnd = c(0xFFA5B4FC),
        translucent = true,
    )

    val graphite = KeyboardTheme(
        id = "graphite", name = "Graphite", dark = true,
        background = intArrayOf(c(0xFF1D1D20), c(0xFF161618)),
        key = c(0xFF3A3A3E), keyTop = c(0xFF414146), keyPressed = c(0xFF58585E),
        functional = c(0xFF2A2A2E), functionalPressed = c(0xFF404046),
        accent = c(0xFF0A84FF), accentPressed = c(0xFF409CFF), onAccent = c(0xFFFFFFFF),
        label = c(0xFFF5F5F7), functionalLabel = c(0xFFE5E5EA), hint = c(0xFF8E8E93),
        highlight = c(0x14FFFFFF), shadow = c(0x8C000000),
        stripSecondary = c(0xFF98989F), divider = c(0x1FFFFFFF),
        popup = c(0xFF4A4A50), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFF0A84FF), trailStart = c(0xFF0A84FF), trailEnd = c(0xFF64D2FF),
    )

    val midnight = KeyboardTheme(
        id = "midnight", name = "Midnight", dark = true,
        background = intArrayOf(c(0xFF000000), c(0xFF000000)),
        key = c(0xFF151517), keyTop = c(0xFF1A1A1D), keyPressed = c(0xFF2E2E33),
        functional = c(0xFF0C0C0D), functionalPressed = c(0xFF232327),
        accent = c(0xFF3B82F6), accentEnd = c(0xFF6366F1), accentPressed = c(0xFF60A5FA), onAccent = c(0xFFFFFFFF),
        label = c(0xFFFFFFFF), functionalLabel = c(0xFFD4D4D8), hint = c(0xFF71717A),
        highlight = c(0x12FFFFFF),
        stripSecondary = c(0xFF8B8B93), divider = c(0x1AFFFFFF),
        popup = c(0xFF1E1E22), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFF3B82F6), trailStart = c(0xFF3B82F6), trailEnd = c(0xFFA855F7),
    )

    val frost = KeyboardTheme(
        id = "frost", name = "Frost", dark = false,
        background = intArrayOf(c(0xFFEFF2F7), c(0xFFE1E6EE)),
        key = c(0xFFFFFFFF), keyPressed = c(0xFFE2E8F0),
        functional = c(0xFFCCD4DF), functionalPressed = c(0xFFB6C0CF),
        accent = c(0xFF2563EB), accentEnd = c(0xFF4F46E5), accentPressed = c(0xFF3B76F0), onAccent = c(0xFFFFFFFF),
        label = c(0xFF0F172A), functionalLabel = c(0xFF1E293B), hint = c(0xFF7A8699),
        shadow = c(0x6694A3B8),
        stripSecondary = c(0xFF64748B), divider = c(0x1F0F172A),
        popup = c(0xFFFFFFFF), popupLabel = c(0xFF0F172A),
        glow = c(0xFF60A5FA), trailStart = c(0xFF2563EB), trailEnd = c(0xFF7C3AED),
    )

    val paper = KeyboardTheme(
        id = "paper", name = "Paper", dark = false,
        background = intArrayOf(c(0xFFF7F4ED), c(0xFFEEE9DF)),
        key = c(0xFFFFFDF8), keyPressed = c(0xFFECE6D9),
        functional = c(0xFFE3DCCD), functionalPressed = c(0xFFD5CDBB),
        accent = c(0xFF1C1917), accentEnd = c(0xFF292524), accentPressed = c(0xFF44403C), onAccent = c(0xFFFAFAF9),
        label = c(0xFF1C1917), functionalLabel = c(0xFF292524), hint = c(0xFFA8A29E),
        shadow = c(0x59B5AC9A),
        stripSecondary = c(0xFF78716C), divider = c(0x1F1C1917),
        popup = c(0xFFFFFDF8), popupLabel = c(0xFF1C1917),
        glow = c(0xFFF59E0B), trailStart = c(0xFF292524), trailEnd = c(0xFFA8A29E),
    )

    val sakura = KeyboardTheme(
        id = "sakura", name = "Sakura", dark = false,
        background = intArrayOf(c(0xFFFFF1F5), c(0xFFFBE2EB)),
        key = c(0xFFFFFFFF), keyPressed = c(0xFFFCE7EF),
        functional = c(0xFFF8D1DF), functionalPressed = c(0xFFF2B8CD),
        accent = c(0xFFEC4899), accentEnd = c(0xFFF472B6), accentPressed = c(0xFFF06AAE), onAccent = c(0xFFFFFFFF),
        label = c(0xFF4A1530), functionalLabel = c(0xFF6B2148), hint = c(0xFFB47A96),
        shadow = c(0x66E9A5BE),
        stripSecondary = c(0xFF9C5C7C), divider = c(0x1F4A1530),
        popup = c(0xFFFFFFFF), popupLabel = c(0xFF4A1530),
        glow = c(0xFFF472B6), trailStart = c(0xFFEC4899), trailEnd = c(0xFFA855F7),
    )

    val ocean = KeyboardTheme(
        id = "ocean", name = "Ocean", dark = true,
        background = intArrayOf(c(0xFF0B3B50), c(0xFF06293A), c(0xFF031722)),
        glows = listOf(Glow(0.18f, 0.0f, 0.70f, c(0x4D06B6D4)), Glow(0.85f, 0.95f, 0.80f, c(0x403B82F6))),
        key = c(0x1AFFFFFF), keyTop = c(0x24FFFFFF), keyPressed = c(0x40FFFFFF),
        functional = c(0x0DFFFFFF), functionalPressed = c(0x2EFFFFFF),
        accent = c(0xFF06B6D4), accentEnd = c(0xFF3B82F6), accentPressed = c(0xFF22D3EE), onAccent = c(0xFFFFFFFF),
        label = c(0xFFE8FBFF), functionalLabel = c(0xFFBCE6F2), hint = c(0x9984C5D6),
        highlight = c(0x29FFFFFF), shadow = c(0x4D000000),
        stripSecondary = c(0xFF8CC3D4), divider = c(0x1FFFFFFF),
        popup = c(0xFF0E4A63), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFF22D3EE), trailStart = c(0xFF22D3EE), trailEnd = c(0xFF3B82F6),
    )

    val forest = KeyboardTheme(
        id = "forest", name = "Forest", dark = true,
        background = intArrayOf(c(0xFF14281D), c(0xFF0B1711)),
        glows = listOf(Glow(0.82f, 0.0f, 0.70f, c(0x2E34D399))),
        key = c(0xFF1D3328), keyTop = c(0xFF233C2F), keyPressed = c(0xFF2E4F3D),
        functional = c(0xFF162A20), functionalPressed = c(0xFF254231),
        accent = c(0xFF34D399), accentEnd = c(0xFF10B981), accentPressed = c(0xFF6EE7B7), onAccent = c(0xFF052E1F),
        label = c(0xFFE9F7EF), functionalLabel = c(0xFFC3E2D1), hint = c(0xFF7FA893),
        highlight = c(0x14FFFFFF), shadow = c(0x66000000),
        stripSecondary = c(0xFF8DB7A2), divider = c(0x1FFFFFFF),
        popup = c(0xFF254231), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFF34D399), trailStart = c(0xFF34D399), trailEnd = c(0xFFA3E635),
    )

    val sunset = KeyboardTheme(
        id = "sunset", name = "Sunset", dark = true,
        background = intArrayOf(c(0xFF3A0F5C), c(0xFF6B1E5E), c(0xFFA3324F)),
        glows = listOf(Glow(0.92f, 1.0f, 0.80f, c(0x66F97316)), Glow(0.08f, 0.15f, 0.60f, c(0x4DEC4899))),
        key = c(0x24FFFFFF), keyTop = c(0x33FFFFFF), keyPressed = c(0x4DFFFFFF),
        functional = c(0x14FFFFFF), functionalPressed = c(0x38FFFFFF),
        accent = c(0xFFFFB86B), accentEnd = c(0xFFFF7EB3), accentPressed = c(0xFFFFCC94), onAccent = c(0xFF3A0F2F),
        label = c(0xFFFFFFFF), functionalLabel = c(0xFFFBE3F1), hint = c(0xB3F8C8E0),
        highlight = c(0x33FFFFFF), shadow = c(0x40000000),
        stripSecondary = c(0xFFF3C6DE), divider = c(0x26FFFFFF),
        popup = c(0xFF6B1E5E), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFFFFB86B), trailStart = c(0xFFFFB86B), trailEnd = c(0xFFFF7EB3),
    )

    /** Monochrome and crisp: near-black, with white for the accent. */
    val carbon = KeyboardTheme(
        id = "carbon", name = "Carbon", dark = true,
        background = intArrayOf(c(0xFF141416), c(0xFF0B0B0C)),
        key = c(0xFF26262A), keyTop = c(0xFF2C2C31), keyPressed = c(0xFF3F3F46),
        functional = c(0xFF1A1A1D), functionalPressed = c(0xFF2E2E33),
        accent = c(0xFFFAFAFA), accentEnd = c(0xFFD4D4D8), accentPressed = c(0xFFFFFFFF), onAccent = c(0xFF0B0B0C),
        label = c(0xFFFAFAFA), functionalLabel = c(0xFFD4D4D8), hint = c(0xFF71717A),
        highlight = c(0x17FFFFFF), shadow = c(0x99000000),
        stripText = c(0xFFF4F4F5), stripSecondary = c(0xFFA1A1AA), divider = c(0x1AFFFFFF),
        popup = c(0xFF2C2C31), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFFE4E4E7), trailStart = c(0xFFFFFFFF), trailEnd = c(0xFF71717A),
    )

    /** Cool blue-greys with a frosty accent. */
    val slate = KeyboardTheme(
        id = "slate", name = "Slate", dark = true,
        background = intArrayOf(c(0xFF2E3440), c(0xFF262B35)),
        key = c(0xFF3B4252), keyTop = c(0xFF434C5E), keyPressed = c(0xFF4C566A),
        functional = c(0xFF333A47), functionalPressed = c(0xFF434C5E),
        accent = c(0xFF88C0D0), accentEnd = c(0xFF81A1C1), accentPressed = c(0xFF8FBCBB), onAccent = c(0xFF1F2430),
        label = c(0xFFECEFF4), functionalLabel = c(0xFFD8DEE9), hint = c(0xFF7B88A1),
        highlight = c(0x14FFFFFF), shadow = c(0x66000000),
        stripText = c(0xFFECEFF4), stripSecondary = c(0xFF9AA5BA), divider = c(0x1FFFFFFF),
        popup = c(0xFF434C5E), popupLabel = c(0xFFECEFF4),
        glow = c(0xFF88C0D0), trailStart = c(0xFF88C0D0), trailEnd = c(0xFFB48EAD),
    )

    /** Night-city colour: magenta and cyan light over near-black glass. */
    val neon = KeyboardTheme(
        id = "neon", name = "Neon", dark = true,
        background = intArrayOf(c(0xFF0A0617), c(0xFF07040F)),
        glows = listOf(Glow(0.05f, 0.0f, 0.75f, c(0x4DFF2BD6)), Glow(0.95f, 1.0f, 0.85f, c(0x4000E5FF))),
        key = c(0x14FFFFFF), keyTop = c(0x1FFFFFFF), keyPressed = c(0x40FFFFFF),
        functional = c(0x0AFFFFFF), functionalPressed = c(0x2EFFFFFF),
        accent = c(0xFFFF2BD6), accentEnd = c(0xFF00E5FF), accentPressed = c(0xFFFF6BE4), onAccent = c(0xFFFFFFFF),
        label = c(0xFFF5F3FF), functionalLabel = c(0xFFE0D7FF), hint = c(0x99C4B5FD),
        highlight = c(0x2EFFFFFF), shadow = c(0x59000000),
        stripText = c(0xFFF5F3FF), stripSecondary = c(0xFFB4A7E6), divider = c(0x1FFFFFFF),
        popup = c(0xFF1E1236), popupLabel = c(0xFFFFFFFF),
        glow = c(0xFFFF2BD6), trailStart = c(0xFFFF2BD6), trailEnd = c(0xFF00E5FF),
    )

    /** Soft lavender and white, with a violet accent. */
    val lilac = KeyboardTheme(
        id = "lilac", name = "Lilac", dark = false,
        background = intArrayOf(c(0xFFF6F3FF), c(0xFFEDE7FE)),
        key = c(0xFFFFFFFF), keyPressed = c(0xFFEDE9FE),
        functional = c(0xFFE4DCFB), functionalPressed = c(0xFFD6CAF8),
        accent = c(0xFF7C3AED), accentEnd = c(0xFFA855F7), accentPressed = c(0xFF8B5CF6), onAccent = c(0xFFFFFFFF),
        label = c(0xFF2E1065), functionalLabel = c(0xFF3B1782), hint = c(0xFF9A88C4),
        shadow = c(0x59B9A6E8),
        stripText = c(0xFF2E1065), stripSecondary = c(0xFF7C6BA8), divider = c(0x1F2E1065),
        popup = c(0xFFFFFFFF), popupLabel = c(0xFF2E1065),
        glow = c(0xFFA78BFA), trailStart = c(0xFF7C3AED), trailEnd = c(0xFFEC4899),
    )

    /** Display order in pickers; the dynamic theme is resolved per device. */
    val catalogue: List<String> = listOf(
        "aurora", DYNAMIC, GLASS, "graphite", "midnight", "carbon", "slate", "frost", "paper", "sakura", "lilac",
        "ocean", "forest", "sunset", "neon",
    )

    private val fixed = listOf(aurora, glass, graphite, midnight, carbon, slate, frost, paper, sakura, lilac, ocean, forest, sunset, neon)
        .associateBy { it.id }

    /** Resolves a theme id for this device. [blurAvailable] decides whether Glass may stay translucent. */
    fun resolve(context: Context, id: String, nightMode: Boolean, blurAvailable: Boolean): KeyboardTheme {
        val theme = when (id) {
            DYNAMIC -> dynamic(context, nightMode)
            else -> fixed[id] ?: aurora
        }
        return if (theme.translucent && !blurAvailable) theme.opaqueFallback() else theme
    }

    /** Material You: colors from the wallpaper on Android 12+, a calm neutral fallback before that. */
    fun dynamic(context: Context, dark: Boolean): KeyboardTheme {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val base = if (dark) graphite else frost
            return KeyboardTheme(
                DYNAMIC, "Material You", dark, base.background, base.glows, base.key, base.keyTop, base.keyPressed,
                base.functional, base.functionalPressed, base.accent, base.accentEnd, base.accentPressed,
                base.onAccent, base.label, base.functionalLabel, base.hint, base.highlight, base.shadow,
                base.stripText, base.stripSecondary, base.divider, base.popup, base.popupLabel, base.glow,
                base.trailStart, base.trailEnd,
            )
        }
        val r = context.resources
        fun s(id: Int) = r.getColor(id, context.theme)
        val n1 = intArrayOf(
            android.R.color.system_neutral1_10, android.R.color.system_neutral1_50, android.R.color.system_neutral1_700,
            android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
        ).map(::s)
        return if (!dark) KeyboardTheme(
            DYNAMIC, "Material You", false,
            background = intArrayOf(s(android.R.color.system_neutral2_50), s(android.R.color.system_neutral2_100)),
            key = n1[0], keyPressed = s(android.R.color.system_neutral2_200),
            functional = s(android.R.color.system_accent2_100), functionalPressed = s(android.R.color.system_accent2_200),
            accent = s(android.R.color.system_accent1_600), accentPressed = s(android.R.color.system_accent1_500),
            onAccent = s(android.R.color.system_accent1_0),
            label = n1[4], functionalLabel = s(android.R.color.system_accent2_900),
            hint = s(android.R.color.system_neutral2_500),
            shadow = Colors.withAlpha(s(android.R.color.system_neutral2_400), 0.35f),
            stripSecondary = s(android.R.color.system_neutral2_600), divider = Colors.withAlpha(n1[4], 0.12f),
            popup = n1[0], popupLabel = n1[4],
            glow = s(android.R.color.system_accent1_300),
            trailStart = s(android.R.color.system_accent1_500), trailEnd = s(android.R.color.system_accent3_400),
        ) else KeyboardTheme(
            DYNAMIC, "Material You", true,
            background = intArrayOf(n1[4], s(android.R.color.system_neutral1_1000).let { Colors.lerp(n1[4], it, 0.35f) }),
            key = n1[3], keyPressed = n1[2],
            functional = s(android.R.color.system_accent2_800), functionalPressed = s(android.R.color.system_accent2_700),
            accent = s(android.R.color.system_accent1_200), accentPressed = s(android.R.color.system_accent1_100),
            onAccent = s(android.R.color.system_accent1_800),
            label = n1[1], functionalLabel = s(android.R.color.system_accent2_100),
            hint = s(android.R.color.system_neutral2_400),
            shadow = 0x66000000,
            stripSecondary = s(android.R.color.system_neutral2_300), divider = Colors.withAlpha(n1[1], 0.12f),
            popup = n1[2], popupLabel = n1[1],
            glow = s(android.R.color.system_accent1_300),
            trailStart = s(android.R.color.system_accent1_200), trailEnd = s(android.R.color.system_accent3_200),
        )
    }
}
