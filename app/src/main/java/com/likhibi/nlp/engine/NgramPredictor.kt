package com.likhibi.nlp.engine

import android.content.Context
import android.util.Log
import com.likhibi.models.PredictionResult
import com.likhibi.models.PredictionSource
import com.likhibi.nlp.PredictionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream

/**
 * Interpolated modified Kneser-Ney trigram model, exported by
 * nlp_research/prediction/kneser_ney.py:
 *
 *   unigrams.json  {word: P1(word)}
 *   bigrams.json   {prev: {"_g": backoff weight, next: discounted probability}}
 *   trigrams.json  {"w1|w2": {"_g": backoff weight, next: discounted probability}}
 *
 *   P(w | w1 w2) = tri[w] + tri._g * (bi[w] + bi._g * uni[w])
 *
 * A context missing from a table has weight 1, so the formula falls through to the
 * next order. Contexts may start with "<s>", the start of a sentence.
 */
class NgramPredictor(private val context: Context? = null) : PredictionEngine {

    private class Table(val gamma: Double, val probabilities: Map<String, Double>)

    private val trigramMap = HashMap<String, Table>()
    private val bigramMap = HashMap<String, Table>()
    private val unigramMap = HashMap<String, Double>()
    private var unigramsByProbability: List<String> = emptyList()

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        context?.let { ctx ->
            try {
                load(
                    ctx.assets.open("models/unigrams.json"),
                    ctx.assets.open("models/bigrams.json"),
                    ctx.assets.open("models/trigrams.json")
                )
            } catch (e: Exception) {
                Log.e("NgramPredictor", "Failed to load the language model from assets", e)
            }
        }
    }

    /** Reads the three tables. Public so JVM tests can load them from files. */
    fun load(unigrams: InputStream, bigrams: InputStream, trigrams: InputStream) {
        val uni = JSONObject(unigrams.bufferedReader(Charsets.UTF_8).use { it.readText() })
        for (word in uni.keys()) unigramMap[word] = uni.getDouble(word)
        unigramsByProbability = unigramMap.entries.sortedByDescending { it.value }.map { it.key }
        readTables(bigrams, bigramMap)
        readTables(trigrams, trigramMap)
        isLoaded = true
        Log.d("NgramPredictor", "Language model: ${unigramMap.size} words, ${bigramMap.size} bigram and ${trigramMap.size} trigram contexts")
    }

    private fun readTables(stream: InputStream, into: HashMap<String, Table>) {
        val json = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        for (contextKey in json.keys()) {
            val inner = json.getJSONObject(contextKey)
            val probabilities = HashMap<String, Double>(inner.length())
            for (word in inner.keys()) {
                if (word != "_g") probabilities[word] = inner.getDouble(word)
            }
            into[contextKey] = Table(inner.optDouble("_g", 1.0), probabilities)
        }
    }

    fun isKnown(word: String): Boolean = unigramMap.containsKey(word)

    /** P(word | the last two words of context). Words outside the model get 0. */
    fun probability(word: String, contextWords: List<String>): Double {
        val unigram = unigramMap[word] ?: return 0.0
        val last = contextWords.lastOrNull()
        val bigramTable = last?.let { bigramMap[it] }
        val bigram = if (bigramTable != null) (bigramTable.probabilities[word] ?: 0.0) + bigramTable.gamma * unigram else unigram
        if (contextWords.size < 2) return bigram
        val trigramTable = trigramMap["${contextWords[contextWords.size - 2]}|$last"] ?: return bigram
        return (trigramTable.probabilities[word] ?: 0.0) + trigramTable.gamma * bigram
    }

    /**
     * Words likely to come next (optionally starting with a prefix): every continuation
     * the trigram and bigram tables list for this context, topped up with the most
     * probable words overall, ranked by probability.
     */
    fun candidates(contextWords: List<String>, prefix: String = "", limit: Int = 10): List<String> {
        if (!isLoaded) return emptyList()
        val found = LinkedHashSet<String>()
        if (contextWords.size >= 2) {
            trigramMap["${contextWords[contextWords.size - 2]}|${contextWords.last()}"]
                ?.probabilities?.keys?.filterTo(found) { it.startsWith(prefix) }
        }
        contextWords.lastOrNull()?.let { last ->
            bigramMap[last]?.probabilities?.keys?.filterTo(found) { it.startsWith(prefix) }
        }
        if (found.size < limit) {
            for (word in unigramsByProbability) {
                if (found.size >= limit * 2) break
                if (word.startsWith(prefix)) found.add(word)
            }
        }
        return found.sortedByDescending { probability(it, contextWords) }.take(limit)
    }

    fun predictNext(contextWords: List<String>, currentPrefix: String = "", maxSuggestions: Int = 3): List<String> =
        candidates(contextWords, currentPrefix.trim().lowercase(), maxSuggestions)

    override suspend fun predictNextWords(
        contextWords: List<String>,
        currentPrefix: String,
        maxSuggestions: Int
    ): PredictionResult = withContext(Dispatchers.Default) {
        PredictionResult(
            suggestions = predictNext(contextWords, currentPrefix, maxSuggestions),
            source = PredictionSource.NGRAM
        )
    }
}
