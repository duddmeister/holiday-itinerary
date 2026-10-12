package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.keyboard.KeyPainter
import io.github.duddmeister.lumen.keyboard.KeyboardView
import io.github.duddmeister.lumen.theme.KeyboardTheme
import kotlin.math.roundToInt

/**
 * Root of the input view: one continuous themed background behind the
 * suggestion strip, the keys (or a panel) and the navigation-bar area, plus an
 * overlay above everything for key previews and long-press panels.
 *
 * One-handed, the content narrows towards one side, with buttons in the room left
 * over. Floating, the content is a card with a handle, drawn anywhere in a view as
 * tall as the room the keyboard window has.
 */
@SuppressLint("ViewConstructor")
class InputRootView(context: Context, val painter: KeyPainter) : ViewGroup(context) {

    /** Hears the one-handed and floating controls. */
    interface Listener {
        /** Narrow towards [side], or back to the full width when null. */
        fun onOneHanded(side: Side?)
        /** The floating card was dragged to [x], [y] (shares of the room across and down). */
        fun onFloatMoved(x: Float, y: Float)
        /** Back to a keyboard along the bottom. */
        fun onDock()
    }

    enum class Side { LEFT, RIGHT }

    var listener: Listener? = null

    /** One-handed: the keys keep to this side. */
    var oneHanded: Side? = null
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    /** Floating: the keyboard is a card over the app. */
    var floating = false
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    /** Where the floating card sits, as shares (0 to 1) of the room across and down. */
    var floatX = 0.5f
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var floatY = 1f
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    /** The floating card, in this view's coordinates; empty when docked. */
    val card = Rect()

    val strip = SuggestionStripView(context, painter)
    val keyboard = KeyboardView(context, painter)
    /** Replaces the strip while searching; the keys type into it. */
    val searchBar = SearchBarView(context, painter)
    private val overlay = Overlay(context)
    var panel: View? = null
        private set
    val searching: Boolean get() = searchBar.visibility == VISIBLE

    val stripHeight = context.dp(46)
    private val handleHeight = context.dp(30)
    /** Room kept between the floating card and the edges of the screen. */
    private val cardMargin = context.dp(10)
    private var insetLeft = 0
    private var insetRight = 0
    private var insetBottom = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardShape = Path()
    private val rect = RectF()
    private val buttonRadius = context.dp(21f)
    private val buttonGap = context.dp(32f)
    private val cardRadius = context.dp(22f)

    init {
        setWillNotDraw(false)
        addView(strip)
        addView(searchBar)
        addView(keyboard)
        addView(overlay)
        keyboard.overlay = overlay
        searchBar.visibility = GONE
    }

    fun applyTheme(theme: KeyboardTheme) {
        painter.theme = theme
        keyboard.applyTheme(theme)
        strip.invalidate()
        searchBar.invalidate()
        panel?.invalidate()
        invalidate()
    }

    /**
     * Shows [view] in place of the strip and keys, or returns to the keys when null.
     * [animate] fades the change in; pass false while the keyboard is not on screen.
     */
    fun showPanel(view: View?, animate: Boolean = true) {
        if (view === panel) return
        panel?.let { removeView(it) }
        panel = view
        if (view != null) {
            addView(view, indexOfChild(overlay))
            fadeIn(view, animate)
            keyboard.cancelTouches()
            searchBar.visibility = GONE
        }
        strip.visibility = if (view == null && !searching) VISIBLE else INVISIBLE
        keyboard.visibility = if (view == null) VISIBLE else INVISIBLE
        if (view == null) {
            fadeIn(keyboard, animate)
            if (!searching) fadeIn(strip, animate)
        }
        requestLayout()
        invalidate()
    }

    /**
     * Shows the search bar for [kind] in place of the strip (closing any panel),
     * or hides it when null. [animate] as for [showPanel].
     */
    fun showSearch(kind: SearchBarView.Kind?, initial: String = "", hint: String? = null, animate: Boolean = true) {
        if (kind == null) {
            if (!searching) return
            searchBar.visibility = GONE
            if (panel == null) {
                strip.visibility = VISIBLE
                fadeIn(strip, animate)
            }
        } else {
            showPanel(null, animate)
            searchBar.start(kind, initial, hint ?: kind.hint)
            searchBar.visibility = VISIBLE
            fadeIn(searchBar, animate)
            strip.visibility = INVISIBLE
        }
        requestLayout()
        invalidate()
    }

    private fun fadeIn(v: View, animate: Boolean) {
        v.animate().cancel()
        if (animate) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(140).start()
        } else {
            v.alpha = 1f
        }
    }

    /** Height above the keys: the strip, or the (possibly taller) search bar. */
    private fun topHeight() = if (searching) searchBar.measuredHeight else stripHeight

    /**
     * How far the empty margin below the keys reaches down over the bar beneath them
     * (Android's hide-keyboard and switch-keyboard buttons, or the navigation bar). That bar
     * keeps its own room above its buttons, so with the margin as well the keys sat far
     * from them. The keys themselves stay clear of it; panels end where they did.
     */
    private fun barOverlap() = if (floating || insetBottom <= 0) 0 else minOf(insetBottom, keyboard.bottomMargin)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - insetLeft - insetRight
        val content = when {
            floating -> minOf(inner, maxOf(context.dp(FLOAT_MIN_DP), minOf(context.dp(FLOAT_MAX_DP), (inner * FLOAT_SHARE).toInt())))
            oneHanded != null -> (inner * ONE_HANDED_SHARE).toInt()
            else -> inner
        }
        val exact = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
        keyboard.measure(exact(content), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        strip.measure(exact(content), exact(stripHeight))
        searchBar.measure(exact(content), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val contentHeight = topHeight() + keyboard.measuredHeight
        panel?.measure(exact(content), exact(contentHeight - barOverlap()))
        overlay.measure(exact(content), exact(contentHeight))
        val height = if (floating) {
            // As tall as the window may be, so the card can go anywhere.
            val room = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.heightPixels
            else MeasureSpec.getSize(heightMeasureSpec)
            maxOf(room, contentHeight + handleHeight + insetBottom + 2 * cardMargin)
        } else {
            contentHeight + insetBottom - barOverlap()
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = width - insetLeft - insetRight
        val content = strip.measuredWidth
        val contentHeight = topHeight() + keyboard.measuredHeight
        val left: Int
        val top: Int
        if (floating) {
            left = insetLeft + cardMargin + (floatX.coerceIn(0f, 1f) * roomAcross(content)).roundToInt()
            top = cardMargin + (floatY.coerceIn(0f, 1f) * roomDown(contentHeight + handleHeight)).roundToInt()
            card.set(left, top, left + content, top + contentHeight + handleHeight)
        } else {
            left = insetLeft + if (oneHanded == Side.RIGHT) inner - content else 0
            top = 0
            card.setEmpty()
        }
        val keysTop = top + topHeight()
        strip.layout(left, top, left + strip.measuredWidth, top + stripHeight)
        searchBar.layout(left, top, left + searchBar.measuredWidth, top + searchBar.measuredHeight)
        keyboard.layout(left, keysTop, left + keyboard.measuredWidth, keysTop + keyboard.measuredHeight)
        panel?.let { it.layout(left, top, left + it.measuredWidth, top + it.measuredHeight) }
        overlay.layout(left, top, left + overlay.measuredWidth, top + overlay.measuredHeight)
    }

    /** How far a floating card [cardWidth] wide can move across, and one [cardHeight] tall down. */
    private fun roomAcross(cardWidth: Int) = (width - insetLeft - insetRight - cardWidth - 2 * cardMargin).coerceAtLeast(0)
    private fun roomDown(cardHeight: Int) = (height - insetBottom - cardHeight - 2 * cardMargin).coerceAtLeast(0)

    override fun onDraw(canvas: Canvas) {
        if (floating) {
            // Behind the navigation bar, as when docked, so its buttons keep their contrast.
            painter.theme?.let { t ->
                paint.color = t.background.last()
                canvas.drawRect(0f, (height - insetBottom).toFloat(), width.toFloat(), height.toFloat(), paint)
            }
            drawCard(canvas)
            return
        }
        painter.drawBackground(canvas, width.toFloat(), height.toFloat())
        if (oneHanded == null) return
        val t = painter.theme ?: return
        val size = context.dp(22f)
        for (b in BESIDE_KEYS) {
            buttonBounds(b, rect)
            paint.color = Colors.withAlpha(t.stripText, if (pressed == b) 0.16f else 0.08f)
            canvas.drawCircle(rect.centerX(), rect.centerY(), buttonRadius, paint)
            val icon = when {
                b == Button.FULL_WIDTH -> Icon.EXPAND
                oneHanded == Side.RIGHT -> Icon.ARROW_LEFT
                else -> Icon.ARROW_RIGHT
            }
            // The chevrons sit off-centre in their icons (they share them with arrows).
            val nudge = when (icon) {
                Icon.ARROW_LEFT -> size / 6f
                Icon.ARROW_RIGHT -> -size / 6f
                else -> 0f
            }
            Icons.draw(canvas, icon, rect.centerX() + nudge, rect.centerY(), size, t.stripSecondary, paint)
        }
    }

    /** The floating card: a shadow, the theme's background in a rounded card, and the handle row. */
    private fun drawCard(canvas: Canvas) {
        val t = painter.theme ?: return
        rect.set(card)
        shadow.color = t.background.firstOrNull() ?: t.divider
        shadow.setShadowLayer(context.dp(16f), 0f, context.dp(5f), Colors.withAlpha(0xFF000000.toInt(), 0.35f))
        canvas.drawRoundRect(rect, cardRadius, cardRadius, shadow)
        cardShape.reset()
        cardShape.addRoundRect(rect, cardRadius, cardRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(cardShape)
        canvas.translate(rect.left, rect.top)
        painter.drawBackground(canvas, rect.width(), rect.height())
        canvas.restore()
        // The handle: a pill to drag by, and a button to dock.
        val cy = card.bottom - handleHeight / 2f
        paint.color = Colors.withAlpha(t.stripSecondary, if (pressed == Button.HANDLE) 0.9f else 0.55f)
        rect.set(card.exactCenterX() - context.dp(22f), cy - context.dp(2.5f), card.exactCenterX() + context.dp(22f), cy + context.dp(2.5f))
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        buttonBounds(Button.DOCK, rect)
        if (pressed == Button.DOCK) {
            paint.color = Colors.withAlpha(t.stripText, 0.12f)
            canvas.drawCircle(rect.centerX(), rect.centerY(), handleHeight * 0.5f, paint)
        }
        Icons.draw(canvas, Icon.DOCK, rect.centerX(), rect.centerY(), context.dp(18f), t.stripSecondary, paint)
    }

    // ---------------------------------------------------------------- the controls around the keys

    private enum class Button { FULL_WIDTH, SWAP_SIDE, HANDLE, DOCK }

    private var pressed: Button? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f

    /** Where [button] is; for the one-handed buttons, in the room beside the keys. */
    private fun buttonBounds(button: Button, out: RectF) {
        when (button) {
            Button.FULL_WIDTH, Button.SWAP_SIDE -> {
                // Halfway across the room beside the keys, one above the other.
                val roomLeft = if (oneHanded == Side.RIGHT) insetLeft else keyboard.right
                val roomRight = if (oneHanded == Side.RIGHT) keyboard.left else width - insetRight
                val cx = (roomLeft + roomRight) / 2f
                val cy = keyboard.top + keyboard.height / 2f + if (button == Button.FULL_WIDTH) -buttonGap else buttonGap
                out.set(cx - buttonRadius, cy - buttonRadius, cx + buttonRadius, cy + buttonRadius)
            }
            Button.HANDLE -> out.set(card.left.toFloat(), (card.bottom - handleHeight).toFloat(), card.right.toFloat(), card.bottom.toFloat())
            Button.DOCK -> {
                val cx = card.right - context.dp(28f)
                out.set(cx - context.dp(24f), (card.bottom - handleHeight).toFloat(), cx + context.dp(24f), card.bottom.toFloat())
            }
        }
    }

    private fun buttonAt(x: Float, y: Float): Button? {
        val candidates = when {
            floating -> ON_HANDLE
            oneHanded != null -> BESIDE_KEYS
            else -> return null
        }
        for (b in candidates) {
            buttonBounds(b, rect)
            if (b == Button.FULL_WIDTH || b == Button.SWAP_SIDE) rect.inset(-context.dp(8f), -context.dp(8f))
            if (rect.contains(x, y)) return b
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = buttonAt(event.x, event.y) ?: return false
                downX = event.x
                downY = event.y
                startX = card.left.toFloat()
                startY = card.top.toFloat()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pressed == Button.HANDLE) {
                // The card follows the finger, kept inside the room it has.
                val across = roomAcross(card.width()).coerceAtLeast(1)
                val down = roomDown(card.height()).coerceAtLeast(1)
                floatX = ((startX + event.x - downX - insetLeft - cardMargin) / across).coerceIn(0f, 1f)
                floatY = ((startY + event.y - downY - cardMargin) / down).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP -> {
                val b = pressed
                pressed = null
                invalidate()
                when (b) {
                    Button.HANDLE -> listener?.onFloatMoved(floatX, floatY)
                    Button.DOCK -> if (buttonAt(event.x, event.y) == Button.DOCK) listener?.onDock()
                    Button.FULL_WIDTH -> if (buttonAt(event.x, event.y) == b) listener?.onOneHanded(null)
                    Button.SWAP_SIDE -> if (buttonAt(event.x, event.y) == b) {
                        listener?.onOneHanded(if (oneHanded == Side.RIGHT) Side.LEFT else Side.RIGHT)
                    }
                    null -> Unit
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // A drag cut short leaves the card where it got to.
                if (pressed == Button.HANDLE) listener?.onFloatMoved(floatX, floatY)
                pressed = null
                invalidate()
            }
        }
        return true
    }

    /**
     * Keeps keys clear of the navigation bar and display cutouts while the background
     * fills behind them. With gesture navigation, Android draws its own back and
     * keyboard-switcher buttons inside the keyboard window and reports them as a
     * caption bar, so the keys stay above that too.
     */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val left: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar() or WindowInsets.Type.displayCutout())
            left = bars.left; right = bars.right; bottom = bars.bottom
        } else {
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        if (left != insetLeft || right != insetRight || bottom != insetBottom) {
            insetLeft = left; insetRight = right; insetBottom = bottom
            requestLayout()
        }
        return insets
    }

    /** Draws previews and panels above the strip; never takes touches. */
    private inner class Overlay(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) {
            if (keyboard.visibility != VISIBLE) return
            if (keyboard.drawOverlay(canvas, 0f, (keyboard.top - top).toFloat())) postInvalidateOnAnimation()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent) = false
    }

    private companion object {
        val BESIDE_KEYS = arrayOf(Button.FULL_WIDTH, Button.SWAP_SIDE)
        /** The dock button first: it sits on the handle. */
        val ON_HANDLE = arrayOf(Button.DOCK, Button.HANDLE)

        /** A one-handed keyboard keeps this share of the width. */
        const val ONE_HANDED_SHARE = 0.8f
        /** A floating keyboard takes this share of the width, within the limits below. */
        const val FLOAT_SHARE = 0.55f
        const val FLOAT_MIN_DP = 320
        const val FLOAT_MAX_DP = 520
    }
}
