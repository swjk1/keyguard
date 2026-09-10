# Keyguard V1 model training

On-device NLP for the Keyguard safety layer. One compact transformer encoder with two
heads: PII entity spans, and eight contextual safety signals. A deterministic risk engine
turns those signals into Level 0–3; the model never decides a risk level itself.

Implements the V1 Model Training Technical Plan. Section references below (§n) point at
that document.

## Status

| Stage | State |
|---|---|
| §1–§5 taxonomy and OpenPII mapping | Done — `keyguard_ml/labels.py`, exported as a JSON contract |
| M1 OpenPII preprocessing | Done — streams the real 1.5M corpus, verified on 2k rows |
| M2 phase A (PII head) | Code + config done; **not yet trained** |
| M3–M4 synthetic corpus | Done — 60,000 examples, 84 template families |
| M5 phase C multi-task | Code + config done; **not yet trained** |
| M6 gold evaluation set | **Partial** — 139 hand-authored examples against a target of 1,000–3,000 |
| M7 evaluation + error analysis | Done — §20/§21/§24 reports, verified end to end |
| M8 ONNX export | Done — single-file export, parity check at 1.2e-06, zero decision flips |
| M9 quantization | Done — INT8 comparison harness; 17.81 MB → 4.67 MB on a smoke model |
| M9 Android benchmark | **Not started** — needs a device |
| M10 risk engine integration | Rules written and exported as JSON; **Kotlin side not built** |

**No model has been trained yet.** Every number produced so far comes from a
deliberately undersized smoke model (BERT-Tiny, one epoch, ~900 examples) whose only
purpose was to prove the pipeline runs. Nothing in `models/` is a result.

## Quick start

### On a cloud GPU

```bash
cd training
bash scripts/setup_cloud.sh          # TORCH_INDEX=cu124 by default
bash scripts/run_pipeline.sh         # milestones 1-9, ~1 hour on a mid-range GPU
```

`SMOKE=1 bash scripts/run_pipeline.sh` runs the whole thing tiny in about five minutes.
Do that first on a new box — it costs almost nothing and catches a broken environment
before an hour of billing.

### Locally (data work and wiring only)

```bash
python -m venv .venv && .venv/Scripts/python -m pip install -r requirements.txt
.venv/Scripts/python -m generators.build_dataset --scale 1.0
```

Data generation is CPU work and belongs here rather than on rented hardware. Training on
CPU is not worth attempting beyond `SMOKE=1`.

## Layout

```
keyguard_ml/          the library — nothing here is a script
  labels.py           taxonomy: 8 context labels, 23 BIO tags, OpenPII mapping
  model.py            encoder + two heads + the ONNX export wrapper
  losses.py           §17, and the masking that makes mixed batches work
  metrics.py          §20 — per-signal and per-entity, no overall accuracy anywhere
  risk_engine.py      §21/§22 — declarative rules, exported as JSON for Kotlin
  trainer.py          §16 — one loop, three phases, §28's experiment record
  inference.py        checkpoint loading, shared by evaluation and export
  data/openpii.py     §3-§5 — stream, filter, map, window
  data/encoding.py    char spans -> wordpiece BIO; the two supervision shapes

generators/           §8-§12 — the synthetic corpus
  lexicon.py          entity values; Faker for the tail, hand-written for the register
  render.py           span-safe template rendering
  slang.py            §11 augmentation
  templates.py        single-signal + general-safe families
  combinations.py     §10 dangerous combinations
  hard_negatives.py   §12, grouped by the false positive each set prevents
  siblings.py         second families for signatures that would be untestable otherwise
  build_dataset.py    generate, verify, split, manifest

train/                three phase entry points over one engine
evaluation/           §20/§21/§24 in one pass
export/               §29 ONNX + parity, §30 INT8 + comparison
configs/              phase_a / phase_b / phase_c
datasets/             generated; not in version control except the gold set's authoring file
scripts/              cloud provisioning and the full pipeline
```

## Decisions that differ from the design doc

Each of these is a deliberate departure, not an oversight.

**The dataset is `pii-masking-openpii-1.5m`, not "OpenPII 1M".** No public dataset
matches the doc's name and 1.43M count. The current Ai4Privacy flagship is 1,636,375
examples, CC-BY-4.0, and that is what the pipeline reads. See `ATTRIBUTION.md` — there is
a licence question there that needs answering before commercial release.

**`CITY` is `coarse_location`, not `exact_location`.** §5 groups STREET, BUILDINGNUM,
ZIPCODE and CITY together. "I live in Toronto" and "I live at 24 Oak Street" are not the
same disclosure, and treating them alike forces the risk engine to be either hysterical
about the first or complacent about the second. The engine gets both buckets.

**The synthetic corpus supervises the token head too.** §15 has OpenPII teaching entities
and the custom data teaching context. But the generators *know* where they inserted an
address, so they emit character spans for free — which gives the token head in-domain
supervision on chat register. OpenPII is business prose ("The workshop scheduled on
1995-01-08T00:00:00 will be led by…"); without this the token head would only ever have
seen addresses in a register the phone never receives.

**`TIME` is a modelled entity even though OpenPII has no such label.** Same reason: the
generators can supervise it, and "at 5:30" is a span the risk engine wants to point at.

**Long OpenPII rows are windowed to ~320 characters.** The phone sees short messages in a
64–128 token window. Training on 500-character administrative documents teaches a length
and register distribution that does not occur at inference.

**Wordpiece continuations are `IGNORE`, not `I-`.** A nine-wordpiece email address
labelled through would become nine easy correct predictions and inflate every token
metric by an order of magnitude. Only first subwords carry supervision.

**Splitting is by signal signature, not by template family alone.** §18 says hold out
whole families, which is right but cannot be applied naively — several families are the
only one asserting their label combination, and holding one out removes the capability
from training rather than removing a paraphrase of it. Families are grouped by signature
and every signature keeps a train anchor; `generators/siblings.py` exists to give the
critical signatures a second family so they become testable at all. The build fails if
any label ends up with zero positives in any split.

**The risk engine is data, not code.** The rules live in a table that exports to
`risk_rules.json`, because the same policy has to run in Python for §21's metrics and in
Kotlin on the phone. Two implementations drift, and when they drift the experiment record
stops describing the shipped product.

## What the first real run already showed

A 4.4M-parameter BERT-Tiny, context head only, two epochs, three minutes on a laptop CPU.
Not a result to quote — but the pipeline it exercised is the one that matters, and it
produced two findings that are already actionable.

| | synthetic validation | human gold set |
|---|---|---|
| context macro-F1 | 0.906 | 0.739 |
| context macro PR-AUC | 0.930 | 0.835 |

Both splits hold out entire template families, so 0.906 is not memorisation. The gap to
0.739 is the synthetic-to-human generalisation gap, and measuring it is the entire reason
§19 asks for a hand-authored set.

**Per-label thresholds are worth a lot.** At a flat 0.5, macro-F1 was 0.74; with
thresholds selected on validation it was 0.906. `alone` went 0.42 → 0.92 and `meetup`
0.57 → 0.91 — their ranking was always good (PR-AUC 0.93+), the operating point was
simply wrong. `pos_weight` buys recall at precision's expense and threshold selection
gives it back.

**§24's error analysis names two specific gaps**, both of which are template work rather
than modelling work:

- *Indirect phrasing is missed* — `indirect_alone`, `indirect_guardian`,
  `indirect_routine` and `subtle` are dominated by false **negatives**. The model learned
  "im home alone" and not "it's just me and the dog" or "nobody's picking me up today".
  The generators are light on indirect constructions; the gold set is not, which is how
  this surfaced.
- *Negation and hypotheticals fire falsely* — `negation`, `hypothetical`, `refusal` and
  `declined_meetup` are dominated by false **positives**. "I'm never alone after school"
  and "imagine living at 24 oak street" both trip signals. There are no hard-negative
  families for negation or hypothetical framing at all; there should be.

**`child_location` is the weakest label** (gold F1 0.708, PR-AUC 0.837) and also the most
safety-critical, since it gates most Level 2 and Level 3 rules. It is the label that
requires distinguishing *a* location from *the child's* location, which is the hardest
thing in the taxonomy. Expect to spend disproportionate effort here.

## Things that are known to be unfinished

- **The gold set is 139 examples against §19's 1,000–3,000.** It covers every category
  the doc lists — subtle positives, hard negatives, slang, typos, incomplete messages,
  ownership ambiguity, adversarial forms, conversational ambiguity — so expanding it is
  addition rather than redesign. But §34's Definition of Done is not met, and the gold
  set is the only measurement not contaminated by sharing a generator with training.

- **Nothing has been trained.** Every metric in this repository so far is from a smoke
  model and means nothing.

- **The Kotlin side does not exist.** The export writes `vocab.txt`, `label_contract.json`,
  `risk_rules.json` and `tokenizer_fixtures.json` for it, but a WordPiece tokenizer in
  Kotlin still has to be written and made to agree with the fixtures. This is the most
  likely place for a silent failure: a lowercasing or accent-stripping mismatch produces
  different token ids and therefore different — but entirely plausible — probabilities.

- **No on-device benchmark (§31).** The latency numbers `export/quantize.py` prints are
  x86 ONNX Runtime and say very little about ARM. The p50 < 75 ms / p95 < 150 ms targets
  are unvalidated.

- **The risk rules have never been reviewed against product policy.** §21's report
  measures the model against the rules; it cannot tell you the rules are right, because a
  wrong rule is wrong identically on both sides of that comparison.

- **Vocabulary pruning is not implemented.** 15.6M of the encoder's 28.8M parameters are
  the 30,522-token embedding table. A chat-domain vocabulary would cut file size
  substantially and is likely the cheapest win available if the Android benchmark comes
  back too large. `keyguard_ml/model.py` notes where it would go.

## Cost and privacy

Training is a one-off cost on rented hardware, not a per-user cost: the shipped model
runs entirely on the device, makes no network call, and adds nothing to the per-message
AI spend. It is the local layer, and the layer Apple's guideline 4.4.1 requires to stand
alone.

No real children's messages are used, collected, or required — §34, and the project's
`PRIVACY.md`.
