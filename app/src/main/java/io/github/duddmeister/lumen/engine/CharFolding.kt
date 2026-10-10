package io.github.duddmeister.lumen.engine

import java.text.Normalizer

/**
 * Case- and accent-insensitive character folding, used whenever typed input is
 * matched against dictionary words ("cafe" matches "Café", "i" matches "I").
 */
object CharFolding {
    private const val TABLE_SIZE = 0x250 // Basic Latin through Latin Extended-B

    private val table = CharArray(TABLE_SIZE) { code ->
        val decomposed = Normalizer.normalize(code.toChar().toString(), Normalizer.Form.NFD)
        decomposed[0].lowercaseChar()
    }

    fun fold(c: Char): Char = if (c.code < TABLE_SIZE) table[c.code] else c.lowercaseChar()

    fun fold(s: CharSequence): String {
        val out = CharArray(s.length)
        for (i in s.indices) out[i] = fold(s[i])
        return String(out)
    }

    /** True for characters that are cheap to insert or drop when matching ("dont" vs "don't"). */
    fun isConnector(c: Char): Boolean = c == '\'' || c == '-' || c == '.' || c == '’'
}
