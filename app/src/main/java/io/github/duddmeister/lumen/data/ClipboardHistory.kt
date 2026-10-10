package io.github.duddmeister.lumen.data

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently copied text, kept while Lumen runs. Unpinned clips stay in memory
 * only and expire after an hour; pinned clips are saved until removed.
 * Clips that apps mark as sensitive (passwords) are never recorded.
 */
class ClipboardHistory(context: Context, private val prefs: SharedPreferences) {
    class Clip(val text: String, val time: Long, var pinned: Boolean)

    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val clips = ArrayList<Clip>()
    private var latest: Clip? = null
    private var chipDismissed = false
    var enabled = true
    var onChange: (() -> Unit)? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

    init {
        try {
            val array = JSONArray(prefs.getString(KEY_PINNED, "[]"))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                clips.add(Clip(o.getString("t"), o.getLong("at"), true))
            }
        } catch (_: Exception) {
            // Corrupt data: start fresh rather than crash the keyboard.
        }
    }

    fun start() = manager?.addPrimaryClipChangedListener(listener)

    fun stop() = manager?.removePrimaryClipChangedListener(listener)

    private fun capture() {
        if (!enabled) return
        val clip = try { manager?.primaryClip } catch (_: SecurityException) { null } ?: return
        if (clip.itemCount == 0) return
        if (Build.VERSION.SDK_INT >= 33 && clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
            latest = null
            return
        }
        val text = clip.getItemAt(0).text?.toString() ?: return
        if (text.isBlank() || text.length > MAX_LENGTH) return
        add(text)
    }

    fun add(text: String, now: Long = System.currentTimeMillis()) {
        val existing = clips.firstOrNull { it.text == text }
        val pinned = existing?.pinned ?: false
        clips.remove(existing)
        val clip = Clip(text, now, pinned)
        clips.add(0, clip)
        latest = clip
        chipDismissed = false
        var unpinned = 0
        clips.removeAll { !it.pinned && ++unpinned > MAX_UNPINNED }
        if (pinned) savePinned()
        onChange?.invoke()
    }

    /** Pinned clips first, then the rest newest first; expired clips are dropped. */
    fun items(now: Long = System.currentTimeMillis()): List<Clip> {
        clips.removeAll { !it.pinned && now - it.time > EXPIRY_MS }
        return clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })
    }

    /** Text for the paste chip: something copied in the last minute that has not been pasted yet. */
    fun freshClip(now: Long = System.currentTimeMillis()): String? {
        val l = latest ?: return null
        return if (!chipDismissed && enabled && now - l.time < CHIP_MS) l.text else null
    }

    fun dismissChip() {
        chipDismissed = true
    }

    fun togglePin(clip: Clip) {
        clip.pinned = !clip.pinned
        savePinned()
        onChange?.invoke()
    }

    fun remove(clip: Clip) {
        clips.remove(clip)
        if (latest === clip) latest = null
        if (clip.pinned) savePinned()
        onChange?.invoke()
    }

    fun clearUnpinned() {
        clips.removeAll { !it.pinned }
        latest = null
        onChange?.invoke()
    }

    private fun savePinned() {
        val array = JSONArray()
        for (c in clips) if (c.pinned) array.put(JSONObject().put("t", c.text).put("at", c.time))
        prefs.edit().putString(KEY_PINNED, array.toString()).apply()
    }

    companion object {
        private const val KEY_PINNED = "clipboard_pinned"
        private const val MAX_UNPINNED = 30
        private const val MAX_LENGTH = 20_000
        private const val EXPIRY_MS = 60 * 60 * 1000L
        private const val CHIP_MS = 60 * 1000L
    }
}
