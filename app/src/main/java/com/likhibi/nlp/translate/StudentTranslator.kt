package com.likhibi.nlp.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

/**
 * Offline English <-> Nagamese translation with the distilled student model (21M
 * parameters, int8), exported by nlp_research/translation/export_student.py.
 *
 * Greedy decoding, exactly as the Python engine (nlp_research/translation/engines/student.py)
 * does it, so the scores measured there hold here:
 *   source = [tag of the target language] + pieces(text)[:126] + [</s>]
 *   target = [<pad>], then the most likely next token until </s> or 2 x source + 10 tokens.
 *
 * Takes model bytes rather than a Context so the same class runs in JVM unit tests.
 * Not thread-safe: call it from one background thread at a time.
 */
class StudentTranslator(
    encoderModel: ByteArray,
    decoderModel: ByteArray,
    val tokenizer: SentencePieceTokenizer,
) : AutoCloseable {

    data class Translation(val text: String, val millis: Long)

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val encoder = env.createSession(encoderModel, options)
    private val decoder = env.createSession(decoderModel, options)

    fun translate(text: String, targetLang: String, maxSourceTokens: Int = 128): Translation {
        val started = System.nanoTime()
        val pieces = tokenizer.encode(text).take(maxSourceTokens - 2)
        if (pieces.isEmpty()) return Translation("", 0)
        val source = LongArray(pieces.size + 2)
        source[0] = tokenizer.tagId(targetLang).toLong()
        pieces.forEachIndexed { i, id -> source[i + 1] = id.toLong() }
        source[source.size - 1] = tokenizer.eosId.toLong()
        val shape = longArrayOf(1, source.size.toLong())
        val mask = LongArray(source.size) { 1L }

        val output = ArrayList<Int>()
        OnnxTensor.createTensor(env, LongBuffer.wrap(source), shape).use { sourceTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                encoder.run(mapOf("input_ids" to sourceTensor, "attention_mask" to maskTensor)).use { encoded ->
                    val states = encoded.get(0) as OnnxTensor
                    val target = arrayListOf(tokenizer.padId.toLong())
                    val limit = minOf(256, 2 * source.size + 10)
                    while (output.size < limit) {
                        val next = OnnxTensor.createTensor(env, LongBuffer.wrap(target.toLongArray()), longArrayOf(1, target.size.toLong())).use { prefix ->
                            decoder.run(
                                mapOf("decoder_input_ids" to prefix, "encoder_hidden_states" to states, "encoder_attention_mask" to maskTensor)
                            ).use { result -> argmax((result.get(0) as OnnxTensor).floatBuffer, repeatedTrigramCompletions(output)) }
                        }
                        if (next == tokenizer.eosId) break
                        output.add(next)
                        target.add(next.toLong())
                    }
                }
            }
        }
        return Translation(tokenizer.decode(output), (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * Tokens that would repeat a three-token sequence already in the output. Greedy decoding by a
     * small model can loop; banning repeated trigrams stops it (no_repeat_ngram_size=3), exactly as
     * repeated_trigram_completions() in export_student.py does.
     */
    private fun repeatedTrigramCompletions(generated: List<Int>): Set<Int> {
        if (generated.size < 2) return emptySet()
        val a = generated[generated.size - 2]
        val b = generated[generated.size - 1]
        val banned = HashSet<Int>()
        for (i in 0 until generated.size - 2) {
            if (generated[i] == a && generated[i + 1] == b) banned.add(generated[i + 2])
        }
        return banned
    }

    private fun argmax(scores: java.nio.FloatBuffer, banned: Set<Int>): Int {
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 0 until scores.remaining()) {
            val value = scores.get(i)
            if (value > bestScore && i !in banned) {
                bestScore = value
                best = i
            }
        }
        return best
    }

    override fun close() {
        encoder.close()
        decoder.close()
        options.close()
    }
}
