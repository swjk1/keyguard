"""Phase C (§16) — joint fine-tuning on mixed batches.

Both objectives, one optimiser, `alpha`/`beta` from the config. This is the checkpoint
that gets exported.
"""

from train._common import run

if __name__ == "__main__":
    run("configs/phase_c_joint.yaml", __doc__)
