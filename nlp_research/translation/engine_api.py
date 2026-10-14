"""
Translation engine contract for Likhibi.

Every translation engine (retrieval-augmented LLM, fine-tuned NMT model,
translation memory, ...) implements :class:`TranslationEngine`. The dashboard
backend, the evaluation harness and, later, the Android keyboard talk to
engines only through this contract. Engines can therefore be added, swapped
or compared without changing any caller.

This module depends on the standard library only, so engines can be developed
in notebooks or training scripts without installing the web stack.

Minimal engine::

    from nlp_research.translation.engine_api import (
        EN_TO_NAG, EngineInfo, EngineKind, TranslationEngine,
        TranslationRequest, TranslationResult,
    )

    class MyEngine(TranslationEngine):
        info = EngineInfo(
            id="my-engine", name="My engine", kind=EngineKind.NMT,
            version="0.1.0", directions=(EN_TO_NAG,), offline=True,
        )

        def translate(self, request: TranslationRequest) -> TranslationResult:
            return TranslationResult(translation=...)

Register it with the dashboard through the ``LIKHIBI_ENGINES`` environment
variable, for example ``LIKHIBI_ENGINES=my_package.my_module:MyEngine``.
"""

from __future__ import annotations

import abc
import enum
import importlib
from dataclasses import dataclass, field
from typing import Any, Mapping, Sequence

ENGLISH = "en"
NAGAMESE = "nag"  # ISO 639-3 code for Nagamese (Naga Pidgin)

SUPPORTED_LANGUAGES: Mapping[str, str] = {
    ENGLISH: "English",
    NAGAMESE: "Nagamese",
}


@dataclass(frozen=True)
class Direction:
    """A translation direction such as English -> Nagamese."""

    source: str
    target: str

    def __post_init__(self) -> None:
        for code in (self.source, self.target):
            if code not in SUPPORTED_LANGUAGES:
                raise ValueError(f"Unsupported language code: {code!r}")
        if self.source == self.target:
            raise ValueError("Source and target languages must differ")

    @property
    def code(self) -> str:
        """Short form used in APIs and file names, e.g. ``"en-nag"``."""
        return f"{self.source}-{self.target}"

    @classmethod
    def parse(cls, code: str) -> "Direction":
        source, sep, target = code.partition("-")
        if not sep:
            raise ValueError(f"Direction must look like 'en-nag', got {code!r}")
        return cls(source, target)

    def __str__(self) -> str:
        return self.code


EN_TO_NAG = Direction(ENGLISH, NAGAMESE)
NAG_TO_EN = Direction(NAGAMESE, ENGLISH)


class EngineKind(str, enum.Enum):
    """The family an engine belongs to. Used for display and reporting."""

    LLM_RAG = "llm-rag"  # LLM prompted with retrieved parallel examples
    NMT = "nmt"  # fine-tuned encoder-decoder model (NLLB, M2M-100, mBART)
    LLM_FINETUNED = "llm-finetuned"  # LoRA or full fine-tune of a decoder LLM
    TRANSLATION_MEMORY = "translation-memory"  # reuse of stored human translations
    RULE_BASED = "rule-based"
    MOCK = "mock"  # development only, never a real translation


class EngineState(str, enum.Enum):
    READY = "ready"
    NOT_CONFIGURED = "not_configured"  # e.g. missing API key or model files
    ERROR = "error"


@dataclass(frozen=True)
class EngineStatus:
    state: EngineState
    message: str | None = None

    @property
    def is_ready(self) -> bool:
        return self.state is EngineState.READY


@dataclass(frozen=True)
class EngineInfo:
    """Static description of an engine."""

    id: str  # stable, URL-safe identifier, e.g. "rag-llm"
    name: str  # human-readable name
    kind: EngineKind
    version: str
    directions: tuple[Direction, ...]
    description: str = ""
    offline: bool = False  # True when the engine runs without network access


@dataclass(frozen=True)
class TranslationRequest:
    text: str
    direction: Direction
    num_alternatives: int = 0  # extra candidates wanted beyond the best one
    options: Mapping[str, Any] = field(default_factory=dict)  # engine-specific knobs


@dataclass(frozen=True)
class Alternative:
    text: str
    score: float | None = None  # engine-defined; higher is better


class EvidenceKind(str, enum.Enum):
    EXAMPLE = "example"  # a retrieved parallel sentence pair
    GLOSSARY = "glossary"  # a dictionary entry used for the translation
    MEMORY = "memory"  # an exact or fuzzy translation-memory match


@dataclass(frozen=True)
class Evidence:
    """Material the engine relied on, shown to users as "why this translation".

    Low-resource output is often uncertain. Showing the human translations an
    engine leaned on lets a speaker judge the output quickly.
    """

    kind: EvidenceKind
    source_text: str
    target_text: str
    origin: str  # provenance, e.g. "bible:JHN_3_16" or "feedback:42"
    similarity: float | None = None  # 0..1 when the engine can compute it


@dataclass(frozen=True)
class TranslationResult:
    translation: str
    alternatives: tuple[Alternative, ...] = ()
    confidence: float | None = None  # 0..1, engine-defined; None when unknown
    evidence: tuple[Evidence, ...] = ()
    warnings: tuple[str, ...] = ()
    metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ParallelExample:
    """A human-verified sentence pair, for example an approved correction."""

    english: str
    nagamese: str
    origin: str  # provenance, e.g. "feedback:42"
    domain: str = "everyday"


class EngineError(Exception):
    """An engine failed to produce a translation."""


class EngineUnavailableError(EngineError):
    """The engine cannot serve requests right now (not loaded, quota, outage)."""


class EngineDeclinedError(EngineError):
    """The engine has no confident answer for this input, which is not a failure.

    A translation memory raises it when nothing close enough is stored. Auto mode
    then moves on to the next engine without warning the user.
    """


class TranslationEngine(abc.ABC):
    """Base class for every Likhibi translation engine.

    Implementations must be safe to call from several threads, because the
    web backend serves requests concurrently. Engines that call remote APIs
    should enforce their own timeouts and raise :class:`EngineError` on
    failure instead of hanging.
    """

    #: Subclasses set this as a class attribute or override it as a property.
    info: EngineInfo

    def load(self) -> None:
        """Load models or open API clients. Called once before first use."""

    def status(self) -> EngineStatus:
        """Report whether the engine can serve requests right now."""
        return EngineStatus(EngineState.READY)

    @abc.abstractmethod
    def translate(self, request: TranslationRequest) -> TranslationResult:
        """Translate one request or raise :class:`EngineError`."""

    def translate_batch(self, requests: Sequence[TranslationRequest]) -> list[TranslationResult]:
        """Translate several requests. Override when the engine can batch."""
        return [self.translate(request) for request in requests]

    def update_community_examples(self, examples: Sequence[ParallelExample]) -> None:
        """Receive the full, current set of approved community corrections.

        The dashboard calls this at start-up and whenever a reviewer approves or
        rejects a correction. Engines that retrieve examples (translation memory,
        retrieval-augmented LLMs) should use them at once; others can ignore them.
        """

    def credentials_changed(self) -> None:
        """Reconnect after an API key saved from the dashboard changes.

        Engines that read keys at start-up rebuild their client here; others can ignore it.
        """

    def supports(self, direction: Direction) -> bool:
        return direction in self.info.directions


def load_engine(spec: str) -> TranslationEngine:
    """Instantiate an engine from a ``"package.module:ClassOrFactory"`` spec.

    The attribute after the colon is called without arguments. Engines read
    their own configuration (API keys, model paths) from environment variables.
    """
    module_name, sep, attr = spec.strip().partition(":")
    if not sep or not module_name or not attr:
        raise ValueError(f"Engine spec must look like 'package.module:ClassName', got {spec!r}")
    module = importlib.import_module(module_name)
    factory = getattr(module, attr)
    engine = factory()
    if not isinstance(engine, TranslationEngine):
        raise TypeError(f"{spec} did not produce a TranslationEngine (got {type(engine).__name__})")
    return engine
