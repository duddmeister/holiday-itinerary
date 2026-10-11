package io.github.duddmeister.lumen.engine

/** Small helpers for reading words and sentence context around the cursor. */
object TextContext {
    fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '’' || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    fun isSentenceEnd(c: Char): Boolean = c == '.' || c == '!' || c == '?' || c == '…'

    /**
     * How far the cursor moves for [words] word jumps: back to the start of each word
     * when negative (reading [before]), forward to the end of each word when positive
     * (reading [after]). Stops at the end of the text it is given.
     */
    fun wordJump(before: CharSequence, after: CharSequence, words: Int): Int {
        if (words < 0) {
            var i = before.length
            repeat(-words) {
                while (i > 0 && !isWordChar(before[i - 1])) i--
                while (i > 0 && isWordChar(before[i - 1])) i--
            }
            return i - before.length
        }
        var i = 0
        repeat(words) {
            while (i < after.length && !isWordChar(after[i])) i++
            while (i < after.length && isWordChar(after[i])) i++
        }
        return i
    }

    /** The word that ends exactly at the end of [before] ("" if a separator precedes the cursor). */
    fun wordBeforeCursor(before: CharSequence): String {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        // Apostrophes only belong to a word when they sit between letters.
        while (i < before.length && (before[i] == '\'' || before[i] == '’')) i++
        return before.subSequence(i, before.length).toString()
    }

    /**
     * Up to [max] words before the cursor, oldest first, for prediction. When a
     * sentence starts within (or right before) that window, the list begins with
     * [NextWordModel.SENTENCE_START].
     */
    fun contextWords(before: CharSequence, max: Int = 2): List<String> {
        val words = ArrayList<String>(max + 1)
        var i = before.length
        while (words.size < max) {
            // Skip separators, watching for the end of the previous sentence.
            var boundary = i == 0
            while (i > 0 && !isWordChar(before[i - 1])) {
                val c = before[i - 1]
                if (isSentenceEnd(c) || c == '\n') boundary = true
                i--
                if (i == 0) boundary = true
            }
            if (boundary || i == 0) {
                words.add(NextWordModel.SENTENCE_START)
                break
            }
            val end = i
            while (i > 0 && isWordChar(before[i - 1])) i--
            words.add(before.subSequence(i, end).toString())
            if (i == 0) {
                if (words.size < max) words.add(NextWordModel.SENTENCE_START)
                break
            }
        }
        words.reverse()
        return words
    }

    /** True when the next word starts a sentence (start of text, or after . ! ? or a blank line). */
    fun atSentenceStart(before: CharSequence): Boolean {
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) {
            if (before[i - 1] == '\n') return true
            i--
        }
        if (i == 0) return true
        var c = before[i - 1]
        // Allow closing quotes/brackets after the terminator: He said "hi." |
        while (i > 1 && (c == '"' || c == '\'' || c == ')' || c == '”' || c == '’')) {
            i--
            c = before[i - 1]
        }
        return isSentenceEnd(c) && i < before.length
    }
}
