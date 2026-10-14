from __future__ import annotations

import dataclasses
import json
from pathlib import Path
from typing import Callable, Iterator

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from nlp_research.translation import credentials

TSV_HEADER = "src_id\tverse_id\tbook\tchapter\tverse\tenglish_text\tnagamese_text\n"

TSV_ROWS = [
    # Placeholder English, as in the current repository corpus.
    ("EN_NAG_1", "JHN_1_1", "John", "1", "1", "[English translation for John 1:1]", "Shuru te Kotha thakise."),
    # Duplicate verse id with leaked footnote marker and running header.
    ("EN_NAG_2", "JHN_1_1", "John", "1", "1", "[English translation for John 1:1]", "Tai Isor logot thakise* John 1:5"),
    # A real pair.
    ("EN_NAG_3", "JHN_1_2", "John", "1", "2", "He was with God in the beginning.", "Tai shuru te Isor logot thakise."),
    ("EN_NAG_4", "MRK_1_1", "Mark", "1", "1", "", "Jisu Khrista laga bhal khobor."),
    # 2 John has a single chapter, so chapter 3 cannot exist.
    ("EN_NAG_5", "2JN_3_1", "2 John", "3", "1", "[English translation for 2 John 3:1]", "Bura manu pora."),
]

LEXICON = [
    {"lemma": "isor", "english_definition": "God", "pos_category": "NOUN", "etymology_origin": "Native"},
    {"lemma": "thakise", "english_definition": "Definition placeholder for 'thakise'", "pos_category": "Unknown", "etymology_origin": "Native"},
    {"lemma": "galaxyte", "english_definition": "Definition placeholder for 'galaxyte'", "pos_category": "Unknown", "etymology_origin": "English Loanword"},
]

MONOLINGUAL = "Shuru te Kotha thakise.\nTai shuru te Isor logot thakise.\n\n"


class MemoryCredentialStore:
    """Stands in for the OS credential store, so tests never read or write a real key."""

    def __init__(self) -> None:
        self.saved: dict[tuple[str, str], str] = {}

    def get_password(self, service: str, name: str) -> str | None:
        return self.saved.get((service, name))

    def set_password(self, service: str, name: str, value: str) -> None:
        self.saved[(service, name)] = value

    def delete_password(self, service: str, name: str) -> None:
        del self.saved[(service, name)]


@pytest.fixture(autouse=True)
def credential_store(monkeypatch: pytest.MonkeyPatch) -> MemoryCredentialStore:
    store = MemoryCredentialStore()
    monkeypatch.setattr(credentials, "_secure_backend", lambda: (store, "Test credential store"))
    for name in ("LIKHIBI_LLM_PROVIDER", "LIKHIBI_LLM_MODEL", "LIKHIBI_LLM_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    return store


@pytest.fixture
def datasets_dir(tmp_path: Path) -> Path:
    root = tmp_path / "datasets"
    parallel = root / "processed" / "parallel_corpus"
    parallel.mkdir(parents=True)
    lines = [TSV_HEADER] + ["\t".join(row) + "\n" for row in TSV_ROWS]
    (parallel / "bible_parallel_corpus.tsv").write_text("".join(lines), encoding="utf-8")

    lexicon = root / "processed" / "lexical_database"
    lexicon.mkdir(parents=True)
    (lexicon / "nagamese_lexicon.json").write_text(json.dumps(LEXICON), encoding="utf-8")

    vocab = root / "raw" / "vocabulary"
    vocab.mkdir(parents=True)
    (vocab / "monolingual_nagamese.txt").write_text(MONOLINGUAL, encoding="utf-8")
    return root


@pytest.fixture
def base_settings(tmp_path: Path, datasets_dir: Path) -> Settings:
    return Settings(
        datasets_dir=datasets_dir,
        db_path=tmp_path / "var" / "test.sqlite3",
        engine_specs=(),
        default_engine=None,
        enable_mock_engine=False,
        max_chars=200,
        log_translations=True,
        admin_token=None,
        cors_origins=("http://localhost:5173",),
        frontend_dist=tmp_path / "no-frontend-build",
    )


@pytest.fixture
def make_client(base_settings: Settings) -> Iterator[Callable[..., TestClient]]:
    """Build a client whose settings override the defaults, e.g. make_client(enable_mock_engine=True)."""
    clients: list[TestClient] = []

    def factory(**overrides: object) -> TestClient:
        settings = dataclasses.replace(base_settings, **overrides)
        client = TestClient(create_app(settings))
        client.__enter__()  # runs the lifespan, which loads the engines
        clients.append(client)
        return client

    yield factory
    for client in clients:
        client.__exit__(None, None, None)


def engines(*names: str) -> tuple[str, ...]:
    return tuple(f"tests.fake_engines:{name}" for name in names)
