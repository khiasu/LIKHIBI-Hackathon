package com.likhibi.android

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.likhibi.keyboard.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Building blocks for the settings sub-screens (theme gallery, theme editor, preferences):
 * the App UI dark palette with Figtree type, as mapped in design/SPECS.md "Settings sub-screens".
 */
object SettingsUi {
    enum class Weight { REGULAR, MEDIUM, SEMIBOLD, BOLD }

    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun color(context: Context, id: Int): Int = ContextCompat.getColor(context, id)

    fun figtree(context: Context, weight: Weight): Typeface {
        val id = when (weight) {
            Weight.REGULAR -> R.font.figtree_regular
            Weight.MEDIUM -> R.font.figtree_medium
            Weight.SEMIBOLD -> R.font.figtree_semibold
            Weight.BOLD -> R.font.figtree_bold
        }
        return runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT
    }

    fun text(context: Context, value: CharSequence, sizeSp: Float, weight: Weight = Weight.REGULAR, colorRes: Int = R.color.app_text): TextView =
        TextView(context).apply {
            text = value
            typeface = figtree(context, weight)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color(context, colorRes))
        }

    fun rounded(fill: Int, radiusPx: Float, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(fill)
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    /** A flat pressed state (a light overlay), clipped to the given corners. */
    fun pressable(context: Context, content: Drawable?, radiusPx: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(color(context, R.color.app_pressed)), content, rounded(0xFFFFFFFF.toInt(), radiusPx))

    /**
     * A sub-screen: app bar (back, title) over a scrolling body, with an optional bar pinned
     * to the bottom. Returns the body column to fill.
     */
    fun screen(activity: Activity, title: String, footer: View? = null): LinearLayout {
        val c = activity
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(c, R.color.app_background))
            fitsSystemWindows = true
        }
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 0f), dp(c, 4f), dp(c, 16f), dp(c, 8f))
        }
        bar.addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_kb_chevron_left)
            setColorFilter(color(c, R.color.app_text))
            setPadding(dp(c, 16f), dp(c, 16f), dp(c, 16f), dp(c, 16f))
            contentDescription = "Back"
            background = pressable(c, null, dp(c, 24f).toFloat())
            setOnClickListener { activity.finish() }
        }, LinearLayout.LayoutParams(dp(c, 48f), dp(c, 48f)))
        bar.addView(text(c, title, 14f, Weight.BOLD).apply { ViewCompat.setAccessibilityHeading(this, true) })
        root.addView(bar)
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 16f), 0, dp(c, 16f), dp(c, 12f))
        }
        root.addView(ScrollView(c).apply { addView(body); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (footer != null) root.addView(footer)
        activity.setContentView(root)
        return body
    }

    fun sectionHeading(context: Context, title: String, first: Boolean): TextView =
        text(context, title, 12f, Weight.SEMIBOLD, R.color.app_text_secondary).apply {
            setPadding(0, dp(context, if (first) 8f else 16f), 0, dp(context, 4f))
            ViewCompat.setAccessibilityHeading(this, true)
        }

    fun label(context: Context, title: String): TextView = text(context, title, 14f, Weight.MEDIUM)

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(color(context, R.color.app_divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun switch(context: Context, checked: Boolean, description: String, onChange: (Boolean) -> Unit): SwitchMaterial =
        SwitchMaterial(context).apply {
            isChecked = checked
            contentDescription = description
            setUseMaterialThemeColors(false)
            thumbTintList = ContextCompat.getColorStateList(context, R.color.app_switch_thumb)
            trackTintList = ContextCompat.getColorStateList(context, R.color.app_switch_track)
            minimumWidth = dp(context, 48f)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    fun slider(context: Context, from: Float, to: Float, value: Float, description: String, onChange: (Float) -> Unit): Slider =
        Slider(context).apply {
            valueFrom = from
            valueTo = to
            stepSize = 1f
            this.value = value.coerceIn(from, to)
            contentDescription = description
            trackHeight = dp(context, 4f)
            thumbRadius = dp(context, 10f)
            haloRadius = dp(context, 18f)
            thumbElevation = 0f
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            isTickVisible = false
            trackActiveTintList = ColorStateList.valueOf(color(context, R.color.app_secondary_strong))
            trackInactiveTintList = ColorStateList.valueOf(color(context, R.color.app_track_off))
            thumbTintList = ColorStateList.valueOf(color(context, R.color.app_secondary))
            haloTintList = ColorStateList.valueOf(color(context, R.color.app_pressed))
            addOnChangeListener { _, v, fromUser -> if (fromUser) onChange(v) }
        }

    /**
     * A segmented control: equal segments on a dark track, the chosen one raised. Returns the
     * container; call the returned select function to change the selection from code.
     */
    fun segmented(context: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): Pair<LinearLayout, (Int) -> Unit> {
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 2f), dp(context, 2f), dp(context, 2f), dp(context, 2f))
            background = rounded(color(context, R.color.app_card), dp(context, 10f).toFloat())
        }
        val cells = options.map { option ->
            text(context, option, 14f, Weight.MEDIUM).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
            }
        }
        fun select(index: Int) {
            cells.forEachIndexed { i, cell ->
                val on = i == index
                cell.setTextColor(color(context, if (on) R.color.app_text else R.color.app_text_secondary))
                cell.background = if (on) rounded(color(context, R.color.app_outline), dp(context, 8f).toFloat()) else null
                cell.isSelected = on
                cell.contentDescription = options[i] + if (on) ", selected" else ""
            }
        }
        cells.forEachIndexed { i, cell ->
            cell.setOnClickListener {
                select(i)
                onSelect(i)
            }
            track.addView(cell, LinearLayout.LayoutParams(0, dp(context, 40f), 1f))
        }
        select(selected)
        return track to ::select
    }
}

/** Saves the photo chosen for the Photo and custom themes, cropped to the keyboard's shape. */
object KeyboardPhoto {
    fun save(context: Context, uri: Uri): Boolean = runCatching {
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return false
        val targetW = minOf(original.width, 1080)
        val targetH = (targetW * 0.75f).roundToInt()
        val scale = maxOf(targetW / original.width.toFloat(), targetH / original.height.toFloat())
        val scaled = Bitmap.createScaledBitmap(original, (original.width * scale).roundToInt().coerceAtLeast(targetW), (original.height * scale).roundToInt().coerceAtLeast(targetH), true)
        val cropped = Bitmap.createBitmap(scaled, (scaled.width - targetW) / 2, (scaled.height - targetH) / 2, targetW, targetH)
        FileOutputStream(File(context.filesDir, KeyboardThemes.PHOTO_FILE)).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        // The keyboard notices this key changing and redraws with the new photo.
        KeyboardThemes.prefs(context).edit().putLong("photo_version", System.currentTimeMillis()).apply()
        true
    }.getOrDefault(false)
}
