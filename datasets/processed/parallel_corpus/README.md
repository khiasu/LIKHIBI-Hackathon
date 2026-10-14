# English–Nagamese parallel corpus

`bible_parallel_corpus.tsv` holds 7,953 verse-aligned English–Nagamese pairs covering all 27 books of the New Testament. Rebuild it with:

```
python -m nlp_research.corpus.build_bible_parallel
```

`build_report.json` records the counts from the last build.

## Columns

| Column | Meaning |
| --- | --- |
| `src_id` | Row id, `BIB_00001` onwards |
| `verse_id` | `BOOK_chapter_verse`, for example `MAT_1_1`. Merged verses read `3JN_1_14` with both texts joined. |
| `book`, `chapter`, `verse` | Reference in readable form |
| `english_text`, `nagamese_text` | The aligned pair |
| `domain` | `bible` for every row here. Other corpora use `everyday`, `news` and so on. |
| `split` | `train`, `dev` (Colossians, 2 Thessalonians) or `test` (1–3 John, Titus, Philemon, Jude). Whole books are held out so near-duplicate passages cannot leak between splits. |
| `origin` | Source editions |

The `dev` and `test` rows are also exported, in both directions, to `datasets/processed/dev_sets/` and `datasets/processed/test_sets/`. Engines must not use them for training or retrieval.

## Sources and licenses

**Nagamese:** *Indian Standard Version (ISV) Nagamese*, New Testament. Copyright © 2019 Bridge Connectivity Solutions Pvt. Ltd. Licensed under [Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Source: [eBible.org](https://ebible.org/Bible/details.php?id=nag). The original USFM files are in `datasets/raw/parallel/ebible_nag_usfm/`.

**English:** text derived from the *World English Bible*, which is in the public domain. Source: [eBible.org](https://ebible.org/Bible/details.php?id=engwebp). The original USFM files are in `datasets/raw/parallel/english_web_usfm/`.

**Changes made to both texts:**

- Footnotes, cross-references, section headings and word-level markup were removed.
- Verses were aligned by reference. Romans 14:24–26 in the English edition was matched to Romans 16:25–27 in the Nagamese.
- The two halves of 3 John 1:14 in the Nagamese were joined.
- Five Nagamese verses with no English counterpart were left out: Luke 17:36, Acts 8:37, 15:34, 24:7, and Revelation 12:18.
- A missing space after punctuation was added in 13 Nagamese verses.

These changes are ours, and the copyright holder does not necessarily endorse them. Because the Nagamese text is ShareAlike, this corpus and anything derived from it must also be shared under CC BY-SA 4.0.
