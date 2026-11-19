package io.github.chris2crafter.thorstore

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Colors of the dark "Thor" look. */
object C {
    const val BG = 0xFF0D1117.toInt()
    const val CARD = 0xFF161B22.toInt()
    const val CARD_HI = 0xFF21262D.toInt()
    const val LINE = 0xFF30363D.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val SUB = 0xFF8B949E.toInt()
    const val ACCENT = 0xFF4EA8FF.toInt()
    const val ACCENT_HI = 0xFF79BDFF.toInt()
    const val OK = 0xFF3FB950.toInt()
    const val WARN = 0xFFF0883E.toInt()
    const val DANGER = 0xFFF85149.toInt()
    const val PURPLE = 0xFFA371F7.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

/** Background that lights up with a thick accent border when focused by the D-pad. */
fun Context.focusBg(normal: Int, focused: Int = C.CARD_HI, radius: Int = 12, border: Int = C.ACCENT): Drawable =
    StateListDrawable().apply {
        val hi = rounded(focused, dp(radius).toFloat(), dp(3), border)
        addState(intArrayOf(android.R.attr.state_focused), hi)
        addState(intArrayOf(android.R.attr.state_pressed), hi)
        addState(intArrayOf(android.R.attr.state_selected), hi)
        addState(intArrayOf(), rounded(normal, dp(radius).toFloat()))
    }

fun Context.text(
    s: CharSequence,
    size: Float = 15f,
    color: Int = C.TEXT,
    bold: Boolean = false,
    lines: Int = 0,
): TextView = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

fun Context.button(label: String, primary: Boolean = false, onClick: (View) -> Unit): TextView =
    text(label, 16f, if (primary) C.BG else C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = if (primary) focusBg(C.ACCENT, C.ACCENT_HI, 10, C.TEXT) else focusBg(C.CARD_HI, C.LINE, 10)
        isFocusable = true
        isClickable = true
        setOnClickListener(onClick)
    }

fun Context.chip(label: String, color: Int): TextView =
    text(label, 12f, color, bold = true).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = rounded((color and 0x00FFFFFF) or 0x26000000, dp(6).toFloat())
        maxLines = 1
    }

fun Context.row(vararg views: View, spacing: Int = 8): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEachIndexed { i, v ->
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (i > 0) marginStart = dp(spacing)
        })
    }
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
