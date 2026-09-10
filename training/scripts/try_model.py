"""Type a message, see what the model and the risk engine make of it.

Three modes:

    python -m scripts.try_model --checkpoint models/phase_c/best.pt
        interactive: type messages, one per line, Ctrl-C to quit

    python -m scripts.try_model --text "im home alone at 24 oak st until 9"
        one-shot, repeatable with several --text flags

    python -m scripts.try_model --demo
        a fixed battery of contrast pairs — the same sentence with and without the
        thing that should change the verdict

The `--demo` battery is the one worth running first. Almost every pair in it produces
*identical* token-head output and opposite risk levels, which is the whole claim the
architecture rests on: PII detection cannot tell you whether a disclosure is dangerous,
and the context head is what does.

Output shows probabilities against each label's selected threshold, not just the fired
signals, because a signal sitting at 0.48 against a 0.50 threshold is a different
engineering problem from one sitting at 0.02.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from keyguard_ml.inference import Predictor
from keyguard_ml.labels import CONTEXT_LABELS, ENTITY_TO_SAFETY_ENTITY
from keyguard_ml.risk_engine import RiskEngine

LEVEL_COLOUR = {0: "\033[32m", 1: "\033[33m", 2: "\033[35m", 3: "\033[31m"}
RESET = "\033[0m"
DIM = "\033[2m"
BOLD = "\033[1m"

DEMO_PAIRS: list[tuple[str, str]] = [
    # The doc's own examples (§15, §19).
    ("I live at 24 Oak Street.", "The restaurant is at 24 Oak Street."),
    ("My number is 514-555-1234.", "Call the restaurant at 514-555-1234."),
    ("I'm home alone tonight.", "I watched Home Alone tonight."),
    ("I'm home alone tonight.", "Just leave me alone."),
    ("Practice ends at 5 every Tuesday.", "The store closes at 5 every Tuesday."),
    ("I go to Lincoln Middle School.", "Lincoln Middle School won the tournament."),
    # Ownership.
    ("my address is 88 cranbrook ave", "my uncle's address is 88 cranbrook ave"),
    # Escalation: the same disclosure, plus one more signal each time.
    ("im at 412 delmar rd", "im at 412 delmar rd alone"),
    ("im at 412 delmar rd alone", "im at 412 delmar rd alone until 9, come over"),
    # Slang and adversarial forms (§11, §23).
    ("my addy is 24 oak st", "24 oak st"),
    ("got the house to myself tonight", "the house is being painted tonight"),
    ("parents back in 2 hrs", "the movie is 2 hrs long"),
]


def bar(probability: float, width: int = 22) -> str:
    filled = int(round(probability * width))
    return "█" * filled + "·" * (width - filled)


def render(prediction, engine: RiskEngine, thresholds: dict[str, float], verbose: bool) -> int:
    entities = prediction.entities()
    buckets = {
        bucket
        for _, _, entity in entities
        if (bucket := ENTITY_TO_SAFETY_ENTITY.get(entity))
    }
    result = engine.evaluate(prediction.context, buckets, thresholds)

    colour = LEVEL_COLOUR[result.level]
    print(f"\n{BOLD}{prediction.text}{RESET}")
    print(f"  {colour}{BOLD}LEVEL {result.level} — {result.level_name}{RESET}", end="")
    if result.message:
        print(f"  {colour}{result.message}{RESET}")
    else:
        print()

    if result.fired:
        print(f"  {DIM}rules: {', '.join(result.fired)}{RESET}")

    if entities:
        rendered = ", ".join(
            f"{entity}={prediction.text[start:end]!r}" for start, end, entity in entities
        )
        print(f"  {DIM}entities: {rendered}{RESET}")
    else:
        print(f"  {DIM}entities: none{RESET}")

    if verbose:
        for label in CONTEXT_LABELS:
            probability = prediction.context[label]
            threshold = thresholds.get(label, 0.5)
            fired = probability >= threshold
            mark = f"{colour}●{RESET}" if fired else f"{DIM}○{RESET}"
            style = "" if fired else DIM
            print(
                f"    {mark} {style}{label:16s} {probability:5.3f} "
                f"{bar(probability)} thr {threshold:.2f}{RESET}"
            )
    else:
        active = [l for l in CONTEXT_LABELS if prediction.context[l] >= thresholds.get(l, 0.5)]
        print(f"  {DIM}signals: {', '.join(active) if active else 'none'}{RESET}")

    return result.level


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--checkpoint", default="models/_cpu_signal/best.pt")
    parser.add_argument("--text", action="append", default=[])
    parser.add_argument("--demo", action="store_true")
    parser.add_argument("--quiet", action="store_true", help="hide the per-label probability bars")
    parser.add_argument("--threshold-override", type=float, default=None,
                        help="use one flat threshold for every label instead of the tuned ones")
    args = parser.parse_args()

    checkpoint = Path(args.checkpoint)
    if not checkpoint.exists():
        raise SystemExit(f"no checkpoint at {checkpoint}. Train one, or pass --checkpoint.")

    predictor = Predictor(checkpoint, device="cpu")
    engine = RiskEngine()
    thresholds = (
        {label: args.threshold_override for label in CONTEXT_LABELS}
        if args.threshold_override is not None
        else predictor.thresholds
    )

    meta = predictor.checkpoint_meta
    print(f"{DIM}checkpoint  {checkpoint}{RESET}")
    print(f"{DIM}encoder     {predictor.config.encoder_name} "
          f"({predictor.model.num_parameters()/1e6:.1f}M params){RESET}")
    if "score" in meta:
        print(f"{DIM}val score   {meta.get('score')}{RESET}")
    print(f"{DIM}thresholds  "
          f"{'flat ' + str(args.threshold_override) if args.threshold_override is not None else 'tuned on validation'}{RESET}")

    verbose = not args.quiet

    if args.demo:
        print(f"\n{BOLD}Contrast pairs — same PII, different meaning{RESET}")
        for left, right in DEMO_PAIRS:
            print(f"\n{DIM}{'─' * 72}{RESET}")
            predictions = predictor.predict_texts([left, right])
            levels = [render(p, engine, thresholds, verbose) for p in predictions]
            verdict = (
                f"{LEVEL_COLOUR[3]}separated{RESET}" if levels[0] != levels[1]
                else f"{LEVEL_COLOUR[1]}NOT separated — both Level {levels[0]}{RESET}"
            )
            print(f"  {DIM}→{RESET} {verdict}")
        return

    if args.text:
        for prediction in predictor.predict_texts(args.text):
            render(prediction, engine, thresholds, verbose)
        return

    if not sys.stdin.isatty():
        # Piped input: one message per line. Makes the tool usable from a script
        # without needing a terminal.
        lines = [line.strip() for line in sys.stdin if line.strip()]
        if not lines:
            raise SystemExit("no input. Pass --text, --demo, or pipe messages on stdin.")
        for prediction in predictor.predict_texts(lines):
            render(prediction, engine, thresholds, verbose)
        return

    print(f"\n{DIM}type a message and press enter. Ctrl-C to quit.{RESET}")
    while True:
        try:
            text = input(f"\n{BOLD}> {RESET}").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return
        if not text:
            continue
        if text in ("quit", "exit"):
            return
        render(predictor.predict_texts([text])[0], engine, thresholds, verbose)


if __name__ == "__main__":
    main()
