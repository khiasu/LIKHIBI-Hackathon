"""
Retrieval-augmented LLM engine: show a large language model the most similar human
sentence pairs (and glossary hints), then ask it to translate.

No training is needed, every pair in the bank is usable immediately, and an approved
community correction improves the very next request. The same method reached chrF++
54 for English to Nagamese at the WMT 2026 shared task (Dhawan et al., 2026).

Configuration (environment variables):
    LIKHIBI_LLM_PROVIDER   "gemini" (default) or "openai" (any OpenAI-compatible server)
    LIKHIBI_LLM_MODEL      default "gemini-3.8-flash" for Gemini; required for "openai"
    LIKHIBI_LLM_THINKING   Gemini thinking level: "low" (default), "minimal", "medium", "high",
                           or "default" to keep the model's own setting
    LIKHIBI_LLM_FALLBACK_MODELS  Gemini models to use when the main one is out of quota, comma-separated
                           (default "gemini-3.5-flash-lite"; "none" turns fallback off)
    LIKHIBI_LLM_API_KEY    falls back to GEMINI_API_KEY / GOOGLE_API_KEY, or OPENAI_API_KEY.
                           A Gemini key saved on the dashboard's Engines page wins over all of them.
    LIKHIBI_LLM_BASE_URL   OpenAI-compatible base URL, e.g. http://localhost:11434/v1 for Ollama
    LIKHIBI_LLM_EXAMPLES   number of retrieved example pairs in the prompt (default 24)
    LIKHIBI_LLM_TIMEOUT    seconds per request (default 45)
    LIKHIBI_LLM_CACHE      path of the response cache, or "off" (default <repo>/.cache/likhibi/llm_cache.sqlite3)
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import threading
from contextlib import closing
from pathlib import Path
from typing import Sequence

from .. import credentials
from ..bank import DEFAULT_DATASETS_DIR, REPO_ROOT, community_examples, load_examples, merge
from ..engine_api import (
    EN_TO_NAG,
    NAG_TO_EN,
    Direction,
    EngineError,
    EngineInfo,
    EngineKind,
    EngineState,
    EngineStatus,
    EngineUnavailableError,
    Evidence,
    EvidenceKind,
    ParallelExample,
    TranslationEngine,
    TranslationRequest,
    TranslationResult,
)
from ..glossary import Glossary
from ..retrieval import Example, ExampleIndex, Hit
from .llm_providers import (
    DEFAULT_GEMINI_FALLBACKS,
    DEFAULT_GEMINI_MODEL,
    GeminiClient,
    LLMClient,
    OpenAICompatibleClient,
)

LANGUAGE_NAMES = {"en": "English", "nag": "Nagamese"}

SYSTEM_PROMPT = """You translate between English and Nagamese (Naga Pidgin, ISO 639-3 "nag"), the Assamese-lexified creole that is the lingua franca of Nagaland, India. Nagamese is written in the Roman script.

Rules:
- Reply with the translation only: no notes, labels, quotation marks or alternatives.
- Follow the vocabulary, spelling and grammar of the example pairs. Nagamese is verb-final; it marks case with postpositions such as ke, te, pora, laga, logot and nimite; plural with khan; past with -ise or -se, present with ase, future with -bo; negation with na- or nohoi.
- Keep names unchanged, and keep English loanwords that Nagamese speakers use, such as school, phone or office.
- Use apuni for a polite "you" unless the source is clearly familiar.
- Many examples come from the Bible. Copy their grammar, not their subject matter: never add religious words such as Isor, Probhu or atma unless the source sentence means them.
- When translating into English, write natural modern English."""


def _env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    return value.strip() if value and value.strip() else default


def configured_provider() -> str:
    return (_env("LIKHIBI_LLM_PROVIDER", "gemini") or "gemini").lower()


def configured_model(provider: str) -> str | None:
    if provider == "gemini":
        return _env("LIKHIBI_LLM_MODEL", DEFAULT_GEMINI_MODEL)
    return _env("LIKHIBI_LLM_MODEL")


def find_api_key(provider: str) -> tuple[str | None, str | None]:
    """The API key to use, and where it came from: "dashboard" or an environment variable name.

    A Gemini key saved on the dashboard's Engines page wins over environment variables.
    """
    if provider == "gemini":
        saved = credentials.get_secret(credentials.GEMINI_API_KEY)
        if saved:
            return saved, "dashboard"
        names: tuple[str, ...] = ("LIKHIBI_LLM_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY")
    else:
        names = ("LIKHIBI_LLM_API_KEY", "OPENAI_API_KEY")
    for name in names:
        value = _env(name)
        if value:
            return value, name
    return None, None


def build_client() -> tuple[LLMClient | None, str | None]:
    """Create the configured client, or explain what is missing."""
    provider = configured_provider()
    timeout = float(_env("LIKHIBI_LLM_TIMEOUT", "45") or 45)
    if provider == "gemini":
        key, _ = find_api_key(provider)
        if not key:
            return None, "Add a Gemini API key on the Engines page, or set GEMINI_API_KEY. Keys are free from Google AI Studio."
        thinking = _env("LIKHIBI_LLM_THINKING", "low") or "low"
        fallbacks = _env("LIKHIBI_LLM_FALLBACK_MODELS", ",".join(DEFAULT_GEMINI_FALLBACKS)) or ""
        return GeminiClient(
            api_key=key,
            model=configured_model(provider) or DEFAULT_GEMINI_MODEL,
            timeout=timeout,
            thinking_level="" if thinking.lower() == "default" else thinking.lower(),
            fallback_models=() if fallbacks.lower() == "none" else tuple(m.strip() for m in fallbacks.split(",") if m.strip()),
        ), None
    if provider == "openai":
        base_url = _env("LIKHIBI_LLM_BASE_URL", "https://api.openai.com/v1") or "https://api.openai.com/v1"
        model = configured_model(provider)
        key, _ = find_api_key(provider)
        local = re.match(r"https?://(localhost|127\.0\.0\.1)", base_url) is not None
        if not model:
            return None, "Set LIKHIBI_LLM_MODEL to the model name your OpenAI-compatible server offers."
        if not key and not local:
            return None, "Set OPENAI_API_KEY or LIKHIBI_LLM_API_KEY for this OpenAI-compatible server."
        return OpenAICompatibleClient(model=model, api_key=key, base_url=base_url, timeout=timeout), None
    return None, f"Unknown LIKHIBI_LLM_PROVIDER '{provider}'. Use 'gemini' or 'openai'."


class ResponseCache:
    """SQLite cache of LLM answers: repeat demo inputs are instant and free."""

    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(path)) as conn, conn:
            conn.execute("CREATE TABLE IF NOT EXISTS answers (key TEXT PRIMARY KEY, text TEXT NOT NULL)")

    def get(self, key: str) -> str | None:
        with closing(sqlite3.connect(self.path, timeout=5)) as conn:
            row = conn.execute("SELECT text FROM answers WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None

    def put(self, key: str, text: str) -> None:
        with closing(sqlite3.connect(self.path, timeout=5)) as conn, conn:
            conn.execute("INSERT OR REPLACE INTO answers (key, text) VALUES (?, ?)", (key, text))


def build_prompt(text: str, direction: Direction, hits: Sequence[Hit], hints: Sequence[tuple[str, str]]) -> str:
    source, target = LANGUAGE_NAMES[direction.source], LANGUAGE_NAMES[direction.target]
    lines = [f"Example translations from {source} to {target}, most similar first:", ""]
    for hit in hits:
        lines.append(f"{source}: {hit.example.side(direction.source)}")
        lines.append(f"{target}: {hit.example.side(direction.target)}")
        lines.append("")
    if hints:
        lines.append("Glossary hints, extracted automatically, so they may be wrong:")
        lines.extend(f"- {word}: {meaning}" for word, meaning in hints)
        lines.append("")
    lines.append(f"Translate this {source} text into {target}.")
    lines.append(f"{source}: {text}")
    lines.append(f"{target}:")
    return "\n".join(lines)


_LABEL = re.compile(r"^\s*(english|nagamese|translation)\s*:\s*", re.IGNORECASE)


def clean_output(raw: str, source_text: str) -> str:
    """Strip labels and quotes the model may add; keep one line for one-line input."""
    lines = [line.strip() for line in raw.strip().splitlines() if line.strip()]
    if not lines:
        return ""
    text = " ".join(lines) if "\n" in source_text.strip() else lines[0]
    text = _LABEL.sub("", text).strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'" and source_text.strip()[:1] not in "\"'":
        text = text[1:-1].strip()
    return text


class RagLlmEngine(TranslationEngine):
    info = EngineInfo(
        id="rag-llm",
        name="LLM with retrieved examples",
        kind=EngineKind.LLM_RAG,
        version="1.0.0",
        directions=(EN_TO_NAG, NAG_TO_EN),
        description=(
            "Prompts a large language model with the most similar human sentence pairs and glossary "
            "hints. Needs an API key; Gemini by default."
        ),
        offline=False,
    )

    def __init__(self, client: LLMClient | None = None, datasets_dir: Path | None = None) -> None:
        self.datasets_dir = Path(datasets_dir or _env("LIKHIBI_DATASETS_DIR") or DEFAULT_DATASETS_DIR)
        self.examples_per_prompt = int(_env("LIKHIBI_LLM_EXAMPLES", "24") or 24)
        self._client_injected = client is not None
        if client is None:
            client, self._config_problem = build_client()
        else:
            self._config_problem = None
        self.client = client
        cache_setting = _env("LIKHIBI_LLM_CACHE", str(REPO_ROOT / ".cache" / "likhibi" / "llm_cache.sqlite3"))
        self.cache = None if cache_setting == "off" else ResponseCache(Path(cache_setting))
        self._base: list[Example] = []
        self._community: list[Example] = []
        self._index = ExampleIndex([])
        self.glossary = Glossary.empty()
        self._lock = threading.Lock()

    def load(self) -> None:
        self._base = load_examples(self.datasets_dir)
        self.glossary = Glossary.load(self.datasets_dir)
        self._rebuild()

    def _rebuild(self) -> None:
        index = ExampleIndex(merge(self._base, self._community))
        with self._lock:
            self._index = index

    def update_community_examples(self, examples: Sequence[ParallelExample]) -> None:
        self._community = community_examples(examples)
        self._rebuild()

    def credentials_changed(self) -> None:
        if self._client_injected:  # a client handed in explicitly, as in tests, stays
            return
        client, problem = build_client()
        with self._lock:
            self.client, self._config_problem = client, problem

    def status(self) -> EngineStatus:
        if self.client is None:
            return EngineStatus(EngineState.NOT_CONFIGURED, self._config_problem)
        if not len(self._index):
            return EngineStatus(EngineState.NOT_CONFIGURED, "No sentence pairs found to show the model.")
        note = self.client.note() if hasattr(self.client, "note") else None
        line = f"{self.client.provider} · {self.client.model}"
        return EngineStatus(EngineState.READY, f"{line}. {note}." if note else line)

    def translate(self, request: TranslationRequest) -> TranslationResult:
        if self.client is None:
            raise EngineUnavailableError(self._config_problem or "The LLM engine is not configured.")
        with self._lock:
            index = self._index
        direction = request.direction
        hits = index.search(request.text, direction, k=self.examples_per_prompt)
        # Most similar first in the evidence, but last in the prompt, closest to the question.
        hints = self.glossary.hints(request.text, direction)
        prompt = build_prompt(request.text, direction, list(reversed(hits)), hints)
        key = hashlib.sha256(
            json.dumps([self.client.provider, self.client.model, SYSTEM_PROMPT, prompt]).encode("utf-8")
        ).hexdigest()
        cached = self.cache.get(key) if self.cache else None
        raw = cached if cached is not None else self.client.complete(SYSTEM_PROMPT, prompt)
        translation = clean_output(raw, request.text)
        if not translation:
            raise EngineError("The model returned no usable translation.")
        if cached is None and self.cache:
            self.cache.put(key, raw)
        evidence = [
            Evidence(
                kind=EvidenceKind.EXAMPLE,
                source_text=hit.example.side(direction.source),
                target_text=hit.example.side(direction.target),
                origin=hit.example.origin,
                similarity=round(hit.similarity, 3),
            )
            for hit in hits[:6]
        ]
        evidence += [
            Evidence(kind=EvidenceKind.GLOSSARY, source_text=word, target_text=meaning, origin="auto-glossary")
            for word, meaning in hints[:4]
        ]
        return TranslationResult(
            translation=translation,
            evidence=tuple(evidence),
            metadata={
                "provider": self.client.provider,
                "model": self.client.model if cached is not None else getattr(self.client, "last_model", None) or self.client.model,
                "examples_in_prompt": len(hits),
                "cached": cached is not None,
            },
        )
