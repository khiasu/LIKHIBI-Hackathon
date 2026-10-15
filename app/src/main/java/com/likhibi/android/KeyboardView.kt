package com.likhibi.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.likhibi.keyboard.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The key grid, drawn on one canvas. One view instead of forty means no layout passes while
 * typing; touch areas cover the gaps between keys, so fast typing never lands on nothing.
 *
 * Touch behaviour follows Gboard: a character is typed when its key is released, a second
 * finger releases the first key at once (so rollover typing keeps its order), sliding to a
 * neighbouring key before lifting changes the letter, and holding a key offers alternates.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        /** A character (or text) to type. */
        fun onKeyText(text: String)
        /** A key that is an action (shift, delete, enter, mode switches, tools). */
        fun onKeyCode(code: Int)
        /** The space bar was dragged: move the cursor this many characters (negative is left). */
        fun onCursorMove(steps: Int)
    }

    enum class Mode { LETTERS, SYMBOLS, EXTRA_SYMBOLS, SEARCH }

    var listener: Listener? = null
    var overlay: KeyPopupOverlay? = null
    var feedback: KeyFeedback? = null
    /** Theme thumbnails: draw, but never take touches. */
    var previewMode = false

    var mode = Mode.LETTERS
        private set
    private var shifted = false
    private var capsLock = false
    private var enterIcon = R.drawable.ic_kb_corner_down_left
    private var enterDescription = "Enter"
    private var numberRowSuppressed = false

    private lateinit var settings: KeyboardSettings
    private lateinit var fonts: KeyboardThemes.Fonts
    private var rows: List<Row> = emptyList()
    private var laidOutWidth = -1
    private var contentHeight = 0

    private val d = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val functionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val rect = RectF()
    private val icons = HashMap<Long, Drawable>()
    private val accessibility = KeyAccessibility()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
    }

    // ------------------------------------------------------------------ configuration

    /** Applies new settings (theme, sizes, toggles) and redraws. */
    fun configure(settings: KeyboardSettings) {
        this.settings = settings
        fonts = KeyboardThemes.Fonts(context, settings.figtree)
        letterPaint.typeface = fonts.regular
        letterPaint.textSize = sp(if (settings.keyHeightDp < 44f) 21f else 23f)
        functionPaint.typeface = fonts.medium
        functionPaint.textSize = sp(14f)
        spacePaint.typeface = fonts.medium
        spacePaint.textSize = sp(13f)
        hintPaint.typeface = fonts.regular
        hintPaint.textSize = sp(10f)
        icons.clear()
        overlay?.setStyle(settings.theme, fonts)
        rebuild()
    }

    fun setMode(mode: Mode) {
        if (this.mode == mode) return
        this.mode = mode
        rebuild()
    }

    fun setShift(shifted: Boolean, capsLock: Boolean) {
        if (this.shifted == shifted && this.capsLock == capsLock) return
        this.shifted = shifted
        this.capsLock = capsLock
        invalidate()
    }

    fun isShifted() = shifted || capsLock
    fun isCapsLock() = capsLock

    fun setEnter(icon: Int, description: String) {
        if (enterIcon == icon && enterDescription == description) return
        enterIcon = icon
        enterDescription = description
        rebuild()
    }

    /** The translate strip hides the number row to keep the keyboard short. */
    fun setNumberRowSuppressed(suppressed: Boolean) {
        if (numberRowSuppressed == suppressed) return
        numberRowSuppressed = suppressed
        rebuild()
    }

    private fun options() = LayoutOptions(
        numberRow = settings.numberRow && !numberRowSuppressed,
        emojiKey = settings.emojiKey,
        longPressSymbols = settings.longPressSymbols,
        enterIcon = enterIcon,
        enterDescription = enterDescription,
        icons = LayoutOptions.Icons(
            shift = R.drawable.ic_kb_arrow_big_up, delete = R.drawable.ic_kb_delete, emoji = R.drawable.ic_kb_smile,
            settings = R.drawable.ic_kb_settings, oneHanded = R.drawable.ic_kb_hand, clipboard = R.drawable.ic_kb_clipboard,
        ),
    )

    private fun rebuild() {
        cancelAllTouches()
        val o = options()
        rows = when (mode) {
            Mode.LETTERS -> KeyboardLayouts.letters(o)
            Mode.SYMBOLS -> KeyboardLayouts.symbols(o)
            Mode.EXTRA_SYMBOLS -> KeyboardLayouts.extraSymbols(o)
            Mode.SEARCH -> KeyboardLayouts.search(o)
        }
        laidOutWidth = -1
        val before = contentHeight
        if (width > 0) layoutKeys(width)
        if (contentHeight != before || width == 0) requestLayout()
        accessibility.invalidateRoot()
        invalidate()
    }

    private fun layoutKeys(width: Int) {
        contentHeight = KeyboardLayouts.place(
            rows, width.toFloat(), settings.keyHeightDp * d, settings.rowGapDp * d,
            keyGap = 6f * d, side = 3f * d, top = 6f * d, bottom = 8f * d,
        ).roundToInt()
        laidOutWidth = width
    }

    /** Height of the letters layout as typed now, so panels can take exactly its place. */
    fun lettersHeight(width: Int): Int {
        val rowsCount = 4 + if (settings.numberRow && !numberRowSuppressed) 1 else 0
        return (6f * d + rowsCount * settings.keyHeightDp * d + (rowsCount - 1) * settings.rowGapDp * d + 8f * d).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (!::settings.isInitialized) {
            setMeasuredDimension(width, 0)
            return
        }
        if (width > 0 && width != laidOutWidth) layoutKeys(width)
        setMeasuredDimension(width, contentHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != laidOutWidth && w > 0 && ::settings.isInitialized) layoutKeys(w)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        if (!::settings.isInitialized) return
        val t = settings.theme
        val radius = t.keyRadiusDp * d
        val border = t.keyBorderWidthDp * d
        stroke.strokeWidth = border
        stroke.color = t.keyBorder
        val upper = (shifted || capsLock) && mode == Mode.LETTERS
        for (row in rows) for (key in row.keys) {
            val pressed = key in pressedKeys
            val accent = key.type == KeyType.ENTER || (key.type == KeyType.SHIFT && capsLock)
            fill.color = when {
                accent -> if (pressed) blend(t.accent, t.onAccent, 0.18f) else t.accent
                pressed -> t.keyPressed
                key.type == KeyType.CHAR || key.type == KeyType.SPACE -> t.key
                else -> t.keyFunction
            }
            rect.set(key.left, key.top, key.right, key.bottom)
            if (Color.alpha(fill.color) > 0) canvas.drawRoundRect(rect, radius, radius, fill)
            if (border > 0 && Color.alpha(t.keyBorder) > 0) {
                rect.inset(border / 2, border / 2)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
            val labelColor = if (accent) t.onAccent else t.keyLabel
            when {
                key.icon != 0 -> {
                    val res = when {
                        key.type == KeyType.SHIFT && (shifted || capsLock) -> R.drawable.ic_kb_arrow_big_up_filled
                        key.type == KeyType.ENTER -> enterIcon
                        else -> key.icon
                    }
                    val size = (if (key.type == KeyType.FUNCTION) 22f else 20f) * d
                    val icon = icon(res, labelColor)
                    val l = (key.centerX - size / 2).roundToInt()
                    val tp = (key.centerY - size / 2).roundToInt()
                    icon.setBounds(l, tp, l + size.roundToInt(), tp + size.roundToInt())
                    icon.draw(canvas)
                }
                key.type == KeyType.SPACE -> drawCentered(canvas, key.label, key, spacePaint, t.keyLabelSecondary)
                key.type == KeyType.CHAR -> drawCentered(canvas, if (upper) key.upper else key.label, key, letterPaint, labelColor)
                else -> drawCentered(canvas, key.label, key, functionPaint, labelColor)
            }
            val hint = key.hint
            if (hint != null) {
                hintPaint.color = t.keyLabelSecondary
                canvas.drawText(hint, key.right - 4f * d, key.top + 4f * d + hintPaint.textSize * 0.8f, hintPaint)
            }
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, key: Key, paint: Paint, color: Int) {
        if (text.isEmpty()) return
        paint.color = color
        val metrics = paint.fontMetrics
        canvas.drawText(text, key.centerX, key.centerY - (metrics.ascent + metrics.descent) / 2, paint)
    }

    private fun icon(res: Int, color: Int): Drawable {
        val cacheKey = (res.toLong() shl 32) or (color.toLong() and 0xffffffffL)
        return icons.getOrPut(cacheKey) { ContextCompat.getDrawable(context, res)!!.mutate().also { it.setTint(color) } }
    }

    // ------------------------------------------------------------------ touch

    private inner class Pointer(val id: Int, var key: Key, val downX: Float) {
        var anchorX = downX
        var consumed = false
        var alternates = false
        var cursorMode = false
        var longPressed = false
        var timer: Runnable? = null
    }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private val pressedKeys = HashSet<Key>()
    private var previewOwner = -1

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (previewMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                down(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                pointers[event.getPointerId(i)]?.let { move(it, event.getX(i), event.getY(i)) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointers.remove(event.getPointerId(event.actionIndex))?.let { up(it, cancelled = false) }
            }
            MotionEvent.ACTION_CANCEL -> cancelAllTouches()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        // Rollover: a new finger releases any letter still held, so letters arrive in order.
        for (other in pointers.values) {
            if (other.key.type == KeyType.CHAR && !other.consumed && !other.alternates) {
                type(other.key)
                other.consumed = true
                cancelTimer(other)
                pressedKeys.remove(other.key)
                if (previewOwner == other.id) hidePreview()
            }
        }
        val key = KeyboardLayouts.keyAt(rows, x, y) ?: return
        val p = Pointer(id, key, x)
        pointers[id] = p
        pressedKeys.add(key)
        feedback?.press(
            when (key.type) {
                KeyType.SPACE -> KeyFeedback.Kind.SPACE
                KeyType.ENTER -> KeyFeedback.Kind.ENTER
                KeyType.CHAR -> KeyFeedback.Kind.STANDARD
                else -> KeyFeedback.Kind.FUNCTION
            },
            settings,
        )
        when (key.type) {
            KeyType.CHAR -> {
                showPreview(p)
                if (key.alternates.isNotEmpty()) schedule(p, LONG_PRESS_MS) { openAlternates(p) }
            }
            KeyType.SPACE -> schedule(p, SPACE_LONG_PRESS_MS) {
                p.longPressed = true
                listener?.onKeyCode(KeyboardLayouts.CODE_SWITCH_IME)
            }
            KeyType.DELETE -> {
                listener?.onKeyCode(KeyboardLayouts.CODE_DELETE)
                schedule(p, REPEAT_START_MS, ::repeatDelete)
            }
            KeyType.SHIFT, KeyType.FUNCTION -> listener?.onKeyCode(key.code)
            KeyType.ENTER -> Unit
        }
        invalidate()
    }

    private fun move(p: Pointer, x: Float, y: Float) {
        when {
            p.alternates -> overlay?.moveAlternates(x + offsetX())
            p.key.type == KeyType.SPACE && !p.longPressed -> {
                val step = (p.key.right - p.key.left) / 9f
                if (!p.cursorMode && abs(x - p.downX) > 12f * d) {
                    p.cursorMode = true
                    p.anchorX = x
                    cancelTimer(p)
                }
                if (p.cursorMode) {
                    val steps = ((x - p.anchorX) / step).toInt()
                    if (steps != 0) {
                        listener?.onCursorMove(steps)
                        p.anchorX += steps * step
                    }
                }
            }
            p.key.type == KeyType.CHAR && !p.consumed -> {
                // Sliding before lifting moves to the neighbouring letter, as on Gboard.
                val now = KeyboardLayouts.keyAt(rows, x, y)
                if (now != null && now !== p.key && now.type == KeyType.CHAR) {
                    pressedKeys.remove(p.key)
                    p.key = now
                    pressedKeys.add(now)
                    cancelTimer(p)
                    showPreview(p)
                    if (now.alternates.isNotEmpty()) schedule(p, LONG_PRESS_MS) { openAlternates(p) }
                    invalidate()
                }
            }
        }
    }

    private fun up(p: Pointer, cancelled: Boolean) {
        cancelTimer(p)
        pressedKeys.remove(p.key)
        if (!cancelled) when (p.key.type) {
            KeyType.CHAR -> if (!p.consumed) {
                if (p.alternates) {
                    overlay?.selectedAlternate()?.let { alt ->
                        if (alt.code < 0) listener?.onKeyCode(alt.code) else listener?.onKeyText(cased(alt.label))
                    }
                } else {
                    type(p.key)
                }
            }
            KeyType.SPACE -> if (!p.cursorMode && !p.longPressed) listener?.onKeyCode(KeyboardLayouts.CODE_SPACE)
            KeyType.ENTER -> listener?.onKeyCode(KeyboardLayouts.CODE_ENTER)
            else -> Unit
        }
        if (p.alternates) overlay?.hideAlternates()
        if (previewOwner == p.id) hidePreview()
        invalidate()
    }

    private fun type(key: Key) {
        listener?.onKeyText(if ((shifted || capsLock) && mode == Mode.LETTERS) key.upper else key.label)
    }

    private fun cased(text: String) = if ((shifted || capsLock) && mode == Mode.LETTERS) text.uppercase() else text

    private fun openAlternates(p: Pointer) {
        p.alternates = true
        hidePreview()
        feedback?.press(KeyFeedback.Kind.FUNCTION, settings)
        val alts = p.key.alternates.map { if (it.icon == 0) it.copy(label = cased(it.label)) else it }
        overlay?.showAlternates(alts, p.key.left + offsetX(), p.key.top + offsetY(), p.key.right + offsetX(), p.anchorX + offsetX())
    }

    private fun repeatDelete() {
        val p = pointers.values.firstOrNull { it.key.type == KeyType.DELETE } ?: return
        listener?.onKeyCode(KeyboardLayouts.CODE_DELETE)
        feedback?.press(KeyFeedback.Kind.FUNCTION, settings)
        schedule(p, REPEAT_MS, ::repeatDelete)
    }

    private fun schedule(p: Pointer, delay: Long, action: () -> Unit) {
        cancelTimer(p)
        p.timer = Runnable { if (pointers[p.id] === p) action() }.also { handler.postDelayed(it, delay) }
    }

    private fun cancelTimer(p: Pointer) {
        p.timer?.let { handler.removeCallbacks(it) }
        p.timer = null
    }

    private fun cancelAllTouches() {
        for (p in pointers.values) cancelTimer(p)
        pointers.clear()
        pressedKeys.clear()
        hidePreview()
        overlay?.hideAlternates()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllTouches()
    }

    // ------------------------------------------------------------------ popups

    private val location = IntArray(2)
    private val overlayLocation = IntArray(2)

    private fun offsetX(): Float {
        val o = overlay ?: return 0f
        getLocationInWindow(location)
        o.getLocationInWindow(overlayLocation)
        return (location[0] - overlayLocation[0]).toFloat()
    }

    private fun offsetY(): Float {
        val o = overlay ?: return 0f
        getLocationInWindow(location)
        o.getLocationInWindow(overlayLocation)
        return (location[1] - overlayLocation[1]).toFloat()
    }

    private fun showPreview(p: Pointer) {
        if (!settings.keyPopup) return
        val key = p.key
        val label = if ((shifted || capsLock) && mode == Mode.LETTERS) key.upper else key.label
        val ox = offsetX()
        val oy = offsetY()
        overlay?.showPreview(label, key.left + ox, key.top + oy, key.right + ox, key.bottom + oy)
        previewOwner = p.id
    }

    private fun hidePreview() {
        overlay?.hidePreview()
        previewOwner = -1
    }

    // ------------------------------------------------------------------ helpers

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )

    /** Keys as virtual views, so TalkBack can read and press them. */
    private inner class KeyAccessibility : ExploreByTouchHelper(this) {
        private fun all(): List<Key> = rows.flatMap { it.keys }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val key = KeyboardLayouts.keyAt(rows, x, y) ?: return INVALID_ID
            return all().indexOf(key)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            all().indices.forEach { virtualViewIds.add(it) }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val key = all().getOrNull(id)
            if (key == null) {
                node.contentDescription = ""
                @Suppress("DEPRECATION")
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = when {
                key.type == KeyType.SHIFT -> if (capsLock) "Shift, caps lock on" else if (shifted) "Shift, on" else "Shift"
                key.type == KeyType.ENTER -> enterDescription
                key.type == KeyType.CHAR -> if (shifted || capsLock) key.upper else key.label
                else -> key.description
            }
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(key.left.roundToInt(), key.top.roundToInt(), key.right.roundToInt(), key.bottom.roundToInt()))
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val key = all().getOrNull(id) ?: return false
            when (key.type) {
                KeyType.CHAR -> type(key)
                KeyType.SPACE -> listener?.onKeyCode(KeyboardLayouts.CODE_SPACE)
                KeyType.ENTER -> listener?.onKeyCode(KeyboardLayouts.CODE_ENTER)
                else -> listener?.onKeyCode(key.code)
            }
            return true
        }
    }

    companion object {
        private const val LONG_PRESS_MS = 330L
        private const val SPACE_LONG_PRESS_MS = 550L
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_MS = 45L
    }
}
