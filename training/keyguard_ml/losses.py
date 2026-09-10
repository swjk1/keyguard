"""§17 — the two losses and how they are combined.

The masking is the part that is easy to get subtly wrong. Phase C mixes OpenPII rows
(token supervision, no context labels) with safety rows (both) in one batch. If the
context loss averaged over the whole batch, every OpenPII row would contribute a target
of all-zeros — teaching the model that formal prose containing an address is *not* a
child disclosure, which happens to be true, but the model would also learn it from rows
whose true labels were simply never annotated. So the context loss is averaged over the
supervised rows only, and a batch with none contributes zero rather than NaN.
"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F

from keyguard_ml.labels import CONTEXT_LABELS, IGNORE_INDEX, NUM_CONTEXT_LABELS


def compute_pos_weight(
    label_counts: dict[str, int], total: int, cap: float = 8.0
) -> torch.Tensor:
    """BCE `pos_weight` from observed positive rates, capped.

    `routine` sits around 6% of the corpus and `child_ownership` above 50%. Left alone,
    the rare signals get a gradient the common ones drown out. Uncapped inverse
    frequency is the other failure: it makes the rarest label so expensive that the model
    buys recall on it with false positives everywhere else, and §21's target is a
    false-positive rate under 2%.
    """
    weights = []
    for label in CONTEXT_LABELS:
        positives = max(1, label_counts.get(label, 0))
        negatives = max(1, total - positives)
        weights.append(min(cap, negatives / positives))
    return torch.tensor(weights, dtype=torch.float)


def pos_weight_from_jsonl(path: str | Path, cap: float = 8.0) -> torch.Tensor:
    counts: Counter = Counter()
    total = 0
    with Path(path).open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            labels = row.get("labels") or row.get("context")
            if not labels:
                continue
            total += 1
            for key, value in labels.items():
                if value:
                    counts[key] += 1
    return compute_pos_weight(dict(counts), max(total, 1), cap)


class MultiTaskLoss(nn.Module):
    def __init__(
        self,
        alpha: float = 0.4,
        beta: float = 0.6,
        pos_weight: torch.Tensor | None = None,
        focal_gamma: float = 0.0,
    ):
        super().__init__()
        self.alpha = alpha
        self.beta = beta
        self.focal_gamma = focal_gamma
        if pos_weight is None:
            pos_weight = torch.ones(NUM_CONTEXT_LABELS)
        self.register_buffer("pos_weight", pos_weight)

    def token_loss(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        return F.cross_entropy(
            logits.reshape(-1, logits.size(-1)),
            targets.reshape(-1),
            ignore_index=IGNORE_INDEX,
        )

    def context_loss(
        self,
        logits: torch.Tensor,
        targets: torch.Tensor,
        mask: torch.Tensor,
    ) -> torch.Tensor:
        supervised = mask > 0.5
        if not supervised.any():
            return logits.sum() * 0.0  # keeps the graph connected; contributes nothing

        logits = logits[supervised]
        targets = targets[supervised]

        per_element = F.binary_cross_entropy_with_logits(
            logits, targets, pos_weight=self.pos_weight.to(logits.dtype), reduction="none"
        )
        if self.focal_gamma > 0:
            # §17 leaves focal loss as a later option. Implemented but off by default:
            # it interacts with pos_weight, and turning both on at once makes an ablation
            # uninterpretable.
            probabilities = torch.sigmoid(logits)
            p_t = targets * probabilities + (1 - targets) * (1 - probabilities)
            per_element = per_element * (1 - p_t).pow(self.focal_gamma)
        return per_element.mean()

    def forward(
        self,
        token_logits: torch.Tensor,
        token_targets: torch.Tensor,
        context_logits: torch.Tensor,
        context_targets: torch.Tensor,
        context_mask: torch.Tensor,
    ) -> dict[str, torch.Tensor]:
        has_token_supervision = (token_targets != IGNORE_INDEX).any()
        token = (
            self.token_loss(token_logits, token_targets)
            if has_token_supervision
            else token_logits.sum() * 0.0
        )
        context = self.context_loss(context_logits, context_targets, context_mask)
        return {
            "loss": self.alpha * token + self.beta * context,
            "token_loss": token.detach(),
            "context_loss": context.detach(),
        }
