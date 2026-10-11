package io.github.duddmeister.lumen.ime

import android.icu.text.BreakIterator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.duddmeister.lumen.engine.KeyGeometry
import io.github.duddmeister.lumen.engine.NextWordModel
import io.github.duddmeister.lumen.engine.Suggestion
import io.github.duddmeister.lumen.engine.SuggestionEngine
import io.github.duddmeister.lumen.engine.SuggestionOptions
import io.github.duddmeister.lumen.engine.SuggestionResult
import io.github.duddmeister.lumen.engine.TextContext
import io.github.duddmeister.lumen.engine.TypedWord
import kotlin.math.abs

/**
 * Everything Lumen does to the text field: composing the current word,
 * autocorrecting it (with one-tap undo), smart spacing around punctuation,
 * double-space period, glide-typed words, cursor control and deletion.
 *
 * Editors report selection changes asynchronously, so the logic remembers
 * where its own edits should leave the cursor and only treats other moves as
 * the user's.
 */
class InputLogic(private val host: Host) {

    interface Host {
        val connection: InputConnection?
        val engine: SuggestionEngine?
        fun keyGeometry(): KeyGeometry?
        /** Sends a key press as raw key events (terminals, single-line enter, arrows). */
        fun sendKey(keyCode: Int)
        /** Sends text to editors that only understand key events. */
        fun sendRawText(text: String)
        /** Text, cursor or suggestions changed: refresh shift state and the strip. */
        fun onInputChanged()
    }

    class Settings {
        var autoCorrect = true
        var suggestions = true
        var doubleSpacePeriod = true
        var learn = true
        var emojiSuggestions = true
        var autoCaps = true
    }

    val settings = Settings()
    var editor = EditorContext(null)
        private set

    /** The word being typed, shown underlined in the editor. */
    val composing = TypedWord()
    var composingFromGesture = false
        private set
    /** Alternatives for the glided word currently composing. */
    var gestureAlternatives: List<String> = emptyList()
        private set
    private var composingRestored = false

    /** A word is being typed or was just glided, as opposed to one the cursor came to rest on. */
    val typingWord: Boolean get() = composing.isNotEmpty && !composingRestored
    private var composeStart = -1
    private var composingContext: List<String> = emptyList()

    var result: SuggestionResult = SuggestionResult.EMPTY
        private set
    var predictions: List<Suggestion> = emptyList()
        private set
    /** True right after a sentence end, so predictions can be capitalised. */
    var predictionsAtSentenceStart = false
        private set

    /** A glided word wants a space before whatever comes next, unless that is punctuation. */
    private var phantomSpace = false
    /** We inserted a space after a picked word; closing punctuation swaps with it. */
    private var autoSpaceInserted = false
    private var lastSpaceAt = 0L

    private class Correction(val typed: String, val committed: String, val separator: String, val cursor: Int)
    private var lastCorrection: Correction? = null

    private var expectedStart = -1
    private var expectedEnd = -1
    /** Editor states our edits will report, oldest first; updates arrive in this order. */
    private val pending = ArrayDeque<Long>()
    private val pendingAt = ArrayDeque<Long>()
    var selectionStart = -1
        private set
    var selectionEnd = -1
        private set

    private var swipeAnchor = -1
    /** The word being typed when the field restarted; it carries on as the same word. */
    private var continuing: String? = null
    private var swipeText: CharSequence = ""
    private var swipeStart = -1
    private var deleteRepeats = 0

    private val handler = Handler(Looper.getMainLooper())
    private val resume = Runnable { host.connection?.let { resumeAtCursor(it, null) } }

    private val useComposing get() = editor.suggestionsAllowed && !editor.isRaw

    // ------------------------------------------------------------ lifecycle

    /**
     * Starts work on a field. The selection defaults to what the field reported
     * when input began; after a restart the caller passes where the cursor is now.
     */
    fun start(
        editor: EditorContext,
        selectionStart: Int = editor.initialSelectionStart,
        selectionEnd: Int = editor.initialSelectionEnd,
    ) {
        this.editor = editor
        handler.removeCallbacks(resume)
        resetComposing()
        phantomSpace = false
        autoSpaceInserted = false
        lastCorrection = null
        lastSpaceAt = 0
        swipeAnchor = -1
        this.selectionStart = selectionStart
        this.selectionEnd = selectionEnd
        continuing = null
        pending.clear()
        pendingAt.clear()
        expectedStart = selectionStart
        expectedEnd = selectionEnd
        refreshPredictions(null)
    }

    /**
     * The same field restarted: folding or unfolding, rotating, or the app asking.
     * The editor still shows our word as composing, so commit it, carry on from
     * where the cursor really is, then put the word back into composition.
     */
    fun restart(editor: EditorContext, selectionStart: Int, selectionEnd: Int) {
        val word = composing.toString()
        finish()
        start(editor, selectionStart, selectionEnd)
        if (word.isNotEmpty()) {
            continuing = word
            // Committing makes the editor report that composition ended. That report can
            // arrive after the word is resumed, so expect it rather than mistake it for
            // the editor dropping the resumed word.
            expect(selectionStart, selectionEnd)
        }
        handler.postDelayed(resume, RESUME_DELAY_MS)
    }

    fun finish() {
        handler.removeCallbacks(resume)
        continuing = null
        if (composing.isNotEmpty) host.connection?.finishComposingText()
        resetComposing()
        phantomSpace = false
        autoSpaceInserted = false
        lastCorrection = null
        result = SuggestionResult.EMPTY
        predictions = emptyList()
    }

    private fun resetComposing() {
        composing.clear()
        composingFromGesture = false
        composingRestored = false
        gestureAlternatives = emptyList()
        composeStart = -1
        result = SuggestionResult.EMPTY
    }

    // ------------------------------------------------------------ typing

    /** A character key or an alternative from a long-press panel. */
    fun onCharacter(text: String, x: Float = Float.NaN, y: Float = Float.NaN) {
        if (text.isEmpty()) return
        if (editor.isRaw) { host.sendRawText(text); return }
        val ic = host.connection ?: return
        val c = text[0]
        val wordChar = TextContext.isWordChar(c) && !((c == '\'' || c == '’') && composing.isEmpty) && text.length <= 2
        if (!wordChar) {
            onSeparator(text)
            return
        }
        lastCorrection = null
        lastSpaceAt = 0
        if (!useComposing) {
            consumePhantomSpace(ic)
            commitText(ic, text)
            autoSpaceInserted = false
            host.onInputChanged()
            return
        }
        ic.beginBatchEdit()
        if (composingFromGesture) finishComposing(ic)
        consumePhantomSpace(ic)
        autoSpaceInserted = false
        if (composing.isEmpty) composingContext = readContext(ic)
        composingRestored = false
        for (ch in text) composing.add(ch, x, y)
        setComposing(ic, composing.toString())
        ic.endBatchEdit()
        updateSuggestions()
    }

    /** Punctuation and symbols end the current word. */
    private fun onSeparator(sep: String) {
        val ic = host.connection ?: return
        val swaps = sep.length == 1 && sep[0] in SWAP_PUNCTUATION
        lastCorrection = null
        ic.beginBatchEdit()
        when {
            composing.isNotEmpty -> {
                if (composingFromGesture && !swaps) {
                    finishComposing(ic)
                    consumePhantomSpace(ic)
                    commitText(ic, sep)
                } else {
                    val autocorrect = !composingFromGesture && sep.length == 1 && sep[0] in AUTOCORRECT_TRIGGERS
                    val gesture = composingFromGesture
                    commitComposing(ic, sep, autocorrect)
                    if (!gesture) phantomSpace = false
                }
            }
            autoSpaceInserted && swaps && charBefore(ic) == ' ' -> {
                // "word |" + "." becomes "word. |"
                deleteBefore(ic, 1)
                commitText(ic, "$sep ")
            }
            else -> {
                if (!swaps) consumePhantomSpace(ic)
                commitText(ic, sep)
                autoSpaceInserted = false
            }
        }
        ic.endBatchEdit()
        lastSpaceAt = 0
        refreshPredictions(ic)
        host.onInputChanged()
    }

    /** Literal text that should not join the current word: emoji, ".com", clipboard pastes. */
    fun onText(text: String) {
        if (text.isEmpty()) return
        if (editor.isRaw) { host.sendRawText(text); return }
        val ic = host.connection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty) finishComposing(ic)
        consumePhantomSpace(ic)
        commitText(ic, text)
        ic.endBatchEdit()
        autoSpaceInserted = false
        lastCorrection = null
        lastSpaceAt = 0
        refreshPredictions(ic)
        host.onInputChanged()
    }

    /**
     * Commits dictated [text] at the cursor: after a space unless it starts the text or
     * a line, capitalised where a sentence starts, and otherwise with its first word in
     * lower case (speech services capitalise each stretch of speech).
     */
    fun onVoice(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        if (editor.isRaw) { host.sendRawText(said); return }
        val ic = host.connection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty) finishComposing(ic)
        phantomSpace = false
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val spaced = before.isNotEmpty() && !before.last().isWhitespace()
        val sentence = settings.autoCaps && editor.capitalisation &&
            if (spaced) TextContext.atSentenceStart("$before ") else ic.getCursorCapsMode(editor.inputType) != 0
        val words = if (sentence) said.replaceFirstChar { it.uppercaseChar() } else lowerFirstWord(said)
        commitText(ic, if (spaced) " $words" else words)
        ic.endBatchEdit()
        autoSpaceInserted = false
        lastCorrection = null
        lastSpaceAt = 0
        refreshPredictions(ic)
        host.onInputChanged()
    }

    /** "Go home" becomes "go home"; "London" and "I'm" keep their capitals. */
    private fun lowerFirstWord(said: String): String {
        val first = said.takeWhile { it.isLetter() || it == '\'' }
        if (first.isEmpty() || !first[0].isUpperCase() || first.drop(1).any { it.isUpperCase() }) return said
        val lexicon = host.engine?.lexicon ?: return said
        val lower = first.lowercase()
        return if (lexicon.findExact(lower) >= 0) lower + said.substring(first.length) else said
    }

    fun onSpace() {
        if (editor.isRaw) { host.sendRawText(" "); return }
        val ic = host.connection ?: return
        val now = SystemClock.uptimeMillis()
        lastCorrection = null
        ic.beginBatchEdit()
        when {
            composing.isNotEmpty -> {
                commitComposing(ic, " ", autocorrect = !composingFromGesture)
                phantomSpace = false
                autoSpaceInserted = false
                lastSpaceAt = now
            }
            phantomSpace -> {
                phantomSpace = false
                commitText(ic, " ")
                lastSpaceAt = now
            }
            settings.doubleSpacePeriod && now - lastSpaceAt < DOUBLE_SPACE_MS && canAddPeriod(ic) -> {
                deleteBefore(ic, 1)
                commitText(ic, ". ")
                lastSpaceAt = 0
                autoSpaceInserted = false
            }
            else -> {
                commitText(ic, " ")
                lastSpaceAt = now
                autoSpaceInserted = false
            }
        }
        ic.endBatchEdit()
        refreshPredictions(ic)
        host.onInputChanged()
    }

    private fun canAddPeriod(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(3, 0) ?: return false
        if (before.length < 2 || before[before.length - 1] != ' ') return false
        val c = before[before.length - 2]
        return c.isLetterOrDigit() || c in "\")'’”"
    }

    fun onEnter() {
        val ic = host.connection
        if (editor.isRaw || ic == null) { host.sendKey(KeyEvent.KEYCODE_ENTER); return }
        ic.beginBatchEdit()
        if (composing.isNotEmpty) commitComposing(ic, "", autocorrect = !composingFromGesture)
        phantomSpace = false
        autoSpaceInserted = false
        lastCorrection = null
        lastSpaceAt = 0
        ic.endBatchEdit()
        when {
            editor.action != EditorInfo.IME_ACTION_NONE -> ic.performEditorAction(editor.action)
            editor.isMultiLine -> commitText(ic, "\n")
            else -> host.sendKey(KeyEvent.KEYCODE_ENTER)
        }
        refreshPredictions(ic)
        host.onInputChanged()
    }

    // ------------------------------------------------------------ deleting

    fun onDelete(repeating: Boolean) {
        val ic = host.connection
        if (editor.isRaw || ic == null) { host.sendKey(KeyEvent.KEYCODE_DEL); return }
        deleteRepeats = if (repeating) deleteRepeats + 1 else 0
        lastSpaceAt = 0
        if (selectionStart >= 0 && selectionEnd > selectionStart && composing.isEmpty) {
            commitText(ic, "")
            lastCorrection = null
            refreshPredictions(ic)
            host.onInputChanged()
            return
        }
        val undo = lastCorrection
        lastCorrection = null
        if (undo != null && !repeating && expectedEnd == undo.cursor && expectedStart == undo.cursor) {
            // Backspace right after an autocorrection restores what was typed.
            ic.beginBatchEdit()
            deleteBefore(ic, undo.committed.length + undo.separator.length)
            composingContext = readContext(ic)
            composing.set(undo.typed)
            composingFromGesture = false
            composingRestored = true
            setComposing(ic, undo.typed)
            ic.endBatchEdit()
            host.engine?.rejectAutoCorrection(undo.typed, System.currentTimeMillis())
            autoSpaceInserted = false
            updateSuggestions()
            return
        }
        autoSpaceInserted = false
        if (composing.isNotEmpty) {
            if (composingFromGesture) {
                // A glided word goes in one tap, like it came in.
                composing.clear()
            } else {
                composing.removeLast(lastGraphemeLength(composing.chars))
            }
            if (composing.isEmpty) {
                commitText(ic, "")
                resetComposing()
                phantomSpace = false
                refreshPredictions(ic)
                host.onInputChanged()
            } else {
                setComposing(ic, composing.toString())
                updateSuggestions()
            }
            return
        }
        phantomSpace = false
        val before = ic.getTextBeforeCursor(64, 0)
        when {
            // The editor won't say what's before the cursor: delete with key events.
            before == null -> host.sendKey(KeyEvent.KEYCODE_DEL)
            // Nothing before the cursor. A tap still sends a backspace, for apps that act on
            // one in an empty field (removing a recipient, say). A held key stops here: many
            // apps take a backspace in an empty field as "close", hiding the keyboard mid-hold.
            before.isEmpty() -> if (!repeating) host.sendKey(KeyEvent.KEYCODE_DEL)
            repeating && deleteRepeats > WORD_DELETE_AFTER -> deleteWordBefore(ic, before)
            else -> {
                val n = lastGraphemeLength(before)
                deleteBefore(ic, n)
                val rest = before.subSequence(0, before.length - n)
                if (!repeating && rest.isNotEmpty() && TextContext.isWordChar(rest[rest.length - 1]) && !TextContext.isWordChar(before[before.length - 1])) {
                    // Backspacing into a word brings its suggestions back.
                    resumeAtCursor(ic, rest)
                } else {
                    refreshPredictions(ic, rest)
                }
            }
        }
        host.onInputChanged()
    }

    /** Deletes the word before the cursor, with any spaces after it; [before] is the text up to the cursor, not empty. */
    private fun deleteWordBefore(ic: InputConnection, before: CharSequence) {
        var i = before.length
        while (i > 0 && !TextContext.isWordChar(before[i - 1])) i--
        while (i > 0 && TextContext.isWordChar(before[i - 1])) i--
        val n = (before.length - i).coerceAtLeast(1)
        deleteBefore(ic, n)
        refreshPredictions(ic, before.subSequence(0, before.length - n))
    }

    /** Swipe left from delete: select [words] words, then delete them when [done]. */
    fun onDeleteSwipe(words: Int, done: Boolean) {
        val ic = host.connection ?: return
        if (swipeAnchor < 0) {
            if (done) return
            if (composing.isNotEmpty) finishComposing(ic)
            phantomSpace = false
            lastCorrection = null
            swipeAnchor = selectionEnd.takeIf { it >= 0 && selectionStart == selectionEnd } ?: return
            swipeText = ic.getTextBeforeCursor(512, 0) ?: ""
        }
        var i = swipeText.length
        repeat(words) {
            while (i > 0 && !TextContext.isWordChar(swipeText[i - 1])) i--
            while (i > 0 && TextContext.isWordChar(swipeText[i - 1])) i--
        }
        val start = swipeAnchor - (swipeText.length - i)
        if (!done) {
            if (start != swipeStart) {
                swipeStart = start
                ic.setSelection(start, swipeAnchor)
                expect(start, swipeAnchor)
            }
            return
        }
        if (words > 0 && start < swipeAnchor) {
            ic.setSelection(start, swipeAnchor)
            expect(start, swipeAnchor)
            commitText(ic, "")
        } else {
            ic.setSelection(swipeAnchor, swipeAnchor)
            expect(swipeAnchor, swipeAnchor)
        }
        swipeAnchor = -1
        swipeStart = -1
        refreshPredictions(ic)
        host.onInputChanged()
    }

    // ------------------------------------------------------------ suggestions & gestures

    /** A suggestion from the strip was tapped. */
    fun onPick(text: String, emoji: Boolean) {
        val ic = host.connection ?: return
        val now = System.currentTimeMillis()
        ic.beginBatchEdit()
        if (emoji) {
            if (composing.isNotEmpty) commitComposing(ic, " ", autocorrect = !composingFromGesture)
            consumePhantomSpace(ic)
            commitText(ic, "$text ")
        } else if (composing.isNotEmpty && composingFromGesture) {
            // Swap the glided word for an alternative; it stays editable.
            composing.set(text)
            setComposing(ic, text)
            ic.endBatchEdit()
            host.onInputChanged()
            return
        } else if (composing.isNotEmpty) {
            val typed = composing.toString()
            // Mid-sentence corrections keep the space that is already there.
            val after = ic.getTextAfterCursor(1, 0)
            val space = if (after.isNullOrEmpty()) " " else ""
            commitText(ic, text + space)
            if (text != typed) {
                lastCorrection = Correction(typed, text, space, expectedEnd)
            } else {
                host.engine?.rejectAutoCorrection(typed, now) // they insisted on their spelling: learn it
            }
            learn(text)
            resetComposing()
        } else {
            consumePhantomSpace(ic)
            val before = charBefore(ic)
            if (before != null && !before.isWhitespace()) commitText(ic, " ")
            composingContext = readContext(ic)
            commitText(ic, "$text ")
            learn(text)
        }
        ic.endBatchEdit()
        phantomSpace = false
        autoSpaceInserted = true
        lastSpaceAt = SystemClock.uptimeMillis()
        refreshPredictions(ic)
        host.onInputChanged()
    }

    /** A glide gesture produced [words] (best first, already cased). */
    fun onGesture(words: List<String>) {
        if (words.isEmpty()) return
        val ic = host.connection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty) {
            if (composingFromGesture) finishComposing(ic) else commitComposing(ic, "", autocorrect = true)
            phantomSpace = true
        }
        lastCorrection = null
        val before = charBefore(ic)
        val needSpace = phantomSpace || (before != null && !before.isWhitespace() && before !in "([{\"“‘'/@#\n")
        phantomSpace = false
        if (needSpace) commitText(ic, " ")
        composingContext = readContext(ic)
        composing.set(words[0])
        composingFromGesture = true
        composingRestored = false
        gestureAlternatives = words
        result = SuggestionResult.EMPTY
        setComposing(ic, words[0])
        ic.endBatchEdit()
        phantomSpace = true
        autoSpaceInserted = false
        lastSpaceAt = 0
        host.onInputChanged()
    }

    /** Moves the cursor [steps] characters (back when negative). */
    fun onCursorSteps(steps: Int) {
        val ic = host.connection ?: return
        if (composing.isNotEmpty) finishComposing(ic)
        phantomSpace = false
        autoSpaceInserted = false
        lastCorrection = null
        val pos = if (steps < 0) selectionStart else selectionEnd
        if (pos < 0 || editor.isRaw) {
            repeat(abs(steps)) { host.sendKey(if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }
            return
        }
        val target = if (steps < 0) {
            val before = ic.getTextBeforeCursor(-steps + 1, 0) ?: ""
            var n = minOf(-steps, before.length)
            if (n < before.length && Character.isLowSurrogate(before[before.length - n])) n++
            pos - n
        } else {
            val after = ic.getTextAfterCursor(steps + 1, 0) ?: ""
            var n = minOf(steps, after.length)
            if (n in 1 until after.length && Character.isLowSurrogate(after[n])) n++
            pos + n
        }
        moveCursor(ic, target)
    }

    /** Space-bar swipes: back to the start of a word, or on to the end of one. */
    fun onCursorWords(words: Int) {
        val ic = host.connection ?: return
        val pos = if (words < 0) selectionStart else selectionEnd
        if (pos < 0 || editor.isRaw) {
            // No text to read word boundaries from: move by characters instead.
            onCursorSteps(words)
            return
        }
        if (composing.isNotEmpty) finishComposing(ic)
        phantomSpace = false
        autoSpaceInserted = false
        lastCorrection = null
        val reach = WORD_JUMP_REACH * abs(words)
        val delta = if (words < 0) {
            TextContext.wordJump(ic.getTextBeforeCursor(reach, 0) ?: "", "", words)
        } else {
            TextContext.wordJump("", ic.getTextAfterCursor(reach, 0) ?: "", words)
        }
        if (delta != 0) moveCursor(ic, pos + delta)
    }

    /**
     * Puts the cursor at [target] and counts it as there straight away: during a slide
     * the next step comes before the editor reports this one.
     */
    private fun moveCursor(ic: InputConnection, target: Int) {
        ic.setSelection(target, target)
        expect(target, target)
        selectionStart = target
        selectionEnd = target
        handler.removeCallbacks(resume)
        handler.postDelayed(resume, RESUME_DELAY_MS)
    }

    fun updateSuggestions() {
        val engine = host.engine
        result = if (composing.isNotEmpty && !composingFromGesture && engine != null && useComposing) {
            engine.suggest(
                composing, composingContext, host.keyGeometry(),
                SuggestionOptions(
                    autoCorrect = settings.autoCorrect && editor.autoCorrectAllowed,
                    emojiSuggestions = settings.emojiSuggestions,
                ),
            )
        } else {
            SuggestionResult.EMPTY
        }
        host.onInputChanged()
    }

    /** Predicts the next word from the text before the cursor. */
    fun refreshPredictions(ic: InputConnection?, before: CharSequence? = null) {
        val engine = host.engine
        if (engine == null || !useComposing || !settings.suggestions || composing.isNotEmpty) {
            predictions = emptyList()
            return
        }
        val text = before ?: ic?.getTextBeforeCursor(64, 0) ?: ""
        val context = TextContext.contextWords(text)
        predictionsAtSentenceStart = context.firstOrNull() == NextWordModel.SENTENCE_START && context.size == 1
        predictions = engine.predict(context, 3)
    }

    /** Whether the next letter should be capitalised (sentence start, or a caps field). */
    fun autoCapitalize(): Boolean {
        if (!settings.autoCaps || !editor.capitalisation) return false
        if (editor.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true
        if (composing.isNotEmpty) return false
        val ic = host.connection ?: return false
        if (phantomSpace) {
            val before = ic.getTextBeforeCursor(32, 0) ?: return false
            return TextContext.atSentenceStart("$before ")
        }
        return ic.getCursorCapsMode(editor.inputType) != 0
    }

    // ------------------------------------------------------------ selection tracking

    fun onUpdateSelection(newStart: Int, newEnd: Int, candidatesStart: Int) {
        selectionStart = newStart
        selectionEnd = newEnd
        if (isExpected(newStart, newEnd)) {
            if (pending.isEmpty() && composing.isNotEmpty && candidatesStart < 0 && composeStart >= 0) {
                // The latest state says the editor dropped our composing region (the text stays).
                resetComposing()
                host.onInputChanged()
            }
            return
        }
        // Someone else moved the cursor or changed the text.
        pending.clear()
        pendingAt.clear()
        expectedStart = newStart
        expectedEnd = newEnd
        if (swipeAnchor >= 0) return
        val ic = host.connection
        if (composing.isNotEmpty) {
            ic?.finishComposingText()
            resetComposing()
        }
        phantomSpace = false
        autoSpaceInserted = false
        lastCorrection = null
        lastSpaceAt = 0
        predictions = emptyList()
        handler.removeCallbacks(resume)
        if (newStart == newEnd) handler.postDelayed(resume, RESUME_DELAY_MS)
        host.onInputChanged()
    }

    /**
     * Whether an update reports a state our own edits produced. Updates arrive in
     * edit order (batches may skip states), so a match consumes everything before it.
     */
    private fun isExpected(start: Int, end: Int): Boolean {
        if (expectedStart < 0) {
            expectedStart = start
            expectedEnd = end
            return true
        }
        val now = SystemClock.uptimeMillis()
        while (pendingAt.isNotEmpty() && now - pendingAt.first() > BELATED_MS) {
            pending.removeFirst()
            pendingAt.removeFirst()
        }
        val packed = pack(start, end)
        val index = pending.indexOf(packed)
        if (index >= 0) {
            repeat(index + 1) {
                pending.removeFirst()
                pendingAt.removeFirst()
            }
            return true
        }
        return start == expectedStart && end == expectedEnd
    }

    /** Records the state an edit we just sent will report back. */
    private fun expect(start: Int, end: Int) {
        expectedStart = start
        expectedEnd = end
        if (start < 0) return
        pending.addLast(pack(start, end))
        pendingAt.addLast(SystemClock.uptimeMillis())
        if (pending.size > MAX_PENDING) {
            pending.removeFirst()
            pendingAt.removeFirst()
        }
    }

    private fun pack(start: Int, end: Int) = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)

    /** Puts a word the cursor rests on back into composition so its suggestions show. */
    private fun resumeAtCursor(ic: InputConnection, knownBefore: CharSequence?) {
        val carried = continuing
        continuing = null
        if (!useComposing || composing.isNotEmpty || selectionEnd < 0 || selectionStart != selectionEnd) return
        val before = knownBefore ?: ic.getTextBeforeCursor(48, 0) ?: return
        val after = ic.getTextAfterCursor(8, 0) ?: ""
        val word = TextContext.wordBeforeCursor(before)
        val insideWord = after.isNotEmpty() && TextContext.isWordChar(after[0])
        if (word.isEmpty() || insideWord || word.length > 32 || word.any { it.isDigit() }) {
            refreshPredictions(ic, before)
            host.onInputChanged()
            return
        }
        val cursor = expectedEnd.takeIf { it >= 0 } ?: selectionEnd
        val start = cursor - word.length
        if (start < 0) return
        ic.setComposingRegion(start, cursor)
        expect(cursor, cursor) // the region change is reported even though the cursor stays put
        composeStart = start
        // The word being typed before a restart carries on: its letters were tapped on
        // keys that may have moved (folding, unfolding), so they sit on the keys' new
        // centres, and autocorrect stays on. Other words come back as restored text.
        val carryOn = word == carried
        val geometry = if (carryOn) host.keyGeometry() else null
        composing.clear()
        for (c in word) {
            val center = geometry?.center(c)
            composing.add(c, center?.get(0) ?: Float.NaN, center?.get(1) ?: Float.NaN)
        }
        composingRestored = !carryOn
        composingFromGesture = false
        composingContext = TextContext.contextWords(before.subSequence(0, before.length - word.length))
        updateSuggestions()
    }

    // ------------------------------------------------------------ editing primitives

    private fun commitComposing(ic: InputConnection, separator: String, autocorrect: Boolean) {
        val typed = composing.toString()
        val correction = if (autocorrect && settings.autoCorrect && editor.autoCorrectAllowed && !composingRestored) {
            result.autoCorrection
        } else null
        val word = correction ?: typed
        commitText(ic, word + separator)
        if (correction != null && correction != typed) lastCorrection = Correction(typed, correction, separator, expectedEnd)
        learn(word)
        resetComposing()
    }

    /** Ends composition, keeping the word as it stands. Only finished (glided) words are learned. */
    private fun finishComposing(ic: InputConnection) {
        val word = composing.toString()
        val complete = composingFromGesture
        ic.finishComposingText()
        if (composeStart >= 0 && expectedEnd >= 0) expect(expectedStart, expectedEnd)
        composeStart = -1
        if (complete && word.isNotEmpty()) learn(word)
        resetComposing()
    }

    private fun consumePhantomSpace(ic: InputConnection) {
        if (!phantomSpace) return
        phantomSpace = false
        val before = charBefore(ic)
        if (before != null && !before.isWhitespace()) commitText(ic, " ")
    }

    private fun learn(word: String) {
        if (!settings.learn || !editor.learningAllowed) return
        val previous = composingContext.lastOrNull()?.takeIf { it != NextWordModel.SENTENCE_START }
        host.engine?.learn(word, previous, System.currentTimeMillis())
    }

    private fun readContext(ic: InputConnection): List<String> =
        TextContext.contextWords(ic.getTextBeforeCursor(64, 0) ?: "")

    private fun charBefore(ic: InputConnection): Char? = ic.getTextBeforeCursor(1, 0)?.firstOrNull()

    /** Commits [text], replacing the composing word or selection if there is one. */
    private fun commitText(ic: InputConnection, text: String) {
        val start = when {
            composeStart >= 0 -> composeStart
            expectedStart >= 0 -> minOf(expectedStart, expectedEnd)
            else -> -1
        }
        ic.commitText(text, 1)
        composeStart = -1
        if (start >= 0) expect(start + text.length, start + text.length) else expect(-1, -1)
    }

    private fun setComposing(ic: InputConnection, text: String) {
        val start = if (composeStart >= 0) composeStart else expectedStart
        ic.setComposingText(text, 1)
        composeStart = if (text.isEmpty()) -1 else start
        if (start >= 0) expect(start + text.length, start + text.length) else expect(-1, -1)
    }

    private fun deleteBefore(ic: InputConnection, n: Int) {
        ic.deleteSurroundingText(n, 0)
        if (expectedStart >= 0) {
            val p = maxOf(0, expectedStart - n)
            expect(p, p)
        }
    }

    private fun lastGraphemeLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(text.toString())
        val start = it.preceding(text.length)
        return if (start == BreakIterator.DONE) 1 else (text.length - start).coerceAtLeast(1)
    }

    companion object {
        /** Closing punctuation that attaches to the previous word ("word ." -> "word."). */
        private const val SWAP_PUNCTUATION = ".,!?;:)]}…"
        private const val AUTOCORRECT_TRIGGERS = ".,!?;:)\"…"
        private const val DOUBLE_SPACE_MS = 1500L
        private const val RESUME_DELAY_MS = 120L
        /** Text read per word jump: plenty for the longest words. */
        private const val WORD_JUMP_REACH = 256
        private const val BELATED_MS = 1500L
        private const val WORD_DELETE_AFTER = 18
        private const val MAX_PENDING = 32
    }
}
