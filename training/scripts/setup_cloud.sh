#!/usr/bin/env bash
# Provision a fresh Linux GPU box (RunPod, Lambda, Vast, a university node) for training.
#
#   bash scripts/setup_cloud.sh            # CUDA 12.4 wheels
#   TORCH_INDEX=cpu bash scripts/setup_cloud.sh
#
# Run it from the `training/` directory. Safe to re-run.
#
# The install order is not cosmetic. torch has to come from the PyTorch index before
# anything else pulls a generic wheel from PyPI, and torchvision must never be installed
# at all: a torchvision whose build does not match the torch build makes `import
# transformers` raise `RuntimeError: operator torchvision::nms does not exist`, which
# reads like a transformers bug and is not one. That failure cost real time on the
# development machine; the explicit uninstall below is why it will not cost any here.

set -euo pipefail

TORCH_VERSION="${TORCH_VERSION:-2.9.1}"
TORCH_INDEX="${TORCH_INDEX:-cu124}"
PYTHON="${PYTHON:-python3}"
VENV="${VENV:-.venv}"

echo "==> python: $($PYTHON --version)"
if command -v nvidia-smi >/dev/null 2>&1; then
    nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
else
    echo "    no nvidia-smi — this box has no GPU, training will be very slow"
fi

if [ ! -d "$VENV" ]; then
    echo "==> creating venv at $VENV"
    "$PYTHON" -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"

python -m pip install --upgrade pip wheel setuptools

echo "==> installing torch $TORCH_VERSION ($TORCH_INDEX)"
pip install "torch==${TORCH_VERSION}" --index-url "https://download.pytorch.org/whl/${TORCH_INDEX}"

echo "==> removing torchvision/torchaudio if present (see header)"
pip uninstall -y torchvision torchaudio 2>/dev/null || true

echo "==> installing the rest"
grep -v '^torch==' requirements.txt > /tmp/kg-reqs.txt
pip install -r /tmp/kg-reqs.txt

# hf_xet makes Hub downloads several times faster, which matters when the OpenPII train
# split is a multi-gigabyte JSONL and the box is billed by the minute.
pip install -q "huggingface_hub[hf_xet]" || true

echo
echo "==> verifying"
python - <<'PY'
import torch, transformers, onnxruntime
print(f"  torch          {torch.__version__}")
print(f"  cuda available {torch.cuda.is_available()}")
if torch.cuda.is_available():
    print(f"  device         {torch.cuda.get_device_name(0)}")
    print(f"  capability     {torch.cuda.get_device_capability(0)}")
print(f"  transformers   {transformers.__version__}")
print(f"  onnxruntime    {onnxruntime.__version__}")

from transformers import AutoModel
m = AutoModel.from_pretrained("google/bert_uncased_L-4_H-512_A-8")
total = sum(p.numel() for p in m.parameters())
print(f"  encoder        {total/1e6:.1f}M parameters (target 10-30M)")
PY

echo
echo "ready. next:  bash scripts/run_pipeline.sh"
