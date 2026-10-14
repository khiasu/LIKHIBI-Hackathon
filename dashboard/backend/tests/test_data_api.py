from __future__ import annotations

from pathlib import Path


def test_data_health_measures_the_corpus(make_client) -> None:
    body = make_client().get("/api/data/health").json()

    (corpus,) = body["parallel"]
    assert corpus["domain"] == "bible"
    assert corpus["rows"] == 5
    assert corpus["english_placeholder"] == 3
    assert corpus["english_real"] == 1
    assert corpus["english_empty"] == 1
    assert corpus["duplicate_verse_ids"] == 1
    assert corpus["impossible_references"] == 1
    assert corpus["suspected_noise_rows"] == 1
    assert "Matthew" in corpus["nt_books_missing"]

    lexicon = body["lexicon"]
    assert lexicon["entries"] == 3
    assert lexicon["glossed"] == 1
    assert lexicon["pos_tagged"] == 1
    assert lexicon["attested_in_corpus"] == 2  # "galaxyte" never occurs in the corpus

    assert body["monolingual"]["lines"] == 2
    checks = {check["id"]: check for check in body["checks"]}
    assert checks["parallel_pairs"]["current"] == 1
    assert checks["parallel_pairs"]["passed"] is False
    assert checks["test_set"]["current"] == 0


def test_domain_column_separates_bible_from_everyday_rows(make_client, datasets_dir: Path) -> None:
    mixed = datasets_dir / "processed" / "parallel_corpus" / "fnpc25.tsv"
    rows = [
        ("id", "english_text", "nagamese_text", "domain"),
        ("1", "In the beginning was the Word.", "Shuru te Kotha thakise.", "bible"),
        ("2", "Where is the market?", "Bazar kot te ase?", "everyday"),
        ("3", "Good morning", "Bhal puwa", "everyday"),
    ]
    mixed.write_text("".join("\t".join(row) + "\n" for row in rows), encoding="utf-8")
    body = make_client().get("/api/data/health").json()
    report = next(r for r in body["parallel"] if r["file"].endswith("fnpc25.tsv"))
    assert report["domain"] == "mixed"
    assert report["real_pairs_by_domain"] == {"everyday": 2, "bible": 1}
    checks = {c["id"]: c for c in body["checks"]}
    assert checks["parallel_pairs"]["current"] == 4  # 3 here + 1 real pair in the Bible file
    assert checks["everyday_pairs"]["current"] == 2


def test_approved_corrections_count_as_everyday_pairs(make_client) -> None:
    client = make_client()
    item = client.post(
        "/api/feedback",
        json={
            "source_lang": "en",
            "target_lang": "nag",
            "source_text": "Good morning",
            "corrected_translation": "Bhal puwa",
            "share_consent": True,
        },
    ).json()
    client.patch(f"/api/feedback/{item['id']}", json={"status": "approved"})
    checks = {c["id"]: c for c in client.get("/api/data/health").json()["checks"]}
    assert checks["everyday_pairs"]["current"] == 1


def test_data_sources_report_import_status(make_client, datasets_dir: Path) -> None:
    client = make_client()
    sources = {s["id"]: s for s in client.get("/api/data/sources").json()}
    assert sources["ebible-isv-nag"]["imported"] is False

    target = datasets_dir / sources["ebible-isv-nag"]["expected_path"]
    target.mkdir(parents=True)
    (target / "41MATnag.SFM").write_text("\\id MAT", encoding="utf-8")
    sources = {s["id"]: s for s in client.get("/api/data/sources").json()}
    assert sources["ebible-isv-nag"]["imported"] is True
