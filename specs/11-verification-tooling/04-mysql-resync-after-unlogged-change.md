# Spec 11.04: Re-synchronising a Replica After an Unlogged Source Change (`ch-mysql-resync`)

## 1. Executive Summary & Purpose
The connector replicates the MySQL binlog. A source change that never enters the
binlog is invisible to it: `SET sql_log_bin = 0` data patches, `DROP`/`CREATE` +
reload refreshes of a whole schema, restores from a logical dump, physical
restores. After such a change the ClickHouse replica silently keeps the old rows
(and keeps rows the source no longer has), the value-level checksum (spec 11.02)
fails, and the connector — correctly — carries on from its offset as if nothing
happened. Restarting it changes nothing; a schema-only snapshot changes nothing.

This spec defines the repair procedure and the tool that performs it,
`sink-connector/python/ch_sink_tools/db_load/mysql_resync.py` (`ch-mysql-resync`).
It is the operator's authoritative-side repair made mechanical: **MySQL is the
source of truth, the replica is conformed to MySQL, never the other way round**
(`AGENTS.md`), and every step is count-reconciled. It replaces ad-hoc `INSERT ...
SELECT` scripts and by-hand `TRUNCATE` + reload, which are where the historical
losses came from (a truncate that reaches the wrong table, a reload whose row
count nobody compared, a partition replaced before its replacement was complete).

Three properties are required of the procedure:

1. **No live-table destruction outside an atomic replacement.** The live table is
   only ever changed by `ALTER TABLE ... REPLACE PARTITION ... FROM <scratch>`,
   which is atomic per partition and leaves the table queryable throughout. The
   tool never issues `TRUNCATE`, `DELETE`, or a mutation against a live table, and
   it never drops a partition unless explicitly instructed (`--drop-ch-only`).
2. **Every replacement is count-reconciled first.** A scratch table is replaced
   into the live table only when its row count equals the exact row count of the
   dump it was loaded from.
3. **No binlogged change is lost.** The binlog position is captured before the
   first table is read; after the patch the connector is rewound to that position
   so that everything binlogged during the dump and the patch is replayed on top
   of the patched tables (idempotent on `ReplacingMergeTree`).

---

## 2. Codebase Mapping on 2.11.0
- **Tool**: `sink-connector/python/ch_sink_tools/db_load/mysql_resync.py` —
  `capture_binlog_position()`, `cmd_dump()` (MySQL Shell `util.dumpTables`),
  `cmd_patch()` (drift → scratch load → reconcile → canary → replace → verify),
  `cmd_rewind_sql()`; pure planning helpers `parse_mysql_ddl()`,
  `column_drift()`, `drift_ddl()`, `plan_replace()`, `plain_identifiers()`,
  `rewind_offset_json()`, `rewind_offset_sql()`, `isolate_table_dir()`,
  `exact_dump_rows()`.
- **Legacy-tree entry point**: `sink-connector/python/db_load/mysql_resync.py`
  (imports the packaged module; no installation needed from a checkout).
- **Loader reused, not re-implemented**:
  `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py`
  (`load_data_mysqlshell()`, invoked once per table with `--mysqlshell
  --data_only`); the legacy copy `sink-connector/python/db_load/clickhouse_loader.py`
  can be substituted with `--loader-cmd`.
- **Console script**: `ch-mysql-resync` in `sink-connector/python/pyproject.toml`.
- **Tests**: `sink-connector/python/db_load/tests/test_mysql_resync.py`
  (offline; run by `.github/workflows/spec-governance.yml`).
- **Related**: spec 11.02 (the checksum that detects the divergence and proves
  the repair), spec 09.03 (the offset store the rewind writes to),
  `sink-connector/python/db_compare/`.

---

## 3. Operational Specification

### 3.1 Command line
```
ch-mysql-resync dump       --dump-base DIR [--stamp YYYYMMDD] --mysql-uri user@host:port --schemas S [S ...]
                           [--tables REGEX] [--threads N] [--consistent] [--mysqlsh PATH]
ch-mysql-resync patch      --dump-base DIR [--stamp YYYYMMDD] --schemas S [S ...] [--tables REGEX]
                           --ch-host HOST [--ch-port 9000] --ch-config client.xml
                           [--apply] [--drop-ch-only] [--restore-suffix _restore]
                           [--load-parallel 2] [--load-threads 4] [--skip-load]
                           [--canary-list FILE] [--canary-threshold 0.99] [--force]
                           [--loader-cmd CMD] [--loader-cwd DIR]
ch-mysql-resync rewind-sql --dump-base DIR [--stamp YYYYMMDD] --offset-table DB.TABLE
                           [--ch-host HOST --ch-config client.xml]
```
The MySQL password is read from `MYSQL_PWD` and handed to `mysqlsh` on stdin
(`--passwords-from-stdin`); it never appears on a command line. ClickHouse
credentials come from a clickhouse-client XML config (`<user>`, `<password>`),
the same file the loader consumes. `patch` is a **dry run unless `--apply`** is
given: every read runs, every write is printed with a `[DRY-RUN]` prefix.
Exit code of `patch` is non-zero when any table ended in `LOAD_FAILED`,
`COUNT_MISMATCH`, `REPLACED_VERIFY_FAIL` or a dump was incomplete.

### 3.2 `dump`
1. If `binlog_position_<stamp>.json` does not exist, run `SHOW MASTER STATUS`
   (plus `NOW()`, `@@hostname`, `UNIX_TIMESTAMP()`) and write it. The file is
   kept on re-runs: the position must predate the **first** table read of the
   dump set, and a re-run that only completes missing schemas must not move it.
   No row (binary logging off, missing `REPLICATION CLIENT`) is an error.
2. Per schema, `util.dumpTables(schema, <every BASE TABLE matching --tables>,
   <dump-base>/<schema>_<stamp>, {compression: zstd, chunking: true,
   bytesPerChunk: 256M, triggers: false, consistent: --consistent})`. Views,
   triggers, routines and events are excluded, so the dumping account needs only
   `SELECT` (+ `RELOAD`/`BACKUP_ADMIN` when `--consistent`). A schema whose
   directory already holds `@.done.json` is skipped; a directory without it is
   an error (never silently resumed or overwritten).
3. `--consistent` is off by default. A consistent dump holds one `REPEATABLE
   READ` transaction per thread for the whole dump (hours on a large schema;
   undo/history growth on the source). The recorded start position together
   with the rewind of §3.5 gives the same end state without the long
   transactions: every row changed after its table was read is replayed from
   the binlog with a newer `_version` and wins on `FINAL`.

### 3.3 `patch` — per schema `S` with scratch database `S<suffix>`
1. **Dump completeness**: `S_<stamp>/@.done.json` must exist, else the schema
   is skipped and reported (`DUMP_INCOMPLETE`).
2. **Schema drift** (per table, from the dump's `S@<table>.sql` versus
   `system.columns`): a source column absent from the live table
   (MATERIALIZED/ALIAS target columns do not count as present — they cannot
   receive a source value; `GENERATED` source columns are ignored — MySQL Shell
   does not dump them) marks the table `SCHEMA_DRIFT`: it is **skipped**, and an
   `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ... AFTER ...` suggestion with a
   best-effort type is written to `schema_drift_<ts>.sql` for a human to review
   and apply before re-running the table. Live-only columns are reported and
   kept (they receive their defaults). Tables missing in ClickHouse or not
   `ReplacingMergeTree` are skipped and reported. Live tables that are not in
   the dump are listed as commented-out `DROP TABLE` lines in the drop file;
   the tool never executes those.
3. **Scratch table**: `DROP TABLE IF EXISTS S<suffix>.t` (the scratch copy
   only) then `CREATE TABLE S<suffix>.t AS S.t`, so the structure, sorting key,
   partition key and settings are exactly the live table's — the precondition
   of `REPLACE PARTITION`. The loader is then run **once per table** on an
   isolated directory of hard links to that table's dump files
   (`_bytable/<table>/`; hard links because `zstd -d --stdout` refuses
   symbolic links), with `--mysqlshell --data_only --rmt_delete_support`, so
   one table's failure never aborts the others and each table has its own log.
   `--truncate_tables` is never passed to the loader: it truncates
   `<mysql_source_database>.<table>`, which is the **live** table.
4. **Reconcile**: rows in `S<suffix>.t` must equal the exact number of
   newline-terminated rows in the table's dump files (`zstd -dc | wc -l`;
   MySQL Shell escapes embedded newlines). A mismatch is `COUNT_MISMATCH` and
   the table is not replaced.
5. **Canary** (optional, `--canary-list`): for tables the operator knows to be
   unchanged (e.g. the ones whose last checksum matched), the scratch rows must
   hash-match the live rows: `countIf(r.h = l.h) / count()` over an `INNER
   JOIN` on the sorting key of `cityHash64(* EXCEPT (_version, is_deleted))`
   computed on both sides (live side with `FINAL`, `is_deleted = 0`). The
   ratio is accumulated over **every schema of the run** and evaluated
   **once, after phase 1 and before the first REPLACE of any schema**. A ratio
   below `--canary-threshold` (default 0.99) means the loader renders some type
   or time zone differently from the connector: no REPLACE is issued for any
   table, every loaded table is reported `CANARY_FAILED`, the scratch tables
   are kept for inspection and the exit code is non-zero (override only with
   `--force` once the difference is understood). Tables whose sorting key
   contains expressions are excluded from the canary.
6. **Replace**: for each partition id present in the scratch table's active
   parts, `ALTER TABLE S.t REPLACE PARTITION ID '<id>' FROM S<suffix>.t`. An
   unpartitioned table is the single `all` partition; replacing it from an
   **empty** scratch table yields an empty live table, which is the source
   state when the MySQL table is empty (a partitioned table with an empty
   dump replaces nothing and lists every live partition as replica-only).
7. **Replica-only partitions**: partitions present in the live table but not
   in the scratch table are never touched by default. Each is written to
   `drop_ch_only_<ts>.sql` as an `ALTER TABLE ... DROP PARTITION ID '<id>'`
   with a `DESTRUCTIVE:` comment stating its row count; they are executed only
   when the tool runs with `--drop-ch-only`. Whether the replica should keep
   history the source has purged is the operator's decision, not the tool's.
8. **Verify**: live `count()` after the replacements must equal the dump rows
   (plus the rows of replica-only partitions that were kept). `REPLACED_OK` /
   `REPLACED_VERIFY_FAIL` per table; a TSV report (`report_<ts>.tsv`) and a log
   are written under `<dump-base>/patch_<stamp>/`.

### 3.4 What `patch` does not do
It does not compare values table-wide (that is the checksum, spec 11.02, which
is re-run after the patch and is the only proof of "in sync"); it does not add
columns (drift is reported, a human applies DDL); it does not rebuild a table
whose sorting key no longer matches the source primary key (a source PK change
is unsupported by the connector and needs a table rebuild; see `AGENTS.md`);
it does not stop or start the connector.

### 3.5 `rewind-sql`
Prints the statement that moves **one** connector back to the captured
position: a new row for the same `offset_key` in the offset store
(`ReplacingMergeTree` keyed by `offset_key`, wall-clock `_version`, spec
09.03) whose `offset_val` is `{"ts_sec": <capture time>, "file": <file>,
"pos": <pos>, "row": 0, "server_id": <current server_id>, "event": 0}`. The
offset store is shared by every connector that writes to the cluster, so the
`INSERT ... SELECT` carries `WHERE offset_key = <key>`: the key comes from
`--offset-key`, or from the table itself only when it holds exactly one row;
zero or several candidates are an error, never a guess. Procedure printed
with it: stop that connector, run the INSERT, start the connector, watch the
replay, re-run the checksum. The source must still hold the binlog file
(`SHOW BINARY LOGS`) — binlog retention bounds how long after the dump the
rewind is possible.

### 3.6 Failure modes the design guards against
| Failure | Guard |
|---|---|
| Truncating/replacing the live table by mistake | only the scratch copy is dropped; `REPLACE PARTITION` is the sole write to a live table; the loader's `--truncate_tables` is never used |
| Replacing with a partial load | exact dump row count == scratch row count, per table, before any REPLACE |
| Loader renders a type/time zone differently from the connector | canary hash join on known-unchanged tables stops the run |
| Losing rows binlogged during the dump/patch window | binlog position captured before the first read; connector rewound to it |
| Dropping replica history the source purged | replica-only partitions listed, never dropped without `--drop-ch-only` |
| One bad table aborting a 400-table schema | per-table isolated loader runs and per-table status |
| A source column the replica lacks | `SCHEMA_DRIFT`: skipped with a DDL suggestion, never a silent partial row |

---

## 4. Invariants Preserved
- **Source of truth**: every write conforms the replica to the MySQL dump; no
  step interprets, keeps or "merges" replica-side values except the explicitly
  operator-owned replica-only partitions (I13 in `CONSTITUTION.md`).
- **Never break existing**: the live table stays queryable throughout; the
  only live-table operation is an atomic per-partition replacement of
  count-reconciled data.
- **Fail loudly**: every table ends in an explicit status; a count mismatch, a
  loader failure or a failed canary is a non-zero exit, never a warning.
- **Offsets are never moved backwards silently**: the rewind is an explicit,
  printed statement the operator runs while the connector is stopped.

---

## 5. Verification Criteria
All tests are offline (`sink-connector/python/db_load/tests/test_mysql_resync.py`,
run by `python3 -m unittest` in `.github/workflows/spec-governance.yml`):

- `TestParseMysqlDdl` — §3.3 step 2: columns come back in order with type,
  nullability and the `GENERATED` flag; index, unique, key and constraint
  lines are not columns.
- `TestTypeSuggestion` — §3.3 step 2: the MySQL→ClickHouse suggestions for the
  common integer/decimal/datetime/date/string/json types, nullable-wrapped.
- `TestDrift` — §3.3 step 2: no drift when every writable source column
  exists; a source-only column is reported while a `GENERATED` one is not; a
  MATERIALIZED target column does **not** satisfy a source column; a
  replica-only column is listed, not fatal; the DDL suggestion keeps the
  source position (`AFTER <previous column>`).
- `TestPlanReplace` — §3.3 steps 6–7: an unpartitioned table is one `REPLACE
  PARTITION ID 'all'`; an empty source empties the replica; both empty is a
  no-op; a partitioned table replaces exactly the dump-side partitions and
  lists (never drops) the replica-only ones; an empty dump of a partitioned
  table replaces nothing and lists everything.
- `TestSortingKey` — §3.3 step 5: plain columns are join keys; an expression
  key disables the canary join.
- `TestRewind` — §3.5: the new offset keeps the current `server_id`, points at
  the captured file/pos with `row`/`event` 0; the SQL inserts a newer row for
  the same key only (`record_insert_seq + 1`, `FROM ... FINAL`, `WHERE
  offset_key = <key>`); an unscoped rewind is refused; the offset row is
  selected unambiguously (given key must match one row; no key is accepted
  only for a single-row table).
- `TestCanaryGate` — §3.3 step 5, with the ClickHouse wrapper replaced by an
  offline fake whose canary join returns 0/10: `patch --apply` exits non-zero,
  issues no `REPLACE PARTITION`, and reports the table `CANARY_FAILED`;
  `--force` proceeds to the `REPLACE`.
- `TestDumpDirectory` — §3.3 steps 3–4: table discovery and data-file matching
  do not bleed across prefixes (`t` vs `t_other`); the isolated directory
  holds hard links (never symlinks); the exact row count counts
  newline-terminated rows with an escaped `\n` inside a field (requires
  `zstd`, skipped otherwise).
- `TestIdentifiers` — backtick quoting.
- Live acceptance (not automatable offline): `patch --apply` on a schema
  followed by the spec 11.02 checksum reporting equality for every patched
  table, and `REPLACE PARTITION ID 'all' FROM <empty scratch>` verified to empty
  an unpartitioned `ReplacingMergeTree` on `clickhouse local`.
