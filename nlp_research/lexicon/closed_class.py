"""
Nagamese function words, hand-tagged with Universal POS tags and an English gloss.

Closed-class words are few but make up about half of running text, and an
automatic tagger handles them worst, so they are listed by hand. Tags follow
Universal Dependencies (https://universaldependencies.org/u/pos/). Every entry is
marked unverified in the lexicon until a speaker confirms it.
"""

from __future__ import annotations

# word: (UPOS, English gloss)
CLOSED_CLASS: dict[str, tuple[str, str]] = {
    # pronouns
    "moi": ("PRON", "I, me"),
    "ami": ("PRON", "we"),
    "amikhan": ("PRON", "we"),
    "apuni": ("PRON", "you (polite)"),
    "apunikhan": ("PRON", "you (plural, polite)"),
    "apnikhan": ("PRON", "you (plural, polite)"),
    "tumi": ("PRON", "you (familiar)"),
    "tumikhan": ("PRON", "you (plural)"),
    "tai": ("PRON", "he, she"),
    "taikhan": ("PRON", "they"),
    "etu": ("PRON", "this"),
    "itu": ("PRON", "this"),
    "utu": ("PRON", "that"),
    "kun": ("PRON", "who, which"),
    "ki": ("PRON", "what"),
    "kiba": ("PRON", "something"),
    "kunba": ("PRON", "someone, some"),
    "nijor": ("PRON", "own, oneself"),
    "khan": ("PART", "plural marker (written apart or attached: manu khan, manukhan)"),
    # postpositions, written apart or attached to the noun
    "laga": ("ADP", "of (possessive)"),
    "pora": ("ADP", "from, by"),
    "te": ("ADP", "in, at, to"),
    "ke": ("ADP", "to (object marker)"),
    "logot": ("ADP", "with"),
    "logote": ("ADP", "with"),
    "nimite": ("ADP", "for"),
    "karone": ("ADP", "because of, for"),
    "uporte": ("ADP", "on, above"),
    "bhitorte": ("ADP", "inside"),
    "agete": ("ADP", "before, in front of"),
    "pichete": ("ADP", "after, behind"),
    "majot": ("ADP", "among, in the middle of"),
    "majote": ("ADP", "among, in the middle of"),
    "tak": ("ADP", "until, up to"),
    "nisina": ("ADP", "like, as"),
    # conjunctions
    "aru": ("CCONJ", "and"),
    "aro": ("CCONJ", "and"),
    "kintu": ("CCONJ", "but"),
    "ba": ("CCONJ", "or"),
    "nohoile": ("CCONJ", "otherwise, or else"),
    "jodi": ("SCONJ", "if"),
    "hoile": ("SCONJ", "then, if so"),
    "kelemane": ("SCONJ", "because"),
    "jitia": ("SCONJ", "when (relative)"),
    "titia": ("ADV", "then"),
    "jineka": ("SCONJ", "as, the way that"),
    "eneka": ("ADV", "like this, so"),
    "teneka": ("ADV", "like that"),
    "kene": ("SCONJ", "having (done), and then"),
    "kena": ("SCONJ", "having (done), and then"),
    # particles and negation
    "na": ("PART", "not"),
    "nai": ("PART", "not, there is not"),
    "nohoi": ("PART", "is not, no"),
    "to": ("PART", "topic or emphasis particle"),
    "he": ("PART", "emphasis particle (indeed, only)"),
    "bi": ("PART", "also, even"),
    "bhi": ("PART", "also, even"),
    "naki": ("PART", "or (in questions), isn't it"),
    "koi": ("PART", "quotative: saying that"),
    # copula and auxiliaries
    "ase": ("AUX", "is, am, are (exists)"),
    "thake": ("AUX", "stays, is (habitually)"),
    "thakise": ("AUX", "was, stayed"),
    "thakibo": ("AUX", "will be, will stay"),
    "hoi": ("AUX", "is, becomes; yes"),
    "hoise": ("AUX", "became, happened"),
    "hobo": ("AUX", "will be, will happen"),
    "pare": ("AUX", "can"),
    "napare": ("AUX", "cannot"),
    "lage": ("AUX", "must, is needed"),
    # determiners and numbers
    "ek": ("NUM", "one"),
    "ekta": ("DET", "one, a"),
    "ekjon": ("DET", "one (person), someone"),
    "dui": ("NUM", "two"),
    "duijon": ("NUM", "two (people)"),
    "tin": ("NUM", "three"),
    "char": ("NUM", "four"),
    "panch": ("NUM", "five"),
    "sob": ("DET", "all"),
    "bisi": ("DET", "many, much, very"),
    "olop": ("DET", "a little, few"),
    "kisu": ("DET", "some"),
    "eku": ("DET", "any (nothing, with a negative)"),
    "dusra": ("DET", "other, another"),
    # adverbs
    "etiya": ("ADV", "now"),
    "aji": ("ADV", "today"),
    "kali": ("ADV", "tomorrow, yesterday"),
    "hodai": ("ADV", "always"),
    "abar": ("ADV", "again"),
    "khali": ("ADV", "only, just"),
    "thik": ("ADV", "right, correctly, okay"),
    "kitia": ("ADV", "when (question)"),
    "kot": ("ADV", "where"),
    "kote": ("ADV", "where"),
    "kineka": ("ADV", "how"),
    "kiman": ("ADV", "how much, how many"),
    "kilekoile": ("ADV", "why"),
    "ta": ("ADV", "there"),
    "itate": ("ADV", "here"),
    # interjections
    "dhanyavad": ("INTJ", "thank you"),
    "amen": ("INTJ", "amen"),
}

# Nagaland's districts and recognised Naga tribes, with the capitalisation the keyboard
# should write. Names are facts, not anyone's text; the list is for spelling only.
# Mon district is left out: "mon" (mind, heart) is a common word and must stay lowercase.
NAGA_PROPER_NOUNS: dict[str, tuple[str, str]] = {
    "nagaland": ("Nagaland", "places"), "kohima": ("Kohima", "places"), "dimapur": ("Dimapur", "places"),
    "chumoukedima": ("Chümoukedima", "places"), "mokokchung": ("Mokokchung", "places"),
    "tuensang": ("Tuensang", "places"), "wokha": ("Wokha", "places"),
    "zunheboto": ("Zunheboto", "places"), "phek": ("Phek", "places"), "kiphire": ("Kiphire", "places"),
    "longleng": ("Longleng", "places"), "peren": ("Peren", "places"), "noklak": ("Noklak", "places"),
    "niuland": ("Niuland", "places"), "shamator": ("Shamator", "places"), "tseminyu": ("Tseminyü", "places"),
    "meluri": ("Meluri", "places"),
    "naga": ("Naga", "culture"), "ao": ("Ao", "culture"), "angami": ("Angami", "culture"),
    "sumi": ("Sümi", "culture"), "lotha": ("Lotha", "culture"), "konyak": ("Konyak", "culture"),
    "chakhesang": ("Chakhesang", "culture"), "chang": ("Chang", "culture"),
    "khiamniungan": ("Khiamniungan", "culture"), "phom": ("Phom", "culture"), "pochury": ("Pochury", "culture"),
    "rengma": ("Rengma", "culture"), "sangtam": ("Sangtam", "culture"), "yimkhiung": ("Yimkhiung", "culture"),
    "zeliang": ("Zeliang", "culture"), "tikhir": ("Tikhir", "culture"), "kachari": ("Kachari", "culture"),
    "kuki": ("Kuki", "culture"), "garo": ("Garo", "culture"), "hornbill": ("Hornbill", "culture"),
    "nagamese": ("Nagamese", "culture"),
}

# Hindi function words that turned up in earlier web-collected lists. They are not
# Nagamese, so unless real Nagamese text uses them they are rejected.
HINDI_FUNCTION_WORDS = {
    "hai", "hain", "tha", "thi", "mein", "mai", "ka", "ko", "se", "ne", "aur", "nahi", "nahin",
    "kya", "kyun", "yeh", "woh", "wo", "hum", "tum", "aap", "mera", "tera", "uska", "hamara",
    "padhna", "karna", "jana", "aana", "khana", "peena", "rehna",
}

# English words that leaked in from web page boilerplate in the earlier collection.
ENGLISH_BOILERPLATE = {
    "a", "an", "the", "of", "and", "or", "in", "on", "at", "for", "with", "by", "is", "are", "was",
    "were", "be", "it", "this", "that", "from", "as", "copyright", "rights", "reserved", "privacy",
    "policy", "terms", "login", "signup", "click", "here", "menu", "home", "search", "share",
    "comments", "reply", "posted", "views", "subscribe", "cookie", "cookies",
}
