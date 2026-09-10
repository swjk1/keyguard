"""§12 — hard negatives, treated as a first-class training objective.

Every family here is *structurally* close to a positive family and labelled the opposite
way. That is the whole point: an easy negative ("hey what's up") teaches the model to
separate chat from disclosure, which it would learn anyway. A hard negative teaches it
that "the pizza place is at 24 Oak St" and "I live at 24 Oak St" produce identical token
output and opposite context output, which is the distinction the product is built on and
the only one it cannot get from OpenPII.

Grouped by the false positive each set is designed to prevent, because that is how error
analysis will come back asking for more of them.
"""

from __future__ import annotations

from generators.render import Template
from generators.templates import L

# ======================================================================================
# FP class: address ownership confusion
# ======================================================================================

ADDRESS_NEGATIVES: list[Template] = [
    Template("hardneg.addr.business", "the pizza place is at {ADDRESS}", L(), weight=1.6, tags=("hard_negative", "ownership")),
    Template("hardneg.addr.business", "{BUSINESS} is at {ADDRESS}", L(), weight=1.6, tags=("hard_negative", "ownership")),
    Template("hardneg.addr.business", "theres a new store at {ADDRESS}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.addr.business", "the dentist is on {STREET}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.addr.business", "{BUSINESS} moved to {CITY}", L(), tags=("hard_negative", "ownership")),

    Template("hardneg.addr.thirdparty", "{RELATIVE} lives at {ADDRESS}", L(), weight=1.6, tags=("hard_negative", "ownership")),
    Template("hardneg.addr.thirdparty", "{NAME} lives on {STREET}", L(), weight=1.4, tags=("hard_negative", "ownership")),
    Template("hardneg.addr.thirdparty", "{RELATIVE}s house is at {ADDRESS}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.addr.thirdparty", "the party is at {NAME}s place on {STREET}", L(), tags=("hard_negative", "ownership")),

    Template("hardneg.addr.fiction", "the movie was filmed at {ADDRESS}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.addr.fiction", "in the book they live at {ADDRESS}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.addr.fiction", "the game map has a house at {ADDRESS}", L(), tags=("hard_negative", "ownership")),

    Template("hardneg.addr.historical", "we used to live at {ADDRESS}", L(child_ownership=1), tags=("hard_negative", "historical")),
    Template("hardneg.addr.historical", "i lived on {STREET} when i was little", L(child_ownership=1), tags=("hard_negative", "historical")),
    Template("hardneg.addr.historical", "our old house was at {ADDRESS}", L(child_ownership=1), tags=("hard_negative", "historical")),
]

# A note on the historical set above: `child_ownership` stays 1 because the disclosure is
# still about the child, while `child_location` is 0 because it is not where they are.
# That pairing is the entire signal — the risk engine needs ownership without current
# location to come out as low risk rather than as a critical address leak.

# ======================================================================================
# FP class: third-party contact info
# ======================================================================================

CONTACT_NEGATIVES: list[Template] = [
    Template("hardneg.contact.business", "call the restaurant at {PHONE}", L(), weight=1.6, tags=("hard_negative", "ownership")),
    Template("hardneg.contact.business", "the school office is {PHONE}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.contact.business", "you can book online or call {PHONE}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.contact.business", "support is at {EMAIL}", L(), tags=("hard_negative", "ownership")),

    Template("hardneg.contact.thirdparty", "{RELATIVE}s number is {PHONE}", L(), weight=1.6, tags=("hard_negative", "ownership")),
    Template("hardneg.contact.thirdparty", "{NAME}s email is {EMAIL}", L(), weight=1.4, tags=("hard_negative", "ownership")),
    Template("hardneg.contact.thirdparty", "text {NAME} at {PHONE} not me", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.contact.thirdparty", "{RELATIVE} said to email {EMAIL}", L(), tags=("hard_negative", "ownership")),
]

# ======================================================================================
# FP class: "alone" as idiom or title
# ======================================================================================

ALONE_NEGATIVES: list[Template] = [
    Template("hardneg.alone.idiom", "just leave me alone", L(), weight=1.8, tags=("hard_negative", "idiom")),
    Template("hardneg.alone.idiom", "leave me alone please", L(), weight=1.5, tags=("hard_negative", "idiom")),
    Template("hardneg.alone.idiom", "can you leave me alone", L(), tags=("hard_negative", "idiom")),
    Template("hardneg.alone.idiom", "she wont leave me alone", L(), tags=("hard_negative", "idiom")),
    Template("hardneg.alone.idiom", "im not alone in thinking that", L(), tags=("hard_negative", "idiom")),
    Template("hardneg.alone.idiom", "you're not alone, i feel that too", L(), tags=("hard_negative", "idiom")),
    Template("hardneg.alone.idiom", "let alone the homework", L(), tags=("hard_negative", "idiom")),

    Template("hardneg.alone.title", "i watched home alone last night", L(), weight=1.8, tags=("hard_negative", "title")),
    Template("hardneg.alone.title", "home alone is such a good movie", L(), weight=1.5, tags=("hard_negative", "title")),
    Template("hardneg.alone.title", "home alone 2 is better", L(), tags=("hard_negative", "title")),
    Template("hardneg.alone.title", "we're watching home alone at {TIME}", L(child_ownership=1, specific_time=1), tags=("hard_negative", "title")),

    Template("hardneg.alone.thirdparty", "{RELATIVE} is home alone", L(), weight=1.4, tags=("hard_negative", "ownership")),
    Template("hardneg.alone.thirdparty", "{NAME} said they were alone", L(), tags=("hard_negative", "ownership")),
]

# ======================================================================================
# FP class: guardian mentioned but present
# ======================================================================================

GUARDIAN_NEGATIVES: list[Template] = [
    Template("hardneg.guardian.present", "{GUARDIAN} is right here", L(), weight=1.6, tags=("hard_negative",)),
    Template("hardneg.guardian.present", "{GUARDIAN} says hi", L(), tags=("hard_negative",)),
    Template("hardneg.guardian.present", "{GUARDIAN} is making dinner", L(), tags=("hard_negative",)),
    Template("hardneg.guardian.present", "im with {GUARDIAN} right now", L(child_ownership=1), tags=("hard_negative",)),
    Template("hardneg.guardian.present", "{GUARDIAN} is driving me there", L(child_ownership=1), tags=("hard_negative",)),
    Template("hardneg.guardian.present", "gotta ask {GUARDIAN} first", L(), tags=("hard_negative",)),
    Template("hardneg.guardian.present", "{GUARDIAN} wont let me", L(), tags=("hard_negative",)),
    Template("hardneg.guardian.present", "{GUARDIAN} came home early", L(), weight=1.4, tags=("hard_negative",)),
]

# ======================================================================================
# FP class: a public schedule is not the child's routine
# ======================================================================================

ROUTINE_NEGATIVES: list[Template] = [
    Template("hardneg.routine.public", "the store closes at {TIME} every {DAY}", L(), weight=1.8, tags=("hard_negative",)),
    Template("hardneg.routine.public", "the bus comes every 20 minutes", L(), tags=("hard_negative",)),
    Template("hardneg.routine.public", "the library is open till {TIME} on {DAY}", L(), tags=("hard_negative",)),
    Template("hardneg.routine.public", "{BUSINESS} is closed every {DAY}", L(), tags=("hard_negative",)),
    Template("hardneg.routine.public", "the show airs every {DAY}", L(), tags=("hard_negative",)),

    Template("hardneg.routine.thirdparty", "{RELATIVE} works every {DAY}", L(), weight=1.4, tags=("hard_negative", "ownership")),
    Template("hardneg.routine.thirdparty", "{NAME} has {ACTIVITY} every {DAY}", L(), weight=1.4, tags=("hard_negative", "ownership")),
]

# ======================================================================================
# FP class: school named without revealing the child's school
# ======================================================================================

SCHOOL_NEGATIVES: list[Template] = [
    Template("hardneg.school.public", "{SCHOOL}s website says school ends at {TIME}", L(), weight=1.6, tags=("hard_negative",)),
    Template("hardneg.school.public", "{SCHOOL} won the tournament", L(), tags=("hard_negative",)),
    Template("hardneg.school.public", "{SCHOOL} is the one by the highway", L(), tags=("hard_negative",)),
    Template("hardneg.school.public", "they're building a new gym at {SCHOOL}", L(), tags=("hard_negative",)),

    Template("hardneg.school.thirdparty", "{NAME} goes to {SCHOOL}", L(), weight=1.5, tags=("hard_negative", "ownership")),
    Template("hardneg.school.thirdparty", "{RELATIVE} teaches at {SCHOOL}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.school.thirdparty", "my old school was {SCHOOL}", L(child_ownership=1), tags=("hard_negative", "historical")),
]

# ======================================================================================
# FP class: a time with nothing at stake
# ======================================================================================

TIME_NEGATIVES: list[Template] = [
    Template("hardneg.time.trivial", "the movie starts at {TIME}", L(), weight=1.5, tags=("hard_negative",)),
    Template("hardneg.time.trivial", "the episode drops at {TIME}", L(), tags=("hard_negative",)),
    Template("hardneg.time.trivial", "class is at {TIME}", L(), tags=("hard_negative",)),
    Template("hardneg.time.trivial", "its already {TIME}", L(), tags=("hard_negative",)),
    Template("hardneg.time.trivial", "i woke up at {TIME} lol", L(child_ownership=1), tags=("hard_negative",)),
    Template("hardneg.time.trivial", "the game is on at {TIME}", L(), tags=("hard_negative",)),
]

# ======================================================================================
# FP class: meetup already happened, or is not with the child
# ======================================================================================

MEETUP_NEGATIVES: list[Template] = [
    Template("hardneg.meetup.past", "we met at {PLACE} yesterday", L(), weight=1.5, tags=("hard_negative",)),
    Template("hardneg.meetup.past", "i saw {NAME} at {PLACE} last week", L(), tags=("hard_negative",)),
    Template("hardneg.meetup.past", "that was fun, we should have gone earlier", L(), tags=("hard_negative",)),

    Template("hardneg.meetup.declined", "i cant come over", L(), weight=1.4, tags=("hard_negative",)),
    Template("hardneg.meetup.declined", "im not allowed to meet up", L(), tags=("hard_negative",)),
    Template("hardneg.meetup.declined", "cant, {GUARDIAN} said no", L(), tags=("hard_negative",)),

    Template("hardneg.meetup.thirdparty", "{NAME} is meeting {NAME} at {PLACE}", L(), tags=("hard_negative", "ownership")),
    Template("hardneg.meetup.thirdparty", "{RELATIVE} is coming over", L(), weight=1.4, tags=("hard_negative",)),
]

# ======================================================================================
# FP class: numbers that look like PII but are not
# ======================================================================================

NUMERIC_NEGATIVES: list[Template] = [
    # Deliberately a literal rather than a {TIME} slot: the point of this family is
    # digits that are *not* an entity, and drawing from the TIME slot would attach a
    # genuine TIME span to a template whose whole purpose is to carry none.
    Template("hardneg.numeric", "i got 82 on the test lol", L(), tags=("hard_negative",)),
    Template("hardneg.numeric", "i got a 94 on the quiz", L(), tags=("hard_negative",)),
    Template("hardneg.numeric", "we won 24 to 18", L(), tags=("hard_negative",)),
    Template("hardneg.numeric", "im level 47 now", L(), tags=("hard_negative",)),
    Template("hardneg.numeric", "it costs like 30 bucks", L(), tags=("hard_negative",)),
    Template("hardneg.numeric", "chapter 12 question 4", L(), tags=("hard_negative",)),
    Template("hardneg.numeric", "room 203 not 204", L(), tags=("hard_negative",)),
]

HARD_NEGATIVE_TEMPLATES: list[Template] = [
    *ADDRESS_NEGATIVES,
    *CONTACT_NEGATIVES,
    *ALONE_NEGATIVES,
    *GUARDIAN_NEGATIVES,
    *ROUTINE_NEGATIVES,
    *SCHOOL_NEGATIVES,
    *TIME_NEGATIVES,
    *MEETUP_NEGATIVES,
    *NUMERIC_NEGATIVES,
]
