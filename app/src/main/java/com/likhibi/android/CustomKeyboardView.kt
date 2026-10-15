package com.likhibi.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.likhibi.keyboard.R
import kotlin.math.roundToInt

/**
 * The key grid, and the clipboard and emoji panels that take its place. Built from the
 * Figma "Keyboard" page (design/SPECS.md): sizes in dp, colours from [KeyboardTheme].
 */
class CustomKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnKeyActionListener {
        fun onKey(code: Int)
        fun onText(text: String)
        /** Replaces the character just typed with an alternate chosen by long-press. */
        fun onAlternate(text: String)
    }

    enum class Mode { QWERTY, SYMBOLS, EXTRA_SYMBOLS }
    enum class ViewState { KEYS, CLIPBOARD, EMOJI_SHELF }
    enum class EnterAction { NEWLINE, SEARCH, SEND, GO, NEXT, DONE }
    enum class HapticType { KEY_STANDARD, KEY_SPACE, KEY_MODIFIER, KEY_ACCENT }

    companion object {
        const val DEFAULT_THEME = KeyboardThemes.DEFAULT_THEME
        const val DEFAULT_HAPTIC_MS = 10
        const val DEFAULT_SOUND_ENABLED = false

        const val CODE_SHIFT = -1
        const val CODE_SYMBOLS = -2
        const val CODE_EXTRA_SYMBOLS = -3
        const val CODE_SYMBOLS_BACK = -4
        const val CODE_DELETE = -5
        const val CODE_EMOJI = -10
        const val CODE_SWITCH_IME = -11
        const val CODE_CURSOR_RIGHT = Int.MAX_VALUE
        const val CODE_CURSOR_LEFT = Int.MIN_VALUE

        /** Height of the strip above the keys (suggestions, toolbar). */
        const val STRIP_HEIGHT_DP = 52f

        private const val LONG_PRESS_MS = 380L
        private const val SPACE_LONG_PRESS_MS = 450L

        private val ACCENTS = mapOf(
            "a" to listOf("à", "á", "â", "ä", "ã", "å", "ā"),
            "e" to listOf("é", "è", "ê", "ë", "ē"),
            "i" to listOf("í", "ì", "î", "ï"),
            "o" to listOf("ó", "ò", "ô", "ö", "õ", "ō"),
            "u" to listOf("ú", "ù", "û", "ü", "ū"),
            "n" to listOf("ñ"),
            "c" to listOf("ç"),
            "y" to listOf("ý"),
        )
        private val ROW1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        private val ROW2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        private val ROW3 = listOf("z", "x", "c", "v", "b", "n", "m")
        private val DIGITS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        // Long-press symbols, as on the symbols layer: a-row ~ row 2, z-row ~ row 3.
        private val ROW2_SYMBOLS = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")")
        private val ROW3_SYMBOLS = listOf("*", "\"", "'", ":", ";", "!", "?")
        private val PERIOD_ALTERNATES = listOf("?", "!", ",", ":", ";", "-", "'", "\"")
    }

    private enum class Kind { CHAR, FUNCTION, SPACE, ENTER, SPACER }

    private data class Key(
        val label: String,
        val code: Int,
        val width: Float,
        val kind: Kind = Kind.CHAR,
        val icon: Int = 0,
        val hint: String? = null,
        val alternates: List<String> = emptyList(),
        val description: String? = null,
    )

    var listener: OnKeyActionListener? = null
    /** Told when a panel replaces the keys, so the strip above can hide. */
    var onPanelChanged: ((ViewState) -> Unit)? = null
    /** Where key previews and long-press popups are drawn (a layer over the whole keyboard). */
    var popupLayer: ViewGroup? = null

    var isCapsLock = false
    var enterAction = EnterAction.NEWLINE
        set(value) {
            if (field != value) {
                field = value
                if (viewState == ViewState.KEYS) buildKeys()
            }
        }
    /** The translate strip hides the number row to keep the keyboard short. */
    var numberRowSuppressed = false
        set(value) {
            if (field != value) {
                field = value
                if (viewState == ViewState.KEYS) buildKeys()
            }
        }

    /** For theme thumbnails: draw this theme instead of the saved one, and ignore touches. */
    var themeOverride: KeyboardTheme? = null
    var previewMode = false
    /** Theme editor previews: Figtree or not, and key height, before they are saved. */
    var fontOverride: Boolean? = null
    var keyHeightOverride: Float? = null

    private val prefs = KeyboardThemes.prefs(context)
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val clipboard = ClipboardStore(context)

    private lateinit var theme: KeyboardTheme
    private lateinit var fonts: KeyboardThemes.Fonts
    private var keyHeightDp = 46f
    private var showNumberRow = true
    private var showEmojiKey = false
    private var keyPopups = true
    private var longPressSymbols = true

    private var isShifted = false
    private var keyboardMode = Mode.QWERTY
    private var viewState = ViewState.KEYS
    private val letterKeys = mutableListOf<Pair<TextView, String>>()
    private var shiftKey: KeyCapView? = null
    private val backgroundCache = HashMap<String, Drawable.ConstantState>()

    private var soundPool: SoundPool? = null
    private var soundClick = 0
    private var soundSpace = 0

    private var emojiSearch: StringBuilder? = null

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        reload()
    }

    // ------------------------------------------------------------------ settings and theme

    /** Re-reads the theme and keyboard settings and redraws whatever is showing. */
    fun reload() {
        theme = themeOverride ?: KeyboardThemes.current(context)
        fonts = KeyboardThemes.Fonts(context, fontOverride ?: KeyboardThemes.usesFigtree(prefs))
        keyHeightDp = keyHeightOverride ?: KeyboardThemes.keyHeightDp(prefs)
        showNumberRow = previewMode || prefs.getBoolean("show_number_row", true)
        showEmojiKey = !previewMode && prefs.getBoolean("show_emoji_key", false)
        keyPopups = !previewMode && prefs.getBoolean("key_popup", true)
        longPressSymbols = prefs.getBoolean("long_press_symbols", true)
        backgroundCache.clear()
        when (viewState) {
            ViewState.KEYS -> buildKeys()
            ViewState.CLIPBOARD -> showClipboard()
            ViewState.EMOJI_SHELF -> showEmojiShelf()
        }
    }

    /** Kept for callers that changed the theme: same as [reload]. */
    fun applyTheme(@Suppress("UNUSED_PARAMETER") forceRebuild: Boolean = true) = reload()

    fun currentTheme(): KeyboardTheme = theme

    fun setShifted(shifted: Boolean) {
        if (isShifted == shifted && shiftKey?.tag == isCapsLock) return
        isShifted = shifted
        if (keyboardMode == Mode.QWERTY && viewState == ViewState.KEYS && emojiSearch == null) {
            for ((tv, base) in letterKeys) tv.text = if (isShifted) base.uppercase() else base
            shiftKey?.let { styleShift(it) }
        }
    }

    fun isShifted(): Boolean = isShifted
    fun getMode(): Mode = keyboardMode
    fun getViewState(): ViewState = viewState

    fun switchMode(mode: Mode) {
        if (keyboardMode != mode || viewState != ViewState.KEYS) {
            keyboardMode = mode
            buildKeys()
        }
    }

    fun resetState(mode: Mode, shifted: Boolean) {
        keyboardMode = mode
        isShifted = shifted
        isCapsLock = false
        emojiSearch = null
        buildKeys()
    }

    /** Height of the strip plus the letters layout, so panels open without the keyboard jumping. */
    fun typingHeightPx(): Int {
        val rows = if (showNumberRow && !numberRowSuppressed) 5 else 4
        return dp(STRIP_HEIGHT_DP) + rows * (dp(keyHeightDp) + dp(theme.keyGapDp)) + dp(6f)
    }

    // ------------------------------------------------------------------ key grid

    private fun rows(): List<List<Key>> {
        val bottomLeft = when (keyboardMode) {
            Mode.QWERTY -> Key("?123", CODE_SYMBOLS, 52.8f, Kind.FUNCTION, description = "Symbols")
            else -> Key("ABC", CODE_SYMBOLS, 52.8f, Kind.FUNCTION, description = "Letters")
        }
        val bottom = buildList {
            add(bottomLeft)
            add(Key(",", ','.code, 35.2f))
            if (showEmojiKey) add(Key("", CODE_EMOJI, 35.2f, Kind.FUNCTION, icon = R.drawable.ic_kb_smile, description = "Emoji"))
            add(Key("Likhibi", ' '.code, if (showEmojiKey) 164.8f else 206f, Kind.SPACE, description = "Space"))
            add(Key(".", '.'.code, 35.2f, alternates = PERIOD_ALTERNATES))
            add(Key("", '\n'.code, 52.8f, Kind.ENTER, icon = enterIcon(), description = enterDescription()))
        }
        val backspace = Key("", CODE_DELETE, 55.8f, Kind.FUNCTION, icon = R.drawable.ic_kb_delete, description = "Delete")

        return when (keyboardMode) {
            Mode.QWERTY -> buildList {
                val numberRow = showNumberRow && !numberRowSuppressed
                if (numberRow) add(DIGITS.map { Key(it, it[0].code, 35.2f) })
                add(ROW1.mapIndexed { i, letter ->
                    val digit = if (longPressSymbols && !numberRow) DIGITS[i] else null
                    Key(letter, letter[0].code, 35.2f, hint = digit, alternates = listOfNotNull(digit) + ACCENTS[letter].orEmpty())
                })
                add(listOf(Key("", 0, 20.6f, Kind.SPACER)) + ROW2.mapIndexed { i, letter ->
                    Key(letter, letter[0].code, 35.2f, alternates = listOfNotNull(ROW2_SYMBOLS[i].takeIf { longPressSymbols }) + ACCENTS[letter].orEmpty())
                } + Key("", 0, 20.6f, Kind.SPACER))
                add(listOf(Key("", CODE_SHIFT, 55.8f, Kind.FUNCTION, icon = R.drawable.ic_kb_arrow_big_up, description = "Shift")) +
                    ROW3.mapIndexed { i, letter ->
                        Key(letter, letter[0].code, 35.2f, alternates = listOfNotNull(ROW3_SYMBOLS[i].takeIf { longPressSymbols }) + ACCENTS[letter].orEmpty())
                    } + backspace)
                add(bottom)
            }
            Mode.SYMBOLS -> listOf(
                DIGITS.map { Key(it, it[0].code, 35.2f) },
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map { Key(it, it[0].code, 35.2f) },
                listOf(Key("=\\<", CODE_EXTRA_SYMBOLS, 55.8f, Kind.FUNCTION, description = "More symbols")) +
                    listOf("*", "\"", "'", ":", ";", "!", "?").map { Key(it, it[0].code, 35.2f) } + backspace,
                bottom,
            )
            Mode.EXTRA_SYMBOLS -> listOf(
                listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { Key(it, it[0].code, 35.2f) },
                listOf("£", "¥", "€", "¢", "^", "°", "=", "{", "}", "\\").map { Key(it, it[0].code, 35.2f) },
                listOf(Key("?123", CODE_SYMBOLS_BACK, 55.8f, Kind.FUNCTION, description = "Symbols")) +
                    listOf("%", "©", "<", ">", "[", "]", "§").map { Key(it, it[0].code, 35.2f) } + backspace,
                bottom,
            )
        }
    }

    private fun enterIcon(): Int = when (enterAction) {
        EnterAction.SEARCH -> R.drawable.ic_kb_search
        EnterAction.SEND -> R.drawable.ic_kb_send
        EnterAction.GO, EnterAction.NEXT -> R.drawable.ic_kb_arrow_right
        EnterAction.DONE -> R.drawable.ic_kb_check
        EnterAction.NEWLINE -> R.drawable.ic_kb_corner_down_left
    }

    private fun enterDescription(): String = when (enterAction) {
        EnterAction.SEARCH -> "Search"
        EnterAction.SEND -> "Send"
        EnterAction.GO -> "Go"
        EnterAction.NEXT -> "Next"
        EnterAction.DONE -> "Done"
        EnterAction.NEWLINE -> "Enter"
    }

    private fun buildKeys() {
        dismissPopups()
        removeAllViews()
        letterKeys.clear()
        shiftKey = null
        emojiSearch = null
        val changed = viewState != ViewState.KEYS
        viewState = ViewState.KEYS
        setPadding(0, dp(3f), 0, dp(3f))
        setPanelHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
        for (row in rows()) addView(buildRow(row, dp(keyHeightDp)))
        if (changed) onPanelChanged?.invoke(viewState)
    }

    private fun buildRow(row: List<Key>, keyHeight: Int, onKeyOverride: ((Key) -> Unit)? = null): LinearLayout {
        val half = dp(theme.keyGapDp / 2)
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyHeight + 2 * half)
            for (key in row) {
                if (key.kind == Kind.SPACER) {
                    addView(View(context), LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, key.width))
                } else {
                    addView(createKey(key, onKeyOverride), LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, key.width).apply {
                        setMargins(half, half, half, half)
                    })
                }
            }
        }
    }

    private fun createKey(key: Key, onKeyOverride: ((Key) -> Unit)?): View {
        val view = KeyCapView(context)
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        view.isSoundEffectsEnabled = false
        view.contentDescription = key.description
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        when (key.kind) {
            Kind.CHAR -> {
                view.text = if (isShifted && keyboardMode == Mode.QWERTY) key.label.uppercase() else key.label
                view.typeface = fonts.regular
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                view.setTextColor(theme.keyLabel)
                view.background = keyBackground(theme.key, theme.keyPressed)
                if (key.label.length == 1 && key.label[0].isLetter()) letterKeys.add(view to key.label)
                key.hint?.let { view.setHint(it, theme.keyLabelSecondary, fonts.regular, sp(10f)) }
            }
            Kind.FUNCTION -> {
                view.text = key.label
                view.typeface = fonts.medium
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                view.setTextColor(theme.keyLabel)
                view.background = keyBackground(theme.keyFunction, theme.keyPressed)
                if (key.icon != 0) view.setIcon(key.icon, theme.keyLabel, dp(20f))
                if (key.code == CODE_SHIFT) {
                    shiftKey = view
                    styleShift(view)
                }
            }
            Kind.SPACE -> {
                view.text = key.label
                view.typeface = fonts.medium
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                view.setTextColor(theme.keyLabelSecondary)
                view.background = keyBackground(theme.key, theme.keyPressed)
            }
            Kind.ENTER -> {
                view.background = keyBackground(theme.accent, blend(theme.accent, theme.onAccent, 0.18f))
                view.setIcon(key.icon, theme.onAccent, dp(20f))
            }
            Kind.SPACER -> Unit
        }
        if (!previewMode) attachTouch(view, key, onKeyOverride)
        return view
    }

    private fun styleShift(view: KeyCapView) {
        view.tag = isCapsLock
        val icon = if (isShifted || isCapsLock) R.drawable.ic_kb_arrow_big_up_filled else R.drawable.ic_kb_arrow_big_up
        if (isCapsLock) {
            view.background = keyBackground(theme.accent, blend(theme.accent, theme.onAccent, 0.18f))
            view.setIcon(icon, theme.onAccent, dp(20f))
        } else {
            view.background = keyBackground(theme.keyFunction, theme.keyPressed)
            view.setIcon(icon, theme.keyLabel, dp(20f))
        }
        view.contentDescription = when {
            isCapsLock -> "Shift, caps lock on"
            isShifted -> "Shift, on"
            else -> "Shift"
        }
    }

    // ------------------------------------------------------------------ touch

    private fun attachTouch(view: KeyCapView, key: Key, onKeyOverride: ((Key) -> Unit)?) {
        var longPress: Runnable? = null
        var repeat: Runnable? = null
        var alternatesShown = false
        var typedUpper = false
        var spaceStartX = 0f
        var spaceDragged = false
        var spaceLongPressed = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptic(when (key.kind) {
                        Kind.ENTER -> HapticType.KEY_ACCENT
                        Kind.SPACE -> HapticType.KEY_SPACE
                        Kind.FUNCTION -> HapticType.KEY_MODIFIER
                        else -> HapticType.KEY_STANDARD
                    })
                    when (key.kind) {
                        Kind.SPACE -> {
                            spaceStartX = event.rawX
                            spaceDragged = false
                            spaceLongPressed = false
                            if (onKeyOverride == null) {
                                longPress = Runnable {
                                    spaceLongPressed = true
                                    haptic(HapticType.KEY_ACCENT)
                                    listener?.onKey(CODE_SWITCH_IME)
                                }.also { handler.postDelayed(it, SPACE_LONG_PRESS_MS) }
                            }
                        }
                        Kind.CHAR -> {
                            typedUpper = isShifted && keyboardMode == Mode.QWERTY
                            dispatch(key, onKeyOverride)
                            if (keyPopups) showPreview(v, (v as TextView).text.toString())
                            if (key.alternates.isNotEmpty() && onKeyOverride == null) {
                                alternatesShown = false
                                longPress = Runnable {
                                    hidePreview()
                                    alternatesShown = true
                                    haptic(HapticType.KEY_MODIFIER)
                                    showAlternates(v, key.alternates.map { if (typedUpper) it.uppercase() else it })
                                }.also { handler.postDelayed(it, LONG_PRESS_MS) }
                            }
                        }
                        else -> {
                            dispatch(key, onKeyOverride)
                            if (key.code == CODE_DELETE) {
                                repeat = object : Runnable {
                                    override fun run() {
                                        dispatch(key, onKeyOverride)
                                        haptic(HapticType.KEY_MODIFIER)
                                        handler.postDelayed(this, 50L)
                                    }
                                }.also { handler.postDelayed(it, 320L) }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (alternatesShown) {
                        moveAlternateHighlight(event.rawX)
                    } else if (key.kind == Kind.SPACE && onKeyOverride == null && !spaceLongPressed) {
                        val dx = event.rawX - spaceStartX
                        if (Math.abs(dx) > dp(18f)) {
                            spaceDragged = true
                            longPress?.let { handler.removeCallbacks(it) }
                            listener?.onKey(if (dx > 0) CODE_CURSOR_RIGHT else CODE_CURSOR_LEFT)
                            spaceStartX = event.rawX
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    longPress?.let { handler.removeCallbacks(it) }
                    longPress = null
                    repeat?.let { handler.removeCallbacks(it) }
                    repeat = null
                    hidePreview()
                    if (alternatesShown) {
                        val chosen = selectedAlternate()
                        hideAlternates()
                        alternatesShown = false
                        if (event.actionMasked == MotionEvent.ACTION_UP && chosen != null) listener?.onAlternate(chosen)
                    }
                    if (key.kind == Kind.SPACE && event.actionMasked == MotionEvent.ACTION_UP && !spaceDragged && !spaceLongPressed) {
                        dispatch(key, onKeyOverride)
                    }
                }
            }
            true
        }
    }

    private fun dispatch(key: Key, onKeyOverride: ((Key) -> Unit)?) {
        if (onKeyOverride != null) onKeyOverride(key) else listener?.onKey(key.code)
    }

    private fun haptic(type: HapticType) {
        if (previewMode) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (prefs.getBoolean("haptic_enabled", true)) {
            val strength = prefs.getInt("haptic_strength", DEFAULT_HAPTIC_MS)
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (strength > 0 && vibrator != null) {
                val duration = when (type) {
                    HapticType.KEY_STANDARD -> strength.toLong()
                    HapticType.KEY_SPACE -> (strength * 1.3).toLong()
                    HapticType.KEY_MODIFIER -> (strength * 1.2).toLong()
                    HapticType.KEY_ACCENT -> (strength * 1.5).toLong()
                }
                runCatching { vibrator.vibrate(VibrationEffect.createOneShot(duration, 140)) }
            }
        }
        if (prefs.getBoolean("sound_enabled", DEFAULT_SOUND_ENABLED)) {
            val pool = soundPool ?: initSoundPool()
            val volume = prefs.getFloat("sound_volume", 0.5f) * 0.35f
            val rate = if (type == HapticType.KEY_SPACE) 0.75f else 0.8f
            runCatching { pool.play(if (type == HapticType.KEY_SPACE) soundSpace else soundClick, volume, volume, 1, 0, rate) }
        }
    }

    private fun initSoundPool(): SoundPool {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        soundClick = pool.load(context, R.raw.key_click, 1)
        soundSpace = pool.load(context, R.raw.key_space, 1)
        soundPool = pool
        return pool
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = previewMode || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(event: MotionEvent): Boolean = if (previewMode) true else super.onTouchEvent(event)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissPopups()
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ popups

    private var previewBubble: TextView? = null
    private var alternatesPopup: LinearLayout? = null
    private var alternatesLeftOnScreen = 0
    private var alternateIndex = 0
    private var alternateCells: List<TextView> = emptyList()

    private fun layerOffset(anchor: View): Pair<Int, Int>? {
        val layer = popupLayer ?: return null
        val a = IntArray(2).also { anchor.getLocationInWindow(it) }
        val l = IntArray(2).also { layer.getLocationInWindow(it) }
        return (a[0] - l[0]) to (a[1] - l[1])
    }

    private fun showPreview(key: View, label: String) {
        val layer = popupLayer ?: return
        val (x, y) = layerOffset(key) ?: return
        val bubble = previewBubble?.takeIf { it.parent === layer } ?: TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layer.addView(this, FrameLayout.LayoutParams(0, 0))
            previewBubble = this
        }
        bubble.text = label
        bubble.typeface = fonts.regular
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        bubble.setTextColor(theme.keyLabel)
        bubble.background = rounded(opaque(theme.key), theme.keyBorder, dp(1f), dp(theme.keyRadiusDp))
        val w = maxOf(key.width + dp(12f), dp(44f))
        val h = key.height + dp(10f)
        val lp = bubble.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.height = h
        lp.leftMargin = (x + (key.width - w) / 2).coerceIn(0, maxOf(0, layer.width - w))
        lp.topMargin = (y - h - dp(4f)).coerceAtLeast(0)
        bubble.layoutParams = lp
        bubble.visibility = View.VISIBLE
    }

    private fun hidePreview() {
        previewBubble?.visibility = View.GONE
    }

    private fun showAlternates(key: View, alternates: List<String>) {
        val layer = popupLayer ?: return
        val (x, y) = layerOffset(key) ?: return
        hideAlternates()
        val pad = dp(4f)
        val cellW = dp(40f)
        val cellH = dp(46f)
        val popup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = rounded(opaque(theme.key), theme.keyBorder, dp(1f), dp(theme.keyRadiusDp))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        alternateCells = alternates.mapIndexed { i, text ->
            TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = fonts.regular
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                popup.addView(this, LayoutParams(cellW, cellH).apply { if (i > 0) marginStart = pad })
            }
        }
        val width = pad * 2 + alternates.size * cellW + (alternates.size - 1) * pad
        val height = pad * 2 + cellH
        val left = (x - pad).coerceIn(0, maxOf(0, layer.width - width))
        layer.addView(popup, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = (y - height - dp(4f)).coerceAtLeast(0)
        })
        val l = IntArray(2).also { layer.getLocationOnScreen(it) }
        alternatesLeftOnScreen = l[0] + left
        alternatesPopup = popup
        highlightAlternate(0)
    }

    private fun moveAlternateHighlight(rawX: Float) {
        val cells = alternateCells.size
        if (cells == 0) return
        val step = dp(44f)
        val index = ((rawX - alternatesLeftOnScreen - dp(4f)) / step).toInt().coerceIn(0, cells - 1)
        if (index != alternateIndex) highlightAlternate(index)
    }

    private fun highlightAlternate(index: Int) {
        alternateIndex = index
        alternateCells.forEachIndexed { i, cell ->
            val on = i == index
            cell.setTextColor(if (on) theme.onAccent else theme.keyLabel)
            cell.background = if (on) rounded(theme.accent, 0, 0, dp(maxOf(theme.keyRadiusDp - 2f, 2f))) else null
        }
    }

    private fun selectedAlternate(): String? = alternateCells.getOrNull(alternateIndex)?.text?.toString()

    private fun hideAlternates() {
        alternatesPopup?.let { (it.parent as? ViewGroup)?.removeView(it) }
        alternatesPopup = null
        alternateCells = emptyList()
    }

    private fun dismissPopups() {
        hidePreview()
        hideAlternates()
    }

    // ------------------------------------------------------------------ clipboard panel

    /** The clipboard panel (screen 6): pinned clips first, two columns, pin and delete per clip. */
    fun showClipboard() {
        dismissPopups()
        removeAllViews()
        emojiSearch = null
        viewState = ViewState.CLIPBOARD
        setPadding(dp(12f), dp(10f), dp(12f), dp(4f))
        onPanelChanged?.invoke(viewState)

        val panelHeight = typingHeightPx()
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(iconButton(R.drawable.ic_kb_chevron_left, theme.keyLabel, 20f, "Back to keyboard") { buildKeys() }, LayoutParams(dp(36f), dp(36f)))
        header.addView(text("Clipboard", 17f, fonts.semibold, theme.keyLabel), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4f) })
        header.addView(text("Clipboard on", 13f, fonts.medium, theme.keyLabelSecondary).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        header.addView(KbSwitch(context, theme, "Clipboard on", clipboard.enabled) { on ->
            clipboard.enabled = on
            showClipboard()
        }, LayoutParams(dp(56f), dp(40f)))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f)))

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        val clips = clipboard.clips()
        when {
            !clipboard.enabled -> list.addView(emptyNote("Clipboard history is off. Turn it on to keep text you copy here."))
            clips.isEmpty() -> list.addView(emptyNote("Text you copy appears here."))
            else -> for (pair in clips.chunked(2)) {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                pair.forEachIndexed { i, clip ->
                    row.addView(clipCard(clip), LayoutParams(0, dp(98f), 1f).apply { if (i == 1) marginStart = dp(8f) })
                }
                if (pair.size == 1) row.addView(View(context), LayoutParams(0, dp(98f), 1f).apply { marginStart = dp(8f) })
                list.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
            }
        }
        scroll.addView(list)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10f) })

        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(panelKey("ABC", 0, "Back to keyboard") { buildKeys() }, LayoutParams(dp(64f), dp(46f)))
        bottom.addView(text("Clips are removed after 1 hour. Pinned clips stay.", 12f, fonts.regular, theme.keyLabelSecondary),
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) })
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)).apply { topMargin = dp(2f) })

        setPanelHeight(panelHeight)
    }

    private fun clipCard(clip: ClipboardStore.Clip): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10f), dp(10f), dp(6f), dp(4f))
            background = keyBackground(theme.key, theme.keyPressed, if (clip.pinned) theme.accent else theme.keyBorder, dp(1f))
            isClickable = true
            contentDescription = "Paste: ${clip.text}"
            setOnClickListener {
                haptic(HapticType.KEY_STANDARD)
                listener?.onText(clip.text)
                buildKeys()
            }
        }
        card.addView(text(clip.text, 13f, fonts.regular, theme.keyLabel).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(4f), 0)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val age = if (clip.pinned) text("Pinned", 11f, fonts.semibold, theme.accent) else text(ClipboardStore.age(clip.time), 11f, fonts.medium, theme.keyLabelSecondary)
        footer.addView(age, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(iconButton(R.drawable.ic_kb_pin, if (clip.pinned) theme.accent else theme.keyLabelSecondary, 16f, if (clip.pinned) "Unpin" else "Pin") {
            clipboard.togglePin(clip.text)
            showClipboard()
        }, LayoutParams(dp(32f), dp(32f)))
        footer.addView(iconButton(R.drawable.ic_kb_trash, theme.keyLabelSecondary, 16f, "Delete clip") {
            clipboard.remove(clip.text)
            showClipboard()
        }, LayoutParams(dp(32f), dp(32f)))
        card.addView(footer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32f)))
        return card
    }

    private fun emptyNote(message: String): View = text(message, 13f, fonts.regular, theme.keyLabelSecondary).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(36f), dp(16f), dp(36f))
    }

    // ------------------------------------------------------------------ emoji panel

    private data class EmojiSection(val title: String, val icon: Int, val emojis: List<String>)

    private fun emojiSections(): List<EmojiSection> = listOf(
        EmojiSection("Recent", R.drawable.ic_kb_clock, recentEmojis()),
        EmojiSection("Smileys", R.drawable.ic_kb_smile, Emoji.SMILEYS),
        EmojiSection("Gestures", R.drawable.ic_kb_hand, Emoji.GESTURES),
        EmojiSection("Nature and places", R.drawable.ic_kb_image, Emoji.NATURE),
        EmojiSection("Symbols", R.drawable.ic_kb_type, Emoji.SYMBOLS),
    )

    private fun recentEmojis(): List<String> =
        prefs.getString("emoji_recent", "").orEmpty().split('\u001F').filter { it.isNotEmpty() }

    private fun rememberEmoji(emoji: String) {
        val recent = listOf(emoji) + recentEmojis().filter { it != emoji }
        prefs.edit().putString("emoji_recent", recent.take(16).joinToString("\u001F")).apply()
    }

    private fun typeEmoji(emoji: String) {
        haptic(HapticType.KEY_STANDARD)
        listener?.onText(emoji)
        rememberEmoji(emoji)
    }

    /** The emoji panel (screen 7): search, five tabs, one scrolling list of sections. */
    fun showEmojiShelf() {
        dismissPopups()
        removeAllViews()
        emojiSearch = null
        viewState = ViewState.EMOJI_SHELF
        setPadding(dp(12f), dp(10f), dp(12f), dp(4f))
        onPanelChanged?.invoke(viewState)

        val search = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            background = keyBackground(theme.key, theme.keyPressed, theme.keyBorder, dp(1f))
            isClickable = true
            contentDescription = "Search emoji"
            setOnClickListener { showEmojiSearch() }
        }
        search.addView(ImageView(context).apply { setImageDrawable(tinted(R.drawable.ic_kb_search, theme.keyLabelSecondary)) }, LayoutParams(dp(18f), dp(18f)))
        search.addView(text("Search emoji", 15f, fonts.regular, theme.keyLabelSecondary), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) })
        addView(search, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42f)))

        val sections = emojiSections().filter { it.emojis.isNotEmpty() }
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
        val underlines = mutableListOf<View>()
        val tabIcons = mutableListOf<ImageView>()
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        val sectionViews = mutableListOf<View>()

        fun markTab(index: Int) {
            underlines.forEachIndexed { i, u -> u.setBackgroundColor(if (i == index) theme.accent else Color.TRANSPARENT) }
            tabIcons.forEachIndexed { i, icon -> icon.setColorFilter(if (i == index) theme.keyLabel else theme.keyLabelSecondary) }
        }

        sections.forEachIndexed { index, section ->
            val tab = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8f), 0, 0)
                isClickable = true
                contentDescription = section.title
                setOnClickListener {
                    haptic(HapticType.KEY_MODIFIER)
                    scroll.smoothScrollTo(0, sectionViews[index].top)
                    markTab(index)
                }
            }
            val icon = ImageView(context).apply { setImageResource(section.icon) }
            tab.addView(icon, LayoutParams(dp(22f), dp(22f)))
            val underline = View(context)
            tab.addView(underline, LayoutParams(dp(40f), dp(2f)).apply { topMargin = dp(8f) })
            tabs.addView(tab, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            underlines.add(underline)
            tabIcons.add(icon)

            val block = LinearLayout(context).apply { orientation = VERTICAL }
            block.addView(text(section.title, 12f, fonts.semibold, theme.keyLabelSecondary).apply {
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                setPadding(0, dp(6f), 0, dp(2f))
            })
            block.addView(emojiGrid(section.emojis))
            content.addView(block)
            sectionViews.add(block)
        }
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) })
        addView(View(context).apply { setBackgroundColor(theme.keyBorder) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)))
        scroll.addView(content)
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val current = sectionViews.indexOfLast { it.top <= y + dp(8f) }.coerceAtLeast(0)
            markTab(current)
        }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        markTab(0)

        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, 0)
        }
        bottom.addView(panelKey("ABC", 0, "Back to keyboard") { buildKeys() }, LayoutParams(dp(64f), dp(46f)))
        bottom.addView(View(context), LayoutParams(0, 1, 1f))
        bottom.addView(repeatingDelete(), LayoutParams(dp(64f), dp(46f)))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)))

        setPanelHeight(typingHeightPx())
    }

    /** Panels take the height the strip and keys had, so the app above does not jump. */
    private fun setPanelHeight(height: Int) {
        val lp = layoutParams ?: return
        if (lp.height != height) {
            lp.height = height
            layoutParams = lp
        }
    }

    private fun emojiGrid(emojis: List<String>, columns: Int = 8): View {
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        for (chunk in emojis.chunked(columns)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (emoji in chunk) row.addView(emojiCell(emoji), LayoutParams(0, dp(42f), 1f))
            repeat(columns - chunk.size) { row.addView(View(context), LayoutParams(0, dp(42f), 1f)) }
            grid.addView(row)
        }
        return grid
    }

    private fun emojiCell(emoji: String): TextView = TextView(context).apply {
        text = emoji
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        background = keyBackground(Color.TRANSPARENT, theme.keyPressed)
        setOnClickListener { typeEmoji(emoji) }
    }

    /** Emoji search: the letter keys type into the search field instead of the app. */
    private fun showEmojiSearch() {
        dismissPopups()
        removeAllViews()
        val query = StringBuilder()
        emojiSearch = query
        setPadding(0, dp(8f), 0, dp(4f))
        setPanelHeight(typingHeightPx())

        val field = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(4f), 0)
            background = rounded(theme.key, theme.keyBorder, dp(1f), dp(theme.keyRadiusDp))
        }
        field.addView(iconButton(R.drawable.ic_kb_chevron_left, theme.keyLabel, 18f, "Back to emoji") { showEmojiShelf() }, LayoutParams(dp(34f), dp(34f)))
        val queryView = text("", 15f, fonts.regular, theme.keyLabel).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        field.addView(queryView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4f) })
        addView(field, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)).apply { setMargins(dp(12f), 0, dp(12f), 0) })

        val resultsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val results = LinearLayout(context).apply { orientation = HORIZONTAL }
        resultsScroll.addView(results)
        addView(resultsScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)).apply { setMargins(dp(12f), 0, dp(12f), 0) })

        fun refresh() {
            queryView.text = if (query.isEmpty()) "Search emoji" else query
            queryView.setTextColor(if (query.isEmpty()) theme.keyLabelSecondary else theme.keyLabel)
            results.removeAllViews()
            val found = Emoji.search(query.toString(), emojiSections().drop(1).flatMap { it.emojis }.distinct())
            if (found.isEmpty() && query.isNotEmpty()) {
                results.addView(text("No emoji found", 13f, fonts.regular, theme.keyLabelSecondary).apply { gravity = Gravity.CENTER_VERTICAL },
                    LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
            }
            for (emoji in found.take(40)) results.addView(emojiCell(emoji), LayoutParams(dp(44f), dp(44f)))
        }

        val onKey: (Key) -> Unit = { key ->
            when {
                key.code == CODE_DELETE -> if (query.isNotEmpty()) query.deleteCharAt(query.length - 1)
                key.kind == Kind.SPACE -> if (query.isNotEmpty() && query.last() != ' ') query.append(' ')
                key.code == CODE_SYMBOLS -> showEmojiShelf()
                key.kind == Kind.CHAR -> query.append(key.label.lowercase())
            }
            if (emojiSearch === query) refresh()
        }
        val available = typingHeightPx() - dp(12f) - dp(40f) - dp(44f) - dp(4f)
        val keyHeight = minOf(dp(keyHeightDp), available / 4 - dp(theme.keyGapDp)).coerceAtLeast(dp(28f))
        val backspace = Key("", CODE_DELETE, 55.8f, Kind.FUNCTION, icon = R.drawable.ic_kb_delete, description = "Delete")
        val rows = listOf(
            ROW1.map { Key(it, it[0].code, 35.2f) },
            listOf(Key("", 0, 20.6f, Kind.SPACER)) + ROW2.map { Key(it, it[0].code, 35.2f) } + Key("", 0, 20.6f, Kind.SPACER),
            listOf(Key("", 0, 55.8f, Kind.SPACER)) + ROW3.map { Key(it, it[0].code, 35.2f) } + backspace,
            listOf(Key("ABC", CODE_SYMBOLS, 52.8f, Kind.FUNCTION, description = "Back to emoji"), Key("Likhibi", ' '.code, 294.4f, Kind.SPACE, description = "Space")),
        )
        val wasShifted = isShifted
        isShifted = false
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        for (row in rows) grid.addView(buildRow(row, keyHeight, onKey))
        isShifted = wasShifted
        letterKeys.clear()
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4f) })
        refresh()
    }

    // ------------------------------------------------------------------ small pieces

    private fun panelKey(label: String, icon: Int, description: String, onClick: () -> Unit): KeyCapView = KeyCapView(context).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = fonts.medium
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(theme.keyLabel)
        background = keyBackground(theme.keyFunction, theme.keyPressed)
        if (icon != 0) setIcon(icon, theme.keyLabel, dp(20f))
        contentDescription = description
        setOnClickListener {
            haptic(HapticType.KEY_MODIFIER)
            onClick()
        }
    }

    private fun repeatingDelete(): View {
        val view = panelKey("", R.drawable.ic_kb_delete, "Delete") {}
        view.setOnClickListener(null)
        attachTouch(view, Key("", CODE_DELETE, 64f, Kind.FUNCTION), null)
        return view
    }

    private fun iconButton(icon: Int, color: Int, sizeDp: Float, description: String, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            // Icons are drawn at their design size inside a larger touch target.
            setImageDrawable(SizedDrawable(tinted(icon, color), dp(sizeDp)))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            background = keyBackground(Color.TRANSPARENT, theme.keyPressed)
            setOnClickListener {
                haptic(HapticType.KEY_MODIFIER)
                onClick()
            }
        }

    private fun text(value: String, sizeSp: Float, face: Typeface, color: Int): TextView = TextView(context).apply {
        text = value
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        includeFontPadding = false
    }

    private fun tinted(icon: Int, color: Int): Drawable =
        ContextCompat.getDrawable(context, icon)!!.mutate().also { it.setTint(color) }

    private fun keyBackground(fill: Int, pressed: Int, border: Int = theme.keyBorder, borderWidth: Int = dp(theme.keyBorderWidthDp)): Drawable {
        val radius = dp(theme.keyRadiusDp)
        val cacheKey = "$fill/$pressed/$border/$borderWidth/$radius"
        backgroundCache[cacheKey]?.let { return it.newDrawable().mutate() }
        val drawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, border, borderWidth, radius))
            addState(intArrayOf(), rounded(fill, border, borderWidth, radius))
        }
        backgroundCache[cacheKey] = drawable.constantState!!
        return drawable
    }

    private fun rounded(fill: Int, border: Int, borderWidth: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (borderWidth > 0 && Color.alpha(border) > 0) setStroke(borderWidth, border)
    }

    /** Popups float over other keys, so a translucent key colour gets a solid backing. */
    private fun opaque(color: Int): Int {
        if (Color.alpha(color) == 255) return color
        val base = if (theme.usesPhoto) Color.parseColor("#3A3D38") else theme.background.let { if (Color.alpha(it) == 255) it else Color.DKGRAY }
        return blend(base, color or (0xFF shl 24), Color.alpha(color) / 255f)
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )

    private fun dp(value: Float): Int = (value * density).roundToInt()
    private fun sp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /** A keycap: its label, an optional centred icon, and an optional small hint in the corner. */
    private class KeyCapView(context: Context) : TextView(context) {
        private var icon: Drawable? = null
        private var iconSize = 0
        private var hint: String? = null
        private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

        fun setIcon(res: Int, color: Int, size: Int) {
            icon = ContextCompat.getDrawable(context, res)!!.mutate().also { it.setTint(color) }
            iconSize = size
            invalidate()
        }

        fun setHint(text: String, color: Int, face: Typeface, sizePx: Float) {
            hint = text
            hintPaint.color = color
            hintPaint.typeface = face
            hintPaint.textSize = sizePx
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            icon?.let {
                val left = (width - iconSize) / 2
                val top = (height - iconSize) / 2
                it.setBounds(left, top, left + iconSize, top + iconSize)
                it.draw(canvas)
            }
            hint?.let {
                val pad = hintPaint.textSize * 0.45f
                canvas.drawText(it, width - pad - hintPaint.measureText(it), pad + hintPaint.textSize * 0.85f, hintPaint)
            }
        }
    }

    /** Draws a drawable at a fixed size, centred, so small icons keep a large touch target. */
    private class SizedDrawable(private val inner: Drawable, private val size: Int) : Drawable() {
        override fun draw(canvas: Canvas) {
            val l = bounds.centerX() - size / 2
            val t = bounds.centerY() - size / 2
            inner.setBounds(l, t, l + size, t + size)
            inner.draw(canvas)
        }
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) { inner.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { inner.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** The keyboard's own switch (design 29:1808, Keyboard context): a 40 x 24 pill in theme colours. */
    private class KbSwitch(
        context: Context,
        private val theme: KeyboardTheme,
        private val label: String,
        private var checked: Boolean,
        private val onChange: (Boolean) -> Unit,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = resources.displayMetrics.density

        init {
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener {
                checked = !checked
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                onChange(checked)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = 40 * d
            val h = 24 * d
            val left = (width - w) / 2
            val top = (height - h) / 2
            paint.color = if (checked) theme.accent else theme.keyFunction
            canvas.drawRoundRect(left, top, left + w, top + h, h / 2, h / 2, paint)
            val r = 9 * d
            val cx = if (checked) left + w - h / 2 else left + h / 2
            paint.color = if (checked) theme.onAccent else theme.keyLabelSecondary
            canvas.drawCircle(cx, top + h / 2, r, paint)
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.Switch::class.java.name
            info.isCheckable = true
            info.isChecked = checked
        }
    }
}

/**
 * CenterCropWallpaperDrawable scales and crops a bitmap to fill the view bounds
 * without distortion and without reporting intrinsic width/height.
 */
class CenterCropWallpaperDrawable(
    private val bitmap: android.graphics.Bitmap,
    private val overlayColor: Int,
) : Drawable() {
    private val drawMatrix = android.graphics.Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint().apply {
        color = overlayColor
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f || bitmap.isRecycled) return
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = maxOf(w / bw, h / bh)
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(bounds.left + (w - bw * scale) * 0.5f, bounds.top + (h - bh * scale) * 0.5f)
        canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(bitmap, drawMatrix, bitmapPaint)
        if (Color.alpha(overlayColor) > 0) canvas.drawRect(bounds, overlayPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        bitmapPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}
