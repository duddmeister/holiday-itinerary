package io.github.duddmeister.lumen.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.github.duddmeister.lumen.keyboard.FieldKind
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.KeyboardMode

/** What the focused text field wants, decoded once from its [EditorInfo]. */
class EditorContext(info: EditorInfo?) {
    val inputType: Int = info?.inputType ?: 0
    private val imeOptions: Int = info?.imeOptions ?: 0
    private val inputClass = inputType and InputType.TYPE_MASK_CLASS
    private val variation = inputType and InputType.TYPE_MASK_VARIATION
    private val flags = inputType and InputType.TYPE_MASK_FLAGS

    /** Raw key events only (terminals and some games): no editing through the connection. */
    val isRaw: Boolean = inputType == InputType.TYPE_NULL
    val isText: Boolean = inputClass == InputType.TYPE_CLASS_TEXT

    val isPassword: Boolean = (isText && variation in PASSWORD_VARIATIONS) ||
        (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    val isEmail: Boolean = isText && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
    val isUrl: Boolean = isText && variation == InputType.TYPE_TEXT_VARIATION_URI
    val isMultiLine: Boolean = isText && flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

    /** Private (incognito) fields ask keyboards not to learn from what is typed. */
    val incognito: Boolean = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    /**
     * Address fields get suggestions too: Chrome's address bar is one, and it's where most
     * searches are typed. Autocorrect there only touches words on their own (see InputLogic).
     */
    val suggestionsAllowed: Boolean = isText && !isPassword && !isEmail &&
        variation != InputType.TYPE_TEXT_VARIATION_FILTER &&
        flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
    val autoCorrectAllowed: Boolean = suggestionsAllowed && variation != InputType.TYPE_TEXT_VARIATION_PERSON_NAME
    /** Nothing is learned from address fields, where much of what's typed is addresses. */
    val learningAllowed: Boolean = suggestionsAllowed && !incognito && !isUrl
    val gesturesAllowed: Boolean = isText && !isPassword && !isEmail
    val capitalisation: Boolean = isText && flags and CAPS_FLAGS != 0

    val mode: KeyboardMode = when (inputClass) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> KeyboardMode.NUMBER
        InputType.TYPE_CLASS_PHONE -> KeyboardMode.PHONE
        else -> KeyboardMode.TEXT
    }

    val fieldKind: FieldKind = when {
        isPassword -> FieldKind.PASSWORD
        isEmail -> FieldKind.EMAIL
        isUrl -> FieldKind.URL
        else -> FieldKind.TEXT
    }

    /** The editor action the enter key performs, or [EditorInfo.IME_ACTION_NONE] for a plain newline. */
    val action: Int = run {
        val a = imeOptions and EditorInfo.IME_MASK_ACTION
        when {
            imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 -> EditorInfo.IME_ACTION_NONE
            a == EditorInfo.IME_ACTION_UNSPECIFIED -> EditorInfo.IME_ACTION_NONE
            else -> a
        }
    }

    val enterIcon: Icon = when (action) {
        EditorInfo.IME_ACTION_SEARCH -> Icon.SEARCH
        EditorInfo.IME_ACTION_SEND -> Icon.SEND
        EditorInfo.IME_ACTION_GO -> Icon.GO
        EditorInfo.IME_ACTION_NEXT -> Icon.NEXT
        EditorInfo.IME_ACTION_PREVIOUS -> Icon.PREVIOUS
        EditorInfo.IME_ACTION_DONE -> Icon.DONE
        else -> Icon.ENTER
    }

    val initialSelectionStart: Int = info?.initialSelStart ?: -1
    val initialSelectionEnd: Int = info?.initialSelEnd ?: -1

    private companion object {
        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        const val CAPS_FLAGS = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
}
