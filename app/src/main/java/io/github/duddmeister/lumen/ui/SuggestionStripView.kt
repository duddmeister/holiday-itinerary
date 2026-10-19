package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.KeyPainter

/**
 * The bar above the keys. Shows three suggestions (the one a space will commit
 * in the middle, emphasised), a paste chip for freshly copied text, a live
 * preview while glide typing, or — when there is nothing to suggest — a
 * toolbar with Lumen's panels, the less used ones behind its ⋯ button.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context, private val painter: KeyPainter) : View(context) {

    class Item(val text: String, val primary: Boolean = false, val quoted: Boolean = false, val emoji: Boolean = false)

    enum class Action(val icon: Icon) {
        TOGGLE(Icon.SPARKLE), SEARCH(Icon.SEARCH), CLIPBOARD(Icon.CLIPBOARD), EMOJI(Icon.EMOJI), GIF(Icon.GIF),
        THEMES(Icon.PALETTE), SETTINGS(Icon.SETTINGS), VOICE(Icon.MIC), HIDE(Icon.KEYBOARD_HIDE),
        /** One-handed or floating, whichever the screen offers; drawn with [modeIcon]. */
        MODE(Icon.ONE_HANDED),
        /** Swaps the toolbar's everyday buttons for the rest, and back. */
        MORE(Icon.MORE),
    }

    companion object {
        /** The strip's height, which the search bar in its place shares. */
        const val HEIGHT_DP = 32
    }

    interface Listener {
        fun onSuggestion(index: Int, item: Item)
        fun onSuggestionLongPress(index: Int, item: Item)
        fun onAction(action: Action)
        fun onPasteChip(text: String)
    }

    var listener: Listener? = null

    /** Left, middle and right slots. */
    private var slots: Array<Item?> = arrayOfNulls(3)
    private var clipText: String? = null
    private var gesturePreview: String? = null
    private var message: String? = null
    private var messageUntil = 0L
    var toolbarRequested = false
        private set
    private var changedAt = 0L
    /** When MORE last swapped the toolbar's buttons. */
    private var swappedAt = 0L

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val buttonSize = context.dp(44f)

    /** The toolbar's buttons between the sparkle and the hide button, MORE last. */
    private fun everyday() = listOfNotNull(
        Action.SEARCH, Action.EMOJI, Action.GIF.takeIf { gifsEnabled }, Action.CLIPBOARD,
        Action.VOICE.takeIf { voiceEnabled }, Action.MORE,
    )

    /** The buttons MORE shows in place of the everyday ones. */
    private fun extras() = listOfNotNull(Action.THEMES, Action.MODE.takeIf { modeIcon != null }, Action.SETTINGS)

    /** Shows the GIF button; off when GIFs are turned off in settings. */
    var gifsEnabled = true
        set(value) {
            if (field != value) {
                field = value
                everyday = everyday()
                extras = extras()
                invalidate()
            }
        }

    /** Shows the microphone; off when voice typing is turned off in settings. */
    var voiceEnabled = true
        set(value) {
            if (field != value) {
                field = value
                everyday = everyday()
                extras = extras()
                invalidate()
            }
        }

    /** The icon for the one-handed or floating button, or null to leave the button out. */
    var modeIcon: Icon? = null
        set(value) {
            if (field != value) {
                field = value
                everyday = everyday()
                extras = extras()
                invalidate()
            }
        }

    private var everyday = everyday()
    private var extras = extras()

    /** The toolbar shows the [extras] in place of the everyday buttons. */
    var moreOpen = false
        private set

    private var pressedSlot = -1
    private var pressedAction: Action? = null
    private var pressedChip = false
    private var downTime = 0L
    private val longPress = Runnable {
        val i = pressedSlot
        val item = slots.getOrNull(i)
        if (i >= 0 && item != null) {
            pressedSlot = -1
            invalidate()
            listener?.onSuggestionLongPress(i, item)
        }
    }

    fun setSuggestions(left: Item?, middle: Item?, right: Item?) {
        val changed = left?.text != slots[0]?.text || middle?.text != slots[1]?.text || right?.text != slots[2]?.text
        slots = arrayOf(left, middle, right)
        if (changed) changedAt = SystemClock.uptimeMillis()
        if (left != null || middle != null || right != null) toolbarRequested = false
        settle()
        invalidate()
    }

    fun clearSuggestions() = setSuggestions(null, null, null)

    fun setClip(text: String?) {
        if (clipText != text) {
            clipText = text
            changedAt = SystemClock.uptimeMillis()
            settle()
            invalidate()
        }
    }

    fun setGesturePreview(word: String?) {
        if (gesturePreview != word) {
            gesturePreview = word
            settle()
            invalidate()
        }
    }

    /** Briefly shows a note in place of suggestions ("Removed "word""). */
    fun flash(note: String) {
        message = note
        messageUntil = SystemClock.uptimeMillis() + 1600
        invalidate()
        postDelayed({ invalidate() }, 1650)
    }

    fun toggleToolbar() {
        toolbarRequested = !toolbarRequested
        moreOpen = false
        changedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun toggleMore() {
        moreOpen = !moreOpen
        swappedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Back to the everyday buttons, at once. */
    fun closeMore() {
        if (moreOpen) {
            moreOpen = false
            invalidate()
        }
    }

    /** The toolbar opens on its everyday buttons each time it comes back. */
    private fun settle() {
        if (!showingToolbar) moreOpen = false
    }

    private val hasSuggestions get() = slots.any { it != null }
    private val showingToolbar get() = toolbarRequested || (!hasSuggestions && clipText == null && gesturePreview == null)

    override fun onDraw(canvas: Canvas) {
        val t = painter.theme ?: return
        val now = SystemClock.uptimeMillis()
        val fade = Motion.easeOutCubic(Motion.progress(changedAt, 160, now))
        if (fade < 1f) postInvalidateOnAnimation()
        val cy = height / 2f

        // Sparkle button: opens the toolbar.
        drawButton(canvas, Action.TOGGLE, buttonSize / 2f + context.dp(4f), cy, t.stripSecondary, toolbarRequested)

        val msg = message
        if (msg != null && now < messageUntil) {
            text.color = t.stripText
            text.typeface = painter.mediumTypeface
            text.textSize = context.dp(14.5f)
            drawEllipsized(canvas, msg, width / 2f, cy, width - 2 * buttonSize)
            return
        }
        message = null

        val alpha = 0.35f + 0.65f * fade
        when {
            gesturePreview != null -> {
                text.color = t.stripText
                text.typeface = painter.mediumTypeface
                text.textSize = context.dp(17f)
                drawEllipsized(canvas, gesturePreview!!, width / 2f, cy, width - 2 * buttonSize)
            }
            showingToolbar -> drawToolbar(canvas, t.stripSecondary, alpha)
            hasSuggestions -> drawSlots(canvas, alpha)
            clipText != null -> drawChip(canvas, alpha)
        }
    }

    private fun slotBounds(i: Int, out: RectF) {
        val left = buttonSize + context.dp(4f)
        val right = width - context.dp(4f)
        val w = (right - left) / 3f
        out.set(left + i * w, 0f, left + (i + 1) * w, height.toFloat())
    }

    private fun drawSlots(canvas: Canvas, alpha: Float) {
        val t = painter.theme ?: return
        for (i in 0..2) {
            slotBounds(i, rect)
            if (i > 0) {
                paint.color = t.divider
                val h = context.dp(18f)
                canvas.drawRect(rect.left - context.dp(0.5f), height / 2f - h / 2f, rect.left + context.dp(0.5f), height / 2f + h / 2f, paint)
            }
            val item = slots[i] ?: continue
            if (i == pressedSlot) {
                paint.color = Colors.withAlpha(t.stripText, 0.08f)
                rect.inset(context.dp(3f), context.dp(5f))
                canvas.drawRoundRect(rect, context.dp(12f), context.dp(12f), paint)
                slotBounds(i, rect)
            }
            text.color = Colors.fade(if (item.primary) t.stripText else Colors.lerp(t.stripText, t.stripSecondary, 0.35f), alpha)
            text.typeface = if (item.primary) painter.mediumTypeface else painter.labelTypeface
            text.isFakeBoldText = false
            text.textSize = context.dp(if (item.emoji) 21f else 16.5f)
            val shown = if (item.quoted) "“${item.text}”" else item.text
            drawEllipsized(canvas, shown, rect.centerX(), height / 2f, rect.width() - context.dp(10f))
        }
    }

    private val toolbarLeft get() = buttonSize + context.dp(12f)
    private val toolbarRight get() = width - buttonSize - context.dp(12f)

    /** Room for each everyday button. MORE keeps the last place, open or not. */
    private val toolbarStep get() = (toolbarRight - toolbarLeft) / everyday.size

    /** Room for each of the [extras], spread over the everyday buttons' places but MORE's. */
    private val extrasStep get() = (toolbarRight - toolbarStep - toolbarLeft) / extras.size

    private fun drawToolbar(canvas: Canvas, color: Int, alpha: Float) {
        val cy = height / 2f
        val faded = Colors.fade(color, alpha)
        // The buttons MORE swaps fade in afresh; MORE, lit up while open, and hide stay put.
        val swap = Motion.easeOutCubic(Motion.progress(swappedAt, 160))
        if (swap < 1f) postInvalidateOnAnimation()
        val swapped = Colors.fade(faded, 0.35f + 0.65f * swap)
        val shown = if (moreOpen) extras else everyday
        val step = if (moreOpen) extrasStep else toolbarStep
        for ((i, action) in shown.withIndex()) {
            if (action != Action.MORE) drawButton(canvas, action, toolbarLeft + step * (i + 0.5f), cy, swapped, false)
        }
        drawButton(canvas, Action.MORE, toolbarRight - toolbarStep / 2f, cy, faded, moreOpen)
        drawButton(canvas, Action.HIDE, width - buttonSize / 2f - context.dp(4f), cy, faded, false)
    }

    private fun drawButton(canvas: Canvas, action: Action, cx: Float, cy: Float, color: Int, active: Boolean) {
        val t = painter.theme ?: return
        if (active || pressedAction == action) {
            paint.color = Colors.withAlpha(t.stripText, if (active) 0.12f else 0.09f)
            canvas.drawCircle(cx, cy, context.dp(14f), paint)
        }
        val tint = if (action == Action.TOGGLE) Colors.lerp(color, t.accent, if (active) 0.9f else 0.55f) else color
        val icon = if (action == Action.MODE) modeIcon ?: action.icon else action.icon
        Icons.draw(canvas, icon, cx, cy, context.dp(22f), tint, paint)
    }

    private fun chipBounds(out: RectF) {
        val t = clipText ?: ""
        text.textSize = context.dp(14f)
        val textWidth = minOf(text.measureText(t), width * 0.55f)
        val w = textWidth + context.dp(52f)
        val left = buttonSize + context.dp(8f)
        out.set(left, height / 2f - context.dp(13f), left + w, height / 2f + context.dp(13f))
    }

    private fun drawChip(canvas: Canvas, alpha: Float) {
        val t = painter.theme ?: return
        val clip = clipText ?: return
        chipBounds(rect)
        paint.color = Colors.fade(Colors.withAlpha(t.stripText, if (pressedChip) 0.16f else 0.09f), alpha)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        Icons.draw(canvas, Icon.CLIPBOARD, rect.left + context.dp(19f), rect.centerY(), context.dp(17f), Colors.fade(t.accent, alpha), paint)
        text.color = Colors.fade(t.stripText, alpha)
        text.typeface = painter.labelTypeface
        text.textSize = context.dp(14f)
        text.textAlign = Paint.Align.LEFT
        val avail = rect.right - rect.left - context.dp(46f)
        val shown = TextUtils.ellipsize(clip.replace('\n', ' '), text, avail, TextUtils.TruncateAt.END).toString()
        text.getTextBounds("Hx", 0, 2, tmpBounds)
        canvas.drawText(shown, rect.left + context.dp(34f), rect.centerY() - tmpBounds.exactCenterY(), text)
        text.textAlign = Paint.Align.CENTER
    }

    private val tmpBounds = android.graphics.Rect()

    private fun drawEllipsized(canvas: Canvas, s: String, cx: Float, cy: Float, maxWidth: Float) {
        val shown = TextUtils.ellipsize(s, text, maxWidth, TextUtils.TruncateAt.END).toString()
        val probe = if (shown.any { Character.isSurrogate(it) }) shown else "Hx"
        text.getTextBounds(probe, 0, probe.length, tmpBounds)
        canvas.drawText(shown, cx, cy - tmpBounds.exactCenterY(), text)
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                hit(event.x, event.y)
                if (pressedSlot >= 0) postDelayed(longPress, 450)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.y < -context.dp(20f) || event.y > height + context.dp(20f)) cancelPress()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val slot = pressedSlot
                val action = pressedAction
                val chip = pressedChip
                cancelPress()
                when {
                    action != null -> {
                        when (action) {
                            Action.TOGGLE -> toggleToolbar()
                            Action.MORE -> toggleMore()
                            // Any other button takes you elsewhere; the everyday buttons are back after.
                            else -> if (moreOpen) toggleMore()
                        }
                        listener?.onAction(action)
                    }
                    chip -> clipText?.let { listener?.onPasteChip(it) }
                    slot >= 0 -> slots[slot]?.let { listener?.onSuggestion(slot, it) }
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelPress()
        }
        return true
    }

    private fun cancelPress() {
        removeCallbacks(longPress)
        pressedSlot = -1
        pressedAction = null
        pressedChip = false
        invalidate()
    }

    private fun hit(x: Float, y: Float) {
        pressedSlot = -1
        pressedAction = null
        pressedChip = false
        if (x < buttonSize + context.dp(4f)) { pressedAction = Action.TOGGLE; return }
        when {
            gesturePreview != null -> Unit
            showingToolbar -> pressedAction = when {
                x > width - buttonSize - context.dp(4f) -> Action.HIDE
                !moreOpen -> everyday.getOrNull(((x - toolbarLeft) / toolbarStep).toInt())
                x >= toolbarRight - toolbarStep -> Action.MORE
                else -> extras.getOrNull(((x - toolbarLeft) / extrasStep).toInt())
            }
            hasSuggestions -> {
                for (i in 0..2) {
                    slotBounds(i, rect)
                    if (x >= rect.left && x < rect.right && slots[i] != null) pressedSlot = i
                }
            }
            clipText != null -> {
                chipBounds(rect)
                pressedChip = rect.contains(x, y) || x < rect.right
            }
        }
    }
}
