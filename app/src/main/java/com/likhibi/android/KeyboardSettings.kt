package com.likhibi.android

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.likhibi.keyboard.R
import java.util.concurrent.Executors

/**
 * Every setting the keyboard reads while typing, loaded once when settings change, so a key
 * press never touches preferences.
 */
data class KeyboardSettings(
    val theme: KeyboardTheme,
    val figtree: Boolean,
    val keyHeightDp: Float,
    val rowGapDp: Float,
    val numberRow: Boolean,
    val emojiKey: Boolean,
    val keyPopup: Boolean,
    val longPressSymbols: Boolean,
    val hapticOn: Boolean,
    val hapticMs: Int,
    val soundOn: Boolean,
    val soundVolume: Float,
    val autoCapitalise: Boolean,
    val doubleSpacePeriod: Boolean,
    val nextWordSuggestions: Boolean,
    val oneHanded: String,
) {
    companion object {
        const val DEFAULT_HAPTIC_MS = 10
        const val DEFAULT_SOUND = false

        /** Key height and the gap between rows for Short, Normal and Tall. */
        fun heights(setting: String?): Pair<Float, Float> = when (setting) {
            "short" -> 42f to 8f
            "tall" -> 52f to 12f
            else -> 46f to 10f
        }

        fun load(context: Context): KeyboardSettings {
            val p = KeyboardThemes.prefs(context)
            val (keyHeight, rowGap) = heights(p.getString("keyboard_height", "normal"))
            return KeyboardSettings(
                theme = KeyboardThemes.current(context),
                figtree = KeyboardThemes.usesFigtree(p),
                keyHeightDp = keyHeight,
                rowGapDp = rowGap,
                numberRow = p.getBoolean("show_number_row", false),
                emojiKey = p.getBoolean("show_emoji_key", true),
                keyPopup = p.getBoolean("key_popup", true),
                longPressSymbols = p.getBoolean("long_press_symbols", true),
                hapticOn = p.getBoolean("haptic_enabled", true),
                hapticMs = p.getInt("haptic_strength", DEFAULT_HAPTIC_MS),
                soundOn = p.getBoolean("sound_enabled", DEFAULT_SOUND),
                soundVolume = p.getFloat("sound_volume", 0.5f),
                autoCapitalise = p.getBoolean("auto_capitalise", true),
                doubleSpacePeriod = p.getBoolean("double_space_period", true),
                nextWordSuggestions = p.getBoolean("next_word_suggestions", true),
                oneHanded = p.getString("one_handed_mode", "off") ?: "off",
            )
        }
    }
}

/**
 * Key-press vibration and sound. Vibration is sent from a background thread, so a slow
 * vibrator service can never hold up the next key.
 */
class KeyFeedback(context: Context) {
    enum class Kind { STANDARD, SPACE, FUNCTION, ENTER }

    private val app = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "likhibi-feedback").apply { isDaemon = true } }
    private val effects = HashMap<Long, VibrationEffect>()
    @Volatile private var soundPool: SoundPool? = null
    private var click = 0
    private var space = 0

    init {
        // Loading the sounds takes a moment; do it now so the first key does not wait.
        worker.execute {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            click = pool.load(app, R.raw.key_click, 1)
            space = pool.load(app, R.raw.key_space, 1)
            soundPool = pool
        }
    }

    fun press(kind: Kind, settings: KeyboardSettings) {
        if (settings.hapticOn && settings.hapticMs > 0 && vibrator != null) {
            val ms = when (kind) {
                Kind.STANDARD -> settings.hapticMs.toLong()
                Kind.SPACE, Kind.FUNCTION -> (settings.hapticMs * 1.2).toLong()
                Kind.ENTER -> (settings.hapticMs * 1.4).toLong()
            }
            val effect = synchronized(effects) { effects.getOrPut(ms) { VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE) } }
            val v = vibrator
            runCatching { worker.execute { runCatching { v.vibrate(effect) } } }
        }
        if (settings.soundOn) {
            val pool = soundPool ?: return
            val volume = settings.soundVolume * 0.35f
            pool.play(if (kind == Kind.SPACE) space else click, volume, volume, 1, 0, if (kind == Kind.SPACE) 0.75f else 0.85f)
        }
    }

    fun release() {
        runCatching { worker.execute { soundPool?.release() } }
        worker.shutdown()
    }
}
