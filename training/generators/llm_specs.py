"""What to ask for, and why each entry is here.

Every spec below was chosen from a measurement, not from a guess about what might be
missing. Two diagnostics drove the list:

*Marker coverage.* Counting surface forms across the 7,935 training rows carrying
`alone` found the corpus saying the same few things: "solo/alone" literal 41.9%, "home
alone" 13.5%, "by myself" 13.4% — and then a cliff. "on my own" 1.4%, "nobody here"
4.2%, "to myself" **0.0%**, "empty house" **0.0%**. The gold misses land exactly there:
"ive got the whole sofa to myself" scored 0.05, "the flat feels massive when its empty"
0.03, "gaff is empty fam" 0.03.

*Per-label ranking.* Threshold-free PR-AUC on held-out gold: `guardian_absent` 0.523
against an 8% base rate — barely a signal — then `alone` 0.664, `school_context` 0.713,
`child_location` 0.729. Oracle substitution puts numbers on what fixing each is worth:
`alone` alone is +0.209 Level 3 recall, `child_location` +0.163.

Negatives are budgeted alongside the positives rather than after them. Every construction
added to a positive family is a new way for the model to over-fire, and `child_ownership`
already accounts for 33.6 of the 36.3 false warnings per 1,000 safe messages. Adding
"empty house" phrasings for `alone` without also adding "*their* house is empty" and "the
house is never empty" would buy recall with precision, which on this product is not a
trade worth making.
"""

from __future__ import annotations

from generators.llm_expand import FamilySpec, L

SPECS: list[FamilySpec] = [
    # ==================================================================================
    # `alone` — the single highest-leverage label (+0.209 L3 recall if perfect)
    # ==================================================================================
    FamilySpec(
        family="alone.to_myself",
        labels=L(child_ownership=1, alone=1),
        construction="having the place/room/house to yourself — the 'to myself' family, "
                     "which appears in 0% of the current corpus",
        examples=("got the place to myself", "whole houses mine tonight"),
        n=45, tags=("alone", "indirect_alone"),
    ),
    FamilySpec(
        family="alone.empty_house",
        labels=L(child_ownership=1, alone=1),
        construction="describing the home as empty, quiet or still — 0% of the current "
                     "corpus. Emptiness stated about the place, not about yourself",
        examples=("house is dead quiet", "flats empty rn"),
        n=45, tags=("alone", "indirect_alone"),
    ),
    FamilySpec(
        family="alone.on_my_own",
        labels=L(child_ownership=1, alone=1),
        construction="being on your own / by yourself, in forms other than the literal "
                     "'home alone' (1.4% of the current corpus)",
        examples=("im on my own tonight", "just me here"),
        n=40, tags=("alone", "indirect_alone"),
    ),
    FamilySpec(
        family="alone.nobody_here",
        labels=L(child_ownership=1, alone=1),
        construction="nobody being present, phrased from the absence rather than from "
                     "yourself (4.2% of the current corpus)",
        examples=("no ones in", "theres literally nobody around"),
        n=40, tags=("alone", "indirect_alone"),
    ),
    FamilySpec(
        family="alone.consequence",
        labels=L(child_ownership=1, alone=1),
        construction="implying you are alone by what you can now do or must now do — "
                     "nobody to stop you, having to sort your own food, letting yourself "
                     "in. Never say 'alone' or 'by myself'",
        examples=("can have the tv as loud as i want", "making my own tea again"),
        n=45, tags=("alone", "subtle", "indirect_alone"),
    ),
    FamilySpec(
        # The pilot showed the model reaches for "til {TIME}" constantly when writing
        # about being alone, because that is how people actually talk. Banning the slot
        # everywhere would have thrown away the most natural phrasings in the batch; this
        # family keeps them and labels them honestly instead.
        family="alone.window",
        labels=L(child_ownership=1, alone=1, specific_time=1),
        construction="being on your own until a stated time — the phrasing people "
                     "actually reach for, where the aloneness comes with a deadline",
        examples=("place is mine til {TIME}", "on my own for the next {RELTIME}"),
        n=45, tags=("alone", "critical"), group="combination",
    ),

    # ==================================================================================
    # `guardian_absent` — weakest label in the model (PR-AUC 0.523)
    # ==================================================================================
    FamilySpec(
        family="guardian.shift",
        labels=L(child_ownership=1, guardian_absent=1),
        construction="a parent or carer being at work, on a shift, or out for the "
                     "evening, without a clock time attached",
        examples=("{GUARDIAN} is on nights this week", "my {GUARDIAN}s working late again"),
        n=45, tags=("guardian", "indirect_guardian"),
    ),
    FamilySpec(
        family="guardian.away",
        labels=L(child_ownership=1, guardian_absent=1),
        construction="a parent or carer being away, out, or not back yet — stated "
                     "plainly, no time given",
        examples=("{GUARDIAN}s not back yet", "parents have gone out"),
        n=40, tags=("guardian",),
    ),
    FamilySpec(
        family="guardian.unreachable",
        labels=L(child_ownership=1, guardian_absent=1),
        construction="not being able to reach a parent, or nobody answering at home",
        examples=("{GUARDIAN} isnt picking up", "no one answers the house phone"),
        n=40, tags=("guardian", "indirect_guardian"),
    ),
    FamilySpec(
        family="guardian.unsupervised",
        labels=L(child_ownership=1, guardian_absent=1),
        construction="nobody checking on you or knowing where you are — absence of "
                     "supervision rather than absence of a person",
        examples=("nobody checks what time i get in", "no one asks where ive been"),
        n=45, tags=("guardian", "subtle", "indirect_guardian"),
        allow_phrases=("routine",),
    ),
    FamilySpec(
        family="guardian.window",
        labels=L(child_ownership=1, guardian_absent=1, specific_time=1),
        construction="a carer being away until a stated clock time — the combination "
                     "that makes Level 3 fire",
        examples=("{GUARDIAN}s not back till {TIME}", "no adults here until {TIME}"),
        n=45, tags=("guardian", "critical"), group="combination",
    ),

    # ==================================================================================
    # `child_location` — second highest recall lever (+0.163)
    # ==================================================================================
    FamilySpec(
        family="loc.landmark",
        labels=L(child_ownership=1, child_location=1),
        construction="giving away where you live by landmark or description rather than "
                     "by address — the house opposite something, behind something",
        examples=("were the one with the red door", "im right by {PLACE}"),
        n=45, tags=("address", "subtle"),
    ),
    FamilySpec(
        family="loc.current_indirect",
        labels=L(child_ownership=1, child_location=1),
        construction="saying where you are right now without naming a street — which "
                     "floor, which end, what you are next to",
        examples=("im out front", "top floor, lifts broken"),
        n=40, tags=("address", "subtle"),
    ),

    # ==================================================================================
    # Negatives — budgeted with the positives, not after them
    # ==================================================================================
    FamilySpec(
        family="hn.alone.third_party",
        labels=L(),
        construction="somebody ELSE being alone or their place being empty — same words "
                     "as the positive families, different subject",
        examples=("{RELATIVE}s been on their own since {DAY}", "their houses empty this week"),
        n=45, tags=("hard_negative", "ownership", "alone"), group="hard_negative",
        allow_slots=("RELATIVE", "GUARDIAN"), allow_phrases=("alone",),
    ),
    FamilySpec(
        family="hn.alone.negated",
        labels=L(),
        construction="explicitly NOT being alone, or never being alone — the negation "
                     "slice, which the model currently over-fires on",
        examples=("im not on my own, {GUARDIAN}s here", "theres always someone in"),
        n=45, tags=("hard_negative", "negation", "alone"), group="hard_negative",
        allow_slots=("GUARDIAN", "RELATIVE"), allow_phrases=("alone",),
    ),
    FamilySpec(
        family="hn.alone.idiom",
        labels=L(),
        construction="'alone', 'by myself' or 'empty' used idiomatically or about an "
                     "object — leave me alone, not alone in thinking, empty fridge",
        examples=("youre not alone in hating that", "fridge is empty again"),
        n=40, tags=("hard_negative", "idiom"), group="hard_negative",
        allow_phrases=("alone", "routine"),
    ),
    FamilySpec(
        family="hn.guardian.present",
        labels=L(),
        construction="a parent or carer being present, nearby, or in charge — the "
                     "reassuring case that must stay silent",
        examples=("{GUARDIAN}s in the kitchen", "my {GUARDIAN} is driving me"),
        n=45, tags=("hard_negative", "guardian_present"), group="hard_negative",
        allow_phrases=("alone",),
        allow_slots=("GUARDIAN", "RELATIVE"),
    ),
    FamilySpec(
        family="hn.guardian.third_party",
        labels=L(),
        construction="somebody else's parents being out or working late",
        examples=("her {GUARDIAN} works nights", "their parents are away"),
        n=40, tags=("hard_negative", "ownership", "guardian"), group="hard_negative",
        allow_slots=("GUARDIAN", "RELATIVE"),
    ),
    FamilySpec(
        family="hn.loc.third_party",
        labels=L(),
        construction="a location that belongs to a business, a public place, or another "
                     "person — identical shape to a disclosure, different owner",
        examples=("the dentist is on {STREET}", "{BUSINESS} is at {ADDRESS}"),
        n=45, tags=("hard_negative", "ownership", "address"), group="hard_negative",
        allow_slots=("STREET", "ADDRESS", "CITY", "PLACE"),
    ),

    # ==================================================================================
    # Ordinary safe chat — the false-warning denominator, and the widest vocabulary gap
    # ==================================================================================
    FamilySpec(
        family="safe.everyday",
        labels=L(),
        construction="ordinary teenage chat with no risk signal at all — school, games, "
                     "food, tiredness, siblings, weather, homework, small complaints. "
                     "This is the distribution the model spends its life on",
        examples=("that lesson dragged", "my phones on 2%"),
        n=60, tags=("safe",), group="safe",
    ),
    FamilySpec(
        family="safe.reply",
        labels=L(),
        construction="short conversational replies and reactions — agreeing, teasing, "
                     "changing subject, signing off",
        examples=("fr though", "ok see you then"),
        n=50, tags=("safe",), group="safe",
    ),
]
