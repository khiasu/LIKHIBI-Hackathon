"""
Translation memory: answers only when a human translation of (nearly) the same
sentence is stored. Otherwise it declines, and auto mode moves to the next engine.

It is precise, instant and offline, which makes it the safety net for a live demo,
and it is where approved community corrections pay off first.

Configuration:
    LIKHIBI_DATASETS_DIR    dataset root (default: <repo>/datasets)
    LIKHIBI_TM_THRESHOLD    minimum similarity for a near match, 0..1 (default 0.95)
"""

from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import Sequence

from ..bank import DEFAULT_DATASETS_DIR, community_examples, load_examples, merge
from ..engine_api import (
    EN_TO_NAG,
    Alternative,
    NAG_TO_EN,
    EngineDeclinedError,
    EngineInfo,
    EngineKind,
    EngineState,
    EngineStatus,
    Evidence,
    EvidenceKind,
    ParallelExample,
    TranslationEngine,
    TranslationRequest,
    TranslationResult,
)
from ..retrieval import Example, ExampleIndex, Hit
from ..text import match_key


class TranslationMemoryEngine(TranslationEngine):
    info = EngineInfo(
        id="memory",
        name="Translation memory",
        kind=EngineKind.TRANSLATION_MEMORY,
        version="1.0.0",
        directions=(EN_TO_NAG, NAG_TO_EN),
        description=(
            "Returns a stored human translation when the input matches one exactly or almost "
            "exactly. Approved corrections are used first. Declines everything else."
        ),
        offline=True,
    )

    def __init__(self, datasets_dir: Path | None = None, threshold: float | None = None) -> None:
        self.datasets_dir = Path(datasets_dir or os.environ.get("LIKHIBI_DATASETS_DIR") or DEFAULT_DATASETS_DIR)
        self.threshold = threshold if threshold is not None else float(os.environ.get("LIKHIBI_TM_THRESHOLD") or 0.95)
        self._base: list[Example] = []
        self._community: list[Example] = []
        self._index = ExampleIndex([])
        self._lock = threading.Lock()

    def load(self) -> None:
        self._base = load_examples(self.datasets_dir)
        self._rebuild()

    def _rebuild(self) -> None:
        index = ExampleIndex(merge(self._base, self._community))
        with self._lock:
            self._index = index

    def update_community_examples(self, examples: Sequence[ParallelExample]) -> None:
        self._community = community_examples(examples)
        self._rebuild()

    def status(self) -> EngineStatus:
        if not len(self._index):
            return EngineStatus(EngineState.NOT_CONFIGURED, "No sentence pairs found in datasets/processed/parallel_corpus/")
        return EngineStatus(EngineState.READY, f"{len(self._index):,} sentence pairs in memory")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        with self._lock:
            index = self._index
        direction = request.direction

        exact = index.exact(request.text, direction)
        if exact:
            # Community corrections come first; sorted() keeps bank order otherwise.
            best = sorted(exact, key=lambda e: not e.origin.startswith("feedback:"))[0]
            return self._result(index, best, request, similarity=1.0, exact=True)

        hits = index.search(request.text, direction, k=5, pool=50)
        best_hit: Hit | None = hits[0] if hits else None
        if best_hit and best_hit.similarity >= self.threshold:
            return self._result(index, best_hit.example, request, similarity=best_hit.similarity, exact=False)
        closest = f" Closest stored sentence is {best_hit.similarity:.0%} similar." if best_hit else ""
        raise EngineDeclinedError(f"No stored translation matches this sentence.{closest}")

    @staticmethod
    def _result(
        index: ExampleIndex, example: Example, request: TranslationRequest, similarity: float, exact: bool
    ) -> TranslationResult:
        direction = request.direction
        warnings = () if exact else (
            "Near match: the stored sentence differs slightly from yours. Check the evidence below.",
        )
        alternatives: tuple[Alternative, ...] = ()
        if request.num_alternatives:
            chosen = match_key(example.side(direction.target), direction.target)
            other_renderings = [
                e for e in index.exact(example.side(direction.source), direction)
                if match_key(e.side(direction.target), direction.target) != chosen
            ]
            alternatives = tuple(Alternative(e.side(direction.target)) for e in other_renderings[: request.num_alternatives])
        return TranslationResult(
            translation=example.side(direction.target),
            alternatives=alternatives,
            confidence=round(similarity, 3),
            evidence=(
                Evidence(
                    kind=EvidenceKind.MEMORY,
                    source_text=example.side(direction.source),
                    target_text=example.side(direction.target),
                    origin=example.origin,
                    similarity=round(similarity, 3),
                ),
            ),
            warnings=warnings,
            metadata={"exact": exact},
        )
