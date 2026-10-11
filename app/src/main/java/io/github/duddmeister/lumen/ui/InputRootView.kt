package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import io.github.duddmeister.lumen.keyboard.KeyPainter
import io.github.duddmeister.lumen.keyboard.KeyboardView
import io.github.duddmeister.lumen.theme.KeyboardTheme

/**
 * Root of the input view: one continuous themed background behind the
 * suggestion strip, the keys (or a panel) and the navigation-bar area, plus an
 * overlay above everything for key previews and long-press panels.
 */
@SuppressLint("ViewConstructor")
class InputRootView(context: Context, val painter: KeyPainter) : ViewGroup(context) {
    val strip = SuggestionStripView(context, painter)
    val keyboard = KeyboardView(context, painter)
    /** Replaces the strip while searching; the keys type into it. */
    val searchBar = SearchBarView(context, painter)
    private val overlay = Overlay(context)
    var panel: View? = null
        private set
    val searching: Boolean get() = searchBar.visibility == VISIBLE

    val stripHeight = context.dp(46)
    private var insetLeft = 0
    private var insetRight = 0
    private var insetBottom = 0

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - insetLeft - insetRight
        val exact = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
        keyboard.measure(exact(inner), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        strip.measure(exact(inner), exact(stripHeight))
        searchBar.measure(exact(inner), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val contentHeight = topHeight() + keyboard.measuredHeight
        panel?.measure(exact(inner), exact(contentHeight))
        overlay.measure(exact(inner), exact(contentHeight))
        setMeasuredDimension(width, contentHeight + insetBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = insetLeft
        val top = topHeight()
        strip.layout(left, 0, left + strip.measuredWidth, stripHeight)
        searchBar.layout(left, 0, left + searchBar.measuredWidth, searchBar.measuredHeight)
        keyboard.layout(left, top, left + keyboard.measuredWidth, top + keyboard.measuredHeight)
        panel?.let { it.layout(left, 0, left + it.measuredWidth, it.measuredHeight) }
        overlay.layout(left, 0, left + overlay.measuredWidth, overlay.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        painter.drawBackground(canvas, width.toFloat(), height.toFloat())
    }

    /** Keeps keys clear of the navigation bar and display cutouts while the background fills behind them. */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val left: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
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
            if (keyboard.drawOverlay(canvas, 0f, keyboard.top.toFloat())) postInvalidateOnAnimation()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent) = false
    }
}
