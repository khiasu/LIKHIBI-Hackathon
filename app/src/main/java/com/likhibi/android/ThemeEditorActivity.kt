package com.likhibi.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.likhibi.keyboard.R
import com.likhibi.android.SettingsUi.Weight

/**
 * Theme editor (Figma 34:3067): builds "My theme" from a base, a photo, borders, corners,
 * keycap opacity and an accent, with a live preview. Nothing is saved until Apply.
 */
class ThemeEditorActivity : AppCompatActivity() {
    private val prefs by lazy { KeyboardThemes.prefs(this) }
    private var settings = CustomSettings()
    private var figtree = true
    private var keyboardHeight = "normal"

    private lateinit var preview: KeyboardPreviewView
    private val baseRings = mutableListOf<Pair<String, FrameLayout>>()
    private val accentRings = mutableListOf<Pair<Int, FrameLayout>>()
    private lateinit var brightnessLabel: TextView
    private lateinit var brightness: Slider
    private lateinit var radiusLabel: TextView
    private lateinit var radius: Slider
    private lateinit var opacityLabel: TextView
    private lateinit var opacity: Slider
    private lateinit var borders: SwitchMaterial
    private lateinit var selectFont: (Int) -> Unit
    private lateinit var selectHeight: (Int) -> Unit

    private val heights = listOf("short", "normal", "tall")

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && KeyboardPhoto.save(this, uri)) {
            settings = settings.copy(base = "photo")
            refresh()
        } else if (uri != null) {
            Toast.makeText(this, "That photo could not be opened", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyboardThemes.migrate(this)
        settings = CustomSettings.load(prefs)
        figtree = KeyboardThemes.usesFigtree(prefs)
        keyboardHeight = prefs.getString("keyboard_height", "normal") ?: "normal"

        val body = SettingsUi.screen(this, "Edit theme", actionBar())

        // 1. Live preview at half scale.
        val panel = FrameLayout(this).apply {
            background = SettingsUi.rounded(color(R.color.app_card), dp(6f).toFloat(), color(R.color.app_divider), dp(1f))
            setPadding(0, dp(12f), 0, dp(12f))
        }
        preview = KeyboardPreviewView(this).apply { cornerRadiusPx = dp(4f).toFloat() }
        panel.addView(preview, FrameLayout.LayoutParams(dp(206f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        body.addView(panel, matchWrap(top = 4f))

        // 2. Background: the bases, then Choose photo.
        body.addView(SettingsUi.label(this, "Background"), matchWrap(top = 14f))
        val bases = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val baseColors = listOf(
            "light" to ("Light" to KeyboardThemes.light.background),
            "dark" to ("Dark" to KeyboardThemes.dark.background),
            "naga" to ("Naga Heritage" to KeyboardThemes.nagaHeritage.background),
            "high_contrast" to ("High contrast" to KeyboardThemes.highContrast.background),
            "photo" to ("Photo" to KeyboardThemes.PHOTO_PLACEHOLDER),
        )
        for ((id, info) in baseColors) {
            val ring = swatch(info.second, info.first) {
                if (id == "photo" && !KeyboardThemes.hasPhoto(this)) pickPhoto.launch("image/*")
                else {
                    settings = settings.copy(base = id)
                    refresh()
                }
            }
            baseRings.add(id to ring)
            bases.addView(ring, LinearLayout.LayoutParams(dp(46f), dp(46f)))
        }
        bases.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bases.addView(tonalButton("Choose photo", R.drawable.ic_kb_image) { pickPhoto.launch("image/*") })
        body.addView(bases, matchWrap(top = 8f))

        // 3. Photo brightness (photo backgrounds only).
        brightnessLabel = sliderLabel()
        body.addView(brightnessLabel, matchWrap(top = 14f))
        brightness = SettingsUi.slider(this, 0f, 100f, settings.photoBrightness.toFloat(), "Photo brightness") {
            settings = settings.copy(photoBrightness = it.toInt())
            refresh()
        }
        body.addView(brightness, matchWrap())

        // 4. Key borders.
        val bordersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bordersRow.addView(SettingsUi.label(this, "Key borders"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        borders = SettingsUi.switch(this, settings.keyBorders, "Key borders") {
            settings = settings.copy(keyBorders = it)
            refresh()
        }
        bordersRow.addView(borders)
        body.addView(bordersRow, matchWrap(top = 6f))

        // 5. Key corner radius.
        radiusLabel = sliderLabel()
        body.addView(radiusLabel, matchWrap(top = 8f))
        radius = SettingsUi.slider(this, 0f, 16f, settings.cornerRadiusDp, "Key corner radius") {
            settings = settings.copy(cornerRadiusDp = it)
            refresh()
        }
        body.addView(radius, matchWrap())

        // 6. Keycap opacity.
        opacityLabel = sliderLabel()
        body.addView(opacityLabel, matchWrap(top = 8f))
        opacity = SettingsUi.slider(this, 10f, 100f, settings.keycapOpacity.toFloat(), "Keycap opacity") {
            settings = settings.copy(keycapOpacity = it.toInt())
            refresh()
        }
        body.addView(opacity, matchWrap())

        // 7. Accent colour.
        body.addView(SettingsUi.label(this, "Accent colour"), matchWrap(top = 14f))
        val accents = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, value) in KeyboardThemes.ACCENTS) {
            val ring = swatch(value, name) {
                settings = settings.copy(accent = value)
                refresh()
            }
            accentRings.add(value to ring)
            accents.addView(ring, LinearLayout.LayoutParams(dp(46f), dp(46f)))
        }
        body.addView(accents, matchWrap(top = 8f))

        // 8. Key label font.
        body.addView(SettingsUi.label(this, "Key label font"), matchWrap(top = 14f))
        val (fontControl, fontSelect) = SettingsUi.segmented(this, listOf("Figtree", "System"), if (figtree) 0 else 1) {
            figtree = it == 0
            refresh()
        }
        selectFont = fontSelect
        body.addView(fontControl, matchWrap(top = 8f))

        // 9. Keyboard height.
        body.addView(SettingsUi.label(this, "Keyboard height"), matchWrap(top = 14f))
        val (heightControl, heightSelect) = SettingsUi.segmented(this, listOf("Short", "Normal", "Tall"), heights.indexOf(keyboardHeight).coerceAtLeast(1)) {
            keyboardHeight = heights[it]
            refresh()
        }
        selectHeight = heightSelect
        body.addView(heightControl, matchWrap(top = 8f))

        refresh()
    }

    /** Reset and Apply, pinned to the bottom. */
    private fun actionBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        bar.addView(SettingsUi.divider(this))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(16f))
        }
        row.addView(SettingsUi.text(this, "Reset", 14f, Weight.MEDIUM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12f), 0, dp(12f), 0)
            background = SettingsUi.pressable(this@ThemeEditorActivity, null, dp(10f).toFloat())
            isClickable = true
            setOnClickListener {
                settings = CustomSettings()
                figtree = true
                keyboardHeight = "normal"
                syncControls()
                refresh()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
        row.addView(SettingsUi.text(this, "Apply", 14f, Weight.MEDIUM).apply {
            gravity = Gravity.CENTER
            background = SettingsUi.pressable(this@ThemeEditorActivity, SettingsUi.rounded(color(R.color.app_secondary_track), dp(10f).toFloat()), dp(10f).toFloat())
            isClickable = true
            setOnClickListener { applyTheme() }
        }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(8f) })
        bar.addView(row)
        return bar
    }

    private fun applyTheme() {
        settings.save(prefs)
        prefs.edit()
            .putString("selected_font", if (figtree) KeyboardThemes.FONT_FIGTREE else KeyboardThemes.FONT_SYSTEM)
            .putString("keyboard_height", keyboardHeight)
            .putString("selected_theme", KeyboardThemes.CUSTOM)
            .apply()
        Toast.makeText(this, "Theme applied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun syncControls() {
        brightness.value = settings.photoBrightness.toFloat()
        radius.value = settings.cornerRadiusDp.coerceIn(0f, 16f)
        opacity.value = settings.keycapOpacity.toFloat().coerceIn(10f, 100f)
        borders.isChecked = settings.keyBorders
        selectFont(if (figtree) 0 else 1)
        selectHeight(heights.indexOf(keyboardHeight).coerceAtLeast(0))
    }

    private fun refresh() {
        val theme = KeyboardThemes.custom(prefs, settings)
        preview.bind(theme, figtree, when (keyboardHeight) { "short" -> 40f; "tall" -> 52f; else -> 46f })
        val photo = settings.base == "photo"
        brightnessLabel.text = "Photo brightness  ${settings.photoBrightness}%"
        brightness.isEnabled = photo
        brightnessLabel.alpha = if (photo) 1f else 0.4f
        radiusLabel.text = "Key corner radius  ${settings.cornerRadiusDp.toInt()}"
        opacityLabel.text = "Keycap opacity  ${settings.keycapOpacity}%"
        for ((id, ring) in baseRings) markRing(ring, id == settings.base)
        for ((value, ring) in accentRings) markRing(ring, value == settings.accent)
    }

    private fun swatch(fill: Int, description: String, onClick: () -> Unit): FrameLayout {
        val ring = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
        ring.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(1f), color(R.color.app_outline))
            }
        }, FrameLayout.LayoutParams(dp(36f), dp(36f), Gravity.CENTER))
        return ring
    }

    private fun markRing(ring: FrameLayout, selected: Boolean) {
        ring.background = if (selected) GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2f), color(R.color.app_text))
        } else null
        ring.isSelected = selected
        val name = ring.contentDescription?.toString()?.removeSuffix(", selected").orEmpty()
        ring.contentDescription = name + if (selected) ", selected" else ""
    }

    private fun tonalButton(label: String, icon: Int, onClick: () -> Unit): View {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            background = SettingsUi.pressable(this@ThemeEditorActivity, SettingsUi.rounded(color(R.color.app_card), dp(10f).toFloat()), dp(10f).toFloat())
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            minimumHeight = dp(40f)
        }
        button.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(color(R.color.app_text))
        }, LinearLayout.LayoutParams(dp(16f), dp(16f)))
        button.addView(SettingsUi.text(this, label, 14f, Weight.MEDIUM).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) })
        return button
    }

    private fun sliderLabel(): TextView = SettingsUi.text(this, "", 14f, Weight.MEDIUM, R.color.app_text_secondary)

    private fun matchWrap(top: Float = 0f) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun dp(v: Float) = SettingsUi.dp(this, v)
    private fun color(id: Int) = SettingsUi.color(this, id)
}
