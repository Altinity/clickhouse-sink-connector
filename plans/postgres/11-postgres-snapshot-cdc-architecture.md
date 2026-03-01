# Plan 11 — PostgreSQL Snapshot + CDC Architecture

**Status:** Implementation Complete  
**Date:** 2026-02-28  
**Scope:** `awacs-qa` and all future PostgreSQL → ClickHouse pipelines

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture Diagram](#2-architecture-diagram)
3. [Phase 1 — Bulk Snapshot (Python)](#3-phase-1--bulk-snapshot-python)
4. [Phase 2 — CDC Streaming (Java / Debezium)](#4-phase-2--cdc-streaming-java--debezium)
5. [ANTLR-based DDL Parser](#5-antlr-based-ddl-parser)
6. [Type Mapping Reference](#6-type-mapping-reference)
7. [awacs-qa Operations Runbook](#7-awacs-qa-operations-runbook)
8. [Cutover Procedure](#8-cutover-procedure)
9. [Verification Checklist](#9-verification-checklist)
10. [Rollback Plan](#10-rollback-plan)

---

## 1. Overview

The root cause of the `awacs-qa` connector failures was the `interval` PostgreSQL
type, which the previous regex-based `PostgreSQLDDLParserService` could not handle
during auto-create.  The fix is a **two-phase pipeline**:

| Phase | Tool | Responsibility |
|-------|------|----------------|
| 1 – Bulk Snapshot | Python (`postgres_dumper.py`) | Parallel `COPY … TO STDOUT FORMAT CSV` → ClickHouse `INSERT FORMAT CSV` |
| 2 – CDC Streaming | Debezium + Java listener | WAL-based change capture after snapshot LSN |

The phases hand off via the `altinity_sink_connector.replica_source_info_*` offset
table: the snapshot writes the capture LSN, and Debezium starts from that LSN with
`snapshot.mode=never`.

---

## 2. Architecture Diagram

```
PostgreSQL (primary)
        │
        ├─ Phase 1 ──► python postgres_dumper.py
        │                    │  parallel COPY CSV  (N threads)
        │                    ▼
        │              clickhouse-client INSERT FORMAT CSV
        │                    │
        │                    ▼
        │              ClickHouse ReplacingMergeTree tables
        │                    │
        │                    ▼
        │              write LSN offset to
        │              altinity_sink_connector.replica_source_info_<db>
        │
        └─ Phase 2 ──► Debezium PostgreSQL Connector
                            │  snapshot.mode=never
                            │  reads from saved LSN
                            ▼
                       Kafka / Embedded Debezium
                            │
                            ▼
                       ClickHouse Sink Connector (Java)
                       PostgreSQLDDLParserService (ANTLR)
                            │
                            ▼
                       ClickHouse ReplacingMergeTree tables
```

---

## 3. Phase 1 — Bulk Snapshot (Python)

### Files

| File | Purpose |
|------|---------|
| `sink-connector/python/db/postgres.py` | PG connection helpers, type mapper, DDL builder |
| `sink-connector/python/db_dump/postgres_dumper.py` | Parallel snapshot orchestrator |
| `sink-connector/python/db_load/postgres_type_mapper.py` | PG → CH type mapping module |

### How It Works

1. **Capture LSN** — `SELECT pg_current_wal_lsn()` before any COPY starts.
2. **Table discovery** — query `information_schema.tables` (optionally filtered by
   `--tables` / `--exclude_tables` glob patterns).
3. **Schema creation** — for each table, introspect `information_schema.columns`
   and emit a `CREATE TABLE IF NOT EXISTS … ENGINE=ReplacingMergeTree` DDL, adding
   `_version Nullable(UInt64)` and `is_deleted UInt8 DEFAULT 0` virtual columns.
4. **Parallel data load** — `ThreadPoolExecutor` (default 8 threads) runs:
   ```
   psql "COPY (SELECT …) TO STDOUT (FORMAT CSV, HEADER false, NULL '')" \
     | clickhouse-client --query "INSERT INTO db.table FORMAT CSV"
   ```
5. **Offset write** — insert a row into
   `altinity_sink_connector.replica_source_info_<database>`:
   ```sql
   INSERT INTO altinity_sink_connector.replica_source_info_<db>
       (id, offset, database, table, binlog_file, binlog_position, …)
   VALUES (1, '<lsn>', '<db>', '', '', 0, …)
   ```

### CLI Reference

```bash
python db_dump/postgres_dumper.py \
  --pg_host  localhost \
  --pg_port  5432 \
  --pg_database  awacs \
  --pg_user  replicator \
  --pg_password  secret \
  --pg_schema    public \
  --ch_host      clickhouse.internal \
  --ch_port      9000 \
  --ch_database  awacs \
  --ch_user      default \
  --threads  8 \
  [--tables "orders,users"] \
  [--exclude_tables "audit_log"] \
  [--schema_only] \
  [--data_only] \
  [--dry_run]
```

---

## 4. Phase 2 — CDC Streaming (Java / Debezium)

### Debezium Connector Config (`snapshot.mode=never`)

```json
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "database.hostname": "pg.internal",
  "database.port": "5432",
  "database.user": "replicator",
  "database.password": "secret",
  "database.dbname": "awacs",
  "database.server.name": "awacs",
  "slot.name": "debezium_awacs",
  "plugin.name": "pgoutput",
  "snapshot.mode": "never",
  "publication.autocreate.mode": "filtered",
  "table.include.list": "public\\..*",
  "decimal.handling.mode": "string",
  "interval.handling.mode": "string",
  "time.precision.mode": "connect",
  "tombstones.on.delete": "false",

  "clickhouse.server.url": "jdbc:clickhouse://ch.internal:8123",
  "clickhouse.server.database": "awacs",
  "auto.create.tables": "true",
  "replacingmergetree.delete.column": "is_deleted"
}
```

### Key Config Decisions

| Setting | Value | Reason |
|---------|-------|--------|
| `snapshot.mode` | `never` | Snapshot already done by Python; Debezium picks up from saved LSN |
| `decimal.handling.mode` | `string` | Avoids Debezium schema-registry decimal encoding issues |
| `interval.handling.mode` | `string` | `interval` maps to `String` in ClickHouse |
| `plugin.name` | `pgoutput` | Native logical replication; no extra PG extension |
| `slot.name` | `debezium_awacs` | Must be created before starting connector |

### Required PostgreSQL Setup

```sql
-- Enable logical replication (requires postgres.conf change + reload)
-- wal_level = logical

-- Create replication slot
SELECT pg_create_logical_replication_slot('debezium_awacs', 'pgoutput');

-- Create publication
CREATE PUBLICATION debezium_awacs FOR ALL TABLES;

-- Grant replication privileges
GRANT REPLICATION SLAVE ON DATABASE awacs TO replicator;
```

---

## 5. ANTLR-based DDL Parser

The previous regex-based `PostgreSQLDDLParserService` is replaced by a proper
ANTLR4 grammar-driven parser.

### Files

| File | Role |
|------|------|
| `src/main/antlr4/postgres/PostgreSQLLexer.g4` | Copied from grammars-v4; tokenises PG SQL |
| `src/main/antlr4/postgres/PostgreSQLParser.g4` | Grammar rules; `superClass=PostgreSQLParserBase` |
| `src/main/java/postgres/PostgreSQLLexerBase.java` | Superclass for lexer (dollar-quoting, helper predicates) |
| `src/main/java/postgres/PostgreSQLParserBase.java` | Superclass for parser (routine body, unquote) |
| `…/ddl/parser/PostgreSQLDDLParserListenerImpl.java` | ANTLR listener → ClickHouse DDL |
| `…/ddl/parser/PostgreSQLDDLParserService.java` | Entry point; replaces regex with ANTLR pipeline |

### ANTLR Pipeline (per DDL statement)

```
PostgreSQLLexer(CharStreams.fromString(sql))
   ↓
CommonTokenStream
   ↓
PostgreSQLParser.root()
   ↓
ParseTreeWalker.walk(PostgreSQLDDLParserListenerImpl, tree)
   ↓
StringBuffer (ClickHouse DDL)
```

### Listener Grammar → ClickHouse Mappings

| PG Grammar Rule | PG DDL | CH DDL |
|-----------------|--------|--------|
| `createstmt` | `CREATE TABLE t (…)` | `CREATE TABLE IF NOT EXISTS db.t (…) ENGINE=ReplacingMergeTree(…)` |
| `altertablestmt` + `ADD_P columnDef` | `ALTER TABLE t ADD COLUMN c TYPE` | `ALTER TABLE db.t ADD COLUMN IF NOT EXISTS c TYPE` |
| `altertablestmt` + `DROP column_? colid` | `ALTER TABLE t DROP COLUMN c` | `ALTER TABLE db.t DROP COLUMN IF EXISTS c` |
| `altertablestmt` + `ALTER colid TYPE_P typename` | `ALTER TABLE t ALTER COLUMN c TYPE newtype` | `ALTER TABLE db.t MODIFY COLUMN c newtype` |
| `renamestmt` | `ALTER TABLE t RENAME COLUMN old TO new` | `ALTER TABLE db.t RENAME COLUMN old TO new` |
| `dropstmt` | `DROP TABLE t` | `DROP TABLE IF EXISTS db.t` |
| `truncatestmt` | `TRUNCATE TABLE t` | `TRUNCATE TABLE IF EXISTS db.t` |

---

## 6. Type Mapping Reference

`PostgreSQLDDLParserService.mapPostgresTypeToClickHouse()` implements the following
mapping.  Non-PK columns are wrapped in `Nullable(…)` by the listener.

| PostgreSQL Type | ClickHouse Type | Notes |
|-----------------|-----------------|-------|
| `bigint`, `int8`, `bigserial` | `Int64` | |
| `integer`, `int`, `int4`, `serial` | `Int32` | |
| `smallint`, `int2`, `smallserial` | `Int16` | |
| `boolean` | `UInt8` | `0`/`1` |
| `real`, `float4` | `Float32` | |
| `double precision`, `float8` | `Float64` | |
| `numeric(p,s)`, `decimal(p,s)` | `Decimal(p,s)` | |
| `numeric` (no precision) | `Decimal(38,9)` | Wide default |
| `money` | `Decimal(19,4)` | |
| `varchar`, `character varying`, `char`, `text`, `citext`, `name` | `String` | |
| `timestamp with time zone`, `timestamptz` | `DateTime64(6,'UTC')` | |
| `timestamp without time zone`, `timestamp` | `DateTime64(6)` | |
| `timestamp(n)` | `DateTime64(n)` | |
| `date` | `Date32` | |
| `time`, `timetz` | `String` | No native equivalent |
| **`interval`** | **`String`** | **Root cause of awacs-qa failures** |
| `uuid` | `UUID` | |
| `json`, `jsonb` | `String` | |
| `bytea` | `String` | Hex-encoded |
| `inet`, `cidr`, `macaddr` | `String` | |
| `tsvector`, `tsquery` | `String` | |
| `xml` | `String` | |
| `bit`, `bit varying`, `varbit` | `String` | |
| `oid` | `UInt32` | |
| `type[]`, `ARRAY` | `String` | JSON-encoded |
| Unknown / UDT | `String` | Safe fallback |

---

## 7. awacs-qa Operations Runbook

### Pre-requisites

```bash
# Python dependencies
pip install psycopg2-binary clickhouse-driver pandas

# Binaries must be on PATH
which psql clickhouse-client

# PostgreSQL replication must be configured
# wal_level = logical in postgresql.conf
```

### Step 1 — Stop the existing connector

```bash
# If running embedded Debezium
systemctl stop clickhouse-sink-connector

# If running Kafka Connect
curl -X DELETE http://kafka-connect:8083/connectors/awacs-pg-connector
```

### Step 2 — Verify replication slot

```sql
-- On PostgreSQL
SELECT slot_name, plugin, slot_type, active, restart_lsn
FROM pg_replication_slots
WHERE slot_name = 'debezium_awacs';

-- If it does not exist, create it:
SELECT pg_create_logical_replication_slot('debezium_awacs', 'pgoutput');
```

### Step 3 — Run the Python snapshot

```bash
cd /opt/clickhouse-sink-connector/sink-connector/python

# Dry run first (schema only, no data)
python db_dump/postgres_dumper.py \
  --pg_host pg.awacs-qa.internal \
  --pg_port 5432 \
  --pg_database awacs \
  --pg_user replicator \
  --pg_password "${PG_PASSWORD}" \
  --pg_schema public \
  --ch_host ch.awacs-qa.internal \
  --ch_port 9000 \
  --ch_database awacs \
  --ch_user default \
  --ch_password "${CH_PASSWORD}" \
  --threads 8 \
  --schema_only \
  --dry_run

# If dry run looks correct, run the full snapshot
python db_dump/postgres_dumper.py \
  --pg_host pg.awacs-qa.internal \
  --pg_port 5432 \
  --pg_database awacs \
  --pg_user replicator \
  --pg_password "${PG_PASSWORD}" \
  --pg_schema public \
  --ch_host ch.awacs-qa.internal \
  --ch_port 9000 \
  --ch_database awacs \
  --ch_user default \
  --ch_password "${CH_PASSWORD}" \
  --threads 8 2>&1 | tee /var/log/awacs-snapshot-$(date +%Y%m%d).log
```

### Step 4 — Verify row counts

```bash
# Quick count comparison script
for table in $(psql -h pg.awacs-qa.internal -U replicator -d awacs \
    -t -c "SELECT tablename FROM pg_tables WHERE schemaname='public'"); do
  pg_count=$(psql -h pg.awacs-qa.internal -U replicator -d awacs \
    -t -c "SELECT COUNT(*) FROM public.$table" | tr -d ' ')
  ch_count=$(clickhouse-client -h ch.awacs-qa.internal -d awacs \
    --query "SELECT COUNT(*) FROM $table FINAL")
  echo "$table: PG=$pg_count  CH=$ch_count"
done
```

### Step 5 — Start CDC connector

```bash
# Verify saved LSN offset
clickhouse-client -h ch.awacs-qa.internal \
  --query "SELECT * FROM altinity_sink_connector.replica_source_info_awacs"

# Start the connector (embedded mode)
systemctl start clickhouse-sink-connector

# Or Kafka Connect
curl -X POST http://kafka-connect:8083/connectors \
  -H 'Content-Type: application/json' \
  -d @/etc/debezium/awacs-connector.json
```

### Step 6 — Monitor

```bash
# Watch replication lag (slots)
psql -h pg.awacs-qa.internal -U replicator -d awacs \
  -c "SELECT slot_name, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS lag
      FROM pg_replication_slots WHERE slot_name = 'debezium_awacs';"

# ClickHouse ingestion rate
clickhouse-client -h ch.awacs-qa.internal \
  --query "SELECT event_time, rows_inserted FROM system.part_log
           WHERE database='awacs' ORDER BY event_time DESC LIMIT 20"

# Connector logs
journalctl -u clickhouse-sink-connector -f
```

---

## 8. Cutover Procedure

| # | Action | Who | Time |
|---|--------|-----|------|
| 1 | Announce maintenance window | Ops | T-1h |
| 2 | Stop application writes to PostgreSQL | App team | T-0 |
| 3 | Stop old connector | Ops | T+0 |
| 4 | Run Python snapshot (`--data_only`) for final delta | Ops | T+5m |
| 5 | Verify row counts (Step 4 above) | Ops | T+15m |
| 6 | Start new CDC connector (`snapshot.mode=never`) | Ops | T+20m |
| 7 | Resume application writes | App team | T+25m |
| 8 | Monitor replication lag for 30 min | Ops | T+55m |
| 9 | Close maintenance window | Ops | T+60m |

---

## 9. Verification Checklist

- [ ] PostgreSQL `wal_level = logical` confirmed
- [ ] Replication slot `debezium_awacs` exists and is inactive before snapshot
- [ ] Python snapshot completes without errors (exit code 0)
- [ ] Row counts match between PostgreSQL and ClickHouse (≤ 0.1% variance acceptable)
- [ ] LSN offset row exists in `altinity_sink_connector.replica_source_info_awacs`
- [ ] CDC connector starts and reports `RUNNING` state
- [ ] Test INSERT/UPDATE/DELETE on PostgreSQL side; verify in ClickHouse within 5s
- [ ] `interval` columns correctly stored as `String` in ClickHouse
- [ ] `jsonb` columns correctly stored as `String` in ClickHouse
- [ ] `timestamptz` columns correctly stored as `DateTime64(6,'UTC')` in ClickHouse
- [ ] No replication slot lag growth after 30 minutes

---

## 10. Rollback Plan

### Rollback Phase 1 (Snapshot failed)

```bash
# Drop any partially created CH tables
clickhouse-client -h ch.awacs-qa.internal \
  --query "DROP DATABASE IF EXISTS awacs"

# Restart from Step 3 after fixing the issue
```

### Rollback Phase 2 (CDC failed)

```bash
# Stop new connector
systemctl stop clickhouse-sink-connector

# Restart with previous connector version / config
systemctl start clickhouse-sink-connector-legacy

# If replication slot is stuck
psql -h pg.awacs-qa.internal -U replicator -d awacs \
  -c "SELECT pg_drop_replication_slot('debezium_awacs');"
# Then recreate: SELECT pg_create_logical_replication_slot(...)
```

### Known Issues and Mitigations

| Issue | Symptom | Mitigation |
|-------|---------|------------|
| `interval` type in CREATE TABLE | Connector crash on auto-create | Fixed: mapped to `String` in ANTLR listener |
| Large tables (>100M rows) | Snapshot timeout | Use `--tables` to snapshot in batches |
| Replication slot lag > 1GB | PostgreSQL WAL bloat | Increase `--threads`, or snapshot during low-traffic window |
| `Decimal(38,9)` overflow | CH insert error for unbounded NUMERIC | Override column type via `schemaOverride` config |
| PG extensions types (e.g. `geometry`) | Fallback to `String` | Verify stored values post-migration |

---

## 11. awacs-qa Specific Runbook (Production)

### Environment

| Component | Host | Port | Details |
|-----------|------|------|---------|
| PostgreSQL | `postgres` | `5435` | database `awacs-qa` |
| ClickHouse | `clickhouse` | `9000` (native) / `8123` (HTTP) | database `awacs_qa` |
| Connector service | `clickhouse` | — | systemd user service: `sink-connector-awacs-qa-sink-dev` |
| Connector config | `clickhouse` | — | `/home/clickhouse/sink-connector/awacs-qa-sink-dev/config/config.yml` |
| Connector log | `clickhouse` | — | `/home/clickhouse/sink-connector/awacs-qa-sink-dev/logs/sink-connector.log` |
| Connector name | — | — | `sink-connector-awacs-qa-sink-dev` |
| CH offset table | — | — | `altinity_sink_connector.replica_source_info_awacs_qa_dev` |
| Helper scripts | `clickhouse` | — | `~/.ch.sh <server> default -q "<sql>"` / `~/.postgres.sh postgres 5435 awacs-qa postgres -c "<sql>"` |

### Step 1 — Stop the connector (on clickhouse)

```bash
# Check current status first
sudo -u clickhouse XDG_RUNTIME_DIR=/run/user/1000 \
  systemctl --user status sink-connector-awacs-qa-sink-dev.service

# Stop
sudo -u clickhouse XDG_RUNTIME_DIR=/run/user/1000 \
  systemctl --user stop sink-connector-awacs-qa-sink-dev.service

# Confirm stopped
sudo -u clickhouse XDG_RUNTIME_DIR=/run/user/1000 \
  systemctl --user is-active sink-connector-awacs-qa-sink-dev.service
# Expected: inactive
```

### Step 2 — Configure CDC-only mode (on clickhouse)

Edit `/home/clickhouse/sink-connector/awacs-qa-sink-dev/config/config.yml`:

Find the `snapshot.mode` line and change it to `never`:

```yaml
# BEFORE (causes Debezium to snapshot on every restart)
snapshot.mode: "initial"

# AFTER (CDC-only; snapshot done by Python postgres_dumper.py)
snapshot.mode: "never"
```

Verify the change:
```bash
grep snapshot.mode \
  /home/clickhouse/sink-connector/awacs-qa-sink-dev/config/config.yml
```

### Step 3 — Drop and recreate awacs_qa in ClickHouse (clean slate)

```bash
# Check current row counts for reference
~/.ch.sh clickhouse default -q \
  "SELECT table, sum(rows) rows FROM system.parts WHERE database='awacs_qa' AND active GROUP BY table ORDER BY rows DESC"

# Drop entire database (this destroys all current data — only do after confirming connector is stopped)
~/.ch.sh clickhouse default -q "DROP DATABASE IF EXISTS awacs_qa"

# Recreate empty database
~/.ch.sh clickhouse default -q "CREATE DATABASE awacs_qa"
```

### Step 4 — Run Python snapshot (on clickhouse or any host with psql + clickhouse-client)

```bash
# Ensure Python dependencies are installed
pip install psycopg2-binary clickhouse-driver pandas

# Navigate to python directory
cd /home/minguyen/workspace/clickhouse-sink-connector/sink-connector/python

# Dry run first — shows DDL without executing
python db_dump/postgres_dumper.py \
  --pg_host postgres \
  --pg_port 5435 \
  --pg_database awacs-qa \
  --pg_user postgres \
  --pg_schema public \
  --ch_host clickhouse \
  --ch_port 9000 \
  --ch_database awacs_qa \
  --ch_user default \
  --threads 8 \
  --offset_table altinity_sink_connector.replica_source_info_awacs_qa_dev \
  --connector_name sink-connector-awacs-qa-sink-dev \
  --schema_only \
  --dry_run

# Full snapshot (parallel, 8 threads, ~70-80M rows total across 36 tables)
python db_dump/postgres_dumper.py \
  --pg_host postgres \
  --pg_port 5435 \
  --pg_database awacs-qa \
  --pg_user postgres \
  --pg_schema public \
  --ch_host clickhouse \
  --ch_port 9000 \
  --ch_database awacs_qa \
  --ch_user default \
  --threads 8 \
  --offset_table altinity_sink_connector.replica_source_info_awacs_qa_dev \
  --connector_name sink-connector-awacs-qa-sink-dev \
  2>&1 | tee /tmp/awacs-qa-snapshot-$(date +%Y%m%d-%H%M%S).log
```

> **Note on `--pg_password`:** If the `postgres` user uses peer auth inside Docker, you may need
> to exec into the container and run `pg_dump` from there. Alternatively set `~/.pgpass` for
> `postgres:5435:awacs-qa:postgres:<password>`.

> **Note on `interval` tables:** `alerts_rule` and `alerts_rulehistoryentry` have `ttl interval`
> columns. The Python dumper correctly maps `interval → String` — these tables will be created
> cleanly and loaded without the `TABLE METADATA not retrieved` errors seen before.

### Step 5 — Verify row counts after snapshot

```bash
# Quick PG vs CH count comparison for all 36 tables
~/.postgres.sh postgres 5435 awacs-qa postgres -c \
  "SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE schemaname='public' ORDER BY relname" \
  | while read table count; do
    ch_count=$(~/.ch.sh clickhouse default -q \
      "SELECT count() FROM awacs_qa.\`$table\` FINAL" 2>/dev/null || echo "N/A")
    printf "%-50s  PG=%-12s  CH=%s\n" "$table" "$count" "$ch_count"
  done

# Spot-check the two previously broken tables
~/.postgres.sh postgres 5435 awacs-qa postgres -c \
  "SELECT COUNT(*) FROM public.alerts_rule"
~/.ch.sh clickhouse default -q \
  "SELECT count() FROM awacs_qa.alerts_rule FINAL"

~/.postgres.sh postgres 5435 awacs-qa postgres -c \
  "SELECT COUNT(*) FROM public.alerts_rulehistoryentry"
~/.ch.sh clickhouse default -q \
  "SELECT count() FROM awacs_qa.alerts_rulehistoryentry FINAL"
```

### Step 6 — Verify LSN offset was written correctly

```bash
~/.ch.sh clickhouse default -q \
  "SELECT id, offset_key, offset_val, record_insert_ts
   FROM altinity_sink_connector.replica_source_info_awacs_qa_dev
   ORDER BY record_insert_ts DESC LIMIT 5"
```

Expected output format:
```
id                                   offset_key                                                                       offset_val                                         record_insert_ts
<uuid>  ["sink-connector-awacs-qa-sink-dev",{"server":"embeddedconnector"}]   {"transaction_id":null,"lsn_proc":<N>,"lsn":<N>,"ts_usec":<T>}   2026-02-28 ...
```

### Step 7 — Start CDC-only connector (on clickhouse)

```bash
# Confirm snapshot.mode=never is in config before starting
grep snapshot.mode \
  /home/clickhouse/sink-connector/awacs-qa-sink-dev/config/config.yml

# Start
sudo -u clickhouse XDG_RUNTIME_DIR=/run/user/1000 \
  systemctl --user start sink-connector-awacs-qa-sink-dev.service

# Watch logs for startup confirmation
sudo -u clickhouse XDG_RUNTIME_DIR=/run/user/1000 \
  journalctl --user -u sink-connector-awacs-qa-sink-dev.service -f --no-pager
# Look for: "Starting streaming" (NOT "Snapshot completed" — that would mean it re-snapshotted)
# Also look for: "No previous offset found" — if this appears, check --connector_name matches
```

### Step 8 — Monitor CDC replication

```bash
# Connector log tail
tail -f /home/clickhouse/sink-connector/awacs-qa-sink-dev/logs/sink-connector.log

# Replication slot WAL lag on PostgreSQL
~/.postgres.sh postgres 5435 awacs-qa postgres -c \
  "SELECT slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS lag
   FROM pg_replication_slots WHERE plugin='pgoutput'"

# ClickHouse ingestion rate
~/.ch.sh clickhouse default -q \
  "SELECT event_time, database, table, rows_inserted
   FROM system.part_log
   WHERE database='awacs_qa' AND event_type='NewPart'
   ORDER BY event_time DESC LIMIT 20"
```

### Bug Fixes Applied (2026-02-28)

These bugs were present in the initial implementation and have been fixed:

| # | File | Bug | Fix |
|---|------|-----|-----|
| 1 | `postgres_dumper.py` | `with clickhouse_connection() as ch_conn:` crashes — `clickhouse_driver` Connection has no `__enter__` | Replaced all 3 occurrences with explicit `conn = ...; try: ... finally: conn.close()` |
| 2 | `postgres_type_mapper.py` | `offset_key='embeddedconnector'` doesn't match Java connector's lookup key format | Fixed to `["<connector_name>",{"server":"embeddedconnector"}]` format matching `DebeziumOffsetStorage.getOffsetKey()` |
| 3 | `postgres_type_mapper.py` | `offset_val` JSON had wrong fields (`snapshot_completed`) and missing fields (`lsn_proc`, `ts_usec`) | Fixed to match actual Java offset format: `{"transaction_id":null,"lsn_proc":N,"lsn":N,"ts_usec":T}` |
| 4 | `postgres_dumper.py` | No `--connector_name` CLI argument | Added `--connector_name` (default: `sink-connector`); must match Java `name` in `config.yml` |
| 5 | `postgres.py` | `get_current_lsn()` returned full 64-bit LSN but Debezium stores only low-32-bit half | Fixed: now returns `int(lo, 16)` only (right side of `/` in LSN string) |
