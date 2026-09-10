"""Milestone 1 — OpenPII → relevant English entities → a compact, reproducible corpus.

Reads `ai4privacy/pii-masking-openpii-1.5m` (1,636,375 rows, 19 labels, 30 languages,
CC-BY-4.0), keeps English, maps its entity names onto ours, drops the entities V1 does not
want, and writes JSONL that the tokenizing dataset in `pii_dataset.py` can consume.

Three things here are not in the design doc and are worth knowing about:

**Windowing.** OpenPII sentences are formal administrative prose and run long — "The
workshop scheduled on 1995-01-08T00:00:00 will be led by ...". The phone sees short chat
messages inside a 64–128 token window. Training the encoder on 300-character documents
teaches it a length and register distribution it will never meet at inference. So long
records are cut into span-preserving windows around their annotations, and the offsets are
rebased. This is lossy in the sense that cross-window context disappears, which is exactly
the context the runtime does not have either.

**Prioritised sampling.** Section 4 asks for the location/contact/date entities to be
prioritised. Rather than filtering the others out — which would teach the model that a
sentence containing a passport number contains an address, since it never saw a
counter-example — records are *weighted*: a record carrying a wanted entity is always kept,
one carrying only unwanted entities is kept at a low rate as negative ballast, with the
unwanted spans collapsed to O.

**A manifest.** Every run writes `manifest.json` beside its output: dataset revision, seed,
filter settings, and the observed label histogram. "OpenPII-derived PII training is
reproducible" (§34) is only true if the sampling decisions are recorded, since they are
random.
"""

from __future__ import annotations

import argparse
import json
import random
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator

from keyguard_ml.labels import (
    OPENPII_OFFICIAL_ENTITIES,
    PII_ENTITIES,
    normalize_openpii_entity,
)

DEFAULT_REPO = "ai4privacy/pii-masking-openpii-1.5m"

# Section 4's priority list. A record containing any of these is always kept.
PRIORITY_ENTITIES: frozenset[str] = frozenset(
    {"STREET", "BUILDINGNUM", "CITY", "ZIPCODE", "EMAIL", "TELEPHONENUM", "DATE"}
)

_SENTENCE_BREAK = re.compile(r"(?<=[.!?])\s+")


@dataclass
class Span:
    start: int
    end: int
    label: str

    def as_dict(self) -> dict:
        return {"start": self.start, "end": self.end, "label": self.label}


@dataclass
class Example:
    text: str
    spans: list[Span]
    uid: str
    region: str

    def as_dict(self) -> dict:
        return {
            "text": self.text,
            "spans": [s.as_dict() for s in self.spans],
            "uid": self.uid,
            "region": self.region,
            "source": "openpii",
        }


@dataclass
class PreprocessConfig:
    repo: str = DEFAULT_REPO
    split: str = "train"
    language: str = "en"
    target: int = 60_000
    max_chars: int = 320
    """Records longer than this are windowed. ~320 chars is roughly 96 wordpieces of
    English prose, which sits inside the 128-token runtime ceiling with headroom."""
    keep_unwanted_only_rate: float = 0.05
    """Probability of keeping a record whose only annotations are entities V1 drops. These
    become all-O ballast so the model learns that not every formal sentence hides an
    address."""
    drop_unannotated: bool = False
    """OpenPII rows always carry at least one span, but windowing can produce empty ones."""
    seed: int = 20260909
    max_scanned: int | None = None
    """Stop after scanning this many source rows. Useful for smoke runs."""


@dataclass
class PreprocessStats:
    scanned: int = 0
    wrong_language: int = 0
    kept: int = 0
    windows_emitted: int = 0
    dropped_unwanted_only: int = 0
    dropped_empty: int = 0
    label_counts: Counter = field(default_factory=Counter)
    dropped_label_counts: Counter = field(default_factory=Counter)
    unknown_labels: Counter = field(default_factory=Counter)
    length_histogram: Counter = field(default_factory=Counter)

    def as_dict(self) -> dict:
        return {
            "scanned": self.scanned,
            "wrong_language": self.wrong_language,
            "kept": self.kept,
            "windows_emitted": self.windows_emitted,
            "dropped_unwanted_only": self.dropped_unwanted_only,
            "dropped_empty": self.dropped_empty,
            "label_counts": dict(self.label_counts.most_common()),
            "dropped_label_counts": dict(self.dropped_label_counts.most_common()),
            "unknown_labels": dict(self.unknown_labels.most_common()),
            "length_histogram": dict(sorted(self.length_histogram.items())),
        }


# --------------------------------------------------------------------------------------
# Span handling
# --------------------------------------------------------------------------------------


def _clean_spans(raw_mask: Iterable[dict], text: str, stats: PreprocessStats) -> tuple[list[Span], bool]:
    """Map raw `privacy_mask` entries onto our entities.

    Returns the surviving spans and whether the record carried any annotation at all
    (including ones we dropped) — the caller needs the difference to distinguish "no PII"
    from "only PII we don't want".
    """
    spans: list[Span] = []
    had_any = False

    for item in raw_mask:
        raw_label = str(item.get("label", "")).strip().upper()
        if not raw_label:
            continue
        had_any = True

        bare = raw_label[2:] if len(raw_label) > 2 and raw_label[1] == "-" else raw_label
        if bare not in OPENPII_OFFICIAL_ENTITIES:
            stats.unknown_labels[bare] += 1

        mapped = normalize_openpii_entity(raw_label)
        if mapped is None or mapped not in PII_ENTITIES:
            stats.dropped_label_counts[bare] += 1
            continue

        start, end = int(item["start"]), int(item["end"])
        if not (0 <= start < end <= len(text)):
            # A span that does not index the text it annotates is a corrupt row, not a
            # span to salvage. Count it as unknown so it shows up in the manifest.
            stats.unknown_labels[f"{bare}:bad_offsets"] += 1
            continue
        spans.append(Span(start, end, mapped))

    spans.sort(key=lambda s: (s.start, s.end))
    return _drop_overlaps(spans), had_any


def _drop_overlaps(spans: list[Span]) -> list[Span]:
    """Keep the longest span when two annotations overlap.

    BIO tagging cannot represent nesting, and OpenPII occasionally emits a GIVENNAME
    inside a longer full-name STREET-adjacent span. Silently letting the second overwrite
    the first would depend on iteration order.
    """
    kept: list[Span] = []
    for span in sorted(spans, key=lambda s: (-(s.end - s.start), s.start)):
        if any(span.start < k.end and k.start < span.end for k in kept):
            continue
        kept.append(span)
    return sorted(kept, key=lambda s: s.start)


def window_example(text: str, spans: list[Span], max_chars: int) -> Iterator[tuple[str, list[Span]]]:
    """Cut `text` into <= max_chars pieces on sentence boundaries, rebasing spans.

    A sentence longer than max_chars is emitted whole rather than split mid-span; the
    tokenizer's own truncation is a safer place to lose the tail than an arbitrary
    character cut that could bisect an address.
    """
    if len(text) <= max_chars:
        yield text, spans
        return

    pieces: list[tuple[int, str]] = []
    cursor = 0
    for sentence in _SENTENCE_BREAK.split(text):
        idx = text.find(sentence, cursor)
        if idx < 0:
            idx = cursor
        pieces.append((idx, sentence))
        cursor = idx + len(sentence)

    buf_start: int | None = None
    buf_end = 0
    for start, sentence in pieces:
        end = start + len(sentence)
        if buf_start is None:
            buf_start, buf_end = start, end
        elif end - buf_start <= max_chars:
            buf_end = end
        else:
            yield _slice(text, spans, buf_start, buf_end)
            buf_start, buf_end = start, end
    if buf_start is not None:
        yield _slice(text, spans, buf_start, buf_end)


def _slice(text: str, spans: list[Span], start: int, end: int) -> tuple[str, list[Span]]:
    # Only spans wholly inside the window survive. A span clipped at a window edge would
    # be a half-address labelled as a whole one — worse than no supervision.
    inner = [
        Span(s.start - start, s.end - start, s.label)
        for s in spans
        if s.start >= start and s.end <= end
    ]
    return text[start:end].strip(), inner


# --------------------------------------------------------------------------------------
# Row iteration
# --------------------------------------------------------------------------------------


def iter_rows(cfg: PreprocessConfig, local_jsonl: Path | None = None) -> Iterator[dict]:
    """Yield raw OpenPII rows, from a local JSONL if given, else streamed from the Hub.

    Streaming matters on a rented GPU box: the full train split is several gigabytes and
    there is no reason to pay for the disk or the wait when the pipeline only keeps the
    English slice.
    """
    if local_jsonl is not None:
        with local_jsonl.open("r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    yield json.loads(line)
        return

    from datasets import load_dataset

    stream = load_dataset(cfg.repo, split=cfg.split, streaming=True)
    yield from stream


def preprocess(
    cfg: PreprocessConfig,
    out_path: Path,
    local_jsonl: Path | None = None,
) -> PreprocessStats:
    rng = random.Random(cfg.seed)
    stats = PreprocessStats()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with out_path.open("w", encoding="utf-8") as out:
        for row in iter_rows(cfg, local_jsonl):
            stats.scanned += 1
            if cfg.max_scanned is not None and stats.scanned > cfg.max_scanned:
                stats.scanned -= 1
                break

            if cfg.language and str(row.get("language", "")).lower() != cfg.language:
                stats.wrong_language += 1
                continue

            text = row.get("source_text") or ""
            if not text.strip():
                continue

            spans, had_any = _clean_spans(row.get("privacy_mask") or [], text, stats)

            if not spans:
                # Nothing we care about. Keep a trickle as all-O ballast.
                if had_any and rng.random() >= cfg.keep_unwanted_only_rate:
                    stats.dropped_unwanted_only += 1
                    continue
                if not had_any and rng.random() >= cfg.keep_unwanted_only_rate:
                    continue

            wanted = {s.label for s in spans} & PRIORITY_ENTITIES
            if spans and not wanted and rng.random() >= 0.35:
                # Carries only non-priority entities we still model (names, SOCIALNUM).
                # Thinned rather than dropped so those classes stay learnable.
                stats.dropped_unwanted_only += 1
                continue

            uid = str(row.get("uid", ""))
            region = str(row.get("region", ""))

            for piece, piece_spans in window_example(text, spans, cfg.max_chars):
                if not piece.strip():
                    continue
                if cfg.drop_unannotated and not piece_spans:
                    stats.dropped_empty += 1
                    continue
                example = Example(piece, piece_spans, uid, region)
                out.write(json.dumps(example.as_dict(), ensure_ascii=False) + "\n")
                stats.windows_emitted += 1
                for span in piece_spans:
                    stats.label_counts[span.label] += 1
                stats.length_histogram[min(len(piece) // 64 * 64, 512)] += 1

            stats.kept += 1
            if stats.windows_emitted >= cfg.target:
                break

    return stats


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--split", default="train")
    parser.add_argument("--language", default="en")
    parser.add_argument("--target", type=int, default=60_000)
    parser.add_argument("--max-chars", type=int, default=320)
    parser.add_argument("--seed", type=int, default=20260909)
    parser.add_argument("--max-scanned", type=int, default=None)
    parser.add_argument("--local-jsonl", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=Path("datasets/openpii/train.jsonl"))
    args = parser.parse_args()

    cfg = PreprocessConfig(
        repo=args.repo,
        split=args.split,
        language=args.language,
        target=args.target,
        max_chars=args.max_chars,
        seed=args.seed,
        max_scanned=args.max_scanned,
    )
    stats = preprocess(cfg, args.out, args.local_jsonl)

    manifest = {
        "config": {
            "repo": cfg.repo,
            "split": cfg.split,
            "language": cfg.language,
            "target": cfg.target,
            "max_chars": cfg.max_chars,
            "keep_unwanted_only_rate": cfg.keep_unwanted_only_rate,
            "seed": cfg.seed,
            "max_scanned": cfg.max_scanned,
            "local_jsonl": str(args.local_jsonl) if args.local_jsonl else None,
        },
        "modelled_entities": list(PII_ENTITIES),
        "priority_entities": sorted(PRIORITY_ENTITIES),
        "stats": stats.as_dict(),
        "attribution": (
            "Training data incorporates OpenPII (pii-masking-openpii-1.5m) by "
            "Ai4Privacy / Ai Suisse SA, licensed under CC-BY-4.0."
        ),
    }
    manifest_path = args.out.parent / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    print(f"wrote {stats.windows_emitted} examples -> {args.out}")
    print(f"manifest -> {manifest_path}")
    print(f"scanned={stats.scanned} kept={stats.kept} wrong_language={stats.wrong_language}")
    print("labels:", dict(stats.label_counts.most_common()))
    if stats.unknown_labels:
        print("UNKNOWN LABELS (check the taxonomy):", dict(stats.unknown_labels))


if __name__ == "__main__":
    main()
