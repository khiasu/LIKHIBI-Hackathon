# Likhibi Nagamese lexicon

Built 15 October 2026 by `python -m nlp_research.lexicon.build_lexicon`.

The lexicon is the word list behind the Likhibi keyboard, and the place where speakers confirm meanings. It has 20,000 entries, and every entry has all its fields filled:
- a part of speech, plus other parts of speech the word can take;
- an English definition and glosses;
- domains;
- origin;
- morphology and features;
- register;
- a usage example.

Every entry records where it came from and how each annotation was made. Nothing in it is marked verified until a Nagamese speaker has checked it.

## At a glance

| Measure | Value |
| --- | --- |
| Entries | 20,000 |
| Found in real Nagamese text (New Testament) | 2,556 |
| Curated by people (keyboard list, thematic word lists, place and tribe names) | 3,292 |
| Candidates from noisy lists, waiting for a speaker | 437 |
| Researched: new everyday words | 4,627 |
| Derived: regular forms generated from listed roots | 9,088 |
| Left out (over an origin share, obscure names, Hindi function words, boilerplate) | 2,008 |
| Entries with a part of speech, definition, glosses, domain, origin, morphology and usage example | 20,000 (100%) |
| Entries listing other parts of speech the word takes | 1,797 |
| Spelling-variant groups | 2,303 |
| Entries flagged as doubtful by research, first in the review queue | 769 |
| Verified by a speaker | 0 |

## Composition by origin

Nagamese is an Assamese-based creole, so its native stratum dominates. The build enforces fixed shares (`ORIGIN_SHARES` in `build_lexicon.py`):

| Stratum | Origins | Share | Entries |
| --- | --- | --- | --- |
| Native | Nagamese, Assamese, Naga languages | 68% | 13,598 |
| English | everyday English loanwords | 22% | 4,400 |
| Other loans and names | Hindi, Persian-Arabic via Hindi, Sanskrit (tatsama), Bengali, Biblical and other names | 10% | 2,002 |

Within each stratum, attested words come first, then curated, candidate, researched and derived ones. Slang counts as curated, and rare scripture names (under 10 New Testament uses) rank after everyday loanwords. Words over a share are listed in `lexicon_rejected.tsv` ("over the other share"). If the native words could not fill 68% of 20,000, the other strata would shrink with them, so the shares always hold.

The 4,627 researched words are 2,482 Assamese-origin, 2,000 English, 52 from Naga languages and 93 others.

The 9,088 derived forms are:
- 3,609 forms of consonant-final verb roots (korikena, koribole, nakoribo, nakora);
- 3,499 forms of -ai verbs, inflected on the stem (bonaise, sikhabo, dikhabi, nadikhai), a paradigm the New Testament attests over 3,000 times;
- 1,690 plurals in -khan (manukhan);
- 290 nouns with te written attached (ghorte), as the New Testament writes usorte and hathte.

## Design rule

Words that real text or a person attests come first, and every entry says how it is known. Two further blocks are marked as such in `status` and never pass for attested words:

- **Researched** entries are everyday words that the sources lack. They come from research lists (see below) and are annotated from a dictionary or by a language model, never presented as verified.
- **Derived** entries fill the lexicon up to 20,000. Regular endings (-ise, -ibo, -khan, attached te) are added only to roots the lexicon already lists. The keyboard never suggests them, because generated forms that nobody types hurt prediction.

## Sources

| Source | What it gives | Licence or status |
| --- | --- | --- |
| ISV Nagamese New Testament ([eBible.org](https://ebible.org/Bible/details.php?id=nag)), read from USFM | Real word counts, contexts, capitalisation, alignments with English, and the usage example of every attested word | CC BY-SA 4.0, © 2019 Bridge Connectivity Solutions |
| World English Bible ([eBible.org](https://ebible.org/Bible/details.php?id=engwebp)) | The English side for alignment and part-of-speech projection | Public domain |
| Likhibi keyboard word list (`NagameseOfflineEngine.kt`) | About 3,000 everyday words, loanwords and their typing weights | Hand-curated by the Likhibi team |
| Earlier thematic lists (`datasets/raw/vocabulary/`) | Everyday vocabulary with domains (food, family, slang and others) | Collected from references listed in each file's header. Web-scraped and Hindi sections are kept only as candidates. |
| Naga place and tribe names (`nlp_research/lexicon/closed_class.py`) | Districts and tribes with their correct capitals and diacritics (Chümoukedima, Sümi) | Facts, listed for spelling |
| [WordNet 3.0](https://wordnet.princeton.edu/) (Princeton), through NLTK | Part of speech, other parts of speech, definition and synonyms of English loanwords (4,111 definitions kept after review) | WordNet licence (free use with attribution) |
| Research lists (`datasets/raw/vocabulary/research/compact/`) | Everyday English and Hindi loanwords used in Nagamese, Assamese-origin everyday words, and annotations for every listed word that no reference covers | Written by language models (Claude Sonnet and Haiku) from their own knowledge, without web browsing. Marked `model-annotated` with a confidence. |
| Assamese Wiktionary through [kaikki.org](https://kaikki.org/dictionary/Assamese/) (`compact/new_assamese_wiktionary.tsv`) | 2,118 Assamese words with part of speech, meaning and source script, respelled as Nagamese (x→h as in huni and hosa, z→j, causative -ua→-ai as in lukai). Obsolete, literary and dialect-only senses, names, English borrowings and words already listed are left out. Each row keeps its Wiktionary URL. | CC BY-SA. Assamese words, so whether Nagamese uses each one is unconfirmed (confidence 0.45 to 0.6). |
| Web research by language-model agents (`compact/new_native_*.tsv`, `new_naga_culture.tsv`, `new_slang_*.tsv`) | Native everyday words, Naga food, festival and institution words (axone, zutho), and slang. Sources include Wikipedia, Wiktionary, Assamese word lists and Urban Dictionary. | Most rows are model knowledge checked against those pages; `doubtful` where a page shows Assamese or Bengali use but not Nagamese use. |

## How each field is made

| Field | Method | Trust |
| --- | --- | --- |
| `status` | `attested` if the New Testament uses it; `curated` if a person listed it; `candidate` if it only appears in a noisy list; `researched` if only a research list gives it; `derived` if generated from a root | Shown on every entry |
| `pos` | In order of trust: hand-tagged function words; verb paradigms from suffix analysis; English tags projected through IBM Model 1 alignments of the parallel verses ([Yarowsky and Ngai, 2001](https://aclanthology.org/N01-1026/)); research (WordNet or a language model), which replaces only a missing or weak tag (projection with under 60% of the votes, or a list heading's guess) | `pos.method` and `pos.confidence` on every entry |
| `pos_alternatives` | Other parts of speech the word takes, with a gloss for each: WordNet's other senses, a researcher's list, or a research tag that disagrees with a stronger one | Same as the research |
| `english_definition`, `definition_method` | A speaker's meaning when there is one (`verified by a speaker`). Otherwise: the hand-tagged gloss; WordNet's definition (`dictionary (WordNet)`); a language model's (`model-annotated`); or, for inflected and derived forms, the root's meaning with the form named (`derived from root`: "Past form of kori ('do')") | `is_validated` stays false until a speaker checks it |
| `gloss_candidates` | English words aligned with the word in the verses (`ibm1-aligned-verses`), then research glosses | Candidates only |
| `morphology`, `root`, `features` | Rule-based suffix analysis for attested forms (kor- + -ise, past), the generated forms' own analysis, and `simple stem (no affix)` for bare words. English verbs note `used with kori as a light verb`. Noun features say countable or mass, animate or inanimate. | Rule-based or researched, unverified |
| `etymology_origin`, `source_form` | Origin (English, Hindi, Persian-Arabic via Hindi, Assamese, Bengali, Sanskrit, Naga languages, Biblical name, Nagamese) from research, with the word in its source language and script where known | Coarse; a linguist should check |
| `usage_example` | The shortest New Testament sentence using the word; otherwise a researcher's sentence; otherwise a plain frame ("Etu school ase.") | Real text for attested words only |
| `register` | `scripture` (only in the New Testament), `everyday`, `both` or `slang`, from which sources attest the word | Follows the sources |
| `orthographic_variants`, `canonical` | Spellings that differ only in sh/s, ee/i, oo/u, v/b or doubled letters, grouped under the most frequent form, plus variants research lists (iskul for school) | Automatic grouping |
| `display` | The capitalised form of names (Khrista, Kohima, November) | Conservative by design |
| `note` | Why research doubted a word (noise, a typo, not Nagamese) | Doubtful entries come first in the review queue |

Part-of-speech tags follow [Universal Dependencies](https://universaldependencies.org/u/pos/).

## Known limits

- **No meaning is verified.** 7,148 definitions are a language model's or Wiktionary's, 2,953 are WordNet's, and 9,703 are built from a root. They are good enough for search and hints, not for a dictionary that people rely on.
- **WordNet's senses were reviewed by a language model, not by people.** It corrected 453 entries where WordNet's most common English sense isn't the one used in Nagaland: "bravo" is now "well done!" rather than "a hired killer", and "mobile" a mobile phone. Some wrong senses may remain.
- **About 45% of the entries are generated forms.** The native share depends on them: written Nagamese is scarce (the New Testament has about 3,300 word types), so 8,795 of the 13,598 native entries are regular verb and noun forms. Every ending used is attested in the New Testament, but a given form may be rare. Derived entries are kept off the keyboard and out of the speakers' meaning tasks.
- **The 2,118 Wiktionary words are Assamese.** Nagamese shares most of its core vocabulary with Assamese, but some of these words, especially formal or rural ones, may not be used in Nagaland. They are the first place a speaker review should look.
- **Slang and profanity are included for NLP use** (89 slang entries; 13 tagged `vulgar` or `offensive`, such as muji and laura). The keyboard never suggests words tagged `vulgar` or `offensive` unless a speaker has validated them.
- **The researched loanwords were not checked against real Nagamese text.** 695 entries are flagged as doubtful.
- **483 Assamese- and Bengali-origin words still give the romanised word as their source form.** The other 1,677 are in Assamese script (মানুহ for manu); the rest were left out rather than guessed.
- **The verb analysis can over-split** a few words that happen to end like verb forms.

## Verifying entries

`lexicon_review_queue.tsv` lists 1,171 entries in order of impact:
1. the function words;
2. the 300 most frequent words;
3. every candidate;
4. the 300 researched words that research was least sure of, doubtful ones first.

A speaker fills in four columns:
- `decision`: keep, reject or variant-of;
- `correct_pos`;
- `correct_gloss`;
- `note`.

The next build reads those decisions into `lexicon_decisions.tsv`, marks the entries verified, and records who verified them. On the dashboard's Contribute page, speakers confirm meanings for every unverified word except derived forms, with the researched glosses offered as guesses.

## Files

| File | Contents |
| --- | --- |
| `nagamese_lexicon.json` | All entries with every field. The dashboard reads this file. |
| `nagamese_lexicon.tsv` | The same entries, one row each, for spreadsheets |
| `lexicon_review_queue.tsv` | What a speaker should check first |
| `lexicon_decisions.tsv` | Every speaker decision made so far, kept across builds |
| `lexicon_rejected.tsv` | Every word left out, with the reason |
| `lexicon_stats.json` | The numbers in this card |
| `attested_vocabulary.tsv` | Words found in the New Testament, with counts |
| `auto_glossary.tsv` | Candidate glosses from the alignment |
| `../../raw/vocabulary/research/compact/*.tsv` | The research lists, one compact row per word |
| `../../raw/vocabulary/research/compact/patch_*.tsv` | Corrections applied after the lists are expanded: reviewed WordNet senses, and source words in Assamese script |
| `../../raw/vocabulary/research/*.jsonl` | The research shards the build reads, expanded from those lists |

To rebuild, from the repository root:

```
python -m nlp_research.lexicon.auto_annotate expand
python -m nlp_research.lexicon.validate_research
python -m nlp_research.lexicon.build_lexicon
python -m nlp_research.prediction.build_keyboard_assets
```

The first step needs NLTK with WordNet (`nltk.download("wordnet")`). The third needs NLTK's tagger (`nltk.download("averaged_perceptron_tagger_eng")`). To add words, write a compact list into `research/compact/` in the format described at the top of `nlp_research/lexicon/auto_annotate.py`. Check it with `python -m nlp_research.lexicon.auto_annotate check <file>` and rebuild. Derived forms shrink to keep the total at 20,000; `--target` changes the total.

## Licence

The lexicon includes data derived from the ISV Nagamese New Testament, so it is shared under CC BY-SA 4.0. Credit "Likhibi Nagamese lexicon, with data from the ISV Nagamese New Testament © 2019 Bridge Connectivity Solutions, and from WordNet 3.0, © 2006 Princeton University".
