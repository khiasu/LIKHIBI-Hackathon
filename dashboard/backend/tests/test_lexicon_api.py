from __future__ import annotations

import json
from pathlib import Path


def test_researched_meanings_still_need_a_speaker(make_client, datasets_dir: Path) -> None:
    # The current lexicon fills english_definition from research, so a word stays open
    # until a speaker verifies it; derived forms are never asked about.
    lexicon = datasets_dir / "processed" / "lexical_database" / "nagamese_lexicon.json"
    lexicon.write_text(json.dumps([
        {"lemma": "school", "english_definition": "A place where children are taught.", "status": "researched",
         "is_validated": False, "frequency_count": 0, "gloss_candidates": [{"english": "school"}]},
        {"lemma": "isor", "english_definition": "God", "status": "attested", "is_validated": True, "frequency_count": 900},
        {"lemma": "korise", "english_definition": "Past form of kori.", "status": "derived", "is_validated": False},
    ]), encoding="utf-8")
    body = make_client().get("/api/lexicon/needs-meaning", params={"count": 5}).json()
    assert [task["lemma"] for task in body["tasks"]] == ["school"]
    assert body["tasks"][0]["guesses"] == ["school"]
    assert body["total"] == 1


def test_words_needing_a_meaning(make_client, datasets_dir: Path) -> None:
    glossary = datasets_dir / "processed" / "lexical_database" / "auto_glossary.tsv"
    glossary.write_text(
        "nagamese\tenglish\tscore\tcount\tstatus\tmethod\n"
        "thakise\twas\t0.4\t9\tunverified\tibm1-bible\n"
        "thakise\tlived\t0.2\t4\tunverified\tibm1-bible\n",
        encoding="utf-8",
    )
    client = make_client()
    body = client.get("/api/lexicon/needs-meaning", params={"count": 5}).json()
    # "isor" already has a meaning in the lexicon, so only the two placeholder entries are open.
    assert {task["lemma"] for task in body["tasks"]} == {"thakise", "galaxyte"}
    assert body["remaining"] == 2 and body["total"] == 2
    assert next(t for t in body["tasks"] if t["lemma"] == "thakise")["guesses"] == ["was", "lived"]

    skipped = client.get("/api/lexicon/needs-meaning", params={"skip": "thakise"}).json()
    assert [task["lemma"] for task in skipped["tasks"]] == ["galaxyte"]

    client.post(
        "/api/feedback",
        json={
            "source_lang": "nag",
            "target_lang": "en",
            "source_text": "Thakise",
            "corrected_translation": "was, stayed",
            "share_consent": True,
        },
    )
    after = client.get("/api/lexicon/needs-meaning").json()
    assert [task["lemma"] for task in after["tasks"]] == ["galaxyte"]  # a meaning is waiting for review
    assert after["remaining"] == 1
