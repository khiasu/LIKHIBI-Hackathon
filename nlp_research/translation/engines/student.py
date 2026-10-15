"""
Phone-sized translation engine: the distilled student model, run with ONNX Runtime on the CPU.

This is the same int8 model and the same greedy decoding that the Android keyboard runs
offline, so its scores on the Evaluation page are the keyboard's scores. Build it with
`python -m nlp_research.translation.distill` and `python -m nlp_research.translation.export_student`.

Configuration:
    LIKHIBI_STUDENT_DIR   folder with encoder.onnx, decoder.onnx and spm.model
                          (default <repo>/models/student/onnx)
"""

from __future__ import annotations

import importlib.util
import os
import time
from pathlib import Path

from ..bank import REPO_ROOT
from ..engine_api import (
    EN_TO_NAG,
    NAG_TO_EN,
    EngineInfo,
    EngineKind,
    EngineState,
    EngineStatus,
    EngineUnavailableError,
    TranslationEngine,
    TranslationRequest,
    TranslationResult,
)

DEFAULT_DIR = REPO_ROOT / "models" / "student" / "onnx"
REQUIRED_FILES = ("encoder.onnx", "decoder.onnx", "spm.model")


class StudentEngine(TranslationEngine):
    info = EngineInfo(
        id="student",
        name="Phone model",
        kind=EngineKind.NMT,
        version="1.0.0",
        directions=(EN_TO_NAG, NAG_TO_EN),
        description=(
            "A 40M-parameter model distilled from the fine-tuned NLLB, small enough to run offline "
            "inside the Likhibi keyboard. Less accurate than NLLB; shown here so the two can be compared."
        ),
        offline=True,
    )

    def __init__(self, folder: Path | None = None) -> None:
        self.folder = Path(folder or os.environ.get("LIKHIBI_STUDENT_DIR") or DEFAULT_DIR)
        self._student = None
        self._problem: str | None = None

    def load(self) -> None:
        missing = [name for name in ("onnxruntime", "sentencepiece", "numpy") if importlib.util.find_spec(name) is None]
        if missing:
            self._problem = f"Missing Python packages: {', '.join(missing)}."
            return
        absent = [name for name in REQUIRED_FILES if not (self.folder / name).is_file()]
        if absent:
            self._problem = f"No exported student in {self.folder}. Run python -m nlp_research.translation.export_student."
            return
        from ..export_student import OnnxStudent

        self._student = OnnxStudent(self.folder)
        self._problem = None

    def status(self) -> EngineStatus:
        if self._student is None:
            return EngineStatus(EngineState.NOT_CONFIGURED, self._problem or "Model not loaded yet.")
        size = sum((self.folder / name).stat().st_size for name in ("encoder.onnx", "decoder.onnx")) / 1e6
        return EngineStatus(EngineState.READY, f"int8 ONNX on the CPU, {size:.0f} MB")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        if self._student is None:
            raise EngineUnavailableError(self._problem or "The phone model is not loaded.")
        started = time.perf_counter()
        text = self._student.translate(request.text, request.direction.target)
        return TranslationResult(
            translation=text.strip(),
            metadata={"decoding": "greedy", "cpu_seconds": round(time.perf_counter() - started, 3)},
        )
