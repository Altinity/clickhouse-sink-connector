# ch-sink-tools

PostgreSQL/MySQL to ClickHouse CDC verification and snapshot tools.

## Installation

```bash
# PostgreSQL-only (primary use case):
pip install ch_sink_tools-0.2.0-py3-none-any.whl

# With MySQL support:
pip install "ch_sink_tools-0.2.0-py3-none-any.whl[mysql]"

# Everything (MySQL + pandas):
pip install "ch_sink_tools-0.2.0-py3-none-any.whl[all]"
```

## CLI Commands

| Command | Description |
|---|---|
| `ch-checksum` | Full PG→CH checksum orchestrator (top-level) |
| `ch-pg-checksum` | Single-table PostgreSQL checksum |
| `ch-pg-count` | PostgreSQL row count comparison |
| `ch-ch-checksum` | ClickHouse-side checksum |
| `ch-ch-count` | ClickHouse row count |
| `ch-pg-dump` | PostgreSQL → ClickHouse snapshot loader |
| `ch-mysql-checksum` | MySQL checksum (requires `[mysql]` extra) |
| `ch-mysql-dump` | MySQL dump (requires `[mysql]` extra) |
| `ch-mysql-load` | MySQL → ClickHouse loader (requires `[mysql]` extra) |

## Quick Start

```bash
# Run checksum verification
ch-checksum --config /path/to/config.yml

# Single-table checksum with debug
ch-checksum --config config.yml --table my_table --debug

# Count-only check
ch-pg-count --config config.yml

# Snapshot load from PostgreSQL
ch-pg-dump --pg-host pgserver --ch-host chserver --pg-database mydb
```

## Building

```bash
pip install build
python -m build --wheel
# Produces: dist/ch_sink_tools-0.2.0-py3-none-any.whl
```

## Deploying

```bash
# Build + deploy in one step:
./build_wheel.sh --deploy

# Or manually:
scp dist/ch_sink_tools-*.whl user@ch-server:/tmp/
ssh user@ch-server '/opt/python-dump/.venv/bin/pip install --force-reinstall /tmp/ch_sink_tools-*.whl'
```

## Dependencies

**Core (always installed):**
- `clickhouse-driver>=0.2.9`
- `psycopg2-binary`
- `pyyaml`

**Optional:**
- `[mysql]`: `pymysql`, `sqlalchemy>=1.4`, `antlr4-python3-runtime==4.11.1`
- `[dataframe]`: `pandas`
