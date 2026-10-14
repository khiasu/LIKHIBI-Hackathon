"""Engines that exercise every path through the registry and the API.

They are loaded through engine specs such as "tests.fake_engines:EchoEngine",
the same way real engines are configured.
"""

from __future__ import annotations

from nlp_research.translation.engine_api import (
    EN_TO_NAG,
    NAG_TO_EN,
    EngineDeclinedError,
    EngineError,
    EngineInfo,
    EngineKind,
    EngineState,
    EngineStatus,
    Evidence,
    EvidenceKind,
    TranslationEngine,
    TranslationRequest,
    TranslationResult,
)

BOTH = (EN_TO_NAG, NAG_TO_EN)


def _info(engine_id: str, directions=BOTH, kind: EngineKind = EngineKind.NMT) -> EngineInfo:
    return EngineInfo(id=engine_id, name=engine_id.title(), kind=kind, version="1.0", directions=directions)


class EchoEngine(TranslationEngine):
    info = _info("echo")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        return TranslationResult(
            translation=f"echo:{request.text}",
            confidence=0.5,
            evidence=(
                Evidence(EvidenceKind.EXAMPLE, "Where is the market?", "Bazar kot te ase?", "test:1", 0.8),
            ),
        )


class EnglishOnlyEngine(TranslationEngine):
    info = _info("en-only", directions=(EN_TO_NAG,))

    def translate(self, request: TranslationRequest) -> TranslationResult:
        return TranslationResult(translation="only en-nag")


class FailingEngine(TranslationEngine):
    info = _info("failing")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        raise EngineError("quota exceeded")


class CrashingEngine(TranslationEngine):
    info = _info("crashing")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        raise RuntimeError("bug inside the engine")


class InvalidResultEngine(TranslationEngine):
    info = _info("invalid-result")

    def translate(self, request: TranslationRequest):  # type: ignore[override]
        return "a bare string instead of a TranslationResult"


class NotConfiguredEngine(TranslationEngine):
    info = _info("not-configured")

    def status(self) -> EngineStatus:
        return EngineStatus(EngineState.NOT_CONFIGURED, "API key missing")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        raise AssertionError("must not be called")


class BadLoadEngine(TranslationEngine):
    info = _info("bad-load")

    def load(self) -> None:
        raise FileNotFoundError("model.bin")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        raise AssertionError("must not be called")


class DecliningEngine(TranslationEngine):
    """Like a translation memory with no match: declines, which is not a failure."""

    info = _info("declining", kind=EngineKind.TRANSLATION_MEMORY)

    def translate(self, request: TranslationRequest) -> TranslationResult:
        raise EngineDeclinedError("no stored translation")


class RecordingEngine(TranslationEngine):
    """Remembers the community examples it was given."""

    info = _info("recording")
    received: list = []

    def update_community_examples(self, examples) -> None:
        RecordingEngine.received = list(examples)

    def translate(self, request: TranslationRequest) -> TranslationResult:
        return TranslationResult(translation=f"recorded:{len(RecordingEngine.received)}")


class UppercaseEngine(TranslationEngine):
    """Deterministic engine for the evaluation harness test."""

    info = _info("uppercase")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        return TranslationResult(translation=request.text.upper())


def not_an_engine() -> object:
    return object()
