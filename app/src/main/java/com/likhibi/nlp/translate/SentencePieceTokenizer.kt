package com.likhibi.nlp.translate

import org.json.JSONObject
import java.io.InputStream
import java.text.Normalizer

/**
 * SentencePiece unigram tokenizer in plain Kotlin, so the keyboard needs no native library.
 *
 * It reads tokenizer.json, written by nlp_research/translation/export_student.py: every
 * piece with its log-probability. Encoding normalises the text the way training did
 * (NFC, straight quotes, single spaces), marks word starts with "▁", and picks the
 * segmentation with the highest total score (Viterbi). A JVM test checks it against the
 * Python tokenizer's ids for 200 sentences.
 */
class SentencePieceTokenizer(json: InputStream) {

    val padId: Int
    val unkId: Int
    val eosId: Int
    private val tagIds: Map<String, Int>
    private val pieces: Array<String>
    private val ids = HashMap<String, Int>()
    private val scores = HashMap<String, Float>()
    private val maxPieceLength: Int
    private val unknownScore: Float

    init {
        val root = JSONObject(json.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val special = root.getJSONObject("special_ids")
        padId = special.getInt("pad")
        unkId = special.getInt("unk")
        eosId = special.getInt("eos")
        tagIds = mapOf("en" to special.getInt("en"), "nag" to special.getInt("nag"))
        val list = root.getJSONArray("pieces")
        pieces = Array(list.length()) { list.getJSONArray(it).getString(0) }
        var longest = 1
        var lowest = 0f
        for (i in 0 until list.length()) {
            val entry = list.getJSONArray(i)
            val score = entry.getDouble(1).toFloat()
            if (entry.getString(2) == "normal") {
                val piece = entry.getString(0)
                ids[piece] = i
                scores[piece] = score
                longest = maxOf(longest, piece.length)
                lowest = minOf(lowest, score)
            }
        }
        maxPieceLength = longest
        unknownScore = lowest - 10f  // SentencePiece's penalty for a character it has never seen
    }

    fun tagId(targetLang: String): Int = tagIds.getValue(targetLang)

    /** The same cleaning as nlp_research/translation/text.py clean(). */
    fun normalise(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val mapped = buildString(nfc.length) {
            for (ch in nfc) {
                append(
                    when (ch) {
                        '’', '‘' -> '\''
                        '“', '”' -> '"'
                        '–', '—' -> '-'
                        else -> ch
                    }
                )
            }
        }
        return mapped.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun encode(text: String): List<Int> {
        val clean = normalise(text)
        if (clean.isEmpty()) return emptyList()
        val s = "▁" + clean.replace(' ', '▁')
        val n = s.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val pieceAt = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (end in 1..n) {
            val start = maxOf(0, end - maxPieceLength)
            for (begin in start until end) {
                if (best[begin] == Float.NEGATIVE_INFINITY) continue
                val piece = s.substring(begin, end)
                val score = scores[piece] ?: continue
                val total = best[begin] + score
                if (total > best[end]) {
                    best[end] = total
                    from[end] = begin
                    pieceAt[end] = ids.getValue(piece)
                }
            }
            // A character no piece covers becomes <unk>, at a heavy cost, so text never fails to encode.
            if (best[end - 1] != Float.NEGATIVE_INFINITY && best[end - 1] + unknownScore > best[end]) {
                best[end] = best[end - 1] + unknownScore
                from[end] = end - 1
                pieceAt[end] = unkId
            }
        }
        val out = ArrayList<Int>()
        var position = n
        while (position > 0) {
            out.add(pieceAt[position])
            position = from[position]
        }
        out.reverse()
        // Like SentencePiece, a run of unknown characters is a single <unk>.
        return out.filterIndexed { i, id -> !(id == unkId && i > 0 && out[i - 1] == unkId) }
    }

    fun decode(tokenIds: List<Int>): String =
        tokenIds
            .filter { it != padId && it != eosId && it != unkId && it !in tagIds.values }
            .joinToString("") { pieces[it] }
            .replace('▁', ' ')
            .trim()
}
