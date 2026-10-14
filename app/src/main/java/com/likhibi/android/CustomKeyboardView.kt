package com.likhibi.android

import com.likhibi.keyboard.R
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class CustomKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnKeyActionListener {
        fun onKey(code: Int)
        fun onText(text: String)
    }

    var listener: OnKeyActionListener? = null
    private var isShifted = false
    var isCapsLock = false
    var enterKeyLabel = "↵"
    private var keyboardMode = Mode.QWERTY
    private var currentTheme = DEFAULT_THEME
    private var lastAppliedTheme: String? = null
    private var cachedThemeColors: ThemeColors? = null
    private var cachedWallpaper: BitmapDrawable? = null
    private val drawableCache = mutableMapOf<String, android.graphics.drawable.Drawable.ConstantState>()

    private val prefs = context.getSharedPreferences("likhibi_keyboard_prefs", Context.MODE_PRIVATE)

    private var soundPool: SoundPool? = null
    private var soundClick: Int = 0
    private var soundSpace: Int = 0
    private var deleteRunnable: Runnable? = null
    private val deleteHandler = Handler(Looper.getMainLooper())

    // Fast-path letter views for instant Shift toggling without recreating layouts
    private val letterKeyViews = mutableListOf<Pair<TextView, String>>()
    private var shiftKeyView: TextView? = null

    // Tracking view state (whether showing normal keys, clipboard, theme switcher, or emoji shelf)
    private var viewState = ViewState.KEYS
    private var currentEmojiCategoryIndex = 0

    companion object {
        /** The brand theme: cream and Naga red by day, smoky black by night. */
        const val DEFAULT_THEME = "theme_naga_heritage"
        const val DEFAULT_HAPTIC_MS = 10
        const val DEFAULT_SOUND_ENABLED = false
    }

    enum class Mode {
        QWERTY, SYMBOLS, EXTRA_SYMBOLS, EMOJI
    }

    enum class ViewState {
        KEYS, CLIPBOARD, THEME_SWITCHER, EMOJI_SHELF
    }

    enum class HapticType {
        KEY_STANDARD, KEY_SPACE, KEY_MODIFIER, KEY_ACCENT
    }

    data class ThemeColors(
        val bgColor: Int,
        val bgGradient: List<String>? = null,
        val keyBgNormal: Int,
        val keyBgNormalPressed: Int,
        val keyBgModifier: Int,
        val keyBgModifierPressed: Int,
        val keyBgAccent: Int,
        val keyBgAccentPressed: Int,
        val keyTextColorNormal: Int,
        val keyTextColorModifier: Int,
        val keyTextColorAccent: Int,
        val isGlass: Boolean = false,
        val hasShadow: Boolean = true,
        val suggestionBarBg: Int,
        val suggestionTextNormal: Int,
        val suggestionTextAccent: Int,
        val toolbarToggleColor: Int,
        val dividerColor: Int,
        val clipboardCardBg: Int,
        val clipboardCardPressed: Int,
        val clipboardEmptyText: Int,
        val deleteColor: Int,
        val accentColor: Int,
        val cornerRadiusDp: Float = 7f,
        val borderWidthDp: Float = 0f,
        val borderColor: Int = Color.TRANSPARENT
    )

    data class KeyInfo(
        val label: String,
        val code: Int,
        val weight: Float = 1.0f,
        val isModifier: Boolean = false,
        val isAccent: Boolean = false,
        val subLabel: String? = null
    )

    var themeChangeListener: (() -> Unit)? = null
    var wallpaperPickerListener: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        currentTheme = prefs.getString("selected_theme", DEFAULT_THEME) ?: DEFAULT_THEME
        applyTheme()
        initSoundPool()
    }

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        try {
            soundClick = soundPool?.load(context, R.raw.key_click, 1) ?: 0
            soundSpace = soundPool?.load(context, R.raw.key_space, 1) ?: 0
        } catch (e: Exception) { /* ignore */ }
    }

    private fun getSelectedTypeface(): Typeface {
        val fontName = prefs.getString("selected_font", "sans-serif") ?: "sans-serif"
        return Typeface.create(fontName, Typeface.NORMAL)
    }

    fun setShifted(shifted: Boolean) {
        if (isShifted != shifted) {
            isShifted = shifted
            if (keyboardMode == Mode.QWERTY && viewState == ViewState.KEYS) {
                // High-performance instantaneous in-place text update
                for ((tv, base) in letterKeyViews) {
                    tv.text = if (isShifted) base.uppercase() else base.lowercase()
                }
                shiftKeyView?.let { skv ->
                    skv.text = if (isShifted) (if (isCapsLock) "⬆" else "⇧") else "⇧"
                    val colors = cachedThemeColors ?: getThemeColors()
                    if (isShifted || isCapsLock) {
                        skv.setTextColor(colors.keyTextColorAccent)
                        skv.setBackgroundColor(colors.keyBgAccent)
                    } else {
                        skv.setTextColor(colors.keyTextColorModifier)
                        skv.setBackgroundColor(colors.keyBgModifier)
                    }
                }
            }
        }
    }

    fun isShifted(): Boolean = isShifted

    fun switchMode(mode: Mode) {
        if (mode == Mode.EMOJI) {
            showEmojiShelf()
        } else {
            if (keyboardMode != mode || viewState != ViewState.KEYS) {
                keyboardMode = mode
                viewState = ViewState.KEYS
                buildKeyboard()
            }
        }
    }

    fun resetState(mode: Mode, shifted: Boolean) {
        val modeChanged = (this.keyboardMode != mode)
        val shiftChanged = (this.isShifted != shifted)
        val stateChanged = (this.viewState != ViewState.KEYS)

        this.keyboardMode = mode
        this.isShifted = shifted
        this.viewState = ViewState.KEYS

        if (modeChanged || shiftChanged || stateChanged) {
            applyTheme(forceRebuild = true)
        } else {
            applyTheme(forceRebuild = false)
        }
    }

    fun getMode(): Mode = keyboardMode
    fun getViewState(): ViewState = viewState

    /**
     * Resolves clean, professional flagship theme colors without AI neon cyber tropes
     */
    private fun getThemeColors(): ThemeColors {
        val theme = prefs.getString("selected_theme", DEFAULT_THEME) ?: DEFAULT_THEME
        val isSystemNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        return when (theme) {
            "theme_system" -> {
                if (isSystemNight) getSlateDarkColors() else getChalkLightColors()
            }
            "theme_slate_dark", "theme_midnight_glass", "theme_liquid_glass" -> {
                getSlateDarkColors()
            }
            "theme_chalk_light", "theme_pure_minimal", "theme_material_you" -> {
                getChalkLightColors()
            }
            "theme_naga_heritage" -> {
                getNagaHeritageColors(isSystemNight)
            }
            "theme_obsidian_oled" -> {
                getObsidianOledColors()
            }
            "theme_nordic_frost" -> {
                getNordicFrostColors()
            }
            "theme_custom" -> {
                getCustomThemeColors()
            }
            else -> {
                if (isSystemNight) getSlateDarkColors() else getChalkLightColors()
            }
        }
    }

    private fun getSlateDarkColors(): ThemeColors = ThemeColors(
        bgColor = Color.parseColor("#1E1F22"),
        keyBgNormal = Color.parseColor("#2B2D31"),
        keyBgNormalPressed = Color.parseColor("#383A40"),
        keyBgModifier = Color.parseColor("#232528"),
        keyBgModifierPressed = Color.parseColor("#32353A"),
        keyBgAccent = Color.parseColor("#4E80EE"),
        keyBgAccentPressed = Color.parseColor("#3B6CD4"),
        keyTextColorNormal = Color.parseColor("#F2F3F5"),
        keyTextColorModifier = Color.parseColor("#B5BAC1"),
        keyTextColorAccent = Color.WHITE,
        hasShadow = true,
        cornerRadiusDp = 7f,
        suggestionBarBg = Color.parseColor("#1E1F22"),
        suggestionTextNormal = Color.parseColor("#949BA4"),
        suggestionTextAccent = Color.parseColor("#6BA0F5"),
        toolbarToggleColor = Color.parseColor("#6BA0F5"),
        dividerColor = Color.parseColor("#2B2D31"),
        clipboardCardBg = Color.parseColor("#2B2D31"),
        clipboardCardPressed = Color.parseColor("#383A40"),
        clipboardEmptyText = Color.parseColor("#808495"),
        deleteColor = Color.parseColor("#EF4444"),
        accentColor = Color.parseColor("#4E80EE")
    )

    private fun getChalkLightColors(): ThemeColors = ThemeColors(
        bgColor = Color.parseColor("#ECEFF1"),
        keyBgNormal = Color.parseColor("#FFFFFF"),
        keyBgNormalPressed = Color.parseColor("#E1E4E8"),
        keyBgModifier = Color.parseColor("#D9DEE3"),
        keyBgModifierPressed = Color.parseColor("#CBD1D8"),
        keyBgAccent = Color.parseColor("#1A73E8"),
        keyBgAccentPressed = Color.parseColor("#1558B0"),
        keyTextColorNormal = Color.parseColor("#1F1F1F"),
        keyTextColorModifier = Color.parseColor("#49454F"),
        keyTextColorAccent = Color.WHITE,
        hasShadow = true,
        cornerRadiusDp = 7f,
        suggestionBarBg = Color.parseColor("#ECEFF1"),
        suggestionTextNormal = Color.parseColor("#5F6368"),
        suggestionTextAccent = Color.parseColor("#1A73E8"),
        toolbarToggleColor = Color.parseColor("#1A73E8"),
        dividerColor = Color.parseColor("#D9DEE3"),
        clipboardCardBg = Color.parseColor("#FFFFFF"),
        clipboardCardPressed = Color.parseColor("#E4E7EB"),
        clipboardEmptyText = Color.parseColor("#9AA0A6"),
        deleteColor = Color.parseColor("#D93025"),
        accentColor = Color.parseColor("#1A73E8")
    )

    private fun getNagaHeritageColors(isNight: Boolean): ThemeColors {
        return if (isNight) {
            ThemeColors(
                bgColor = Color.parseColor("#141416"),
                keyBgNormal = Color.parseColor("#222124"),
                keyBgNormalPressed = Color.parseColor("#333136"),
                keyBgModifier = Color.parseColor("#1A191C"),
                keyBgModifierPressed = Color.parseColor("#2A282D"),
                keyBgAccent = Color.parseColor("#D32F2F"),
                keyBgAccentPressed = Color.parseColor("#B71C1C"),
                keyTextColorNormal = Color.parseColor("#F5EFE6"),
                keyTextColorModifier = Color.parseColor("#A8A096"),
                keyTextColorAccent = Color.WHITE,
                hasShadow = true,
                cornerRadiusDp = 7f,
                suggestionBarBg = Color.parseColor("#141416"),
                suggestionTextNormal = Color.parseColor("#A8A096"),
                suggestionTextAccent = Color.parseColor("#E53935"),
                toolbarToggleColor = Color.parseColor("#E53935"),
                dividerColor = Color.parseColor("#262428"),
                clipboardCardBg = Color.parseColor("#222124"),
                clipboardCardPressed = Color.parseColor("#2F2E33"),
                clipboardEmptyText = Color.parseColor("#807A72"),
                deleteColor = Color.parseColor("#E53935"),
                accentColor = Color.parseColor("#E53935")
            )
        } else {
            ThemeColors(
                bgColor = Color.parseColor("#F7F4EE"),
                keyBgNormal = Color.parseColor("#FFFFFF"),
                keyBgNormalPressed = Color.parseColor("#EDE7DE"),
                keyBgModifier = Color.parseColor("#ECE5DA"),
                keyBgModifierPressed = Color.parseColor("#DFD7CB"),
                keyBgAccent = Color.parseColor("#C62828"),
                keyBgAccentPressed = Color.parseColor("#A72020"),
                keyTextColorNormal = Color.parseColor("#1C1917"),
                keyTextColorModifier = Color.parseColor("#57534E"),
                keyTextColorAccent = Color.WHITE,
                hasShadow = true,
                cornerRadiusDp = 7f,
                suggestionBarBg = Color.parseColor("#F7F4EE"),
                suggestionTextNormal = Color.parseColor("#78716C"),
                suggestionTextAccent = Color.parseColor("#C62828"),
                toolbarToggleColor = Color.parseColor("#C62828"),
                dividerColor = Color.parseColor("#E2D9CC"),
                clipboardCardBg = Color.parseColor("#FFFFFF"),
                clipboardCardPressed = Color.parseColor("#EDE7DE"),
                clipboardEmptyText = Color.parseColor("#8A8072"),
                deleteColor = Color.parseColor("#C62828"),
                accentColor = Color.parseColor("#C62828")
            )
        }
    }

    private fun getObsidianOledColors(): ThemeColors = ThemeColors(
        bgColor = Color.parseColor("#000000"),
        keyBgNormal = Color.parseColor("#141416"),
        keyBgNormalPressed = Color.parseColor("#252528"),
        keyBgModifier = Color.parseColor("#0E0E10"),
        keyBgModifierPressed = Color.parseColor("#1E1E22"),
        keyBgAccent = Color.parseColor("#3B82F6"),
        keyBgAccentPressed = Color.parseColor("#2563EB"),
        keyTextColorNormal = Color.parseColor("#FFFFFF"),
        keyTextColorModifier = Color.parseColor("#9CA3AF"),
        keyTextColorAccent = Color.WHITE,
        hasShadow = false,
        cornerRadiusDp = 7f,
        suggestionBarBg = Color.parseColor("#000000"),
        suggestionTextNormal = Color.parseColor("#9CA3AF"),
        suggestionTextAccent = Color.parseColor("#60A5FA"),
        toolbarToggleColor = Color.parseColor("#60A5FA"),
        dividerColor = Color.parseColor("#1F2228"),
        clipboardCardBg = Color.parseColor("#141416"),
        clipboardCardPressed = Color.parseColor("#222226"),
        clipboardEmptyText = Color.parseColor("#6B7280"),
        deleteColor = Color.parseColor("#EF4444"),
        accentColor = Color.parseColor("#3B82F6")
    )

    private fun getNordicFrostColors(): ThemeColors = ThemeColors(
        bgColor = Color.parseColor("#242933"),
        keyBgNormal = Color.parseColor("#2E3440"),
        keyBgNormalPressed = Color.parseColor("#3B4252"),
        keyBgModifier = Color.parseColor("#1E232B"),
        keyBgModifierPressed = Color.parseColor("#2E3440"),
        keyBgAccent = Color.parseColor("#5E81AC"),
        keyBgAccentPressed = Color.parseColor("#4C6A8D"),
        keyTextColorNormal = Color.parseColor("#ECEFF4"),
        keyTextColorModifier = Color.parseColor("#D8DEE9"),
        keyTextColorAccent = Color.parseColor("#ECEFF4"),
        hasShadow = true,
        cornerRadiusDp = 7f,
        suggestionBarBg = Color.parseColor("#242933"),
        suggestionTextNormal = Color.parseColor("#D8DEE9"),
        suggestionTextAccent = Color.parseColor("#88C0D0"),
        toolbarToggleColor = Color.parseColor("#88C0D0"),
        dividerColor = Color.parseColor("#2E3440"),
        clipboardCardBg = Color.parseColor("#2E3440"),
        clipboardCardPressed = Color.parseColor("#3B4252"),
        clipboardEmptyText = Color.parseColor("#7B88A1"),
        deleteColor = Color.parseColor("#BF616A"),
        accentColor = Color.parseColor("#88C0D0")
    )

    private fun getCustomThemeColors(): ThemeColors {
        val keyOpacity = prefs.getInt("custom_key_opacity", 70).coerceIn(0, 255)
        val overlayOpacity = prefs.getInt("custom_overlay_opacity", 140).coerceIn(0, 240)
        val cornerRadius = prefs.getFloat("custom_corner_radius", 8f).coerceIn(0f, 20f)
        val hasShadow = prefs.getBoolean("custom_key_has_shadow", true)
        val accentColorValue = prefs.getInt("custom_accent_color", Color.parseColor("#1A73E8"))

        return ThemeColors(
            bgColor = Color.TRANSPARENT,
            keyBgNormal = Color.argb(keyOpacity.coerceAtLeast(160), 30, 32, 40),
            keyBgNormalPressed = Color.argb((keyOpacity + 40).coerceAtMost(255), 50, 54, 66),
            keyBgModifier = Color.argb(keyOpacity.coerceAtLeast(140), 22, 24, 30),
            keyBgModifierPressed = Color.argb((keyOpacity + 40).coerceAtMost(255), 40, 44, 54),
            keyBgAccent = accentColorValue,
            keyBgAccentPressed = darkenColor(accentColorValue, 0.2f),
            keyTextColorNormal = Color.WHITE,
            keyTextColorModifier = Color.parseColor("#D1D5DB"),
            keyTextColorAccent = Color.WHITE,
            hasShadow = hasShadow,
            cornerRadiusDp = cornerRadius,
            suggestionBarBg = Color.argb(overlayOpacity, 16, 18, 24),
            suggestionTextNormal = Color.parseColor("#9CA3AF"),
            suggestionTextAccent = accentColorValue,
            toolbarToggleColor = accentColorValue,
            dividerColor = Color.argb(40, 255, 255, 255),
            clipboardCardBg = Color.argb(keyOpacity.coerceAtLeast(150), 30, 32, 40),
            clipboardCardPressed = Color.argb(255, 50, 54, 66),
            clipboardEmptyText = Color.argb(180, 200, 200, 200),
            deleteColor = Color.parseColor("#EF4444"),
            accentColor = accentColorValue
        )
    }

    fun applyTheme(forceRebuild: Boolean = false) {
        val selectedTheme = prefs.getString("selected_theme", DEFAULT_THEME) ?: DEFAULT_THEME
        if (!forceRebuild && selectedTheme == lastAppliedTheme && cachedThemeColors != null) {
            return
        }

        if (forceRebuild || selectedTheme != lastAppliedTheme) {
            drawableCache.clear()
            if (forceRebuild) cachedWallpaper = null
        }

        currentTheme = selectedTheme
        lastAppliedTheme = currentTheme
        cachedThemeColors = getThemeColors()
        val colors = cachedThemeColors!!

        val density = resources.displayMetrics.density
        setPadding((3 * density).toInt(), (2 * density).toInt(), (3 * density).toInt(), (2 * density).toInt())

        when {
            currentTheme == "theme_custom" -> {
                val overlayDim = prefs.getInt("custom_overlay_opacity", 140)
                val dimColor = Color.argb(overlayDim.coerceIn(0, 220), 12, 14, 20)
                val wallpaperFile = File(context.filesDir, "custom_wallpaper.jpg")
                if (wallpaperFile.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                        if (bitmap != null) {
                            background = CenterCropWallpaperDrawable(bitmap, dimColor)
                        } else {
                            setBackgroundColor(Color.parseColor("#181920"))
                        }
                    } catch (e: Exception) {
                        setBackgroundColor(Color.parseColor("#181920"))
                    }
                } else {
                    setBackgroundColor(Color.parseColor("#181920"))
                }
            }
            colors.bgGradient != null -> {
                cachedWallpaper = null
                val parsedColors = colors.bgGradient.map { Color.parseColor(it) }.toIntArray()
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, parsedColors)
            }
            else -> {
                cachedWallpaper = null
                setBackgroundColor(colors.bgColor)
            }
        }

        when (viewState) {
            ViewState.KEYS -> buildKeyboard()
            ViewState.CLIPBOARD -> showClipboard()
            ViewState.THEME_SWITCHER -> showThemeSwitcher()
            ViewState.EMOJI_SHELF -> showEmojiShelf()
        }
    }

    fun buildKeyboard() {
        removeAllViews()
        viewState = ViewState.KEYS
        letterKeyViews.clear()
        shiftKeyView = null

        val rows = getRowsForCurrentState()
        val colors = cachedThemeColors ?: getThemeColors().also { cachedThemeColors = it }

        val density = resources.displayMetrics.density
        val showNumberRow = prefs.getBoolean("show_number_row", false)
        val rowHeightPx = if (showNumberRow && keyboardMode == Mode.QWERTY) (44 * density).toInt() else (48 * density).toInt()
        val isAtoLRowIndex = if (showNumberRow) 2 else 1

        for ((index, rowKeys) in rows.withIndex()) {
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx)
                gravity = Gravity.CENTER
                isMotionEventSplittingEnabled = true
            }

            val isRowAtoL = (keyboardMode == Mode.QWERTY && index == isAtoLRowIndex)
            if (isRowAtoL) {
                rowLayout.addView(createSpacer(0.5f))
            }

            for (key in rowKeys) {
                rowLayout.addView(createKeyView(key, colors))
            }

            if (isRowAtoL) {
                rowLayout.addView(createSpacer(0.5f))
            }

            addView(rowLayout)
        }
    }

    private fun getRowsForCurrentState(): List<List<KeyInfo>> {
        return when (keyboardMode) {
            Mode.QWERTY -> getQwertyRows()
            Mode.SYMBOLS -> getSymbolsRows()
            Mode.EXTRA_SYMBOLS -> getExtraSymbolsRows()
            Mode.EMOJI -> getQwertyRows() // Emoji Shelf handles its own view
        }
    }

    private fun getQwertyRows(): List<List<KeyInfo>> {
        val showNumberRow = prefs.getBoolean("show_number_row", false)
        val r0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val r1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val r2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        val r3 = listOf("z", "x", "c", "v", "b", "n", "m")

        val result = mutableListOf<List<KeyInfo>>()
        if (showNumberRow) {
            result.add(r0.map { KeyInfo(it, it[0].code, weight = 1.0f) })
        }

        // Q..P row: map top-row letters with number hint subLabels
        val topNumberHints = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        result.add(r1.mapIndexed { idx, it ->
            KeyInfo(formatText(it), it[0].code, weight = 1.0f, subLabel = if (!showNumberRow) topNumberHints[idx] else null)
        })

        // A..L row
        result.add(r2.map { KeyInfo(formatText(it), it[0].code, weight = 1.0f) })

        // Z..M row with Shift and Backspace
        result.add(listOf(
            KeyInfo(if (isShifted) (if (isCapsLock) "⬆" else "⇧") else "⇧", -1, weight = 1.5f, isModifier = true),
            *r3.map { KeyInfo(formatText(it), it[0].code, weight = 1.0f) }.toTypedArray(),
            KeyInfo("⌫", -5, weight = 1.5f, isModifier = true)
        ))

        // Bottom row: ?123, comma, emoji, space, period, enter
        result.add(listOf(
            KeyInfo("?123", -2, weight = 1.4f, isModifier = true),
            KeyInfo(",", 44, weight = 1.0f),
            KeyInfo("😊", -10, weight = 1.0f, isModifier = true),
            KeyInfo("likhibi", 32, weight = 4.2f),
            KeyInfo(".", 46, weight = 1.0f),
            KeyInfo(enterKeyLabel, 10, weight = 1.4f, isAccent = true)
        ))
        return result
    }

    private fun getSymbolsRows(): List<List<KeyInfo>> {
        val r1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val r2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
        val r3 = listOf("*", "\"", "'", ":", ";", "!", "?")

        return listOf(
            r1.map { KeyInfo(it, it[0].code, weight = 1.0f) },
            r2.map { KeyInfo(it, it[0].code, weight = 1.0f) },
            listOf(
                KeyInfo("=/<", -3, weight = 1.5f, isModifier = true),
                *r3.map { KeyInfo(it, it[0].code, weight = 1.0f) }.toTypedArray(),
                KeyInfo("⌫", -5, weight = 1.5f, isModifier = true)
            ),
            listOf(
                KeyInfo("ABC", -2, weight = 1.4f, isModifier = true),
                KeyInfo(",", 44, weight = 1.0f),
                KeyInfo("😊", -10, weight = 1.0f, isModifier = true),
                KeyInfo("space", 32, weight = 4.2f),
                KeyInfo(".", 46, weight = 1.0f),
                KeyInfo(enterKeyLabel, 10, weight = 1.4f, isAccent = true)
            )
        )
    }

    private fun getExtraSymbolsRows(): List<List<KeyInfo>> {
        val r1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")
        val r2 = listOf("£", "¥", "€", "¢", "^", "°", "=", "{", "}", "\\")
        val r3 = listOf("%", "_", "<", ">", "[", "]", "§")

        return listOf(
            r1.map { KeyInfo(it, it[0].code, weight = 1.0f) },
            r2.map { KeyInfo(it, it[0].code, weight = 1.0f) },
            listOf(
                KeyInfo("?123", -4, weight = 1.5f, isModifier = true),
                *r3.map { KeyInfo(it, it[0].code, weight = 1.0f) }.toTypedArray(),
                KeyInfo("⌫", -5, weight = 1.5f, isModifier = true)
            ),
            listOf(
                KeyInfo("ABC", -2, weight = 1.4f, isModifier = true),
                KeyInfo(",", 44, weight = 1.0f),
                KeyInfo("😊", -10, weight = 1.0f, isModifier = true),
                KeyInfo("space", 32, weight = 4.2f),
                KeyInfo(".", 46, weight = 1.0f),
                KeyInfo(enterKeyLabel, 10, weight = 1.4f, isAccent = true)
            )
        )
    }

    private fun formatText(text: String): String {
        return if (isShifted) text.uppercase() else text.lowercase()
    }

    private fun createKeyView(key: KeyInfo, colors: ThemeColors): View {
        val density = resources.displayMetrics.density

        val keyTextView = TextView(context).apply {
            text = if (isShifted && key.label.length == 1 && key.label[0].isLetter()) key.label.uppercase() else key.label
            gravity = Gravity.CENTER
            val selectedFont = getSelectedTypeface()
            typeface = if (key.isAccent) Typeface.create(selectedFont, Typeface.BOLD) else selectedFont

            if (key.isAccent) {
                setTextColor(colors.keyTextColorAccent)
                textSize = 20f
            } else if (key.isModifier) {
                setTextColor(colors.keyTextColorModifier)
                textSize = 16f
            } else {
                setTextColor(colors.keyTextColorNormal)
                textSize = 19f
                if (key.code == 32) {
                    textSize = 13f
                    setTextColor(colors.suggestionTextNormal)
                }
            }

            val bgNormalColor = when {
                key.isAccent -> colors.keyBgAccent
                key.isModifier -> {
                    if (key.code == -1 && (isShifted || isCapsLock)) colors.keyBgAccent
                    else colors.keyBgModifier
                }
                else -> colors.keyBgNormal
            }

            val bgPressedColor = when {
                key.isAccent -> colors.keyBgAccentPressed
                key.isModifier -> {
                    if (key.code == -1 && (isShifted || isCapsLock)) colors.keyBgAccentPressed
                    else colors.keyBgModifierPressed
                }
                else -> colors.keyBgNormalPressed
            }

            val shadowColor = when {
                key.isAccent -> darkenColor(colors.keyBgAccent, 0.35f)
                key.isModifier -> darkenColor(colors.keyBgModifier, 0.35f)
                else -> darkenColor(colors.keyBgNormal, 0.3f)
            }

            background = getCachedKeyDrawable(
                normalColor = bgNormalColor,
                pressedColor = bgPressedColor,
                shadowColor = shadowColor,
                hasShadow = colors.hasShadow,
                radius = colors.cornerRadiusDp * density,
                borderWidthDp = colors.borderWidthDp,
                borderColor = colors.borderColor
            )

            val params = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, key.weight)
            params.setMargins(
                (2.5f * density).toInt(),
                (2.5f * density).toInt(),
                (2.5f * density).toInt(),
                (2.5f * density).toInt()
            )
            layoutParams = params

            // Cache letter views for instantaneous zero-jank Shift updates
            if (key.label.length == 1 && key.label[0].isLetter()) {
                letterKeyViews.add(Pair(this, key.label))
            } else if (key.code == -1) {
                shiftKeyView = this
            }

            // Blazing-fast multi-touch listener handling ACTION_DOWN and ACTION_POINTER_DOWN
            var startX = 0f
            var isDraggingSpace = false
            var spaceLongPressRunnable: Runnable? = null
            var keyLongPressRunnable: Runnable? = null
            val keyHandler = Handler(Looper.getMainLooper())

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val pointerIndex = event.actionIndex
                        startX = event.getX(pointerIndex)
                        isDraggingSpace = false
                        v.isPressed = true

                        // Subtle fast haptic tap feedback
                        performPremiumHaptic(when {
                            key.isAccent -> HapticType.KEY_ACCENT
                            key.code == 32 -> HapticType.KEY_SPACE
                            key.isModifier -> HapticType.KEY_MODIFIER
                            else -> HapticType.KEY_STANDARD
                        })

                        // Check for long press on letters/punctuation
                        if (key.subLabel != null) {
                            keyLongPressRunnable = Runnable {
                                performPremiumHaptic(HapticType.KEY_MODIFIER)
                                listener?.onText(key.subLabel)
                                v.isPressed = false
                            }
                            keyHandler.postDelayed(keyLongPressRunnable!!, 380L)
                        } else if (key.code == 46) { // Period '.' long press inserts quick punctuation
                            keyLongPressRunnable = Runnable {
                                performPremiumHaptic(HapticType.KEY_MODIFIER)
                                listener?.onText("?")
                                v.isPressed = false
                            }
                            keyHandler.postDelayed(keyLongPressRunnable!!, 380L)
                        }

                        // Instant key dispatch on down for zero-latency fluid typing
                        if (key.code != 32) {
                            if (keyboardMode == Mode.EMOJI && !key.isModifier) {
                                listener?.onText(key.label)
                            } else {
                                listener?.onKey(key.code)
                            }
                        } else {
                            listener?.onKey(32)
                            spaceLongPressRunnable = Runnable {
                                performPremiumHaptic(HapticType.KEY_ACCENT)
                                listener?.onKey(-11) // Switch input method
                            }
                            keyHandler.postDelayed(spaceLongPressRunnable!!, 450L)
                        }

                        // Continuous backspace repeat
                        if (key.code == -5) {
                            deleteRunnable = object : Runnable {
                                override fun run() {
                                    listener?.onKey(key.code)
                                    performPremiumHaptic(HapticType.KEY_MODIFIER)
                                    deleteHandler.postDelayed(this, 50L)
                                }
                            }
                            deleteHandler.postDelayed(deleteRunnable!!, 320L)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (key.code == 32) {
                            val diffX = event.x - startX
                            if (Math.abs(diffX) > 18 * density) {
                                isDraggingSpace = true
                                spaceLongPressRunnable?.let { keyHandler.removeCallbacks(it) }
                                if (diffX > 0) {
                                    listener?.onKey(Int.MAX_VALUE) // D-pad right
                                } else {
                                    listener?.onKey(Int.MIN_VALUE) // D-pad left
                                }
                                startX = event.x
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        keyLongPressRunnable?.let { keyHandler.removeCallbacks(it) }
                        keyLongPressRunnable = null

                        if (key.code == 32) {
                            spaceLongPressRunnable?.let { keyHandler.removeCallbacks(it) }
                            spaceLongPressRunnable = null
                        }

                        if (key.code == -5) {
                            deleteRunnable?.let { deleteHandler.removeCallbacks(it) }
                            deleteRunnable = null
                        }
                    }
                }
                true
            }
        }

        return keyTextView
    }

    private fun createSpacer(weight: Float): View {
        return View(context).apply {
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        }
    }

    private fun getCachedKeyDrawable(
        normalColor: Int,
        pressedColor: Int,
        shadowColor: Int,
        hasShadow: Boolean,
        radius: Float,
        borderWidthDp: Float = 0f,
        borderColor: Int = Color.TRANSPARENT
    ): RippleDrawable {
        val key = "$normalColor-$pressedColor-$shadowColor-$hasShadow-$radius-$borderWidthDp-$borderColor"
        val state = drawableCache.getOrPut(key) {
            createKeyDrawable(normalColor, pressedColor, shadowColor, hasShadow, radius, borderWidthDp, borderColor).constantState!!
        }
        return state.newDrawable() as RippleDrawable
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - factor)).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color).coerceAtLeast(80), r, g, b)
    }

    private fun createKeyDrawable(
        normalColor: Int,
        pressedColor: Int,
        shadowColor: Int,
        hasShadow: Boolean,
        radius: Float,
        borderWidthDp: Float = 0f,
        borderColor: Int = Color.TRANSPARENT
    ): RippleDrawable {
        val density = resources.displayMetrics.density
        val contentDrawable = if (hasShadow) {
            val shadow = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(shadowColor)
            }
            val keycap = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(normalColor)
            }
            val layers = arrayOf(shadow, keycap)
            LayerDrawable(layers).apply {
                setLayerInset(1, 0, 0, 0, (2f * density).toInt())
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(normalColor)
                val bwPx = if (borderWidthDp > 0f) (borderWidthDp * density).toInt().coerceAtLeast(1) else 0
                if (bwPx > 0 && borderColor != Color.TRANSPARENT) {
                    setStroke(bwPx, borderColor)
                }
            }
        }

        val maskDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }

        return RippleDrawable(
            ColorStateList.valueOf(pressedColor),
            contentDrawable,
            maskDrawable
        )
    }

    private fun performPremiumHaptic(type: HapticType) {
        // High-frequency Android OS keyboard tap (non-blocking)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        // Light user-configurable subtle vibration fallback
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val strengthSetting = prefs.getInt("haptic_strength", DEFAULT_HAPTIC_MS)
        if (strengthSetting > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val duration = when (type) {
                HapticType.KEY_STANDARD -> strengthSetting.toLong()
                HapticType.KEY_SPACE -> (strengthSetting * 1.3).toLong()
                HapticType.KEY_MODIFIER -> (strengthSetting * 1.2).toLong()
                HapticType.KEY_ACCENT -> (strengthSetting * 1.5).toLong()
            }
            try {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, 140))
            } catch (e: Exception) { /* ignore */ }
        }

        val soundEnabled = prefs.getBoolean("sound_enabled", DEFAULT_SOUND_ENABLED)
        if (soundEnabled && soundPool != null) {
            try {
                val baseVolume = prefs.getFloat("sound_volume", 0.5f) * 0.35f
                val rate = when (type) {
                    HapticType.KEY_SPACE -> 0.75f
                    HapticType.KEY_MODIFIER -> 0.8f
                    HapticType.KEY_ACCENT -> 0.85f
                    else -> 0.8f
                }
                if (type == HapticType.KEY_SPACE) {
                    soundPool?.play(soundSpace, baseVolume, baseVolume, 1, 0, rate)
                } else {
                    soundPool?.play(soundClick, baseVolume, baseVolume, 1, 0, rate)
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * Professional, full-featured Emoji Shelf (Gboard style) with categorized emoji tabs
     */
    fun showEmojiShelf() {
        removeAllViews()
        viewState = ViewState.EMOJI_SHELF
        val density = resources.displayMetrics.density
        val colors = cachedThemeColors ?: getThemeColors()

        val categories = listOf(
            Pair("Smileys", listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
                "😘", "😗", "😚", "😙", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨",
                "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕",
                "🤢", "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "🥸", "😎", "🤓", "🧐", "😕", "😟",
                "🙁", "😮", "😯", "😲", "😳", "🥺", "😦", "😧", "😨", "😰", "😥", "😢", "😭", "😱", "😖", "😣",
                "😞", "😓", "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "💀", "☠️", "💩", "🤡", "👹", "👻", "👽"
            )),
            Pair("Gestures", listOf(
                "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆",
                "🖕", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✍️",
                "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃", "🧠", "🦷", "🦴", "👀", "👁️", "👅",
                "👄", "💋", "👶", "🧒", "👦", "👧", "🧑", "👱", "👨", "🧔", "👩", "🧓", "👴", "👵", "🏃", "🚶"
            )),
            Pair("Hearts", listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖",
                "💘", "💝", "💟", "☮️", "✝️", "☪️", "🕉️", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐", "👑", "💎",
                "🎈", "🎉", "🎊", "🎋", "🎍", "🎎", "🎏", "🎐", "🎑", "🧧", "🎀", "🎁", "🎫", "🏆", "🏅", "🥇",
                "🥈", "🥉", "⚽", "🏀", "🏈", "⚾", "🎾", "🎮", "🎯", "🎲", "🎵", "🎶", "✨", "🔥", "💯", "⭐"
            )),
            Pair("Animals", listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
                "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞",
                "🐜", "🦟", "🐢", "🐍", "🦎", "🐙", "🦑", "🦐", "🦀", "🐡", "🐠", "🐟", "🐬", "🐳", "🦈", "🐊",
                "🐅", "🐆", "🦓", "🦍", "🦧", "🐘", "🦛", "🦏", "🐪", "🐫", "🦒", "🦘", "🐃", "🐂", "🐎", "🐖",
                "🌲", "🌳", "🌴", "🌵", "🌷", "🌸", "🌹", "🌺", "🌻", "🌼", "🌾", "🌿", "🍀", "🍁", "🍂", "🍃"
            )),
            Pair("Food", listOf(
                "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥",
                "🥝", "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️", "🫑", "🌽", "🥕", "🧄", "🧅", "🥔", "🍠", "🥐",
                "🥯", "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓", "🥩", "🍗", "🍖", "🌭", "🍔",
                "🍟", "🍕", "🥪", "🌮", "🌯", "🥗", "🥘", "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟", "🍤", "🍙",
                "🍰", "🎂", "🧁", "🥧", "🍫", "🍬", "🍭", "🍮", "🍿", "🍩", "🍪", "☕", "🍵", "🧃", "🍺", "🍷"
            ))
        )

        // Top Category Bar
        val categoryBar = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (38 * density).toInt())
            isHorizontalScrollBarEnabled = false
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }

        val scrollArea = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (185 * density).toInt())
            isVerticalScrollBarEnabled = false
        }

        val gridContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        }

        fun populateEmojis(categoryIndex: Int) {
            gridContainer.removeAllViews()
            val list = categories[categoryIndex].second
            val cols = 8
            var rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt())
            }
            var count = 0
            for (em in list) {
                val tv = TextView(context).apply {
                    text = em
                    textSize = 24f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    background = createKeyDrawable(
                        Color.TRANSPARENT,
                        colors.keyBgNormalPressed,
                        0, false, 8f * density
                    )
                    setOnClickListener {
                        performPremiumHaptic(HapticType.KEY_STANDARD)
                        listener?.onText(em)
                    }
                }
                rowLayout.addView(tv)
                count++
                if (count % cols == 0) {
                    gridContainer.addView(rowLayout)
                    rowLayout = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt())
                    }
                }
            }
            if (rowLayout.childCount > 0) {
                while (rowLayout.childCount < cols) {
                    rowLayout.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    })
                }
                gridContainer.addView(rowLayout)
            }
        }

        for ((idx, cat) in categories.withIndex()) {
            val tab = TextView(context).apply {
                text = cat.first
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                setTextColor(if (idx == currentEmojiCategoryIndex) colors.accentColor else colors.keyTextColorModifier)
                typeface = Typeface.create(getSelectedTypeface(), if (idx == currentEmojiCategoryIndex) Typeface.BOLD else Typeface.NORMAL)
                setOnClickListener {
                    currentEmojiCategoryIndex = idx
                    for (i in 0 until tabContainer.childCount) {
                        val child = tabContainer.getChildAt(i) as? TextView ?: continue
                        child.setTextColor(if (i == idx) colors.accentColor else colors.keyTextColorModifier)
                        child.typeface = Typeface.create(getSelectedTypeface(), if (i == idx) Typeface.BOLD else Typeface.NORMAL)
                    }
                    populateEmojis(idx)
                }
            }
            tabContainer.addView(tab)
        }

        categoryBar.addView(tabContainer)
        addView(categoryBar)

        // Divider
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt())
            setBackgroundColor(colors.dividerColor)
        })

        populateEmojis(currentEmojiCategoryIndex)
        scrollArea.addView(gridContainer)
        addView(scrollArea)

        // Bottom control row: [ABC] [Space] [Backspace]
        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * density).toInt()).apply {
                setPadding((4 * density).toInt(), (3 * density).toInt(), (4 * density).toInt(), (3 * density).toInt())
            }
        }

        val abcBtn = TextView(context).apply {
            text = "ABC"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(colors.keyTextColorModifier)
            typeface = Typeface.create(getSelectedTypeface(), Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.8f).apply {
                setMargins((2 * density).toInt(), 0, (2 * density).toInt(), 0)
            }
            background = getCachedKeyDrawable(colors.keyBgModifier, colors.keyBgModifierPressed, 0, false, 7f * density)
            setOnClickListener {
                performPremiumHaptic(HapticType.KEY_MODIFIER)
                switchMode(Mode.QWERTY)
            }
        }
        bottomBar.addView(abcBtn)

        val spaceBtn = TextView(context).apply {
            text = "space"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(colors.suggestionTextNormal)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4.4f).apply {
                setMargins((2 * density).toInt(), 0, (2 * density).toInt(), 0)
            }
            background = getCachedKeyDrawable(colors.keyBgNormal, colors.keyBgNormalPressed, 0, colors.hasShadow, 7f * density)
            setOnClickListener {
                performPremiumHaptic(HapticType.KEY_SPACE)
                listener?.onKey(32)
            }
        }
        bottomBar.addView(spaceBtn)

        val delBtn = TextView(context).apply {
            text = "⌫"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(colors.keyTextColorModifier)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.8f).apply {
                setMargins((2 * density).toInt(), 0, (2 * density).toInt(), 0)
            }
            background = getCachedKeyDrawable(colors.keyBgModifier, colors.keyBgModifierPressed, 0, false, 7f * density)
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        performPremiumHaptic(HapticType.KEY_MODIFIER)
                        listener?.onKey(-5)
                        deleteRunnable = object : Runnable {
                            override fun run() {
                                listener?.onKey(-5)
                                performPremiumHaptic(HapticType.KEY_MODIFIER)
                                deleteHandler.postDelayed(this, 50L)
                            }
                        }
                        deleteHandler.postDelayed(deleteRunnable!!, 320L)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        deleteRunnable?.let { deleteHandler.removeCallbacks(it) }
                        deleteRunnable = null
                    }
                }
                true
            }
        }
        bottomBar.addView(delBtn)

        addView(bottomBar)
    }

    /**
     * In-keyboard scrolling Clipboard Manager Shelf.
     */
    fun showClipboard() {
        removeAllViews()
        viewState = ViewState.CLIPBOARD
        val density = resources.displayMetrics.density
        val colors = cachedThemeColors ?: getThemeColors()

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt())
        }

        val title = TextView(context).apply {
            text = "Clipboard"
            setTextColor(colors.keyTextColorNormal)
            textSize = 16f
            typeface = Typeface.create(getSelectedTypeface(), Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_close_clean)
            setColorFilter(colors.keyTextColorModifier)
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            background = createKeyDrawable(
                Color.argb(20, 255, 255, 255),
                Color.argb(50, 255, 255, 255),
                0, false, 8f * density
            )
            setOnClickListener {
                viewState = ViewState.KEYS
                buildKeyboard()
            }
        }
        header.addView(closeBtn)
        addView(header)

        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                setMargins(0, 0, 0, (6 * density).toInt())
            }
            setBackgroundColor(colors.dividerColor)
        })

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (185 * density).toInt())
            isVerticalScrollBarEnabled = false
        }

        val listLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), (8 * density).toInt())
        }

        val saved = prefs.getString("clipboard_history", "") ?: ""
        val items = if (saved.isEmpty()) emptyList() else saved.split("[LIKHIBI_SPLIT]").filter { it.isNotEmpty() }

        if (items.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "Clipboard history is empty\nCopied text will appear here automatically."
                setTextColor(colors.clipboardEmptyText)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (40 * density).toInt(), 0, (40 * density).toInt())
            }
            listLayout.addView(emptyTv)
        } else {
            for (item in items.reversed()) {
                val card = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, (3 * density).toInt(), 0, (3 * density).toInt())
                    }
                    background = createKeyDrawable(
                        colors.clipboardCardBg,
                        colors.clipboardCardPressed,
                        0, false, 10f * density
                    )
                }

                val clipText = TextView(context).apply {
                    text = item
                    setTextColor(colors.keyTextColorNormal)
                    textSize = 14f
                    maxLines = 2
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(0, 0, (8 * density).toInt(), 0)
                    }
                }
                card.addView(clipText)

                val delBtn = ImageView(context).apply {
                    setImageResource(R.drawable.ic_trash_clean)
                    setColorFilter(colors.deleteColor)
                    layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
                    setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                    setOnClickListener {
                        val currentList = prefs.getString("clipboard_history", "")?.split("[LIKHIBI_SPLIT]")?.toMutableList() ?: mutableListOf()
                        currentList.remove(item)
                        prefs.edit().putString("clipboard_history", currentList.joinToString("[LIKHIBI_SPLIT]")).apply()
                        showClipboard()
                    }
                }
                card.addView(delBtn)

                card.setOnClickListener {
                    performPremiumHaptic(HapticType.KEY_STANDARD)
                    listener?.onText(item)
                    viewState = ViewState.KEYS
                    buildKeyboard()
                }

                listLayout.addView(card)
            }
        }

        scrollView.addView(listLayout)
        addView(scrollView)
    }

    /**
     * Redesigned Theme Switcher Shelf displaying professional flagship themes
     */
    fun showThemeSwitcher() {
        removeAllViews()
        viewState = ViewState.THEME_SWITCHER
        val density = resources.displayMetrics.density
        val colors = cachedThemeColors ?: getThemeColors()

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt())
        }

        val title = TextView(context).apply {
            text = "Themes"
            setTextColor(colors.keyTextColorNormal)
            textSize = 16f
            typeface = Typeface.create(getSelectedTypeface(), Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val wallpaperBtn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (32 * density).toInt()).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            background = createKeyDrawable(
                Color.argb(30, Color.red(colors.accentColor), Color.green(colors.accentColor), Color.blue(colors.accentColor)),
                Color.argb(70, Color.red(colors.accentColor), Color.green(colors.accentColor), Color.blue(colors.accentColor)),
                0, false, 16f * density
            )
            setOnClickListener {
                wallpaperPickerListener?.invoke()
            }
        }
        val wpIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_wallpaper_clean)
            setColorFilter(colors.accentColor)
            layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt()).apply {
                setMargins(0, 0, (5 * density).toInt(), 0)
            }
        }
        wallpaperBtn.addView(wpIcon)
        val wpText = TextView(context).apply {
            text = "Custom Photo"
            setTextColor(colors.accentColor)
            textSize = 12f
            typeface = Typeface.create(getSelectedTypeface(), Typeface.BOLD)
        }
        wallpaperBtn.addView(wpText)
        header.addView(wallpaperBtn)

        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_close_clean)
            setColorFilter(colors.keyTextColorModifier)
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            background = createKeyDrawable(
                Color.argb(20, 255, 255, 255),
                Color.argb(50, 255, 255, 255),
                0, false, 8f * density
            )
            setOnClickListener {
                viewState = ViewState.KEYS
                buildKeyboard()
            }
        }
        header.addView(closeBtn)
        addView(header)

        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
                setMargins(0, 0, 0, (8 * density).toInt())
            }
            setBackgroundColor(colors.dividerColor)
        })

        val scrollView = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (165 * density).toInt())
            isHorizontalScrollBarEnabled = false
        }

        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
        }

        val themePresets = listOf(
            Triple("theme_system", "Dynamic System", Triple("#1E1F22", "#2B2D31", "#4E80EE")),
            Triple("theme_slate_dark", "Slate Dark", Triple("#1E1F22", "#2B2D31", "#4E80EE")),
            Triple("theme_chalk_light", "Chalk Light", Triple("#ECEFF1", "#FFFFFF", "#1A73E8")),
            Triple("theme_naga_heritage", "Naga Heritage", Triple("#F7F4EE", "#FFFFFF", "#C62828")),
            Triple("theme_obsidian_oled", "Obsidian OLED", Triple("#000000", "#141416", "#3B82F6")),
            Triple("theme_nordic_frost", "Nordic Frost", Triple("#242933", "#2E3440", "#5E81AC")),
            Triple("theme_custom", "Custom Studio", Triple("#12141C", "#262A36", "#1A73E8"))
        )

        for ((id, name, palette) in themePresets) {
            val isSelected = (currentTheme == id)
            val cardBg = Color.parseColor(palette.first)
            val keyColor = Color.parseColor(palette.second)
            val accentClr = Color.parseColor(palette.third)

            val itemCard = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                layoutParams = LinearLayout.LayoutParams((114 * density).toInt(), (145 * density).toInt()).apply {
                    setMargins((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                }

                background = createKeyDrawable(
                    if (isSelected) Color.argb(40, Color.red(accentClr), Color.green(accentClr), Color.blue(accentClr)) else colors.clipboardCardBg,
                    colors.clipboardCardPressed,
                    0,
                    false,
                    12f * density,
                    if (isSelected) 1.5f else 0.5f,
                    if (isSelected) accentClr else Color.argb(30, 255, 255, 255)
                )
            }

            val isLightTheme = (id == "theme_chalk_light" || id == "theme_naga_heritage")
            val previewBox = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LinearLayout.LayoutParams((98 * density).toInt(), (66 * density).toInt()).apply {
                    setMargins(0, (2 * density).toInt(), 0, (10 * density).toInt())
                }
                setPadding((5 * density).toInt(), (6 * density).toInt(), (5 * density).toInt(), (6 * density).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(cardBg)
                    setStroke(
                        (1f * density).toInt(),
                        if (isLightTheme) Color.parseColor("#CCCCCC") else Color.argb(50, 255, 255, 255)
                    )
                }
            }

            // Preview Row 1
            val pRow1 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (12 * density).toInt()).apply {
                    setMargins(0, 0, 0, (4 * density).toInt())
                }
            }
            for (k in 0..5) {
                pRow1.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins((1 * density).toInt(), 0, (1 * density).toInt(), 0)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 3f * density
                        setColor(keyColor)
                    }
                })
            }
            previewBox.addView(pRow1)

            // Preview Row 2
            val pRow2 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (12 * density).toInt()).apply {
                    setMargins(0, 0, 0, (4 * density).toInt())
                }
            }
            for (k in 0..4) {
                pRow2.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins((1 * density).toInt(), 0, (1 * density).toInt(), 0)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 3f * density
                        setColor(keyColor)
                    }
                })
            }
            previewBox.addView(pRow2)

            // Preview Row 3
            val pRow3 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (12 * density).toInt())
            }
            pRow3.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f).apply {
                    setMargins((1 * density).toInt(), 0, (1 * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 3f * density
                    setColor(keyColor)
                }
            })
            pRow3.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f).apply {
                    setMargins((1 * density).toInt(), 0, (1 * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 3f * density
                    setColor(keyColor)
                }
            })
            pRow3.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f).apply {
                    setMargins((1 * density).toInt(), 0, (1 * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 3f * density
                    setColor(accentClr)
                }
            })
            previewBox.addView(pRow3)

            itemCard.addView(previewBox)

            val nameRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val nameTxt = TextView(context).apply {
                text = name
                setTextColor(if (isSelected) accentClr else colors.keyTextColorNormal)
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create(getSelectedTypeface(), if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            }
            nameRow.addView(nameTxt)

            if (isSelected) {
                val checkIcon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_check_clean)
                    setColorFilter(accentClr)
                    layoutParams = LinearLayout.LayoutParams((14 * density).toInt(), (14 * density).toInt()).apply {
                        setMargins((4 * density).toInt(), 0, 0, 0)
                    }
                }
                nameRow.addView(checkIcon)
            }

            itemCard.addView(nameRow)

            itemCard.setOnClickListener {
                prefs.edit().putString("selected_theme", id).apply()
                cachedThemeColors = null
                themeChangeListener?.invoke()
                viewState = ViewState.KEYS
                applyTheme(forceRebuild = true)
            }

            container.addView(itemCard)
        }

        scrollView.addView(container)
        addView(scrollView)
    }
}

/**
 * CenterCropWallpaperDrawable scales and crops a bitmap to fill the view bounds
 * without distortion and without reporting intrinsic width/height.
 */
class CenterCropWallpaperDrawable(
    private val bitmap: android.graphics.Bitmap,
    private val overlayColor: Int
) : android.graphics.drawable.Drawable() {
    private val drawMatrix = android.graphics.Matrix()
    private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = android.graphics.Paint().apply {
        color = overlayColor
        style = android.graphics.Paint.Style.FILL
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f || bitmap.isRecycled) return

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = Math.max(w / bw, h / bh)
        val dx = (w - bw * scale) * 0.5f
        val dy = (h - bh * scale) * 0.5f

        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)

        canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(bitmap, drawMatrix, bitmapPaint)
        if (android.graphics.Color.alpha(overlayColor) > 0) {
            canvas.drawRect(bounds, overlayPaint)
        }
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
