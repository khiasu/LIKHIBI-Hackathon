from __future__ import annotations

import math
from collections import Counter
from pathlib import Path

import pytest

from nlp_research.lexicon.build_lexicon import analyse_verbs, domain_for, fold, read_vocab_sections, section_kind
from nlp_research.prediction.build_keyboard_assets import trie_json
from nlp_research.prediction.kneser_ney import BOS, EOS, UNK, KneserNeyTrigram, ShippedModel

CORPUS = [
    "moi kali bazar te jabo".split(),
    "moi aji bazar te jabo".split(),
    "tai kali ghor te jabo".split(),
    "apuni kineka ase".split(),
    "moi bhal ase".split(),
    "tai laga ghor bisi dangor ase".split(),
    "manu khan bazar te jaise".split(),
    "moi tai laga ghor te jaise".split(),
] * 3


@pytest.fixture(scope="module")
def model() -> KneserNeyTrigram:
    return KneserNeyTrigram().fit(CORPUS)


def test_kneser_ney_distributions_sum_to_one(model: KneserNeyTrigram) -> None:
    vocabulary = model.vocab
    for context in ([], [BOS], ["moi"], ["moi", "kali"], ["unseen", "words"]):
        total = sum(model.probability(w, context) for w in vocabulary)
        assert math.isclose(total, 1.0, abs_tol=1e-9), (context, total)


def test_context_changes_the_prediction(model: KneserNeyTrigram) -> None:
    assert model.probability("bazar", ["moi", "kali"]) > model.probability("bazar", [BOS])
    assert model.probability("te", ["kali", "bazar"]) > 0.5


def test_pruned_tables_stay_normalised(model: KneserNeyTrigram) -> None:
    # Keeping only the single likeliest continuation must not change the total probability:
    # the backoff weight is renormalised over the words left out.
    shipped = ShippedModel(*model.export(top_bigram=1, top_trigram=1))
    for context in ([BOS], ["moi"], ["moi", "kali"], ["tai", "laga"]):
        expected = 1.0 - model.probability(EOS, context) - model.probability(UNK, context)
        total = sum(shipped.probability(w, context) for w in shipped.vocab)
        assert math.isclose(total, expected, abs_tol=1e-4), (context, total, expected)
        # Words the pruned table kept get exactly the full model's probability.
        key = "|".join(context[-2:]) if len(context) >= 2 else context[-1]
        table = (shipped.tri if len(context) >= 2 else shipped.bi).get(key, {})
        for word in (w for w in table if w != "_g"):
            assert math.isclose(shipped.probability(word, context), model.probability(word, context), rel_tol=1e-3)


def test_trie_metadata_cannot_collide_with_letters() -> None:
    trie = trie_json(Counter({"ef": 3, "e": 2, "fe": 5, "f": 1}))["trie_root"]
    # "e" and "f" are both words and the first letters of longer words.
    assert trie["e"]["$"] == 1 and trie["e"]["$f"] == 2
    assert trie["e"]["f"]["$f"] == 3
    assert trie["f"]["e"]["$f"] == 5 and trie["f"]["$f"] == 1


def test_spelling_variants_fold_together() -> None:
    assert fold("bishi") == fold("bisi") == fold("bissi")
    assert fold("salaam") == fold("salam")
    assert fold("vachan") == fold("bachan")
    assert fold("manu") != fold("mane")


def test_verb_analysis_needs_a_paradigm() -> None:
    words = {"kori", "kore", "korise", "koribo", "koribole", "jabo", "jai", "jaise", "manu", "ghor", "nakoribo"}
    analyses = analyse_verbs(words)
    assert analyses["korise"][:2] == ("kor", "ise")
    assert analyses["koribole"][:2] == ("kor", "ibole")
    assert analyses["jaise"][:2] == ("ja", "ise")
    assert analyses["nakoribo"][0] == "kor" and "negative" in analyses["nakoribo"][2]
    assert "manu" not in analyses and "ghor" not in analyses


def test_vocabulary_sections_give_domains_and_trust(tmp_path: Path) -> None:
    listing = tmp_path / "words.txt"
    listing.write_text(
        "# === FOOD & COOKING ===\nbhat\ndal\n# === INFORMAL SLANG & COLLOQUIAL ===\nchalu\n"
        "# Automated Web Scraped Vocabulary Additions\ncopyright\n",
        encoding="utf-8",
    )
    sections = read_vocab_sections(listing)
    assert ("bhat", "FOOD & COOKING") in sections
    assert domain_for("FOOD & COOKING") == "food" and domain_for("INFORMAL SLANG & COLLOQUIAL") == "slang"
    assert section_kind("Automated Web Scraped Vocabulary Additions") == "scraped"
    assert section_kind("HINDI LOANWORDS — COMMON") == "hindi"


def test_derived_forms_fill_exactly_and_never_repeat() -> None:
    from nlp_research.lexicon.build_lexicon import Entry, derive_forms

    entries = {}
    for word, pos, features in (("kori", "VERB", ["citation form"]), ("korise", "VERB", ["past"]),
                                ("manu", "NOUN", ["base form", "countable", "animate"]),
                                ("pani", "NOUN", ["base form", "mass"]), ("jai", "VERB", ["citation form"])):
        e = Entry(word)
        e.pos, e.features, e.nt_count = pos, features, 5
        e.glosses = [{"english": "do" if word == "kori" else word}]
        entries[word] = e
    assert len(derive_forms(entries, 50)) == 17  # 16 new forms of kor- and one plural: all there is
    made = derive_forms(entries, 17)
    words = [d.word for d in made]
    assert len(made) == 17 and len(set(words)) == 17
    assert "korise" not in words and "koribo" in words and "nakoribo" in words and "nakora" in words
    assert "manukhan" in words and "panikhan" not in words  # mass nouns get no plural
    assert not any(w.startswith(("ja", "naja")) for w in words)  # vowel-final roots are irregular (gaise, jabo)
    koribo = next(d for d in made if d.word == "koribo")
    assert koribo.root == "kor" and "future" in koribo.morphology and koribo.definition and koribo.usage_example
    assert len(derive_forms(entries, 0)) == 0
    entries["ghor"] = Entry("ghor", pos="NOUN", features=["base form", "countable"], domains={"home"})
    more = [d.word for d in derive_forms(entries, 50)]
    assert more.index("ghorkhan") < more.index("ghorte")  # locatives come only after every plural
    assert "panite" not in more  # pani has no place, time or body domain here


def test_ai_verbs_inflect_on_the_stem_and_origin_shares_hold() -> None:
    from nlp_research.lexicon.build_lexicon import Entry, apply_origin_shares, derive_forms

    bonai = Entry("bonai")
    bonai.pos, bonai.features, bonai.origin, bonai.nt_count = "VERB", ["citation form"], "Assamese", 5
    bonai.glosses = [{"english": "make"}]
    words = {d.word for d in derive_forms({"bonai": bonai}, 50)}
    assert {"bonaise", "bonabo", "bonabole", "nabonai", "nabonabo"} <= words and "bonaiise" not in words

    kept = []
    for i in range(100):
        for origin, status in (("Assamese", "derived"), ("English loanword", "curated"), ("Hindi loanword", "curated")):
            e = Entry(f"{origin[0].lower()}{i}")
            e.origin = origin
            kept.append((e.word, e, status))
    chosen, rejected = apply_origin_shares(kept, 1000)
    origins = [e.origin for _, e, _ in chosen]
    # 100 native words carry 68%, so the lexicon shrinks to 147 with 32 English and 15 other.
    assert len(chosen) == 147 and origins.count("Assamese") == 100
    assert origins.count("English loanword") == 32 and origins.count("Hindi loanword") == 15
    assert len(rejected) == 68 + 85


def test_compact_rows_expand_to_valid_research_rows(tmp_path: Path) -> None:
    from nlp_research.lexicon.auto_annotate import parse_compact
    from nlp_research.lexicon.validate_research import check_row

    shard = tmp_path / "new_test.tsv"
    shard.write_text(
        "koshish\tNOUN\tVERB=to try (with kori)\tAn attempt; with kori, to try.\ttry;effort\tactions\tH\tकोशिश"
        "\tbase form,countable,inanimate\tkosis\t-\t0.8\t\n"
        "hai\tAUX\t\tIs.\tis\tgrammar\tH\tहै\tinvariable\t\tTai hai.\t0.5\t\n"
        "badrow\tXYZ\n",
        encoding="utf-8",
    )
    rows, errors = parse_compact(shard, {"koshish": "Tai koshish korise."}, {})
    assert [r["word"] for r in rows] == ["koshish"]  # a Hindi function word and a broken row are skipped
    koshish = rows[0]
    assert check_row(koshish) == []
    assert koshish["origin"] == "Hindi loanword" and koshish["usage_example"] == "Tai koshish korise."
    assert koshish["pos_alternatives"] == [{"tag": "VERB", "gloss": "to try (with kori)"}]
    assert "kori" in koshish["morphology"] and len(errors) == 2


def test_patches_correct_rows_and_keep_them_valid(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from nlp_research.lexicon import auto_annotate
    from nlp_research.lexicon.validate_research import check_row

    bravo = auto_annotate.row("bravo", "NOUN", "A murderer who is hired.", ["bravo"], ["community"], "English loanword",
                              features=["base form", "countable", "animate"], method="dictionary (WordNet)",
                              urls=["https://wordnet.princeton.edu/"])
    manu = auto_annotate.row("manu", "NOUN", "A person.", ["person"], ["community"], "Assamese")
    (tmp_path / "patch_senses.tsv").write_text(
        "word\tpos\tenglish_definition\tglosses\nbravo\tINTJ\tWell done!\twell done;bravo\nnosuch\tNOUN\tX.\tx\n",
        encoding="utf-8")
    (tmp_path / "patch_script.tsv").write_text("word\tsource_form\nmanu\tমানুহ\n", encoding="utf-8")
    monkeypatch.setattr(auto_annotate, "COMPACT_DIR", tmp_path)
    errors: list[str] = []
    assert auto_annotate.apply_patches([bravo, manu], errors) == 2
    assert len(errors) == 1  # the word with no row is reported, not added
    assert bravo["pos"] == "INTJ" and bravo["english_definition"] == "Well done!" and bravo["glosses"] == ["well done", "bravo"]
    assert bravo["features"] == ["invariable"] and bravo["method"] == "model-annotated"
    assert bravo["usage_example"] == auto_annotate.example_for("bravo", "INTJ", ["invariable"])
    assert manu["source_form"] == "মানুহ" and manu["english_definition"] == "A person."
    assert check_row(bravo) == [] and check_row(manu) == []
