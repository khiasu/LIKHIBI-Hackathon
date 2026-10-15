package com.likhibi.android

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.likhibi.keyboard.R
import com.likhibi.android.SettingsUi.Weight

/** Preferences (Figma 35:3350): layout, key press, typing, translate and learned words. */
class PreferencesActivity : AppCompatActivity() {
    private val prefs by lazy { KeyboardThemes.prefs(this) }
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyboardThemes.migrate(this)
        body = SettingsUi.screen(this, "Preferences")
        render()
    }

    private fun render() {
        body.removeAllViews()
        section("Layout", first = true) {
            switchRow("Number row", null, "show_number_row", true)
            switchRow("Show emoji switch key", null, "show_emoji_key", false)
            choiceRow("Keyboard height", null, "keyboard_height", listOf("short" to "Short", "normal" to "Normal", "tall" to "Tall"), "normal")
            choiceRow("One-handed mode", null, "one_handed_mode", listOf("off" to "Off", "left" to "Left", "right" to "Right"), "off")
        }
        section("Key press") {
            switchRow("Sound on keypress", null, "sound_enabled", CustomKeyboardView.DEFAULT_SOUND_ENABLED)
            switchRow("Vibration on keypress", "Strength: ${strengthName()}", "haptic_enabled", true)
            checkRow("Popup on keypress", "key_popup", true)
            checkRow("Long-press for symbols", "long_press_symbols", true)
        }
        section("Typing") {
            switchRow("Next-word suggestions", null, "next_word_suggestions", true)
        }
        section("Translate") {
            choiceRow(
                "Translate default direction", "Runs on this device, no network", "translate_direction",
                listOf("en-nag" to "English → Nagamese", "nag-en" to "Nagamese → English"), "en-nag",
            )
        }
        section("Learned words") {
            add(row("Clear learned words", "Removes words Likhibi learned from your typing", clearButton()))
        }
    }

    private fun strengthName(): String {
        val ms = prefs.getInt("haptic_strength", CustomKeyboardView.DEFAULT_HAPTIC_MS)
        return when {
            ms <= 8 -> "light"
            ms <= 20 -> "medium"
            else -> "strong"
        }
    }

    private class Section(val rows: MutableList<View> = mutableListOf()) {
        fun add(view: View) = rows.add(view)
    }

    private fun section(title: String, first: Boolean = false, build: Section.() -> Unit) {
        body.addView(SettingsUi.sectionHeading(this, title, first))
        val section = Section().apply(build)
        section.rows.forEachIndexed { i, row ->
            if (i > 0) body.addView(SettingsUi.divider(this))
            body.addView(row)
        }
    }

    /** A row: label (and description) on the left, its control on the right. */
    private fun row(label: String, description: String?, end: View, onClick: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            if (onClick != null) {
                isClickable = true
                background = SettingsUi.pressable(this@PreferencesActivity, null, dp(8f).toFloat())
                setOnClickListener { onClick() }
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(SettingsUi.text(this, label, 14f).apply { setLineSpacing(0f, 1.15f) })
        if (description != null) texts.addView(SettingsUi.text(this, description, 12f, colorRes = R.color.app_text_secondary))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        row.addView(end)
        return row
    }

    private fun Section.switchRow(label: String, description: String?, key: String, default: Boolean) {
        val switch = SettingsUi.switch(this@PreferencesActivity, prefs.getBoolean(key, default), label) {
            prefs.edit().putBoolean(key, it).apply()
        }
        add(row(label, description, switch) { switch.toggle() })
    }

    private fun Section.checkRow(label: String, key: String, default: Boolean) {
        val box = CheckBox(this@PreferencesActivity).apply {
            setButtonDrawable(R.drawable.pref_checkbox)
            isChecked = prefs.getBoolean(key, default)
            contentDescription = label
            minimumWidth = dp(48f)
            minimumHeight = dp(48f)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
        add(row(label, null, box) { box.toggle() })
    }

    /** A setting with a few named values: shows the current one; tapping offers the choices. */
    private fun Section.choiceRow(label: String, description: String?, key: String, options: List<Pair<String, String>>, default: String) {
        val current = prefs.getString(key, default) ?: default
        val value = LinearLayout(this@PreferencesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        value.addView(SettingsUi.text(this@PreferencesActivity, options.firstOrNull { it.first == current }?.second ?: current, 14f, colorRes = R.color.app_text_secondary))
        value.addView(ImageView(this@PreferencesActivity).apply {
            setImageResource(R.drawable.ic_kb_chevron_right)
            setColorFilter(SettingsUi.color(this@PreferencesActivity, R.color.app_text_secondary))
        }, LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { marginStart = dp(4f) })
        add(row(label, description, value) {
            val index = options.indexOfFirst { it.first == (prefs.getString(key, default) ?: default) }
            AlertDialog.Builder(this@PreferencesActivity, R.style.Likhibi_Dialog)
                .setTitle(label)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), index) { dialog, which ->
                    prefs.edit().putString(key, options[which].first).apply()
                    dialog.dismiss()
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
    }

    /** A tonal destructive button; asks first, because learned words cannot be brought back. */
    private fun clearButton(): TextView = SettingsUi.text(this, "Clear", 14f, Weight.MEDIUM, R.color.app_primary).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12f), 0, dp(12f), 0)
        minHeight = dp(40f)
        background = SettingsUi.pressable(this@PreferencesActivity, SettingsUi.rounded(0x26D32F2E, dp(10f).toFloat()), dp(10f).toFloat())
        isClickable = true
        contentDescription = "Clear learned words"
        setOnClickListener {
            AlertDialog.Builder(this@PreferencesActivity, R.style.Likhibi_Dialog)
                .setTitle("Clear learned words?")
                .setMessage("Likhibi forgets the words and word pairs it learned from your typing. This cannot be undone.")
                .setPositiveButton("Clear") { _, _ -> clearLearnedWords() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun clearLearnedWords() {
        getSharedPreferences("likhibi_user_dict_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        // The keyboard forgets its in-memory copy the next time it opens.
        prefs.edit().putLong("learned_reset_at", System.currentTimeMillis()).apply()
        Toast.makeText(this, "Learned words cleared", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Float) = SettingsUi.dp(this, v)
}
