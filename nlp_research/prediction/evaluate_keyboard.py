"""
How well does the keyboard predict Nagamese it has never seen?

Everything is trained without the test books (1-3 John, Titus, Philemon, Jude) and
measured on them:

1. Perplexity of the language models: an add-k baseline (k = 0.01, backoff to
   the bigram when a word pair is unseen) against interpolated modified Kneser-Ney.
   Lower is better. Perplexity on training text says nothing about unseen text, so
   only held-out books are used.
2. Typing simulation: for every word of every held-out sentence, the keyboard shows
   three suggestions after each keystroke. A word counts as predicted when it is
   among them, and accepting it costs one tap. Keystroke savings is the share of
   keystrokes (letters plus the space) the suggestions save; top-3 accuracy is how
   often the next word is suggested before any letter is typed.

The simulation mirrors the scoring in app/src/main/java/com/likhibi/nlp/engine/
NagameseOfflineEngine.kt, including its built-in everyday word list, read straight
from the Kotlin source.

    python -m nlp_research.prediction.evaluate_keyboard
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from nlp_research.corpus.build_bible_parallel import TEST_BOOKS
from nlp_research.prediction.build_keyboard_assets import REPO_ROOT, lexicon_tables, sentences
from nlp_research.prediction.kneser_ney import BOS, EOS, KneserNeyTrigram, ShippedModel
from nlp_research.preprocessing.tokenizer import NagameseTokenizer

ENGINE_SOURCE = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "likhibi" / "nlp" / "engine" / "NagameseOfflineEngine.kt"
REPORT = REPO_ROOT / "datasets" / "evaluations" / "keyboard" / "keyboard_eval.json"


def tokenized(lines: list[str]) -> list[list[str]]:
    tokenizer = NagameseTokenizer()
    return [tokens for tokens in (tokenizer.tokenize(line) for line in lines) if tokens]


def held_out_split() -> tuple[list[list[str]], list[list[str]]]:
    all_books = sentences()
    train = sentences(exclude_books=TEST_BOOKS)
    train_set = set(train)
    test = [s for s in all_books if s not in train_set]
    return tokenized(train), tokenized(test)


class AddK:
    """The previous model: add-k trigram, backing off to an add-k bigram when the pair is unseen."""

    def __init__(self, sentences_: list[list[str]], k: float = 0.01) -> None:
        self.k = k
        self.uni: Counter = Counter()
        self.bi: dict = defaultdict(Counter)
        self.tri: dict = defaultdict(Counter)
        for tokens in sentences_:
            padded = [BOS, *tokens, EOS]
            self.uni.update(tokens)
            for i in range(len(padded) - 1):
                self.bi[padded[i]][padded[i + 1]] += 1
            for i in range(len(padded) - 2):
                self.tri[(padded[i], padded[i + 1])][padded[i + 2]] += 1
        self.v = len(self.uni)
        self.n = sum(self.uni.values())

    def p_bigram(self, word: str, prev: str) -> float:
        total = sum(self.bi[prev].values())
        if total == 0:
            return (self.uni.get(word, 0) + self.k) / (self.n + self.k * self.v)
        return (self.bi[prev].get(word, 0) + self.k) / (total + self.k * self.v)

    def probability(self, word: str, history: list[str]) -> float:
        if len(history) >= 2:
            nexts = self.tri[(history[-2], history[-1])]
            total = sum(nexts.values())
            if total:
                return (nexts.get(word, 0) + self.k) / (total + self.k * self.v)
        return self.p_bigram(word, history[-1])

    def perplexity(self, sentences_: list[list[str]], bigram_only: bool = False) -> float:
        log_sum, tokens = 0.0, 0
        for sentence in sentences_:
            history = [BOS]
            for word in [*sentence, EOS]:
                p = self.p_bigram(word, history[-1]) if bigram_only else self.probability(word, history)
                log_sum += math.log2(p)
                history.append(word)
                tokens += 1
        return 2 ** (-log_sum / tokens)


def builtin_words() -> dict[str, int]:
    """The keyboard's hand-curated everyday word list (localDictionary) with its weights."""
    source = ENGINE_SOURCE.read_text(encoding="utf-8")
    block = source[source.find("localDictionary") : source.find("localBigrams")]
    return {word.lower(): int(weight) for word, weight in re.findall(r'"([^"]+)"\s+to\s+(\d+)', block)}


def builtin_bigrams() -> dict[str, list[str]]:
    source = ENGINE_SOURCE.read_text(encoding="utf-8")
    block = source[source.find("localBigrams") :]
    block = block[: block.find("\n    )") if "\n    )" in block else len(block)]
    return {
        first.lower(): re.findall(r'"([^"]+)"', nexts)
        for first, nexts in re.findall(r'"([^"]+)"\s+to\s+listOf\(([^)]*)\)', block)
    }


class KeyboardSimulator:
    """NagameseOfflineEngine.kt in Python: the shipped tables, its candidate sources and its score.

    Completions (a prefix typed): the 30 most frequent corpus words with that prefix, the
    language model's 10 likeliest continuations with that prefix, and every everyday word
    with that prefix. Next words (nothing typed yet): the language model's 10 likeliest
    continuations plus the built-in word pairs. Both are ranked by
    0.85 x P_kneser_ney(word | context) + 0.15 x everyday weight share.
    """

    def __init__(
        self,
        lm: ShippedModel,
        word_counts: dict[str, int],
        everyday: dict[str, int],
        bigrams: dict[str, list[str]],
        prior_weight: float = 0.15,
    ) -> None:
        self.lm = lm
        self.bigrams = bigrams
        self.prior_weight = prior_weight
        total = sum(everyday.values()) or 1
        self.prior = {w: c / total for w, c in everyday.items()}
        self.by_frequency = sorted(word_counts, key=lambda w: -word_counts[w])
        self.unigrams_by_probability = sorted(lm.uni, key=lambda w: -lm.uni[w])
        self.prefix_index: dict[str, list[str]] = defaultdict(list)  # corpus words, most frequent first
        for word in self.by_frequency:
            for length in range(1, min(3, len(word)) + 1):
                self.prefix_index[word[:length]].append(word)
        self.everyday_index: dict[str, list[str]] = defaultdict(list)
        for word in everyday:
            for length in range(1, min(3, len(word)) + 1):
                self.everyday_index[word[:length]].append(word)

    def score(self, word: str, context: list[str]) -> float:
        return (1 - self.prior_weight) * self.lm.probability(word, context) + self.prior_weight * self.prior.get(word, 0.0)

    def lm_candidates(self, context: list[str], prefix: str, limit: int = 10) -> list[str]:
        """NgramPredictor.candidates(): table continuations, topped up with likely words."""
        found: dict[str, None] = {}
        if len(context) >= 2:
            for w in self.lm.tri.get(f"{context[-2]}|{context[-1]}", {}):
                if w != "_g" and w.startswith(prefix):
                    found[w] = None
        if context:
            for w in self.lm.bi.get(context[-1], {}):
                if w != "_g" and w.startswith(prefix):
                    found[w] = None
        if len(found) < limit:
            for w in self.unigrams_by_probability:
                if len(found) >= limit * 2:
                    break
                if w.startswith(prefix):
                    found[w] = None
        return sorted(found, key=lambda w: -self.lm.probability(w, context))[:limit]

    def suggest(self, context: list[str], prefix: str, k: int = 3) -> list[str]:
        if prefix:
            corpus = [w for w in self.prefix_index.get(prefix[:3], []) if w.startswith(prefix)][:30]
            everyday = [w for w in self.everyday_index.get(prefix[:3], []) if w.startswith(prefix)]
            candidates = set(corpus) | set(self.lm_candidates(context, prefix)) | set(everyday)
            return sorted(candidates, key=lambda w: -self.score(w, context))[:k]
        candidates = set(self.lm_candidates(context, "")) | set(self.bigrams.get(context[-1], []) if context else [])
        candidates = [w for w in candidates if w not in (context[-1] if context else None, BOS)]
        return sorted(candidates, key=lambda w: -self.score(w, context))[:k]


def simulate(simulator, test: list[list[str]]) -> dict:
    keystrokes_plain = keystrokes_used = 0
    next_word_hits = words = 0
    completed_after_one = 0
    for sentence in test:
        context = [BOS]
        for word in sentence:
            words += 1
            keystrokes_plain += len(word) + 1  # letters plus the space
            if word in simulator.suggest(context, ""):
                next_word_hits += 1
                keystrokes_used += 1  # one tap; the keyboard adds the space
            else:
                cost = len(word) + 1
                for typed in range(1, len(word)):
                    if word in simulator.suggest(context, word[:typed]):
                        cost = typed + 1
                        if typed == 1:
                            completed_after_one += 1
                        break
                keystrokes_used += cost
            context.append(word)
    return {
        "words": words,
        "keystroke_savings": round(1 - keystrokes_used / keystrokes_plain, 4),
        "next_word_top3": round(next_word_hits / words, 4),
        "found_after_one_letter": round(completed_after_one / words, 4),
    }


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--limit", type=int, help="simulate only the first N test sentences (faster)")
    args = parser.parse_args(argv)

    train, test = held_out_split()
    baseline = AddK(train)
    kn = KneserNeyTrigram().fit(train)
    report = {
        "train_sentences": len(train),
        "test_sentences": len(test),
        "test_tokens": sum(len(s) + 1 for s in test),
        "perplexity": {
            "add_k_bigram (baseline)": round(baseline.perplexity(test, bigram_only=True), 1),
            "add_k_trigram_backoff (baseline)": round(baseline.perplexity(test), 1),
            "kneser_ney_trigram (shipped)": round(kn.perplexity(test), 1),
        },
        "kneser_ney_discounts": {"trigram": kn.tri.d, "bigram": kn.bi.d, "unigram": kn.uni.d},
    }
    sample = test[: args.limit] if args.limit else test
    # What the phone runs: the pruned tables, and the everyday prior merged from the keyboard
    # list and the lexicon (curated words only, so nothing from the test books leaks in).
    shipped = ShippedModel(*kn.export())
    everyday = dict(builtin_words())
    for word, weight in lexicon_tables()[0].items():
        everyday[word] = max(everyday.get(word, 0), weight)
    counts = Counter(w for sentence in train for w in sentence)
    simulator = KeyboardSimulator(shipped, counts, everyday, builtin_bigrams())
    report["typing_simulation"] = simulate(simulator, sample)
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
