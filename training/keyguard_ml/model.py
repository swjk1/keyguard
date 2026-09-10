"""§14 — one compact encoder, two heads.

Size budget. The design doc asks for 10–30M parameters. For a WordPiece BERT most of that
is the embedding table, not the transformer:

    google/bert_uncased_L-4_H-512_A-8   ~28.8M total,  15.6M of it word embeddings
    google/bert_uncased_L-4_H-256_A-4   ~11.2M total,   7.8M of it word embeddings

So "make the model smaller" mostly means "make the vocabulary smaller", and the encoder
depth is cheap by comparison. That matters for the Android benchmark in §31: dropping from
L-4 to L-2 buys latency, dropping vocabulary buys file size, and they are separate knobs.
`export/prune_vocab.py` is where the second one lives.

Pooling. The context head reads a masked mean over token states rather than `[CLS]`.
These are 6–20 token messages where the signal is a couple of words — "alone", "till 9" —
and mean pooling keeps those words' contribution proportional instead of routing
everything through a single position that has to learn to attend to them. It is also one
fewer thing to get wrong at export: a mean over the attention mask is the same op in
PyTorch and ONNX, whereas `[CLS]`-plus-tanh-pooler is an extra module that BERT variants
initialise inconsistently.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import torch
import torch.nn as nn
from transformers import AutoConfig, AutoModel

from keyguard_ml.labels import NUM_CONTEXT_LABELS, NUM_PII_TAGS


@dataclass
class ModelConfig:
    encoder_name: str = "google/bert_uncased_L-4_H-512_A-8"
    dropout: float = 0.1
    context_hidden: int = 0
    """0 = linear probe straight off the pooled state. A small hidden layer sometimes
    helps the rarer signals; it is a sweep knob, not a default."""
    pooling: str = "mean"
    """'mean' or 'cls'."""
    freeze_layers: int = 0
    """Number of lowest encoder layers to freeze. §16's phase B suggests this; it is off
    by default because at 4 layers there is very little to freeze."""

    def as_dict(self) -> dict:
        return {
            "encoder_name": self.encoder_name,
            "dropout": self.dropout,
            "context_hidden": self.context_hidden,
            "pooling": self.pooling,
            "freeze_layers": self.freeze_layers,
        }


@dataclass
class MultiTaskOutput:
    token_logits: torch.Tensor
    """(batch, seq, NUM_PII_TAGS)"""
    context_logits: torch.Tensor
    """(batch, NUM_CONTEXT_LABELS)"""
    hidden: torch.Tensor | None = field(default=None, repr=False)


class KeyguardModel(nn.Module):
    def __init__(self, config: ModelConfig):
        super().__init__()
        self.config = config
        encoder_config = AutoConfig.from_pretrained(config.encoder_name)
        self.encoder = AutoModel.from_pretrained(config.encoder_name, config=encoder_config)
        hidden = encoder_config.hidden_size

        self.dropout = nn.Dropout(config.dropout)
        self.token_head = nn.Linear(hidden, NUM_PII_TAGS)

        if config.context_hidden > 0:
            self.context_head = nn.Sequential(
                nn.Linear(hidden, config.context_hidden),
                nn.GELU(),
                nn.Dropout(config.dropout),
                nn.Linear(config.context_hidden, NUM_CONTEXT_LABELS),
            )
        else:
            self.context_head = nn.Linear(hidden, NUM_CONTEXT_LABELS)

        # Temperature for §22's calibration. One scalar per context label, learned after
        # training on the validation set and frozen thereafter. Registered as a buffer so
        # it travels with the checkpoint and into ONNX without being trained by the main
        # optimiser — calibrating with the same data that fit the weights would just
        # relearn the overconfidence.
        self.register_buffer(
            "context_temperature", torch.ones(NUM_CONTEXT_LABELS), persistent=True
        )

        if config.freeze_layers > 0:
            self._freeze(config.freeze_layers)

    def _freeze(self, n: int) -> None:
        embeddings = getattr(self.encoder, "embeddings", None)
        if embeddings is not None:
            for param in embeddings.parameters():
                param.requires_grad = False
        layers = getattr(getattr(self.encoder, "encoder", None), "layer", None)
        if layers is None:
            return
        for layer in layers[:n]:
            for param in layer.parameters():
                param.requires_grad = False

    def unfreeze_all(self) -> None:
        for param in self.parameters():
            param.requires_grad = True

    def pool(self, hidden: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        if self.config.pooling == "cls":
            return hidden[:, 0]
        mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
        summed = (hidden * mask).sum(dim=1)
        # clamp, not epsilon-add: an all-zero mask is a bug upstream, and dividing by a
        # clamped 1.0 gives a zero vector rather than an inf that surfaces three layers
        # away as a NaN loss.
        counts = mask.sum(dim=1).clamp(min=1.0)
        return summed / counts

    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
    ) -> MultiTaskOutput:
        hidden = self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
        dropped = self.dropout(hidden)
        token_logits = self.token_head(dropped)
        pooled = self.dropout(self.pool(hidden, attention_mask))
        context_logits = self.context_head(pooled)
        return MultiTaskOutput(token_logits, context_logits, hidden)

    @torch.no_grad()
    def predict(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
        *,
        calibrated: bool = True,
    ) -> tuple[torch.Tensor, torch.Tensor]:
        """(token_probs, context_probs) — what the phone actually consumes."""
        out = self.forward(input_ids, attention_mask)
        logits = out.context_logits
        if calibrated:
            logits = logits / self.context_temperature.clamp(min=1e-3)
        return out.token_logits.softmax(-1), logits.sigmoid()

    def num_parameters(self, trainable_only: bool = False) -> int:
        params = self.parameters()
        if trainable_only:
            params = (p for p in params if p.requires_grad)
        return sum(p.numel() for p in params)

    def parameter_breakdown(self) -> dict[str, int]:
        embed = self.encoder.get_input_embeddings().weight.numel()
        total = self.num_parameters()
        heads = sum(p.numel() for p in self.token_head.parameters()) + sum(
            p.numel() for p in self.context_head.parameters()
        )
        return {
            "total": total,
            "word_embeddings": embed,
            "heads": heads,
            "transformer_body": total - embed - heads,
        }


class ExportWrapper(nn.Module):
    """The graph that goes to ONNX.

    Deliberately returns probabilities, not logits. The Kotlin side should not be
    reimplementing a sigmoid, a temperature division, and a softmax, because those are
    three more places for the runtime and the trainer to disagree — and §29's job is to
    prove they do not. Folding them into the graph makes the comparison meaningful.
    """

    def __init__(self, model: KeyguardModel):
        super().__init__()
        self.model = model

    def forward(
        self, input_ids: torch.Tensor, attention_mask: torch.Tensor
    ) -> tuple[torch.Tensor, torch.Tensor]:
        out = self.model(input_ids, attention_mask)
        token_probs = out.token_logits.softmax(-1)
        temperature = self.model.context_temperature.clamp(min=1e-3)
        context_probs = (out.context_logits / temperature).sigmoid()
        return token_probs, context_probs
