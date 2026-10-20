package io.github.duddmeister.lumen.settings

import io.github.duddmeister.lumen.engine.UserHistory
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Writer

/**
 * A backup the user keeps as a text file: Lumen's settings, one a line, then the
 * words it has learned, in [UserHistory]'s own lines.
 *
 * It carries only the settings listed in [SETTINGS]: not GIF service keys (they
 * belong to the user's own accounts), clipboard text or recent emoji, nor what this
 * phone has already done (such as which update it announced). Settings come back
 * only with the type Lumen keeps them as, so a damaged or edited file can't upset
 * the keyboard.
 */
object Backup {
    private const val HEADER = "# Lumen backup"
    private const val VERSION = 1
    private const val SETTING = "setting"
    /** Far more than a backup holds (12,000 words and 30,000 pairs). */
    private const val MAX_BYTES = 16L * 1024 * 1024

    enum class Kind { BOOL, INT, FLOAT, STRING }

    /** Every setting a backup carries, with the type it is kept as. */
    val SETTINGS: Map<String, Kind> = linkedMapOf(
        Prefs.THEME to Kind.STRING,
        Prefs.KEY_SHAPE to Kind.STRING,
        Prefs.KEY_BACKGROUNDS to Kind.BOOL,
        Prefs.ACCENT to Kind.STRING,
        Prefs.KEY_FINISH to Kind.STRING,
        Prefs.LABEL_FONT to Kind.STRING,
        Prefs.LETTER_SIZE to Kind.STRING,
        Prefs.LAYOUT to Kind.STRING,
        Prefs.SPELLING to Kind.STRING,
        Prefs.HEIGHT to Kind.INT,
        Prefs.NUMBER_ROW to Kind.BOOL,
        Prefs.SPLIT to Kind.BOOL,
        Prefs.ONE_HANDED to Kind.BOOL,
        Prefs.ONE_HANDED_LEFT to Kind.BOOL,
        Prefs.FLOATING to Kind.BOOL,
        Prefs.FLOAT_X to Kind.FLOAT,
        Prefs.FLOAT_Y to Kind.FLOAT,
        Prefs.SYMBOL_HINTS to Kind.BOOL,
        Prefs.FLICK_SYMBOLS to Kind.BOOL,
        Prefs.KEY_PREVIEWS to Kind.BOOL,
        Prefs.LIGHT_EFFECTS to Kind.BOOL,
        Prefs.AUTO_CAPS to Kind.BOOL,
        Prefs.AUTO_CORRECT to Kind.BOOL,
        Prefs.SUGGESTIONS to Kind.BOOL,
        Prefs.DOUBLE_SPACE to Kind.BOOL,
        Prefs.VOICE to Kind.BOOL,
        Prefs.EMOJI_SUGGESTIONS to Kind.BOOL,
        Prefs.HAPTICS to Kind.BOOL,
        Prefs.HAPTIC_STRENGTH to Kind.INT,
        Prefs.SOUND to Kind.BOOL,
        Prefs.SOUND_VOLUME to Kind.INT,
        Prefs.LEARN to Kind.BOOL,
        Prefs.CLIPBOARD to Kind.BOOL,
        Prefs.SPACE_CURSOR to Kind.BOOL,
        Prefs.DELETE_SWIPE to Kind.BOOL,
        Prefs.LONG_PRESS to Kind.INT,
        Prefs.GIFS to Kind.BOOL,
        Prefs.GIF_SERVICE to Kind.STRING,
        Prefs.AUTO_UPDATES to Kind.BOOL,
    )

    /** Settings deliberately left out (see above). */
    val LEFT_OUT = setOf(Prefs.GIPHY_KEY, Prefs.KLIPY_KEY, Prefs.ANNOUNCED_VERSION, Prefs.NOTIFICATIONS_ASKED)

    class NotABackup : IOException("Not a Lumen backup")

    class Contents(val settings: Map<String, Any>, val words: UserHistory)

    /** Writes [settings] (as SharedPreferences hands them over) and the learned [words]. */
    fun write(out: Writer, settings: Map<String, *>, words: UserHistory) {
        out.write("$HEADER v$VERSION\n")
        for ((key, kind) in SETTINGS) {
            val value = settings[key] ?: continue
            val matches = when (kind) {
                Kind.BOOL -> value is Boolean
                Kind.INT -> value is Int
                Kind.FLOAT -> value is Float
                Kind.STRING -> value is String
            }
            if (matches) out.write("$SETTING\t$key\t${kind.name.lowercase()}\t${escape(value.toString())}\n")
        }
        words.write(out, saved = false)
    }

    /** Reads a backup; [NotABackup] if it isn't one. Settings Lumen doesn't keep are skipped. */
    fun read(input: InputStream): Contents {
        val reader = Capped(input).bufferedReader(Charsets.UTF_8)
        val first = reader.readLine()?.removePrefix("\uFEFF")
        if (first == null || !first.startsWith(HEADER)) throw NotABackup()
        return read(reader)
    }

    private fun read(reader: BufferedReader): Contents {
        val settings = LinkedHashMap<String, Any>()
        val rest = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("$SETTING\t")) {
                rest.append(line).append('\n')
                continue
            }
            val f = line.split('\t')
            if (f.size != 4) continue
            val kind = SETTINGS[f[1]] ?: continue
            if (f[2] != kind.name.lowercase()) continue
            val text = unescape(f[3])
            val value: Any = when (kind) {
                Kind.BOOL -> text.toBooleanStrictOrNull()
                Kind.INT -> text.toIntOrNull()
                Kind.FLOAT -> text.toFloatOrNull()?.takeIf { it.isFinite() }
                Kind.STRING -> text
            } ?: continue
            settings[f[1]] = value
        }
        return Contents(settings, UserHistory.read(rest.toString().reader().buffered()))
    }

    /** Tabs, line breaks and backslashes, kept out of the way of the tabs between fields. */
    private fun escape(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val b = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> b.append("\\\\")
            '\t' -> b.append("\\t")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            else -> b.append(c)
        }
        return b.toString()
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i == s.length) { b.append(c); continue }
            when (val n = s[i++]) {
                't' -> b.append('\t')
                'n' -> b.append('\n')
                'r' -> b.append('\r')
                else -> b.append(n)
            }
        }
        return b.toString()
    }

    /** Gives up on a file far bigger than any backup, rather than reading it all. */
    private class Capped(input: InputStream) : FilterInputStream(input) {
        private var count = 0L

        private fun counted(n: Int): Int {
            if (n > 0) {
                count += n
                if (count > MAX_BYTES) throw NotABackup()
            }
            return n
        }

        override fun read(): Int = super.read().also { if (it >= 0) counted(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = counted(super.read(b, off, len))
    }
}
