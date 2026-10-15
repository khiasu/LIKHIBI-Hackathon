"""
Build the keyboard's word-prediction assets from real Nagamese text.

The keyboard reads four files from app/src/main/assets/models/:

    trie_index.json   every word and its frequency as a character tree, for completions
    unigrams.json     Kneser-Ney unigram probabilities, the last fallback for the next word
    bigrams.json      the 20 likeliest next words after each word, with the backoff weight
    trigrams.json     the 10 likeliest next words after each word pair, keyed "w1|w2"

The three model tables come from an interpolated modified Kneser-Ney trigram model
(kneser_ney.py), which nlp_research/prediction/evaluate_keyboard.py measures on books
held out from training.

Earlier versions built the trie from nagamese_lexicon.json, whose entries are mostly
generated forms that never occur in real text ("scobypora", "khankhan"). Every word
here comes from the ISV Nagamese New Testament (CC BY-SA 4.0), read straight from
the USFM source so no footnote markers or page headers leak in.

    python -m nlp_research.prediction.build_keyboard_assets            # all books, copied into the app
    python -m nlp_research.prediction.build_keyboard_assets --holdout  # without the test books, for evaluation

The trie marks metadata with keys that can never be letters: "$" ends a word and
"$f" holds its frequency. The old format used "e" and "f", which collided with the
letters e and f and made every word containing them unreachable.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from collections import Counter
from pathlib import Path

from nlp_research.corpus.build_bible_parallel import TEST_BOOKS, fix_nagamese_spacing
from nlp_research.corpus.usfm import read_usfm_dir
from nlp_research.prediction.kneser_ney import KneserNeyTrigram
from nlp_research.preprocessing.tokenizer import NagameseTokenizer

REPO_ROOT = Path(__file__).resolve().parents[2]
NAGAMESE_USFM = REPO_ROOT / "datasets" / "raw" / "parallel" / "ebible_nag_usfm"
OUTPUT_DIR = REPO_ROOT / "datasets" / "processed" / "prediction_models"
APP_ASSETS = REPO_ROOT / "app" / "src" / "main" / "assets" / "models"
VOCABULARY_FILE = REPO_ROOT / "datasets" / "processed" / "lexical_database" / "attested_vocabulary.tsv"
ASSET_FILES = ("trie_index.json", "unigrams.json", "bigrams.json", "trigrams.json")
SOURCE = "isv-nagamese-nt"

_SENTENCE_END = re.compile(r"(?<=[.?!])\s+")


def sentences(usfm_dir: Path = NAGAMESE_USFM, exclude_books: frozenset[str] = frozenset()) -> list[str]:
    """Nagamese sentences from the New Testament, one per item, verse text split at . ? !"""
    found: list[str] = []
    for book, verses in read_usfm_dir(usfm_dir).items():
        if book in exclude_books:
            continue
        for verse in verses:
            text = " ".join(fix_nagamese_spacing(verse.text).split())
            found.extend(part for part in _SENTENCE_END.split(text) if part.strip())
    return found


def word_counts(lines: list[str]) -> Counter[str]:
    tokenizer = NagameseTokenizer()
    counts: Counter[str] = Counter()
    for line in lines:
        counts.update(tokenizer.tokenize(line))
    return counts


def trie_json(counts: Counter[str]) -> dict:
    """The keyboard's trie: nested letters, with "$" marking a word and "$f" its frequency."""
    root: dict = {}
    for word, count in counts.items():
        node = root
        for letter in word:
            node = node.setdefault(letter, {})
        node["$"] = 1
        node["$f"] = count
    return {"total_words": len(counts), "format": 2, "trie_root": root}


LEXICON_JSON = REPO_ROOT / "datasets" / "processed" / "lexical_database" / "nagamese_lexicon.json"
DEFAULT_CURATED_WEIGHT = 15  # a curated word that the keyboard's own list does not weight
RESEARCHED_WEIGHT = 8  # a researched everyday word or loanword, below anything a person listed


def lexicon_tables() -> tuple[dict[str, int], dict[str, str]]:
    """Everyday-word weights and display forms from the lexicon (nlp_research/lexicon/build_lexicon.py).

    Candidates and derived forms are left out: candidates reach the keyboard only after a
    speaker accepts them, and derived forms were generated, not seen. Researched words
    (everyday loanwords such as school and office) come in at a low weight.
    """
    if not LEXICON_JSON.is_file():
        return {}, {}
    entries = json.loads(LEXICON_JSON.read_text(encoding="utf-8"))
    prior: dict[str, int] = {}
    display: dict[str, str] = {}
    for entry in entries:
        if entry["status"] in ("candidate", "derived") and not entry.get("is_validated"):
            continue
        # Profanity stays in the lexicon for NLP but is never suggested unless a speaker validated it.
        if {"vulgar", "offensive"} & set(entry.get("features") or []) and not entry.get("is_validated"):
            continue
        word = entry["lemma"]
        if entry["keyboard_weight"] or entry["status"] == "curated" or entry.get("is_validated"):
            prior[word] = entry["keyboard_weight"] or DEFAULT_CURATED_WEIGHT
        elif entry["status"] == "researched" and not entry.get("note"):
            prior[word] = RESEARCHED_WEIGHT
        if entry.get("display") and entry["display"] != word:
            display[word] = entry["display"]
    # Names the lexicon leaves out for its origin shares keep their capitals from the New Testament.
    from nlp_research.lexicon.build_lexicon import nt_counts_and_display

    for word, proper in nt_counts_and_display()[1].items():
        display.setdefault(word, proper)
    return prior, display


def build(output_dir: Path = OUTPUT_DIR, exclude_books: frozenset[str] = frozenset(), copy_to_app: bool = True) -> dict:
    lines = sentences(exclude_books=exclude_books)
    counts = word_counts(lines)
    output_dir.mkdir(parents=True, exist_ok=True)

    tokenizer = NagameseTokenizer()
    model = KneserNeyTrigram().fit([tokens for tokens in (tokenizer.tokenize(line) for line in lines) if tokens])
    for name, table in zip(("unigrams.json", "bigrams.json", "trigrams.json"), model.export()):
        (output_dir / name).write_text(json.dumps(table, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (output_dir / "trie_index.json").write_text(
        json.dumps(trie_json(counts), ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    report = {
        "source": "ISV Nagamese New Testament (eBible.org, CC BY-SA 4.0), read from USFM",
        "model": "interpolated modified Kneser-Ney trigram (nlp_research/prediction/kneser_ney.py)",
        "excluded_books": sorted(exclude_books),
        "sentences": len(lines),
        "tokens": sum(counts.values()),
        "distinct_words": len(counts),
        "words_seen_once": sum(1 for c in counts.values() if c == 1),
        "discounts": {"trigram": model.tri.d, "bigram": model.bi.d, "unigram": model.uni.d},
    }
    (output_dir / "build_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    # From the lexicon: an everyday-word prior (curated words the corpus lacks, such as
    # loanwords, slang and place names) and the capitalised form of names.
    prior, display = lexicon_tables()
    (output_dir / "lexicon_prior.json").write_text(json.dumps(prior, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (output_dir / "display_forms.json").write_text(json.dumps(display, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    report["lexicon_prior_words"] = len(prior)
    report["display_forms"] = len(display)

    if copy_to_app:
        APP_ASSETS.mkdir(parents=True, exist_ok=True)
        for name in ASSET_FILES + ("lexicon_prior.json", "display_forms.json"):
            shutil.copyfile(output_dir / name, APP_ASSETS / name)
    if not exclude_books:
        VOCABULARY_FILE.parent.mkdir(parents=True, exist_ok=True)
        with VOCABULARY_FILE.open("w", encoding="utf-8", newline="") as handle:
            handle.write("word\tcount\tsource\n")
            handle.writelines(f"{word}\t{count}\t{SOURCE}\n" for word, count in counts.most_common())
    return report


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Build the keyboard's prediction assets from real Nagamese text.")
    parser.add_argument(
        "--holdout",
        action="store_true",
        help="leave out the test books (1-3 John, Titus, Philemon, Jude) and write to a separate folder for evaluation",
    )
    args = parser.parse_args(argv)
    if args.holdout:
        report = build(OUTPUT_DIR / "holdout", TEST_BOOKS, copy_to_app=False)
    else:
        report = build()
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
