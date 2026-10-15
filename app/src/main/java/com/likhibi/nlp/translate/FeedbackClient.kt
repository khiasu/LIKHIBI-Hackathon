package com.likhibi.nlp.translate

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Translation corrections from the keyboard, for people who chose to contribute. Each one is
 * kept on the phone until the Likhibi server (POST {server}/api/feedback, the dashboard's own
 * contract) accepts it, so nothing is lost offline. On the server a verified reviewer
 * approves or rejects it; approved ones teach the translator.
 */
class FeedbackClient(context: Context) {
    data class Correction(
        val sourceLang: String,
        val targetLang: String,
        val sourceText: String,
        val machineTranslation: String?,
        val correctedTranslation: String,
    )

    private val prefs = context.getSharedPreferences("likhibi_keyboard_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    val enabled: Boolean get() = prefs.getBoolean("contribute_feedback", false)
    val pending: Int get() = queue().length()

    /** Keeps the correction on the phone; [flush] sends it. */
    fun add(correction: Correction) {
        val item = JSONObject()
            .put("source_lang", correction.sourceLang)
            .put("target_lang", correction.targetLang)
            .put("source_text", correction.sourceText.take(5000))
            .put("machine_translation", correction.machineTranslation?.take(5000) ?: JSONObject.NULL)
            .put("corrected_translation", correction.correctedTranslation.take(5000))
            .put("rating", "down")
            .put("engine_id", "likhibi-phone")
            .put("origin", "keyboard")
            .put("share_consent", true)
        prefs.getString("contributor_name", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { item.put("contributor", it.take(80)) }
        prefs.getString("contributor_variety", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { item.put("variety", it.take(60)) }
        synchronized(LOCK) {
            val queue = queue()
            queue.put(item)
            while (queue.length() > MAX_QUEUE) queue.remove(0)
            prefs.edit().putString(KEY, queue.toString()).apply()
        }
    }

    /**
     * Sends what is waiting. Blocking: call it off the main thread. Returns how many were
     * accepted; stops at the first network failure and keeps the rest for next time.
     */
    fun flush(): Int {
        val server = prefs.getString("translate_server_url", "")?.trim()?.trimEnd('/').orEmpty()
        if (server.isEmpty()) return 0
        var sent = 0
        while (true) {
            val item = synchronized(LOCK) { queue().optJSONObject(0) } ?: break
            val request = Request.Builder()
                .url("$server/api/feedback")
                .post(item.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val outcome = runCatching { client.newCall(request).execute().use { it.code } }.getOrNull() ?: break
            // 2xx: accepted. 4xx: the server will never take it (invalid), so drop it too.
            if (outcome in 500..599) break
            synchronized(LOCK) {
                val queue = queue()
                if (queue.length() > 0) queue.remove(0)
                prefs.edit().putString(KEY, queue.toString()).apply()
            }
            if (outcome in 200..299) sent++
        }
        return sent
    }

    private fun queue(): JSONArray = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())

    companion object {
        private const val KEY = "feedback_queue"
        private const val MAX_QUEUE = 100
        private val LOCK = Any()
    }
}
