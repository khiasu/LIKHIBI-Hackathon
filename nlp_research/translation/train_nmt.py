"""
Fine-tune NLLB-200 (distilled 600M) for English <-> Nagamese with LoRA.

Designed to fit a 4 GB laptop GPU: the base model is frozen in bfloat16 and only
low-rank adapters (LoRA) on the attention and feed-forward layers are trained.
One model learns both directions.

NLLB has no Nagamese language code, so Nagamese reuses Tok Pisin's code (tpi_Latn):
another creole written in Latin script, so the decoder starts in the right script.
The mapping is saved with the adapter and the NMT engine reads it from there.

Training data: the training split of every parallel corpus in datasets/ (dev and
test sentences are excluded by nlp_research.translation.bank). The dev split
(Colossians, 2 Thessalonians) picks the best epoch.

Usage, from the repository root, with the ML requirements installed
(nlp_research/requirements-ml.txt):

    python -m nlp_research.translation.train_nmt --epochs 3

On an RTX 2050 (4 GB) this takes about 45 minutes. --resume continues an interrupted run.

Output: models/nmt-nllb-lora/ (adapter weights, language codes, training log).
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from .bank import DEFAULT_DATASETS_DIR, REPO_ROOT, load_examples

DEFAULT_BASE = "facebook/nllb-200-distilled-600M"
LOCAL_BASE = Path.home() / ".cache" / "likhibi" / "models" / "nllb-200-distilled-600M"
DEFAULT_OUTPUT = REPO_ROOT / "models" / "nmt-nllb-lora"
LANG_CODES = {"en": "eng_Latn", "nag": "tpi_Latn"}


def power_status() -> tuple[bool, int | None]:
    """(on mains power, battery percent). Off Windows, assume mains power."""
    if sys.platform != "win32":
        return True, None
    import ctypes

    class SystemPowerStatus(ctypes.Structure):
        _fields_ = [
            ("ACLineStatus", ctypes.c_ubyte),
            ("BatteryFlag", ctypes.c_ubyte),
            ("BatteryLifePercent", ctypes.c_ubyte),
            ("SystemStatusFlag", ctypes.c_ubyte),
            ("BatteryLifeTime", ctypes.c_ulong),
            ("BatteryFullLifeTime", ctypes.c_ulong),
        ]

    status = SystemPowerStatus()
    if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(status)):
        return True, None
    percent = None if status.BatteryLifePercent == 255 else int(status.BatteryLifePercent)
    return status.ACLineStatus != 0, percent


def keep_awake() -> None:
    """Ask Windows not to sleep while training runs. Released automatically when the process exits."""
    if sys.platform == "win32":
        import ctypes

        es_continuous, es_system_required = 0x80000000, 0x00000001
        ctypes.windll.kernel32.SetThreadExecutionState(es_continuous | es_system_required)


def read_pairs(path: Path) -> list[tuple[str, str]]:
    import csv

    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))
    return [(r["source_text"], r["target_text"]) for r in rows]


def encode(tokenizer, pairs, src: str, tgt: str, max_length: int) -> list[dict]:
    tokenizer.src_lang = LANG_CODES[src]
    tokenizer.tgt_lang = LANG_CODES[tgt]
    encoded = tokenizer(
        [s for s, _ in pairs], text_target=[t for _, t in pairs], max_length=max_length, truncation=True
    )
    return [
        {"input_ids": ids, "labels": labels}
        for ids, labels in zip(encoded["input_ids"], encoded["labels"])
    ]


def batches(examples: list[dict], max_sentences: int, max_tokens: int, rng: random.Random) -> list[list[dict]]:
    """Length-bucketed batches capped by a token budget.

    NLLB's output layer has 256k entries, so the loss on one target token costs about
    1 MB of GPU memory. Capping padded target tokens per batch keeps a batch of long
    verses from running a 4 GB GPU out of memory, while short sentences batch densely.
    """
    order = sorted(range(len(examples)), key=lambda i: len(examples[i]["labels"]) + rng.random())
    chunks: list[list[dict]] = []
    current: list[dict] = []
    longest = 0
    for index in order:
        item = examples[index]
        length = max(len(item["labels"]), len(item["input_ids"]))
        if current and (len(current) + 1 > max_sentences or (len(current) + 1) * max(longest, length) > max_tokens):
            chunks.append(current)
            current, longest = [], 0
        current.append(item)
        longest = max(longest, length)
    if current:
        chunks.append(current)
    rng.shuffle(chunks)
    return chunks


def collate(batch: list[dict], pad_id: int, device):
    import torch

    width = max(len(item["input_ids"]) for item in batch)
    label_width = max(len(item["labels"]) for item in batch)
    input_ids = torch.full((len(batch), width), pad_id, dtype=torch.long)
    attention = torch.zeros((len(batch), width), dtype=torch.long)
    labels = torch.full((len(batch), label_width), -100, dtype=torch.long)
    for row, item in enumerate(batch):
        input_ids[row, : len(item["input_ids"])] = torch.tensor(item["input_ids"])
        attention[row, : len(item["input_ids"])] = 1
        labels[row, : len(item["labels"])] = torch.tensor(item["labels"])
    return input_ids.to(device), attention.to(device), labels.to(device)


def translate(model, tokenizer, texts: list[str], src: str, tgt: str, device, batch_size: int = 16, beams: int = 1) -> list[str]:
    import torch

    tokenizer.src_lang = LANG_CODES[src]
    forced = tokenizer.convert_tokens_to_ids(LANG_CODES[tgt])
    outputs: list[str] = []
    model.eval()
    for start in range(0, len(texts), batch_size):
        chunk = texts[start : start + batch_size]
        inputs = tokenizer(chunk, return_tensors="pt", padding=True, truncation=True, max_length=192).to(device)
        with torch.no_grad(), torch.autocast(device.type, dtype=torch.bfloat16, enabled=device.type == "cuda"):
            generated = model.generate(
                **inputs,
                forced_bos_token_id=forced,
                num_beams=beams,
                max_new_tokens=min(256, int(inputs["input_ids"].shape[1] * 2) + 16),
            )
        outputs.extend(tokenizer.batch_decode(generated, skip_special_tokens=True))
    model.train()
    return outputs


def chrf(hypotheses: list[str], references: list[str]) -> float:
    from sacrebleu.metrics import CHRF

    return round(CHRF(word_order=2).corpus_score(hypotheses, [references]).score, 2)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-model", default=str(LOCAL_BASE) if LOCAL_BASE.is_dir() else DEFAULT_BASE)
    parser.add_argument("--datasets-dir", type=Path, default=DEFAULT_DATASETS_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=16, help="Most sentences per batch")
    parser.add_argument(
        "--max-tokens", type=int, default=448, help="Most padded tokens per batch (448 fits a 4 GB GPU; raise it on a bigger one)"
    )
    parser.add_argument("--grad-accumulation", type=int, default=2)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--warmup-steps", type=int, default=150)
    parser.add_argument("--max-length", type=int, default=128)
    parser.add_argument("--lora-r", type=int, default=32)
    parser.add_argument("--lora-alpha", type=int, default=64)
    parser.add_argument("--gradient-checkpointing", action="store_true")
    parser.add_argument("--max-minutes", type=float, default=0, help="Stop early after this many minutes (0: no limit)")
    parser.add_argument("--skip-baseline", action="store_true", help="Skip scoring the untouched model first")
    parser.add_argument("--resume", action="store_true", help="Continue training the adapter already in --output")
    parser.add_argument(
        "--min-battery", type=int, default=35,
        help="On a laptop running on battery, stop and save below this charge (0: never)",
    )
    parser.add_argument("--seed", type=int, default=13)
    args = parser.parse_args()
    # Logs redirected to a file on Windows default to cp1252, which cannot print “quotes”.
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")

    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    keep_awake()
    on_mains, percent = power_status()
    print(f"Power: {'mains' if on_mains else 'battery'}{f', {percent}%' if percent is not None else ''}", flush=True)
    rng = random.Random(args.seed)
    torch.manual_seed(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device: {device} {torch.cuda.get_device_name(0) if device.type == 'cuda' else ''}", flush=True)

    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    # Loading straight onto the GPU avoids holding a second full copy in system RAM.
    model = AutoModelForSeq2SeqLM.from_pretrained(
        args.base_model,
        dtype=torch.bfloat16 if device.type == "cuda" else torch.float32,
        device_map={"": 0} if device.type == "cuda" else None,
    )
    # Generation always passes max_new_tokens; the checkpoint's max_length of 200 would only add a warning per call.
    model.generation_config.max_length = None
    if args.gradient_checkpointing:
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
        model.enable_input_require_grads()
    if args.resume:
        from peft import PeftModel

        model = PeftModel.from_pretrained(model, args.output, is_trainable=True)
        print(f"Resuming from the adapter in {args.output}", flush=True)
    else:
        lora = LoraConfig(
            r=args.lora_r,
            lora_alpha=args.lora_alpha,
            lora_dropout=0.05,
            target_modules=["q_proj", "k_proj", "v_proj", "out_proj", "fc1", "fc2"],
            task_type="SEQ_2_SEQ_LM",
        )
        model = get_peft_model(model, lora)
    model.print_trainable_parameters()
    model.to(device)
    model.train()

    examples = load_examples(args.datasets_dir)
    pairs_en_nag = [(e.english, e.nagamese) for e in examples]
    pairs_nag_en = [(e.nagamese, e.english) for e in examples]
    train = encode(tokenizer, pairs_en_nag, "en", "nag", args.max_length) + encode(
        tokenizer, pairs_nag_en, "nag", "en", args.max_length
    )
    dev_dir = args.datasets_dir / "processed" / "dev_sets"
    dev = {
        "en-nag": read_pairs(dev_dir / "bible_dev_en_nag.tsv"),
        "nag-en": read_pairs(dev_dir / "bible_dev_nag_en.tsv"),
    }
    print(f"Training examples: {len(train)} (both directions); dev: {len(dev['en-nag'])} per direction", flush=True)

    trainable = [p for p in model.parameters() if p.requires_grad]
    optimizer = torch.optim.AdamW(trainable, lr=args.lr, weight_decay=0.0)
    batches_per_epoch = len(batches(train, args.batch_size, args.max_tokens, random.Random(0)))
    steps_per_epoch = math.ceil(batches_per_epoch / args.grad_accumulation)
    total_steps = steps_per_epoch * args.epochs
    print(f"{batches_per_epoch} batches per epoch, {total_steps} optimizer steps in total", flush=True)

    def schedule(step: int) -> float:
        if step < args.warmup_steps:
            return (step + 1) / args.warmup_steps
        return max(0.0, (total_steps - step) / max(1, total_steps - args.warmup_steps))

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, schedule)
    log: dict = {"args": {k: str(v) for k, v in vars(args).items()}, "lang_codes": LANG_CODES, "epochs": [], "loss": []}
    args.output.mkdir(parents=True, exist_ok=True)
    best_score = -1.0
    started = time.time()
    step = 0
    stop = False

    def evaluate() -> dict[str, float]:
        scores = {}
        for direction, pairs in dev.items():
            src, tgt = direction.split("-")
            hyps = translate(model, tokenizer, [s for s, _ in pairs], src, tgt, device)
            scores[direction] = chrf(hyps, [t for _, t in pairs])
            print(f"  dev {direction}: chrF++ {scores[direction]}  e.g. {hyps[0][:90]!r}", flush=True)
        return scores

    def write_meta() -> None:
        meta = {
            "base_model": args.base_model if not Path(args.base_model).is_dir() else DEFAULT_BASE,
            "lang_codes": LANG_CODES,
            "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "training_pairs": len(examples),
            "best_mean_dev_chrf++": round(best_score, 2),
            "license_note": "Base model NLLB-200 is CC BY-NC 4.0; training text includes CC BY-SA 4.0 Nagamese scripture.",
            "log": log,
        }
        (args.output / "likhibi_nmt.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def optimizer_step() -> None:
        torch.nn.utils.clip_grad_norm_(trainable, 1.0)
        optimizer.step()
        scheduler.step()
        optimizer.zero_grad(set_to_none=True)

    if not args.skip_baseline:
        print("Baseline before fine-tuning:", flush=True)
        log["baseline_dev_chrf++"] = evaluate()

    for epoch in range(1, args.epochs + 1):
        running, seen = 0.0, 0
        for index, batch in enumerate(batches(train, args.batch_size, args.max_tokens, rng), start=1):
            input_ids, attention, labels = collate(batch, tokenizer.pad_token_id, device)
            with torch.autocast(device.type, dtype=torch.bfloat16, enabled=device.type == "cuda"):
                loss = model(input_ids=input_ids, attention_mask=attention, labels=labels).loss
            (loss / args.grad_accumulation).backward()
            running += loss.item()
            seen += 1
            if index % args.grad_accumulation == 0:
                optimizer_step()
                step += 1
                if step % 50 == 0:
                    elapsed = (time.time() - started) / 60
                    print(f"epoch {epoch} step {step}/{total_steps} loss {running / seen:.4f} lr {scheduler.get_last_lr()[0]:.2e} {elapsed:.1f} min", flush=True)
                    log["loss"].append({"step": step, "loss": round(running / seen, 4)})
                    running, seen = 0.0, 0
                if args.max_minutes and (time.time() - started) / 60 > args.max_minutes:
                    stop = True
                    break
                if args.min_battery and step % 25 == 0:
                    on_mains, percent = power_status()
                    if not on_mains and percent is not None and percent < args.min_battery:
                        print(f"Battery at {percent}% and unplugged: stopping to save the adapter.", flush=True)
                        stop = True
                        break
        if seen and index % args.grad_accumulation:
            optimizer_step()  # do not carry a half-accumulated gradient into the next epoch
            step += 1
        scores = evaluate()
        average = (scores["en-nag"] + scores["nag-en"]) / 2
        log["epochs"].append({"epoch": epoch, "step": step, "dev_chrf++": scores, "minutes": round((time.time() - started) / 60, 1)})
        if average > best_score:
            best_score = average
            model.save_pretrained(args.output)
            tokenizer.save_pretrained(args.output)
            print(f"  saved best adapter (mean dev chrF++ {average:.2f})", flush=True)
        write_meta()  # keep the log current even if a later epoch crashes
        if stop:
            print("Stopped early. Continue later with --resume.", flush=True)
            break

    write_meta()
    print(f"Done in {(time.time() - started) / 60:.1f} min. Best mean dev chrF++ {best_score:.2f}. Adapter: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
