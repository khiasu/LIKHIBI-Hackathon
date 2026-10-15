"""Lexicon words that still need a speaker-checked English meaning, for speakers to supply.

The lexicon lists 20,000 Nagamese words. Most have a researched meaning, but none is
verified until a speaker checks it. Speakers fill them in on the Contribute page,
most frequent words first, with the researched glosses offered as guesses. Each
meaning goes through the same review as any other contribution, and an approved
one reaches the translation memory at once. Derived forms (kor- + -ise) are not
asked about: their meaning follows from the root's.
"""

from __future__ import annotations

import csv
import json
import random
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from .data_health import GLOSSARY_FILE, LEXICON_FILE

# Words are drawn at random from the most frequent open ones, so people working at
# the same time rarely get the same word.
POOL_SIZE = 200


@dataclass(frozen=True)
class MeaningTask:
    lemma: str
    frequency: int
    origin: str | None
    guesses: tuple[str, ...]


def _needs_meaning(entry: dict) -> bool:
    if "is_validated" in entry:  # current lexicon: definitions are researched, a speaker verifies them
        return not entry["is_validated"] and entry.get("status") != "derived"
    text = str(entry.get("english_definition") or "").strip()
    return not text or text.startswith("Definition placeholder")


@lru_cache(maxsize=4)
def _open_words(path: str, mtime: float) -> tuple[tuple[str, int, str | None, tuple[str, ...]], ...]:
    entries = json.loads(Path(path).read_text(encoding="utf-8"))
    words = [
        (
            str(e["lemma"]).strip(),
            int(e.get("frequency_count") or 0),
            e.get("etymology_origin"),
            tuple(str(g.get("english")) for g in (e.get("gloss_candidates") or [])[:2] if g.get("english")),
        )
        for e in entries
        if e.get("lemma") and _needs_meaning(e)
    ]
    return tuple(sorted(words, key=lambda w: -w[1]))


@lru_cache(maxsize=4)
def _guesses(path: str, mtime: float) -> dict[str, tuple[str, ...]]:
    found: dict[str, list[tuple[float, str]]] = {}
    with open(path, encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            found.setdefault(row["nagamese"].lower(), []).append((float(row["score"]), row["english"]))
    return {word: tuple(e for _, e in sorted(pairs, reverse=True)[:2]) for word, pairs in found.items()}


def meaning_tasks(
    datasets_dir: Path, done: set[str], count: int, skip: set[str], rng: random.Random | None = None
) -> tuple[list[MeaningTask], int, int]:
    """Up to `count` words to define, the number still open, and the lexicon size needing meanings."""
    lexicon = datasets_dir / LEXICON_FILE
    if not lexicon.is_file():
        return [], 0, 0
    words = _open_words(str(lexicon), lexicon.stat().st_mtime)
    glossary = datasets_dir / GLOSSARY_FILE
    guesses = _guesses(str(glossary), glossary.stat().st_mtime) if glossary.is_file() else {}
    open_words = [w for w in words if w[0].lower() not in done]
    pool = [w for w in open_words[:POOL_SIZE] if w[0].lower() not in skip]
    picks = (rng or random.Random()).sample(pool, min(count, len(pool)))
    picks.sort(key=lambda w: -w[1])
    tasks = [
        MeaningTask(lemma, frequency, origin, guesses.get(lemma.lower()) or researched)
        for lemma, frequency, origin, researched in picks
    ]
    return tasks, len(open_words), len(words)
