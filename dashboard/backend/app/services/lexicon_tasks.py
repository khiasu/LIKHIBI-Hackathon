"""Lexicon words that still need an English meaning, for speakers to supply.

The lexicon lists about 6,900 Nagamese words, but none has a verified meaning yet.
Speakers fill them in on the Contribute page, most frequent words first. Each
meaning goes through the same review as any other contribution, and an approved
one reaches the translation memory at once.
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


def _needs_meaning(definition: object) -> bool:
    text = str(definition or "").strip()
    return not text or text.startswith("Definition placeholder")


@lru_cache(maxsize=4)
def _open_words(path: str, mtime: float) -> tuple[tuple[str, int, str | None], ...]:
    entries = json.loads(Path(path).read_text(encoding="utf-8"))
    words = [
        (str(e["lemma"]).strip(), int(e.get("frequency_count") or 0), e.get("etymology_origin"))
        for e in entries
        if e.get("lemma") and _needs_meaning(e.get("english_definition"))
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
    tasks = [MeaningTask(lemma, frequency, origin, guesses.get(lemma.lower(), ())) for lemma, frequency, origin in picks]
    return tasks, len(open_words), len(words)
