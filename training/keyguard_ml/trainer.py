"""The training engine behind all three phases of §16.

Phases A, B and C are the same loop with different data mixtures and loss weights, so
they share this module and differ only in a YAML file. Writing three loops would mean
three places for the seeding, the checkpoint format, or the metric definition to drift,
and §28's experiment record is worthless if two experiments recorded the same field from
different code.

Mixing (phase C). The doc says "mixed batches". This builds them by concatenating the two
datasets and letting the sampler interleave, with `pii_ratio` controlling the proportion
by *upsampling or truncating the OpenPII slice* rather than by alternating batches. The
difference matters: alternating pure-PII and pure-context batches makes every context
batch's gradient fight the previous PII batch's, and with a 4-layer encoder that shows up
as visible oscillation in both losses. A mixed batch averages the two objectives before
the optimiser sees them.
"""

from __future__ import annotations

import json
import math
import random
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader

from keyguard_ml.data.encoding import (
    Collator,
    MultiTaskDataset,
    RawExample,
    read_jsonl,
)
from keyguard_ml.labels import CONTEXT_LABELS, IGNORE_INDEX, PII_ID_TO_TAG
from keyguard_ml.losses import MultiTaskLoss, pos_weight_from_jsonl
from keyguard_ml.metrics import context_report, entity_report, select_thresholds
from keyguard_ml.model import KeyguardModel, ModelConfig


@dataclass
class TrainConfig:
    name: str = "unnamed"
    phase: str = "c"

    # data
    pii_train: str | None = None
    pii_eval: str | None = None
    context_train: str | None = None
    context_eval: str | None = None
    pii_ratio: float = 0.5
    """Fraction of the training mixture that should be OpenPII rows. Phase A is 1.0,
    phase B is 0.0, phase C is somewhere between."""
    max_length: int = 128

    # model
    encoder_name: str = "google/bert_uncased_L-4_H-512_A-8"
    dropout: float = 0.1
    context_hidden: int = 0
    pooling: str = "mean"
    freeze_layers: int = 0
    init_from: str | None = None
    """Checkpoint to warm-start from — how phase B inherits phase A's encoder."""

    # optimisation
    epochs: int = 3
    batch_size: int = 64
    eval_batch_size: int = 128
    learning_rate: float = 5e-5
    head_learning_rate: float | None = None
    weight_decay: float = 0.01
    warmup_ratio: float = 0.1
    max_grad_norm: float = 1.0
    alpha: float = 0.4
    beta: float = 0.6
    pos_weight_cap: float = 8.0
    focal_gamma: float = 0.0
    label_smoothing: float = 0.0

    # runtime
    seed: int = 20260909
    device: str = "auto"
    amp: bool = True
    num_workers: int = 0
    output_dir: str = "models/run"
    select_metric: str = "context_macro_f1"
    log_every: int = 50

    @staticmethod
    def from_yaml(path: str | Path) -> "TrainConfig":
        import yaml

        data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
        known = {f for f in TrainConfig.__dataclass_fields__}
        unknown = set(data) - known
        if unknown:
            raise ValueError(f"unknown config keys in {path}: {sorted(unknown)}")
        return TrainConfig(**data)

    def model_config(self) -> ModelConfig:
        return ModelConfig(
            encoder_name=self.encoder_name,
            dropout=self.dropout,
            context_hidden=self.context_hidden,
            pooling=self.pooling,
            freeze_layers=self.freeze_layers,
        )


def resolve_device(preference: str) -> torch.device:
    if preference != "auto":
        return torch.device(preference)
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def build_mixture(
    pii: list[RawExample],
    context: list[RawExample],
    pii_ratio: float,
    rng: random.Random,
) -> list[RawExample]:
    """Resample the PII slice so it forms `pii_ratio` of the mixture.

    Upsamples with replacement when the target exceeds what is available, which is
    intentional: repeating an OpenPII row inside an epoch is a much smaller problem than
    letting the ratio silently be whatever the corpus sizes happened to give.
    """
    if pii_ratio <= 0 or not pii:
        return list(context)
    if pii_ratio >= 1 or not context:
        return list(pii)

    n_context = len(context)
    n_pii = int(round(n_context * pii_ratio / (1 - pii_ratio)))
    if n_pii <= len(pii):
        sampled = rng.sample(pii, n_pii)
    else:
        sampled = list(pii) + [rng.choice(pii) for _ in range(n_pii - len(pii))]
    mixture = [*context, *sampled]
    rng.shuffle(mixture)
    return mixture


@dataclass
class EvalResult:
    context: dict | None = None
    entities: dict | None = None
    losses: dict[str, float] = field(default_factory=dict)

    def primary(self, metric: str) -> float:
        if metric == "context_macro_f1":
            return (self.context or {}).get("macro_f1", 0.0)
        if metric == "context_macro_pr_auc":
            return (self.context or {}).get("macro_pr_auc", 0.0)
        if metric == "entity_micro_f1":
            return (self.entities or {}).get("micro_f1", 0.0)
        if metric == "neg_loss":
            return -self.losses.get("loss", math.inf)
        raise ValueError(f"unknown select_metric {metric!r}")


class Trainer:
    def __init__(self, config: TrainConfig, tokenizer=None):
        from transformers import AutoTokenizer

        self.config = config
        self.device = resolve_device(config.device)
        set_seed(config.seed)

        self.tokenizer = tokenizer or AutoTokenizer.from_pretrained(config.encoder_name)
        self.model = KeyguardModel(config.model_config())
        if config.init_from:
            self.load_weights(config.init_from)
        self.model.to(self.device)

        pos_weight = (
            pos_weight_from_jsonl(config.context_train, config.pos_weight_cap)
            if config.context_train
            else None
        )
        self.criterion = MultiTaskLoss(
            alpha=config.alpha,
            beta=config.beta,
            pos_weight=pos_weight,
            focal_gamma=config.focal_gamma,
        ).to(self.device)

        self.collator = Collator(self.tokenizer.pad_token_id)
        self.output_dir = Path(config.output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.history: list[dict] = []

    # -- checkpointing ---------------------------------------------------------------
    def load_weights(self, path: str | Path) -> None:
        checkpoint = torch.load(Path(path), map_location="cpu", weights_only=False)
        state = checkpoint.get("model_state", checkpoint)
        missing, unexpected = self.model.load_state_dict(state, strict=False)
        if missing:
            print(f"  init_from: {len(missing)} params left at initialisation")
        if unexpected:
            print(f"  init_from: {len(unexpected)} unexpected params ignored")

    def save(self, path: Path, extra: dict | None = None) -> None:
        torch.save(
            {
                "model_state": self.model.state_dict(),
                "model_config": self.model.config.as_dict(),
                "train_config": asdict(self.config),
                "context_labels": list(CONTEXT_LABELS),
                **(extra or {}),
            },
            path,
        )

    # -- data ------------------------------------------------------------------------
    def _loader(self, examples: list[RawExample], batch_size: int, shuffle: bool) -> DataLoader:
        return DataLoader(
            MultiTaskDataset(examples, self.tokenizer, self.config.max_length),
            batch_size=batch_size,
            shuffle=shuffle,
            collate_fn=self.collator,
            num_workers=self.config.num_workers,
            drop_last=False,
        )

    def training_examples(self) -> list[RawExample]:
        rng = random.Random(self.config.seed)
        pii = read_jsonl(self.config.pii_train) if self.config.pii_train else []
        context = read_jsonl(self.config.context_train) if self.config.context_train else []
        return build_mixture(pii, context, self.config.pii_ratio, rng)

    # -- loop ------------------------------------------------------------------------
    def train(self) -> dict:
        train_examples = self.training_examples()
        loader = self._loader(train_examples, self.config.batch_size, shuffle=True)

        decay, no_decay, heads = [], [], []
        head_names = ("token_head", "context_head")
        for name, param in self.model.named_parameters():
            if not param.requires_grad:
                continue
            if name.startswith(head_names):
                heads.append(param)
            elif param.ndim == 1 or name.endswith(".bias"):
                no_decay.append(param)
            else:
                decay.append(param)

        head_lr = self.config.head_learning_rate or self.config.learning_rate
        optimizer = torch.optim.AdamW(
            [
                {"params": decay, "weight_decay": self.config.weight_decay},
                {"params": no_decay, "weight_decay": 0.0},
                {"params": heads, "weight_decay": 0.0, "lr": head_lr},
            ],
            lr=self.config.learning_rate,
        )

        total_steps = max(1, len(loader) * self.config.epochs)
        warmup = int(total_steps * self.config.warmup_ratio)

        def lr_lambda(step: int) -> float:
            if step < warmup:
                return step / max(1, warmup)
            progress = (step - warmup) / max(1, total_steps - warmup)
            return max(0.0, 1.0 - progress)

        scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, lr_lambda)
        use_amp = self.config.amp and self.device.type == "cuda"
        scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

        print(
            f"[{self.config.name}] phase {self.config.phase} | "
            f"{len(train_examples):,} examples | {total_steps:,} steps | {self.device}"
        )
        print(f"  params: {self.model.parameter_breakdown()}")

        best_score = -math.inf
        best_path = self.output_dir / "best.pt"
        started = time.time()

        for epoch in range(1, self.config.epochs + 1):
            self.model.train()
            running: dict[str, float] = {"loss": 0.0, "token_loss": 0.0, "context_loss": 0.0}
            seen = 0

            for step, batch in enumerate(loader, start=1):
                batch = {k: v.to(self.device, non_blocking=True) for k, v in batch.items()}
                optimizer.zero_grad(set_to_none=True)

                with torch.amp.autocast("cuda", enabled=use_amp):
                    out = self.model(batch["input_ids"], batch["attention_mask"])
                    losses = self.criterion(
                        out.token_logits,
                        batch["token_labels"],
                        out.context_logits,
                        batch["context_targets"],
                        batch["context_mask"],
                    )

                scaler.scale(losses["loss"]).backward()
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(self.model.parameters(), self.config.max_grad_norm)
                scaler.step(optimizer)
                scaler.update()
                scheduler.step()

                seen += 1
                for key in running:
                    running[key] += float(losses[key])
                if step % self.config.log_every == 0:
                    avg = {k: round(v / seen, 4) for k, v in running.items()}
                    print(f"  epoch {epoch} step {step}/{len(loader)} {avg} lr={scheduler.get_last_lr()[0]:.2e}")

            evaluation = self.evaluate()
            score = evaluation.primary(self.config.select_metric)
            record = {
                "epoch": epoch,
                "train_loss": {k: round(v / max(1, seen), 4) for k, v in running.items()},
                "eval": {
                    "context": evaluation.context,
                    "entities": evaluation.entities,
                    "losses": evaluation.losses,
                },
                "select_metric": self.config.select_metric,
                "score": score,
                "elapsed_s": round(time.time() - started, 1),
            }
            self.history.append(record)
            print(f"  epoch {epoch}: {self.config.select_metric}={score:.4f}")
            if evaluation.context:
                for row in evaluation.context["per_label"]:
                    print(
                        f"      {row['label']:17s} P={row['precision']:.3f} "
                        f"R={row['recall']:.3f} F1={row['f1']:.3f} AUC={row['pr_auc']:.3f}"
                    )
            if evaluation.entities:
                print(f"      entities micro-F1={evaluation.entities['micro_f1']:.4f}")

            if score > best_score:
                best_score = score
                self.save(best_path, {"epoch": epoch, "score": score})
                print(f"      new best -> {best_path}")

        self.save(self.output_dir / "last.pt", {"epoch": self.config.epochs})
        summary = self.finalize(best_path, best_score, time.time() - started)
        return summary

    # -- evaluation --------------------------------------------------------------------
    @torch.no_grad()
    def evaluate(self, thresholds: dict[str, float] | None = None) -> EvalResult:
        self.model.eval()
        result = EvalResult()

        if self.config.context_eval:
            scores, truths = self._collect_context(self.config.context_eval)
            if len(truths):
                result.context = context_report(truths, scores, thresholds)

        if self.config.pii_eval:
            gold, pred = self._collect_entities(self.config.pii_eval)
            if gold:
                result.entities = entity_report(gold, pred)

        return result

    @torch.no_grad()
    def _collect_context(self, path: str) -> tuple[np.ndarray, np.ndarray]:
        examples = [e for e in read_jsonl(path) if e.context is not None]
        if not examples:
            return np.zeros((0, len(CONTEXT_LABELS))), np.zeros((0, len(CONTEXT_LABELS)))
        loader = self._loader(examples, self.config.eval_batch_size, shuffle=False)
        scores, truths = [], []
        for batch in loader:
            ids = batch["input_ids"].to(self.device)
            mask = batch["attention_mask"].to(self.device)
            logits = self.model(ids, mask).context_logits
            scores.append(torch.sigmoid(logits).float().cpu().numpy())
            truths.append(batch["context_targets"].numpy())
        return np.concatenate(scores), np.concatenate(truths)

    @torch.no_grad()
    def _collect_entities(self, path: str) -> tuple[list[list[str]], list[list[str]]]:
        examples = read_jsonl(path)
        loader = self._loader(examples, self.config.eval_batch_size, shuffle=False)
        gold_sequences, pred_sequences = [], []
        for batch in loader:
            ids = batch["input_ids"].to(self.device)
            mask = batch["attention_mask"].to(self.device)
            predictions = self.model(ids, mask).token_logits.argmax(-1).cpu()
            labels = batch["token_labels"]
            for row_pred, row_gold in zip(predictions, labels):
                keep = row_gold != IGNORE_INDEX
                if not keep.any():
                    continue
                gold_sequences.append([PII_ID_TO_TAG[int(t)] for t in row_gold[keep]])
                pred_sequences.append([PII_ID_TO_TAG[int(t)] for t in row_pred[keep]])
        return gold_sequences, pred_sequences

    # -- calibration + record ----------------------------------------------------------
    @torch.no_grad()
    def fit_temperature(self, path: str, max_iter: int = 200) -> torch.Tensor:
        """§22 — one temperature per label, fitted on validation by NLL.

        Fitted on the *validation* split, never on training data: temperature scaling is
        supposed to correct the overconfidence that fitting produced, and refitting it on
        the same rows would reproduce the overconfidence rather than measure it.
        """
        examples = [e for e in read_jsonl(path) if e.context is not None]
        if not examples:
            return self.model.context_temperature

        self.model.eval()
        loader = self._loader(examples, self.config.eval_batch_size, shuffle=False)
        logits_all, targets_all = [], []
        for batch in loader:
            ids = batch["input_ids"].to(self.device)
            mask = batch["attention_mask"].to(self.device)
            logits_all.append(self.model(ids, mask).context_logits.float().cpu())
            targets_all.append(batch["context_targets"])
        logits = torch.cat(logits_all)
        targets = torch.cat(targets_all)

        log_temperature = torch.zeros(logits.shape[1], requires_grad=True)
        optimizer = torch.optim.LBFGS([log_temperature], lr=0.1, max_iter=max_iter)

        def closure():
            optimizer.zero_grad()
            scaled = logits / log_temperature.exp()
            loss = torch.nn.functional.binary_cross_entropy_with_logits(scaled, targets)
            loss.backward()
            return loss

        with torch.enable_grad():
            optimizer.step(closure)

        temperature = log_temperature.detach().exp().clamp(0.05, 20.0)
        self.model.context_temperature.copy_(temperature.to(self.model.context_temperature.device))
        return temperature

    def finalize(self, best_path: Path, best_score: float, elapsed: float) -> dict:
        """Reload the best checkpoint, calibrate, pick thresholds, write §28's record."""
        if best_path.exists():
            self.load_weights(best_path)
            self.model.to(self.device)

        temperature = None
        thresholds = None
        if self.config.context_eval:
            temperature = self.fit_temperature(self.config.context_eval).tolist()
            scores, truths = self._collect_context(self.config.context_eval)
            if len(truths):
                thresholds = select_thresholds(truths, scores, objective="f1")

        final = self.evaluate(thresholds)
        self.save(
            best_path,
            {
                "score": best_score,
                "thresholds": thresholds,
                "context_temperature": temperature,
            },
        )

        record = {
            "name": self.config.name,
            "phase": self.config.phase,
            "config": asdict(self.config),
            "model": {
                "config": self.model.config.as_dict(),
                "parameters": self.model.parameter_breakdown(),
            },
            "device": str(self.device),
            "elapsed_s": round(elapsed, 1),
            "best_score": best_score,
            "select_metric": self.config.select_metric,
            "thresholds": thresholds,
            "context_temperature": temperature,
            "history": self.history,
            "final_eval": {
                "context": final.context,
                "entities": final.entities,
            },
        }
        (self.output_dir / "experiment.json").write_text(
            json.dumps(record, indent=2) + "\n", encoding="utf-8"
        )
        print(f"  experiment record -> {self.output_dir / 'experiment.json'}")
        return record
