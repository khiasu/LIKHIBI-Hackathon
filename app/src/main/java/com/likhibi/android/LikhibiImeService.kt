package com.likhibi.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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
import kotlin.math.abs
import kotlin.math.roundToInt

class LikhibiImeService : InputMethodService(), KeyboardView.Listener, KeyboardPanels.Host {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var engine: NagameseOfflineEngine
    private lateinit var clipboardStore: ClipboardStore

    override lateinit var settings: KeyboardSettings
    override var overlay: KeyPopupOverlay? = null
    override var feedback: KeyFeedback? = null
    private lateinit var fonts: KeyboardThemes.Fonts
    private lateinit var panels: KeyboardPanels
    private var openPanel: KeyboardPanels.Kind? = null

    // Views
    private var root: View? = null
    private var mainColumn: View? = null
    private var stripContainer: View? = null
    private var suggestionStrip: View? = null
    private var toolbarStrip: View? = null
    private var translateStrip: View? = null
    private var keysFrame: View? = null
    private var panelContainer: FrameLayout? = null
    private var keyboard: KeyboardView? = null
    private var suggestionViews: List<TextView> = emptyList()
    private var suggestionDividers: List<View> = emptyList()

    // Settings changed elsewhere are applied the next time the keyboard opens.
    @Volatile private var settingsDirty = false
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key !in QUIET_KEYS) settingsDirty = true
    }

    // ------------------------------------------------------------------ text the keyboard knows about
    //
    // Reading text back from the app is a blocking call, so the keyboard keeps its own copy of
    // the text before the cursor and updates it with every edit it makes. The app reports every
    // cursor change; one the keyboard did not cause (a tap elsewhere, the app clearing the
    // field) makes the copy stale, and it is read again once, when next needed.

    private val composing = StringBuilder()
    private val before = StringBuilder()
    private var beforeValid = false
    private var beforeTruncated = false
    private var cursor = -1
    private var selStart = -1
    private var selEnd = -1
    private val pendingCursor = ArrayDeque<Int>()
    private var quietUntil = 0L

    private var suggestionsEnabledForField = true
    // False in password and incognito fields: nothing typed there is remembered.
    private var learningAllowed = true
    private var capsMode = 0
    private var autoSpace = false
    private var lastSpaceAt = 0L
    private var autoShifted = false
    private var lastShiftTap = 0L
    private var lastLearnedReset = 0L

    // Suggestions are worked out on their own thread; only the newest request is shown.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "likhibi-predict").apply { isDaemon = true } }
    private var suggestionGeneration = 0
    private var suggestionsPosted = false
    private val suggestionRunnable = Runnable {
        suggestionsPosted = false
        refreshSuggestions()
    }

    // Translate strip
    private var translateMode = false
    private var translateDirection = "en-nag"
    private var translateJob: Job? = null
    private var translateGeneration = 0
    private var pendingSource: TranslationSource? = null
    private var previewText = ""
    private var shownText = ""
    private var undoState: UndoState? = null
    private data class TranslationSource(val text: String, val deleteCount: Int, val isSelection: Boolean)
    private data class UndoState(val original: String, val inserted: String)
    private val translateDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Volatile private var student: StudentTranslator? = null
    @Volatile private var studentState = "unloaded"

    private var clipboardManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureCurrentClip() }
    private var isClipListenerRegistered = false

    companion object {
        private val QUIET_KEYS = setOf("clipboard_items", "emoji_recent", "translate_direction", "learned_reset_at")
        private const val DOUBLE_SPACE_MS = 700L
    }

    override fun onCreate() {
        super.onCreate()
        KeyboardThemes.migrate(this)
        prefs = KeyboardThemes.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        clipboardStore = ClipboardStore(this)
        engine = NagameseOfflineEngine(this)
        feedback = KeyFeedback(this)
        settings = KeyboardSettings.load(this)
        panels = KeyboardPanels(this, this)
        lastLearnedReset = prefs.getLong("learned_reset_at", 0L)
        background { engine.warmUp() }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.ime_view, null)
        root = view
        mainColumn = view.findViewById(R.id.main_column)
        stripContainer = view.findViewById(R.id.strip_container)
        suggestionStrip = view.findViewById(R.id.suggestion_strip)
        toolbarStrip = view.findViewById(R.id.toolbar_strip)
        translateStrip = view.findViewById(R.id.translate_strip)
        keysFrame = view.findViewById(R.id.keys_frame)
        suggestionViews = listOf(R.id.suggestion_1, R.id.suggestion_2, R.id.suggestion_3).map { view.findViewById(it) }
        suggestionDividers = listOf(R.id.suggestion_divider_1, R.id.suggestion_divider_2).map { view.findViewById(it) }
        translateDirection = prefs.getString("translate_direction", "en-nag") ?: "en-nag"

        val popups = KeyPopupOverlay(this)
        overlay = popups
        keyboard = KeyboardView(this).also {
            it.listener = this
            it.overlay = popups
            it.feedback = feedback
            (view.findViewById<FrameLayout>(R.id.keys_frame)).addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panelContainer = FrameLayout(this).apply { visibility = View.GONE }
        (mainColumn as LinearLayout).addView(panelContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        (view as FrameLayout).addView(popups, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        for (tv in suggestionViews) tv.setOnClickListener {
            tap()
            val word = (it as TextView).text?.toString()?.trim().orEmpty()
            if (word.isNotEmpty()) acceptSuggestion(word)
        }
        view.findViewById<View>(R.id.btn_toolbar_toggle).setOnClickListener { tap(); showToolbar(true) }
        view.findViewById<View>(R.id.btn_toolbar_back).setOnClickListener { tap(); showToolbar(false) }
        view.findViewById<View>(R.id.tool_emoji).setOnClickListener { tap(); showPanel(KeyboardPanels.Kind.EMOJI) }
        view.findViewById<View>(R.id.tool_clipboard).setOnClickListener { tap(); showPanel(KeyboardPanels.Kind.CLIPBOARD) }
        view.findViewById<View>(R.id.tool_translate).setOnClickListener { tap(); openTranslate() }
        view.findViewById<View>(R.id.tool_themes).setOnClickListener { tap(); showPanel(KeyboardPanels.Kind.THEMES) }
        view.findViewById<View>(R.id.tool_one_hand).setOnClickListener { tap(); toggleOneHanded() }
        view.findViewById<View>(R.id.tool_settings).setOnClickListener { tap(); showPanel(KeyboardPanels.Kind.SETTINGS) }
        for (id in listOf(R.id.one_hand_left_exit, R.id.one_hand_right_exit)) view.findViewById<View>(id).setOnClickListener { tap(); setOneHanded("off") }
        view.findViewById<View>(R.id.one_hand_left_move).setOnClickListener { tap(); setOneHanded("left") }
        view.findViewById<View>(R.id.one_hand_right_move).setOnClickListener { tap(); setOneHanded("right") }
        for (id in listOf(R.id.tool_emoji, R.id.tool_clipboard, R.id.tool_translate, R.id.tool_themes, R.id.tool_one_hand, R.id.tool_settings, R.id.btn_toolbar_toggle, R.id.btn_toolbar_back)) {
            view.findViewById<View>(id).let { it.tooltipText = it.contentDescription }
        }

        view.findViewById<View>(R.id.translate_seg_en_nag).setOnClickListener { tap(); setDirection("en-nag") }
        view.findViewById<View>(R.id.translate_seg_nag_en).setOnClickListener { tap(); setDirection("nag-en") }
        view.findViewById<View>(R.id.translate_close).setOnClickListener { tap(); closeTranslate() }
        view.findViewById<View>(R.id.translate_replace).setOnClickListener { tap(); insertTranslation() }
        view.findViewById<View>(R.id.translate_card).setOnClickListener { tap(); insertTranslation() }
        view.findViewById<View>(R.id.translate_undo).setOnClickListener { tap(); undoInsert() }
        view.findViewById<View>(R.id.translate_copy).setOnClickListener { tap(); copyTranslation() }
        view.findViewById<View>(R.id.translate_online).setOnClickListener { tap(); pendingSource?.let { requestOnline(it, translateGeneration) } }

        applySettings()
        registerClipboardListener()
        return view
    }

    private fun tap() = feedback?.press(KeyFeedback.Kind.FUNCTION, settings)

    // ------------------------------------------------------------------ settings and theme

    /** Reloads every setting and redraws the keyboard, strips and any open panel. */
    private fun applySettings() {
        settings = KeyboardSettings.load(this)
        settingsDirty = false
        val view = root ?: return
        val theme = settings.theme
        fonts = KeyboardThemes.Fonts(this, settings.figtree)
        keyboard?.configure(settings)
        view.background = KeyboardThemes.backgroundDrawable(this, theme)
        for (strip in listOf(suggestionStrip, toolbarStrip, translateStrip)) strip?.setBackgroundColor(theme.strip)

        suggestionViews.forEachIndexed { i, tv ->
            tv.setTextColor(theme.stripLabel)
            tv.typeface = if (i == 1) fonts.semibold else fonts.regular
            tv.background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(8f))
        }
        val divider = Color.argb(60, Color.red(theme.stripLabel), Color.green(theme.stripLabel), Color.blue(theme.stripLabel))
        suggestionDividers.forEach { it.setBackgroundColor(divider) }
        // One-handed rail: raised, key-coloured buttons that read as controls, not stray icons.
        val railFill = if (Color.alpha(theme.keyFunction) >= 80) theme.keyFunction else theme.key
        for (id in listOf(R.id.one_hand_left_exit, R.id.one_hand_left_move, R.id.one_hand_right_exit, R.id.one_hand_right_move)) {
            view.findViewById<ImageView>(id).apply {
                setColorFilter(theme.keyLabel)
                background = pressable(railFill, theme.keyPressed, dp(24f))
            }
        }
        for (id in listOf(R.id.btn_toolbar_toggle, R.id.btn_toolbar_back, R.id.tool_emoji, R.id.tool_clipboard, R.id.tool_translate, R.id.tool_themes, R.id.tool_settings)) {
            view.findViewById<ImageView>(id).apply {
                setColorFilter(theme.stripLabel)
                background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(24f))
            }
        }
        view.findViewById<ImageView>(R.id.tool_one_hand).apply {
            setColorFilter(if (settings.oneHanded != "off") theme.accent else theme.stripLabel)
            background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(24f))
            contentDescription = if (settings.oneHanded != "off") "One-handed mode, on" else "One-handed mode"
        }

        // Translate strip, built from keyboard tokens so it follows every theme.
        view.findViewById<View>(R.id.translate_segments).background = rounded(theme.keyFunction.takeIf { Color.alpha(it) > 60 } ?: theme.keyPressed, dp(8f))
        view.findViewById<ImageView>(R.id.translate_close).apply {
            setColorFilter(theme.stripLabel)
            background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(20f))
        }
        view.findViewById<View>(R.id.translate_card).background = GradientDrawable().apply {
            cornerRadius = dp(theme.keyRadiusDp + 2f)
            setColor(theme.key)
            if (theme.keyBorderWidthDp > 0) setStroke(dp(theme.keyBorderWidthDp).roundToInt(), theme.keyBorder)
        }
        view.findViewById<TextView>(R.id.translate_status).apply { setTextColor(theme.keyLabelSecondary); typeface = fonts.medium }
        for (id in listOf(R.id.translate_bar_1, R.id.translate_bar_2)) view.findViewById<View>(id).background = rounded(theme.keyPressed, dp(4f))
        view.findViewById<TextView>(R.id.translate_result).apply { setTextColor(theme.keyLabel); typeface = fonts.medium }
        view.findViewById<TextView>(R.id.translate_source_text).apply { setTextColor(theme.keyLabelSecondary); typeface = fonts.medium }
        view.findViewById<ImageView>(R.id.translate_source_icon).setColorFilter(theme.keyLabelSecondary)
        for ((button, icon, label) in listOf(
            Triple(R.id.translate_undo, R.id.translate_undo_icon, R.id.translate_undo_text),
            Triple(R.id.translate_copy, R.id.translate_copy_icon, R.id.translate_copy_text),
            Triple(R.id.translate_online, R.id.translate_online_icon, R.id.translate_online_text),
        )) {
            view.findViewById<View>(button).background = pressable(Color.TRANSPARENT, theme.keyPressed, dp(18f))
            view.findViewById<ImageView>(icon).setColorFilter(theme.stripLabel)
            view.findViewById<TextView>(label).apply { setTextColor(theme.stripLabel); typeface = fonts.medium }
        }
        view.findViewById<TextView>(R.id.translate_replace).apply {
            setTextColor(theme.onAccent)
            typeface = fonts.medium
            background = pressable(theme.accent, blend(theme.accent, theme.onAccent, 0.18f), dp(18f))
        }
        renderDirection()
        applyOneHanded()
        openPanel?.let { rebuildPanel(it) }
    }

    override fun settingsChanged() = applySettings()

    // ------------------------------------------------------------------ strips, panels, one-handed

    private fun showToolbar(show: Boolean) {
        if (translateMode) return
        toolbarStrip?.visibility = if (show) View.VISIBLE else View.GONE
        suggestionStrip?.visibility = if (show) View.GONE else View.VISIBLE
    }

    override fun panelHeight(): Int {
        val kb = keyboard ?: return dp(280f).roundToInt()
        return dp(48f).roundToInt() + kb.lettersHeight(kb.width)
    }

    override fun showPanel(kind: KeyboardPanels.Kind) {
        if (translateMode) closeTranslate()
        val container = panelContainer ?: return
        val reopening = openPanel == kind
        openPanel = kind
        overlay?.hidePreview()
        overlay?.hideAlternates()
        val scrollY = if (reopening) (container.findViewWithTag<ScrollView>(KeyboardPanels.SCROLL_TAG))?.scrollY ?: 0 else 0
        container.removeAllViews()
        container.addView(panels.build(kind), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.layoutParams = container.layoutParams.apply { height = panelHeight() }
        container.visibility = View.VISIBLE
        stripContainer?.visibility = View.GONE
        keysFrame?.visibility = View.GONE
        if (scrollY > 0) container.findViewWithTag<ScrollView>(KeyboardPanels.SCROLL_TAG)?.let { s -> s.post { s.scrollTo(0, scrollY) } }
    }

    private fun rebuildPanel(kind: KeyboardPanels.Kind) {
        if (openPanel == kind) showPanel(kind)
    }

    override fun closePanel() {
        if (openPanel == null) return
        openPanel = null
        panelContainer?.apply {
            removeAllViews()
            visibility = View.GONE
        }
        keysFrame?.visibility = View.VISIBLE
        stripContainer?.visibility = if (suggestionsEnabledForField) View.VISIBLE else View.GONE
        showToolbar(false)
        keyboard?.setMode(KeyboardView.Mode.LETTERS)
        updateAutoCap()
        scheduleSuggestions()
    }

    override fun openApp(extra: String?) {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (extra != null) putExtra(extra, true)
        })
    }

    private fun toggleOneHanded() = setOneHanded(if (settings.oneHanded == "off") "right" else "off")

    private fun setOneHanded(mode: String) {
        prefs.edit().putString("one_handed_mode", mode).apply()
        applySettings()
    }

    /** One-handed, as on Gboard: the keyboard narrows to one side; the other side holds the controls. */
    private fun applyOneHanded() {
        val mode = settings.oneHanded
        (mainColumn?.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = if (mode == "off") 100f else 80f
            mainColumn?.layoutParams = it
        }
        root?.findViewById<View>(R.id.one_hand_left)?.visibility = if (mode == "right") View.VISIBLE else View.GONE
        root?.findViewById<View>(R.id.one_hand_right)?.visibility = if (mode == "left") View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ input lifecycle

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composing.clear()
        pendingCursor.clear()
        autoSpace = false
        lastSpaceAt = 0L
        selStart = attribute?.initialSelStart ?: -1
        selEnd = attribute?.initialSelEnd ?: -1
        cursor = if (selStart >= 0 && selStart == selEnd) selEnd else -1
        val initial = if (attribute != null && Build.VERSION.SDK_INT >= 30) attribute.getInitialTextBeforeCursor(400, 0) else null
        before.setLength(0)
        if (initial != null) before.append(initial)
        beforeValid = initial != null
        beforeTruncated = (initial?.length ?: 0) >= 400

        val type = attribute?.inputType ?: 0
        suggestionsEnabledForField = attribute?.let { !isPasswordField(it) } ?: true
        val incognito = ((attribute?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        learningAllowed = suggestionsEnabledForField && !incognito
        val variation = type and InputType.TYPE_MASK_VARIATION
        val textClass = (type and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        val noCaps = !textClass || !suggestionsEnabledForField || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        capsMode = if (noCaps) 0 else type and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

        val learnedReset = prefs.getLong("learned_reset_at", 0L)
        if (learnedReset > lastLearnedReset) {
            lastLearnedReset = learnedReset
            background { engine.clearUserData() }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (settingsDirty) applySettings()
        if (translateMode) closeTranslate()
        if (openPanel != null) closePanel()
        val kb = keyboard ?: return
        val imeOptions = info?.imeOptions ?: 0
        val multiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val (icon, label) = if (noAction || multiLine) R.drawable.ic_kb_corner_down_left to "Enter" else when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_kb_search to "Search"
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_kb_send to "Send"
            EditorInfo.IME_ACTION_GO -> R.drawable.ic_kb_arrow_right to "Go"
            EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_kb_arrow_right to "Next"
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_kb_check to "Done"
            else -> R.drawable.ic_kb_corner_down_left to "Enter"
        }
        kb.setEnter(icon, label)
        val inputClass = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        val numeric = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
        kb.setMode(if (numeric) KeyboardView.Mode.SYMBOLS else KeyboardView.Mode.LETTERS)
        kb.setShift(false, false)
        autoShifted = false
        stripContainer?.visibility = if (suggestionsEnabledForField) View.VISIBLE else View.GONE
        // As on Gboard: an empty field opens with the tools; typing brings the suggestions.
        showToolbar(beforeValid && before.isEmpty())
        // Nothing typed in a password or incognito field is translated or sent anywhere.
        root?.findViewById<View>(R.id.tool_translate)?.visibility = if (learningAllowed) View.VISIBLE else View.GONE
        updateAutoCap()
        scheduleSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        overlay?.hidePreview()
        overlay?.hideAlternates()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (newSelStart == newSelEnd && pendingCursor.contains(newSelStart)) {
            while (pendingCursor.isNotEmpty() && pendingCursor.removeFirst() != newSelStart) Unit
            return
        }
        if (newSelStart == newSelEnd && pendingCursor.isEmpty() && newSelStart == cursor) return
        // The app did not say where the cursor started; the first report tells us.
        if (cursor < 0 && pendingCursor.isEmpty() && newSelStart == newSelEnd && composing.isNotEmpty()) {
            cursor = newSelEnd
            return
        }
        // The person moved the cursor, or the app changed the text: finish the word in progress
        // where it is (as Gboard does) and read the text again when it is next needed.
        pendingCursor.clear()
        cursor = if (newSelStart == newSelEnd) newSelEnd else -1
        beforeValid = false
        autoSpace = false
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing.clear()
        }
        if (SystemClock.uptimeMillis() < quietUntil) return
        if (translateMode) scheduleTranslation() else {
            updateAutoCap()
            scheduleSuggestions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        main.removeCallbacksAndMessages(null)
        translateJob?.cancel()
        student?.close()
        translateDispatcher.close()
        worker.shutdown()
        feedback?.release()
        unregisterClipboardListener()
        serviceScope.coroutineContext[Job]?.cancel()
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyText(text: String) {
        val ic = currentInputConnection ?: return
        if (toolbarStrip?.visibility == View.VISIBLE) showToolbar(false)
        if (isWordPart(text)) {
            composing.append(text)
            ic.setComposingText(composing, 1)
            before.append(text)
            moved(text.length)
        } else {
            // A word picked from the strip comes with a space; punctuation typed next takes it back.
            if (autoSpace && text.length == 1 && text[0] in ".,?!:;)" && before.endsWith(" ") && composing.isEmpty()) {
                ic.deleteSurroundingText(1, 0)
                before.setLength(before.length - 1)
                moved(-1)
            }
            commitComposing(ic)
            ic.commitText(text, 1)
            before.append(text)
            moved(text.length)
        }
        autoSpace = false
        lastSpaceAt = 0L
        val kb = keyboard
        if (kb != null && kb.isShifted() && !kb.isCapsLock()) {
            kb.setShift(false, false)
            autoShifted = false
        }
        afterEdit()
    }

    private fun isWordPart(text: String): Boolean {
        if (text.isEmpty() || Character.charCount(text.codePointAt(0)) != text.length) return false
        val c = text.codePointAt(0)
        if (Character.isLetterOrDigit(c)) return true
        return composing.isNotEmpty() && (text == "'" || text == "-")
    }

    override fun onKeyCode(code: Int) {
        val kb = keyboard ?: return
        when (code) {
            KeyboardLayouts.CODE_DELETE -> deleteBackward()
            KeyboardLayouts.CODE_SHIFT -> {
                val now = SystemClock.uptimeMillis()
                when {
                    kb.isCapsLock() -> kb.setShift(false, false)
                    kb.isShifted() -> if (now - lastShiftTap < 350) kb.setShift(true, true) else kb.setShift(false, false)
                    else -> kb.setShift(true, false)
                }
                lastShiftTap = now
                autoShifted = false
            }
            KeyboardLayouts.CODE_SYMBOLS -> {
                kb.setMode(if (kb.mode == KeyboardView.Mode.LETTERS) KeyboardView.Mode.SYMBOLS else KeyboardView.Mode.LETTERS)
                updateAutoCap()
            }
            KeyboardLayouts.CODE_EXTRA_SYMBOLS -> kb.setMode(KeyboardView.Mode.EXTRA_SYMBOLS)
            KeyboardLayouts.CODE_SYMBOLS_BACK -> kb.setMode(KeyboardView.Mode.SYMBOLS)
            KeyboardLayouts.CODE_EMOJI -> showPanel(KeyboardPanels.Kind.EMOJI)
            KeyboardLayouts.CODE_SETTINGS -> showPanel(KeyboardPanels.Kind.SETTINGS)
            KeyboardLayouts.CODE_CLIPBOARD -> showPanel(KeyboardPanels.Kind.CLIPBOARD)
            KeyboardLayouts.CODE_ONE_HANDED -> toggleOneHanded()
            KeyboardLayouts.CODE_SWITCH_IME -> (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
            KeyboardLayouts.CODE_SPACE -> space()
            KeyboardLayouts.CODE_ENTER -> enter()
        }
    }

    private fun space() {
        val ic = currentInputConnection ?: return
        commitComposing(ic)
        val now = SystemClock.uptimeMillis()
        val doubled = settings.doubleSpacePeriod && beforeValid && now - lastSpaceAt < DOUBLE_SPACE_MS &&
            before.length >= 2 && before.last() == ' ' && Character.isLetterOrDigit(before[before.length - 2])
        if (doubled) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            before.setLength(before.length - 1)
            before.append(". ")
            moved(1)
            lastSpaceAt = 0L
        } else {
            ic.commitText(" ", 1)
            before.append(' ')
            moved(1)
            lastSpaceAt = now
        }
        autoSpace = false
        afterEdit()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        commitComposing(ic)
        val info = currentInputEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val isMultiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!noEnterAction && !isMultiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
            beforeValid = false
        } else if (ic.commitText("\n", 1)) {
            before.append('\n')
            moved(1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            beforeValid = false
        }
        autoSpace = false
        lastSpaceAt = 0L
        afterEdit()
    }

    /** Deletes one character (a whole emoji or accented letter), or the selection. */
    override fun deleteBackward() {
        val ic = currentInputConnection ?: return
        autoSpace = false
        lastSpaceAt = 0L
        when {
            composing.isNotEmpty() -> {
                val n = lastGraphemeLength(composing)
                composing.setLength(composing.length - n)
                if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
                if (before.length >= n) before.setLength(before.length - n)
                moved(-n)
            }
            selStart >= 0 && selStart != selEnd -> {
                ic.commitText("", 1)
                beforeValid = false
            }
            beforeValid && before.isNotEmpty() -> {
                val n = lastGraphemeLength(before)
                ic.deleteSurroundingText(n, 0)
                before.setLength(before.length - n)
                moved(-n)
                if (before.isEmpty() && beforeTruncated) beforeValid = false
            }
            beforeValid -> Unit
            else -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                beforeValid = false
                cursor = -1
            }
        }
        afterEdit()
    }

    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        commitComposing(ic)
        if (cursor >= 0 && selStart == selEnd) {
            val target = (cursor + steps).coerceAtLeast(0)
            ic.setSelection(target, target)
            if (steps < 0 && beforeValid && before.length >= -steps) before.setLength(before.length + steps) else beforeValid = false
            noteCursor(target)
        } else {
            val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(steps)) { sendDownUpKeyEvents(key) }
            beforeValid = false
        }
        afterEdit()
    }

    /** Emoji and clipboard text from the panels. */
    override fun typeText(text: String) {
        val ic = currentInputConnection ?: return
        commitComposing(ic)
        ic.commitText(text, 1)
        before.append(text)
        moved(text.length)
        autoSpace = false
        afterEdit()
    }

    private fun commitComposing(ic: InputConnection) {
        if (composing.isEmpty()) return
        val word = composing.toString()
        val previous = previousWord(word.length)
        ic.finishComposingText()
        composing.clear()
        learn(previous, word)
    }

    private fun acceptSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val previous = previousWord(composing.length)
        ic.commitText("$word ", 1)
        if (before.length >= composing.length) before.setLength(before.length - composing.length)
        before.append(word).append(' ')
        moved(word.length + 1 - composing.length)
        composing.clear()
        learn(previous, word)
        autoSpace = true
        lastSpaceAt = SystemClock.uptimeMillis()
        val kb = keyboard
        if (kb != null && kb.isShifted() && !kb.isCapsLock()) kb.setShift(false, false)
        afterEdit()
    }

    private fun afterEdit() {
        trimBefore()
        if (translateMode) {
            scheduleTranslation()
        } else {
            updateAutoCap()
            scheduleSuggestions()
        }
    }

    // ------------------------------------------------------------------ cursor bookkeeping

    private fun moved(delta: Int) {
        if (cursor >= 0) noteCursor(cursor + delta)
    }

    private fun noteCursor(position: Int) {
        cursor = position
        pendingCursor.addLast(position)
        while (pendingCursor.size > 64) pendingCursor.removeFirst()
    }

    private fun trimBefore() {
        if (before.length > 800) {
            before.delete(0, before.length - 400)
            beforeTruncated = true
        }
    }

    private fun ensureBefore() {
        if (beforeValid) return
        val text = currentInputConnection?.getTextBeforeCursor(400, 0) ?: return
        before.setLength(0)
        before.append(text)
        beforeValid = true
        beforeTruncated = text.length >= 400
    }

    private val graphemes = android.icu.text.BreakIterator.getCharacterInstance()

    private fun lastGraphemeLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val tail = text.subSequence(maxOf(0, text.length - 32), text.length).toString()
        val it = graphemes
        it.setText(tail)
        val end = it.last()
        val start = it.previous()
        return if (start == android.icu.text.BreakIterator.DONE) 1 else (end - start).coerceAtLeast(1)
    }

    private fun previousWord(composingLength: Int): String? {
        if (!beforeValid) return null
        val text = before.substring(0, maxOf(0, before.length - composingLength))
        val words = text.trimEnd().split(Regex("\\s+"))
        val last = words.lastOrNull()?.lowercase()?.filter { it.isLetter() }
        return last?.takeIf { it.isNotEmpty() && !text.trimEnd().endsWith(".") }
    }

    private fun learn(previous: String?, word: String) {
        if (!learningAllowed || translateMode) return
        val clean = word.trim()
        if (clean.isEmpty()) return
        background {
            engine.learnWord(clean)
            if (previous != null) engine.learnBigram(previous, clean)
        }
    }

    /** As on Gboard: a capital letter at the start of a sentence, if the app's field asks for it. */
    private fun updateAutoCap() {
        val kb = keyboard ?: return
        val allowed = settings.autoCapitalise && capsMode != 0 && kb.mode == KeyboardView.Mode.LETTERS && !kb.isCapsLock() && composing.isEmpty() && openPanel == null
        if (!allowed || !beforeValid) {
            if (autoShifted && kb.isShifted() && !kb.isCapsLock() && (!allowed)) {
                kb.setShift(false, false)
                autoShifted = false
            }
            return
        }
        val text = before
        val want = when {
            (capsMode and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0 -> true
            text.isEmpty() -> true
            (capsMode and InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0 -> text.last().isWhitespace()
            else -> text.last() == '\n' || SENTENCE_END.containsMatchIn(text.takeLast(8))
        }
        if (want && !kb.isShifted()) {
            kb.setShift(true, false)
            autoShifted = true
        } else if (!want && autoShifted && kb.isShifted()) {
            kb.setShift(false, false)
            autoShifted = false
        }
    }

    private val SENTENCE_END = Regex("[.?!][\"')\\]]*\\s+$")

    // ------------------------------------------------------------------ suggestions

    /** Runs on the prediction thread; quietly does nothing once the keyboard has shut down. */
    private fun background(task: () -> Unit) {
        runCatching { worker.execute(task) }
    }

    private fun scheduleSuggestions() {
        if (suggestionsPosted) return
        suggestionsPosted = true
        main.post(suggestionRunnable)
    }

    /** Gathers what the prediction needs on this thread, works it out on the predict thread. */
    private fun refreshSuggestions() {
        if (!suggestionsEnabledForField || translateMode || openPanel != null) return
        if (!beforeValid) {
            ensureBefore()
            updateAutoCap()
        }
        val word = composing.toString()
        val text = before.toString()
        val context = predictionContext(text.substring(0, maxOf(0, text.length - word.length)))
        val capitalise = if (word.isNotEmpty()) word[0].isUpperCase() else atSentenceStart(text)
        val nextWord = settings.nextWordSuggestions
        val generation = ++suggestionGeneration
        background {
            val found = runCatching {
                when {
                    word.isNotEmpty() -> engine.getPrefixMatches(context, word).ifEmpty { listOf(word) }
                    !nextWord -> emptyList()
                    context.isEmpty() || context == listOf("<s>") -> engine.getPopularWords()
                    else -> engine.getLocalNextWordPredictions(context)
                }
            }.getOrDefault(emptyList())
            val styled = if (capitalise) found.map { w -> w.replaceFirstChar { it.uppercaseChar() } } else found
            main.post { if (generation == suggestionGeneration) showSuggestions(styled) }
        }
    }

    private fun atSentenceStart(text: String): Boolean {
        val t = text.trimEnd()
        return t.isEmpty() || t.last() in ".?!\n"
    }

    /**
     * The last few words before the cursor, lowercase and without punctuation, as the
     * prediction tables store them. A sentence end restarts the context at "<s>".
     */
    private fun predictionContext(text: String, maxWords: Int = 3): List<String> {
        val context = mutableListOf<String>()
        for (raw in text.takeLast(120).split(Regex("\\s+"))) {
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

    private fun showSuggestions(words: List<String>) {
        if (!suggestionsEnabledForField) return
        val padded = (words + listOf("", "", "")).take(3)
        // The likeliest suggestion sits in the middle, in the bolder weight.
        val ordered = listOf(padded[1], padded[0], padded[2])
        suggestionViews.forEachIndexed { i, tv ->
            if (tv.text.toString() != ordered[i]) tv.text = ordered[i]
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
        runCatching { clipboardManager?.removePrimaryClipChangedListener(clipListener) }
        isClipListenerRegistered = false
    }

    /** Keeps text copied while the keyboard is open, unless clipboard history is off. */
    private fun captureCurrentClip() {
        if (!clipboardStore.enabled || !learningAllowed) return
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                clipboardStore.add(text)
                if (openPanel == KeyboardPanels.Kind.CLIPBOARD) showPanel(KeyboardPanels.Kind.CLIPBOARD)
            }
        } catch (e: Exception) {
            Log.e("LikhibiIME", "Failed to capture clipboard text: ${e.message}")
        }
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
        keyboard?.setNumberRowSuppressed(true)
        keyboard?.setMode(KeyboardView.Mode.LETTERS)
        renderDirection()
        scheduleTranslation(immediate = true)
    }

    private fun closeTranslate() {
        translateMode = false
        translateGeneration++
        translateJob?.cancel()
        translateStrip?.visibility = View.GONE
        suggestionStrip?.visibility = View.VISIBLE
        keyboard?.setNumberRowSuppressed(false)
        updateAutoCap()
        scheduleSuggestions()
    }

    private fun setDirection(direction: String) {
        if (direction == translateDirection) return
        translateDirection = direction
        prefs.edit().putString("translate_direction", direction).apply()
        renderDirection()
        undoState = null
        scheduleTranslation(immediate = true)
    }

    private fun renderDirection() {
        val view = root ?: return
        val theme = settings.theme
        for ((id, value) in listOf(R.id.translate_seg_en_nag to "en-nag", R.id.translate_seg_nag_en to "nag-en")) {
            val selected = value == translateDirection
            view.findViewById<TextView>(id).apply {
                setTextColor(if (selected) theme.keyLabel else theme.keyLabelSecondary)
                typeface = if (selected) fonts.semibold else fonts.regular
                background = if (selected) rounded(if (Color.alpha(theme.key) >= 200) theme.key else theme.keyPressed, dp(6f)) else null
                contentDescription = (if (value == "en-nag") "English to Nagamese" else "Nagamese to English") + if (selected) ", selected" else ""
            }
        }
    }

    private fun showPreview(state: Preview, message: String = "", online: Boolean = false) {
        val view = root ?: return
        val status = view.findViewById<TextView>(R.id.translate_status)
        val result = view.findViewById<TextView>(R.id.translate_result)
        val source = view.findViewById<View>(R.id.translate_source)
        status.visibility = if (state == Preview.RESULT) View.GONE else View.VISIBLE
        listOf(R.id.translate_bar_1, R.id.translate_bar_2).forEach { view.findViewById<View>(it).visibility = if (state == Preview.PROGRESS) View.VISIBLE else View.GONE }
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
            val result = withContext(translateDispatcher) { runCatching { loadStudent()?.translate(source.text, target) }.getOrNull() }
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
        if (selStart >= 0 && selStart != selEnd) {
            val selected = ic.getSelectedText(0)?.toString()
            if (!selected.isNullOrBlank()) return TranslationSource(selected.trim(), 0, isSelection = true)
        }
        ensureBefore()
        val text = before.toString().trimEnd()
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
        quietUntil = SystemClock.uptimeMillis() + 500
        ic.beginBatchEdit()
        ic.finishComposingText()
        composing.clear()
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
        beforeValid = false
        previewText = ""
        updateActions()
    }

    private fun undoInsert() {
        val ic = currentInputConnection ?: return
        val undo = undoState ?: return
        quietUntil = SystemClock.uptimeMillis() + 500
        if (ic.getTextBeforeCursor(undo.inserted.length, 0)?.toString() == undo.inserted) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(undo.inserted.length, 0)
            ic.commitText(undo.original, 1)
            ic.endBatchEdit()
        }
        beforeValid = false
        undoState = null
        scheduleTranslation(immediate = true)
    }

    private fun copyTranslation() {
        val text = shownText.takeIf { it.isNotBlank() } ?: return
        clipboardManager?.setPrimaryClip(ClipData.newPlainText("Likhibi translation", text))
    }

    // ------------------------------------------------------------------ drawing helpers

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun rounded(fill: Int, radius: Float): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
    }

    private fun pressable(fill: Int, pressed: Int, radius: Float): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius))
        addState(intArrayOf(), rounded(fill, radius))
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )
}
