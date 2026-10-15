package com.likhibi.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text copied while the keyboard is open. Unpinned clips are removed after an hour (the
 * clipboard panel promises this); pinned clips stay until the person deletes them.
 */
class ClipboardStore(context: Context) {
    data class Clip(val text: String, val time: Long, val pinned: Boolean)

    private val prefs = KeyboardThemes.prefs(context)

    var enabled: Boolean
        get() = prefs.getBoolean("clipboard_enabled", true)
        set(value) = prefs.edit().putBoolean("clipboard_enabled", value).apply()

    /** Pinned clips first, then the newest; expired clips are dropped. */
    fun clips(now: Long = System.currentTimeMillis()): List<Clip> {
        val all = read()
        val kept = all.filter { it.pinned || now - it.time < EXPIRY_MS }
        if (kept.size != all.size) write(kept)
        return kept.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })
    }

    fun add(text: String, now: Long = System.currentTimeMillis()) {
        if (!enabled || text.isBlank()) return
        val clips = read()
        val pinned = clips.any { it.text == text && it.pinned }
        val rest = clips.filter { it.text != text }
        val unpinned = rest.filter { !it.pinned }.sortedByDescending { it.time }.take(MAX_UNPINNED - 1)
        write(rest.filter { it.pinned } + unpinned + Clip(text, now, pinned))
    }

    fun remove(text: String) = write(read().filter { it.text != text })

    fun togglePin(text: String) = write(read().map { if (it.text == text) it.copy(pinned = !it.pinned) else it })

    fun clear() = write(emptyList())

    private fun read(): List<Clip> {
        val legacy = prefs.getString("clipboard_history", null)
        if (legacy != null) {
            // Earlier versions kept plain text without times; treat those clips as copied now.
            val now = System.currentTimeMillis()
            val texts = legacy.split("[LIKHIBI_SPLIT]").filter { it.isNotBlank() }
            val clips = texts.mapIndexed { i, t -> Clip(t, now - (texts.size - i), false) }
            prefs.edit().remove("clipboard_history").apply()
            write(clips)
            return clips
        }
        val json = prefs.getString("clipboard_items", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Clip(o.getString("text"), o.getLong("time"), o.optBoolean("pinned"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(clips: List<Clip>) {
        val array = JSONArray()
        for (c in clips) array.put(JSONObject().put("text", c.text).put("time", c.time).put("pinned", c.pinned))
        prefs.edit().putString("clipboard_items", array.toString()).apply()
    }

    companion object {
        const val EXPIRY_MS = 60 * 60 * 1000L
        private const val MAX_UNPINNED = 20

        /** "now", "2 min", "1 h": how long ago a clip was copied. */
        fun age(time: Long, now: Long = System.currentTimeMillis()): String {
            val minutes = ((now - time) / 60_000).coerceAtLeast(0)
            return when {
                minutes < 1 -> "now"
                minutes < 60 -> "$minutes min"
                minutes < 24 * 60 -> "${minutes / 60} h"
                else -> "${minutes / (24 * 60)} d"
            }
        }
    }
}
