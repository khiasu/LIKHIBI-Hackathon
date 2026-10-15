package com.likhibi.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.likhibi.keyboard.R
import com.likhibi.nlp.engine.NagameseOfflineEngine
import com.likhibi.nlp.translate.RemoteTranslator
import com.likhibi.nlp.translate.SentencePieceTokenizer
import com.likhibi.nlp.translate.StudentTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class LikhibiImeService : InputMethodService() {
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: SharedPreferences
    private lateinit var offlineEngine: NagameseOfflineEngine
    private lateinit var clipboardStore: ClipboardStore

    private var root: View? = null
    private var keyboardView: CustomKeyboardView? = null
    private var stripContainer: View? = null
    private var suggestionStrip: View? = null
    private var toolbarStrip: View? = null
    private var translateStrip: View? = null
    private var suggestionViews: List<TextView> = emptyList()
    private var suggestionDividers: List<View> = emptyList()
    private var oneHandLeft: View? = null
    private var oneHandRight: View? = null

    // Settings changed in the settings app are applied the next time the keyboard opens.
    @Volatile private var visualsDirty = false
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && VISUAL_KEYS.any { key.startsWith(it) }) visualsDirty = true
    }

    // Translate strip: English <-> Nagamese on the phone, with the Likhibi server as an online option.
    private var translateMode = false
    private var translateDirection = "en-nag"
    private var translateJob: Job? = null
    private var translateGeneration = 0
    private var pendingSource: TranslationSource? = null
    private var previewText = ""
    private var shownText = ""
    private var undoState: UndoState? = null

    /** The text being translated: a selection, or the sentence that ends at the cursor. */
    private data class TranslationSource(val text: String, val deleteCount: Int, val isSelection: Boolean)
    private data class UndoState(val original: String, val inserted: String)

    // ONNX Runtime calls run one at a time on their own thread, never on the main thread.
    private val translateDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Volatile private var student: StudentTranslator? = null
    @Volatile private var studentState = "unloaded"  // unloaded, ready, missing, failed

    private var clipboardManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureCurrentClip() }
    private var isClipListenerRegistered = false

    private var suggestionsEnabledForField = true
    // False in password and incognito fields: nothing typed there is remembered.
    private var learningAllowed = true
    private var currentComposing = StringBuilder()
    private var lastLearnedReset = 0L

    companion object {
        const val KEYCODE_DELETE = CustomKeyboardView.CODE_DELETE
        const val KEYCODE_SHIFT = CustomKeyboardView.CODE_SHIFT
        const val KEYCODE_SYMBOL_SWITCH = CustomKeyboardView.CODE_SYMBOLS
        const val KEYCODE_SYMBOL_EXTRA = CustomKeyboardView.CODE_EXTRA_SYMBOLS
        const val KEYCODE_SYMBOL_BACK = CustomKeyboardView.CODE_SYMBOLS_BACK
        const val KEYCODE_EMOJI_SWITCH = CustomKeyboardView.CODE_EMOJI
        const val KEYCODE_SWITCH_IME = CustomKeyboardView.CODE_SWITCH_IME

        private val VISUAL_KEYS = listOf(
            "selected_theme", "selected_font", "keyboard_height", "show_", "key_popup",
            "long_press_symbols", "custom_", "photo_version", "one_handed_mode",
        )
    }

    override fun onCreate() {
        super.onCreate()
        KeyboardThemes.migrate(this)
        prefs = KeyboardThemes.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        clipboardStore = ClipboardStore(this)
        offlineEngine = NagameseOfflineEngine(this)
        lastLearnedReset = prefs.getLong("learned_reset_at", 0L)
        // Load the word tree and n-gram tables off the main thread; typing works meanwhile.
        serviceScope.launch(Dispatchers.Default) { offlineEngine.warmUp() }
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_view, null)
        root = view
        stripContainer = view.findViewById(R.id.strip_container)
        suggestionStrip = view.findViewById(R.id.suggestion_strip)
        toolbarStrip = view.findViewById(R.id.toolbar_strip)
        translateStrip = view.findViewById(R.id.translate_strip)
        suggestionViews = listOf(R.id.suggestion_1, R.id.suggestion_2, R.id.suggestion_3).map { view.findViewById(it) }
        suggestionDividers = listOf(R.id.suggestion_divider_1, R.id.suggestion_divider_2).map { view.findViewById(it) }
        oneHandLeft = view.findViewById(R.id.one_hand_left)
        oneHandRight = view.findViewById(R.id.one_hand_right)
        translateDirection = prefs.getString("translate_direction", "en-nag") ?: "en-nag"

        for (tv in suggestionViews) {
            tv.setOnClickListener {
                tap()
                val word = (it as TextView).text?.toString()?.trim().orEmpty()
                if (word.isNotEmpty()) acceptSuggestion(word)
            }
        }

        view.findViewById<View>(R.id.btn_toolbar_toggle).setOnClickListener { tap(); showToolbar(true) }
        view.findViewById<View>(R.id.btn_toolbar_back).setOnClickListener { tap(); showToolbar(false) }
        view.findViewById<View>(R.id.tool_emoji).setOnClickListener { tap(); keyboardView?.showEmojiShelf() }
        view.findViewById<View>(R.id.tool_clipboard).setOnClickListener { tap(); keyboardView?.showClipboard() }
        view.findViewById<View>(R.id.tool_translate).setOnClickListener { tap(); openTranslate() }
        view.findViewById<View>(R.id.tool_themes).setOnClickListener { tap(); openSettings(SettingsActivity.EXTRA_OPEN_THEMES) }
        view.findViewById<View>(R.id.tool_one_hand).setOnClickListener {
            tap()
            setOneHandedMode(if (oneHandedMode() == "off") "right" else "off")
        }
        view.findViewById<View>(R.id.tool_settings).setOnClickListener { tap(); openSettings(null) }

        for (id in listOf(R.id.one_hand_left_move, R.id.one_hand_right_move)) {
            view.findViewById<View>(id).setOnClickListener { tap(); setOneHandedMode(if (oneHandedMode() == "left") "right" else "left") }
        }
        for (id in listOf(R.id.one_hand_left_exit, R.id.one_hand_right_exit)) {
            view.findViewById<View>(id).setOnClickListener { tap(); setOneHandedMode("off") }
        }

        view.findViewById<View>(R.id.translate_seg_en_nag).setOnClickListener { tap(); setDirection("en-nag") }
        view.findViewById<View>(R.id.translate_seg_nag_en).setOnClickListener { tap(); setDirection("nag-en") }
        view.findViewById<View>(R.id.translate_close).setOnClickListener { tap(); closeTranslate() }
        view.findViewById<View>(R.id.translate_replace).setOnClickListener { tap(); insertTranslation() }
        view.findViewById<View>(R.id.translate_card).setOnClickListener { tap(); insertTranslation() }
        view.findViewById<View>(R.id.translate_undo).setOnClickListener { tap(); undoInsert() }
        view.findViewById<View>(R.id.translate_copy).setOnClickListener { tap(); copyTranslation() }
        view.findViewById<View>(R.id.translate_online).setOnClickListener { tap(); pendingSource?.let { requestOnline(it, translateGeneration) } }

        keyboardView = view.findViewById<CustomKeyboardView>(R.id.keyboard_view).also {
            it.popupLayer = view.findViewById(R.id.popup_layer)
            it.listener = object : CustomKeyboardView.OnKeyActionListener {
                override fun onKey(code: Int) = handleCustomKey(code)
                override fun onText(text: String) = handleText(text)
                override fun onAlternate(text: String) = handleAlternate(text)
            }
            it.onPanelChanged = { state -> onPanelChanged(state) }
        }

        applyTheme()
        registerClipboardListener()
        return view
    }

    private fun tap() {
        keyboardView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun openSettings(extra: String?) {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (extra != null) putExtra(extra, true)
        })
    }

    // ------------------------------------------------------------------ theme

    private lateinit var fonts: KeyboardThemes.Fonts

    /** Colours every strip from the current [KeyboardTheme]; the key grid themes itself. */
    private fun applyTheme() {
        val view = root ?: return
        keyboardView?.reload()
        val theme = keyboardView?.currentTheme() ?: KeyboardThemes.current(this)
        fonts = KeyboardThemes.Fonts(this, KeyboardThemes.usesFigtree(prefs))
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()

        view.background = KeyboardThemes.backgroundDrawable(this, theme)
        for (strip in listOf(suggestionStrip, toolbarStrip, translateStrip)) strip?.setBackgroundColor(theme.strip)

        suggestionViews.forEachIndexed { i, tv ->
            tv.setTextColor(theme.stripLabel)
            tv.typeface = if (i == 1) fonts.semibold else fonts.regular
            tv.background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(theme.keyRadiusDp).toFloat())
        }
        suggestionDividers.forEach { it.setBackgroundColor(theme.keyBorder) }
        view.findViewById<ImageView>(R.id.btn_toolbar_toggle).apply {
            setColorFilter(theme.stripLabel)
            background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(theme.keyRadiusDp).toFloat())
        }
        view.findViewById<ImageView>(R.id.btn_toolbar_back).apply {
            setColorFilter(theme.stripLabel)
            background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(theme.keyRadiusDp).toFloat())
        }
        for (id in listOf(R.id.tool_emoji, R.id.tool_clipboard, R.id.tool_translate, R.id.tool_themes, R.id.tool_one_hand, R.id.tool_settings)) {
            view.findViewById<View>(id).background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(theme.keyRadiusDp).toFloat())
        }
        for (id in listOf(R.id.text_tool_emoji, R.id.text_tool_clipboard, R.id.text_tool_translate, R.id.text_tool_themes, R.id.text_tool_one_hand, R.id.text_tool_settings)) {
            view.findViewById<TextView>(id).typeface = fonts.medium
        }
        renderToolStates(theme)

        // Translate strip, built from keyboard tokens so it follows every theme (flat, no shadows).
        view.findViewById<View>(R.id.translate_segments).background = rounded(theme.keyFunction, dp(6f).toFloat())
        view.findViewById<ImageView>(R.id.translate_close).apply {
            setColorFilter(theme.keyLabel)
            background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(20f).toFloat())
        }
        view.findViewById<View>(R.id.translate_card).background = GradientDrawable().apply {
            cornerRadius = dp(theme.keyRadiusDp).toFloat()
            setColor(theme.key)
            if (theme.keyBorderWidthDp > 0) setStroke(dp(theme.keyBorderWidthDp), theme.keyBorder)
        }
        view.findViewById<TextView>(R.id.translate_status).apply { setTextColor(theme.keyLabelSecondary); typeface = fonts.medium }
        for (id in listOf(R.id.translate_bar_1, R.id.translate_bar_2)) view.findViewById<View>(id).background = rounded(theme.keyPressed, dp(4f).toFloat())
        view.findViewById<TextView>(R.id.translate_result).apply { setTextColor(theme.keyLabel); typeface = fonts.medium }
        view.findViewById<TextView>(R.id.translate_source_text).apply { setTextColor(theme.keyLabelSecondary); typeface = fonts.medium }
        view.findViewById<ImageView>(R.id.translate_source_icon).setColorFilter(theme.keyLabelSecondary)
        for ((button, icon, label) in listOf(
            Triple(R.id.translate_undo, R.id.translate_undo_icon, R.id.translate_undo_text),
            Triple(R.id.translate_copy, R.id.translate_copy_icon, R.id.translate_copy_text),
            Triple(R.id.translate_online, R.id.translate_online_icon, R.id.translate_online_text),
        )) {
            view.findViewById<View>(button).background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(10f).toFloat())
            view.findViewById<ImageView>(icon).setColorFilter(theme.keyLabel)
            view.findViewById<TextView>(label).apply { setTextColor(theme.keyLabel); typeface = fonts.medium }
        }
        view.findViewById<TextView>(R.id.translate_replace).apply {
            setTextColor(theme.onAccent)
            typeface = fonts.medium
            background = pressable(theme.accent, blendColor(theme.accent, theme.onAccent, 0.18f), dp(10f).toFloat())
        }
        renderDirection()

        for (id in listOf(R.id.one_hand_left_move, R.id.one_hand_left_exit, R.id.one_hand_right_move, R.id.one_hand_right_exit)) {
            view.findViewById<ImageView>(id).apply {
                setColorFilter(theme.keyLabelSecondary)
                background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(24f).toFloat())
            }
        }
        applyOneHandedMode()
        visualsDirty = false
    }

    /** Tool icons and labels in the secondary colour; a tool that is on (one-handed) in the accent. */
    private fun renderToolStates(theme: KeyboardTheme = keyboardView?.currentTheme() ?: KeyboardThemes.current(this)) {
        val view = root ?: return
        val active = mapOf(R.id.icon_tool_one_hand to (oneHandedMode() != "off"))
        val pairs = listOf(
            R.id.icon_tool_emoji to R.id.text_tool_emoji,
            R.id.icon_tool_clipboard to R.id.text_tool_clipboard,
            R.id.icon_tool_translate to R.id.text_tool_translate,
            R.id.icon_tool_themes to R.id.text_tool_themes,
            R.id.icon_tool_one_hand to R.id.text_tool_one_hand,
            R.id.icon_tool_settings to R.id.text_tool_settings,
        )
        for ((icon, label) in pairs) {
            val color = if (active[icon] == true) theme.accent else theme.keyLabelSecondary
            view.findViewById<ImageView>(icon).setColorFilter(color)
            view.findViewById<TextView>(label).setTextColor(color)
        }
        view.findViewById<View>(R.id.tool_one_hand).contentDescription =
            if (oneHandedMode() != "off") "One-handed mode, on" else "One-handed mode"
    }

    private fun rounded(fill: Int, radius: Float): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
    }

    private fun pressable(fill: Int, pressed: Int, radius: Float): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius))
        addState(intArrayOf(), rounded(fill, radius))
    }

    private fun blendColor(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )

    // ------------------------------------------------------------------ strip states

    private fun showToolbar(show: Boolean) {
        if (translateMode) return
        toolbarStrip?.visibility = if (show) View.VISIBLE else View.GONE
        suggestionStrip?.visibility = if (show) View.GONE else View.VISIBLE
        if (!show && keyboardView?.getViewState() != CustomKeyboardView.ViewState.KEYS) {
            keyboardView?.switchMode(CustomKeyboardView.Mode.QWERTY)
        }
    }

    /** Clipboard and emoji panels replace the strip and the keys; the strip returns with the keys. */
    private fun onPanelChanged(state: CustomKeyboardView.ViewState) {
        val keys = state == CustomKeyboardView.ViewState.KEYS
        stripContainer?.visibility = if (keys && suggestionsEnabledForField) View.VISIBLE else View.GONE
        if (keys && !translateMode) {
            toolbarStrip?.visibility = View.GONE
            suggestionStrip?.visibility = View.VISIBLE
        }
        applyOneHandedMode()
    }

    private fun oneHandedMode(): String = prefs.getString("one_handed_mode", "off") ?: "off"

    private fun setOneHandedMode(mode: String) {
        prefs.edit().putString("one_handed_mode", mode).apply()
        applyOneHandedMode()
        renderToolStates()
    }

    /** One-handed: the keys shrink towards one side and the controls take the other. */
    private fun applyOneHandedMode() {
        val keysShowing = keyboardView?.getViewState() == CustomKeyboardView.ViewState.KEYS
        val mode = if (keysShowing) oneHandedMode() else "off"
        oneHandLeft?.visibility = if (mode == "right") View.VISIBLE else View.GONE
        oneHandRight?.visibility = if (mode == "left") View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ clipboard

    private fun registerClipboardListener() {
        if (isClipListenerRegistered) return
        try {
            if (clipboardManager == null) clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
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

    /** Keeps text copied while the keyboard is open, unless the person turned clipboard history off. */
    private fun captureCurrentClip() {
        if (!clipboardStore.enabled || !learningAllowed) return
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                clipboardStore.add(text)
                if (keyboardView?.getViewState() == CustomKeyboardView.ViewState.CLIPBOARD) keyboardView?.showClipboard()
            }
        } catch (e: Exception) {
            Log.e("LikhibiIME", "Failed to capture clipboard text: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ input lifecycle

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)

        val learnedReset = prefs.getLong("learned_reset_at", 0L)
        if (learnedReset > lastLearnedReset) {
            offlineEngine.clearUserData()
            lastLearnedReset = learnedReset
        }

        val imeOptions = attribute?.imeOptions ?: 0
        val inputType = attribute?.inputType ?: 0
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val enterAction = if (noEnterAction || isMultiLine) CustomKeyboardView.EnterAction.NEWLINE else when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> CustomKeyboardView.EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> CustomKeyboardView.EnterAction.SEND
            EditorInfo.IME_ACTION_GO -> CustomKeyboardView.EnterAction.GO
            EditorInfo.IME_ACTION_NEXT -> CustomKeyboardView.EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> CustomKeyboardView.EnterAction.DONE
            else -> CustomKeyboardView.EnterAction.NEWLINE
        }

        currentComposing = StringBuilder()
        suggestionsEnabledForField = attribute?.let { !isPasswordField(it) } ?: true
        val incognito = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        learningAllowed = suggestionsEnabledForField && !incognito

        if (translateMode) closeTranslate()
        if (visualsDirty) applyTheme()
        keyboardView?.enterAction = enterAction
        // Nothing typed in a password or incognito field is translated or sent anywhere.
        root?.findViewById<View>(R.id.tool_translate)?.visibility = if (learningAllowed) View.VISIBLE else View.GONE
        keyboardView?.resetState(CustomKeyboardView.Mode.QWERTY, false)
        onPanelChanged(CustomKeyboardView.ViewState.KEYS)
        updateSuggestionsForCurrentState()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (visualsDirty) applyTheme()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentComposing = StringBuilder()
        updateSuggestionBar(offlineEngine.getPopularWords())
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        translateJob?.cancel()
        student?.close()
        translateDispatcher.close()
        unregisterClipboardListener()
        serviceScope.coroutineContext[Job]?.cancel()
    }

    // ------------------------------------------------------------------ keys

    private fun handleCustomKey(code: Int) {
        val ic = currentInputConnection ?: return
        when (code) {
            KEYCODE_DELETE -> handleBackspace(ic)
            KEYCODE_SHIFT -> handleShift()
            KEYCODE_SYMBOL_SWITCH -> handleSymbolSwitch()
            KEYCODE_SYMBOL_EXTRA -> keyboardView?.switchMode(CustomKeyboardView.Mode.EXTRA_SYMBOLS)
            KEYCODE_SYMBOL_BACK -> keyboardView?.switchMode(CustomKeyboardView.Mode.SYMBOLS)
            KEYCODE_EMOJI_SWITCH -> keyboardView?.showEmojiShelf()
            KEYCODE_SWITCH_IME -> (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
            10 -> handleEnter(ic)
            32 -> handleSpace(ic)
            CustomKeyboardView.CODE_CURSOR_RIGHT -> ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            CustomKeyboardView.CODE_CURSOR_LEFT -> ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
            else -> handleCharacter(ic, code)
        }
    }

    private var lastShiftTime = 0L

    private fun handleShift() {
        val kv = keyboardView ?: return
        val now = System.currentTimeMillis()
        if (now - lastShiftTime < 300 && !kv.isCapsLock) {
            kv.isCapsLock = true
            kv.setShifted(true)
        } else {
            val turnOn = !kv.isShifted() && !kv.isCapsLock
            kv.isCapsLock = false
            kv.setShifted(turnOn)
        }
        lastShiftTime = now
    }

    private fun handleText(text: String) {
        val ic = currentInputConnection ?: return
        val composing = currentComposing.toString().trim()
        if (composing.isNotEmpty()) {
            learnWord(composing)
            ic.commitText(currentComposing.toString(), 1)
            ic.finishComposingText()
            currentComposing = StringBuilder()
        }
        ic.commitText(text, 1)
        val last2 = getLastWords(2)
        if (last2.size == 2) learnBigram(last2[0], last2[1])
        updateSuggestionsForCurrentState()
    }

    private fun handleCharacter(ic: InputConnection, primaryCode: Int) {
        var ch = primaryCode.toChar().toString()
        if (keyboardView?.isShifted() == true) ch = ch.uppercase()
        currentComposing.append(ch)
        ic.setComposingText(currentComposing, 1)
        updateSuggestionsForCurrentState()
        val kv = keyboardView ?: return
        if (kv.isShifted() && !kv.isCapsLock) kv.setShifted(false)
    }

    /** A long-press alternate (é, 3, @) replaces the character the key typed on touch. */
    private fun handleAlternate(text: String) {
        val ic = currentInputConnection ?: return
        if (currentComposing.isNotEmpty()) {
            currentComposing.deleteCharAt(currentComposing.length - 1)
            currentComposing.append(text)
            ic.setComposingText(currentComposing, 1)
        } else {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(text, 1)
        }
        updateSuggestionsForCurrentState()
    }

    private fun handleBackspace(ic: InputConnection) {
        if (currentComposing.isNotEmpty()) {
            currentComposing.deleteCharAt(currentComposing.length - 1)
            if (currentComposing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(currentComposing, 1)
        } else {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
        }
        updateSuggestionsForCurrentState()
    }

    private fun commitComposing(ic: InputConnection) {
        val composing = currentComposing.toString().trim()
        if (composing.isNotEmpty()) {
            learnWord(composing)
            ic.commitText(currentComposing.toString(), 1)
            ic.finishComposingText()
            currentComposing = StringBuilder()
        }
    }

    private fun handleSpace(ic: InputConnection) {
        commitComposing(ic)
        ic.commitText(" ", 1)
        val last2 = getLastWords(2)
        if (last2.size == 2) learnBigram(last2[0], last2[1])
        updateSuggestionsForCurrentState()
    }

    private fun handleEnter(ic: InputConnection) {
        commitComposing(ic)
        val info = currentInputEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val inputType = info?.inputType ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!noEnterAction && !isMultiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else if (!ic.commitText("\n", 1)) {
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }
        val last2 = getLastWords(2)
        if (last2.size == 2) learnBigram(last2[0], last2[1])
        updateSuggestionsForCurrentState()
    }

    private fun handleSymbolSwitch() {
        val kv = keyboardView ?: return
        kv.switchMode(if (kv.getMode() != CustomKeyboardView.Mode.QWERTY) CustomKeyboardView.Mode.QWERTY else CustomKeyboardView.Mode.SYMBOLS)
    }

    // ------------------------------------------------------------------ suggestions

    private fun acceptSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        if (currentComposing.toString().trim().isNotEmpty()) learnWord(currentComposing.toString().trim())
        ic.commitText(word, 1)
        ic.finishComposingText()
        ic.commitText(" ", 1)
        currentComposing = StringBuilder()
        learnWord(word)
        val last2 = getLastWords(2)
        if (last2.size == 2) learnBigram(last2[0], last2[1])
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
            // Typing returns the strip from the toolbar to the suggestions.
            if (toolbarStrip?.visibility == View.VISIBLE) showToolbar(false)
            // Completions use the words already typed, so "moi bazar te j" ranks "jabo" first.
            val matches = offlineEngine.getPrefixMatches(predictionContext(includeComposing = false), composing)
            updateSuggestionBar(if (matches.isNotEmpty()) styled(matches) else listOf(composing))
        } else if (!prefs.getBoolean("next_word_suggestions", true)) {
            updateSuggestionBar(emptyList())
        } else {
            val context = predictionContext(includeComposing = true)
            updateSuggestionBar(styled(if (context.isEmpty()) offlineEngine.getPopularWords() else offlineEngine.getLocalNextWordPredictions(context)))
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

    private fun getLastWords(maxWords: Int): List<String> {
        val ic = currentInputConnection ?: return emptyList()
        val before = ic.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        val combined = (before + currentComposing.toString()).trim()
        if (combined.isEmpty()) return emptyList()
        return combined.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.takeLast(maxWords)
    }

    private fun updateSuggestionBar(words: List<String>) {
        if (!suggestionsEnabledForField) return
        val padded = (words + listOf("", "", "")).take(3)
        // The likeliest suggestion sits in the middle, in the bolder weight.
        val ordered = listOf(padded[1], padded[0], padded[2])
        for (i in 0..2) {
            val tv = suggestionViews.getOrNull(i) ?: continue
            tv.text = ordered[i]
            tv.contentDescription = if (ordered[i].isEmpty()) null else "Suggestion: ${ordered[i]}"
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

    // ------------------------------------------------------------------ translate strip

    private enum class Preview { HINT, PROGRESS, RESULT, MESSAGE }

    private fun openTranslate() {
        translateMode = true
        undoState = null
        previewText = ""
        shownText = ""
        toolbarStrip?.visibility = View.GONE
        suggestionStrip?.visibility = View.GONE
        translateStrip?.visibility = View.VISIBLE
        keyboardView?.numberRowSuppressed = true
        if (keyboardView?.getViewState() != CustomKeyboardView.ViewState.KEYS) keyboardView?.switchMode(CustomKeyboardView.Mode.QWERTY)
        renderDirection()
        scheduleTranslation(immediate = true)
    }

    private fun closeTranslate() {
        translateMode = false
        translateGeneration++
        translateJob?.cancel()
        translateStrip?.visibility = View.GONE
        suggestionStrip?.visibility = View.VISIBLE
        keyboardView?.numberRowSuppressed = false
        updateSuggestionsForCurrentState()
    }

    private fun setDirection(direction: String) {
        if (direction == translateDirection) return
        translateDirection = direction
        prefs.edit().putString("translate_direction", direction).apply()
        renderDirection()
        undoState = null
        scheduleTranslation(immediate = true)
    }

    /** The two-segment direction control: the chosen direction is a raised segment. */
    private fun renderDirection() {
        val view = root ?: return
        val theme = keyboardView?.currentTheme() ?: return
        val d = resources.displayMetrics.density
        for ((id, value) in listOf(R.id.translate_seg_en_nag to "en-nag", R.id.translate_seg_nag_en to "nag-en")) {
            val selected = value == translateDirection
            view.findViewById<TextView>(id).apply {
                setTextColor(if (selected) theme.keyLabel else theme.keyLabelSecondary)
                typeface = if (selected) fonts.semibold else fonts.regular
                background = if (selected) rounded(theme.key, 4 * d) else null
                isSelected = selected
                contentDescription = (if (value == "en-nag") "English to Nagamese" else "Nagamese to English") + if (selected) ", selected" else ""
            }
        }
    }

    private fun showPreview(state: Preview, message: String = "", online: Boolean = false) {
        val view = root ?: return
        val status = view.findViewById<TextView>(R.id.translate_status)
        val result = view.findViewById<TextView>(R.id.translate_result)
        val source = view.findViewById<View>(R.id.translate_source)
        val bars = listOf(view.findViewById<View>(R.id.translate_bar_1), view.findViewById<View>(R.id.translate_bar_2))
        status.visibility = if (state == Preview.RESULT) View.GONE else View.VISIBLE
        bars.forEach { it.visibility = if (state == Preview.PROGRESS) View.VISIBLE else View.GONE }
        result.visibility = if (state == Preview.RESULT) View.VISIBLE else View.GONE
        source.visibility = if (state == Preview.RESULT) View.VISIBLE else View.GONE
        when (state) {
            Preview.PROGRESS -> status.text = "Translating…"
            Preview.RESULT -> {
                result.text = message
                shownText = message
                view.findViewById<TextView>(R.id.translate_source_text).text = if (online) "Online · server" else "Offline · Likhibi"
                view.findViewById<ImageView>(R.id.translate_source_icon).setImageResource(if (online) R.drawable.ic_kb_wifi else R.drawable.ic_kb_wifi_off)
            }
            else -> status.text = message
        }
        updateActions()
    }

    private fun serverUrl(): String = prefs.getString("translate_server_url", "")?.trim().orEmpty()

    private fun updateActions() {
        val view = root ?: return
        fun enable(id: Int, on: Boolean) = view.findViewById<View>(id).apply {
            isEnabled = on
            alpha = if (on) 1f else 0.4f
        }
        enable(R.id.translate_undo, undoState != null)
        enable(R.id.translate_copy, shownText.isNotBlank())
        enable(R.id.translate_replace, previewText.isNotBlank())
        view.findViewById<View>(R.id.translate_online).visibility = if (serverUrl().isNotEmpty() && undoState == null) View.VISIBLE else View.GONE
    }

    private fun hintLabel(): String =
        if (translateDirection == "en-nag") "Type in English. The Nagamese appears here, translated on this phone."
        else "Type in Nagamese. The English appears here, translated on this phone."

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
                shownText = ""
                showPreview(Preview.HINT, hintLabel())
                return@launch
            }
            val target = translateDirection.substringAfter("-")
            showPreview(Preview.PROGRESS)
            val result = withContext(translateDispatcher) {
                runCatching { loadStudent()?.translate(source.text, target) }.getOrNull()
            }
            if (generation != translateGeneration) return@launch
            if (result != null && result.text.isNotBlank()) {
                previewText = result.text
                showPreview(Preview.RESULT, result.text)
            } else {
                previewText = ""
                shownText = ""
                val problem = when (studentState) {
                    "missing" -> "The offline translator is not installed in this build"
                    "failed" -> "The offline translator could not start on this phone"
                    else -> "No translation for this text"
                }
                showPreview(Preview.MESSAGE, problem + if (serverUrl().isNotEmpty()) ". Tap Online to ask the server." else ".")
            }
            if (prefs.getBoolean("translate_auto_online", false) && serverUrl().isNotEmpty()) requestOnline(source, generation)
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
        showPreview(Preview.PROGRESS)
        serviceScope.launch {
            val (from, to) = translateDirection.split("-")
            val result = withContext(Dispatchers.IO) { runCatching { RemoteTranslator(server).translate(source.text, from, to) } }
            if (generation != translateGeneration) return@launch
            result.onSuccess {
                previewText = it.text
                showPreview(Preview.RESULT, it.text, online = true)
            }.onFailure {
                showPreview(Preview.MESSAGE, "Online translation failed: ${it.message ?: "no connection"}")
            }
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

    /** Replace: puts the translation in place of the sentence (or selection) it came from. */
    private fun insertTranslation() {
        val ic = currentInputConnection ?: return
        val source = pendingSource ?: return
        val translation = previewText.takeIf { it.isNotBlank() } ?: return
        translateGeneration++
        translateJob?.cancel()
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
        updateActions()
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

    private fun copyTranslation() {
        val text = shownText.takeIf { it.isNotBlank() } ?: return
        clipboardManager?.setPrimaryClip(ClipData.newPlainText("Likhibi translation", text))
    }
}
