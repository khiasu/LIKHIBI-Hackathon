"""
Teach the NMT model the translations the community contributed and reviewers approved.

    python -m nlp_research.translation.learn_from_community --db dashboard/backend/var/likhibi.db

1. Approved English <-> Nagamese corrections are read from the dashboard database and
   written to datasets/processed/parallel_corpus/community_approved.tsv, where every
   trainer and the translation memory read parallel data. Each one is repeated a few
   times: they are few, recent and checked by a person, so they should count for more.
   (Pairs in other languages and dialects are kept for when those models exist.)
2. A copy of the current adapter keeps training for one epoch on everything, at a lower
   learning rate, so the model learns the new pairs without forgetting the old ones.
   With no adapter yet, a full training run is made instead.
3. Both are scored on the held-out dev books. The new adapter replaces the old one only if
   its score did not drop by more than --tolerance chrF++ points; the old one is kept as
   models/nmt-nllb-lora.previous so a bad update can be undone by hand.

With --run-id, the outcome is written to that row of the dashboard's learning_runs table.
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sqlite3
import subprocess
import sys
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

from .bank import DEFAULT_DATASETS_DIR, REPO_ROOT

ADAPTER = REPO_ROOT / "models" / "nmt-nllb-lora"
COMMUNITY_TSV = DEFAULT_DATASETS_DIR / "processed" / "parallel_corpus" / "community_approved.tsv"
REPEATS = 3


def approved_pairs(db: Path) -> list[tuple[int, str, str, str]]:
    """(feedback id, english, nagamese, contributor) for every approved en<->nag correction."""
    with closing(sqlite3.connect(db)) as conn:
        rows = conn.execute(
            "SELECT id, source_lang, target_lang, source_text, corrected_translation, contributor FROM feedback"
            " WHERE status = 'approved' AND corrected_translation IS NOT NULL AND TRIM(corrected_translation) != ''"
            " ORDER BY id"
        ).fetchall()
    pairs = []
    for fid, src, tgt, source, corrected, contributor in rows:
        if {src, tgt} != {"en", "nag"}:
            continue
        english, nagamese = (source, corrected) if src == "en" else (corrected, source)
        pairs.append((fid, " ".join(english.split()), " ".join(nagamese.split()), contributor or ""))
    return pairs


def write_tsv(pairs: list[tuple[int, str, str, str]]) -> None:
    COMMUNITY_TSV.parent.mkdir(parents=True, exist_ok=True)
    with COMMUNITY_TSV.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(["id", "english_text", "nagamese_text", "domain", "split", "contributor", "license"])
        for fid, english, nagamese, contributor in pairs:
            for copy in range(REPEATS):
                writer.writerow([f"community-{fid}-{copy}", english, nagamese, "community", "train", contributor, "CC-BY-SA-4.0"])


def mean_score(meta_path: Path, key: str) -> float | None:
    log = json.loads(meta_path.read_text(encoding="utf-8")).get("log", {})
    if key == "baseline":
        scores = log.get("baseline_dev_chrf++")
    else:
        epochs = log.get("epochs") or []
        scores = max((e["dev_chrf++"] for e in epochs), key=lambda s: sum(s.values()) / len(s), default=None)
    if not scores:
        return None
    return round(sum(scores.values()) / len(scores), 2)


def record(db: Path, run_id: int | None, state: str, accepted: bool | None, before: float | None, after: float | None, message: str) -> None:
    print(message, flush=True)
    if run_id is None:
        return
    with closing(sqlite3.connect(db)) as conn, conn:
        conn.execute(
            "UPDATE learning_runs SET finished_at = ?, state = ?, accepted = ?, score_before = ?, score_after = ?, message = ? WHERE id = ?",
            (datetime.now(timezone.utc).isoformat(timespec="seconds"), state, None if accepted is None else int(accepted), before, after, message, run_id),
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", type=Path, required=True, help="The dashboard's SQLite database")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--lr", type=float, default=1e-4)
    parser.add_argument("--tolerance", type=float, default=0.3, help="Largest dev chrF++ drop still accepted")
    args = parser.parse_args()
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")

    pairs = approved_pairs(args.db)
    write_tsv(pairs)
    print(f"{len(pairs)} approved English-Nagamese pairs written to {COMMUNITY_TSV}", flush=True)

    candidate = ADAPTER.with_name(ADAPTER.name + ".candidate")
    shutil.rmtree(candidate, ignore_errors=True)
    fresh = not (ADAPTER / "adapter_config.json").is_file()
    command = [sys.executable, "-m", "nlp_research.translation.train_nmt", "--output", str(candidate), "--min-battery", "0"]
    if fresh:
        command += ["--epochs", "3", "--skip-baseline"]
    else:
        shutil.copytree(ADAPTER, candidate)
        command += ["--resume", "--epochs", str(args.epochs), "--lr", str(args.lr), "--warmup-steps", "30"]
    print("Running: " + " ".join(command), flush=True)
    if subprocess.run(command, cwd=REPO_ROOT, check=False).returncode != 0:
        shutil.rmtree(candidate, ignore_errors=True)
        record(args.db, args.run_id, "failed", None, None, None, "Training failed; the current model is unchanged.")
        return 1

    meta = candidate / "likhibi_nmt.json"
    after = mean_score(meta, "epochs")
    before = None if fresh else mean_score(meta, "baseline")
    accepted = after is not None and (before is None or after >= before - args.tolerance)
    if accepted:
        previous = ADAPTER.with_name(ADAPTER.name + ".previous")
        shutil.rmtree(previous, ignore_errors=True)
        if ADAPTER.exists():
            ADAPTER.rename(previous)
        candidate.rename(ADAPTER)
        message = f"Learned {len(pairs)} community pairs. Dev chrF++ {before if before is not None else 'n/a'} -> {after}. The new model is in use."
    else:
        shutil.rmtree(candidate, ignore_errors=True)
        message = f"Not used: dev chrF++ would drop from {before} to {after}. The current model is unchanged."
    record(args.db, args.run_id, "done", accepted, before, after, message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
