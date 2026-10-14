"""
Automatic bilingual glossary: candidate word translations extracted from the aligned
corpus with IBM Model 1 (Brown et al., 1993), trained in both directions.

Every entry is machine-extracted and unverified. The LLM engine shows entries as
"hints that may be wrong", and they never count as validated lexicon glosses.
A speaker should check an entry before it moves into nagamese_lexicon.json.

Build it from the repository root:
    python -m nlp_research.translation.glossary
"""

from __future__ import annotations

import argparse
import csv
import math
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from .bank import DEFAULT_DATASETS_DIR, load_examples
from .engine_api import Direction
from .text import words

GLOSSARY_PATH = Path("processed") / "lexical_database" / "auto_glossary.tsv"

# Function words carry grammar, which the example sentences teach better than a glossary.
ENGLISH_STOPWORDS = frozenset(
    """a about above after again against all also am an and any are as at be because been before being
    between both but by can could did do does doing down during each few for from further had has have
    having he her here hers herself him himself his how i if in into is it its itself just me more most
    my myself no nor not now of off on once only or other our ours ourselves out over own same she should
    so some such than that the their theirs them themselves then there these they this those through to
    too under until up very was we were what when where which while who whom why will with would you your
    yours yourself yourselves shall may might must one also even let us thus unto yet o oh""".split()
)


def _ibm_model1(pairs: Sequence[tuple[list[str], list[str]]], iterations: int) -> dict[str, dict[str, float]]:
    """Return t[target_word][source_word] = P(target | source), with a NULL source token."""
    t: dict[str, dict[str, float]] = defaultdict(dict)
    sources = {w for src, _ in pairs for w in src} | {"<null>"}
    uniform = 1.0 / len(sources)
    for src, tgt in pairs:
        for f in tgt:
            for e in [*src, "<null>"]:
                t[f][e] = uniform
    for _ in range(iterations):
        counts: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
        totals: dict[str, float] = defaultdict(float)
        for src, tgt in pairs:
            src_null = [*src, "<null>"]
            for f in tgt:
                row = t[f]
                norm = sum(row[e] for e in src_null)
                for e in src_null:
                    share = row[e] / norm
                    counts[f][e] += share
                    totals[e] += share
        for f, row in counts.items():
            for e, value in row.items():
                t[f][e] = value / totals[e]
    return t


@dataclass(frozen=True)
class Entry:
    nagamese: str
    english: str
    score: float
    count: int


def extract(pairs: Sequence[tuple[str, str]], iterations: int = 6, min_count: int = 3, min_score: float = 0.08) -> list[Entry]:
    tokenised = [(words(en), words(nag)) for en, nag in pairs]
    tokenised = [(en, nag) for en, nag in tokenised if en and nag and len(en) <= 80 and len(nag) <= 80]
    nag_given_en = _ibm_model1(tokenised, iterations)  # P(nag | en)
    en_given_nag = _ibm_model1([(nag, en) for en, nag in tokenised], iterations)  # P(en | nag)

    nag_counts = Counter(w for _, nag in tokenised for w in nag)
    co_counts: Counter[tuple[str, str]] = Counter()
    for en, nag in tokenised:
        for n in set(nag):
            for e in set(en):
                co_counts[(n, e)] += 1

    entries: list[Entry] = []
    for nag_word, count in nag_counts.items():
        if count < min_count or len(nag_word) < 2:
            continue
        candidates = []
        # nag_given_en[nag][en] = P(nag | en); en_given_nag[en][nag] = P(en | nag).
        for en_word, p_nag in nag_given_en.get(nag_word, {}).items():
            if en_word == "<null>" or en_word in ENGLISH_STOPWORDS or not en_word.isalpha():
                continue
            p_en = en_given_nag.get(en_word, {}).get(nag_word, 0.0)
            score = math.sqrt(p_en * p_nag)  # agreement of both directions
            if score >= min_score and co_counts[(nag_word, en_word)] >= min_count:
                candidates.append(Entry(nag_word, en_word, round(score, 4), co_counts[(nag_word, en_word)]))
        candidates.sort(key=lambda entry: entry.score, reverse=True)
        entries.extend(candidates[:2])
    entries.sort(key=lambda entry: (-nag_counts[entry.nagamese], -entry.score))
    return entries


def write(entries: Sequence[Entry], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(["nagamese", "english", "score", "count", "status", "method"])
        for entry in entries:
            writer.writerow([entry.nagamese, entry.english, entry.score, entry.count, "unverified", "ibm1-bible"])


class Glossary:
    """Lookup of glossary hints for the words in an input sentence."""

    def __init__(self, entries: Sequence[Entry]) -> None:
        self.by_nagamese: dict[str, list[Entry]] = defaultdict(list)
        self.by_english: dict[str, list[Entry]] = defaultdict(list)
        for entry in entries:
            self.by_nagamese[entry.nagamese].append(entry)
            self.by_english[entry.english].append(entry)
        for table in (self.by_nagamese, self.by_english):
            for key in table:
                table[key].sort(key=lambda entry: entry.score, reverse=True)

    def __len__(self) -> int:
        return sum(len(v) for v in self.by_nagamese.values())

    @classmethod
    def empty(cls) -> "Glossary":
        return cls([])

    @classmethod
    def load(cls, datasets_dir: Path = DEFAULT_DATASETS_DIR) -> "Glossary":
        path = datasets_dir / GLOSSARY_PATH
        if not path.is_file():
            return cls.empty()
        with path.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
        return cls([Entry(r["nagamese"], r["english"], float(r["score"]), int(r["count"])) for r in rows])

    def hints(self, text: str, direction: Direction, limit: int = 12) -> list[tuple[str, str]]:
        """(word, candidate translations) for content words of the input."""
        table = self.by_english if direction.source == "en" else self.by_nagamese
        found: list[tuple[str, str]] = []
        seen: set[str] = set()
        for word in words(text):
            if word in seen or word in ENGLISH_STOPWORDS or word not in table:
                continue
            seen.add(word)
            options = table[word][:2]
            meaning = " / ".join(e.nagamese if direction.source == "en" else e.english for e in options)
            found.append((word, meaning))
            if len(found) == limit:
                break
        return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--datasets-dir", type=Path, default=DEFAULT_DATASETS_DIR)
    args = parser.parse_args()
    examples = load_examples(args.datasets_dir)
    entries = extract([(e.english, e.nagamese) for e in examples])
    path = args.datasets_dir / GLOSSARY_PATH
    write(entries, path)
    print(f"{len(entries)} unverified glossary candidates for {len({e.nagamese for e in entries})} Nagamese words -> {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
