package com.likhibi.android

import com.likhibi.keyboard.R
import com.likhibi.nlp.engine.NagameseOfflineEngine

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.likhibi.nlp.translate.RemoteTranslator
import com.likhibi.nlp.translate.SentencePieceTokenizer
import com.likhibi.nlp.translate.StudentTranslator
import java.util.concurrent.Executors

class LikhibiImeService : InputMethodService() {
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastTheme = ""
    private var lastFont = ""

    private var keyboardView: CustomKeyboardView? = null
    private var imeRootView: View? = null
    private var suggestionBar: LinearLayout? = null
    private var suggestionViews: List<TextView> = emptyList()

    // Toolbar elements
    private var btnToggle: android.widget.ImageView? = null
    private var iconToolClip: android.widget.ImageView? = null
    private var iconToolTheme: android.widget.ImageView? = null
    private var iconToolSettings: android.widget.ImageView? = null
    private var textToolClip: TextView? = null
    private var textToolTheme: TextView? = null
    private var textToolSettings: TextView? = null
    private var suggestionsLayout: LinearLayout? = null
    private var toolsLayout: LinearLayout? = null
    private var btnToolClip: View? = null
    private var btnToolTheme: View? = null
    private var btnToolSettings: View? = null
    private var toolbarDivider: View? = null
    private var barDivider: View? = null

    // Translate strip: English <-> Nagamese on the phone, with the Likhibi server as an online option
    private var btnToolTranslate: View? = null
    private var iconToolTranslate: android.widget.ImageView? = null
    private var textToolTranslate: TextView? = null
    private var translateContainer: LinearLayout? = null
    private var translateDirectionView: TextView? = null
    private var translateLabel: TextView? = null
    private var translateResult: TextView? = null
    private var translateAction: TextView? = null
    private var translateInsert: android.widget.ImageView? = null

    private var translateMode = false
    private var translateDirection = "en-nag"
    private var translateJob: Job? = null
    private var translateGeneration = 0
    private var pendingSource: TranslationSource? = null
    private var previewText = ""
    private var undoState: UndoState? = null

    /** The text being translated: a selection, or the sentence that ends at the cursor. */
    private data class TranslationSource(val text: String, val deleteCount: Int, val isSelection: Boolean)
    private data class UndoState(val original: String, val inserted: String)

    // ONNX Runtime calls run one at a time on their own thread, never on the main thread.
    private val translateDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Volatile private var student: StudentTranslator? = null
    @Volatile private var studentState = "unloaded"  // unloaded, ready, missing, failed

    // Clipboard elements
    private var clipboardManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureCurrentClip() }
    private var isClipListenerRegistered = false

    private lateinit var offlineEngine: NagameseOfflineEngine

    private var suggestionsEnabledForField: Boolean = true
    // False in password and incognito fields: nothing typed there is remembered.
    private var learningAllowed: Boolean = true
    private var currentComposing: StringBuilder = StringBuilder()

    companion object {
        const val KEYCODE_DELETE = -5
        const val KEYCODE_SHIFT = -1
        const val KEYCODE_SYMBOL_SWITCH = -2
        const val KEYCODE_SYMBOL_EXTRA = -3
        const val KEYCODE_SYMBOL_BACK = -4
        const val KEYCODE_EMOJI_SWITCH = -10
        const val KEYCODE_SWITCH_IME = -11
    }

    override fun onCreate() {
        super.onCreate()
        offlineEngine = NagameseOfflineEngine(this)

        // Load the word tree and n-gram tables off the main thread; typing works meanwhile.
        serviceScope.launch(Dispatchers.Default) {
            offlineEngine.warmUp()
        }
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_view, null)
        imeRootView = view
        suggestionBar = view.findViewById(R.id.suggestion_bar)
        val s1 = view.findViewById<TextView>(R.id.suggestion_1)
        val s2 = view.findViewById<TextView>(R.id.suggestion_2)
        val s3 = view.findViewById<TextView>(R.id.suggestion_3)
        suggestionViews = listOf(s1, s2, s3)

        for (tv in suggestionViews) {
            tv.setOnClickListener {
                keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                val word = (it as TextView).text?.toString()?.trim().orEmpty()
                if (word.isNotEmpty()) {
                    acceptSuggestion(word)
                }
            }
        }

        val prefs = getSharedPreferences("likhibi_keyboard_prefs", Context.MODE_PRIVATE)
        lastTheme = prefs.getString("selected_theme", CustomKeyboardView.DEFAULT_THEME) ?: CustomKeyboardView.DEFAULT_THEME
        lastFont = prefs.getString("selected_font", "sans-serif") ?: "sans-serif"

        // Initialize and bind toolbar controls
        btnToggle = view.findViewById(R.id.btn_toolbar_toggle)
        suggestionsLayout = view.findViewById(R.id.suggestions_container)
        toolsLayout = view.findViewById(R.id.tools_container)
        toolbarDivider = view.findViewById(R.id.toolbar_divider)
        barDivider = view.findViewById(R.id.bar_divider)
        btnToolClip = view.findViewById(R.id.btn_tool_clip)
        btnToolTheme = view.findViewById(R.id.btn_tool_theme)
        btnToolSettings = view.findViewById(R.id.btn_tool_settings)
        iconToolClip = view.findViewById(R.id.icon_tool_clip)
        iconToolTheme = view.findViewById(R.id.icon_tool_theme)
        iconToolSettings = view.findViewById(R.id.icon_tool_settings)
        textToolClip = view.findViewById(R.id.text_tool_clip)
        textToolTheme = view.findViewById(R.id.text_tool_theme)
        textToolSettings = view.findViewById(R.id.text_tool_settings)
        iconToolClip = view.findViewById(R.id.icon_tool_clip)
        iconToolTheme = view.findViewById(R.id.icon_tool_theme)
        iconToolSettings = view.findViewById(R.id.icon_tool_settings)
        textToolClip = view.findViewById(R.id.text_tool_clip)
        textToolTheme = view.findViewById(R.id.text_tool_theme)
        textToolSettings = view.findViewById(R.id.text_tool_settings)

        btnToolTranslate = view.findViewById(R.id.btn_tool_translate)
        iconToolTranslate = view.findViewById(R.id.icon_tool_translate)
        textToolTranslate = view.findViewById(R.id.text_tool_translate)
        translateContainer = view.findViewById(R.id.translate_container)
        translateDirectionView = view.findViewById(R.id.translate_direction)
        translateLabel = view.findViewById(R.id.translate_label)
        translateResult = view.findViewById(R.id.translate_result)
        translateAction = view.findViewById(R.id.translate_action)
        translateInsert = view.findViewById(R.id.translate_insert)
        translateDirection = prefs.getString("translate_direction", "en-nag") ?: "en-nag"

        btnToggle?.setOnClickListener {
            keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            toggleToolbar()
        }

        btnToolTranslate?.setOnClickListener {
            keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            openTranslate()
        }
        translateDirectionView?.setOnClickListener {
            keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            translateDirection = if (translateDirection == "en-nag") "nag-en" else "en-nag"
            getSharedPreferences("likhibi_keyboard_prefs", MODE_PRIVATE).edit().putString("translate_direction", translateDirection).apply()
            renderDirection()
            undoState = null
            scheduleTranslation(immediate = true)
        }
        val insert = View.OnClickListener {
            keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            insertTranslation()
        }
        translateInsert?.setOnClickListener(insert)
        view.findViewById<View>(R.id.translate_preview)?.setOnClickListener(insert)
        translateAction?.setOnClickListener {
            keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (undoState != null) undoInsert() else pendingSource?.let { requestOnline(it, translateGeneration) }
        }

        btnToolClip?.setOnClickListener {
            keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            keyboardView?.showClipboard()
        }

        btnToolTheme?.setOnClickListener {
            keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            keyboardView?.showThemeSwitcher()
        }

        btnToolSettings?.setOnClickListener {
            keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        keyboardView = view.findViewById<CustomKeyboardView>(R.id.keyboard_view).also {
            it.listener = object : CustomKeyboardView.OnKeyActionListener {
                override fun onKey(code: Int) {
                    handleCustomKey(code)
                }

                override fun onText(text: String) {
                    handleText(text)
                }
            }
        }

        // Apply active theme immediately on view creation
        keyboardView?.applyTheme()
        applySuggestionBarTheme()

        keyboardView?.themeChangeListener = {
            applySuggestionBarTheme()
        }
        keyboardView?.wallpaperPickerListener = {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("ACTION_PICK_WALLPAPER", true)
            }
            startActivity(intent)
        }

        registerClipboardListener()

        return view
    }

    /**
     * Applies theme-specific colors to the suggestion bar, toolbar, and tool buttons
     */
    private fun applySuggestionBarTheme() {
        val prefs = getSharedPreferences("likhibi_keyboard_prefs", MODE_PRIVATE)
        val theme = prefs.getString("selected_theme", CustomKeyboardView.DEFAULT_THEME) ?: CustomKeyboardView.DEFAULT_THEME
        val isSystemNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        data class BarColors(
            val barBg: Int, val textNormal: Int, val textAccent: Int,
            val toggleColor: Int, val divider: Int, val toolText: Int
        )

        val slateColors = BarColors(
            Color.parseColor("#1E1F22"), Color.parseColor("#949BA4"), Color.parseColor("#6BA0F5"),
            Color.parseColor("#6BA0F5"), Color.parseColor("#2B2D31"), Color.parseColor("#D1D5DB")
        )

        val chalkColors = BarColors(
            Color.parseColor("#ECEFF1"), Color.parseColor("#5F6368"), Color.parseColor("#1A73E8"),
            Color.parseColor("#1A73E8"), Color.parseColor("#D9DEE3"), Color.parseColor("#49454F")
        )

        val c = when (theme) {
            "theme_system" -> if (isSystemNight) slateColors else chalkColors
            "theme_slate_dark", "theme_midnight_glass", "theme_liquid_glass" -> slateColors
            "theme_chalk_light", "theme_pure_minimal", "theme_material_you" -> chalkColors
            "theme_naga_heritage" -> {
                if (isSystemNight) {
                    BarColors(
                        Color.parseColor("#141416"), Color.parseColor("#A8A096"), Color.parseColor("#E53935"),
                        Color.parseColor("#E53935"), Color.parseColor("#262428"), Color.parseColor("#F5EFE6")
                    )
                } else {
                    BarColors(
                        Color.parseColor("#F7F4EE"), Color.parseColor("#78716C"), Color.parseColor("#C62828"),
                        Color.parseColor("#C62828"), Color.parseColor("#E2D9CC"), Color.parseColor("#1C1917")
                    )
                }
            }
            "theme_obsidian_oled" -> BarColors(
                Color.parseColor("#000000"), Color.parseColor("#9CA3AF"), Color.parseColor("#60A5FA"),
                Color.parseColor("#60A5FA"), Color.parseColor("#1F2228"), Color.parseColor("#E5E7EB")
            )
            "theme_nordic_frost" -> BarColors(
                Color.parseColor("#242933"), Color.parseColor("#D8DEE9"), Color.parseColor("#88C0D0"),
                Color.parseColor("#88C0D0"), Color.parseColor("#2E3440"), Color.parseColor("#ECEFF4")
            )
            "theme_custom" -> {
                val accentColorValue = prefs.getInt("custom_accent_color", Color.parseColor("#1A73E8"))
                val overlayOpacity = prefs.getInt("custom_overlay_opacity", 140).coerceIn(0, 220)
                BarColors(
                    Color.argb(overlayOpacity, 16, 18, 24), Color.parseColor("#9CA3AF"), accentColorValue,
                    accentColorValue, Color.argb(40, 255, 255, 255), Color.WHITE
                )
            }
            else -> if (isSystemNight) slateColors else chalkColors
        }

        toolTextColor = c.toolText
        if (toolsLayout?.visibility == View.VISIBLE || translateMode) btnToggle?.setColorFilter(toolTextColor)
        suggestionBar?.setBackgroundColor(c.barBg)
        imeRootView?.setBackgroundColor(c.barBg)
        // btnToggle logo is pristine background-less
        toolbarDivider?.setBackgroundColor(c.divider)
        barDivider?.setBackgroundColor(c.divider)

        // Suggestion text colors
        suggestionViews.getOrNull(0)?.setTextColor(c.textNormal)
        suggestionViews.getOrNull(1)?.setTextColor(c.textAccent)
        suggestionViews.getOrNull(2)?.setTextColor(c.textNormal)

        // Suggestion separator dividers
        suggestionsLayout?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child !is TextView && child.layoutParams.width == 1) {
                    child.setBackgroundColor(c.divider)
                }
            }
        }

        // Tool button vector tint & text colors
        textToolClip?.setTextColor(c.toolText)
        textToolTheme?.setTextColor(c.toolText)
        textToolSettings?.setTextColor(c.toolText)
        iconToolClip?.setColorFilter(c.toolText)
        iconToolTheme?.setColorFilter(c.toolText)
        iconToolSettings?.setColorFilter(c.toolText)
        textToolTranslate?.setTextColor(c.toolText)
        iconToolTranslate?.setColorFilter(c.toolText)
        translateDirectionView?.setTextColor(c.textAccent)
        translateAction?.setTextColor(c.textAccent)
        translateInsert?.setColorFilter(c.textAccent)
        translateLabel?.setTextColor(c.textNormal)
        translateResult?.setTextColor(c.toolText)
    }

    // The close icon is a white glyph, so it takes the theme's tool colour; the logo keeps its own colours.
    private var toolTextColor: Int = Color.WHITE

    private fun showToggleClose() {
        btnToggle?.setImageResource(R.drawable.ic_close_clean)
        btnToggle?.setColorFilter(toolTextColor)
    }

    private fun showToggleLogo() {
        btnToggle?.setImageResource(R.drawable.likhibi_logo)
        btnToggle?.clearColorFilter()
    }

    private fun toggleToolbar() {
        if (translateMode) {
            closeTranslate()
            return
        }
        if (toolsLayout?.visibility == View.VISIBLE) {
            // Close tools, show suggestions
            toolsLayout?.visibility = View.GONE
            suggestionsLayout?.visibility = View.VISIBLE
            showToggleLogo()
            btnToggle?.rotation = 0f
            // Revert keyboard shelf if showing clipboard or theme swappers
            if (keyboardView?.getViewState() != CustomKeyboardView.ViewState.KEYS) {
                keyboardView?.switchMode(CustomKeyboardView.Mode.QWERTY)
            }
        } else {
            // Show tools, hide suggestions
            suggestionsLayout?.visibility = View.GONE
            toolsLayout?.visibility = View.VISIBLE
            showToggleClose()
        }
    }

    private fun registerClipboardListener() {
        if (isClipListenerRegistered) return
        try {
            if (clipboardManager == null) {
                clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            }
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
            isClipListenerRegistered = true
        } catch (e: Exception) {
            Log.e("LikhibiIME", "Failed to register clipboard listener: ${e.message}")
        }
    }

    private fun unregisterClipboardListener() {
        if (!isClipListenerRegistered) return
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipListener)
            isClipListenerRegistered = false
        } catch (e: Exception) {
            Log.e("LikhibiIME", "Failed to unregister clipboard listener: ${e.message}")
        }
    }

    /**
     * Intercepts copied text while keyboard is active and saves to SharedPreferences
     */
    private fun captureCurrentClip() {
        try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    saveToClipboardHistory(text)
                }
            }
        } catch (e: Exception) {
            Log.e("LikhibiIME", "Failed to capture clipboard text: ${e.message}")
        }
    }

    private fun saveToClipboardHistory(text: String) {
        val prefs = getSharedPreferences("likhibi_keyboard_prefs", MODE_PRIVATE)
        val saved = prefs.getString("clipboard_history", "") ?: ""
        val clips = if (saved.isEmpty()) mutableListOf() else saved.split("[LIKHIBI_SPLIT]").filter { it.isNotEmpty() }.toMutableList()

        // Push new item to the top by removing past duplicates and inserting
        clips.remove(text)
        clips.add(text)

        // Limit clipboard history to last 15 items
        val trimmed = if (clips.size > 15) clips.takeLast(15) else clips

        prefs.edit().putString("clipboard_history", trimmed.joinToString("[LIKHIBI_SPLIT]")).apply()

        // Live refresh the clipboard layout if currently showing on screen
        if (keyboardView?.getViewState() == CustomKeyboardView.ViewState.CLIPBOARD) {
            keyboardView?.showClipboard()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        val prefs = getSharedPreferences("likhibi_keyboard_prefs", Context.MODE_PRIVATE)
        val currentTheme = prefs.getString("selected_theme", CustomKeyboardView.DEFAULT_THEME) ?: CustomKeyboardView.DEFAULT_THEME
        val currentFont = prefs.getString("selected_font", "sans-serif") ?: "sans-serif"
        
        if (currentTheme != lastTheme || currentFont != lastFont) {
            setInputView(onCreateInputView())
        }

        super.onStartInput(attribute, restarting)

        val imeOptions = attribute?.imeOptions ?: 0
        val inputType = attribute?.inputType ?: 0
        val enterAction = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        val enterLabel = if (noEnterAction || isMultiLine) {
            "↵"
        } else {
            when (enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> "🔍"
                EditorInfo.IME_ACTION_GO -> "➔"
                EditorInfo.IME_ACTION_SEND -> "➤"
                EditorInfo.IME_ACTION_NEXT -> "↵"
                EditorInfo.IME_ACTION_DONE -> "✓"
                else -> "↵"
            }
        }
        keyboardView?.enterKeyLabel = enterLabel
        currentComposing = StringBuilder()
        suggestionsEnabledForField = attribute?.let { !isPasswordField(it) } ?: true
        val incognito = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        learningAllowed = suggestionsEnabledForField && !incognito
        updateSuggestionsForCurrentState()
        suggestionBar?.visibility = if (suggestionsEnabledForField) View.VISIBLE else View.GONE
        
        // Auto reset toolbar state back to suggestions on launch
        toolsLayout?.visibility = View.GONE
        suggestionsLayout?.visibility = View.VISIBLE
        showToggleLogo()
        translateMode = false
        translateContainer?.visibility = View.GONE
        // Nothing typed in a password or incognito field is translated or sent anywhere.
        btnToolTranslate?.visibility = if (learningAllowed) View.VISIBLE else View.GONE

        // Batch reset visual state to avoid redundant UI rebuilds and ANRs
        keyboardView?.resetState(CustomKeyboardView.Mode.QWERTY, false)
        applySuggestionBarTheme()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentComposing = StringBuilder()
        updateSuggestionBar(offlineEngine.getPopularWords())
    }

    override fun onDestroy() {
        super.onDestroy()
        translateJob?.cancel()
        student?.close()
        translateDispatcher.close()
        unregisterClipboardListener()
        serviceScope.coroutineContext[Job]?.cancel()
    }

    private fun handleCustomKey(code: Int) {
        val ic = currentInputConnection ?: return

        when (code) {
            KEYCODE_DELETE -> handleBackspace(ic)
            KEYCODE_SHIFT -> handleShift()
            KEYCODE_SYMBOL_SWITCH -> handleSymbolSwitch()
            KEYCODE_SYMBOL_EXTRA -> handleSymbolExtra()
            KEYCODE_SYMBOL_BACK -> handleSymbolBack()
            KEYCODE_EMOJI_SWITCH -> handleEmojiSwitch()
            KEYCODE_SWITCH_IME -> {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showInputMethodPicker()
            }
            10 -> handleEnter(ic)
            32 -> handleSpace(ic)
            Int.MAX_VALUE -> ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            Int.MIN_VALUE -> ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
            else -> handleCharacter(ic, code)
        }
    }

    private var lastShiftTime = 0L

    private fun handleShift() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTime < 300) {
            keyboardView?.isCapsLock = true
            keyboardView?.setShifted(true)
        } else {
            keyboardView?.isCapsLock = false
            keyboardView?.setShifted(!(keyboardView?.isShifted() ?: false))
        }
        lastShiftTime = now
    }

    private fun handleText(text: String) {
        val ic = currentInputConnection ?: return
        
        // Commit current composition first if active
        val composing = currentComposing.toString().trim()
        if (composing.isNotEmpty()) {
            learnWord(composing)
            ic.commitText(currentComposing.toString(), 1)
            ic.finishComposingText()
            currentComposing = StringBuilder()
        }
        ic.commitText(text, 1)

        // Dynamically learn the bigram from the last 2 committed words
        val last2 = getLastWords(2)
        if (last2.size == 2) {
            learnBigram(last2[0], last2[1])
        }
    }

    private fun handleCharacter(ic: InputConnection, primaryCode: Int) {
        var ch = primaryCode.toChar().toString()
        if (keyboardView?.isShifted() == true) {
            ch = ch.uppercase()
        }
        currentComposing.append(ch)
        ic.setComposingText(currentComposing, 1)
        updateSuggestionsForCurrentState()
        
        // Auto-shift back to lowercase after typing a letter
        val kv = keyboardView ?: return
        if (kv.isShifted() && !kv.isCapsLock) {
            kv.setShifted(false)
        }
    }

    private fun handleBackspace(ic: InputConnection) {
        if (currentComposing.isNotEmpty()) {
            currentComposing.deleteAt(currentComposing.length - 1)
            if (currentComposing.isEmpty()) {
                ic.finishComposingText()
            } else {
                ic.setComposingText(currentComposing, 1)
            }
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        updateSuggestionsForCurrentState()
    }

    private fun handleSpace(ic: InputConnection) {
        val composing = currentComposing.toString().trim()
        if (composing.isNotEmpty()) {
            learnWord(composing)
            ic.commitText(currentComposing.toString(), 1)
            ic.finishComposingText()
            currentComposing = StringBuilder()
        }
        ic.commitText(" ", 1)

        // Dynamically learn the bigram from the last 2 committed words
        val last2 = getLastWords(2)
        if (last2.size == 2) {
            learnBigram(last2[0], last2[1])
        }

        updateSuggestionsForCurrentState()
    }

    private fun handleEnter(ic: InputConnection) {
        val composing = currentComposing.toString().trim()
        if (composing.isNotEmpty()) {
            learnWord(composing)
            ic.commitText(currentComposing.toString(), 1)
            ic.finishComposingText()
            currentComposing = StringBuilder()
        }

        val info = currentInputEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val inputType = info?.inputType ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (!noEnterAction && !isMultiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            // Newline for multiline chat & text fields (WhatsApp, Telegram, Notes, Messages)
            val committed = ic.commitText("\n", 1)
            if (!committed) {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            }
        }

        // Dynamically learn the bigram from the last 2 committed words
        val last2 = getLastWords(2)
        if (last2.size == 2) {
            learnBigram(last2[0], last2[1])
        }

        updateSuggestionsForCurrentState()
    }


    private fun handleSymbolSwitch() {
        val kv = keyboardView ?: return
        if (kv.getMode() != CustomKeyboardView.Mode.QWERTY) {
            kv.switchMode(CustomKeyboardView.Mode.QWERTY)
        } else {
            kv.switchMode(CustomKeyboardView.Mode.SYMBOLS)
        }
    }

    private fun handleSymbolExtra() {
        keyboardView?.switchMode(CustomKeyboardView.Mode.EXTRA_SYMBOLS)
    }

    private fun handleSymbolBack() {
        keyboardView?.switchMode(CustomKeyboardView.Mode.SYMBOLS)
    }

    private fun handleEmojiSwitch() {
        val kv = keyboardView ?: return
        if (kv.getViewState() == CustomKeyboardView.ViewState.EMOJI_SHELF) {
            kv.switchMode(CustomKeyboardView.Mode.QWERTY)
        } else {
            kv.showEmojiShelf()
        }
    }

    private fun acceptSuggestion(word: String) {
        val ic = currentInputConnection ?: return

        val composing = currentComposing.toString().trim()
        if (composing.isNotEmpty()) {
            learnWord(composing)
        }

        ic.commitText(word, 1)
        ic.finishComposingText()
        ic.commitText(" ", 1)
        currentComposing = StringBuilder()

        learnWord(word)

        val last2 = getLastWords(2)
        if (last2.size == 2) {
            learnBigram(last2[0], last2[1])
        }

        updateSuggestionsForCurrentState()
    }

    private fun updateSuggestionsForCurrentState() {
        if (translateMode) {
            scheduleTranslation()
            return
        }
        if (!suggestionsEnabledForField) return

        val composing = currentComposing.toString().trim()
        if (composing.isNotEmpty()) {
            // Auto revert toolbar back to Suggestions Mode when user starts active typing
            if (toolsLayout?.visibility == View.VISIBLE) {
                toolsLayout?.visibility = View.GONE
                suggestionsLayout?.visibility = View.VISIBLE
                showToggleLogo()
                if (keyboardView?.getViewState() != CustomKeyboardView.ViewState.KEYS) {
                    keyboardView?.switchMode(CustomKeyboardView.Mode.QWERTY)
                }
            }

            // Completions use the words already typed, so "moi bazar te j" ranks "jabo" first.
            val localMatches = offlineEngine.getPrefixMatches(predictionContext(includeComposing = false), composing)
            if (localMatches.isNotEmpty()) {
                updateSuggestionBar(styled(localMatches))
            } else {
                updateSuggestionBar(listOf(composing, "", ""))
            }
        } else {
            val context = predictionContext(includeComposing = true)
            if (context.isEmpty()) {
                updateSuggestionBar(styled(offlineEngine.getPopularWords()))
                return
            }
            updateSuggestionBar(styled(offlineEngine.getLocalNextWordPredictions(context)))
        }
    }

    /**
     * Suggestions follow the capital letter the person started the word with, and the
     * first word of a sentence is offered capitalised. Names already carry their capital.
     */
    private fun styled(words: List<String>): List<String> {
        val composing = currentComposing.toString()
        val capitalise = if (composing.isNotEmpty()) composing[0].isUpperCase() else atSentenceStart()
        return if (capitalise) words.map { word -> word.replaceFirstChar { it.uppercaseChar() } } else words
    }

    private fun atSentenceStart(): Boolean {
        val before = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty().trimEnd()
        return before.isEmpty() || before.last() in ".?!\n"
    }

    /**
     * The last few words before the cursor, lowercase and without punctuation, as the
     * prediction tables store them. A sentence end restarts the context at "<s>", the
     * marker the n-gram tables use for the start of a sentence.
     */
    private fun predictionContext(includeComposing: Boolean, maxWords: Int = 3): List<String> {
        val ic = currentInputConnection ?: return emptyList()
        var before = ic.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        // Most editors already include the composing word in the text before the cursor.
        val composing = currentComposing.toString()
        if (composing.isNotEmpty() && before.endsWith(composing)) before = before.dropLast(composing.length)
        val text = if (includeComposing) before + composing else before
        val context = mutableListOf<String>()
        for (raw in text.split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            val word = raw.lowercase().filter { it.isLetter() }
            if (word.isNotEmpty()) context.add(word)
            if (raw.last() in ".?!") {
                context.clear()
                context.add("<s>")
            }
        }
        return context.takeLast(maxWords)
    }

    // Text typed for translation (often English) never reaches the Nagamese user dictionary.
    private fun learnWord(word: String) {
        if (learningAllowed && !translateMode) offlineEngine.learnWord(word)
    }

    private fun learnBigram(first: String, second: String) {
        if (learningAllowed && !translateMode) offlineEngine.learnBigram(first, second)
    }

    // ------------------------------------------------------------------ translate strip

    private fun openTranslate() {
        translateMode = true
        undoState = null
        previewText = ""
        toolsLayout?.visibility = View.GONE
        suggestionsLayout?.visibility = View.GONE
        translateContainer?.visibility = View.VISIBLE
        showToggleClose()
        if (keyboardView?.getViewState() != CustomKeyboardView.ViewState.KEYS) {
            keyboardView?.switchMode(CustomKeyboardView.Mode.QWERTY)
        }
        renderDirection()
        updateAction()
        scheduleTranslation(immediate = true)
    }

    private fun closeTranslate() {
        translateMode = false
        translateGeneration++
        translateJob?.cancel()
        translateContainer?.visibility = View.GONE
        suggestionsLayout?.visibility = View.VISIBLE
        showToggleLogo()
        updateSuggestionsForCurrentState()
    }

    private fun renderDirection() {
        translateDirectionView?.text = if (translateDirection == "en-nag") "EN → NAG" else "NAG → EN"
    }

    private fun showPreview(label: String, text: String) {
        translateLabel?.text = label
        translateResult?.text = text
    }

    private fun serverUrl(): String =
        getSharedPreferences("likhibi_keyboard_prefs", MODE_PRIVATE).getString("translate_server_url", "")?.trim().orEmpty()

    private fun updateAction() {
        val action = translateAction ?: return
        when {
            undoState != null -> {
                action.text = "Undo"
                action.visibility = View.VISIBLE
            }
            serverUrl().isNotEmpty() -> {
                action.text = "Online"
                action.visibility = View.VISIBLE
            }
            else -> action.visibility = View.GONE
        }
    }

    private fun hintLabel(): String =
        if (translateDirection == "en-nag") "Type English: Nagamese appears here, translated on this phone"
        else "Type Nagamese: English appears here, translated on this phone"

    /** Translates the current sentence shortly after typing pauses. */
    private fun scheduleTranslation(immediate: Boolean = false) {
        val generation = ++translateGeneration
        translateJob?.cancel()
        translateJob = serviceScope.launch {
            if (!immediate) delay(350)
            val source = currentSource()
            pendingSource = source
            if (source == null || source.text.isBlank()) {
                previewText = ""
                showPreview(hintLabel(), "")
                updateAction()
                return@launch
            }
            val target = translateDirection.substringAfter("-")
            showPreview("Translating on this phone…", previewText)
            val result = withContext(translateDispatcher) {
                runCatching { loadStudent()?.translate(source.text, target) }.getOrNull()
            }
            if (generation != translateGeneration) return@launch
            if (result != null && result.text.isNotBlank()) {
                previewText = result.text
                showPreview("On this phone · ${result.millis} ms · tap to insert", result.text)
            } else {
                previewText = ""
                val problem = when (studentState) {
                    "missing" -> "The offline model is not installed in this build"
                    "failed" -> "The offline model could not start on this phone"
                    else -> "No translation for this text"
                }
                showPreview(problem + if (serverUrl().isNotEmpty()) ". Tap Online." else ".", "")
            }
            updateAction()
            val autoOnline = getSharedPreferences("likhibi_keyboard_prefs", MODE_PRIVATE).getBoolean("translate_auto_online", false)
            if (autoOnline && serverUrl().isNotEmpty()) requestOnline(source, generation)
        }
    }

    private fun loadStudent(): StudentTranslator? {
        student?.let { return it }
        if (studentState == "missing" || studentState == "failed") return null
        return try {
            val tokenizer = assets.open("translate/tokenizer.json").use { SentencePieceTokenizer(it) }
            val encoder = assets.open("translate/encoder.onnx").use { it.readBytes() }
            val decoder = assets.open("translate/decoder.onnx").use { it.readBytes() }
            StudentTranslator(encoder, decoder, tokenizer).also {
                student = it
                studentState = "ready"
            }
        } catch (e: java.io.FileNotFoundException) {
            studentState = "missing"
            null
        } catch (e: Throwable) {
            Log.e("LikhibiIME", "Offline translator failed to load", e)
            studentState = "failed"
            null
        }
    }

    /** Sends only this sentence to the Likhibi server, and only because the person asked. */
    private fun requestOnline(source: TranslationSource, generation: Int) {
        val server = serverUrl()
        if (server.isEmpty()) return
        showPreview("Asking the Likhibi server…", previewText)
        serviceScope.launch {
            val (from, to) = translateDirection.split("-")
            val result = withContext(Dispatchers.IO) { runCatching { RemoteTranslator(server).translate(source.text, from, to) } }
            if (generation != translateGeneration) return@launch
            result.onSuccess {
                previewText = it.text
                showPreview("Online · ${it.engineName} · ${it.millis} ms · tap to insert", it.text)
            }.onFailure {
                showPreview("Online translation failed: ${it.message ?: "no connection"}", previewText)
            }
            updateAction()
        }
    }

    private fun currentSource(): TranslationSource? {
        val ic = currentInputConnection ?: return null
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrBlank()) return TranslationSource(selected.trim(), 0, isSelection = true)
        val before = ic.getTextBeforeCursor(600, 0)?.toString() ?: return null
        val text = before.trimEnd()
        val trailing = before.length - text.length
        // The sentence ending at the cursor; a finished sentence ("Moi kali jabo.") still counts.
        val searchEnd = if (text.isNotEmpty() && text.last() in ".?!") text.length - 1 else text.length
        val boundary = (searchEnd - 1 downTo 0).firstOrNull { text[it] == '.' || text[it] == '?' || text[it] == '!' || text[it] == '\n' } ?: -1
        val sentence = text.substring(boundary + 1)
        val leading = sentence.length - sentence.trimStart().length
        return TranslationSource(sentence.trim(), sentence.length - leading + trailing, isSelection = false)
    }

    private fun insertTranslation() {
        val ic = currentInputConnection ?: return
        val source = pendingSource ?: return
        val translation = previewText.takeIf { it.isNotBlank() } ?: return
        translateGeneration++
        ic.beginBatchEdit()
        ic.finishComposingText()
        currentComposing = StringBuilder()
        undoState = if (source.isSelection) {
            val original = ic.getSelectedText(0)?.toString() ?: source.text
            ic.commitText(translation, 1)
            UndoState(original, translation)
        } else {
            val original = ic.getTextBeforeCursor(source.deleteCount, 0)?.toString().orEmpty()
            ic.deleteSurroundingText(source.deleteCount, 0)
            val inserted = "$translation "
            ic.commitText(inserted, 1)
            UndoState(original, inserted)
        }
        ic.endBatchEdit()
        previewText = ""
        showPreview("Inserted · Undo puts your text back", translation)
        updateAction()
    }

    private fun undoInsert() {
        val ic = currentInputConnection ?: return
        val undo = undoState ?: return
        if (ic.getTextBeforeCursor(undo.inserted.length, 0)?.toString() == undo.inserted) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(undo.inserted.length, 0)
            ic.commitText(undo.original, 1)
            ic.endBatchEdit()
        }
        undoState = null
        scheduleTranslation(immediate = true)
    }

    private fun getLastWords(maxWords: Int): List<String> {
        val ic = currentInputConnection ?: return emptyList()
        val before = ic.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        val combined = (before + currentComposing.toString()).trim()
        if (combined.isEmpty()) return emptyList()
        val tokens = combined
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return tokens.takeLast(maxWords)
    }

    private fun updateSuggestionBar(words: List<String>) {
        if (!suggestionsEnabledForField) return
        val padded = (words + listOf("", "", "")).take(3)
        for (i in 0..2) {
            suggestionViews.getOrNull(i)?.text = padded[i]
        }
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        val klass = type and InputType.TYPE_MASK_CLASS
        val textPassword = klass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        val numberPassword = klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return textPassword || numberPassword
    }
}
