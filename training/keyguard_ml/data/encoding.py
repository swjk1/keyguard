"""Turning JSONL examples into model tensors.

One encoder, two supervision shapes:

* a PII example carries character spans and supervises the token head;
* a context example carries eight binary labels and supervises the sequence head;
* a synthetic safety example carries *both*, because the generator knows where it put the
  address as well as what the sentence means.

Both shapes go through `encode_example`, and whichever supervision is absent is filled
with the ignore sentinel. That lets a single collate function build mixed batches for
phase C without the training loop branching on example type.

Wordpiece alignment: the first subword of an entity gets `B-`, every continuation gets
`I-`.

An earlier version left continuations as IGNORE_INDEX, on the argument that labelling
them inflates token-level metrics — a nine-wordpiece email address becoming nine easy
correct predictions. That argument is real but it does not apply here, because the
headline metric is entity-level (`metrics.entity_report`), and the cost of the
IGNORE scheme turned out to be severe: with no supervision on continuations the model
has no way to express where an entity *ends*, so it emits `B-` on every subword.
"604-555-0182" decoded as three separate TELEPHONENUM spans and "24 oak street" as two.
The risk engine survived that — it only asks whether a `contact_info` is present — but
the UI underlines the span, and a fragmented underline is a visible defect.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator, Sequence

import torch

from keyguard_ml.labels import (
    CONTEXT_LABELS,
    IGNORE_INDEX,
    NUM_CONTEXT_LABELS,
    PII_TAG_TO_ID,
)

# Sentinel for "this example has no context supervision". BCEWithLogitsLoss has no
# ignore_index, so the mask is carried alongside the targets instead.
NO_CONTEXT = -1.0


@dataclass
class RawExample:
    text: str
    spans: list[dict]
    """Each: {start, end, label} with char offsets into `text`."""
    context: dict[str, int] | None
    """None when the example carries no contextual supervision."""
    template_family: str = ""
    """Used by the splitter to hold whole families out; empty for OpenPII."""
    source: str = ""
    uid: str = ""
    tags: list[str] = field(default_factory=list)
    """What this example is testing — 'ownership', 'idiom', 'abbreviation', and so on.
    §24's error analysis buckets failures by these, so a failing bucket names something
    a generator can produce more of. Dropping them here would make that report empty
    without making it look empty."""
    group: str = ""

    @staticmethod
    def from_dict(row: dict) -> "RawExample":
        return RawExample(
            text=row["text"],
            spans=list(row.get("spans") or []),
            context=row.get("labels") or row.get("context"),
            template_family=row.get("template_family", ""),
            source=row.get("source", ""),
            uid=str(row.get("uid", "")),
            tags=list(row.get("tags") or []),
            group=row.get("group", ""),
        )


def read_jsonl(path: str | Path) -> list[RawExample]:
    out: list[RawExample] = []
    with Path(path).open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                out.append(RawExample.from_dict(json.loads(line)))
    return out


def iter_jsonl(path: str | Path) -> Iterator[RawExample]:
    with Path(path).open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                yield RawExample.from_dict(json.loads(line))


def align_spans_to_tokens(
    offsets: Sequence[tuple[int, int]],
    special_tokens_mask: Sequence[int],
    spans: Sequence[dict],
    *,
    supervise: bool,
) -> list[int]:
    """Produce one BIO id per token position.

    `supervise=False` returns all-IGNORE, for a context-only example whose token labels
    are unknown rather than known-to-be-O. Training on "unknown means O" would teach the
    model that child-chat text never contains an address, which is precisely backwards.
    """
    n = len(offsets)
    if not supervise:
        return [IGNORE_INDEX] * n

    labels = [IGNORE_INDEX] * n
    # Every real token starts as O; specials and padding stay ignored.
    for i, is_special in enumerate(special_tokens_mask):
        if not is_special and offsets[i][1] > offsets[i][0]:
            labels[i] = PII_TAG_TO_ID["O"]

    for span in spans:
        start, end, label = int(span["start"]), int(span["end"]), span["label"]
        b_id = PII_TAG_TO_ID.get(f"B-{label}")
        i_id = PII_TAG_TO_ID.get(f"I-{label}")
        if b_id is None or i_id is None:
            continue

        first = True
        for i, (tok_start, tok_end) in enumerate(offsets):
            if special_tokens_mask[i] or tok_end <= tok_start:
                continue
            # Overlap, not containment: a tokenizer may merge a trailing period into the
            # last piece of "St." and a containment test would then miss it.
            if tok_start < end and start < tok_end:
                labels[i] = b_id if first else i_id
                first = False
    return labels


def context_vector(context: dict[str, int] | None) -> tuple[list[float], float]:
    """(targets, mask) — mask is 1.0 when the example supervises the context head."""
    if context is None:
        return [0.0] * NUM_CONTEXT_LABELS, 0.0
    missing = [k for k in CONTEXT_LABELS if k not in context]
    if missing:
        raise ValueError(f"context example missing labels: {missing}")
    unknown = [k for k in context if k not in CONTEXT_LABELS]
    if unknown:
        raise ValueError(f"context example has unknown labels: {unknown}")
    return [float(context[k]) for k in CONTEXT_LABELS], 1.0


@dataclass
class EncodedExample:
    input_ids: list[int]
    attention_mask: list[int]
    token_labels: list[int]
    context_targets: list[float]
    context_mask: float


def encode_example(tokenizer, example: RawExample, max_length: int) -> EncodedExample:
    enc = tokenizer(
        example.text,
        truncation=True,
        max_length=max_length,
        return_offsets_mapping=True,
        return_special_tokens_mask=True,
    )
    # A PII example with zero spans is still supervision — it says "no entities here".
    # A context example whose `spans` key is absent supervises nothing. The two are
    # distinguished by `source`, which the generators always set.
    supervise_tokens = example.source in ("openpii", "synthetic") or bool(example.spans)
    token_labels = align_spans_to_tokens(
        enc["offset_mapping"],
        enc["special_tokens_mask"],
        example.spans,
        supervise=supervise_tokens,
    )
    targets, mask = context_vector(example.context)
    return EncodedExample(
        input_ids=enc["input_ids"],
        attention_mask=enc["attention_mask"],
        token_labels=token_labels,
        context_targets=targets,
        context_mask=mask,
    )


class MultiTaskDataset(torch.utils.data.Dataset):
    """Lazily encodes; keeps only the raw text in memory.

    The OpenPII slice is ~60k short strings, so holding raw text costs a few megabytes
    while holding pre-tokenised tensors for the same rows costs a few hundred. On a rented
    box the difference is a larger batch size.
    """

    def __init__(self, examples: Sequence[RawExample], tokenizer, max_length: int = 128):
        self.examples = list(examples)
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self) -> int:
        return len(self.examples)

    def __getitem__(self, idx: int) -> EncodedExample:
        return encode_example(self.tokenizer, self.examples[idx], self.max_length)


@dataclass
class Collator:
    pad_token_id: int

    def __call__(self, batch: Sequence[EncodedExample]) -> dict[str, torch.Tensor]:
        width = max(len(b.input_ids) for b in batch)
        input_ids, attn, token_labels = [], [], []
        for b in batch:
            pad = width - len(b.input_ids)
            input_ids.append(b.input_ids + [self.pad_token_id] * pad)
            attn.append(b.attention_mask + [0] * pad)
            token_labels.append(b.token_labels + [IGNORE_INDEX] * pad)

        return {
            "input_ids": torch.tensor(input_ids, dtype=torch.long),
            "attention_mask": torch.tensor(attn, dtype=torch.long),
            "token_labels": torch.tensor(token_labels, dtype=torch.long),
            "context_targets": torch.tensor(
                [b.context_targets for b in batch], dtype=torch.float
            ),
            "context_mask": torch.tensor([b.context_mask for b in batch], dtype=torch.float),
        }
