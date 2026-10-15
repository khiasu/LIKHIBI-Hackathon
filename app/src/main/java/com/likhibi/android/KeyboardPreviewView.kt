package com.likhibi.android

import android.content.Context
import android.graphics.Outline
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.likhibi.keyboard.R
import kotlin.math.roundToInt

/**
 * A live, scaled-down keyboard: the real key grid drawn at phone width, then shrunk to fit.
 * Used for the theme gallery thumbnails and the theme editor preview. It never takes touches.
 */
class KeyboardPreviewView(context: Context) : ViewGroup(context) {
    private val virtualWidth = (412 * resources.displayMetrics.density).roundToInt()
    private var content: LinearLayout? = null
    var cornerRadiusPx = 0f
        set(value) {
            field = value
            clipToOutline = value > 0
            invalidateOutline()
        }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
        }
    }

    fun bind(theme: KeyboardTheme, figtree: Boolean? = null, keyHeightDp: Float? = null) {
        removeAllViews()
        val fonts = KeyboardThemes.Fonts(context, figtree ?: KeyboardThemes.usesFigtree(KeyboardThemes.prefs(context)))
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = KeyboardThemes.backgroundDrawable(context, theme)
        }
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            setBackgroundColor(theme.strip)
        }
        strip.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_kb_layout_grid)
            setColorFilter(theme.stripLabel)
            setPadding(dp(10f), dp(13f), dp(10f), dp(13f))
        }, LinearLayout.LayoutParams(dp(40f), dp(46f)))
        listOf("ase", "berabole", "bole").forEachIndexed { i, word ->
            if (i > 0) strip.addView(View(context).apply { setBackgroundColor(theme.keyBorder) }, LinearLayout.LayoutParams(dp(1f), dp(22f)))
            strip.addView(TextView(context).apply {
                text = word
                gravity = Gravity.CENTER
                setTextColor(theme.stripLabel)
                typeface = if (i == 1) fonts.semibold else fonts.regular
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(0, dp(46f), 1f))
        }
        column.addView(strip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52f)))
        column.addView(CustomKeyboardView(context).apply {
            previewMode = true
            themeOverride = theme
            fontOverride = figtree
            keyHeightOverride = keyHeightDp
            reload()
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content = column
        addView(column)
        requestLayout()
    }

    private fun scale(width: Int) = width.toFloat() / virtualWidth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val child = content
        if (child == null) {
            setMeasuredDimension(width, 0)
            return
        }
        child.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val natural = (child.measuredHeight * scale(width)).roundToInt()
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(natural, MeasureSpec.getSize(heightMeasureSpec))
            else -> natural
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = content ?: return
        child.layout(0, 0, virtualWidth, child.measuredHeight)
        val s = scale(r - l)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = s
        child.scaleY = s
        // Centre the scaled keyboard vertically when the box is taller than it.
        child.translationY = ((b - t) - child.measuredHeight * s).coerceAtLeast(0f) / 2
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = true
}
