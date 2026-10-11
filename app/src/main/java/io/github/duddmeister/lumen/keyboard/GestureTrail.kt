package io.github.duddmeister.lumen.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import io.github.duddmeister.lumen.ui.Colors
import io.github.duddmeister.lumen.ui.Motion
import kotlin.math.sqrt

/**
 * Records a glide-typing path and paints it as a glowing comet: thick and bright
 * at the fingertip, tapering and fading towards the tail.
 */
class GestureTrail {
    var xs = FloatArray(256)
        private set
    var ys = FloatArray(256)
        private set
    private var ts = LongArray(256)
    var count = 0
        private set
    var active = false
        private set
    private var endedAt = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ribbon = Path()
    private val spine = Path()
    private var side = FloatArray(64)

    fun start(x: Float, y: Float, time: Long) {
        count = 0
        active = true
        add(x, y, time)
    }

    fun add(x: Float, y: Float, time: Long) {
        if (count > 0) {
            val dx = x - xs[count - 1]
            val dy = y - ys[count - 1]
            if (dx * dx + dy * dy < 2.25f) return
        }
        if (count == xs.size) {
            xs = xs.copyOf(count * 2); ys = ys.copyOf(count * 2); ts = ts.copyOf(count * 2)
        }
        xs[count] = x; ys[count] = y; ts[count] = time
        count++
    }

    fun end(time: Long) {
        active = false
        endedAt = time
    }

    fun clear() {
        count = 0
        active = false
        endedAt = 0L
    }

    /**
     * Paints the trail as one tapered ribbon (no overlapping segments, so no
     * beading), with a soft glow underneath. Returns true while it needs frames.
     */
    fun draw(canvas: Canvas, now: Long, width: Float, start: Int, end: Int): Boolean {
        if (count < 2) return false
        val fadeOut = if (active) 1f else 1f - Motion.progress(endedAt, END_FADE_MS, now)
        if (fadeOut <= 0f) {
            count = 0
            return false
        }
        val head = ts[count - 1]
        var first = count - 1
        while (first > 0 && head - ts[first - 1] < TAIL_MS) first--
        val n = count - first
        if (n < 2) return active
        if (side.size < n * 4) side = FloatArray(n * 8)

        spine.reset()
        spine.moveTo(xs[first], ys[first])
        for (j in 0 until n) {
            val i = first + j
            if (j > 0) spine.lineTo(xs[i], ys[i])
            val r = j / (n - 1f)
            val half = width * (0.1f + 0.9f * r) / 2f
            val prev = maxOf(first, i - 1)
            val next = minOf(count - 1, i + 1)
            var dx = xs[next] - xs[prev]
            var dy = ys[next] - ys[prev]
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            dx /= len; dy /= len
            side[4 * j] = xs[i] - dy * half
            side[4 * j + 1] = ys[i] + dx * half
            side[4 * j + 2] = xs[i] + dy * half
            side[4 * j + 3] = ys[i] - dx * half
        }
        ribbon.reset()
        ribbon.moveTo(side[0], side[1])
        for (j in 1 until n) ribbon.lineTo(side[4 * j], side[4 * j + 1])
        for (j in n - 1 downTo 0) ribbon.lineTo(side[4 * j + 2], side[4 * j + 3])
        ribbon.close()

        val tx = xs[first]; val ty = ys[first]
        val hx = xs[count - 1]; val hy = ys[count - 1]
        glow.strokeWidth = width * 2.8f
        glow.shader = LinearGradient(tx, ty, hx, hy, Colors.withAlpha(start, 0f), Colors.withAlpha(end, 0.2f * fadeOut), Shader.TileMode.CLAMP)
        canvas.drawPath(spine, glow)
        // Opaque, so the gradient's own alpha is all that applies (the head dot below
        // leaves a fading color on this paint).
        fill.color = Color.WHITE
        fill.shader = LinearGradient(
            tx, ty, hx, hy,
            intArrayOf(Colors.withAlpha(start, 0f), Colors.withAlpha(start, 0.9f * fadeOut), Colors.withAlpha(end, fadeOut)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(ribbon, fill)
        fill.shader = null
        fill.color = Colors.withAlpha(end, fadeOut)
        canvas.drawCircle(hx, hy, width / 2f, fill)
        return true
    }

    companion object {
        private const val TAIL_MS = 320L
        private const val END_FADE_MS = 220L
    }
}
