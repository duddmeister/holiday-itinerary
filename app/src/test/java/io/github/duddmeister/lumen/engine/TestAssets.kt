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
    private val britishLexicon: Lexicon by lazy { reader("dict/en_GB.txt").use { Lexicon.read(it) } }
    private val pairs = HashMap<Spelling, WordPairs>()

    fun pairs(spelling: Spelling): WordPairs =
        pairs.getOrPut(spelling) { reader("dict/${spelling.locale}_bigrams.txt").use { WordPairs.read(it) } }

    fun engine(history: UserHistory = UserHistory(), spelling: Spelling = Spelling.AMERICAN): SuggestionEngine = SuggestionEngine(
        if (spelling == Spelling.BRITISH) britishLexicon else lexicon,
        reader("dict/${spelling.locale}_shortcuts.txt").use { SuggestionEngine.readShortcuts(it) },
        reader("dict/${spelling.locale}_predictions.txt").use { NextWordModel.read(it) },
        history,
        reader("emoji/keywords.txt").use { SuggestionEngine.readEmojiKeywords(it) },
        pairs(spelling),
    )
}
