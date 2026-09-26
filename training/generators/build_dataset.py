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

Holding families out correctly is necessary and not sufficient. It decides *which*
constructions each split contains and says nothing about the distribution that results,
and §22 fits the shipped per-label thresholds on one split and reports them on another. So
after the holdout constraints are satisfied, a balance pass pulls the eval splits onto
train along three distributions at once: label marginals, the risk-level histogram, and
per-rule match rates. All three, because they fail differently and independently — an
earlier version matched marginals alone and produced splits where validation reached
Level 3 by `risk.address_alone` and test reached it by `risk.address_meetup`, so a
`meetup` threshold that cost validation nothing cost test 30% of its Level 3 recall. The
build then asserts the result rather than trusting the search: a level histogram out of
line with train, or a well-represented rule no evaluation row exercises, fails it.

One distribution is measured and reported but deliberately not optimised: which signals
the Level 3 rows rest on. Adding it to the search made it worse, and no weighting brought
it under about 40% — with 105 families over 16 signature buckets and an anchor required in
train, the freedom to equalise it does not exist. That is a shortage of template families,
and the build says so on every run.
"""

from __future__ import annotations

import argparse
import json
import random
from collections import Counter, defaultdict
from dataclasses import dataclass
from functools import lru_cache
from itertools import combinations
from pathlib import Path

from generators.combinations import COMBO_TEMPLATES
from generators.hard_negatives import HARD_NEGATIVE_TEMPLATES
from generators.lexicon import SLOT_DISPATCH, Lexicon
from generators.render import (
    GeneratedExample,
    Template,
    assemble,
    render_chunks,
    slots_in,
    verify_spans,
)
from generators.generated_templates import GENERATED_GROUPS, GENERATED_TEMPLATES
from generators.slang import augment
from generators.siblings import SIBLING_TEMPLATES
from generators.templates import SAFE_TEMPLATES, SINGLE_SIGNAL_TEMPLATES
from keyguard_ml.labels import CONTEXT_LABELS, ENTITY_TO_SAFETY_ENTITY
from keyguard_ml.risk_engine import RULES, RiskEngine

SPLITS = ("train", "validation", "test")

# Every eval split's share of rows at each risk level must sit this close to train's.
# 0.05 is wide enough to absorb the jitter of drawing from a weighted pool and narrow
# enough to have caught the split that shipped: validation was 22.7% Level 3 against
# test's 18.4% and, far worse, disagreed completely about which rules produced them.
MAX_LEVEL_GAP = 0.05

# A rule matching at least this share of train rows, but no evaluation row at all, is a
# policy branch the report cannot say anything about: `risk.school_pickup` had 427 train
# matches and zero in either eval split, so nothing measured whether the model could
# reach Level 3 that way. A share rather than a count, so `--scale 0.1` asserts the same
# property as a full build rather than a tenth of one. 0.004 of 48,000 train rows is
# ~190, which is about where a rule stops being a rounding error in the level metrics.
RULE_EVAL_FLOOR_RATE = 0.004


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


def _generated(group: str) -> list[Template]:
    """Model-written patterns for one group (§9, `generators/llm_expand.py`).

    They are ordinary templates — same dataclass, same slots, same holdout unit — and are
    mixed into the hand-written ones rather than kept in a group of their own. Splitting
    them out would let the family holdout put every generated way of saying a thing on one
    side of the split, which is the opposite of what they are for: the corpus failed on
    real text because it knew too few constructions, so the constructions have to be
    spread across train and test like any other.
    """
    return [t for t in GENERATED_TEMPLATES if GENERATED_GROUPS.get(t.family) == group]


def default_groups(scale: float = 1.0) -> list[GroupSpec]:
    """§13's table. `scale` shrinks everything proportionally for smoke runs."""
    return [
        GroupSpec("single_signal",
                  [*SINGLE_SIGNAL_TEMPLATES, *_single_siblings(), *_generated("single_signal")],
                  int(10_000 * scale)),
        GroupSpec("combination",
                  [*COMBO_TEMPLATES, *_combo_siblings(), *_generated("combination")],
                  int(15_000 * scale)),
        GroupSpec("hard_negative",
                  [*HARD_NEGATIVE_TEMPLATES, *_generated("hard_negative")],
                  int(15_000 * scale)),
        GroupSpec("safe", [*SAFE_TEMPLATES, *_generated("safe")], int(10_000 * scale)),
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
) -> tuple[dict[str, str], list[str], dict[str, float]]:
    """Map family -> split. Returns the mapping, the train-only signatures, and how far
    the balance pass moved the splits together.

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

    balance = _balance_split_distributions(
        assignment, groups, primary_group, family_signature, by_signature, val_ratio, test_ratio
    )

    return assignment, sorted(set(train_only)), balance


# --------------------------------------------------------------------------------------
# What a template is worth to the risk engine
# --------------------------------------------------------------------------------------
#
# The balancer below equalises distributions across splits, and *which* distribution it
# equalises is the whole question. Per-label positive rates are the obvious answer and
# they are not sufficient: every rule in the risk engine is a conjunction, so the level a
# message reaches depends on the joint label vector plus which entity buckets are present.
# Two splits can agree on all eight marginals and still disagree completely about what a
# Level 3 row is made of. They did — validation's Level 3 rows were 89% `risk.address_alone`
# and test's were 100% `risk.address_meetup` — and that is not a cosmetic difference,
# because §22 fits per-label thresholds against the product-level objective on one split
# and ships them. Suppressing `meetup` cost validation no Level 3 recall at all and cost
# test 30% of it, so the calibrator set `meetup` to 0.975 for free precision and Level 3
# recall fell from 0.989 to 0.435 between two splits of the same generator.
#
# A template's level is computable without generating anything: its label vector is fixed
# and its slots determine which entity buckets it can produce. So each template is scored
# once, here, and the search below matches level and rule histograms as well as marginals.

_ENGINE = RiskEngine()
_RULE_IDS: tuple[str, ...] = tuple(rule.id for rule in RULES)
_LEVELS: tuple[int, ...] = (0, 1, 2, 3)

_PROBE_SEED = 913
_PROBE_DRAWS = 24

# Layout of the feature vector the search works on: marginals, then the level histogram,
# then per-rule match rates. One flat vector so a candidate assignment is scored with one
# pass of arithmetic rather than three.
_LABEL_SLICE = slice(0, len(CONTEXT_LABELS))
_LEVEL_SLICE = slice(len(CONTEXT_LABELS), len(CONTEXT_LABELS) + len(_LEVELS))
_RULE_SLICE = slice(_LEVEL_SLICE.stop, _LEVEL_SLICE.stop + len(_RULE_IDS))
_DIM = _RULE_SLICE.stop

# Relative importance of the three components, each already normalised to a mean gap per
# term so the 18 rules cannot outvote the 8 labels by sheer count. Levels weigh most
# because the level is what §22 calibrates against and what the phone acts on; rule shares
# weigh more than marginals because they are what distinguishes two splits that agree on
# every marginal and still mean different things.
# Swept, not guessed. At (1, 3, 2) the level histogram came out flat but `alone` sat at
# 0.241 of train and 0.094 of validation; doubling the label weight pulls the worst
# marginal gap from 0.148 to 0.091 and costs 0.005 on the worst level gap, which is
# well inside MAX_LEVEL_GAP. Rule coverage is unchanged at either setting.
_W_LABEL, _W_LEVEL, _W_RULE = 2.0, 3.0, 2.0

# Coverage is scored separately from rule *rates* because the two fail at different
# magnitudes. A rule that is 9% of train and 0% of validation contributes about 0.005 to
# a mean-gap term — invisible next to the level histogram — while being exactly the
# defect that matters: `risk.address_alone` was 9% of train rows and absent from the
# split the thresholds are fitted on. Presence is therefore counted, not averaged.
_W_COVERAGE = 2.0
_COVERAGE_EPS = 1e-9


@lru_cache(maxsize=1)
def slot_buckets() -> dict[str, frozenset[str]]:
    """Slot name -> the safety-entity buckets it can produce.

    Probed from the lexicon rather than written out by hand: a second copy of
    "{PHONE} means contact_info" is a second thing to forget when a slot changes. Drawn
    repeatedly and unioned, so a slot whose entity varied between draws is described by
    everything it can emit rather than by whichever draw happened to come first.
    """
    lex = Lexicon(_PROBE_SEED)
    out: dict[str, frozenset[str]] = {}
    for slot, method in SLOT_DISPATCH.items():
        seen: set[str] = set()
        for _ in range(_PROBE_DRAWS):
            value = getattr(lex, method)()
            entities = [e for _, _, e in value.parts] if value.parts else [value.entity]
            seen |= {ENTITY_TO_SAFETY_ENTITY[e] for e in entities if e in ENTITY_TO_SAFETY_ENTITY}
        out[slot] = frozenset(seen)
    return out


def template_terms(template: Template) -> set[str]:
    """Everything a rule could match against in a row this template produces."""
    buckets = slot_buckets()
    terms = {label for label, value in template.labels.items() if value}
    for slot in slots_in(template.pattern):
        terms |= buckets.get(slot, frozenset())
    return terms


def template_rules(template: Template) -> tuple[str, ...]:
    """Every rule the template matches — not just the ones at its maximum level.

    `RiskEngine.evaluate` reports only the rules that set the level, which is right for a
    decision and wrong for coverage: a Level 2 rule that only ever fires underneath a
    Level 3 rule is still a branch of the policy that an eval split should exercise.
    """
    terms = template_terms(template)
    return tuple(rule.id for rule in RULES if rule.matches(terms))


def _level_of(terms: set[str]) -> int:
    return max((rule.level for rule in RULES if rule.matches(terms)), default=0)


def template_level(template: Template) -> int:
    return _level_of(template_terms(template))


@dataclass(frozen=True)
class _Contribution:
    """One family's aggregate inside one group, precomputed once.

    The search rescores thousands of candidate assignments. Doing that over ~1,700
    templates each time is what made a swap pass unaffordable and left the hill-climb
    stuck in the first local minimum it found. Every quantity it needs is a weighted sum
    over the family's templates, and none of those change as families move between splits
    — only which split they land in does.
    """

    group: str
    family: str
    weight: float
    vector: tuple[float, ...]


def _contributions(groups: list[GroupSpec]) -> dict[str, list[_Contribution]]:
    """Per-group, per-family aggregates.

    Note there is no `primary_group` filter here, deliberately. An earlier version counted
    a template only towards the group that owned its family, mirroring how families are
    *assigned*. But `build` fills a group from every template in it, owned or not, and the
    `slang_variant` group is built entirely from families owned by other groups — so the
    balancer was optimising a model of 50,000 rows while 10,000 were written by a pass it
    could not see, and those 10,000 were 15%/27%/13% Level 3 across the three splits.
    """
    out: dict[str, list[_Contribution]] = {}
    for group in groups:
        vectors: dict[str, list[float]] = {}
        weights: dict[str, float] = {}
        for template in group.templates:
            vector = vectors.setdefault(template.family, [0.0] * _DIM)
            weights[template.family] = weights.get(template.family, 0.0) + template.weight
            for i, label in enumerate(CONTEXT_LABELS):
                if template.labels.get(label):
                    vector[_LABEL_SLICE.start + i] += template.weight
            terms = template_terms(template)
            level = _level_of(terms)
            vector[_LEVEL_SLICE.start + level] += template.weight
            fired = {rule.id for rule in RULES if rule.matches(terms)}
            for i, rule_id in enumerate(_RULE_IDS):
                if rule_id in fired:
                    vector[_RULE_SLICE.start + i] += template.weight
        out[group.name] = [
            _Contribution(group.name, family, weights[family], tuple(vector))
            for family, vector in vectors.items()
        ]
    return out


class _SplitDistribution:
    """Incremental per-split feature vector.

    Rates are recomputed from per-(group, split) running totals rather than from the
    templates, so a trial move costs one subtraction and one addition per group the family
    appears in instead of a full pass over the corpus specification.
    """

    def __init__(
        self,
        contributions: dict[str, list[_Contribution]],
        targets: dict[str, int],
        assignment: dict[str, str],
        val_ratio: float,
        test_ratio: float,
    ) -> None:
        self.targets = targets
        self.shares = {
            "train": 1 - val_ratio - test_ratio,
            "validation": val_ratio,
            "test": test_ratio,
        }
        self.by_family: dict[str, list[tuple[str, float, tuple[float, ...]]]] = defaultdict(list)
        self.weight: dict[str, dict[str, float]] = {g: {s: 0.0 for s in SPLITS} for g in targets}
        self.vector: dict[str, dict[str, list[float]]] = {
            g: {s: [0.0] * _DIM for s in SPLITS} for g in targets
        }
        for group_name, contribs in contributions.items():
            for contribution in contribs:
                self.by_family[contribution.family].append(
                    (group_name, contribution.weight, contribution.vector)
                )
                split = assignment[contribution.family]
                self.weight[group_name][split] += contribution.weight
                destination = self.vector[group_name][split]
                for i, value in enumerate(contribution.vector):
                    if value:
                        destination[i] += value

    def move(self, family: str, origin: str, target: str) -> None:
        for group_name, weight, vector in self.by_family[family]:
            self.weight[group_name][origin] -= weight
            self.weight[group_name][target] += weight
            out_of, into = self.vector[group_name][origin], self.vector[group_name][target]
            for i, value in enumerate(vector):
                if value:
                    out_of[i] -= value
                    into[i] += value

    def rates(self) -> dict[str, list[float]]:
        totals = {s: 0.0 for s in SPLITS}
        sums = {s: [0.0] * _DIM for s in SPLITS}
        for group_name, target in self.targets.items():
            for split in SPLITS:
                pool_weight = self.weight[group_name][split]
                if pool_weight <= 0:
                    continue
                quota = target * self.shares[split]
                totals[split] += quota
                scale = quota / pool_weight
                destination, source = sums[split], self.vector[group_name][split]
                for i, value in enumerate(source):
                    if value:
                        destination[i] += value * scale
        return {s: [v / totals[s] if totals[s] else 0.0 for v in sums[s]] for s in SPLITS}


def _divergence(rates: dict[str, list[float]]) -> float:
    """How far the eval splits sit from train, across all three distributions.

    Train is the reference because it is the largest split and the one the operating
    points have to generalise *from*; pulling validation and test onto it is what makes a
    threshold fitted on validation mean something on test. Each component is averaged over
    its own terms before weighting, so adding rules to the policy does not silently
    increase their say.
    """
    reference = rates["train"]
    total = 0.0
    for component, weight in (
        (_LABEL_SLICE, _W_LABEL),
        (_LEVEL_SLICE, _W_LEVEL),
        (_RULE_SLICE, _W_RULE),
    ):
        width = component.stop - component.start
        gap = sum(
            abs(rates[split][i] - reference[i])
            for split in ("validation", "test")
            for i in range(component.start, component.stop)
        )
        total += weight * gap / (2 * width)

    # Symmetric on purpose. A rule in train and not in an eval split cannot be measured;
    # a rule in an eval split and not in train is measured against a route the model was
    # never taught, which is how `risk.contact_meetup` came to be 2.2% of validation and
    # 0% of train.
    missing = present = 0
    for i in range(_RULE_SLICE.start, _RULE_SLICE.stop):
        values = [rates[split][i] for split in SPLITS]
        if max(values) <= _COVERAGE_EPS:
            continue
        present += 1
        missing += sum(1 for value in values if value <= _COVERAGE_EPS)
    if present:
        total += _W_COVERAGE * missing / (len(SPLITS) * present)
    return total


def _balance_split_distributions(
    assignment: dict[str, str],
    groups: list[GroupSpec],
    primary_group: dict[str, str],
    family_signature: dict[str, tuple[int, ...]],
    by_signature: dict[tuple[int, ...], list[str]],
    val_ratio: float = 0.1,
    test_ratio: float = 0.1,
    max_rounds: int = 200,
) -> dict[str, float]:
    """Constraint 4: the eval splits must look like train *to the risk engine*, not merely
    contain the same labels (constraint 3).

    Holding out whole families is right for measuring generalisation, but it says nothing
    about how many positives of each label land where, nor — the part an earlier version
    missed — about which *combinations* land where. Both matter and they fail differently:
    a marginal that is off makes a per-label threshold meaningless on another split, while
    a rule composition that is off makes the whole product-level calibration meaningless.

    Greedy over single family moves first, then over pairwise swaps once no single move
    helps. The swap pass is what the earlier version lacked and what it needed: with 105
    families spread over 16 signature buckets, the families asserting `meetup` split 14
    train / 2 validation / 4 test, and no *single* move could correct that without
    breaking a constraint or making some other label worse. A swap is also size-preserving
    by construction, so it cannot quietly hollow out train.

    Moves that would violate constraints 1-3 are rejected, so this can only tighten the
    split, never invalidate it. Returns the before/after divergence for the manifest.
    """
    contributions = _contributions(groups)
    targets = {group.name: group.target for group in groups}
    distribution = _SplitDistribution(contributions, targets, assignment, val_ratio, test_ratio)

    counts_by_split: Counter = Counter(assignment.values())
    group_families: dict[str, list[str]] = defaultdict(list)
    for family, group_name in primary_group.items():
        group_families[group_name].append(family)
    label_positives = [
        [f for f, sig in family_signature.items() if sig[i]] for i in range(len(CONTEXT_LABELS))
    ]
    original = dict(assignment)

    def violates(candidate: dict[str, str]) -> bool:
        # 1: every signature keeps an anchor in train.
        for families_in_signature in by_signature.values():
            if not any(candidate[f] == "train" for f in families_in_signature):
                return True
        # 2: no group loses an eval slice it previously had.
        for group_name, group_family_list in group_families.items():
            if len(group_family_list) < 4:
                continue
            for split in ("validation", "test"):
                had = any(original[f] == split for f in group_family_list)
                if had and not any(candidate[f] == split for f in group_family_list):
                    return True
        # 3: every label stays measurable in both eval splits.
        for positives in label_positives:
            if not positives:
                continue
            for split in ("validation", "test"):
                if not any(candidate[f] == split for f in positives):
                    return True
        return False

    families = sorted(assignment)
    start = _divergence(distribution.rates())
    current = start

    for _ in range(max_rounds):
        best_moves: tuple[tuple[str, str, str], ...] | None = None
        best_score = current - 1e-9

        for family in families:
            origin = assignment[family]
            for target in SPLITS:
                if target == origin:
                    continue
                assignment[family] = target
                # A balance pass that empties train would trade one distortion for a worse
                # one; single moves change the split sizes, so they are checked here.
                sized = Counter(assignment.values())["train"] >= 0.5 * counts_by_split["train"]
                if sized and not violates(assignment):
                    distribution.move(family, origin, target)
                    score = _divergence(distribution.rates())
                    distribution.move(family, target, origin)
                    if score < best_score:
                        best_score, best_moves = score, ((family, origin, target),)
                assignment[family] = origin

        if best_moves is None:
            for first, second in combinations(families, 2):
                origin_a, origin_b = assignment[first], assignment[second]
                if origin_a == origin_b:
                    continue
                assignment[first], assignment[second] = origin_b, origin_a
                if not violates(assignment):
                    distribution.move(first, origin_a, origin_b)
                    distribution.move(second, origin_b, origin_a)
                    score = _divergence(distribution.rates())
                    distribution.move(first, origin_b, origin_a)
                    distribution.move(second, origin_a, origin_b)
                    if score < best_score:
                        best_score = score
                        best_moves = (
                            (first, origin_a, origin_b),
                            (second, origin_b, origin_a),
                        )
                assignment[first], assignment[second] = origin_a, origin_b

        if best_moves is None:
            break
        for family, origin, target in best_moves:
            assignment[family] = target
            distribution.move(family, origin, target)
        current = best_score

    return {"divergence_before": round(start, 5), "divergence_after": round(current, 5)}


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
    assignment, train_only, balance = assign_splits(
        groups, random.Random(seed + 1), val_ratio, test_ratio
    )

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
    # Measured on the rows actually written rather than on the templates, because
    # rendering and augmentation are what decide whether an entity survives into the
    # text. The template-level scoring above steers the split; this checks the result.
    level_counts: dict[str, Counter] = {s: Counter() for s in SPLITS}
    rule_counts: dict[str, Counter] = {s: Counter() for s in SPLITS}
    load_bearing: dict[str, Counter] = {s: Counter() for s in SPLITS}
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
                    terms = {k for k, v in example.labels.items() if v} | {
                        ENTITY_TO_SAFETY_ENTITY[span["label"]]
                        for span in example.spans
                        if span["label"] in ENTITY_TO_SAFETY_ENTITY
                    }
                    matched = [rule for rule in RULES if rule.matches(terms)]
                    row_level = max((r.level for r in matched), default=0)
                    level_counts[split][row_level] += 1
                    for rule in matched:
                        rule_counts[split][rule.id] += 1
                    if row_level >= 3:
                        for label, value in example.labels.items():
                            if value and _level_of(terms - {label}) < 3:
                                load_bearing[split][label] += 1
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

    # The balance pass pulls the eval splits' label rates onto train's, so a large gap
    # here is a failure rather than an expected consequence of family holdout. What it
    # cannot fix is absolute count: a label balanced to the right *rate* in a 6,000-row
    # split can still have too few positives to fit a threshold against anything but
    # noise, and §22's calibration inherits that threshold.
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

    level_rates = {
        split: {
            f"level_{level}": round(level_counts[split][level] / max(1, sum(counts[split].values())), 4)
            for level in _LEVELS
        }
        for split in SPLITS
    }

    # A split whose level histogram does not match train's is a split the operating
    # points cannot be fitted on. §22 chooses per-label thresholds against Level 3
    # recall and a false-warning budget measured on one split and ships them to a
    # phone; if validation is 22.7% Level 3 and test is 18.4%, the number the pipeline
    # reports is not the number the model will produce. Fail rather than write it.
    skewed = {
        split: {
            level: round(level_rates[split][level] - level_rates["train"][level], 4)
            for level in level_rates[split]
            if abs(level_rates[split][level] - level_rates["train"][level]) > MAX_LEVEL_GAP
        }
        for split in ("validation", "test")
    }
    skewed = {split: gaps for split, gaps in skewed.items() if gaps}
    assert not skewed, (
        f"risk level distribution diverges from train by more than {MAX_LEVEL_GAP}: "
        f"{skewed}. The split assignment could not be balanced; add template families "
        f"for the under-represented levels, or relax MAX_LEVEL_GAP knowing that "
        f"thresholds fitted on validation will not hold on test."
    )

    # Which signals the Level 3 rows actually rest on. Reported, not asserted, and not
    # part of the balance objective, because it is demonstrably not the splitter's to
    # fix: adding it to the search moved the worst gap from 39% to 58% while the level
    # histogram tightened, and every objective tried bottomed out between 42% and 57%.
    # With 105 families over 16 signature buckets and an anchor required in train, the
    # freedom to equalise this does not exist — it needs more families, not better moves.
    # It is the number to watch after adding any, and the reason §22's thresholds still
    # must be sanity-checked against `report_test.json` rather than trusted from
    # validation alone.
    level3_load_bearing = {
        split: {
            label: round(load_bearing[split][label] / max(1, level_counts[split][3]), 4)
            for label in CONTEXT_LABELS
        }
        for split in SPLITS
    }
    # Spread across all three splits, not just validation against test. `alone` came out
    # load-bearing for 57.7% of train's Level 3 rows and 0% of validation's, which the
    # calibration split therefore cannot say anything about at all — a different failure
    # from validation and test disagreeing, and an equally quiet one.
    load_bearing_gaps = {
        label: round(
            max(level3_load_bearing[s][label] for s in SPLITS)
            - min(level3_load_bearing[s][label] for s in SPLITS),
            4,
        )
        for label in CONTEXT_LABELS
    }

    # Matching histograms is not the same as matching composition: two splits can hold
    # equal shares of Level 3 and reach it by entirely different rules.
    train_rows = max(1, sum(counts["train"].values()))
    unexercised = {
        rule.id: {split: rule_counts[split][rule.id] for split in SPLITS}
        for rule in RULES
        if rule_counts["train"][rule.id] >= RULE_EVAL_FLOOR_RATE * train_rows
        and not rule_counts["validation"][rule.id]
        and not rule_counts["test"][rule.id]
    }
    assert not unexercised, (
        f"rules matching at least {RULE_EVAL_FLOOR_RATE:.1%} of train rows but no "
        f"evaluation row at all: {unexercised}. Nothing measures whether the model can "
        f"reach those levels by those routes. Add a second template family with the "
        f"same signature so the family holdout has something to hold out."
    )

    # One step weaker, and a warning rather than an assert: a rule present in some splits
    # and not others. This is the shape that produced the `meetup` failure — absent from
    # validation, 100% of test's Level 3 rows — so it is worth naming every time. It is
    # not an assert because it is not always the splitter's to fix: `risk.school_pickup`
    # has two template families in the entire corpus, one of which must anchor train, so
    # no assignment can put it in both eval splits. That needs a family, not a move.
    asymmetric = {
        rule.id: {split: rule_counts[split][rule.id] for split in SPLITS}
        for rule in RULES
        if any(rule_counts[s][rule.id] for s in SPLITS)
        and not all(rule_counts[s][rule.id] for s in SPLITS)
    }

    # A rule that matches nothing anywhere is a different problem and not one the
    # splitter can fix — no arrangement of families creates a row that does not exist.
    # Reported rather than asserted, because the fix is new templates or a policy edit.
    dead_rules = [
        rule.id for rule in RULES if not any(rule_counts[s][rule.id] for s in SPLITS)
    ]

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
        "level_rates": level_rates,
        "max_level_gap": MAX_LEVEL_GAP,
        "rule_match_counts": {
            s: {rule.id: rule_counts[s][rule.id] for rule in RULES} for s in SPLITS
        },
        "level3_load_bearing": level3_load_bearing,
        "level3_load_bearing_gaps": load_bearing_gaps,
        "rule_eval_floor_rate": RULE_EVAL_FLOOR_RATE,
        "asymmetric_rules": asymmetric,
        "dead_rules": dead_rules,
        "split_balance": balance,
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
    print(
        "split divergence: "
        f"{manifest['split_balance']['divergence_before']:.4f} -> "
        f"{manifest['split_balance']['divergence_after']:.4f}"
    )
    print("risk level distribution:")
    header = "".join(f"{f'L{level}':>9s}" for level in _LEVELS)
    print(f"    {'':11s}{header}")
    for split in SPLITS:
        row = "".join(f"{manifest['level_rates'][split][f'level_{level}']:>9.1%}" for level in _LEVELS)
        print(f"    {split:11s}{row}")
    print("share of each split's Level 3 rows that rest on a single signal:")
    print(f"    {'signal':18s}{'train':>9s}{'val':>9s}{'test':>9s}{'spread':>10s}")
    for label in CONTEXT_LABELS:
        shares = manifest["level3_load_bearing"]
        gap = manifest["level3_load_bearing_gaps"][label]
        mark = "  <-- " if gap > 0.25 else ""
        print(
            f"    {label:18s}{shares['train'][label]:9.1%}{shares['validation'][label]:9.1%}"
            f"{shares['test'][label]:9.1%}{gap:10.1%}{mark}"
        )
    if any(gap > 0.25 for gap in manifest["level3_load_bearing_gaps"].values()):
        print(
            "  Marked signals carry very different shares of Level 3 in the split the\n"
            "  thresholds are fitted on and the split they are reported on. The\n"
            "  calibrator will treat a signal that is free on validation as free, so\n"
            "  read report_test.json next to the calibration numbers. Closing this\n"
            "  needs more template families, not a different split."
        )
    if manifest["asymmetric_rules"]:
        print(
            "\nWARNING: risk rules that fire in some splits and not others — a "
            "threshold fitted on a split that never sees one cannot be trusted on a "
            "split that does:"
        )
        for rule_id, per_split in manifest["asymmetric_rules"].items():
            shown = " ".join(f"{split}={per_split[split]}" for split in SPLITS)
            print(f"    {rule_id:32s} {shown}")
        print("  Fix by adding a sibling family with the same signature.")
    if manifest["dead_rules"]:
        print(
            "\nWARNING: risk rules no generated row matches — nothing in the corpus "
            "exercises them:"
        )
        for rule_id in manifest["dead_rules"]:
            print(f"    {rule_id}")
        print("  Only the gold set measures these. Add template families asserting them.")
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
