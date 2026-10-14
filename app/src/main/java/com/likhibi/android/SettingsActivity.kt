package com.likhibi.android

import com.likhibi.keyboard.R
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.AdapterView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import android.widget.LinearLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var lblHaptic: TextView
    private lateinit var seekHaptic: SeekBar

    private lateinit var fontSpinner: Spinner
    private lateinit var switchSound: SwitchMaterial
    private lateinit var lblSoundVolume: TextView
    private lateinit var seekSoundVolume: SeekBar

    companion object {
        private const val RC_PICK_WALLPAPER = 1001

        /** Stored theme ids, in the order the theme spinner lists them. */
        private val THEME_VALUES = arrayOf(
            "theme_system",
            "theme_slate_dark",
            "theme_chalk_light",
            "theme_naga_heritage",
            "theme_obsidian_oled",
            "theme_nordic_frost",
            "theme_custom"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("likhibi_keyboard_prefs", Context.MODE_PRIVATE)

        findViewById<Button>(R.id.btn_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btn_select_keyboard).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // Theme Selection via Spinner
        val themeSpinner: Spinner = findViewById(R.id.spinner_theme)
        val themeOptions = arrayOf(
            "Dynamic System (Auto)",
            "Slate Dark",
            "Chalk Light",
            "Naga Heritage",
            "Obsidian AMOLED",
            "Nordic Frost",
            "Custom Studio"
        )
        val themeValues = THEME_VALUES
        val themeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themeOptions)
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        themeSpinner.adapter = themeAdapter

        val currentTheme = prefs.getString("selected_theme", CustomKeyboardView.DEFAULT_THEME) ?: CustomKeyboardView.DEFAULT_THEME
        val resolvedIndex = when (currentTheme) {
            "theme_midnight_glass", "theme_liquid_glass" -> themeValues.indexOf("theme_slate_dark")
            "theme_pure_minimal", "theme_material_you" -> themeValues.indexOf("theme_chalk_light")
            else -> themeValues.indexOf(currentTheme)
        }.coerceAtLeast(0)
        themeSpinner.setSelection(resolvedIndex)
        var isInitialSelection = true

        val customControlsLayout: LinearLayout = findViewById(R.id.layout_custom_theme_controls)
        val btnChangeWallpaper: Button = findViewById(R.id.btn_change_wallpaper)
        val seekKeyOpacity: SeekBar = findViewById(R.id.seek_key_opacity)
        val lblKeyOpacity: TextView = findViewById(R.id.lbl_key_opacity)
        val seekOverlayOpacity: SeekBar = findViewById(R.id.seek_overlay_opacity)
        val lblOverlayOpacity: TextView = findViewById(R.id.lbl_overlay_opacity)
        val seekCornerRadius: SeekBar = findViewById(R.id.seek_corner_radius)
        val lblCornerRadius: TextView = findViewById(R.id.lbl_corner_radius)
        val switchCustomShadow: SwitchMaterial = findViewById(R.id.switch_custom_shadow)
        val switchNumberRow: SwitchMaterial = findViewById(R.id.switch_number_row)

        val isNumberRowEnabled = prefs.getBoolean("show_number_row", false)
        switchNumberRow.isChecked = isNumberRowEnabled
        switchNumberRow.setOnCheckedChangeListener { _, isChecked ->
            performHapticClick()
            prefs.edit().putBoolean("show_number_row", isChecked).apply()
        }

        val initKeyOpacity = prefs.getInt("custom_key_opacity", 70)
        seekKeyOpacity.progress = initKeyOpacity
        lblKeyOpacity.text = "Keycap Opacity: ${(initKeyOpacity * 100 / 255)}%"

        val initOverlayOpacity = prefs.getInt("custom_overlay_opacity", 140)
        seekOverlayOpacity.progress = initOverlayOpacity
        lblOverlayOpacity.text = "Wallpaper Dimming: ${(initOverlayOpacity * 100 / 240)}%"

        val initRadius = prefs.getFloat("custom_corner_radius", 8f).toInt()
        seekCornerRadius.progress = initRadius
        lblCornerRadius.text = "Key Corner Rounding: ${initRadius}dp"

        switchCustomShadow.isChecked = prefs.getBoolean("custom_key_has_shadow", true)
        switchCustomShadow.setOnCheckedChangeListener { _, isChecked ->
            performHapticClick()
            prefs.edit().putBoolean("custom_key_has_shadow", isChecked).apply()
        }

        seekKeyOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lblKeyOpacity.text = "Keycap Opacity: ${(progress * 100 / 255)}%"
                prefs.edit().putInt("custom_key_opacity", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekOverlayOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lblOverlayOpacity.text = "Wallpaper Dimming: ${(progress * 100 / 240)}%"
                prefs.edit().putInt("custom_overlay_opacity", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekCornerRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lblCornerRadius.text = "Key Corner Rounding: ${progress}dp"
                prefs.edit().putFloat("custom_corner_radius", progress.toFloat()).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnChangeWallpaper.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            startActivityForResult(intent, RC_PICK_WALLPAPER)
        }

        // Accent Color Chips setup (Curated, non-neon flagship palette)
        val accentChips = listOf(
            Pair(R.id.color_chip_cyan, android.graphics.Color.parseColor("#1A73E8")),   // Classic Material Blue
            Pair(R.id.color_chip_amber, android.graphics.Color.parseColor("#D97706")),  // Warm Amber
            Pair(R.id.color_chip_emerald, android.graphics.Color.parseColor("#059669")),// Forest Emerald
            Pair(R.id.color_chip_rose, android.graphics.Color.parseColor("#D32F2F")),   // Crimson Red
            Pair(R.id.color_chip_violet, android.graphics.Color.parseColor("#6366F1")), // Slate Violet
            Pair(R.id.color_chip_blue, android.graphics.Color.parseColor("#4B5563")),   // Neutral Graphite
            Pair(R.id.color_chip_white, android.graphics.Color.WHITE)
        )
        for ((viewId, colorVal) in accentChips) {
            findViewById<View>(viewId)?.setOnClickListener {
                performHapticClick()
                prefs.edit().putInt("custom_accent_color", colorVal).apply()
                Toast.makeText(this, "Accent color updated", Toast.LENGTH_SHORT).show()
            }
        }

        val isCustomInit = currentTheme == "theme_custom"
        customControlsLayout.visibility = if (isCustomInit) View.VISIBLE else View.GONE

        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isInitialSelection) performHapticClick()
                
                val isCustomTheme = themeValues[position] == "theme_custom"
                customControlsLayout.visibility = if (isCustomTheme) View.VISIBLE else View.GONE
                
                if (isCustomTheme && !isInitialSelection) {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                    startActivityForResult(intent, RC_PICK_WALLPAPER)
                }
                
                prefs.edit().putString("selected_theme", themeValues[position]).apply()
                isInitialSelection = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Font Selection
        fontSpinner = findViewById(R.id.spinner_font)
        val fontOptions = arrayOf("System Default", "Sans Serif Clean", "Modern Light", "Bold Impact", "Classic Serif", "Monospace Code")
        val fontValues = arrayOf("sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-black", "serif", "monospace")
        val fontAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fontOptions)
        fontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fontSpinner.adapter = fontAdapter

        val currentFont = prefs.getString("selected_font", "sans-serif") ?: "sans-serif"
        val fontIndex = fontValues.indexOf(currentFont).coerceAtLeast(0)
        fontSpinner.setSelection(fontIndex)

        fontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                performHapticClick()
                prefs.edit().putString("selected_font", fontValues[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Sound Feedback
        switchSound = findViewById(R.id.switch_sound)
        lblSoundVolume = findViewById(R.id.lbl_sound_volume)
        seekSoundVolume = findViewById(R.id.seek_sound_volume)

        val soundEnabled = prefs.getBoolean("sound_enabled", CustomKeyboardView.DEFAULT_SOUND_ENABLED)
        switchSound.isChecked = soundEnabled
        seekSoundVolume.isEnabled = soundEnabled

        val currentVolume = (prefs.getFloat("sound_volume", 0.5f) * 100).toInt()
        seekSoundVolume.progress = currentVolume
        lblSoundVolume.text = "Volume: ${currentVolume}%"

        switchSound.setOnCheckedChangeListener { _, isChecked ->
            performHapticClick()
            prefs.edit().putBoolean("sound_enabled", isChecked).apply()
            seekSoundVolume.isEnabled = isChecked
        }

        seekSoundVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lblSoundVolume.text = "Volume: ${progress}%"
                prefs.edit().putFloat("sound_volume", progress / 100f).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Haptic Intensity
        lblHaptic = findViewById(R.id.lbl_haptic)
        seekHaptic = findViewById(R.id.seek_haptic)

        val activeHaptic = prefs.getInt("haptic_strength", CustomKeyboardView.DEFAULT_HAPTIC_MS)
        seekHaptic.progress = activeHaptic
        lblHaptic.text = "Vibration strength: ${activeHaptic}ms"

        seekHaptic.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lblHaptic.text = "Vibration strength: ${progress}ms"
                prefs.edit().putInt("haptic_strength", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                triggerTactileSample(seekHaptic.progress)
            }
        })

        // Clipboard Manager
        // Translation: the offline model ships in the APK; the server is an opt-in extra.
        val modelStatus: TextView = findViewById(R.id.text_translate_model_status)
        modelStatus.text = try {
            val bytes = listOf("translate/encoder.onnx", "translate/decoder.onnx").sumOf { assets.openFd(it).use { fd -> fd.length } }
            "Installed, %.0f MB. Works without internet.".format(bytes / 1_000_000.0)
        } catch (e: Exception) {
            "Not included in this build"
        }
        val switchOnline: SwitchMaterial = findViewById(R.id.switch_translate_online)
        switchOnline.isChecked = prefs.getBoolean("translate_auto_online", false)
        switchOnline.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("translate_auto_online", checked).apply()
        }
        val serverField: android.widget.EditText = findViewById(R.id.edit_translate_server)
        serverField.setText(prefs.getString("translate_server_url", ""))
        serverField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString("translate_server_url", s?.toString()?.trim().orEmpty()).apply()
            }
        })

        findViewById<LinearLayout>(R.id.btn_clear_clipboard).setOnClickListener {
            performHapticClick()
            prefs.edit().putString("clipboard_history", "").apply()
            Toast.makeText(this, "Clipboard history cleared!", Toast.LENGTH_SHORT).show()
        }

        // Direct wallpaper picker trigger if requested from keyboard toolbar
        if (intent?.getBooleanExtra("ACTION_PICK_WALLPAPER", false) == true) {
            val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            startActivityForResult(pickIntent, RC_PICK_WALLPAPER)
        }
    }

    private fun performHapticClick() {
        val hapticStrength = prefs.getInt("haptic_strength", CustomKeyboardView.DEFAULT_HAPTIC_MS)
        triggerTactileSample(hapticStrength)
    }

    private fun triggerTactileSample(durationMs: Int) {
        if (durationMs <= 0) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), 200))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs.toLong())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PICK_WALLPAPER && resultCode == RESULT_OK && data != null) {
            val uri: Uri = data.data ?: return
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream.close()

                    if (originalBitmap != null) {
                        // Crop and scale to optimal keyboard aspect ratio (target 1080x540 max, ~2:1 ratio)
                        val targetW = Math.min(originalBitmap.width, 1080)
                        val targetH = (targetW * 0.5f).toInt()
                        
                        val bw = originalBitmap.width.toFloat()
                        val bh = originalBitmap.height.toFloat()
                        val scale = Math.max(targetW / bw, targetH / bh)
                        val scaledW = (bw * scale).toInt()
                        val scaledH = (bh * scale).toInt()

                        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, scaledW, scaledH, true)
                        val cropX = Math.max(0, (scaledW - targetW) / 2)
                        val cropY = Math.max(0, (scaledH - targetH) / 2)
                        val croppedBitmap = android.graphics.Bitmap.createBitmap(scaledBitmap, cropX, cropY, targetW, targetH)

                        val wallpaperFile = File(filesDir, "custom_wallpaper.jpg")
                        val outputStream = FileOutputStream(wallpaperFile)
                        croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, outputStream)
                        outputStream.close()

                        prefs.edit().putString("selected_theme", "theme_custom").apply()
                        findViewById<Spinner>(R.id.spinner_theme).setSelection(THEME_VALUES.indexOf("theme_custom"))

                        Toast.makeText(this, "Custom wallpaper applied & framed for keyboard!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to process wallpaper: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
