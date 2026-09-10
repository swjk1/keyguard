"""§19 — the human-curated gold evaluation set.

Every example below was written by hand. None of them comes from a training template, and
that is the entire point: the synthetic corpus can only test whether the model learned the
patterns it was shown, and the gold set is the only measurement of whether it learned the
*policy*. If a template ever gets copied in here, the set stops being able to detect
overfitting to the generator and nothing will announce that it happened.

Each row is `(text, active_labels, tags)`. Labels not listed are 0. Tags are for slicing
error analysis, and every row carries at least one.

SIZE: §19 asks for 1,000–3,000. This file currently holds far fewer — see `gold_status()`.
It is a genuine seed, not a complete set, and the Definition of Done in §34 is not met
until it is expanded. What it does cover is every *category* the doc lists, so the shape
is right and expansion is addition rather than redesign.

Labelling convention reminder (see `generators/templates.py` for the full statement):
`child_ownership` is the "this is about me" flag, and `child_location` means a location in
the message is where the child is/lives/will be — not that the location is precise.
"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from keyguard_ml.labels import CONTEXT_LABELS

# (text, active labels, tags)
Row = tuple[str, list[str], list[str]]

GOLD: list[Row] = [
    # ==================================================================================
    # Obvious positives
    # ==================================================================================
    ("I live at 88 Cranbrook Avenue.", ["child_ownership", "child_location"], ["obvious", "address"]),
    ("my house is 412 Delmar Road", ["child_ownership", "child_location"], ["obvious", "address"]),
    ("Our address is 7 Ridgemont Cres, apartment 4.", ["child_ownership", "child_location"], ["obvious", "address"]),
    ("my phone number is 604-555-0182", ["child_ownership"], ["obvious", "contact"]),
    ("you can reach me at tomasz.k91@outlook.com", ["child_ownership"], ["obvious", "contact"]),
    ("I go to Brookhaven Middle School.", ["child_ownership", "school_context"], ["obvious", "school"]),
    ("I'm home alone tonight.", ["child_ownership", "alone"], ["obvious", "alone"]),
    ("My parents aren't home until 10.", ["child_ownership", "guardian_absent", "specific_time"], ["obvious", "guardian"]),
    ("Come over after 7.", ["child_ownership", "meetup", "specific_time"], ["obvious", "meetup"]),
    ("Meet me behind the school.", ["child_ownership", "meetup", "child_location", "school_context"], ["obvious", "meetup"]),
    ("Practice ends at 5:30.", ["child_ownership", "specific_time"], ["obvious", "time"]),
    ("I walk home through Kensington Park every Tuesday.", ["child_ownership", "child_location", "routine"], ["obvious", "routine"]),

    # ==================================================================================
    # Subtle positives — the signal is there but never stated plainly
    # ==================================================================================
    ("nobody's picking me up today so I'll just walk", ["child_ownership", "alone", "guardian_absent"], ["subtle", "indirect_guardian"]),
    ("got the place to myself for once", ["child_ownership", "alone", "guardian_absent"], ["subtle", "indirect_alone"]),
    ("mom's shift doesn't end till midnight", ["child_ownership", "guardian_absent", "specific_time"], ["subtle", "indirect_guardian"]),
    ("it's just me and the dog", ["child_ownership", "alone", "guardian_absent"], ["subtle", "indirect_alone"]),
    ("same bench as always after last period", ["child_ownership", "child_location", "routine", "school_context"], ["subtle", "indirect_routine"]),
    ("you know the blue house on the corner of Fenwick? that's us", ["child_ownership", "child_location"], ["subtle", "address"]),
    ("I'm the one in the green jacket by the north doors at 3", ["child_ownership", "child_location", "specific_time", "meetup"], ["subtle", "meetup"]),
    ("door's unlocked if you want to come by", ["child_ownership", "meetup", "child_location"], ["subtle", "meetup"]),
    ("nobody checks on me after school anyway", ["child_ownership", "alone", "guardian_absent", "routine"], ["subtle", "indirect_guardian"]),
    ("I'll be the only one there", ["child_ownership", "alone"], ["subtle", "indirect_alone"]),
    ("dad works nights so evenings are quiet here", ["child_ownership", "guardian_absent", "routine"], ["subtle", "indirect_routine"]),
    ("we're at 1120 but the buzzer's broken", ["child_ownership", "child_location"], ["subtle", "address"]),

    # ==================================================================================
    # Difficult negatives — structurally identical to a positive
    # ==================================================================================
    ("The restaurant is at 24 Oak Street.", [], ["hard_negative", "ownership"]),
    ("Call the restaurant at 514-555-1234.", [], ["hard_negative", "ownership"]),
    ("My uncle lives at 24 Oak Street.", [], ["hard_negative", "ownership"]),
    ("The movie was filmed at 24 Oak Street.", [], ["hard_negative", "ownership"]),
    ("Just leave me alone.", [], ["hard_negative", "idiom"]),
    ("I watched Home Alone tonight.", [], ["hard_negative", "title"]),
    ("Home Alone 2 is objectively better", [], ["hard_negative", "title"]),
    ("The store closes at 5 every Tuesday.", [], ["hard_negative", "public_schedule"]),
    ("Riverside Secondary's website says school ends at 3.", [], ["hard_negative", "school"]),
    ("my sister goes to Brookhaven Middle School", [], ["hard_negative", "ownership", "school"]),
    ("she won't leave me alone about it", [], ["hard_negative", "idiom"]),
    ("you're not alone, loads of people failed that", [], ["hard_negative", "idiom"]),
    ("the bus comes every 12 minutes on weekends", [], ["hard_negative", "public_schedule"]),
    ("my mum's number is 604-555-0182 if you need her", [], ["hard_negative", "ownership", "contact"]),
    ("support@riverside.example.com is the address for complaints", [], ["hard_negative", "ownership", "contact"]),
    ("the party's at Devon's place, 91 Harker Lane", [], ["hard_negative", "ownership", "address"]),
    ("Grandma's been home alone since Tuesday", [], ["hard_negative", "ownership", "alone"]),
    ("we met at the mall last weekend", [], ["hard_negative", "past_meetup"]),
    ("I can't come over, I'm grounded", [], ["hard_negative", "declined_meetup"]),
    ("mom said no to the sleepover", [], ["hard_negative", "declined_meetup"]),
    ("I got a 91 on the science test", [], ["hard_negative", "numeric"]),
    ("we lost 24 to 18", [], ["hard_negative", "numeric"]),
    ("room 214, not 215", [], ["hard_negative", "numeric"]),
    ("chapter 7 questions 1 through 12", [], ["hard_negative", "numeric"]),
    ("the movie starts at 7:40", [], ["hard_negative", "trivial_time"]),
    ("my dad's right here, hang on", [], ["hard_negative", "guardian_present"]),
    ("mom just got home", [], ["hard_negative", "guardian_present"]),
    ("I'm with my parents at the mall", ["child_ownership", "child_location"], ["hard_negative", "guardian_present"]),

    # ==================================================================================
    # Historical / past location — ownership without current location
    # ==================================================================================
    ("we used to live at 60 Halsey Street", ["child_ownership"], ["historical"]),
    ("I lived in Winnipeg until grade 3", ["child_ownership"], ["historical"]),
    ("my old school was Fairmount Elementary", ["child_ownership"], ["historical", "school"]),
    ("that was my number two phones ago", ["child_ownership"], ["historical", "contact"]),

    # ==================================================================================
    # Ownership ambiguity — the case the whole architecture exists for
    # ==================================================================================
    ("my friend's address is 33 Bellwood Dr", [], ["ownership_ambiguity"]),
    ("her house is on the same street as mine", ["child_ownership", "child_location"], ["ownership_ambiguity"]),
    ("we both live on Carlton", ["child_ownership", "child_location"], ["ownership_ambiguity"]),
    ("that's my cousin's number not mine", [], ["ownership_ambiguity", "contact"]),
    ("I'll give you his email, mine's broken", [], ["ownership_ambiguity", "contact"]),
    ("my brother and I are home alone", ["child_ownership", "guardian_absent"], ["ownership_ambiguity", "alone"]),
    ("it's my mum's car but I'm the one driving", ["child_ownership"], ["ownership_ambiguity"]),

    # ==================================================================================
    # Slang, abbreviation, missing punctuation
    # ==================================================================================
    ("my addy is 24 oak st", ["child_ownership", "child_location"], ["slang", "address"]),
    ("im home alone til 9 lol", ["child_ownership", "alone", "specific_time"], ["slang", "alone"]),
    ("parents gone till 9", ["child_ownership", "guardian_absent", "specific_time"], ["slang", "guardian"]),
    ("no ones home rn", ["child_ownership", "alone", "guardian_absent"], ["slang", "alone"]),
    ("im alone rn", ["child_ownership", "alone"], ["slang", "alone"]),
    ("my parents r gone", ["child_ownership", "guardian_absent"], ["slang", "guardian"]),
    ("practice ends @ 530", ["child_ownership", "specific_time"], ["slang", "time"]),
    ("meet me outside school @ 5", ["child_ownership", "meetup", "child_location", "school_context", "specific_time"], ["slang", "meetup"]),
    ("come thru, 88 cranbrook ave", ["child_ownership", "child_location", "meetup"], ["slang", "address"]),
    ("hmu 6045550182", ["child_ownership"], ["slang", "contact"]),
    ("u can pull up to mine after 8", ["child_ownership", "meetup", "specific_time", "child_location"], ["slang", "meetup"]),
    ("rents r out till late", ["child_ownership", "guardian_absent"], ["slang", "guardian"]),
    ("finna be home solo tn", ["child_ownership", "alone"], ["slang", "alone"]),

    # ==================================================================================
    # Typos and incomplete messages
    # ==================================================================================
    ("im hoem alone", ["child_ownership", "alone"], ["typo", "alone"]),
    ("my adress is 412 delmar rd", ["child_ownership", "child_location"], ["typo", "address"]),
    ("nobdy is hoem", ["child_ownership", "alone", "guardian_absent"], ["typo", "alone"]),
    ("meet me at teh park at 4", ["child_ownership", "meetup", "child_location", "specific_time"], ["typo", "meetup"]),
    ("i live at", ["child_ownership"], ["incomplete", "address"]),
    ("my number is", ["child_ownership"], ["incomplete", "contact"]),
    ("come to 88", ["child_ownership", "meetup", "child_location"], ["incomplete", "address"]),
    ("home alone", ["child_ownership", "alone"], ["incomplete", "alone"]),
    ("parents aren't", ["child_ownership", "guardian_absent"], ["incomplete", "guardian"]),

    # ==================================================================================
    # §23 adversarial — abbreviation, obfuscation, relative time, indirection
    # ==================================================================================
    ("24 oak st", [], ["adversarial", "abbreviation"]),
    ("i'm at 24 oak ave", ["child_ownership", "child_location"], ["adversarial", "abbreviation"]),
    ("we're at 24 oak rd not oak st", ["child_ownership", "child_location"], ["adversarial", "abbreviation"]),
    ("2 4 o a k street", [], ["adversarial", "obfuscation"]),
    ("twenty four oak street is where i live", ["child_ownership", "child_location"], ["adversarial", "obfuscation"]),
    ("parents back in 2 hrs", ["child_ownership", "guardian_absent", "specific_time"], ["adversarial", "relative_time"]),
    ("free for like 40 mins", ["child_ownership", "specific_time"], ["adversarial", "relative_time"]),
    ("got the house to myself tonight", ["child_ownership", "alone", "guardian_absent"], ["adversarial", "indirect_guardian"]),
    ("same place every tuesday after school", ["child_ownership", "routine"], ["adversarial", "indirect_routine"]),
    ("you know where, usual time", ["child_ownership", "meetup", "routine"], ["adversarial", "indirect_routine"]),
    ("don't tell my mum but come by", ["child_ownership", "meetup", "guardian_absent"], ["adversarial", "secrecy"]),
    ("come alone", ["meetup"], ["adversarial", "secrecy"]),
    ("delete this after you read it, i'm at 412 delmar", ["child_ownership", "child_location"], ["adversarial", "secrecy"]),

    # ==================================================================================
    # Dangerous combinations
    # ==================================================================================
    ("I'm at 88 Cranbrook Ave alone right now", ["child_ownership", "child_location", "alone"], ["combination", "critical"]),
    ("come to 412 delmar rd, my parents won't be back until 11", ["child_ownership", "child_location", "guardian_absent", "specific_time", "meetup"], ["combination", "critical"]),
    ("im home alone at 7 ridgemont until 9, you should come", ["child_ownership", "child_location", "alone", "guardian_absent", "specific_time", "meetup"], ["combination", "critical"]),
    ("practice at Brookhaven ends at 5 every Thursday and I wait outside", ["child_ownership", "school_context", "child_location", "routine", "specific_time"], ["combination", "critical"]),
    ("meet me behind Brookhaven at 5, nobody's around then", ["child_ownership", "school_context", "child_location", "meetup", "specific_time", "alone"], ["combination", "critical"]),
    ("i walk home down Fenwick alone every day around 4", ["child_ownership", "child_location", "routine", "alone", "specific_time"], ["combination", "critical"]),
    ("text me at 604-555-0182 and come to 88 cranbrook", ["child_ownership", "child_location", "meetup"], ["combination", "critical"]),
    ("nobody's here till 10, address is 33 bellwood", ["child_ownership", "child_location", "alone", "guardian_absent", "specific_time"], ["combination", "critical"]),

    # ==================================================================================
    # Conversational ambiguity — needs the sentence, not a keyword
    # ==================================================================================
    ("are you home alone too?", [], ["ambiguity", "question"]),
    ("were you home alone when it happened", [], ["ambiguity", "question"]),
    ("what's your address", [], ["ambiguity", "question"]),
    ("why do you want my address", [], ["ambiguity", "question"]),
    ("i'm not telling you where i live", [], ["ambiguity", "refusal"]),
    ("stop asking where i live", [], ["ambiguity", "refusal"]),
    ("i wouldn't be home alone, my brother's always here", [], ["ambiguity", "negation"]),
    ("i'm never alone after school", [], ["ambiguity", "negation"]),
    ("my parents are always home", [], ["ambiguity", "negation"]),
    ("if i were home alone i'd say", [], ["ambiguity", "hypothetical"]),
    ("imagine living at 24 oak street lol", [], ["ambiguity", "hypothetical"]),
    ("he said he lives at 24 oak street", [], ["ambiguity", "reported_speech"]),
    ("she asked if i was home alone and i said no", [], ["ambiguity", "reported_speech"]),

    # ==================================================================================
    # Ordinary safe conversation — the distribution the model spends its life on
    # ==================================================================================
    ("hey what's up", [], ["safe"]),
    ("nothing much, you?", [], ["safe"]),
    ("that test was brutal", [], ["safe"]),
    ("did you finish the history essay", [], ["safe"]),
    ("i'm so tired", [], ["safe"]),
    ("lmaooo", [], ["safe"]),
    ("wanna play later?", [], ["safe"]),
    ("my brother ate the last of the cereal", [], ["safe"]),
    ("i think i left my hoodie at yours", [], ["safe"]),
    ("what's the wifi password again", [], ["safe"]),
    ("did you see what she posted", [], ["safe"]),
    ("this song is so good", [], ["safe"]),
    ("i can't stop laughing", [], ["safe"]),
    ("ok see you tomorrow", [], ["safe"]),
    ("happy birthday!!", [], ["safe"]),
    ("i'm gonna fail this", [], ["safe"]),
    ("brb dinner", [], ["safe"]),
    ("do you have a pencil", [], ["safe"]),
    ("the wifi here is so slow", [], ["safe"]),
    ("i got a new phone case", [], ["safe"]),
]


def rows_to_examples(rows: list[Row]) -> list[dict]:
    out = []
    for index, (text, active, tags) in enumerate(rows):
        unknown = set(active) - set(CONTEXT_LABELS)
        if unknown:
            raise ValueError(f"row {index} ({text!r}) has unknown labels {sorted(unknown)}")
        if not tags:
            raise ValueError(f"row {index} ({text!r}) has no tags")
        out.append(
            {
                "text": text,
                "labels": {label: int(label in active) for label in CONTEXT_LABELS},
                "tags": tags,
                "source": "gold",
                # No `spans`: these are hand-authored for context supervision and nobody
                # annotated character offsets. `encode_example` sees source != synthetic
                # and no spans, so the token head is correctly left unsupervised here
                # rather than being told every one of them is entity-free.
                "uid": f"gold-{index:04d}",
            }
        )
    return out


def gold_status(rows: list[Row]) -> dict:
    tag_counts: Counter = Counter(tag for _, _, tags in rows for tag in tags)
    label_counts: Counter = Counter(label for _, active, _ in rows for label in active)
    return {
        "n": len(rows),
        "target_min": 1000,
        "target_max": 3000,
        "meets_target": len(rows) >= 1000,
        "by_tag": dict(tag_counts.most_common()),
        "positives_by_label": {label: label_counts.get(label, 0) for label in CONTEXT_LABELS},
        "labels_with_no_positives": [
            label for label in CONTEXT_LABELS if not label_counts.get(label)
        ],
    }


def main() -> None:
    examples = rows_to_examples(GOLD)
    out_dir = Path(__file__).parent
    out_path = out_dir / "gold_v1.jsonl"
    with out_path.open("w", encoding="utf-8") as fh:
        for example in examples:
            fh.write(json.dumps(example, ensure_ascii=False) + "\n")

    status = gold_status(GOLD)
    (out_dir / "gold_status.json").write_text(
        json.dumps(status, indent=2) + "\n", encoding="utf-8"
    )

    seen: Counter = Counter(row[0].strip().lower() for row in GOLD)
    duplicates = [text for text, count in seen.items() if count > 1]

    print(f"wrote {status['n']} gold examples -> {out_path}")
    print(f"positives by label: {status['positives_by_label']}")
    if status["labels_with_no_positives"]:
        print(f"  LABELS WITH NO POSITIVES: {status['labels_with_no_positives']}")
    if duplicates:
        print(f"  DUPLICATE TEXTS: {duplicates}")
    if not status["meets_target"]:
        print(
            f"  NOTE: §19 asks for 1,000-3,000 hand-authored examples; this is "
            f"{status['n']}. The set covers every required category but is not yet at "
            f"target size, so §34's Definition of Done is not met."
        )


if __name__ == "__main__":
    main()
