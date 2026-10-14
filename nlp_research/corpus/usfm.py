"""
Minimal USFM reader for verse-aligned scripture text.

USFM (Unified Standard Format Markers) is the source format eBible.org publishes.
This reader keeps only verse text: it drops footnotes, cross references, section
headings and book titles, and strips character markup such as Strong's-number
word tags (``\\w grace|strong="G5485"\\w*`` becomes ``grace``).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

# Notes and other character spans whose *content* must go: footnotes, cross references,
# figures, alternate or published verse numbers, and alternate chapter numbers.
_REMOVED_SPANS = re.compile(r"\\(f|fe|ef|x|ex|fig|rq|va|vp|ca|cp)\s.*?\\\1\*", re.DOTALL)
# Word-level attributes, e.g. |strong="G5485" before a closing \w* marker.
_ATTRIBUTES = re.compile(r"\|[^\\]*(?=\\\+?[a-z]+[0-9]*\*)")
# Closing markers such as \w* or \+nd* render as nothing; the text after them is kept.
_CLOSING_MARKER = re.compile(r"\\\+?[a-z]+[0-9]*\*")
# An opening marker owns the single space after it (USFM's delimiter), so removing
# both reproduces the rendered text: "can’\w t\w*" renders as "can’t".
_OPENING_MARKER = re.compile(r"\\\+?[a-z]+[0-9]*[ \t]?")
# Lines that hold titles, headings or metadata rather than verse text.
_SKIP_LINE = re.compile(
    r"^\s*\\(id|ide|h|toc\d*|toca\d*|mt\d*|mte\d*|ms\d*|mr|s\d*|sr|r|d|sp|cl|cd|rem|sts|"
    r"is\d*|ip|ipi|im|imi|iot|io\d*|ib|ie|imt\d*|imte\d*|iex|ili\d*|ipr|iq\d*|periph|usfm)\b"
)
_CHAPTER_OR_VERSE = re.compile(r"\\c\s+(\d+)|\\v\s+(\d+)[a-z]?(?:-(\d+)[a-z]?)?")
_SPACE = re.compile(r"\s+")
_SPACE_BEFORE_PUNCT = re.compile(r"\s+([,.;:!?”’)\]])")
_SPACE_AFTER_OPENER = re.compile(r"([“‘(\[])\s+")


@dataclass(frozen=True)
class Verse:
    book: str  # three-letter USFM code, e.g. "MAT"
    chapter: int
    start: int
    end: int  # equals start unless the source bridges verses, e.g. \v 1-2
    text: str


def clean_inline(text: str) -> str:
    """Strip character markup from a verse's raw USFM text."""
    text = _ATTRIBUTES.sub("", text)
    text = _CLOSING_MARKER.sub("", text)
    text = _OPENING_MARKER.sub("", text)
    text = _SPACE.sub(" ", text).strip()
    text = _SPACE_AFTER_OPENER.sub(r"\1", text)
    return _SPACE_BEFORE_PUNCT.sub(r"\1", text)


def book_code(usfm: str) -> str:
    match = re.search(r"\\id\s+([0-9A-Z]{3})", usfm)
    if not match:
        raise ValueError("USFM text has no \\id line")
    return match.group(1)


def parse_usfm(usfm: str) -> list[Verse]:
    """Return the verses of one USFM book, in order."""
    book = book_code(usfm)
    body = _REMOVED_SPANS.sub(" ", usfm)
    body = "\n".join(line for line in body.splitlines() if not _SKIP_LINE.match(line))

    verses: list[Verse] = []
    chapter = 0
    current: tuple[int, int] | None = None
    position = 0
    pieces: list[str] = []

    def flush() -> None:
        if current is not None:
            text = clean_inline(" ".join(pieces))
            if text:
                verses.append(Verse(book, chapter, current[0], current[1], text))

    for match in _CHAPTER_OR_VERSE.finditer(body):
        if current is not None:
            pieces.append(body[position : match.start()])
        if match.group(1):  # \c N
            flush()
            chapter, current, pieces = int(match.group(1)), None, []
        else:  # \v N or \v N-M
            flush()
            start = int(match.group(2))
            end = int(match.group(3)) if match.group(3) else start
            current, pieces = (start, end), []
        position = match.end()
    if current is not None:
        pieces.append(body[position:])
    flush()
    return verses


def read_usfm_dir(directory: Path) -> dict[str, list[Verse]]:
    """Parse every *.usfm / *.sfm file in a directory, keyed by book code."""
    books: dict[str, list[Verse]] = {}
    # Suffix check rather than several globs: Windows globbing is case-insensitive.
    for path in sorted(p for p in directory.iterdir() if p.suffix.lower() in {".usfm", ".sfm"}):
        verses = parse_usfm(path.read_text(encoding="utf-8-sig"))
        if verses:
            books[verses[0].book] = verses
    return books
