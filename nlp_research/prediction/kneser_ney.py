"""
Interpolated modified Kneser-Ney trigram model for the keyboard (Chen and Goodman, 1998).

The keyboard used to rank next words by raw counts with fixed weights (trigram x100,
bigram x10). Kneser-Ney is the standard smoothing for n-gram models:

- every seen count is discounted (three discounts, for counts of 1, 2 and 3+, estimated
  from the counts of counts), and the freed probability goes to the lower order;
- the lower orders use *continuation* counts: how many different words a word follows,
  not how often it occurs. "laga" is frequent, but it follows almost anything, so as a
  fallback it should rank by spread, not raw frequency.

Export layout, read by app/src/main/java/com/likhibi/nlp/engine/NgramPredictor.kt:

    unigrams.json  {word: P1(word)}
    bigrams.json   {prev: {"_g": backoff weight, next: discounted probability, ...}}   top 20 per context
    trigrams.json  {"w1|w2": {"_g": backoff weight, next: discounted probability}}    top 10 per context

    P(w | w1 w2) = tri[w] + tri._g * (bi[w] + bi._g * uni[w])

A context that is not in a table has weight 1, so the formula falls through to the
next order. "_g" cannot collide with a word: words contain letters only.
"""

from __future__ import annotations

import math
from collections import Counter, defaultdict
from dataclasses import dataclass, field

BOS, EOS, UNK = "<s>", "</s>", "<unk>"


def _discounts(counts) -> tuple[float, float, float]:
    """Modified Kneser-Ney discounts D1, D2, D3+ from the counts of counts."""
    histogram = Counter(c for c in counts if c <= 4)  # exact counts of counts, n1 to n4
    n1, n2, n3, n4 = (histogram.get(k, 0) for k in (1, 2, 3, 4))
    if not (n1 and n2 and n3 and n4):
        return 0.5, 0.75, 0.9  # too little data to estimate; conventional values
    y = n1 / (n1 + 2 * n2)
    estimates = (1 - 2 * y * n2 / n1, 2 - 3 * y * n3 / n2, 3 - 4 * y * n4 / n3)
    # A discount for count k must stay within (0, k]; odd histograms can push the formula outside.
    return tuple(min(max(d, 0.05), float(k)) for k, d in zip((1, 2, 3), estimates))


def _discount(count: int, d: tuple[float, float, float]) -> float:
    return 0.0 if count == 0 else d[min(count, 3) - 1]


@dataclass
class _Order:
    """Counts for one context length: context -> {word: count}, with totals and backoff weights."""

    counts: dict[tuple, Counter] = field(default_factory=lambda: defaultdict(Counter))
    totals: dict[tuple, int] = field(default_factory=dict)
    gammas: dict[tuple, float] = field(default_factory=dict)
    d: tuple[float, float, float] = (0.5, 0.75, 0.9)

    def finish(self) -> None:
        self.d = _discounts(c for nexts in self.counts.values() for c in nexts.values())
        for context, nexts in self.counts.items():
            total = sum(nexts.values())
            self.totals[context] = total
            freed = sum(_discount(c, self.d) for c in nexts.values())
            self.gammas[context] = freed / total

    def discounted(self, context: tuple, word: str) -> float:
        total = self.totals.get(context)
        if not total:
            return 0.0
        count = self.counts[context].get(word, 0)
        return max(count - _discount(count, self.d), 0.0) / total

    def gamma(self, context: tuple) -> float:
        return self.gammas.get(context, 1.0)


class KneserNeyTrigram:
    def __init__(self) -> None:
        self.vocab: set[str] = set()
        self.tri = _Order()
        self.bi = _Order()
        self.uni = _Order()

    def fit(self, sentences: list[list[str]]) -> "KneserNeyTrigram":
        """Train on tokenised sentences. Each sentence is wrapped as <s> w1 ... wn </s>."""
        raw_bigrams: Counter = Counter()
        for tokens in sentences:
            padded = [BOS, *tokens, EOS]
            self.vocab.update(tokens)
            for i in range(1, len(padded)):
                raw_bigrams[(padded[i - 1], padded[i])] += 1
                if i >= 2:
                    self.tri.counts[(padded[i - 2], padded[i - 1])][padded[i]] += 1
        self.vocab.update((EOS, UNK))

        # Bigram level: continuation counts (distinct left contexts), except after <s>,
        # which nothing precedes, so its raw counts are used.
        left_contexts: dict[tuple, set] = defaultdict(set)
        for (u, v), nexts in self.tri.counts.items():
            for w in nexts:
                left_contexts[(v, w)].add(u)
        for (v, w), count in raw_bigrams.items():
            self.bi.counts[(v,)][w] = count if v == BOS else len(left_contexts[(v, w)]) or count

        # Unigram level: in how many distinct bigram contexts each word appears.
        for (v,), nexts in self.bi.counts.items():
            for w in nexts:
                self.uni.counts[()][w] += 1

        for order in (self.tri, self.bi, self.uni):
            order.finish()
        return self

    def p_unigram(self, word: str) -> float:
        uniform = 1.0 / len(self.vocab)
        return self.uni.discounted((), word) + self.uni.gamma(()) * uniform

    def p_bigram(self, word: str, prev: str) -> float:
        return self.bi.discounted((prev,), word) + self.bi.gamma((prev,)) * self.p_unigram(word)

    def probability(self, word: str, context: list[str]) -> float:
        """P(word | last two words of context); unknown words count as <unk>."""
        word = word if word in self.vocab else UNK
        context = [w if w in self.vocab or w == BOS else UNK for w in context[-2:]]
        if not context:
            return self.p_unigram(word)
        if len(context) == 1:
            return self.p_bigram(word, context[0])
        key = (context[0], context[1])
        return self.tri.discounted(key, word) + self.tri.gamma(key) * self.p_bigram(word, context[1])

    def perplexity(self, sentences: list[list[str]]) -> float:
        log_sum, tokens = 0.0, 0
        for sentence in sentences:
            history = [BOS]
            for word in [*sentence, EOS]:
                log_sum += math.log2(self.probability(word, history))
                history.append(word)
                tokens += 1
        return 2 ** (-log_sum / tokens)

    def export(self, top_bigram: int = 20, top_trigram: int = 10) -> tuple[dict, dict, dict]:
        """The three JSON tables the keyboard reads, pruned to the likeliest continuations.

        Pruning keeps each context's top words. To keep every distribution summing to one,
        the backoff weight is renormalised over the words left out (as in ARPA backoff
        models), and each kept word stores its full probability minus that weight times
        the lower order, so the keyboard's formula stays tri[w] + _g * lower(w).
        </s> and <unk> are never suggested, so they are not stored.
        """
        p1 = {w: self.p_unigram(w) for w in self.vocab}

        def pruned(contexts, top: int, full, lower, lower_reserved) -> dict:
            out = {}
            for context, nexts in contexts:
                candidates = [w for w in nexts if w not in (EOS, UNK, BOS)]
                kept = sorted(((w, full(w, context)) for w in candidates), key=lambda item: -item[1])[:top]
                if not kept:
                    continue
                # </s> and <unk> are never stored, but their probability is not the backoff's to
                # hand out: count them as kept at both orders, so every stored word's total stays exact.
                kept_mass = sum(p for _, p in kept) + sum(full(w, context) for w in (EOS, UNK))
                lower_mass = sum(lower(w, context) for w, _ in kept) + lower_reserved(context)
                gamma = (1 - kept_mass) / (1 - lower_mass) if lower_mass < 1 - 1e-9 else 0.0
                out[context] = (gamma, {w: p - gamma * lower(w, context) for w, p in kept})
            return out

        bigram_tables = pruned(
            ((v, nexts) for (v,), nexts in self.bi.counts.items()), top_bigram,
            lambda w, v: self.p_bigram(w, v), lambda w, v: p1[w], lambda v: p1[EOS] + p1[UNK],
        )

        def p2_shipped(w: str, v: str) -> float:
            if v in bigram_tables:
                gamma, stored = bigram_tables[v]
                return stored.get(w, 0.0) + gamma * p1.get(w, 0.0)
            return p1.get(w, 0.0)

        def bigram_reserved(uv: tuple) -> float:
            # The mass the shipped bigram distribution leaves outside the stored vocabulary.
            v = uv[1]
            if v in bigram_tables:
                return self.p_bigram(EOS, v) + self.p_bigram(UNK, v)
            return p1[EOS] + p1[UNK]

        trigram_tables = pruned(
            (((u, v), nexts) for (u, v), nexts in self.tri.counts.items()), top_trigram,
            lambda w, uv: self.probability(w, list(uv)), lambda w, uv: p2_shipped(w, uv[1]), bigram_reserved,
        )

        def as_json(tables: dict, key) -> dict:
            return {
                key(context): {"_g": float(f"{gamma:.6g}")} | {w: float(f"{p:.6g}") for w, p in stored.items()}
                for context, (gamma, stored) in tables.items()
            }

        unigrams = {
            w: float(f"{p1[w]:.6g}") for w in sorted(self.vocab, key=lambda w: -p1[w]) if w not in (EOS, UNK, BOS)
        }
        return unigrams, as_json(bigram_tables, lambda v: v), as_json(trigram_tables, lambda uv: f"{uv[0]}|{uv[1]}")


class ShippedModel:
    """The exported tables, scored exactly as the keyboard's NgramPredictor.kt scores them."""

    def __init__(self, unigrams: dict, bigrams: dict, trigrams: dict) -> None:
        self.uni, self.bi, self.tri = unigrams, bigrams, trigrams
        self.vocab = set(unigrams)

    def probability(self, word: str, context: list[str]) -> float:
        unigram = self.uni.get(word)
        if unigram is None:
            return 0.0
        last = context[-1] if context else None
        table = self.bi.get(last) if last else None
        bigram = table.get(word, 0.0) + table["_g"] * unigram if table else unigram
        if len(context) < 2:
            return bigram
        table = self.tri.get(f"{context[-2]}|{last}")
        return table.get(word, 0.0) + table["_g"] * bigram if table else bigram
