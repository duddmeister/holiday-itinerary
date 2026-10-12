package io.github.duddmeister.lumen.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * into key events, long-press alternatives, glide gestures, space-bar swipes
 * control and swipe-to-delete. All text semantics live in the IME; this view
 * only reports what the fingers did.
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
        /** A swipe along the space bar, once the finger lifts: [direction] is -1 for left, 1 for right. */
        fun onSpaceSwipe(direction: Int)
        /** Swipe left on delete selects [words] words; [done] once the finger lifts. */
        fun onDeleteSwipe(words: Int, done: Boolean)
        fun onGestureStart()
        fun onGestureMove(trail: GestureTrail)
        fun onGestureEnd(trail: GestureTrail)
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
    var gesturesEnabled = true
    var trailEnabled = true
    var spaceSwipesEnabled = true
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

    private val sidePadding = context.dp(4f)
    private val topPadding = context.dp(5f)
    private val bottomPadding = context.dp(6f)
    private val gapX = context.dp(5.5f)
    private val gapY = context.dp(9f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    /** When a character or space was last tapped. */
    private var typedAt = 0L

    private enum class Mode { PRESS, PANEL, GESTURE, SPACE_SWIPE, DELETE_SWIPE, FLICK, DONE }

    private inner class Pointer(val id: Int) : Runnable {
        var key: Key? = null
        var downX = 0f
        var downY = 0f
        var x = 0f
        var y = 0f
        var mode = Mode.PRESS
        var panel: PopupPanel? = null
        var anchorX = 0f
        var words = 0
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
        /** Came down while the user was tapping keys: a glide has to be unmistakable. */
        var afterTyping = false
        /** How far the finger has travelled, leaving out the jitter of a finger at rest. */
        var travel = 0f
        var travelX = 0f
        var travelY = 0f
        /** The path so far, for a glide only recognised part way along. */
        var pathX = FloatArray(16)
        var pathY = FloatArray(16)
        var pathT = LongArray(16)
        var pathCount = 0

        fun record(x: Float, y: Float, time: Long) {
            if (pathCount == pathX.size) {
                pathX = pathX.copyOf(pathCount * 2); pathY = pathY.copyOf(pathCount * 2); pathT = pathT.copyOf(pathCount * 2)
            }
            pathX[pathCount] = x; pathY[pathCount] = y; pathT[pathCount] = time
            pathCount++
        }

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
    private val trail = GestureTrail()
    private var lastGestureNotify = 0L

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

        val cursorKey = activeKey(Mode.SPACE_SWIPE)
        val deleteKey = activeKey(Mode.DELETE_SWIPE)
        val labelledSpace = l.keys.firstOrNull { it.code == KeyCodes.SPACE }
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

        if (trailEnabled && trail.count > 1) {
            val t = theme
            if (t != null && trail.draw(canvas, now, context.dp(5.5f), t.trailStart, t.trailEnd)) animating = true
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
        val h = b.height() * 1.28f
        var top = b.top + b.height() * 0.1f - h
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
                    if (p.mode == Mode.GESTURE) {
                        for (h in 0 until event.historySize) {
                            trail.add(event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalEventTime(h))
                        }
                    }
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
            if (other.mode == Mode.GESTURE) return // one finger at a time while gliding
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
        p.travelX = x; p.travelY = y
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
                val step = hypot(x - p.travelX, y - p.travelY)
                if (step >= touchSlop / 3f) {
                    p.travel += step
                    p.travelX = x; p.travelY = y
                }
                val glideable = gesturesEnabled && key.isLetter && pointers.size() == 1
                if (glideable) p.record(x, y, time)
                // Heading down a key with a symbol in its corner: a flick, until it goes too far.
                val flickShaped = flickEnabled && key.hint != null && !p.flickOff && dy > 0f && abs(dx) < dy * FLICK_SLANT
                when {
                    key.code == KeyCodes.SPACE && spaceSwipesEnabled && abs(dx) > touchSlop * 1.5f -> {
                        // A swipe, not a space: it acts once, when the finger lifts.
                        removeCallbacks(p)
                        p.mode = Mode.SPACE_SWIPE
                        invalidate()
                    }
                    key.code == KeyCodes.DELETE && deleteSwipeEnabled && dx < -touchSlop * 2f -> {
                        removeCallbacks(p)
                        p.mode = Mode.DELETE_SWIPE
                        p.anchorX = p.downX - touchSlop
                        p.words = 0
                    }
                    flickShaped && dy > flickStart(key) -> {
                        // Flicking down for the symbol in the corner; the preview shows it.
                        removeCallbacks(p)
                        p.mode = Mode.FLICK
                        relabelPreview(key, key.hint)
                    }
                    glideable && !flickShaped && p.travel > glideStart(l, p, time) -> {
                        removeCallbacks(p)
                        p.mode = Mode.GESTURE
                        hidePreview(key)
                        releaseVisual(key)
                        trail.start(p.startX, p.startY, p.downAt)
                        for (i in 0 until p.pathCount) trail.add(p.pathX[i], p.pathY[i], p.pathT[i])
                        lastGestureNotify = time
                        listener?.onGestureStart()
                        invalidate()
                    }
                    p.moved && key.isCharacter -> {
                        val under = l.keyAt(x, y)
                        // A thumb rolling over the edge as it lifts stays on its key.
                        if (under != null && under !== key && under.isCharacter && wellInside(under, x, y)) {
                            // Sliding without glide typing: follow the finger to the key it rests on.
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
            Mode.GESTURE -> {
                trail.add(x, y, time)
                if (time - lastGestureNotify > GESTURE_NOTIFY_MS) {
                    lastGestureNotify = time
                    listener?.onGestureMove(trail)
                }
                invalidate()
            }
            Mode.SPACE_SWIPE -> Unit
            Mode.FLICK -> {
                val dx = x - p.downX
                val dy = y - p.downY
                if (gesturesEnabled && key.isLetter && pointers.size() == 1) p.record(x, y, time)
                if (dy > key.bounds.height() * FLICK_REACH || abs(dx) > maxOf(l.keyWidth * 0.8f, dy * FLICK_SLANT * 1.5f)) {
                    // Too far, or too sideways, for a flick: a glide, or a slide to another key.
                    if (gesturesEnabled && key.isLetter && pointers.size() == 1) {
                        p.mode = Mode.GESTURE
                        hidePreview(key)
                        releaseVisual(key)
                        trail.start(p.startX, p.startY, p.downAt)
                        for (i in 0 until p.pathCount) trail.add(p.pathX[i], p.pathY[i], p.pathT[i])
                        trail.add(x, y, time)
                        lastGestureNotify = time
                        listener?.onGestureStart()
                    } else {
                        p.mode = Mode.PRESS
                        p.moved = true
                        p.flickOff = true
                        relabelPreview(key, keyLabel(key))
                    }
                    invalidate()
                } else {
                    // Back above the line, the letter again.
                    relabelPreview(key, if (dy > flickStart(key)) key.hint ?: keyLabel(key) else keyLabel(key))
                }
            }
            Mode.DELETE_SWIPE -> {
                val words = ((p.anchorX - x) / (l.keyWidth * 0.9f)).toInt().coerceAtLeast(0)
                if (words != p.words) {
                    p.words = words
                    listener?.onDeleteSwipe(words, false)
                }
            }
            Mode.DONE -> Unit
        }
    }

    private fun up(p: Pointer, x: Float, y: Float, time: Long) {
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
            Mode.GESTURE -> {
                trail.add(x, y, time)
                trail.end(time)
                listener?.onGestureEnd(trail)
                invalidate()
            }
            Mode.SPACE_SWIPE -> {
                releaseVisual(key)
                // One word, however far or slowly it went; drifting back first cancels it.
                val dx = x - p.downX
                val keyWidth = layout?.keyWidth ?: 0f
                if (abs(dx) >= maxOf(touchSlop * 1.5f, keyWidth * SWIPE_MIN_KEYS)) listener?.onSpaceSwipe(if (dx > 0) 1 else -1)
            }
            Mode.DELETE_SWIPE -> {
                releaseVisual(key)
                listener?.onDeleteSwipe(p.words, true)
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
     * How far a finger must travel to be glide typing. Just after a tap the user is typing,
     * and a thumb rolling off a key mustn't start a glide (which lost the letter, or typed a
     * word): then the distance starts at a few keys and comes down over half a second, as in
     * Android's own keyboard. The path is kept meanwhile, so a glide recognised late loses
     * none of it.
     */
    private fun glideStart(l: KeyboardLayout, p: Pointer, time: Long): Float {
        val near = maxOf(touchSlop * 1.5f, l.keyWidth * 0.55f)
        val held = time - p.downAt
        if (!p.afterTyping || held >= GLIDE_SETTLE_MS) return near
        val far = l.keyWidth * GLIDE_AFTER_TYPING_KEYS
        return far - (far - near) * held / GLIDE_SETTLE_MS
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
        if (trail.active) trail.clear()
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
        private const val GESTURE_NOTIFY_MS = 90L
        /** How far, in key widths, a space-bar swipe must end from where it began to count. */
        internal const val SWIPE_MIN_KEYS = 0.4f
        /**
         * A flick down counts past this share of the key's height (about 4 mm on a phone)...
         * A thumb typing fast often rolls a couple of millimetres down a key as it lifts, and
         * at 0.3 that typed ")" in the middle of "full".
         */
        private const val FLICK_START = 0.6f
        /** ...and stops being a flick (and becomes a glide) past this many key heights. */
        private const val FLICK_REACH = 1.4f
        /** A flick may lean this far sideways for each step down. */
        private const val FLICK_SLANT = 0.6f
        /** A finger down this soon after a tap is typing, not starting a glide... */
        private const val FAST_TYPING_MS = 350L
        /** ...unless it travels this many key widths, coming down to the usual over [GLIDE_SETTLE_MS]. */
        private const val GLIDE_AFTER_TYPING_KEYS = 3f
        private const val GLIDE_SETTLE_MS = 450L
        /** A slide moves the press to another key once this share of that key's size inside it. */
        private const val SLIDE_INSET = 0.2f
        private const val PAD_MAX_WIDTH_DP = 520f
        /** Limits on a split key's width, its gap included. */
        private const val SPLIT_KEY_MIN_DP = 40f
        private const val SPLIT_KEY_MAX_DP = 64f
    }
}
