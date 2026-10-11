package io.github.duddmeister.lumen.ime

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.duddmeister.lumen.data.ClipboardHistory
import io.github.duddmeister.lumen.data.EmojiData
import io.github.duddmeister.lumen.engine.CharFolding
import io.github.duddmeister.lumen.engine.KeyGeometry
import io.github.duddmeister.lumen.engine.SuggestionEngine
import io.github.duddmeister.lumen.keyboard.FieldKind
import io.github.duddmeister.lumen.keyboard.GestureTrail
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Key
import io.github.duddmeister.lumen.keyboard.KeyCodes
import io.github.duddmeister.lumen.keyboard.KeyPainter
import io.github.duddmeister.lumen.keyboard.KeyboardLayout
import io.github.duddmeister.lumen.keyboard.KeyboardMode
import io.github.duddmeister.lumen.keyboard.KeyboardView
import io.github.duddmeister.lumen.keyboard.LayoutOptions
import io.github.duddmeister.lumen.keyboard.Layouts
import io.github.duddmeister.lumen.settings.Prefs
import io.github.duddmeister.lumen.settings.SettingsActivity
import io.github.duddmeister.lumen.theme.KeyboardTheme
import io.github.duddmeister.lumen.theme.Themes
import io.github.duddmeister.lumen.ui.ClipboardPanelView
import io.github.duddmeister.lumen.ui.EmojiPanelView
import io.github.duddmeister.lumen.ui.InputRootView
import io.github.duddmeister.lumen.ui.PanelView
import io.github.duddmeister.lumen.ui.SuggestionStripView
import io.github.duddmeister.lumen.ui.ThemePanelView
import io.github.duddmeister.lumen.ui.dp
import java.util.concurrent.Executors
import java.util.function.Consumer

/** Lumen's input method service: wires the views, the input logic and the settings together. */
class LumenIME : InputMethodService(), KeyboardView.Listener, SuggestionStripView.Listener, PanelView.Host,
    InputLogic.Host, SharedPreferences.OnSharedPreferenceChangeListener {

    private enum class Shift { OFF, ONE_SHOT, AUTO, LOCKED }
    private enum class Panel { EMOJI, CLIPBOARD, THEMES }

    /** What the user was looking at when a configuration change (folding, unfolding, rotating) rebuilt the keyboard. */
    private class Carry(val mode: KeyboardMode, val shift: Shift, val panel: Panel?)

    private lateinit var prefs: Prefs
    private lateinit var feedback: Feedback
    private lateinit var clipboard: ClipboardHistory
    private val painter by lazy { KeyPainter(this) }
    private val logic = InputLogic(this)
    private val main = Handler(Looper.getMainLooper())
    private val gestureWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-glide") }

    private var root: InputRootView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var clipboardPanel: ClipboardPanelView? = null
    private var themePanel: ThemePanelView? = null
    private var emojiData: EmojiData? = null

    private var editor = EditorContext(null)
    private var mode = KeyboardMode.TEXT
    private var layout: KeyboardLayout? = null
    private var geometry: KeyGeometry? = null
    private var geometryLayout: KeyboardLayout? = null
    private var geometryWidth = 0
    private var theme: KeyboardTheme = Themes.aurora
    private var blurAvailable = false
    private var blurListener: Consumer<Boolean>? = null

    private var shift = Shift.OFF
    private var shiftHeld = false
    private var typedWhileShiftHeld = false
    private var lastShiftTap = NEVER
    private var lastShiftFrom = Shift.OFF
    private var autoCapsSuppressed = false
    private var symbolTyped = false
    private var gestureActive = false
    private var previewPending = false
    private var carry: Carry? = null

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        feedback = Feedback(this)
        clipboard = ClipboardHistory(this, prefs.store)
        clipboard.onChange = { main.post { refreshStrip() } }
        clipboard.start()
        prefs.store.registerOnSharedPreferenceChangeListener(this)
        applySettings()
        EngineHolder.load(this) {
            logic.updateSuggestions()
            logic.refreshPredictions(currentInputConnection)
            refreshStrip()
        }
        Thread({
            val data = EmojiData.load(this, prefs.store)
            main.post {
                emojiData = data
                emojiPanel?.data = data
            }
        }, "lumen-emoji").start()
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(WindowManager::class.java)?.let { wm ->
                blurAvailable = wm.isCrossWindowBlurEnabled
                val listener = Consumer<Boolean> { enabled ->
                    blurAvailable = enabled
                    applyTheme()
                }
                wm.addCrossWindowBlurEnabledListener(mainExecutor, listener)
                blurListener = listener
            }
        }
    }

    override fun onDestroy() {
        EngineHolder.save(this)
        clipboard.stop()
        prefs.store.unregisterOnSharedPreferenceChangeListener(this)
        if (Build.VERSION.SDK_INT >= 31) {
            blurListener?.let { getSystemService(WindowManager::class.java)?.removeCrossWindowBlurEnabledListener(it) }
        }
        gestureWorker.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val r = InputRootView(this, painter)
        r.keyboard.listener = this
        r.strip.listener = this
        root = r
        emojiPanel = null
        clipboardPanel = null
        themePanel = null
        applyTheme()
        applySettings()
        layout?.let { r.keyboard.setKeyboard(it) }
        return r
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editor = EditorContext(attribute)
        val ic = currentInputConnection
        if (restarting && ic != null) {
            // Same field, restarted: folding or unfolding, rotating, or the app asking.
            // Ask the editor where the cursor is: the EditorInfo still describes where
            // it was when input began.
            val selection = liveSelection(ic)
            if (selection != null) {
                logic.restart(editor, selection[0], selection[1])
                return
            }
            // Without the live cursor, commit the word being typed and start afresh.
            logic.finish()
        }
        logic.start(editor)
    }

    /** The editor's current selection, or null if it cannot say. */
    private fun liveSelection(ic: InputConnection): IntArray? {
        if (Build.VERSION.SDK_INT >= 31) {
            val s = ic.getSurroundingText(0, 0, 0)
            if (s != null && s.offset >= 0) return intArrayOf(s.offset + s.selectionStart, s.offset + s.selectionEnd)
        }
        val e = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        if (e.startOffset < 0 || e.selectionStart < 0) return null
        return intArrayOf(e.startOffset + e.selectionStart, e.startOffset + e.selectionEnd)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editor = EditorContext(info)
        // After a configuration change, come back to the same page, shift and panel.
        val c = carry
        mode = c?.mode ?: editor.mode
        symbolTyped = false
        shift = c?.shift ?: Shift.OFF
        autoCapsSuppressed = false
        // A new input view has no touches in progress, whatever the old one had.
        shiftHeld = false
        gestureActive = false
        root?.showPanel(null)
        applyTheme()
        applySettings()
        rebuildLayout()
        root?.keyboard?.enterIcon = editor.enterIcon
        logic.refreshPredictions(currentInputConnection)
        updateShift()
        refreshStrip()
        when (c?.panel) {
            Panel.EMOJI -> openEmoji()
            Panel.CLIPBOARD -> openClipboard()
            Panel.THEMES -> openThemes()
            null -> Unit
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        logic.finish()
        root?.keyboard?.cancelTouches()
        root?.showPanel(null)
        EngineHolder.save(this)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        logic.finish()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        logic.onUpdateSelection(newSelStart, newSelEnd, candidatesStart)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // The framework may rebuild the input view inside super (it does when the
        // screen size changes); onStartInputView uses this to restore the state.
        carry = Carry(mode, shift, shownPanel())
        try {
            super.onConfigurationChanged(newConfig)
        } finally {
            carry = null
        }
        applyTheme()
        applySettings()
    }

    private fun shownPanel(): Panel? {
        val shown = root?.panel ?: return null
        return when {
            shown === emojiPanel -> Panel.EMOJI
            shown === clipboardPanel -> Panel.CLIPBOARD
            shown === themePanel -> Panel.THEMES
            else -> null
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null || key.startsWith("emoji_") || key.startsWith("clipboard_pinned")) return
        applySettings()
        when (key) {
            Prefs.THEME, Prefs.KEY_SHAPE, Prefs.KEY_BACKGROUNDS -> applyTheme()
            Prefs.LAYOUT, Prefs.NUMBER_ROW, Prefs.SYMBOL_HINTS, Prefs.SPLIT -> rebuildLayout()
        }
        refreshStrip()
    }

    // ------------------------------------------------------------ setup

    private fun applySettings() {
        feedback.configure(prefs.haptics, prefs.hapticStrength, prefs.sound, prefs.soundVolume)
        clipboard.enabled = prefs.clipboardHistory
        logic.settings.apply {
            autoCorrect = prefs.autoCorrect
            suggestions = prefs.suggestions
            doubleSpacePeriod = prefs.doubleSpacePeriod
            learn = prefs.learnWords
            emojiSuggestions = prefs.emojiSuggestions
            autoCaps = prefs.autoCaps
        }
        // Short screens (phones in landscape, flip phones' cover screens) get lower rows;
        // an unfolded foldable in landscape has room for full-height ones.
        val config = resources.configuration
        val short = if (config.screenHeightDp > 0) config.screenHeightDp < SHORT_SCREEN_DP
        else config.orientation == Configuration.ORIENTATION_LANDSCAPE
        root?.keyboard?.apply {
            previewsEnabled = prefs.keyPreviews
            gesturesEnabled = prefs.glideTyping && editor.gesturesAllowed && mode == KeyboardMode.TEXT
            trailEnabled = prefs.glideTrail
            cursorControlEnabled = prefs.spaceCursor
            deleteSwipeEnabled = prefs.deleteSwipe
            lightEffects = prefs.lightEffects
            longPressDelay = prefs.longPressDelay.toLong()
            heightScale = prefs.heightPercent / 100f
            rowHeightDp = if (short) 40f else 54f
        }
    }

    private fun applyTheme() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        theme = Themes.resolve(this, prefs.theme, night, blurAvailable)
        painter.shape = prefs.keyShape
        painter.keyBackgrounds = prefs.keyBackgrounds
        root?.applyTheme(theme)
        themePanel?.let {
            it.selectedId = prefs.theme
            it.shape = prefs.keyShape
        }
        setupWindow()
    }

    /** Draws the keyboard behind the navigation bar, matches bar icons to the theme, blurs for Glass. */
    private fun setupWindow() {
        val w = window?.window ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            // Deprecated on 35, where edge-to-edge is the default, but still needed on 30-34.
            @Suppress("DEPRECATION")
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.setSystemBarsAppearance(
                if (theme.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            val flags = w.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (theme.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        @Suppress("DEPRECATION")
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 31) {
            w.setBackgroundBlurRadius(if (theme.translucent && blurAvailable) dp(40) else 0)
        }
    }

    private fun rebuildLayout() {
        val options = LayoutOptions(
            letters = prefs.letterLayout,
            field = editor.fieldKind,
            numberRow = prefs.numberRow,
            symbolHints = prefs.symbolHints,
            emojiKey = editor.fieldKind == FieldKind.TEXT,
            split = splitActive(),
        )
        val l = Layouts.build(mode, options)
        layout = l
        geometry = null
        root?.keyboard?.let {
            it.setKeyboard(l)
            it.spaceLabel = if (mode == KeyboardMode.TEXT) "English" else null
            it.enterIcon = editor.enterIcon
            it.gesturesEnabled = prefs.glideTyping && editor.gesturesAllowed && mode == KeyboardMode.TEXT
        }
        updateShift()
    }

    /** Split for thumb typing on large screens: an unfolded foldable or a tablet. */
    private fun splitActive(): Boolean =
        prefs.splitKeyboard && resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP

    private fun setMode(m: KeyboardMode) {
        if (mode == m) return
        mode = m
        symbolTyped = false
        rebuildLayout()
    }

    // ------------------------------------------------------------ InputLogic.Host

    override val connection: InputConnection? get() = currentInputConnection
    override val engine: SuggestionEngine? get() = EngineHolder.engine

    override fun keyGeometry(): KeyGeometry? {
        val l = layout ?: return null
        if (l.mode != KeyboardMode.TEXT || l.width == 0) return null
        if (geometryLayout === l && geometryWidth == l.width) return geometry
        val centers = HashMap<Char, FloatArray>()
        for (k in l.keys) {
            if (k.isLetter || k.code == '\''.code) {
                centers[CharFolding.fold(k.code.toChar())] = floatArrayOf(k.bounds.centerX(), k.bounds.centerY())
            }
        }
        return KeyGeometry(l.keyWidth, l.rowHeight, centers).also {
            geometry = it
            geometryLayout = l
            geometryWidth = l.width
        }
    }

    override fun sendKey(keyCode: Int) = sendDownUpKeyEvents(keyCode)

    override fun sendRawText(text: String) {
        for (c in text) sendKeyChar(c)
    }

    override fun onInputChanged() {
        updateShift()
        refreshStrip()
    }

    // ------------------------------------------------------------ shift

    private fun updateShift() {
        if (!shiftHeld && (shift == Shift.OFF || shift == Shift.AUTO)) {
            shift = if (!autoCapsSuppressed && mode == KeyboardMode.TEXT && logic.autoCapitalize()) Shift.AUTO else Shift.OFF
        }
        root?.keyboard?.let {
            it.shifted = shift != Shift.OFF
            it.shiftIcon = when (shift) {
                Shift.OFF -> Icon.SHIFT
                Shift.ONE_SHOT, Shift.AUTO -> Icon.SHIFT_ON
                Shift.LOCKED -> Icon.CAPS_LOCK
            }
        }
    }

    private fun onShiftDown() {
        val now = SystemClock.uptimeMillis()
        val from = shift
        val doubleTap = now - lastShiftTap < DOUBLE_TAP_MS && lastShiftFrom != Shift.LOCKED
        shift = when {
            from == Shift.LOCKED -> Shift.OFF
            doubleTap -> Shift.LOCKED
            from == Shift.OFF -> Shift.ONE_SHOT
            else -> {
                if (from == Shift.AUTO) autoCapsSuppressed = true
                Shift.OFF
            }
        }
        lastShiftFrom = from
        lastShiftTap = now
        shiftHeld = true
        typedWhileShiftHeld = false
        updateShift()
    }

    private fun onShiftUp() {
        shiftHeld = false
        if (typedWhileShiftHeld && shift == Shift.ONE_SHOT) shift = Shift.OFF
        typedWhileShiftHeld = false
        updateShift()
    }

    /** A letter was typed: one-shot shift ends (unless shift is being held for chording). */
    private fun consumeShift() {
        if (shiftHeld) typedWhileShiftHeld = true
        else if (shift == Shift.ONE_SHOT || shift == Shift.AUTO) shift = Shift.OFF
    }

    private fun cased(word: String): String = when (shift) {
        Shift.LOCKED -> word.uppercase()
        Shift.ONE_SHOT, Shift.AUTO -> word.replaceFirstChar { it.uppercaseChar() }
        Shift.OFF -> word
    }

    // ------------------------------------------------------------ KeyboardView.Listener

    override fun onKeyDown(key: Key) {
        feedback.key(key.code)
        // Any other key between two shift taps means they were not a double tap.
        if (key.code == KeyCodes.SHIFT) onShiftDown() else lastShiftTap = NEVER
    }

    override fun onKeyTap(key: Key, x: Float, y: Float) {
        if (key.code != KeyCodes.SHIFT) autoCapsSuppressed = false
        when (key.code) {
            KeyCodes.SHIFT -> Unit
            KeyCodes.DELETE -> logic.onDelete(false)
            KeyCodes.SPACE -> {
                logic.onSpace()
                if (symbolTyped && (mode == KeyboardMode.SYMBOLS || mode == KeyboardMode.SYMBOLS_MORE) && editor.mode == KeyboardMode.TEXT) {
                    setMode(KeyboardMode.TEXT)
                }
            }
            KeyCodes.ENTER -> logic.onEnter()
            KeyCodes.SYMBOLS -> setMode(KeyboardMode.SYMBOLS)
            KeyCodes.SYMBOLS_MORE -> setMode(KeyboardMode.SYMBOLS_MORE)
            KeyCodes.ALPHABET -> setMode(editor.mode)
            KeyCodes.EMOJI -> openEmoji()
            KeyCodes.TEXT -> logic.onText(key.text ?: key.label)
            else -> typeCharacter(key.code, key.isLetter, x, y)
        }
    }

    private fun typeCharacter(code: Int, isLetter: Boolean, x: Float, y: Float) {
        val upper = isLetter && shift != Shift.OFF
        val text = String(Character.toChars(if (upper) Character.toUpperCase(code) else code))
        if (mode != KeyboardMode.TEXT && !Character.isDigit(code)) symbolTyped = true
        if (isLetter) consumeShift()
        logic.onCharacter(text, x, y)
    }

    override fun onKeyRelease(key: Key) {
        if (key.code == KeyCodes.SHIFT) onShiftUp()
    }

    override fun onKeyRepeat(key: Key) {
        if (key.code == KeyCodes.DELETE) logic.onDelete(true)
    }

    override fun onAlternative(key: Key, text: String) {
        autoCapsSuppressed = false
        val shown = if (key.isLetter && shift != Shift.OFF) text.uppercase() else text
        if (key.code == KeyCodes.TEXT) {
            logic.onText(shown)
        } else {
            if (key.isLetter) consumeShift()
            if (mode != KeyboardMode.TEXT) symbolTyped = true
            logic.onCharacter(shown, key.bounds.centerX(), key.bounds.centerY())
        }
    }

    override fun onLongPress(key: Key): Boolean = when (key.code) {
        KeyCodes.SPACE -> {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            true
        }
        KeyCodes.SHIFT -> {
            shift = Shift.LOCKED
            feedback.tick()
            updateShift()
            true
        }
        else -> false
    }

    override fun onPanelOpened() = feedback.tick()

    override fun onCursorSteps(steps: Int) {
        logic.onCursorSteps(steps)
        feedback.tick()
        updateShift()
    }

    override fun onDeleteSwipe(words: Int, done: Boolean) {
        logic.onDeleteSwipe(words, done)
        if (!done) feedback.tick()
    }

    override fun onGestureStart() {
        gestureActive = true
        root?.strip?.setGesturePreview("")
    }

    override fun onGestureMove(trail: GestureTrail) {
        val decoder = EngineHolder.decoder ?: return
        val g = keyGeometry() ?: return
        if (previewPending) return
        previewPending = true
        val n = trail.count
        val xs = trail.xs.copyOf(n)
        val ys = trail.ys.copyOf(n)
        gestureWorker.execute {
            val best = try { decoder.decode(xs, ys, n, g, 1).firstOrNull()?.text } catch (e: RuntimeException) { null }
            main.post {
                previewPending = false
                if (gestureActive && best != null) root?.strip?.setGesturePreview(cased(best))
            }
        }
    }

    override fun onGestureEnd(trail: GestureTrail) {
        gestureActive = false
        root?.strip?.setGesturePreview(null)
        val decoder = EngineHolder.decoder
        val g = keyGeometry()
        if (decoder == null || g == null) {
            refreshStrip()
            return
        }
        val words = decoder.decode(trail.xs, trail.ys, trail.count, g, 5).map { cased(it.text) }.distinct()
        if (words.isEmpty()) {
            refreshStrip()
            return
        }
        autoCapsSuppressed = false
        consumeShift()
        logic.onGesture(words)
    }

    // ------------------------------------------------------------ strip

    private fun refreshStrip() {
        val strip = root?.strip ?: return
        if (gestureActive) return
        val clip = if (logic.composing.isEmpty) clipboard.freshClip() else null
        strip.setClip(clip)
        if (!prefs.suggestions || !editor.suggestionsAllowed) {
            strip.clearSuggestions()
            return
        }
        when {
            logic.composingFromGesture -> {
                val current = logic.composing.toString()
                val others = logic.gestureAlternatives.filter { it != current }
                strip.setSuggestions(
                    others.getOrNull(0)?.let { SuggestionStripView.Item(it) },
                    SuggestionStripView.Item(current, primary = true),
                    others.getOrNull(1)?.let { SuggestionStripView.Item(it) },
                )
            }
            logic.composing.isNotEmpty -> {
                val r = logic.result
                val typed = logic.composing.toString()
                val auto = r.autoCorrection
                val others = r.candidates.filter { it.text != auto && it.text != typed }
                val emoji = r.emoji.firstOrNull()?.let { SuggestionStripView.Item(it, emoji = true) }
                if (auto != null) {
                    strip.setSuggestions(
                        SuggestionStripView.Item(typed, quoted = true),
                        SuggestionStripView.Item(auto, primary = true),
                        emoji ?: others.getOrNull(0)?.let { SuggestionStripView.Item(it.text) },
                    )
                } else {
                    strip.setSuggestions(
                        others.getOrNull(0)?.let { SuggestionStripView.Item(it.text) },
                        SuggestionStripView.Item(typed, primary = r.typedIsWord),
                        emoji ?: others.getOrNull(1)?.let { SuggestionStripView.Item(it.text) },
                    )
                }
            }
            clip != null -> strip.clearSuggestions()
            else -> {
                val p = logic.predictions.map { cased(it.text) }
                if (p.isEmpty()) strip.clearSuggestions() else strip.setSuggestions(
                    p.getOrNull(1)?.let { SuggestionStripView.Item(it) },
                    SuggestionStripView.Item(p[0], primary = true),
                    p.getOrNull(2)?.let { SuggestionStripView.Item(it) },
                )
            }
        }
    }

    override fun onSuggestion(index: Int, item: SuggestionStripView.Item) {
        feedback.tick()
        autoCapsSuppressed = false
        logic.onPick(item.text, item.emoji)
    }

    override fun onSuggestionLongPress(index: Int, item: SuggestionStripView.Item) {
        val e = engine ?: return
        if (!item.emoji && e.history.count(item.text) > 0) {
            e.forget(item.text)
            feedback.tick()
            root?.strip?.flash("Forgot “${item.text}”")
            logic.updateSuggestions()
        }
    }

    override fun onAction(action: SuggestionStripView.Action) {
        feedback.tick()
        when (action) {
            SuggestionStripView.Action.TOGGLE -> Unit
            SuggestionStripView.Action.CLIPBOARD -> openClipboard()
            SuggestionStripView.Action.EMOJI -> openEmoji()
            SuggestionStripView.Action.THEMES -> openThemes()
            SuggestionStripView.Action.SETTINGS -> openSettings()
            SuggestionStripView.Action.HIDE -> requestHideSelf(0)
        }
    }

    override fun onPasteChip(text: String) {
        feedback.tick()
        clipboard.dismissChip()
        logic.onText(text)
    }

    // ------------------------------------------------------------ panels

    private fun openEmoji() {
        val r = root ?: return
        val panel = emojiPanel ?: EmojiPanelView(this, painter).also {
            it.host = this
            it.onEmoji = { e -> logic.onText(e) }
            emojiPanel = it
        }
        panel.data = emojiData
        r.showPanel(panel)
    }

    private fun openClipboard() {
        val r = root ?: return
        val panel = clipboardPanel ?: ClipboardPanelView(this, painter, clipboard).also {
            it.host = this
            it.onPaste = { text ->
                clipboard.dismissChip()
                logic.onText(text)
            }
            clipboardPanel = it
        }
        panel.refresh()
        r.showPanel(panel)
    }

    private fun openThemes() {
        val r = root ?: return
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val panel = themePanel ?: ThemePanelView(this, painter) { id -> Themes.resolve(this, id, night, blurAvailable) }.also {
            it.host = this
            it.onTheme = { id -> prefs.theme = id }
            it.onShape = { s -> prefs.keyShape = s }
            themePanel = it
        }
        panel.selectedId = prefs.theme
        panel.shape = prefs.keyShape
        r.showPanel(panel)
    }

    private fun openSettings() {
        requestHideSelf(0)
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }

    override fun onPanelClose() {
        root?.showPanel(null)
        logic.refreshPredictions(currentInputConnection)
        updateShift()
        refreshStrip()
    }

    override fun onPanelKey(code: Int) {
        when (code) {
            KeyCodes.DELETE -> logic.onDelete(false)
            KeyCodes.SPACE -> logic.onSpace()
        }
    }

    override fun onPanelFeedback() = feedback.key(0)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Back closes an open panel before it hides the keyboard.
        if (keyCode == KeyEvent.KEYCODE_BACK && root?.panel != null && isInputViewShown) {
            onPanelClose()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val DOUBLE_TAP_MS = 320L
        const val NEVER = Long.MIN_VALUE / 2
        /** Smallest width of large screens (Android's tablet breakpoint). */
        const val LARGE_SCREEN_DP = 600
        /** Screens shorter than this get lower key rows. */
        const val SHORT_SCREEN_DP = 480
    }
}
