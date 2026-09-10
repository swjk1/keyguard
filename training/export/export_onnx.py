"""§29 — PyTorch to ONNX, with the parity check treated as part of the export.

The export is not finished when the file is written; it is finished when PyTorch and ONNX
Runtime have been shown to agree on several hundred real messages. So `--skip-parity`
exists but is not the default, and a failing parity check is a non-zero exit rather than a
warning: an ONNX file that silently disagrees with the model that was evaluated is worse
than no ONNX file, because every metric in the experiment record now describes something
that is not what ships.

What gets written next to the model, and why each one is load-bearing on the Android side:

    model.onnx            the graph, probabilities out (see ExportWrapper)
    vocab.txt             the WordPiece vocabulary — the Kotlin tokenizer must use this
                          exact file, not a copy from anywhere else
    tokenizer_config.json lowercasing and special-token ids
    label_contract.json   label order for both heads; the runtime asserts against it
    risk_rules.json       the deterministic engine, shared with the Python evaluation
    export_manifest.json  what was exported, from which checkpoint, and the parity result

Tokenizer parity is the failure mode most likely to bite here and the one this script
cannot check: Kotlin has to reimplement WordPiece, and a mismatch in lowercasing or accent
stripping produces different token ids and therefore different — but entirely plausible —
probabilities. `--emit-tokenizer-fixtures` writes a set of (text, expected input_ids) pairs
so the Kotlin side has something to assert against.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import numpy as np
import torch

from keyguard_ml.data.encoding import read_jsonl
from keyguard_ml.inference import Predictor
from keyguard_ml.labels import export_contract
from keyguard_ml.model import ExportWrapper
from keyguard_ml.risk_engine import RiskEngine

# 18 rather than 17: torch's exporter implements 18 and silently down-converts a lower
# request, which prints a warning about the conversion possibly failing. Nothing here
# needs a 17-only runtime, so asking for what the exporter actually produces is simpler
# than reading that warning on every run.
OPSET = 18


def export(
    checkpoint: str,
    out_dir: Path,
    max_length: int = 128,
    opset: int = OPSET,
) -> Path:
    predictor = Predictor(checkpoint, device="cpu", max_length=max_length)
    wrapper = ExportWrapper(predictor.model).eval()
    out_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = out_dir / "model.onnx"

    # Two examples of different lengths in the sample batch, so the tracer cannot bake a
    # sequence length into the graph by seeing only one.
    sample = predictor.tokenizer(
        ["im home alone at 24 oak st until 9", "hey"],
        padding=True,
        truncation=True,
        max_length=max_length,
        return_tensors="pt",
    )

    # `dynamic_shapes`, not `dynamic_axes`. The dynamo exporter warns that `dynamic_axes`
    # is not recommended, and it is not merely a style preference: the graph it produced
    # carried a value_info claiming the context output's dimension 0 was the hidden size
    # rather than the label count, which passed inference and runtime fine but made
    # `onnx.shape_inference` — and therefore `quantize_dynamic` — fail outright.
    # `Dim.AUTO` rather than named `Dim`s. A named `Dim("batch")` makes the exporter
    # generate a `batch != 1` guard and then reject its own constraint — and batch 1 is
    # the only batch size the phone ever uses, so a graph that excludes it is useless.
    auto = torch.export.Dim.AUTO

    torch.onnx.export(
        wrapper,
        (sample["input_ids"], sample["attention_mask"]),
        str(onnx_path),
        input_names=["input_ids", "attention_mask"],
        output_names=["token_probs", "context_probs"],
        dynamic_shapes={
            "input_ids": {0: auto, 1: auto},
            "attention_mask": {0: auto, 1: auto},
        },
        opset_version=opset,
        do_constant_folding=True,
        # Single file, always. Left to itself the exporter writes initialisers to a
        # sibling `model.onnx.data`, which is a deployment trap: the graph loads fine
        # from a 24 KB file, the Android asset pipeline ships one file, and the model
        # fails at runtime on a device rather than in CI. It also made this script's own
        # size report read 0.02 MB for a 17.5 MB model.
        external_data=False,
    )

    stray = onnx_path.with_suffix(onnx_path.suffix + ".data")
    if stray.exists():
        raise RuntimeError(
            f"exporter still wrote external data to {stray}. The Android build expects "
            f"a single self-contained {onnx_path.name}."
        )
    return onnx_path


def write_sidecars(predictor: Predictor, out_dir: Path) -> dict[str, str]:
    written: dict[str, str] = {}

    saved = predictor.tokenizer.save_pretrained(out_dir / "_tok")
    for name in ("vocab.txt", "tokenizer_config.json", "special_tokens_map.json", "tokenizer.json"):
        source = out_dir / "_tok" / name
        if source.exists():
            shutil.copy(source, out_dir / name)
            written[name] = str(out_dir / name)
    shutil.rmtree(out_dir / "_tok", ignore_errors=True)

    written["label_contract.json"] = str(export_contract(out_dir / "label_contract.json"))
    written["risk_rules.json"] = str(RiskEngine().export(out_dir / "risk_rules.json"))

    thresholds_path = out_dir / "thresholds.json"
    thresholds_path.write_text(
        json.dumps(
            {
                "thresholds": predictor.thresholds,
                "note": (
                    "Per-label operating points chosen on the validation split. The "
                    "temperature is already folded into the ONNX graph; these are not."
                ),
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    written["thresholds.json"] = str(thresholds_path)
    return written


def tokenizer_fixtures(predictor: Predictor, out_dir: Path, texts: list[str]) -> Path:
    """(text, input_ids) pairs for the Kotlin WordPiece implementation to assert against."""
    fixtures = []
    for text in texts:
        encoded = predictor.tokenizer(
            text, truncation=True, max_length=predictor.max_length
        )
        fixtures.append(
            {
                "text": text,
                "input_ids": encoded["input_ids"],
                "tokens": predictor.tokenizer.convert_ids_to_tokens(encoded["input_ids"]),
            }
        )
    path = out_dir / "tokenizer_fixtures.json"
    path.write_text(json.dumps(fixtures, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def parity_check(
    predictor: Predictor,
    onnx_path: Path,
    texts: list[str],
    tolerance: float = 1e-4,
    batch_size: int = 32,
) -> dict:
    """Compare PyTorch and ONNX Runtime over `texts`. Returns the worst deviations."""
    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    wrapper = ExportWrapper(predictor.model).eval()

    worst_context = 0.0
    worst_token = 0.0
    worst_example = ""
    label_flips = 0
    compared = 0

    for start in range(0, len(texts), batch_size):
        chunk = texts[start : start + batch_size]
        encoded = predictor.tokenizer(
            chunk,
            padding=True,
            truncation=True,
            max_length=predictor.max_length,
            return_tensors="pt",
        )
        with torch.no_grad():
            torch_token, torch_context = wrapper(
                encoded["input_ids"], encoded["attention_mask"]
            )
        onnx_token, onnx_context = session.run(
            None,
            {
                "input_ids": encoded["input_ids"].numpy().astype(np.int64),
                "attention_mask": encoded["attention_mask"].numpy().astype(np.int64),
            },
        )

        context_delta = np.abs(torch_context.numpy() - onnx_context)
        token_delta = np.abs(torch_token.numpy() - onnx_token)
        if context_delta.max() > worst_context:
            worst_context = float(context_delta.max())
            worst_example = chunk[int(context_delta.max(axis=1).argmax())]
        worst_token = max(worst_token, float(token_delta.max()))

        # A numerical difference only matters if it changes a decision, so count the
        # threshold crossings too — that is the number a reviewer actually cares about.
        torch_decisions = torch_context.numpy() >= 0.5
        onnx_decisions = onnx_context >= 0.5
        label_flips += int((torch_decisions != onnx_decisions).sum())
        compared += int(torch_decisions.size)

    return {
        "n_texts": len(texts),
        "max_abs_diff_context": worst_context,
        "max_abs_diff_token": worst_token,
        "tolerance": tolerance,
        "within_tolerance": worst_context <= tolerance and worst_token <= tolerance,
        "label_decision_flips": label_flips,
        "label_decisions_compared": compared,
        "worst_example": worst_example,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--out", type=Path, default=Path("models/export"))
    parser.add_argument("--max-length", type=int, default=128)
    parser.add_argument("--opset", type=int, default=OPSET)
    parser.add_argument("--parity-dataset", default="datasets/child_safety/test.jsonl")
    parser.add_argument("--parity-n", type=int, default=500)
    parser.add_argument("--tolerance", type=float, default=1e-4)
    parser.add_argument("--skip-parity", action="store_true")
    args = parser.parse_args()

    predictor = Predictor(args.checkpoint, device="cpu", max_length=args.max_length)
    onnx_path = export(args.checkpoint, args.out, args.max_length, args.opset)
    size_mb = onnx_path.stat().st_size / 1e6
    print(f"exported {onnx_path} ({size_mb:.2f} MB)")

    sidecars = write_sidecars(predictor, args.out)
    for name in sidecars:
        print(f"  sidecar {name}")

    parity = None
    if not args.skip_parity:
        examples = read_jsonl(args.parity_dataset)[: args.parity_n]
        texts = [e.text for e in examples]
        fixture_path = tokenizer_fixtures(predictor, args.out, texts[:50])
        print(f"  sidecar {fixture_path.name}")

        parity = parity_check(predictor, onnx_path, texts, args.tolerance)
        print(
            f"\nparity over {parity['n_texts']} messages: "
            f"context max|Δ|={parity['max_abs_diff_context']:.3e} "
            f"token max|Δ|={parity['max_abs_diff_token']:.3e} "
            f"(tolerance {args.tolerance:.0e})"
        )
        print(
            f"  decision flips: {parity['label_decision_flips']} of "
            f"{parity['label_decisions_compared']}"
        )

    manifest = {
        "checkpoint": args.checkpoint,
        "onnx": str(onnx_path),
        "onnx_size_bytes": onnx_path.stat().st_size,
        "opset": args.opset,
        "max_length": args.max_length,
        "encoder": predictor.config.encoder_name,
        "parameters": predictor.model.parameter_breakdown(),
        "thresholds": predictor.thresholds,
        "sidecars": sidecars,
        "parity": parity,
        "attribution": (
            "Training data incorporates OpenPII (pii-masking-openpii-1.5m) by "
            "Ai4Privacy / Ai Suisse SA, licensed under CC-BY-4.0."
        ),
    }
    (args.out / "export_manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    print(f"manifest -> {args.out / 'export_manifest.json'}")

    if parity and not parity["within_tolerance"]:
        raise SystemExit(
            f"PARITY FAILED: max|Δ| exceeded {args.tolerance}. Do not ship this file — "
            f"the evaluated model and the exported model are not the same function."
        )


if __name__ == "__main__":
    main()
