from __future__ import annotations

import dataclasses
from pathlib import Path
from typing import Callable, Iterator

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from nlp_research.translation import credentials
from nlp_research.translation.engine_api import EngineError
from nlp_research.translation.engines import llm_providers

KEY = "AIzaTest-0123456789abcdefghijklmnopq"
RAG = "nlp_research.translation.engines.rag_llm:RagLlmEngine"


@pytest.fixture
def client_at(
    base_settings: Settings, datasets_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> Iterator[Callable[..., TestClient]]:
    """A client for the LLM engine, browsing from a chosen address and host name."""
    monkeypatch.setenv("LIKHIBI_DATASETS_DIR", str(datasets_dir))
    monkeypatch.setenv("LIKHIBI_LLM_CACHE", "off")
    opened: list[TestClient] = []

    def factory(address: str = "127.0.0.1", base_url: str = "http://localhost:8000", **overrides: object) -> TestClient:
        settings = dataclasses.replace(base_settings, engine_specs=(RAG,), **overrides)
        client = TestClient(create_app(settings), base_url=base_url, client=(address, 50000))
        client.__enter__()
        opened.append(client)
        return client

    yield factory
    for client in opened:
        client.__exit__(None, None, None)


@pytest.fixture
def google(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, str]]:
    """Google's key check, answered locally. Records each (key, model) it was asked about."""
    asked: list[tuple[str, str]] = []

    def check(api_key: str, model: str = llm_providers.DEFAULT_GEMINI_MODEL, timeout: float = 15.0) -> str | None:
        asked.append((api_key, model))
        if api_key.startswith("AIzaBad"):
            raise EngineError("Google rejected this key (HTTP 400). Copy it again from Google AI Studio.")
        return None

    monkeypatch.setattr(llm_providers, "check_gemini_key", check)
    return asked


def rag_state(client: TestClient) -> str:
    engines = client.get("/api/engines").json()["engines"]
    return next(engine["state"] for engine in engines if engine["id"] == "rag-llm")


def test_save_use_and_remove_a_key(client_at, google, credential_store) -> None:
    client = client_at()
    before = client.get("/api/settings/llm").json()
    assert before["key_source"] is None and before["can_edit"] and before["store"] == "Test credential store"
    assert rag_state(client) == "not_configured"

    response = client.put("/api/settings/llm-key", json={"api_key": f"  {KEY}\n"})
    assert response.status_code == 200
    body = response.json()
    assert body["key_source"] == "dashboard" and body["key_hint"] == KEY[-4:]
    assert KEY not in response.text  # the key never comes back
    assert google == [(KEY, "gemini-3.8-flash")]
    assert credential_store.saved[(credentials.SERVICE, credentials.GEMINI_API_KEY)] == KEY
    assert rag_state(client) == "ready"  # no restart needed

    response = client.delete("/api/settings/llm-key")
    assert response.status_code == 200 and response.json()["key_source"] is None
    assert credential_store.saved == {}
    assert rag_state(client) == "not_configured"


def test_environment_key_is_reported_but_never_returned(client_at, monkeypatch) -> None:
    monkeypatch.setenv("GEMINI_API_KEY", KEY)
    response = client_at().get("/api/settings/llm")
    body = response.json()
    assert body["key_source"] == "environment" and body["key_env_var"] == "GEMINI_API_KEY"
    assert KEY not in response.text


@pytest.mark.parametrize(
    ("address", "base_url"),
    [
        ("203.0.113.5", "http://dashboard.example:8000"),  # another computer
        ("127.0.0.1", "http://attacker.example:8000"),  # a page that re-pointed its domain at this computer
    ],
)
def test_only_this_computer_can_change_the_key(client_at, google, credential_store, address, base_url) -> None:
    client = client_at(address=address, base_url=base_url)
    assert client.get("/api/settings/llm").json()["can_edit"] is False
    response = client.put("/api/settings/llm-key", json={"api_key": KEY})
    assert response.status_code == 403 and response.json()["error"]["code"] == "local_only"
    assert client.delete("/api/settings/llm-key").status_code == 403
    assert credential_store.saved == {} and google == []


def test_admin_token_guards_the_key_when_set(client_at, google, credential_store) -> None:
    client = client_at(address="203.0.113.5", base_url="http://dashboard.example:8000", admin_token="s3cret")
    assert client.put("/api/settings/llm-key", json={"api_key": KEY}).status_code == 401
    response = client.put("/api/settings/llm-key", json={"api_key": KEY}, headers={"X-Admin-Token": "s3cret"})
    assert response.status_code == 200 and credential_store.saved


def test_rejected_or_malformed_keys_are_not_saved(client_at, google, credential_store) -> None:
    client = client_at()
    bad = "AIzaBad-0123456789abcdefghijklmnopqrs"
    response = client.put("/api/settings/llm-key", json={"api_key": bad})
    assert response.status_code == 400 and response.json()["error"]["code"] == "api_key_rejected"
    assert bad not in response.text

    response = client.put("/api/settings/llm-key", json={"api_key": "not a key"})
    assert response.status_code == 422 and response.json()["error"]["code"] == "invalid_api_key"
    assert credential_store.saved == {}


def test_without_a_secure_store_nothing_is_saved(client_at, google, monkeypatch) -> None:
    monkeypatch.setattr(credentials, "_secure_backend", lambda: None)
    client = client_at()
    assert client.get("/api/settings/llm").json()["store"] is None
    response = client.put("/api/settings/llm-key", json={"api_key": KEY})
    assert response.status_code == 409 and response.json()["error"]["code"] == "no_secure_store"
    assert google == []
