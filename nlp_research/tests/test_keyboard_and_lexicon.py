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
