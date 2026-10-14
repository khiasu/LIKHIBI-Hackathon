"""
Evaluate a translation engine on a held-out test set and record the run.

The run is written to datasets/evaluations/mt_runs/<run_id>.json, where the
dashboard's Evaluation page picks it up.

Usage, from the repository root:

    python -m nlp_research.translation.evaluate_engine \
        --engine nlp_research.translation.engines.rag_llm:RagLlmEngine \
        --test-set datasets/processed/test_sets/everyday_test_en_nag.tsv \
        --direction en-nag

Repeat ``--engine`` to score a fallback chain the way the dashboard's auto mode
runs it, for example the translation memory first and the NMT model after it.

Test set format: a UTF-8 TSV with a header row containing the columns
``source_text`` and ``target_text``, one segment per row.

Metrics come from sacrebleu (``pip install sacrebleu``):

* chrF++ is the primary metric. It scores character n-grams, so it is fairer
  than BLEU to unstandardised Nagamese spelling (apnikhan / apuni khan).
* BLEU is reported for comparison with published work.

A segment the engine fails on gets an empty hypothesis and therefore scores
zero. Failures are counted in the run file, never silently skipped.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

from .engine_api import (
    Direction,
    EngineDeclinedError,
    EngineError,
    TranslationEngine,
    TranslationRequest,
    load_engine,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RUNS_DIR = REPO_ROOT / "datasets" / "evaluations" / "mt_runs"
BATCH_SIZE = 16


def read_test_set(path: Path) -> list[tuple[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE)
        if not reader.fieldnames or not {"source_text", "target_text"} <= set(reader.fieldnames):
            raise ValueError(f"{path} needs a header row with 'source_text' and 'target_text'")
        pairs = [
            ((row.get("source_text") or "").strip(), (row.get("target_text") or "").strip())
            for row in reader
        ]
    pairs = [(source, target) for source, target in pairs if source and target]
    if not pairs:
        raise ValueError(f"{path} has no usable rows")
    return pairs


def score(
    hypotheses: Sequence[str], references: Sequence[str]
) -> tuple[dict[str, float], dict[str, str], list[float]]:
    """Corpus chrF++ and BLEU, their sacrebleu signatures, and per-segment chrF++."""
    try:
        from sacrebleu.metrics import BLEU, CHRF
    except ImportError as exc:
        raise SystemExit("sacrebleu is required: pip install sacrebleu") from exc

    chrf = CHRF(word_order=2)  # word_order=2 makes chrF into chrF++
    bleu = BLEU()
    metrics = {
        "chrf++": round(chrf.corpus_score(list(hypotheses), [list(references)]).score, 2),
        "bleu": round(bleu.corpus_score(list(hypotheses), [list(references)]).score, 2),
    }
    signatures = {"chrf++": str(chrf.get_signature()), "bleu": str(bleu.get_signature())}
    per_segment = [
        round(chrf.sentence_score(hypothesis, [reference]).score, 2)
        for hypothesis, reference in zip(hypotheses, references)
    ]
    return metrics, signatures, per_segment


def _translate_chain(
    engines: Sequence[TranslationEngine], requests: Sequence[TranslationRequest]
) -> tuple[list[str], list[str], int]:
    """Translate with the first engine that answers each segment, like the dashboard's auto mode.

    Returns hypotheses, the id of the engine that answered each one, and the number
    of segments nobody answered (scored as empty, never skipped).
    """
    hypotheses = [""] * len(requests)
    answered_by = [""] * len(requests)
    pending = list(range(len(requests)))
    for engine in engines:
        if not pending:
            break
        answers: dict[int, str] = {}
        for start in range(0, len(pending), BATCH_SIZE):
            chunk = pending[start : start + BATCH_SIZE]
            try:
                for index, result in zip(chunk, engine.translate_batch([requests[i] for i in chunk])):
                    answers[index] = result.translation
            except EngineError:
                for index in chunk:  # one by one, so a declined or bad segment costs only itself
                    try:
                        answers[index] = engine.translate(requests[index]).translation
                    except EngineDeclinedError:
                        continue
                    except EngineError as exc:
                        print(f"  {engine.info.id} failed on a segment: {exc}", file=sys.stderr)
            print(f"  {engine.info.id}: answered {len(answers)} of {len(pending)} so far", file=sys.stderr)
        for index, text in answers.items():
            hypotheses[index] = text
            answered_by[index] = engine.info.id
        pending = [i for i in pending if i not in answers]
    return hypotheses, answered_by, len(pending)


def _load_ready(specs: Sequence[str], direction: Direction) -> list[TranslationEngine]:
    engines = []
    for spec in specs:
        engine = load_engine(spec)
        engine.load()
        status = engine.status()
        if not status.is_ready:
            print(f"Skipping {engine.info.id}: {status.message or status.state.value}", file=sys.stderr)
            continue
        if not engine.supports(direction):
            print(f"Skipping {engine.info.id}: does not support {direction.code}", file=sys.stderr)
            continue
        engines.append(engine)
    if not engines:
        raise SystemExit("No engine in the chain is ready for this direction.")
    return engines


def _slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-") or "run"


def evaluate(
    engine_spec: str | Sequence[str],
    test_set: Path,
    direction: Direction,
    test_set_name: str | None = None,
    limit: int | None = None,
    notes: str | None = None,
    runs_dir: Path = DEFAULT_RUNS_DIR,
) -> Path:
    """Run the evaluation and return the path of the written run file.

    Several specs form a fallback chain: each segment is answered by the first
    engine that does not decline or fail.
    """
    specs = [engine_spec] if isinstance(engine_spec, str) else list(engine_spec)
    engines = _load_ready(specs, direction)
    chain_id = "+".join(engine.info.id for engine in engines)

    pairs = read_test_set(test_set)
    if limit is not None:
        pairs = pairs[:limit]
    sources = [source for source, _ in pairs]
    references = [reference for _, reference in pairs]
    requests = [TranslationRequest(text=source, direction=direction) for source in sources]

    print(f"Evaluating {chain_id} on {len(pairs)} segments ({direction.code})", file=sys.stderr)
    hypotheses, answered_by, failures = _translate_chain(engines, requests)
    metrics, signatures, per_segment = score(hypotheses, references)

    now = datetime.now(timezone.utc)
    name = test_set_name or test_set.stem
    run_id = _slug(f"{now:%Y%m%dT%H%M%SZ}_{chain_id}_{name}_{direction.code}")
    run = {
        "run_id": run_id,
        "engine_id": chain_id,
        "engine_name": " then ".join(engine.info.name for engine in engines),
        "engine_version": "+".join(engine.info.version for engine in engines),
        "engines": [engine.info.id for engine in engines],
        "answered_by": dict(Counter(answer for answer in answered_by if answer)),
        "test_set": name,
        "test_set_file": test_set.as_posix(),
        "direction": direction.code,
        "n_segments": len(pairs),
        "failed_segments": failures,
        "metrics": metrics,
        "signatures": signatures,
        "created_at": now.isoformat(timespec="seconds"),
        "notes": notes,
        "segments": [
            {"source": s, "reference": r, "hypothesis": h, "chrf++": c, "engine": e}
            for s, r, h, c, e in zip(sources, references, hypotheses, per_segment, answered_by)
        ],
    }
    runs_dir.mkdir(parents=True, exist_ok=True)
    path = runs_dir / f"{run_id}.json"
    path.write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--engine",
        required=True,
        action="append",
        help="Engine spec, 'package.module:ClassName'. Repeat to evaluate a fallback chain in that order.",
    )
    parser.add_argument("--test-set", required=True, type=Path, help="TSV with source_text and target_text")
    parser.add_argument("--direction", required=True, help="en-nag or nag-en")
    parser.add_argument("--test-set-name", help="Name shown in the dashboard (default: file name)")
    parser.add_argument("--limit", type=int, help="Evaluate only the first N segments")
    parser.add_argument("--notes", help="Free text stored with the run, e.g. the configuration")
    parser.add_argument("--runs-dir", type=Path, default=DEFAULT_RUNS_DIR)
    args = parser.parse_args(argv)
    # Output redirected to a file on Windows defaults to cp1252, which cannot print “quotes”.
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")

    path = evaluate(
        engine_spec=args.engine,
        test_set=args.test_set,
        direction=Direction.parse(args.direction),
        test_set_name=args.test_set_name,
        limit=args.limit,
        notes=args.notes,
        runs_dir=args.runs_dir,
    )
    run = json.loads(path.read_text(encoding="utf-8"))
    print(
        f"chrF++ {run['metrics']['chrf++']:.2f}  BLEU {run['metrics']['bleu']:.2f}  "
        f"({run['n_segments']} segments, {run['failed_segments']} failed)"
    )
    print(f"Run saved to {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
