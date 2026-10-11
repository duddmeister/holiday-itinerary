package io.github.duddmeister.lumen.keyboard

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lumen's icon set, drawn in code on a 24-unit grid with rounded strokes so
 * every glyph matches the keyboard's line weight at any size.
 */
object Icons {
    private const val STROKE = 1.8f
    private val soften = CornerPathEffect(1.6f)
    private val paths = HashMap<String, Path>()
    private val oval = RectF()

    private fun path(name: String, build: Path.() -> Unit): Path = paths.getOrPut(name) { Path().apply(build) }

    private fun Path.poly(vararg p: Float) {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
    }

    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        val scale = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(scale, scale)
        paint.isAntiAlias = true
        paint.shader = null
        paint.color = color
        paint.strokeWidth = STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        when (icon) {
            Icon.SHIFT, Icon.SHIFT_ON -> {
                if (icon == Icon.SHIFT_ON) paint.style = Paint.Style.FILL_AND_STROKE
                paint.pathEffect = soften
                canvas.drawPath(path("shift") { poly(12f, 4.5f, 4.5f, 12.5f, 8.75f, 12.5f, 8.75f, 19f, 15.25f, 19f, 15.25f, 12.5f, 19.5f, 12.5f); close() }, paint)
            }
            Icon.CAPS_LOCK -> {
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.pathEffect = soften
                canvas.drawPath(path("caps") { poly(12f, 3f, 4.5f, 11f, 8.75f, 11f, 8.75f, 16f, 15.25f, 16f, 15.25f, 11f, 19.5f, 11f); close() }, paint)
                paint.pathEffect = null
                paint.style = Paint.Style.STROKE
                canvas.drawLine(8.75f, 20f, 15.25f, 20f, paint)
            }
            Icon.DELETE -> {
                paint.pathEffect = soften
                canvas.drawPath(path("delete") { poly(2.8f, 12f, 8.4f, 5.5f, 20f, 5.5f, 20f, 18.5f, 8.4f, 18.5f); close() }, paint)
                paint.pathEffect = null
                canvas.drawLine(11.6f, 9.6f, 16.2f, 14.4f, paint)
                canvas.drawLine(16.2f, 9.6f, 11.6f, 14.4f, paint)
            }
            Icon.ENTER -> {
                paint.pathEffect = soften
                canvas.drawPath(path("enter") { poly(18.5f, 6f, 18.5f, 12.5f, 6f, 12.5f) }, paint)
                paint.pathEffect = null
                canvas.drawPath(path("enterHead") { poly(10f, 8.5f, 6f, 12.5f, 10f, 16.5f) }, paint)
            }
            Icon.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 5.6f, paint)
                canvas.drawLine(14.7f, 14.7f, 19.5f, 19.5f, paint)
            }
            Icon.SEND -> {
                paint.pathEffect = soften
                canvas.drawPath(path("send") { poly(4f, 5f, 20.5f, 12f, 4f, 19f, 6.8f, 12f); close() }, paint)
                paint.pathEffect = null
                canvas.drawLine(6.8f, 12f, 12.5f, 12f, paint)
            }
            Icon.GO, Icon.NEXT, Icon.ARROW_RIGHT -> {
                if (icon != Icon.ARROW_RIGHT) canvas.drawLine(5f, 12f, 19f, 12f, paint)
                canvas.drawPath(path("arrowR") { poly(13f, 6f, 19f, 12f, 13f, 18f) }, paint)
            }
            Icon.PREVIOUS, Icon.ARROW_LEFT -> {
                if (icon == Icon.PREVIOUS) canvas.drawLine(5f, 12f, 19f, 12f, paint)
                canvas.drawPath(path("arrowL") { poly(11f, 6f, 5f, 12f, 11f, 18f) }, paint)
            }
            Icon.DONE -> canvas.drawPath(path("done") { poly(5f, 12.5f, 10f, 17.5f, 19.5f, 7.5f) }, paint)
            Icon.EMOJI -> {
                canvas.drawCircle(12f, 12f, 8.6f, paint)
                oval.set(8f, 9.2f, 16f, 16.4f)
                canvas.drawArc(oval, 25f, 130f, false, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(9.2f, 10f, 1.2f, paint)
                canvas.drawCircle(14.8f, 10f, 1.2f, paint)
            }
            Icon.SPACE -> canvas.drawPath(path("space") { poly(5.5f, 10.5f, 5.5f, 14.5f, 18.5f, 14.5f, 18.5f, 10.5f) }, paint)
            Icon.SETTINGS -> {
                paint.pathEffect = soften
                canvas.drawPath(path("gear") {
                    for (k in 0 until 32) {
                        val a = Math.toRadians(k * 11.25 - 5.6).toFloat()
                        val r = if ((k / 2) % 2 == 0) 9.2f else 6.9f
                        val x = 12f + r * cos(a)
                        val y = 12f + r * sin(a)
                        if (k == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }, paint)
                paint.pathEffect = null
                canvas.drawCircle(12f, 12f, 2.9f, paint)
            }
            Icon.CLIPBOARD -> {
                oval.set(5.8f, 5f, 18.2f, 20.5f)
                canvas.drawRoundRect(oval, 2.4f, 2.4f, paint)
                oval.set(9f, 3.2f, 15f, 6.8f)
                canvas.drawRoundRect(oval, 1.2f, 1.2f, paint)
                canvas.drawLine(9f, 11f, 15f, 11f, paint)
                canvas.drawLine(9f, 14.6f, 13.4f, 14.6f, paint)
            }
            Icon.PALETTE -> {
                paint.pathEffect = soften
                canvas.drawPath(path("palette") {
                    moveTo(12f, 3.5f)
                    cubicTo(6.8f, 3.5f, 3.5f, 7.4f, 3.5f, 12f)
                    cubicTo(3.5f, 16.8f, 7.3f, 20.5f, 12f, 20.5f)
                    cubicTo(13.4f, 20.5f, 14f, 19.6f, 13.6f, 18.4f)
                    cubicTo(13.1f, 17f, 13.9f, 15.6f, 15.4f, 15.6f)
                    lineTo(17f, 15.6f)
                    cubicTo(19f, 15.6f, 20.5f, 14.1f, 20.5f, 12f)
                    cubicTo(20.5f, 7.3f, 16.7f, 3.5f, 12f, 3.5f)
                    close()
                }, paint)
                paint.pathEffect = null
                paint.style = Paint.Style.FILL
                canvas.drawCircle(8f, 11.5f, 1.25f, paint)
                canvas.drawCircle(10.5f, 7.8f, 1.25f, paint)
                canvas.drawCircle(15f, 8.2f, 1.25f, paint)
            }
            Icon.KEYBOARD_HIDE -> canvas.drawPath(path("hide") { poly(6.5f, 9.5f, 12f, 15f, 17.5f, 9.5f) }, paint)
            Icon.SPARKLE -> {
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = 0.6f
                canvas.drawPath(path("sparkle") {
                    moveTo(11f, 3f)
                    quadTo(11.7f, 10.3f, 19f, 11f); quadTo(11.7f, 11.7f, 11f, 19f)
                    quadTo(10.3f, 11.7f, 3f, 11f); quadTo(10.3f, 10.3f, 11f, 3f); close()
                    moveTo(18.5f, 14.5f)
                    quadTo(18.8f, 17.2f, 21.5f, 17.5f); quadTo(18.8f, 17.8f, 18.5f, 20.5f)
                    quadTo(18.2f, 17.8f, 15.5f, 17.5f); quadTo(18.2f, 17.2f, 18.5f, 14.5f); close()
                }, paint)
            }
            Icon.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8.6f, paint)
                canvas.drawPath(path("clock") { poly(12f, 7.4f, 12f, 12f, 15.2f, 14f) }, paint)
            }
            Icon.PIN -> {
                paint.pathEffect = soften
                canvas.drawPath(path("pin") { poly(9.4f, 4f, 14.6f, 4f, 14f, 10f, 17.2f, 13.4f, 6.8f, 13.4f, 10f, 10f); close() }, paint)
                paint.pathEffect = null
                canvas.drawLine(12f, 13.4f, 12f, 20f, paint)
            }
            Icon.CLEAR -> {
                canvas.drawLine(7f, 7f, 17f, 17f, paint)
                canvas.drawLine(17f, 7f, 7f, 17f, paint)
            }
            Icon.GIF -> {
                oval.set(2.8f, 6.2f, 21.2f, 17.8f)
                canvas.drawRoundRect(oval, 3.2f, 3.2f, paint)
                val typeface = paint.typeface
                val align = paint.textAlign
                val textSize = paint.textSize
                paint.style = Paint.Style.FILL
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 7.4f
                canvas.drawText("GIF", 12f, 14.6f, paint)
                paint.typeface = typeface
                paint.textAlign = align
                paint.textSize = textSize
            }
            Icon.TRASH -> {
                canvas.drawLine(5.5f, 7f, 18.5f, 7f, paint)
                canvas.drawPath(path("trashLid") { poly(9.8f, 7f, 9.8f, 4.8f, 14.2f, 4.8f, 14.2f, 7f) }, paint)
                paint.pathEffect = soften
                canvas.drawPath(path("trashBin") { poly(7f, 7f, 8f, 19.6f, 16f, 19.6f, 17f, 7f) }, paint)
                paint.pathEffect = null
                canvas.drawLine(10.5f, 10.6f, 10.5f, 16.2f, paint)
                canvas.drawLine(13.5f, 10.6f, 13.5f, 16.2f, paint)
            }
        }
        canvas.restore()
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
    }
}
