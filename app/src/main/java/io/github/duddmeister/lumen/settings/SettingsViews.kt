package io.github.duddmeister.lumen.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import io.github.duddmeister.lumen.keyboard.Icon
import io.github.duddmeister.lumen.keyboard.Icons
import io.github.duddmeister.lumen.ui.Colors
import io.github.duddmeister.lumen.ui.dp
import kotlin.math.ceil
import kotlin.math.hypot

/** The settings app's palette, light or dark with the system. */
class Palette(context: Context) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val background = if (dark) 0xFF0C0D12.toInt() else 0xFFF4F5FA.toInt()
    val card = if (dark) 0xFF16181F.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFF3F4F8.toInt() else 0xFF12131A.toInt()
    val secondary = if (dark) 0xFF9A9DB0.toInt() else 0xFF646779.toInt()
    val divider = if (dark) 0xFF232633.toInt() else 0xFFECEDF3.toInt()
    val accent = if (dark) 0xFF9284FF.toInt() else 0xFF6352F5.toInt()
    val accentSoft = if (dark) 0xFF262145.toInt() else 0xFFEEEBFF.toInt()
    val chip = if (dark) 0xFF1F222C.toInt() else 0xFFEEF0F6.toInt()
    val success = if (dark) 0xFF4ADE80.toInt() else 0xFF16A34A.toInt()
    val gradient = intArrayOf(0xFF7C5CFF.toInt(), 0xFF2DD4BF.toInt())
}

/** Builders for the settings screen; plain platform views styled to match Lumen. */
class SettingsKit(private val context: Context, val palette: Palette) {
    private val medium: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 500, false)
    else Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun dp(v: Float) = context.dp(v)
    private fun dp(v: Int) = context.dp(v)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun ripple(content: android.graphics.drawable.Drawable?, radius: Float): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Colors.withAlpha(palette.text, 0.1f)), content, rounded(0xFFFFFFFF.toInt(), radius))

    fun text(value: CharSequence, size: Float, color: Int = palette.text, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = medium
        setLineSpacing(0f, 1.12f)
    }

    /** "Lumen" with the brand gradient running through the letters. */
    fun gradientTitle(value: String, size: Float) = text(value, size, bold = true).apply {
        letterSpacing = -0.02f
        addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            (v as TextView).paint.shader = LinearGradient(0f, 0f, (r - l).toFloat() * 0.6f, 0f, palette.gradient, null, Shader.TileMode.CLAMP)
        }
    }

    fun section(title: String) = text(title.uppercase(), 12.5f, palette.secondary, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(20), dp(26), dp(20), dp(10))
    }

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.card, dp(22f))
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), 0, dp(14), 0)
        }
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(palette.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
            setMargins(dp(18), 0, dp(18), 0)
        }
    }

    private fun texts(title: String, subtitle: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 16f))
        if (subtitle != null) addView(text(subtitle, 13.5f, palette.secondary).apply { setPadding(0, dp(2), 0, 0) })
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = Switch(context).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(palette.accent, if (palette.dark) 0xFFB9BCCB.toInt() else 0xFFFFFFFF.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(Colors.withAlpha(palette.accent, 0.45f), Colors.withAlpha(palette.secondary, 0.35f)))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(14))
            addView(texts(title, subtitle))
            addView(toggle)
            background = ripple(null, 0f)
            setOnClickListener { toggle.toggle() }
        }
    }

    /** A choice of [options], each chip set in its own typeface from [typefaces] if given. */
    fun chips(title: String?, options: List<String>, selected: Int, typefaces: List<Typeface>? = null, onSelect: (Int) -> Unit): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(14), dp(14))
        }
        if (title != null) column.addView(text(title, 16f).apply { setPadding(0, 0, 0, dp(10)) })
        val row = FlowLayout(context, dp(8), dp(8))
        val views = ArrayList<TextView>()
        fun style(i: Int, chosen: Int) {
            val on = i == chosen
            views[i].background = ripple(rounded(if (on) palette.accent else palette.chip, dp(18f)), dp(18f))
            views[i].setTextColor(if (on) 0xFFFFFFFF.toInt() else palette.text)
        }
        options.forEachIndexed { i, option ->
            val chip = text(option, 14f, bold = true).apply {
                setPadding(dp(16), dp(9), dp(16), dp(9))
                gravity = Gravity.CENTER
                typefaces?.getOrNull(i)?.let { typeface = it }
            }
            views.add(chip)
            row.addView(chip)
        }
        options.indices.forEach { style(it, selected) }
        views.forEachIndexed { i, v ->
            v.setOnClickListener {
                options.indices.forEach { style(it, i) }
                onSelect(i)
            }
        }
        column.addView(row)
        return column
    }

    fun slider(title: String, min: Int, max: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit): View {
        val label = text(format(value), 14f, palette.accent, bold = true)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(title, 16f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(label)
        }
        val bar = SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = value
            progressTintList = ColorStateList.valueOf(palette.accent)
            thumbTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(Colors.withAlpha(palette.secondary, 0.35f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = format(progress)
                    if (fromUser) onChange(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(14), dp(10))
            addView(header)
            addView(bar)
        }
    }

    fun button(label: String, filled: Boolean = true, onClick: () -> Unit) = text(label, 15f, if (filled) 0xFFFFFFFF.toInt() else palette.accent, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = ripple(rounded(if (filled) palette.accent else palette.accentSoft, dp(24f)), dp(24f))
        setOnClickListener { onClick() }
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /**
     * A choice of [count] colours under [title], with the chosen one's name beside it. Each
     * swatch is the gradient [colors] gives for it: its start, end and ink. The first, marked
     * with a sparkle, stands for the colours something else (the theme) brings.
     */
    fun swatches(title: String, count: Int, colors: (Int) -> IntArray, names: (Int) -> String, selected: Int, onSelect: (Int) -> Unit): SwatchPicker =
        SwatchPicker(context, this, title, count, colors, names, selected, onSelect)

    fun actionRow(title: String, subtitle: String?, action: String, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(16), dp(14))
        addView(texts(title, subtitle))
        addView(button(action, filled = false, onClick = onClick))
    }
}

/** Colour swatches in balanced rows, the chosen one ringed; see [SettingsKit.swatches]. */
@SuppressLint("ViewConstructor")
class SwatchPicker(
    context: Context,
    kit: SettingsKit,
    private val title: String,
    private val count: Int,
    private val colors: (Int) -> IntArray,
    private val names: (Int) -> String,
    private var selected: Int,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(context) {
    private val name = kit.text(names(selected), 14f, kit.palette.accent, bold = true)
    private val ringColor = kit.palette.text
    private val circles = Circles()

    init {
        orientation = VERTICAL
        setPadding(context.dp(20), context.dp(14), context.dp(14), context.dp(12))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(kit.text(title, 16f).apply { layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(name)
        })
        addView(circles)
        circles.contentDescription = "$title: ${names(selected)}"
    }

    /** Redraws the swatches, for colours that follow something else (the theme's own). */
    fun refresh() = circles.refresh()

    @SuppressLint("ViewConstructor")
    private inner class Circles : View(context) {
        private val size = context.dp(34f)
        private val minGap = context.dp(10f)
        private val ringGap = context.dp(4f)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(2.5f) }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
        private var columns = 1
        private val shaders = arrayOfNulls<LinearGradient>(count)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val room = width - 2 * ringGap
            val most = maxOf(1, ((room + minGap) / (size + minGap)).toInt())
            // As many to a row as fit, spread evenly over as few rows as that needs.
            val rows = ceil(count / most.toFloat()).toInt()
            columns = ceil(count / rows.toFloat()).toInt()
            setMeasuredDimension(width, (rows * (size + 2 * ringGap) + (rows - 1) * minGap + context.dp(10f)).toInt())
        }

        private fun center(i: Int): Pair<Float, Float> {
            val cell = (width - 2 * ringGap) / columns.toFloat()
            val x = ringGap + cell * (i % columns + 0.5f)
            val y = context.dp(10f) + ringGap + size / 2f + (i / columns) * (size + 2 * ringGap + minGap)
            return x to y
        }

        fun refresh() {
            shaders.fill(null)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refresh()

        private fun shader(i: Int, x: Float, y: Float): LinearGradient {
            val r = size / 2f
            return shaders[i] ?: colors(i).let { LinearGradient(x - r, y - r, x + r, y + r, it[0], it[1], Shader.TileMode.CLAMP) }.also { shaders[i] = it }
        }

        override fun onDraw(canvas: Canvas) {
            for (i in 0 until count) {
                val (x, y) = center(i)
                val r = size / 2f
                fill.shader = shader(i, x, y)
                canvas.drawCircle(x, y, r, fill)
                fill.shader = null
                if (i == 0) Icons.draw(canvas, Icon.SPARKLE, x, y, size * 0.5f, colors(0)[2], icon)
                if (i == selected) {
                    ring.color = ringColor
                    canvas.drawCircle(x, y, r + ringGap - ring.strokeWidth / 2f + context.dp(1f), ring)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                for (i in 0 until count) {
                    val (x, y) = center(i)
                    if (hypot(event.x - x, event.y - y) <= size / 2f + minGap / 2f && i != selected) {
                        selected = i
                        name.text = names(i)
                        contentDescription = "$title: ${names(i)}"
                        invalidate()
                        onSelect(i)
                        break
                    }
                }
            }
            return true
        }
    }
}

/** A vertical column that stops growing at [maxWidth], so pages stay readable on large screens. */
@SuppressLint("ViewConstructor")
class Column(context: Context, private val maxWidth: Int) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (MeasureSpec.getSize(widthMeasureSpec) > maxWidth) {
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY)
        } else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}

/** Lays children out left to right, wrapping onto new lines. */
@SuppressLint("ViewConstructor")
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > width) {
                x = 0
                y += line + vGap
                line = 0
            }
            x += c.measuredWidth + hGap
            line = maxOf(line, c.measuredHeight)
        }
        setMeasuredDimension(width, y + line)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > width) {
                x = 0
                y += line + vGap
                line = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            line = maxOf(line, c.measuredHeight)
        }
    }
}
