"""
Fine-tuned NMT engine: NLLB-200 (distilled 600M) with the Likhibi LoRA adapter.

Runs fully offline: on a CUDA GPU in bfloat16 when available, otherwise on the CPU.
It needs the ML requirements (nlp_research/requirements-ml.txt) and an adapter
trained with `python -m nlp_research.translation.train_nmt`. Without them the
engine reports "not configured" and auto mode skips it.

Configuration:
    LIKHIBI_NMT_ADAPTER      adapter directory (default <repo>/models/nmt-nllb-lora)
    LIKHIBI_NMT_BASE_MODEL   base model path or Hugging Face id
                             (default: ~/.cache/likhibi/models/nllb-200-distilled-600M if present,
                             else facebook/nllb-200-distilled-600M)
    LIKHIBI_NMT_DEVICE       "cuda" or "cpu" (default: cuda when available)
    LIKHIBI_NMT_BEAMS        beam size (default 4)
"""

from __future__ import annotations

import importlib.util
import json
import math
import os
import threading
from pathlib import Path
from typing import Sequence

from ..bank import REPO_ROOT
from ..engine_api import (
    EN_TO_NAG,
    NAG_TO_EN,
    Alternative,
    EngineError,
    EngineInfo,
    EngineKind,
    EngineState,
    EngineStatus,
    EngineUnavailableError,
    TranslationEngine,
    TranslationRequest,
    TranslationResult,
)

DEFAULT_ADAPTER = REPO_ROOT / "models" / "nmt-nllb-lora"
LOCAL_BASE = Path.home() / ".cache" / "likhibi" / "models" / "nllb-200-distilled-600M"
HUB_BASE = "facebook/nllb-200-distilled-600M"
MAX_SOURCE_TOKENS = 256
MAX_BATCH = 16
# Beam hypotheses x tokens allowed in one generate call, so long texts go fewer at a time on a 4 GB GPU.
HYPOTHESIS_TOKEN_BUDGET = 12_000


def _max_new_tokens(source_tokens: int) -> int:
    return min(400, source_tokens * 2 + 16)


def _missing_packages() -> list[str]:
    return [name for name in ("torch", "transformers", "peft") if importlib.util.find_spec(name) is None]


class NmtEngine(TranslationEngine):
    info = EngineInfo(
        id="nmt",
        name="Fine-tuned NLLB",
        kind=EngineKind.NMT,
        version="1.0.0",
        directions=(EN_TO_NAG, NAG_TO_EN),
        description=(
            "NLLB-200 600M fine-tuned with LoRA on the aligned English–Nagamese corpus. "
            "Runs offline on the GPU or CPU. Trained on scripture, so everyday register is weak."
        ),
        offline=True,
    )

    def __init__(self, adapter_dir: Path | None = None) -> None:
        self.adapter_dir = Path(adapter_dir or os.environ.get("LIKHIBI_NMT_ADAPTER") or DEFAULT_ADAPTER)
        self.base_model = os.environ.get("LIKHIBI_NMT_BASE_MODEL") or (str(LOCAL_BASE) if LOCAL_BASE.is_dir() else HUB_BASE)
        self.beams = int(os.environ.get("LIKHIBI_NMT_BEAMS") or 4)
        self._requested_device = os.environ.get("LIKHIBI_NMT_DEVICE")
        self._model = None
        self._tokenizer = None
        self._device = None
        self._codes: dict[str, str] = {}
        self._problem: str | None = None
        self._lock = threading.Lock()

    def load(self) -> None:
        missing = _missing_packages()
        if missing:
            self._problem = (
                f"Missing Python packages: {', '.join(missing)}. Run the backend in the ML environment "
                "(nlp_research/requirements-ml.txt)."
            )
            return
        meta_path = self.adapter_dir / "likhibi_nmt.json"
        if not (self.adapter_dir / "adapter_config.json").is_file() or not meta_path.is_file():
            self._problem = f"No trained adapter in {self.adapter_dir}. Train one with python -m nlp_research.translation.train_nmt."
            return

        import torch
        from peft import PeftModel
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        self._codes = dict(meta["lang_codes"])
        use_cuda = torch.cuda.is_available() and self._requested_device != "cpu"
        self._device = torch.device("cuda" if use_cuda else "cpu")
        dtype = torch.bfloat16 if use_cuda else torch.float32
        tokenizer = AutoTokenizer.from_pretrained(self.adapter_dir)
        # Straight onto the GPU when there is one, so system RAM never holds a second copy.
        base = AutoModelForSeq2SeqLM.from_pretrained(
            self.base_model, dtype=dtype, device_map={"": 0} if use_cuda else None
        )
        model = PeftModel.from_pretrained(base, self.adapter_dir)
        model = model.merge_and_unload()  # plain model: faster generation
        # _generate always passes max_new_tokens; the checkpoint's max_length of 200 would only add a warning per call.
        model.generation_config.max_length = None
        model.to(self._device)
        model.eval()
        self._tokenizer, self._model = tokenizer, model
        self._problem = None

    def status(self) -> EngineStatus:
        if self._model is None:
            return EngineStatus(EngineState.NOT_CONFIGURED, self._problem or "Model not loaded yet.")
        device = "GPU" if self._device is not None and self._device.type == "cuda" else "CPU"
        return EngineStatus(EngineState.READY, f"LoRA adapter on {device}")

    def translate(self, request: TranslationRequest) -> TranslationResult:
        return self.translate_batch([request])[0]

    def translate_batch(self, requests: Sequence[TranslationRequest]) -> list[TranslationResult]:
        if self._model is None:
            raise EngineUnavailableError(self._problem or "The NMT model is not loaded.")
        import torch

        # Group by direction and alternatives so each generate call shares settings.
        groups: dict[tuple, list[int]] = {}
        for index, request in enumerate(requests):
            groups.setdefault((request.direction, request.num_alternatives), []).append(index)
        ordered: dict[int, TranslationResult] = {}
        with self._lock:
            try:
                for (_, alternatives), indices in groups.items():
                    beams = max(self.beams, 1 + alternatives)
                    for chunk in self._chunks(indices, requests, beams):
                        for index, result in zip(chunk, self._generate([requests[i] for i in chunk], beams)):
                            ordered[index] = result
            except RuntimeError as exc:
                # CUDA reports a failed allocation as OutOfMemoryError or, inside some kernels, as AcceleratorError.
                if "out of memory" not in str(exc).lower():
                    raise
                if self._device.type == "cuda":
                    torch.cuda.empty_cache()
                raise EngineError("The GPU ran out of memory. Try a shorter text.") from exc
        return [ordered[i] for i in range(len(requests))]

    def _chunks(self, indices: list[int], requests: Sequence[TranslationRequest], beams: int) -> list[list[int]]:
        """Split a group into generate calls that fit in memory, with similar lengths together to cut padding."""
        lengths = {
            i: len(self._tokenizer(requests[i].text, truncation=True, max_length=MAX_SOURCE_TOKENS)["input_ids"])
            for i in indices
        }
        chunks: list[list[int]] = []
        current: list[int] = []
        for i in sorted(indices, key=lengths.__getitem__):
            # Sorted by length, so text i is the longest in the chunk and sets its padded size.
            cost = (len(current) + 1) * beams * (lengths[i] + _max_new_tokens(lengths[i]))
            if current and (len(current) == MAX_BATCH or cost > HYPOTHESIS_TOKEN_BUDGET):
                chunks.append(current)
                current = []
            current.append(i)
        if current:
            chunks.append(current)
        return chunks

    def _generate(self, requests: Sequence[TranslationRequest], beams: int) -> list[TranslationResult]:
        """One generate call for requests that share direction and alternatives. The caller holds the lock."""
        import torch

        direction = requests[0].direction
        returns = 1 + requests[0].num_alternatives
        tokenizer, model = self._tokenizer, self._model
        tokenizer.src_lang = self._codes[direction.source]
        inputs = tokenizer(
            [r.text for r in requests], return_tensors="pt", padding=True, truncation=True, max_length=MAX_SOURCE_TOKENS
        ).to(self._device)
        with torch.no_grad(), torch.autocast(self._device.type, dtype=torch.bfloat16, enabled=self._device.type == "cuda"):
            sequences = model.generate(
                **inputs,
                forced_bos_token_id=tokenizer.convert_tokens_to_ids(self._codes[direction.target]),
                num_beams=beams,
                num_return_sequences=returns,
                max_new_tokens=_max_new_tokens(inputs["input_ids"].shape[1]),
            )
            confidences = self._confidences(inputs, sequences[::returns])
        texts = [t.strip() for t in tokenizer.batch_decode(sequences, skip_special_tokens=True)]
        results = []
        for i, confidence in enumerate(confidences):
            block = texts[i * returns : (i + 1) * returns]
            results.append(
                TranslationResult(
                    translation=block[0],
                    alternatives=tuple(Alternative(t) for t in block[1:] if t and t != block[0]),
                    confidence=confidence,
                    metadata={"beams": beams},
                )
            )
        return results

    def _confidences(self, inputs, best) -> list[float | None]:
        """Mean probability the model gives each token of its best output, after the language code.

        Beam search only returns its scores together with a full-vocabulary tensor for every
        step, which does not fit on a small GPU, so each best output is rescored in one pass.
        """
        import torch
        from transformers.modeling_outputs import BaseModelOutput

        pad = self._tokenizer.pad_token_id
        encoded = self._model.get_encoder()(input_ids=inputs["input_ids"], attention_mask=inputs["attention_mask"])
        values: list[float | None] = []
        for row, sequence in enumerate(best):
            sequence = sequence[sequence != pad]  # padding only trails a finished output
            if sequence.numel() < 3:  # start token, language code, at least one word piece
                values.append(None)
                continue
            # Position t predicts token t + 1; position 0 predicts the forced language code, so skip it.
            logits = self._model(
                encoder_outputs=BaseModelOutput(last_hidden_state=encoded.last_hidden_state[row : row + 1]),
                attention_mask=inputs["attention_mask"][row : row + 1],
                decoder_input_ids=sequence[None, :-1],
            ).logits[0, 1:]
            chosen = logits.gather(1, sequence[2:, None]).squeeze(1).float()
            normalizer = torch.cat([torch.logsumexp(part.float(), dim=-1) for part in logits.split(64)])
            values.append(round(math.exp(float((chosen - normalizer).mean())), 3))
        return values
