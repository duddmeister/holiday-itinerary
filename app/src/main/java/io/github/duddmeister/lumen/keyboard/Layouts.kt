package io.github.duddmeister.lumen.keyboard

/** Letter arrangements offered in settings. */
enum class LetterLayout(val id: String, val title: String, val rows: List<String>) {
    QWERTY("qwerty", "QWERTY", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
    QWERTZ("qwertz", "QWERTZ", listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")),
    AZERTY("azerty", "AZERTY", listOf("azertyuiop", "qsdfghjklm", "wxcvbn'")),
    COLEMAK("colemak", "Colemak", listOf("qwfpgjluy", "arstdhneio", "zxcvbkm")),
    DVORAK("dvorak", "Dvorak", listOf("',.pyfgcrl", "aoeuidhtns", "qjkxbmwvz"));

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: QWERTY
    }
}

/** What kind of text the field expects; tunes the bottom row. */
enum class FieldKind { TEXT, EMAIL, URL, PASSWORD }

class LayoutOptions(
    val letters: LetterLayout = LetterLayout.QWERTY,
    val field: FieldKind = FieldKind.TEXT,
    val numberRow: Boolean = false,
    val symbolHints: Boolean = true,
    val emojiKey: Boolean = true,
    /** Split letters and symbols into two halves for thumb typing on large screens. */
    val split: Boolean = false,
)

/** Builds every keyboard Lumen shows. */
object Layouts {
    private val accents = mapOf(
        'a' to "àáâäæãåā", 'c' to "çćč", 'e' to "èéêëēęė", 'i' to "ìíîïīį", 'n' to "ñń",
        'o' to "òóôöõøōœ", 's' to "ßśš", 'u' to "ùúûüū", 'y' to "ÿý", 'z' to "žźż",
        'l' to "ł", 'g' to "ğ", 'd' to "ð", 't' to "þ",
    )
    private const val ROW2_SYMBOLS = "@#\$_&-+()/"
    private const val ROW3_SYMBOLS = "*\"':;!?"

    /** Width of shift, delete, enter and the page keys at the ends of a split row: Gboard's. */
    private const val SPLIT_WIDE = 4f / 3f
    private const val EPSILON = 1e-3f

    private val numberPopups = mapOf(
        '1' to "¹½⅓¼⅛", '2' to "²⅔", '3' to "³¾⅜", '4' to "⁴", '5' to "⅝", '7' to "⅞", '0' to "ⁿ∅",
    )
    private val symbolPopups = mapOf(
        "$" to "€£¥¢₹₩₽₺₿", "&" to "§", "-" to "–—·", "+" to "±", "(" to "<[{", ")" to ">]}",
        "#" to "№", "/" to "\\", "*" to "†‡★", "\"" to "“”„«»", "'" to "‘’‚‹›", "!" to "¡", "?" to "¿",
        "%" to "‰", "=" to "≠≈∞", "°" to "′″", "•" to "♪♥♠♦♣", "π" to "Π", "<" to "≤«‹", ">" to "≥»›",
    )

    private fun chars(s: String) = s.map { it.toString() }

    private fun charKey(c: String, hint: String? = null, popup: List<String> = emptyList()) =
        Key(c.codePointAt(0), c, hint = hint, popup = popup)

    private fun symbolKey(c: String) = charKey(c, popup = symbolPopups[c]?.let(::chars) ?: emptyList())

    private fun shift() = Key(KeyCodes.SHIFT, icon = Icon.SHIFT, width = 1.5f, style = KeyStyle.FUNCTIONAL, preview = false)
    private fun delete(width: Float = 1.5f) =
        Key(KeyCodes.DELETE, icon = Icon.DELETE, width = width, style = KeyStyle.FUNCTIONAL, preview = false)
    private fun enter(width: Float = 1.5f) =
        Key(KeyCodes.ENTER, icon = Icon.ENTER, width = width, style = KeyStyle.ACCENT, preview = false)
    private fun space(width: Float) = Key(KeyCodes.SPACE, " ", width = width, style = KeyStyle.SPACE)
    private fun mode(code: Int, label: String, width: Float = 1.5f) =
        Key(code, label, width = width, style = KeyStyle.FUNCTIONAL, preview = false)
    private fun emoji() = Key(KeyCodes.EMOJI, icon = Icon.EMOJI, style = KeyStyle.FUNCTIONAL, preview = false)
    private fun punct(c: String, popup: String) =
        Key(c.codePointAt(0), c, style = KeyStyle.FUNCTIONAL, popup = chars(popup), preview = true)

    fun build(mode: KeyboardMode, options: LayoutOptions): KeyboardLayout {
        val layout = when (mode) {
            KeyboardMode.TEXT -> letters(options)
            KeyboardMode.SYMBOLS -> symbols(options)
            KeyboardMode.SYMBOLS_MORE -> symbolsMore()
            KeyboardMode.NUMBER -> numberPad()
            KeyboardMode.PHONE -> phonePad()
        }
        // Number and phone pads stay whole; the view keeps them compact instead.
        val splittable = mode == KeyboardMode.TEXT || mode == KeyboardMode.SYMBOLS || mode == KeyboardMode.SYMBOLS_MORE
        return if (options.split && splittable) split(layout) else layout
    }

    /**
     * Splits every row between the thumbs the way Gboard does on an open foldable:
     * halves of the same width that sit against the opening, the key in the middle
     * of an odd row (G, V) on both halves, and one space bar across the opening.
     * A row as wide as the keyboard stretches to fill its halves; a shorter one keeps
     * its keys and leaves the room at the outer edges.
     */
    private fun split(layout: KeyboardLayout): KeyboardLayout {
        val units = layout.rows.maxOf { it.units }
        val halves = layout.rows.map(::halves)
        val half = halves.maxOf { maxOf(it.left.units(), it.right.units()) }
        val rows = layout.rows.zip(halves) { row, h ->
            when {
                h.space != null -> {
                    val left = half - h.left.units()
                    val space = h.space.copy(width = left + half - h.right.units())
                    KeyRow(h.left + space + h.right, row.heightFactor, h.left.size, bridgeLeft = left)
                }
                row.units > units - EPSILON -> KeyRow(h.left.stretched(half) + h.right.stretched(half), row.heightFactor, h.left.size)
                else -> KeyRow(h.left + h.right, row.heightFactor, h.left.size)
            }
        }
        return KeyboardLayout(layout.mode, rows, layout.name)
    }

    private class Halves(val left: List<Key>, val right: List<Key>, val space: Key? = null)

    /** Where a row breaks, with its wide keys narrowed to [SPLIT_WIDE]. */
    private fun halves(row: KeyRow): Halves {
        val keys = row.keys.map { if (it.width > 1f && it.code != KeyCodes.SPACE) it.copy(width = SPLIT_WIDE) else it }
        val space = keys.indexOfFirst { it.code == KeyCodes.SPACE }
        if (space >= 0) return Halves(keys.subList(0, space), keys.subList(space + 1, keys.size), keys[space])
        val chars = keys.indices.filter { keys[it].style == KeyStyle.CHARACTER }.ifEmpty { keys.indices.toList() }
        val middle = chars[chars.size / 2]
        return if (chars.size % 2 == 1) {
            // Either thumb can reach the middle key.
            Halves(keys.subList(0, middle + 1), listOf(keys[middle].copy()) + keys.subList(middle + 1, keys.size))
        } else {
            Halves(keys.subList(0, middle), keys.subList(middle, keys.size))
        }
    }

    private fun List<Key>.units() = sumOf { it.width.toDouble() }.toFloat()

    private fun List<Key>.stretched(to: Float): List<Key> {
        val scale = to / units()
        return map { it.copy(width = it.width * scale) }
    }

    private fun numberRow() = KeyRow(
        chars("1234567890").map { charKey(it, popup = numberPopups[it[0]]?.let(::chars) ?: emptyList()) },
        heightFactor = 0.82f,
    )

    private fun letters(o: LayoutOptions): KeyboardLayout {
        val rows = ArrayList<KeyRow>()
        val showNumbers = o.numberRow || o.field == FieldKind.PASSWORD
        if (showNumbers) rows.add(numberRow())
        o.letters.rows.forEachIndexed { r, row ->
            val keys = row.mapIndexed { i, ch ->
                val hint: String? = when {
                    r == 0 && !showNumbers && row.length >= 9 -> ((i + 1) % 10).toString()
                    r == 1 && o.symbolHints -> ROW2_SYMBOLS.getOrNull(i)?.toString()
                    r == 2 && o.symbolHints -> ROW3_SYMBOLS.getOrNull(i)?.toString()
                    else -> null
                }
                val popup = listOfNotNull(hint) + (accents[ch]?.let(::chars) ?: emptyList())
                charKey(ch.toString(), hint, popup)
            }
            rows.add(KeyRow(if (r == 2) listOf(shift()) + keys + delete() else keys))
        }
        rows.add(bottomRow(o, KeyCodes.SYMBOLS, "?123"))
        return KeyboardLayout(KeyboardMode.TEXT, rows, o.letters.title)
    }

    /**
     * The space bar keeps its place whatever the field, so a thumb that finds it in a
     * message finds it in a search box too (there, "." and ".com" used to sit where the
     * right of the space bar is, and taps meant for it typed full stops).
     */
    private fun bottomRow(o: LayoutOptions, modeCode: Int, modeLabel: String): KeyRow {
        val modeKey = mode(modeCode, modeLabel)
        val period = punct(".", "?!,'\":;-/@&…")
        return KeyRow(
            when (o.field) {
                FieldKind.EMAIL -> listOf(modeKey, punct("@", "._-+"), space(5f), period, enter())
                FieldKind.URL -> listOf(
                    modeKey, punct("/", ":_-?&=#"), space(5f),
                    Key('.'.code, ".", style = KeyStyle.FUNCTIONAL, preview = true,
                        popup = listOf(".com", ".co.uk", ".org", ".net", ".io", ".app", ".dev")),
                    enter(),
                )
                FieldKind.PASSWORD -> listOf(modeKey, punct(",", ";:'\""), space(5f), period, enter())
                FieldKind.TEXT -> if (o.emojiKey) {
                    listOf(modeKey, punct(",", ";:'\"!?"), emoji(), space(4f), period, enter())
                } else {
                    listOf(modeKey, punct(",", ";:'\"!?"), space(5f), period, enter())
                }
            },
        )
    }

    private fun symbols(o: LayoutOptions) = KeyboardLayout(
        KeyboardMode.SYMBOLS,
        listOf(
            KeyRow(chars("1234567890").map { charKey(it, popup = numberPopups[it[0]]?.let(::chars) ?: emptyList()) }),
            KeyRow(chars(ROW2_SYMBOLS).map(::symbolKey)),
            KeyRow(listOf(mode(KeyCodes.SYMBOLS_MORE, "=\\<")) + chars(ROW3_SYMBOLS).map(::symbolKey) + delete()),
            bottomRow(o, KeyCodes.ALPHABET, "ABC"),
        ),
        "Symbols",
    )

    private fun symbolsMore() = KeyboardLayout(
        KeyboardMode.SYMBOLS_MORE,
        listOf(
            KeyRow(chars("~`|•√π÷×§∆").map(::symbolKey)),
            KeyRow(chars("£¢€¥^°={}\\").map(::symbolKey)),
            KeyRow(listOf(mode(KeyCodes.SYMBOLS, "?123")) + chars("%©®™✓[]").map(::symbolKey) + delete()),
            KeyRow(
                listOf(
                    mode(KeyCodes.ALPHABET, "ABC"), symbolKey("<"), space(5f), symbolKey(">"), enter(),
                ),
            ),
        ),
        "Symbols",
    )

    private fun padKey(label: String, caption: String? = null, popup: List<String> = emptyList()) =
        Key(label.codePointAt(0), label, caption = caption, popup = popup, preview = false)

    private fun numberPad() = KeyboardLayout(
        KeyboardMode.NUMBER,
        listOf(
            KeyRow(listOf(padKey("1"), padKey("2"), padKey("3"), punct("-", "+±"))),
            KeyRow(listOf(padKey("4"), padKey("5"), padKey("6"), Key(KeyCodes.SPACE, " ", icon = Icon.SPACE, style = KeyStyle.FUNCTIONAL, preview = false))),
            KeyRow(listOf(padKey("7"), padKey("8"), padKey("9"), delete(1f))),
            KeyRow(listOf(punct(",", ""), padKey("0"), punct(".", ""), enter(1f))),
        ),
        "Numbers",
    )

    private fun phonePad() = KeyboardLayout(
        KeyboardMode.PHONE,
        listOf(
            KeyRow(listOf(padKey("1"), padKey("2", "ABC"), padKey("3", "DEF"), punct("-", "()"))),
            KeyRow(listOf(padKey("4", "GHI"), padKey("5", "JKL"), padKey("6", "MNO"), Key(KeyCodes.SPACE, " ", icon = Icon.SPACE, style = KeyStyle.FUNCTIONAL, preview = false))),
            KeyRow(listOf(padKey("7", "PQRS"), padKey("8", "TUV"), padKey("9", "WXYZ"), delete(1f))),
            KeyRow(listOf(punct("*", "#,;"), padKey("0", "+", listOf("+")), punct("#", "*"), enter(1f))),
        ),
        "Phone",
    )
}
