"""Second families for signatures that would otherwise be train-only.

`build_dataset.assign_splits` refuses to hold out a signature's only template family,
because doing so removes the capability from training rather than removing a paraphrase
of it. The cost is that those signatures are then untestable: the manifest lists them
under `train_only_signatures` and the test set proves nothing about them.

Several of those signatures are the critical ones — location plus guardian-absent plus
time is the canonical Level 3 case. "We cannot measure generalisation on our most
important case" is not an acceptable place to leave it, so this module supplies a second,
independently-worded family for each. The wording is deliberately *not* a light edit of
the original: same meaning, different construction, different lead-in, so that holding
one out is a real test rather than a spelling variation.

Run `python -m generators.build_dataset` and check `train_only_signatures` shrank before
assuming a new family landed where it was aimed.
"""

from __future__ import annotations

from generators.render import Template
from generators.templates import L

SIBLING_TEMPLATES: list[Template] = [
    # -- child_ownership + alone ------------------------------------------------------
    Template("alone.solo.sib", "theres literally no one with me", L(child_ownership=1, alone=1), weight=1.2, tags=("sibling",)),
    Template("alone.solo.sib", "im on my own today", L(child_ownership=1, alone=1), tags=("sibling",)),
    Template("alone.solo.sib", "just me here", L(child_ownership=1, alone=1), tags=("sibling",)),

    # -- child_ownership + alone + guardian_absent ------------------------------------
    Template("alone.unsupervised.sib", "theres no adults here rn", L(child_ownership=1, alone=1, guardian_absent=1), weight=1.3, tags=("sibling",)),
    Template("alone.unsupervised.sib", "im the only one in the house", L(child_ownership=1, alone=1, guardian_absent=1), tags=("sibling",)),

    # -- child_ownership + routine ----------------------------------------------------
    Template("routine.weekly.sib", "{ACTIVITY} is on my schedule every week", L(child_ownership=1, routine=1), tags=("sibling",)),
    Template("routine.weekly.sib", "i never miss {ACTIVITY}, its every {DAY}", L(child_ownership=1, routine=1), tags=("sibling",)),

    # -- child_ownership + routine + alone --------------------------------------------
    Template("routine.solo.sib", "nobody walks with me, its like that every {DAY}", L(child_ownership=1, routine=1, alone=1), weight=1.3, tags=("sibling",)),
    Template("routine.solo.sib", "every day after school theres no one around me", L(child_ownership=1, routine=1, alone=1), tags=("sibling",)),

    # -- child_ownership + specific_time + guardian_absent ----------------------------
    Template("guardian.eta.sib", "nobodys back here before {TIME}", L(child_ownership=1, guardian_absent=1, specific_time=1), weight=1.3, tags=("sibling",)),
    Template("guardian.eta.sib", "{GUARDIAN} finishes work at {TIME}", L(child_ownership=1, guardian_absent=1, specific_time=1), tags=("sibling",)),

    # -- child_ownership + child_location + guardian_absent ---------------------------
    Template("combo.home_unwatched.sib", "nobodys watching me and im at {ADDRESS}", L(child_ownership=1, child_location=1, guardian_absent=1), weight=1.6, tags=("sibling", "combination")),
    Template("combo.home_unwatched.sib", "at {ADDRESS} with no adults around", L(child_ownership=1, child_location=1, guardian_absent=1), weight=1.5, tags=("sibling", "combination")),

    # -- child_ownership + child_location + specific_time + guardian_absent + meetup --
    Template(
        "combo.invite_unwatched.sib",
        "swing by {ADDRESS}, theres no adults here till {TIME}",
        L(child_ownership=1, child_location=1, guardian_absent=1, specific_time=1, meetup=1),
        weight=2.2,
        tags=("sibling", "combination", "critical"),
    ),
    Template("combo.invite_unwatched.sib", "you can come to {ADDRESS}, nobody gets back before {TIME}", L(child_ownership=1, child_location=1, guardian_absent=1, specific_time=1, meetup=1), weight=2.0, tags=("sibling", "combination", "critical")),

    # -- the full critical signature --------------------------------------------------
    Template(
        "combo.everything.sib",
        "nobodys here with me at {ADDRESS} till {TIME} if you wanna come",
        L(child_ownership=1, child_location=1, alone=1, specific_time=1, meetup=1, guardian_absent=1),
        weight=2.3,
        tags=("sibling", "combination", "critical"),
    ),
    Template("combo.everything.sib", "just me at {ADDRESS} until {TIME}, you should come by", L(child_ownership=1, child_location=1, alone=1, specific_time=1, meetup=1, guardian_absent=1), weight=2.2, tags=("sibling", "combination", "critical")),

    # -- child_ownership + alone + guardian_absent + meetup ---------------------------
    Template("combo.secret_invite.sib", "nobodys home, you can come by, keep it quiet", L(child_ownership=1, alone=1, guardian_absent=1, meetup=1), weight=2.0, tags=("sibling", "combination", "critical")),

    # -- school + child_location + alone ----------------------------------------------
    Template("combo.school_solo.sib", "still stuck at {SCHOOL} and theres no one left", L(child_ownership=1, school_context=1, child_location=1, alone=1), weight=1.6, tags=("sibling", "combination")),

    # -- school + routine + specific_time ---------------------------------------------
    Template("combo.school_weekly.sib", "every {DAY} {ACTIVITY} at {SCHOOL} wraps up around {TIME}", L(child_ownership=1, school_context=1, routine=1, specific_time=1), weight=1.8, tags=("sibling", "combination")),

    # -- school + location + time + meetup --------------------------------------------
    Template("combo.school_meet.sib", "ill be by the {SCHOOL} gate around {TIME}, find me there", L(child_ownership=1, school_context=1, child_location=1, specific_time=1, meetup=1), weight=2.0, tags=("sibling", "combination", "critical")),

    # -- child_ownership + specific_time + meetup -------------------------------------
    Template("meetup.timed.sib", "lets link up around {TIME}", L(child_ownership=1, meetup=1, specific_time=1), tags=("sibling",)),
    Template("meetup.timed.sib", "ill come by at {TIME}", L(child_ownership=1, meetup=1, specific_time=1), tags=("sibling",)),

    # -- meetup alone ------------------------------------------------------------------
    Template("meetup.bare.sib", "just you, no one else", L(meetup=1), tags=("sibling",)),
    Template("meetup.bare.sib", "dont bring anyone", L(meetup=1), tags=("sibling",)),

    # -- school_context alone ----------------------------------------------------------
    Template("school.mention.sib", "{SCHOOL} has a new principal", L(school_context=1), tags=("sibling",)),
    Template("school.mention.sib", "everyone at {SCHOOL} is talking about it", L(school_context=1), tags=("sibling",)),
]
