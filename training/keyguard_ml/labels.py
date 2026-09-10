"""The label taxonomy — single source of truth for both heads.

Everything downstream imports its label lists from here: the OpenPII preprocessor, the
synthetic generators, the model's head widths, the metrics tables, the ONNX export
metadata, and the JSON contract the Kotlin risk engine reads. Nothing else is allowed to
hardcode a label name or an index, because an index that drifts between the exporter and
the runtime is a silent mislabelling rather than a crash: the phone would keep producing
eight plausible probabilities and attach them to the wrong signals.

`export_contract()` writes the whole taxonomy to JSON so the Android side can assert
against it at load time instead of trusting that two codebases were edited together.
"""

from __future__ import annotations

import json
from pathlib import Path

# --------------------------------------------------------------------------------------
# Head B — contextual safety signals
# --------------------------------------------------------------------------------------

# Order is the wire format. Append only; never reorder, never delete. A removed signal
# should become permanently-zero rather than shift every index after it.
CONTEXT_LABELS: tuple[str, ...] = (
    "child_ownership",
    "child_location",
    "school_context",
    "routine",
    "specific_time",
    "alone",
    "guardian_absent",
    "meetup",
)

CONTEXT_LABEL_TO_ID: dict[str, int] = {name: i for i, name in enumerate(CONTEXT_LABELS)}
NUM_CONTEXT_LABELS = len(CONTEXT_LABELS)

# Written into the contract so the definition a labeller applied and the definition the
# risk engine assumes can be diffed. These are the normative wording; the design doc's
# section 7 is the prose version.
CONTEXT_LABEL_DEFINITIONS: dict[str, str] = {
    "child_ownership": (
        "The sensitive information in the message belongs to, or describes, the person "
        "typing. 'My number is ...' is true; 'my mom's number is ...' is false. A message "
        "with no sensitive information at all is false."
    ),
    "child_location": (
        "A location in the message is where the person typing is, lives, or will be. "
        "'I live at 24 Oak Street' is true; 'the restaurant is at 24 Oak Street' is false. "
        "Past locations they are no longer at are false."
    ),
    "school_context": (
        "The message reveals which school the person attends, or where/when they are at "
        "it. Naming a school someone else attends, or a school as a landmark, is false."
    ),
    "routine": (
        "The message describes repeated, predictable behaviour by the person typing — "
        "something an outsider could use to know where they will be in future. Requires a "
        "recurrence cue ('every Tuesday', 'always', 'after school'), not a one-off plan."
    ),
    "specific_time": (
        "The message pins an event to a time precise enough to act on — a clock time, a "
        "named part of a specific day, or a countdown. 'Later' and 'sometime' are false."
    ),
    "alone": (
        "The message indicates the person typing is, or will be, physically unaccompanied. "
        "'Leave me alone' and the film 'Home Alone' are false."
    ),
    "guardian_absent": (
        "The message indicates a parent, guardian, or other supervising adult is away or "
        "will be. Distinct from `alone`: a sibling may be home. Both are often true."
    ),
    "meetup": (
        "The message proposes, arranges, or agrees to an in-person meeting — including "
        "inviting someone to a location. Reporting a meeting that already happened is false."
    ),
}

# --------------------------------------------------------------------------------------
# Head A — PII token classification (BIO)
# --------------------------------------------------------------------------------------

# The OpenPII entity types we keep. The dataset ships 19; these are the ones that carry
# child-safety signal. The rest (TAXNUM, PASSPORTNUM, DRIVERLICENSENUM, CREDITCARDNUMBER,
# PASSWORD, ...) are deliberately dropped — see `DISCARDED_OPENPII_ENTITIES`. Their spans
# collapse to O, which is correct rather than lossy: the Android privacy gate refuses to
# read a password or payment field at all, so the model never sees one at runtime and
# training it to recognise them would spend capacity on an input it cannot receive.
#
# TIME is in this list but is *not* one of OpenPII's 19 labels. It is supervised entirely
# by our own generators, which know where they inserted a clock time and can emit the span
# for free. That is worth having: "at 5:30" is a span the risk engine wants to point at,
# and OpenPII folds times into DATE when it annotates them at all.
PII_ENTITIES: tuple[str, ...] = (
    "STREET",
    "BUILDINGNUM",
    "CITY",
    "ZIPCODE",
    "EMAIL",
    "TELEPHONENUM",
    "DATE",
    "TIME",
    "GIVENNAME",
    "SURNAME",
    "SOCIALNUM",
)

# Kept out of V1 on purpose. Recorded rather than merely omitted so the next person can
# see this was a decision, and so the preprocessor can assert it saw nothing unexpected.
DISCARDED_OPENPII_ENTITIES: tuple[str, ...] = (
    "TAXNUM",
    "PASSPORTNUM",
    "DRIVERLICENSENUM",
    "CREDITCARDNUMBER",
    "IDCARDNUM",
    "SOCIALNUM_UNUSED",  # placeholder; SOCIALNUM itself is kept
    "SEX",
    "GENDER",
    "AGE",
    "TITLE",
)

# The 19 labels the upstream dataset card promises. The preprocessor asserts it saw
# nothing outside this set, so a future OpenPII release that renames or adds an entity
# fails loudly at preprocessing time instead of quietly dropping spans into O.
OPENPII_OFFICIAL_ENTITIES: frozenset[str] = frozenset(
    {
        "DATE",
        "GIVENNAME",
        "SURNAME",
        "EMAIL",
        "CITY",
        "TITLE",
        "TELEPHONENUM",
        "AGE",
        "STREET",
        "BUILDINGNUM",
        "ZIPCODE",
        "IDCARDNUM",
        "CREDITCARDNUMBER",
        "DRIVERLICENSENUM",
        "GENDER",
        "TAXNUM",
        "SEX",
        "SOCIALNUM",
        "PASSPORTNUM",
        # Present in the data but absent from the card's table of 19. Found by the
        # preprocessor's unknown-label counter on a streaming run over the real corpus,
        # which is what that counter is for — the card is documentation, the data is the
        # contract. Both are deliberately dropped: TIME is supervised by our own
        # generators instead, and a COUNTRY is far too coarse to be a safety signal.
        "TIME",
        "COUNTRY",
    }
)


def _build_bio(entities: tuple[str, ...]) -> tuple[str, ...]:
    tags = ["O"]
    for entity in entities:
        tags.append(f"B-{entity}")
        tags.append(f"I-{entity}")
    return tuple(tags)


PII_TAGS: tuple[str, ...] = _build_bio(PII_ENTITIES)
PII_TAG_TO_ID: dict[str, int] = {tag: i for i, tag in enumerate(PII_TAGS)}
PII_ID_TO_TAG: dict[int, str] = {i: tag for tag, i in PII_TAG_TO_ID.items()}
NUM_PII_TAGS = len(PII_TAGS)

# Index used for "this position has no supervision" — wordpiece continuations, padding,
# and every token of a context-only example. CrossEntropyLoss ignores it.
IGNORE_INDEX = -100

# --------------------------------------------------------------------------------------
# Coarse safety entities (design doc §5)
# --------------------------------------------------------------------------------------

# The risk engine reasons in these four buckets, not in the twelve fine entity types. The
# fine types are still what the model predicts — collapsing them at the loss would throw
# away supervision OpenPII already paid for — so this map is applied to *predictions*, at
# the boundary between the model and the engine.
ENTITY_TO_SAFETY_ENTITY: dict[str, str] = {
    "STREET": "exact_location",
    "BUILDINGNUM": "exact_location",
    "ZIPCODE": "exact_location",
    "CITY": "coarse_location",
    "EMAIL": "contact_info",
    "TELEPHONENUM": "contact_info",
    "DATE": "time_signal",
    "TIME": "time_signal",
    "GIVENNAME": "name",
    "SURNAME": "name",
    "SOCIALNUM": "government_id",
}

SAFETY_ENTITIES: tuple[str, ...] = (
    "exact_location",
    "coarse_location",
    "contact_info",
    "time_signal",
    "name",
    "government_id",
)

# CITY is separated from STREET/ZIPCODE deliberately. The design doc groups all four into
# `exact_location`, but a city is not an exact location — "I live in Toronto" is not the
# same disclosure as "I live at 24 Oak Street", and treating them alike would either make
# the engine hysterical about the first or complacent about the second. The engine gets
# both buckets and weighs them differently.

# --------------------------------------------------------------------------------------
# Mapping raw OpenPII labels onto ours
# --------------------------------------------------------------------------------------

# OpenPII's own naming is not perfectly stable across its releases (some use TIME, some
# fold time into DATE; some use SOCIALNUM, some SSN). Aliases are resolved here so the
# preprocessor can be pointed at a different release without editing the pipeline.
OPENPII_ALIASES: dict[str, str] = {
    "TELEPHONENUM": "TELEPHONENUM",
    "PHONE_NUMBER": "TELEPHONENUM",
    "PHONEIMEI": "TELEPHONENUM",
    "EMAIL": "EMAIL",
    "STREET": "STREET",
    "STREETADDRESS": "STREET",
    "BUILDINGNUM": "BUILDINGNUM",
    "BUILDING": "BUILDINGNUM",
    "CITY": "CITY",
    "STATE": "CITY",
    "ZIPCODE": "ZIPCODE",
    "POSTCODE": "ZIPCODE",
    "DATE": "DATE",
    "DATEOFBIRTH": "DATE",
    "DOB": "DATE",
    "TIME": "TIME",
    "GIVENNAME": "GIVENNAME",
    "FIRSTNAME": "GIVENNAME",
    "SURNAME": "SURNAME",
    "LASTNAME": "SURNAME",
    "SOCIALNUM": "SOCIALNUM",
    "SSN": "SOCIALNUM",
}


def normalize_openpii_entity(raw: str) -> str | None:
    """Map a raw OpenPII entity name onto ours, or None if V1 drops it.

    Accepts BIO-prefixed input ('B-STREET') as well as bare names, because the several
    OpenPII releases disagree about which they publish.
    """
    name = raw.strip().upper()
    if name in ("O", ""):
        return None
    if len(name) > 2 and name[1] == "-" and name[0] in ("B", "I", "E", "S"):
        name = name[2:]
    return OPENPII_ALIASES.get(name)


# --------------------------------------------------------------------------------------
# Contract export
# --------------------------------------------------------------------------------------

CONTRACT_VERSION = 1


def contract() -> dict:
    """The taxonomy as a plain dict, ready to serialise next to a model artifact."""
    return {
        "contract_version": CONTRACT_VERSION,
        "context_labels": list(CONTEXT_LABELS),
        "context_label_definitions": CONTEXT_LABEL_DEFINITIONS,
        "pii_tags": list(PII_TAGS),
        "pii_entities": list(PII_ENTITIES),
        "safety_entities": list(SAFETY_ENTITIES),
        "entity_to_safety_entity": ENTITY_TO_SAFETY_ENTITY,
        "discarded_openpii_entities": list(DISCARDED_OPENPII_ENTITIES),
        "ignore_index": IGNORE_INDEX,
    }


def export_contract(path: str | Path) -> Path:
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(contract(), indent=2) + "\n", encoding="utf-8")
    return out


if __name__ == "__main__":  # pragma: no cover - convenience entry point
    import sys

    target = sys.argv[1] if len(sys.argv) > 1 else "datasets/label_contract.json"
    print(f"wrote {export_contract(target)}")
    print(f"{NUM_CONTEXT_LABELS} context labels, {NUM_PII_TAGS} BIO tags")
