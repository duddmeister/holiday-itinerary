package io.github.duddmeister.lumen.ime

import android.app.SearchManager
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipDescription
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import io.github.duddmeister.lumen.R
import io.github.duddmeister.lumen.data.ClipboardHistory
import io.github.duddmeister.lumen.data.EmojiData
import io.github.duddmeister.lumen.data.Gif
import io.github.duddmeister.lumen.data.GifApi
import io.github.duddmeister.lumen.data.GifFiles
import io.github.duddmeister.lumen.data.GifImages
import io.github.duddmeister.lumen.data.GifPage
import io.github.duddmeister.lumen.data.HttpStatusException
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
import io.github.duddmeister.lumen.ui.GifPanelView
import io.github.duddmeister.lumen.ui.InputRootView
import io.github.duddmeister.lumen.update.UpdateAlerts
import io.github.duddmeister.lumen.ui.PanelView
import io.github.duddmeister.lumen.ui.SearchBarView
import io.github.duddmeister.lumen.ui.SuggestionStripView
import io.github.duddmeister.lumen.ui.ThemePanelView
import io.github.duddmeister.lumen.ui.VoicePanelView
import io.github.duddmeister.lumen.ui.dp
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.math.hypot

/** Lumen's input method service: wires the views, the input logic and the settings together. */
class LumenIME : InputMethodService(), KeyboardView.Listener, SuggestionStripView.Listener, PanelView.Host,
    SearchBarView.Listener, InputLogic.Host, SharedPreferences.OnSharedPreferenceChangeListener, VoiceTyping.Listener,
    InputRootView.Listener {

    private enum class Shift { OFF, ONE_SHOT, AUTO, LOCKED }
    private enum class Panel { EMOJI, GIF, CLIPBOARD, THEMES }

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
    private var gifPanel: GifPanelView? = null
    private var voicePanel: VoicePanelView? = null
    private var voice: VoiceTyping? = null
    private var emojiData: EmojiData? = null
    private val gifImages by lazy { GifImages() }
    private val gifWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-gifs").apply { isDaemon = true } }
    /** Counts the text fields typed into, so a GIF still downloading when the user moves on is dropped. */
    private var fieldCount = 0

    /** Set while the keys type into the search bar instead of the app. */
    private var search: SearchBarView.Kind? = null
    private val searchQuery = StringBuilder()

    private var editor = EditorContext(null)
    private var mode = KeyboardMode.TEXT
    private var layout: KeyboardLayout? = null
    private var geometry: KeyGeometry? = null
    private var geometryLayout: KeyboardLayout? = null
    private var geometryWidth = 0
    private var theme: KeyboardTheme = Themes.aurora
    private var blurAvailable = false
    private var blurListener: Consumer<Boolean>? = null
    private val location = IntArray(2)

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
        // Touches that start on the keys reach them before Android's buttons below (see KeysFirst).
        window?.window?.let { w -> w.callback?.let { w.callback = KeysFirst(it) { root } } }
        prefs = Prefs(this)
        feedback = Feedback(this)
        clipboard = ClipboardHistory(this, prefs.store)
        clipboard.onChange = { main.post { refreshStrip() } }
        clipboard.start()
        prefs.store.registerOnSharedPreferenceChangeListener(this)
        applySettings()
        loadDictionary()
        UpdateAlerts.sync(this)
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
        voice?.cancel()
        EngineHolder.save(this)
        clipboard.stop()
        prefs.store.unregisterOnSharedPreferenceChangeListener(this)
        if (Build.VERSION.SDK_INT >= 31) {
            blurListener?.let { getSystemService(WindowManager::class.java)?.removeCrossWindowBlurEnabledListener(it) }
        }
        gestureWorker.shutdown()
        gifWorker.shutdownNow()
        gifImages.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val r = InputRootView(this, painter)
        r.keyboard.listener = this
        r.strip.listener = this
        r.searchBar.listener = this
        r.listener = this
        root = r
        emojiPanel = null
        clipboardPanel = null
        themePanel = null
        gifPanel = null
        voice?.cancel()
        voicePanel = null
        applyTheme()
        applySettings()
        layout?.let { r.keyboard.setKeyboard(it) }
        return r
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) fieldCount++
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
        // Back to the keys before the window shows, without a fade.
        search = null
        searchQuery.setLength(0)
        root?.showSearch(null, animate = false)
        root?.showPanel(null, animate = false)
        applyTheme()
        applySettings()
        rebuildLayout()
        root?.keyboard?.enterIcon = enterIcon()
        logic.refreshPredictions(currentInputConnection)
        updateShift()
        refreshStrip()
        when (c?.panel) {
            Panel.EMOJI -> openEmoji()
            Panel.GIF -> openGif(gifPanel?.query)
            Panel.CLIPBOARD -> openClipboard()
            Panel.THEMES -> openThemes()
            null -> Unit
        }
        // Back from allowing the microphone: carry on into voice typing.
        if (VoiceTyping.justGranted() && prefs.voiceTyping) startVoice()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        voice?.cancel()
        logic.finish()
        search = null
        root?.keyboard?.cancelTouches()
        // Leave an open panel or search in place: switching to the keys now would
        // flash them while the keyboard slides away. onStartInputView returns to the
        // keys before the keyboard shows again.
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
            shown === gifPanel -> Panel.GIF
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
            Prefs.FLOATING -> {
                applyTheme()
                rebuildLayout()
            }
            Prefs.SPELLING -> {
                loadDictionary()
                rebuildLayout()
            }
            Prefs.GIFS, Prefs.GIF_SERVICE, Prefs.GIPHY_KEY, Prefs.KLIPY_KEY -> {
                val panel = gifPanel
                if (panel != null && root?.panel === panel && isInputViewShown) openGif(panel.query)
            }
        }
        refreshStrip()
    }

    // ------------------------------------------------------------ setup

    /** Loads (or switches to) the dictionary for the chosen spelling. */
    private fun loadDictionary() {
        EngineHolder.load(this, prefs.spelling) {
            logic.updateSuggestions()
            logic.refreshPredictions(currentInputConnection)
            refreshStrip()
        }
    }

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
        root?.strip?.gifsEnabled = prefs.gifs
        root?.strip?.voiceEnabled = prefs.voiceTyping
        // While searching, the keys only type into the search bar.
        val typingIntoApp = search == null
        root?.keyboard?.apply {
            previewsEnabled = prefs.keyPreviews
            gesturesEnabled = prefs.glideTyping && editor.gesturesAllowed && mode == KeyboardMode.TEXT && typingIntoApp
            trailEnabled = prefs.glideTrail
            spaceSwipesEnabled = prefs.spaceCursor && typingIntoApp
            deleteSwipeEnabled = prefs.deleteSwipe && typingIntoApp
            flickEnabled = prefs.flickSymbols
            lightEffects = prefs.lightEffects
            longPressDelay = prefs.longPressDelay.toLong()
            heightScale = prefs.heightPercent / 100f
            rowHeightDp = if (short) 40f else 54f
        }
        applyKeyboardMode()
    }

    /** One-handed or floating, as set and as this screen allows; with the toolbar button to match. */
    private fun applyKeyboardMode() {
        val r = root ?: return
        r.floating = floatingActive()
        r.floatX = prefs.floatX
        r.floatY = prefs.floatY
        r.oneHanded = when {
            !oneHandedOffered() || !prefs.oneHanded -> null
            prefs.oneHandedLeft -> InputRootView.Side.LEFT
            else -> InputRootView.Side.RIGHT
        }
        // The way back is beside the keys (one-handed) or under them (floating).
        r.strip.modeIcon = when {
            largeScreen() -> if (r.floating) null else Icon.FLOAT
            oneHandedOffered() -> if (r.oneHanded != null) null else Icon.ONE_HANDED
            else -> null
        }
    }

    private fun largeScreen() = resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP

    /** Floating is for large screens: an unfolded foldable or a tablet. */
    private fun floatingActive() = prefs.floating && largeScreen()

    /** One-handed is for a phone held upright; held sideways, both thumbs reach. */
    private fun oneHandedOffered() =
        !largeScreen() && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    /** Glass blurs the whole keyboard window; floating, that window covers the app too. */
    private fun blurUsable() = blurAvailable && !floatingActive()

    private fun applyTheme() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        theme = Themes.resolve(this, prefs.theme, night, blurUsable())
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
            w.setBackgroundBlurRadius(if (theme.translucent && blurUsable()) dp(40) else 0)
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
            it.spaceLabel = if (mode == KeyboardMode.TEXT) prefs.spelling.language else null
            it.enterIcon = enterIcon()
            it.gesturesEnabled = prefs.glideTyping && editor.gesturesAllowed && mode == KeyboardMode.TEXT && search == null
        }
        updateShift()
    }

    /** Split for thumb typing on large screens: an unfolded foldable or a tablet (unless floating). */
    private fun splitActive(): Boolean = prefs.splitKeyboard && largeScreen() && !floatingActive()

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
        val centers = HashMap<Char, ArrayList<FloatArray>>()
        for (k in l.keys) {
            if (k.isLetter || k.code == '\''.code) {
                centers.getOrPut(CharFolding.fold(k.code.toChar())) { ArrayList(1) }.add(floatArrayOf(k.bounds.centerX(), k.bounds.centerY()))
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
            val autoCaps = search == null && !autoCapsSuppressed && mode == KeyboardMode.TEXT && logic.autoCapitalize()
            shift = if (autoCaps) Shift.AUTO else Shift.OFF
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
        if (search != null) {
            onSearchKey(key)
            return
        }
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
        if (key.code != KeyCodes.DELETE) return
        if (search != null) deleteSearchChar() else logic.onDelete(true)
    }

    override fun onAlternative(key: Key, text: String) {
        autoCapsSuppressed = false
        val shown = if (key.isLetter && shift != Shift.OFF) text.uppercase() else text
        if (search != null) {
            if (key.isLetter) consumeShift()
            appendSearch(shown)
            return
        }
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

    /**
     * A swipe along the space bar. Right finishes the word being typed with the
     * suggestion; otherwise the cursor jumps a word that way.
     */
    override fun onSpaceSwipe(direction: Int) {
        if (search != null) return
        feedback.tick()
        when {
            direction > 0 && logic.typingWord -> {
                autoCapsSuppressed = false
                // A glided word is already the best match: just finish it.
                if (logic.composingFromGesture) logic.onSpace() else logic.onPick(bestSuggestion(), emoji = false)
            }
            else -> logic.onCursorWords(direction)
        }
        updateShift()
    }

    /** The strip's highlighted suggestion for the word being typed: its correction, else the typed word if it's a word, else the best other suggestion. */
    private fun bestSuggestion(): String {
        val r = logic.result
        val typed = logic.composing.toString()
        return r.autoCorrection
            ?: typed.takeIf { r.typedIsWord }
            ?: r.candidates.firstOrNull()?.text
            ?: typed
    }

    override fun onDeleteSwipe(words: Int, done: Boolean) {
        if (search != null) return
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
        if (search != null) return
        val decoder = EngineHolder.decoder
        val g = keyGeometry()
        val words = if (decoder == null || g == null) emptyList() else {
            decoder.decode(trail.xs, trail.ys, trail.count, g, 5).map { cased(it.text) }.distinct()
        }
        if (words.isEmpty()) {
            if (!tapInstead(trail)) refreshStrip()
            return
        }
        autoCapsSuppressed = false
        consumeShift()
        logic.onGesture(words)
    }

    /**
     * A glide too short to spell anything was a tap that slid (a thumb rolling as it lifts):
     * types the key it started on, rather than losing the letter.
     */
    private fun tapInstead(trail: GestureTrail): Boolean {
        val l = layout ?: return false
        if (trail.count == 0 || l.keyWidth <= 0f) return false
        val x = trail.xs[0]
        val y = trail.ys[0]
        val key = l.keyAt(x, y)?.takeIf { it.isLetter } ?: return false
        var length = 0f
        for (i in 1 until trail.count) length += hypot(trail.xs[i] - trail.xs[i - 1], trail.ys[i] - trail.ys[i - 1])
        if (length > l.keyWidth * TAP_SLIDE_KEYS) return false
        onKeyTap(key, x, y)
        return true
    }

    // ------------------------------------------------------------ strip

    private fun refreshStrip() {
        val strip = root?.strip ?: return
        if (gestureActive || search != null) return
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
            SuggestionStripView.Action.SEARCH -> startSearch(SearchBarView.Kind.WEB, selectedText())
            SuggestionStripView.Action.CLIPBOARD -> openClipboard()
            SuggestionStripView.Action.EMOJI -> openEmoji()
            SuggestionStripView.Action.GIF -> openGif()
            SuggestionStripView.Action.THEMES -> openThemes()
            SuggestionStripView.Action.SETTINGS -> openSettings()
            SuggestionStripView.Action.VOICE -> startVoice()
            SuggestionStripView.Action.MODE -> if (largeScreen()) {
                prefs.set(Prefs.FLOATING, !prefs.floating)
            } else {
                prefs.set(Prefs.ONE_HANDED, !prefs.oneHanded)
            }
            SuggestionStripView.Action.HIDE -> requestHideSelf(0)
        }
    }

    override fun onPasteChip(text: String) {
        feedback.tick()
        clipboard.dismissChip()
        logic.onText(text)
    }

    // ------------------------------------------------------------ one-handed and floating

    override fun onOneHanded(side: InputRootView.Side?) {
        feedback.tick()
        val edit = prefs.store.edit().putBoolean(Prefs.ONE_HANDED, side != null)
        if (side != null) edit.putBoolean(Prefs.ONE_HANDED_LEFT, side == InputRootView.Side.LEFT)
        edit.apply()
    }

    override fun onFloatMoved(x: Float, y: Float) {
        prefs.store.edit().putFloat(Prefs.FLOAT_X, x).putFloat(Prefs.FLOAT_Y, y).apply()
    }

    override fun onDock() {
        feedback.tick()
        prefs.set(Prefs.FLOATING, false)
    }

    /**
     * Floating, the keyboard window covers the screen so the card can go anywhere, but the
     * app isn't resized or pushed up for it (Android may still keep room for its own buttons
     * along the bottom), and touches outside the card go straight through to the app.
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val r = root?.takeIf { it.floating } ?: return
        val decor = window?.window?.decorView ?: return
        outInsets.contentTopInsets = decor.height
        outInsets.visibleTopInsets = decor.height
        r.getLocationInWindow(location)
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(
            r.card.left + location[0], r.card.top + location[1], r.card.right + location[0], r.card.bottom + location[1],
        )
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
        val panel = themePanel ?: ThemePanelView(this, painter) { id -> Themes.resolve(this, id, night, blurUsable()) }.also {
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

    // ------------------------------------------------------------ voice typing

    private fun startVoice() {
        val r = root ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // A keyboard can't ask; this screen does, and voice typing starts when the keyboard is back.
            VoicePermissionActivity.start(this)
            return
        }
        val typing = voice ?: VoiceTyping(this, this).also { voice = it }
        if (!typing.available) {
            r.strip.flash(getString(R.string.voice_unavailable))
            return
        }
        val panel = voicePanel ?: VoicePanelView(this, painter).also {
            it.host = this
            it.onMic = { if (typing.listening) typing.stop() else listen() }
            voicePanel = it
        }
        panel.language = prefs.spelling.language
        r.showPanel(panel)
        listen()
    }

    private fun listen() {
        voice?.start(prefs.spelling.locale.replace('_', '-'))
    }

    override fun onVoiceState(state: VoiceTyping.State) {
        val panel = voicePanel ?: return
        panel.listening = state is VoiceTyping.State.Starting || state is VoiceTyping.State.Listening
        panel.level = (state as? VoiceTyping.State.Listening)?.level ?: 0f
        val heard = when (state) {
            is VoiceTyping.State.Listening -> state.heard
            is VoiceTyping.State.Working -> state.heard
            else -> ""
        }
        panel.heard = heard.isNotEmpty()
        panel.text = heard.ifEmpty {
            getString(
                when (state) {
                    VoiceTyping.State.Starting, is VoiceTyping.State.Listening -> R.string.voice_speak
                    is VoiceTyping.State.Working -> R.string.voice_working
                    else -> R.string.voice_tap
                },
            )
        }
        if (state is VoiceTyping.State.Failed) {
            panel.heard = false
            panel.text = state.message
        }
    }

    override fun onVoiceResult(text: String) {
        logic.onVoice(text)
        // Said and written: back to the keys.
        if (root?.panel === voicePanel) onPanelClose()
    }

    override fun onPanelClose() {
        voice?.cancel()
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

    // ------------------------------------------------------------ search

    private fun enterIcon(): Icon = when (search) {
        null -> editor.enterIcon
        SearchBarView.Kind.EMOJI -> Icon.DONE
        else -> Icon.SEARCH
    }

    private fun selectedText(): String =
        currentInputConnection?.getSelectedText(0)?.toString()?.replace('\n', ' ')?.trim()?.take(MAX_QUERY).orEmpty()

    /** Opens the search bar; the keys type into it until the search ends. */
    private fun startSearch(kind: SearchBarView.Kind, initial: String = "") {
        val r = root ?: return
        logic.finish() // commit the word being typed in the app first
        search = kind
        searchQuery.setLength(0)
        searchQuery.append(initial)
        r.showSearch(kind, initial, if (kind == SearchBarView.Kind.GIF) "Search ${prefs.gifService.title}" else null)
        shift = Shift.OFF
        if (mode != KeyboardMode.TEXT) setMode(KeyboardMode.TEXT) else rebuildLayout()
        applySettings()
        updateShift()
        updateSearchResults()
    }

    /** Leaves the search bar for the keys. */
    private fun endSearch() {
        if (search == null) return
        search = null
        searchQuery.setLength(0)
        root?.showSearch(null)
        root?.keyboard?.enterIcon = enterIcon()
        applySettings()
        updateShift()
        logic.refreshPredictions(currentInputConnection)
        refreshStrip()
    }

    private fun onSearchKey(key: Key) {
        when (key.code) {
            KeyCodes.SHIFT -> Unit
            KeyCodes.DELETE -> deleteSearchChar()
            KeyCodes.SPACE -> {
                appendSearch(" ")
                if (symbolTyped && mode != KeyboardMode.TEXT) setMode(KeyboardMode.TEXT)
            }
            KeyCodes.ENTER -> submitSearch()
            KeyCodes.SYMBOLS -> setMode(KeyboardMode.SYMBOLS)
            KeyCodes.SYMBOLS_MORE -> setMode(KeyboardMode.SYMBOLS_MORE)
            KeyCodes.ALPHABET -> setMode(KeyboardMode.TEXT)
            KeyCodes.EMOJI -> {
                endSearch()
                openEmoji()
            }
            KeyCodes.TEXT -> appendSearch(key.text ?: key.label)
            else -> {
                val upper = key.isLetter && shift != Shift.OFF
                if (mode != KeyboardMode.TEXT && !Character.isDigit(key.code)) symbolTyped = true
                if (key.isLetter) consumeShift()
                appendSearch(String(Character.toChars(if (upper) Character.toUpperCase(key.code) else key.code)))
            }
        }
    }

    private fun appendSearch(text: String) {
        if (searchQuery.length + text.length > MAX_QUERY) return
        searchQuery.append(text)
        searchChanged()
    }

    private fun deleteSearchChar() {
        if (searchQuery.isEmpty()) return
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(searchQuery.toString())
        val start = breaks.preceding(searchQuery.length).coerceAtLeast(0)
        searchQuery.delete(start, searchQuery.length)
        searchChanged()
    }

    private fun searchChanged() {
        root?.searchBar?.setQuery(searchQuery.toString())
        updateSearchResults()
        updateShift()
    }

    private fun updateSearchResults() {
        if (search == SearchBarView.Kind.EMOJI) root?.searchBar?.setResults(emojiData?.search(searchQuery) ?: emptyList())
    }

    private fun submitSearch() {
        val query = searchQuery.toString().trim()
        when (search) {
            SearchBarView.Kind.EMOJI -> {
                endSearch()
                openEmoji()
            }
            SearchBarView.Kind.GIF -> {
                endSearch()
                openGif(query)
            }
            SearchBarView.Kind.WEB -> if (query.isNotEmpty()) {
                endSearch()
                webSearch(query)
            }
            null -> Unit
        }
    }

    /** Searches the web in the user's browser or search app, then gets out of the way. */
    private fun webSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val page = Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
            try {
                startActivity(Intent(Intent.ACTION_VIEW, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: ActivityNotFoundException) {
                root?.strip?.flash("No app can search the web")
                return
            }
        }
        requestHideSelf(0)
    }

    override fun onSearchBack() {
        val kind = search
        endSearch()
        when (kind) {
            SearchBarView.Kind.EMOJI -> openEmoji()
            SearchBarView.Kind.GIF -> openGif(gifPanel?.query)
            else -> Unit
        }
    }

    override fun onSearchClear() {
        searchQuery.setLength(0)
        searchChanged()
    }

    override fun onSearchEmoji(emoji: String) {
        feedback.key(0)
        logic.onText(emoji)
        emojiData?.recordUse(emoji)
    }

    override fun onPanelSearch() {
        val shown = root?.panel ?: return
        when {
            shown === emojiPanel -> startSearch(SearchBarView.Kind.EMOJI)
            shown === gifPanel -> startSearch(SearchBarView.Kind.GIF, gifPanel?.query.orEmpty())
        }
    }

    // ------------------------------------------------------------ GIFs

    private val gifSource = object : GifPanelView.Source {
        /** Recent pages, so the panel reopens instantly and spares the key's hourly allowance. */
        private val pages = LruCache<String, Pair<Long, GifPage>>(PAGES_KEPT)

        override val unavailable: String?
            get() = when {
                !prefs.gifs -> "GIFs are turned off. Turn them on in Lumen's settings."
                prefs.gifKey.isEmpty() -> "GIF search needs a free ${prefs.gifService.title} API key. Add one in Lumen's settings, under GIFs."
                else -> null
            }

        override val attribution: String
            get() = "Powered by ${prefs.gifService.title}"

        override fun load(query: String?, pos: String?, done: (GifPage?, String?) -> Unit) {
            val service = prefs.gifService
            val key = prefs.gifKey
            val cacheKey = listOf(service.id, key, query, pos).joinToString("\n")
            val cached = pages.get(cacheKey)
            if (cached != null && SystemClock.elapsedRealtime() - cached.first < PAGE_FRESH_MS) {
                done(cached.second, null)
                return
            }
            val api = GifApi(service, key)
            val name = service.title
            gifWorker.execute {
                val (page, error) = try {
                    (if (query == null) api.featured(pos) else api.search(query, pos)) to null
                } catch (e: HttpStatusException) {
                    null to when (e.code) {
                        400, 401, 403 -> "$name didn't accept the API key. Check it in Lumen's settings."
                        429 -> "$name limits how many GIFs a key can load each hour. Try again a little later."
                        else -> "$name is having trouble. Tap to try again."
                    }
                } catch (e: IOException) {
                    null to "Couldn't reach $name. Check your connection, then tap to try again."
                } catch (e: org.json.JSONException) {
                    null to "$name sent something unexpected. Tap to try again."
                }
                main.post {
                    if (page != null) pages.put(cacheKey, SystemClock.elapsedRealtime() to page)
                    done(page, error)
                }
            }
        }

        override fun preview(gif: Gif, ready: () -> Unit): Drawable? = gifImages.get(gif.previewUrl, ready)
    }

    private fun openGif(query: String? = null) {
        val r = root ?: return
        val panel = gifPanel ?: GifPanelView(this, painter, gifSource).also {
            it.host = this
            it.onGif = { gif -> sendGif(gif) }
            gifPanel = it
        }
        panel.show(query)
        r.showPanel(panel)
    }

    /**
     * Sends [gif] as an image where the app accepts GIFs (through [GifFiles] and
     * its provider), or as a link where it does not.
     */
    private fun sendGif(gif: Gif) {
        feedback.tick()
        val accepts = currentInputEditorInfo?.contentMimeTypes
            ?.any { ClipDescription.compareMimeTypes("image/gif", it) } == true
        if (!accepts) {
            logic.onText(gif.pageUrl)
            return
        }
        val panel = gifPanel
        val field = fieldCount
        panel?.setSending(gif)
        gifWorker.execute {
            val uri = try { GifFiles.download(this, gif) } catch (e: IOException) { null }
            main.post {
                panel?.setSending(null)
                // Not into another field the user moved to while it downloaded.
                if (field != fieldCount) return@post
                val ic = currentInputConnection ?: return@post
                if (uri == null) {
                    panel?.notice("Couldn't download that GIF")
                    return@post
                }
                val description = ClipDescription(gif.description.ifEmpty { "GIF" }, arrayOf("image/gif"))
                val content = InputContentInfo(uri, description, Uri.parse(gif.pageUrl))
                val sent = ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                if (!sent) logic.onText(gif.pageUrl)
            }
        }
    }

    private companion object {
        const val DOUBLE_TAP_MS = 320L
        /** A "glide" no longer than this many key widths that spells nothing was a tap. */
        const val TAP_SLIDE_KEYS = 1f
        const val NEVER = Long.MIN_VALUE / 2
        /** Smallest width of large screens (Android's tablet breakpoint). */
        const val LARGE_SCREEN_DP = 600
        /** Screens shorter than this get lower key rows. */
        const val SHORT_SCREEN_DP = 480
        const val MAX_QUERY = 120
        const val PAGES_KEPT = 12
        /** How long a page of GIFs is shown again without asking the service. */
        const val PAGE_FRESH_MS = 10 * 60_000L
    }
}
