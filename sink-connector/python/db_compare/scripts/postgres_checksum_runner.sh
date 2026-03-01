#!/usr/bin/env bash
# -- ===========================================================================
# -- FileName    : postgres_checksum_runner.sh
# -- Summary     : Cron-compatible shell wrapper for PostgreSQL→ClickHouse
# --               periodic checksum verification on fpif-dbachl4.
# --
# -- Usage       : ./postgres_checksum_runner.sh [config.yml] [extra args...]
# --               ./postgres_checksum_runner.sh                        # uses default config
# --               ./postgres_checksum_runner.sh config_postgres_system.yml --no-checksum
# --               ./postgres_checksum_runner.sh config_postgres_system.yml --table alerts_rule
# --
# -- Cron        : # Run hourly (at minute 5 past the hour — avoids top-of-hour contention)
# --               5 * * * * /home/clickhouse/python-dump/db_compare/postgres_checksum_runner.sh \
# --                   >> /var/log/pg_ch_checksum/cron.log 2>&1
# --
# --               # Optional: daily full run with debug output
# --               5 2 * * * /home/clickhouse/python-dump/db_compare/postgres_checksum_runner.sh \
# --                   --debug >> /var/log/pg_ch_checksum/daily_$(date +\%Y\%m\%d).log 2>&1
# --
# -- Exit code   : 0  → all tables PASS
# --               1  → one or more tables FAIL / MISSING / ERROR
# --               2  → script startup error (missing config, bad Python path)
# -- ===========================================================================
set -uo pipefail

# ---------------------------------------------------------------------------
# Configurable paths
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_DIR="$(dirname "$SCRIPT_DIR")"

# Default config file — override with first positional argument
DEFAULT_CONFIG="${SCRIPT_DIR}/config_postgres_system.yml"

# Log directory and file (date-stamped for daily rotation)
LOG_DIR="${PG_CH_CHECKSUM_LOG_DIR:-/var/log/pg_ch_checksum}"
LOG_FILE="${LOG_DIR}/system_$(date +%Y%m%d).log"

# Python virtualenv (same one used by postgres_dumper.py)
VENV="${PYTHON_VENV:-${PYTHON_DIR}/.venv}"

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
CONFIG_FILE="$DEFAULT_CONFIG"
EXTRA_ARGS=()

if [[ $# -gt 0 && "${1}" != --* && "${1}" != -* ]]; then
    # First positional arg is the config file if it doesn't start with '-'
    CONFIG_FILE="$1"
    shift
fi

# Remaining args are passed through to top_level_postgres_checksum.py
EXTRA_ARGS=("$@")

# ---------------------------------------------------------------------------
# Validate config file
# ---------------------------------------------------------------------------
if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "ERROR: Config file not found: $CONFIG_FILE" >&2
    echo "Usage: $0 [config.yml] [--table TABLE] [--no-checksum] [--debug]" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# Set up log directory
# ---------------------------------------------------------------------------
if [[ ! -d "$LOG_DIR" ]]; then
    mkdir -p "$LOG_DIR" 2>/dev/null || {
        # Fall back to /tmp if /var/log is not writable
        LOG_DIR="/tmp/pg_ch_checksum"
        LOG_FILE="${LOG_DIR}/system_$(date +%Y%m%d).log"
        mkdir -p "$LOG_DIR"
        echo "WARNING: Using fallback log dir: $LOG_DIR" >&2
    }
fi

# ---------------------------------------------------------------------------
# Activate virtualenv if present
# ---------------------------------------------------------------------------
if [[ -f "${VENV}/bin/activate" ]]; then
    # shellcheck disable=SC1091
    source "${VENV}/bin/activate"
    echo "Activated virtualenv: ${VENV}"
else
    echo "WARNING: No virtualenv found at ${VENV}; using system Python" >&2
fi

# ---------------------------------------------------------------------------
# Set PYTHONPATH so db/ and db_compare/ imports resolve correctly
# ---------------------------------------------------------------------------
export PYTHONPATH="${PYTHON_DIR}:${PYTHONPATH:-}"

# ---------------------------------------------------------------------------
# Run the checksum
# ---------------------------------------------------------------------------
cd "$PYTHON_DIR"

TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "=== ${TIMESTAMP} PG→CH checksum starting (config: ${CONFIG_FILE}) ===" | tee -a "$LOG_FILE"
echo "=== Extra args: ${EXTRA_ARGS[*]:-<none>} ===" | tee -a "$LOG_FILE"

python db_compare/top_level_postgres_checksum.py \
    --config "${CONFIG_FILE}" \
    "${EXTRA_ARGS[@]}" \
    2>&1 | tee -a "$LOG_FILE"

# Capture exit code from python (not from tee)
EXIT_CODE="${PIPESTATUS[0]}"

TIMESTAMP_END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "=== ${TIMESTAMP_END} PG→CH checksum finished (exit=${EXIT_CODE}) ===" | tee -a "$LOG_FILE"

# ---------------------------------------------------------------------------
# Alert on failure
# ---------------------------------------------------------------------------
if [[ "$EXIT_CODE" -ne 0 ]]; then
    echo "CHECKSUM FAILED (exit=${EXIT_CODE}) — see ${LOG_FILE}" >&2

    # Uncomment to send email alert:
    # if command -v mail &>/dev/null; then
    #     tail -50 "$LOG_FILE" | mail -s "PG→CH checksum FAILED: awacs-qa (exit=${EXIT_CODE})" ops@example.com
    # fi

    # Uncomment to call a webhook (e.g. PagerDuty):
    # curl -s -X POST https://events.pagerduty.com/v2/enqueue \
    #   -H "Content-Type: application/json" \
    #   -d "{\"routing_key\": \"${PAGERDUTY_KEY}\", \"event_action\": \"trigger\", \
    #        \"payload\": {\"summary\": \"PG→CH checksum FAILED: awacs-qa\", \
    #                      \"severity\": \"critical\", \"source\": \"fpif-dbachl4\"}}"
fi

exit "$EXIT_CODE"
