package io.github.duddmeister.lumen.ime

import android.view.MotionEvent
import android.view.Window
import io.github.duddmeister.lumen.ui.InputRootView

/**
 * The keyboard window's own touch handling, with touches that start on the keys sent
 * straight to them. The keys reach a little way over the hide-keyboard and switch-keyboard
 * buttons Android draws in this window, and those buttons, drawn on top, would otherwise
 * take a tap on the bottom of ?123 or enter. Every other touch goes on as before.
 */
internal class KeysFirst(
    private val base: Window.Callback,
    private val root: () -> InputRootView?,
) : Window.Callback by base {
    private var toKeys: InputRootView? = null
    private val at = IntArray(2)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            toKeys = root()?.takeIf { r ->
                r.isAttachedToWindow && r.run {
                    getLocationInWindow(at)
                    ownsTouch(event.x - at[0], event.y - at[1])
                }
            }
        }
        val r = toKeys ?: return base.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) toKeys = null
        r.getLocationInWindow(at)
        val local = MotionEvent.obtain(event)
        local.offsetLocation(-at[0].toFloat(), -at[1].toFloat())
        val handled = r.dispatchTouchEvent(local)
        local.recycle()
        return handled
    }
}
