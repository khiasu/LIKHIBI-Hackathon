package com.likhibi.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Draws the key preview bubble and the long-press alternates over the whole keyboard. It only
 * ever redraws; nothing is added or laid out while typing. It never takes touches.
 */
class KeyPopupOverlay(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var theme: KeyboardTheme = KeyboardThemes.light
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * resources.displayMetrics.density }
    private val previewText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val altText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var previewLabel: String? = null
    private val previewRect = RectF()

    private var alternates: List<Alt> = emptyList()
    private val altPanel = RectF()
    private var cellWidth = 0f
    private var highlight = 0
    private val icons = HashMap<Long, Drawable>()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    fun setStyle(theme: KeyboardTheme, fonts: KeyboardThemes.Fonts) {
        this.theme = theme
        previewText.typeface = fonts.regular
        previewText.textSize = sp(28f)
        altText.typeface = fonts.regular
        altText.textSize = sp(24f)
        icons.clear()
        invalidate()
    }

    fun showPreview(label: String, left: Float, top: Float, right: Float, bottom: Float) {
        val keyWidth = right - left
        val w = maxOf(keyWidth + 12f * d, 44f * d)
        val h = (bottom - top) + 14f * d
        var l = (left + right) / 2 - w / 2
        l = l.coerceIn(0f, maxOf(0f, width - w))
        val t = maxOf(0f, top - h - 4f * d)
        previewRect.set(l, t, l + w, t + h)
        previewLabel = label
        invalidate()
    }

    fun hidePreview() {
        if (previewLabel == null) return
        previewLabel = null
        invalidate()
    }

    /** Shows the alternates above the key, with the one under the finger highlighted. */
    fun showAlternates(alts: List<Alt>, keyLeft: Float, keyTop: Float, keyRight: Float, fingerX: Float) {
        alternates = alts
        cellWidth = 40f * d
        val pad = 4f * d
        val cellHeight = 48f * d
        val w = pad * 2 + alts.size * cellWidth
        val h = pad * 2 + cellHeight
        var l = keyLeft - pad
        if (l + w > width) l = width - w
        l = maxOf(0f, l)
        val t = maxOf(0f, keyTop - h - 4f * d)
        altPanel.set(l, t, l + w, t + h)
        highlight = indexAt(fingerX)
        invalidate()
    }

    fun moveAlternates(fingerX: Float) {
        if (alternates.isEmpty()) return
        val i = indexAt(fingerX)
        if (i != highlight) {
            highlight = i
            invalidate()
        }
    }

    fun selectedAlternate(): Alt? = alternates.getOrNull(highlight)

    fun hideAlternates() {
        if (alternates.isEmpty()) return
        alternates = emptyList()
        invalidate()
    }

    private fun indexAt(x: Float): Int =
        ((x - altPanel.left - 4f * d) / cellWidth).toInt().coerceIn(0, maxOf(0, alternates.size - 1))

    override fun onDraw(canvas: Canvas) {
        val radius = theme.keyRadiusDp * d
        val label = previewLabel
        if (label != null) {
            drawPanel(canvas, previewRect, radius)
            previewText.color = theme.keyLabel
            val m = previewText.fontMetrics
            canvas.drawText(label, previewRect.centerX(), previewRect.centerY() - (m.ascent + m.descent) / 2, previewText)
        }
        if (alternates.isNotEmpty()) {
            drawPanel(canvas, altPanel, radius)
            val pad = 4f * d
            alternates.forEachIndexed { i, alt ->
                val l = altPanel.left + pad + i * cellWidth
                val cell = RectF(l, altPanel.top + pad, l + cellWidth, altPanel.bottom - pad)
                val on = i == highlight
                if (on) {
                    fill.color = theme.accent
                    val r = maxOf(radius - 2f * d, 3f * d)
                    canvas.drawRoundRect(cell, r, r, fill)
                }
                val color = if (on) theme.onAccent else theme.keyLabel
                if (alt.icon != 0) {
                    val size = (22f * d).roundToInt()
                    val icon = icon(alt.icon, color)
                    val il = (cell.centerX() - size / 2f).roundToInt()
                    val it = (cell.centerY() - size / 2f).roundToInt()
                    icon.setBounds(il, it, il + size, it + size)
                    icon.draw(canvas)
                } else {
                    altText.color = color
                    val m = altText.fontMetrics
                    canvas.drawText(alt.label, cell.centerX(), cell.centerY() - (m.ascent + m.descent) / 2, altText)
                }
            }
        }
    }

    /** Popups float over other keys, so a see-through key colour gets a solid backing. */
    private fun drawPanel(canvas: Canvas, r: RectF, radius: Float) {
        fill.color = solid(theme.key)
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = if (Color.alpha(theme.keyBorder) > 0 && theme.keyBorder != theme.key) theme.keyBorder else theme.keyPressed
        canvas.drawRoundRect(r, radius, radius, stroke)
    }

    private fun solid(color: Int): Int {
        if (Color.alpha(color) == 255) return color
        val base = if (theme.usesPhoto) Color.rgb(58, 61, 56) else Color.DKGRAY
        val a = Color.alpha(color) / 255f
        return Color.rgb(
            (Color.red(base) + (Color.red(color) - Color.red(base)) * a).roundToInt(),
            (Color.green(base) + (Color.green(color) - Color.green(base)) * a).roundToInt(),
            (Color.blue(base) + (Color.blue(color) - Color.blue(base)) * a).roundToInt(),
        )
    }

    private fun icon(res: Int, color: Int): Drawable {
        val key = (res.toLong() shl 32) or (color.toLong() and 0xffffffffL)
        return icons.getOrPut(key) { ContextCompat.getDrawable(context, res)!!.mutate().also { it.setTint(color) } }
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}
