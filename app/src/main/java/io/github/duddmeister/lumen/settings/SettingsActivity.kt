package io.github.duddmeister.lumen.settings

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.duddmeister.lumen.BuildConfig
import io.github.duddmeister.lumen.R
import io.github.duddmeister.lumen.data.GifService
import io.github.duddmeister.lumen.engine.Spelling
import io.github.duddmeister.lumen.ime.EngineHolder
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.KeyFinish
import io.github.duddmeister.lumen.keyboard.KeyPainter
import io.github.duddmeister.lumen.keyboard.KeyShape
import io.github.duddmeister.lumen.keyboard.KeyboardPreview
import io.github.duddmeister.lumen.keyboard.LabelFont
import io.github.duddmeister.lumen.keyboard.LetterLayout
import io.github.duddmeister.lumen.keyboard.LetterSize
import io.github.duddmeister.lumen.theme.Accent
import io.github.duddmeister.lumen.theme.KeyboardTheme
import io.github.duddmeister.lumen.theme.Themes
import io.github.duddmeister.lumen.ui.Colors
import io.github.duddmeister.lumen.ui.Motion
import io.github.duddmeister.lumen.ui.dp
import io.github.duddmeister.lumen.update.UpdateAlerts
import io.github.duddmeister.lumen.update.Updater
import java.io.IOException
import java.time.LocalDate
import kotlin.math.ceil

/** Lumen's home screen: setup, a live theme gallery and every setting. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var kit: SettingsKit
    private lateinit var setupCard: LinearLayout
    private var updateCard: LinearLayout? = null
    private var resumed = false
    /** Opened from the update notification: install the update once it's found. */
    private var installWhenFound = false
    private val onUpdate: (Updater.State) -> Unit = { refreshUpdate() }
    private lateinit var hero: HeroPreview
    private lateinit var accents: SwatchPicker
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
        installWhenFound = intent?.getBooleanExtra(UpdateAlerts.EXTRA_INSTALL, false) == true
        build(content)
        Updater.listen(onUpdate)
        UpdateAlerts.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(UpdateAlerts.EXTRA_INSTALL, false)) installWhenFound = true
    }

    override fun onDestroy() {
        Updater.unlisten(onUpdate)
        super.onDestroy()
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

        // Updates, for builds that can install Lumen's releases
        if (Updater.enabled) {
            root.addView(kit.section(getString(R.string.section_updates)))
            val card = kit.card()
            updateCard = card
            root.addView(card)
            refreshUpdate()
        }

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
        accents = kit.swatches(
            getString(R.string.accent_colour), Accent.entries.size,
            colors = { i ->
                val own = ownTheme()
                val accent = Accent.entries[i]
                if (accent == Accent.THEME) intArrayOf(own.accent, own.accentEnd, own.onAccent)
                else intArrayOf(accent.start(own.dark), accent.end(own.dark), accent.on(own.dark))
            },
            names = { i -> if (i == 0) getString(R.string.accent_theme) else Accent.entries[i].title },
            selected = prefs.accent.ordinal,
        ) {
            prefs.accent = Accent.entries[it]
            refreshPreviews()
        }
        root.addView(kit.card().apply {
            addView(gallery, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(kit.divider())
            addView(accents)
            addView(kit.divider())
            addView(kit.chips(getString(R.string.key_finish), KeyFinish.entries.map { it.title }, prefs.keyFinish.ordinal) {
                prefs.keyFinish = KeyFinish.entries[it]
                refreshPreviews()
            })
            addView(kit.divider())
            addView(kit.chips(getString(R.string.key_shape), KeyShape.entries.map { it.title }, prefs.keyShape.ordinal) {
                prefs.keyShape = KeyShape.entries[it]
                refreshPreviews()
            })
            addView(kit.divider())
            // Each font's chip is set in that font.
            val fonts = KeyPainter(this@SettingsActivity)
            val faces = LabelFont.entries.map { fonts.font = it; fonts.mediumTypeface }
            addView(kit.chips(getString(R.string.label_font), LabelFont.entries.map { it.title }, prefs.labelFont.ordinal, faces) {
                prefs.labelFont = LabelFont.entries[it]
                refreshPreviews()
            })
            addView(kit.divider())
            addView(kit.chips(getString(R.string.letter_size), LetterSize.entries.map { it.title }, prefs.letterSize.ordinal) {
                prefs.letterSize = LetterSize.entries[it]
                refreshPreviews()
            })
        })

        // Typing
        root.addView(kit.section(getString(R.string.section_typing)))
        root.addView(kit.card().apply {
            addView(kit.chips(getString(R.string.spelling), Spelling.entries.map { it.title }, prefs.spelling.ordinal) {
                prefs.spelling = Spelling.entries[it]
            })
            addView(kit.divider())
            toggle(Prefs.AUTO_CORRECT, R.string.autocorrect, R.string.autocorrect_summary, prefs.autoCorrect)
            toggle(Prefs.SUGGESTIONS, R.string.suggestions, R.string.suggestions_summary, prefs.suggestions)
            toggle(Prefs.SPACE_CURSOR, R.string.space_cursor, R.string.space_cursor_summary, prefs.spaceCursor)
            toggle(Prefs.GLIDE, R.string.glide, R.string.glide_summary, prefs.glideTyping)
            toggle(Prefs.GLIDE_TRAIL, R.string.glide_trail, null, prefs.glideTrail)
            toggle(Prefs.VOICE, R.string.voice_typing, R.string.voice_typing_summary, prefs.voiceTyping)
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
            toggle(Prefs.FLICK_SYMBOLS, R.string.flick_symbols, R.string.flick_symbols_summary, prefs.flickSymbols)
            toggle(Prefs.SPLIT, R.string.split_keyboard, R.string.split_keyboard_summary, prefs.splitKeyboard)
            toggle(Prefs.ONE_HANDED, R.string.one_handed, R.string.one_handed_summary, prefs.oneHanded)
            toggle(Prefs.FLOATING, R.string.floating_keyboard, R.string.floating_keyboard_summary, prefs.floating)
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
            toggle(Prefs.DELETE_SWIPE, R.string.delete_swipe, R.string.delete_swipe_summary, prefs.deleteSwipe, last = true)
        })

        // GIFs
        root.addView(kit.section(getString(R.string.section_gifs)))
        root.addView(kit.card().apply {
            toggle(Prefs.GIFS, R.string.gifs, R.string.gifs_summary, prefs.gifs)
            val keyField = EditText(this@SettingsActivity)
            addView(kit.chips(getString(R.string.gif_service), GifService.entries.map { it.title }, prefs.gifService.ordinal) {
                prefs.gifService = GifService.entries[it]
                showGifKey(keyField)
            })
            addView(kit.text(getString(R.string.gif_key), 16f).apply { setPadding(dp(20), dp(14), dp(20), 0) })
            addView(keyField.apply {
                id = R.id.gif_key_field
                // Always the saved key: if the field put back its own old text when the
                // screen is rebuilt (after a restore picks another service, say), it would
                // save that over the key.
                isSaveEnabled = false
                setHintTextColor(p.secondary)
                setTextColor(p.text)
                textSize = 15f
                isSingleLine = true
                // A password-style field, so keyboards (Lumen included) never learn the key.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                background = kit.rounded(p.chip, dp(14f))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                showGifKey(this)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = prefs.set(Prefs.keyPref(prefs.gifService), s?.toString()?.trim().orEmpty())
                })
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(14), dp(8), dp(14), dp(10))
                }
            })
            addView(kit.divider())
            addView(kit.actionRow(getString(R.string.gif_get_key), getString(R.string.gif_get_key_summary), getString(R.string.gif_get_key_action)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefs.gifService.keyPage)))
                } catch (e: android.content.ActivityNotFoundException) {
                    // No browser: nothing to open.
                }
            })
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

        // Backup
        root.addView(kit.section(getString(R.string.section_backup)))
        root.addView(kit.card().apply {
            addView(kit.actionRow(getString(R.string.backup), getString(R.string.backup_summary), getString(R.string.backup_action)) {
                choose(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TITLE, getString(R.string.backup_file_name, LocalDate.now().toString())),
                    REQUEST_BACKUP,
                )
            })
            addView(kit.divider())
            addView(kit.actionRow(getString(R.string.restore), getString(R.string.restore_summary), getString(R.string.restore_action)) {
                // A backup is plain text, but not every place that keeps files says so.
                choose(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_RESTORE)
            })
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

    // ------------------------------------------------------------ backup

    /** Asks Android's file chooser for a place to save to or a file to open. */
    private fun choose(intent: Intent, request: Int) {
        try {
            startActivityForResult(intent, request)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.backup_no_files, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_BACKUP -> backUp(uri)
            REQUEST_RESTORE -> restore(uri)
        }
    }

    /** Writes the settings and learned words to [uri], off the main thread. */
    private fun backUp(uri: Uri) {
        val settings = HashMap(prefs.store.all)
        val app = applicationContext
        EngineHolder.learned(this) { words ->
            Thread({
                val saved = try {
                    val out = app.contentResolver.openOutputStream(uri, "w") ?: throw IOException("No file to write to")
                    out.bufferedWriter().use { Backup.write(it, settings, words) }
                    true
                } catch (e: Exception) {
                    // Can't write there (a full disk, a file manager that refused, an expired grant).
                    false
                }
                val message = if (saved) {
                    app.resources.getQuantityString(R.plurals.backup_saved, words.wordCount, words.wordCount)
                } else {
                    app.getString(R.string.backup_failed)
                }
                runOnUiThread { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
            }, "lumen-backup").start()
        }
    }

    /** Reads a backup from [uri] off the main thread, then restores it. */
    private fun restore(uri: Uri) {
        val app = applicationContext
        Thread({
            var problem = R.string.restore_failed
            val backup = try {
                app.contentResolver.openInputStream(uri)?.use { Backup.read(it) }
            } catch (e: Backup.NotABackup) {
                problem = R.string.restore_not_backup
                null
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (backup == null) {
                    Toast.makeText(app, problem, Toast.LENGTH_LONG).show()
                } else {
                    restore(backup)
                }
            }
        }, "lumen-restore").start()
    }

    private fun restore(backup: Backup.Contents) {
        val edit = prefs.store.edit()
        for ((key, value) in backup.settings) {
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
            }
        }
        // A backup from before key finishes holds only the switch for key backgrounds: let it decide.
        if (Prefs.KEY_BACKGROUNDS in backup.settings && Prefs.KEY_FINISH !in backup.settings) edit.remove(Prefs.KEY_FINISH)
        edit.apply()
        EngineHolder.restoreLearned(this, backup.words)
        val count = backup.words.wordCount
        Toast.makeText(applicationContext, resources.getQuantityString(R.plurals.restored, count, count), Toast.LENGTH_LONG).show()
        // Show the settings as they now are.
        if (!isFinishing && !isDestroyed) recreate()
    }

    private fun LinearLayout.toggle(key: String, title: Int, summary: Int?, value: Boolean, last: Boolean = false) {
        addView(kit.switchRow(getString(title), summary?.let { getString(it) }, value) { prefs.set(key, it) })
        if (!last) addView(kit.divider())
    }

    /** Shows where updating stands: one row for the latest news, then the automatic-check switch. */
    private fun refreshUpdate() {
        val card = updateCard ?: return
        val state = Updater.state
        // Android asked the user to confirm the install: show its screen while we're in front.
        if (state is Updater.State.Installing && state.confirm != null && resumed) {
            try {
                startActivity(state.confirm)
            } catch (e: android.content.ActivityNotFoundException) {
                // Nothing to show it; the installer reports the failure.
            }
            Updater.confirmShown()
            return
        }
        if (installWhenFound) {
            when (state) {
                is Updater.State.Available -> {
                    installWhenFound = false
                    Updater.install(this, state.release)
                    return
                }
                Updater.State.Current, is Updater.State.Failed -> installWhenFound = false
                else -> {}
            }
        }
        card.removeAllViews()
        val current = BuildConfig.VERSION_NAME
        val lumen = getString(R.string.app_name) + " " + current
        when (state) {
            Updater.State.Idle ->
                card.addView(updateRow(lumen, getString(R.string.update_unchecked), getString(R.string.update_check), filled = false) {
                    Updater.check(now = true)
                })
            Updater.State.Checking ->
                card.addView(updateRow(lumen, getString(R.string.update_checking)))
            Updater.State.Current ->
                card.addView(updateRow(lumen, getString(R.string.update_current), getString(R.string.update_check), filled = false) {
                    Updater.check(now = true)
                })
            is Updater.State.Available -> {
                val news = state.release.changes.joinToString("\n") { "• $it" }
                    .ifEmpty { getString(R.string.update_available_summary, current) }
                card.addView(updateRow(getString(R.string.update_available, state.release.versionName), news, getString(R.string.update_install)) {
                    Updater.install(this, state.release)
                })
            }
            is Updater.State.NeedsPermission ->
                card.addView(updateRow(getString(R.string.update_permission), getString(R.string.update_permission_summary), getString(R.string.update_permission_action)) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (e: android.content.ActivityNotFoundException) {
                        // No such screen on this device; Android's installer asks instead.
                        Updater.install(this, state.release)
                    }
                })
            is Updater.State.Downloading -> {
                val percent = (state.progress * 100).toInt()
                card.addView(updateRow(getString(R.string.update_downloading, state.release.versionName), "$percent%"))
                card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = percent
                    progressTintList = ColorStateList.valueOf(kit.palette.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(Colors.withAlpha(kit.palette.secondary, 0.35f))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(20), 0, dp(20), dp(14))
                    }
                })
            }
            is Updater.State.Installing ->
                card.addView(updateRow(getString(R.string.update_installing, state.release.versionName), getString(R.string.update_installing_summary)))
            is Updater.State.Failed -> {
                val release = state.release
                val title = getString(if (release != null) R.string.update_failed else R.string.update_check_failed)
                card.addView(updateRow(title, state.message, getString(R.string.update_retry), filled = false) {
                    if (release != null) Updater.install(this, release) else Updater.check(now = true)
                })
            }
        }
        card.addView(kit.divider())
        card.addView(kit.switchRow(getString(R.string.auto_updates), getString(R.string.auto_updates_summary), prefs.autoUpdates) {
            prefs.set(Prefs.AUTO_UPDATES, it)
            UpdateAlerts.sync(this)
            card.post { refreshUpdate() }
        })
        // Once Android has been asked (or never needed asking), say if notifications are off.
        val asked = Build.VERSION.SDK_INT < 33 || prefs.notificationsAsked
        if (prefs.autoUpdates && asked && getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == false) {
            card.addView(kit.divider())
            card.addView(updateRow(getString(R.string.update_alerts_off), getString(R.string.update_alerts_off_summary), getString(R.string.update_alerts_on), filled = false) {
                try {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } catch (e: android.content.ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            })
        }
    }

    /** Update notifications need Android 13's permission: ask once, when Lumen is set up. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || !Updater.enabled || !prefs.autoUpdates || prefs.notificationsAsked) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || !isEnabled()) return
        prefs.set(Prefs.NOTIFICATIONS_ASKED, true)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) refreshUpdate()
    }

    private fun updateRow(title: String, subtitle: String, action: String? = null, filled: Boolean = true, onClick: () -> Unit = {}): View {
        val p = kit.palette
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(kit.text(title, 16f, bold = true))
                addView(kit.text(subtitle, 13.5f, p.secondary).apply { setPadding(0, dp(2), 0, 0) })
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            })
            if (action != null) addView(kit.button(action, filled = filled, onClick = onClick))
        }
    }

    /** Puts the key saved for the chosen GIF service in [field]. */
    private fun showGifKey(field: EditText) {
        val service = prefs.gifService
        field.setText(prefs.store.getString(Prefs.keyPref(service), ""))
        field.hint = getString(if (service.builtInKey.isEmpty()) R.string.gif_key_hint else R.string.gif_key_builtin)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (Updater.enabled) {
            val state = Updater.state
            // Back from allowing Lumen to install apps: carry on with the update.
            if (state is Updater.State.NeedsPermission && packageManager.canRequestPackageInstalls()) {
                Updater.install(this, state.release)
            } else if (installWhenFound) {
                Updater.check(now = true)
            } else if (prefs.autoUpdates) {
                Updater.check(now = false)
            }
            refreshUpdate()
            UpdateAlerts.dismissIfInstalled(this)
            askForNotifications()
        }
        refreshSetup()
        refreshPreviews()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog: re-check when it closes.
        if (hasFocus) refreshSetup()
    }

    private fun refreshPreviews() {
        if (::hero.isInitialized) hero.invalidate()
        if (::gallery.isInitialized) gallery.invalidate()
        if (::accents.isInitialized) accents.refresh()
    }

    /** The chosen theme in its own colours, as the settings app's light or dark mode shows it. */
    private fun ownTheme(): KeyboardTheme = Themes.resolve(this, prefs.theme, kit.palette.dark, blurAvailable = false)

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
            val theme = Themes.resolve(context, prefs.theme, night, blurAvailable = false).withAccent(prefs.accent)
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
            preview.draw(canvas, rect, theme, prefs.keyLook, corner, label, press)
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
            val accent = prefs.accent
            val look = prefs.keyLook
            for ((i, id) in ids.withIndex()) {
                bounds(i, rect)
                val theme = Themes.resolve(context, id, night, blurAvailable = false).withAccent(accent)
                val corner = context.dp(16f)
                canvas.save()
                if (i == pressed) canvas.scale(0.97f, 0.97f, rect.centerX(), rect.centerY())
                previews.getValue(id).draw(canvas, rect, theme, look, corner)
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
                        refreshPreviews()
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
        const val REQUEST_NOTIFICATIONS = 1
        const val REQUEST_BACKUP = 2
        const val REQUEST_RESTORE = 3
        /** The settings column stops growing here on large screens. */
        const val MAX_CONTENT_WIDTH_DP = 640
        /** Gallery width from which three previews fit per row. */
        const val WIDE_GALLERY_DP = 520f
    }
}
