"""Phase A (§16) — adapt the encoder and the token head on OpenPII alone.

Goal: strong recognition of addresses, emails, phone numbers and the rest. The context
head is present but receives no supervision here, so its weights stay near
initialisation; phase B is where it starts learning.
"""

from train._common import run

if __name__ == "__main__":
    run("configs/phase_a_pii.yaml", __doc__)
