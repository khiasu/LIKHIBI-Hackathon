"""
Rebuild the English–Nagamese Bible parallel corpus from eBible.org USFM.

This replaces the PDF-extraction pipeline, whose English column was never filled
in and whose verse numbers were partly wrong.

Inputs, downloaded from eBible.org:
    datasets/raw/parallel/ebible_nag_usfm/    ISV Nagamese New Testament, CC BY-SA 4.0
    datasets/raw/parallel/english_web_usfm/   World English Bible, public domain

Outputs:
    datasets/processed/parallel_corpus/bible_parallel_corpus.tsv
    datasets/processed/parallel_corpus/build_report.json
    datasets/processed/test_sets/bible_heldout_test_{en_nag,nag_en}.tsv
    datasets/processed/dev_sets/bible_dev_{en_nag,nag_en}.tsv

Whole books are held out for testing and development, because random verse
splits leak near-identical passages (the Gospels repeat each other) into both sides.

Usage, from the repository root:
    python -m nlp_research.corpus.build_bible_parallel
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import statistics
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from .usfm import Verse, read_usfm_dir

REPO_ROOT = Path(__file__).resolve().parents[2]

BOOKS: tuple[tuple[str, str], ...] = (
    ("MAT", "Matthew"), ("MRK", "Mark"), ("LUK", "Luke"), ("JHN", "John"), ("ACT", "Acts"),
    ("ROM", "Romans"), ("1CO", "1 Corinthians"), ("2CO", "2 Corinthians"), ("GAL", "Galatians"),
    ("EPH", "Ephesians"), ("PHP", "Philippians"), ("COL", "Colossians"), ("1TH", "1 Thessalonians"),
    ("2TH", "2 Thessalonians"), ("1TI", "1 Timothy"), ("2TI", "2 Timothy"), ("TIT", "Titus"),
    ("PHM", "Philemon"), ("HEB", "Hebrews"), ("JAS", "James"), ("1PE", "1 Peter"), ("2PE", "2 Peter"),
    ("1JN", "1 John"), ("2JN", "2 John"), ("3JN", "3 John"), ("JUD", "Jude"), ("REV", "Revelation"),
)
TEST_BOOKS = frozenset({"1JN", "2JN", "3JN", "TIT", "PHM", "JUD"})
DEV_BOOKS = frozenset({"COL", "2TH"})
# Pairs whose character lengths differ by more than this factor are almost always
# misaligned (a verse moved into its neighbour), so they are dropped.
MAX_LENGTH_RATIO = 3.0

ORIGIN = "ebible:nag+engwebp"

# Known versification differences between the two editions, mapped onto shared numbers.
# The World English Bible places the Romans doxology at 14:24-26; the Nagamese at 16:25-27.
ENGLISH_REMAP = {("ROM", 14, 24): (16, 25), ("ROM", 14, 25): (16, 26), ("ROM", 14, 26): (16, 27)}
# The Nagamese splits the last verse of 3 John into 14 and 15; English keeps one verse 14.
NAGAMESE_REMAP = {("3JN", 1, 15): (1, 14)}


def remap(verses: list[Verse], mapping: dict[tuple[str, int, int], tuple[int, int]]) -> list[Verse]:
    remapped = []
    for verse in verses:
        target = mapping.get((verse.book, verse.chapter, verse.start))
        if target is None:
            remapped.append(verse)
        else:
            chapter, number = target
            remapped.append(Verse(verse.book, chapter, number, number, verse.text))
    return remapped


@dataclass(frozen=True)
class Pair:
    book: str
    chapter: int
    start: int
    end: int
    english: str
    nagamese: str

    @property
    def verse_id(self) -> str:
        span = f"{self.start}" if self.start == self.end else f"{self.start}-{self.end}"
        return f"{self.book}_{self.chapter}_{span}"

    @property
    def verse_label(self) -> str:
        return f"{self.start}" if self.start == self.end else f"{self.start}-{self.end}"


def split_for(book: str) -> str:
    if book in TEST_BOOKS:
        return "test"
    if book in DEV_BOOKS:
        return "dev"
    return "train"


def align_chapter(nagamese: list[Verse], english: list[Verse]) -> tuple[list[Pair], Counter[str]]:
    """Group verses whose ranges overlap, so a bridge such as \\v 1-2 on either side still aligns."""
    spans = sorted(
        [(v.start, v.end, "nag", v.text) for v in nagamese] + [(v.start, v.end, "eng", v.text) for v in english],
        key=lambda span: (span[0], span[1]),
    )
    pairs: list[Pair] = []
    stats: Counter[str] = Counter()
    group: list[tuple[int, int, str, str]] = []
    group_end = 0

    def close() -> None:
        if not group:
            return
        nag = " ".join(text for _, _, side, text in group if side == "nag")
        eng = " ".join(text for _, _, side, text in group if side == "eng")
        start = min(s for s, _, _, _ in group)
        end = max(e for _, e, _, _ in group)
        if not nag:
            stats["only_english"] += 1
        elif not eng:
            stats["only_nagamese"] += 1
        else:
            if sum(1 for _, _, side, _ in group if side == "nag") > 1 or sum(1 for _, _, side, _ in group if side == "eng") > 1:
                stats["merged_bridges"] += 1
            ref = nagamese[0] if nagamese else english[0]
            pairs.append(Pair(ref.book, ref.chapter, start, end, eng, nag))

    for span in spans:
        if group and span[0] > group_end:
            close()
            group = []
        if not group:
            group_end = span[1]
        group.append(span)
        group_end = max(group_end, span[1])
    close()
    return pairs, stats


def align_books(nag_books: dict[str, list[Verse]], eng_books: dict[str, list[Verse]]) -> tuple[list[Pair], Counter[str]]:
    pairs: list[Pair] = []
    stats: Counter[str] = Counter()
    for code, _ in BOOKS:
        nag = remap(nag_books.get(code, []), NAGAMESE_REMAP)
        eng = remap(eng_books.get(code, []), ENGLISH_REMAP)
        if not nag or not eng:
            stats[f"missing_book_{code}"] += 1
            continue
        chapters = sorted({v.chapter for v in nag} | {v.chapter for v in eng})
        for chapter in chapters:
            chapter_pairs, chapter_stats = align_chapter(
                [v for v in nag if v.chapter == chapter],
                [v for v in eng if v.chapter == chapter],
            )
            pairs.extend(chapter_pairs)
            stats.update(chapter_stats)
    return pairs, stats


def length_ratio(pair: Pair) -> float:
    return len(pair.nagamese) / max(len(pair.english), 1)


def _write_tsv(path: Path, header: list[str], rows: list[list[str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n", quoting=csv.QUOTE_NONE, escapechar=None)
        writer.writerow(header)
        writer.writerows(rows)


def _clean_cell(text: str) -> str:
    return " ".join(text.replace("\t", " ").split())


# A handful of Nagamese verses lack the space after punctuation ("imandar,aru").
_MISSING_SPACE = re.compile(r"([,.;:!?])(?=[^\W\d_])")


def fix_nagamese_spacing(text: str) -> str:
    return _MISSING_SPACE.sub(r"\1 ", text)


def build(nag_dir: Path, eng_dir: Path, datasets_dir: Path) -> dict:
    nag_books = read_usfm_dir(nag_dir)
    eng_books = read_usfm_dir(eng_dir)
    pairs, stats = align_books(nag_books, eng_books)

    kept: list[Pair] = []
    for pair in pairs:
        ratio = length_ratio(pair)
        if ratio > MAX_LENGTH_RATIO or ratio < 1 / MAX_LENGTH_RATIO:
            stats["dropped_length_ratio"] += 1
            continue
        kept.append(pair)

    names = dict(BOOKS)
    corpus_rows = [
        [
            f"BIB_{index:05d}",
            pair.verse_id,
            names[pair.book],
            str(pair.chapter),
            pair.verse_label,
            _clean_cell(pair.english),
            _clean_cell(fix_nagamese_spacing(pair.nagamese)),
            "bible",
            split_for(pair.book),
            ORIGIN,
        ]
        for index, pair in enumerate(kept, start=1)
    ]
    _write_tsv(
        datasets_dir / "processed" / "parallel_corpus" / "bible_parallel_corpus.tsv",
        ["src_id", "verse_id", "book", "chapter", "verse", "english_text", "nagamese_text", "domain", "split", "origin"],
        corpus_rows,
    )

    for split, folder, stem in (("test", "test_sets", "bible_heldout_test"), ("dev", "dev_sets", "bible_dev")):
        selected = [row for row in corpus_rows if row[8] == split]
        _write_tsv(
            datasets_dir / "processed" / folder / f"{stem}_en_nag.tsv",
            ["id", "source_text", "target_text", "domain"],
            [[row[1], row[5], row[6], "bible"] for row in selected],
        )
        _write_tsv(
            datasets_dir / "processed" / folder / f"{stem}_nag_en.tsv",
            ["id", "source_text", "target_text", "domain"],
            [[row[1], row[6], row[5], "bible"] for row in selected],
        )

    ratios = [length_ratio(pair) for pair in kept]
    split_counts = Counter(split_for(pair.book) for pair in kept)
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sources": {
            "nagamese": {
                "path": nag_dir.as_posix(),
                "title": "Indian Standard Version (ISV) Nagamese New Testament",
                "license": "CC BY-SA 4.0, copyright 2019 Bridge Connectivity Solutions",
                "url": "https://ebible.org/Bible/details.php?id=nag",
                "verses": sum(len(v) for v in nag_books.values()),
            },
            "english": {
                "path": eng_dir.as_posix(),
                "title": "World English Bible (public domain), footnotes and markup removed",
                "license": "Public domain",
                "url": "https://ebible.org/Bible/details.php?id=engwebp",
                "verses": sum(len(v) for v in eng_books.values()),
            },
        },
        "aligned_pairs": len(pairs),
        "kept_pairs": len(kept),
        "splits": dict(split_counts),
        "test_books": sorted(TEST_BOOKS),
        "dev_books": sorted(DEV_BOOKS),
        "length_ratio": {
            "max_allowed": MAX_LENGTH_RATIO,
            "median": round(statistics.median(ratios), 3) if ratios else None,
        },
        "events": dict(stats),
        "pairs_per_book": {names[code]: sum(1 for p in kept if p.book == code) for code, _ in BOOKS},
    }
    report_path = datasets_dir / "processed" / "parallel_corpus" / "build_report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--datasets-dir", type=Path, default=REPO_ROOT / "datasets")
    args = parser.parse_args()
    raw = args.datasets_dir / "raw" / "parallel"
    report = build(raw / "ebible_nag_usfm", raw / "english_web_usfm", args.datasets_dir)
    print(json.dumps({k: report[k] for k in ("aligned_pairs", "kept_pairs", "splits", "events", "length_ratio")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
