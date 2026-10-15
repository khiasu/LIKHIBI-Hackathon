package com.likhibi.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.likhibi.keyboard.R

/** The settings home (Figma 54:245). Themes and the on/off preferences have their own screens. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var lblHaptic: TextView
    private lateinit var seekHaptic: Slider
    private lateinit var lblSoundVolume: TextView
    private lateinit var seekSoundVolume: Slider

    companion object {
        const val EXTRA_OPEN_THEMES = "open_themes"
        const val EXTRA_PICK_PHOTO = "ACTION_PICK_WALLPAPER"
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && KeyboardPhoto.save(this, uri)) {
            prefs.edit().putString("selected_theme", KeyboardThemes.PHOTO).apply()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyboardThemes.migrate(this)
        setContentView(R.layout.activity_settings)
        prefs = KeyboardThemes.prefs(this)

        findViewById<Button>(R.id.btn_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_select_keyboard).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        findViewById<View>(R.id.row_theme).setOnClickListener {
            startActivity(Intent(this, ThemeGalleryActivity::class.java))
        }
        findViewById<View>(R.id.row_more_preferences).setOnClickListener {
            startActivity(Intent(this, PreferencesActivity::class.java))
        }

        // Keyboard font: Figtree (the design's keyboard font) or the phone's own.
        val fontSpinner: Spinner = findViewById(R.id.spinner_font)
        val fontValues = arrayOf(KeyboardThemes.FONT_FIGTREE, KeyboardThemes.FONT_SYSTEM)
        fontSpinner.adapter = ArrayAdapter(this, R.layout.item_select_value, arrayOf("Figtree", "System")).also {
            it.setDropDownViewResource(R.layout.item_select_dropdown)
        }
        fontSpinner.setSelection(if (KeyboardThemes.usesFigtree(prefs)) 0 else 1)
        fontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (prefs.getString("selected_font", KeyboardThemes.FONT_FIGTREE) != fontValues[position]) {
                    performHapticClick()
                    prefs.edit().putString("selected_font", fontValues[position]).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        lblHaptic = findViewById(R.id.lbl_haptic)
        seekHaptic = findViewById(R.id.seek_haptic)
        seekHaptic.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.edit().putInt("haptic_strength", value.toInt()).apply()
            renderFeedbackLabels()
        }
        seekHaptic.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = triggerTactileSample(slider.value.toInt())
        })

        lblSoundVolume = findViewById(R.id.lbl_sound_volume)
        seekSoundVolume = findViewById(R.id.seek_sound_volume)
        seekSoundVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.edit().putFloat("sound_volume", value / 100f).apply()
            renderFeedbackLabels()
        }

        // Translation: the offline model ships in the APK; the server is an opt-in extra.
        findViewById<TextView>(R.id.text_translate_model_status).text = try {
            val bytes = listOf("translate/encoder.onnx", "translate/decoder.onnx").sumOf { assets.openFd(it).use { fd -> fd.length } }
            "Installed, %.0f MB. Works without internet.".format(bytes / 1_000_000.0)
        } catch (e: Exception) {
            "Not included in this build"
        }
        findViewById<SwitchMaterial>(R.id.switch_translate_online).apply {
            isChecked = prefs.getBoolean("translate_auto_online", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("translate_auto_online", checked).apply() }
        }
        findViewById<EditText>(R.id.edit_translate_server).apply {
            setText(prefs.getString("translate_server_url", ""))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit().putString("translate_server_url", s?.toString()?.trim().orEmpty()).apply()
                }
            })
        }

        // Contributing corrections: opt-in, with an optional name to credit and the speaker's dialect.
        val contributorBox = findViewById<View>(R.id.layout_contributor)
        findViewById<SwitchMaterial>(R.id.switch_contribute).apply {
            isChecked = prefs.getBoolean("contribute_feedback", false)
            contributorBox.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("contribute_feedback", on).apply()
                contributorBox.visibility = if (on) View.VISIBLE else View.GONE
            }
        }
        for ((id, key) in listOf(R.id.edit_contributor_name to "contributor_name", R.id.edit_contributor_variety to "contributor_variety")) {
            findViewById<EditText>(id).apply {
                setText(prefs.getString(key, ""))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        prefs.edit().putString(key, s?.toString()?.trim().orEmpty()).apply()
                    }
                })
            }
        }

        findViewById<View>(R.id.btn_clear_clipboard).setOnClickListener {
            performHapticClick()
            ClipboardStore(this).clear()
            Toast.makeText(this, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
        }

        // Opened from the keyboard's toolbar.
        if (savedInstanceState == null) {
            if (intent?.getBooleanExtra(EXTRA_OPEN_THEMES, false) == true) startActivity(Intent(this, ThemeGalleryActivity::class.java))
            if (intent?.getBooleanExtra(EXTRA_PICK_PHOTO, false) == true) pickPhoto.launch("image/*")
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Settings change on other screens; show their current values here. */
    private fun refresh() {
        findViewById<TextView>(R.id.text_theme_value).text = KeyboardThemes.NAMES[KeyboardThemes.selectedId(prefs)] ?: ""
        findViewById<Spinner>(R.id.spinner_font).setSelection(if (KeyboardThemes.usesFigtree(prefs)) 0 else 1)
        seekHaptic.value = prefs.getInt("haptic_strength", KeyboardSettings.DEFAULT_HAPTIC_MS).coerceIn(1, 50).toFloat()
        seekSoundVolume.value = (prefs.getFloat("sound_volume", 0.5f) * 100).toInt().coerceIn(0, 100).toFloat()
        renderFeedbackLabels()
        renderQueue()
    }

    private fun renderQueue() {
        val waiting = com.likhibi.nlp.translate.FeedbackClient(this).pending
        findViewById<TextView>(R.id.text_feedback_queue).text = when {
            waiting == 0 -> "Corrections are sent to the server address above."
            prefs.getString("translate_server_url", "").isNullOrBlank() -> "$waiting waiting on this phone. Add a server address above to send them."
            else -> "$waiting waiting to be sent; they go the next time the keyboard opens online."
        }
    }

    private fun renderFeedbackLabels() {
        val hapticOn = prefs.getBoolean("haptic_enabled", true)
        val soundOn = prefs.getBoolean("sound_enabled", KeyboardSettings.DEFAULT_SOUND)
        lblHaptic.text = if (hapticOn) "Vibration strength: ${seekHaptic.value.toInt()}ms" else "Vibration is off. Turn it on in More preferences."
        lblSoundVolume.text = if (soundOn) "Volume: ${seekSoundVolume.value.toInt()}%" else "Sounds are off. Turn them on in More preferences."
        seekHaptic.isEnabled = hapticOn
        seekSoundVolume.isEnabled = soundOn
        seekHaptic.alpha = if (hapticOn) 1f else 0.4f
        seekSoundVolume.alpha = if (soundOn) 1f else 0.4f
    }

    private fun performHapticClick() {
        if (prefs.getBoolean("haptic_enabled", true)) triggerTactileSample(prefs.getInt("haptic_strength", KeyboardSettings.DEFAULT_HAPTIC_MS))
    }

    private fun triggerTactileSample(durationMs: Int) {
        if (durationMs <= 0) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), 200))
    }
}
