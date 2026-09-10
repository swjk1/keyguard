"""Builds the child-safety corpus: generate, augment, verify, split, manifest.

The split (§18) is the part worth reading. "Hold out entire template families" is right
but cannot be applied naively: several families are the only one asserting their
combination of signals, and holding one of those out removes the capability from training
rather than removing a paraphrase of it. Level 3 recall measured against a model that
never saw a full critical combination is not a measurement of anything.

So families are grouped by *signal signature* — the set of context labels they assert —
and assigned within each group, with the rule that every signature keeps at least one
family in train. A signature with only one family is train-only, and the manifest names
those explicitly, because they are the patterns for which the test set proves nothing.

Entity values are drawn from a different lexicon seed per split, so a test example never
reuses a street name or phone number the model saw in training. That is the second half
of §18: holding out the wording is pointless if "24 Oak St" itself is memorable.
"""

from __future__ import annotations

import argparse
import json
import random
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from generators.combinations import COMBO_TEMPLATES
from generators.hard_negatives import HARD_NEGATIVE_TEMPLATES
from generators.lexicon import Lexicon
from generators.render import (
    GeneratedExample,
    Template,
    assemble,
    render_chunks,
    verify_spans,
)
from generators.slang import augment
from generators.siblings import SIBLING_TEMPLATES
from generators.templates import SAFE_TEMPLATES, SINGLE_SIGNAL_TEMPLATES
from keyguard_ml.labels import CONTEXT_LABELS

SPLITS = ("train", "validation", "test")


@dataclass
class GroupSpec:
    name: str
    templates: list[Template]
    target: int
    augment_intensity: float = 1.0


def _combo_siblings() -> list[Template]:
    return [t for t in SIBLING_TEMPLATES if "combination" in t.tags]


def _single_siblings() -> list[Template]:
    return [t for t in SIBLING_TEMPLATES if "combination" not in t.tags]


def default_groups(scale: float = 1.0) -> list[GroupSpec]:
    """§13's table. `scale` shrinks everything proportionally for smoke runs."""
    return [
        GroupSpec("single_signal", [*SINGLE_SIGNAL_TEMPLATES, *_single_siblings()], int(10_000 * scale)),
        GroupSpec("combination", [*COMBO_TEMPLATES, *_combo_siblings()], int(15_000 * scale)),
        GroupSpec("hard_negative", HARD_NEGATIVE_TEMPLATES, int(15_000 * scale)),
        GroupSpec("safe", SAFE_TEMPLATES, int(10_000 * scale)),
        # §13 lists "slang/typo variants" as its own 10k. They are not separate
        # templates — they are the same families at maximum augmentation. Generating
        # them as a distinct pass keeps the count honest and lets error analysis slice
        # on the group rather than having to parse the augmentation list.
        GroupSpec(
            "slang_variant",
            [*SINGLE_SIGNAL_TEMPLATES, *COMBO_TEMPLATES, *HARD_NEGATIVE_TEMPLATES, *SIBLING_TEMPLATES],
            int(10_000 * scale),
            augment_intensity=1.6,
        ),
    ]


def signature(template: Template) -> tuple[int, ...]:
    return tuple(template.labels[k] for k in CONTEXT_LABELS)


def _signature_name(sig: tuple[int, ...]) -> str:
    return "+".join(k for k, v in zip(CONTEXT_LABELS, sig) if v) or "none"


def assign_splits(
    groups: list[GroupSpec],
    rng: random.Random,
    val_ratio: float = 0.1,
    test_ratio: float = 0.1,
) -> tuple[dict[str, str], list[str]]:
    """Map family -> split. Returns the mapping and the train-only signatures.

    A family gets exactly one split even though the slang pass reuses every family, so
    it is assigned once against the *first* group it appears in. A family that trained
    in one group and tested in another would be the precise leak §18 is about.

    Two constraints are enforced, in this order:

    1. Every *signature* keeps at least one family in train. Otherwise holding out a
       family removes a capability rather than a paraphrase, and the test set measures
       something the model was never taught.
    2. Every *group* gets at least one family in validation and in test, once it has
       enough families to spare them. Without this, a group can end up with an empty
       validation slice and its metrics silently vanish from the report.
    3. Every *label* has positive families in validation and in test. Constraint 1
       assigns whole signatures, and a signature is a whole label vector, so a label can
       be entirely absent from a split even when every signature is represented. That
       happened: the first working build produced a validation split with zero
       `school_context`, `specific_time` and `meetup` positives, which makes those
       labels' precision, recall and PR-AUC undefined — and PR-AUC returns NaN, which
       `macro_pr_auc` then propagates into the model-selection metric. A split that
       cannot measure a label must not be produced silently.
    """
    primary_group: dict[str, str] = {}
    family_signature: dict[str, tuple[int, ...]] = {}
    for group in groups:
        for template in group.templates:
            if template.family not in primary_group:
                primary_group[template.family] = group.name
                family_signature[template.family] = signature(template)

    assignment: dict[str, str] = {}
    train_only: list[str] = []

    # -- constraint 1: split within each signature bucket -----------------------------
    by_signature: dict[tuple[int, ...], list[str]] = defaultdict(list)
    for family, sig in family_signature.items():
        by_signature[sig].append(family)

    for sig, families in sorted(by_signature.items()):
        families = sorted(families)
        rng.shuffle(families)
        if len(families) == 1:
            assignment[families[0]] = "train"
            train_only.append(_signature_name(sig))
            continue

        assignment[families[0]] = "train"  # the signature's anchor in train
        rest = families[1:]
        n_test = max(1, round(len(families) * test_ratio))
        n_val = max(1, round(len(families) * val_ratio)) if len(families) >= 3 else 0
        # Never spend more than half a signature's families on evaluation.
        budget = max(0, len(rest) - max(1, len(families) // 2 - 1))
        n_test = min(n_test, budget)
        n_val = min(n_val, max(0, budget - n_test))

        for i, family in enumerate(rest):
            if i < n_test:
                assignment[family] = "test"
            elif i < n_test + n_val:
                assignment[family] = "validation"
            else:
                assignment[family] = "train"

    # -- constraint 2: make sure no group has an empty eval slice ---------------------
    for group in groups:
        families = sorted({t.family for t in group.templates if primary_group[t.family] == group.name})
        if len(families) < 4:
            continue
        for split in ("test", "validation"):
            if any(assignment[f] == split for f in families):
                continue
            # Promote a train family whose signature has another train family to spare.
            trainers = [f for f in families if assignment[f] == "train"]
            rng.shuffle(trainers)
            for candidate in trainers:
                siblings = by_signature[family_signature[candidate]]
                if sum(1 for s in siblings if assignment[s] == "train") > 1:
                    assignment[candidate] = split
                    break

    # -- constraint 3: every label measurable in every eval split ---------------------
    def _movable(family: str) -> bool:
        """True when moving `family` out of train leaves its signature an anchor."""
        siblings = by_signature[family_signature[family]]
        return sum(1 for s in siblings if assignment[s] == "train") > 1

    for label_index, label in enumerate(CONTEXT_LABELS):
        positives = [f for f, sig in family_signature.items() if sig[label_index]]
        if not positives:
            continue
        for split in ("validation", "test"):
            if any(assignment[f] == split for f in positives):
                continue
            # Prefer a donor whose signature has the most train families to spare, so a
            # scarce signature is not the one raided.
            donors = sorted(
                (f for f in positives if assignment[f] == "train" and _movable(f)),
                key=lambda f: -sum(
                    1 for s in by_signature[family_signature[f]] if assignment[s] == "train"
                ),
            )
            if donors:
                assignment[donors[0]] = split

    return assignment, sorted(set(train_only))


def generate_one(
    template: Template,
    lex: Lexicon,
    rng: random.Random,
    intensity: float,
) -> GeneratedExample | None:
    chunks = render_chunks(template, lex)
    chunks, applied = augment(chunks, rng, intensity=intensity)
    try:
        text, spans = assemble(chunks)
        verify_spans(text, spans)
    except AssertionError:
        # A transform produced something structurally invalid. Dropping the sample is
        # correct — emitting it would put a misaligned label in the corpus, and that is
        # a defect no downstream metric can see.
        return None
    if not text.strip():
        return None
    return GeneratedExample(
        text=text,
        spans=spans,
        labels=dict(template.labels),
        template_family=template.family,
        tags=template.tags,
        augmentations=applied,
    )


def build(
    groups: list[GroupSpec],
    out_dir: Path,
    seed: int = 20260909,
    val_ratio: float = 0.1,
    test_ratio: float = 0.1,
) -> dict:
    rng = random.Random(seed)
    assignment, train_only = assign_splits(groups, random.Random(seed + 1), val_ratio, test_ratio)

    # A different entity seed per split — see the module docstring.
    lexicons = {
        "train": Lexicon(seed + 100),
        "validation": Lexicon(seed + 200),
        "test": Lexicon(seed + 300),
    }
    writers = {}
    out_dir.mkdir(parents=True, exist_ok=True)
    counts: dict[str, Counter] = {s: Counter() for s in SPLITS}
    label_counts: dict[str, Counter] = {s: Counter() for s in SPLITS}
    family_counts: Counter = Counter()
    rejected = 0

    try:
        for split in SPLITS:
            writers[split] = (out_dir / f"{split}.jsonl").open("w", encoding="utf-8")

        for group in groups:
            # Weighted draw within the group, restricted per split to that split's
            # families. Weight is how §10's "oversample the combinations" is expressed.
            per_split: dict[str, list[Template]] = {s: [] for s in SPLITS}
            for template in group.templates:
                per_split[assignment[template.family]].append(template)

            for split in SPLITS:
                pool = per_split[split]
                if not pool:
                    continue
                share = {"train": 1 - val_ratio - test_ratio, "validation": val_ratio, "test": test_ratio}[split]
                quota = max(1, int(group.target * share))
                weights = [t.weight for t in pool]

                produced = 0
                attempts = 0
                while produced < quota and attempts < quota * 5:
                    attempts += 1
                    template = rng.choices(pool, weights=weights, k=1)[0]
                    example = generate_one(template, lexicons[split], rng, group.augment_intensity)
                    if example is None:
                        rejected += 1
                        continue
                    row = example.as_dict()
                    row["group"] = group.name
                    row["split"] = split
                    writers[split].write(json.dumps(row, ensure_ascii=False) + "\n")
                    produced += 1
                    counts[split][group.name] += 1
                    family_counts[example.template_family] += 1
                    for label, value in example.labels.items():
                        if value:
                            label_counts[split][label] += 1
    finally:
        for writer in writers.values():
            writer.close()

    split_families: dict[str, list[str]] = defaultdict(list)
    for family, split in assignment.items():
        split_families[split].append(family)

    leaks = set(split_families["train"]) & (set(split_families["test"]) | set(split_families["validation"]))
    assert not leaks, f"family leaked across splits: {leaks}"

    # A split that cannot measure a label is worse than a missing split: the metric is
    # NaN or a meaningless zero, and it flows into model selection looking like a number.
    # Fail the build rather than write it.
    unmeasurable = {
        split: [label for label in CONTEXT_LABELS if not label_counts[split][label]]
        for split in SPLITS
    }
    broken = {s: labels for s, labels in unmeasurable.items() if labels}
    assert not broken, (
        f"labels with zero positives in a split: {broken}. Add template families "
        f"asserting those labels, or relax the split ratios."
    )

    # Family holdout means the eval splits contain entirely different template families
    # from train, so their label rates will not match train's and are not supposed to.
    # What does need watching is absolute count: a label with a handful of positives in
    # validation gives a threshold fitted to noise, and §22's calibration inherits it.
    MIN_POSITIVES = 100
    thin = {
        split: {
            label: label_counts[split][label]
            for label in CONTEXT_LABELS
            if 0 < label_counts[split][label] < MIN_POSITIVES
        }
        for split in ("validation", "test")
    }
    thin = {s: v for s, v in thin.items() if v}

    manifest = {
        "seed": seed,
        "groups": {g.name: g.target for g in groups},
        "counts": {s: dict(counts[s]) for s in SPLITS},
        "totals": {s: sum(counts[s].values()) for s in SPLITS},
        "positive_label_counts": {s: dict(label_counts[s]) for s in SPLITS},
        "positive_label_rates": {
            s: {
                label: round(label_counts[s][label] / max(1, sum(counts[s].values())), 4)
                for label in CONTEXT_LABELS
            }
            for s in SPLITS
        },
        "thin_labels": thin,
        "thin_label_threshold": MIN_POSITIVES,
        "families_per_split": {s: sorted(split_families[s]) for s in SPLITS},
        "train_only_signatures": train_only,
        "rejected_by_span_check": rejected,
        "notes": (
            "train_only_signatures list label combinations held only by a single template "
            "family. The test set proves nothing about generalisation for those; add a "
            "second family with the same signature to make them measurable."
        ),
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("datasets/child_safety"))
    parser.add_argument("--scale", type=float, default=1.0, help="fraction of the §13 targets")
    parser.add_argument("--seed", type=int, default=20260909)
    args = parser.parse_args()

    manifest = build(default_groups(args.scale), args.out, seed=args.seed)
    print(f"wrote -> {args.out}")
    for split in SPLITS:
        print(f"  {split:11s} {manifest['totals'][split]:>7,}  {manifest['counts'][split]}")
    print(f"rejected by span check: {manifest['rejected_by_span_check']}")
    print(f"train-only signatures ({len(manifest['train_only_signatures'])}):")
    for sig in manifest["train_only_signatures"]:
        print(f"    {sig}")
    if manifest["thin_labels"]:
        print(
            f"\nWARNING: labels with under {manifest['thin_label_threshold']} positives "
            f"in an eval split — thresholds for these are fitted to noise:"
        )
        for split, labels in manifest["thin_labels"].items():
            for label, count in sorted(labels.items(), key=lambda kv: kv[1]):
                print(f"    {split:11s} {label:17s} {count}")
        print("  Fix by adding template families that assert those labels.")


if __name__ == "__main__":
    main()
