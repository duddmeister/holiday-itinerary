package io.github.duddmeister.lumen.ui

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue

/** Easing curves shared by every animation in the keyboard. */
object Motion {
    fun clamp01(t: Float): Float = if (t < 0f) 0f else if (t > 1f) 1f else t

    fun easeOutCubic(t: Float): Float {
        val u = 1f - clamp01(t)
        return 1f - u * u * u
    }

    fun easeInOutCubic(t: Float): Float {
        val x = clamp01(t)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
    }

    /** Ease out with a gentle overshoot, used for keys springing back. */
    fun easeOutBack(t: Float, overshoot: Float = 1.6f): Float {
        val x = clamp01(t) - 1f
        return 1f + (overshoot + 1f) * x * x * x + overshoot * x * x
    }

    /** Progress (0..1) of an animation that started at [start] and lasts [duration] ms. */
    fun progress(start: Long, duration: Long, now: Long = SystemClock.uptimeMillis()): Float =
        if (duration <= 0) 1f else clamp01((now - start).toFloat() / duration)
}

/** Color math for themes. */
object Colors {
    fun lerp(a: Int, b: Int, t: Float): Int {
        val f = Motion.clamp01(t)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )
    }

    /** [color] with its alpha multiplied by [factor]. */
    fun fade(color: Int, factor: Float): Int =
        (color and 0x00FFFFFF) or ((Color.alpha(color) * Motion.clamp01(factor)).toInt() shl 24)

    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((255 * Motion.clamp01(alpha)).toInt() shl 24)

    fun opaque(color: Int): Int = color or 0xFF000000.toInt()
}

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.dp(value: Int): Int = dp(value.toFloat()).toInt()
