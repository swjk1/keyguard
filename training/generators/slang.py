"""§11 — child-register surface augmentation.

Two rules govern everything here.

**Never change what the sentence means.** These transforms alter spelling, casing, and
punctuation. Any transform that could flip a context label — dropping "not", turning
"my mom's" into "my" — belongs in a template with different labels, not in an augmenter,
because the labels are attached to the template and the augmenter cannot revise them.

**Length-changing transforms touch literal chunks only.** An entity chunk is a labelled
span; shortening it from the inside moves its own end offset, and every downstream check
would still pass because the chunk owns its offsets — but the *value* would then be a
corrupted address rather than an abbreviated one. Entity surface variation happens in the
lexicon at draw time instead, where "Oak Street" and "Oak St" are two legitimate values of
the same slot.
"""

from __future__ import annotations

import random
import re

from generators.render import Chunk

# --------------------------------------------------------------------------------------
# Word-level substitutions
# --------------------------------------------------------------------------------------

# Meaning-preserving only. Note what is absent: no "cant" -> "can" style truncations, and
# nothing that touches a negation.
SLANG_MAP: dict[str, list[str]] = {
    "i am": ["im", "i'm", "iam"],
    "i'm": ["im", "i am"],
    "you are": ["ur", "youre", "you're"],
    "you're": ["ur", "youre"],
    "your": ["ur"],
    "are": ["r"],
    "you": ["u", "yu"],
    "be": ["b"],
    "to": ["2", "to"],
    "too": ["2", "to"],
    "for": ["4", "fr"],
    "before": ["b4"],
    "please": ["pls", "plz"],
    "thanks": ["thx", "ty", "tysm"],
    "because": ["bc", "cuz", "cus", "bcs"],
    "about": ["abt", "bout"],
    "right now": ["rn", "rite now"],
    "tonight": ["2nite", "tn"],
    "tomorrow": ["tmrw", "tmr", "2moro"],
    "today": ["2day", "tdy"],
    "until": ["til", "till", "untill", "til like"],
    "till": ["til", "until"],
    "with": ["w", "wit", "w/"],
    "without": ["w/o"],
    "and": ["n", "&", "nd"],
    "at": ["@"],
    "my": ["my", "me"],
    "parents": ["rents", "parents", "folks", "parnts"],
    "mother": ["mom", "mum"],
    "father": ["dad"],
    "grandmother": ["grandma", "gramma", "nana"],
    "address": ["addy", "adress", "addres"],
    "apartment": ["apt", "appartment"],
    "school": ["skool", "school", "schl"],
    "practice": ["practise", "prac", "practice"],
    "probably": ["prob", "prolly", "probs"],
    "definitely": ["def", "definately"],
    "see you": ["cya", "cu", "syu"],
    "okay": ["ok", "k", "kk", "okok"],
    "yes": ["ya", "yea", "yeah", "yep", "ye"],
    "no one": ["noone", "no1", "nobody"],
    "nobody": ["no one", "nobody", "no1"],
    "going to": ["gonna", "gna", "gunna"],
    "want to": ["wanna", "wana"],
    "got to": ["gotta"],
    "kind of": ["kinda"],
    "something": ["smth", "sumthin", "somethin"],
    "nothing": ["nothin", "nuthin"],
    "really": ["rly", "rlly", "reely"],
    "should": ["shud", "shld"],
    "would": ["wud", "wld"],
    "come over": ["come ovr", "cme over", "come over"],
    "house": ["hous", "crib", "place", "house"],
}

# Appended, not substituted, so they cannot disturb anything before them.
TRAILERS = [
    " lol", " lmao", " haha", " ok", " ??", " !!", " ...", " :)", " :D", " 😭", " 😂",
    " 🙃", " idk", " tho", " fr", " ngl", " btw", " pls", " 👀", " 💀",
]

LEADERS = ["ok so ", "wait ", "yo ", "hey ", "bro ", "omg ", "so ", "uh ", "lmao ",
           "btw ", "guys ", "ok ", "hii ", "anyway "]

# Adjacent-key slips on a QWERTY phone keyboard. Real typos cluster; random letter
# substitution produces strings no thumb ever generated.
_QWERTY_NEIGHBOURS = {
    "q": "wa", "w": "qes", "e": "wrd", "r": "etf", "t": "ryg", "y": "tuh", "u": "yij",
    "i": "uok", "o": "ipl", "p": "ol", "a": "qsz", "s": "awdx", "d": "sefc",
    "f": "drgv", "g": "fthb", "h": "gyjn", "j": "hukm", "k": "jil", "l": "kop",
    "z": "asx", "x": "zsdc", "c": "xdfv", "v": "cfgb", "b": "vghn", "n": "bhjm",
    "m": "njk",
}


def _substitute_slang(text: str, rng: random.Random, rate: float) -> str:
    out = text
    # Longest first, so "right now" is matched before "now" would be.
    for phrase in sorted(SLANG_MAP, key=len, reverse=True):
        if rng.random() >= rate:
            continue
        pattern = re.compile(rf"(?<![\w']){re.escape(phrase)}(?![\w'])", re.IGNORECASE)
        if not pattern.search(out):
            continue
        out = pattern.sub(lambda _m: rng.choice(SLANG_MAP[phrase]), out, count=1)
    return out


def _drop_apostrophes(text: str, rng: random.Random) -> str:
    return re.sub(r"(?<=\w)'(?=\w)", "", text)


def _lowercase(text: str) -> str:
    return text.lower()


def _shout_case(text: str, rng: random.Random) -> str:
    return "".join(c.upper() if rng.random() < 0.5 else c for c in text)


def _strip_end_punct(text: str) -> str:
    return re.sub(r"[.!?]+\s*$", "", text)


def _typo(text: str, rng: random.Random, rate: float) -> str:
    chars = list(text)
    out: list[str] = []
    i = 0
    while i < len(chars):
        ch = chars[i]
        if ch.isalpha() and rng.random() < rate:
            mode = rng.random()
            low = ch.lower()
            if mode < 0.4 and low in _QWERTY_NEIGHBOURS:
                repl = rng.choice(_QWERTY_NEIGHBOURS[low])
                out.append(repl.upper() if ch.isupper() else repl)
            elif mode < 0.65:
                out.append(ch)
                out.append(ch)  # doubled letter
            elif mode < 0.85 and i + 1 < len(chars) and chars[i + 1].isalpha():
                out.append(chars[i + 1])  # transposition
                out.append(ch)
                i += 2
                continue
            # else: dropped letter — append nothing
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def _collapse_spaces(text: str) -> str:
    return re.sub(r" {2,}", " ", text)


# --------------------------------------------------------------------------------------
# Public API
# --------------------------------------------------------------------------------------


def augment(
    chunks: list[Chunk],
    rng: random.Random,
    *,
    intensity: float = 1.0,
) -> tuple[list[Chunk], tuple[str, ...]]:
    """Return augmented chunks and the names of the transforms that fired.

    The names are recorded on the example so error analysis can ask "are the false
    negatives concentrated in the heavy-typo slice?" — which is a different fix from
    "the model does not understand guardian absence".
    """
    applied: list[str] = []
    out = [Chunk(c.text, c.spans, c.literal) for c in chunks]

    # -- length-changing: literal chunks only -------------------------------------
    if rng.random() < 0.55 * intensity:
        applied.append("slang")
        rate = rng.choice([0.3, 0.5, 0.8])
        for c in out:
            if c.literal:
                c.text = _substitute_slang(c.text, rng, rate)

    if rng.random() < 0.45 * intensity:
        applied.append("no_apostrophes")
        for c in out:
            if c.literal:
                c.text = _drop_apostrophes(c.text, rng)

    if rng.random() < 0.18 * intensity:
        rate = rng.choice([0.02, 0.04, 0.08])
        applied.append(f"typo:{rate}")
        for c in out:
            if c.literal:
                c.text = _typo(c.text, rng, rate)

    if rng.random() < 0.5 * intensity:
        applied.append("no_end_punct")
        for i in range(len(out) - 1, -1, -1):
            if out[i].literal and out[i].text.strip():
                out[i].text = _strip_end_punct(out[i].text)
                break

    # -- length-preserving: safe on every chunk -----------------------------------
    roll = rng.random()
    if roll < 0.62 * intensity:
        applied.append("lowercase")
        for c in out:
            c.text = _lowercase(c.text)
    elif roll < 0.66 * intensity:
        applied.append("shout")
        for c in out:
            c.text = _shout_case(c.text, rng)

    # -- boundary additions: cannot disturb interior offsets ----------------------
    if rng.random() < 0.2 * intensity:
        applied.append("leader")
        out.insert(0, Chunk(rng.choice(LEADERS)))

    if rng.random() < 0.3 * intensity:
        applied.append("trailer")
        out.append(Chunk(rng.choice(TRAILERS)))

    # Slang substitution can leave a double space where "with" became "w/" beside a
    # comma. Collapsing inside a literal chunk is safe; the assert in `assemble` is the
    # backstop for anything this misses.
    for c in out:
        if c.literal:
            c.text = _collapse_spaces(c.text)

    # A double space can also straddle a boundary — literal chunk ending in a space
    # followed by a literal chunk starting with one. Entity chunks never begin or end
    # with whitespace, so only literal-literal pairs need this.
    for i in range(len(out) - 1):
        if out[i].literal and out[i + 1].literal and out[i].text.endswith(" "):
            out[i + 1].text = out[i + 1].text.lstrip(" ")

    # Strip a leading space that appears when the first literal chunk lost a word.
    if out and out[0].literal:
        out[0].text = out[0].text.lstrip()
    if out and out[-1].literal:
        out[-1].text = out[-1].text.rstrip()

    return out, tuple(applied)
