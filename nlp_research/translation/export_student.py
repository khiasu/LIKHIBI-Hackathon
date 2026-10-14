"""
Export the distilled student for the keyboard: ONNX, int8, plus a tokenizer file for Kotlin.

Writes to models/student/onnx/ and copies into app/src/main/assets/translate/:

    encoder.onnx      source token ids -> encoder states
    decoder.onnx      target prefix + encoder states -> scores for the next token only
    tokenizer.json    SentencePiece pieces and scores, so Kotlin can tokenise without a native library
    parity.json       200 sentences with their Python token ids, for the Kotlin tokenizer test

Greedy decoding (the same in nlp_research/translation/engines/student.py and the
keyboard's StudentTranslator.kt):

    source  = [tag for the target language] + pieces(clean(text))[:126] + [</s>]
    target  = [<pad>]; repeat: append argmax(decoder(target, encoder(source)))
              until </s> or 2 x len(source) + 10 tokens

Both weights are dynamically quantised to int8, which shrinks the model about four
times; the script checks the quantised model against the original on the dev set.

    python -m nlp_research.translation.export_student
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from pathlib import Path

from .bank import REPO_ROOT
from .distill import DEV_FILES, EOS, PAD, STUDENT_DIR, TAGS, UNK, encode_source
from .text import clean

ONNX_DIR = STUDENT_DIR / "onnx"
APP_ASSETS = REPO_ROOT / "app" / "src" / "main" / "assets" / "translate"
APP_TEST_FIXTURES = REPO_ROOT / "app" / "src" / "test" / "resources" / "translate"


def max_new_tokens(source_length: int) -> int:
    return min(256, 2 * source_length + 10)


def repeated_trigram_completions(generated: list[int]) -> set[int]:
    """Tokens that would repeat a three-token sequence already in the output.

    Greedy decoding by a small model can loop ("prathana kori kene prathana kori kene...");
    banning repeated trigrams stops that, like no_repeat_ngram_size=3 in Hugging Face.
    StudentTranslator.kt applies the same rule.
    """
    if len(generated) < 2:
        return set()
    last_two = generated[-2:]
    return {generated[i + 2] for i in range(len(generated) - 2) if generated[i : i + 2] == last_two}


def export(student_dir: Path = STUDENT_DIR, onnx_dir: Path = ONNX_DIR, opset: int = 17) -> None:
    import torch
    from transformers import MarianMTModel

    model = MarianMTModel.from_pretrained(student_dir / "hf").eval()

    class Encoder(torch.nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.encoder = model.get_encoder()

        def forward(self, input_ids, attention_mask):
            return self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state

    class Decoder(torch.nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.decoder = model.get_decoder()
            self.lm_head = model.lm_head
            self.register_buffer("bias", model.final_logits_bias)

        def forward(self, decoder_input_ids, encoder_hidden_states, encoder_attention_mask):
            hidden = self.decoder(
                input_ids=decoder_input_ids,
                encoder_hidden_states=encoder_hidden_states,
                encoder_attention_mask=encoder_attention_mask,
                use_cache=False,
            ).last_hidden_state
            return self.lm_head(hidden[:, -1, :]) + self.bias  # scores for the next token only

    onnx_dir.mkdir(parents=True, exist_ok=True)
    source = torch.tensor([[3, 10, 11, 12, EOS]])
    mask = torch.ones_like(source)
    with torch.no_grad():
        states = Encoder()(source, mask)
        torch.onnx.export(
            Encoder(), (source, mask), str(onnx_dir / "encoder.fp32.onnx"), opset_version=opset, dynamo=False,
            input_names=["input_ids", "attention_mask"], output_names=["encoder_hidden_states"],
            dynamic_axes={"input_ids": {0: "batch", 1: "source"}, "attention_mask": {0: "batch", 1: "source"},
                          "encoder_hidden_states": {0: "batch", 1: "source"}},
        )
        torch.onnx.export(
            Decoder(), (torch.tensor([[PAD, 20]]), states, mask), str(onnx_dir / "decoder.fp32.onnx"),
            opset_version=opset, dynamo=False,
            input_names=["decoder_input_ids", "encoder_hidden_states", "encoder_attention_mask"], output_names=["logits"],
            dynamic_axes={"decoder_input_ids": {0: "batch", 1: "target"}, "encoder_hidden_states": {0: "batch", 1: "source"},
                          "encoder_attention_mask": {0: "batch", 1: "source"}, "logits": {0: "batch"}},
        )


def quantize(onnx_dir: Path = ONNX_DIR) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    for part in ("encoder", "decoder"):
        quantize_dynamic(str(onnx_dir / f"{part}.fp32.onnx"), str(onnx_dir / f"{part}.onnx"), weight_type=QuantType.QInt8)


def tokenizer_json(sp) -> dict:
    pieces = []
    for index in range(sp.get_piece_size()):
        piece = sp.id_to_piece(index)
        kind = "normal"
        if sp.is_unknown(index):
            kind = "unknown"
        elif sp.is_control(index):
            kind = "control"
        elif piece in TAGS.values():
            kind = "user"
        pieces.append([piece, round(sp.get_score(index), 6), kind])
    return {
        "model": "sentencepiece-unigram",
        "normalisation": "NFC, curly quotes and dashes to ASCII, single spaces (nlp_research/translation/text.py clean)",
        "word_marker": "▁",
        "add_dummy_prefix": True,
        "special_ids": {"pad": PAD, "unk": UNK, "eos": EOS, **{lang: sp.piece_to_id(tag) for lang, tag in TAGS.items()}},
        "pieces": pieces,
    }


class OnnxStudent:
    """Greedy decoding with ONNX Runtime, exactly as the keyboard does it."""

    def __init__(self, folder: Path = ONNX_DIR, threads: int = 2) -> None:
        import onnxruntime as ort
        import sentencepiece as spm

        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        self.encoder = ort.InferenceSession(str(folder / "encoder.onnx"), options, providers=["CPUExecutionProvider"])
        self.decoder = ort.InferenceSession(str(folder / "decoder.onnx"), options, providers=["CPUExecutionProvider"])
        self.sp = spm.SentencePieceProcessor(model_file=str(folder / "spm.model"))

    def translate(self, text: str, target_lang: str) -> str:
        import numpy as np

        source = np.array([encode_source(self.sp, text, target_lang)], dtype=np.int64)
        mask = np.ones_like(source)
        states = self.encoder.run(None, {"input_ids": source, "attention_mask": mask})[0]
        target = [PAD]
        for _ in range(max_new_tokens(source.shape[1])):
            logits = self.decoder.run(
                None,
                {"decoder_input_ids": np.array([target], dtype=np.int64), "encoder_hidden_states": states, "encoder_attention_mask": mask},
            )[0][0]
            for banned in repeated_trigram_completions(target[1:]):
                logits[banned] = -np.inf
            token = int(logits.argmax())
            if token == EOS:
                break
            target.append(token)
        return self.sp.decode(target[1:])


def check(onnx_dir: Path = ONNX_DIR, limit: int | None = None) -> dict:
    """chrF++ of the int8 ONNX model on the dev set, with timing."""
    from .train_nmt import chrf, read_pairs

    student = OnnxStudent(onnx_dir)
    report = {}
    for direction, path in DEV_FILES.items():
        pairs = read_pairs(path)[:limit] if limit else read_pairs(path)
        target_lang = direction.split("-")[1]
        started = time.time()
        hypotheses = [student.translate(source, target_lang) for source, _ in pairs]
        elapsed = (time.time() - started) / len(pairs)
        report[direction] = {"dev_chrf++": chrf(hypotheses, [t for _, t in pairs]), "seconds_per_sentence_cpu": round(elapsed, 3),
                             "example": [pairs[0][0], hypotheses[0]]}
    return report


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Export the student to ONNX int8 for the keyboard.")
    parser.add_argument("--no-app", action="store_true", help="do not copy into the Android assets")
    parser.add_argument("--student-dir", type=Path, default=STUDENT_DIR, help="folder with hf/ and spm.model")
    args = parser.parse_args(argv)
    import sentencepiece as spm

    student_dir = args.student_dir
    onnx_dir = student_dir / "onnx"
    export(student_dir, onnx_dir)
    quantize(onnx_dir)
    shutil.copyfile(student_dir / "spm.model", onnx_dir / "spm.model")
    sp = spm.SentencePieceProcessor(model_file=str(student_dir / "spm.model"))
    (onnx_dir / "tokenizer.json").write_text(json.dumps(tokenizer_json(sp), ensure_ascii=False), encoding="utf-8")

    from .train_nmt import read_pairs

    samples = [s for path in DEV_FILES.values() for s, _ in read_pairs(path)][:200]
    student = OnnxStudent(onnx_dir)
    translations = []
    for direction, path in DEV_FILES.items():
        target = direction.split("-")[1]
        for source, _ in read_pairs(path)[:10]:
            translations.append({"text": source, "target": target, "output": student.translate(source, target)})
    parity = {
        "tokenizer": [{"text": clean(s), "ids": sp.encode(clean(s))} for s in samples],
        "translations": translations,
    }
    (onnx_dir / "parity.json").write_text(json.dumps(parity, ensure_ascii=False), encoding="utf-8")

    sizes = {p.name: round(p.stat().st_size / 1e6, 2) for p in onnx_dir.iterdir() if p.suffix in (".onnx", ".json", ".model")}
    report = {"sizes_mb": sizes, "check": check(onnx_dir)}
    meta_path = student_dir / "student_meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.is_file() else {}
    meta["onnx_int8"] = report
    meta_path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    (onnx_dir / "student_meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")

    if not args.no_app:
        APP_ASSETS.mkdir(parents=True, exist_ok=True)
        for name in ("encoder.onnx", "decoder.onnx", "tokenizer.json", "student_meta.json"):
            shutil.copyfile(onnx_dir / name, APP_ASSETS / name)
        # The Kotlin tests compare the shipped model with these Python outputs.
        APP_TEST_FIXTURES.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(onnx_dir / "parity.json", APP_TEST_FIXTURES / "parity.json")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
