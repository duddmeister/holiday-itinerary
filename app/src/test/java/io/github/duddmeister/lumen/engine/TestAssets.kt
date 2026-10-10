package io.github.duddmeister.lumen.engine

import java.io.BufferedReader
import java.io.File

/** Loads the real app assets from the source tree so tests exercise production data. */
object TestAssets {
    private val root: File by lazy {
        listOfNotNull(
            System.getProperty("lumen.assets")?.let(::File),
            File("src/main/assets"),
            File("app/src/main/assets"),
        ).firstOrNull { File(it, "dict/en_US.txt").isFile }
            ?: error("Cannot find app assets; run tests from the project or app directory")
    }

    fun reader(path: String): BufferedReader = File(root, path).bufferedReader()

    val lexicon: Lexicon by lazy { reader("dict/en_US.txt").use { Lexicon.read(it) } }

    fun engine(history: UserHistory = UserHistory()): SuggestionEngine = SuggestionEngine(
        lexicon,
        reader("dict/en_US_shortcuts.txt").use { SuggestionEngine.readShortcuts(it) },
        reader("dict/en_US_predictions.txt").use { NextWordModel.read(it) },
        history,
        reader("emoji/keywords.txt").use { SuggestionEngine.readEmojiKeywords(it) },
    )
}
