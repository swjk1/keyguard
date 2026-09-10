"""§30 — INT8 dynamic quantization, and the evidence needed to decide whether to ship it.

The doc's instruction is the important part: "Do not deploy INT8 merely because it is
smaller." So this script does not just quantize — it re-runs the full evaluation on the
quantized graph and prints FP32 and INT8 side by side, including Level 3 recall. A size
number on its own is not a decision.

Dynamic quantization is the right starting point for a transformer (weights quantized
ahead of time, activations at runtime) because it needs no calibration data and it targets
the MatMuls that dominate the cost. Static quantization would need a representative
calibration set and buys most of its extra speed on convolutions.

One caveat worth knowing before reading the numbers: quantized MatMul kernels on x86 and
on ARM are different implementations with different speedups. A latency win measured on
this laptop tells you almost nothing about a phone. §31's on-device benchmark is the
measurement that counts; this script's latency columns are a sanity check, not a result.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

from keyguard_ml.data.encoding import read_jsonl
from keyguard_ml.inference import Predictor
from keyguard_ml.labels import CONTEXT_LABELS
from keyguard_ml.metrics import context_report
from keyguard_ml.risk_engine import RiskEngine, entities_from_tags
from keyguard_ml.labels import ENTITY_TO_SAFETY_ENTITY, PII_ID_TO_TAG


def _strip_value_info(fp32_path: Path) -> Path:
    """Drop torch's intermediate shape annotations before quantizing.

    `torch.onnx.export` writes ~180 `value_info` entries describing intermediate
    tensors, and at least one of them contradicts what `onnx.shape_inference` derives
    for the same tensor. The graph runs correctly either way — ONNX Runtime does not
    consult those annotations — but `quantize_dynamic` calls shape inference first, and
    shape inference fails hard on a disagreement:

        InferenceError: Inferred shape and existing shape differ in dimension 0:
        (128) vs (8)

    Clearing the annotations lets inference derive them from the graph, which is where
    the authoritative answer was all along. Nothing is lost: they are re-derived
    immediately, and the initialisers and I/O signatures — the parts that matter — are
    untouched.
    """
    import onnx

    model = onnx.load(str(fp32_path))
    if not model.graph.value_info:
        return fp32_path
    del model.graph.value_info[:]
    cleaned = fp32_path.with_name(fp32_path.stem + "_novi.onnx")
    onnx.save(model, str(cleaned))
    return cleaned


def quantize(fp32_path: Path, int8_path: Path) -> Path:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    source = _strip_value_info(fp32_path)
    try:
        quantize_dynamic(
            model_input=str(source),
            model_output=str(int8_path),
            weight_type=QuantType.QInt8,
        )
    finally:
        if source != fp32_path:
            source.unlink(missing_ok=True)
    return int8_path


def _run_session(session, tokenizer, texts: list[str], max_length: int, batch_size: int):
    context_scores, token_tags, latencies = [], [], []
    for start in range(0, len(texts), batch_size):
        chunk = texts[start : start + batch_size]
        encoded = tokenizer(
            chunk, padding=True, truncation=True, max_length=max_length, return_tensors="np"
        )
        feed = {
            "input_ids": encoded["input_ids"].astype(np.int64),
            "attention_mask": encoded["attention_mask"].astype(np.int64),
        }
        began = time.perf_counter()
        token_probs, ctx_probs = session.run(None, feed)
        latencies.append((time.perf_counter() - began) / len(chunk) * 1000.0)
        context_scores.append(ctx_probs)
        argmax = token_probs.argmax(-1)
        for row, mask in zip(argmax, encoded["attention_mask"]):
            token_tags.append([PII_ID_TO_TAG[int(t)] for t, m in zip(row, mask) if m])
    return np.concatenate(context_scores), token_tags, latencies


def _single_message_latency(session, tokenizer, text: str, max_length: int, runs: int = 60):
    """p50/p95 for a batch of one, which is what the phone actually does.

    Batched throughput is the wrong number for §31: the runtime scores one message after
    a debounce, never thirty-two at once, and per-message cost in a batch of 32 is several
    times lower than the cost of a batch of 1.
    """
    encoded = tokenizer(text, truncation=True, max_length=max_length, return_tensors="np")
    feed = {
        "input_ids": encoded["input_ids"].astype(np.int64),
        "attention_mask": encoded["attention_mask"].astype(np.int64),
    }
    for _ in range(5):  # warm up
        session.run(None, feed)
    samples = []
    for _ in range(runs):
        began = time.perf_counter()
        session.run(None, feed)
        samples.append((time.perf_counter() - began) * 1000.0)
    samples.sort()
    return {
        "p50_ms": round(samples[len(samples) // 2], 2),
        "p95_ms": round(samples[int(len(samples) * 0.95)], 2),
        "tokens": int(encoded["input_ids"].shape[1]),
    }


def evaluate_session(session, predictor: Predictor, examples, thresholds, batch_size=32):
    texts = [e.text for e in examples]
    scores, token_tags, latencies = _run_session(
        session, predictor.tokenizer, texts, predictor.max_length, batch_size
    )

    supervised = [i for i, e in enumerate(examples) if e.context is not None]
    truths = np.array(
        [[float(examples[i].context[label]) for label in CONTEXT_LABELS] for i in supervised]
    )
    context = context_report(truths, scores[supervised], thresholds)

    engine = RiskEngine()
    gold_levels, pred_levels = [], []
    for i in supervised:
        example = examples[i]
        gold_entities = {
            bucket
            for span in example.spans
            if (bucket := ENTITY_TO_SAFETY_ENTITY.get(span["label"]))
        }
        gold_levels.append(
            engine.evaluate(
                {label: float(example.context[label]) for label in CONTEXT_LABELS},
                gold_entities,
                {label: 0.5 for label in CONTEXT_LABELS},
            ).level
        )
        pred_levels.append(
            engine.evaluate(
                dict(zip(CONTEXT_LABELS, scores[i].tolist())),
                entities_from_tags(token_tags[i]),
                thresholds,
            ).level
        )

    gold = np.array(gold_levels)
    pred = np.array(pred_levels)
    at3 = gold >= 3
    level3_recall = float((at3 & (pred >= 3)).sum() / max(1, at3.sum()))
    safe = gold == 0
    false_warnings = 1000 * float((safe & (pred > 0)).sum()) / max(1, int(safe.sum()))

    return {
        "context_macro_f1": context["macro_f1"],
        "context_macro_pr_auc": context["macro_pr_auc"],
        "level3_recall": round(level3_recall, 4),
        "false_warnings_per_1000_safe": round(false_warnings, 2),
        "mean_batched_ms_per_message": round(float(np.mean(latencies)), 3),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True, help="for the tokenizer and thresholds")
    parser.add_argument("--fp32", type=Path, default=Path("models/export/model.onnx"))
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--dataset", default="datasets/child_safety/test.jsonl")
    parser.add_argument("--n", type=int, default=2000)
    parser.add_argument("--latency-text", default="im home alone at 24 oak st until 9")
    args = parser.parse_args()

    import onnxruntime as ort

    int8_path = args.out or args.fp32.with_name(args.fp32.stem + "_int8.onnx")
    quantize(args.fp32, int8_path)

    predictor = Predictor(args.checkpoint, device="cpu")
    examples = read_jsonl(args.dataset)[: args.n]
    thresholds = predictor.thresholds

    rows = {}
    for name, path in (("FP32", args.fp32), ("INT8", int8_path)):
        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        metrics = evaluate_session(session, predictor, examples, thresholds)
        metrics["size_mb"] = round(path.stat().st_size / 1e6, 2)
        metrics.update(
            _single_message_latency(session, predictor.tokenizer, args.latency_text, predictor.max_length)
        )
        rows[name] = metrics

    fields = [
        ("size_mb", "model size (MB)", "{:.2f}"),
        ("p50_ms", "p50 latency, batch 1 (ms)", "{:.2f}"),
        ("p95_ms", "p95 latency, batch 1 (ms)", "{:.2f}"),
        ("context_macro_f1", "context macro-F1", "{:.4f}"),
        ("context_macro_pr_auc", "context macro PR-AUC", "{:.4f}"),
        ("level3_recall", "Level 3 recall", "{:.4f}"),
        ("false_warnings_per_1000_safe", "false warnings / 1k safe", "{:.2f}"),
    ]
    print(f"\n{'metric':30s} {'FP32':>12s} {'INT8':>12s} {'delta':>12s}")
    for key, label, fmt in fields:
        a, b = rows["FP32"][key], rows["INT8"][key]
        print(f"{label:30s} {fmt.format(a):>12s} {fmt.format(b):>12s} {fmt.format(b - a):>12s}")

    f1_drop = rows["FP32"]["context_macro_f1"] - rows["INT8"]["context_macro_f1"]
    recall_drop = rows["FP32"]["level3_recall"] - rows["INT8"]["level3_recall"]
    print("\nshipping guidance (§30):")
    print(f"  macro-F1 lost to quantization:      {f1_drop:+.4f}")
    print(f"  Level 3 recall lost:                {recall_drop:+.4f}")
    if recall_drop > 0.01:
        print("  -> Level 3 recall dropped by more than a point. Do not ship INT8 on")
        print("     size grounds; this is the metric the product cannot trade away.")
    elif f1_drop > 0.02:
        print("  -> macro-F1 dropped materially. Investigate per-label before shipping.")
    else:
        print("  -> accuracy cost is small. Decide on the on-device benchmark (§31).")

    report_path = int8_path.with_name("quantization_report.json")
    report_path.write_text(
        json.dumps(
            {
                "fp32": {"path": str(args.fp32), **rows["FP32"]},
                "int8": {"path": str(int8_path), **rows["INT8"]},
                "dataset": args.dataset,
                "n": len(examples),
                "note": (
                    "Latency measured with onnxruntime on x86. ARM kernels differ; §31's "
                    "on-device benchmark is the number that decides deployment."
                ),
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"\nreport -> {report_path}")


if __name__ == "__main__":
    main()
