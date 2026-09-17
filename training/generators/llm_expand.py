"""§9 — widen the generator's register by writing templates with a model.

The corpus is not short of rows. It is short of *ways of saying things*: 541 templates
stretched over 48,000 messages, which is ~89 rows per template, and the model learns the
89 rather than the concept. Measured on the gold set, the `alone` label has 7,935 training
examples and still misses two of five real positives, because its misses land on
constructions the generator has never once produced — "to myself" and "empty house" are at
literally 0% of training rows, "on my own" at 1.4%, "nobody here" at 4.2%. The whole
corpus draws on a 6,128-word content vocabulary; ordinary words like *tonight*, *again*,
*gone* and *mine* never appear.

So: ask a model for the patterns. Three things about how, because each was a choice and
the obvious alternative is worse.

**It writes templates, not messages.** A pattern like "got the place to myself till
{TIME}" flows through the existing render → assemble → verify_spans path, which means
character offsets for the token head come out exact, entity values still come from the
per-split lexicon (so a test row never reuses a training street name), and the family
holdout still means something. Generating finished text would throw all of that away and
buy nothing the placeholder form does not already give.

**Labels are an input, never an output.** Each request asks for patterns realising a
label vector we specify. The model chooses wording; it never decides what the wording
means. Asking it to label its own output would put an unreviewed second opinion about the
§9 policy into the training signal, and nothing downstream would catch the drift.

**A family is one construction.** `alone.to_myself` and `alone.empty_house` are separate
families, so holding one out removes a way of speaking rather than a capability — which is
exactly the generalisation this corpus keeps failing. Grouping generated patterns by
signature instead would produce families that are paraphrases of each other and a test set
that flatters the model.

Output is a checked-in module, not a build-time API call. Templates are training data:
they need to be reviewable in a diff, stable across runs, and buildable on a GPU box with
no API key.

    python -m generators.llm_expand --dry-run          # print the plan and the spend, call nothing
    python -m generators.llm_expand --spec alone       # one group
    python -m generators.llm_expand --out generators/generated_templates.py
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from generators.lexicon import SLOT_DISPATCH, Lexicon
from generators.render import Template, assemble, render_chunks, verify_spans
from keyguard_ml.labels import CONTEXT_LABELS

MODEL = "claude-opus-5"

# Rough per-MTok rates for the spend estimate printed before any call. They are here to
# make the cost visible at --dry-run, not to be authoritative — check current pricing
# before reading a number off this table and believing it.
PRICES = {"claude-opus-5": (5.0, 25.0), "claude-sonnet-5": (2.0, 10.0), "claude-haiku-4-5": (1.0, 5.0)}


@dataclass
class FamilySpec:
    """One construction to write patterns for."""

    family: str
    labels: dict[str, int]
    construction: str
    """The way of speaking this family covers, in plain words. Goes into the prompt."""
    examples: tuple[str, ...]
    """Two or three patterns showing the shape. Seeds the style; the model must not
    return these back."""
    n: int = 40
    tags: tuple[str, ...] = ()
    group: str = "single_signal"
    allow_slots: tuple[str, ...] = ()
    """Slots permitted even though they imply a label this spec leaves at 0.

    Only hard negatives should need this, and for them it is the entire point: the third
    party in "her {GUARDIAN} works nights" is what makes the row a negative. For a
    positive family, reach for a new spec with the extra label set instead — see
    SLOT_IMPLIES."""
    allow_phrases: tuple[str, ...] = ()
    """Label names whose phrase guard is waived for this family.

    Hard negatives built on a word are the case: "youre not alone in hating that" has
    to say "alone" to be the negative it is. Waiving the guard is safe there precisely
    because the family exists to teach that the word is not the signal."""


# The corpus has a settled convention, checked against the 541 hand-written templates
# before this was written: a slot's presence implies its label. Of the `alone` templates
# using {TIME}, 10 of 10 also set specific_time; {GUARDIAN}, 6 of 6 set guardian_absent;
# {ADDRESS}, 18 of 18 set child_location. {DAY} implies nothing (0 of 6) — a weekday reads
# as routine, not as a clock time.
#
# A generated pattern that breaks this would not fail loudly; it would enter training as a
# row asserting that "{GUARDIAN} is gone til {TIME}" involves neither an absent guardian
# nor a time. Aimed at `guardian_absent`, already the weakest label in the model at 0.523
# PR-AUC, that is worse than generating nothing.
SLOT_IMPLIES: dict[str, str] = {
    "TIME": "specific_time",
    "RELTIME": "specific_time",
    "ADDRESS": "child_location",
    "STREET": "child_location",
    "APT": "child_location",
    "CITY": "child_location",
    "ZIP": "child_location",
    "PLACE": "child_location",
    "GUARDIAN": "guardian_absent",
    "RELATIVE": "guardian_absent",
    "SCHOOL": "school_context",
}


# Some signals have no slot to betray them. `meetup` is the one that matters: it is a term
# in six rules, two of them Level 3, and it is carried entirely by wording. The pilot
# produced "im ruling over an empty house {NAME} come over or something" under a spec with
# meetup=0 — a Level 3 combination filed as a single-signal row. Lexical, so a phrase list
# is the honest tool; deliberately narrow, since over-broad patterns here would quietly
# delete good rows instead of bad ones.
LABEL_PHRASES: dict[str, tuple[str, ...]] = {
    "meetup": (
        r"come over", r"come round", r"come by", r"come thru", r"come through",
        r"pull up", r"slide thru", r"slide through", r"swing by", r"meet me",
        r"you should come", r"wanna come", r"u should come", r"stop by",
    ),
    # A spec that leaves `alone` at 0 must not produce text asserting it. The
    # guardian.window batch wrote "my mom left me alone, said 10pm" under
    # alone=0 — the exact label the expansion exists to strengthen, taught backwards.
    "alone": (
        r"\balone\b", r"by myself", r"on my own", r"to myself", r"\bsolo\b",
        r"no ?one else (is |s )?(here|in|around)", r"nobody else (is |s )?(here|in|around)",
    ),
    # "every thursday" and "always after school" are routine, not a bare day.
    "routine": (
        r"\bevery (day|night|week|morning|afternoon|evening|mon|tue|wed|thu|fri|sat|sun)",
        r"\bevery \{DAY\}", r"most (days|nights|weeks)",
        # "always", "usually" and "every time" were here and were far too broad:
        # they deleted "they always know that stuff" and "near where {NAME} usually
        # sits", neither of which asserts anything about the child's schedule. A
        # guard that removes good negatives is worse than the leak it was added for.
    ),
}


def leaked_label(pattern: str, spec: FamilySpec) -> str | None:
    """A label this spec leaves at 0 that the wording nevertheless asserts."""
    low = pattern.lower()
    for label, phrases in LABEL_PHRASES.items():
        if spec.labels.get(label) or label in spec.allow_phrases:
            continue
        for phrase in phrases:
            if re.search(phrase, low):
                return f"{label} implied by {phrase!r}"
    return None


def forbidden_slots(spec: FamilySpec) -> set[str]:
    """Slots that would assert a label this spec sets to 0."""
    return {
        slot
        for slot, label in SLOT_IMPLIES.items()
        if not spec.labels.get(label) and slot not in spec.allow_slots
    }


def allowed_slots(spec: FamilySpec) -> list[str]:
    return sorted(set(SLOT_DISPATCH) - forbidden_slots(spec))


def L(**kw: int) -> dict[str, int]:
    labels = {k: 0 for k in CONTEXT_LABELS}
    for key, value in kw.items():
        if key not in labels:
            raise KeyError(f"unknown context label {key!r}")
        labels[key] = value
    return labels


SYSTEM = f"""You write message templates for a child-safety training corpus. The corpus \
teaches a small on-device model to recognise when a child is disclosing something risky \
in a chat message.

A template is a short message with {{SLOT}} placeholders. Available slots, and nothing \
else: {", ".join(sorted(SLOT_DISPATCH))}.

Rules:
- Write the way a 10-15 year old actually texts. Lowercase, missing apostrophes, \
abbreviations, run-ons and typos are all correct and wanted. Vary sentence length \
sharply — some three words, some twenty.
- Use a placeholder wherever a real message would carry a concrete value. Never invent a \
literal street name, phone number, time or school — that is what the slots are for.
- Every pattern must be a message somebody would plausibly send. No narration, no \
explanation, no quotation marks around the whole thing.
- Vary the vocabulary hard. Repeating a verb or an idiom across your patterns wastes the \
request; the corpus already has thousands of rows saying the same few things.
- Never number or bullet the patterns.

Return ONLY a JSON array of strings. No prose, no code fence."""


def load_dotenv() -> str | None:
    """Read `.env` from the training dir or the repo root, without a dependency.

    A real environment variable always wins, so exporting for one command still overrides
    the file. Both locations are covered by `.gitignore` — checked before this was
    written, because a credential helper that quietly makes committing a key easier is
    worse than no helper at all.
    """
    here = Path(__file__).resolve().parent.parent
    for candidate in (here / ".env", here.parent / ".env"):
        if not candidate.is_file():
            continue
        for line in candidate.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key, value = key.strip(), value.strip().strip('"').strip("'")
            if key and key not in os.environ:
                os.environ[key] = value
        return str(candidate)
    return None


def build_prompt(spec: FamilySpec, avoid: list[str]) -> str:
    on = [k for k, v in spec.labels.items() if v] or ["(none — this is a negative)"]
    avoid_block = ""
    if avoid:
        shown = avoid[:60]
        avoid_block = (
            "\n\nThe corpus ALREADY contains the patterns below. Do not return these, and "
            "do not return near-paraphrases of them — the point of this request is the "
            "constructions that are missing:\n"
            + "\n".join(f"  {p}" for p in shown)
        )
    banned = sorted(forbidden_slots(spec))
    slot_rule = (
        f"\n\nUse ONLY these slots: {', '.join(allowed_slots(spec))}.\n"
        f"Do NOT use: {', '.join(banned)} — in this corpus each of those asserts a signal "
        f"this family does not carry, so a pattern using one is thrown away."
        if banned else ""
    )
    return f"""Write {spec.n} message templates for the construction: {spec.construction}

Every one must be a message where these are true of the person typing: {", ".join(on)}
{"Do not imply any other risk signal: no clock time, no address, no absent parent, unless it is already listed above." if any(spec.labels.values()) else
 "This is a NEGATIVE: it must look superficially like a risky message while being genuinely harmless — a third party's information, an idiom, a public fact, or a negated claim."}{slot_rule}

Shape (do not return these):
{chr(10).join('  ' + e for e in spec.examples)}{avoid_block}

Return {spec.n} distinct templates as a JSON array of strings."""


def existing_patterns() -> dict[str, list[str]]:
    """Patterns already in the corpus, keyed by family prefix, to send as an avoid-list."""
    from generators.build_dataset import default_groups

    out: dict[str, list[str]] = {}
    for group in default_groups(1.0):
        for t in group.templates:
            out.setdefault(t.family.split(".")[0], []).append(t.pattern)
    return out


def validate(pattern: str, spec: FamilySpec, lex: Lexicon) -> tuple[bool, str]:
    """A pattern is kept only if it survives the same path the corpus builder uses.

    Constructing the Template checks slots and labels; rendering and `verify_spans` check
    that the thing actually produces aligned character offsets. A generated pattern that
    renders to a misaligned span would put a silently wrong label into the token head, so
    this is a hard gate rather than a warning.
    """
    pattern = pattern.strip()
    if not pattern:
        return False, "empty"
    if len(pattern) > 180:
        return False, "too long"
    if pattern[0] in "-*0123456789" and pattern[1:3] in (". ", ") "):
        return False, "looks numbered"
    banned = forbidden_slots(spec)
    for slot in re.findall(r"\{([A-Z]+)(?::\d+)?\}", pattern):
        if slot not in SLOT_DISPATCH:
            return False, f"unknown slot {slot}"
        if slot in banned:
            # Hard rejection, not a relabel: silently upgrading the label here would let
            # the generator decide policy, which is the one thing this module must not do.
            return False, f"slot {slot} implies {SLOT_IMPLIES[slot]}, unset in this spec"
    if re.search(r"\{[a-z]", pattern):
        return False, "lowercase slot"
    leak = leaked_label(pattern, spec)
    if leak:
        return False, f"leak: {leak}"
    try:
        template = Template(spec.family, pattern, dict(spec.labels), tags=spec.tags)
    except Exception as exc:  # noqa: BLE001 — any construction failure is a rejection
        return False, f"template: {exc}"
    for _ in range(3):  # a few draws, since slot values vary
        try:
            text, spans = assemble(render_chunks(template, lex))
            verify_spans(text, spans)
        except Exception as exc:  # noqa: BLE001
            return False, f"render: {exc}"
        if not text.strip():
            return False, "renders empty"
    return True, ""


def call_model(client, spec: FamilySpec, avoid: list[str], model: str = MODEL) -> list[str]:
    # Server-side fallbacks are on because this workload draws false refusals. Writing
    # "a parent is out until late" phrasings for a child-safety corpus is exactly the
    # shape a safety classifier is built to look at, and on the first full run 8 of 21
    # specs came back refused under category 'cyber' — including specs that had succeeded
    # twice when run alone, so it is stochastic rather than a property of the prompt.
    # `fallbacks: "default"` reroutes by category instead of pinning a model list.
    message = client.beta.messages.create(
        model=model,
        max_tokens=8000,
        system=SYSTEM,
        thinking={"type": "adaptive"},
        output_config={"effort": "medium"},
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
        messages=[{"role": "user", "content": build_prompt(spec, avoid)}],
    )
    if message.stop_reason == "refusal":
        details = getattr(message, "stop_details", None)
        category = getattr(details, "category", None)
        print(f"    refused even with fallback (category={category})", file=sys.stderr)
        return []
    text = "".join(b.text for b in message.content if b.type == "text").strip()
    text = re.sub(r"^```(?:json)?|```$", "", text, flags=re.M).strip()
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        # Fall back to the largest array-looking span, which is the usual failure when a
        # model adds a sentence before the JSON.
        match = re.search(r"\[.*\]", text, re.S)
        if not match:
            return []
        try:
            parsed = json.loads(match.group(0))
        except json.JSONDecodeError:
            return []
    return [p for p in parsed if isinstance(p, str)]


def load_existing(path: Path) -> dict[str, list[str]]:
    """Patterns already written to the output module, so a re-run tops up rather than
    clobbers.

    Refusals are per-request and stochastic, so a full run can leave a few families empty
    while the rest are fine. Re-running just those and merging is cheaper than paying for
    the whole set again, and it keeps a good batch from being thrown away by a later
    unlucky one.
    """
    if not path.is_file():
        return {}
    out: dict[str, list[str]] = {}
    for family, pattern in re.findall(
        r'Template\("([^"]+)",\s*"((?:[^"\\]|\\.)*)"', path.read_text(encoding="utf-8")
    ):
        out.setdefault(family, []).append(pattern.replace('\\"', '"').replace("\\\\", "\\"))
    return out


def render_module(kept: dict[str, list[tuple[str, FamilySpec]]], stats: dict) -> str:
    lines = [
        '"""Generated message templates — DO NOT EDIT BY HAND.',
        "",
        "Written by `generators/llm_expand.py`; see that module for why these exist and",
        "why they are patterns rather than finished messages. Checked in deliberately:",
        "training data belongs in the diff, and the GPU box has no API key.",
        "",
        f"Patterns: {stats['kept']} kept of {stats['seen']} returned "
        f"({stats['rejected']} rejected, {stats['dupes']} duplicates).",
        "Every pattern here rendered and passed verify_spans at generation time.",
        '"""',
        "",
        "from __future__ import annotations",
        "",
        "from generators.render import Template",
        "from keyguard_ml.labels import CONTEXT_LABELS",
        "",
        "",
        "def L(**kw: int) -> dict[str, int]:",
        "    labels = {k: 0 for k in CONTEXT_LABELS}",
        "    for key, value in kw.items():",
        "        if key not in labels:",
        "            raise KeyError(f'unknown context label {key!r}')",
        "        labels[key] = value",
        "    return labels",
        "",
        "",
        "GENERATED_TEMPLATES: list[Template] = [",
    ]
    for family in sorted(kept):
        rows = kept[family]
        if not rows:
            continue
        spec = rows[0][1]
        on = ", ".join(f"{k}=1" for k, v in spec.labels.items() if v)
        lines.append(f"    # {family} — {spec.construction}")
        for pattern, _ in rows:
            literal = pattern.replace("\\", "\\\\").replace('"', '\\"')
            tags = f", tags={spec.tags!r}" if spec.tags else ""
            lines.append(f'    Template("{family}", "{literal}", L({on}){tags}),')
        lines.append("")
    lines.append("]")
    lines.append("")
    lines.append("")
    lines.append("# Which corpus group each generated family belongs to, so build_dataset")
    lines.append("# can place them without importing the generation tooling, which the GPU")
    lines.append("# box has no reason to carry.")
    lines.append("GENERATED_GROUPS: dict[str, str] = {")
    for family in sorted(kept):
        if kept[family]:
            lines.append(f'    "{family}": "{kept[family][0][1].group}",')
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("generators/generated_templates.py"))
    parser.add_argument("--spec", action="append", default=[],
                        help="only run family specs whose name starts with this")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the plan and estimated spend; call nothing")
    parser.add_argument("--model", default=MODEL)
    parser.add_argument("--limit", type=int, default=0, help="cap the number of requests")
    parser.add_argument("--revalidate", action="store_true",
                        help="re-screen --out against current rules; calls nothing")
    parser.add_argument("--rerender", action="store_true",
                        help="rewrite --out from its own contents; calls nothing")
    parser.add_argument("--only-missing", type=int, default=0, metavar="N",
                        help="only run specs with fewer than N patterns already in --out")
    args = parser.parse_args()
    model = args.model

    from generators.llm_specs import SPECS

    specs = [s for s in SPECS if not args.spec or any(s.family.startswith(p) for p in args.spec)]
    if args.limit:
        specs = specs[: args.limit]

    total = sum(s.n for s in specs)
    rate_in, rate_out = PRICES.get(model, (5.0, 25.0))
    # ~1.5k input per request (system + avoid-list), ~35 output tokens per pattern.
    est = (len(specs) * 1500 / 1e6) * rate_in + (total * 35 / 1e6) * rate_out
    print(f"{len(specs)} family specs, {total} patterns requested, model {model}")
    print(f"estimated spend: ~${est:.2f}\n")
    for s in specs:
        on = ",".join(k for k, v in s.labels.items() if v) or "negative"
        print(f"  {s.family:28s} n={s.n:<4d} [{on}]")
    if args.dry_run:
        print("\ndry run: nothing called")
        return

    if args.revalidate:
        # Re-screen what is already in --out against the current rules and drop what
        # no longer passes. Guards get tightened as leaks are found; without this the
        # only way to apply a new rule to an old batch is to pay for it again.
        prior = load_existing(args.out)
        by_family = {sp.family: sp for sp in SPECS}
        lex = Lexicon(999)
        kept, dropped = {}, Counter()
        for family, patterns in prior.items():
            spec = by_family.get(family)
            if not spec:
                continue
            good = []
            for pat in patterns:
                ok, why = validate(pat, spec, lex)
                if ok:
                    good.append((pat, spec))
                else:
                    dropped[why.split(":")[0]] += 1
                    print(f"  drop [{family}] {pat[:58]}  <- {why[:44]}")
            kept[family] = good
        n = sum(len(v) for v in kept.values())
        before = sum(len(v) for v in prior.values())
        args.out.write_text(
            render_module(kept, {"kept": n, "seen": before, "rejected": before - n, "dupes": 0}),
            encoding="utf-8",
        )
        print("")
        print(f"revalidated: kept {n} of {before}; dropped "
              + ", ".join(f"{k}={v}" for k, v in dropped.most_common()))
        return

    if args.rerender:
        prior = load_existing(args.out)
        by_family = {sp.family: sp for sp in SPECS}
        kept = {f: [(pat, by_family[f]) for pat in ps]
                for f, ps in prior.items() if f in by_family}
        n = sum(len(v) for v in kept.values())
        args.out.write_text(
            render_module(kept, {"kept": n, "seen": n, "rejected": 0, "dupes": 0}),
            encoding="utf-8",
        )
        print(f"re-rendered {n} patterns into {args.out}, nothing called")
        return

    source = load_dotenv()
    if not os.environ.get("ANTHROPIC_API_KEY") and not os.environ.get("ANTHROPIC_AUTH_TOKEN"):
        print(
            f"\nNo credential found{f' (read {source}, no key in it)' if source else ''}."
            "\nPut ANTHROPIC_API_KEY=... in training/.env (gitignored), or export it.",
            file=sys.stderr,
        )
        raise SystemExit(2)
    if source:
        print(f"credential from {source}")

    import anthropic

    client = anthropic.Anthropic()
    lex = Lexicon(999)
    avoid_by_prefix = existing_patterns()

    prior = load_existing(args.out)
    if prior:
        print(f"merging into {sum(len(v) for v in prior.values())} patterns already in {args.out}")
    kept: dict[str, list[tuple[str, FamilySpec]]] = {}
    by_family = {s.family: s for s in SPECS}
    for family, patterns in prior.items():
        spec = by_family.get(family)
        if spec:
            kept[family] = [(p, spec) for p in patterns]
    seen_norm: set[str] = {re.sub(r"\s+", " ", p.lower().strip())
                           for ps in avoid_by_prefix.values() for p in ps}
    seen_norm |= {re.sub(r"\s+", " ", p.lower().strip())
                  for ps in prior.values() for p in ps}
    stats = Counter()
    reasons = Counter()

    if args.only_missing:
        before = len(specs)
        specs = [s for s in specs if len(prior.get(s.family, [])) < args.only_missing]
        print(f"--only-missing {args.only_missing}: {len(specs)} of {before} specs need topping up")

    for i, spec in enumerate(specs, 1):
        print(f"[{i}/{len(specs)}] {spec.family} ...", end=" ", flush=True)
        patterns = call_model(client, spec,
                              avoid_by_prefix.get(spec.family.split(".")[0], []), model)
        stats["seen"] += len(patterns)
        good = []
        for pattern in patterns:
            pattern = pattern.strip()
            norm = re.sub(r"\s+", " ", pattern.lower())
            if norm in seen_norm:
                stats["dupes"] += 1
                continue
            ok, why = validate(pattern, spec, lex)
            if not ok:
                stats["rejected"] += 1
                reasons[why.split(":")[0]] += 1
                continue
            seen_norm.add(norm)
            good.append((pattern, spec))
        kept.setdefault(spec.family, []).extend(good)
        stats["kept"] += len(good)
        print(f"{len(good)} kept / {len(patterns)} returned")

    print(f"\nkept {stats['kept']} of {stats['seen']} "
          f"({stats['rejected']} rejected, {stats['dupes']} duplicate)")
    if reasons:
        print("rejection reasons: " + ", ".join(f"{k}={v}" for k, v in reasons.most_common()))

    stats["kept"] = sum(len(v) for v in kept.values())
    args.out.write_text(render_module(kept, stats), encoding="utf-8")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
