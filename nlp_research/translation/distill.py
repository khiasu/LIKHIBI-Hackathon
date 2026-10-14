"""
Distil the fine-tuned NLLB teacher into a small student model that runs on a phone.

NLLB-200 600M is far too large for a keyboard: about 600 MB even at 8-bit. The
student is a Marian-style transformer of about 21M parameters. It learns from the
real aligned verse pairs and from the teacher's translations of short everyday
English sentences from Tatoeba, and it translates both directions, chosen by a tag
token at the start of the input.

Steps, from the repository root in the ML environment:

    python -m nlp_research.translation.distill prepare    # pick everyday English sentences
    python -m nlp_research.translation.distill generate   # the teacher translates them (GPU, resumable)
    python -m nlp_research.translation.distill train      # tokenizer and student (GPU)

Then export it for the keyboard with `python -m nlp_research.translation.export_student`.

Licenses: Tatoeba sentences are CC BY 2.0 FR. The teacher's outputs, and the student
trained on them, follow NLLB's CC BY-NC 4.0 and the ISV Nagamese CC BY-SA 4.0:
non-commercial, share-alike, with attribution.
"""

from __future__ import annotations

import argparse
import bz2
import csv
import json
import os
import random
import re
import sys
import time
from pathlib import Path

from .bank import DEFAULT_DATASETS_DIR, REPO_ROOT, held_out_keys
from .text import clean, match_key

TATOEBA_ENGLISH = DEFAULT_DATASETS_DIR / "raw" / "monolingual" / "tatoeba_eng" / "eng_sentences.tsv.bz2"
SYNTHETIC_DIR = DEFAULT_DATASETS_DIR / "processed" / "synthetic"
SELECTED = SYNTHETIC_DIR / "tatoeba_en_selected.tsv"
TEACHER_OUTPUT = SYNTHETIC_DIR / "tatoeba_en_nag_teacher.tsv"
BIBLE_KD = SYNTHETIC_DIR / "bible_kd.tsv"  # the teacher's translations of the training verses, both ways
STUDENT_DIR = REPO_ROOT / "models" / "student"

# Everyday sentences only: plain ASCII text without digits, 3 to 14 words.
_ALLOWED = re.compile(r"^[A-Za-z ,.?!'\-]+$")
_PRONOUN_I = {"I", "I'm", "I'll", "I've", "I'd"}


def _everyday(text: str) -> bool:
    if not _ALLOWED.match(text):
        return False
    tokens = text.split()
    if not 3 <= len(tokens) <= 14:
        return False
    # A capital after the first word is almost always a name ("Tom", "Boston"); names
    # teach the student nothing about Nagamese, so those sentences are left out.
    return not any(t[0].isupper() and t.strip(",.?!") not in _PRONOUN_I for t in tokens[1:])


def prepare(count: int, seed: int, source: Path = TATOEBA_ENGLISH, output: Path = SELECTED) -> int:
    """Choose `count` everyday English sentences and write them as id/english TSV."""
    blocked = {key for lang, key in held_out_keys(DEFAULT_DATASETS_DIR) if lang == "en"}
    seen: set[str] = set()
    candidates: list[tuple[str, str]] = []
    with bz2.open(source, "rt", encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            sentence_id, _, text = parts
            text = clean(text)
            lowered = text.lower()
            if lowered in seen or not _everyday(text) or match_key(text, "en") in blocked:
                continue
            seen.add(lowered)
            candidates.append((sentence_id, text))
    rng = random.Random(seed)
    chosen = rng.sample(candidates, min(count, len(candidates)))
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as handle:
        handle.write("tatoeba_id\tenglish\n")
        handle.writelines(f"{sentence_id}\t{text}\n" for sentence_id, text in chosen)
    print(f"{len(candidates):,} everyday sentences found; wrote {len(chosen):,} to {output}")
    return len(chosen)


def load_teacher():
    """The fine-tuned NLLB, merged and in bfloat16 on the GPU, as the NMT engine loads it."""
    import torch
    from peft import PeftModel
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    from .engines.nmt import DEFAULT_ADAPTER, HUB_BASE, LOCAL_BASE

    adapter = Path(os.environ.get("LIKHIBI_NMT_ADAPTER") or DEFAULT_ADAPTER)
    base = os.environ.get("LIKHIBI_NMT_BASE_MODEL") or (str(LOCAL_BASE) if LOCAL_BASE.is_dir() else HUB_BASE)
    if not torch.cuda.is_available():
        raise SystemExit("The teacher needs a CUDA GPU; generating on the CPU would take days.")
    tokenizer = AutoTokenizer.from_pretrained(adapter)
    model = AutoModelForSeq2SeqLM.from_pretrained(base, dtype=torch.bfloat16, device_map={"": 0})
    model = PeftModel.from_pretrained(model, adapter).merge_and_unload()
    model.generation_config.max_length = None
    model.eval()
    return model, tokenizer, torch.device("cuda")


def _read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))


def _cell(text: str) -> str:
    """One TSV cell: tabs and line breaks would split the row, so they become spaces."""
    return clean(text.replace("\t", " ").replace("\n", " ").replace("\r", " "))


def generate(beams: int, batch_size: int, block: int, limit: int | None, min_battery: int) -> int:
    """Translate the selected sentences into Nagamese with the teacher. Safe to stop and rerun."""
    from .train_nmt import keep_awake, power_status, translate

    rows = _read_tsv(SELECTED)
    done: set[str] = set()
    if TEACHER_OUTPUT.is_file():
        done = {row["tatoeba_id"] for row in _read_tsv(TEACHER_OUTPUT)}
    todo = [row for row in rows if row["tatoeba_id"] not in done]
    if limit is not None:
        todo = todo[:limit]
    if not todo:
        print(f"Nothing to do: {len(done):,} sentences already translated in {TEACHER_OUTPUT}")
        return 0
    keep_awake()
    model, tokenizer, device = load_teacher()
    print(f"Teacher loaded. {len(done):,} done, {len(todo):,} to go, beams {beams}, batch {batch_size}", flush=True)
    # Similar lengths together keep padding, and so wasted GPU work, low.
    todo.sort(key=lambda row: len(row["english"]))
    new_file = not TEACHER_OUTPUT.is_file()
    started, finished = time.time(), 0
    with TEACHER_OUTPUT.open("a", encoding="utf-8", newline="") as handle:
        if new_file:
            handle.write("tatoeba_id\tenglish\tnagamese\n")
        for start in range(0, len(todo), block):
            on_mains, percent = power_status()
            if not on_mains and percent is not None and percent < min_battery:
                print(f"Stopped: on battery at {percent}%. Plug in and rerun to continue.", flush=True)
                return 1
            chunk = todo[start : start + block]
            outputs = translate(model, tokenizer, [row["english"] for row in chunk], "en", "nag", device, batch_size, beams)
            for row, nagamese in zip(chunk, outputs):
                handle.write(f"{row['tatoeba_id']}\t{row['english']}\t{_cell(nagamese)}\n")
            handle.flush()
            finished += len(chunk)
            rate = finished / (time.time() - started)
            remaining = (len(todo) - finished) / rate if rate else 0
            print(f"  {len(done) + finished:,} translated, {rate:.1f}/s, about {remaining / 60:.0f} min left", flush=True)
    print(f"Done: {len(done) + finished:,} pairs in {TEACHER_OUTPUT}", flush=True)
    return 0


def generate_bible_kd(beams: int, batch_size: int) -> int:
    """The teacher translates every training verse both ways (sequence-level distillation).

    A small student learns a teacher's outputs more easily than human translations of
    the same sentences (Kim and Rush, 2016), so these pairs are added next to the real ones.
    Dev and test books are already excluded by bank.load_examples.
    """
    from .bank import load_examples
    from .train_nmt import keep_awake, translate

    keep_awake()
    examples = load_examples(DEFAULT_DATASETS_DIR)
    model, tokenizer, device = load_teacher()
    SYNTHETIC_DIR.mkdir(parents=True, exist_ok=True)
    started = time.time()
    with BIBLE_KD.open("w", encoding="utf-8", newline="") as handle:
        handle.write("direction\tsource\tteacher_output\n")
        for source_lang, target_lang in (("en", "nag"), ("nag", "en")):
            sources = sorted({clean(e.english if source_lang == "en" else e.nagamese) for e in examples}, key=len)
            outputs = translate(model, tokenizer, sources, source_lang, target_lang, device, batch_size, beams)
            for source, output in zip(sources, outputs):
                handle.write(f"{source_lang}-{target_lang}\t{_cell(source)}\t{_cell(output)}\n")
            print(f"  {source_lang}-{target_lang}: {len(sources):,} verses, {(time.time() - started) / 60:.1f} min", flush=True)
    return 0


def bible_kd_pairs() -> dict[str, list[tuple[str, str]]]:
    """(english, nagamese) pairs per direction, where the target side is the teacher's output."""
    pairs: dict[str, list[tuple[str, str]]] = {"en-nag": [], "nag-en": []}
    if not BIBLE_KD.is_file():
        return pairs
    for row in _read_tsv(BIBLE_KD):
        source, output = row["source"], row["teacher_output"]
        if row["direction"] == "en-nag" and _plausible(source, output):
            pairs["en-nag"].append((source, output))
        elif row["direction"] == "nag-en" and output:
            pairs["nag-en"].append((output, source))
    return pairs


# ----------------------------------------------------------------------------- student

TAGS = {"en": "<2en>", "nag": "<2nag>"}  # first source token: the language to translate into
PAD, UNK, EOS = 0, 1, 2
SPM_PREFIX = STUDENT_DIR / "spm"
DEV_FILES = {
    "en-nag": DEFAULT_DATASETS_DIR / "processed" / "dev_sets" / "bible_dev_en_nag.tsv",
    "nag-en": DEFAULT_DATASETS_DIR / "processed" / "dev_sets" / "bible_dev_nag_en.tsv",
}


def _plausible(english: str, nagamese: str) -> bool:
    """Drop teacher outputs that are empty, runaway long, or stuck repeating one word."""
    if not nagamese or len(nagamese) > 3 * len(english) + 20:
        return False
    words = nagamese.lower().split()
    return len(words) < 4 or max(words.count(w) for w in set(words)) <= len(words) / 2


def training_pairs() -> tuple[list[tuple[str, str]], list[tuple[str, str]]]:
    """Real and synthetic (English, Nagamese) pairs. Dev and test books are already excluded."""
    from .bank import load_examples

    real = [(clean(e.english), clean(e.nagamese)) for e in load_examples(DEFAULT_DATASETS_DIR)]
    synthetic = [
        (clean(row["english"]), clean(row["nagamese"]))
        for row in _read_tsv(TEACHER_OUTPUT)
        if _plausible(row["english"], row["nagamese"])
    ]
    return real, synthetic


def build_tokenizer(texts: list[str], vocab_size: int):
    """A SentencePiece unigram model shared by both languages, with the two direction tags as pieces.

    Normalisation is left to `text.clean` (NFC, straight quotes, single spaces) so the
    keyboard's Kotlin tokenizer only has to repeat that and the "▁" word marker.
    """
    import sentencepiece as spm

    STUDENT_DIR.mkdir(parents=True, exist_ok=True)
    corpus = STUDENT_DIR / "spm_corpus.txt"
    corpus.write_text("\n".join(texts) + "\n", encoding="utf-8")
    spm.SentencePieceTrainer.train(
        input=str(corpus),
        model_prefix=str(SPM_PREFIX),
        vocab_size=vocab_size,
        model_type="unigram",
        character_coverage=1.0,
        normalization_rule_name="identity",
        pad_id=PAD,
        unk_id=UNK,
        bos_id=-1,
        eos_id=EOS,
        user_defined_symbols=list(TAGS.values()),
        input_sentence_size=1_000_000,
        shuffle_input_sentence=True,
        num_threads=8,
        minloglevel=2,
    )
    corpus.unlink()
    return spm.SentencePieceProcessor(model_file=f"{SPM_PREFIX}.model")


def encode_source(sp, text: str, target_lang: str, max_length: int = 128) -> list[int]:
    return [sp.piece_to_id(TAGS[target_lang])] + sp.encode(clean(text))[: max_length - 2] + [EOS]


def encode_pairs(sp, pairs: list[tuple[str, str]], direction: str, max_length: int = 128) -> list[dict]:
    source_lang, target_lang = direction.split("-")
    examples = []
    for english, nagamese in pairs:
        source, target = (english, nagamese) if source_lang == "en" else (nagamese, english)
        examples.append(
            {
                "input_ids": encode_source(sp, source, target_lang, max_length),
                "labels": sp.encode(clean(target))[: max_length - 1] + [EOS],
            }
        )
    return examples


def student_config(vocab_size: int, d_model: int = 256, ffn: int = 1024, decoder_layers: int = 3):
    """About 10M parameters at d_model 256, 21M at 384 (the shipped model): small enough for a phone,
    with a deep encoder to read Nagamese well."""
    from transformers import MarianConfig

    return MarianConfig(
        vocab_size=vocab_size,
        decoder_vocab_size=vocab_size,
        max_position_embeddings=256,
        d_model=d_model,
        encoder_layers=6,
        decoder_layers=decoder_layers,
        encoder_attention_heads=d_model // 64,
        decoder_attention_heads=d_model // 64,
        encoder_ffn_dim=ffn,
        decoder_ffn_dim=ffn,
        dropout=0.1,
        attention_dropout=0.0,
        activation_dropout=0.0,
        activation_function="gelu",
        scale_embedding=True,
        pad_token_id=PAD,
        eos_token_id=EOS,
        decoder_start_token_id=PAD,
        forced_eos_token_id=EOS,
        share_encoder_decoder_embeddings=True,
        tie_word_embeddings=True,
    )


def greedy_translate(model, sp, texts: list[str], target_lang: str, device, batch_size: int = 64) -> list[str]:
    import torch

    outputs: list[str] = []
    model.eval()
    for start in range(0, len(texts), batch_size):
        encoded = [encode_source(sp, text, target_lang) for text in texts[start : start + batch_size]]
        width = max(len(ids) for ids in encoded)
        input_ids = torch.full((len(encoded), width), PAD, dtype=torch.long)
        attention = torch.zeros((len(encoded), width), dtype=torch.long)
        for row, ids in enumerate(encoded):
            input_ids[row, : len(ids)] = torch.tensor(ids)
            attention[row, : len(ids)] = 1
        with torch.no_grad():
            generated = model.generate(
                input_ids=input_ids.to(device),
                attention_mask=attention.to(device),
                num_beams=1,
                do_sample=False,
                max_new_tokens=min(256, 2 * width + 10),
            )
        for ids in generated.tolist():
            outputs.append(sp.decode([i for i in ids if i not in (PAD, EOS)]))
    model.train()
    return outputs


def train_student(minutes: float, max_tokens: int, lr: float, warmup: int, real_weight: int, vocab_size: int, seed: int,
                  d_model: int = 256, ffn: int = 1024, decoder_layers: int = 3, kd: bool = False) -> int:
    import torch
    import torch.nn.functional as F
    from transformers import MarianMTModel

    from .train_nmt import batches, chrf, collate, keep_awake, power_status, read_pairs

    keep_awake()
    rng = random.Random(seed)
    torch.manual_seed(seed)
    real, synthetic = training_pairs()
    distilled = bible_kd_pairs() if kd else {"en-nag": [], "nag-en": []}
    print(f"Real pairs {len(real):,}, synthetic pairs {len(synthetic):,} (after filtering), "
          f"distilled verses {len(distilled['en-nag']):,} + {len(distilled['nag-en']):,}", flush=True)
    texts = [text for pair in real + synthetic + distilled["en-nag"] + distilled["nag-en"] for text in pair]
    sp = build_tokenizer(texts, vocab_size)
    train_set = []
    for direction in ("en-nag", "nag-en"):
        train_set += encode_pairs(sp, real * real_weight, direction) + encode_pairs(sp, synthetic, direction)
        train_set += encode_pairs(sp, distilled[direction], direction)
    dev = {direction: read_pairs(path) for direction, path in DEV_FILES.items()}

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = MarianMTModel(student_config(sp.get_piece_size(), d_model, ffn, decoder_layers)).to(device)
    parameters = sum(p.numel() for p in model.parameters())
    print(f"Student: {parameters / 1e6:.1f}M parameters, {len(train_set):,} training examples, device {device}", flush=True)
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, betas=(0.9, 0.98), eps=1e-9)

    def rate(step: int) -> float:  # linear warm-up, then inverse square root decay
        step = max(step, 1)
        return min(step / warmup, (warmup / step) ** 0.5)

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, rate)
    vocab = sp.get_piece_size()
    started, step, best, log = time.time(), 0, -1.0, []
    hf_dir = STUDENT_DIR / "hf"
    epoch = 0
    model.train()
    while (time.time() - started) / 60 < minutes:
        epoch += 1
        for batch in batches(train_set, 512, max_tokens, rng):
            input_ids, attention, labels = collate(batch, PAD, device)
            decoder_input = torch.full_like(labels, PAD)
            decoder_input[:, 1:] = labels[:, :-1].clamp(min=0)
            with torch.autocast(device.type, dtype=torch.bfloat16, enabled=device.type == "cuda"):
                logits = model(input_ids=input_ids, attention_mask=attention, decoder_input_ids=decoder_input).logits
            loss = F.cross_entropy(logits.float().view(-1, vocab), labels.view(-1), ignore_index=-100, label_smoothing=0.1)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()
            optimizer.zero_grad(set_to_none=True)
            step += 1
            if step % 500 == 0:
                print(f"  epoch {epoch} step {step} loss {loss.item():.3f} lr {scheduler.get_last_lr()[0]:.2e} {(time.time() - started) / 60:.1f} min", flush=True)
            if step % 200 == 0:
                on_mains, percent = power_status()
                if not on_mains and percent is not None and percent < 35:
                    print(f"Stopped: on battery at {percent}%.", flush=True)
                    minutes = 0
                    break
            if (time.time() - started) / 60 >= minutes:
                break
        scores = {}
        for direction, pairs in dev.items():
            target_lang = direction.split("-")[1]
            hypotheses = greedy_translate(model, sp, [s for s, _ in pairs], target_lang, device)
            scores[direction] = chrf(hypotheses, [t for _, t in pairs])
        mean = round(sum(scores.values()) / len(scores), 2)
        log.append({"epoch": epoch, "step": step, "dev_chrf++": scores, "minutes": round((time.time() - started) / 60, 1)})
        print(f"  epoch {epoch}: dev chrF++ {scores}", flush=True)
        if mean > best:
            best = mean
            model.save_pretrained(hf_dir)
            print(f"  saved best student (mean dev chrF++ {mean})", flush=True)

    meta = {
        "architecture": f"Marian transformer, 6-layer encoder, {decoder_layers}-layer decoder, d_model {d_model}, ffn {ffn}",
        "sequence_level_distillation_on_training_verses": kd,
        "parameters": parameters,
        "vocab_size": vocab,
        "special_ids": {"pad": PAD, "unk": UNK, "eos": EOS, **{lang: sp.piece_to_id(tag) for lang, tag in TAGS.items()}},
        "training_pairs": {"real": len(real), "real_weight": real_weight, "synthetic": len(synthetic),
                           "distilled_verses": {d: len(v) for d, v in distilled.items()}},
        "best_mean_dev_chrf++": best,
        "log": log,
        "trained_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "license_note": (
            "Learns from NLLB-200 outputs (CC BY-NC 4.0), ISV Nagamese text (CC BY-SA 4.0) and Tatoeba English "
            "(CC BY 2.0 FR): non-commercial, share-alike, with attribution."
        ),
    }
    (STUDENT_DIR / "student_meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    print(f"Done in {(time.time() - started) / 60:.1f} min. Best mean dev chrF++ {best}.", flush=True)
    return 0


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare", help="choose everyday English sentences from Tatoeba")
    prep.add_argument("--count", type=int, default=100_000)
    prep.add_argument("--seed", type=int, default=13)
    gen = commands.add_parser("generate", help="translate them with the fine-tuned NLLB teacher")
    gen.add_argument("--beams", type=int, default=2)
    gen.add_argument("--batch-size", type=int, default=96)
    gen.add_argument("--block", type=int, default=2048, help="sentences written per checkpoint")
    gen.add_argument("--limit", type=int, help="translate only this many, for timing")
    gen.add_argument("--min-battery", type=int, default=35)
    tr = commands.add_parser("train", help="train the tokenizer and the student model")
    tr.add_argument("--minutes", type=float, default=100, help="training time budget")
    tr.add_argument("--max-tokens", type=int, default=8192, help="most padded tokens per batch")
    tr.add_argument("--lr", type=float, default=7e-4)
    tr.add_argument("--warmup", type=int, default=2000)
    tr.add_argument("--real-weight", type=int, default=3, help="how many times each real pair is repeated")
    tr.add_argument("--vocab-size", type=int, default=8000)
    tr.add_argument("--seed", type=int, default=13)
    tr.add_argument("--d-model", type=int, default=256)
    tr.add_argument("--ffn", type=int, default=1024)
    tr.add_argument("--decoder-layers", type=int, default=3)
    tr.add_argument("--kd", action="store_true", help="also train on the teacher's translations of the training verses")
    kd = commands.add_parser("kd", help="the teacher translates the training verses both ways")
    kd.add_argument("--beams", type=int, default=2)
    kd.add_argument("--batch-size", type=int, default=48)
    args = parser.parse_args(argv)
    if args.command == "prepare":
        prepare(args.count, args.seed)
        return 0
    if args.command == "generate":
        return generate(args.beams, args.batch_size, args.block, args.limit, args.min_battery)
    if args.command == "train":
        return train_student(args.minutes, args.max_tokens, args.lr, args.warmup, args.real_weight, args.vocab_size,
                             args.seed, args.d_model, args.ffn, args.decoder_layers, args.kd)
    if args.command == "kd":
        return generate_bible_kd(args.beams, args.batch_size)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
