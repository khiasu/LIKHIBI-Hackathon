"""Holds the configured engines and decides which one serves a request."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Sequence

from nlp_research.translation.engine_api import (
    Direction,
    EngineKind,
    EngineState,
    EngineStatus,
    ParallelExample,
    TranslationEngine,
    load_engine,
)

from ..config import Settings
from .mock import MockEngine

logger = logging.getLogger(__name__)

AUTO = "auto"


class RegistryError(Exception):
    pass


class UnknownEngineError(RegistryError):
    pass


class EngineNotReadyError(RegistryError):
    pass


class NoEngineAvailableError(RegistryError):
    pass


@dataclass
class EngineEntry:
    engine: TranslationEngine
    load_error: str | None = None

    @property
    def id(self) -> str:
        return self.engine.info.id

    def status(self) -> EngineStatus:
        if self.load_error:
            return EngineStatus(EngineState.ERROR, self.load_error)
        try:
            return self.engine.status()
        except Exception as exc:  # an engine bug must not break the dashboard
            logger.exception("status() failed for engine %s", self.id)
            return EngineStatus(EngineState.ERROR, f"status() failed: {exc}")


@dataclass(frozen=True)
class BrokenEngine:
    """An engine spec that could not be turned into an engine."""

    spec: str
    error: str


class EngineRegistry:
    def __init__(
        self,
        engines: Sequence[TranslationEngine] = (),
        default_engine: str | None = None,
    ) -> None:
        self._entries: dict[str, EngineEntry] = {}
        self._broken: list[BrokenEngine] = []
        self._default = default_engine
        for engine in engines:
            self.add(engine)

    @classmethod
    def from_settings(cls, settings: Settings) -> "EngineRegistry":
        registry = cls(default_engine=settings.default_engine)
        for spec in settings.engine_specs:
            try:
                registry.add(load_engine(spec))
            except Exception as exc:
                logger.exception("Could not load engine %s", spec)
                registry._broken.append(BrokenEngine(spec, f"{type(exc).__name__}: {exc}"))
        if settings.enable_mock_engine:
            registry.add(MockEngine())
        return registry

    def add(self, engine: TranslationEngine) -> None:
        engine_id = engine.info.id
        if engine_id == AUTO or not engine_id:
            raise ValueError(f"Invalid engine id: {engine_id!r}")
        if engine_id in self._entries:
            raise ValueError(f"Duplicate engine id: {engine_id}")
        self._entries[engine_id] = EngineEntry(engine)

    def load_all(self) -> None:
        for entry in self._entries.values():
            try:
                entry.engine.load()
            except Exception as exc:
                logger.exception("load() failed for engine %s", entry.id)
                entry.load_error = f"load() failed: {type(exc).__name__}: {exc}"

    def broadcast_community_examples(self, examples: Sequence[ParallelExample]) -> None:
        """Hand every engine the current set of approved corrections."""
        for entry in self._entries.values():
            if entry.load_error:
                continue
            try:
                entry.engine.update_community_examples(examples)
            except Exception:  # one engine's bug must not block the others
                logger.exception("update_community_examples() failed for engine %s", entry.id)

    def broadcast_credentials_changed(self) -> None:
        """Tell every engine that an API key saved from the dashboard changed."""
        for entry in self._entries.values():
            if entry.load_error:
                continue
            try:
                entry.engine.credentials_changed()
            except Exception:
                logger.exception("credentials_changed() failed for engine %s", entry.id)

    @property
    def default_engine_id(self) -> str | None:
        return self._default if self._default in self._entries else None

    @property
    def broken(self) -> list[BrokenEngine]:
        return list(self._broken)

    def entries(self) -> list[EngineEntry]:
        return list(self._entries.values())

    def get(self, engine_id: str) -> EngineEntry:
        try:
            return self._entries[engine_id]
        except KeyError:
            raise UnknownEngineError(f"No engine named '{engine_id}' is registered") from None

    def ready_for(self, direction: Direction) -> list[EngineEntry]:
        """Ready engines that support the direction, in the order "auto" tries them.

        The configured default comes first, real engines next, and the mock
        engine last so it never shadows a real engine.
        """
        ready = [
            entry
            for entry in self._entries.values()
            if entry.engine.supports(direction) and entry.status().is_ready
        ]

        def priority(entry: EngineEntry) -> int:
            if entry.id == self.default_engine_id:
                return 0
            if entry.engine.info.kind is EngineKind.MOCK:
                return 2
            return 1

        return sorted(ready, key=priority)  # sorted() is stable, keeps registration order

    def candidates(self, engine_id: str, direction: Direction) -> list[EngineEntry]:
        """Engines to try, in order, for a request naming ``engine_id``."""
        if engine_id == AUTO:
            ready = self.ready_for(direction)
            if not ready:
                raise NoEngineAvailableError(
                    f"No ready engine can translate {direction.code}. "
                    "Connect one through LIKHIBI_ENGINES."
                )
            return ready
        entry = self.get(engine_id)
        if not entry.engine.supports(direction):
            raise EngineNotReadyError(f"Engine '{engine_id}' does not support {direction.code}")
        status = entry.status()
        if not status.is_ready:
            reason = status.message or status.state.value
            raise EngineNotReadyError(f"Engine '{engine_id}' is not ready: {reason}")
        return [entry]
