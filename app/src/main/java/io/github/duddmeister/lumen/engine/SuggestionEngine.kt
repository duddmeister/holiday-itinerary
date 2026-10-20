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
    private val pairs: WordPairs = WordPairs.EMPTY,
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

        val previous = context.lastOrNull()
        val candidates = HashMap<String, Candidate>()
        fun consider(text: String, freq: Int, cost: Float, kind: Suggestion.Kind, penalty: Float = 0f) {
            if (text == typed) return
            val score = freq - COST_WEIGHT * cost - penalty + pairBonus(previous, text)
            val old = candidates[text]
            if (old == null) {
                candidates[text] = Candidate(text, score, cost, kind, freq)
            } else if (score > old.score) {
                old.score = score; old.cost = cost; old.kind = kind
            }
        }

        val maxCost = when {
            n <= 1 -> 0f
            // Enough for a tap squarely on a neighbouring key ("ti" for "to").
            n == 2 -> 0.85f
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

        val singles = candidates.values.sortedByDescending { it.score }
        val lowercase = typed.none { it.isUpperCase() }
        // Two words run together, read apart: "thelink", "sincebthe".
        val split = if (!typedIsWord && !hasDigits && n >= 3) splitReading(word, folded, geometry, lowercase, previous) else null
        val ranked = if (split == null) singles else (singles + split).sortedByDescending { it.score }
        val shortcut = shortcuts[folded]
        val autoCorrection = when {
            !options.autoCorrect || folded in rejected -> null
            shortcut != null && shortcut.autoCorrect && lexicon.findExact(typed) < 0 ->
                applyCase(shortcut.replacement, word)
            !typedIsWord && !hasDigits -> {
                // Only a close second to the best guess, or the best way to finish what was
                // typed: a guess far behind is usually wrong ("gir" isn't "air" or "girt").
                val best = singles.firstOrNull()?.score ?: 0f
                val bestFinish = singles.firstOrNull { CharFolding.fold(it.text).startsWith(folded) }
                val single = singles.firstOrNull {
                    isAutoCorrectable(it, n, lowercase) && (it === bestFinish || best - it.score <= AUTOCORRECT_MARGIN)
                }
                val pick = if (split != null && (single == null || split.score > single.score)) split else single
                pick?.let { applyCase(it.text, word) }
            }
            // A slip first, so "tue" (meant "the") isn't taken for "Tue".
            node >= 0 -> (slipFix(node, word, learnedCount, ranked, predicted) ?: contextFix(node, word, learnedCount, ranked, previous))
                ?.let { applyCase(it.text, word) } ?: caseFix(typed, node, word)
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

    /** One side of a split: the word read there, how common it is, and what reading it cost. */
    private class Part(val text: String, val freq: Int, val cost: Float)

    /**
     * What was typed read as two words: a space never typed ("thelink"), or one that landed
     * on a letter above the space bar ("sincebthe"). Each side is a word as typed, or one side
     * takes a small correction of its own ("lastbniggt"). Null when no reading works.
     */
    private fun splitReading(word: TypedWord, folded: String, geometry: KeyGeometry?, lowercase: Boolean, previous: String?): Candidate? {
        val n = folded.length
        var best: Candidate? = null
        fun offer(left: Part, right: Part, between: Float) {
            val cost = left.cost + right.cost + between
            val score = left.freq + right.freq - SPLIT_WORD_OFFSET - COST_WEIGHT * cost - shortPenalty(left) - shortPenalty(right) +
                pairBonus(previous, left.text) + pairBonus(left.text, right.text)
            if (best.let { it == null || score > it.score }) {
                best = Candidate("${left.text} ${right.text}", score, cost, Suggestion.Kind.CORRECTION, minOf(left.freq, right.freq))
            }
        }
        // Splits where one side is a word and the other needs correcting, most promising first.
        class Pending(val known: Part, val knownLeft: Boolean, val from: Int, val to: Int, val between: Float)
        val pending = ArrayList<Pending>()
        fun consider(leftEnd: Int, rightStart: Int, between: Float) {
            val left = exactPart(folded, 0, leftEnd, lowercase)
            val right = exactPart(folded, rightStart, n, lowercase)
            // A side is corrected only when long enough to tell, or as short as two letters
            // where a tap above the space bar marks the break ("thbevenings" is "the evenings").
            val shortest = if (rightStart > leftEnd) 2 else 3
            when {
                left != null && right != null -> offer(left, right, between)
                left != null && n - rightStart >= shortest -> pending.add(Pending(left, true, rightStart, n, between))
                right != null && leftEnd >= shortest -> pending.add(Pending(right, false, 0, leftEnd, between))
            }
        }
        for (i in 1 until n) {
            consider(i, i, MISSED_SPACE_COST)
            if (i + 1 < n) geometry?.spaceCost(folded[i], word.tapX(i), word.tapY(i))?.let { consider(i, i + 1, it) }
        }
        pending.sortByDescending { it.known.freq }
        for (p in pending.take(MAX_SPLIT_CORRECTIONS)) {
            correctedParts(word, folded, p.from, p.to, geometry, lowercase) { other ->
                if (p.knownLeft) offer(p.known, other, p.between) else offer(other, p.known, p.between)
            }
        }
        return best
    }

    /** What the word before says about [text] coming next, on the frequency scale; 0 when it says nothing. */
    private fun pairBonus(previous: String?, text: String): Float {
        val bits = previous?.let { pairs.score(it, text) } ?: return 0f
        return PAIR_WEIGHT * bits.coerceIn(PAIR_MIN_BITS, PAIR_MAX_BITS)
    }

    /** Single letters stand alone only as "a" and "I", and even those are a stretch. */
    private fun shortPenalty(p: Part) = if (p.text.length == 1) SPLIT_LETTER_PENALTY else 0f

    /** Letters [from] until [to] as a word just as typed, if a common one. */
    private fun exactPart(folded: String, from: Int, to: Int, lowercase: Boolean): Part? {
        val part = folded.substring(from, to)
        if (part.length == 1 && part != "a" && part != "i") return null
        val node = lexicon.lookup(part)
        if (node < 0 || !lexicon.isSuggestable(node)) return null
        return part(lexicon.wordAt(node), lexicon.frequency(node), 0f, lowercase)
    }

    /** Each word a small slip away from letters [from] until [to] that can be a side of a split. */
    private fun correctedParts(word: TypedWord, folded: String, from: Int, to: Int, geometry: KeyGeometry?, lowercase: Boolean, block: (Part) -> Unit) {
        val query = SpatialQuery(word.slice(from, to), geometry, folded.substring(from, to), SPLIT_CORRECTION_COST, -1f, 0)
        lexicon.fuzzySearch(query) { found, cost, isCompletion ->
            if (!isCompletion && lexicon.isSuggestable(found)) part(lexicon.wordAt(found), lexicon.frequency(found), cost, lowercase)?.let(block)
        }
    }

    /**
     * A side of a split, if common enough. Typed in lower case, a name only if a well-known one
     * ("kaylee" isn't "Kay Lee"), and never an abbreviation ("cheee" isn't "CH see").
     */
    private fun part(text: String, dictFreq: Int, cost: Float, lowercase: Boolean): Part? {
        val freq = dictFreq + history.boost(text)
        if (freq < SPLIT_MIN_FREQ) return null
        if (lowercase && text != "I" && text.any { it.isUpperCase() }) {
            if (text.length > 1 && text.all { !it.isLetter() || it.isUpperCase() }) return null
            if (freq < NAME_MIN_FREQ) return null
        }
        return Part(text, freq, cost)
    }

    private fun isAutoCorrectable(c: Candidate, typedLength: Int, lowercase: Boolean): Boolean {
        if (c.kind == Suggestion.Kind.SHORTCUT) return c.cost == 0f
        if (typedLength < 2) return false
        // Typed in lower case, a slip far more likely means an everyday word than an
        // abbreviation or a little-known name: "ppl" isn't "PPP", "lolol" isn't "Rollo".
        if (lowercase && c.kind != Suggestion.Kind.LEARNED && c.text.any { it.isUpperCase() }) {
            if (c.text.length > 1 && c.text.all { !it.isLetter() || it.isUpperCase() }) return false
            if (c.freq < NAME_MIN_FREQ) return false
        }
        val maxAuto = when {
            // One neighbouring key, even squarely on it ("ti" for "to"), or two letters swapped.
            typedLength == 2 -> 0.8f
            typedLength <= 4 -> 1f
            typedLength <= 7 -> 1.6f
            else -> 2.2f
        }
        if (c.cost > maxAuto) return false
        if (c.kind == Suggestion.Kind.COMPLETION) {
            if (c.cost > 0f) return false
            val extra = c.text.length - typedLength
            // Two or three letters are finished only as an everyday word one longer: "th" is
            // "the" and "ful" is "full" (but "nav" isn't "Navy").
            val dropped = extra == 1 && c.text.none { it.isUpperCase() } &&
                (typedLength == 2 && c.freq >= TWO_LETTER_FINISH_FREQ || typedLength == 3 && c.freq >= SHORT_FINISH_FREQ)
            if (!dropped && !(typedLength >= 4 && extra <= 1) && !(typedLength >= 6 && extra <= 2)) return false
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
     * A slip onto a real word that doesn't fit after the word before, when a word one slip
     * away fits far better: "I jeep" is "I keep". The typed word must be unexpected there and
     * the other far likelier, by more the commoner the typed word is. A word the user has
     * typed before is theirs.
     */
    private fun contextFix(node: Int, word: TypedWord, typedBefore: Int, ranked: List<Candidate>, previous: String?): Candidate? {
        if (previous == null || typedBefore > 0 || word.length < 2) return null
        val typed = lexicon.wordAt(node)
        val typedFits = pairs.score(previous, typed) ?: return null
        if (typedFits > CONTEXT_TYPED_MAX_BITS) return null
        // In lower case, a word the dictionary only knows as a name ("kidd") is as good as rare.
        val asName = word.chars.none { it.isUpperCase() } && typed.any { it.isUpperCase() }
        val typedFreq = lexicon.frequency(node) - if (asName) CONTEXT_NAME_DISCOUNT else 0f
        for (c in ranked.take(CONTEXT_CANDIDATES)) {
            val slip = when (c.kind) {
                Suggestion.Kind.CORRECTION -> c.cost.takeIf { it <= CONTEXT_SLIP_COST }
                // One letter short: a tap that never registered.
                Suggestion.Kind.COMPLETION -> Lexicon.DROPPED_COST.takeIf { c.cost == 0f && c.text.length == typed.length + 1 }
                else -> null
            } ?: continue
            val fits = pairs.score(previous, c.text) ?: continue
            val gain = fits - typedFits
            if (gain < CONTEXT_MIN_GAIN_BITS) continue
            val margin = when {
                typedFreq <= CONTEXT_RARE_FREQ -> CONTEXT_MARGIN_RARE
                typedFreq <= CONTEXT_MID_FREQ -> CONTEXT_MARGIN_MID
                else -> CONTEXT_MARGIN_COMMON
            }
            if (c.freq - COST_WEIGHT * slip - typedFreq + PAIR_WEIGHT * gain >= margin) return c
        }
        return null
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
        // A correction can be two words ("a bit", "the station"): each follows the one before.
        var before = previous
        for (part in word.split(' ')) {
            val clean = part.trim('\'', '-', '.')
            if (clean.isEmpty() || clean.any { it.isDigit() } || clean.length > 32) {
                before = null
                continue
            }
            history.record(clean, before, now)
            before = clean
        }
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
         * Frequency points for each bit (doubling) of how much likelier a word is after the one
         * before than in general: about what a doubling is worth on the dictionary's scale.
         */
        const val PAIR_WEIGHT = 6f
        /** The word before nudges, but never decides alone: what it says is kept within these bits. */
        const val PAIR_MIN_BITS = -3f
        const val PAIR_MAX_BITS = 5f
        /** Words read apart pay for the second word: the likelier one word is, the less two can win. */
        const val SPLIT_WORD_OFFSET = 230f
        /** A space never typed between two words ("thelink"). */
        const val MISSED_SPACE_COST = 0.8f
        /** Each side of a split must be at least this common a word... */
        const val SPLIT_MIN_FREQ = 80
        /** ...and a lone "a" or "I" pays this much more. */
        const val SPLIT_LETTER_PENALTY = 25f
        /** One side of a split may be corrected this much ("lastbniggt"), trying this many sides at most. */
        const val SPLIT_CORRECTION_COST = 1f
        const val MAX_SPLIT_CORRECTIONS = 3
        /**
         * A real word is weighed against the [CONTEXT_CANDIDATES] best alternatives after the word
         * before only when it fits there this badly (in bits), against one a slip away: a tap
         * near the key beside, two letters swapped or a letter dropped...
         */
        const val CONTEXT_TYPED_MAX_BITS = -1f
        const val CONTEXT_CANDIDATES = 5
        const val CONTEXT_SLIP_COST = 0.85f
        /** ...that fits at least this much better... */
        const val CONTEXT_MIN_GAIN_BITS = 3f
        /** ...by a margin that grows with how common the typed word is (rare, middling, common). */
        const val CONTEXT_RARE_FREQ = 90
        const val CONTEXT_MID_FREQ = 130
        const val CONTEXT_MARGIN_RARE = 20f
        const val CONTEXT_MARGIN_MID = 40f
        const val CONTEXT_MARGIN_COMMON = 60f
        /** Typed in lower case, a word the dictionary only knows as a name counts as this much rarer. */
        const val CONTEXT_NAME_DISCOUNT = 30f
        /** How far behind the best guess a correction may score (measured on simulated typing). */
        const val AUTOCORRECT_MARGIN = 20f
        /** Lower-case typing is corrected to a name only this common ("London", not "Rollo"). */
        const val NAME_MIN_FREQ = 100
        /** Three letters short of an everyday word by one ("wil", "hav") are finished as it... */
        const val SHORT_FINISH_FREQ = 120
        /** ...and two letters only as a very common one ("th", "wa"). */
        const val TWO_LETTER_FINISH_FREQ = 160
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
