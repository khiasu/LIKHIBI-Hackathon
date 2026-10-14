from __future__ import annotations

CORRECTION = {
    "source_lang": "en",
    "target_lang": "nag",
    "source_text": "Where is the market?",
    "machine_translation": "Bazar kote?",
    "corrected_translation": "Bazar\tkot te\nase?",
    "rating": "down",
    "engine_id": "mock",
    "contributor": "Asen",
    "share_consent": True,
}


def test_feedback_review_and_export_flow(make_client) -> None:
    client = make_client()
    created = client.post("/api/feedback", json=CORRECTION)
    assert created.status_code == 201
    item = created.json()
    assert item["status"] == "pending"

    listing = client.get("/api/feedback", params={"status": "pending"}).json()
    assert [entry["id"] for entry in listing["items"]] == [item["id"]]
    assert listing["counts"]["pending"] == 1

    approved = client.patch(f"/api/feedback/{item['id']}", json={"status": "approved"})
    assert approved.json()["status"] == "approved"
    assert approved.json()["reviewed_at"] is not None

    export = client.get("/api/feedback/export.tsv")
    assert export.status_code == 200
    header, row = export.text.strip().split("\n")
    assert header.split("\t")[3:7] == ["source_text", "target_text", "contributor", "license"]
    # Tabs and newlines inside the correction must not break the TSV.
    assert row.split("\t")[3:7] == ["Where is the market?", "Bazar kot te ase?", "Asen", "CC-BY-SA-4.0"]


def test_sharing_a_translation_needs_consent(make_client) -> None:
    client = make_client()
    response = client.post("/api/feedback", json={**CORRECTION, "share_consent": False})
    assert response.status_code == 422
    assert "CC BY-SA 4.0" in response.json()["error"]["message"]
    rating = client.post(
        "/api/feedback", json={**CORRECTION, "corrected_translation": None, "share_consent": False, "rating": "up"}
    ).json()
    assert rating["license"] is None and rating["contributor"] is None  # nothing of theirs is published
    shared = client.post("/api/feedback", json=CORRECTION).json()
    assert shared["license"] == "CC-BY-SA-4.0" and shared["contributor"] == "Asen"


def test_withdrawn_contribution_is_gone_everywhere(make_client) -> None:
    client = make_client()
    item = client.post("/api/feedback", json=CORRECTION).json()
    client.patch(f"/api/feedback/{item['id']}", json={"status": "approved"})
    assert client.get("/api/community/stats").json()["approved_pairs"] == 1
    assert client.delete(f"/api/feedback/{item['id']}").status_code == 204
    assert client.get("/api/feedback", params={"status": "all"}).json()["items"] == []
    assert client.get("/api/community/stats").json()["approved_pairs"] == 0
    assert client.delete(f"/api/feedback/{item['id']}").status_code == 404


def test_community_stats_count_credited_people_once(make_client) -> None:
    client = make_client()
    for name in ("Asen", " asen ", "Vito", None):
        item = client.post("/api/feedback", json={**CORRECTION, "contributor": name}).json()
        client.patch(f"/api/feedback/{item['id']}", json={"status": "approved"})
    client.post("/api/feedback", json=CORRECTION)  # still pending
    stats = client.get("/api/community/stats").json()
    assert stats == {"approved_pairs": 4, "pending": 1, "contributors": 2, "translations": 0}


def test_rating_without_correction_is_not_exported(make_client) -> None:
    client = make_client()
    item = client.post("/api/feedback", json={**CORRECTION, "corrected_translation": None, "rating": "up"}).json()
    client.patch(f"/api/feedback/{item['id']}", json={"status": "approved"})
    lines = client.get("/api/feedback/export.tsv").text.strip().split("\n")
    assert len(lines) == 1  # header only


def test_feedback_needs_rating_or_correction(make_client) -> None:
    client = make_client()
    response = client.post(
        "/api/feedback",
        json={**CORRECTION, "rating": None, "corrected_translation": "   "},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_request"


def test_admin_token_guards_review_but_not_submission(make_client) -> None:
    client = make_client(admin_token="secret")
    assert client.post("/api/feedback", json=CORRECTION).status_code == 201
    assert client.get("/api/feedback").status_code == 401
    assert client.get("/api/feedback", headers={"X-Admin-Token": "wrong"}).status_code == 401
    assert client.get("/api/feedback", headers={"X-Admin-Token": "secret"}).status_code == 200
    assert client.get("/api/feedback/export.tsv").status_code == 401
    assert client.delete("/api/feedback/1").status_code == 401


def test_review_unknown_feedback_is_404(make_client) -> None:
    response = make_client().patch("/api/feedback/999", json={"status": "approved"})
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "feedback_not_found"
