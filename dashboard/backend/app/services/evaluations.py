"""Reads evaluation runs written by nlp_research.translation.evaluate_engine.

Each run is one JSON file in datasets/evaluations/mt_runs/ with this shape:

    {
      "run_id": "20261020T101500Z_rag-llm_everyday-test-v1_en-nag",
      "engine_id": "rag-llm",
      "engine_version": "0.1.0",
      "test_set": "everyday-test-v1",
      "direction": "en-nag",
      "n_segments": 200,
      "metrics": {"chrf++": 41.2, "bleu": 12.3},
      "created_at": "2026-10-20T10:15:00+00:00",
      "notes": "optional free text",
      "segments": [{"source": "...", "reference": "...", "hypothesis": "...", "chrf++": 38.1}]
    }
"""

from __future__ import annotations

import json
from numbers import Real
from pathlib import Path
from typing import Any

RUNS_DIR = Path("evaluations/mt_runs")
_REQUIRED = ("run_id", "engine_id", "test_set", "direction", "n_segments", "metrics", "created_at")


class InvalidRunError(ValueError):
    pass


def _validate(data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise InvalidRunError("Top level must be a JSON object")
    missing = [key for key in _REQUIRED if key not in data]
    if missing:
        raise InvalidRunError(f"Missing keys: {', '.join(missing)}")
    metrics = data["metrics"]
    if not isinstance(metrics, dict) or not all(
        isinstance(value, Real) and not isinstance(value, bool) for value in metrics.values()
    ):
        raise InvalidRunError("'metrics' must map metric names to numbers")
    if not isinstance(data["n_segments"], int):
        raise InvalidRunError("'n_segments' must be an integer")
    segments = data.get("segments", [])
    if not isinstance(segments, list):
        raise InvalidRunError("'segments' must be a list")
    return data


def _summary(data: dict[str, Any], file: str) -> dict[str, Any]:
    return {
        "run_id": str(data["run_id"]),
        "engine_id": str(data["engine_id"]),
        "engine_version": str(data.get("engine_version") or ""),
        "test_set": str(data["test_set"]),
        "direction": str(data["direction"]),
        "n_segments": data["n_segments"],
        "metrics": {str(key): float(value) for key, value in data["metrics"].items()},
        "created_at": str(data["created_at"]),
        "notes": data.get("notes"),
        "file": file,
    }


def _load_all(datasets_dir: Path) -> tuple[list[tuple[dict[str, Any], dict[str, Any]]], list[dict[str, str]]]:
    runs_dir = datasets_dir / RUNS_DIR
    loaded: list[tuple[dict[str, Any], dict[str, Any]]] = []
    invalid: list[dict[str, str]] = []
    for path in sorted(runs_dir.glob("*.json")):
        file = path.relative_to(datasets_dir).as_posix()
        try:
            data = _validate(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError) as exc:  # JSONDecodeError is a ValueError
            invalid.append({"file": file, "error": str(exc)})
            continue
        loaded.append((_summary(data, file), data))
    loaded.sort(key=lambda item: item[0]["created_at"], reverse=True)
    return loaded, invalid


def list_runs(datasets_dir: Path) -> dict[str, Any]:
    loaded, invalid = _load_all(datasets_dir)
    return {"runs": [summary for summary, _ in loaded], "invalid_files": invalid}


def get_run(datasets_dir: Path, run_id: str) -> dict[str, Any] | None:
    loaded, _ = _load_all(datasets_dir)
    for summary, data in loaded:
        if summary["run_id"] == run_id:
            segments = []
            for segment in data.get("segments", []):
                if not isinstance(segment, dict):
                    continue
                score = segment.get("chrf++")
                segments.append(
                    {
                        "source": str(segment.get("source", "")),
                        "reference": str(segment.get("reference", "")),
                        "hypothesis": str(segment.get("hypothesis", "")),
                        "chrf": float(score) if isinstance(score, Real) else None,
                    }
                )
            return {**summary, "segments": segments}
    return None
