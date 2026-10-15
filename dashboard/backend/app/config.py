"""Runtime configuration, read from environment variables.

| Variable                     | Meaning                                                   |
| ---------------------------- | --------------------------------------------------------- |
| LIKHIBI_ENGINES              | Comma-separated engine specs, "package.module:Class".     |
|                              | Unset: the built-in Likhibi engines. "none": no engines.  |
| LIKHIBI_DEFAULT_ENGINE       | Engine id tried first when a request asks for "auto"      |
| LIKHIBI_ENABLE_MOCK_ENGINE   | "1" registers a mock engine for UI development            |
| LIKHIBI_MAX_CHARS            | Maximum characters per translation request (default 2000) |
| LIKHIBI_LOG_TRANSLATIONS     | "0" stops storing translated text (default on)            |
| LIKHIBI_ADMIN_TOKEN          | When set, reviewing and exporting feedback needs it       |
| LIKHIBI_LEARN_THRESHOLD      | Approved corrections that start learning (default 20, 0 off) |
| LIKHIBI_DATASETS_DIR         | Dataset root (default: <repo>/datasets)                   |
| LIKHIBI_DB_PATH              | SQLite file (default: dashboard/backend/var/...)          |
| LIKHIBI_CORS_ORIGINS         | Comma-separated origins allowed to call the API           |
| LIKHIBI_FRONTEND_DIST        | Built frontend served at "/" when the folder exists       |
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from . import REPO_ROOT

BACKEND_ROOT = Path(__file__).resolve().parents[1]

_DEFAULT_CORS = ("http://localhost:5173", "http://127.0.0.1:5173")

# The built-in engines, in the order auto mode tries them after the default:
# exact human translations first, then the LLM with retrieved examples, then the
# offline NMT model. Engines that are not configured report it and are skipped.
DEFAULT_ENGINES = (
    "nlp_research.translation.engines.translation_memory:TranslationMemoryEngine",
    "nlp_research.translation.engines.rag_llm:RagLlmEngine",
    "nlp_research.translation.engines.nmt:NmtEngine",
)
DEFAULT_ENGINE_ID = "memory"


def _flag(value: str | None, default: bool) -> bool:
    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _items(value: str | None) -> tuple[str, ...]:
    if not value:
        return ()
    return tuple(item.strip() for item in value.split(",") if item.strip())


def _text(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    return value.strip()


@dataclass(frozen=True)
class Settings:
    datasets_dir: Path
    db_path: Path
    engine_specs: tuple[str, ...]
    default_engine: str | None
    enable_mock_engine: bool
    max_chars: int
    log_translations: bool
    admin_token: str | None
    cors_origins: tuple[str, ...]
    frontend_dist: Path
    learn_threshold: int = 20

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        env = os.environ if env is None else env
        raw_engines = env.get("LIKHIBI_ENGINES")
        if raw_engines is None:
            engine_specs, default_engine = DEFAULT_ENGINES, DEFAULT_ENGINE_ID
        elif raw_engines.strip().lower() in {"", "none"}:
            engine_specs, default_engine = (), None
        else:
            engine_specs, default_engine = _items(raw_engines), None
        return cls(
            datasets_dir=Path(env.get("LIKHIBI_DATASETS_DIR") or REPO_ROOT / "datasets"),
            db_path=Path(env.get("LIKHIBI_DB_PATH") or BACKEND_ROOT / "var" / "likhibi.sqlite3"),
            engine_specs=engine_specs,
            default_engine=_text(env.get("LIKHIBI_DEFAULT_ENGINE")) or default_engine,
            enable_mock_engine=_flag(env.get("LIKHIBI_ENABLE_MOCK_ENGINE"), False),
            max_chars=int(env.get("LIKHIBI_MAX_CHARS") or 2000),
            log_translations=_flag(env.get("LIKHIBI_LOG_TRANSLATIONS"), True),
            admin_token=_text(env.get("LIKHIBI_ADMIN_TOKEN")),
            learn_threshold=int(env.get("LIKHIBI_LEARN_THRESHOLD") or 20),
            cors_origins=_items(env.get("LIKHIBI_CORS_ORIGINS")) or _DEFAULT_CORS,
            frontend_dist=Path(
                env.get("LIKHIBI_FRONTEND_DIST") or REPO_ROOT / "dashboard" / "frontend" / "dist"
            ),
        )
