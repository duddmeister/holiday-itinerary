package io.github.duddmeister.lumen.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint

/** One emoji and, when it supports them, its five skin-tone variants. */
class Emoji(val base: String, val variants: List<String>)

class EmojiCategory(val name: String, val icon: String, val emoji: List<Emoji>)

/**
 * Emoji catalogue loaded from assets/emoji/emoji.txt, trimmed to what the
 * device's emoji font can actually draw, plus the user's recents and preferred
 * skin tones.
 */
class EmojiData private constructor(val categories: List<EmojiCategory>, private val prefs: SharedPreferences) {

    private val recents = ArrayList<String>(prefs.getString(KEY_RECENTS, "")!!.split(' ').filter { it.isNotEmpty() })
    private val tones = HashMap<String, String>().apply {
        prefs.getString(KEY_TONES, "")!!.split(' ').forEach {
            val i = it.indexOf('=')
            if (i > 0) put(it.substring(0, i), it.substring(i + 1))
        }
    }
    private val variantsByBase = HashMap<String, Emoji>().apply {
        for (c in categories) for (e in c.emoji) put(e.base, e)
    }

    fun recents(): List<String> = recents

    fun variantsOf(emoji: String): Emoji? = variantsByBase[emoji] ?: variantsByBase[baseOf(emoji)]

    private fun baseOf(variant: String): String? =
        variantsByBase.values.firstOrNull { variant in it.variants }?.base

    /** The form to show for [base]: the user's last chosen skin tone, if any. */
    fun display(base: String): String = tones[base] ?: base

    fun recordUse(emoji: String) {
        recents.remove(emoji)
        recents.add(0, emoji)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.size - 1)
        val base = baseOf(emoji)
        if (base != null) tones[base] = emoji else if (variantsByBase[emoji] != null) tones.remove(emoji)
        prefs.edit()
            .putString(KEY_RECENTS, recents.joinToString(" "))
            .putString(KEY_TONES, tones.entries.joinToString(" ") { "${it.key}=${it.value}" })
            .apply()
    }

    companion object {
        private const val KEY_RECENTS = "emoji_recents"
        private const val KEY_TONES = "emoji_tones"
        private const val MAX_RECENTS = 32

        private val ICONS = mapOf(
            "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻",
            "Food & Drink" to "🍔", "Travel & Places" to "🚗", "Activities" to "⚽",
            "Objects" to "💡", "Symbols" to "❤️", "Flags" to "🏳️",
        )

        /** Reads the catalogue; call off the main thread (glyph checks take a moment). */
        fun load(context: Context, prefs: SharedPreferences): EmojiData {
            val paint = Paint()
            val categories = ArrayList<EmojiCategory>()
            var name: String? = null
            var list = ArrayList<Emoji>()
            fun flush() {
                val n = name ?: return
                if (list.isNotEmpty()) categories.add(EmojiCategory(n, ICONS[n] ?: list.first().base, list))
            }
            context.assets.open("emoji/emoji.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("@group ")) {
                        flush()
                        name = line.substring(7)
                        list = ArrayList()
                        continue
                    }
                    if (line.isBlank()) continue
                    val parts = line.split(' ')
                    val base = parts[0]
                    if (!paint.hasGlyph(base)) continue
                    val variants = parts.drop(1).takeIf { it.isNotEmpty() && paint.hasGlyph(it[0]) } ?: emptyList()
                    list.add(Emoji(base, variants))
                }
            }
            flush()
            return EmojiData(categories, prefs)
        }
    }
}
