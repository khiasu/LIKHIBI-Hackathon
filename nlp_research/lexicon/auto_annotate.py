"""
Annotate what needs no judgement, and turn compact research lists into research shards.

Research agents write compact tab-separated lists (cheap to write) into
datasets/raw/vocabulary/research/compact/; this script expands them into the JSON
Lines shards that validate_research.py checks and build_lexicon.py reads. It also
annotates, without any model, every listed word whose annotations follow from a
reference:

    function words      the hand-tagged list in closed_class.py
    names               Naga place and tribe names, and names written with a capital
    English loanwords   WordNet (Princeton), for words it lists and English text uses
    inflected forms     korise, manukhan, ghorte: from the annotation of their root
    usage examples      the shortest New Testament sentence that uses the word

    python -m nlp_research.lexicon.auto_annotate worklist --out DIR --parts 9
        words that still need a research agent, as compact lists split into parts
    python -m nlp_research.lexicon.auto_annotate expand
        compact lists -> new_*.jsonl and enrich_*.jsonl, plus enrich_auto.jsonl,
        then the corrections in patch_*.tsv

Compact list formats (tab-separated, no header, one word per line):

    english_<name>.tsv   word, pos, gloss, domains, variants
                         new English loanwords; WordNet fills the rest
    new_<name>.tsv       word, pos, alternatives, definition, glosses, domains, origin,
    enrich_<name>.tsv    source_form, features, variants, example, confidence, note[, source_url]

    alternatives  TAG=gloss|TAG=gloss      glosses  a;b;c      domains, features, variants  a,b
    origin        a code from ORIGIN_CODES    example  "-" to use a New Testament sentence
    note          starts with "doubtful" to flag a word that may not be Nagamese
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from nlp_research.lexicon.closed_class import CLOSED_CLASS, ENGLISH_BOILERPLATE, HINDI_FUNCTION_WORDS, NAGA_PROPER_NOUNS
from nlp_research.lexicon.validate_research import LEXICON_JSON, RESEARCH_DIR, UPOS, WORD, check_row

COMPACT_DIR = RESEARCH_DIR / "compact"
ORIGIN_CODES = {
    "N": "Nagamese", "A": "Assamese", "B": "Bengali", "H": "Hindi loanword", "P": "Persian-Arabic via Hindi",
    "E": "English loanword", "S": "Sanskrit (tatsama)", "L": "Naga languages", "BN": "Biblical name", "PN": "Proper name",
}
WORDNET_URL = "https://wordnet.princeton.edu/"
LEXNAME_DOMAINS = {
    "noun.food": "food", "noun.body": "body", "noun.person": "community", "noun.artifact": "home",
    "noun.act": "actions", "noun.communication": "media", "noun.time": "time", "noun.location": "places",
    "noun.plant": "nature", "noun.animal": "nature", "noun.object": "nature", "noun.phenomenon": "weather",
    "noun.feeling": "feelings", "noun.motive": "feelings", "noun.cognition": "education", "noun.possession": "money",
    "noun.group": "community", "noun.state": "health", "noun.attribute": "describing", "noun.shape": "describing",
    "noun.quantity": "numbers", "noun.event": "culture", "noun.process": "actions", "noun.substance": "home",
    "noun.relation": "grammar", "verb.communication": "media", "verb.consumption": "food", "verb.emotion": "feelings",
    "verb.motion": "travel", "verb.possession": "money", "verb.social": "community", "verb.body": "body",
    "verb.weather": "weather", "verb.cognition": "education", "verb.creation": "work", "verb.competition": "leisure",
}
# Substances and abstract nouns: no -khan plural is generated for these ("availabilitykhan").
MASS_LEXNAMES = {"noun.substance", "noun.food", "noun.attribute", "noun.state", "noun.feeling", "noun.cognition",
                 "noun.act", "noun.process", "noun.phenomenon", "noun.relation", "noun.motive", "noun.possession"}
DEFAULT_DOMAIN = {"NOUN": "home", "VERB": "actions", "ADJ": "describing", "ADV": "grammar", "PROPN": "places"}
WN_POS = {"n": "NOUN", "v": "VERB", "a": "ADJ", "s": "ADJ", "r": "ADV"}
LIGHT_VERB = "; used with kori as a light verb"


def example_for(word: str, pos: str, features: list[str], light_verb: bool = True) -> str:
    """A plain Nagamese frame for a word without a sentence of its own.

    Loanword verbs take the light verb kori (Moi upload kori dibo); native verbs inflect themselves (Moi likhi dibo).
    """
    if pos == "VERB":
        return f"Moi {word} kori dibo." if light_verb else f"Moi {word} dibo."
    if pos == "ADJ":
        return f"Etu bisi {word} ase."
    if pos == "ADV":
        return f"Tai {word} ahise."
    if pos == "PROPN":
        return f"Moi {word} te jabo."
    if pos == "INTJ":
        return f"{word[:1].upper() + word[1:]}! Etu bhal hoise."
    if "animate" in features:
        return f"Tai {word} ase."
    return f"Etu {word} ase."


def row(word: str, pos: str, definition: str, glosses: list[str], domains: list[str], origin: str, *,
        alternatives: list[dict] | None = None, source_form: str = "", morphology: str = "simple stem (no affix)",
        features: list[str] | None = None, variants: list[str] | None = None, register: str = "everyday",
        example: str = "", urls: list[str] | None = None, method: str = "model-annotated", confidence: float = 0.6,
        display: str = "", note: str = "") -> dict:
    features = features or (["base form"] if pos in ("NOUN", "VERB", "ADJ") else ["invariable"])
    result = {
        "word": word, "display": display or word, "pos": pos, "pos_alternatives": alternatives or [],
        "english_definition": definition, "glosses": [g for g in glosses if g][:4] or [word],
        "domains": list(dict.fromkeys(domains))[:3] or [DEFAULT_DOMAIN.get(pos, "grammar")], "origin": origin,
        "source_form": source_form or word, "morphology": morphology, "features": features,
        "variants": [v for v in variants or [] if WORD.match(v) and v != word], "register": register,
        "usage_example": example or example_for(display or word, pos, features, light_verb=origin == "English loanword"), "source_urls": urls or [],
        "method": method, "confidence": confidence,
    }
    if note:
        result["flag"], result["note"] = "doubtful", note
    return result


def sentence_examples(words: set[str]) -> dict[str, str]:
    """The shortest New Testament sentence (up to 140 characters) that uses each word."""
    from nlp_research.prediction.build_keyboard_assets import sentences
    from nlp_research.preprocessing.tokenizer import NagameseTokenizer

    tokenizer, best = NagameseTokenizer(), {}
    for sentence in sentences():
        sentence = re.sub(r"[“”\"‘’]", "", sentence).strip(" ,;:")
        if len(sentence) > 140 or len(sentence) < 8 or "*" in sentence or "†" in sentence:
            continue
        for token in set(tokenizer.tokenize(sentence)) & words:
            if token not in best or len(sentence) < len(best[token]):
                best[token] = sentence
    return best


def english_count(word: str) -> int:
    from nltk.corpus import wordnet as wn

    return sum(lemma.count() for synset in wn.synsets(word) for lemma in synset.lemmas() if lemma.name().lower() == word)


def wordnet_row(word: str, pos_hint: str = "", gloss_hint: str = "", domains: list[str] | None = None,
                variants: list[str] | None = None, example: str = "", register: str = "everyday") -> dict | None:
    """An English loanword annotated from WordNet, or None if WordNet doesn't list it."""
    from nltk.corpus import wordnet as wn

    groups: dict[str, list] = defaultdict(list)
    for synset in wn.synsets(word):
        # Only senses that list the word itself: "cooked" must not borrow the sense of "cook".
        if any(l.name().lower() == word for l in synset.lemmas()):
            groups[WN_POS[synset.pos()]].append(synset)
    if not groups:
        return None
    weight = {tag: sum(l.count() for s in syns for l in s.lemmas() if l.name().lower() == word) for tag, syns in groups.items()}
    main = pos_hint if pos_hint in groups else max(groups, key=lambda t: (weight[t], -list(WN_POS.values()).index(t)))
    chosen = groups[main][0]
    if gloss_hint:
        hint = set(re.findall(r"[a-z]+", gloss_hint.lower()))
        for synset in groups[main]:
            names = {n.lower() for n in synset.lemma_names()} | set(re.findall(r"[a-z]+", synset.definition()))
            if hint & names - {word}:
                chosen = synset
                break
    pos, display = main, ""
    proper = next((l.name() for l in chosen.lemmas() if l.name().lower() == word and l.name()[0].isupper()), "")
    if main == "NOUN" and proper:
        pos, display = "PROPN", proper
    lexname = chosen.lexname()
    glosses = [gloss_hint or word] + [n.replace("_", " ").lower() for n in chosen.lemma_names() if n.lower() != word and "_" not in n]
    alternatives = [
        {"tag": tag, "gloss": syns[0].definition() + (" (used with kori)" if tag == "VERB" else "")}
        for tag, syns in groups.items() if tag != main
    ]
    if pos == "NOUN":
        features = ["base form", "mass" if lexname in MASS_LEXNAMES else "countable",
                    "animate" if lexname in ("noun.person", "noun.animal") else "inanimate"]
    else:
        features = {"PROPN": ["proper name"], "VERB": ["citation form"], "ADJ": ["base form"]}.get(pos, ["invariable"])
    definition = chosen.definition()
    return row(word, pos, definition[0].upper() + definition[1:] + ".", list(dict.fromkeys(glosses)),
               list(domains or []) + [LEXNAME_DOMAINS.get(lexname, DEFAULT_DOMAIN.get(pos, "home"))], "English loanword",
               alternatives=alternatives, morphology="simple stem (no affix)" + (LIGHT_VERB if pos == "VERB" else ""),
               features=features, variants=variants, register=register, example=example, urls=[WORDNET_URL],
               method="dictionary (WordNet)", confidence=0.6, display=display)


def source_domains(e: dict) -> set[str]:
    """The domains an entry's own word lists give it (the headings of curated lists)."""
    from nlp_research.lexicon.build_lexicon import domain_for

    found = set()
    for source in e["sources"]:
        name = source["name"]
        if name.startswith("vocab:") and source.get("kind") != "scraped":
            domain = domain_for(name.split("#", 1)[-1])
            if domain:
                found.add(domain)
        elif name == "naga-place-and-tribe-names" and e["lemma"] in NAGA_PROPER_NOUNS:
            found.add(NAGA_PROPER_NOUNS[e["lemma"]][1])
    return found


def lexicon_entries() -> list[dict]:
    """Listed words, with the fields research changes reset to what the sources alone say.

    The lexicon file is itself built from these shards, so register and origin are
    recomputed from each entry's sources: expanding twice gives the same shards.
    """
    entries = [e for e in json.loads(LEXICON_JSON.read_text(encoding="utf-8")) if e["status"] not in ("researched", "derived")]
    for e in entries:
        kinds = {s.get("kind") for s in e["sources"]}
        everyday = e["keyboard_weight"] or kinds & {"nagamese", "english", "hindi", "variants"} or any(
            s["name"] == "naga-place-and-tribe-names" for s in e["sources"])
        e["register"] = "both" if e["frequency_count"] and everyday else "scripture" if e["frequency_count"] else "everyday"
        e["listed_as_english"] = "english" in kinds
        e["gloss_candidates"] = [g for g in e["gloss_candidates"] if g.get("method") in ("ibm1-aligned-verses", "hand-written")]
        e["domains"] = sorted(source_domains(e))
    return entries


def is_inflected(e: dict) -> bool:
    return "+" in e["morphology"] and e["root"] != e["lemma"]


def auto_rows(entries: list[dict], examples: dict[str, str]) -> dict[str, dict]:
    """Rows for listed words that a reference annotates: function words, names, English loanwords."""
    rows = {}
    for e in entries:
        word, example = e["lemma"], examples.get(e["lemma"], "")
        domains = e["domains"]
        if word in CLOSED_CLASS:
            tag, gloss = CLOSED_CLASS[word]
            rows[word] = row(word, tag, gloss[0].upper() + gloss[1:] + ".", re.split(r",\s*|;\s*", gloss),
                             ["pronouns" if tag == "PRON" else "grammar"], "Nagamese", features=["invariable"],
                             register=e["register"], example=example, method="hand-tagged list", confidence=0.9)
        elif word in NAGA_PROPER_NOUNS:
            proper, domain = NAGA_PROPER_NOUNS[word]
            kind = "a place in Nagaland" if domain == "places" else "a Naga people, festival or language"
            rows[word] = row(word, "PROPN", f"{proper}, {kind}.", [proper], [domain], "Proper name",
                             features=["proper name"], register=e["register"], example=example, display=proper,
                             method="name list", confidence=0.9)
        elif e["display"] != word and not is_inflected(e):
            rows[word] = row(word, "PROPN", f"{e['display']}, a name used in the Nagamese New Testament.", [e["display"]],
                             ["religion"], "Biblical name" if e["frequency_count"] and not e["keyboard_weight"] else "Proper name",
                             features=["proper name"], register=e["register"], example=example, display=e["display"],
                             method="name list", confidence=0.8)
        elif not is_inflected(e) and len(word) >= 3 and (
            e["listed_as_english"] or (not e["frequency_count"] and len(word) >= 5 and english_count(word) > 0)
        ):
            made = wordnet_row(word, domains=domains, example=example, register=e["register"])
            if made:
                rows[word] = made
    return rows


def inflected_rows(entries: list[dict], rows: dict[str, dict], examples: dict[str, str]) -> dict[str, dict]:
    """Rows for korise, manukhan, ghorte from their root's row (or its alignment gloss)."""
    made = {}
    for e in entries:
        word = e["lemma"]
        if word in rows or not is_inflected(e):
            continue
        root = e["root"]
        base = rows.get(root + "i") or rows.get(root)
        meaning = e["features"][0] if e["features"] else "inflected form"
        gloss = base["glosses"][0] if base else (e["gloss_candidates"][0]["english"] if e["gloss_candidates"] else root)
        base_name = base["word"] if base else root + "-"
        if "-khan" in e["morphology"]:
            definition, pos, features = f"Plural of {base_name} ('{gloss}').", "NOUN", ["plural"]
        elif "attached" in meaning:
            definition, pos, features = f"{base_name} with the postposition {meaning.split()[-1]} written attached ('{gloss}').", \
                (base["pos"] if base else "NOUN"), [meaning]
        else:
            definition, pos, features = f"{meaning.capitalize()} form of {base_name} ('{gloss}').", "VERB", [meaning]
        if e["pos_category"] not in ("Unknown", pos) and e["pos"]["confidence"] >= 0.6:
            pos = e["pos_category"]
        made[word] = row(word, pos, definition, [f"{gloss} ({meaning})"],
                         e["domains"] or (base["domains"] if base else [DEFAULT_DOMAIN.get(pos, "actions")]),
                         base["origin"] if base else "Nagamese", source_form=word, morphology=e["morphology"],
                         features=features, register=e["register"], example=examples.get(word, ""),
                         method="derived from root", confidence=0.7 if base else 0.5)
    return made


def split(value: str, sep: str) -> list[str]:
    return [part.strip() for part in value.split(sep) if part.strip()]


def parse_compact(path: Path, examples: dict[str, str], registers: dict[str, str]) -> tuple[list[dict], list[str]]:
    rows, errors = [], []
    english_list = path.name.startswith("english_")
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip() or line.startswith("#"):
            continue
        cols = [c.strip() for c in line.split("\t")]
        word = cols[0].lower()
        # Web boilerplate such as "share" or "login" is a real loanword when a research list
        # gives it; only grammar words are kept out.
        unwanted = word in HINDI_FUNCTION_WORDS or (word in ENGLISH_BOILERPLATE and len(word) <= 4 and word not in ("menu", "home"))
        if not WORD.match(word) or (unwanted and not path.name.startswith("enrich_")):
            errors.append(f"{path.name}:{number}: skipped {cols[0]!r}")
            continue
        if english_list:
            cols += [""] * (5 - len(cols))
            pos, gloss, domains, variants = cols[1].upper(), cols[2], split(cols[3], ","), split(cols[4].lower(), ",")
            made = wordnet_row(word, pos, gloss, domains, variants, examples.get(word, ""))
            if made is None:
                if pos not in UPOS or not gloss:
                    errors.append(f"{path.name}:{number}: {word!r} not in WordNet and no pos and gloss")
                    continue
                features = ["citation form"] if pos == "VERB" else ["base form", "countable", "inanimate"] if pos == "NOUN" else None
                made = row(word, pos, gloss[0].upper() + gloss[1:] + ".", [gloss], domains, "English loanword",
                           morphology="simple stem (no affix)" + (LIGHT_VERB if pos == "VERB" else ""), features=features,
                           variants=variants, example=examples.get(word, ""), confidence=0.5)
            rows.append(made)
            continue
        cols += [""] * (14 - len(cols))
        (_, pos, alts, definition, glosses, domains, origin, source_form, features, variants, example, confidence, note) = cols[:13]
        urls = [cols[13]] if cols[13].startswith("http") else []  # optional: the page the word was taken from
        alternatives = []
        for part in split(alts, "|"):
            tag, _, gloss = part.partition("=")
            if tag.strip().upper() in UPOS and gloss.strip():
                alternatives.append({"tag": tag.strip().upper(), "gloss": gloss.strip()})
        origin_name = ORIGIN_CODES.get(origin.upper(), origin)
        light = "kori" in definition.lower() or any("kori" in a["gloss"] for a in alternatives)
        try:
            confidence_value = min(max(float(confidence), 0.1), 1.0)
        except ValueError:
            confidence_value = 0.6
        domain_list = split(domains.lower(), ",")
        made = row(word, pos.upper(), definition, split(glosses, ";"), domain_list, origin_name,
                   alternatives=alternatives, source_form=source_form,
                   morphology="simple stem (no affix)" + (LIGHT_VERB if light and pos.upper() in ("VERB", "NOUN") else ""),
                   features=split(features.lower(), ","), variants=split(variants.lower(), ","),
                   register=registers.get(word) or ("slang" if "slang" in domain_list else "everyday"),
                   example=examples.get(word) if example in ("", "-") else example,
                   urls=urls, confidence=confidence_value, note=note if note.lower().startswith("doubtful") else "")
        problems = check_row(made)
        if problems:
            errors.append(f"{path.name}:{number} {word!r}: {'; '.join(problems)}")
            continue
        rows.append(made)
    return rows, errors


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


PATCH_FIELDS = {"pos", "english_definition", "glosses", "source_form"}


def apply_patches(rows: list[dict], errors: list[str], missing: set[str] | None = None) -> int:
    """Correct fields of finished rows from compact/patch_*.tsv, whose header names the fields.

    Used for reviews that only change some rows: a wrong WordNet sense, a source word in
    its native script. A corrected definition is marked model-annotated, and a changed
    part of speech also updates the features, morphology and plain example frame.
    """
    by_word = {r["word"]: r for r in rows}
    patched = 0
    for path in sorted(COMPACT_DIR.glob("patch_*.tsv")):
        lines = [l for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
        if not lines:
            continue
        header = [h.strip() for h in lines[0].split("	")]
        if header[0] != "word" or not set(header[1:]) <= PATCH_FIELDS:
            errors.append(f"{path.name}: header must be word plus some of {sorted(PATCH_FIELDS)}")
            continue
        for number, line in enumerate(lines[1:], start=2):
            values = dict(zip(header, (v.strip() for v in line.split("	"))))
            row = by_word.get(values.get("word", "").lower())
            if row is None:
                if missing is None:
                    errors.append(f"{path.name}:{number}: {values.get('word')!r} has no row to patch")
                else:
                    missing.add(values.get("word", "").lower())
                continue
            if missing is not None:
                missing.discard(row["word"])
            updated = dict(row)
            old_pos, old_frame = row["pos"], example_for(row["display"], row["pos"], row["features"])
            for field in header[1:]:
                value = values.get(field, "")
                if not value:
                    continue
                if field == "glosses":
                    updated["glosses"] = split(value, ";")[:4]
                elif field == "pos":
                    updated["pos"] = value.upper()
                else:
                    updated[field] = value
            if updated["pos"] != old_pos:
                pos = updated["pos"]
                if old_pos == "PROPN":
                    updated["display"] = row["word"]  # WordNet's name sense (Mobile, Alabama) no longer applies
                updated["pos_alternatives"] = [a for a in row["pos_alternatives"] if a["tag"] != pos]
                updated["features"] = {"VERB": ["citation form"], "ADJ": ["base form"], "PROPN": ["proper name"],
                                       "NOUN": ["base form", "countable", "inanimate"]}.get(pos, ["invariable"])
                if pos not in ("VERB", "NOUN"):
                    updated["morphology"] = updated["morphology"].replace(LIGHT_VERB, "")
                elif pos == "VERB" and LIGHT_VERB not in updated["morphology"] and updated["origin"] == "English loanword":
                    updated["morphology"] += LIGHT_VERB
                if row["usage_example"] == old_frame:
                    updated["usage_example"] = example_for(updated["display"], pos, updated["features"])
            if "english_definition" in values and values["english_definition"]:
                updated["method"] = "model-annotated"
            problems = check_row(updated)
            if problems:
                errors.append(f"{path.name}:{number} {row['word']!r}: {'; '.join(problems)}")
                continue
            row.clear()
            row.update(updated)
            patched += 1
    return patched


def expand() -> int:
    # The lexicon file leaves out words over their origin share; build every listed word first
    # (no derived forms, so no shares), or those words would come back as new ones.
    from nlp_research.lexicon.build_lexicon import build
    build(target=0)
    entries = lexicon_entries()
    listed = {e["lemma"] for e in entries}
    registers = {e["lemma"]: e["register"] for e in entries}
    examples = sentence_examples(listed)
    errors: list[str] = []
    enriched: dict[str, dict] = {}
    new_by_shard: dict[str, list[dict]] = defaultdict(list)
    taken_new: set[str] = set()
    lists = [path for path in COMPACT_DIR.glob("*.tsv") if not path.name.startswith("patch_")]
    for path in sorted(lists, key=lambda p: (not p.name.startswith("enrich_"), p.name)):
        rows, shard_errors = parse_compact(path, examples, registers)
        errors.extend(shard_errors)
        for r in rows:
            word = r["word"]
            if word in listed:
                enriched.setdefault(word, r)  # an enrich_ list first; a new_ list's row for a listed word fills a gap
            elif word not in taken_new:
                taken_new.add(word)
                new_by_shard[path.stem].append(r)
    automatic = auto_rows([e for e in entries if e["lemma"] not in enriched], examples)
    combined = {**automatic, **enriched}
    # Patch roots first, so inflected forms take the corrected meaning; then the inflected forms.
    unmatched: set[str] = set()
    patched = apply_patches([*combined.values(), *(r for rows in new_by_shard.values() for r in rows)], errors, unmatched)
    inflected = inflected_rows(entries, combined, examples)
    patched += apply_patches(list(inflected.values()), [], set())
    still = unmatched - set(inflected)
    errors.extend(f"patch: {w!r} has no row to patch" for w in sorted(still))
    for path in RESEARCH_DIR.glob("*.jsonl"):
        path.unlink()
    for shard, rows in new_by_shard.items():
        write_jsonl(RESEARCH_DIR / f"{shard if shard.startswith('new_') else 'new_' + shard}.jsonl", rows)
    write_jsonl(RESEARCH_DIR / "enrich_research.jsonl", list(enriched.values()))
    write_jsonl(RESEARCH_DIR / "enrich_auto.jsonl", list(automatic.values()) + list(inflected.values()))
    missing = sorted(listed - set(enriched) - set(automatic) - set(inflected))
    print(json.dumps({
        "new_words": {k: len(v) for k, v in new_by_shard.items()}, "new_total": len(taken_new),
        "enriched_by_research": len(enriched), "enriched_automatically": len(automatic),
        "inflected_from_root": len(inflected), "patched": patched, "listed_words_without_a_row": len(missing),
        "errors": len(errors),
        "errors_by_file": dict(Counter(e.split(":")[0] for e in errors)),
    }, indent=1))
    for error in errors[:40]:
        print("SKIPPED", error)
    if missing:
        print("without a row:", " ".join(missing[:60]))
    return 0


def worklist(out: Path, parts: int) -> int:
    entries = lexicon_entries()
    examples = sentence_examples({e["lemma"] for e in entries})
    automatic = auto_rows(entries, examples)
    todo = [e for e in entries if e["lemma"] not in automatic and not is_inflected(e)]
    out.mkdir(parents=True, exist_ok=True)
    size = -(-len(todo) // parts)
    for index in range(parts):
        lines = []
        for e in todo[index * size:(index + 1) * size]:
            context = examples.get(e["lemma"], "")
            lines.append("\t".join([
                e["lemma"], e["pos_category"], ";".join(g["english"] for g in e["gloss_candidates"][:2]),
                str(e["frequency_count"]), e["etymology_origin"], e["status"], context or "-",
            ]))
        (out / f"worklist_{index + 1}.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(entries)} listed, {len(automatic)} annotated automatically, "
          f"{sum(1 for e in entries if is_inflected(e))} inflected (from roots), {len(todo)} for research in {parts} parts")
    return 0


def check(path: Path) -> int:
    """Parse one compact list and report its rows and problems, for agents to fix their own file."""
    rows, errors = parse_compact(path, {}, {})
    listed = {e["lemma"] for e in lexicon_entries()}
    print(f"{path.name}: {len(rows)} good rows, {sum(r['word'] in listed for r in rows)} of them already in the lexicon, {len(errors)} problems")
    for error in errors[:60]:
        print("PROBLEM", error)
    return 1 if errors else 0


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    work = sub.add_parser("worklist")
    work.add_argument("--out", type=Path, required=True)
    work.add_argument("--parts", type=int, default=9)
    sub.add_parser("expand")
    one = sub.add_parser("check")
    one.add_argument("path", type=Path)
    args = parser.parse_args(argv)
    if args.command == "check":
        return check(args.path)
    return worklist(args.out, args.parts) if args.command == "worklist" else expand()


if __name__ == "__main__":
    raise SystemExit(main())
