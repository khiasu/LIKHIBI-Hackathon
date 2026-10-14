from __future__ import annotations

import csv
import json
from pathlib import Path

from nlp_research.corpus.build_bible_parallel import align_chapter, build, fix_nagamese_spacing
from nlp_research.corpus.usfm import Verse, clean_inline, parse_usfm

NAG_USFM = r"""\id JHN - Nagamese test
\h John
\mt1 John
\c 1
\s1 Shuru te Kotha
\p
\v 1 Shuru te Kotha thakise\f + \fr 1.1 \ft a footnote that must go\f*, aru Kotha Isor logot thakise.
\v 2 Tai shuru te Isor logot thakise.
\c 2
\p
\v 1 Tisra din te Cana te ekta shadi hoise.
\v 2 Jisu aru Tai laga chela khan ke bhi matise.
"""

ENG_USFM = r"""\id JHN 43-JHN-web.sfm World English Bible
\ide UTF-8
\h John
\c 1
\p
\v 1 \w In|strong="G1722"\w* \w the|strong="G3588"\w* \w beginning|strong="G746"\w* \w was|strong="G1510"\w* \w the|strong="G3588"\w* \w Word|strong="G3056"\w*.\x - \xo 1:1 \xt Gen 1:1\x*
\v 2 \w He|strong="G3778"\w* can’\w t|strong="G3588"\w* \wj “stay,”\wj* \w he|strong="G3778"\w* said.
\c 2
\v 1-2 \w On|strong="G1722"\w* the third day there was a wedding in Cana. Jesus also was invited.
"""


def test_parse_usfm_drops_notes_and_markup() -> None:
    nag = parse_usfm(NAG_USFM)
    assert [(v.chapter, v.start) for v in nag] == [(1, 1), (1, 2), (2, 1), (2, 2)]
    assert nag[0].text == "Shuru te Kotha thakise, aru Kotha Isor logot thakise."
    assert all("Shuru te Kotha" != v.text for v in nag)  # the \s1 heading is not a verse

    eng = parse_usfm(ENG_USFM)
    assert eng[0].text == "In the beginning was the Word."
    assert eng[1].text == "He can’t “stay,” he said."  # contraction and quote spacing intact
    assert (eng[2].start, eng[2].end) == (1, 2)  # verse bridge


def test_clean_inline_spacing() -> None:
    assert clean_inline(r"\wj “ Woman\wj* , behold") == "“Woman, behold"
    assert fix_nagamese_spacing("imandar,aru Susanna") == "imandar, aru Susanna"


def test_align_chapter_merges_bridges_and_drops_one_sided_verses() -> None:
    nag = [Verse("JHN", 2, 1, 1, "Tisra din"), Verse("JHN", 2, 2, 2, "Jisu bhi"), Verse("JHN", 2, 3, 3, "only here")]
    eng = [Verse("JHN", 2, 1, 2, "On the third day. Jesus also.")]
    pairs, stats = align_chapter(nag, eng)
    assert len(pairs) == 1
    assert (pairs[0].start, pairs[0].end) == (1, 2)
    assert pairs[0].nagamese == "Tisra din Jisu bhi"
    assert stats["only_nagamese"] == 1
    assert stats["merged_bridges"] == 1


def test_build_writes_corpus_splits_and_report(tmp_path: Path) -> None:
    nag_dir, eng_dir = tmp_path / "nag", tmp_path / "eng"
    nag_dir.mkdir()
    eng_dir.mkdir()
    (nag_dir / "44-JHNnag.usfm").write_text(NAG_USFM, encoding="utf-8")
    (eng_dir / "73-JHNengwebp.usfm").write_text(ENG_USFM, encoding="utf-8")
    datasets = tmp_path / "datasets"
    report = build(nag_dir, eng_dir, datasets)

    assert report["kept_pairs"] == 3  # 1:1, 1:2, and the bridged 2:1-2
    with (datasets / "processed/parallel_corpus/bible_parallel_corpus.tsv").open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))
    assert [r["verse_id"] for r in rows] == ["JHN_1_1", "JHN_1_2", "JHN_2_1-2"]
    assert {r["split"] for r in rows} == {"train"}  # John is a training book
    assert rows[0]["domain"] == "bible"
    saved = json.loads((datasets / "processed/parallel_corpus/build_report.json").read_text(encoding="utf-8"))
    assert saved["sources"]["nagamese"]["license"].startswith("CC BY-SA 4.0")
    assert (datasets / "processed/test_sets/bible_heldout_test_en_nag.tsv").is_file()
