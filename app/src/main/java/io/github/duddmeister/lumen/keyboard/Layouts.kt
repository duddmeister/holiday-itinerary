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

    fun build(mode: KeyboardMode, options: LayoutOptions): KeyboardLayout = when (mode) {
        KeyboardMode.TEXT -> letters(options)
        KeyboardMode.SYMBOLS -> symbols(options)
        KeyboardMode.SYMBOLS_MORE -> symbolsMore()
        KeyboardMode.NUMBER -> numberPad()
        KeyboardMode.PHONE -> phonePad()
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

    private fun bottomRow(o: LayoutOptions, modeCode: Int, modeLabel: String): KeyRow {
        val modeKey = mode(modeCode, modeLabel)
        val period = punct(".", "?!,'\":;-/@&…")
        return KeyRow(
            when (o.field) {
                FieldKind.EMAIL -> listOf(modeKey, punct("@", "._-+"), space(5f), period, enter())
                FieldKind.URL -> listOf(
                    modeKey, punct("/", ":_-?&=#"), space(3.5f), period,
                    Key(KeyCodes.TEXT, ".com", text = ".com", width = 1.5f, style = KeyStyle.FUNCTIONAL,
                        popup = listOf(".net", ".org", ".io", ".co", ".app", ".dev"), preview = false),
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
