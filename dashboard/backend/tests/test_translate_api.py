from __future__ import annotations

from .conftest import engines

EN_NAG = {"source_lang": "en", "target_lang": "nag"}


def test_no_engine_returns_structured_503(make_client) -> None:
    client = make_client()
    response = client.post("/api/translate", json={"text": "Hello", **EN_NAG})
    assert response.status_code == 503
    error = response.json()["error"]
    assert error["code"] == "no_engine_available"
    assert error["details"]["direction"] == "en-nag"


def test_mock_engine_is_clearly_labelled(make_client) -> None:
    client = make_client(enable_mock_engine=True)
    response = client.post("/api/translate", json={"text": " Hello ", "num_alternatives": 2, **EN_NAG})
    assert response.status_code == 200
    body = response.json()
    assert body["translation"] == "[mock] Hello"
    assert body["engine"]["kind"] == "mock"
    assert len(body["alternatives"]) == 2
    assert any("not a translation" in warning for warning in body["warnings"])


def test_input_validation(make_client) -> None:
    client = make_client(enable_mock_engine=True)
    same = client.post("/api/translate", json={"text": "Hi", "source_lang": "en", "target_lang": "en"})
    assert same.status_code == 400
    assert same.json()["error"]["code"] == "same_language"

    blank = client.post("/api/translate", json={"text": "   ", **EN_NAG})
    assert blank.status_code == 422
    assert blank.json()["error"]["code"] == "empty_text"

    too_long = client.post("/api/translate", json={"text": "a" * 201, **EN_NAG})
    assert too_long.status_code == 413
    assert too_long.json()["error"]["details"]["max_chars"] == 200

    bad_lang = client.post("/api/translate", json={"text": "Hi", "source_lang": "fr", "target_lang": "nag"})
    assert bad_lang.status_code == 422
    assert bad_lang.json()["error"]["code"] == "invalid_request"


def test_auto_prefers_real_engines_over_mock(make_client) -> None:
    client = make_client(engine_specs=engines("EchoEngine"), enable_mock_engine=True)
    body = client.post("/api/translate", json={"text": "Hello", **EN_NAG}).json()
    assert body["engine"]["id"] == "echo"
    assert body["evidence"][0]["origin"] == "test:1"
    assert body["confidence"] == 0.5


def test_auto_falls_back_when_default_engine_fails(make_client) -> None:
    client = make_client(engine_specs=engines("FailingEngine", "EchoEngine"), default_engine="failing")
    body = client.post("/api/translate", json={"text": "Hello", **EN_NAG}).json()
    assert body["engine"]["id"] == "echo"
    assert "quota exceeded" in body["warnings"][0]


def test_explicit_engine_failures_become_502(make_client) -> None:
    client = make_client(engine_specs=engines("CrashingEngine", "InvalidResultEngine"))
    for engine_id, fragment in (("crashing", "bug inside the engine"), ("invalid-result", "invalid result")):
        response = client.post("/api/translate", json={"text": "Hello", "engine": engine_id, **EN_NAG})
        assert response.status_code == 502
        assert response.json()["error"]["code"] == "engine_failed"
        assert fragment in response.json()["error"]["message"]


def test_engine_selection_errors(make_client) -> None:
    client = make_client(engine_specs=engines("NotConfiguredEngine", "EnglishOnlyEngine"))
    unknown = client.post("/api/translate", json={"text": "Hi", "engine": "nope", **EN_NAG})
    assert unknown.status_code == 404

    not_ready = client.post("/api/translate", json={"text": "Hi", "engine": "not-configured", **EN_NAG})
    assert not_ready.status_code == 503
    assert "API key missing" in not_ready.json()["error"]["message"]

    wrong_direction = client.post(
        "/api/translate",
        json={"text": "Moi", "engine": "en-only", "source_lang": "nag", "target_lang": "en"},
    )
    assert wrong_direction.status_code == 503
    assert wrong_direction.json()["error"]["code"] == "engine_not_ready"


def test_engines_endpoint_reports_states_and_broken_specs(make_client) -> None:
    client = make_client(
        engine_specs=(*engines("EchoEngine", "NotConfiguredEngine", "BadLoadEngine"), "missing.module:Engine"),
        default_engine="echo",
    )
    body = client.get("/api/engines").json()
    states = {engine["id"]: engine["state"] for engine in body["engines"]}
    assert states == {"echo": "ready", "not-configured": "not_configured", "bad-load": "error"}
    assert body["default_engine"] == "echo"
    assert body["broken"][0]["spec"] == "missing.module:Engine"


def test_compare_runs_every_ready_engine(make_client) -> None:
    client = make_client(engine_specs=engines("EchoEngine", "FailingEngine"), enable_mock_engine=True)
    body = client.post("/api/translate/compare", json={"text": "Hello", **EN_NAG}).json()
    outcomes = {item["engine"]["id"]: item["ok"] for item in body["results"]}
    assert outcomes == {"echo": True, "failing": False, "mock": True}


def test_translations_are_logged_only_when_enabled(make_client) -> None:
    logged = make_client(enable_mock_engine=True)
    logged.post("/api/translate", json={"text": "Hello", **EN_NAG})
    assert logged.app.state.storage.translation_count() == 1

    private = make_client(enable_mock_engine=True, log_translations=False, db_path=logged.app.state.settings.db_path.with_name("private.sqlite3"))
    private.post("/api/translate", json={"text": "Hello", **EN_NAG})
    assert private.app.state.storage.translation_count() == 0


def test_meta_describes_the_server(make_client) -> None:
    body = make_client(admin_token="secret").get("/api/meta").json()
    assert body["review_requires_token"] is True
    assert {language["code"] for language in body["languages"]} == {"en", "nag"}
