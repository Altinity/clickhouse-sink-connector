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
`COUNT_MISMATCH`, `CANARY_FAILED`, `REPLACED_VERIFY_FAIL` or a dump was
incomplete — and, with `--apply`, when any **selected** table was left
unrepaired (`SCHEMA_DRIFT`, `NOT_IN_CH`, `ENGINE_*`): the rewind advice is
printed only when every selected table reached `REPLACED_OK`.

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
   `--force` once the difference is understood). A canary table whose join
   returns **zero rows while its scratch copy is not empty** (no sorting-key
   overlap at all) is a failure in its own right, never "no evidence"; a
   canary list none of whose tables was loaded in the run is reported as a
   loud warning (the rendering is unverified for that run). Tables whose
   sorting key contains expressions are excluded from the canary.
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

These are design guards. Section 6 gives the Detection, Recovery and RTO of each failure the tool itself can meet.

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
  `--force` proceeds to the `REPLACE`; a canary join with zero rows on a
  non-empty scratch table fails closed the same way; a selected table
  skipped for `SCHEMA_DRIFT` makes an apply run fail (§3.1 exit code).
- `TestDdlLiterals` — §3.3 step 2: a `DEFAULT 'this is NOT NULL'` literal
  does not make the column non-nullable.
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

---

## 6. Failure Modes & Recovery
`ch-mysql-resync` is the declared repair of Invariant I15 item 2: moving an offset past a transaction is valid only when it is followed by this procedure. It changes a live table in exactly one place, the per-partition atomic `REPLACE PARTITION`, so every interruption leaves each partition either fully old or fully replaced. The scratch copies survive, and the procedure is resumable. Its time bound is its weak point: the unit of repair is a whole table and the rewind replays from the dump start, so the RTO is proportional to table size and dump duration, not to the one in-flight transaction.

- **FM-11.04-1 `patch --apply` interrupted in phase 2**
  - **Trigger**: the tool is killed, the ClickHouse connection is cut, or one `REPLACE PARTITION` fails. For example, ClickHouse refuses it because the connector applied a DDL to the live table after the scratch copy was created with `CREATE TABLE ... AS`, so the structures differ.
  - **Behaviour**: `ClickHouse._run()` raises `RuntimeError("clickhouse-client rc=...")`, which nothing in `cmd_patch()` catches. The process exits with a traceback and writes no `report_<ts>.tsv`. Partitions replaced before the failure hold the dump's state. The rest still hold the live state. The scratch tables `S<suffix>.t` are untouched: `REPLACE PARTITION ... FROM` copies parts and leaves the source table intact. The connector keeps writing. Rows it wrote into an already-replaced partition after the dump started are absent until the rewind replays them. They stay recoverable as long as the source keeps the binlog from the captured position.
  - **Detection**: non-zero exit with the traceback, immediately. The last `[APPLY] ALTER TABLE ... REPLACE PARTITION ...` line in `<dump-base>/patch_<stamp>/patch_<ts>.log` shows how far it got.
  - **Blast radius**: the table is transiently regressed to dump state in the replaced partitions. Nothing is lost that the rewind cannot replay. Other tables are unaffected.
  - **Recovery**: re-run the same command with `--skip-load` and the same `--stamp`. It re-reconciles the scratch counts, re-issues every `REPLACE PARTITION` (idempotent) and verifies `REPLACED_OK`. After a structure mismatch, re-run without `--skip-load` so the scratch copy is recreated from the current live structure. Then run `rewind-sql` (FM-11.04-4 for its limits).
  - **RTO**: the re-run is metadata-only per partition, about seconds per partition (unmeasured), plus the replay of the binlog from the captured position (proportional to the time since the dump started).
  - **Test**: `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestInterruptedPatch::test_interruption_leaves_scratch_tables_and_a_resume_completes`.

- **FM-11.04-2 `dump` interrupted half way**
  - **Trigger**: mysqlsh is killed, the source restarts, or the disk fills during `util.dumpTables`.
  - **Behaviour**: `cmd_dump()` logs `FAILED <schema> rc=...` and returns 1. On the next run the kept `binlog_position_<stamp>.json` is not re-captured (`binlog position already captured`), because it must predate the first read. A complete schema is skipped (`SKIP <schema>: already complete`). A directory without `@.done.json` is refused (`ERROR <schema>: <dir> exists but is incomplete -- move it away and rerun`), never resumed or overwritten.
  - **Detection**: exit 1 and the lines above, on the run that failed and on every re-run until the directory is moved.
  - **Blast radius**: nothing is written to ClickHouse by `dump`.
  - **Recovery**: move the incomplete `<schema>_<stamp>` directory away and re-run `dump` with the same `--stamp`. The kept position only lengthens the later replay.
  - **RTO**: re-dump of that schema (unmeasured, proportional to its size; mysqlsh `--threads`).
  - **Test**: `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestDumpRerun::test_rerun_keeps_the_position_skips_complete_and_refuses_incomplete`.

- **FM-11.04-3 A huge table: the unit of repair is the whole table**
  - **Trigger**: an unlogged change touched a few rows or one partition of a table of hundreds of GB.
  - **Behaviour**: `dump` exports every `BASE TABLE` in full (`util.dumpTables` with no `where` or partition filter). `patch` loads it all into the scratch table and counts it (`exact_dump_rows()`: `zstd -dc | wc -l` per file, serially). With a canary, it hash-joins the whole table (`canary_ratio()`). Every ClickHouse call has a 3600 s `max_execution_time`, and the subprocess timeout is 3660 s (`ClickHouse._run()`). A longer count or canary raises `RuntimeError` in phase 1, before any REPLACE. The rewind then replays everything binlogged since the dump started.
  - **Detection**: none for the duration. A call past 3600 s fails loudly (traceback, exit 1, nothing replaced).
  - **Blast radius**: the divergence persists for the whole dump, load and replay. The source carries the dump read load.
  - **Recovery**: none bounded in the tool. Narrow with `--tables`. There is no partition- or key-range-scoped resync.
  - **RTO**: unmeasured, proportional to table size (dump plus load plus count plus canary) plus replay of the dump window. Hours on large tables, far above the Invariant I15 target.
  - **Test**: GAP: a `patch` test with a partition-scoped dump asserting only the dumped partitions are replaced and no other live partition is listed as replica-only.
  - **DEFECT**: a repair that needs one partition costs a full-table reload (Invariant I15 item 3). The fix is to pass a `where`/`partitions` option through to `util.dumpTables` and restrict `plan_replace()` to the partitions in scope.

- **FM-11.04-4 Rewind after a source failover, or with a dump taken from another server**
  - **Trigger**: the MySQL primary fails over between `dump` and `rewind-sql`, or the dump was taken from a replica while the connector reads the primary.
  - **Behaviour**: `rewind_offset_json()` writes `{"ts_sec","file","pos","row":0,"server_id","event":0}` only. The captured `gtid_executed` is printed as a comment but not stored as `gtids`. The file and position name a binlog of the server the dump ran on. What the connector does with a file/pos from another server is Debezium's behaviour (not verified here).
  - **Detection**: none from the tool. The printed `source_host` comment can be compared by hand with the connector's `database.hostname`.
  - **Blast radius**: the replay starts at a wrong position or not at all. Rows binlogged during the dump window can be lost, or the connector fails at start.
  - **Recovery**: with the connector stopped (`sink-connector-client stop_replica`), set the position by GTID: `sink-connector-client update_binlog --gtid <gtid_executed from binlog_position_<stamp>.json>` (stored as `gtids` by `DebeziumOffsetStorage.updateBinLogInformation`). Then `start_replica` and re-run the checksum.
  - **RTO**: a connector restart plus replay of the dump window.
  - **Test**: `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestRewindAfterFailover::test_rewind_carries_the_captured_gtid_set` (skipped, DEFECT). The file/pos form is pinned by `...::test_rewind_points_at_the_captured_file_and_position`.
  - **DEFECT**: the rewind ignores the GTID set the dump recorded, so it is not failover-safe.

- **FM-11.04-5 The binlog at the captured position is purged before the rewind**
  - **Trigger**: the dump and patch take longer than the source's binlog retention (`binlog_expire_logs_seconds`).
  - **Behaviour**: nothing in `patch` or `rewind-sql` checks retention. `rewind-sql` only prints `check SHOW BINARY LOGS before starting the connector`. The connector then cannot read from the position.
  - **Detection**: at connector start, in the Debezium MySQL connector's binlog-availability error (library behaviour, not verified here). Until then, none.
  - **Blast radius**: the patched tables miss every change binlogged since the dump started, and the rewind cannot supply them.
  - **Recovery**: start over with a new `--stamp`: new position, new dump, new patch.
  - **RTO**: a second full dump and patch. Unbounded relative to the target.
  - **Test**: GAP: a `rewind-sql` test with a stubbed `SHOW BINARY LOGS` that lacks the captured file, expecting a refusal.
  - **DEFECT**: retention is checked by nobody, and a purge is discovered only after the patch has been applied.

- **FM-11.04-6 The rewind INSERT runs while the connector is running**
  - **Trigger**: the printed INSERT is executed without stopping that connector first.
  - **Behaviour**: the rewind row wins only until the connector's next offset flush inserts a newer row for the same `offset_key` (`_version` is `now64()` at insert). The rewind is silently undone.
  - **Detection**: none from the tool. After a correct restart, `show_replica_status` shows the captured file and position (spec 10.03). A position past it means the rewind was lost.
  - **Blast radius**: the patched tables miss the dump-window changes, which is a silent divergence.
  - **Recovery**: stop the connector (`sink-connector-client stop_replica`, or stop the service). Re-run the INSERT and check `SELECT offset_val FROM <offset table> FINAL WHERE offset_key = '<key>'` before starting.
  - **RTO**: a connector restart plus the replay of the dump window.
  - **Test**: GAP: an operator-procedure check is not unit-testable in this tool. A `rewind-sql --apply` mode that refuses while `/status` reports the connector running would make it testable.
  - **DEFECT**: the correctness of the rewind depends on an unenforced manual step.

- **FM-11.04-7 KeeperMap offset store**
  - **Trigger**: the connector's offset table is a KeeperMap table (spec 09.03).
  - **Behaviour**: `cmd_rewind_sql()` reads `SELECT offset_key, offset_val FROM <table> FINAL`, and the generated INSERT reads `FROM <table> FINAL`. A KeeperMap table rejects `FINAL` (stated in `DebeziumOffsetStorage.offsetValueQuery`).
  - **Detection**: loud. `RuntimeError: clickhouse-client rc=...` from `rewind-sql`, or the INSERT's error when run by hand.
  - **Blast radius**: nothing is changed.
  - **Recovery**: use `sink-connector-client update_binlog --binlog_file <file> --binlog_position <pos>` or `--gtid <set>` with the connector stopped.
  - **RTO**: minutes plus the replay of the dump window.
  - **Test**: GAP: a `rewind-sql` test against a KeeperMap-declared offset table.

- **FM-11.04-8 `patch` without a canary list: a loader rendering difference is installed silently**
  - **Trigger**: `--canary-list` is omitted and the loader renders some type or zone differently from the connector (spec 11.05 §6).
  - **Behaviour**: the count reconciliation passes and `REPLACE PARTITION` installs the differently rendered values. The warning `a canary list was given but none of its tables was loaded` fires only when a list was given.
  - **Detection**: none from `patch`. Only the post-patch checksum (spec 11.02) reports `Checksum difference`.
  - **Blast radius**: every patched table can diverge in the affected columns.
  - **Recovery**: re-run the checksum. On a difference, fix the loader rendering and re-run `patch --apply` from the same dump (with a canary list).
  - **RTO**: a second load and replace of the affected tables (proportional to their size).
  - **Test**: GAP: a `patch --apply` test without `--canary-list` expecting a warning that rendering is unverified.
  - **DEFECT**: the absence of any rendering check is silent.

- **FM-11.04-9 `patch` pointed at a replication-history database**
  - **Trigger**: `--schemas` names the history database of a mode-2 connector (spec 12.01).
  - **Behaviour**: SCD2 tables are `ReplacingMergeTree`, so they pass the engine check. `_valid_from`, `_valid_to` and `_operation` are reported as "ClickHouse-only columns (kept, filled with defaults)". The loaded rows get the sentinel `_valid_from`, the default `_version`, and fall into the `2100-01-01` partition. Replacing it would overwrite every open row and delete marker with those rows, while the closed-version partitions are listed as replica-only. A canary, if given, fails because the history columns are inside its hash.
  - **Detection**: `CANARY_FAILED` when a canary list is given. Otherwise none.
  - **Blast radius**: the current-state view of every patched SCD2 table is rewritten with corrupt history metadata.
  - **Recovery**: do not use `patch` on SCD2 tables. The history repair procedure is spec 12.03 §7.
  - **RTO**: see spec 12.03 §7.
  - **Test**: GAP: a `patch` test on a table carrying `_valid_to` expecting a refusal.
  - **DEFECT**: the tool does not refuse SCD2 tables.

Summary: 9 failure modes, 6 DEFECT, 6 GAP.
