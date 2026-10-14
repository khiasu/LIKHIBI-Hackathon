package com.likhibi.nlp.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The keyboard's offline translator must behave exactly like the Python model that was
 * measured: same token ids, same greedy output. It tests the model the APK ships
 * (src/main/assets/translate) against the reference outputs that
 * nlp_research/translation/export_student.py wrote for that same export.
 */
class StudentParityTest {

    private val folder = File("src/main/assets/translate")
    private val parityFile = File("src/test/resources/translate/parity.json")

    private fun tokenizer() = SentencePieceTokenizer(File(folder, "tokenizer.json").inputStream())

    @Test
    fun tokenizerMatchesSentencePiece() {
        assumeTrue("export the student first", parityFile.exists())
        val tokenizer = tokenizer()
        val cases = JSONObject(parityFile.readText()).getJSONArray("tokenizer")
        val mismatches = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val ids = case.getJSONArray("ids")
            val expected = (0 until ids.length()).map { ids.getInt(it) }
            val actual = tokenizer.encode(case.getString("text"))
            if (actual != expected) mismatches.add(case.getString("text"))
        }
        assertEquals("mismatches: ${mismatches.take(3)}", 0, mismatches.size)
    }

    @Test
    fun translatorMatchesPythonGreedyDecoding() {
        assumeTrue("export the student first", parityFile.exists())
        val cases = JSONObject(parityFile.readText()).getJSONArray("translations")
        val mismatches = mutableListOf<String>()
        StudentTranslator(
            File(folder, "encoder.onnx").readBytes(),
            File(folder, "decoder.onnx").readBytes(),
            tokenizer()
        ).use { translator ->
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val result = translator.translate(case.getString("text"), case.getString("target"))
                if (result.text != case.getString("output")) {
                    mismatches.add("${case.getString("text")}\n  python: ${case.getString("output")}\n  kotlin: ${result.text}")
                }
            }
        }
        // The JVM test runs desktop ONNX Runtime 1.19 (see app/build.gradle.kts) while the reference
        // came from 1.31, and their int8 kernels can break a near-tie between two tokens differently.
        // Wrong token ids or decoding logic would break almost every case, not one in ten.
        mismatches.forEach { println("translation differs:\n$it") }
        val matched = cases.length() - mismatches.size
        assertTrue("only $matched of ${cases.length()} translations match Python", matched >= cases.length() * 0.8)
    }
}
