package com.likhibi.nlp.translate

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The online fallback: the Likhibi translation server (dashboard backend), which runs
 * the stronger models (fine-tuned NLLB, the LLM engine). POST {server}/api/translate,
 * the same JSON contract the dashboard uses. The keyboard only calls it when the person
 * asks for an online translation or has switched online translation on in Settings.
 */
class RemoteTranslator(private val serverUrl: String) {

    data class Translation(val text: String, val engineName: String, val millis: Long)

    class RemoteError(message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun translate(text: String, sourceLang: String, targetLang: String): Translation {
        val started = System.nanoTime()
        val body = JSONObject()
            .put("text", text)
            .put("source_lang", sourceLang)
            .put("target_lang", targetLang)
            .put("engine", "auto")
            .put("num_alternatives", 0)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(serverUrl.trimEnd('/') + "/api/translate").post(body).build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(payload) }.getOrNull()
            if (!response.isSuccessful || json == null) {
                val message = json?.optJSONObject("error")?.optString("message").orEmpty()
                throw RemoteError(message.ifEmpty { "The Likhibi server answered HTTP ${response.code}." })
            }
            return Translation(
                text = json.optString("translation").trim(),
                engineName = json.optJSONObject("engine")?.optString("name").orEmpty().ifEmpty { "server" },
                millis = (System.nanoTime() - started) / 1_000_000,
            )
        }
    }
}
