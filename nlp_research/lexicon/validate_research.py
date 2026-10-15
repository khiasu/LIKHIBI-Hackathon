"""
Check the research shards that grow and annotate the Likhibi lexicon.

Research shards are JSON Lines files in datasets/raw/vocabulary/research/:

    new_<name>.jsonl      words not yet in the lexicon (loanwords, everyday words)
    enrich_<name>.jsonl   annotations for words the lexicon already lists

Every row carries the same fields, and every field must be filled, so the build
can trust a shard without second-guessing it. A row says how it was made:
"web-verified" when a page in source_urls supports it, "model-annotated" otherwise.
Neither counts as verified: only a speaker verifies an entry.

    python -m nlp_research.lexicon.validate_research                  # every shard
    python -m nlp_research.lexicon.validate_research path/to/shard.jsonl
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path

from nlp_research.prediction.build_keyboard_assets import REPO_ROOT

RESEARCH_DIR = REPO_ROOT / "datasets" / "raw" / "vocabulary" / "research"
LEXICON_JSON = REPO_ROOT / "datasets" / "processed" / "lexical_database" / "nagamese_lexicon.json"

WORD = re.compile(r"^[a-z]{2,}$")
UPOS = {"ADJ", "ADP", "ADV", "AUX", "CCONJ", "DET", "INTJ", "NOUN", "NUM", "PART", "PRON", "PROPN", "SCONJ", "VERB", "X"}
DOMAINS = {
    "greetings", "pronouns", "numbers", "time", "weather", "family", "body", "health", "food", "nature",
    "farming", "feelings", "colours", "describing", "actions", "grammar", "places", "culture", "religion",
    "community", "civic", "home", "education", "technology", "travel", "money", "work", "leisure",
    "clothing", "media", "slang", "compounds",
}
ORIGINS = {
    "Nagamese", "Assamese", "Bengali", "Hindi loanword", "English loanword", "Persian-Arabic via Hindi",
    "Sanskrit (tatsama)", "Naga languages", "Biblical name", "Proper name",
}
REGISTERS = {"everyday", "scripture", "both", "formal", "slang"}
# web-verified and model-annotated come from research agents; the rest from auto_annotate.py.
METHODS = {"web-verified", "model-annotated", "dictionary (WordNet)", "hand-tagged list", "derived from root", "name list"}
# Fields that must be present and non-empty. pos_alternatives and variants must be
# present but may be empty lists: many words have one part of speech and one spelling.
REQUIRED = ("word", "pos", "english_definition", "glosses", "domains", "origin", "source_form",
            "morphology", "features", "register", "usage_example", "method", "confidence")
LISTS_MAY_BE_EMPTY = ("pos_alternatives", "variants", "source_urls")


def existing_lemmas() -> set[str]:
    if not LEXICON_JSON.is_file():
        return set()
    known = {e["lemma"] for e in json.loads(LEXICON_JSON.read_text(encoding="utf-8")) if e.get("status") not in ("researched", "derived")}
    # Words a source lists but the origin shares leave out are still listed words.
    rejected = LEXICON_JSON.with_name("lexicon_rejected.tsv")
    if rejected.is_file():
        for line in rejected.read_text(encoding="utf-8").splitlines()[1:]:
            word, source, reason = (line.split("	") + ["", ""])[:3]
            if reason.startswith("over the") and "web-research" not in source:
                known.add(word)
    return known


def check_row(row: dict) -> list[str]:
    errors = []
    for name in REQUIRED:
        value = row.get(name)
        if value is None or value == "" or value == []:
            errors.append(f"missing {name}")
    for name in LISTS_MAY_BE_EMPTY:
        if not isinstance(row.get(name, []), list):
            errors.append(f"{name} must be a list")
    word = row.get("word", "")
    if word and not WORD.match(word):
        errors.append(f"word {word!r} is not one lowercase a-z word")
    if row.get("pos") and row["pos"] not in UPOS:
        errors.append(f"pos {row['pos']!r} is not a UPOS tag")
    for alt in row.get("pos_alternatives") or []:
        if not isinstance(alt, dict) or alt.get("tag") not in UPOS or not alt.get("gloss"):
            errors.append(f"bad pos_alternative {alt!r}")
    for name in ("glosses", "domains", "features"):
        if row.get(name) is not None and not isinstance(row[name], list):
            errors.append(f"{name} must be a list")
    bad_domains = [d for d in row.get("domains") or [] if d not in DOMAINS]
    if bad_domains:
        errors.append(f"unknown domains {bad_domains}")
    if row.get("origin") and row["origin"] not in ORIGINS:
        errors.append(f"origin {row['origin']!r} not in {sorted(ORIGINS)}")
    if row.get("register") and row["register"] not in REGISTERS:
        errors.append(f"register {row['register']!r} not in {sorted(REGISTERS)}")
    if row.get("method") and row["method"] not in METHODS:
        errors.append(f"method {row['method']!r} not in {sorted(METHODS)}")
    if row.get("method") == "web-verified" and not any(str(u).startswith("http") for u in row.get("source_urls") or []):
        errors.append("web-verified without a source URL")
    confidence = row.get("confidence")
    if confidence is not None and not (isinstance(confidence, (int, float)) and 0 < confidence <= 1):
        errors.append(f"confidence {confidence!r} not in (0, 1]")
    for variant in row.get("variants") or []:
        if not isinstance(variant, str) or not WORD.match(variant):
            errors.append(f"variant {variant!r} is not one lowercase a-z word")
    return errors


def read_shard(path: Path) -> tuple[list[dict], list[str]]:
    rows, errors = [], []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            errors.append(f"{path.name}:{number}: not JSON ({error})")
            continue
        rows.append(row)
        errors.extend(f"{path.name}:{number} {row.get('word')!r}: {e}" for e in check_row(row))
    return rows, errors


def validate(paths: list[Path]) -> tuple[dict[str, int], list[str]]:
    known = existing_lemmas()
    counts: dict[str, int] = {}
    errors: list[str] = []
    seen_new: Counter = Counter()
    for path in paths:
        rows, shard_errors = read_shard(path)
        errors.extend(shard_errors)
        counts[path.name] = len(rows)
        within = Counter(r.get("word") for r in rows)
        errors.extend(f"{path.name}: {w!r} appears {n} times" for w, n in within.items() if n > 1)
        is_new = path.name.startswith("new_")
        for row in rows:
            word = row.get("word")
            if is_new:
                if word in known:
                    errors.append(f"{path.name}: {word!r} is already in the lexicon (put it in an enrich_ shard)")
                seen_new[word] += 1
            elif word not in known:
                errors.append(f"{path.name}: {word!r} is not in the lexicon (put it in a new_ shard)")
    errors.extend(f"{w!r} is in {n} new_ shards" for w, n in seen_new.items() if n > 1)
    return counts, errors


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    args = sys.argv[1:] if argv is None else argv
    paths = [Path(a) for a in args] or sorted(RESEARCH_DIR.glob("*.jsonl"))
    counts, errors = validate(paths)
    for name, count in counts.items():
        print(f"{name}: {count} rows")
    for error in errors[:200]:
        print("ERROR", error)
    if len(errors) > 200:
        print(f"... and {len(errors) - 200} more errors")
    print(f"{sum(counts.values())} rows, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
