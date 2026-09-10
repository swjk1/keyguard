#!/usr/bin/env bash
# Milestones 1-9 end to end, in order, on a GPU box.
#
#   bash scripts/run_pipeline.sh                 # full run
#   SMOKE=1 bash scripts/run_pipeline.sh         # tiny everything, ~5 minutes, proves wiring
#   SKIP_DATA=1 bash scripts/run_pipeline.sh     # reuse datasets/, retrain only
#
# Each stage is idempotent and writes its own manifest, so a run that dies partway can be
# resumed by re-running with SKIP_DATA=1.
#
# Cost note, because this is being run on rented hardware: stage 1 is the only one that
# touches the network at scale, and it streams rather than downloading the full 1.6M-row
# split. Stages 3-5 are the GPU spend. On a single mid-range GPU the whole thing is
# roughly an hour; on CPU it is not worth attempting beyond SMOKE=1.

set -euo pipefail
cd "$(dirname "$0")/.."

SMOKE="${SMOKE:-0}"
SKIP_DATA="${SKIP_DATA:-0}"
PY="${PY:-python}"

if [ "$SMOKE" = "1" ]; then
    OPENPII_TARGET=2000
    OPENPII_SCANNED=40000
    CORPUS_SCALE=0.05
    EPOCHS_A=1; EPOCHS_B=1; EPOCHS_C=1
    ENCODER="google/bert_uncased_L-2_H-128_A-2"
    BATCH=32
else
    OPENPII_TARGET=60000
    OPENPII_SCANNED=""
    CORPUS_SCALE=1.0
    EPOCHS_A=3; EPOCHS_B=4; EPOCHS_C=3
    ENCODER="google/bert_uncased_L-4_H-512_A-8"
    BATCH=64
fi

banner() { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }

# ---------------------------------------------------------------- milestone 1: OpenPII
if [ "$SKIP_DATA" != "1" ]; then
    banner "Milestone 1 — OpenPII preprocessing"
    SCAN_ARG=""
    [ -n "$OPENPII_SCANNED" ] && SCAN_ARG="--max-scanned $OPENPII_SCANNED"
    # shellcheck disable=SC2086
    $PY -m keyguard_ml.data.openpii \
        --split train --target "$OPENPII_TARGET" $SCAN_ARG \
        --out datasets/openpii/train.jsonl
    # shellcheck disable=SC2086
    $PY -m keyguard_ml.data.openpii \
        --split validation --target $((OPENPII_TARGET / 10)) $SCAN_ARG \
        --out datasets/openpii/validation.jsonl

    banner "Milestones 3-4 — synthetic child-safety corpus"
    $PY -m generators.build_dataset --scale "$CORPUS_SCALE" --out datasets/child_safety

    banner "Milestone 6 — gold evaluation set"
    PYTHONPATH=. $PY datasets/gold_test/author_gold.py

    $PY -m keyguard_ml.labels datasets/label_contract.json
    $PY -m keyguard_ml.risk_engine datasets/risk_rules.json
fi

COMMON="--set encoder_name=$ENCODER batch_size=$BATCH"

# --------------------------------------------------------- milestone 2: phase A (PII)
banner "Milestone 2 — phase A: PII token head"
# shellcheck disable=SC2086
$PY -m train.train_pii $COMMON epochs=$EPOCHS_A

# ------------------------------------------------------ milestone 3: phase B (context)
banner "Milestone 3 — phase B: contextual head"
# shellcheck disable=SC2086
$PY -m train.train_context $COMMON epochs=$EPOCHS_B

# ------------------------------------------------- milestone 5: phase C (multi-task)
banner "Milestone 5 — phase C: joint fine-tuning"
# shellcheck disable=SC2086
$PY -m train.train_multitask $COMMON epochs=$EPOCHS_C

# -------------------------------------------- milestone 7: evaluation + error analysis
banner "Milestone 7 — evaluation on held-out test split"
$PY -m evaluation.evaluate \
    --checkpoint models/phase_c/best.pt \
    --dataset datasets/child_safety/test.jsonl \
    --thresholds-from datasets/child_safety/validation.jsonl \
    --objective recall --min-precision 0.90 \
    --out models/phase_c/report_test.json

banner "Milestone 7 — evaluation on the human-curated gold set"
# The gold set is the number to quote. The synthetic test split shares a generator with
# training even though it shares no template family, and only the gold set is free of
# that. Thresholds still come from validation: fitting them on gold would turn the one
# clean measurement into another tuning set.
$PY -m evaluation.evaluate \
    --checkpoint models/phase_c/best.pt \
    --dataset datasets/gold_test/gold_v1.jsonl \
    --thresholds-from datasets/child_safety/validation.jsonl \
    --objective recall --min-precision 0.90 \
    --out models/phase_c/report_gold.json

# ------------------------------------------------------------- milestone 8: ONNX export
banner "Milestone 8 — ONNX export and parity check"
$PY -m export.export_onnx \
    --checkpoint models/phase_c/best.pt \
    --out models/export \
    --parity-dataset datasets/child_safety/test.jsonl \
    --parity-n 500

# ---------------------------------------------------------- milestone 9: quantization
banner "Milestone 9 — INT8 quantization comparison"
$PY -m export.quantize \
    --checkpoint models/phase_c/best.pt \
    --fp32 models/export/model.onnx \
    --dataset datasets/child_safety/test.jsonl

banner "done"
cat <<'EOF'
Artifacts:
  models/phase_c/experiment.json      §28 experiment record
  models/phase_c/report_test.json     §20/§21/§24 on the synthetic test split
  models/phase_c/report_gold.json     the same on the human-curated gold set  <- quote this
  models/export/                      model.onnx + vocab + contracts, ready for Android
  models/export/quantization_report.json   FP32 vs INT8 (§30)

Still to do by hand (§31, §34):
  - benchmark model.onnx on the oldest supported Android device
  - expand the gold set to §19's 1,000-3,000 examples
EOF
