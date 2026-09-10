"""Loading a checkpoint and running it — shared by evaluation, export and parity checks.

Everything that consumes a trained model goes through `Predictor`, including the ONNX
parity check in §29. That is deliberate: if the parity check built its own PyTorch input
pipeline, it would be comparing ONNX against a second implementation rather than against
the thing that produced the metrics, and a tokenisation difference would show up as a
numerical difference with no obvious cause.

A checkpoint carries its own thresholds and temperature (written by `Trainer.finalize`),
so a caller never has to remember which operating point a model was evaluated at.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch

from keyguard_ml.data.encoding import Collator, MultiTaskDataset, RawExample
from keyguard_ml.labels import CONTEXT_LABELS, PII_ID_TO_TAG
from keyguard_ml.model import KeyguardModel, ModelConfig


@dataclass
class Prediction:
    text: str
    context: dict[str, float]
    """Calibrated probabilities, in CONTEXT_LABELS order."""
    tags: list[str]
    """One BIO tag per non-special token."""
    offsets: list[tuple[int, int]]
    """Character offsets for those tokens, so a span can be recovered from the text."""

    def entities(self) -> list[tuple[int, int, str]]:
        """(char_start, char_end, entity), merging each `B-X I-X ...` run into one span.

        The merge is what makes the span usable by the UI: an address is `B-STREET`
        followed by `I-STREET`, and emitting those separately would underline "oak" and
        "street" as two findings. A stray `I-X` with no `B-X` before it is treated as
        opening a span rather than dropped — the model can produce one, and half an
        address underlined is better than none.
        """
        out: list[tuple[int, int, str]] = []
        current: list[int | str] | None = None

        for tag, (start, end) in zip(self.tags, self.offsets):
            if end <= start:
                continue
            if tag == "O":
                if current:
                    out.append((current[0], current[1], current[2]))
                    current = None
                continue

            prefix, entity = tag[:1], tag[2:]
            if prefix == "I" and current and current[2] == entity:
                current[1] = end
                continue
            if current:
                out.append((current[0], current[1], current[2]))
            current = [start, end, entity]

        if current:
            out.append((current[0], current[1], current[2]))
        return out


class Predictor:
    def __init__(
        self,
        checkpoint: str | Path,
        device: str = "auto",
        max_length: int = 128,
    ):
        blob = torch.load(Path(checkpoint), map_location="cpu", weights_only=False)
        model_config = ModelConfig(**blob["model_config"])
        self.model = KeyguardModel(model_config)
        self.model.load_state_dict(blob["model_state"], strict=True)
        self.model.eval()

        self.device = (
            torch.device("cuda" if torch.cuda.is_available() else "cpu")
            if device == "auto"
            else torch.device(device)
        )
        self.model.to(self.device)

        from transformers import AutoTokenizer

        self.tokenizer = AutoTokenizer.from_pretrained(model_config.encoder_name)
        self.max_length = max_length
        self.thresholds: dict[str, float] = blob.get("thresholds") or {
            label: 0.5 for label in CONTEXT_LABELS
        }
        self.config = model_config
        self.checkpoint_meta = {
            k: v for k, v in blob.items() if k not in ("model_state",)
        }

    @torch.no_grad()
    def predict_texts(self, texts: list[str], batch_size: int = 64) -> list[Prediction]:
        out: list[Prediction] = []
        for start in range(0, len(texts), batch_size):
            chunk = texts[start : start + batch_size]
            encodings = [
                self.tokenizer(
                    text,
                    truncation=True,
                    max_length=self.max_length,
                    return_offsets_mapping=True,
                    return_special_tokens_mask=True,
                )
                for text in chunk
            ]
            width = max(len(e["input_ids"]) for e in encodings)
            input_ids, attention = [], []
            for enc in encodings:
                pad = width - len(enc["input_ids"])
                input_ids.append(enc["input_ids"] + [self.tokenizer.pad_token_id] * pad)
                attention.append(enc["attention_mask"] + [0] * pad)

            ids = torch.tensor(input_ids, dtype=torch.long, device=self.device)
            mask = torch.tensor(attention, dtype=torch.long, device=self.device)
            token_probs, context_probs = self.model.predict(ids, mask, calibrated=True)
            tag_ids = token_probs.argmax(-1).cpu().numpy()
            context_probs = context_probs.float().cpu().numpy()

            for row, (text, enc) in enumerate(zip(chunk, encodings)):
                tags, offsets = [], []
                for position, (special, offset) in enumerate(
                    zip(enc["special_tokens_mask"], enc["offset_mapping"])
                ):
                    if special or offset[1] <= offset[0]:
                        continue
                    tags.append(PII_ID_TO_TAG[int(tag_ids[row][position])])
                    offsets.append((int(offset[0]), int(offset[1])))
                out.append(
                    Prediction(
                        text=text,
                        context=dict(zip(CONTEXT_LABELS, context_probs[row].tolist())),
                        tags=tags,
                        offsets=offsets,
                    )
                )
        return out

    @torch.no_grad()
    def score_dataset(
        self, examples: list[RawExample], batch_size: int = 128
    ) -> tuple[np.ndarray, np.ndarray, list[list[str]], list[list[str]]]:
        """(context_scores, context_truth, gold_tag_seqs, pred_tag_seqs).

        Rows with no context supervision contribute a NaN row to `context_truth`, which
        the caller filters. Returning them keeps the row order aligned with `examples`,
        which error analysis relies on to name the failing text.
        """
        from torch.utils.data import DataLoader

        from keyguard_ml.labels import IGNORE_INDEX

        loader = DataLoader(
            MultiTaskDataset(examples, self.tokenizer, self.max_length),
            batch_size=batch_size,
            shuffle=False,
            collate_fn=Collator(self.tokenizer.pad_token_id),
        )
        scores, truths, gold_sequences, pred_sequences = [], [], [], []
        for batch in loader:
            ids = batch["input_ids"].to(self.device)
            mask = batch["attention_mask"].to(self.device)
            token_probs, context_probs = self.model.predict(ids, mask, calibrated=True)
            scores.append(context_probs.float().cpu().numpy())

            truth = batch["context_targets"].numpy().copy()
            truth[batch["context_mask"].numpy() < 0.5] = np.nan
            truths.append(truth)

            predictions = token_probs.argmax(-1).cpu()
            for row_pred, row_gold in zip(predictions, batch["token_labels"]):
                keep = row_gold != IGNORE_INDEX
                if not keep.any():
                    gold_sequences.append([])
                    pred_sequences.append([])
                    continue
                gold_sequences.append([PII_ID_TO_TAG[int(t)] for t in row_gold[keep]])
                pred_sequences.append([PII_ID_TO_TAG[int(t)] for t in row_pred[keep]])

        return (
            np.concatenate(scores),
            np.concatenate(truths),
            gold_sequences,
            pred_sequences,
        )
