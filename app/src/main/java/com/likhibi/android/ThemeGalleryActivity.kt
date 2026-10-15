package com.likhibi.android

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.likhibi.keyboard.R
import com.likhibi.android.SettingsUi.Weight

/** Theme gallery (Figma 32:2036): live keyboard thumbnails, grouped; tap one to apply it. */
class ThemeGalleryActivity : AppCompatActivity() {
    private val prefs by lazy { KeyboardThemes.prefs(this) }
    private lateinit var body: LinearLayout

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && KeyboardPhoto.save(this, uri)) {
            apply(KeyboardThemes.PHOTO)
        } else if (uri != null) {
            Toast.makeText(this, "That photo could not be opened", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyboardThemes.migrate(this)
        body = SettingsUi.screen(this, "Themes")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun apply(id: String) {
        prefs.edit().putString("selected_theme", id).apply()
        render()
    }

    private fun render() {
        body.removeAllViews()
        val selected = KeyboardThemes.selectedId(prefs)
        val customSaved = prefs.getBoolean("custom_saved", false)
        val sections = listOf(
            "Default" to listOf(Tile.Theme(KeyboardThemes.SYSTEM), Tile.Theme(KeyboardThemes.LIGHT), Tile.Theme(KeyboardThemes.DARK)),
            "Colours" to listOf(Tile.Theme(KeyboardThemes.NAGA_HERITAGE), Tile.Theme(KeyboardThemes.HIGH_CONTRAST), Tile.Theme(KeyboardThemes.BORDERLESS)),
            "Landscapes / Photo" to listOf(Tile.Theme(KeyboardThemes.PHOTO), Tile.Action("Choose photo", R.drawable.ic_kb_image) { pickPhoto.launch("image/*") }),
            "Custom" to listOfNotNull(
                Tile.Theme(KeyboardThemes.CUSTOM).takeIf { customSaved },
                Tile.Action(if (customSaved) "Edit theme" else "New theme", if (customSaved) R.drawable.ic_kb_palette else R.drawable.ic_kb_plus) {
                    startActivity(Intent(this, ThemeEditorActivity::class.java))
                },
            ),
        )
        sections.forEachIndexed { i, (title, tiles) ->
            body.addView(SettingsUi.text(this, title, 14f, Weight.MEDIUM).apply {
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                setPadding(0, SettingsUi.dp(this@ThemeGalleryActivity, if (i == 0) 8f else 16f), 0, SettingsUi.dp(this@ThemeGalleryActivity, 8f))
            })
            for (row in tiles.chunked(3)) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0 until 3) {
                    val tile = row.getOrNull(k)
                    val view = when (tile) {
                        is Tile.Theme -> themeTile(tile.id, tile.id == selected)
                        is Tile.Action -> actionTile(tile)
                        null -> View(this)
                    }
                    line.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (k > 0) marginStart = SettingsUi.dp(this@ThemeGalleryActivity, 12f)
                    })
                }
                body.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = SettingsUi.dp(this@ThemeGalleryActivity, 12f)
                })
            }
        }
    }

    private sealed class Tile {
        data class Theme(val id: String) : Tile()
        data class Action(val label: String, val icon: Int, val onClick: () -> Unit) : Tile()
    }

    private fun thumbnailHeight(): Int {
        val width = resources.displayMetrics.widthPixels - SettingsUi.dp(this, 32f + 24f)
        return (width / 3f * 92f / 118.5f).toInt()
    }

    private fun themeTile(id: String, applied: Boolean): View {
        val name = KeyboardThemes.NAMES[id] ?: id
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = name + if (applied) ", applied" else ""
            setOnClickListener {
                if (applied && id == KeyboardThemes.CUSTOM) startActivity(Intent(this@ThemeGalleryActivity, ThemeEditorActivity::class.java))
                else apply(id)
            }
        }
        val radius = SettingsUi.dp(this, 6f).toFloat()
        val inset = SettingsUi.dp(this, if (applied) 2f else 1f)
        val frame = FrameLayout(this).apply {
            background = SettingsUi.rounded(
                SettingsUi.color(this@ThemeGalleryActivity, R.color.app_card), radius,
                SettingsUi.color(this@ThemeGalleryActivity, if (applied) R.color.app_text else R.color.app_divider), inset,
            )
            setPadding(inset, inset, inset, inset)
        }
        val preview = KeyboardPreviewView(this).apply {
            cornerRadiusPx = radius - inset
            bind(KeyboardThemes.forId(this@ThemeGalleryActivity, id))
        }
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tile.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbnailHeight()))
        tile.addView(caption(name, applied), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dp(this, 26f)))
        return tile
    }

    private fun actionTile(action: Tile.Action): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = action.label
            setOnClickListener { action.onClick() }
        }
        val box = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = SettingsUi.dp(this@ThemeGalleryActivity, 6f).toFloat()
                setStroke(SettingsUi.dp(this@ThemeGalleryActivity, 1f), SettingsUi.color(this@ThemeGalleryActivity, R.color.app_outline),
                    SettingsUi.dp(this@ThemeGalleryActivity, 4f).toFloat(), SettingsUi.dp(this@ThemeGalleryActivity, 3f).toFloat())
            }
            foreground = SettingsUi.pressable(this@ThemeGalleryActivity, null, SettingsUi.dp(this@ThemeGalleryActivity, 6f).toFloat())
        }
        box.addView(ImageView(this).apply {
            setImageResource(action.icon)
            setColorFilter(SettingsUi.color(this@ThemeGalleryActivity, R.color.app_text_secondary))
        }, FrameLayout.LayoutParams(SettingsUi.dp(this, 20f), SettingsUi.dp(this, 20f), Gravity.CENTER))
        tile.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbnailHeight()))
        tile.addView(caption(action.label, false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dp(this, 26f)))
        return tile
    }

    private fun caption(name: String, applied: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, SettingsUi.dp(this@ThemeGalleryActivity, 6f), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        row.addView(SettingsUi.text(this, name, 12f, Weight.MEDIUM).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (applied) row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_kb_check)
            setColorFilter(SettingsUi.color(this@ThemeGalleryActivity, R.color.app_text))
        }, LinearLayout.LayoutParams(SettingsUi.dp(this, 14f), SettingsUi.dp(this, 14f)))
        return row
    }
}
