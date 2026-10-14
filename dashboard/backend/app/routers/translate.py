from __future__ import annotations

from fastapi import APIRouter, Depends

from nlp_research.translation.engine_api import Direction, EngineInfo

from ..config import Settings
from ..deps import get_registry, get_service, get_settings
from ..engines.registry import (
    EngineNotReadyError,
    EngineRegistry,
    NoEngineAvailableError,
    UnknownEngineError,
)
from ..errors import ApiError
from ..schemas import (
    AlternativeOut,
    CompareIn,
    CompareItem,
    CompareOut,
    EngineSummary,
    EvidenceOut,
    TranslateIn,
    TranslationOut,
)
from ..services.translation import (
    AllEnginesFailedError,
    NoConfidentTranslationError,
    ServedTranslation,
    TranslationService,
)

router = APIRouter(tags=["translation"])


def _summary(info: EngineInfo) -> EngineSummary:
    return EngineSummary(id=info.id, name=info.name, kind=info.kind.value, version=info.version)


def _to_out(served: ServedTranslation) -> TranslationOut:
    result = served.result
    return TranslationOut(
        request_id=served.request_id,
        created_at=served.created_at,
        engine=_summary(served.info),
        source_lang=served.request.direction.source,
        target_lang=served.request.direction.target,
        source_text=served.request.text,
        translation=result.translation,
        alternatives=[AlternativeOut(text=a.text, score=a.score) for a in result.alternatives],
        confidence=result.confidence,
        evidence=[
            EvidenceOut(
                kind=e.kind.value,
                source_text=e.source_text,
                target_text=e.target_text,
                origin=e.origin,
                similarity=e.similarity,
            )
            for e in result.evidence
        ],
        warnings=[*served.fallback_warnings, *result.warnings],
        latency_ms=served.latency_ms,
    )


def _validated_input(text: str, source: str, target: str, settings: Settings) -> tuple[str, Direction]:
    if source == target:
        raise ApiError(400, "same_language", "Source and target languages must differ.")
    cleaned = text.strip()
    if not cleaned:
        raise ApiError(422, "empty_text", "Enter some text to translate.")
    if len(cleaned) > settings.max_chars:
        raise ApiError(
            413,
            "text_too_long",
            f"Text is limited to {settings.max_chars} characters.",
            {"max_chars": settings.max_chars, "length": len(cleaned)},
        )
    return cleaned, Direction(source, target)


def _no_engine_error(registry: EngineRegistry, direction: Direction, message: str) -> ApiError:
    return ApiError(
        503,
        "no_engine_available",
        message,
        {
            "direction": direction.code,
            "registered_engines": [entry.id for entry in registry.entries()],
        },
    )


@router.post("/translate", response_model=TranslationOut)
def translate(
    body: TranslateIn,
    settings: Settings = Depends(get_settings),
    registry: EngineRegistry = Depends(get_registry),
    service: TranslationService = Depends(get_service),
) -> TranslationOut:
    text, direction = _validated_input(body.text, body.source_lang, body.target_lang, settings)
    try:
        served = service.translate(text, direction, body.engine, body.num_alternatives)
    except UnknownEngineError as exc:
        raise ApiError(404, "unknown_engine", str(exc)) from exc
    except EngineNotReadyError as exc:
        raise ApiError(503, "engine_not_ready", str(exc)) from exc
    except NoEngineAvailableError as exc:
        raise _no_engine_error(registry, direction, str(exc)) from exc
    except AllEnginesFailedError as exc:
        raise ApiError(
            502,
            "engine_failed",
            str(exc),
            {"failures": [{"engine": e, "error": m} for e, m in exc.failures]},
        ) from exc
    except NoConfidentTranslationError as exc:
        raise ApiError(
            422,
            "no_confident_translation",
            str(exc),
            {"declined": [{"engine": e, "reason": m} for e, m in exc.declines]},
        ) from exc
    return _to_out(served)


@router.post("/translate/compare", response_model=CompareOut)
def compare(
    body: CompareIn,
    settings: Settings = Depends(get_settings),
    registry: EngineRegistry = Depends(get_registry),
    service: TranslationService = Depends(get_service),
) -> CompareOut:
    text, direction = _validated_input(body.text, body.source_lang, body.target_lang, settings)
    try:
        outcomes = service.compare(text, direction)
    except NoEngineAvailableError as exc:
        raise _no_engine_error(registry, direction, str(exc)) from exc
    return CompareOut(
        results=[
            CompareItem(
                engine=_summary(outcome.info),
                ok=outcome.served is not None,
                result=_to_out(outcome.served) if outcome.served else None,
                error=outcome.error,
                declined=outcome.declined,
            )
            for outcome in outcomes
        ]
    )
