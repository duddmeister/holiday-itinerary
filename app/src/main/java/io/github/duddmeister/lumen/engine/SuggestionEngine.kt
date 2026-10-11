package io.github.duddmeister.lumen.engine

import java.io.BufferedReader

/** One entry in the suggestion strip. */
class Suggestion(val text: String, val kind: Kind, val score: Float) {
    enum class Kind { TYPED, CORRECTION, COMPLETION, SHORTCUT, LEARNED, PREDICTION }

    override fun toString() = "$text($kind,${"%.1f".format(score)})"
}

class SuggestionResult(
    /** The word exactly as typed. */
    val typed: String,
    /** Whether the typed word is a known word (dictionary or learned). */
    val typedIsWord: Boolean,
    /** Ranked alternatives, best first; never contains [typed] itself. */
    val candidates: List<Suggestion>,
    /** What a space or punctuation will turn the typed word into; null keeps it. */
    val autoCorrection: String?,
    /** Emoji matching the typed word, best first. */
    val emoji: List<String>,
) {
    companion object {
        val EMPTY = SuggestionResult("", false, emptyList(), null, emptyList())
    }
}

class SuggestionOptions(
    val autoCorrect: Boolean = true,
    val emojiSuggestions: Boolean = true,
    val maxResults: Int = 5,
)

class Shortcut(val replacement: String, val autoCorrect: Boolean)

/**
 * Lumen's prediction engine: combines the dictionary, the user's own words,
 * a tap spatial model and context to produce suggestions and autocorrections.
 * Pure Kotlin, so it is unit-tested on the JVM.
 */
class SuggestionEngine(
    val lexicon: Lexicon,
    private val shortcuts: Map<String, Shortcut>,
    private val nextWords: NextWordModel,
    val history: UserHistory,
    private val emojiKeywords: Map<String, List<String>>,
) {
    /** Typed words whose autocorrection the user undid; respected for the rest of the session. */
    private val rejected = HashSet<String>()

    private class Candidate(val text: String, var score: Float, var cost: Float, var kind: Suggestion.Kind, val freq: Int)

    fun suggest(
        word: TypedWord,
        context: List<String>,
        geometry: KeyGeometry?,
        options: SuggestionOptions = SuggestionOptions(),
    ): SuggestionResult {
        val typed = word.toString()
        if (typed.isEmpty()) return SuggestionResult.EMPTY
        val folded = CharFolding.fold(typed)
        val n = folded.length

        val node = lexicon.lookup(typed)
        val learnedCount = history.count(typed)
        val typedIsWord = node >= 0 || learnedCount >= LEARNED_THRESHOLD || folded in rejected
        val hasDigits = typed.any { it.isDigit() }

        val candidates = HashMap<String, Candidate>()
        fun consider(text: String, freq: Int, cost: Float, kind: Suggestion.Kind, penalty: Float = 0f) {
            if (text == typed) return
            val score = freq - COST_WEIGHT * cost - penalty
            val old = candidates[text]
            if (old == null) {
                candidates[text] = Candidate(text, score, cost, kind, freq)
            } else if (score > old.score) {
                old.score = score; old.cost = cost; old.kind = kind
            }
        }

        val maxCost = when {
            n <= 1 -> 0f
            n == 2 -> 0.6f
            n <= 4 -> 1.25f
            n <= 7 -> 2f
            else -> 2.5f
        }
        val query = SpatialQuery(word, geometry, folded, maxCost, minOf(1f, maxCost), 3)
        lexicon.fuzzySearch(query) { found, cost, isCompletion ->
            if (lexicon.isSuggestable(found)) {
                val text = lexicon.wordAt(found)
                val freq = lexicon.frequency(found) + history.boost(text)
                if (isCompletion) {
                    // Completing a word that also needs correcting is a bigger leap than either alone.
                    val extra = text.length - n
                    val fuzzy = if (cost > 0f) FUZZY_COMPLETION_PENALTY else 0f
                    consider(text, freq, cost, Suggestion.Kind.COMPLETION, COMPLETION_BASE + COMPLETION_PER_CHAR * extra + fuzzy)
                } else {
                    consider(text, freq, cost, Suggestion.Kind.CORRECTION)
                }
            }
        }

        // Words the user taught Lumen that the dictionary does not know.
        history.forEachWord(LEARNED_THRESHOLD) { e ->
            if (lexicon.lookup(e.display) >= 0) return@forEachWord
            val key = CharFolding.fold(e.display)
            val freq = LEARNED_BASE_FREQ + history.boost(e.display)
            if (key.length > n && key.startsWith(folded)) {
                consider(e.display, freq, 0f, Suggestion.Kind.LEARNED, COMPLETION_BASE + COMPLETION_PER_CHAR * (key.length - n))
            } else if (key.length in n - 2..n + 2 && n >= 3) {
                val cost = editCost(query, key)
                if (cost <= maxCost) consider(e.display, freq, cost, Suggestion.Kind.LEARNED)
            }
        }

        shortcuts[folded]?.let { s ->
            if (s.autoCorrect) consider(s.replacement, SHORTCUT_FREQ, 0f, Suggestion.Kind.SHORTCUT)
            else consider(s.replacement, SUGGESTED_SHORTCUT_FREQ, 0.3f, Suggestion.Kind.SHORTCUT)
        }

        // Context: favour words that often follow the previous word.
        val predicted = if (context.isEmpty()) emptyList() else nextWords.predict(context, history, 24)
        predicted.forEachIndexed { rank, (w, _) ->
            candidates[w]?.let { it.score += (CONTEXT_BONUS - rank).coerceAtLeast(4).toFloat() }
        }

        val ranked = candidates.values.sortedByDescending { it.score }
        val shortcut = shortcuts[folded]
        val autoCorrection = when {
            !options.autoCorrect || folded in rejected -> null
            shortcut != null && shortcut.autoCorrect && lexicon.findExact(typed) < 0 ->
                applyCase(shortcut.replacement, word)
            !typedIsWord && !hasDigits -> ranked.firstOrNull { isAutoCorrectable(it, n) }?.let { applyCase(it.text, word) }
            // A slip first, so "tue" (meant "the") isn't taken for "Tue".
            node >= 0 -> slipFix(node, word, learnedCount, ranked, predicted)?.let { applyCase(it.text, word) }
                ?: caseFix(typed, node, word)
            else -> null
        }?.takeIf { it != typed }

        val seen = HashSet<String>()
        val out = ArrayList<Suggestion>()
        for (c in ranked) {
            val text = applyCase(c.text, word)
            if (text == typed || !seen.add(text)) continue
            out.add(Suggestion(text, c.kind, c.score))
            if (out.size == options.maxResults) break
        }
        val emoji = if (options.emojiSuggestions) emojiFor(folded, autoCorrection) else emptyList()
        return SuggestionResult(typed, typedIsWord, out, autoCorrection, emoji)
    }

    private fun isAutoCorrectable(c: Candidate, typedLength: Int): Boolean {
        if (c.kind == Suggestion.Kind.SHORTCUT) return c.cost == 0f
        if (typedLength < 2) return false
        val maxAuto = when {
            typedLength == 2 -> 0.45f
            typedLength <= 4 -> 1f
            typedLength <= 7 -> 1.6f
            else -> 2.2f
        }
        if (c.cost > maxAuto) return false
        if (c.kind == Suggestion.Kind.COMPLETION) {
            if (c.cost > 0f) return false
            val extra = c.text.length - typedLength
            if (!(typedLength >= 4 && extra <= 1) && !(typedLength >= 6 && extra <= 2)) return false
        }
        return c.freq >= MIN_AUTOCORRECT_FREQ || c.kind == Suggestion.Kind.LEARNED
    }

    /**
     * A slip that lands on a rare word, like "bur" for "but" or "nit" for "not" (or on a
     * rare name, like "tue" for "the"): corrected when a far more common word is one slip
     * away. A slip is two letters swapped, or a tap on a neighbouring key: one that landed
     * near the key meant takes a rare word and a big difference in frequency; one squarely
     * on the key beside it, a very rare word and a huge difference. Without the tap to go
     * on (a word picked up again at the cursor), only a swap counts. A rare word typed on
     * purpose is otherwise kept, as is one the user has typed before or that what came
     * before points to. Common words are never turned into other words ("form", "lime").
     */
    private fun slipFix(node: Int, word: TypedWord, typedBefore: Int, ranked: List<Candidate>, predicted: List<Pair<String, Float>>): Candidate? {
        if (word.length < 3 || typedBefore > 0) return null
        val folded = CharFolding.fold(lexicon.wordAt(node))
        if (predicted.any { CharFolding.fold(it.first) == folded }) return null
        val best = ranked.firstOrNull() ?: return null
        if (best.kind != Suggestion.Kind.CORRECTION) return null
        val slip = slipAt(folded, CharFolding.fold(best.text)) ?: return null
        val tapped = slip == SWAPPED || (folded.length == word.length && !word.tapX(slip).isNaN())
        val (rarest, gap) = when {
            !tapped -> return null
            best.cost <= SLIP_NEAR_COST -> SLIP_RARE_FREQ to SLIP_FREQ_GAP
            best.cost <= SLIP_SQUARE_COST -> SLIP_VERY_RARE_FREQ to SLIP_HUGE_FREQ_GAP
            else -> return null
        }
        val typedFreq = lexicon.frequency(node)
        return best.takeIf { typedFreq <= rarest && it.freq - typedFreq >= gap }
    }

    /**
     * Where [b] differs from [a] by one slip: the index of the one letter that differs,
     * [SWAPPED] for two side by side swapped, or null for anything else.
     */
    private fun slipAt(a: String, b: String): Int? {
        if (a.length != b.length) return null
        var first = -1
        var second = -1
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            when {
                first < 0 -> first = i
                second < 0 -> second = i
                else -> return null
            }
        }
        return when {
            first < 0 -> null
            second < 0 -> first
            second == first + 1 && a[first] == b[second] && a[second] == b[first] -> SWAPPED
            else -> null
        }
    }

    /**
     * "monday" -> "Monday": capitalise names the dictionary only knows capitalised.
     * Acronyms ("LOL", "PM") are left alone because people type them lowercase on purpose.
     */
    private fun caseFix(typed: String, node: Int, word: TypedWord): String? {
        val dict = lexicon.wordAt(node)
        if (dict == typed || lexicon.findExact(typed) >= 0) return null
        if (!dict[0].isUpperCase() || dict.drop(1).any { it.isUpperCase() }) return null
        if (dict.lowercase() != typed.lowercase()) return null // accents differ: leave the user's spelling
        if (typed.drop(1).any { it.isUpperCase() }) return null
        return applyCase(dict, word)
    }

    private fun emojiFor(folded: String, autoCorrection: String?): List<String> {
        val key = autoCorrection?.let { CharFolding.fold(it) } ?: folded
        return emojiKeywords[key] ?: emptyList()
    }

    /** Next-word predictions after [context] (most recent word last). */
    fun predict(context: List<String>, limit: Int = 5): List<Suggestion> =
        nextWords.predict(context, history, limit * 2)
            .filter { (w, _) -> isOfferable(w) }
            .take(limit)
            .map { (w, s) -> Suggestion(w, Suggestion.Kind.PREDICTION, s) }

    /** Predictions come from curated seeds or the user's own typing; only offensive words are held back. */
    private fun isOfferable(word: String): Boolean {
        val node = lexicon.lookup(word)
        return node < 0 || !lexicon.isOffensive(node)
    }

    /** The user undid an autocorrection of [typed]: never correct it again and start learning it. */
    fun rejectAutoCorrection(typed: String, now: Long) {
        rejected.add(CharFolding.fold(typed))
        history.record(typed, null, now, weight = LEARNED_THRESHOLD)
    }

    /** Records a committed word so that frequent words and phrases rise to the top. */
    fun learn(word: String, previous: String?, now: Long) {
        val clean = word.trim('\'', '-', '.')
        if (clean.isEmpty() || clean.any { it.isDigit() } || clean.length > 32) return
        history.record(clean, previous, now)
    }

    fun forget(word: String) = history.forget(word)

    /** Weighted edit distance with the same costs as the trie search, for words outside the trie. */
    private fun editCost(q: FuzzyQuery, candidate: String): Float {
        val a = q.input
        val n = a.length
        val m = candidate.length
        var prev2 = FloatArray(n + 1)
        var prev = FloatArray(n + 1)
        var cur = FloatArray(n + 1)
        for (i in 1..n) prev[i] = prev[i - 1] + q.deleteCost(i - 1)
        for (j in 1..m) {
            val c = candidate[j - 1]
            val ins = q.insertCost(c, if (j > 1) candidate[j - 2] else '\u0000')
            cur[0] = prev[0] + ins
            for (i in 1..n) {
                val t = a[i - 1]
                var v = prev[i - 1] + if (t == c) 0f else q.substitutionCost(i - 1, c)
                v = minOf(v, prev[i] + ins, cur[i - 1] + q.deleteCost(i - 1))
                if (i > 1 && j > 1 && t == candidate[j - 2] && a[i - 2] == c && t != c) {
                    v = minOf(v, prev2[i - 2] + Lexicon.TRANSPOSE_COST)
                }
                cur[i] = v
            }
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[n]
    }

    companion object {
        const val COST_WEIGHT = 55f
        const val COMPLETION_BASE = 6f
        const val COMPLETION_PER_CHAR = 3f
        const val FUZZY_COMPLETION_PENALTY = 15f
        const val CONTEXT_BONUS = 28
        const val SHORTCUT_FREQ = 240
        const val SUGGESTED_SHORTCUT_FREQ = 150
        const val LEARNED_BASE_FREQ = 110
        const val LEARNED_THRESHOLD = 2
        const val MIN_AUTOCORRECT_FREQ = 40
        /**
         * A slip costing this much at most: two letters swapped (0.6), or a tap on a
         * neighbouring key that leaned towards the key meant...
         */
        const val SLIP_NEAR_COST = 0.6f
        /** ...turns a word this rare or rarer into one this much more frequent (0 to 255 scale). */
        const val SLIP_RARE_FREQ = 90
        const val SLIP_FREQ_GAP = 85
        /**
         * A tap squarely on the key beside the one meant costs 0.75 (diagonal neighbours a
         * little more, so they never count), and needs a very rare word and a huge gap.
         */
        const val SLIP_SQUARE_COST = 0.8f
        const val SLIP_VERY_RARE_FREQ = 70
        const val SLIP_HUGE_FREQ_GAP = 115
        private const val SWAPPED = -2

        fun applyCase(candidate: String, typed: TypedWord): String = when {
            typed.isAllCaps -> candidate.uppercase()
            typed.isCapitalized && candidate.isNotEmpty() && candidate[0].isLowerCase() ->
                candidate[0].uppercaseChar() + candidate.substring(1)
            else -> candidate
        }

        fun readShortcuts(reader: BufferedReader): Map<String, Shortcut> {
            val map = HashMap<String, Shortcut>()
            while (true) {
                val line = reader.readLine() ?: break
                val f = line.split('\t')
                if (f.size >= 2) map[CharFolding.fold(f[0])] = Shortcut(f[1], f.getOrNull(2) != "s")
            }
            return map
        }

        fun readEmojiKeywords(reader: BufferedReader): Map<String, List<String>> {
            val map = HashMap<String, List<String>>()
            while (true) {
                val line = reader.readLine() ?: break
                val tab = line.indexOf('\t')
                if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1).split(' ')
            }
            return map
        }
    }
}
