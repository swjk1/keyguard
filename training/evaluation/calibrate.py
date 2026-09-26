"""§21 — fit the shipped operating points, once, on an explicitly named split.

This step exists because the thresholds used to *have* two authors. `Trainer.finalize`
fitted a set into the checkpoint and `evaluation.evaluate` refitted its own for the
report, so the numbers quoted in `report_gold.json` described a configuration the phone
would never run: six of eight thresholds differed. One place fits them now, writes them
into the checkpoint, and evaluation and export both read that.

Two choices are deliberate and worth stating, because both were previously implicit:

`--calibration-set` is a flag, not a constant. Operating points only transfer to data that
shares the calibration split's label priors, and the corpus holds out whole template
families, so the splits are *not* interchangeable by construction — `guardian_absent`
once held 0.90 precision on validation and 0.08 on test at one and the same threshold.
Naming the split makes that a reviewable decision. The generator balances the splits
against each other (`generators.build_dataset._balance_split_distributions`) on label
priors, risk-level histogram and per-rule composition — all three, because matching only
the marginals still left validation reaching Level 3 by different rules than test, and a
threshold fitted here then cost 0.55 of Level 3 recall there. That balance is what makes
validation a defensible default rather than merely a convenient one; it is not a licence
to skip reading `report_test.json` next to this split's numbers.

`--budget` is the product constraint, expressed in the unit a product owner can actually
rule on: warnings per 1,000 safe messages. Recall is then whatever that budget permits,
which is the honest direction for the tradeoff. The previous objective set a precision
floor per label and let the false-warning rate fall where it may; it fell at 179 per 1,000
on gold, roughly one interruption per six safe messages.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

from keyguard_ml.data.encoding import read_jsonl
from keyguard_ml.inference import Predictor
from keyguard_ml.labels import CONTEXT_LABELS, ENTITY_TO_SAFETY_ENTITY, SAFETY_ENTITIES
from keyguard_ml.operating_points import (
    compile_rules,
    levels_from_terms,
    select_operating_points,
)
from keyguard_ml.risk_engine import RiskEngine, entities_from_tags


def _entity_matrix(buckets: list[set[str]]) -> np.ndarray:
    matrix = np.zeros((len(buckets), len(SAFETY_ENTITIES)), dtype=bool)
    for row, active in enumerate(buckets):
        for column, entity in enumerate(SAFETY_ENTITIES):
            matrix[row, column] = entity in active
    return matrix


def collect(predictor: Predictor, dataset: str):
    """(truths, scores, predicted-entity matrix, gold levels) for a calibration split."""
    examples = [e for e in read_jsonl(dataset) if e.context is not None]
    scores, truths, _gold_tags, pred_tags = predictor.score_dataset(examples)

    predictions = predictor.predict_texts([e.text for e in examples])

    predicted_buckets, gold_buckets = [], []
    for index, example in enumerate(examples):
        buckets = entities_from_tags(pred_tags[index]) if pred_tags[index] else set()
        buckets |= {
            bucket
            for _, _, entity in predictions[index].entities()
            if (bucket := ENTITY_TO_SAFETY_ENTITY.get(entity))
        }
        predicted_buckets.append(buckets)
        # Gold level is the engine on the *annotated* labels and *annotated* spans, so it
        # measures the model rather than the rule table (see `evaluate.risk_report`).
        gold_buckets.append(
            {
                bucket
                for span in example.spans
                if (bucket := ENTITY_TO_SAFETY_ENTITY.get(span["label"]))
            }
            # Sets without character offsets (the gold set) state their buckets directly.
            | set(example.entities or ())
        )

    truth_matrix = np.array(
        [[float(example.context[label]) for label in CONTEXT_LABELS] for example in examples]
    )
    gold_active = np.concatenate([truth_matrix > 0.5, _entity_matrix(gold_buckets)], axis=1)
    gold_levels = levels_from_terms(compile_rules(RiskEngine()), gold_active)

    return truth_matrix, scores, _entity_matrix(predicted_buckets), gold_levels


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument(
        "--calibration-set",
        default="datasets/child_safety/validation.jsonl",
        help="split whose label priors the thresholds will be tuned against",
    )
    parser.add_argument(
        "--budget",
        type=float,
        default=20.0,
        help="tolerated Level>=1 warnings per 1,000 genuinely safe messages",
    )
    parser.add_argument(
        "--min-level3-recall",
        type=float,
        default=0.95,
        help=(
            "floor on critical-disclosure recall. Non-zero by default and deliberately: "
            "with no floor, silencing the model satisfies any budget at recall 0.000, "
            "and because nearly every rule requires child_ownership, raising that one "
            "threshold is enough to do it. A silent keyboard is not a solution here."
        ),
    )
    parser.add_argument(
        "--min-level2-recall",
        type=float,
        default=0.0,
        help=(
            "floor on Level>=2 recall. This is the expensive one: Level 2 fires on common "
            "combinations such as child_ownership + child_location, which overlap heavily "
            "with safe chat. On the phase C checkpoint, 95%% critical recall alone cost "
            "3.8 warnings/1k, and adding a 70%% Level 2 floor cost 217/1k."
        ),
    )
    parser.add_argument(
        "--curve",
        action="store_true",
        help="print the cost of each recall floor and exit, instead of choosing a point",
    )
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--out", type=Path, default=None, help="write the outcome as JSON")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="report the operating points without writing them into the checkpoint",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="write the thresholds even when the constraints were not met",
    )
    args = parser.parse_args()

    predictor = Predictor(args.checkpoint)
    before = dict(predictor.thresholds)

    truths, scores, entity_active, gold_levels = collect(predictor, args.calibration_set)
    print(
        f"calibrating on {args.calibration_set}: {len(gold_levels)} examples, "
        f"{int((gold_levels == 0).sum())} safe, {int((gold_levels >= 3).sum())} Level 3"
    )

    if args.curve:
        print(
            f"\n{'req L3':>8s} {'req L2+':>8s} {'warn/1k':>9s} "
            f"{'got L3':>8s} {'got L2+':>8s}"
        )
        for level3, level2 in [
            (0.80, 0.0), (0.90, 0.0), (0.95, 0.0), (0.98, 0.0),
            (0.95, 0.50), (0.95, 0.70), (0.95, 0.90),
        ]:
            point = select_operating_points(
                truths, scores, entity_active, gold_levels,
                budget_per_1000=1e9, min_level3_recall=level3,
                min_level2_recall=level2, rounds=args.rounds,
            )
            flag = "" if point.feasible else "   NOT ACHIEVABLE"
            print(
                f"{level3:8.2f} {level2:8.2f} {point.false_warnings_per_1000:9.1f} "
                f"{point.level3_recall:8.4f} {point.at_or_above_2_recall:8.4f}{flag}"
            )
        print("\ncurve only: checkpoint left unchanged")
        return

    outcome = select_operating_points(
        truths,
        scores,
        entity_active,
        gold_levels,
        budget_per_1000=args.budget,
        min_level3_recall=args.min_level3_recall,
        min_level2_recall=args.min_level2_recall,
        rounds=args.rounds,
    )

    print(f"\n{'label':18s} {'before':>8s} {'after':>8s}")
    for label in CONTEXT_LABELS:
        print(f"{label:18s} {before.get(label, 0.5):8.3f} {outcome.thresholds[label]:8.3f}")

    print(
        f"\nconstraints: <= {args.budget:.1f} warnings/1k safe, "
        f"Level 3 recall >= {args.min_level3_recall:.2f}, "
        f"Level >= 2 recall >= {args.min_level2_recall:.2f}"
    )
    print(f"false warnings      {outcome.false_warnings_per_1000:.1f}/1k")
    print(f"Level 3 recall      {outcome.level3_recall:.4f}")
    print(f"Level >= 2 recall   {outcome.at_or_above_2_recall:.4f}")
    print(f"context macro-F1    {outcome.macro_f1:.4f}")

    if not outcome.feasible:
        print(
            "\n*** NOT ACHIEVABLE ***\n"
            "No operating point satisfies all three constraints at once. The thresholds\n"
            "above are the closest reachable point, not a solution — treat the gap as a\n"
            "model problem rather than a threshold one, and do not ship against it.\n"
            "Run again with --curve to see what each recall floor actually costs."
        )

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(outcome.as_dict(), indent=2) + "\n", encoding="utf-8")
        print(f"\noutcome -> {args.out}")

    if args.dry_run:
        print("\ndry run: checkpoint left unchanged")
        return

    if not outcome.feasible and not args.force:
        print(
            "\nRefusing to write unachievable operating points into the checkpoint: export\n"
            "would ship them and the phone would run them. Loosen a constraint, or pass\n"
            "--force if you are deliberately recording a known-bad point."
        )
        raise SystemExit(2)

    blob = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    blob["thresholds"] = outcome.thresholds
    blob["calibration"] = {
        "calibration_set": args.calibration_set,
        **outcome.as_dict(),
    }
    torch.save(blob, args.checkpoint)
    print(f"\nthresholds written into {args.checkpoint}")


if __name__ == "__main__":
    main()
