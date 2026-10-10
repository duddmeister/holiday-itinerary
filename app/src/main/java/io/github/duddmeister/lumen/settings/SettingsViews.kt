package io.github.duddmeister.lumen.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import io.github.duddmeister.lumen.ui.Colors
import io.github.duddmeister.lumen.ui.dp

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

    fun chips(title: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
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

    fun actionRow(title: String, subtitle: String?, action: String, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(16), dp(14))
        addView(texts(title, subtitle))
        addView(button(action, filled = false, onClick = onClick))
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
