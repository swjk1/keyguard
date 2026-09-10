"""Phase B (§16) — add the context head, train on the child-safety corpus.

Warm-starts from phase A's checkpoint. The synthetic corpus carries token spans too, so
the token head is not abandoned here — it keeps a small weight (`alpha`) to stop phase
A's entity recognition decaying while the encoder shifts towards chat register.
"""

from train._common import run

if __name__ == "__main__":
    run("configs/phase_b_context.yaml", __doc__)
