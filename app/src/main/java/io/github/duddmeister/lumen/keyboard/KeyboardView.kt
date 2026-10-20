package io.github.duddmeister.lumen.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.duddmeister.lumen.theme.KeyboardTheme
import io.github.duddmeister.lumen.ui.Colors
import io.github.duddmeister.lumen.ui.Motion
import io.github.duddmeister.lumen.ui.dp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The keyboard surface: draws keys with Lumen's light effects and turns touches
 * into key events, long-press alternatives, flicks, cursor swipes and
 * swipe-to-delete. All text semantics live in the IME; this view only reports
 * what the fingers did.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, val painter: KeyPainter) : View(context) {

    interface Listener {
        /** A finger landed on [key]. Shift acts here; everything else just gives feedback. */
        fun onKeyDown(key: Key)
        /** A key was tapped (finger lifted on it). [x],[y] is where it was touched. */
        fun onKeyTap(key: Key, x: Float, y: Float)
        /** The finger left shift, or left delete after it auto-repeated. */
        fun onKeyRelease(key: Key)
        /** Delete is being held. */
        fun onKeyRepeat(key: Key)
        /** An alternative was chosen from a long-press panel. */
        fun onAlternative(key: Key, text: String)
        /** A long press the IME wants to handle itself; return true to consume it. */
        fun onLongPress(key: Key): Boolean
        /** The long-press panel opened (for haptic feedback). */
        fun onPanelOpened()
        /** A sideways swipe across the keys, once the finger lifts: [direction] is -1 for left, 1 for right. */
        fun onCursorSwipe(direction: Int)
        /** Swipe left on delete selects [words] words; [done] once the finger lifts. */
        fun onDeleteSwipe(words: Int, done: Boolean)
    }

    var listener: Listener? = null
    var overlay: View? = null

    var layout: KeyboardLayout? = null
        private set
    var shifted = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var shiftIcon: Icon = Icon.SHIFT
        set(value) { if (field != value) { field = value; invalidate() } }
    var enterIcon: Icon = Icon.ENTER
        set(value) { if (field != value) { field = value; invalidate() } }
    var spaceLabel: String? = null
        set(value) { field = value; invalidate() }

    var previewsEnabled = true
    /** Sideways swipes anywhere on the keys move the cursor. */
    var cursorSwipesEnabled = true
    var deleteSwipeEnabled = true
    /** A quick flick down on a key types the symbol in its corner. */
    var flickEnabled = true
    var lightEffects = true
    var hintsVisible = true
    var longPressDelay = 300L
    /** Row height in dp before the user's height preference. */
    var rowHeightDp = 54f
        set(value) { if (field != value) { field = value; requestLayout() } }
    var heightScale = 1f
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val sidePadding = context.dp(2f)
    private val topPadding = 0f
    private val bottomPadding = context.dp(6f)
    private val gapX = context.dp(3f)
    private val gapY = context.dp(6f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    /** When a character or space was last tapped. */
    private var typedAt = 0L

    private enum class Mode { PRESS, PANEL, SWIPE, DELETE_SWIPE, FLICK, DONE }

    private inner class Pointer(val id: Int) : Runnable {
        var key: Key? = null
        var downX = 0f
        var downY = 0f
        var x = 0f
        var y = 0f
        var mode = Mode.PRESS
        var panel: PopupPanel? = null
        var words = 0
        /** The most words a delete swipe has reached. */
        var reached = 0
        var repeats = 0
        var moved = false
        /** Slid off the key it came down on, to the one it's on now. */
        var switched = false
        /** Went past a flick: from here on it's a slide. */
        var flickOff = false
        /** Where and when the finger came down, kept when a slide takes it to another key. */
        var startX = 0f
        var startY = 0f
        var downAt = 0L
        /** Came down while the user was tapping keys: a swipe has to be unmistakable. */
        var afterTyping = false

        /** Long press and key repeat share one timer. */
        override fun run() {
            val k = key ?: return
            if (mode != Mode.PRESS) return
            if (k.code == KeyCodes.DELETE) {
                listener?.onKeyRepeat(k)
                repeats++
                postDelayed(this, (REPEAT_INTERVAL_MS - repeats * 2).coerceAtLeast(REPEAT_MIN_MS))
                return
            }
            if (listener?.onLongPress(k) == true) {
                mode = Mode.DONE
                releaseVisual(k)
                return
            }
            if (k.popup.isNotEmpty()) openPanel(this, k)
        }
    }

    private val pointers = SparseArray<Pointer>()

    private class Bloom(val x: Float, val y: Float, val start: Long)
    private val blooms = ArrayList<Bloom>()

    private class Preview(val key: Key, val label: String, val shownAt: Long) {
        var hiddenAt = 0L
    }
    private val previews = ArrayList<Preview>()
    private val panels = ArrayList<PopupPanel>()

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tmp = RectF()

    val theme: KeyboardTheme? get() = painter.theme

    fun setKeyboard(newLayout: KeyboardLayout) {
        cancelTouches()
        layout = newLayout
        requestLayout()
        invalidate()
    }

    fun applyTheme(theme: KeyboardTheme) {
        painter.theme = theme
        invalidate()
        overlay?.invalidate()
    }

    /** Empty room below the bottom row's keys: its half of the gap between rows, and the padding. */
    val bottomMargin: Int get() = (bottomPadding + gapY / 2f).toInt()

    /** Height the keyboard needs for [l] (used to size panels consistently). */
    fun heightFor(l: KeyboardLayout): Int = l.totalHeight(rowHeightPx(), topPadding, bottomPadding)

    private fun rowHeightPx() = context.dp(rowHeightDp) * heightScale

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val l = layout
        val height = if (l == null) 0 else heightFor(l)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        layoutKeys()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Swipes from keys by the screen's edges (delete, and the letters at either end) stay
        // with the keys. Android's back gesture would take a quick one, letting a touch through
        // only once it had been held still for a moment. A keyboard may claim its full height.
        if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    private fun layoutKeys() {
        val l = layout ?: return
        val w = width.toFloat()
        // Number and phone pads stay compact and centred on wide screens.
        val pad = l.mode == KeyboardMode.NUMBER || l.mode == KeyboardMode.PHONE
        val side = if (pad) maxOf(sidePadding, (w - context.dp(PAD_MAX_WIDTH_DP)) / 2f) else sidePadding
        // Split halves keep thumb-sized keys near the edges; spare width opens between them.
        val splitUnit = if (l.isSplit) {
            (w / l.splitUnitsAcross).coerceIn(context.dp(SPLIT_KEY_MIN_DP), context.dp(SPLIT_KEY_MAX_DP))
        } else 0f
        l.layout(width, rowHeightPx(), side, topPadding, gapX, gapY, splitUnit)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        if (l.width != width) layoutKeys()
        val now = SystemClock.uptimeMillis()
        var animating = false

        if (lightEffects && blooms.isNotEmpty()) {
            val iterator = blooms.iterator()
            while (iterator.hasNext()) {
                val b = iterator.next()
                val p = Motion.progress(b.start, BLOOM_MS, now)
                if (p >= 1f) { iterator.remove(); continue }
                val radius = l.keyWidth * (1.1f + 0.9f * Motion.easeOutCubic(p))
                painter.drawBloom(canvas, b.x, b.y, radius, 1f - Motion.easeInOutCubic(p))
                animating = true
            }
        }

        val labelledSpace = l.keys.firstOrNull { it.code == KeyCodes.SPACE }
        // A cursor swipe shows its arrows on the space bar, wherever on the keys it began.
        val cursorKey = if (activeKey(Mode.SWIPE) != null) labelledSpace else null
        val deleteKey = activeKey(Mode.DELETE_SWIPE)
        for (key in l.keys) {
            val press = pressAmount(key, now)
            if ((key.pressed && now - key.pressedAt < PRESS_MS) || key.releasedAt > 0) animating = true
            val icon = when {
                key.code == KeyCodes.SHIFT -> shiftIcon
                key.code == KeyCodes.ENTER -> enterIcon
                else -> key.icon
            }
            if (key === cursorKey) {
                painter.drawKey(canvas, key, 1f, shifted, icon = null, spaceLabel = null)
                val t = theme ?: continue
                val s = key.bounds.height() * 0.42f
                Icons.draw(canvas, Icon.ARROW_LEFT, key.bounds.centerX() - s, key.bounds.centerY(), s, t.label, overlayPaint)
                Icons.draw(canvas, Icon.ARROW_RIGHT, key.bounds.centerX() + s, key.bounds.centerY(), s, t.label, overlayPaint)
                continue
            }
            painter.drawKey(
                canvas, key, if (key === deleteKey) 1f else press, shifted, icon = icon,
                showHint = hintsVisible, spaceLabel = if (key === labelledSpace) spaceLabel else null,
            )
        }

        if (animating) postInvalidateOnAnimation()
        if (previews.isNotEmpty() || panels.isNotEmpty()) overlay?.invalidate()
    }

    /** Animated press amount: quick dip on touch, springy return on release. */
    private fun pressAmount(key: Key, now: Long): Float = when {
        key.pressed -> Motion.easeOutCubic(Motion.progress(key.pressedAt, PRESS_MS, now))
        key.releasedAt > 0 -> {
            val p = Motion.progress(key.releasedAt, RELEASE_MS, now)
            if (p >= 1f) { key.releasedAt = 0; 0f } else 1f - Motion.easeOutBack(p, 1.2f).coerceAtMost(1.15f)
        }
        else -> 0f
    }.coerceIn(-0.25f, 1f)

    /**
     * Draws key previews and long-press panels into the overlay that spans the
     * suggestion strip, offset by ([dx], [dy]). Returns true while animating.
     */
    fun drawOverlay(canvas: Canvas, dx: Float, dy: Float): Boolean {
        val t = theme ?: return false
        val l = layout ?: return false
        val now = SystemClock.uptimeMillis()
        var animating = false
        canvas.save()
        canvas.translate(dx, dy)
        val iterator = previews.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            val show = Motion.easeOutCubic(Motion.progress(p.shownAt, PREVIEW_IN_MS, now))
            val hide = if (p.hiddenAt > 0) Motion.progress(p.hiddenAt, PREVIEW_OUT_MS, now) else 0f
            if (hide >= 1f) { iterator.remove(); continue }
            if (show < 1f || p.hiddenAt > 0) animating = true
            drawPreview(canvas, t, l, p, show, 1f - hide, -dy)
        }
        for (panel in panels) {
            val open = Motion.easeOutCubic(Motion.progress(panel.openedAt, PANEL_IN_MS, now))
            if (open < 1f) animating = true
            drawPanel(canvas, t, panel, open)
        }
        canvas.restore()
        return animating
    }

    private fun drawPreview(canvas: Canvas, t: KeyboardTheme, l: KeyboardLayout, p: Preview, show: Float, alpha: Float, minTop: Float) {
        val b = p.key.bounds
        val w = maxOf(b.width() + context.dp(12f), l.keyWidth * 1.3f)
        val bottom = b.top + b.height() * 0.1f
        // Over the top row there's less room above the key: a shorter bubble, rather than covering more of it.
        val h = minOf(b.height() * 1.28f, maxOf(b.height(), bottom - minTop - context.dp(2f)))
        var top = bottom - h
        if (top < minTop + context.dp(2f)) top = minTop + context.dp(2f)
        val left = (b.centerX() - w / 2f).coerceIn(context.dp(2f), width - w - context.dp(2f))
        val scale = 0.86f + 0.14f * show
        tmp.set(left, top, left + w, top + h)
        canvas.save()
        canvas.scale(scale, scale, tmp.centerX(), tmp.bottom)
        val radius = context.dp(12f)
        drawSoftShadow(canvas, tmp, radius, alpha * show)
        overlayPaint.color = Colors.fade(Colors.opaque(t.popup), alpha)
        canvas.drawRoundRect(tmp, radius, radius, overlayPaint)
        overlayText.color = Colors.fade(t.popupLabel, alpha)
        overlayText.typeface = painter.labelTypeface
        overlayText.textSize = minOf(h * 0.5f, w * 0.7f)
        painter.drawCentered(canvas, p.label, tmp.centerX(), tmp.centerY() - h * 0.02f, useOwnBounds = !p.key.isLetter && p.label != "_", paint = overlayText)
        canvas.restore()
    }

    private fun drawPanel(canvas: Canvas, t: KeyboardTheme, panel: PopupPanel, open: Float) {
        val radius = context.dp(14f)
        canvas.save()
        val scale = 0.9f + 0.1f * open
        canvas.scale(scale, scale, panel.key.bounds.centerX(), panel.bounds.bottom)
        drawSoftShadow(canvas, panel.bounds, radius, open)
        overlayPaint.color = Colors.fade(Colors.opaque(t.popup), open)
        canvas.drawRoundRect(panel.bounds, radius, radius, overlayPaint)
        overlayText.typeface = painter.labelTypeface
        for ((i, cell) in panel.cells.withIndex()) {
            val text = panel.options[i]
            val selected = i == panel.selected
            if (selected) {
                tmp.set(cell)
                tmp.inset(context.dp(2f), context.dp(3f))
                overlayPaint.color = Colors.fade(t.accent, open)
                canvas.drawRoundRect(tmp, context.dp(9f), context.dp(9f), overlayPaint)
            }
            overlayText.color = Colors.fade(if (selected) t.onAccent else t.popupLabel, open)
            overlayText.textSize = if (text.length > 2) cell.height() * 0.32f else minOf(cell.height() * 0.46f, cell.width() * 0.6f)
            val shown = if (shifted && panel.key.isLetter) text.uppercase() else text
            painter.drawCentered(canvas, shown, cell.centerX(), cell.centerY(), useOwnBounds = shown != "_", paint = overlayText)
        }
        canvas.restore()
    }

    /** Layered translucent shapes read as a soft shadow and stay cheap on every Android version. */
    private fun drawSoftShadow(canvas: Canvas, r: RectF, radius: Float, strength: Float) {
        val dark = theme?.dark ?: true
        val base = if (dark) 0.22f else 0.10f
        for (i in 1..3) {
            val spread = context.dp(1.6f) * i
            tmp.set(r.left - spread, r.top - spread + context.dp(2f) * i, r.right + spread, r.bottom + spread + context.dp(2f) * i)
            overlayPaint.color = Colors.withAlpha(0xFF000000.toInt(), base * strength / (i + 0.5f))
            canvas.drawRoundRect(tmp, radius + spread, radius + spread, overlayPaint)
        }
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = layout ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                down(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val p = pointers[event.getPointerId(i)] ?: continue
                    move(l, p, event.getX(i), event.getY(i), event.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                pointers[event.getPointerId(i)]?.let { up(it, event.getX(i), event.getY(i), event.eventTime) }
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float, time: Long) {
        val held = ArrayList<Pointer>()
        for (i in 0 until pointers.size()) {
            val other = pointers.valueAt(i)
            val k = other.key
            // A key part way into a flick counts too: the thumb was rolling off it, not flicking.
            val pressing = other.mode == Mode.PRESS || other.mode == Mode.FLICK
            if (pressing && k != null && (k.isCharacter || k.code == KeyCodes.SPACE)) held.add(other)
        }
        // Rollover: a new finger commits keys that are still held, so fast typing keeps its order.
        for (other in held) {
            val k = other.key ?: continue
            removeCallbacks(other)
            other.mode = Mode.DONE
            releaseVisual(k)
            listener?.onKeyTap(k, other.downX, other.downY)
            typedAt = time
        }
        // Committing may have switched layouts (e.g. space returning from symbols), so look up afresh.
        val key = layout?.keyAt(x, y) ?: return
        val p = Pointer(id)
        p.key = key
        p.downX = x; p.downY = y; p.x = x; p.y = y
        p.startX = x; p.startY = y; p.downAt = time
        p.afterTyping = time - typedAt < FAST_TYPING_MS
        pointers.put(id, p)
        pressVisual(key, x, y)
        listener?.onKeyDown(key)
        when {
            key.code == KeyCodes.DELETE -> postDelayed(p, REPEAT_DELAY_MS)
            key.popup.isNotEmpty() || key.code == KeyCodes.SPACE || key.code == KeyCodes.SHIFT ||
                key.code == KeyCodes.EMOJI -> postDelayed(p, longPressDelay)
        }
    }

    private fun move(l: KeyboardLayout, p: Pointer, x: Float, y: Float, time: Long) {
        p.x = x; p.y = y
        val key = p.key ?: return
        when (p.mode) {
            Mode.PRESS -> {
                val dx = x - p.downX
                val dy = y - p.downY
                val distance = hypot(dx, dy)
                if (distance > touchSlop) p.moved = true
                // Heading down a key with a symbol in its corner: a flick, until it goes too far.
                val flickShaped = flickEnabled && key.hint != null && !p.flickOff && dy > 0f && abs(dx) < dy * FLICK_SLANT
                // Heading sideways: a cursor swipe, once it goes far enough.
                val sx = x - p.startX
                val swipeShaped = cursorSwipesEnabled && pointers.size() == 1 &&
                    key.code != KeyCodes.SHIFT && key.code != KeyCodes.DELETE && abs(y - p.startY) < abs(sx) * SWIPE_SLANT
                when {
                    key.code == KeyCodes.SPACE && cursorSwipesEnabled && abs(dx) > touchSlop * 1.5f -> {
                        // A swipe, not a space: it acts once, when the finger lifts.
                        removeCallbacks(p)
                        p.mode = Mode.SWIPE
                        invalidate()
                    }
                    key.code == KeyCodes.DELETE && deleteSwipeEnabled && dx < -touchSlop * 2f -> {
                        removeCallbacks(p)
                        p.mode = Mode.DELETE_SWIPE
                        p.words = 0
                        p.reached = 0
                        selectWords(l, p, x)
                    }
                    flickShaped && dy > flickStart(key) -> {
                        // Flicking down for the symbol in the corner; the preview shows it.
                        removeCallbacks(p)
                        p.mode = Mode.FLICK
                        relabelPreview(key, key.hint)
                    }
                    swipeShaped && abs(sx) > swipeStart(l, p, time) -> {
                        // Not a key after all: the swipe acts once, when the finger lifts.
                        removeCallbacks(p)
                        hidePreview(key)
                        releaseVisual(key)
                        p.mode = Mode.SWIPE
                        invalidate()
                    }
                    // Sideways, the finger is on its way to a swipe rather than to another key.
                    p.moved && key.isCharacter && !swipeShaped -> {
                        val under = l.keyAt(x, y)
                        // A thumb rolling over the edge as it lifts stays on its key.
                        if (under != null && under !== key && under.isCharacter && wellInside(under, x, y)) {
                            // Sliding: follow the finger to the key it rests on.
                            removeCallbacks(p)
                            hidePreview(key)
                            releaseVisual(key)
                            p.key = under
                            p.downX = x; p.downY = y
                            p.switched = true
                            pressVisual(under, x, y)
                            listener?.onKeyDown(under)
                            if (under.popup.isNotEmpty()) postDelayed(p, longPressDelay)
                        }
                    }
                }
            }
            Mode.PANEL -> if (p.panel?.select(x, y) == true) overlay?.invalidate()
            Mode.SWIPE -> Unit
            Mode.FLICK -> {
                val dx = x - p.downX
                val dy = y - p.downY
                if (dy > key.bounds.height() * FLICK_REACH || abs(dx) > maxOf(l.keyWidth * 0.8f, dy * FLICK_SLANT * 1.5f)) {
                    // Too far, or too sideways, for a flick: a slide to another key.
                    p.mode = Mode.PRESS
                    p.moved = true
                    p.flickOff = true
                    relabelPreview(key, keyLabel(key))
                    invalidate()
                } else {
                    // Back above the line, the letter again.
                    relabelPreview(key, if (dy > flickStart(key)) key.hint ?: keyLabel(key) else keyLabel(key))
                }
            }
            Mode.DELETE_SWIPE -> selectWords(l, p, x)
            Mode.DONE -> Unit
        }
    }

    /** A delete swipe selects a word for each [DELETE_WORD_KEYS] key widths it has come left of where it landed. */
    private fun selectWords(l: KeyboardLayout, p: Pointer, x: Float) {
        val words = ((p.downX - x) / (l.keyWidth * DELETE_WORD_KEYS)).toInt().coerceAtLeast(0)
        if (words == p.words) return
        p.words = words
        p.reached = maxOf(p.reached, words)
        listener?.onDeleteSwipe(words, false)
    }

    private fun up(p: Pointer, x: Float, y: Float, time: Long) {
        // A quick flick can outrun the moves: where it lifts counts as one more (it may make a
        // tap a swipe, or reach another word to delete).
        val l = layout
        val moving = p.mode == Mode.PRESS || p.mode == Mode.DELETE_SWIPE
        if (moving && l != null && (x != p.x || y != p.y)) move(l, p, x, y, time)
        removeCallbacks(p)
        pointers.remove(p.id)
        val key = p.key ?: return
        if (key.code == KeyCodes.SHIFT) {
            releaseVisual(key)
            listener?.onKeyRelease(key)
            return
        }
        when (p.mode) {
            Mode.PRESS -> {
                releaseVisual(key)
                when {
                    key.code == KeyCodes.DELETE && p.repeats > 0 -> listener?.onKeyRelease(key)
                    // Taps use where the finger landed; slides to another key, where it came to rest.
                    p.switched -> listener?.onKeyTap(key, x, y)
                    else -> listener?.onKeyTap(key, p.downX, p.downY)
                }
                if (key.isCharacter || key.code == KeyCodes.SPACE) typedAt = time
            }
            Mode.PANEL -> {
                val panel = p.panel
                panels.remove(panel)
                releaseVisual(key)
                if (panel != null) listener?.onAlternative(key, panel.selectedText)
                overlay?.invalidate()
            }
            Mode.SWIPE -> {
                releaseVisual(key)
                // One word, however far or slowly it went; drifting back first cancels it.
                val dx = x - p.startX
                val keyWidth = layout?.keyWidth ?: 0f
                val reach = if (key.code == KeyCodes.SPACE) maxOf(touchSlop * 1.5f, keyWidth * SWIPE_MIN_KEYS) else keyWidth * SWIPE_KEEP_KEYS
                if (abs(dx) >= reach) listener?.onCursorSwipe(if (dx > 0) 1 else -1)
            }
            Mode.DELETE_SWIPE -> {
                releaseVisual(key)
                when {
                    p.reached > 0 -> listener?.onDeleteSwipe(p.words, true)
                    // Never far enough for a word: a tap that slid, deleting a letter as a tap does.
                    p.repeats > 0 -> listener?.onKeyRelease(key)
                    else -> listener?.onKeyTap(key, p.downX, p.downY)
                }
            }
            Mode.FLICK -> {
                releaseVisual(key)
                val hint = key.hint
                // Lifted below the line: the symbol. Brought back up first: the letter after all.
                if (hint != null && y - p.downY > flickStart(key)) listener?.onAlternative(key, hint)
                else listener?.onKeyTap(key, p.downX, p.downY)
                typedAt = time
            }
            Mode.DONE -> releaseVisual(key)
        }
    }

    /** Whether ([x], [y]) is well inside [key], not just over its edge. */
    private fun wellInside(key: Key, x: Float, y: Float): Boolean {
        val b = key.bounds
        val insetX = b.width() * SLIDE_INSET
        val insetY = b.height() * SLIDE_INSET
        return x > b.left + insetX && x < b.right - insetX && y > b.top + insetY && y < b.bottom - insetY
    }

    /** How far down a flick has to go before it counts. */
    private fun flickStart(key: Key) = maxOf(touchSlop, key.bounds.height() * FLICK_START)

    /**
     * How far sideways a finger must go from where it landed for a cursor swipe: further than a
     * thumb rolls as it lifts, and just after a tap, while the user is typing, further still,
     * coming down to the usual over half a second.
     */
    private fun swipeStart(l: KeyboardLayout, p: Pointer, time: Long): Float {
        val near = l.keyWidth * SWIPE_START_KEYS
        val held = time - p.downAt
        if (!p.afterTyping || held >= SWIPE_SETTLE_MS) return near
        val far = l.keyWidth * SWIPE_AFTER_TYPING_KEYS
        return far - (far - near) * held / SWIPE_SETTLE_MS
    }

    private fun keyLabel(key: Key) = if (shifted && key.isLetter) key.label.uppercase() else key.label

    /** Shows [label] in [key]'s preview bubble, if it has one up. */
    private fun relabelPreview(key: Key, label: String) {
        val i = previews.indexOfFirst { it.key === key && it.hiddenAt == 0L }
        if (i < 0 || previews[i].label == label) return
        previews[i] = Preview(key, label, previews[i].shownAt)
        overlay?.invalidate()
    }

    private fun openPanel(p: Pointer, key: Key) {
        val l = layout ?: return
        val panel = PopupPanel(key, key.popup)
        val longest = key.popup.maxOf { it.length }
        val cellWidth = if (longest > 2) l.keyWidth * 1.5f else maxOf(l.keyWidth, context.dp(34f))
        val overlayTop = -((overlay?.height ?: height) - height).toFloat()
        panel.layout(width.toFloat(), cellWidth, key.bounds.height() * 0.95f, context.dp(6f), overlayTop + context.dp(2f), 10)
        panel.openedAt = SystemClock.uptimeMillis()
        panel.select(p.x, panel.bounds.bottom - 1f)
        p.panel = panel
        p.mode = Mode.PANEL
        panels.add(panel)
        hidePreview(key)
        listener?.onPanelOpened()
        overlay?.invalidate()
    }

    private fun pressVisual(key: Key, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        key.pressed = true
        key.pressedAt = now
        key.releasedAt = 0
        if (lightEffects) {
            blooms.add(Bloom(x, y, now))
            if (blooms.size > 8) blooms.removeAt(0)
        }
        if (previewsEnabled && key.preview) {
            val label = keyLabel(key)
            previews.removeAll { it.key === key }
            previews.add(Preview(key, label, now))
            overlay?.invalidate()
        }
        invalidate()
    }

    private fun releaseVisual(key: Key) {
        if (key.pressed) {
            key.pressed = false
            key.releasedAt = SystemClock.uptimeMillis()
        }
        hidePreview(key)
        invalidate()
    }

    private fun hidePreview(key: Key) {
        val now = SystemClock.uptimeMillis()
        for (p in previews) if (p.key === key && p.hiddenAt == 0L) p.hiddenAt = now
        overlay?.invalidate()
    }

    private fun activeKey(mode: Mode): Key? {
        for (i in 0 until pointers.size()) {
            val p = pointers.valueAt(i)
            if (p.mode == mode) return p.key
        }
        return null
    }

    /** Drops every touch in progress, e.g. when the layout changes or the keyboard hides. */
    fun cancelTouches() {
        for (i in 0 until pointers.size()) {
            val p = pointers.valueAt(i)
            removeCallbacks(p)
            p.key?.let { it.pressed = false; it.releasedAt = 0 }
            if (p.mode == Mode.DELETE_SWIPE) listener?.onDeleteSwipe(0, true)
        }
        pointers.clear()
        panels.clear()
        previews.clear()
        invalidate()
        overlay?.invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val PRESS_MS = 70L
        private const val RELEASE_MS = 220L
        private const val BLOOM_MS = 520L
        private const val PREVIEW_IN_MS = 70L
        private const val PREVIEW_OUT_MS = 90L
        private const val PANEL_IN_MS = 120L
        private const val REPEAT_DELAY_MS = 380L
        private const val REPEAT_INTERVAL_MS = 70L
        private const val REPEAT_MIN_MS = 35L
        /** How far, in key widths, a space-bar swipe must end from where it began to count. */
        internal const val SWIPE_MIN_KEYS = 0.4f
        /** A cursor swipe from any other key starts this many key widths sideways... */
        internal const val SWIPE_START_KEYS = 1.2f
        /** ...or this many just after a tap, coming down to the usual over [SWIPE_SETTLE_MS]... */
        private const val SWIPE_AFTER_TYPING_KEYS = 1.6f
        private const val SWIPE_SETTLE_MS = 450L
        /** ...and counts if it ends at least this many from where it began. */
        private const val SWIPE_KEEP_KEYS = 0.8f
        /** A cursor swipe may lean this far up or down for each step sideways. */
        private const val SWIPE_SLANT = 0.5f
        /** A delete swipe selects another word for each this many key widths it goes left. */
        internal const val DELETE_WORD_KEYS = 0.7f
        /**
         * A flick down counts past this share of the key's height (about 4 mm on a phone)...
         * A thumb typing fast often rolls a couple of millimetres down a key as it lifts, and
         * at 0.3 that typed ")" in the middle of "full".
         */
        private const val FLICK_START = 0.6f
        /** ...and stops being a flick past this many key heights. */
        private const val FLICK_REACH = 1.4f
        /** A flick may lean this far sideways for each step down. */
        private const val FLICK_SLANT = 0.6f
        /** A finger down this soon after a tap is typing, not starting a swipe. */
        private const val FAST_TYPING_MS = 350L
        /** A slide moves the press to another key once this share of that key's size inside it. */
        private const val SLIDE_INSET = 0.2f
        private const val PAD_MAX_WIDTH_DP = 520f
        /** Limits on a split key's width, its gap included. */
        private const val SPLIT_KEY_MIN_DP = 40f
        private const val SPLIT_KEY_MAX_DP = 64f
    }
}
