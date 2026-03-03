#!/usr/bin/env bash
# -- ===========================================================================
# -- build_wheel.sh — Build ch_sink_tools wheel and optionally deploy
# --
# -- Usage:
# --   ./build_wheel.sh              # build only
# --   ./build_wheel.sh --deploy     # build + scp + pip install on target
# -- ===========================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

TARGET_HOST="${CH_DEPLOY_HOST:-ch-server}"
TARGET_USER="${CH_DEPLOY_USER:-clickhouse}"
TARGET_VENV="${CH_DEPLOY_VENV:-/opt/python-dump/.venv}"

# ---------------------------------------------------------------------------
# Clean previous builds
# ---------------------------------------------------------------------------
rm -rf dist/ build/ *.egg-info ch_sink_tools.egg-info

# ---------------------------------------------------------------------------
# Build wheel
# ---------------------------------------------------------------------------
echo "=== Building wheel ==="
python -m build --wheel --outdir dist/

WHEEL=$(ls -1t dist/ch_sink_tools-*.whl | head -1)
echo "Built: ${WHEEL}"
echo "Size:  $(du -h "${WHEEL}" | cut -f1)"

# ---------------------------------------------------------------------------
# Verify wheel contents
# ---------------------------------------------------------------------------
echo ""
echo "=== Wheel contents (top-level) ==="
unzip -l "${WHEEL}" | grep -E '(ch_sink_tools/[^/]+\.py|ch_sink_tools/[^/]+/$)' | head -20

# ---------------------------------------------------------------------------
# Deploy (optional)
# ---------------------------------------------------------------------------
if [[ "${1:-}" == "--deploy" ]]; then
    echo ""
    echo "=== Deploying to ${TARGET_HOST} ==="
    WHEEL_NAME=$(basename "${WHEEL}")
    scp "${WHEEL}" "${TARGET_USER}@${TARGET_HOST}:/tmp/${WHEEL_NAME}"
    ssh "${TARGET_USER}@${TARGET_HOST}" \
        "${TARGET_VENV}/bin/pip install --force-reinstall /tmp/${WHEEL_NAME}"
    echo "=== Deployed and installed on ${TARGET_HOST} ==="
    echo ""
    echo "Verify with:"
    echo "  ssh ${TARGET_USER}@${TARGET_HOST} '${TARGET_VENV}/bin/ch-checksum --help'"
fi
