#!/usr/bin/env bash
# Own the entire life of a rented GPU pod in a single process: claim it, run the
# pipeline, pull the artifacts, and give it back. Run this from WSL, where runpodctl
# and the ssh key live.
#
#   bash scripts/remote_run.sh              # claim, run, fetch, remove
#   KEEP_POD=1 bash scripts/remote_run.sh   # leave the pod up (debugging; it BILLS)
#   MAX_RUN_MIN=240 bash scripts/remote_run.sh
#
# Why this script exists, and why pod lifetime is not managed by hand: on 2026-09-14 a
# pod was claimed, its pipeline finished in about an hour, attention moved to an
# unrelated bug, and it idled for 23h until the balance hit zero -- about 11 USD for
# nothing, and its output was never fetched. runpodctl 2.14.0 has NO --terminate-after
# and no --stop-after flag on pod create (checked against pod create --help), so a
# server-side deadline is not available. The next best guarantee is that the process
# which creates the pod is the only thing that can leave it running, and that it
# releases the pod from an EXIT trap on success, failure and Ctrl-C alike.
#
# Disposal policy, deliberately asymmetric:
#   artifacts fetched  -> pod remove  (ends GPU and disk billing)
#   not fetched        -> pod stop    (ends GPU billing, KEEPS the volume and data)
# Stopping rather than removing on the failure path costs about 0.03 USD/hr in disk,
# and is the difference between a recoverable run and a destroyed one.

set -uo pipefail

cd "$(dirname "$0")/.."
SRC="$PWD"

MAX_RUN_MIN="${MAX_RUN_MIN:-180}"
CLAIM_MAX_SEC="${CLAIM_MAX_SEC:-2700}"
CLAIM_INTERVAL="${CLAIM_INTERVAL:-30}"
KEEP_POD="${KEEP_POD:-0}"
KEY="${KEY:-$HOME/.runpod/ssh/runpodctl-ssh-key}"
POD_NAME="${POD_NAME:-keyguard-full}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="${DEST:-$SRC/_artifacts/$STAMP}"

POD_ID=""
FETCHED=0
RATE="unknown"

# name|price per hour, cheapest first. Blackwell parts (sm_120) are excluded on
# purpose: they need CUDA 12.8+/Torch 2.7+ and this stack is pinned to 2.6.0+cu124.
CANDIDATES=(
    "NVIDIA A40|0.49"
    "NVIDIA L4|0.49"
    "NVIDIA RTX A6000|0.53"
    "NVIDIA GeForce RTX 4090|0.74"
    "NVIDIA L40S|0.79"
    "NVIDIA RTX 6000 Ada Generation|0.84"
)

say() { echo; echo "== $1"; }

cleanup() {
    rc=$?
    set +e
    if [ -z "$POD_ID" ]; then
        say "no pod was claimed; nothing to release"
        return $rc
    fi
    if [ "$KEEP_POD" = "1" ]; then
        say "KEEP_POD=1 -- pod $POD_ID LEFT RUNNING at about $RATE USD/hr"
        echo "   release it yourself:  runpodctl pod remove $POD_ID"
        return $rc
    fi
    if [ "$FETCHED" = "1" ]; then
        say "artifacts are local; removing pod $POD_ID (ends GPU and disk billing)"
        runpodctl pod remove "$POD_ID" 2>&1 | head -5
    else
        say "artifacts NOT fetched; stopping pod $POD_ID instead of removing it"
        runpodctl pod stop "$POD_ID" 2>&1 | head -5
        echo "   the volume is intact, so the run is recoverable, but it still bills"
        echo "   about 0.03 USD/hr. Recover it, or release it:"
        echo "     runpodctl pod start  $POD_ID"
        echo "     runpodctl pod remove $POD_ID"
    fi
    say "final pod state"
    runpodctl pod get "$POD_ID" 2>&1 | grep -E "desiredStatus|runtimeStatusReason" | head -4
    runpodctl user 2>&1 | grep -E "clientBalance|currentSpendPerHr"
    return $rc
}
trap cleanup EXIT INT TERM

# --------------------------------------------------------------------- claim a GPU
say "claiming a GPU (up to ${CLAIM_MAX_SEC}s)"
CLAIM_START=$(date +%s)
while [ -z "$POD_ID" ]; do
    elapsed=$(( $(date +%s) - CLAIM_START ))
    if [ "$elapsed" -ge "$CLAIM_MAX_SEC" ]; then
        echo "TIMEOUT: no capacity after ${elapsed}s"
        exit 2
    fi
    for entry in "${CANDIDATES[@]}"; do
        gpu="${entry%%|*}"
        price="${entry##*|}"
        out=$(runpodctl pod create --name "$POD_NAME" --template-id runpod-torch-v240 --gpu-id "$gpu" --cloud-type SECURE --container-disk-in-gb 30 --volume-in-gb 30 --volume-mount-path /workspace --wait --wait-timeout 10m 2>/dev/null)
        if [ $? -eq 0 ]; then
            POD_ID=$(printf "%s" "$out" | python3 scripts/_podinfo.py id)
            if [ -n "$POD_ID" ]; then
                RATE="$price"
                say "claimed $gpu at $price USD/hr -- pod $POD_ID"
                break
            fi
        fi
    done
    if [ -z "$POD_ID" ]; then sleep "$CLAIM_INTERVAL"; fi
done

# ----------------------------------------------------------------- wait for real ssh
# RUNNING does not mean sshd is up, and ssh info exits 0 while reporting that the pod
# is not connectable -- so this loop tests the payload, and is bounded.
say "waiting for ssh"
IP=""
PORT=""
for i in $(seq 1 60); do
    endpoint=$(runpodctl ssh info "$POD_ID" 2>/dev/null | python3 scripts/_podinfo.py ssh 2>/dev/null)
    if [ -n "$endpoint" ]; then
        IP="${endpoint%% *}"
        PORT="${endpoint##* }"
        echo "   ssh ready at $IP:$PORT after $(( i * 10 ))s"
        break
    fi
    sleep 10
done
if [ -z "$IP" ]; then
    echo "ERROR: pod $POD_ID never became ssh-reachable"
    exit 3
fi

SSHOPTS="-i $KEY -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$HOME/.ssh/known_hosts -o ConnectTimeout=20 -o BatchMode=yes"

# ----------------------------------------------------------------------- ship + launch
say "shipping source"
tar -C "$SRC" --exclude=.venv --exclude=models --exclude=_artifacts --exclude=_remote_artifacts --exclude=_run2_artifacts --exclude=__pycache__ --exclude="*.pyc" -czf /tmp/kg-src.tgz .
ls -lh /tmp/kg-src.tgz
scp $SSHOPTS -P "$PORT" /tmp/kg-src.tgz "root@$IP:/workspace/keyguard-training-src.tgz" || exit 4
scp $SSHOPTS -P "$PORT" "$SRC/scripts/remote_bootstrap.sh" "root@$IP:/workspace/remote_bootstrap.sh" || exit 4

say "bootstrapping and launching the pipeline"
ssh $SSHOPTS -p "$PORT" "root@$IP" "bash /workspace/remote_bootstrap.sh" 2>&1 | tail -30

# ------------------------------------------------------------------------------- wait
# run_pipeline.sh writes /workspace/PIPELINE_DONE from an EXIT trap, so the marker
# appears on failure too -- which is what makes a partial run fetchable rather than
# something to sit and wait out.
say "waiting for the pipeline (deadline ${MAX_RUN_MIN}m)"
DEADLINE=$(( $(date +%s) + MAX_RUN_MIN * 60 ))
DONE=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    if ssh $SSHOPTS -p "$PORT" "root@$IP" "test -f /workspace/PIPELINE_DONE" 2>/dev/null; then
        DONE=1
        break
    fi
    sleep 60
done
if [ "$DONE" = "1" ]; then
    say "pipeline finished"
    ssh $SSHOPTS -p "$PORT" "root@$IP" "cat /workspace/PIPELINE_DONE" 2>/dev/null
else
    say "DEADLINE HIT after ${MAX_RUN_MIN}m -- packaging whatever exists"
    ssh $SSHOPTS -p "$PORT" "root@$IP" "cd /workspace/training && tar -czf /workspace/run_artifacts.tgz --ignore-failed-read models logs 2>/dev/null; ls -la /workspace/run_artifacts.tgz" 2>/dev/null
fi

# ------------------------------------------------------------------------------ fetch
say "fetching artifacts to $DEST"
mkdir -p "$DEST"
if scp $SSHOPTS -P "$PORT" "root@$IP:/workspace/run_artifacts.tgz" "$DEST/run_artifacts.tgz"; then
    if tar -xzf "$DEST/run_artifacts.tgz" -C "$DEST"; then
        rm -f "$DEST/run_artifacts.tgz"
        FETCHED=1
        echo "   extracted:"
        find "$DEST" -maxdepth 2 -type d | head -20
        du -sh "$DEST"
    else
        echo "   ERROR: archive arrived but did not extract; keeping the pod"
    fi
else
    echo "   ERROR: could not fetch /workspace/run_artifacts.tgz; keeping the pod"
fi

if [ "$FETCHED" = "1" ] && [ "$DONE" = "1" ]; then
    say "run complete and local"
    exit 0
fi
exit 5
