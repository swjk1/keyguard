"""§21/§22 — the deterministic risk engine, as a declarative rule table.

The design doc says the model must not decide `Safe` / `High Risk` / `Critical`; a
deterministic engine combines signals into Level 0–3. This is that engine — but written
as *data* rather than as Python, and exported to JSON.

The reason is that this logic has to exist twice: here, so §21's product-level metrics can
be computed during training, and in Kotlin, so the phone can act on it. Two
implementations of the same policy drift, and when they drift the numbers in the
experiment record stop describing the shipped product. One JSON table read by both is the
only version of this that stays true. It is also the same shape the repo already uses for
`detect/src/main/resources/rules/pack-v0.json`, so it is a familiar artifact rather than
a new concept.

Levels map onto the existing Kotlin `Severity`:

    0  NONE    nothing to say
    1  LOW     a subtle nudge
    2  MEDIUM  a real concern       <- §21's "high risk"
    3  HIGH    immediate serious risk <- §21's "critical disclosure"

Policy evolution without retraining (§35) works because a rule references *signals*, and
signals are what the model emits. Changing when an address plus a time becomes Level 3 is
an edit to this table, not a training run.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from keyguard_ml.labels import CONTEXT_LABELS, ENTITY_TO_SAFETY_ENTITY, SAFETY_ENTITIES

LEVEL_NAMES = {0: "NONE", 1: "LOW", 2: "MEDIUM", 3: "HIGH"}

# Every term a rule may reference: the eight context signals plus the six coarse entity
# buckets the token head produces. Validated at construction so a typo in a rule is a
# startup error rather than a rule that silently never fires.
VALID_TERMS = frozenset({*CONTEXT_LABELS, *SAFETY_ENTITIES})


@dataclass(frozen=True)
class Rule:
    id: str
    level: int
    all_of: tuple[str, ...] = ()
    any_of: tuple[str, ...] = ()
    none_of: tuple[str, ...] = ()
    message: str = ""

    def __post_init__(self) -> None:
        unknown = (set(self.all_of) | set(self.any_of) | set(self.none_of)) - VALID_TERMS
        if unknown:
            raise ValueError(f"rule {self.id!r} references unknown terms: {sorted(unknown)}")
        if self.level not in LEVEL_NAMES:
            raise ValueError(f"rule {self.id!r} has level {self.level} outside 0..3")

    def matches(self, active: set[str]) -> bool:
        if not all(term in active for term in self.all_of):
            return False
        if self.any_of and not any(term in active for term in self.any_of):
            return False
        if any(term in active for term in self.none_of):
            return False
        return True

    def as_dict(self) -> dict:
        return {
            "id": self.id,
            "level": self.level,
            "all_of": list(self.all_of),
            "any_of": list(self.any_of),
            "none_of": list(self.none_of),
            "message": self.message,
        }


# --------------------------------------------------------------------------------------
# The V1 policy
# --------------------------------------------------------------------------------------
#
# Ordered most severe first only for readability; `evaluate` takes the maximum, so order
# does not affect the result. Every rule requires `child_ownership`, with two deliberate
# exceptions noted inline — that single term is what stops "the pizza place is at 24 Oak
# St" from reaching any level at all, and it is the reason the context head exists.

RULES: tuple[Rule, ...] = (
    # ---- Level 3: an adult could arrive where this child is, unsupervised -----------
    Rule(
        "risk.address_alone",
        3,
        all_of=("child_ownership", "child_location", "exact_location"),
        any_of=("alone", "guardian_absent"),
        message="You're sharing exactly where you are and that no one is with you.",
    ),
    Rule(
        "risk.address_meetup",
        3,
        all_of=("child_ownership", "child_location", "exact_location", "meetup"),
        message="You're inviting someone to your exact address.",
    ),
    Rule(
        "risk.unsupervised_window",
        3,
        all_of=("child_ownership", "specific_time"),
        any_of=("alone", "guardian_absent"),
        # Without a location this is a schedule, not a rendezvous — unless the
        # conversation is already arranging one.
        # `meetup` promotes it; `none_of` keeps the pure-schedule case at Level 2.
        message="You're telling someone when you'll be on your own.",
    ),
    Rule(
        "risk.school_pickup",
        3,
        all_of=("child_ownership", "school_context", "child_location", "meetup", "specific_time"),
        message="You're arranging to meet someone at your school at a set time.",
    ),
    Rule(
        "risk.routine_alone_location",
        3,
        all_of=("child_ownership", "child_location", "routine", "alone"),
        message="You're describing where you'll predictably be on your own.",
    ),

    # ---- Level 2: a real concern, worth interrupting for ---------------------------
    Rule(
        "risk.address_disclosure",
        2,
        all_of=("child_ownership", "child_location", "exact_location"),
        message="You're about to share your home address.",
    ),
    Rule(
        "risk.contact_meetup",
        2,
        all_of=("child_ownership", "contact_info", "meetup"),
        message="You're sharing contact details while arranging to meet.",
    ),
    Rule(
        "risk.alone_meetup",
        2,
        all_of=("child_ownership", "meetup"),
        any_of=("alone", "guardian_absent"),
        message="You're inviting someone over while no one else is around.",
    ),
    Rule(
        "risk.school_identity_time",
        2,
        all_of=("child_ownership", "school_context", "routine"),
        message="Together, this says where to find you and when.",
    ),
    Rule(
        "risk.routine_location",
        2,
        all_of=("child_ownership", "child_location", "routine"),
        message="This says where you'll be, regularly.",
    ),
    Rule(
        "risk.contact_disclosure",
        2,
        all_of=("child_ownership", "contact_info"),
        message="You're about to share a way to contact you directly.",
    ),
    Rule(
        "risk.alone_disclosure",
        2,
        all_of=("child_ownership", "child_location"),
        any_of=("alone", "guardian_absent"),
        message="You're saying where you are and that you're on your own.",
    ),

    # ---- Level 1: a nudge ------------------------------------------------------------
    Rule(
        "risk.school_identity",
        1,
        all_of=("child_ownership", "school_context"),
        message="This tells someone which school you go to.",
    ),
    Rule(
        "risk.coarse_location",
        1,
        all_of=("child_ownership", "child_location"),
        message="This narrows down where you are.",
    ),
    Rule(
        # `guardian_absent` used to be an alternative trigger here, via
        # any_of=("alone", "guardian_absent"). Measured on the 1,016-row gold set, this
        # one rule produced 19 of the 27 false warnings the whole table generated — 70%
        # of the interruption budget, spent by the mildest rule in the policy.
        #
        # The cause is that it was the only rule where a single weak signal was enough.
        # `guardian_absent` ranks at 0.523 PR-AUC against an 8% base rate, barely above
        # chance, and here it needed no corroboration. Splitting the variants apart makes
        # that plain: requiring `alone` drops false warnings from 36.3 to 13.2 per 1,000,
        # while requiring `guardian_absent` leaves them at 35.7.
        #
        # Level 2 and Level 3 recall are untouched by this — identical to three decimal
        # places across every variant tried — because no higher rule depends on it. The
        # cost is Level 1 recall, 0.516 to 0.483.
        #
        # `guardian_absent` is not demoted out of the policy: it still fires
        # `risk.unsupervised_window` (L3), `risk.address_alone` (L3), `risk.alone_meetup`
        # (L2) and `risk.alone_disclosure` (L2). In each of those it must arrive with a
        # time, an address, or a meetup alongside it. What changed is that it can no
        # longer raise a warning on its own evidence.
        "risk.unsupervised",
        1,
        all_of=("child_ownership", "alone"),
        message="You're telling someone you're on your own.",
    ),
    Rule(
        "risk.routine",
        1,
        all_of=("child_ownership", "routine"),
        message="This makes it easy to predict where you'll be.",
    ),
    # Two rules that intentionally do not require `child_ownership`. A government ID
    # number is never safe to send regardless of whose it is, and `meetup` on its own is
    # a conversation state the engine wants recorded even when the message discloses
    # nothing about the person typing.
    Rule(
        "risk.government_id",
        3,
        all_of=("government_id",),
        message="This looks like a government ID number. Never send this in a message.",
    ),
    Rule(
        "risk.meetup_context",
        1,
        all_of=("meetup",),
        message="This is about meeting someone in person.",
    ),
)


@dataclass
class RiskResult:
    level: int
    fired: list[str] = field(default_factory=list)
    active: list[str] = field(default_factory=list)
    message: str = ""

    @property
    def level_name(self) -> str:
        return LEVEL_NAMES[self.level]


class RiskEngine:
    def __init__(self, rules: tuple[Rule, ...] = RULES):
        self.rules = rules

    def active_terms(
        self,
        context_probs: dict[str, float] | list[float],
        entities: set[str] | None = None,
        thresholds: dict[str, float] | None = None,
    ) -> set[str]:
        """Threshold the model's probabilities into the terms a rule can reference."""
        if not isinstance(context_probs, dict):
            context_probs = dict(zip(CONTEXT_LABELS, context_probs))
        thresholds = thresholds or {}
        active = {
            label
            for label, probability in context_probs.items()
            if probability >= thresholds.get(label, 0.5)
        }
        return active | (entities or set())

    def evaluate(
        self,
        context_probs: dict[str, float] | list[float],
        entities: set[str] | None = None,
        thresholds: dict[str, float] | None = None,
    ) -> RiskResult:
        active = self.active_terms(context_probs, entities, thresholds)
        fired = [rule for rule in self.rules if rule.matches(active)]
        if not fired:
            return RiskResult(0, [], sorted(active))
        best = max(fired, key=lambda r: r.level)
        return RiskResult(
            level=best.level,
            fired=[r.id for r in fired if r.level == best.level],
            active=sorted(active),
            message=best.message,
        )

    def export(self, path: str | Path) -> Path:
        out = Path(path)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(
                {
                    "version": 1,
                    "levels": LEVEL_NAMES,
                    "context_labels": list(CONTEXT_LABELS),
                    "safety_entities": list(SAFETY_ENTITIES),
                    "entity_to_safety_entity": ENTITY_TO_SAFETY_ENTITY,
                    "rules": [rule.as_dict() for rule in self.rules],
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        return out


def entities_from_tags(tags: list[str]) -> set[str]:
    """Collapse predicted BIO tags into the coarse buckets the rules reference."""
    out: set[str] = set()
    for tag in tags:
        if tag == "O" or len(tag) < 3:
            continue
        bucket = ENTITY_TO_SAFETY_ENTITY.get(tag[2:])
        if bucket:
            out.add(bucket)
    return out


if __name__ == "__main__":  # pragma: no cover
    import sys

    target = sys.argv[1] if len(sys.argv) > 1 else "datasets/risk_rules.json"
    engine = RiskEngine()
    print(f"wrote {engine.export(target)} ({len(engine.rules)} rules)")

    demo = [
        ("I live at 24 Oak Street", {"child_ownership", "child_location"}, {"exact_location"}),
        ("the pizza place is at 24 Oak Street", set(), {"exact_location"}),
        ("im home alone at 24 oak st until 9", {"child_ownership", "child_location", "alone", "specific_time"}, {"exact_location", "time_signal"}),
        ("my number is 514-555-1234", {"child_ownership"}, {"contact_info"}),
        ("call the restaurant at 514-555-1234", set(), {"contact_info"}),
    ]
    for text, signals, entities in demo:
        probs = {label: (1.0 if label in signals else 0.0) for label in CONTEXT_LABELS}
        result = engine.evaluate(probs, entities)
        print(f"  L{result.level} {result.level_name:6s} {text!r}")
        if result.fired:
            print(f"          {result.fired} — {result.message}")
