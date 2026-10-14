package com.likhibi.nlp.engine

import android.content.Context
import android.util.Log
import com.likhibi.models.PredictionResult
import com.likhibi.models.PredictionSource
import com.likhibi.nlp.PredictionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Production implementation of Trie-based prefix matching engine with
 * Nagamese phonetic normalization and dialect-resilient fuzzy matching.
 */
class TriePredictor(private val context: Context? = null) : PredictionEngine {

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        var isWord: Boolean = false
        var frequency: Int = 0
    }

    private val root = TrieNode()

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        context?.let { ctx ->
            try {
                loadFromAssets(ctx, "models/trie_index.json")
            } catch (e: Exception) {
                Log.e("TriePredictor", "Failed to load trie_index.json from assets", e)
            }
        }
    }

    fun loadFromAssets(ctx: Context, assetPath: String = "models/trie_index.json") {
        try {
            load(ctx.assets.open(assetPath))
            Log.d("TriePredictor", "Successfully loaded Trie index from $assetPath.")
        } catch (e: Exception) {
            Log.e("TriePredictor", "Error loading asset: $assetPath", e)
        }
    }

    /** Reads trie_index.json. Public so JVM tests can load the asset file directly. */
    fun load(stream: java.io.InputStream) {
        val jsonString = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        val trieRootObj = JSONObject(jsonString).optJSONObject("trie_root")
        if (trieRootObj != null) {
            parseTrieNode(trieRootObj, root)
            isLoaded = true
        }
    }

    /**
     * Reads one trie node. "$" marks the end of a word and "$f" its frequency; every
     * single-letter key whose value is an object is a child. Older files used "f" and
     * "e" for metadata, which collide with the letters f and e, so a key counts as
     * metadata only when its value is not an object.
     */
    private fun parseTrieNode(jsonObj: JSONObject, node: TrieNode) {
        val keys = jsonObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val childObj = jsonObj.optJSONObject(key)
            when {
                key == "$" -> node.isWord = true
                childObj != null && key.length == 1 -> {
                    val childNode = node.children.getOrPut(key[0]) { TrieNode() }
                    parseTrieNode(childObj, childNode)
                }
                key == "\$f" || key == "f" -> node.frequency = jsonObj.optInt(key, 1)
            }
        }
    }

    override suspend fun predictNextWords(
        contextWords: List<String>,
        currentPrefix: String,
        maxSuggestions: Int
    ): PredictionResult = withContext(Dispatchers.Default) {
        val prefix = currentPrefix.trim().lowercase()
        if (prefix.isEmpty() || !isLoaded) {
            return@withContext PredictionResult(
                suggestions = emptyList(),
                source = PredictionSource.TRIE
            )
        }

        val matches = findPrefixMatches(prefix, maxSuggestions).map { it.first }
        return@withContext PredictionResult(
            suggestions = matches,
            source = PredictionSource.TRIE
        )
    }

    /** Whether the trie holds exactly this word. */
    fun contains(word: String): Boolean {
        var node = root
        for (ch in word.lowercase()) node = node.children[ch] ?: return false
        return node.isWord
    }

    fun findPrefixMatches(prefix: String, maxSuggestions: Int = 10): List<Pair<String, Int>> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty() || !isLoaded) return emptyList()

        var curr = root
        for (ch in p) {
            curr = curr.children[ch] ?: return findPhoneticFallbackMatches(p, maxSuggestions)
        }

        val candidates = mutableListOf<Pair<String, Int>>()
        collectWords(curr, StringBuilder(p), candidates)

        val directMatches = candidates
            .sortedByDescending { it.second }
            .distinctBy { it.first }
            .take(maxSuggestions)

        if (directMatches.size < 3) {
            val phonetic = findPhoneticFallbackMatches(p, maxSuggestions - directMatches.size)
            return (directMatches + phonetic).distinctBy { it.first }.take(maxSuggestions)
        }

        return directMatches
    }

    /**
     * Nagamese Phonetic & Dialect Normalization (s/sh/ch, o/u, ee/i, b/v)
     */
    private fun findPhoneticFallbackMatches(prefix: String, limit: Int): List<Pair<String, Int>> {
        val variations = mutableSetOf<String>()
        if (prefix.contains("sh")) variations.add(prefix.replace("sh", "s"))
        if (prefix.contains("s") && !prefix.contains("sh")) variations.add(prefix.replace("s", "sh"))
        if (prefix.contains("ch")) variations.add(prefix.replace("ch", "s"))
        if (prefix.contains("u")) variations.add(prefix.replace("u", "o"))
        if (prefix.contains("o")) variations.add(prefix.replace("o", "u"))
        if (prefix.contains("ee")) variations.add(prefix.replace("ee", "i"))
        if (prefix.contains("i")) variations.add(prefix.replace("i", "ee"))

        val results = mutableListOf<Pair<String, Int>>()
        for (v in variations) {
            var curr = root
            var possible = true
            for (ch in v) {
                curr = curr.children[ch] ?: run { possible = false; return@run root }
            }
            if (possible) {
                collectWords(curr, StringBuilder(v), results)
            }
        }

        return results.sortedByDescending { it.second }.distinctBy { it.first }.take(limit)
    }

    private fun collectWords(node: TrieNode, currentPath: StringBuilder, results: MutableList<Pair<String, Int>>) {
        if (node.isWord) {
            results.add(currentPath.toString() to node.frequency)
        }
        for ((ch, childNode) in node.children) {
            currentPath.append(ch)
            collectWords(childNode, currentPath, results)
            currentPath.deleteCharAt(currentPath.length - 1)
        }
    }
}
