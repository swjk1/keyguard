"""§21 — choosing the per-label thresholds the product ships.

The thresholds are not a modelling detail; they *are* the product's behaviour. A keyboard
that interrupts a child on a fifth of their safe messages will be switched off, and a
model that never interrupts is worthless, so the operating points decide whether the model
is usable at all — independently of how well it was trained.

Two things are wrong with picking them per label, which is what `metrics.select_thresholds`
does and what this module replaces for anything that ships:

1. *The constraint is not per label.* What a user experiences is a Level 0-3 warning, and
   a level comes out of the rule table combining several signals. Eight thresholds chosen
   one at a time against per-label precision cannot see the false-warning rate they add up
   to, because no single label is responsible for it.

2. *"Highest recall subject to precision >= 0.90" is degenerate here.* On a label the model
   ranks well but rarely fires confidently, the only points clearing a 0.90 precision floor
   sit far out on the tail, so the rule returns a threshold with near-zero recall and calls
   it a success: `child_location` was assigned 0.905, scoring 1.000 precision at 0.031
   recall on gold — a model with 0.77 PR-AUC reduced to catching one positive in thirty.

So: search all eight jointly, through the rule table, against the number the product is
actually budgeted on. `budget_per_1000` is the number of Level >= 1 warnings tolerated per
1,000 genuinely safe messages; within that budget the search maximises Level 3 recall
first, because a missed critical disclosure is the failure this product exists to prevent.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from keyguard_ml.labels import CONTEXT_LABELS, SAFETY_ENTITIES
from keyguard_ml.risk_engine import RiskEngine

TERMS: tuple[str, ...] = (*CONTEXT_LABELS, *SAFETY_ENTITIES)
_TERM_INDEX = {term: i for i, term in enumerate(TERMS)}


@dataclass
class CompiledRule:
    level: int
    all_of: np.ndarray
    any_of: np.ndarray
    none_of: np.ndarray


def compile_rules(engine: RiskEngine) -> list[CompiledRule]:
    """Rules as index arrays, so a whole split can be levelled with array ops.

    The per-example `RiskEngine.evaluate` is the readable version and stays the reference;
    this is the same logic shaped for a search that evaluates it tens of thousands of
    times. Two implementations of one rule table drift, so if this file is touched, check
    it against the engine over random active sets before trusting a number that came out
    of it — the last such check ran 20,000 rows across all four levels with no mismatch.
    """
    compiled = []
    for rule in engine.rules:
        compiled.append(
            CompiledRule(
                level=rule.level,
                all_of=np.array([_TERM_INDEX[t] for t in rule.all_of], dtype=int),
                any_of=np.array([_TERM_INDEX[t] for t in rule.any_of], dtype=int),
                none_of=np.array([_TERM_INDEX[t] for t in rule.none_of], dtype=int),
            )
        )
    return compiled


def levels_from_terms(compiled: list[CompiledRule], active: np.ndarray) -> np.ndarray:
    """`active` is (n_examples, len(TERMS)) booleans -> (n_examples,) Level 0-3."""
    n = active.shape[0]
    levels = np.zeros(n, dtype=np.int8)
    for rule in compiled:
        fires = np.ones(n, dtype=bool)
        if rule.all_of.size:
            fires &= active[:, rule.all_of].all(axis=1)
        if rule.any_of.size:
            fires &= active[:, rule.any_of].any(axis=1)
        if rule.none_of.size:
            fires &= ~active[:, rule.none_of].any(axis=1)
        np.maximum(levels, np.where(fires, rule.level, 0).astype(np.int8), out=levels)
    return levels


@dataclass
class BudgetOutcome:
    thresholds: dict[str, float]
    feasible: bool
    false_warnings_per_1000: float
    level3_recall: float
    at_or_above_2_recall: float
    macro_f1: float
    budget_per_1000: float
    min_level3_recall: float = 0.0
    history: list[dict] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "thresholds": self.thresholds,
            "feasible": self.feasible,
            "budget_per_1000": self.budget_per_1000,
            "min_level3_recall": self.min_level3_recall,
            "false_warnings_per_1000_safe": round(self.false_warnings_per_1000, 2),
            "level3_recall": round(self.level3_recall, 4),
            "at_or_above_2_recall": round(self.at_or_above_2_recall, 4),
            "context_macro_f1": round(self.macro_f1, 4),
        }


def _best_f1_threshold(truth: np.ndarray, score: np.ndarray, grid: np.ndarray) -> float:
    """Per-label F1 optimum — only ever a starting point for the joint search."""
    actual = truth > 0.5
    best_value, best_threshold = -1.0, 0.5
    for threshold in grid:
        predicted = score >= threshold
        tp = float(np.sum(predicted & actual))
        if tp == 0:
            continue
        fp = float(np.sum(predicted & ~actual))
        fn = float(np.sum(~predicted & actual))
        f1 = 2 * tp / (2 * tp + fp + fn)
        if f1 > best_value:
            best_value, best_threshold = f1, float(threshold)
    return best_threshold


def _macro_f1(truths: np.ndarray, active: np.ndarray) -> float:
    scores = []
    for i in range(len(CONTEXT_LABELS)):
        predicted, actual = active[:, i], truths[:, i] > 0.5
        tp = float(np.sum(predicted & actual))
        fp = float(np.sum(predicted & ~actual))
        fn = float(np.sum(~predicted & actual))
        scores.append(0.0 if tp == 0 else 2 * tp / (2 * tp + fp + fn))
    return float(np.mean(scores))


def select_operating_points(
    truths: np.ndarray,
    scores: np.ndarray,
    entity_active: np.ndarray,
    gold_levels: np.ndarray,
    *,
    engine: RiskEngine | None = None,
    budget_per_1000: float = 20.0,
    min_level3_recall: float = 0.0,
    min_level2_recall: float = 0.0,
    grid: np.ndarray | None = None,
    rounds: int = 4,
) -> BudgetOutcome:
    """Per-label thresholds maximising Level 3 recall within a false-warning budget.

    Coordinate ascent: sweep one label's threshold across `grid` with the other seven
    held, take the best, repeat until a full pass changes nothing. Not guaranteed global,
    but the objective is a step function of eight bounded coordinates and the alternative
    — 40**8 points — is not available.

    Ordering among candidates:
      * anything inside the budget beats anything outside it;
      * inside, higher Level 3 recall wins, then Level >= 2 recall, then macro-F1;
      * outside, the lower false-warning rate wins, so an infeasible budget still returns
        the closest thing to it rather than an arbitrary point.

    Run from several starts, because a single one is not safe here. From thresholds at
    0.5 the first improving move tends to be *silence* — raise everything until nothing
    fires, and a model that never warns trivially meets any budget at Level 3 recall
    0.000. It is feasible, it is a local optimum, and it is worthless. The sensitive start
    (every threshold low) approaches from the opposite side: it begins over budget with
    most criticals caught, and the search tightens until it fits, which keeps recall on
    the way down. The best final point across starts wins.
    """
    engine = engine or RiskEngine()
    compiled = compile_rules(engine)
    grid = np.arange(0.025, 1.0, 0.025) if grid is None else grid

    n_labels = len(CONTEXT_LABELS)
    safe = gold_levels == 0
    n_safe = int(safe.sum())
    critical = gold_levels >= 3
    serious = gold_levels >= 2

    def measure(thr: np.ndarray) -> tuple[bool, float, float, float, float]:
        active = np.concatenate([scores >= thr, entity_active], axis=1)
        levels = levels_from_terms(compiled, active)
        warned = levels >= 1
        fw = 1000.0 * float(np.sum(warned & safe)) / n_safe if n_safe else 0.0
        l3 = float(np.mean(levels[critical] >= 3)) if critical.any() else 1.0
        l2 = float(np.mean(levels[serious] >= 2)) if serious.any() else 1.0
        feasible = (
            fw <= budget_per_1000
            and l3 >= min_level3_recall
            and l2 >= min_level2_recall
        )
        return feasible, fw, l3, l2, _macro_f1(truths, active[:, :n_labels])

    def rank(m: tuple[bool, float, float, float, float]) -> tuple:
        feasible, fw, l3, l2, f1 = m
        if feasible:
            # With a recall floor set, that floor is the requirement and the question is
            # what it costs, so take the cheapest point clearing it. Without one, the
            # budget binds and the question is what recall it buys.
            floored = min_level3_recall > 0 or min_level2_recall > 0
            return (1, -fw, l3, l2, f1) if floored else (1, l3, l2, f1, -fw)
        # Outside the feasible region, close the recall shortfall *first* and only then
        # the budget overshoot. Summing the two does not work: a rate far above budget
        # dwarfs a recall shortfall that can never exceed 1.0, so the search buys budget
        # with critical recall and arrives at silence — which is the one outcome this
        # product cannot accept. Ordering them keeps recall from being traded away.
        under_recall = max(0.0, min_level3_recall - l3) + max(0.0, min_level2_recall - l2)
        over_budget = max(0.0, fw - budget_per_1000)
        return (0, -under_recall, -over_budget, 0.0, 0.0)

    def climb(start: np.ndarray) -> tuple[np.ndarray, tuple, list[dict]]:
        thresholds = start.copy()
        best = measure(thresholds)
        history = []
        for _ in range(rounds):
            improved = False
            for i in range(n_labels):
                trial = thresholds.copy()
                for value in grid:
                    trial[i] = value
                    candidate = measure(trial)
                    if rank(candidate) > rank(best):
                        best, thresholds = candidate, trial.copy()
                        improved = True
                trial[i] = thresholds[i]
            history.append(
                {
                    "false_warnings_per_1000_safe": round(best[1], 2),
                    "level3_recall": round(best[2], 4),
                }
            )
            if not improved:
                break
        return thresholds, best, history

    starts = [
        np.full(n_labels, 0.10),   # sensitive: over budget, most criticals caught
        np.full(n_labels, 0.25),
        np.full(n_labels, 0.50),   # neutral
        np.array(
            [_best_f1_threshold(truths[:, i], scores[:, i], grid) for i in range(n_labels)]
        ),
    ]

    thresholds, best, history = None, None, []
    for start in starts:
        candidate_thresholds, candidate_best, candidate_history = climb(start)
        if best is None or rank(candidate_best) > rank(best):
            thresholds, best, history = candidate_thresholds, candidate_best, candidate_history

    feasible, fw, l3, l2, f1 = best
    return BudgetOutcome(
        thresholds={l: float(t) for l, t in zip(CONTEXT_LABELS, thresholds)},
        feasible=feasible,
        false_warnings_per_1000=fw,
        level3_recall=l3,
        at_or_above_2_recall=l2,
        macro_f1=f1,
        budget_per_1000=budget_per_1000,
        min_level3_recall=min_level3_recall,
        history=history,
    )
