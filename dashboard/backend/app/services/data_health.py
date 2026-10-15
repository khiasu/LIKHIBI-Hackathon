"""Measures how ready the datasets are for machine translation.

Every number comes from the files themselves, so the page stays honest as the
data improves. Reports are cached and recomputed when a file changes.
"""

from __future__ import annotations

import csv
import json
import re
import threading
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PARALLEL_DIR = Path("processed/parallel_corpus")
LEXICON_FILE = Path("processed/lexical_database/nagamese_lexicon.json")
GLOSSARY_FILE = Path("processed/lexical_database/auto_glossary.tsv")
MONOLINGUAL_FILE = Path("raw/vocabulary/monolingual_nagamese.txt")
TEST_SETS_DIR = Path("processed/test_sets")

NT_BOOKS = (
    "Matthew", "Mark", "Luke", "John", "Acts", "Romans", "1 Corinthians",
    "2 Corinthians", "Galatians", "Ephesians", "Philippians", "Colossians",
    "1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus",
    "Philemon", "Hebrews", "James", "1 Peter", "2 Peter", "1 John", "2 John",
    "3 John", "Jude", "Revelation",
)

# Chapters per New Testament book, to catch verse references that cannot exist.
NT_CHAPTERS = {
    "Matthew": 28, "Mark": 16, "Luke": 24, "John": 21, "Acts": 28, "Romans": 16,
    "1 Corinthians": 16, "2 Corinthians": 13, "Galatians": 6, "Ephesians": 6,
    "Philippians": 4, "Colossians": 4, "1 Thessalonians": 5, "2 Thessalonians": 3,
    "1 Timothy": 6, "2 Timothy": 4, "Titus": 3, "Philemon": 1, "Hebrews": 13,
    "James": 5, "1 Peter": 5, "2 Peter": 3, "1 John": 5, "2 John": 1, "3 John": 1,
    "Jude": 1, "Revelation": 22,
}

_PLACEHOLDER_ENGLISH = re.compile(r"^\[English translation for .*\]$")
_PLACEHOLDER_GLOSS = re.compile(r"^definition placeholder", re.IGNORECASE)
# Footnote markers (*, dagger, double dagger, section sign) or chapter:verse references inside
# verse text usually mean footnotes or running page headers leaked in during
# PDF extraction.
_EXTRACTION_NOISE = re.compile(r"[*†‡§]|\b\d{1,3}:\d{1,3}\b")
_WORD = re.compile(r"[^\W\d_]+")
LONG_ROW_TOKENS = 100

_cache_lock = threading.Lock()
_cache: dict[tuple[Any, ...], dict[str, Any]] = {}


def tokenize(text: str) -> list[str]:
    return _WORD.findall(text.lower())


def _signature(path: Path) -> tuple[str, float, int] | None:
    try:
        stat = path.stat()
    except OSError:
        return None
    return (str(path), stat.st_mtime, stat.st_size)


def _relative(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def _parallel_report(path: Path, root: Path) -> dict[str, Any]:
    """Measure one parallel TSV with 'english_text' and 'nagamese_text' columns.

    An optional 'domain' column (bible, everyday, news...) labels each row.
    Without it, a file whose name contains "bible" counts as Bible text.
    """
    file_domain = "bible" if "bible" in path.stem.lower() else "other"
    report: dict[str, Any] = {
        "file": _relative(path, root),
        "domain": file_domain,
        "schema_ok": True,
        "schema_error": None,
        "rows": 0,
        "english_real": 0,
        "english_placeholder": 0,
        "english_empty": 0,
        "nagamese_empty": 0,
        "duplicate_verse_ids": 0,
        "impossible_references": 0,
        "suspected_noise_rows": 0,
        "long_rows": 0,
        "avg_nagamese_tokens": 0.0,
        "books": {},
        "nt_books_missing": [],
        "real_pairs_by_domain": {},
    }
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE)
        fields = reader.fieldnames or []
        if "english_text" not in fields or "nagamese_text" not in fields:
            report["schema_ok"] = False
            report["schema_error"] = "Expected columns 'english_text' and 'nagamese_text'"
            return report
        has_domain = "domain" in fields
        books: Counter[str] = Counter()
        verse_ids: Counter[str] = Counter()
        domains: Counter[str] = Counter()
        real_by_domain: Counter[str] = Counter()
        token_total = 0
        for row in reader:
            report["rows"] += 1
            english = (row.get("english_text") or "").strip()
            row_domain = ((row.get("domain") or "").strip().lower() if has_domain else "") or file_domain
            domains[row_domain] += 1
            nagamese = (row.get("nagamese_text") or "").strip()
            if not english:
                report["english_empty"] += 1
            elif _PLACEHOLDER_ENGLISH.match(english):
                report["english_placeholder"] += 1
            else:
                report["english_real"] += 1
                real_by_domain[row_domain] += 1
            if not nagamese:
                report["nagamese_empty"] += 1
            if _EXTRACTION_NOISE.search(nagamese):
                report["suspected_noise_rows"] += 1
            n_tokens = len(tokenize(nagamese))
            token_total += n_tokens
            if n_tokens > LONG_ROW_TOKENS:
                report["long_rows"] += 1
            book = row.get("book") or ""
            if book:
                books[book] += 1
            chapter = (row.get("chapter") or "").strip()
            if book in NT_CHAPTERS and chapter.isdigit() and int(chapter) > NT_CHAPTERS[book]:
                report["impossible_references"] += 1
            if row.get("verse_id"):
                verse_ids[row["verse_id"]] += 1
    report["duplicate_verse_ids"] = sum(1 for count in verse_ids.values() if count > 1)
    report["avg_nagamese_tokens"] = round(token_total / report["rows"], 1) if report["rows"] else 0.0
    report["books"] = dict(books.most_common())
    report["real_pairs_by_domain"] = dict(real_by_domain.most_common())
    if has_domain:
        report["domain"] = next(iter(domains)) if len(domains) == 1 else "mixed"
    if books:
        report["nt_books_missing"] = [book for book in NT_BOOKS if book not in books]
    return report


def _monolingual_report(path: Path, root: Path) -> tuple[dict[str, Any], frozenset[str]]:
    report: dict[str, Any] = {
        "file": _relative(path, root),
        "exists": path.is_file(),
        "lines": 0,
        "tokens": 0,
        "word_types": 0,
    }
    if not report["exists"]:
        return report, frozenset()
    types: set[str] = set()
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            words = tokenize(line)
            report["lines"] += 1
            report["tokens"] += len(words)
            types.update(words)
    report["word_types"] = len(types)
    return report, frozenset(types)


def _lexicon_report(path: Path, root: Path, corpus_types: frozenset[str]) -> dict[str, Any]:
    report: dict[str, Any] = {
        "file": _relative(path, root),
        "exists": path.is_file(),
        "entries": 0,
        "glossed": 0,
        "verified": 0,
        "pos_tagged": 0,
        "attested_in_corpus": 0,
        "etymology": {},
        "auto_glossary_entries": 0,
    }
    if not report["exists"]:
        return report
    data = json.loads(path.read_text(encoding="utf-8"))
    entries = data if isinstance(data, list) else data.get("entries", [])
    etymology: Counter[str] = Counter()
    for entry in entries:
        report["entries"] += 1
        gloss = str(entry.get("english_definition") or "").strip()
        if gloss and not _PLACEHOLDER_GLOSS.match(gloss):
            report["glossed"] += 1
        if entry.get("is_validated"):
            report["verified"] += 1
        if str(entry.get("pos_category") or "").strip().lower() not in {"", "unknown"}:
            report["pos_tagged"] += 1
        if str(entry.get("lemma") or "").lower() in corpus_types:
            report["attested_in_corpus"] += 1
        etymology[str(entry.get("etymology_origin") or "Unknown")] += 1
    report["etymology"] = dict(etymology.most_common())
    return report


def _count_rows(path: Path) -> int:
    with path.open(encoding="utf-8") as handle:
        return max(sum(1 for line in handle if line.strip()) - 1, 0)


def _test_set_report(path: Path, root: Path) -> dict[str, Any]:
    """Rows in one test set. Bible test sets are reported but never count as everyday ones."""
    domain = "bible" if "bible" in path.stem.lower() else "everyday"
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE)
        if reader.fieldnames and "domain" in reader.fieldnames:
            domains = Counter((row.get("domain") or "").strip().lower() or domain for row in reader)
            domain = domains.most_common(1)[0][0] if domains else domain
    return {"file": _relative(path, root), "rows": _count_rows(path), "domain": domain}


def _file_reports(datasets_dir: Path) -> dict[str, Any]:
    parallel_files = sorted((datasets_dir / PARALLEL_DIR).glob("*.tsv"))
    test_files = sorted((datasets_dir / TEST_SETS_DIR).glob("*.tsv"))
    lexicon = datasets_dir / LEXICON_FILE
    glossary = datasets_dir / GLOSSARY_FILE
    monolingual = datasets_dir / MONOLINGUAL_FILE
    key = tuple(_signature(path) for path in (*parallel_files, *test_files, lexicon, glossary, monolingual))
    key = (str(datasets_dir), len(parallel_files), len(test_files), *key)
    with _cache_lock:
        cached = _cache.get(key)
    if cached is not None:
        return cached

    mono_report, corpus_types = _monolingual_report(monolingual, datasets_dir)
    lexicon_report = _lexicon_report(lexicon, datasets_dir, corpus_types)
    lexicon_report["auto_glossary_entries"] = _count_rows(glossary) if glossary.is_file() else 0
    reports = {
        "parallel": [_parallel_report(path, datasets_dir) for path in parallel_files],
        "monolingual": mono_report,
        "lexicon": lexicon_report,
        "test_sets": [_test_set_report(path, datasets_dir) for path in test_files],
    }
    with _cache_lock:
        _cache.clear()
        _cache[key] = reports
    return reports


def _check(check_id: str, label: str, current: int, target: int, hint: str) -> dict[str, Any]:
    return {
        "id": check_id,
        "label": label,
        "current": current,
        "target": target,
        "passed": current >= target,
        "hint": hint,
    }


def build_data_health(
    datasets_dir: Path,
    feedback_counts: dict[str, int],
    approved_pairs: int,
    eval_run_count: int,
) -> dict[str, Any]:
    files = _file_reports(datasets_dir)
    parallel = files["parallel"]
    bible_pairs = sum(r["real_pairs_by_domain"].get("bible", 0) for r in parallel)
    other_pairs = sum(r["english_real"] for r in parallel) - bible_pairs
    test_rows = sum(t["rows"] for t in files["test_sets"] if t["domain"] != "bible")

    checks = [
        _check(
            "parallel_pairs",
            "Sentence pairs with real English text",
            bible_pairs + other_pairs,
            5000,
            "Rebuild the Bible corpus from eBible.org text and align it with a "
            "public-domain English Bible by verse reference.",
        ),
        _check(
            "everyday_pairs",
            "Everyday (non-Bible) sentence pairs",
            other_pairs + approved_pairs,
            1000,
            "Import FNPC-25 and the WMT26 en-nag data, and approve corrections "
            "on the Contributions page.",
        ),
        _check(
            "test_set",
            "Held-out everyday test sentences",
            test_rows,
            200,
            "Have two speakers translate about 200 everyday sentences into "
            "datasets/processed/test_sets/. Keep them out of training and retrieval.",
        ),
        _check(
            "glossary",
            "Lexicon meanings checked by speakers",
            files["lexicon"]["verified"],
            500,
            "Researched meanings are unverified. Have speakers confirm the most frequent words first, "
            "on the Contribute page or in lexicon_review_queue.tsv.",
        ),
        _check(
            "eval_runs",
            "Recorded evaluation runs",
            eval_run_count,
            1,
            "Run python -m nlp_research.translation.evaluate_engine once an engine is connected.",
        ),
    ]

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "checks": checks,
        "parallel": parallel,
        "monolingual": files["monolingual"],
        "lexicon": files["lexicon"],
        "test_sets": files["test_sets"],
        "community": {
            "approved_pairs": approved_pairs,
            "pending": feedback_counts.get("pending", 0),
            "approved": feedback_counts.get("approved", 0),
            "rejected": feedback_counts.get("rejected", 0),
        },
    }
