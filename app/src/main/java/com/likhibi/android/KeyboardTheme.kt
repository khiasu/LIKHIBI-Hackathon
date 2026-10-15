package com.likhibi.android

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import com.likhibi.keyboard.R
import java.io.File

/** One keyboard theme: the Figma "Keyboard Theme" tokens (kb/...) for one mode. */
data class KeyboardTheme(
    val id: String,
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val keyFunction: Int,
    val keyLabel: Int,
    val keyLabelSecondary: Int,
    val keyBorder: Int,
    val accent: Int,
    val onAccent: Int,
    val strip: Int,
    val stripLabel: Int,
    val keyRadiusDp: Float,
    val keyBorderWidthDp: Float,
    val keyGapDp: Float = 6f,
    val usesPhoto: Boolean = false,
    /** Black overlay drawn over the photo (Photo mode only). */
    val photoDim: Int = Color.TRANSPARENT,
)

object KeyboardThemes {
    const val PREFS = "likhibi_keyboard_prefs"

    const val LIGHT = "theme_light"
    const val DARK = "theme_dark"
    const val NAGA_HERITAGE = "theme_naga_heritage"
    const val HIGH_CONTRAST = "theme_high_contrast"
    const val BORDERLESS = "theme_borderless"
    const val PHOTO = "theme_photo"
    const val CUSTOM = "theme_custom"
    const val SYSTEM = "theme_system"

    const val DEFAULT_THEME = NAGA_HERITAGE

    const val FONT_FIGTREE = "figtree"
    const val FONT_SYSTEM = "system"

    const val PHOTO_FILE = "custom_wallpaper.jpg"
    val PHOTO_PLACEHOLDER = Color.parseColor("#6B7A66")
    private val PHOTO_DIM = Color.argb(97, 0, 0, 0)  // kb/photo-dim, black at 38%

    /** Accent swatches for a custom theme (Figma collection "Accent Options"). */
    val ACCENTS = listOf(
        "Slate blue" to Color.parseColor("#2E5C88"),
        "Deep red" to Color.parseColor("#8B2A2A"),
        "Forest" to Color.parseColor("#3E6B4F"),
        "Ochre" to Color.parseColor("#A8741E"),
        "Teal" to Color.parseColor("#2A6F73"),
        "Charcoal" to Color.parseColor("#3A3F47"),
    )

    /** Theme ids with their display names, in gallery order. */
    val NAMES = linkedMapOf(
        SYSTEM to "System",
        LIGHT to "Light",
        DARK to "Dark",
        NAGA_HERITAGE to "Naga Heritage",
        HIGH_CONTRAST to "High contrast",
        BORDERLESS to "Borderless",
        PHOTO to "Photo",
        CUSTOM to "My theme",
    )

    private fun c(hex: String) = Color.parseColor(hex)
    private fun white(alphaPercent: Int) = Color.argb(alphaPercent * 255 / 100, 255, 255, 255)
    private fun black(alphaPercent: Int) = Color.argb(alphaPercent * 255 / 100, 0, 0, 0)

    val light = KeyboardTheme(
        id = LIGHT, background = c("#E4E7EB"), key = c("#FAFBFC"), keyPressed = c("#CDD2D9"),
        keyFunction = c("#CBD0D7"), keyLabel = c("#1E2227"), keyLabelSecondary = c("#6A717B"),
        keyBorder = c("#C5CAD1"), accent = c("#2E5C88"), onAccent = c("#F6F8FA"),
        strip = c("#E4E7EB"), stripLabel = c("#1E2227"), keyRadiusDp = 8f, keyBorderWidthDp = 0f,
    )
    val dark = KeyboardTheme(
        id = DARK, background = c("#17181B"), key = c("#2C2E33"), keyPressed = c("#4A4D55"),
        keyFunction = c("#222428"), keyLabel = c("#E9EAEC"), keyLabelSecondary = c("#8D929B"),
        keyBorder = c("#3B3D43"), accent = c("#8DB3D8"), onAccent = c("#13171B"),
        strip = c("#17181B"), stripLabel = c("#E9EAEC"), keyRadiusDp = 8f, keyBorderWidthDp = 0f,
    )
    val nagaHeritage = KeyboardTheme(
        id = NAGA_HERITAGE, background = c("#EFE9DD"), key = c("#F9F6F0"), keyPressed = c("#DCD3C1"),
        keyFunction = c("#E3DBCB"), keyLabel = c("#121214"), keyLabelSecondary = c("#7B7567"),
        keyBorder = c("#CFC6B3"), accent = c("#8B2A2A"), onAccent = c("#F9F6F0"),
        strip = c("#EFE9DD"), stripLabel = c("#121214"), keyRadiusDp = 6f, keyBorderWidthDp = 1f,
    )
    val highContrast = KeyboardTheme(
        id = HIGH_CONTRAST, background = c("#000000"), key = c("#141414"), keyPressed = c("#3A3A3A"),
        keyFunction = c("#2B2B2B"), keyLabel = c("#FFFFFF"), keyLabelSecondary = c("#D0D0D0"),
        keyBorder = c("#FFFFFF"), accent = c("#FFD84D"), onAccent = c("#000000"),
        strip = c("#000000"), stripLabel = c("#FFFFFF"), keyRadiusDp = 6f, keyBorderWidthDp = 2f,
    )
    val borderless = KeyboardTheme(
        id = BORDERLESS, background = c("#ECEEF0"), key = c("#ECEEF0"), keyPressed = c("#D6DADF"),
        keyFunction = c("#ECEEF0"), keyLabel = c("#1E2227"), keyLabelSecondary = c("#6A717B"),
        keyBorder = c("#ECEEF0"), accent = c("#2E5C88"), onAccent = c("#F6F8FA"),
        strip = c("#ECEEF0"), stripLabel = c("#1E2227"), keyRadiusDp = 8f, keyBorderWidthDp = 0f,
    )
    val photo = KeyboardTheme(
        id = PHOTO, background = Color.TRANSPARENT, key = white(22), keyPressed = white(40),
        keyFunction = black(32), keyLabel = c("#F7F7F5"), keyLabelSecondary = white(70),
        keyBorder = white(30), accent = c("#E0BC8A"), onAccent = c("#1B1510"),
        strip = black(28), stripLabel = c("#F7F7F5"), keyRadiusDp = 10f, keyBorderWidthDp = 0f,
        usesPhoto = true, photoDim = PHOTO_DIM,
    )

    /** Bases a custom theme can start from, as shown in the theme editor. */
    val CUSTOM_BASES = listOf(
        "light" to light, "dark" to dark, "naga" to nagaHeritage, "high_contrast" to highContrast, "photo" to photo,
    )

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedId(prefs: SharedPreferences): String = prefs.getString("selected_theme", DEFAULT_THEME) ?: DEFAULT_THEME

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The theme the keyboard should draw now. */
    fun current(context: Context): KeyboardTheme = forId(context, selectedId(prefs(context)))

    fun forId(context: Context, id: String): KeyboardTheme = when (id) {
        LIGHT -> light
        DARK -> dark
        NAGA_HERITAGE -> nagaHeritage
        HIGH_CONTRAST -> highContrast
        BORDERLESS -> borderless
        PHOTO -> photo
        CUSTOM -> custom(prefs(context), CustomSettings.load(prefs(context)))
        SYSTEM -> if (isNight(context)) dark else light
        else -> nagaHeritage
    }

    /** A custom theme: one of the bases, with the person's accent, corners, borders and opacity. */
    fun custom(prefs: SharedPreferences, s: CustomSettings): KeyboardTheme {
        val base = CUSTOM_BASES.firstOrNull { it.first == s.base }?.second ?: light
        val opacity = s.keycapOpacity.coerceIn(0, 100) / 100f
        fun scaled(color: Int) = Color.argb((Color.alpha(color) * opacity).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        val dimAlpha = ((100 - s.photoBrightness.coerceIn(0, 100)) / 100f * 0.76f * 255).toInt()
        return base.copy(
            id = CUSTOM,
            key = scaled(base.key),
            keyFunction = scaled(base.keyFunction),
            accent = s.accent,
            onAccent = onColor(s.accent),
            keyRadiusDp = s.cornerRadiusDp,
            keyBorderWidthDp = if (s.keyBorders) maxOf(base.keyBorderWidthDp, 1f) else 0f,
            photoDim = if (base.usesPhoto) Color.argb(dimAlpha, 0, 0, 0) else Color.TRANSPARENT,
        )
    }

    /** Readable text on a fill: near-black on light fills, near-white on dark ones. */
    fun onColor(fill: Int): Int {
        val luminance = (0.2126 * Color.red(fill) + 0.7152 * Color.green(fill) + 0.0722 * Color.blue(fill)) / 255
        return if (luminance > 0.55) Color.parseColor("#13171B") else Color.parseColor("#F6F8FA")
    }

    /** The whole keyboard's backdrop: a flat colour, or the chosen photo with its dimming layer. */
    fun backgroundDrawable(context: Context, theme: KeyboardTheme): Drawable {
        if (!theme.usesPhoto) return ColorDrawable(theme.background)
        val file = File(context.filesDir, PHOTO_FILE)
        val bitmap = if (file.exists()) runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() else null
        return if (bitmap != null) CenterCropWallpaperDrawable(bitmap, theme.photoDim) else ColorDrawable(PHOTO_PLACEHOLDER)
    }

    fun hasPhoto(context: Context): Boolean = File(context.filesDir, PHOTO_FILE).exists()

    /** Key height for the person's keyboard height setting (Short, Normal, Tall). */
    fun keyHeightDp(prefs: SharedPreferences): Float = when (prefs.getString("keyboard_height", "normal")) {
        "short" -> 40f
        "tall" -> 52f
        else -> 46f
    }

    fun usesFigtree(prefs: SharedPreferences): Boolean = prefs.getString("selected_font", FONT_FIGTREE) != FONT_SYSTEM

    /** The keyboard's typefaces: Figtree (bundled) unless the person chose the system font. */
    class Fonts(context: Context, figtree: Boolean) {
        val regular: Typeface
        val medium: Typeface
        val semibold: Typeface

        init {
            val res = { id: Int -> runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() }
            val f = if (figtree) Triple(res(R.font.figtree_regular), res(R.font.figtree_medium), res(R.font.figtree_semibold)) else Triple(null, null, null)
            regular = f.first ?: Typeface.create("sans-serif", Typeface.NORMAL)
            medium = f.second ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
            semibold = f.third ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }

    /**
     * Moves settings saved by earlier versions to the current names, once. Old theme ids map
     * to the nearest designed mode so nobody's keyboard changes unrecognisably on update.
     */
    fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs.getInt("settings_schema", 1) >= 2) return
        val edit = prefs.edit()
        val oldTheme = prefs.getString("selected_theme", null)
        val newTheme = when (oldTheme) {
            null -> null
            "theme_chalk_light", "theme_pure_minimal", "theme_material_you", "theme_nordic_frost" -> LIGHT
            "theme_slate_dark", "theme_midnight_glass", "theme_liquid_glass", "theme_obsidian_oled" -> DARK
            in NAMES.keys -> oldTheme
            else -> DEFAULT_THEME
        }
        if (newTheme != null) edit.putString("selected_theme", newTheme)

        // Old custom themes drew dark keys over a photo.
        if (prefs.contains("custom_key_opacity") || oldTheme == CUSTOM) {
            val dim = prefs.getInt("custom_overlay_opacity", 140).coerceIn(0, 240) / 255f
            edit.putString("custom_base", if (hasPhoto(context)) "photo" else "dark")
            edit.putInt("custom_photo_brightness", (100 * (1 - dim / 0.76f)).toInt().coerceIn(0, 100))
            edit.putBoolean("custom_saved", true)
        }

        // The old default font was the system sans-serif; anyone who picked another font chose the system look.
        val oldFont = prefs.getString("selected_font", null)
        if (oldFont != null && oldFont != FONT_FIGTREE && oldFont != FONT_SYSTEM) {
            edit.putString("selected_font", if (oldFont == "sans-serif") FONT_FIGTREE else FONT_SYSTEM)
        }
        if (prefs.contains("haptic_strength") && prefs.getInt("haptic_strength", 10) == 0) {
            edit.putBoolean("haptic_enabled", false).putInt("haptic_strength", KeyboardSettings.DEFAULT_HAPTIC_MS)
        }
        edit.putInt("settings_schema", 2).apply()
    }
}

/** What the theme editor saves for "My theme". */
data class CustomSettings(
    val base: String = "light",
    val photoBrightness: Int = 50,
    val keyBorders: Boolean = false,
    val cornerRadiusDp: Float = 8f,
    val keycapOpacity: Int = 100,
    val accent: Int = KeyboardThemes.ACCENTS.first().second,
) {
    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("custom_base", base)
            .putInt("custom_photo_brightness", photoBrightness)
            .putBoolean("custom_key_borders", keyBorders)
            .putFloat("custom_corner_radius", cornerRadiusDp)
            .putInt("custom_keycap_opacity", keycapOpacity)
            .putInt("custom_accent_color", accent)
            .putBoolean("custom_saved", true)
            .apply()
    }

    companion object {
        fun load(prefs: SharedPreferences): CustomSettings {
            val d = CustomSettings()
            return CustomSettings(
                base = prefs.getString("custom_base", d.base) ?: d.base,
                photoBrightness = prefs.getInt("custom_photo_brightness", d.photoBrightness),
                keyBorders = prefs.getBoolean("custom_key_borders", d.keyBorders),
                cornerRadiusDp = prefs.getFloat("custom_corner_radius", d.cornerRadiusDp),
                keycapOpacity = prefs.getInt("custom_keycap_opacity", d.keycapOpacity),
                accent = prefs.getInt("custom_accent_color", d.accent),
            )
        }
    }
}

/**
 * CenterCropWallpaperDrawable scales and crops a bitmap to fill the view bounds
 * without distortion and without reporting intrinsic width/height.
 */
class CenterCropWallpaperDrawable(
    private val bitmap: android.graphics.Bitmap,
    private val overlayColor: Int,
) : android.graphics.drawable.Drawable() {
    private val drawMatrix = android.graphics.Matrix()
    private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = android.graphics.Paint().apply {
        color = overlayColor
        style = android.graphics.Paint.Style.FILL
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f || bitmap.isRecycled) return
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = maxOf(w / bw, h / bh)
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(bounds.left + (w - bw * scale) * 0.5f, bounds.top + (h - bh * scale) * 0.5f)
        canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(bitmap, drawMatrix, bitmapPaint)
        if (Color.alpha(overlayColor) > 0) canvas.drawRect(bounds, overlayPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        bitmapPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}
