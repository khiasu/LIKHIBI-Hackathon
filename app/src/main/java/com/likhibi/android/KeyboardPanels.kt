package com.likhibi.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.likhibi.keyboard.R
import kotlin.math.roundToInt

/**
 * The panels that open inside the keyboard: settings, themes, theme customising, clipboard and
 * emoji. Each takes exactly the keyboard's place, so the app above never moves, and each is
 * drawn in the current keyboard theme.
 */
class KeyboardPanels(private val context: Context, private val host: Host) {

    enum class Kind { SETTINGS, THEMES, CUSTOMISE, CLIPBOARD, EMOJI, EMOJI_SEARCH }

    interface Host {
        val settings: KeyboardSettings
        val overlay: KeyPopupOverlay?
        val feedback: KeyFeedback?
        fun showPanel(kind: Kind)
        fun closePanel()
        fun typeText(text: String)
        fun deleteBackward()
        /** A setting was saved: reload the keyboard and redraw the open panel. */
        fun settingsChanged()
        /** Opens the Likhibi app, for what only an app screen can do (picking a photo). */
        fun openApp(extra: String?)
        fun panelHeight(): Int
    }

    private val prefs = KeyboardThemes.prefs(context)
    private val d = context.resources.displayMetrics.density
    private val clipboard = ClipboardStore(context)
    private val theme get() = host.settings.theme
    private lateinit var fonts: KeyboardThemes.Fonts

    fun build(kind: Kind): View {
        fonts = KeyboardThemes.Fonts(context, host.settings.figtree)
        val panel = when (kind) {
            Kind.SETTINGS -> settingsPanel()
            Kind.THEMES -> themesPanel()
            Kind.CUSTOMISE -> customisePanel()
            Kind.CLIPBOARD -> clipboardPanel()
            Kind.EMOJI -> emojiPanel()
            Kind.EMOJI_SEARCH -> emojiSearchPanel()
        }
        panel.setBackgroundColor(theme.strip)
        return panel
    }

    // ------------------------------------------------------------------ settings

    private fun settingsPanel(): View {
        val (panel, body) = scrollingPanel("Settings", action("All settings") { host.openApp(null) })
        body.addView(section("Layout"))
        body.addView(switchRow("Number row", "show_number_row", false))
        body.addView(switchRow("Emoji key", "show_emoji_key", true))
        body.addView(choiceRow("Keyboard height", "keyboard_height", listOf("short" to "Short", "normal" to "Normal", "tall" to "Tall"), "normal"))
        body.addView(choiceRow("One-handed", "one_handed_mode", listOf("off" to "Off", "left" to "Left", "right" to "Right"), "off"))

        body.addView(section("Typing"))
        body.addView(switchRow("Next-word suggestions", "next_word_suggestions", true))
        body.addView(switchRow("Auto-capitalisation", "auto_capitalise", true))
        body.addView(switchRow("Double-space period", "double_space_period", true))
        body.addView(switchRow("Long-press for symbols", "long_press_symbols", true))
        body.addView(switchRow("Popup on keypress", "key_popup", true))

        body.addView(section("Sound and vibration"))
        body.addView(switchRow("Sound on keypress", "sound_enabled", KeyboardSettings.DEFAULT_SOUND))
        if (host.settings.soundOn) {
            body.addView(sliderRow("Volume", 0, 100, (host.settings.soundVolume * 100).roundToInt(), { "$it%" }) {
                prefs.edit().putFloat("sound_volume", it / 100f).apply()
            })
        }
        body.addView(switchRow("Vibration on keypress", "haptic_enabled", true))
        if (host.settings.hapticOn) {
            body.addView(sliderRow("Strength", 1, 50, host.settings.hapticMs, { "$it ms" }) {
                prefs.edit().putInt("haptic_strength", it).apply()
            })
        }

        body.addView(section("Appearance"))
        body.addView(linkRow("Theme", KeyboardThemes.NAMES[KeyboardThemes.selectedId(prefs)].orEmpty()) { host.showPanel(Kind.THEMES) })
        body.addView(choiceRow("Key font", "selected_font", listOf(KeyboardThemes.FONT_FIGTREE to "Figtree", KeyboardThemes.FONT_SYSTEM to "System"), KeyboardThemes.FONT_FIGTREE))

        body.addView(section("Translate"))
        body.addView(choiceRow("Direction", "translate_direction", listOf("en-nag" to "English → Nagamese", "nag-en" to "Nagamese → English"), "en-nag", stacked = true))

        body.addView(section("Clipboard"))
        body.addView(switchRow("Clipboard history", "clipboard_enabled", true))
        body.addView(View(context), LinearLayout.LayoutParams(1, dp(12f)))
        return panel
    }

    // ------------------------------------------------------------------ themes

    private fun themesPanel(): View {
        val (panel, body) = scrollingPanel("Themes", action("Customise") { host.showPanel(Kind.CUSTOMISE) })
        val selected = KeyboardThemes.selectedId(prefs)
        val tiles = mutableListOf<View>()
        val ids = buildList {
            addAll(listOf(KeyboardThemes.SYSTEM, KeyboardThemes.LIGHT, KeyboardThemes.DARK, KeyboardThemes.NAGA_HERITAGE,
                KeyboardThemes.HIGH_CONTRAST, KeyboardThemes.BORDERLESS))
            if (KeyboardThemes.hasPhoto(context)) add(KeyboardThemes.PHOTO)
            if (prefs.getBoolean("custom_saved", false)) add(KeyboardThemes.CUSTOM)
        }
        for (id in ids) tiles.add(themeTile(id, id == selected))
        tiles.add(actionTile(if (KeyboardThemes.hasPhoto(context)) "New photo" else "Choose photo", R.drawable.ic_kb_image) {
            host.openApp(SettingsActivity.EXTRA_PICK_PHOTO)
        })
        tiles.add(actionTile("Customise", R.drawable.ic_kb_palette) { host.showPanel(Kind.CUSTOMISE) })
        body.setPadding(dp(12f), dp(4f), dp(12f), dp(12f))
        for (row in tiles.chunked(3)) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 3) {
                line.addView(row.getOrNull(i) ?: View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(10f)
                })
            }
            body.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) })
        }
        return panel
    }

    private fun tileWidth(): Int = ((context.resources.displayMetrics.widthPixels - dp(24f + 20f)) / 3f).toInt()

    private fun themeTile(id: String, applied: Boolean): View {
        val name = KeyboardThemes.NAMES[id].orEmpty()
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            contentDescription = name + if (applied) ", applied" else ""
            setOnClickListener {
                tap(this)
                if (applied && id == KeyboardThemes.CUSTOM) {
                    host.showPanel(Kind.CUSTOMISE)
                } else {
                    prefs.edit().putString("selected_theme", id).apply()
                    host.settingsChanged()
                }
            }
        }
        val frameInset = dp(if (applied) 2f else 1f)
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setStroke(frameInset, if (applied) theme.accent else theme.keyBorder.takeIf { Color.alpha(it) > 0 } ?: theme.keyPressed)
            }
            setPadding(frameInset, frameInset, frameInset, frameInset)
        }
        frame.addView(KeyboardPreviewView(context).apply {
            cornerRadiusPx = dp(7f).toFloat()
            bind(KeyboardThemes.forId(context, id), host.settings.figtree)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tile.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (tileWidth() * 0.72f).toInt()))
        tile.addView(caption(name, applied), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24f)))
        return tile
    }

    private fun actionTile(label: String, icon: Int, onClick: () -> Unit): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            contentDescription = label
            setOnClickListener { tap(this); onClick() }
        }
        val box = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setStroke(dp(1f), theme.keyLabelSecondary, dp(4f).toFloat(), dp(3f).toFloat())
            }
        }
        box.addView(ImageView(context).apply { setImageDrawable(tinted(icon, theme.stripLabel)) }, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
        tile.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (tileWidth() * 0.72f).toInt()))
        tile.addView(caption(label, false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24f)))
        return tile
    }

    private fun caption(text: String, applied: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(4f), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        row.addView(text(text, 12f, fonts.medium, theme.stripLabel).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (applied) row.addView(ImageView(context).apply { setImageDrawable(tinted(R.drawable.ic_kb_check, theme.accent)) },
            LinearLayout.LayoutParams(dp(14f), dp(14f)))
        return row
    }

    // ------------------------------------------------------------------ customise

    private fun customisePanel(): View {
        val custom = CustomSettings.load(prefs)
        val (panel, body) = scrollingPanel("Customise", action("Reset") {
            CustomSettings().save(prefs)
            prefs.edit().putString("selected_theme", KeyboardThemes.CUSTOM).apply()
            host.settingsChanged()
        }, back = Kind.THEMES)
        fun save(s: CustomSettings) {
            s.save(prefs)
            prefs.edit().putString("selected_theme", KeyboardThemes.CUSTOM).apply()
            host.settingsChanged()
        }

        body.addView(section("Background"))
        val bases = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
        }
        val baseColors = listOf(
            "light" to ("Light" to KeyboardThemes.light.background), "dark" to ("Dark" to KeyboardThemes.dark.background),
            "naga" to ("Naga Heritage" to KeyboardThemes.nagaHeritage.background), "high_contrast" to ("High contrast" to KeyboardThemes.highContrast.background),
            "photo" to ("Photo" to KeyboardThemes.PHOTO_PLACEHOLDER),
        )
        for ((id, info) in baseColors) {
            bases.addView(swatch(info.second, info.first, id == custom.base) {
                if (id == "photo" && !KeyboardThemes.hasPhoto(context)) host.openApp(SettingsActivity.EXTRA_PICK_PHOTO)
                else save(custom.copy(base = id))
            }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        }
        body.addView(bases)

        body.addView(section("Accent colour"))
        val accents = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f), 0, dp(12f), 0)
        }
        for ((name, value) in KeyboardThemes.ACCENTS) {
            accents.addView(swatch(value, name, value == custom.accent) { save(custom.copy(accent = value)) }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        }
        body.addView(accents)

        body.addView(section("Keys"))
        body.addView(row("Key borders", KbSwitch(context, theme, "Key borders", custom.keyBorders) { save(custom.copy(keyBorders = it)) }))
        body.addView(sliderRow("Corner radius", 0, 16, custom.cornerRadiusDp.roundToInt(), { "$it" }) { save(custom.copy(cornerRadiusDp = it.toFloat())) })
        body.addView(sliderRow("Keycap opacity", 10, 100, custom.keycapOpacity, { "$it%" }) { save(custom.copy(keycapOpacity = it)) })
        if (custom.base == "photo") {
            body.addView(sliderRow("Photo brightness", 0, 100, custom.photoBrightness, { "$it%" }) { save(custom.copy(photoBrightness = it)) })
        }
        body.addView(View(context), LinearLayout.LayoutParams(1, dp(12f)))
        return panel
    }

    private fun swatch(fill: Int, description: String, selected: Boolean, onClick: () -> Unit): View {
        val ring = FrameLayout(context).apply {
            isClickable = true
            contentDescription = description + if (selected) ", selected" else ""
            setOnClickListener { tap(this); onClick() }
            if (selected) background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2f), theme.stripLabel)
            }
        }
        ring.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(1f), theme.keyBorder.takeIf { Color.alpha(it) > 0 } ?: theme.keyPressed)
            }
        }, FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.CENTER))
        return ring
    }

    // ------------------------------------------------------------------ clipboard

    private fun clipboardPanel(): View {
        val panel = column()
        val switch = KbSwitch(context, theme, "Clipboard history", clipboard.enabled) {
            clipboard.enabled = it
            host.showPanel(Kind.CLIPBOARD)
        }
        panel.addView(header("Clipboard", switch))
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; tag = SCROLL_TAG }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        val clips = clipboard.clips()
        when {
            !clipboard.enabled -> list.addView(note("Clipboard history is off. Turn it on to keep text you copy here."))
            clips.isEmpty() -> list.addView(note("Text you copy appears here."))
            else -> for (pair in clips.chunked(2)) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in 0 until 2) {
                    val clip = pair.getOrNull(i)
                    row.addView(if (clip != null) clipCard(clip) else View(context), LinearLayout.LayoutParams(0, dp(92f), 1f).apply { if (i == 1) marginStart = dp(8f) })
                }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
            }
        }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(text("Clips are removed after 1 hour. Pinned clips stay.", 12f, fonts.regular, theme.keyLabelSecondary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(6f), dp(12f), dp(10f))
        })
        return panel
    }

    private fun clipCard(clip: ClipboardStore.Clip): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(8f), dp(4f), 0)
            background = pressable(theme.key, theme.keyPressed, if (clip.pinned) theme.accent else theme.keyBorder, dp(1f), theme.keyRadiusDp)
            isClickable = true
            contentDescription = "Paste: ${clip.text}"
            setOnClickListener {
                tap(this)
                host.typeText(clip.text)
                host.closePanel()
            }
        }
        card.addView(text(clip.text, 13f, fonts.regular, theme.keyLabel).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(6f), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(
            if (clip.pinned) text("Pinned", 11f, fonts.semibold, theme.accent) else text(ClipboardStore.age(clip.time), 11f, fonts.medium, theme.keyLabelSecondary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        footer.addView(iconButton(R.drawable.ic_kb_pin, if (clip.pinned) theme.accent else theme.keyLabelSecondary, 16f, if (clip.pinned) "Unpin" else "Pin") {
            clipboard.togglePin(clip.text)
            host.showPanel(Kind.CLIPBOARD)
        }, LinearLayout.LayoutParams(dp(32f), dp(32f)))
        footer.addView(iconButton(R.drawable.ic_kb_trash, theme.keyLabelSecondary, 16f, "Delete clip") {
            clipboard.remove(clip.text)
            host.showPanel(Kind.CLIPBOARD)
        }, LinearLayout.LayoutParams(dp(32f), dp(32f)))
        card.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32f)))
        return card
    }

    // ------------------------------------------------------------------ emoji

    private data class EmojiSection(val title: String, val icon: Int, val emojis: List<String>)

    private fun emojiSections(): List<EmojiSection> = listOf(
        EmojiSection("Recent", R.drawable.ic_kb_clock, recentEmojis()),
        EmojiSection("Smileys", R.drawable.ic_kb_smile, Emoji.SMILEYS),
        EmojiSection("Gestures", R.drawable.ic_kb_hand, Emoji.GESTURES),
        EmojiSection("Nature and places", R.drawable.ic_kb_image, Emoji.NATURE),
        EmojiSection("Symbols", R.drawable.ic_kb_type, Emoji.SYMBOLS),
    )

    private fun recentEmojis(): List<String> = prefs.getString("emoji_recent", "").orEmpty().split('\u001F').filter { it.isNotEmpty() }

    private fun typeEmoji(emoji: String) {
        host.typeText(emoji)
        val recent = listOf(emoji) + recentEmojis().filter { it != emoji }
        prefs.edit().putString("emoji_recent", recent.take(16).joinToString("\u001F")).apply()
    }

    /** Emoji (Gboard's layout): search on top, the emoji in sections, categories along the bottom. */
    private fun emojiPanel(): View {
        val panel = column()
        val search = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            background = pressable(theme.key, theme.keyPressed, theme.keyBorder, dp(1f), 20f)
            isClickable = true
            contentDescription = "Search emoji"
            setOnClickListener { tap(this); host.showPanel(Kind.EMOJI_SEARCH) }
        }
        search.addView(ImageView(context).apply { setImageDrawable(tinted(R.drawable.ic_kb_search, theme.keyLabelSecondary)) }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
        search.addView(text("Search emoji", 15f, fonts.regular, theme.keyLabelSecondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10f) })
        panel.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)).apply { setMargins(dp(8f), dp(6f), dp(8f), dp(2f)) })

        val sections = emojiSections().filter { it.emojis.isNotEmpty() }
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; tag = SCROLL_TAG }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4f), 0, dp(4f), 0) }
        val blocks = sections.map { section ->
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(section.title, 12f, fonts.semibold, theme.keyLabelSecondary).apply {
                    ViewCompat.setAccessibilityHeading(this, true)
                    setPadding(dp(8f), dp(8f), 0, dp(2f))
                })
                addView(emojiGrid(section.emojis))
            }.also { content.addView(it) }
        }
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), dp(4f), dp(6f))
        }
        bottom.addView(keyButton("ABC", 0, "Back to keyboard") { host.closePanel() }, LinearLayout.LayoutParams(dp(56f), dp(40f)))
        val tabIcons = sections.mapIndexed { i, section ->
            ImageView(context).apply {
                setImageDrawable(SizedDrawable(tinted(section.icon, theme.keyLabelSecondary), dp(22f)))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = section.title
                background = pressable(Color.TRANSPARENT, theme.keyPressed, 0, 0, 20f)
                setOnClickListener {
                    tap(this)
                    scroll.smoothScrollTo(0, blocks[i].top)
                }
            }.also { bottom.addView(it, LinearLayout.LayoutParams(0, dp(40f), 1f)) }
        }
        fun markTab(index: Int) = tabIcons.forEachIndexed { i, icon ->
            icon.setImageDrawable(SizedDrawable(tinted(sections[i].icon, if (i == index) theme.accent else theme.keyLabelSecondary), dp(22f)))
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> markTab(blocks.indexOfLast { it.top <= y + dp(8f) }.coerceAtLeast(0)) }
        markTab(0)
        bottom.addView(deleteButton(), LinearLayout.LayoutParams(dp(56f), dp(40f)))
        panel.addView(bottom)
        return panel
    }

    private fun emojiGrid(emojis: List<String>, columns: Int = 8): View {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (chunk in emojis.chunked(columns)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (emoji in chunk) row.addView(emojiCell(emoji), LinearLayout.LayoutParams(0, dp(44f), 1f))
            repeat(columns - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, dp(44f), 1f)) }
            grid.addView(row)
        }
        return grid
    }

    private fun emojiCell(emoji: String): TextView = TextView(context).apply {
        text = emoji
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        background = pressable(Color.TRANSPARENT, theme.keyPressed, 0, 0, theme.keyRadiusDp)
        setOnClickListener {
            host.feedback?.press(KeyFeedback.Kind.STANDARD, host.settings)
            typeEmoji(emoji)
        }
    }

    /** Emoji search: the keyboard below types into the search box, results show as you type. */
    private fun emojiSearchPanel(): View {
        val panel = column()
        val query = StringBuilder()
        val field = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(12f), 0)
            background = rounded(theme.key, 20f * d, theme.keyBorder, dp(1f))
        }
        field.addView(iconButton(R.drawable.ic_kb_chevron_left, theme.keyLabel, 20f, "Back to emoji") { host.showPanel(Kind.EMOJI) }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        val queryView = text("Search emoji", 15f, fonts.regular, theme.keyLabelSecondary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
        field.addView(queryView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4f) })
        panel.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)).apply { setMargins(dp(8f), dp(6f), dp(8f), 0) })

        val results = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        panel.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(results) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)).apply { setMargins(dp(8f), 0, dp(8f), 0) })

        val pool = emojiSections().drop(1).flatMap { it.emojis }.distinct()
        fun refresh() {
            queryView.text = if (query.isEmpty()) "Search emoji" else query
            queryView.setTextColor(if (query.isEmpty()) theme.keyLabelSecondary else theme.keyLabel)
            results.removeAllViews()
            val found = Emoji.search(query.toString(), pool)
            if (query.isNotEmpty() && found.isEmpty()) results.addView(text("No emoji found", 13f, fonts.regular, theme.keyLabelSecondary))
            for (emoji in found.take(40)) results.addView(emojiCell(emoji), LinearLayout.LayoutParams(dp(44f), dp(44f)))
        }

        // A smaller copy of the keyboard that fits under the search box.
        val available = host.panelHeight() - dp(46f + 48f)
        val keyHeight = ((available / d - 14f - 3 * 8f) / 4f).coerceIn(32f, host.settings.keyHeightDp)
        val keys = KeyboardView(context).apply {
            overlay = host.overlay
            feedback = host.feedback
            configure(host.settings.copy(numberRow = false, keyHeightDp = keyHeight, rowGapDp = 8f, longPressSymbols = false))
            setMode(KeyboardView.Mode.SEARCH)
            listener = object : KeyboardView.Listener {
                override fun onKeyText(text: String) {
                    query.append(text.lowercase())
                    refresh()
                }
                override fun onKeyCode(code: Int) {
                    when (code) {
                        KeyboardLayouts.CODE_DELETE -> if (query.isNotEmpty()) query.deleteCharAt(query.length - 1)
                        KeyboardLayouts.CODE_SPACE -> if (query.isNotEmpty() && query.last() != ' ') query.append(' ')
                        KeyboardLayouts.CODE_LETTERS, KeyboardLayouts.CODE_SYMBOLS, KeyboardLayouts.CODE_ENTER -> {
                            host.showPanel(Kind.EMOJI)
                            return
                        }
                    }
                    refresh()
                }
                override fun onCursorMove(steps: Int) = Unit
            }
        }
        panel.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refresh()
        return panel
    }

    // ------------------------------------------------------------------ building blocks

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** A panel with the shared header and a scrolling body; returns both. */
    private fun scrollingPanel(title: String, action: View?, back: Kind? = null): Pair<View, LinearLayout> {
        val panel = column()
        panel.addView(header(title, action, back))
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            tag = SCROLL_TAG
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return panel to body
    }

    /** 48dp, the same height as the suggestion strip: back, title, then an optional action. */
    private fun header(title: String, action: View?, back: Kind? = null): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8f), 0)
        }
        bar.addView(iconButton(R.drawable.ic_kb_chevron_left, theme.stripLabel, 22f, "Back") {
            if (back != null) host.showPanel(back) else host.closePanel()
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        bar.addView(text(title, 16f, fonts.semibold, theme.stripLabel).apply { ViewCompat.setAccessibilityHeading(this, true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) bar.addView(action)
        return bar
    }

    private fun action(label: String, onClick: () -> Unit): TextView = text(label, 14f, fonts.medium, theme.accentOn()).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12f), 0, dp(12f), 0)
        minHeight = dp(40f)
        background = pressable(Color.TRANSPARENT, theme.keyPressed, 0, 0, 20f)
        isClickable = true
        setOnClickListener { tap(this); onClick() }
    }

    /** The accent where it reads well on the strip, otherwise the strip's own text colour. */
    private fun KeyboardTheme.accentOn(): Int = if (contrast(accent, solidStrip()) >= 3.0) accent else stripLabel

    private fun KeyboardTheme.solidStrip(): Int = if (usesPhoto) Color.rgb(40, 42, 40) else if (Color.alpha(strip) == 255) strip else background

    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun section(title: String): View = text(title, 12f, fonts.semibold, theme.keyLabelSecondary).apply {
        ViewCompat.setAccessibilityHeading(this, true)
        setPadding(dp(16f), dp(14f), dp(16f), dp(6f))
    }

    private fun row(label: String, end: View, description: String? = null, onClick: (() -> Unit)? = null): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(dp(16f), dp(2f), dp(12f), dp(2f))
            if (onClick != null) {
                isClickable = true
                background = pressable(Color.TRANSPARENT, theme.keyPressed, 0, 0, 0f)
                setOnClickListener { onClick() }
            }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(label, 15f, fonts.regular, theme.stripLabel))
        if (description != null) texts.addView(text(description, 12f, fonts.regular, theme.keyLabelSecondary))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        row.addView(end)
        return row
    }

    private fun switchRow(label: String, key: String, default: Boolean): View {
        val switch = KbSwitch(context, theme, label, prefs.getBoolean(key, default)) {
            prefs.edit().putBoolean(key, it).apply()
            host.settingsChanged()
        }
        return row(label, switch) { switch.performClick() }
    }

    private fun linkRow(label: String, value: String, onClick: () -> Unit): View {
        val end = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        end.addView(text(value, 14f, fonts.regular, theme.keyLabelSecondary))
        end.addView(ImageView(context).apply { setImageDrawable(tinted(R.drawable.ic_kb_chevron_right, theme.keyLabelSecondary)) },
            LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { marginStart = dp(4f) })
        return row(label, end) { onClick() }
    }

    /** A setting with a few values, as a segmented control (Gboard puts these inline too). */
    private fun choiceRow(label: String, key: String, options: List<Pair<String, String>>, default: String, stacked: Boolean = false): View {
        val current = prefs.getString(key, default) ?: default
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            background = rounded(theme.keyFunction.let { if (Color.alpha(it) < 60) theme.keyPressed else it }, 10f * d)
        }
        for ((value, name) in options) {
            val on = value == current
            track.addView(text(name, 13f, if (on) fonts.semibold else fonts.regular, if (on) theme.keyLabel else theme.keyLabelSecondary).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(10f), 0, dp(10f), 0)
                if (on) background = rounded(solidKey(), 8f * d)
                isClickable = true
                contentDescription = "$label: $name" + if (on) ", selected" else ""
                setOnClickListener {
                    tap(this)
                    prefs.edit().putString(key, value).apply()
                    host.settingsChanged()
                }
            }, LinearLayout.LayoutParams(if (stacked) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, dp(32f), if (stacked) 1f else 0f))
        }
        if (!stacked) return row(label, track)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(8f))
        }
        box.addView(text(label, 15f, fonts.regular, theme.stripLabel), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
        box.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    /** A labelled slider; the value is saved when the finger lifts, not on every step. */
    private fun sliderRow(label: String, min: Int, max: Int, value: Int, format: (Int) -> String, onSave: (Int) -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(8f), dp(4f))
        }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text(label, 15f, fonts.regular, theme.stripLabel), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = text(format(value), 13f, fonts.medium, theme.keyLabelSecondary).apply { setPadding(0, 0, dp(8f), 0) }
        top.addView(valueView)
        box.addView(top)
        box.addView(SeekBar(context).apply {
            this.max = max - min
            progress = value - min
            contentDescription = label
            progressTintList = ColorStateList.valueOf(theme.accentOn())
            thumbTintList = ColorStateList.valueOf(theme.accentOn())
            progressBackgroundTintList = ColorStateList.valueOf(theme.keyLabelSecondary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    valueView.text = format(progress + min)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    onSave(seekBar.progress + min)
                    host.settingsChanged()
                }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f)))
        return box
    }

    private fun note(message: String): View = text(message, 13f, fonts.regular, theme.keyLabelSecondary).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(40f), dp(16f), dp(40f))
    }

    private fun keyButton(label: String, icon: Int, description: String, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = fonts.medium
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(theme.keyLabel)
        background = pressable(theme.keyFunction, theme.keyPressed, theme.keyBorder, dp(theme.keyBorderWidthDp), theme.keyRadiusDp)
        if (icon != 0) setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(icon, theme.keyLabel), null, null, null)
        contentDescription = description
        setOnClickListener {
            host.feedback?.press(KeyFeedback.Kind.FUNCTION, host.settings)
            onClick()
        }
    }

    /** Backspace for the emoji panel, repeating while held. */
    private fun deleteButton(): View = ImageView(context).apply {
        setImageDrawable(SizedDrawable(tinted(R.drawable.ic_kb_delete, theme.keyLabel), dp(22f)))
        scaleType = ImageView.ScaleType.CENTER
        background = pressable(theme.keyFunction, theme.keyPressed, theme.keyBorder, dp(theme.keyBorderWidthDp), theme.keyRadiusDp)
        contentDescription = "Delete"
        isClickable = true
        val handler = Handler(Looper.getMainLooper())
        val repeat = object : Runnable {
            override fun run() {
                host.deleteBackward()
                handler.postDelayed(this, 50L)
            }
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    host.feedback?.press(KeyFeedback.Kind.FUNCTION, host.settings)
                    host.deleteBackward()
                    handler.postDelayed(repeat, 400L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                }
            }
            true
        }
    }

    private fun iconButton(icon: Int, color: Int, sizeDp: Float, description: String, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageDrawable(SizedDrawable(tinted(icon, color), dp(sizeDp)))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        background = pressable(Color.TRANSPARENT, theme.keyPressed, 0, 0, 24f)
        setOnClickListener { tap(this); onClick() }
    }

    private fun text(value: CharSequence, sizeSp: Float, face: android.graphics.Typeface, color: Int) = TextView(context).apply {
        text = value
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        includeFontPadding = false
    }

    private fun solidKey(): Int = if (Color.alpha(theme.key) >= 200) theme.key else theme.keyPressed

    private fun tap(view: View) {
        host.feedback?.press(KeyFeedback.Kind.FUNCTION, host.settings) ?: view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun tinted(icon: Int, color: Int): Drawable = ContextCompat.getDrawable(context, icon)!!.mutate().also { it.setTint(color) }

    private fun rounded(fill: Int, radius: Float, border: Int = 0, borderWidth: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
        if (borderWidth > 0 && Color.alpha(border) > 0) setStroke(borderWidth, border)
    }

    private fun pressable(fill: Int, pressed: Int, border: Int, borderWidth: Int, radiusDp: Float): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radiusDp * d, border, borderWidth))
        addState(intArrayOf(), rounded(fill, radiusDp * d, border, borderWidth))
    }

    private fun dp(v: Float) = (v * d).roundToInt()

    companion object {
        const val SCROLL_TAG = "panel_scroll"
    }
}

/** Draws a drawable at a fixed size, centred, so small icons keep a large touch target. */
class SizedDrawable(private val inner: Drawable, private val size: Int) : Drawable() {
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

/** The keyboard's own switch (design 29:1808): a 40 x 24 pill in theme colours, 48dp to touch. */
class KbSwitch(
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
        minimumWidth = (52 * d).toInt()
        minimumHeight = (48 * d).toInt()
        setOnClickListener {
            checked = !checked
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            invalidate()
            onChange(checked)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize((52 * d).toInt(), widthMeasureSpec), resolveSize((48 * d).toInt(), heightMeasureSpec))

    override fun onDraw(canvas: Canvas) {
        val w = 40 * d
        val h = 24 * d
        val left = (width - w) / 2
        val top = (height - h) / 2
        paint.color = if (checked) theme.accent else if (Color.alpha(theme.keyFunction) < 60) theme.keyPressed else theme.keyFunction
        canvas.drawRoundRect(left, top, left + w, top + h, h / 2, h / 2, paint)
        paint.color = if (checked) theme.onAccent else theme.keyLabelSecondary
        canvas.drawCircle(if (checked) left + w - h / 2 else left + h / 2, top + h / 2, 9 * d, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = checked
    }
}
