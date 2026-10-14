"""Runs engines, applies the fallback chain, and logs what was served."""

from __future__ import annotations

import logging
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from datetime import datetime, timezone

from nlp_research.translation.engine_api import (
    Direction,
    EngineDeclinedError,
    EngineError,
    EngineInfo,
    ParallelExample,
    TranslationRequest,
    TranslationResult,
)

from ..engines.registry import EngineEntry, EngineRegistry, NoEngineAvailableError
from ..storage import Storage, TranslationRecord

logger = logging.getLogger(__name__)


class AllEnginesFailedError(Exception):
    def __init__(self, failures: list[tuple[str, str]]) -> None:
        self.failures = failures
        summary = "; ".join(f"{engine_id}: {message}" for engine_id, message in failures)
        super().__init__(f"Every candidate engine failed ({summary})")


class NoConfidentTranslationError(Exception):
    """Every engine that was asked declined; none failed."""

    def __init__(self, declines: list[tuple[str, str]]) -> None:
        self.declines = declines
        super().__init__("No engine has a confident translation for this text yet.")


def approved_examples(storage: Storage) -> list[ParallelExample]:
    """Approved community corrections as sentence pairs, for engines that retrieve examples."""
    examples = []
    for row in storage.approved_corrections():
        corrected = row["corrected_translation"]
        if row["source_lang"] == "en":
            english, nagamese = row["source_text"], corrected
        else:
            english, nagamese = corrected, row["source_text"]
        examples.append(ParallelExample(english=english, nagamese=nagamese, origin=f"feedback:{row['id']}"))
    return examples


@dataclass(frozen=True)
class ServedTranslation:
    request_id: str
    created_at: datetime
    info: EngineInfo
    request: TranslationRequest
    result: TranslationResult
    latency_ms: float
    fallback_warnings: tuple[str, ...] = ()


@dataclass(frozen=True)
class CompareOutcome:
    info: EngineInfo
    served: ServedTranslation | None
    error: str | None
    declined: bool = False


class TranslationService:
    def __init__(self, registry: EngineRegistry, storage: Storage, log_translations: bool) -> None:
        self.registry = registry
        self.storage = storage
        self.log_translations = log_translations

    def translate(
        self,
        text: str,
        direction: Direction,
        engine_id: str = "auto",
        num_alternatives: int = 0,
    ) -> ServedTranslation:
        """Translate with the named engine, or walk the fallback chain for "auto"."""
        candidates = self.registry.candidates(engine_id, direction)
        request = TranslationRequest(text=text, direction=direction, num_alternatives=num_alternatives)
        failures: list[tuple[str, str]] = []
        failed_names: list[str] = []
        declines: list[tuple[str, str]] = []
        for entry in candidates:
            try:
                served = self._run(entry, request)
            except EngineDeclinedError as exc:
                # "No confident answer" is normal for a translation memory: move on quietly.
                declines.append((entry.id, str(exc)))
                continue
            except EngineError as exc:
                failures.append((entry.id, str(exc)))
                failed_names.append(entry.engine.info.name)
                continue
            if failures:
                notes = tuple(
                    f"{name} could not answer ({message.rstrip('.')}), so {entry.engine.info.name} answered instead."
                    for name, (_, message) in zip(failed_names, failures)
                )
                served = replace(served, fallback_warnings=notes)
            return served
        if failures:
            raise AllEnginesFailedError(failures)
        raise NoConfidentTranslationError(declines)

    def compare(self, text: str, direction: Direction) -> list[CompareOutcome]:
        """Run every ready engine on the same input, concurrently."""
        entries = self.registry.ready_for(direction)
        if not entries:
            raise NoEngineAvailableError(f"No ready engine can translate {direction.code}")
        request = TranslationRequest(text=text, direction=direction)

        def run(entry: EngineEntry) -> CompareOutcome:
            try:
                return CompareOutcome(entry.engine.info, self._run(entry, request), None)
            except EngineDeclinedError as exc:
                return CompareOutcome(entry.engine.info, None, str(exc), declined=True)
            except EngineError as exc:
                return CompareOutcome(entry.engine.info, None, str(exc))

        with ThreadPoolExecutor(max_workers=len(entries)) as pool:
            return list(pool.map(run, entries))

    def _run(self, entry: EngineEntry, request: TranslationRequest) -> ServedTranslation:
        started = time.perf_counter()
        try:
            result = entry.engine.translate(request)
        except EngineDeclinedError:
            raise
        except EngineError:
            logger.warning("Engine %s failed", entry.id, exc_info=True)
            raise
        except Exception as exc:  # engine bugs become clean API errors, not 500s
            logger.exception("Engine %s crashed", entry.id)
            raise EngineError(f"{type(exc).__name__}: {exc}") from exc
        latency_ms = round((time.perf_counter() - started) * 1000, 1)
        if not isinstance(result, TranslationResult) or not isinstance(result.translation, str):
            raise EngineError(f"Engine '{entry.id}' returned an invalid result")
        served = ServedTranslation(
            request_id=uuid.uuid4().hex,
            created_at=datetime.now(timezone.utc),
            info=entry.engine.info,
            request=request,
            result=result,
            latency_ms=latency_ms,
        )
        if self.log_translations:
            self._log(served)
        return served

    def _log(self, served: ServedTranslation) -> None:
        try:
            self.storage.record_translation(
                TranslationRecord(
                    id=served.request_id,
                    created_at=served.created_at.isoformat(timespec="seconds"),
                    engine_id=served.info.id,
                    engine_version=served.info.version,
                    source_lang=served.request.direction.source,
                    target_lang=served.request.direction.target,
                    source_text=served.request.text,
                    translation=served.result.translation,
                    latency_ms=served.latency_ms,
                )
            )
        except Exception:  # a logging failure must never cost the user their translation
            logger.exception("Could not log translation %s", served.request_id)
