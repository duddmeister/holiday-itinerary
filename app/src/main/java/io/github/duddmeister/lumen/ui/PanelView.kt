package io.github.duddmeister.lumen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Key
import io.github.duddmeister.lumen.keyboard.KeyCodes
import io.github.duddmeister.lumen.keyboard.KeyPainter
import io.github.duddmeister.lumen.keyboard.KeyStyle
import kotlin.math.abs

/**
 * Base for the full-height panels (emoji, clipboard, themes): a header row,
 * a scrolling content area with fling, and a footer of real keys (ABC,
 * space, delete) painted exactly like the keyboard's.
 */
@SuppressLint("ViewConstructor")
abstract class PanelView(context: Context, protected val painter: KeyPainter) : View(context) {

    interface Host {
        /** ABC was tapped: go back to the letters. */
        fun onPanelClose()
        /** A footer key fired (space or delete). */
        fun onPanelKey(code: Int)
        /** A finger landed on something tappable (for haptics and sound). */
        fun onPanelFeedback()
    }

    var host: Host? = null

    protected val headerHeight = context.dp(46f)
    protected val footerHeight = context.dp(54f)
    protected val content = RectF()
    protected var scroll = 0f
    protected var contentHeight = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    protected open val footerKeys: List<Key> = listOf(
        Key(KeyCodes.ALPHABET, "ABC", width = 1.5f, style = KeyStyle.FUNCTIONAL, preview = false),
        Key(KeyCodes.DELETE, icon = Icon.DELETE, width = 1.5f, style = KeyStyle.FUNCTIONAL, preview = false),
    )
    private var pressedFooter: Key? = null
    private var repeats = 0
    private val repeat = object : Runnable {
        override fun run() {
            val k = pressedFooter ?: return
            if (k.code != KeyCodes.DELETE) return
            repeats++
            host?.onPanelKey(KeyCodes.DELETE)
            postDelayed(this, 60)
        }
    }

    private enum class Region { HEADER, CONTENT, FOOTER }
    private var region = Region.CONTENT
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private val header = RectF()
    private val edgePaint = android.graphics.Paint()
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        if (!dragging && onContentLongPress(downX, downY + scroll - content.top)) {
            longPressed = true
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    abstract fun drawHeader(canvas: Canvas, bounds: RectF)

    /** Draws content; the canvas is already translated so (0,0) is the top of the content. */
    abstract fun drawContent(canvas: Canvas, top: Float, bottom: Float)

    open fun onHeaderTap(x: Float, y: Float) {}
    open fun onContentTap(x: Float, y: Float) {}
    open fun onContentLongPress(x: Float, y: Float): Boolean = false
    open fun onLongPressMove(x: Float, y: Float) {}
    open fun onLongPressUp(x: Float, y: Float) {}
    open fun onContentPress(x: Float, y: Float) {}
    open fun onScrollChanged() {}

    protected fun maxScroll() = maxOf(0f, contentHeight - content.height())

    fun scrollToContent(y: Float, animate: Boolean = true) {
        val target = y.coerceIn(0f, maxScroll())
        if (animate) {
            scroller.forceFinished(true)
            scroller.startScroll(0, scroll.toInt(), 0, (target - scroll).toInt(), 320)
            postInvalidateOnAnimation()
        } else {
            scroll = target
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        content.set(0f, headerHeight, w.toFloat(), h - footerHeight)
        layoutFooter()
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    private fun layoutFooter() {
        val keys = footerKeys
        val gap = context.dp(5.5f)
        val pad = context.dp(4f)
        val unit = (width - 2 * pad) / FOOTER_UNITS
        val top = height - footerHeight + context.dp(4.5f)
        val bottom = height - context.dp(5f)
        // Outer keys hug the edges; a space key (if any) fills the middle.
        val first = keys.first()
        val last = keys.last()
        first.bounds.set(pad + gap / 2f, top, pad + first.width * unit - gap / 2f, bottom)
        last.bounds.set(width - pad - last.width * unit + gap / 2f, top, width - pad - gap / 2f, bottom)
        for (k in keys) if (k !== first && k !== last) {
            k.bounds.set(first.bounds.right + gap, top, last.bounds.left - gap, bottom)
        }
        for (k in keys) k.hitBounds.set(k.bounds.left - gap / 2f, top - gap, k.bounds.right + gap / 2f, height.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            onScrollChanged()
            postInvalidateOnAnimation()
        }
        header.set(0f, 0f, width.toFloat(), headerHeight)
        drawHeader(canvas, header)
        canvas.save()
        canvas.clipRect(content)
        canvas.translate(0f, content.top - scroll)
        drawContent(canvas, scroll, scroll + content.height())
        canvas.restore()
        drawScrollEdges(canvas)
        for (k in footerKeys) {
            val press = if (k === pressedFooter) 1f else 0f
            painter.drawKey(canvas, k, press, false, spaceLabel = null)
        }
    }

    /** Thin fades at the content edges hint that it scrolls. */
    private fun drawScrollEdges(canvas: Canvas) {
        val t = painter.theme ?: return
        if (contentHeight <= content.height()) return
        val h = context.dp(1f)
        edgePaint.color = t.divider
        if (scroll > 1f) canvas.drawRect(content.left, content.top, content.right, content.top + h, edgePaint)
        if (scroll < maxScroll() - 1f) canvas.drawRect(content.left, content.bottom - h, content.right, content.bottom, edgePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                dragging = false
                longPressed = false
                region = when {
                    event.y < content.top -> Region.HEADER
                    event.y >= content.bottom -> Region.FOOTER
                    else -> Region.CONTENT
                }
                when (region) {
                    Region.FOOTER -> {
                        pressedFooter = footerKeys.firstOrNull { it.hitBounds.contains(event.x, event.y) }
                        if (pressedFooter != null) {
                            host?.onPanelFeedback()
                            repeats = 0
                            if (pressedFooter?.code == KeyCodes.DELETE) postDelayed(repeat, 380)
                        }
                    }
                    Region.CONTENT -> {
                        scroller.forceFinished(true)
                        velocity?.recycle()
                        velocity = VelocityTracker.obtain().apply { addMovement(event) }
                        onContentPress(event.x, event.y + scroll - content.top)
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    Region.HEADER -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (region == Region.CONTENT) {
                velocity?.addMovement(event)
                if (longPressed) {
                    onLongPressMove(event.x, event.y + scroll - content.top)
                } else {
                    if (!dragging && abs(event.y - downY) > touchSlop) {
                        dragging = true
                        removeCallbacks(longPress)
                        onContentPress(-1f, -1f)
                    }
                    if (dragging) {
                        scroll = (scroll + lastY - event.y).coerceIn(0f, maxScroll())
                        onScrollChanged()
                        invalidate()
                    }
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                removeCallbacks(repeat)
                when (region) {
                    Region.FOOTER -> {
                        val k = pressedFooter
                        pressedFooter = null
                        if (k != null && k.hitBounds.contains(event.x, event.y)) {
                            when (k.code) {
                                KeyCodes.ALPHABET -> host?.onPanelClose()
                                KeyCodes.DELETE -> if (repeats == 0) host?.onPanelKey(KeyCodes.DELETE)
                                else -> host?.onPanelKey(k.code)
                            }
                        }
                    }
                    Region.HEADER -> if (abs(event.x - downX) < touchSlop * 2) onHeaderTap(event.x, event.y)
                    Region.CONTENT -> {
                        if (longPressed) {
                            onLongPressUp(event.x, event.y + scroll - content.top)
                        } else if (!dragging) {
                            onContentTap(event.x, event.y + scroll - content.top)
                        } else {
                            velocity?.let {
                                it.addMovement(event)
                                it.computeCurrentVelocity(1000)
                                scroller.fling(0, scroll.toInt(), 0, -it.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt())
                                postInvalidateOnAnimation()
                            }
                        }
                        onContentPress(-1f, -1f)
                    }
                }
                velocity?.recycle()
                velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                removeCallbacks(repeat)
                pressedFooter = null
                if (longPressed) onLongPressUp(-1f, -1f)
                onContentPress(-1f, -1f)
                velocity?.recycle()
                velocity = null
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val FOOTER_UNITS = 10f
    }
}
