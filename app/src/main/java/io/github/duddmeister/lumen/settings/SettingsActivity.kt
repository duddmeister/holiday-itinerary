package io.github.duddmeister.lumen.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.duddmeister.lumen.BuildConfig
import io.github.duddmeister.lumen.R
import io.github.duddmeister.lumen.ime.EngineHolder
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.KeyShape
import io.github.duddmeister.lumen.keyboard.KeyboardPreview
import io.github.duddmeister.lumen.keyboard.LetterLayout
import io.github.duddmeister.lumen.theme.Themes
import io.github.duddmeister.lumen.ui.Colors
import io.github.duddmeister.lumen.ui.Motion
import io.github.duddmeister.lumen.ui.dp
import kotlin.math.ceil

/** Lumen's home screen: setup, a live theme gallery and every setting. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var kit: SettingsKit
    private lateinit var setupCard: LinearLayout
    private lateinit var hero: HeroPreview
    private lateinit var gallery: ThemeGallery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val palette = Palette(this)
        kit = SettingsKit(this, palette)
        window.decorView.setBackgroundColor(palette.background)
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION") // edge-to-edge is the default from 35; still needed on 30-34
            window.setDecorFitsSystemWindows(false)
            val light = if (palette.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                light,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }

        // On an unfolded foldable or a tablet the page stays a centred, readable column.
        val content = Column(this, dp(MAX_CONTENT_WIDTH_DP))
        val scroll = ScrollView(this).apply {
            id = R.id.settings_scroll
            isFillViewport = true
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        build(content)
    }

    private fun build(root: Column) {
        val p = kit.palette
        // Hero
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(6))
            addView(kit.gradientTitle(getString(R.string.app_name), 40f))
            addView(kit.text(getString(R.string.tagline), 16f, p.secondary).apply { setPadding(0, dp(4), 0, 0) })
        })
        hero = HeroPreview(this)
        root.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), dp(18), dp(14), dp(4))
        })

        // Setup
        setupCard = kit.card()
        root.addView(kit.section(getString(R.string.section_setup)))
        root.addView(setupCard)

        // Try it
        root.addView(kit.section(getString(R.string.section_try)))
        root.addView(kit.card().apply {
            addView(EditText(this@SettingsActivity).apply {
                id = R.id.try_field
                hint = getString(R.string.try_hint)
                setHintTextColor(p.secondary)
                setTextColor(p.text)
                textSize = 16f
                background = kit.rounded(p.chip, dp(16f))
                setPadding(dp(16), dp(14), dp(16), dp(14))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(14), dp(14), dp(14), dp(14))
                }
            })
        })

        // Themes
        root.addView(kit.section(getString(R.string.section_themes)))
        gallery = ThemeGallery(this)
        root.addView(kit.card().apply {
            addView(gallery, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(kit.divider())
            addView(kit.chips(getString(R.string.key_shape), KeyShape.entries.map { it.title }, prefs.keyShape.ordinal) {
                prefs.keyShape = KeyShape.entries[it]
                refreshPreviews()
            })
            addView(kit.divider())
            addView(kit.switchRow(getString(R.string.key_backgrounds), getString(R.string.key_backgrounds_summary), prefs.keyBackgrounds) {
                prefs.set(Prefs.KEY_BACKGROUNDS, it)
                refreshPreviews()
            })
        })

        // Typing
        root.addView(kit.section(getString(R.string.section_typing)))
        root.addView(kit.card().apply {
            toggle(Prefs.AUTO_CORRECT, R.string.autocorrect, R.string.autocorrect_summary, prefs.autoCorrect)
            toggle(Prefs.SUGGESTIONS, R.string.suggestions, R.string.suggestions_summary, prefs.suggestions)
            toggle(Prefs.GLIDE, R.string.glide, R.string.glide_summary, prefs.glideTyping)
            toggle(Prefs.GLIDE_TRAIL, R.string.glide_trail, null, prefs.glideTrail)
            toggle(Prefs.EMOJI_SUGGESTIONS, R.string.emoji_suggestions, R.string.emoji_suggestions_summary, prefs.emojiSuggestions)
            toggle(Prefs.AUTO_CAPS, R.string.auto_caps, null, prefs.autoCaps)
            toggle(Prefs.DOUBLE_SPACE, R.string.double_space, R.string.double_space_summary, prefs.doubleSpacePeriod, last = true)
        })

        // Layout
        root.addView(kit.section(getString(R.string.section_layout)))
        root.addView(kit.card().apply {
            addView(kit.chips(getString(R.string.letter_layout), LetterLayout.entries.map { it.title }, prefs.letterLayout.ordinal) {
                prefs.letterLayout = LetterLayout.entries[it]
            })
            addView(kit.divider())
            toggle(Prefs.NUMBER_ROW, R.string.number_row, null, prefs.numberRow)
            toggle(Prefs.SYMBOL_HINTS, R.string.symbol_hints, R.string.symbol_hints_summary, prefs.symbolHints)
            toggle(Prefs.SPLIT, R.string.split_keyboard, R.string.split_keyboard_summary, prefs.splitKeyboard)
            addView(kit.slider(getString(R.string.keyboard_height), 80, 130, prefs.heightPercent, { "$it%" }) {
                prefs.set(Prefs.HEIGHT, it)
            })
        })

        // Feel
        root.addView(kit.section(getString(R.string.section_feel)))
        root.addView(kit.card().apply {
            toggle(Prefs.KEY_PREVIEWS, R.string.key_previews, null, prefs.keyPreviews)
            toggle(Prefs.LIGHT_EFFECTS, R.string.light_effects, R.string.light_effects_summary, prefs.lightEffects)
            toggle(Prefs.HAPTICS, R.string.haptics, null, prefs.haptics)
            addView(kit.chips(getString(R.string.haptic_strength), listOf(getString(R.string.light), getString(R.string.medium), getString(R.string.firm)), prefs.hapticStrength - 1) {
                prefs.set(Prefs.HAPTIC_STRENGTH, it + 1)
            })
            addView(kit.divider())
            toggle(Prefs.SOUND, R.string.sound, null, prefs.sound)
            addView(kit.slider(getString(R.string.sound_volume), 0, 100, prefs.soundVolume, { "$it%" }) { prefs.set(Prefs.SOUND_VOLUME, it) })
            addView(kit.divider())
            addView(kit.slider(getString(R.string.long_press_delay), 150, 700, prefs.longPressDelay, { "$it ms" }) { prefs.set(Prefs.LONG_PRESS, it) })
            addView(kit.divider())
            toggle(Prefs.SPACE_CURSOR, R.string.space_cursor, R.string.space_cursor_summary, prefs.spaceCursor)
            toggle(Prefs.DELETE_SWIPE, R.string.delete_swipe, R.string.delete_swipe_summary, prefs.deleteSwipe, last = true)
        })

        // Privacy
        root.addView(kit.section(getString(R.string.section_privacy)))
        root.addView(kit.card().apply {
            toggle(Prefs.LEARN, R.string.learn_words, R.string.learn_words_summary, prefs.learnWords)
            toggle(Prefs.CLIPBOARD, R.string.clipboard_history, R.string.clipboard_history_summary, prefs.clipboardHistory)
            addView(kit.actionRow(getString(R.string.clear_learned), getString(R.string.clear_learned_summary), getString(R.string.clear)) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.clear_learned)
                    .setMessage(R.string.clear_learned_confirm)
                    .setPositiveButton(R.string.clear) { _, _ -> EngineHolder.clearLearned(this@SettingsActivity) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
        })
        root.addView(kit.text(getString(R.string.privacy_note), 13.5f, p.secondary).apply {
            setPadding(dp(22), dp(12), dp(22), dp(4))
        })

        // About
        root.addView(kit.section(getString(R.string.section_about)))
        root.addView(kit.card().apply {
            addView(kit.text(getString(R.string.about_body, BuildConfig.VERSION_NAME), 14f, p.secondary).apply {
                setPadding(dp(20), dp(16), dp(20), dp(16))
            })
        })
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(32)))
    }

    private fun LinearLayout.toggle(key: String, title: Int, summary: Int?, value: Boolean, last: Boolean = false) {
        addView(kit.switchRow(getString(title), summary?.let { getString(it) }, value) { prefs.set(key, it) })
        if (!last) addView(kit.divider())
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshPreviews()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog: re-check when it closes.
        if (hasFocus) refreshSetup()
    }

    private fun refreshPreviews() {
        if (::hero.isInitialized) hero.invalidate()
        if (::gallery.isInitialized) gallery.invalidate()
    }

    private fun isEnabled(): Boolean = getSystemService(InputMethodManager::class.java)
        ?.enabledInputMethodList?.any { it.packageName == packageName } == true

    private fun isSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun refreshSetup() {
        if (!::setupCard.isInitialized) return
        setupCard.removeAllViews()
        val p = kit.palette
        val enabled = isEnabled()
        val selected = enabled && isSelected()
        if (selected) {
            setupCard.addView(stepRow(true, getString(R.string.setup_done), getString(R.string.setup_done_summary), null, null))
            return
        }
        setupCard.addView(stepRow(enabled, getString(R.string.step_enable), getString(R.string.step_enable_summary), getString(R.string.open_settings).takeIf { !enabled }) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        setupCard.addView(kit.divider())
        setupCard.addView(stepRow(false, getString(R.string.step_select), getString(R.string.step_select_summary), getString(R.string.choose).takeIf { enabled }) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        })
        setupCard.background = kit.rounded(p.card, dp(22f))
    }

    private fun stepRow(done: Boolean, title: String, subtitle: String, action: String?, onClick: (() -> Unit)?): View {
        val p = kit.palette
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(16), dp(16))
            addView(StatusDot(this@SettingsActivity, done, p.success, p.secondary), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(kit.text(title, 16f, bold = true))
                addView(kit.text(subtitle, 13.5f, p.secondary).apply { setPadding(0, dp(2), 0, 0) })
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (action != null && onClick != null) addView(kit.button(action, onClick = onClick))
        }
    }

    /** A check in a circle when a step is done, an empty ring otherwise. */
    private class StatusDot(context: Context, private val done: Boolean, private val on: Int, private val off: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            if (done) {
                paint.style = Paint.Style.FILL
                paint.color = on
                canvas.drawCircle(r, r, r, paint)
                Icons.draw(canvas, Icon.DONE, r, r, r * 1.2f, 0xFFFFFFFF.toInt(), paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dp(2f)
                paint.color = Colors.withAlpha(off, 0.6f)
                canvas.drawCircle(r, r, r - paint.strokeWidth, paint)
            }
        }
    }

    /** The current theme as a big live keyboard that "types" L-U-M-E-N. */
    private inner class HeroPreview(context: Context) : View(context) {
        private val preview = KeyboardPreview(context)
        private val rect = RectF()
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val started = SystemClock.uptimeMillis()
        private val word = "lumen"

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w / 1.75f).toInt() + context.dp(8))
        }

        override fun onDraw(canvas: Canvas) {
            val night = kit.palette.dark
            val theme = Themes.resolve(context, prefs.theme, night, blurAvailable = false)
            rect.set(0f, 0f, width.toFloat(), height - context.dp(8f))
            shadow.color = Colors.withAlpha(0xFF000000.toInt(), if (night) 0.35f else 0.12f)
            val corner = context.dp(22f)
            for (i in 1..3) {
                val s = context.dp(2f) * i
                canvas.drawRoundRect(rect.left + s, rect.top + s * 1.5f, rect.right - s, rect.bottom + s * 1.5f, corner, corner, shadow.apply { alpha = 40 / i })
            }
            // A gentle loop: each letter of "lumen" lights up in turn, then a pause.
            val t = (SystemClock.uptimeMillis() - started) % 3600
            val index = (t / 380).toInt()
            val within = (t % 380) / 380f
            val press = if (index < word.length) Motion.easeOutCubic(1f - within) else 0f
            val label = word.getOrNull(index)?.toString()
            preview.draw(canvas, rect, theme, prefs.keyShape, corner, prefs.keyBackgrounds, label, press)
            if (isShown) postInvalidateDelayed(16)
        }
    }

    /** Every theme as a live miniature; tap to apply. */
    private inner class ThemeGallery(context: Context) : View(context) {
        private val ids = Themes.catalogue
        private val previews = ids.associateWith { KeyboardPreview(context) }
        private val rect = RectF()
        private val ring = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var columns = 2
        private val pad = context.dp(14f)
        private val gap = context.dp(12f)
        private val labelSpace = context.dp(30f)
        private var pressed = -1

        private fun cardWidth() = (width - 2 * pad - (columns - 1) * gap) / columns
        private fun previewHeight() = cardWidth() / 1.72f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            // Three previews per row once there is room: an unfolded foldable, a tablet.
            columns = if (w >= context.dp(WIDE_GALLERY_DP)) 3 else 2
            val cw = (w - 2 * pad - (columns - 1) * gap) / columns
            val rows = ceil(ids.size / columns.toFloat()).toInt()
            setMeasuredDimension(w, (pad + rows * (cw / 1.72f + labelSpace + gap) - gap + pad / 2).toInt())
        }

        private fun bounds(i: Int, out: RectF) {
            val cw = cardWidth()
            val left = pad + (i % columns) * (cw + gap)
            val top = pad + (i / columns) * (previewHeight() + labelSpace + gap)
            out.set(left, top, left + cw, top + previewHeight())
        }

        override fun onDraw(canvas: Canvas) {
            val night = kit.palette.dark
            val selected = prefs.theme
            for ((i, id) in ids.withIndex()) {
                bounds(i, rect)
                val theme = Themes.resolve(context, id, night, blurAvailable = false)
                val corner = context.dp(16f)
                canvas.save()
                if (i == pressed) canvas.scale(0.97f, 0.97f, rect.centerX(), rect.centerY())
                previews.getValue(id).draw(canvas, rect, theme, prefs.keyShape, corner, prefs.keyBackgrounds)
                if (id == selected) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = context.dp(3f)
                    paint.color = kit.palette.accent
                    ring.set(rect)
                    ring.inset(-context.dp(4f), -context.dp(4f))
                    canvas.drawRoundRect(ring, corner + context.dp(4f), corner + context.dp(4f), paint)
                }
                canvas.restore()
                label.color = if (id == selected) kit.palette.text else kit.palette.secondary
                label.textSize = context.dp(14f)
                label.isFakeBoldText = id == selected
                val name = if (id == Themes.GLASS && Build.VERSION.SDK_INT < 31) "${theme.name} (12+)" else theme.name
                canvas.drawText(name, rect.centerX(), rect.bottom + context.dp(21f), label)
            }
        }

        private fun indexAt(x: Float, y: Float): Int {
            for (i in ids.indices) {
                bounds(i, rect)
                rect.bottom += labelSpace
                if (rect.contains(x, y)) return i
            }
            return -1
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = indexAt(event.x, event.y); invalidate() }
                MotionEvent.ACTION_UP -> {
                    val i = indexAt(event.x, event.y)
                    if (i >= 0 && i == pressed) {
                        prefs.theme = ids[i]
                        performClick()
                        hero.invalidate()
                    }
                    pressed = -1
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private companion object {
        /** The settings column stops growing here on large screens. */
        const val MAX_CONTENT_WIDTH_DP = 640
        /** Gallery width from which three previews fit per row. */
        const val WIDE_GALLERY_DP = 520f
    }
}
