package io.github.duddmeister.lumen.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.duddmeister.lumen.engine.Lexicon
import io.github.duddmeister.lumen.engine.NextWordModel
import io.github.duddmeister.lumen.engine.Shortcut
import io.github.duddmeister.lumen.engine.Spelling
import io.github.duddmeister.lumen.engine.SuggestionEngine
import io.github.duddmeister.lumen.engine.UserHistory
import io.github.duddmeister.lumen.engine.WordPairs
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
    /** Words restored from a backup while the engine was loading, for it to take on. */
    private val restored = ArrayList<UserHistory>()

    @Volatile var engine: SuggestionEngine? = null
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
                val pairs = assets.open("dict/${spelling.locale}_bigrams.txt").bufferedReader().use { WordPairs.read(it) }
                val stored = if (readHistory) readStored(app) else UserHistory()
                Log.i(TAG, "Dictionary ready: ${lexicon.wordCount} words (${spelling.locale}) in ${System.currentTimeMillis() - started} ms")
                main.post { publish(app, spelling, lexicon, shortcuts, predictions, emoji, pairs, stored) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the dictionary", e)
                main.post { loading = false }
            }
        }
    }

    private fun publish(
        app: Context, spelling: Spelling, lexicon: Lexicon, shortcuts: Map<String, Shortcut>,
        predictions: NextWordModel, emoji: Map<String, List<String>>, pairs: WordPairs, stored: UserHistory,
    ) {
        loading = false
        // Another spelling was chosen while this one loaded.
        if (wanted != spelling) { start(app); return }
        val history = engine?.history ?: stored
        restored.forEach { history.merge(it) }
        val restoring = restored.isNotEmpty()
        restored.clear()
        engine = SuggestionEngine(lexicon, shortcuts, predictions, history, emoji, pairs)
        this.spelling = spelling
        if (restoring) save(app)
        waiting.forEach { it() }
        waiting.clear()
    }

    /** Saves learned words if anything changed. Serialises on the caller's (main) thread, writes in the background. */
    fun save(context: Context) {
        val history = engine?.history ?: return
        if (!history.dirty) return
        val text = StringWriter().also { history.write(it) }.toString()
        val app = context.applicationContext
        io.execute { writeStored(app, text) }
    }

    /**
     * A copy of everything learned, for a backup: the running engine's words, else
     * the saved ones. [done] runs on the main thread.
     */
    fun learned(context: Context, done: (UserHistory) -> Unit) {
        val running = engine
        if (running != null) {
            val copy = StringWriter().also { running.history.write(it, saved = false) }.toString()
            done(UserHistory.read(copy.reader().buffered()))
            return
        }
        val app = context.applicationContext
        io.execute {
            val stored = readStored(app)
            main.post { done(stored) }
        }
    }

    /** Adds words learned elsewhere (restored from a backup) to what has been learned here. */
    fun restoreLearned(context: Context, words: UserHistory) {
        val app = context.applicationContext
        val running = engine
        when {
            running != null -> {
                running.history.merge(words)
                save(app)
            }
            loading -> restored.add(words)
            // Nothing loaded: add them to the saved words (a later load reads after this).
            else -> io.execute {
                val stored = readStored(app)
                stored.merge(words)
                writeStored(app, StringWriter().also { stored.write(it) }.toString())
            }
        }
    }

    private fun readStored(app: Context): UserHistory {
        val file = File(app.filesDir, HISTORY_FILE)
        if (!file.isFile) return UserHistory()
        return try {
            file.bufferedReader().use { UserHistory.read(it) }
        } catch (e: Exception) {
            UserHistory()
        }
    }

    private fun writeStored(app: Context, text: String) {
        try {
            val tmp = File(app.filesDir, "$HISTORY_FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(app.filesDir, HISTORY_FILE))) Log.w(TAG, "Could not replace learned words")
        } catch (e: Exception) {
            Log.w(TAG, "Could not save learned words", e)
        }
    }

    /** Forgets everything learned (settings > Clear learned words). */
    fun clearLearned(context: Context) {
        engine?.history?.clear()
        val dir = context.applicationContext.filesDir
        io.execute { File(dir, HISTORY_FILE).delete() }
    }
}
