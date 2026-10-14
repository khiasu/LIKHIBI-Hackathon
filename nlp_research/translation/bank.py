"""
The parallel example bank engines draw on: every usable sentence pair in datasets/,
minus anything held out for evaluation.

Rows are read from datasets/processed/parallel_corpus/*.tsv (columns english_text and
nagamese_text, optional domain and split). Only training rows are used, and any pair
whose source or target also appears in a dev or test set is dropped, so engines can
never be scored on sentences they have already seen.
"""

from __future__ import annotations

import csv
import re
from pathlib import Path
from typing import Iterable, Sequence

from .engine_api import ParallelExample
from .retrieval import Example
from .text import match_key

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASETS_DIR = REPO_ROOT / "datasets"
_PLACEHOLDER = re.compile(r"^\[English translation for .*\]$")


def _read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))


def held_out_keys(datasets_dir: Path) -> set[tuple[str, str]]:
    """Match keys of every sentence in the dev and test sets, per language."""
    keys: set[tuple[str, str]] = set()
    for folder in ("test_sets", "dev_sets"):
        for path in sorted((datasets_dir / "processed" / folder).glob("*.tsv")):
            # File names end in _en_nag or _nag_en; that tells us each column's language.
            source_lang, target_lang = ("nag", "en") if path.stem.endswith("_nag_en") else ("en", "nag")
            for row in _read_tsv(path):
                if row.get("source_text"):
                    keys.add((source_lang, match_key(row["source_text"], source_lang)))
                if row.get("target_text"):
                    keys.add((target_lang, match_key(row["target_text"], target_lang)))
    return keys


def load_examples(datasets_dir: Path = DEFAULT_DATASETS_DIR) -> list[Example]:
    """Training-split pairs from every parallel TSV, with held-out sentences removed."""
    blocked = held_out_keys(datasets_dir)
    examples: list[Example] = []
    for path in sorted((datasets_dir / "processed" / "parallel_corpus").glob("*.tsv")):
        file_domain = "bible" if "bible" in path.stem.lower() else "other"
        for row in _read_tsv(path):
            english = (row.get("english_text") or "").strip()
            nagamese = (row.get("nagamese_text") or "").strip()
            if not english or not nagamese or _PLACEHOLDER.match(english):
                continue
            if (row.get("split") or "train").strip().lower() != "train":
                continue
            if ("en", match_key(english, "en")) in blocked or ("nag", match_key(nagamese, "nag")) in blocked:
                continue
            reference = row.get("verse_id") or row.get("id") or row.get("src_id") or ""
            domain = (row.get("domain") or file_domain).strip().lower() or file_domain
            examples.append(Example(english, nagamese, f"{domain}:{reference}" if reference else domain, domain))
    return examples


def community_examples(examples: Iterable[ParallelExample]) -> list[Example]:
    return [Example(e.english, e.nagamese, e.origin, e.domain) for e in examples if e.english and e.nagamese]


def merge(base: Sequence[Example], community: Sequence[Example]) -> list[Example]:
    return [*community, *base]
