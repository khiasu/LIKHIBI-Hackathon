"""Teaching the translation model from approved community contributions.

Approved corrections reach the retrieval engines at once (see feedback.review_feedback).
This service also folds them into the NMT model itself: once enough new ones are waiting,
it runs nlp_research.translation.learn_from_community in a separate process. That script
continues training the LoRA adapter on all data plus the corrections, scores the result on
held-out text, and only replaces the model if it did not get worse. The NMT engine is
unloaded while training runs (the GPU is shared) and reloaded afterwards.
"""

from __future__ import annotations

import importlib.util
import logging
import subprocess
import sys
import threading
from pathlib import Path

from .. import REPO_ROOT
from ..engines.registry import EngineRegistry
from ..storage import Storage

logger = logging.getLogger(__name__)

TRAINING_PACKAGES = ("torch", "transformers", "peft", "sacrebleu")


class LearningService:
    def __init__(self, storage: Storage, registry: EngineRegistry, db_path: Path, threshold: int, log_dir: Path) -> None:
        self.storage = storage
        self.registry = registry
        self.db_path = db_path
        self.threshold = threshold
        self.log_dir = log_dir
        self._lock = threading.Lock()
        # A run that was going when the dashboard stopped will never finish.
        for run in storage.learning_runs(50):
            if run["state"] == "running":
                storage.finish_learning_run(run["id"], "interrupted", None, None, None, "The dashboard stopped while it ran.")

    def problem(self) -> str | None:
        missing = [name for name in TRAINING_PACKAGES if importlib.util.find_spec(name) is None]
        if missing:
            return "Install the training tools to let the model learn: pip install -r nlp_research/requirements-ml.txt (missing: " + ", ".join(missing) + ")."
        return None

    def status(self) -> dict:
        last = self.storage.last_learned_feedback()
        return {
            "available": self.problem() is None,
            "problem": self.problem(),
            "approved_pairs": len(self.storage.approved_corrections()),
            "new_since_last": self.storage.approved_since(last),
            "threshold": self.threshold,
            "running": self.storage.running_learning_run() is not None,
            "runs": self.storage.learning_runs(),
        }

    def maybe_start(self) -> bool:
        """Starts learning if enough approved corrections are waiting and nothing is running."""
        if self.threshold <= 0 or self.problem() is not None:
            return False
        if self.storage.approved_since(self.storage.last_learned_feedback()) < self.threshold:
            return False
        return self.start("auto") is not None

    def start(self, trigger: str) -> int | None:
        with self._lock:
            if self.storage.running_learning_run() is not None:
                return None
            corrections = self.storage.approved_corrections()
            last = max((row["id"] for row in corrections), default=0)
            run_id = self.storage.start_learning_run(trigger, len(corrections), last)
        threading.Thread(target=self._run, args=(run_id,), name=f"learning-{run_id}", daemon=True).start()
        return run_id

    def _nmt_engines(self) -> list:
        return [entry.engine for entry in self.registry.entries() if hasattr(entry.engine, "unload")]

    def _run(self, run_id: int) -> None:
        for engine in self._nmt_engines():
            try:
                engine.unload()
            except Exception:
                logger.exception("Could not unload the NMT engine before training")
        self.log_dir.mkdir(parents=True, exist_ok=True)
        log_path = self.log_dir / f"learning-{run_id}.log"
        try:
            with log_path.open("w", encoding="utf-8") as log:
                result = subprocess.run(
                    [sys.executable, "-m", "nlp_research.translation.learn_from_community", "--db", str(self.db_path), "--run-id", str(run_id)],
                    cwd=REPO_ROOT, stdout=log, stderr=subprocess.STDOUT, check=False,
                )
            if result.returncode != 0:
                tail = log_path.read_text(encoding="utf-8", errors="replace").strip().splitlines()[-3:]
                self._fail(run_id, "Training stopped with an error: " + " | ".join(tail)[:400])
        except Exception as exc:
            logger.exception("Learning run %s failed", run_id)
            self._fail(run_id, f"Training could not start: {exc}")
        finally:
            for engine in self._nmt_engines():
                try:
                    engine.load()
                except Exception:
                    logger.exception("Could not reload the NMT engine after training")

    def _fail(self, run_id: int, message: str) -> None:
        running = self.storage.running_learning_run()
        if running is not None and running["id"] == run_id:
            self.storage.finish_learning_run(run_id, "failed", None, None, None, message)
