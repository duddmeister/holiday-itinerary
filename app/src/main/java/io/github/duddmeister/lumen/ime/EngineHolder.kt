package io.github.duddmeister.lumen.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.duddmeister.lumen.engine.GestureDecoder
import io.github.duddmeister.lumen.engine.Lexicon
import io.github.duddmeister.lumen.engine.NextWordModel
import io.github.duddmeister.lumen.engine.Shortcut
import io.github.duddmeister.lumen.engine.Spelling
import io.github.duddmeister.lumen.engine.SuggestionEngine
import io.github.duddmeister.lumen.engine.UserHistory
import java.io.File
import java.io.StringWriter
import java.util.concurrent.Executors

/**
 * Loads the prediction engine once per process, off the main thread, and
 * persists what it learns. Everything stays in the app's private storage.
 */
object EngineHolder {
    private const val TAG = "Lumen"
    private const val HISTORY_FILE = "user_history.tsv"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-engine").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayList<() -> Unit>()
    private var loading = false

    @Volatile var engine: SuggestionEngine? = null
        private set
    @Volatile var decoder: GestureDecoder? = null
        private set

    /** How [engine] spells; while another spelling loads, still the old one. */
    var spelling: Spelling? = null
        private set
    private var wanted: Spelling? = null

    /**
     * Makes sure the engine spells in [spelling], loading it if needed; [onReady] runs
     * on the main thread once it does. A new spelling keeps the words learned so far.
     */
    fun load(context: Context, spelling: Spelling, onReady: () -> Unit) {
        if (engine != null && this.spelling == spelling && !loading) { onReady(); return }
        waiting.add(onReady)
        wanted = spelling
        if (!loading) start(context.applicationContext)
    }

    private fun start(app: Context) {
        val spelling = wanted ?: return
        loading = true
        // A running engine hands on its learned words; otherwise they come from storage.
        val readHistory = engine == null
        io.execute {
            try {
                val started = System.currentTimeMillis()
                val assets = app.assets
                val lexicon = assets.open("dict/${spelling.locale}.txt").bufferedReader().use { Lexicon.read(it) }
                val shortcuts = assets.open("dict/${spelling.locale}_shortcuts.txt").bufferedReader().use { SuggestionEngine.readShortcuts(it) }
                val predictions = assets.open("dict/${spelling.locale}_predictions.txt").bufferedReader().use { NextWordModel.read(it) }
                val emoji = assets.open("emoji/keywords.txt").bufferedReader().use { SuggestionEngine.readEmojiKeywords(it) }
                val file = File(app.filesDir, HISTORY_FILE)
                val stored = if (readHistory && file.isFile) {
                    try { file.bufferedReader().use { UserHistory.read(it) } } catch (e: Exception) { UserHistory() }
                } else UserHistory()
                Log.i(TAG, "Dictionary ready: ${lexicon.wordCount} words (${spelling.locale}) in ${System.currentTimeMillis() - started} ms")
                main.post { publish(app, spelling, lexicon, shortcuts, predictions, emoji, stored) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the dictionary", e)
                main.post { loading = false }
            }
        }
    }

    private fun publish(
        app: Context, spelling: Spelling, lexicon: Lexicon, shortcuts: Map<String, Shortcut>,
        predictions: NextWordModel, emoji: Map<String, List<String>>, stored: UserHistory,
    ) {
        loading = false
        // Another spelling was chosen while this one loaded.
        if (wanted != spelling) { start(app); return }
        val history = engine?.history ?: stored
        val built = SuggestionEngine(lexicon, shortcuts, predictions, history, emoji)
        val gestures = GestureDecoder(lexicon, history)
        // Snapshot learned words here, on the thread that owns the history.
        val learned = gestures.learnedWords()
        engine = built
        decoder = null
        this.spelling = spelling
        waiting.forEach { it() }
        waiting.clear()
        io.execute {
            gestures.buildIndex(learned)
            main.post { if (engine === built) decoder = gestures }
        }
    }

    /** Saves learned words if anything changed. Serialises on the caller's (main) thread, writes in the background. */
    fun save(context: Context) {
        val history = engine?.history ?: return
        if (!history.dirty) return
        val text = StringWriter().also { history.write(it) }.toString()
        val dir = context.applicationContext.filesDir
        io.execute {
            try {
                val tmp = File(dir, "$HISTORY_FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(File(dir, HISTORY_FILE))) Log.w(TAG, "Could not replace learned words")
            } catch (e: Exception) {
                Log.w(TAG, "Could not save learned words", e)
            }
        }
    }

    /** Forgets everything learned (settings > Clear learned words). */
    fun clearLearned(context: Context) {
        engine?.history?.clear()
        val dir = context.applicationContext.filesDir
        io.execute { File(dir, HISTORY_FILE).delete() }
    }
}
