"""
Text normalisation for matching, shared by retrieval, the translation memory and evaluation.

Nagamese has no enforced spelling standard, so matching uses a *folded* form that
merges common variants. The folded form is only ever used to compare texts; the
text shown to users is never rewritten.
"""

from __future__ import annotations

import re
import unicodedata

_PUNCTUATION_MAP = str.maketrans(
    {"’": "'", "‘": "'", "“": '"', "”": '"', "–": "-", "—": "-"}
)
_WORD = re.compile(r"[^\W_]+(?:'[^\W_]+)?")
_REPEATS = re.compile(r"(.)\1+")

# Spelling pairs listed in datasets/raw/vocabulary/additional_nagamese_vocab.txt,
# plus the contracted plural pronoun seen in the corpus (apnikhan / apuni khan).
NAGAMESE_VARIANTS = {"itu": "etu", "aro": "aru", "bhaal": "bhal", "hoy": "hoi", "apnikhan": "apunikhan"}


def clean(text: str) -> str:
    """Unicode NFC, straight quotes and single spaces. Safe for display."""
    text = unicodedata.normalize("NFC", text).translate(_PUNCTUATION_MAP)
    return " ".join(text.split())


def words(text: str) -> list[str]:
    return _WORD.findall(clean(text).lower())


def fold_nagamese(token: str) -> list[str]:
    """Map one Nagamese word to its matching form, possibly splitting a plural."""
    token = NAGAMESE_VARIANTS.get(token, token)
    # Sound clusters the Likhibi keyboard already treats as equivalent.
    token = token.replace("sh", "s").replace("ee", "i").replace("oo", "u").replace("v", "b")
    token = _REPEATS.sub(r"\1", token)
    # The plural marker is written both attached and apart: manukhan / manu khan.
    if token.endswith("khan") and len(token) > 6:
        return [token[: -len("khan")], "khan"]
    return [token]


def match_tokens(text: str, lang: str) -> list[str]:
    tokens = words(text)
    if lang == "nag":
        return [part for token in tokens for part in fold_nagamese(token)]
    return tokens


def match_key(text: str, lang: str) -> str:
    """A spelling-tolerant key: equal keys mean the same sentence for matching purposes."""
    return " ".join(match_tokens(text, lang))
