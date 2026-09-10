"""Template rendering with span-safe augmentation.

The problem this solves: §11 wants child-register text — dropped apostrophes, typos,
abbreviations, missing capitals — and §14's token head wants character offsets for every
entity. Those two requirements fight. Augmenting a rendered string and then trying to find
the address again is how a corpus quietly acquires misaligned labels, and a misaligned
label is invisible in the loss curve and fatal in the metrics.

So nothing ever augments a rendered string. A template renders to a list of `Chunk`s —
literal text, or an entity value that knows its own internal spans — and augmentation runs
per chunk. Literal chunks accept any transform. Entity chunks accept only transforms that
cannot move a boundary, plus the surface variation the lexicon already built in
("Oak Street" vs "Oak St" is chosen when the value is drawn, not patched in afterwards).
Offsets are computed once, at assembly, from chunk lengths that are already final.
"""

from __future__ import annotations

import random
import re
from dataclasses import dataclass, field

from generators.lexicon import SLOT_DISPATCH, Lexicon, Rendered
from keyguard_ml.labels import CONTEXT_LABELS

_SLOT = re.compile(r"\{([A-Z]+)(?::(\d+))?\}")


@dataclass
class Chunk:
    text: str
    spans: tuple[tuple[int, int, str], ...] = ()
    literal: bool = True


@dataclass
class Template:
    """One pattern plus the contextual truth it asserts.

    `family` is the holdout unit (§18). Every template that shares a family is either
    entirely in train or entirely in test, so a model cannot pass the test set by having
    memorised "I live at {ADDRESS}" and meeting "I live at {ADDRESS}" again with a
    different street name.
    """

    family: str
    pattern: str
    labels: dict[str, int]
    weight: float = 1.0
    tags: tuple[str, ...] = ()
    """Free-form markers used by error analysis to slice results — 'hard_negative',
    'combination', 'adversarial', 'ownership_ambiguity'."""

    def __post_init__(self) -> None:
        missing = [k for k in CONTEXT_LABELS if k not in self.labels]
        if missing:
            raise ValueError(f"template {self.family!r} missing labels {missing}")
        unknown = [k for k in self.labels if k not in CONTEXT_LABELS]
        if unknown:
            raise ValueError(f"template {self.family!r} has unknown labels {unknown}")
        for slot, _ in _SLOT.findall(self.pattern):
            if slot not in SLOT_DISPATCH:
                raise ValueError(f"template {self.family!r} uses unknown slot {{{slot}}}")


def render_chunks(template: Template, lex: Lexicon) -> list[Chunk]:
    """Expand `{SLOT}` placeholders into entity chunks, keeping literals separate.

    A numbered slot (`{TIME:1}`) reuses the value drawn for that number, so
    "from {TIME:1} to {TIME:2}" gets two different times while
    "{GUARDIAN:1} said {GUARDIAN:1} would be back" stays coherent.
    """
    chunks: list[Chunk] = []
    memo: dict[str, Rendered] = {}
    cursor = 0

    for match in _SLOT.finditer(template.pattern):
        if match.start() > cursor:
            chunks.append(Chunk(template.pattern[cursor : match.start()]))

        slot, index = match.group(1), match.group(2)
        key = f"{slot}:{index}" if index else f"{slot}:{len(chunks)}"
        if key in memo:
            value = memo[key]
        else:
            value = getattr(lex, SLOT_DISPATCH[slot])()
            memo[key] = value

        if value.parts:
            spans = value.parts
        elif value.entity:
            spans = ((0, len(value.text), value.entity),)
        else:
            spans = ()
        chunks.append(Chunk(value.text, spans=spans, literal=False))
        cursor = match.end()

    if cursor < len(template.pattern):
        chunks.append(Chunk(template.pattern[cursor:]))
    return chunks


def assemble(chunks: list[Chunk]) -> tuple[str, list[dict]]:
    """Join chunks and rebase every span onto the final string."""
    parts: list[str] = []
    spans: list[dict] = []
    offset = 0
    for chunk in chunks:
        parts.append(chunk.text)
        for start, end, label in chunk.spans:
            if label:
                spans.append(
                    {"start": offset + start, "end": offset + end, "label": label}
                )
        offset += len(chunk.text)
    text = "".join(parts)

    # Assembly can leave doubled spaces where a slot rendered empty. Collapsing them
    # would move offsets, so templates are written not to produce them and this asserts
    # rather than repairs.
    assert "  " not in text, f"double space in rendered text: {text!r}"
    return text, spans


def verify_spans(text: str, spans: list[dict]) -> None:
    """Cheap invariant check, run on every generated example.

    A generator bug that shifts spans by one character produces a corpus that trains and
    evaluates without complaint and is worthless. This is the only place that catches it.
    """
    for span in spans:
        start, end = span["start"], span["end"]
        if not (0 <= start < end <= len(text)):
            raise AssertionError(f"span {span} out of range for {text!r}")
        surface = text[start:end]
        if surface != surface.strip():
            raise AssertionError(f"span {span} has ragged whitespace: {surface!r}")
    ordered = sorted(spans, key=lambda s: s["start"])
    for a, b in zip(ordered, ordered[1:]):
        if a["end"] > b["start"]:
            raise AssertionError(f"overlapping spans {a} and {b} in {text!r}")


@dataclass
class GeneratedExample:
    text: str
    spans: list[dict]
    labels: dict[str, int]
    template_family: str
    tags: tuple[str, ...] = ()
    augmentations: tuple[str, ...] = field(default_factory=tuple)

    def as_dict(self) -> dict:
        return {
            "text": self.text,
            "spans": self.spans,
            "labels": self.labels,
            "template_family": self.template_family,
            "tags": list(self.tags),
            "augmentations": list(self.augmentations),
            "source": "synthetic",
        }
