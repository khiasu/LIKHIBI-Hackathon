"""
Retrieval of similar sentence pairs: BM25 for candidates, character n-gram F-score to verify.

Used by the translation memory (is there a stored translation of this sentence?)
and by the retrieval-augmented LLM engine (which pairs should the prompt show?).
"""

from __future__ import annotations

import heapq
import math
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Iterable, Sequence

from .engine_api import Direction
from .text import match_key, match_tokens


class BM25:
    """Okapi BM25 over pre-tokenised documents."""

    def __init__(self, documents: Sequence[Sequence[str]], k1: float = 1.5, b: float = 0.75) -> None:
        self.k1 = k1
        self.b = b
        self.lengths = [len(doc) for doc in documents]
        self.average_length = (sum(self.lengths) / len(self.lengths)) if self.lengths else 0.0
        postings: dict[str, list[tuple[int, int]]] = defaultdict(list)
        for doc_id, doc in enumerate(documents):
            for term, count in Counter(doc).items():
                postings[term].append((doc_id, count))
        total = len(documents)
        self.postings = dict(postings)
        self.idf = {
            term: math.log(1 + (total - len(docs) + 0.5) / (len(docs) + 0.5)) for term, docs in postings.items()
        }

    def search(self, query: Iterable[str], k: int) -> list[tuple[int, float]]:
        scores: dict[int, float] = defaultdict(float)
        for term in set(query):
            idf = self.idf.get(term)
            if idf is None:
                continue
            for doc_id, tf in self.postings[term]:
                norm = self.k1 * (1 - self.b + self.b * self.lengths[doc_id] / (self.average_length or 1))
                scores[doc_id] += idf * tf * (self.k1 + 1) / (tf + norm)
        return heapq.nlargest(k, scores.items(), key=lambda item: item[1])


def _ngrams(text: str, n: int) -> Counter[str]:
    compact = text.replace(" ", "")
    return Counter(compact[i : i + n] for i in range(len(compact) - n + 1))


def char_similarity(a: str, b: str, max_n: int = 6) -> float:
    """Symmetric character n-gram F1 (the core of chrF), from 0 to 1."""
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    precisions, recalls = [], []
    for n in range(1, max_n + 1):
        grams_a, grams_b = _ngrams(a, n), _ngrams(b, n)
        if not grams_a or not grams_b:
            continue
        overlap = sum((grams_a & grams_b).values())
        precisions.append(overlap / sum(grams_a.values()))
        recalls.append(overlap / sum(grams_b.values()))
    if not precisions:
        return 0.0
    p, r = sum(precisions) / len(precisions), sum(recalls) / len(recalls)
    return 0.0 if p + r == 0 else 2 * p * r / (p + r)


@dataclass(frozen=True)
class Example:
    english: str
    nagamese: str
    origin: str  # e.g. "bible:MAT_1_1" or "feedback:12"
    domain: str  # "bible", "everyday", ...

    def side(self, lang: str) -> str:
        return self.nagamese if lang == "nag" else self.english


@dataclass(frozen=True)
class Hit:
    example: Example
    score: float  # BM25, unbounded
    similarity: float  # character similarity of the source sides, 0..1


class ExampleIndex:
    """Searchable examples for both directions. Rebuilt whole, then swapped in."""

    def __init__(self, examples: Sequence[Example]) -> None:
        self.examples = list(examples)
        self._keys = {
            lang: [match_key(example.side(lang), lang) for example in self.examples] for lang in ("en", "nag")
        }
        self._bm25 = {
            lang: BM25([key.split() for key in self._keys[lang]]) for lang in ("en", "nag")
        }
        self._exact: dict[str, dict[str, list[int]]] = {"en": defaultdict(list), "nag": defaultdict(list)}
        for lang in ("en", "nag"):
            for index, key in enumerate(self._keys[lang]):
                if key:
                    self._exact[lang][key].append(index)

    def __len__(self) -> int:
        return len(self.examples)

    def exact(self, text: str, direction: Direction) -> list[Example]:
        key = match_key(text, direction.source)
        return [self.examples[i] for i in self._exact[direction.source].get(key, [])]

    def search(self, text: str, direction: Direction, k: int, pool: int = 200) -> list[Hit]:
        """Top-k examples by BM25, re-ranked with character similarity and source preference.

        Community corrections and non-Bible pairs get a mild boost, because they
        are closer to how people actually write.
        """
        lang = direction.source
        query_key = match_key(text, lang)
        candidates = self._bm25[lang].search(match_tokens(text, lang), pool)
        if not candidates:
            return []
        top = candidates[0][1] or 1.0
        hits = []
        for index, score in candidates:
            example = self.examples[index]
            similarity = char_similarity(query_key, self._keys[lang][index])
            boost = 1.25 if example.origin.startswith("feedback:") else 1.1 if example.domain != "bible" else 1.0
            rank = (0.5 * (score / top) + 0.5 * similarity) * boost
            hits.append((rank, Hit(example, score, similarity)))
        hits.sort(key=lambda item: item[0], reverse=True)
        unique: list[Hit] = []
        seen: set[str] = set()
        for _, hit in hits:
            fingerprint = match_key(hit.example.side(direction.target), direction.target)
            if fingerprint in seen:
                continue
            seen.add(fingerprint)
            unique.append(hit)
            if len(unique) == k:
                break
        return unique
