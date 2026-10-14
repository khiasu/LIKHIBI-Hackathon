from __future__ import annotations

from app.config import DEFAULT_ENGINE_ID, DEFAULT_ENGINES, Settings

from .conftest import engines
from .fake_engines import RecordingEngine

EN_NAG = {"source_lang": "en", "target_lang": "nag"}


def test_declining_engine_hands_over_without_a_warning(make_client) -> None:
    client = make_client(engine_specs=engines("DecliningEngine", "EchoEngine"), default_engine="declining")
    body = client.post("/api/translate", json={"text": "Hello", **EN_NAG}).json()
    assert body["engine"]["id"] == "echo"
    assert body["warnings"] == []


def test_when_every_engine_declines_the_api_says_so(make_client) -> None:
    client = make_client(engine_specs=engines("DecliningEngine"))
    response = client.post("/api/translate", json={"text": "Hello", **EN_NAG})
    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "no_confident_translation"
    assert error["details"]["declined"][0]["engine"] == "declining"


def test_compare_marks_declines_separately_from_failures(make_client) -> None:
    client = make_client(engine_specs=engines("DecliningEngine", "FailingEngine", "EchoEngine"))
    results = client.post("/api/translate/compare", json={"text": "Hello", **EN_NAG}).json()["results"]
    by_id = {item["engine"]["id"]: item for item in results}
    assert by_id["declining"]["declined"] is True
    assert by_id["failing"]["declined"] is False and by_id["failing"]["ok"] is False
    assert by_id["echo"]["ok"] is True


def test_approved_corrections_reach_engines_immediately(make_client) -> None:
    RecordingEngine.received = []
    client = make_client(engine_specs=engines("RecordingEngine"))
    nag_to_en = client.post(
        "/api/feedback",
        json={
            "source_lang": "nag",
            "target_lang": "en",
            "source_text": "Moi bazar te jai ase",
            "corrected_translation": "I am going to the market",
            "share_consent": True,
        },
    ).json()
    assert RecordingEngine.received == []  # pending corrections are not used

    client.patch(f"/api/feedback/{nag_to_en['id']}", json={"status": "approved"})
    (example,) = RecordingEngine.received
    assert example.english == "I am going to the market"  # orientation follows the source language
    assert example.nagamese == "Moi bazar te jai ase"
    assert example.origin == f"feedback:{nag_to_en['id']}"

    client.patch(f"/api/feedback/{nag_to_en['id']}", json={"status": "rejected"})
    assert RecordingEngine.received == []


def test_approved_corrections_are_loaded_at_startup(make_client, base_settings) -> None:
    first = make_client(engine_specs=engines("RecordingEngine"))
    item = first.post(
        "/api/feedback",
        json={
            "source_lang": "en",
            "target_lang": "nag",
            "source_text": "Good morning",
            "corrected_translation": "Bhal puwa",
            "share_consent": True,
        },
    ).json()
    first.patch(f"/api/feedback/{item['id']}", json={"status": "approved"})
    RecordingEngine.received = []

    make_client(engine_specs=engines("RecordingEngine"))  # same database, fresh app
    assert [e.nagamese for e in RecordingEngine.received] == ["Bhal puwa"]


def test_engine_settings_defaults() -> None:
    unset = Settings.from_env({})
    assert unset.engine_specs == DEFAULT_ENGINES
    assert unset.default_engine == DEFAULT_ENGINE_ID
    assert Settings.from_env({"LIKHIBI_ENGINES": "none"}).engine_specs == ()
    assert Settings.from_env({"LIKHIBI_ENGINES": ""}).engine_specs == ()
    custom = Settings.from_env({"LIKHIBI_ENGINES": "a.b:C", "LIKHIBI_DEFAULT_ENGINE": "c"})
    assert custom.engine_specs == ("a.b:C",) and custom.default_engine == "c"
