"""Shared entry-point plumbing for the three phase scripts.

The three scripts in this directory exist because §28 names them and because each phase
has a genuinely different default config. They share every line of actual logic, which
lives in `keyguard_ml.trainer`; what they do not share is the ability to be run with the
wrong defaults by accident.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from keyguard_ml.trainer import TrainConfig, Trainer


def run(default_config: str, description: str) -> dict:
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--config", default=default_config, help="YAML config path")
    parser.add_argument("--set", nargs="*", default=[], metavar="KEY=VALUE",
                        help="override any config field, e.g. --set epochs=1 batch_size=16")
    args = parser.parse_args()

    config = TrainConfig.from_yaml(args.config)
    for override in args.set:
        key, _, raw = override.partition("=")
        if key not in TrainConfig.__dataclass_fields__:
            raise SystemExit(f"unknown config field: {key}")
        current = getattr(config, key)
        if isinstance(current, bool):
            value = raw.lower() in ("1", "true", "yes")
        elif isinstance(current, int) and not isinstance(current, bool):
            value = int(raw)
        elif isinstance(current, float):
            value = float(raw)
        else:
            value = None if raw in ("", "none", "null") else raw
        setattr(config, key, value)

    print(json.dumps({k: v for k, v in vars(config).items()}, indent=2, default=str))
    summary = Trainer(config).train()
    print(f"\nbest {config.select_metric} = {summary['best_score']:.4f}")
    return summary
