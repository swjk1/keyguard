#!/usr/bin/env bash
# Runs ON the pod, shipped there by scripts/remote_run.sh. Extracts source, builds the
# venv, verifies CUDA, then launches the FULL pipeline detached so it survives the SSH
# session closing.
set -euo pipefail

echo "=== extracting source ==="
mkdir -p /workspace/training
tar --no-same-owner -xzf /workspace/keyguard-training-src.tgz -C /workspace/training
cd /workspace/training

# Clear the completion marker and any archive from a previous run on this volume.
# Without this, a restarted pod makes remote_run.sh's poll succeed instantly and fetch
# the PREVIOUS run's artifacts, which looks exactly like success.
echo "=== clearing stale run markers ==="
rm -f /workspace/PIPELINE_DONE /workspace/run_artifacts.tgz
ls -la /workspace/PIPELINE_DONE /workspace/run_artifacts.tgz 2>/dev/null || echo "  none present (good)"

echo "=== confirming the pipeline fixes survived transfer ==="
grep -n 'TORCH_VERSION:-' scripts/setup_cloud.sh
grep -n 'sed -i' scripts/setup_cloud.sh
grep -n 'venv/bin/python' scripts/run_pipeline.sh
grep -n 'dynamo=True' export/export_onnx.py
grep -n 'trap package_artifacts EXIT' scripts/run_pipeline.sh

echo "=== nvidia-smi ==="
nvidia-smi

echo "=== setup_cloud.sh (installs torch 2.6.0 cu124) ==="
export HF_HOME=/workspace/hf-cache
export PIP_CACHE_DIR=/workspace/pip-cache
bash scripts/setup_cloud.sh

echo "=== torch / cuda verification ==="
.venv/bin/python -c "import torch; print('torch', torch.__version__, '| cuda', torch.cuda.is_available(), '|', torch.cuda.get_device_name(0))"

echo "=== launching FULL pipeline (SMOKE unset) ==="
mkdir -p logs
nohup env HF_HOME=/workspace/hf-cache PIP_CACHE_DIR=/workspace/pip-cache \
    bash scripts/run_pipeline.sh > logs/full.log 2>&1 < /dev/null &
echo "PIPELINE_PID=$!"
sleep 5
echo "=== first lines of logs/full.log ==="
tail -n 20 logs/full.log || true
