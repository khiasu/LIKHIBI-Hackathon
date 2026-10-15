from __future__ import annotations

from tests.test_feedback_api import CORRECTION

ADMIN = {"X-Admin-Token": "admin-secret"}


def _reviewer(client, name: str, languages: list[str] | None = None) -> dict:
    created = client.post("/api/reviewers", json={"name": name, "languages": languages or []}, headers=ADMIN)
    assert created.status_code == 201, created.text
    body = created.json()
    assert body["token"].startswith("lkb_")
    return {"X-Admin-Token": body["token"]}


def test_verified_reviewer_approves_and_is_credited(make_client) -> None:
    client = make_client(admin_token="admin-secret")
    item = client.post("/api/feedback", json={**CORRECTION, "variety": "Kohima", "origin": "keyboard"}).json()
    assert item["variety"] == "Kohima" and item["origin"] == "keyboard"

    # Without a token, nothing can be reviewed.
    assert client.get("/api/feedback").status_code == 401

    imchen = _reviewer(client, "Imchen", ["nag"])
    assert client.get("/api/reviewers/me", headers=imchen).json() == {"name": "Imchen", "role": "reviewer", "languages": ["nag"]}
    approved = client.patch(f"/api/feedback/{item['id']}", json={"status": "approved", "note": "Good"}, headers=imchen).json()
    assert approved["reviewed_by"] == "Imchen" and approved["review_note"] == "Good"
    listed = client.get("/api/reviewers", headers=ADMIN).json()["items"]
    assert listed[0]["reviews"] == 1


def test_reviewers_are_limited_to_their_languages_and_rights(make_client) -> None:
    client = make_client(admin_token="admin-secret")
    item = client.post("/api/feedback", json=CORRECTION).json()
    ao_only = _reviewer(client, "Ao reviewer", ["ao"])
    assert client.get("/api/feedback", headers=ao_only).json()["items"] == []
    assert client.patch(f"/api/feedback/{item['id']}", json={"status": "approved"}, headers=ao_only).status_code == 403
    # Only the admin adds reviewers or deletes contributions.
    assert client.post("/api/reviewers", json={"name": "Someone"}, headers=ao_only).status_code == 403
    assert client.delete(f"/api/feedback/{item['id']}", headers=ao_only).status_code == 403


def test_deactivated_reviewer_loses_access(make_client) -> None:
    client = make_client(admin_token="admin-secret")
    token = _reviewer(client, "Temporary")
    reviewer_id = client.get("/api/reviewers", headers=ADMIN).json()["items"][0]["id"]
    assert client.post(f"/api/reviewers/{reviewer_id}/deactivate", headers=ADMIN).json()["active"] is False
    assert client.get("/api/reviewers/me", headers=token).status_code == 401
    assert client.post("/api/reviewers", json={"name": "temporary"}, headers=ADMIN).status_code == 409


def test_learning_status_counts_what_the_model_has_not_learned(make_client) -> None:
    client = make_client(learn_threshold=0)
    item = client.post("/api/feedback", json=CORRECTION).json()
    client.patch(f"/api/feedback/{item['id']}", json={"status": "approved"})
    status = client.get("/api/learning").json()
    assert status["approved_pairs"] == 1
    assert status["new_since_last"] == 1
    assert status["running"] is False and status["runs"] == []
