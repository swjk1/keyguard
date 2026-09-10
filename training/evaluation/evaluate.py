"""§20, §21 and §24 in one pass over a dataset.

Split into three reports because they answer different questions and fail for different
reasons:

* **Context** (§20) — is each safety signal being detected? A bad number here is a
  modelling problem.
* **Entities** (§20) — is the token head still recognising PII? A bad number here after
  phase B usually means the joint weights let it decay.
* **Risk** (§21) — does the *product* behave? This is the only report whose numbers are
  comparable to a promise made to a parent, and it is the one that can look bad while
  both of the others look fine, because the risk engine composes signals and a rule can
  be wrong even when every signal feeding it is right.

The risk report also computes "false warnings per 1,000 safe messages", which the doc
singles out. It is measured against rows whose *gold* risk level is 0, not against rows
tagged `safe`, so hard negatives count towards it — which is the point of having them.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

from keyguard_ml.data.encoding import read_jsonl
from keyguard_ml.inference import Predictor
from keyguard_ml.labels import CONTEXT_LABELS, ENTITY_TO_SAFETY_ENTITY
from keyguard_ml.metrics import context_report, entity_report, select_thresholds
from keyguard_ml.risk_engine import RiskEngine, entities_from_tags


def _gold_entities(example) -> set[str]:
    return {
        bucket
        for span in example.spans
        if (bucket := ENTITY_TO_SAFETY_ENTITY.get(span["label"]))
    }


def risk_report(
    examples,
    scores: np.ndarray,
    pred_sequences: list[list[str]],
    predictions,
    thresholds: dict[str, float],
    engine: RiskEngine,
) -> dict:
    """Predicted vs gold Level 0–3, per §21.

    Gold level comes from running the same engine on the *annotated* labels. That makes
    this a measurement of the model, not of the rule table: if the rules are wrong they
    are wrong identically on both sides and this report will not notice. Rule quality is
    a separate review, not something a model metric can establish.
    """
    gold_levels, pred_levels = [], []
    confusion: Counter = Counter()
    per_example = []

    for index, example in enumerate(examples):
        if example.context is None:
            continue

        gold_level = engine.evaluate(
            {label: float(example.context[label]) for label in CONTEXT_LABELS},
            _gold_entities(example),
            thresholds={label: 0.5 for label in CONTEXT_LABELS},
        )
        predicted_entities = (
            entities_from_tags(pred_sequences[index]) if pred_sequences[index] else set()
        )
        # Gold spans are used for the *gold* level and predicted tags for the predicted
        # level. Using gold entities on both sides would hide every token-head failure
        # behind a correct context head.
        if predictions is not None:
            predicted_entities |= {
                bucket
                for _, _, entity in predictions[index].entities()
                if (bucket := ENTITY_TO_SAFETY_ENTITY.get(entity))
            }

        pred_level = engine.evaluate(
            dict(zip(CONTEXT_LABELS, scores[index].tolist())),
            predicted_entities,
            thresholds,
        )

        gold_levels.append(gold_level.level)
        pred_levels.append(pred_level.level)
        confusion[(gold_level.level, pred_level.level)] += 1
        per_example.append(
            {
                "text": example.text,
                "gold_level": gold_level.level,
                "pred_level": pred_level.level,
                "gold_fired": gold_level.fired,
                "pred_fired": pred_level.fired,
                "tags": getattr(example, "tags", []),
            }
        )

    gold = np.array(gold_levels)
    pred = np.array(pred_levels)
    per_level = {}
    for level in (1, 2, 3):
        tp = int(((pred == level) & (gold == level)).sum())
        fp = int(((pred == level) & (gold != level)).sum())
        fn = int(((pred != level) & (gold == level)).sum())
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        per_level[f"level_{level}"] = {
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "f1": round(2 * precision * recall / (precision + recall), 4)
            if precision + recall
            else 0.0,
            "support": int((gold == level).sum()),
            "predicted": int((pred == level).sum()),
        }

    # "At or above" is what actually matters for a warning: a Level 3 case predicted as
    # Level 2 still interrupts the child, and counting it as a total miss overstates the
    # failure. Both framings are reported.
    for level in (2, 3):
        gold_at = gold >= level
        pred_at = pred >= level
        tp = int((gold_at & pred_at).sum())
        fp = int((~gold_at & pred_at).sum())
        fn = int((gold_at & ~pred_at).sum())
        per_level[f"at_or_above_{level}"] = {
            "precision": round(tp / (tp + fp), 4) if tp + fp else 0.0,
            "recall": round(tp / (tp + fn), 4) if tp + fn else 0.0,
            "support": int(gold_at.sum()),
        }

    safe = gold == 0
    false_warnings = int((safe & (pred > 0)).sum())
    per_thousand = 1000 * false_warnings / max(1, int(safe.sum()))

    return {
        "n": len(gold_levels),
        "per_level": per_level,
        "false_warnings_per_1000_safe": round(per_thousand, 2),
        "safe_messages": int(safe.sum()),
        "false_warnings": false_warnings,
        "exact_level_accuracy": round(float((gold == pred).mean()), 4),
        "confusion": {f"gold{g}_pred{p}": c for (g, p), c in sorted(confusion.items())},
        "_per_example": per_example,
    }


def error_analysis(per_example: list[dict], scores, examples, thresholds) -> dict:
    """§24 — group failures so the next batch of training data can be aimed.

    Buckets by the tags already on the example rather than by inventing a taxonomy at
    analysis time. The generators and the gold set both tag every row with what it is
    testing (`ownership`, `idiom`, `indirect_guardian`, `abbreviation`, ...), so a
    failure bucket names something a generator can produce more of.
    """
    by_tag_fp: Counter = Counter()
    by_tag_fn: Counter = Counter()
    by_tag_total: Counter = Counter()
    label_failures: dict[str, dict[str, list[str]]] = defaultdict(
        lambda: {"false_positive": [], "false_negative": []}
    )

    row = 0
    for example in examples:
        if example.context is None:
            continue
        tags = getattr(example, "tags", []) or ["untagged"]
        for tag in tags:
            by_tag_total[tag] += 1

        for index, label in enumerate(CONTEXT_LABELS):
            truth = int(example.context[label])
            predicted = int(scores[row][index] >= thresholds.get(label, 0.5))
            if truth == predicted:
                continue
            kind = "false_positive" if predicted else "false_negative"
            if len(label_failures[label][kind]) < 25:
                label_failures[label][kind].append(example.text)
            for tag in tags:
                (by_tag_fp if predicted else by_tag_fn)[tag] += 1
        row += 1

    level_failures = defaultdict(list)
    for record in per_example:
        if record["gold_level"] == record["pred_level"]:
            continue
        key = f"gold{record['gold_level']}_pred{record['pred_level']}"
        if len(level_failures[key]) < 25:
            level_failures[key].append(
                {"text": record["text"], "gold_fired": record["gold_fired"], "pred_fired": record["pred_fired"]}
            )

    return {
        "by_tag": {
            tag: {
                "examples": by_tag_total[tag],
                "false_positives": by_tag_fp.get(tag, 0),
                "false_negatives": by_tag_fn.get(tag, 0),
                "errors_per_example": round(
                    (by_tag_fp.get(tag, 0) + by_tag_fn.get(tag, 0)) / by_tag_total[tag], 3
                ),
            }
            for tag in sorted(by_tag_total, key=lambda t: -by_tag_total[t])
        },
        "worst_tags": [
            tag
            for tag in sorted(
                by_tag_total,
                key=lambda t: -(by_tag_fp.get(t, 0) + by_tag_fn.get(t, 0)) / by_tag_total[t],
            )[:10]
        ],
        "label_failures": {k: dict(v) for k, v in label_failures.items()},
        "level_failures": dict(level_failures),
    }


def run(
    checkpoint: str,
    dataset: str,
    out: Path | None,
    threshold_source: str | None,
    objective: str,
    min_precision: float | None,
) -> dict:
    predictor = Predictor(checkpoint)
    examples = read_jsonl(dataset)
    print(f"loaded {len(examples)} examples from {dataset}")

    scores, truths, gold_sequences, pred_sequences = predictor.score_dataset(examples)
    predictions = predictor.predict_texts([e.text for e in examples])

    # Thresholds: refit on a held-out file if one is given, else use the checkpoint's.
    if threshold_source:
        tune_examples = [e for e in read_jsonl(threshold_source) if e.context is not None]
        tune_scores, tune_truth, _, _ = predictor.score_dataset(tune_examples)
        thresholds = select_thresholds(
            tune_truth, tune_scores, objective=objective, min_precision=min_precision
        )
        print(f"thresholds refit on {threshold_source}")
    else:
        thresholds = predictor.thresholds

    supervised = ~np.isnan(truths).any(axis=1)
    context = (
        context_report(truths[supervised], scores[supervised], thresholds)
        if supervised.any()
        else None
    )
    entities = (
        entity_report(
            [g for g in gold_sequences if g], [p for g, p in zip(gold_sequences, pred_sequences) if g]
        )
        if any(gold_sequences)
        else None
    )

    engine = RiskEngine()
    risk = risk_report(examples, scores, pred_sequences, predictions, thresholds, engine)
    per_example = risk.pop("_per_example")
    errors = error_analysis(per_example, scores[supervised], [e for e in examples if e.context is not None], thresholds)

    report = {
        "checkpoint": checkpoint,
        "dataset": dataset,
        "thresholds": thresholds,
        "context": context,
        "entities": entities,
        "risk": risk,
        "error_analysis": errors,
    }

    if out:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        (out.parent / f"{out.stem}_per_example.json").write_text(
            json.dumps(per_example, indent=2) + "\n", encoding="utf-8"
        )
        print(f"report -> {out}")

    _print(report)
    return report


def _print(report: dict) -> None:
    context = report.get("context")
    if context:
        print("\n=== context signals (§20) ===")
        print(f"{'label':18s} {'thr':>5s} {'P':>7s} {'R':>7s} {'F1':>7s} {'PR-AUC':>7s} {'FPR':>7s} {'n+':>6s}")
        for row in context["per_label"]:
            print(
                f"{row['label']:18s} {row['threshold']:5.2f} {row['precision']:7.3f} "
                f"{row['recall']:7.3f} {row['f1']:7.3f} {row['pr_auc']:7.3f} "
                f"{row['false_positive_rate']:7.3f} {row['support']:6d}"
            )
        print(f"{'macro':18s} {'':5s} {'':7s} {'':7s} {context['macro_f1']:7.3f} {context['macro_pr_auc']:7.3f}")

    entities = report.get("entities")
    if entities:
        print("\n=== PII entities (§20) ===")
        print(f"micro P={entities['micro_precision']:.3f} R={entities['micro_recall']:.3f} F1={entities['micro_f1']:.3f}")
        for row in entities["per_entity"]:
            print(f"  {row['entity']:14s} P={row['precision']:.3f} R={row['recall']:.3f} F1={row['f1']:.3f} n={row['support']}")

    risk = report.get("risk")
    if risk:
        print("\n=== product risk levels (§21) ===")
        for key, row in risk["per_level"].items():
            if key.startswith("level_"):
                print(f"  {key:16s} P={row['precision']:.3f} R={row['recall']:.3f} n={row['support']}")
        for level in (2, 3):
            row = risk["per_level"][f"at_or_above_{level}"]
            print(f"  >= level {level}      P={row['precision']:.3f} R={row['recall']:.3f} n={row['support']}")
        print(f"  false warnings per 1,000 safe messages: {risk['false_warnings_per_1000_safe']}")
        print(f"  exact level accuracy: {risk['exact_level_accuracy']:.3f}")

        print("\n  provisional targets (§21):")
        critical = risk["per_level"]["at_or_above_3"]["recall"]
        high_precision = risk["per_level"]["at_or_above_2"]["precision"]
        fpr = risk["false_warnings_per_1000_safe"] / 10.0
        for name, value, target, ok in [
            ("critical recall  > 98%", critical * 100, 98.0, critical >= 0.98),
            ("high precision   > 90%", high_precision * 100, 90.0, high_precision >= 0.90),
            ("false positives  <  2%", fpr, 2.0, fpr < 2.0),
        ]:
            print(f"    [{'PASS' if ok else 'FAIL'}] {name}  actual {value:.2f}")

    errors = report.get("error_analysis")
    if errors and errors["by_tag"]:
        print("\n=== error analysis (§24) — worst slices ===")
        for tag in errors["worst_tags"]:
            row = errors["by_tag"][tag]
            print(
                f"  {tag:22s} n={row['examples']:5d} FP={row['false_positives']:4d} "
                f"FN={row['false_negatives']:4d} err/ex={row['errors_per_example']:.2f}"
            )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--thresholds-from", default=None,
                        help="refit thresholds on this file instead of using the checkpoint's")
    parser.add_argument("--objective", default="f1", choices=["f1", "recall"])
    parser.add_argument("--min-precision", type=float, default=None)
    args = parser.parse_args()

    run(args.checkpoint, args.dataset, args.out, args.thresholds_from, args.objective, args.min_precision)


if __name__ == "__main__":
    main()
