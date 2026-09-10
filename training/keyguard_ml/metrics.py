"""§20 — per-signal and per-entity metrics.

Overall accuracy is deliberately not computed anywhere. With `routine` at ~6% positive, a
model that predicts all-zero scores 94% on it, and the number would appear in a report
next to numbers that mean something.

Two things here are worth more than the metric definitions themselves:

`select_thresholds` picks an operating point per label rather than using 0.5 everywhere.
The labels have wildly different base rates and the risk engine treats them differently —
missing `alone` costs more than a spurious `specific_time` — so a single global threshold
is leaving both precision and recall on the table.

`entity_metrics` scores *entities*, not tokens. A nine-wordpiece email address counted as
nine correct predictions inflates the number by an order of magnitude relative to the one
decision the runtime actually makes about it.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from keyguard_ml.labels import CONTEXT_LABELS, PII_ID_TO_TAG


# --------------------------------------------------------------------------------------
# Context head
# --------------------------------------------------------------------------------------


@dataclass
class LabelMetrics:
    label: str
    threshold: float
    precision: float
    recall: float
    f1: float
    pr_auc: float
    false_positive_rate: float
    support: int
    predicted_positive: int

    def as_dict(self) -> dict:
        return {
            "label": self.label,
            "threshold": round(self.threshold, 4),
            "precision": round(self.precision, 4),
            "recall": round(self.recall, 4),
            "f1": round(self.f1, 4),
            "pr_auc": round(self.pr_auc, 4),
            "false_positive_rate": round(self.false_positive_rate, 4),
            "support": self.support,
            "predicted_positive": self.predicted_positive,
        }


def _pr_auc(y_true: np.ndarray, y_score: np.ndarray) -> float:
    from sklearn.metrics import average_precision_score

    if y_true.sum() == 0 or y_true.sum() == len(y_true):
        return float("nan")
    return float(average_precision_score(y_true, y_score))


def label_metrics(
    y_true: np.ndarray, y_score: np.ndarray, label: str, threshold: float
) -> LabelMetrics:
    y_pred = y_score >= threshold
    tp = int((y_pred & (y_true == 1)).sum())
    fp = int((y_pred & (y_true == 0)).sum())
    fn = int((~y_pred & (y_true == 1)).sum())
    tn = int((~y_pred & (y_true == 0)).sum())

    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    fpr = fp / (fp + tn) if fp + tn else 0.0

    return LabelMetrics(
        label=label,
        threshold=threshold,
        precision=precision,
        recall=recall,
        f1=f1,
        pr_auc=_pr_auc(y_true, y_score),
        false_positive_rate=fpr,
        support=int((y_true == 1).sum()),
        predicted_positive=int(y_pred.sum()),
    )


def context_report(
    y_true: np.ndarray,
    y_score: np.ndarray,
    thresholds: dict[str, float] | None = None,
) -> dict:
    """y_true / y_score: (n, NUM_CONTEXT_LABELS)."""
    thresholds = thresholds or {}
    per_label = [
        label_metrics(
            y_true[:, i], y_score[:, i], label, thresholds.get(label, 0.5)
        ).as_dict()
        for i, label in enumerate(CONTEXT_LABELS)
    ]
    macro_f1 = float(np.mean([m["f1"] for m in per_label]))
    # Macro over labels, not micro over decisions: micro would be dominated by
    # `child_ownership`, which is half the corpus and the easiest signal in it.
    return {
        "per_label": per_label,
        "macro_f1": round(macro_f1, 4),
        "macro_pr_auc": round(
            float(np.nanmean([m["pr_auc"] for m in per_label])), 4
        ),
        "n": int(y_true.shape[0]),
    }


def select_thresholds(
    y_true: np.ndarray,
    y_score: np.ndarray,
    *,
    objective: str = "f1",
    min_precision: float | None = None,
    grid: int = 199,
) -> dict[str, float]:
    """One threshold per label, chosen on validation data.

    `min_precision` turns the search into "highest recall subject to a precision floor",
    which is the shape §21 actually asks for — Level 3 recall above 98% is only
    meaningful alongside a precision constraint, or the trivial always-fire model wins.
    """
    candidates = np.linspace(0.005, 0.995, grid)
    chosen: dict[str, float] = {}

    for i, label in enumerate(CONTEXT_LABELS):
        truth, score = y_true[:, i], y_score[:, i]
        best_threshold, best_value = 0.5, -1.0
        for threshold in candidates:
            metrics = label_metrics(truth, score, label, float(threshold))
            if min_precision is not None and metrics.precision < min_precision:
                continue
            value = metrics.recall if objective == "recall" else metrics.f1
            if value > best_value:
                best_threshold, best_value = float(threshold), value
        chosen[label] = best_threshold
    return chosen


# --------------------------------------------------------------------------------------
# Token head
# --------------------------------------------------------------------------------------


def decode_bio(tag_ids: list[int], valid: list[bool]) -> list[str]:
    """Turn predicted ids into BIO strings, ignoring positions with no supervision."""
    return [PII_ID_TO_TAG.get(t, "O") if v else "O" for t, v in zip(tag_ids, valid)]


def spans_from_bio(tags: list[str]) -> set[tuple[int, int, str]]:
    """(start_token, end_token, entity). Continuations were trained as IGNORE, so a
    predicted sequence is a run of B- tags rather than B- followed by I-; both shapes are
    accepted so the function also works on gold sequences from another source."""
    spans: set[tuple[int, int, str]] = set()
    start, entity = None, None
    for i, tag in enumerate([*tags, "O"]):
        if tag.startswith("B-") or tag == "O" or (tag.startswith("I-") and entity is None):
            if entity is not None:
                spans.add((start, i, entity))
                start, entity = None, None
            if tag.startswith("B-"):
                start, entity = i, tag[2:]
        elif tag.startswith("I-"):
            if tag[2:] != entity:
                spans.add((start, i, entity))
                start, entity = i, tag[2:]
    return spans


@dataclass
class EntityMetrics:
    entity: str
    precision: float
    recall: float
    f1: float
    support: int
    predicted: int

    def as_dict(self) -> dict:
        return {
            "entity": self.entity,
            "precision": round(self.precision, 4),
            "recall": round(self.recall, 4),
            "f1": round(self.f1, 4),
            "support": self.support,
            "predicted": self.predicted,
        }


def entity_report(
    gold_sequences: list[list[str]], pred_sequences: list[list[str]]
) -> dict:
    """Entity-level precision / recall / F1, overall and per entity type."""
    gold_all: list[set] = [spans_from_bio(seq) for seq in gold_sequences]
    pred_all: list[set] = [spans_from_bio(seq) for seq in pred_sequences]

    by_entity: dict[str, dict[str, int]] = {}
    total_tp = total_fp = total_fn = 0

    for gold, pred in zip(gold_all, pred_all):
        for span in pred:
            bucket = by_entity.setdefault(span[2], {"tp": 0, "fp": 0, "fn": 0})
            if span in gold:
                bucket["tp"] += 1
                total_tp += 1
            else:
                bucket["fp"] += 1
                total_fp += 1
        for span in gold:
            if span not in pred:
                bucket = by_entity.setdefault(span[2], {"tp": 0, "fp": 0, "fn": 0})
                bucket["fn"] += 1
                total_fn += 1

    def prf(tp: int, fp: int, fn: int) -> tuple[float, float, float]:
        p = tp / (tp + fp) if tp + fp else 0.0
        r = tp / (tp + fn) if tp + fn else 0.0
        f = 2 * p * r / (p + r) if p + r else 0.0
        return p, r, f

    per_entity = []
    for entity, counts in sorted(by_entity.items()):
        p, r, f = prf(counts["tp"], counts["fp"], counts["fn"])
        per_entity.append(
            EntityMetrics(
                entity=entity,
                precision=p,
                recall=r,
                f1=f,
                support=counts["tp"] + counts["fn"],
                predicted=counts["tp"] + counts["fp"],
            ).as_dict()
        )

    p, r, f = prf(total_tp, total_fp, total_fn)
    return {
        "micro_precision": round(p, 4),
        "micro_recall": round(r, 4),
        "micro_f1": round(f, 4),
        "per_entity": per_entity,
    }
