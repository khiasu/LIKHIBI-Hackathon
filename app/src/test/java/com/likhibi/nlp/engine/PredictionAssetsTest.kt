package com.likhibi.nlp.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the shipped prediction assets with the keyboard's own Kotlin loaders.
 * Unit tests run from the app/ folder, so the assets are read straight from src/main/assets.
 */
class PredictionAssetsTest {

    private val models = File("src/main/assets/models")

    private fun vocabulary(): List<String> =
        JSONObject(File(models, "unigrams.json").readText()).keys().asSequence().toList()

    private fun languageModel() = NgramPredictor(null).apply {
        load(
            File(models, "unigrams.json").inputStream(),
            File(models, "bigrams.json").inputStream(),
            File(models, "trigrams.json").inputStream()
        )
    }

    @Test
    fun everyCorpusWordIsReachableInTheTrie() {
        // The old loader skipped any word containing "e" or "f"; every word must load now.
        val trie = TriePredictor(null).apply { load(File(models, "trie_index.json").inputStream()) }
        val missing = vocabulary().filterNot { trie.contains(it) }
        assertEquals("unreachable: ${missing.take(10)}", 0, missing.size)
        assertTrue(trie.findPrefixMatches("ke", 10).any { it.first == "kene" })
        assertTrue(trie.findPrefixMatches("et", 10).any { it.first == "etu" })
    }

    @Test
    fun kneserNeyProbabilitiesAreProperAndUseTheContext() {
        val lm = languageModel()
        val words = vocabulary()
        for (context in listOf(listOf("<s>"), listOf("moi"), listOf("aru", "tai"), listOf("tai", "laga"))) {
            val total = words.sumOf { lm.probability(it, context) }
            // At most 1; less only by the continuations pruned from the shipped tables.
            assertTrue("sum for $context is $total", total in 0.5..1.0001)
        }
        assertTrue(lm.probability("laga", listOf("moi")) > lm.probability("laga", listOf("<s>")))
    }

    @Test
    fun trigramContextsAreFound() {
        // The old predictor joined trigram keys with a space; the file uses "|".
        val lm = languageModel()
        val withTrigram = lm.probability("ke", listOf("manu", "khan"))
        val bigramOnly = lm.probability("ke", listOf("unknownword", "khan"))
        assertTrue("trigram $withTrigram vs bigram $bigramOnly", withTrigram != bigramOnly)
        assertEquals(3, lm.predictNext(listOf("aru"), "", 3).size)
    }
}
