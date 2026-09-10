"""§10 — dangerous combinations, generated explicitly rather than hoped for.

The reason this file exists separately: if combinations only appeared by chance, their
frequency would be the product of the individual signal rates, which for a four-signal
case is a rounding error. The model would then be excellent at every signal in isolation
and unreliable at exactly the messages the product exists to interrupt.

These families are oversampled (§10) and they are the ones Level 3 recall is measured on.
"""

from __future__ import annotations

from generators.render import Template
from generators.templates import L

# ======================================================================================
# Location + alone
# ======================================================================================

COMBO_TEMPLATES: list[Template] = [
    Template(
        "combo.loc_alone",
        "I'm at {ADDRESS} alone right now",
        L(child_ownership=1, child_location=1, alone=1),
        weight=2.0,
        tags=("combination",),
    ),
    Template("combo.loc_alone", "home alone at {ADDRESS}", L(child_ownership=1, child_location=1, alone=1), weight=1.8, tags=("combination",)),
    Template("combo.loc_alone", "im by myself at {ADDRESS}", L(child_ownership=1, child_location=1, alone=1), weight=1.8, tags=("combination",)),
    Template("combo.loc_alone", "nobody else is at {ADDRESS} with me", L(child_ownership=1, child_location=1, alone=1, guardian_absent=1), weight=1.6, tags=("combination",)),

    # ---- location + guardian absent -------------------------------------------------
    Template("combo.loc_guardian", "I live at {ADDRESS} and {GUARDIAN} aren't home", L(child_ownership=1, child_location=1, guardian_absent=1), weight=2.0, tags=("combination",)),
    Template("combo.loc_guardian", "{GUARDIAN} is out, im at {ADDRESS}", L(child_ownership=1, child_location=1, guardian_absent=1), weight=1.8, tags=("combination",)),
    Template("combo.loc_guardian", "im at {ADDRESS}, {GUARDIAN} went to work", L(child_ownership=1, child_location=1, guardian_absent=1), weight=1.6, tags=("combination",)),

    # ---- location + guardian absent + time (the canonical Level 3) ------------------
    Template(
        "combo.loc_guardian_time",
        "come to {ADDRESS}, {GUARDIAN} won't be back until {TIME}",
        L(child_ownership=1, child_location=1, guardian_absent=1, specific_time=1, meetup=1),
        weight=2.5,
        tags=("combination", "critical"),
    ),
    Template("combo.loc_guardian_time", "im at {ADDRESS} and {GUARDIAN} aren't home til {TIME}", L(child_ownership=1, child_location=1, guardian_absent=1, specific_time=1), weight=2.2, tags=("combination", "critical")),
    Template("combo.loc_guardian_time", "{GUARDIAN} gets back at {TIME}, im at {ADDRESS}", L(child_ownership=1, child_location=1, guardian_absent=1, specific_time=1), weight=2.0, tags=("combination", "critical")),

    # ---- location + alone + time + meetup (everything at once) ----------------------
    Template(
        "combo.full_critical",
        "im home alone at {ADDRESS} until {TIME}, come over",
        L(child_ownership=1, child_location=1, alone=1, specific_time=1, meetup=1, guardian_absent=1),
        weight=2.5,
        tags=("combination", "critical"),
    ),
    Template("combo.full_critical", "come to {ADDRESS} at {TIME}, im alone", L(child_ownership=1, child_location=1, alone=1, specific_time=1, meetup=1), weight=2.3, tags=("combination", "critical")),
    Template("combo.full_critical", "{ADDRESS}, im by myself till {TIME}", L(child_ownership=1, child_location=1, alone=1, specific_time=1, guardian_absent=1), weight=2.2, tags=("combination", "critical")),

    # ---- school + routine + time ----------------------------------------------------
    Template("combo.school_routine_time", "{ACTIVITY} at {SCHOOL} ends at {TIME} every {DAY}", L(child_ownership=1, school_context=1, routine=1, specific_time=1), weight=2.0, tags=("combination",)),
    Template("combo.school_routine_time", "im at {SCHOOL} till {TIME} every {DAY}", L(child_ownership=1, school_context=1, child_location=1, routine=1, specific_time=1), weight=1.8, tags=("combination",)),
    Template("combo.school_routine_time", "every {DAY} i wait outside {SCHOOL} until {TIME}", L(child_ownership=1, school_context=1, child_location=1, routine=1, specific_time=1), weight=1.8, tags=("combination",)),

    # ---- location + routine ---------------------------------------------------------
    Template("combo.loc_routine", "I walk through {PLACE} every afternoon", L(child_ownership=1, child_location=1, routine=1), weight=1.5, tags=("combination",)),
    Template("combo.loc_routine", "im at {PLACE} every {DAY} after school", L(child_ownership=1, child_location=1, routine=1), weight=1.5, tags=("combination",)),
    Template("combo.loc_routine", "i pass {ADDRESS} on my way home every day", L(child_ownership=1, child_location=1, routine=1), weight=1.5, tags=("combination",)),

    # ---- meetup + location + time ---------------------------------------------------
    Template("combo.meetup_loc_time", "meet me behind {SCHOOL} at {TIME}", L(child_ownership=1, school_context=1, child_location=1, specific_time=1, meetup=1), weight=2.2, tags=("combination", "critical")),
    Template("combo.meetup_loc_time", "meet me at {PLACE} at {TIME}", L(child_ownership=1, child_location=1, specific_time=1, meetup=1), weight=2.0, tags=("combination",)),
    Template("combo.meetup_loc_time", "ill be at {ADDRESS} at {TIME}, come find me", L(child_ownership=1, child_location=1, specific_time=1, meetup=1), weight=2.2, tags=("combination", "critical")),
    Template("combo.meetup_loc_time", "come to {ADDRESS} after {TIME}", L(child_ownership=1, child_location=1, specific_time=1, meetup=1), weight=2.0, tags=("combination", "critical")),

    # ---- meetup + alone + secrecy ---------------------------------------------------
    Template("combo.meetup_alone_secret", "come over, im alone, dont tell {GUARDIAN}", L(child_ownership=1, alone=1, meetup=1, guardian_absent=1), weight=2.3, tags=("combination", "critical", "adversarial")),
    Template("combo.meetup_alone_secret", "meet me at {PLACE}, ill be alone", L(child_ownership=1, child_location=1, alone=1, meetup=1), weight=2.0, tags=("combination", "critical")),

    # ---- contact + meetup -----------------------------------------------------------
    Template("combo.contact_meetup", "my number is {PHONE}, text me when you get here", L(child_ownership=1, meetup=1), weight=1.6, tags=("combination",)),
    Template("combo.contact_meetup", "text me at {PHONE} and come to {ADDRESS}", L(child_ownership=1, child_location=1, meetup=1), weight=2.0, tags=("combination", "critical")),

    # ---- school + alone -------------------------------------------------------------
    Template("combo.school_alone", "im waiting outside {SCHOOL} by myself", L(child_ownership=1, school_context=1, child_location=1, alone=1), weight=1.8, tags=("combination",)),
    Template("combo.school_alone", "everyone left, im still at {SCHOOL}", L(child_ownership=1, school_context=1, child_location=1, alone=1), weight=1.6, tags=("combination",)),

    # ---- routine + alone ------------------------------------------------------------
    Template("combo.routine_alone", "i walk home alone every {DAY}", L(child_ownership=1, routine=1, alone=1), weight=1.8, tags=("combination",)),
    Template("combo.routine_alone", "im always by myself after school", L(child_ownership=1, routine=1, alone=1), weight=1.6, tags=("combination",)),
    Template("combo.routine_alone", "{GUARDIAN} works late every {DAY} so im alone", L(child_ownership=1, routine=1, alone=1, guardian_absent=1), weight=1.8, tags=("combination", "critical")),
]
