# Spec 13.07: PostgreSQL-to-ClickHouse Verification and `auto_diff`

## 1. Executive Summary & Purpose
This spec describes, as built on 2.11.0, the PostgreSQL side of the
verification toolset. It covers:

- `ch-checksum`, the orchestrator that compares every table of one PostgreSQL
  schema with its ClickHouse replica;
- `ch-pg-checksum` and `ch-pg-count`, the standalone PostgreSQL-side helpers;
- `auto_diff`, the chunked XOR search that `ch-checksum` can run after a
  checksum mismatch to name the divergent rows;
- `postgres_checksum_runner.sh`, the cron wrapper.

Spec 11.02 specifies the value-level checksum protocol for the **MySQL**
pipeline and says nothing about PostgreSQL. This spec is the authoritative
behavioural description of the PostgreSQL tools. It reuses 11.02's vocabulary
(row string, four 32-bit word sums, `md5('cnt#a#b#c#d#')`) and its two rules: a
verifier must never report EQUAL when values differ, and it must not report
DIFFERENT for representation noise.

Key facts:

- **Packaged tree only.** These tools exist only in the packaged tree
  `sink-connector/python/ch_sink_tools/`. The legacy top-level tree
  (`sink-connector/python/db_compare/`, `sink-connector/python/db/`) has no
  PostgreSQL checksum, count or diff module and no copy of `_expressions.py` or
  `db/postgres.py`. So no legacy-vs-packaged divergence exists for these files
  (§3.20).
- **No unit tests.** No test in the repository imports any of these modules.
  Every behaviour below was established by reading every line and by offline
  repro scripts with mocked connections (§5).
- **Main hazards** (§6, §7). The run reports `PASS` and exits 0 in four
  situations:
  - the ClickHouse checksum query fails;
  - no column of the table is comparable;
  - the counts differ by less than the alert thresholds while the checksum was
    not compared;
  - a PostgreSQL column is missing from ClickHouse altogether.

  In `snapshot_mode`, the "REPEATABLE READ" snapshot is in fact the server's
  default isolation level, and the precomputed ClickHouse checksum is built from
  ClickHouse metadata rather than PostgreSQL metadata. Both produce false
  mismatches. The cron wrapper cannot start the tool at all.

---

## 2. Codebase Mapping on 2.11.0
- **Orchestrator** `ch-checksum` = `main()` in
  `sink-connector/python/ch_sink_tools/db_compare/top_level_postgres_checksum.py`
  (2,141 lines). It handles YAML parsing and validation, the LSN wait, the
  connector-status wait, ClickHouse catalog helpers, the ClickHouse count and
  checksum (`get_ch_checksum()`), the per-table comparison (`compare_table()`),
  the summary (`print_summary()`) and both run modes (`run_config()`).
- **PostgreSQL checksum** `ch-pg-checksum` = `main()` in
  `sink-connector/python/ch_sink_tools/db_compare/postgres_table_checksum.py`
  (798 lines). It holds the per-column expression builder
  (`build_pg_select_expression()`), the chunk query builders, the chunk divider
  and `get_postgres_table_checksum()`, which `ch-checksum` imports.
- **PostgreSQL count** `ch-pg-count` = `main()` in
  `sink-connector/python/ch_sink_tools/db_compare/postgres_table_count.py`
  (204 lines).
- **ClickHouse column expression** `_build_ch_col_expr()` in
  `sink-connector/python/ch_sink_tools/db_compare/_expressions.py` (62 lines).
  It is used only by the two modules above and `auto_diff`. Spec 13.06 shares
  ownership of the packaged `db_compare` helpers. This spec is authoritative
  for this function's PostgreSQL semantics.
- **Diff search** in
  `sink-connector/python/ch_sink_tools/db_compare/auto_diff.py` (1,149 lines).
  Its only entry point is `run_auto_diff_for_table()`, imported lazily by
  `run_config()`. It has no CLI.
- **Cron wrapper** in
  `sink-connector/python/ch_sink_tools/db_compare/scripts/postgres_checksum_runner.sh`
  (134 lines). It is shipped as package data
  (`sink-connector/python/pyproject.toml`, `[tool.setuptools.package-data]`).
- **Shared helpers** (owned by spec 13.02, cross-referenced here):
  - `sink-connector/python/ch_sink_tools/db/postgres.py`:
    `get_postgres_connection`, `execute_pg`, `get_tables`, `get_table_columns`,
    `get_table_pk`, `get_table_row_count`, `get_standby_lsn`,
    `pause_wal_replay`, `resume_wal_replay`, `is_in_recovery`,
    `is_wal_replay_paused`, `resolve_credentials_from_pgpass`.
  - `sink-connector/python/ch_sink_tools/db/clickhouse.py`:
    `clickhouse_connection`, `execute_sql`, `resolve_credentials_from_config`.
- **Packaging.** Entry points are declared in
  `sink-connector/python/pyproject.toml`:
  - `ch-checksum = ch_sink_tools.db_compare.top_level_postgres_checksum:main`
  - `ch-pg-checksum = ch_sink_tools.db_compare.postgres_table_checksum:main`
  - `ch-pg-count = ch_sink_tools.db_compare.postgres_table_count:main`

  `sink-connector/python/README.md` documents them.
- **Dockerfile.** `sink-connector/python/Dockerfile_clickhouse_checksum`
  copies only the legacy `db` and `db_compare` directories. Its entry point
  runs the legacy `clickhouse_table_checksum.py`. It cannot run any tool in
  this spec.
- **Tests.** None. The existing offline suite (227 passed, 5 skipped) is in
  `sink-connector/python/db_compare/tests/`, `sink-connector/python/db_load/tests/`,
  `sink-connector/python/db_dump/tests/` and `sink-connector/python/tests/`.
  None of it references these modules.

---

## 3. Contract (Behaviour as Built)

### 3.1 Tool inventory

| Tool | Module:function | Role | Opens | Writes |
|---|---|---|---|---|
| `ch-checksum` | `top_level_postgres_checksum:main` | compare every selected table PG vs CH; verdict + exit code | PG (1 or more), CH (1 or more), optional HTTP to the connector | stdout/log only; optional auto_diff files |
| `ch-pg-checksum` | `postgres_table_checksum:main` | print one PG-side checksum line per table | PG | optional `out.<table>.pg.txt` in CWD (debug) |
| `ch-pg-count` | `postgres_table_count:main` | print exact PG `COUNT(*)` per table | PG | nothing |
| `auto_diff` | `auto_diff:run_auto_diff_for_table` | locate divergent rows of a FAIL table | PG (shared), CH (own) | `checksum_diff_<table>_<ts>.json` or `.txt` |
| runner | `scripts/postgres_checksum_runner.sh` | cron wrapper with log tee | none | `<log dir>/system_<YYYYMMDD>.log` |

All tools are read-only on table data. `ch-checksum` has two side effects
outside the databases' data:

- `pg_wal_replay_pause()` and `pg_wal_replay_resume()` on a hot standby
  (`checksum.wal_replay_pause`);
- HTTP GET `/flush` and `/resume` on the sink connector
  (`checksum.flush_connector`).

### 3.2 `ch-checksum` command line (`top_level_postgres_checksum.py:2090-2137`)

| Flag | Type | Default | Effect | Honoured? |
|---|---|---|---|---|
| `--config`, `--config_file` | path | required | YAML file (§3.3) | yes |
| `--table` | str | none | compare one table. Becomes the PG regex `^<table>$` without escaping (`:1448-1449`, `:1966-1967`) and overrides `source.postgres.table_include_list` | yes. Metacharacters stay live (D-13.07-28) |
| `--no-checksum`, `--no_checksum` | flag | false | skip both checksums. Counts only | yes |
| `--verbose`, `-v`, `--debug` | flag | false | root logger and handler at DEBUG. `execute_pg` and `execute_sql` then log every SQL statement | yes |

The `--help` description advertises three tiers ("Tier-1 <100K rows", "Tier-2
100K-10M", "Tier-3 >10M"). The code forces Tier-1 for every table (§3.12;
D-13.07-26).

`main()` installs one `StreamHandler(sys.stdout)` with the format
`%(asctime)s - %(levelname)s - %(threadName)s - %(message)s`. Then it calls
`parse_config()`, `validate_config()` and `run_config()`. Importing the module
replaces the process-wide `logging` record factory: every record gets
`user="me"` (`:2074-2083`). The two sibling modules do the same.

### 3.3 YAML configuration schema
`parse_config()` (`:102-113`) uses `yaml.safe_load`. A missing file or a YAML
error is logged and exits 1. `validate_config()` (`:116-132`) only checks that
these five keys exist:

- `source.postgres.host`
- `clickhouse.host`
- `clickhouse.database`
- `connector.offset_db`
- `connector.offset_table`

An empty file loads as `None` and fails validation (exit 1). No key is
type-checked. A missing `source.postgres.database` passes validation and then
raises `KeyError` in `run_config()` (`:1192`; D-13.07-25).

| Key | Type | Default | Read at | Honoured? / effect |
|---|---|---|---|---|
| `source.postgres.host` | str | required | `:1190` | PG host. Replaced by `checksum.pg_wal_pause_host` in snapshot mode when WAL pause is enabled (`:1292-1301`) |
| `source.postgres.port` | int | 5432 | `:1191` | yes |
| `source.postgres.database` | str | (not validated) | `:1192` | `KeyError` if absent |
| `source.postgres.schema` | str | `public` | `:1193` | the **only** schema compared |
| `source.postgres.user` / `password` | str | none | `:1194-1195` | when either is missing, it is taken from the **first** entry of the pgpass file, whatever its host, port and database (spec 13.02) |
| `source.postgres.pgpass_file` | path | `~/.pgpass` | `:1198` | yes |
| `source.postgres.table_include_list` | str | none | `:1450`, `:1968` | a **PostgreSQL regex** (`~`) matched against bare table names, unanchored. It is not a Debezium-style `schema.table,...` list: such a list matches nothing and the run exits 1 with "No tables found" |
| `clickhouse.host` | str | required | `:1206` | yes |
| `clickhouse.port` | int | 9000 | `:1207` | native protocol |
| `clickhouse.database` | str | required | `:1208` | every PG table `t` is paired with `<database>.t` |
| `clickhouse.user` / `password` | str | `default` / `''` | `:1209-1210` | yes |
| `clickhouse.secure` | bool | false | `:1211` | `bool(value)`, so the string `"false"` means TLS **on** (D-13.07-25) |
| `clickhouse.config_file` | path | none | `:1213-1218` | read only when the password is empty. It replaces **both** user and password. A read error is a WARNING and the run continues with an empty password |
| `connector.offset_db` | str | required (default `altinity_sink_connector` if the validator were bypassed) | `:1221` | database of the offset table and of the replica-status view |
| `connector.offset_table` | str | required, may be `''` | `:1222` | `''` skips every catch-up wait (WARNING `LSN wait skipped`) |
| `checksum.snapshot_mode` | bool | false | `:1240` | selects snapshot mode (§3.6) or per-table mode (§3.5) |
| `checksum.wal_replay_pause` | bool | false | `:1265` | snapshot mode only. Pauses standby WAL replay |
| `checksum.pg_wal_pause_host` / `pg_wal_pause_port` | str / int | none / `source.postgres.port` | `:1289-1290` | snapshot mode with WAL pause only. All PG connections go to this host |
| `checksum.flush_connector.enabled` | bool | false | `:1309` | snapshot mode only |
| `checksum.flush_connector.flush_url` | URL | `http://localhost:7008/flush` | `:1311` | GET |
| `checksum.flush_connector.resume_url` | URL | `http://localhost:7008/resume` | `:1313` | GET |
| `checksum.flush_connector.timeout` | int s | 30 | `:1315` | `urlopen` timeout |
| `checksum.flush_connector.stabilize_wait` | int s | 5 | `:1316` | sleep after a successful flush |
| `checksum.medium_table_threshold` (alias `tier1_max_rows`) | int | 100000 | `:1225` | parsed and passed down, **never used** (Tier-1 is forced) |
| `checksum.large_table_threshold` (alias `tier2_max_rows`) | int | 10000000 | `:1226` | parsed, **never used** |
| `checksum.chunk_size` | int | 100000 | `:1227` | PK range width per PG chunk query. No validation: `<= 0` loops forever (D-13.07-14) |
| `checksum.threads` | int | 4 | `:1228` | per-table mode only: tables in parallel. Snapshot Phase A is fixed at 4 workers (`:1777`) |
| `checksum.threads_per_table` | int | 1 | `:1229` | per-table mode only: PG chunk threads. Snapshot mode is serial |
| `checksum.lsn_wait_timeout_seconds` | int | 300 | `:1230` | LSN wait bound. In WAL-pause mode the connector-status wait uses `max(this, 120)` |
| `checksum.lsn_wait_poll_interval_seconds` | int | 10 | `:1231` | LSN wait only. The connector-status wait polls every 5 s |
| `checksum.alert_count_delta_pct` | float | 0.0001 | `:1232` | count FAIL threshold (fraction, not percent) |
| `checksum.alert_count_delta_abs` | int | 100 | `:1233` | count FAIL threshold (rows) |
| `checksum.skip_tables` | list | `[]` | `:1234` | exact bare names removed after discovery |
| `checksum.include_floating_point_columns` | bool | false | `:1235` | opt-in for `real` and `double precision` |
| `checksum.include_json_columns` | bool | false | `:1236` | opt-in for `json` and `jsonb` |
| `checksum.exclude_ch_columns` | list | `_version, is_deleted, _is_deleted, __is_deleted` | `:1237` | **replaces** the default set. Only filters `system.columns` reads; the queries still hard-code `is_deleted = 0` (D-13.07-11) |
| `checksum.skip_table_columns` (preferred) or `checksum.skip_columns` | map table → list | `{}` | `:1253-1254` | per-table columns removed from the checksum (not from the count). The comment's "list form = global exclusions" is **ignored**: a list becomes `{}` (D-13.07-24) |
| `checksum.lsn_encoding` | str | — | not read | docstring only (`:1180-1182`) |
| `checksum.auto_diff.*` | map | see §3.17 | `:1850-1851`, `auto_diff.py:936-943` | snapshot mode only. Ignored in per-table mode |

### 3.4 Credentials and connections
- **PostgreSQL.** `get_postgres_connection()` (`db/postgres.py:221-236`)
  connects with `connect_timeout=20` and `options='-c statement_timeout=0'`
  (no statement bound) and sets `autocommit = True`. The pgpass fallback is
  described in §3.3.
- **ClickHouse.** `clickhouse_connection()` (`db/clickhouse.py:9-19`) opens a
  `clickhouse_driver` DB-API connection with `connect_timeout=20`. Queries set
  no `max_execution_time`.
- **Where secrets go.** Passwords are never logged by these modules. The
  standalone tools accept `--pg_password` on the command line (visible in the
  process list) and log a WARNING. Spec 13.02 owns the ClickHouse config-file
  reader, which logs the password at DEBUG.

### 3.5 Run orchestration, per-table mode (`snapshot_mode: false`; the code calls it "legacy mode") (`:1957-2056`)
1. Open one PG connection. Read `get_standby_lsn()`:
   `pg_last_wal_replay_lsn()::text`, or `pg_current_wal_lsn()::text` when the
   former is NULL (primary). It returns `hi*2^32 + lo`. Discover tables (§3.8)
   and close the connection. An empty table list logs ERROR and exits 1.
2. Open a CH connection. If `offset_table` is set and the LSN is greater than
   0, run `wait_for_ch_lsn()` (§3.7). A timeout is a WARNING and the run
   continues. Close the connection.
3. Run `compare_table()` for every table on a `ThreadPoolExecutor(threads)`.
   Each call opens its own PG and CH connections. The PG checksum opens one
   more PG connection per chunk on a `ThreadPoolExecutor(threads_per_table)`.
   Results are collected in `as_completed` order.
4. Print the summary and exit (§3.12). No `auto_diff`, no WAL pause and no
   connector flush happen in this mode.

The mode has no cross-table or cross-statement consistency. The PG count, the
PG chunk queries and the CH queries of one table run at different moments, in
autocommit.

### 3.6 Run orchestration, snapshot mode (`snapshot_mode: true`) (`:1261-1952`)
Steps as executed:

0. **WAL pause** (if `wal_replay_pause`, `:1379-1408`). Open a separate
   control connection (autocommit). If `pg_is_in_recovery()` is false, log a
   WARNING "NOT a standby" and disable the pause. If
   `pg_is_wal_replay_paused()` is already true, log a WARNING "paused by
   another process" and disable the pause. Otherwise run
   `SELECT pg_wal_replay_pause()`, then `SELECT pg_is_wal_replay_paused()`;
   `RuntimeError` if that returns false. Log the frozen LSN.
   `wal_was_paused_by_us = True`.
1. **Snapshot connection** (`:1420-1432`). Open a new connection, set
   `autocommit = False`, execute `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ`.
   A failure closes the connection and exits 1. **As built this does not
   produce a REPEATABLE READ transaction** (§3.7.3; D-13.07-5).
2. **Snapshot LSN** (`:1438`). `get_standby_lsn()` on the snapshot connection.
3. **Discover tables** on the snapshot connection (`:1447-1466`). An empty
   list rolls back, closes and exits 1.
4. **Catch-up** (`:1474-1534`). Open a CH connection. If `offset_table` is set
   and the LSN is greater than 0:
   - if the WAL was paused by this run: `_wait_for_connector_caught_up()`
     (§3.7.2);
   - otherwise: `wait_for_ch_lsn()` with tolerance 0 (§3.7.1).

   Close the connection.
   - **4b. Flush** (if `flush_connector.enabled`, `:1546-1573`). GET
     `flush_url`. On success set `connector_flushed = True` and sleep
     `stabilize_wait`. Any error is logged and the run continues **without**
     a flush.
5. **Pre-fetch PG metadata** (`:1589-1599`). Run `get_table_columns()` for
   every table on the snapshot connection, serially. An error stores `[]` for
   that table.
6. **Phase A** (`:1607-1794`). `_query_ch_for_table()` runs per table on a
   `ThreadPoolExecutor(max_workers=4)`, each call with its own CH connection.
   It returns `{'exists', 'count', 'tier3', 'checksum', 'checksum_tier'}`:
   - `ch_table_exists`. Missing table: `exists False`, count -1.
   - `get_ch_count` (§3.12).
   - `get_ch_columns_meta`: names and types from `system.columns`, ClickHouse
     order, internal columns excluded.
   - The first element of `system.tables.sorting_key`, split on `,` and
     stripped of `"`.
   - `get_ch_tier3_metrics`: an unused full `FINAL` scan.
   - When the count is greater than 0 and checksums are on: tier 1, filter
     the columns (§3.9), then `get_ch_checksum()` over the **ClickHouse**
     column list with **ClickHouse** column metadata (`:1734`, `:1749-1755`;
     D-13.07-6).

   Any exception returns count -1 and checksum None.
7. **Phase B** (`:1807-1843`). Run `compare_table()` serially on the snapshot
   connection for each table, passing that table's Phase A result as
   `ch_precomputed`. The precomputed checksum is used when it is not None and
   its tier equals 1, which is always. Otherwise CH is queried on the fly
   (§3.11).
8. **auto_diff** (if `auto_diff.enabled`, `:1850-1918`). For each result with
   `status == 'FAIL' and checksum_match is False`, call
   `run_auto_diff_for_table()` with the snapshot connection, as long as the
   time since the first diff started is at most `auto_diff.timeout_seconds`.
   Errors are logged per table. Nothing feeds back into the verdict.
9. Commit and close the snapshot connection (`:1923-1930`). A commit error is
   a WARNING.
10. `finally` (`:1932-1952`):
    - `_ensure_wal_resumed()`: `SELECT pg_wal_replay_resume()` plus verify.
    - Close the control connection.
    - `_ensure_connector_resumed()`: GET `resume_url`.
    - Restore the signal handlers, if they were installed.

    Resume failures are logged as `CRITICAL ... MANUAL INTERVENTION REQUIRED`
    at ERROR level and are **not** reflected in the exit code (D-13.07-9).

**Signals** (`:1352-1368`). When WAL pause or flush is enabled at start,
SIGTERM and SIGINT handlers run both resume helpers. Each handler then chains
to the previous handler if it is callable and calls `sys.exit(128+signum)`.
For SIGINT the previous handler is Python's `default_int_handler`, which
raises `KeyboardInterrupt` first. If step 0 disables the WAL pause and flush is
off, the condition at `:1950` is false and the handlers are not restored. The
process exits right after, so this is cosmetic (D-13.07-29). SIGKILL leaves
the WAL replay and the connector paused (FM-13.07-9).

### 3.7 Consistency: how "the replica caught up" is established

#### 3.7.1 LSN wait (`wait_for_ch_lsn`, `:139-230`)
The function polls every `poll_interval` seconds until
`time.time() >= deadline`:

```sql
SELECT max(toInt64OrZero(JSONExtractRaw(offset_val, 'lsn'))) AS ch_lsn
FROM <offset_db>.<offset_table> FINAL
WHERE isValidJSON(offset_val) AND JSONHas(offset_val, 'lsn')
```

- **Success.** Returns True as soon as `ch_lsn >= target - tolerance_bytes`.
  Tolerance is always 0 as called.
- **Errors and empty results** are logged as WARNINGs and polling goes on.
- **Timeout.** Logs `Running checksum anyway — results may show false
  positives` and returns False. Both callers continue regardless.
- **What it compares.** The target is the standby's **physical** replay LSN
  (or the primary's current LSN). The connector's offset is the **logical**
  position it has stored, and the maximum is taken over **all** rows of the
  offset table. That can include rows of other connectors that share the
  table.
- **When the target can stay out of reach.** WAL that the connector never
  consumes (other databases of the cluster, unpublished tables, vacuum,
  checkpoints) keeps the physical LSN ahead. The code itself documents a
  "permanent ~82 MB gap" for that reason, but uses it only to skip this wait
  in WAL-pause mode (`:1483-1488`). In the normal path such a gap makes every
  run wait the full timeout and then compare anyway (D-13.07-17).
- **Encoding assumption.** The JSON `lsn` must be a JSON number. A quoted
  string extracts as `"..."` and converts to 0. A neighbouring helper,
  `get_current_lsn()` (not used here), documents the opposite encoding (low 32
  bits only; spec 13.02).

#### 3.7.2 Connector-status wait (WAL-pause mode, `_wait_for_connector_caught_up`, `:264-380`)
1. Derive the view name (`_derive_replica_status_view`, `:237-261`):
   `replica_source_info_<s>` gives `<offset_db>.show_replica_status_<s>`.
   Otherwise every substring `replica_source_info` is replaced by
   `show_replica_status`. A table named `offsets` therefore maps to itself
   (repro P9).
2. Probe the view with `SELECT 1 FROM <view> LIMIT 0`. On failure, sleep
   `settle_time` (30 s) and return True.
3. Poll `SELECT Seconds_Behind_Source FROM <view> LIMIT 1` (no `ORDER BY`)
   every 5 s until `max(lsn_wait_timeout, 120)` seconds have passed. Return
   True on 0. On timeout, return **True** as well: the function never returns
   False.

`Seconds_Behind_Source` measures the connector against **its** source, which
is where the replication slot lives. When the slot is on the primary, the
connector keeps consuming changes made after the standby froze. A zero lag
then means ClickHouse is **ahead of** the frozen PostgreSQL snapshot. The
flush in step 4b only stops writes that arrive after it. The docstring's
"Correctness guarantee" (`:1169-1174`) therefore does not hold, and active
tables give false mismatches (D-13.07-10).

#### 3.7.3 Transaction isolation on the PostgreSQL side
The connection arrives with `autocommit = True`. `run_config()` sets
`autocommit = False` and then runs `cursor.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ")`.
psycopg2 behaviour, documented for `connection.autocommit`: in non-autocommit
mode the driver itself sends `BEGIN` before the first command of a
transaction. PostgreSQL's documented behaviour for `BEGIN` inside a
transaction block: it raises a WARNING and leaves the transaction state
unchanged. The isolation level therefore stays `default_transaction_isolation`,
normally READ COMMITTED, and each statement takes a fresh snapshot.

Repro S4 shows the statement order on the connection: `set autocommit=False`,
then `execute 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ'`. The
implicit driver `BEGIN` cannot be observed without a server.

Consequences:

- With WAL replay paused, the standby is frozen, so READ COMMITTED still reads
  one state. The isolation defect is then masked.
- Without the pause, PG data moves during Phase B, which the code itself
  estimates at about 15 minutes. The logged "snapshot LSN" is not the data's
  LSN. The PG count and checksum of one table can see different states.
  `auto_diff` reads a later state than the checksum (D-13.07-5).

#### 3.7.4 Summary of the modes

| Mode | PG consistency | CH vs PG point in time | Writes during compare |
|---|---|---|---|
| per-table | none (autocommit per statement) | CH waited to ≥ LSN read **before** discovery | continue on both sides |
| snapshot, no WAL pause, no flush | per statement (D-13.07-5) | CH ≥ snapshot LSN, then keeps moving | continue on both sides |
| snapshot, flush only | per statement | CH frozen at flush, PG moving | PG only |
| snapshot, WAL pause + flush | frozen standby | CH ≥ frozen point (possibly ahead, §3.7.2) | none after flush |

### 3.8 Table discovery and pairing
`get_tables()` (`db/postgres.py:296-318`; spec 13.02) issues:

```sql
SELECT table_name FROM information_schema.tables
WHERE table_schema = '<schema>' AND table_type = 'BASE TABLE'
  AND table_name ~ '<include_regex>'     -- only if a filter is given
ORDER BY table_name
```

- **Quoting.** The regex and the schema are interpolated without escaping; a
  `'` breaks the statement (repro P8). `skip_tables` is then applied by exact
  name.
- **Privilege filter.** `information_schema.tables` (and `.columns`) shows only
  objects the current user has a privilege on. Tables the checksum user cannot
  read are silently absent: they appear in no count and no warning, and the
  final line still says "all N tables match" (D-13.07-4).
- **Partitions.** Partitioned parents and their partitions are both
  `BASE TABLE` (PostgreSQL behaviour, not verified offline). Both are compared,
  and whichever has no ClickHouse twin is reported MISSING.
- **Pairing.** PG `<schema>.<t>` is compared with CH `<clickhouse.database>.<t>`,
  same bare name, case-sensitive. There is no rename map, no schema-to-database
  map and only one schema per run.
- **Missing twin.** A CH table that does not exist gives status MISSING. CH
  tables with no PG twin are never examined.

### 3.9 Column selection
**Phase B and per-table mode** (`compare_table`, `:838-858`, `:959-988`):

1. Take the PG columns from `get_table_columns()` in `ordinal_position` order.
   Each has `column_name`, `pg_type` (= `information_schema.columns.data_type`),
   `udt_name` and `nullable`.
2. Take the CH column names from `system.columns` in `position` order, minus
   `exclude_ch_columns` (`get_ch_columns`, `:387-406`). A query error returns
   `[]`.
3. **Shared columns** are the PG columns present in CH and not in the table's
   skip list, kept in PG order. A PG column missing from CH is dropped. The only
   trace is the INFO counts `pg_cols=` and `shared_cols=` (D-13.07-3). A CH
   column missing from PG is ignored.
4. **PG side.** `build_pg_select_expression()` drops these types:
   - float (`real`, `float4`, `double precision`, `float8`, `float`) unless
     opted in;
   - `json` and `jsonb` unless opted in;
   - arrays (`udt_name` starting with `_`, a type ending in `[]` or containing
     `array`; `information_schema` reports `ARRAY`);
   - `bytea`;
   - the six built-in range types (by type or `udt_name`).

   Each exclusion logs an INFO line.
5. **CH side.** The same filters are re-implemented on the PG types
   (`:959-981`), producing `pg_included_cols` in PG order and passing **PG**
   metadata to `_build_ch_col_expr`. Both sides therefore agree on the list,
   the order and the nullability.

**Phase A, snapshot mode** (`_query_ch_for_table`, `:1681-1734`). It iterates
the **ClickHouse** columns in ClickHouse order. It applies the skip list and
the type filters using the real PG type when one exists (CH-only columns fall
back to the inferred type). But it appends the **ClickHouse** metadata
dictionary to `included_meta` (`:1734`). The CH expression is therefore built
from:

- the ClickHouse order;
- `_ch_type_to_pg_type(ch_type)` (`:450-510`) instead of the PG type;
- `nullable = ch_type.startswith('Nullable(')` (`:435`) instead of PG
  nullability.

Columns that exist only in ClickHouse are included: ALIAS columns added by
type overrides (spec 13.05), MATERIALIZED columns, and columns whose PG twin
was dropped. Repro S1 builds a table whose PG and Phase A expressions differ in
order (`flag` before `amount`), Bool rendering (`toString("flag")` against
`CASE ... '1'/'0'`), time zone (`toTimeZone(... 'UTC')` against the naive PG
rendering) and an extra CH-only column (D-13.07-6).

`_ch_type_to_pg_type` has an inference table (repro P2):

| ClickHouse type | inferred `pg_type` | consequence in Phase A |
|---|---|---|
| `UInt8` | `boolean` | `if(c = 0,'0','1')`. Any genuine `UInt8` column is rendered as a boolean |
| `Bool` | `text` | `toString(c)` gives `true`/`false`, but PG gives `1`/`0` |
| `DateTime64(p, '<any tz>')` | `timestamp with time zone` | `toString(toTimeZone(c,'UTC'))`, also for a PG *timestamp without time zone* whose CH column carries a zone annotation (the dumper's `pg_type_to_ch` adds one when it knows the server zone) |
| `DateTime64(p)`, `DateTime` | `timestamp without time zone` | `toString(c)` |
| `Array(...)`, `Point` | `integer` (the `'int'` substring test runs before the array test) | `toString(c)` |
| anything else | `text`, `numeric`, `uuid`, `date`, `double precision` | `toString(c)` |

### 3.10 Per-type canonicalisation
Row strings must be byte-identical on both sides for equal values. The columns
below are: the PG `data_type`, the PG expression (`build_pg_select_expression`,
`postgres_table_checksum.py:42-155`), the CH expression built from the PG type
(`_build_ch_col_expr`, `_expressions.py:11-62`, used in Phase B and per-table
mode), and whether they agree. Phase A deviations are covered in §3.9.

For a nullable column, both sides wrap the expression in `coalesce(..., '')`
and add the column's NULL bit (§3.11).

| PG type | PG expression | CH expression (from PG type) | Agreement as built |
|---|---|---|---|
| `boolean` | `CASE WHEN c THEN '1' ELSE '0' END`. Nullable: `CASE WHEN c IS NULL THEN NULL WHEN c ...` | `if(c = 0,'0','1')`. Nullable: `if(isNull(c), NULL, ...)` | yes for CH `UInt8` and `Bool`. A CH `String` column makes `c = 0` a type error, so the checksum is None and the table is PASSed on count (D-13.07-1) |
| `timestamp with time zone` | `to_char(c AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')`, always 6 fraction digits, UTC wall clock | `toString(toTimeZone(c,'UTC'))` | yes **only** for CH `DateTime64(6, ...)`. `DateTime64(3)` prints 3 digits and `DateTime` prints none, so every non-NULL row mismatches. Values outside the `DateTime64` range are clamped by ClickHouse. PG `infinity` makes `to_char` return NULL (PostgreSQL behaviour, not verified offline) |
| `timestamp without time zone` | **`to_char(c AT TIME ZONE 'UTC', ...)`**: the test `'time zone' in type` at `:105` also matches `without time zone`. `AT TIME ZONE 'UTC'` turns the naive value into a `timestamptz` read as UTC, and `to_char` then renders it in the **session TimeZone** | `toString(c)`, with no conversion (the "Bug 84.2-1 fix" was applied only on the CH side) | only when the PG session TimeZone is UTC. Otherwise every non-NULL row is shifted by the session offset (repro P1; D-13.07-7). Also requires that the CH column's zone, or the server's when unannotated, renders the stored instant as the original wall clock |
| `date` | `to_char(c,'YYYY-MM-DD')` | `toString(c)` | yes inside the `Date32` range (1900–2299). Values outside it are clamped by CH and mismatch |
| `time without time zone` | `to_char(c,'HH24:MI:SS.US')` (resolves through the `time`→`interval` cast) | `toString(c)` | only if CH stores `HH:MM:SS.ffffff` text. The dumper maps `time` to `String`; the CDC value format is set by the connector, not here |
| `time with time zone` | `to_char(c,'HH24:MI:SS.US')` | `toString(c)` | whether PostgreSQL resolves `to_char(timetz, text)` is **not verified** offline. If it does not, the PG query fails and the table is ERROR (and in snapshot mode the transaction is aborted, D-13.07-13). The offset is dropped either way |
| `numeric`, `decimal` | `c::text`, which keeps the declared scale (`1.50`) | `toString(c)`. ClickHouse prints `Decimal` **without trailing zeros** (`1.5`), the reason 11.02 §3.3 renders MySQL decimals with `toDecimalString(col, s)` | **no** for any value with trailing fractional zeros (D-13.07-8). Unconstrained `numeric` maps to CH `String` in the dumper and agrees only if the writer stored PG's text |
| `integer`, `bigint`, `smallint` | `c::text` | `toString(c)` | yes |
| `real`, `double precision` | excluded. Opt-in: `c::text` (shortest round-trip on PG ≥ 12) | opt-in: `toString(c)` | opt-in: differs for exponent notation (`1e+20` vs `100000000000000000000`), `NaN` vs `nan`, `Infinity` vs `inf` |
| `text`, `character varying` | `c::text` | `toString(c)` | yes |
| `character(n)` | `c::text`, which **strips trailing blanks** | `toString(c)` (stored as sent) | no for padded values if the writer kept the padding (D-13.07-8) |
| `uuid` | `lower(c::text)` | `toString(c)` | yes (CH `UUID` and Debezium both use canonical lowercase) |
| `json`, `jsonb` | excluded. Opt-in: `c::text` | opt-in: `c` (raw `String`) | requires CH to hold PG's text output byte for byte. `jsonb` output orders keys by length then bytes (not alphabetically, as the comment at `_expressions.py:52-55` claims) and uses `", "` and `": "` separators |
| `bytea` | excluded (Debezium base64 vs dump hex) | — | not compared |
| arrays (`ARRAY`) | excluded ("Bug 84.2-2"). The array branch at `:136-137` is dead | — | not compared |
| range types | excluded | — | not compared |
| `interval` | `c::text` (`1 day 02:00:00`, depends on `IntervalStyle`) | `toString(c)` | no: CDC delivers microseconds or ISO 8601 (D-13.07-8) |
| `money`, `bit`, `bit varying`, `inet`, `cidr`, `macaddr`, `xml`, geometric, `tsvector`, enums (`USER-DEFINED`) | `c::text` | `toString(c)` | agree only if the writer stored PG's text output (`money` depends on `lc_monetary`; `bit` is delivered as bytes by CDC) |

**NULL vs empty.** Nullable columns get `coalesce(expr,'')` plus a NULL bit, so
NULL and `''` differ. NOT NULL columns get neither. PostgreSQL `concat_ws`
skips NULL arguments, so a NOT NULL column whose expression yields NULL (for
example `to_char` of `infinity`) **removes** its field from the PG row string.

**Separator.** `chr(1)` (PG) and `char(1)` (CH). A value containing `\x01` can
shift a field boundary between adjacent columns. This is the same structural
blind spot as 11.02 §6 item 1 with a rarer byte. It is a design choice, not
registered as a defect.

### 3.11 Row string, chunking and aggregate

**PG chunk query** (Tier-1, `build_tier1_chunk_query`, `:162-250`):

```sql
SELECT count(*) AS cnt,
       sum(('x' || substring(row_hash,  1, 8))::bit(32)::int8) AS a,
       sum(('x' || substring(row_hash,  9, 8))::bit(32)::int8) AS b,
       sum(('x' || substring(row_hash, 17, 8))::bit(32)::int8) AS c,
       sum(('x' || substring(row_hash, 25, 8))::bit(32)::int8) AS d
FROM (SELECT md5(concat_ws(chr(1), <e1>, ..., <en>)
                 || chr(1) || (CASE WHEN "<n1>" IS NULL THEN '1' ELSE '0' END) || ...) AS row_hash
      FROM "<schema>"."<table>"
      WHERE 1=1 [AND <where>] [AND "<pk>" BETWEEN <lo> AND <hi>]) t
```

- The NULL-bit suffix is present only when at least one included column is
  nullable.
- `build_tier1_chunk_query` returns `None` when no column survives.
  `get_postgres_table_checksum` then skips every chunk and returns the
  **empty-table digest** `md5('0#0#0#0#0#')` whatever the row count
  (repro P7).
- **Chunking** (`get_postgres_table_checksum`, `:431-449`;
  `divide_table_into_chunks`, `:301-328`):
  - The chunk key is the first PK column whose `pg_type` contains `int`,
    `serial`, `bigint` or `smallint`. This is a substring test, so `interval`
    qualifies (repro P6, `TypeError`; D-13.07-15). PK columns absent from the
    filtered column list cannot be the chunk key.
  - Without a chunk key there is one chunk and no range filter.
  - Otherwise `SELECT min(pk), max(pk)` gives contiguous `[lo, lo+chunk_size-1]`
    ranges up to the maximum. That is `ceil((max-min+1)/chunk_size)` queries
    even when the keys are sparse: 100,000 queries for keys up to 10^10, and
    4.6·10^13 for 2^62-style keys (repro P5). `chunk_size <= 0` never advances
    and exhausts memory (repro P5; D-13.07-14).
- **Concurrency.** Snapshot mode runs the chunks serially on the snapshot
  connection. Per-table mode runs them on `threads_per_table` threads, one
  connection per chunk; the first chunk exception is re-raised.
- **Accumulation.** The five values are summed in Python as exact integers,
  then `md5('cnt#a#b#c#d#')`. The PG `sum()` of `int8` returns `numeric`, so
  it is exact too. An empty chunk (`a..d` NULL) contributes zeros.

**CH query** (`get_ch_checksum`, `:640-744`):

```sql
SELECT count(*) AS cnt,
       coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash,  1, 8))))), 0) AS a,
       ... b, c, d (offsets 9, 17, 25)
FROM (SELECT hex(MD5(concat_ws(char(1), <e1>, ..., <en>) || char(1) || (case when "<n1>" is null then '1' else '0' end) || ...)) AS hash
      FROM `<db>`.`<table>` FINAL
      WHERE is_deleted = 0) t
SETTINGS do_not_merge_across_partitions_select_final = 1,
         max_memory_usage = 80000000000
```

- **Word equivalence.** The words are identical to the PG words: an unsigned
  32-bit value from 8 hex digits (repro P3).
- **Overflow.** ClickHouse `sum(Int64)` wraps modulo 2^64 while PG is exact.
  For tables beyond about 2^32 rows (mean word 2^31) the digests differ
  (repro P3; D-13.07-16).
- **Empty or missing.** An empty result or `cnt = 0` gives the empty-table
  digest.
- **Errors.** No column, or any exception, returns `None` (`:657-660`,
  `:742-744`).
- **Partitions.** `FINAL` collapses versions **within each partition only**,
  always. 11.02 §3.7 removed that setting from the MySQL path for exactly this
  reason (finding C16). Here a row whose partition-key value changed survives
  twice (D-13.07-11).
- **`is_deleted`.** `WHERE is_deleted = 0` is hard-coded even though
  `exclude_ch_columns` anticipates `_is_deleted` and `__is_deleted`. A table
  without `is_deleted` fails every count and checksum query (D-13.07-11).
- **Tier-2** (`toString("<pk>")`, PK-list MD5) and its PG twin
  (`build_tier2_chunk_query`, which wraps an `ORDER BY` in the subquery) are
  reachable only from `ch-pg-checksum --tier 2`, never from `ch-checksum`.

### 3.12 Counts, status, summary, exit codes
**Counts.**

- PG: `SELECT COUNT(*) AS cnt FROM "<schema>"."<table>"` (`:909-911`).
- CH: `get_ch_count`, `:530-547`:

  ```sql
  SELECT count() FROM `<db>`.`<t>` FINAL WHERE is_deleted = 0
  SETTINGS do_not_merge_across_partitions_select_final = 1
  ```

  An error gives -1.
- Before that, `compare_table` reads `pg_stat_user_tables.n_live_tup`. If it
  is missing, it runs an extra full `COUNT(*)`. The value is only logged
  (`:824-829`).

**Tier.** Hard-coded `tier = 1` (`:833`, `:1668`). The Tier-3 branch
(`:866-905`, `get_pg_tier3_metrics`, max-PK comparison) is dead code. Phase A
still runs the Tier-3 CH scan for every table (D-13.07-26).

**Status** (`:919-1030`):

```text
count_delta     = ch_cnt - pg_cnt
count_delta_pct = |count_delta| / pg_cnt   (0.0 when pg_cnt == 0)
count_fail      = |count_delta| > alert_count_delta_abs  or  count_delta_pct > alert_count_delta_pct
checksum_match  = (pg == ch) if both checksums are not None else None
status = FAIL  if checksum_match is False or count_fail
         WARN  elif count_delta != 0            (the "or checksum_match is False" there is dead)
         PASS  otherwise
```

Other statuses: `MISSING` when the CH table does not exist; `ERROR` on any
exception in `compare_table`, including connection failures. A CH count of -1
enters the arithmetic as a count.

**Verdict table** (repro `repro_verdicts.py`):

| Situation | Status | Summary column | Counted as failure? |
|---|---|---|---|
| checksums equal, counts equal | PASS | `MATCH` | no |
| checksums differ | FAIL | `MISMATCH` | yes |
| CH checksum query fails, counts equal (R1) | **PASS** | `SKIP` | **no** |
| `system.columns` read fails, counts equal (R5) | **PASS** | `SKIP` | **no** |
| no comparable column (all json, bytea, array…) | **PASS** | `SKIP` | **no** |
| PG column missing in CH, rest equal (R2) | **PASS** | `MATCH` | **no** |
| checksum skipped or failed, \|Δ\| ≤ 100 and Δ% ≤ 0.01 % (R3b, R3c) | **WARN** | `SKIP` | **no** |
| PG empty, CH has ≤ 100 rows, `--no-checksum` (R4) | **WARN** | `SKIP` | **no** |
| CH count query fails, PG empty (R6) | **WARN** | — | **no** |
| CH table missing | MISSING | `MISSING` | yes |
| exception | ERROR | `SKIP` | yes |

**Summary** (`print_summary`, `:1073-1134`, stdout):

- a header with source, replica, LSN (string and integer) and run start, end
  and duration in UTC;
- one row per table, sorted by name: table, tier, PG count, CH count, delta,
  delta %, checksum label, status, plus `↳ <detail>` when there is a detail;
- `RESULT: PASS — all <N> tables match` when no table is FAIL, MISSING or
  ERROR, otherwise `RESULT: FAIL — <k> of <N> tables have mismatches`;
- `Exit code: <0|1>`.

The PASS line is printed for runs that contain WARN and SKIP rows
(D-13.07-1, D-13.07-2).

**Exit codes of `ch-checksum`.**

| Code | When |
|---|---|
| 0 | every table PASS or WARN, including the rows above |
| 1 | any FAIL, MISSING or ERROR; config not found, YAML error or failed validation; no tables found; REPEATABLE READ `BEGIN` failed; any uncaught exception (traceback), e.g. PG unreachable at start, missing `source.postgres.database` |
| 2 | argparse error |
| 128+signum | SIGTERM while a WAL-pause or flush handler is installed (SIGINT surfaces as `KeyboardInterrupt`) |

Resume failures, LSN timeouts, flush failures and auto_diff errors never
change the exit code.

### 3.13 Concurrency and connections

| Mode | PG connections | CH connections | Threads |
|---|---|---|---|
| per-table | 1 (discovery), then per table 1 plus 1 per chunk | 1 (wait), then 1 per table | `threads` × `threads_per_table` |
| snapshot | control (WAL pause) + 1 snapshot connection shared serially | 1 (wait), 4 in Phase A, then 1 per table in Phase B, 1 per auto_diff table | Phase A: 4 fixed. Phase B and auto_diff: main thread |

The snapshot connection is never used from two threads: Phase A uses only CH,
and the PG metadata pre-fetch is serial. Locks: none. ClickHouse queries carry
`max_memory_usage` (80 GB) and `max_threads = 4` (Tier-3 only) and no time
limit. PostgreSQL has `statement_timeout = 0`. Runtime is unbounded, while
standby WAL replay and the connector stay paused for the whole of steps 4b–9
including auto_diff (D-13.07-18).

### 3.14 Logging and artefacts
- **Logging.** INFO per table carries the tier, approximate rows, PK, PG and
  shared column counts, counts, delta and checksum label. INFO per excluded
  column. WARNINGs for waits. ERRORs with tracebacks for exceptions. Snapshot
  mode adds `WAL_REPLAY_PAUSE:`, `FLUSH_CONNECTOR:` and `AUTO_DIFF:` prefixes.
  `get_postgres_table_checksum` logs
  `Checksum for table <db>.<schema>.<table> = <md5> count <n>`.
- **Artefacts.** `ch-checksum` writes only `auto_diff` files. `ch-pg-checksum
  --debug_output` writes `out.<table>.pg.txt` into the CWD (truncated first,
  then one `{'row_hash': ...}` dict per line).

### 3.15 `ch-pg-checksum` (`postgres_table_checksum.py:659-794`)

| Flag | Type | Default | Effect / honoured? |
|---|---|---|---|
| `--pg_host` | str | required | yes |
| `--pg_user` | str | none | required with `--pg_password` (an `assert`) |
| `--pg_password` | str | none | WARNING "insecure". Without it, user **and** password come from the first pgpass entry; exit 1 if none |
| `--pgpass_file` | path | `~/.pgpass` | yes |
| `--pg_database` | str | required | yes |
| `--pg_port` | str | 5432 | `int()` at connect |
| `--pg_schema` | str | `public` | yes |
| `--tables_regex` | str | required | PG regex via `get_tables` |
| `--ignore_tables_regex` | str | none | applied twice: as a PG `!~` in discovery (case-sensitive) and as a Python `re.match` with IGNORECASE in `calculate_checksum` |
| `--no_wc` | flag | false | treat `--tables_regex` as a literal table name, no discovery |
| `--where` | SQL | none | appended verbatim to every chunk and to min/max |
| `--exclude_columns` | list (`nargs='+'`) | `[]` | each token split on `,` |
| `--tier` | 1 or 2 | 1 | 2 = PK-list MD5. Without a PK it returns `None` silently and prints no checksum line |
| `--threads_per_table` | int | 1 | chunk threads |
| `--chunk_size` | int | 100000 | no validation (D-13.07-14) |
| `--threads` | int | 1 | tables in parallel |
| `--include_floating_point_columns`, `--include_json_columns` | flag | false | opt-ins |
| `--debug_output` | flag | false | write hashes, print no checksum |
| `--debug_limit` | str | none | interpolated as `LIMIT <v>` per chunk |
| `--debug` | flag | false | DEBUG logging |

Output: one INFO line per table, `Checksum for table <db>.<schema>.<table> =
<md5> count <n>`. This digest uses this spec's row string (`chr(1)`, NULL bits,
§3.10). It is **not** comparable with the `ch-ch-checksum` output, which uses
11.02's `#`-separated MySQL canonical form. The module docstring's
"ClickHouse-compatible" is true only of `ch-checksum`'s internal
`get_ch_checksum()` (D-13.07-27).

Exit codes: 0 even when a table fails, because `calculate_checksum` catches and
logs (`:632-634`; repro; D-13.07-23). 1 on discovery or connection exception,
or when credentials are unresolved. 1 via `os._exit` on interrupt. 2 on
argparse error. `calculate_checksum` reads the module-global `args` and raises
`NameError` when called as a library function without `main()`.

### 3.16 `ch-pg-count` (`postgres_table_count.py:98-200`)
Flags: `--pg_host`, `--pg_user`, `--pg_password`, `--pgpass_file`,
`--pg_database`, `--pg_port`, `--pg_schema` (as in §3.15),
`--include_tables_regex` (default `.`), `--exclude_tables_regex` (PG `!~` in
discovery, plus a Python `re.match` with IGNORECASE per table), `--no_wc`,
`--where` (verbatim), `--threads` (default 1) and `--debug`. There is no
`--config`: the README's `ch-pg-count --config config.yml` is rejected by
argparse with exit 2 (repro; D-13.07-27).

Per table: `SELECT COUNT(*) AS cnt FROM "<schema>"."<table>"[ WHERE <where>]`,
logging `Count for table <db>.<schema>.<table> = <n>`. An error logs an ERROR
and returns -1. The run still exits 0 (repro; D-13.07-23). The helper
`get_postgres_table_count()` (`:29-51`) is unused by any entry point.

### 3.17 `auto_diff` (`auto_diff.py`)

**Invocation.** Snapshot mode only, after Phase B, for tables with `FAIL` and
`checksum_match is False`. It runs on the shared snapshot connection (§3.7.3)
plus its own CH connection. It returns
`{'diff_file', 'divergent_rows', 'stats'}`. `run_config` logs only the row
count and the file name.

**Config** (`checksum.auto_diff`, `:936-943`). None of these is validated.

| Key | Default | Effect |
|---|---|---|
| `enabled` | false | gate (top level) |
| `timeout_seconds` | 600 | per-table deadline, checked between queries (not inside a query). The top level also stops starting new tables once elapsed > timeout, so the total is up to about 2 × timeout plus the longest query |
| `max_divergent_rows` | 10 | stop after this many rows |
| `num_chunks` | 10 | fan-out per level. 0 gives `ZeroDivisionError` (repro A6; D-13.07-22) |
| `max_depth` | 6 | recursion bound |
| `per_row_threshold` | 100000 | compare row by row when `max(pg_cnt, ch_cnt)` is at or below this |
| `output_dir` | `.` (CWD) | created if missing |
| `output_format` | `json` | `text` for the human format, anything else gives JSON |
| `per_column_diff` | true | fetch the full rows of the divergent keys and diff per column |

**Key column** (`:954-981`; D-13.07-19):

- the column named `id`, whatever its type or PK status;
- else the first column whose `pg_type` is in
  `{integer, bigint, smallint, int, int2, int4, int8, serial, bigserial}`;
- else skip with `{'skipped': True, 'reason': 'no_integer_pk'}`.

`get_table_pk()` is not consulted. Consequences:

- A `uuid` `id` raises `ValueError` from `int()` (repro A4). The top level
  logs it.
- A composite or non-integer PK falls back to `id` or the first integer column.
- A non-unique key such as `tenant_id` makes the per-row maps collapse
  (repro A5: one modified row, 0 found).
- A **nullable** key column excludes NULL-key rows from every range, so they
  are never compared.

**Row hash.** `_build_pg_row_concat` and `_build_ch_row_concat` (`:50-158`)
use the same per-column expressions as §3.10 with the same type exclusions.
They take their metadata from **all PG columns** (`pg_columns_by_table`), not
from the shared set, so a PG-only column makes every CH query fail
(D-13.07-22). They use PG types on both sides, so they do **not** reproduce
the Phase A deviations of §3.9: a FAIL caused by D-13.07-6 typically yields
zero divergent rows. The columns are joined with `'|'` and **no NULL bits**,
so NULL and `''` are indistinguishable (D-13.07-21).

**Chunk hash** (`[start, end)`):

```sql
-- PG (parameters bound by psycopg2)
SELECT count(*), bit_xor(('x' || substr(md5(<pg_concat>), 1, 16))::bit(64)::bigint)::text
FROM "<schema>"."<table>" WHERE "<pk>" >= %s AND "<pk>" < %s
-- CH (integers interpolated)
SELECT count(), toString(groupBitXor(reinterpretAsUInt64(reverse(unhex(
       substring(lower(hex(MD5(<ch_concat>))), 1, 16))))))
FROM `<db>`.`<table>` FINAL WHERE is_deleted = 0 AND "<pk>" >= <start> AND "<pk>" < <end>
```

- **Hash agreement.** The PG signed `bigint` is mapped to unsigned by adding
  2^64 when negative. Both sides then read the first 64 bits of the MD5
  big-endian, so they agree (repro P4).
- **PostgreSQL version.** `bit_xor` exists from PostgreSQL 14. On older
  servers the first chunk query fails and auto_diff for that table ends with
  an exception (not verified offline).
- **Duplicates and XOR.** Two identical rows cancel under XOR, but the count
  is compared as well, so a CH duplicate is still a chunk mismatch.
- **Mismatch test.** A chunk mismatches when the counts or the XOR differ.

**Range and split** (`_get_id_range`, `:718-753`; `_binary_search_chunks`,
`:370-498`):

- **Range.** The **union** `[min(pg_min, ch_min), max(pg_max, ch_max)+1)`.
  The docstring says intersection.
- **One side empty.** PG empty returns `(None, None)` before CH is read, so
  the table is skipped as `empty_table` even though every CH row is
  `ch_only`. A CH `min()` returning NULL (a Nullable key) is skipped the same
  way even though every PG row is `pg_only` (repro A3, A3b; D-13.07-20). For a
  non-Nullable key, ClickHouse returns 0 for `min()` over no rows, which widens
  the range to 0.
- **Split.** `chunk = max(1, (hi-lo) // num_chunks)`. The first
  `num_chunks-1` chunks have that width and the last one runs to `hi`.
  Generation stops early once the start reaches `hi` (repro P10:
  `(0,7,10)` gives seven width-1 chunks; `(0,1001,10)` gives nine of width 100
  and a last one of 101).
- **Per mismatched chunk:**
  - if `max(pg_cnt, ch_cnt) <= per_row_threshold`, compare row by row;
  - else if `depth >= max_depth`, record a skipped chunk
    `{pk_range, reason: 'exceeds per_row_threshold at max_depth', row_estimate}`
    and do not compare it;
  - else recurse with `depth+1`.
- **Termination.** Guaranteed by `max_depth`, `max_divergent_rows` and the
  deadline. The number of queries is at most `2·num_chunks` per mismatched
  chunk per level.
- **Skewed keys.** Wide gaps put most rows into one chunk per level. For keys
  1..100 plus 10^12 with threshold 10, the search descends 7 levels and ends
  with one skipped chunk and 0 rows (repro A7).

**Per-row comparison** (`:253-363`):

```sql
SELECT "<pk>", md5(<pg_concat>) AS row_hash FROM ... WHERE "<pk>" >= %s AND "<pk>" < %s ORDER BY "<pk>"
SELECT "<pk>", lower(hex(MD5(<ch_concat>))) AS row_hash FROM ... FINAL WHERE is_deleted = 0 AND ... ORDER BY "<pk>"
```

Both results go into `{pk: hash}` dictionaries, so duplicate keys keep the
last row. Each key in the sorted union is classified:

- `modified`: both sides have it and the hashes differ;
- `pg_only` or `ch_only`: only one side has it.

The scan stops at `max_divergent_rows - len(found)`. A CH duplicate of an
identical row mismatches at chunk level but produces **no** divergent row and
no skipped chunk (repro A2: total 0; D-13.07-21).

**Full rows** (`_fetch_full_rows`, `:505-711`, when `per_column_diff`). For each
divergent key:

```sql
SELECT <expr> AS "<col>", ... FROM ... WHERE "<pk>" = %s
SELECT <expr> AS `<col>`, ... FROM ... FINAL WHERE is_deleted = 0 AND "<pk>" = <pk_val>
```

The key column is excluded from both. `modified` rows get
`columns = {col: {pg, ch, match}}` with string comparison of the normalised
texts. `pg_only` and `ch_only` rows get `pg_row` and `ch_row`. With
`per_column_diff: false`, entries carry only key, type and hashes.

**Output file.** `checksum_diff_<table>_<YYYYmmdd_HHMMSS>.json` (or `.txt`) in
`output_dir`, with the timestamp taken from the run start. JSON layout:

- `metadata`: `table`, `schema`, `database_ch`, `run_timestamp`,
  `diff_timestamp`, `pk_column`, `pk_range [min, max+1)`,
  `binary_search_config`, `binary_search_stats {levels_explored,
  total_chunk_queries, total_per_row_queries, elapsed_seconds}`;
- `divergent_rows`;
- `skipped_chunks`;
- `summary {total_divergent, modified, pg_only, ch_only, truncated,
  skipped_chunks}`.

`truncated` is `len(found) >= max_divergent_rows`. It is true when exactly the
maximum was found, even if nothing else diverges (repro A8). The text format
prints per-row mismatching columns as `col:  PG=[..]  CH=[..]  X`, then
`(<n> columns match)`, skipped chunks and a summary line. The files contain
**row values** and are written with the process umask into the CWD by default.

### 3.18 Cron wrapper `postgres_checksum_runner.sh`

**Script.**

- Shell options: `set -uo pipefail` with no `-e` (`:24`).
- `SCRIPT_DIR` is the script's directory, `.../ch_sink_tools/db_compare/scripts`,
  and `PYTHON_DIR="$(dirname "$SCRIPT_DIR")"` is `.../ch_sink_tools/db_compare`
  (`:29-30`).
- The first argument is the config path unless it starts with `-`. The
  default is `$SCRIPT_DIR/config_postgres_system.yml`, which is not shipped.
- A missing config prints usage and exits 2.
- Logs go to `${PG_CH_CHECKSUM_LOG_DIR:-/var/log/pg_ch_checksum}/system_<YYYYMMDD>.log`.
  If the directory cannot be created, the script falls back to
  `/tmp/pg_ch_checksum`.
- It sources `${PYTHON_VENV:-$PYTHON_DIR/.venv}/bin/activate` if present.
- It runs `export PYTHONPATH="$PYTHON_DIR:..."`, then `cd "$PYTHON_DIR"` and
  `python db_compare/top_level_postgres_checksum.py --config "$CONFIG_FILE" "${EXTRA_ARGS[@]}" 2>&1 | tee -a "$LOG_FILE"`.
- `EXIT_CODE="${PIPESTATUS[0]}"` is read right after the pipeline, so the
  Python status, not tee's, is propagated by `exit "$EXIT_CODE"`. A non-zero
  code also prints `CHECKSUM FAILED` to stderr. The mail and webhook hooks are
  commented out.
- **Quoting.** Every expansion is quoted. Under `set -u`, the empty-array
  expansion is safe on bash ≥ 4.4 but fails as "unbound variable" on older
  bash.

**As built the wrapper cannot run the tool** (repro; D-13.07-12):

- The target path resolves to `.../ch_sink_tools/db_compare/db_compare/top_level_postgres_checksum.py`,
  which does not exist. Python prints `can't open file` and exits 2, and the
  wrapper exits 2.
- Even with the path corrected, running the module file with `PYTHONPATH` set
  to the package directory fails with
  `ModuleNotFoundError: No module named 'ch_sink_tools'` (repro).
- A run with no arguments exits 2 on the missing default config.
- The header's exit code 2 for "script startup error" cannot be told apart
  from Python's own exit 2.
- The header says "0 → all tables PASS", but WARN runs exit 0 too.

### 3.19 Dockerfile
`Dockerfile_clickhouse_checksum` builds the legacy MySQL-pipeline ClickHouse
side script. Its entry point passes `$CLICKHOUSE_HOST`-style strings in exec
form, so they are not expanded (spec 13.01). It contains neither
`ch_sink_tools` nor any PostgreSQL tool. No container image in the repository
runs `ch-checksum`.

### 3.20 Legacy vs packaged
- **One copy.** `top_level_postgres_checksum.py`, `postgres_table_checksum.py`,
  `postgres_table_count.py`, `auto_diff.py`, `_expressions.py`,
  `scripts/postgres_checksum_runner.sh` and `db/postgres.py` exist **only**
  under `sink-connector/python/ch_sink_tools/`. The legacy trees have no
  counterpart, so there is no behavioural divergence to record.
- **Imports.** Every entry point and every internal import uses the packaged
  absolute imports (`from ch_sink_tools.db...`). The tools work only as an
  installed package or with the `sink-connector/python` directory on
  `sys.path`.
- **Who uses which copy.** The legacy unit tests do not import them. The only
  Dockerfile in scope does not ship them. The runner script was written for a
  pre-package layout (`db_compare/` directly under the Python root), which is
  why its paths are wrong (§3.18).
- **Python 3.6.** `pyproject.toml` declares `requires-python >= 3.6`.
  `auto_diff.py` avoids f-strings for that reason. The other three modules use
  f-strings, which are valid in 3.6.

### 3.21 Quoting and escaping
- **PostgreSQL identifiers.**
  - Data queries quote schema, table and columns as `"<name>"` without
    doubling embedded `"`.
  - Catalog queries (`get_tables`, `get_table_columns`, `get_table_pk`,
    `get_table_row_count`) interpolate the names as `'<name>'` literals
    without escaping.
- **Values.** `auto_diff` binds PG range and key values as parameters.
  `ch-pg-checksum` interpolates `--where` and `--debug_limit` verbatim.
- **ClickHouse.**
  - Count and checksum queries quote the database and table with backticks
    and the columns with `"`.
  - Catalog queries interpolate `'<db>'` and `'<table>'` unescaped.
  - The exclusion list uses Python `repr()` (single-quoted).
  - `auto_diff` interpolates the integer bounds and key values.
- **Time zones.**
  - `timestamptz` on the PG side is rendered in UTC; CH applies
    `toTimeZone(...,'UTC')`.
  - `timestamp` on the PG side depends on the session TimeZone (D-13.07-7);
    CH renders in the column's or the server's zone.
  - Run timestamps are UTC. The diff file name uses the run start's UTC
    clock.

---

## 4. Invariants Preserved
Invariants that hold as built:

1. **Read-only on data.** No tool issues DML or DDL against PostgreSQL or
   ClickHouse tables. The only state changes are standby WAL replay
   pause/resume and connector flush/resume.
2. **Order independence.** Both digests are sums (PG exact, CH modulo 2^64),
   so the physical row order, the chunking and the thread count do not change
   the result, as long as a table has fewer than about 2^32 rows (D-13.07-16).
3. **Word agreement.** For identical row strings, the PG and CH per-row words
   and the auto_diff 64-bit hashes are identical (repro P3, P4).
4. **Empty tables.** An empty table on both sides yields `md5('0#0#0#0#0#')`
   on both sides.
5. **Paired filtering.** In Phase B and per-table mode, both sides use the same
   column list, order and nullability: the shared PG columns minus the same
   type exclusions.
6. **Cleanup.** If this run paused WAL replay or flushed the connector, it
   attempts the resume in a `finally` block and in SIGTERM/SIGINT handlers.

Invariants the tool claims but does **not** preserve (each is a defect in §7):

- "never PASS without a value-level comparison" (D-13.07-1, D-13.07-2,
  D-13.07-3, D-13.07-4);
- "PG is read inside one REPEATABLE READ snapshot pinned at the logged LSN"
  (D-13.07-5);
- "CH and PG reflect the same logical state" (D-13.07-10, D-13.07-17);
- "Phase A excludes the same columns as Phase B and builds matching
  expressions" (D-13.07-6);
- "a resume failure is surfaced" (D-13.07-9).

---

## 5. Verification Criteria
**Existing automated tests:** none.
`grep -rln -e postgres_table_checksum -e top_level_postgres -e auto_diff -e _expressions -e postgres_table_count`
over `sink-connector/python/db_compare/tests`, `sink-connector/python/db_load/tests`,
`sink-connector/python/db_dump/tests` and `sink-connector/python/tests` finds
nothing. The offline suite (`python -m pytest -q -p no:cacheprovider db_compare/tests db_load/tests db_dump/tests tests`
from `sink-connector/python`) passes 227 and skips 5 on 2.11.0, and is
unaffected by and blind to these modules.

**Offline repros run for this spec.** These are throwaway scripts, not
committed. Each imports the packaged modules and mocks every connection. They
use the project's Python 3.12 environment with psycopg2 2.9.13 and
clickhouse-driver 0.2.11.

- **`repro_verdicts.py`** runs `run_config()` in per-table mode with mocked
  PG and CH. Printed:
  - R1: CH checksum raises, counts 1,000/1,000. Result: `SKIP PASS`,
    `RESULT: PASS — all 1 tables match`, exit 0.
  - R2: PG `b` absent in CH. Result: `MATCH PASS`, exit 0, and `"b"` appears
    in neither checksum SQL.
  - R3a: `--no-checksum`, 1,000/999. Result: FAIL, exit 1.
  - R3b and R3c: `--no-checksum`, and CH checksum raising, each with
    2,000,000/1,999,950. Result: `SKIP WARN`, `RESULT: PASS`, exit 0.
  - R4: `--no-checksum`, PG 0 / CH 50. Result: `WARN`, exit 0.
  - R5: CH `system.columns` raises. Result: `SKIP PASS`, exit 0.
  - R6: CH count raises, PG empty. Result: CH count `-`, `WARN`, exit 0.
- **`repro_snapshot.py`** runs `run_config()` in snapshot mode with mocked
  PG, CH and HTTP.
  - S1: the Phase A CH row expression is
    `concat_ws(char(1), toString("id"), toString("flag"), toString("amount"), coalesce(toString(toTimeZone("created", 'UTC')), ''), toString("amount_x100")) || char(1) || ...`,
    while the PG expression is
    `concat_ws(chr(1), "id"::text, "amount"::text, coalesce(to_char("created" AT TIME ZONE 'UTC', ...), ''), CASE WHEN "flag" THEN '1' ELSE '0' END) || chr(1) || ...`.
  - S2: `/resume` fails. Result: exit 0, ERROR `FLUSH_CONNECTOR: CRITICAL — Failed to resume connector`.
  - S3: `pg_wal_replay_resume` fails. Result: exit 0, ERROR
    `CRITICAL: Failed to resume WAL replay`.
  - S4: the snapshot connection received `set autocommit=False`,
    `execute 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ'`, commit,
    close.
- **`repro_pure.py`:**
  - P1: per-type expressions (the §3.10 table), including
    `to_char("c" AT TIME ZONE 'UTC', ...)` for `timestamp without time zone`.
  - P2: the `_ch_type_to_pg_type` table of §3.9.
  - P3: PG and CH word equality for an MD5, and Int64 wrap at 2^32+10 rows
    (`equal=False`).
  - P4: auto_diff 64-bit hash equality.
  - P5: 100,000 chunks for keys up to 10^10; `chunk_size=0` gives
    `MemoryError` under a 1.5 GB address-space limit.
  - P6: an `interval` PK gives `TypeError: int() argument must be ... not 'datetime.timedelta'`.
  - P7: all columns jsonb gives the digest `081af8c0194a83cd9ef7788fccb53f40`
    = `md5('0#0#0#0#0#')` with no query issued.
  - P8: `AND table_name ~ '^o'reilly$'`.
  - P9: view derivation.
  - P10: chunk splits.
- **`repro_auto_diff.py`** runs `run_auto_diff_for_table()` over in-memory
  tables.
  - A1: missing, modified and orphan keys are found as
    `[(42,'modified'),(537,'pg_only'),(20000,'ch_only')]`.
  - A2: a CH duplicate gives total 0 and 0 skipped chunks.
  - A3 and A3b: one side empty gives `empty_table`.
  - A4: a uuid `id` gives `ValueError`.
  - A5: a non-unique key gives 0 found.
  - A6: `num_chunks=0` gives `ZeroDivisionError`.
  - A7: a skewed key range gives 0 found and 1 skipped chunk.
  - A8: `truncated: True` when exactly the maximum is found.
- **`repro_standalone.py`:** `ch-pg-checksum` and `ch-pg-count` exit 0 when
  every table errors.
- **Runner:** running the wrapper with a dummy config printed `python: can't
  open file '.../ch_sink_tools/db_compare/db_compare/top_level_postgres_checksum.py'`
  and `exit=2`. With no arguments it exits 2 on the missing default config.
  `python -m ch_sink_tools.db_compare.postgres_table_count --config x.yml`
  gives an argparse error.

**Acceptance criteria for a conforming implementation.** These are
behavioural statements that tests must pin; each is a GAP today.

1. No status other than FAIL, MISSING or ERROR when a checksum was requested
   but not computed, or when a PG column is absent from CH.
2. A run with any WARN or SKIP row does not print "all tables match".
3. The snapshot connection reports `SHOW transaction_isolation` =
   `repeatable read`.
4. Phase A and Phase B build identical CH expressions for a table whose CH
   types are `Bool`, `DateTime64(6,'<zone>')` and an ALIAS column.
5. `timestamp without time zone` renders the same under PG session TimeZone
   UTC and a non-UTC zone.
6. `numeric(10,2)` value `1.50` produces the same text on both sides.
7. A resume failure makes the exit code non-zero.
8. The runner starts the tool from an installed package.
9. `ch-pg-checksum` and `ch-pg-count` exit non-zero when a table fails.

---

## 6. Failure Modes & Recovery
All tools here are read-only on data, and any run can be repeated. Recovery
is "fix the cause, re-run (narrower with `--table`)". The two exceptions are
the WAL-pause and connector-flush side effects (FM-13.07-8, FM-13.07-9).
Failure modes that make the tool lie are listed first.

- **FM-13.07-1 A checksum that was not computed is reported PASS**
  - **Trigger**: the CH checksum query fails, for example from a memory limit,
    a type error such as `if(c = 0, ...)` on a `String` column or
    `toTimeZone` on a `String`, or a network error. Or `system.columns`
    cannot be read, or no column survives the type exclusions.
  - **Behaviour**: `get_ch_checksum` returns `None` (`top_level_postgres_checksum.py:742-744`,
    `:657-660`), or `get_ch_columns` returns `[]` (`:404-406`). Then
    `checksum_match = None` (`:1019-1022`) and the status is PASS when the
    counts are equal (`:1025-1030`). The summary prints `SKIP` and
    `RESULT: PASS — all N tables match`, and the run exits 0
    (`:1122-1134`, `:2066-2067`).
  - **Detection**: an ERROR `CH checksum error for ...` in the log, and the
    `SKIP` label in the summary. The exit code and the RESULT line say PASS.
  - **Blast radius**: any value-level divergence in that table is certified
    by row count only.
  - **Recovery**: treat every `SKIP` row in a run without `--no-checksum` as
    a failure. Fix the cause and re-run with `--table <t>`.
  - **RTO**: one re-run of the table (unmeasured, proportional to its size).
  - **Test**: GAP: a test with a mocked CH checksum query that raises must
    yield ERROR and exit 1.
  - **DEFECT**: a failed or impossible checksum is a PASS (D-13.07-1).

- **FM-13.07-2 Counts differ within the thresholds: WARN, "all tables match", exit 0**
  - **Trigger**: `--no-checksum`, or a checksum that is `None`, together with
    0 < |Δ| ≤ `alert_count_delta_abs` (100) and Δ/PG ≤
    `alert_count_delta_pct` (0.0001). Or a PG table that is empty while CH
    has up to 100 rows (Δ% is defined as 0).
  - **Behaviour**: status WARN (`:1027-1028`), not counted as a failure
    (`:1122-1123`, `:2066`). The RESULT line claims all tables match.
  - **Detection**: the WARN status and the nonzero Delta column in the
    summary.
  - **Blast radius**: up to 100 missing or extra rows per table, or 0.01 % of
    a large table, pass silently.
  - **Recovery**: scan the summary for WARN rows, then run with checksums and
    `--table`.
  - **RTO**: one re-run per table (unmeasured).
  - **Test**: GAP: a run with Δ = 50 on 2,000,000 rows and `--no-checksum`
    must not print "all tables match".
  - **DEFECT**: WARN is invisible in the exit code, and the RESULT text is
    false (D-13.07-2).

- **FM-13.07-3 A column or a table is silently left out of the comparison**
  - **Trigger**: a PG column missing in CH, for example a schema change the
    connector did not apply. Or a table or column the checksum user has no
    privilege on (hidden by `information_schema`). Or CH-only tables.
  - **Behaviour**: shared-column intersection (`:856-858`). An INFO line
    reports `pg_cols=N shared_cols=M`. Tables come from
    `information_schema.tables` (`db/postgres.py:308-316`).
  - **Detection**: compare `pg_cols` with `shared_cols` in the log. Compare
    the table list with the catalog by hand.
  - **Blast radius**: a missing column, or an unreadable table, is certified
    as MATCH and PASS (repro R2).
  - **Recovery**: re-run as a user with SELECT on all tables. Diff the PG and
    CH column lists, and repair the schema per spec 13.05 or the connector's
    DDL path.
  - **RTO**: unbounded while unnoticed. Then one re-run.
  - **Test**: GAP: a PG column absent from CH must make the table FAIL.
  - **DEFECT**: coverage loss is not reported (D-13.07-3, D-13.07-4).

- **FM-13.07-4 Snapshot mode reports MISMATCH for equal data (Phase A metadata drift)**
  - **Trigger**: `snapshot_mode: true` on a table whose CH column order
    differs from PG's, or which has a CH-only column (ALIAS, MATERIALIZED, a
    dropped PG column), a `Bool` column, a zone-annotated `DateTime64` for a
    naive PG timestamp, or a nullability that differs.
  - **Behaviour**: Phase A precomputes the CH digest from CH metadata
    (`:1693-1734`, `:1749-1755`), and Phase B uses it unconditionally
    (`:992-997`). The PG digest uses PG metadata. Result: FAIL. auto_diff, which
    uses PG metadata, then typically finds 0 divergent rows.
  - **Detection**: FAIL with `MISMATCH`, and an auto_diff file with
    `total_divergent: 0`.
  - **Blast radius**: noise only, but it trains operators to ignore failures.
  - **Recovery**: re-run the table with `snapshot_mode: false`. Phase B then
    computes CH from PG metadata.
  - **RTO**: one per-table-mode run.
  - **Test**: GAP: Phase A and Phase B must produce the same CH SQL for a
    table with `Bool`, `DateTime64(6,'<zone>')` and an ALIAS column.
  - **DEFECT**: the precomputed digest uses the wrong metadata (D-13.07-6).

- **FM-13.07-5 Representation mismatch for specific PG types**
  - **Trigger**: `numeric(p,s)` values with trailing fractional zeros;
    `timestamp without time zone` under a non-UTC PG session TimeZone;
    `interval`, `char(n)` with padding, `money`, `bit`, and `time` stored in a
    format other than PG's text; `timestamptz` stored as `DateTime64(3)`.
  - **Behaviour**: different text on the two sides (`postgres_table_checksum.py:105-106`,
    `:144-149`; `_expressions.py:57-58`). Every affected row changes the
    digest, giving FAIL.
  - **Detection**: FAIL. auto_diff per-column output shows the formatting
    difference, for example `PG=[1.50] CH=[1.5]`.
  - **Blast radius**: false FAIL for whole classes of tables. Real
    divergences in those tables are buried in the noise.
  - **Recovery**: list the affected column in `skip_table_columns` for that
    table (this loses coverage), or run with the PG session TimeZone set to
    UTC (for example through `PGTZ` in the environment) for the timestamp
    case.
  - **RTO**: one re-run.
  - **Test**: GAP: per-type golden pairs (PG text vs CH text) for every row of
    the §3.10 table.
  - **DEFECT**: no canonical form for these types, and the naive-timestamp
    shift (D-13.07-7, D-13.07-8).

- **FM-13.07-6 The source keeps moving during the compare**
  - **Trigger**: snapshot mode without `wal_replay_pause` on an actively
    written database, or WAL-pause mode where the replication slot is on the
    primary, or per-table mode at any time.
  - **Behaviour**: the PG transaction is READ COMMITTED (`:1420-1425`,
    `db/postgres.py:235`; §3.7.3). In WAL-pause mode, "caught up" means caught
    up with the primary, and `_wait_for_connector_caught_up` returns True even
    on timeout (`:375-380`). CH and PG therefore read different points in time.
  - **Detection**: FAIL on hot tables that disappears on re-run.
    Indistinguishable from a real divergence without auto_diff.
  - **Blast radius**: false FAIL (noise). In principle this is not a false
    PASS, because two moving states rarely coincide.
  - **Recovery**: re-run during a quiet period, or with WAL pause plus flush
    and a connector whose slot is on the paused standby.
  - **RTO**: one re-run (unmeasured).
  - **Test**: GAP: a test asserting the snapshot connection uses
    `set_session(isolation_level=REPEATABLE READ)` or an equivalent before the
    first statement.
  - **DEFECT**: the snapshot is not repeatable, and "caught up" is not
    established against the frozen point (D-13.07-5, D-13.07-10).

- **FM-13.07-7 The catch-up wait never converges**
  - **Trigger**: a cluster with WAL the connector does not consume, an offset
    `lsn` that is not a JSON number, or no rows in the offset table.
  - **Behaviour**: `wait_for_ch_lsn` polls until `lsn_wait_timeout_seconds`
    (default 300 s), logs `Running checksum anyway`, and the caller proceeds
    (`:226-230`, `:1519-1524`, `:2000-2004`).
  - **Detection**: the WARNING `Timeout after <n>s`, and the repeated
    `still waiting` lines.
  - **Blast radius**: each run is delayed by the timeout. Results may compare
    a lagging CH (false FAIL).
  - **Recovery**: set `offset_table: ''` to skip the wait knowingly, or use
    WAL-pause mode.
  - **RTO**: `lsn_wait_timeout_seconds` per run.
  - **Test**: GAP: `wait_for_ch_lsn` with a mocked offset that stays below
    the target must return False after the deadline. Also: the physical LSN
    must not be compared with a logical offset.
  - **DEFECT**: physical vs logical LSN comparison, maximum taken over
    unrelated offset rows (D-13.07-17).

- **FM-13.07-8 Replication is left paused after a "successful" run**
  - **Trigger**: `GET resume_url` fails (connector restarting, network), or
    `pg_wal_replay_resume()` fails.
  - **Behaviour**: `_ensure_connector_resumed` and `_ensure_wal_resumed` log
    ERROR `CRITICAL ... MANUAL INTERVENTION REQUIRED` (`:1318-1349`). The run
    still exits by its verdict, possibly 0 (repro S2, S3).
  - **Detection**: only the ERROR line. Downstream, the connector lag grows
    without bound or the standby replay lag grows.
  - **Blast radius**: CH stops receiving changes (connector paused), or the
    standby stops replaying (WAL accumulates and the standby is stale for
    every reader).
  - **Recovery**: call `GET <resume_url>` by hand, and on the standby run
    `SELECT pg_wal_replay_resume();`. Then confirm the lag drains (spec 10.03
    view).
  - **RTO**: unbounded until noticed. After that, minutes (one HTTP call or
    SQL statement).
  - **Test**: GAP: a test with a mocked resume failure must make the exit
    code non-zero.
  - **DEFECT**: a failed cleanup of a replication-stopping side effect is not
    in the exit code (D-13.07-9).

- **FM-13.07-9 The run is killed, or runs very long, while replication is paused**
  - **Trigger**: SIGKILL (OOM killer, cron timeout with `-9`), a host
    reboot, or simply a large schema. Phase B is serial and auto_diff runs
    before the resume.
  - **Behaviour**: no `finally` block runs on SIGKILL. With SIGTERM and SIGINT
    the handlers resume. There is no bound on the pause: statement timeout 0,
    no ClickHouse execution limit, and auto_diff adds up to about 2 ×
    `timeout_seconds` (`:1850-1918` run before `:1932-1947`).
  - **Detection**: standby `pg_is_wal_replay_paused()` = true with no
    checksum process alive. The connector status shows paused.
  - **Blast radius**: as FM-13.07-8, for the duration of the run or until a
    human intervenes.
  - **Recovery**: resume both by hand as in FM-13.07-8. Bound the runs by
    splitting the schema across several configs (`table_include_list`).
  - **RTO**: unmeasured. Equal to the run length plus the time to notice.
  - **Test**: GAP: auto_diff must run after the resume, or the pause must
    carry a deadline.
  - **DEFECT**: unbounded pause duration, and no watchdog (D-13.07-18).

- **FM-13.07-10 One PG error poisons every later table in snapshot mode**
  - **Trigger**: any PG error on the snapshot connection, such as a recovery
    conflict cancellation on an unpaused standby, an unsupported expression
    (possibly `to_char(timetz)`), or an `interval` PK.
  - **Behaviour**: the transaction is aborted. `execute_pg` does not roll
    back (`db/postgres.py:239-248`) and `compare_table` returns ERROR
    (`:1057-1066`). Every later statement fails with "current transaction is
    aborted", so every later table and the auto_diff are ERROR.
  - **Detection**: a cascade of ERROR rows with the same message. Exit 1.
  - **Blast radius**: the coverage of the rest of the run is lost. There is no
    false verdict.
  - **Recovery**: exclude the failing table (`skip_tables`) and re-run.
  - **RTO**: one re-run.
  - **Test**: GAP: a table that raises must not turn the next table into
    ERROR.
  - **DEFECT**: no rollback or savepoint per table (D-13.07-13).

- **FM-13.07-11 Pathological primary keys**
  - **Trigger**: `chunk_size: 0` or negative; sparse integer keys
    (snowflake-style IDs); a PK typed `interval`.
  - **Behaviour**: an endless chunk loop that exhausts memory; one query per
    `chunk_size` key slice (up to about 4.6·10^13 for 2^62); or a `TypeError`
    from `int(timedelta)` (`postgres_table_checksum.py:318-326`, `:438`).
  - **Detection**: process memory growth with no SQL activity; or an ERROR
    row.
  - **Blast radius**: the run never finishes. In snapshot mode it holds the
    WAL pause and the connector flush meanwhile (FM-13.07-9).
  - **Recovery**: kill the process, resume replication by hand, and set a
    sane `chunk_size` or skip the table.
  - **RTO**: unmeasured.
  - **Test**: GAP: `divide_table_into_chunks` must reject `chunk_size <= 0`
    and bound the chunk count. A non-integer PK must not be chosen as the
    chunk key.
  - **DEFECT**: D-13.07-14, D-13.07-15.

- **FM-13.07-12 Duplicates across partitions, or a table without `is_deleted`**
  - **Trigger**: a CH partition key that is not a function of the sorting
    key, with an UPDATE that moves a row; or a replica table whose delete
    marker column has another name.
  - **Behaviour**: the per-partition `FINAL` keeps both versions, so the CH
    count and digest include the stale row (`:540`, `:722`). Or every CH
    query fails on `is_deleted` (`:539`, `:708`; `auto_diff.py:230`, `:289`,
    `:740`).
  - **Detection**: FAIL with CH count > PG count; or ERROR, PASS or WARN per
    FM-13.07-1 and FM-13.07-2.
  - **Blast radius**: false FAIL. With a failing query it falls into the
    false-PASS paths.
  - **Recovery**: `OPTIMIZE ... FINAL` is not a fix (the versions sit in
    different partitions). Compare by hand with `FINAL` without the setting.
  - **RTO**: unmeasured.
  - **Test**: GAP: the setting must be applied only when the partition key is
    a function of the sorting key, as in 11.02 §3.7. The delete column must
    come from the engine.
  - **DEFECT**: regression of 11.02 finding C16, and a hard-coded
    `is_deleted` (D-13.07-11).

- **FM-13.07-13 Very large table: CH sum wraps**
  - **Trigger**: a table with more than about 2^32 rows.
  - **Behaviour**: ClickHouse `sum(Int64)` wraps while the PG `numeric` sum is
    exact (`top_level_postgres_checksum.py:713-716` vs
    `postgres_table_checksum.py:239-242`).
  - **Detection**: FAIL with equal counts.
  - **Blast radius**: false FAIL for multi-billion-row tables.
  - **Recovery**: compare the table in key ranges (`ch-pg-checksum --where`)
    against hand-written CH range queries.
  - **RTO**: unmeasured.
  - **Test**: GAP: an arithmetic test with mocked aggregates near 2^63.
  - **DEFECT**: D-13.07-16.

- **FM-13.07-14 auto_diff names the wrong rows or none**
  - **Trigger**:
    - an `id` column that is not an integer PK, or a table without `id` (the
      first integer column is used);
    - a nullable key;
    - CH duplicates;
    - NULL-vs-empty divergences;
    - one side empty;
    - a PG-only column;
    - a skewed key range;
    - `num_chunks: 0`.
  - **Behaviour**: an exception (`ValueError`, `ZeroDivisionError`, CH missing
    column) logged as `AUTO_DIFF: [t] Error`. Or a file with
    `total_divergent: 0`, or `empty_table` / `no_integer_pk` skips
    (`auto_diff.py:957-981`, `:731-744`, `:272-303`, `:398`).
  - **Detection**: an auto_diff file whose totals disagree with the FAIL row,
    or a skipped-chunk list.
  - **Blast radius**: diagnosis only. The verdict is unaffected.
  - **Recovery**: compare by hand by the real PK range with the expressions of
    §3.10.
  - **RTO**: manual (unmeasured).
  - **Test**: GAP: auto_diff must use `get_table_pk`, include NULL bits,
    report count-only mismatches and handle one empty side.
  - **DEFECT**: D-13.07-19, D-13.07-20, D-13.07-21, D-13.07-22.

- **FM-13.07-15 The cron wrapper never runs the checksum**
  - **Trigger**: any invocation of `postgres_checksum_runner.sh`.
  - **Behaviour**: it runs a non-existent path, so Python exits 2 and the
    wrapper exits 2 with `CHECKSUM FAILED (exit=2)` (`postgres_checksum_runner.sh:29-30`,
    `:98-110`).
  - **Detection**: exit 2 and the `can't open file` line in the log.
  - **Blast radius**: no verification at all while the cron "runs".
  - **Recovery**: call `ch-checksum --config <file>` from the installed
    package directly in cron.
  - **RTO**: minutes (cron edit).
  - **Test**: GAP: a shell test running the wrapper against a stub
    `ch-checksum`.
  - **DEFECT**: D-13.07-12.

- **FM-13.07-16 Standalone helpers exit 0 when a table fails**
  - **Trigger**: any per-table error in `ch-pg-checksum` or `ch-pg-count`.
  - **Behaviour**: the error is logged. No checksum line is printed, or the
    count is -1. The exit code is 0 (`postgres_table_checksum.py:632-634`,
    `:794`; `postgres_table_count.py:77-80`, `:200`).
  - **Detection**: ERROR log lines, and a missing `Checksum for table` or
    `Count for table` line.
  - **Blast radius**: a script that trusts the exit code and parses the lines
    sees a missing table as nothing to compare.
  - **Recovery**: check that one output line exists per expected table.
  - **RTO**: one re-run.
  - **Test**: GAP: a mocked table error must give a non-zero exit.
  - **DEFECT**: D-13.07-23.

- **FM-13.07-17 Configuration accepted but not honoured**
  - **Trigger**: `skip_columns` given as a list; `secure: "false"` as a
    string; a missing `source.postgres.database`; tier thresholds; `threads`
    in snapshot mode; `auto_diff` in per-table mode.
  - **Behaviour**: silently ignored, inverted, or a late `KeyError`
    (`:1192`, `:1211`, `:1253-1254`, `:1225-1229`).
  - **Detection**: none, or a traceback.
  - **Blast radius**: columns meant to be excluded are compared, giving a
    false FAIL. TLS is enabled unexpectedly, which is loud. Performance
    settings have no effect.
  - **Recovery**: use the map form `skip_table_columns: {table: [cols]}` and
    YAML booleans.
  - **RTO**: one re-run.
  - **Test**: GAP: config-schema validation tests.
  - **DEFECT**: D-13.07-24, D-13.07-25.

Summary: 17 failure modes, 17 DEFECT, 17 GAP.

---

## 7. Defect Register

| ID | Severity | Copy (legacy/packaged/both) | Location | Evidence | Summary |
|---|---|---|---|---|---|
| D-13.07-1 | S1 | packaged | `ch_sink_tools/db_compare/top_level_postgres_checksum.py:1019-1030`, `:742-744`, `:404-406`, `:1129-1130` | reproduced (R1, R5: `SKIP PASS`, `RESULT: PASS`, exit 0) | A checksum that failed or could not be built (`None`) yields PASS when the counts are equal, and the run reports "all tables match" |
| D-13.07-2 | S1 | packaged | `top_level_postgres_checksum.py:920-930`, `:1025-1030`, `:1122-1123`, `:2066` | reproduced (R3b, R3c, R4, R6: `WARN`, `RESULT: PASS`, exit 0) | A count difference within the thresholds (or PG empty and CH ≤ 100 rows) is WARN, not a failure, and the summary claims all tables match |
| D-13.07-3 | S1 | packaged | `top_level_postgres_checksum.py:856-858`, `:959-961` | reproduced (R2: column absent from both SQL texts, `MATCH PASS`) | PG columns missing from ClickHouse are silently dropped from the comparison |
| D-13.07-4 | S1 | packaged | `ch_sink_tools/db/postgres.py:308-316` (used by `top_level_postgres_checksum.py:1453`, `:1971`) | code-read (PostgreSQL shows only privileged objects in `information_schema`) | Tables or columns the checksum user cannot see are silently not compared, and CH-only tables are never examined |
| D-13.07-5 | S2 | packaged | `top_level_postgres_checksum.py:1420-1425`; `ch_sink_tools/db/postgres.py:235` | code-read (psycopg2 implicit `BEGIN`, then PostgreSQL ignores the nested `BEGIN`); statement order reproduced (S4) | The "REPEATABLE READ snapshot" runs at the default isolation level; the logged snapshot LSN does not describe the data read |
| D-13.07-6 | S2 | packaged | `top_level_postgres_checksum.py:1693-1734`, `:1749-1755`, `:450-510`, `:435` | reproduced (S1 expressions, P2 table) | The Phase A precomputed CH digest uses ClickHouse order, inferred types and nullability, and includes CH-only columns, so snapshot mode gives false MISMATCH |
| D-13.07-7 | S2 | packaged | `ch_sink_tools/db_compare/postgres_table_checksum.py:105-106` | reproduced (P1 expression); semantics code-read | `timestamp without time zone` matches the `'time zone'` test and is rendered `AT TIME ZONE 'UTC'`, so it is shifted by the PG session TimeZone while CH is not |
| D-13.07-8 | S2 | packaged | `postgres_table_checksum.py:144-149`; `ch_sink_tools/db_compare/_expressions.py:57-58` | code-read (CH `Decimal` text drops trailing zeros, cf. 11.02 §3.3) | `numeric` scale, `interval`, `char(n)`, `money`, `bit` and `time` are compared as raw `::text` vs `toString()`, with no canonical form |
| D-13.07-9 | S2 | packaged | `top_level_postgres_checksum.py:1318-1349`, `:1932-1947`, `:2066-2067` | reproduced (S2, S3: exit 0) | A failure to resume the connector or the standby WAL replay is only logged; the run can exit 0 with replication stopped |
| D-13.07-10 | S2 | packaged | `top_level_postgres_checksum.py:264-380`, `:1481-1504` | code-read | WAL-pause "caught up" is `Seconds_Behind_Source = 0` against the connector's own source (ahead of the frozen standby), and it always returns True, even on timeout |
| D-13.07-11 | S2 | packaged | `top_level_postgres_checksum.py:539-540`, `:572`, `:708`, `:722`; `ch_sink_tools/db_compare/auto_diff.py:230`, `:289`, `:634`, `:740` | code-read | `do_not_merge_across_partitions_select_final=1` is always applied (11.02 C16 regression), and `is_deleted = 0` is hard-coded despite the alternative names in the exclusion set |
| D-13.07-12 | S3 | packaged | `ch_sink_tools/db_compare/scripts/postgres_checksum_runner.sh:29-30`, `:33`, `:93`, `:98-107` | reproduced (`can't open file .../db_compare/db_compare/...`, exit 2; `ModuleNotFoundError`) | The cron wrapper computes the wrong directory and module path and cannot start the tool; the default config is not shipped |
| D-13.07-13 | S3 | packaged | `ch_sink_tools/db/postgres.py:239-248`; `top_level_postgres_checksum.py:1057-1066`, `:1828-1837` | code-read | One PG error aborts the shared snapshot transaction; with no rollback, every later table and the auto_diff are ERROR |
| D-13.07-14 | S3 | packaged | `postgres_table_checksum.py:318-326`; `top_level_postgres_checksum.py:1227` | reproduced (P5: `MemoryError` for `chunk_size=0`; 100,000 chunks for keys up to 10^10) | The chunk divider loops forever for `chunk_size <= 0` and issues one query per key slice for sparse keys |
| D-13.07-15 | S3 | packaged | `postgres_table_checksum.py:438` | reproduced (P6: `TypeError`) | The integer-PK test is a substring test (`'int' in 'interval'`), so an interval PK crashes the table |
| D-13.07-16 | S3 | packaged | `top_level_postgres_checksum.py:713-716` vs `postgres_table_checksum.py:239-242` | reproduced (P3 arithmetic: `equal=False` at 2^32+10 rows) | The CH `sum(Int64)` wraps while the PG sum is exact, giving a false mismatch beyond about 2^32 rows |
| D-13.07-17 | S3 | packaged | `top_level_postgres_checksum.py:139-230`, `:1505-1530`, `:1993-2004` | code-read | The LSN wait compares a physical standby LSN with the connector's logical offset (maximum over all offset rows), and proceeds after the timeout |
| D-13.07-18 | S3 | packaged | `top_level_postgres_checksum.py:1850-1918`, `:1932-1947`; `ch_sink_tools/db/postgres.py:233` | code-read | The WAL replay and connector pauses have no time bound, auto_diff runs before the resume, and there is no watchdog against SIGKILL |
| D-13.07-19 | S3 | packaged | `auto_diff.py:954-981` | reproduced (A4 `ValueError`; A5 0 rows found) | The auto_diff key is the column named `id` or the first integer column, not the PK, so it crashes on a uuid `id` and misattributes on non-unique keys |
| D-13.07-20 | S3 | packaged | `auto_diff.py:731-732`, `:743-744` | reproduced (A3, A3b: `empty_table`) | auto_diff skips a table as empty when either side is empty, although every row then diverges |
| D-13.07-21 | S3 | packaged | `auto_diff.py:90`, `:157`, `:272-275`, `:301-303` | reproduced (A2: total 0) | The per-row maps collapse duplicate keys and the row text has no NULL bits, so count-only and NULL-vs-empty mismatches yield zero divergent rows |
| D-13.07-22 | S3 | packaged | `auto_diff.py:398`, `:936-943`; `top_level_postgres_checksum.py:1890-1891` | reproduced (A6: `ZeroDivisionError`); PG-only column path code-read | The auto_diff config is not validated, and its column list is all PG columns rather than the shared set |
| D-13.07-23 | S3 | packaged | `postgres_table_checksum.py:632-634`, `:794`; `ch_sink_tools/db_compare/postgres_table_count.py:77-80`, `:200` | reproduced (both exit 0 with every table failing) | `ch-pg-checksum` and `ch-pg-count` exit 0 when tables fail |
| D-13.07-24 | S3 | packaged | `top_level_postgres_checksum.py:1251-1254` | code-read | `skip_columns` given as a list (documented as global exclusions) is silently ignored |
| D-13.07-25 | S4 | packaged | `top_level_postgres_checksum.py:116-132`, `:1192`, `:1211` | code-read | Validation omits `source.postgres.database` (late `KeyError`), and `secure: "false"` enables TLS |
| D-13.07-26 | S4 | packaged | `top_level_postgres_checksum.py:833`, `:866-905`, `:1225-1226`, `:1654`, `:824-829`, `:2095-2098` | code-read | The tier thresholds and Tier-2/3 code are dead while `--help` advertises them; Phase A still runs the Tier-3 `FINAL` scan, and an extra `COUNT(*)` runs when stats are missing |
| D-13.07-27 | S4 | packaged | `sink-connector/python/README.md` Quick Start; `postgres_table_checksum.py:661` | reproduced (`ch-pg-count --config` gives an argparse error) | The README invokes `ch-pg-count --config`; `ch-pg-checksum` output is called "ClickHouse-compatible" but cannot be compared with `ch-ch-checksum` |
| D-13.07-28 | S4 | packaged | `ch_sink_tools/db/postgres.py:303-306`; `top_level_postgres_checksum.py:1449`, `:1967` | reproduced (P8) | The table regex and names are interpolated without escaping: a quote breaks the SQL, and `--table` metacharacters stay live |
| D-13.07-29 | S4 | packaged | `top_level_postgres_checksum.py:1393`, `:1399`, `:1950-1952` | code-read | When the WAL pause is disabled at step 0 and flush is off, the installed signal handlers are not restored |
