package io.github.duddmeister.lumen.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.duddmeister.lumen.data.GifService
import io.github.duddmeister.lumen.engine.Spelling
import io.github.duddmeister.lumen.keyboard.KeyShape
import io.github.duddmeister.lumen.keyboard.LetterLayout
import io.github.duddmeister.lumen.theme.Themes
import java.util.Locale

/** Typed access to every Lumen setting. Changes reach the keyboard live through [store] listeners. */
class Prefs(context: Context) {
    val store: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = store.getBoolean(key, default)
    private fun int(key: String, default: Int) = store.getInt(key, default)

    fun set(key: String, value: Boolean) = store.edit().putBoolean(key, value).apply()
    fun set(key: String, value: Int) = store.edit().putInt(key, value).apply()
    fun set(key: String, value: String) = store.edit().putString(key, value).apply()

    var theme: String
        get() = store.getString(THEME, Themes.DEFAULT) ?: Themes.DEFAULT
        set(value) = set(THEME, value)
    var keyShape: KeyShape
        get() = KeyShape.of(store.getString(KEY_SHAPE, null))
        set(value) = set(KEY_SHAPE, value.id)
    var letterLayout: LetterLayout
        get() = LetterLayout.of(store.getString(LAYOUT, null))
        set(value) = set(LAYOUT, value.id)
    /** Which English to spell in: the user's choice, else the phone's region decides. */
    var spelling: Spelling
        get() = Spelling.of(store.getString(SPELLING, null)) ?: Spelling.forRegion(Locale.getDefault().country)
        set(value) = set(SPELLING, value.id)

    val keyBackgrounds get() = bool(KEY_BACKGROUNDS, true)
    val heightPercent get() = int(HEIGHT, 100).coerceIn(80, 130)
    val numberRow get() = bool(NUMBER_ROW, false)
    val splitKeyboard get() = bool(SPLIT, true)
    val symbolHints get() = bool(SYMBOL_HINTS, true)
    val keyPreviews get() = bool(KEY_PREVIEWS, true)
    val lightEffects get() = bool(LIGHT_EFFECTS, true)
    val autoCaps get() = bool(AUTO_CAPS, true)
    val autoCorrect get() = bool(AUTO_CORRECT, true)
    val suggestions get() = bool(SUGGESTIONS, true)
    val doubleSpacePeriod get() = bool(DOUBLE_SPACE, true)
    val glideTyping get() = bool(GLIDE, true)
    val glideTrail get() = bool(GLIDE_TRAIL, true)
    val emojiSuggestions get() = bool(EMOJI_SUGGESTIONS, true)
    val haptics get() = bool(HAPTICS, true)
    val hapticStrength get() = int(HAPTIC_STRENGTH, 2).coerceIn(1, 3)
    val sound get() = bool(SOUND, false)
    val soundVolume get() = int(SOUND_VOLUME, 40).coerceIn(0, 100)
    val learnWords get() = bool(LEARN, true)
    val clipboardHistory get() = bool(CLIPBOARD, true)
    /** Swipes on the space bar move by words and finish words. */
    val spaceCursor get() = bool(SPACE_CURSOR, true)
    val deleteSwipe get() = bool(DELETE_SWIPE, true)
    val longPressDelay get() = int(LONG_PRESS, 300).coerceIn(150, 700)
    val gifs get() = bool(GIFS, true)
    /** Look for a new version of Lumen every day, and whenever the app opens. */
    val autoUpdates get() = bool(AUTO_UPDATES, true)
    /** The newest version a notification has announced, so each is announced once. */
    val announcedVersion get() = int(ANNOUNCED_VERSION, 0)
    /** Lumen has asked for Android's notification permission (it only asks once). */
    val notificationsAsked get() = bool(NOTIFICATIONS_ASKED, false)

    var gifService: GifService
        get() = GifService.of(store.getString(GIF_SERVICE, null)) ?: GifService.default
        set(value) = set(GIF_SERVICE, value.id)

    /** The API key for the GIF service: the user's own if they added one, else the one built in (if any). */
    val gifKey: String
        get() = gifService.let { store.getString(keyPref(it), null)?.trim().orEmpty().ifEmpty { it.builtInKey } }

    companion object {
        const val FILE = "lumen_settings"
        const val THEME = "theme"
        const val KEY_SHAPE = "key_shape"
        const val KEY_BACKGROUNDS = "key_backgrounds"
        const val LAYOUT = "layout"
        const val SPELLING = "spelling"
        const val HEIGHT = "keyboard_height"
        const val NUMBER_ROW = "number_row"
        const val SPLIT = "split_keyboard"
        const val SYMBOL_HINTS = "symbol_hints"
        const val KEY_PREVIEWS = "key_previews"
        const val LIGHT_EFFECTS = "light_effects"
        const val AUTO_CAPS = "auto_caps"
        const val AUTO_CORRECT = "auto_correct"
        const val SUGGESTIONS = "suggestions"
        const val DOUBLE_SPACE = "double_space_period"
        const val GLIDE = "glide_typing"
        const val GLIDE_TRAIL = "glide_trail"
        const val EMOJI_SUGGESTIONS = "emoji_suggestions"
        const val HAPTICS = "haptics"
        const val HAPTIC_STRENGTH = "haptic_strength"
        const val SOUND = "sound"
        const val SOUND_VOLUME = "sound_volume"
        const val LEARN = "learn_words"
        const val CLIPBOARD = "clipboard_history"
        const val SPACE_CURSOR = "space_cursor"
        const val DELETE_SWIPE = "delete_swipe"
        const val LONG_PRESS = "long_press_delay"
        const val GIFS = "gifs"
        const val AUTO_UPDATES = "auto_updates"
        const val ANNOUNCED_VERSION = "announced_version"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
        const val GIF_SERVICE = "gif_service"
        const val GIPHY_KEY = "giphy_api_key"
        const val KLIPY_KEY = "klipy_api_key"

        /** Where the user's own key for [service] is saved. */
        fun keyPref(service: GifService) = when (service) {
            GifService.GIPHY -> GIPHY_KEY
            GifService.KLIPY -> KLIPY_KEY
        }
    }
}
