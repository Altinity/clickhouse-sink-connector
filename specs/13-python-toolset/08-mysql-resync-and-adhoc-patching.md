# Spec 13.08: Ad Hoc Repair and Re-synchronisation (`ch-mysql-resync`)

## 1. Executive Summary & Purpose
`ch-mysql-resync` is the toolset's ad hoc repair and patching tool. Use it when a
MySQL change never reached the binlog: a `SET sql_log_bin = 0` data patch, a
`DROP`/`CREATE` + reload of a schema, or a restore from a logical or physical
backup. The streaming connector cannot see such a change, so the ClickHouse
replica keeps stale rows. It also keeps rows the source no longer has. The
value-level checksum (spec 11.02, tool spec 13.06) reports the difference. The
tool conforms ClickHouse to MySQL again in three sub-commands:

1. `dump` records the source binlog position, then exports every selected base
   table to files with MySQL Shell `util.dumpTables`. MySQL Shell fetches the rows
   on the client side. The tool never uses the ClickHouse `mysql()` table function
   and never opens a MySQL connection of its own.
2. `patch` works schema by schema and table by table. It builds a scratch copy
   of each live table (`CREATE TABLE ... AS`), loads the dump into it with the
   toolset loader (spec 13.04), and compares the scratch row count with the dump
   row count. On request it runs a hash canary against the live table. It then
   swaps each partition into the live table with
   `ALTER TABLE ... REPLACE PARTITION ... FROM`, and finally compares live counts.
3. `rewind-sql` prints, and never runs, an `INSERT` into the connector's offset
   table. The `INSERT` moves one connector back to the recorded position, so the
   connector replays everything binlogged during the dump and the patch on top of
   the patched tables.

The procedure is described from the operator's side in spec 11.04. This spec is
the authoritative account of the code as built on 2.11.0: every flag, every
statement and its order, the destructive steps and what a crash between them
leaves behind, version and delete-flag semantics of reloaded rows, value
rendering, the dry-run contract, the rewind, and concurrency with a running
connector. It records 32 defects (§7). The most serious:

- binary columns are loaded in a different text form from the one the connector
  writes in its default mode (D-13.08-1);
- the packaged loader picks the time zone for `TIMESTAMP` parsing at random from
  the zones whose offset is `+00:00` on the day of the run, which can be a DST
  zone (D-13.08-2);
- reloaded rows of legacy-engine tables get `_sign = 0` (D-13.08-3);
- a pooled canary ratio hides a table whose rows all differ (D-13.08-4);
- nothing compares the connector's position with the recorded position, either
  before the replacement or in the rewind (D-13.08-5, D-13.08-6).

---

## 2. Codebase Mapping on 2.11.0
- **Tool (only implementation)**: `sink-connector/python/ch_sink_tools/db_load/mysql_resync.py` (673 lines)
  - Pure helpers: `q`, `sql_str`, `mysql_to_ch` (`MYSQL_TYPE_MAP`), `parse_mysql_ddl`, `column_drift`,
    `drift_ddl`, `plan_replace`, `plain_identifiers`, `rewind_offset_json`, `rewind_offset_sql`,
    `select_offset_row`, `isolate_table_dir`, `data_files`, `dump_tables`, `exact_dump_rows`.
  - Wrappers: class `ClickHouse` (`_run`, `rows`, `one`, `write`), which shells out to `clickhouse-client`;
    `mysqlsh_run`, `capture_binlog_position`; the JavaScript program `DUMP_JS`.
  - Sub-commands: `cmd_dump`, `cmd_patch` (with the inner `one_table` and `set_status`), `cmd_rewind_sql`,
    plus `loader_command`, `run_loader`, `column_names`, `canary_ratio`, `log`, `main`.
- **Legacy-tree shim**: `sink-connector/python/db_load/mysql_resync.py` (16 lines). It puts
  `sink-connector/python` on `sys.path` and re-exports only `main` from the packaged module
  (`from ch_sink_tools.db_load.mysql_resync import main`). Its only behaviour is
  `sys.exit(main())`. There is no legacy copy of the tool, so the two trees cannot diverge here (§3.14).
- **Loader that the tool spawns** (spec 13.04 owns it): `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py`
  (default, via `python -m ch_sink_tools.db_load.clickhouse_loader`). The legacy
  `sink-connector/python/db_load/clickhouse_loader.py` is used only if the operator passes `--loader-cmd`.
  Paths reached from the tool: `main`, `load_schema_mysqlshell` (in dry-run mode, to build the column map),
  `get_unix_timezone_from_mysql_timezone`, `get_column_list`, `load_data`, `load_data_mysqlshell`, `execute_load`.
  The DDL-to-column map comes from `convert_to_clickhouse_table_antlr` in
  `sink-connector/python/ch_sink_tools/db_load/mysql_parser/mysql_parser.py`. The loader also uses
  `resolve_credentials_from_config` and `clickhouse_connection` from
  `sink-connector/python/ch_sink_tools/db/clickhouse.py` (spec 13.02), and `is_binary_datatype` from
  `sink-connector/python/ch_sink_tools/db/mysql.py`.
- **Console script**: `ch-mysql-resync = ch_sink_tools.db_load.mysql_resync:main` in `sink-connector/python/pyproject.toml`.
  No Dockerfile installs or invokes the tool. `sink-connector/python/Dockerfile_db_load` ships `mysqlsh` only.
- **Tests (offline)**: `sink-connector/python/db_load/tests/test_mysql_resync.py` (run by
  `.github/workflows/spec-governance.yml` with `unittest`) and
  `sink-connector/python/db_load/tests/test_resync_failure_modes.py`. The second file is not in any workflow,
  but it runs in the repository's offline pytest suite. Both import the packaged module.
- **Docs that point here**: `sink-connector/python/README.md`, `AGENTS.md`, `doc/limitations.md`.
  `sink-connector-lightweight/tests/e2e/binlog_transaction_compression_edge.sh` defines `resync_table()` as a
  stand-in that uses the `mysql()` table function and a version above the maximum. It does not run this tool (§3.9.4).
- **Connector-side facts this spec relies on**: the `_version UInt64` / `is_deleted UInt8` / `_sign Int8` columns
  with no `DEFAULT` clause in
  `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java`
  and `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java`,
  with the constants in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseDbConstants.java`.
  The version formula is spec 02.01, the offset store is spec 09.03, binary rendering is spec 07.05 §3.2 and
  temporal rendering is spec 07.03.
- **Related specs**: 11.04 (procedure; §3.15 lists every deviation), 11.02 / 13.06 (the checksum that detects
  the divergence and proves the repair), 13.04 (loader), 13.02 (connection layer), 13.01 (packaging and the two trees).

---

## 3. Contract (Behaviour as Built)

### 3.1 Process model, prerequisites, credentials
- The tool runs as a single Python process. It runs no SQL driver itself. Every database action is a child
  process:
  - `mysqlsh` for `dump`;
  - `clickhouse-client` for every ClickHouse read and write the tool makes;
  - `sh -c "zstd -dc <file> | wc -l"` for row counts;
  - the loader, a Python child that starts one `sh -c "export TZ=...; zstd -d --stdout ... | clickhouse-client ..."`
    per data file.
- **MySQL credentials**: `dump` requires `MYSQL_PWD` (`cmd_dump`, `mysql_resync.py:308-310`). If it is missing,
  the tool calls `sys.exit("MYSQL_PWD must be set ...")` (exit 1). The password goes to `mysqlsh` on stdin, as
  the first line, with `--passwords-from-stdin` (`mysqlsh_run` `:268-272`, `cmd_dump` `:333-334`). It never
  appears on a command line. The child also inherits `MYSQL_PWD` from the environment
  (`env = dict(os.environ, ...)` `:330`). `--mysql-uri` is passed verbatim, so a password written into the URI
  would show on the `mysqlsh` command line. That is an operator error and the tool does not check for it.
- **ClickHouse credentials**: `--ch-config` names a clickhouse-client config file. The tool's own calls pass it
  as `clickhouse-client --config-file <cfg>` (`:246`), so the user and password never appear on its command
  lines. The loader receives `--clickhouse_config_file <cfg>`:
  - It reads `<user>` and `<password>` with `resolve_credentials_from_config` (spec 13.02). That function
    asserts the file name ends in `.xml`, `.yml` or `.yaml`, so any other name ends in `LOAD_FAILED`.
  - The **packaged** loader then builds its command from `args.clickhouse_password`, which the tool never sets
    (`clickhouse_loader.py:463-466`). No `--password` is emitted, and clickhouse-client takes the password from
    `--config-file`. The user is always emitted as `-u<user>`, so a config without `<user>` produces `-uNone`
    (reproduced, §3.9.1; D-13.08-25).
  - The **legacy** loader (only through `--loader-cmd`) puts the resolved password on the clickhouse-client
    command line (D-13.08-12, §3.14).
- **Secure transport**: the tool has no TLS flag. Its clickhouse-client calls get TLS only if the config file
  enables it. The loader is never given `--clickhouse_secure`, so its native connection object is created with
  `secure=False`. In the data-only path that connection never executes a statement (§3.9.1).
- **Programs on `PATH`**: `dump` needs `mysqlsh`. `patch` needs `clickhouse-client` and `zstd`, plus the loader's
  Python dependencies (`antlr4`, `clickhouse_driver`). The loader asserts both programs exist with
  `/usr/bin/which`. `rewind-sql` needs `clickhouse-client` only when `--ch-config` is given.
- **Python level**: `from __future__ import annotations` needs Python 3.7 or later, and the loader's `zoneinfo`
  needs 3.9 or later. `pyproject.toml` declares `>=3.6` (D-13.08-32; spec 13.01).

### 3.2 Command line (`main`, `mysql_resync.py:618-669`)
`argparse`, `prog="ch-mysql-resync"`, sub-command required (`dest="cmd"`). Every sub-command shares two options
(`common`, `:622-624`). Argument errors exit with code 2 (argparse).

| Flag | Type | Default | Effect | Honoured? |
|---|---|---|---|---|
| `--dump-base` | path | required (all three sub-commands) | Root for the dump directories, the position file and `patch_<stamp>/` | yes. `rewind-sql` requires it even when `--position-file` is given (it is used only to build the default position path) |
| `--stamp` | string | today's local date `YYYYMMDD` (`dt.date.today()`) | Names the dump set: `<schema>_<stamp>/`, `binlog_position_<stamp>.json`, `patch_<stamp>/` | yes. A `patch` or `rewind-sql` run on a later day without `--stamp` looks for today's set |

#### 3.2.1 `dump`
| Flag | Type | Default | Effect | Honoured? |
|---|---|---|---|---|
| `--mysql-uri` | `user@host:port` | required | `mysqlsh --uri` | yes |
| `--schemas` | 1+ names | required | One `util.dumpTables` per schema, in the given order | yes |
| `--tables` | regex | `.*` | `RESYNC_TABLES`, used as a JavaScript `new RegExp(...).test(name)` (unanchored search) over `information_schema.TABLES` base tables | yes (JavaScript regex dialect) |
| `--threads` | int | 8 | `RESYNC_THREADS` → `util.dumpTables({threads})` | yes |
| `--consistent` | flag | off | `RESYNC_CONSISTENT=true` → `{consistent: true}` (one `REPEATABLE READ` snapshot, which needs `RELOAD`/`BACKUP_ADMIN`) | yes |
| `--mysqlsh` | path | `mysqlsh` | Binary used for the position capture and the dump | yes |

Environment: `MYSQL_PWD` (required). The tool sets `RESYNC_SCHEMA`, `RESYNC_DIR`, `RESYNC_THREADS`,
`RESYNC_CONSISTENT` and `RESYNC_TABLES` for the `mysqlsh` child.

#### 3.2.2 `patch`
| Flag | Type | Default | Effect | Honoured? |
|---|---|---|---|---|
| `--schemas` | 1+ names | required | ClickHouse database name, which must equal the MySQL schema name. Also gives the dump directory `<schema>_<stamp>` | yes |
| `--tables` | regex | `.*` | Python `re.search` over the table names **found in the dump directory** (`:406,433`) | yes. A different dialect from `dump --tables` |
| `--ch-host` | host | required | clickhouse-client `--host`, loader `--clickhouse_host` | yes |
| `--ch-port` | int | 9000 | `--port` / `--clickhouse_port` (native protocol) | yes |
| `--ch-config` | path | required | Must exist, otherwise `sys.exit("ClickHouse client config not found")` (`:389-390`). Passed to clickhouse-client and to the loader | yes |
| `--apply` | flag | off | Without it, `ClickHouse.write` only logs `[DRY-RUN] would execute: <sql>` and the loader gets `--dry_run` | yes (§3.10) |
| `--drop-ch-only` | flag | off | Runs `ALTER TABLE ... DROP PARTITION ID` for replica-only partitions (`:557-558`). It goes through `write`, so a dry run only logs it | yes |
| `--restore-suffix` | string | `_restore` | Scratch database = `<schema><suffix>`. Not validated: an empty value makes the scratch database the live one (D-13.08-11) | yes |
| `--load-parallel` | int | 2 | `ThreadPoolExecutor(max_workers)`: tables counted and loaded at once | yes |
| `--load-threads` | int | 4 | Loader `--threads`: concurrent per-file INSERT pipelines per table | yes |
| `--skip-load` | flag | off | No scratch `DROP`/`CREATE`, no loader run. The dump is still counted and the scratch table is reconciled, canaried and replaced | yes. Nothing checks that the scratch table came from this stamp (D-13.08-8) |
| `--canary-list` | path | none | File with one `schema.table` per line (first tab-separated field, stripped). Listed tables are hash-compared after the load | yes, in apply mode or with `--skip-load` (§3.6) |
| `--canary-threshold` | float | 0.99 | Minimum **pooled** identical/joined ratio (`:519`) | yes (D-13.08-4) |
| `--force` | flag | off | Ignores a failed canary and continues to `REPLACE` | yes |
| `--loader-cmd` | string | none | `shlex.split` replacement for `[sys.executable, -m, ch_sink_tools.db_load.clickhouse_loader]` (`:345-348`) | yes |
| `--loader-cwd` | path | package root | `cwd` of the loader process (`:364`) | yes |

#### 3.2.3 `rewind-sql`
| Flag | Type | Default | Effect | Honoured? |
|---|---|---|---|---|
| `--offset-table` | `db.table` | required | Put into the SQL **verbatim**, with no quoting (D-13.08-23) | yes |
| `--offset-key` | string | none | Selects the connector row. May be omitted only when the table is read and holds exactly one row | yes. Never checked against the table when `--ch-config` is absent (D-13.08-7) |
| `--position-file` | path | `<dump-base>/binlog_position_<stamp>.json` | Position source | yes |
| `--ch-host` | host | none | Used only with `--ch-config`. If the config is given without it, the run crashes with `TypeError` (D-13.08-21) | partially |
| `--ch-port` | int | 9000 | as for `patch` | yes |
| `--ch-config` | path | none | If the file **exists**, the offset rows are read. If the path is given but missing, it is **silently ignored** (`os.path.isfile`, `:595`) | partially |

### 3.3 `dump` (`cmd_dump`, `:307-342`)
1. `os.makedirs(dump_base, exist_ok=True)`.
2. **Position capture.** This step runs only when `binlog_position_<stamp>.json` does not exist (`:312-319`).
   `capture_binlog_position` (`:275-286`) runs
   `mysqlsh --passwords-from-stdin --uri <uri> --sql --result-format=json/raw -e "SELECT NOW() AS taken_at, @@hostname AS source_host, UNIX_TIMESTAMP() AS ts_sec; SHOW MASTER STATUS;"`.
   - Every stdout line that starts with `{` is parsed as JSON. The first document with `taken_at` supplies the
     metadata, and the first with `File` supplies the position.
   - The file written (`json.dump`, indent 2) is
     `{taken_at, source_host, ts_sec:int, file, pos:int, gtid_executed}`. `taken_at` is the source session's
     `NOW()` in the source session zone, and `ts_sec` is the source's `UNIX_TIMESTAMP()`.
   - A non-zero `mysqlsh` exit raises `RuntimeError("mysqlsh SHOW MASTER STATUS failed ...")`. No `File` row
     raises `RuntimeError("SHOW MASTER STATUS returned no row ...")`, which is the case when binary logging is
     off or `REPLICATION CLIENT` is missing. Either way the result is a traceback with exit 1.
   - `SHOW MASTER STATUS` does not exist on MySQL 8.4 and later (D-13.08-20).
   - If the file already exists, the run logs `binlog position already captured: ... (kept ...)`, so the
     position always predates the first table read of the whole dump set.
3. **Per schema, in order** (`:321-341`):
   - If `<dump_base>/<schema>_<stamp>/@.done.json` exists, the run logs `SKIP <schema>: already complete` and
     goes to the next schema.
   - If the directory exists without that file, the run logs `ERROR <schema>: <dir> exists but is incomplete -- move it away and rerun`
     and sets `rc_all = 1`. It never resumes or overwrites the directory.
   - Otherwise it runs `mysqlsh --passwords-from-stdin --uri <uri> --js -e DUMP_JS` with the `RESYNC_*`
     environment. There is **no timeout** (D-13.08-22).
   - `DUMP_JS` (`:289-301`) first runs
     `SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME`
     (bound parameter) and filters the names through the regex. It then calls
     `util.dumpTables(schema, tables, dir, {threads, consistent, compression: "zstd", chunking: true, bytesPerChunk: "256M", triggers: false, showProgress: false, defaultCharacterSet: "utf8mb4"})`.
     Views, routines, events and triggers are not exported. Rows are fetched on the client side by MySQL Shell
     and written as chunked, zstd-compressed TSV (`<schema>@<table>@@<n>.tsv.zst`), together with the
     per-table `.sql` (DDL) and `.json` (metadata) files.
   - `mysqlsh` stdout and stderr are **appended** to `<dump_base>/dump_<schema>_<stamp>.log`.
   - A non-zero exit, or no `@.done.json` afterwards, logs `FAILED <schema> rc=<rc>: <last 400 chars of stderr>`
     and sets `rc_all = 1`. Otherwise the run logs `OK <schema>`.
   - If no table matches, `util.dumpTables` is called with an empty list. Whether MySQL Shell accepts that is
     library behaviour that was not verified offline.
4. The exit code is `rc_all` (0 or 1). `dump` writes nothing to ClickHouse.

### 3.4 `patch` phase 1: per schema, load and reconcile (`cmd_patch`, `:387-516`)
Setup (`:389-405`):
- Check that the config exists, and set `ts = now()` as `%Y%m%d_%H%M%S` (local time).
- Create `outdir = <dump_base>/patch_<stamp>/`. Open `patch_<ts>.log` in append mode as the global `LOG_FILE`.
  Plan the paths `report_<ts>.tsv`, `schema_drift_<ts>.sql` and `drop_ch_only_<ts>.sql`.
- Build `ClickHouse(host, config, apply, port)`. Log the banner, then run `SELECT version()` and
  `SELECT hostName()`. These are reads, and they run in a dry run too.
- Read the canary list, if one was given.

For each schema in `--schemas` order, with `restore_db = schema + suffix` and
`dump_dir = <dump_base>/<schema>_<stamp>`, the statements below run in this order. "W" marks a statement sent
through `write`, which only logs it in a dry run.

| # | Statement / action | Kind | Condition |
|---|---|---|---|
| 1 | Check that `dump_dir/@.done.json` exists. If not, add the report row `[schema, "*", "DUMP_INCOMPLETE"]` and go to the next schema | file | always |
| 2 | `dump_tables(dump_dir, schema)`: the `<schema>@<x>.sql` files, with `x` taken after the first `@`. Filter by `--tables` | file | |
| 3 | `SELECT name, engine, partition_key, sorting_key, total_rows FROM system.tables WHERE database = '<schema>'` | read | |
| 4 | Every live table that is not in the dump and whose engine is not `View` → two commented lines in the drop file (`-- DESTRUCTIVE (never executed ...) rows=<total_rows>` / `-- DROP TABLE <schema>.<t>;`) | file | never executed (D-13.08-14) |
| 5 | `CREATE DATABASE IF NOT EXISTS `<restore_db>`` | W | |
| 6 | Per selected table, in sorted order: a table not in `system.tables` → `NOT_IN_CH`. An engine other than exactly `ReplacingMergeTree` → `ENGINE_<engine>`, which includes `ReplicatedReplacingMergeTree` (D-13.08-19) | — | |
| 7 | `parse_mysql_ddl(<schema>@<t>.sql)`, then `SELECT name, default_kind FROM system.columns WHERE database='<schema>' AND table='<t>' ORDER BY position`, then `column_drift` | read | |
| 8 | If MySQL has a column the live table lacks → `SCHEMA_DRIFT` with the column list, and `drift_ddl` lines in the drift file. Live-only columns are logged as "kept, filled with defaults" | file | |
| 9 | `DROP TABLE IF EXISTS `<restore_db>`.`<t>`` then `CREATE TABLE `<restore_db>`.`<t>` AS `<schema>`.`<t>`` | W, W | not `--skip-load` |
| 10 | In a thread pool (`--load-parallel` workers, `ex.map` in table order), `one_table`: build the hard-link directory `dump_dir/_bytable/<t>/` (`isolate_table_dir`), run `exact_dump_rows` (`zstd -dc <f> \| wc -l` per file, serially), then `run_loader` unless `--skip-load` | file, shell, child process | the hard-link directory is created **in dry runs too** |
| 11 | Loader exit code ≠ 0 → `LOAD_FAILED` | — | |
| 12 | Dry run without `--skip-load`: add the report row `DRY_RUN` with the dump rows and queue the table. No further reads | — | |
| 13 | `SELECT count() FROM `<restore_db>`.`<t>`` (plain `count()`, no `FINAL`). Count ≠ dump rows → `COUNT_MISMATCH`, no `REPLACE` | read | apply, or `--skip-load` |
| 14 | Table in the canary list: `canary_ratio` (§3.6) | read | apply, or `--skip-load` |
| 15 | Queue the table and add the report row `LOADED` with `dump_rows`, `restore_rows` and the canary `same/n` | — | |

The loader command line (`run_loader`, `:351-365`) is:
```
<loader> --mysqlshell --data_only --rmt_delete_support --clickhouse_host <host> --clickhouse_port <port>
         --clickhouse_database <schema><suffix> --mysql_source_database <schema>
         --dump_dir <dump_dir>/_bytable/<t> --threads <load-threads> --clickhouse_config_file <cfg> [--dry_run]
```
It runs with `cwd = --loader-cwd or <package root>` and `PYTHONPATH=<package root>:$PYTHONPATH`. Its output
goes to `patch_<stamp>/load_<schema>.<t>.log`. There is no timeout (D-13.08-22). `--truncate_tables` is never
passed. Comment `:352` gives the reason: the loader's truncate targets `<mysql_source_database>.<table>`, which
is the live table.

### 3.5 Canary gate (evaluated once, before any replacement, `:518-533`)
- `canary_failed` is true in either of two cases. Either `canary_total > 0` and `canary_hits / canary_total`
  is below the threshold, where the totals are **summed over every canary table in every schema**. Or at
  least one canary table joined zero rows although its scratch count was above 0 (`canary_no_overlap`).
- The overall ratio is logged. In apply mode, if a canary list was given but no canary table was evaluated,
  the run logs a warning (`... the loader rendering is UNVERIFIED for this run`). No warning is printed when
  no list was given (FM-11.04-8).
- If the canary failed and `--force` is not set, the run logs `!! CANARY FAILED ... No REPLACE was issued ...`,
  marks every queued table `CANARY_FAILED`, and empties the queue. The scratch tables are kept.

### 3.6 `canary_ratio` (`:372-384`)
- The sorting key must be a plain comma-separated list of identifiers (`plain_identifiers`). Otherwise the
  function returns `None` and the table is skipped **without any log line** (D-13.08-24).
- It reads `SELECT name FROM system.columns WHERE database='<schema>' AND table='<t>'`. If a column named
  `is_deleted` exists, the hash excludes `(_version, is_deleted)` and the live side is filtered with
  `WHERE is_deleted = 0`. Otherwise only `(_version)` is excluded and there is no filter.
- Statement:
  ```
  SELECT countIf(r.h = l.h), count()
  FROM (SELECT <pk>, cityHash64(* EXCEPT <exc>) AS h FROM `<restore_db>`.`<t>`) AS r
  INNER JOIN (SELECT <pk>, cityHash64(* EXCEPT <exc>) AS h FROM `<schema>`.`<t>` FINAL <where>) AS l USING (<pk>)
  SETTINGS join_algorithm = 'parallel_hash'
  ```
- `*` does not expand to MATERIALIZED or ALIAS columns. Rows present on only one side are ignored.
- Columns the hash still covers: `_sign` on legacy-engine tables (scratch `0`, live `1`, so every row
  differs), `_is_deleted` when the source has its own `is_deleted` column, and live-only columns (scratch
  default against live value). Any of these makes the canary fail on rendering-neutral differences.
- The canary runs only when the scratch table has really been loaded (apply mode or `--skip-load`). A dry run
  without `--skip-load` evaluates none.

### 3.7 `patch` phase 2: replace, replica-only partitions, verify (`:535-565`)
For each queued table, in queue order:

| # | Statement | Kind | Condition |
|---|---|---|---|
| 1 | `SELECT partition_id, sum(rows) FROM system.parts WHERE database='<schema>' AND table='<t>' AND active GROUP BY partition_id` → `lparts`, `before` | read | always |
| 2 | Dry run without `--skip-load`: log `DRY_RUN live_partitions=... (REPLACE plan is computed from the scratch table after the load ...)`, call `set_status(DRY_RUN, ["n/a","n/a",before,""])` and stop for this table | — | D-13.08-29, D-13.08-30 |
| 3 | The same query on `<restore_db>` → `rparts` | read | |
| 4 | `plan_replace`: unpartitioned (`partition_key == ""`) → `ALTER TABLE `<schema>`.`<t>` REPLACE PARTITION ID 'all' FROM `<restore_db>`.`<t>`` when either side has parts, nothing when neither does. Partitioned → one statement per scratch partition id, in sorted order. `ch_only` = live ids minus scratch ids | W each | |
| 5 | Per `ch_only` id, in sorted order: add a `-- DESTRUCTIVE: partition id <pid> (rows=<n>) ...` comment and the statement `ALTER TABLE `<schema>`.`<t>` DROP PARTITION ID '<pid>';` to the drop file. The statement is executed only with `--drop-ch-only` | file / W | |
| 6 | `SELECT count() FROM `<schema>`.`<t>`` (plain `count()`). `expect = dump_rows + (0 if --drop-ch-only else Σ lparts[ch_only])`. Equal → `REPLACED_OK`, otherwise `REPLACED_VERIFY_FAIL` | read | apply only. A dry run with `--skip-load` gets `DRY_RUN_PLANNED` |

Then the run writes `report_<ts>.tsv`, with the columns `schema table status dump_rows restore_rows canary
partitions_replaced ch_only_partitions live_before live_after` and each row padded or truncated to 10 fields.
It writes the drift file and the drop file, logs the status counts, the exit decision (§3.12) and either the
`next: rewind-sql` advice or `== FAILED ... do not rewind`, closes `LOG_FILE`, and returns.

### 3.8 Destructive steps, their guards, and the state a crash leaves
| Step | Target | Guard | Reversible? |
|---|---|---|---|
| `DROP TABLE IF EXISTS <schema><suffix>.<t>` | scratch, by **name** | `--apply` and not `--skip-load`. No check that `<schema><suffix>` is not a live or foreign database (D-13.08-11) | no (Atomic databases keep a dropped table for `database_atomic_delay_before_drop_table_sec`; `UNDROP TABLE` exists on recent servers. Not verified offline) |
| `REPLACE PARTITION ... FROM scratch` | live partition | `--apply`, count reconcile done earlier in phase 1, canary gate (unless `--force`) | the live partition's old parts become inactive and are removed after `old_parts_lifetime`. The tool keeps no copy. The scratch table still holds the new data |
| `DROP PARTITION ID` | live partition | `--apply` and `--drop-ch-only`. No row-count limit, no confirmation | no |
| `CREATE DATABASE IF NOT EXISTS`, `CREATE TABLE AS`, the loader's INSERTs | scratch | `--apply` | n/a |

The tool never issues `TRUNCATE`, `DELETE`, `ALTER ... DELETE/UPDATE` or `DROP TABLE` against a live table,
except through a mis-set suffix (D-13.08-11).

Crash analysis (process killed, connection cut, or a statement fails). Every unhandled `RuntimeError` exits
with a traceback, writes no report, and leaves `LOG_FILE` unclosed. Its lines are already flushed.

| Killed after | Live table | Scratch | Lost / duplicated? | Resume |
|---|---|---|---|---|
| scratch `DROP`, before `CREATE` | untouched | absent | nothing | re-run without `--skip-load` |
| `CREATE`, during or after a partial load | untouched | partial | nothing. A `--skip-load` re-run catches the partial load with `COUNT_MISMATCH`. A load with rc 0 that lost a `zstd` stream (no `pipefail` in the loader pipeline) is also caught by the count | re-run without `--skip-load` |
| reconcile/canary, before the first `REPLACE` | untouched | complete | nothing | `--skip-load` |
| the k-th `REPLACE` of n | partitions 1..k hold the dump state. They lack connector writes made after the table was read, until the rewind replays them. Partitions k+1..n keep the stale pre-repair state | complete | nothing lost that the rewind cannot replay (binlog retention permitting). `REPLACE` never duplicates rows | `--skip-load` re-issues every `REPLACE` (idempotent; FM-11.04-1) |
| a `DROP PARTITION` (with `--drop-ch-only`) | some replica-only partitions dropped | complete | the dropped rows are gone. They are rows MySQL did not have, or rows the connector wrote after the dump into a partition the dump had no rows for. The rewind replays those | re-run. Already-dropped ids are no longer listed |
| verify | replaced | complete | nothing | re-run with `--skip-load` for the report |

One duplication risk is outside the tool: a row whose partition-key value changed lives in two partitions. If
the old partition is replica-only and kept, the stale copy survives next to the reloaded one, because
`ReplacingMergeTree` does not merge rows across partitions.

### 3.9 Data path: rendering, versions, deletes, time zones

#### 3.9.1 What the loader inserts (reproduced)
An offline run of the packaged loader's `main()` with the exact argument vector of `run_loader`
(`clickhouse_connection`, `check_program_exists` and the shell runner mocked) produces this for the DDL
`id int, b blob, vb varbinary(16), bt bit(8), ts timestamp(3) NULL, dt datetime(6), is_deleted tinyint`:
```
export TZ=Etc/UCT; zstd -d --stdout <dump>/_bytable/t/mydb@t@@0.tsv.zst | clickhouse-client --config-file '<cfg>'
  --use_client_time_zone 1 --throw_if_no_data_to_insert=0 --max_partitions_per_insert_block=1000 -h ch-host --port 9000
  --query="INSERT INTO mydb_restore.t(`id`,`b`,`vb`,`bt`,`ts`,`dt`,`is_deleted`)
           SELECT `id`,`b`,`vb`,`bt`,`ts`,`dt`,`is_deleted`
           FROM input(' `id` String, `b` Nullable(String), `vb` String, `bt` Nullable(String),
                        `ts` Nullable( DateTime64(3)), `dt` String, `is_deleted` String') FORMAT TSV" -uu1 -mn
```
- In a dry run the same line is logged followed by `dry-run not executing`. No statement and no shell command
  is executed. The two native connections (`default`, `<restore_db>`) are created but never used.
- The column map is built by `load_schema(..., dry_run=True)` (`clickhouse_loader.py:615`), so data-only mode
  issues no `CREATE`.
- The INSERT column list is the MySQL DDL's non-generated columns minus the virtual columns `_sign`,
  `_version`, `is_deleted` and `_is_deleted`. A source column literally named `is_deleted` is kept
  (`has_is_deleted_column`).
- Every non-`timestamp` column arrives as `String` or `Nullable(String)` and is cast server-side to the
  scratch column type. `timestamp` columns arrive typed (§3.9.3).
- Names are unquoted in `INSERT INTO <db>.<t>`. The data-file path is not shell-quoted (D-13.08-26).
- If the antlr converter fails, the regexp fallback runs. Its column dictionaries lack `generated`,
  `mysql_datatype` and `has_is_deleted_column`, so `load_data_mysqlshell` raises `KeyError` → `LOAD_FAILED`
  (code-read, `clickhouse_loader.py:496-503`).

#### 3.9.2 Binary columns (D-13.08-1, S1)
`get_column_list(..., transform=True, mysqlshell=True)` would render binary columns as
`if(col='\N', null, lower(hex(base64Decode(col))))` (`clickhouse_loader.py:399-401`). The branch is guarded by
`is_binary_datatype(column['datatype'])`. On the antlr path, `datatype` holds the **ClickHouse** type
(`String`), not the MySQL type, so the guard is false for `blob`, `varbinary` and `bit` and the branch never
runs (reproduced above: `` `b` ``, `` `vb` ``, `` `bt` `` are selected verbatim). The scratch table, and after
`REPLACE` the live table, therefore holds the dump file's text for each binary value. MySQL Shell documents that
binary columns are base64-encoded in its TSV dumps (not verified offline).

The connector, by contrast, stores lower-case hex under its default `binary.handling.mode=bytes` (spec 07.05 §3.2).
The resync text cannot equal that hex: neither base64 nor raw bytes is lower-case hex. Under
`binary.handling.mode=base64` (the shipped templates' setting) the two can agree only if MySQL Shell's base64 has
no line breaks. MySQL's `TO_BASE64()` wraps every 76 characters, and which encoder MySQL Shell uses was not
determinable offline. Without a canary table that holds binary values, the difference is installed silently and
is reported only by the next checksum.

#### 3.9.3 Time zones (D-13.08-2, S1, packaged loader)
- `load_schema_mysqlshell` fixes the dump zone at `'+00:00'` (`clickhouse_loader.py:347`). MySQL Shell's
  default `tzUtc` writes `TIMESTAMP` values in UTC.
- `get_unix_timezone_from_mysql_timezone` (`:268-285`) walks `zoneinfo.available_timezones()`. That is a `set`:
  the `sorted(timezones)` call on `:271` discards its result. The function returns the **first zone in hash
  order whose offset at the moment of the run equals `+00:00`**. String hashing is randomised per process, so
  the zone changes from run to run.
- Reproduced with "now" pinned to 2026-01-15 over `PYTHONHASHSEED` 0..29: 9 of 30 runs chose a zone with
  summer time (`Europe/Dublin` ×4, `WET`, `Atlantic/Faroe`, `Europe/Jersey`, `Europe/Guernsey`: +01:00 in July.
  `Antarctica/Troll`: +02:00 in July). Two live runs today produced `Etc/UCT` and `GMT-0`.
- The loader exports `TZ=<zone>` with `--use_client_time_zone 1`, and the `ts` field is parsed as
  `DateTime64(3)` in that client zone. A winter run that picks a DST zone stores every summer-dated `TIMESTAMP`
  1 or 2 hours early.
- The legacy loader sorts the zone list and returns the first match (`Africa/Abidjan`, which has no DST) or
  `UTC`. It is deterministic and correct for `+00:00`.
- `DATETIME` columns arrive as `String`. The server converts them into the scratch column type. For a
  connector-created `DateTime64(p, 'UTC')` column (spec 07.03 §3.1.3) the digits are kept, which matches the
  connector. For a column with no zone, the server or session zone applies (ClickHouse semantics, not verified
  offline).
- Zero dates and values outside the `DateTime64` range are converted by the server, not clamped the way the
  connector clamps them (spec 07.03). The outcome was not verified offline. The canary is the only guard.

#### 3.9.4 `_version`, `is_deleted`, `_sign` of reloaded rows, and ranking against streamed rows
- Reloaded rows carry no value for any virtual column, so each takes the **column default of the live table**
  (copied by `CREATE TABLE AS`):
  - Connector-created tables declare `_version UInt64`, `is_deleted UInt8` / `_is_deleted UInt8` and (legacy
    engine) `_sign Int8` with no `DEFAULT` (`ClickHouseAutoCreateTable.java:268-282`,
    `MySqlDDLParserListenerImpl.java:781-787`). Every reloaded row therefore has `_version = 0`,
    `is_deleted = 0` and `_sign = 0`.
  - Loader-created tables declare `_version UInt64 DEFAULT 0` and, for the regexp converter's legacy layout,
    `_sign Int8 DEFAULT 1`.
- Every version the connector writes is at least about 1.8·10^18 (spec 02.01). It never writes 0 (refused,
  spec 02.05). A reloaded row therefore **ranks below every streamed row** and can never win against a later
  legitimate update. That is correct.
- Can a reloaded row lose to a stale streamed row?
  - Not to rows present **before** the `REPLACE`: those rows are removed with the partition, including
    `is_deleted = 1` tombstones and any row carrying the historical `UInt64` maximum sentinel.
  - Yes to any streamed row written **after** the `REPLACE`, whatever source position it came from. Three
    cases:
    1. The intended rewind replay. It re-applies after-images from the recorded position onward and converges
       to the newest state.
    2. A connector that was behind the unlogged change while the `REPLACE` ran. It re-applies older row images
       with high versions over the repaired rows, and nothing later corrects them (D-13.08-5, S1).
    3. A forward move of the offset (D-13.08-6).
- **Legacy engine** (`ReplacingMergeTree(_version)` with `_sign`): reloaded rows have `_sign = 0`. The connector
  writes `1` for live rows and `-1` for deletes. Consumers and the toolset checksum filter `_sign > 0`
  (`sink-connector/python/ch_sink_tools/db_compare/clickhouse_table_checksum.py:233-234`), so every reloaded
  row disappears from those views while the tool reports `REPLACED_OK` (D-13.08-3, S1, code-read).
- The e2e harness's `resync_table()` uses another design: an INSERT with a version above the maximum, plus
  tombstones for missing keys. Its behaviour is not evidence for this tool.

#### 3.9.5 Rows deleted in MySQL
| Location | Removed? |
|---|---|
| Unpartitioned table | yes. `REPLACE PARTITION ID 'all'` replaces everything, and an empty dump empties the table |
| Partitioned table, partition with at least one dump row | yes, the whole partition is replaced |
| Partitioned table, partition with no dump rows | **no** by default. The partition is listed in the drop file and kept, and `expect` counts it, so `REPLACED_OK` and exit 0 follow. It is dropped only with `--drop-ch-only` |
| Whole table absent from the dump | never. It appears as a commented `DROP TABLE` line |

### 3.10 Dry run versus apply
- The dry run is the default. It issues **no ClickHouse write**: every DDL and DML statement goes through
  `ClickHouse.write`, which logs `[DRY-RUN] would execute: <sql>` and returns. The loader receives `--dry_run`
  and executes nothing (§3.9.1).
- Reproduced with the real `ClickHouse` class and `subprocess.run` mocked. The only commands that reach a
  process in a dry run are `SELECT version()`, `SELECT hostName()`, the `system.tables` / `system.columns` /
  `system.parts` reads, the `zstd | wc -l` counts and the loader child in dry-run mode.
- The dry run **does** write files: `patch_<stamp>/` with the log, report, drift and drop files, the per-table
  loader logs, and the hard links under `<dump>/_bytable/`.
- Without `--skip-load`, a dry run does not read the scratch table, so it prints **no** `REPLACE` or
  `DROP PARTITION` plan and evaluates no canary. With `--skip-load` (on a scratch table that already exists) it
  prints the exact plan, status `DRY_RUN_PLANNED`. If the scratch table is missing, the `count()` read fails
  and the run exits with a traceback.
- A dry run exits 0 even when the apply would fail, because `SCHEMA_DRIFT`, `NOT_IN_CH` and `ENGINE_*` count as
  failures only with `--apply` (D-13.08-30). Its report row is misaligned (D-13.08-29).

### 3.11 `rewind-sql` (`cmd_rewind_sql`, `:592-615`)
- Read the position JSON, from `--position-file` or the default path. A missing file raises
  `FileNotFoundError` (traceback, exit 1).
- If `--ch-config` names an existing file, read
  `SELECT offset_key, offset_val FROM <offset_table> FINAL ORDER BY offset_key` through
  `ClickHouse(apply=False)`. Output is TSV, split on tab, with no unescaping.
  - If `--ch-config` is absent and `--offset-key` is also absent, exit with the message `--offset-key is required ...`.
  - If rows came back, `select_offset_row`: with a key, exactly one row must match, otherwise `ValueError`.
    Without a key, the table must hold exactly one row, otherwise `ValueError`.
  - If no rows came back (an empty table, or no `--ch-config`), the given key is used **unchecked** and
    `current = ""`.
- `rewind_offset_json` → `{"ts_sec":<pos.ts_sec or 0>,"file":<file>,"pos":<pos>,"row":0,"server_id":<current server_id or 0>,"event":0}`.
  No `gtids`, no `snapshot` field.
- `rewind_offset_sql` refuses an empty key (`ValueError`) and otherwise returns:
  ```
  INSERT INTO <offset_table> (id, offset_key, offset_val, record_insert_ts, record_insert_seq)
  SELECT id, offset_key, '<new offset json>', now(), record_insert_seq + 1
  FROM <offset_table> FINAL
  WHERE offset_key = '<key>';
  ```
  `sql_str` escapes `\` and `'`. The row reuses the old `id`. Which row wins is decided by the table's own
  version column: the shipped DDL uses `_version UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9))`, the
  ClickHouse server clock (spec 09.03). A load query that orders by `record_insert_ts, record_insert_seq` also
  sees the new row last.
- It prints comment lines (target key and table, the recorded file:pos, `taken_at`, `source_host`,
  `gtid_executed`, the five-step procedure, a reminder to check `SHOW BINARY LOGS`, and, when the table was
  read, the current `offset_val`) followed by the SQL. It returns 0.
- It never writes to ClickHouse and never contacts MySQL.
- **Interaction with a running connector.** The printed procedure says to stop the connector first. Nothing
  enforces it: a running connector's next offset flush supersedes the row (FM-11.04-6, D-13.08-9).
- The current `offset_val` is read but its `file` and `pos` are never compared with the recorded position, so
  a connector that is **behind** that position is moved **forward** (reproduced, D-13.08-6).
- The replay re-applies every captured table of that connector, not only the patched ones. That is harmless on
  `ReplacingMergeTree` targets. On history (SCD2) or non-RMT targets it is not (spec 12.03).

### 3.12 Exit codes, statuses, logging
| Sub-command | 0 | 1 | 2 |
|---|---|---|---|
| `dump` | every schema complete or skipped | any schema failed or was refused. `MYSQL_PWD` missing (`sys.exit` message). Traceback from the position capture | argparse |
| `patch` | no failure as defined below | any row in `FAILED_STATUSES` (`LOAD_FAILED`, `COUNT_MISMATCH`, `CANARY_FAILED`, `REPLACED_VERIFY_FAIL`, `DUMP_INCOMPLETE`). With `--apply`, also `SCHEMA_DRIFT`, `NOT_IN_CH`, `ENGINE_*`. Missing config (`sys.exit`). Any uncaught `RuntimeError` / `OSError` (traceback) | argparse |
| `rewind-sql` | SQL printed | `sys.exit` message. `ValueError` (ambiguous or absent key). `FileNotFoundError`. `TypeError` (D-13.08-21) | argparse |

Statuses written to the report:
- `DUMP_INCOMPLETE` (table `*`), `NOT_IN_CH`, `ENGINE_<engine>`, `SCHEMA_DRIFT` (plus the column list),
  `LOAD_FAILED`;
- `DRY_RUN`, `COUNT_MISMATCH`, `CANARY_FAILED`;
- `DRY_RUN_PLANNED`, `REPLACED_OK`, `REPLACED_VERIFY_FAIL`.

`LOADED` is transient: phase 2 always overwrites it, unless the canary gate emptied the queue, in which case
the table shows `CANARY_FAILED`.

Logging: `log()` prints `<local ISO time> <msg>` to stdout (flushed). During `patch` it also writes to
`patch_<ts>.log`. Full SQL is logged. No secret is logged by the tool. `ClickHouse._run` errors carry up to
1500 characters of stderr and the first 400 characters of the SQL. The loader logs its full command line at
INFO (packaged: no password present. Legacy: password redacted in the log but present in the process list).

### 3.13 Concurrency and ordering
- Phase 1 finishes for **every** schema before phase 2 starts. For each table, the time between the dump read
  and its `REPLACE` therefore includes the load of every other table. The rewind must cover this window.
- Inside a schema, `--load-parallel` tables run at once. Each runs `--load-threads` concurrent
  `zstd | clickhouse-client` INSERT pipelines (one per data file). `exact_dump_rows` is serial per table.
  `LOG_FILE` is written only from the main thread.
- **Connector running during `patch`** (the tool neither stops nor checks it):
  - It keeps writing to live tables. Its writes to a partition between the dump read and the `REPLACE` are
    wiped by the `REPLACE` and come back only through the rewind.
  - Its writes after the `REPLACE` win over the reloaded rows (`_version` 0). That is correct when its position
    is past the recorded position, and harmful when it is behind the unlogged change (D-13.08-5).
  - Its writes between `before` and the verify `count()` make `after > expect`, giving a spurious
    `REPLACED_VERIFY_FAIL` (D-13.08-18).
  - A DDL it applies to a live table after the scratch `CREATE TABLE AS` makes `REPLACE PARTITION` fail with a
    structure mismatch (FM-11.04-1).
- **Two `patch` runs at once**: nothing locks or namespaces the scratch tables by stamp or run. One run can
  `DROP` and reload a scratch table that the other already reconciled and is about to `REPLACE` from
  (D-13.08-15).
- **Scratch lifetime**: scratch databases and tables are never dropped after success (D-13.08-31). Their parts
  share hard links with the live partitions they were copied into until merges rewrite them.

### 3.14 Legacy versus packaged copies
| Aspect | Packaged (`ch_sink_tools/db_load/mysql_resync.py`) | Legacy (`db_load/mysql_resync.py`) |
|---|---|---|
| Implementation | the whole tool | 16-line shim: adds `sink-connector/python` to `sys.path`, imports **only** `main` from the packaged module, runs `sys.exit(main())`. Same behaviour by construction |
| Console script / tests | `ch-mysql-resync` entry point; both test files import this module | not tested separately |
| Default loader | `python -m ch_sink_tools.db_load.clickhouse_loader` (packaged) | same: the shim runs packaged code |

The two trees differ in behaviour **only in the loader** (spec 13.04), which the operator can select with
`--loader-cmd "python db_load/clickhouse_loader.py"`. Differences on the resync path:

| Loader function | Packaged | Legacy |
|---|---|---|
| `get_unix_timezone_from_mysql_timezone` | first `+00:00` zone in **hash order** of a set, so random and possibly DST (D-13.08-2) | `sorted(...)`, first match (`Africa/Abidjan`), fallback `UTC`: deterministic |
| `load_data_mysqlshell` password | `args.clickhouse_password` (never set by resync), so no `--password`, and credentials come from `--config-file` | the resolved password: `--password <shlex-quoted>` **on the clickhouse-client command line** (reproduced: `--password p1`) and redacted only in the log (`redact_password`) (D-13.08-12) |
| Config path quoting | `'<path>'` | `shlex.quote` |
| `load_data` → `load_data_mysqlshell` | passes `dry_run=False` | passes `dry_run=dry_run`. No effect either way, because `execute_load` reads the global `args.dry_run` |
| Binary transform, column filter, `input()` structure, `TZ`/`--use_client_time_zone` | identical (D-13.08-1 applies to both) | identical |

### 3.15 Deviations from spec 11.04
1. **Dry-run contract** (11.04 §3.1 "every read runs, every write is printed"): without `--skip-load` the
   scratch count, canary and scratch-partition reads do not run, and no `REPLACE` or `DROP PARTITION` is
   printed (§3.10).
2. **Canary hash** (11.04 §3.3.5 `* EXCEPT (_version, is_deleted)`): `(_version)` alone when the table has no
   `is_deleted` column. When the source has its own `is_deleted` column, that column is excluded and filtered
   on as if it were the delete flag, and `_is_deleted` stays inside the hash (§3.6). Tables with an expression
   key are skipped without a log line. 11.04 is silent about the pooled ratio letting a fully mismatching
   table pass (D-13.08-4).
3. **Rewind key** (11.04 §3.5 "zero or several candidates are an error, never a guess"): zero candidates are
   accepted when the table is not read or is empty. The generated INSERT then matches no row (D-13.08-7).
4. **Rewind direction**: 11.04 calls it a rewind. The code never compares positions and can move forward (D-13.08-6).
5. **CLI synopsis** (11.04 §3.1): `rewind-sql` also has `--offset-key`, `--position-file` and `--ch-port`.
   `--dump-base` is required even with `--position-file`. `--ch-config` without `--ch-host` crashes, and a
   missing `--ch-config` file is ignored silently.
6. **Dump options** (11.04 §3.2): the tool also passes `threads`, `showProgress: false` and
   `defaultCharacterSet: "utf8mb4"`.
7. **Engine check** (11.04 §3.3.2 "not ReplacingMergeTree are skipped"): exact string comparison, so
   `ReplicatedReplacingMergeTree` and `SharedReplacingMergeTree` tables are skipped too. On a replicated
   cluster the tool repairs nothing (D-13.08-19).
8. **Live-only tables** (11.04 §3.3.2): any live table that is neither in the dump nor a `View` is listed as a
   `DROP TABLE` candidate. That includes tables excluded by `dump --tables`, materialized views and their
   `.inner` tables (D-13.08-14).
9. **Value rendering** (11.04 §3.6 "canary stops the run"): 11.04 does not state that binary columns are
   loaded verbatim (D-13.08-1), that the packaged loader's `TIMESTAMP` zone is random (D-13.08-2), or that
   legacy-engine rows get `_sign = 0` (D-13.08-3). With no canary list these differences are installed silently.
10. **Verify** (11.04 §3.3.8): a plain `count()` taken while the connector runs (D-13.08-18).
11. **Workflow** (11.04 §5): only `test_mysql_resync.py` runs in `.github/workflows/spec-governance.yml`.
    `test_resync_failure_modes.py` (which 11.04 §6 cites) runs only in the offline pytest suite.
12. **Suffix** (11.04 §3.3.3 "only the scratch copy is dropped"): this holds only for a non-empty suffix that
    names no live database (D-13.08-11).

### 3.16 Pure helpers (inputs → outputs, no side effects unless stated)
- `q(ident)`: wraps in backticks and doubles any embedded backtick. `sql_str(v)`: single-quoted literal with
  `\` and `'` backslash-escaped.
- `mysql_to_ch(type, nullable)`: the first matching regex in `MYSQL_TYPE_MAP` (`:73-86`), default `String`,
  wrapped in `Nullable(...)` when nullable. Used only for the drift suggestions. Notes: `datetime` with no
  precision maps to `DateTime64(3)`, `time` to `String`, `bit` to `String`.
- `parse_mysql_ddl(text)`: walks the lines from the first `CREATE TABLE` up to the first line starting with
  `)`. Every line starting with a backtick is a column: `(name, type, nullable, generated)`.
  - The type is `\S+` plus an optional `(...)`, `unsigned` and `zerofill`, so an `enum('a','b c')` type is
    truncated at the space. This affects only the suggestion.
  - Quoted literals are blanked before the `NOT NULL` and `GENERATED ALWAYS` tests.
- `column_drift(mysql_cols, ch_cols)`:
  - `writable` = live columns whose `default_kind` is not `MATERIALIZED` or `ALIAS`.
  - `mysql_only` = non-generated MySQL columns not in `writable` (case-sensitive comparison).
  - `ch_only` = sorted `writable - VIRTUAL_COLUMNS - mysql names`, where
    `VIRTUAL_COLUMNS = {_version, is_deleted, _sign, _is_deleted, __is_deleted}`.
- `drift_ddl`: one statement per missing column, in source order:
  ``ALTER TABLE `db`.`t` ADD COLUMN IF NOT EXISTS `c` <type> AFTER `prev`|FIRST;  -- MySQL: <type>[ NOT NULL]``.
- `plan_replace`: §3.7 step 4. `plain_identifiers`: §3.6.
- `select_offset_row`, `rewind_offset_json`, `rewind_offset_sql`: §3.11.
- `isolate_table_dir` (side effect: creates `_bytable/<t>/`, replaces symlinks, adds missing hard links. An
  existing regular file is kept even if stale).
- `data_files`: sorted `<schema>@<t>@*.tsv.zst` plus `<schema>@<t>.tsv.zst`, so `t` does not match `t_other`.
- `dump_tables`: §3.4 step 2. File names are used as table names unchanged. MySQL Shell percent-encodes
  special characters in file names, so such tables would come out as `NOT_IN_CH` (inferred, not verified offline).
- `exact_dump_rows` (side effect: shell): `zstd -dc <shlex-quoted file> | wc -l`, summed. A non-zero pipeline
  exit (that is, `wc`'s) raises `RuntimeError`.

---

## 4. Invariants Preserved
- **MySQL is the source of truth** (`AGENTS.md`, Constitution I13): every live-table write installs dump
  content. Replica-side values are kept only in partitions the operator chose to keep, and in live-only columns,
  which are reset to their defaults.
- **Live tables change only through per-partition atomic replacement** (`REPLACE PARTITION`), plus the
  opt-in `DROP PARTITION`. No `TRUNCATE`, `DELETE` or mutation. This holds only while the scratch-name guard
  of D-13.08-11 holds.
- **Count reconciliation before replacement**: each table's scratch `count()` equals the exact dump line count
  before it is queued. The check runs in phase 1, not immediately before the `REPLACE` (D-13.08-15).
- **No implicit offset move**: `rewind-sql` only prints.
- **Version ordering**: reloaded rows carry `_version = 0`, below every connector version (spec 02.01, 02.05).
  They never override a later legitimate update. That they are not overridden by stale events depends on the
  connector being at or past the recorded position, and the tool does not enforce that (D-13.08-5, D-13.08-6).
- **Fail loudly**: every selected table ends in an explicit status in the report. Any failure, or with
  `--apply` any unrepaired selected table, gives a non-zero exit, and the rewind advice is withheld.

---

## 5. Verification Criteria
Existing offline tests (with the toolset virtualenv, from `sink-connector/python`:
`python -m pytest -q -p no:cacheprovider db_load/tests/test_mysql_resync.py db_load/tests/test_resync_failure_modes.py`
gives 32 passed, 1 skipped):

- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestParseMysqlDdl::test_columns_in_order_with_nullability_and_generated`
  and `...::TestParseMysqlDdl::test_index_and_constraint_lines_are_not_columns` cover §3.16 `parse_mysql_ddl`.
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestTypeSuggestion::test_common_types` covers `mysql_to_ch`.
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestDrift::test_no_drift_when_every_writable_source_column_exists`,
  `...::TestDrift::test_source_only_column_is_reported_and_generated_is_ignored`,
  `...::TestDrift::test_materialized_target_column_does_not_satisfy_a_source_column`,
  `...::TestDrift::test_replica_only_column_is_listed_not_fatal` and
  `...::TestDrift::test_drift_ddl_keeps_source_position` cover `column_drift` and `drift_ddl`.
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestPlanReplace::test_unpartitioned_table_is_one_replace_of_the_all_partition`,
  `...::test_unpartitioned_empty_source_empties_the_replica`, `...::test_unpartitioned_both_empty_is_a_noop`,
  `...::test_partitioned_replaces_dump_partitions_and_lists_replica_only_ones` and
  `...::test_partitioned_empty_source_replaces_nothing_and_lists_everything` cover §3.7 step 4 and §3.9.5.
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestSortingKey::test_plain_columns` and
  `...::TestSortingKey::test_expression_key_disables_the_hash_join` cover §3.6.
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestRewind::test_offset_keeps_server_id_and_points_at_the_captured_position`,
  `...::test_offset_without_current_row`, `...::test_sql_inserts_a_newer_row_for_the_same_key_only`,
  `...::test_sql_refuses_an_unscoped_rewind` and `...::test_select_offset_row_requires_an_unambiguous_key`
  cover §3.11 pure parts.
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestCanaryGate::test_failed_canary_is_nonzero_and_replaces_nothing`,
  `...::test_force_overrides_the_canary_and_replaces`, `...::test_zero_joined_canary_rows_fail_closed` and
  `...::test_apply_fails_when_a_selected_table_is_skipped_for_schema_drift` cover §3.5 and §3.12.
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestDdlLiterals::test_default_literal_containing_not_null_is_still_nullable`.
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestDumpDirectory::test_table_list_and_data_files_do_not_bleed_across_prefixes`,
  `...::test_isolated_dir_uses_hard_links` and `...::test_exact_dump_rows_counts_newline_terminated_rows`
  (needs `zstd`).
- `sink-connector/python/db_load/tests/test_mysql_resync.py::TestIdentifiers::test_backtick_quoting`.
- `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestInterruptedPatch::test_interruption_leaves_scratch_tables_and_a_resume_completes` covers §3.8.
- `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestDumpRerun::test_rerun_keeps_the_position_skips_complete_and_refuses_incomplete` covers §3.3.
- `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestRewindAfterFailover::test_rewind_points_at_the_captured_file_and_position`.
  `...::test_rewind_carries_the_captured_gtid_set` is **skipped** (DEFECT FM-11.04-4, D-13.08-16).

Offline reproductions behind this spec (throwaway scripts, not in the tree, with no database or network
access; `subprocess.run`, `clickhouse_connection` and `check_program_exists` mocked):
1. Loader `main()` driven with `run_loader`'s argument vector, in dry-run and apply mode. Results: the INSERT
   template of §3.9.1, the binary columns selected verbatim, no statement executed in a dry run, `-uNone`
   with a config that has no `<user>`, and for the legacy loader `--password p1` on the shell command and
   `TZ=Africa/Abidjan`.
2. `get_unix_timezone_from_mysql_timezone('+00:00')` with "now" pinned to 2026-01-15 over 30 hash seeds:
   9 DST zones (§3.9.3).
3. `cmd_patch` with the real `ClickHouse` wrapper in apply, dry, dry+`--skip-load` and apply+`--drop-ch-only`
   modes: the statement sequence of §3.4 and §3.7, the dry-run report misalignment, and the false
   `DROP TABLE` candidate.
4. `cmd_patch` with three canary tables (1000/1000, 0/5, and one expression key): `REPLACED_OK` for all three, exit 0.
5. `cmd_rewind_sql`: the forward move, empty table plus key, no config plus an unchecked key, and an unquoted
   table name. Also `subprocess.run([... , None])` raises `TypeError`.
6. `--restore-suffix ""`: `DROP TABLE IF EXISTS `mydb`.`t``.

Acceptance criteria once the defects are fixed (each is a GAP test in §6): binary and `TIMESTAMP` values
reloaded by `patch` hash-equal the connector's for the configured `binary.handling.mode`. The legacy-engine
`_sign` equals 1. Every canary table must pass on its own. `rewind-sql` refuses a forward move and a key it
cannot see. `patch` refuses a suffix that resolves to an existing non-scratch database.

---

## 6. Failure Modes & Recovery

- **FM-13.08-1 Interrupted in phase 1 (before any REPLACE)**
  - **Trigger**: the process is killed, or a ClickHouse read fails, while scratch tables are created, loaded,
    counted or canaried.
  - **Behaviour**: `ClickHouse._run` raises `RuntimeError` (`mysql_resync.py:249-250`), and nothing catches it
    in `cmd_patch`. The run ends with a traceback, exit 1 and no report. No live table has been written
    (`REPLACE` happens only in phase 2, `:536`).
  - **Detection**: traceback and exit 1. The log shows the last `[APPLY]` line.
  - **Blast radius**: none on live tables. Scratch tables may be partial.
  - **Recovery**: re-run without `--skip-load`. The scratch tables are dropped and reloaded.
  - **RTO**: one reload of the selected tables (unmeasured, proportional to size).
  - **Test**: GAP: a `cmd_patch` test whose fake raises on the scratch `count()`, asserting no `REPLACE` and that a re-run reloads.

- **FM-13.08-2 Interrupted in phase 2**
  - **Trigger**: the process is killed, or a `REPLACE PARTITION` fails (connection cut, structure changed by a connector DDL).
  - **Behaviour**: as FM-11.04-1. Replaced partitions hold the dump state, the rest the stale state. Scratch
    tables are intact. `REPLACE` never duplicates rows (§3.8).
  - **Detection**: traceback and exit 1. The last `[APPLY] ALTER TABLE ... REPLACE PARTITION` line in `patch_<ts>.log`.
  - **Blast radius**: the affected table is temporarily at dump state in its replaced partitions.
  - **Recovery**: `--skip-load` with the same `--stamp` (without it after a structure change), then `rewind-sql`.
  - **RTO**: seconds per partition (unmeasured), plus the binlog replay.
  - **Test**: `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestInterruptedPatch::test_interruption_leaves_scratch_tables_and_a_resume_completes`.

- **FM-13.08-3 Binary columns installed in a different representation**
  - **Trigger**: a repaired table has a BLOB, BINARY, VARBINARY or BIT column, and the connector runs with
    `binary.handling.mode=bytes` (the default; lower-case hex) or `base64` with line-wrapped dump text.
  - **Behaviour**: the binary transform is unreachable because `is_binary_datatype` receives the ClickHouse
    type `String` (`clickhouse_loader.py:399`, both loaders). The dump text is inserted verbatim, count
    reconciliation passes, and `REPLACE` installs it.
  - **Detection**: a canary table with non-empty binary values fails. Without one, only the next checksum
    (11.02 / 13.06) reports it.
  - **Blast radius**: every binary value of every patched table. Later connector updates rewrite the affected
    rows, so the table holds two representations at once.
  - **Recovery**: fix the loader (render according to the connector's `binary.handling.mode`) and re-run
    `patch --apply` from the same dump. Until then, exclude binary-column tables with `--tables`.
  - **RTO**: one reload of the affected tables (unmeasured).
  - **Test**: GAP: a loader test asserting a `blob` column is selected as the connector's representation for each `binary.handling.mode`.
  - **DEFECT**: D-13.08-1. Binary columns are loaded verbatim, not in the connector's representation.

- **FM-13.08-4 TIMESTAMP values shifted by a randomly chosen DST zone**
  - **Trigger**: the packaged loader runs on a date when DST zones are at `+00:00` (northern winter), and the
    per-process hash order puts such a zone first.
  - **Behaviour**: `get_unix_timezone_from_mysql_timezone` returns e.g. `Europe/Dublin`
    (`clickhouse_loader.py:268-285`). `TZ` is exported with `--use_client_time_zone 1`, and summer-dated
    `TIMESTAMP` values are stored 1 or 2 hours early. Reproduced: 9 of 30 seeds.
  - **Detection**: a canary table with summer `TIMESTAMP` values fails. Otherwise only the checksum. Because
    of the randomness, a re-run can come out clean.
  - **Blast radius**: every `TIMESTAMP` value in the DST half-year of every table loaded in that run.
  - **Recovery**: re-load with the legacy loader (`--loader-cmd`, deterministic zone; beware D-13.08-12) or
    with `PYTHONHASHSEED` fixed to a value that picks a UTC zone, after fixing the code.
  - **RTO**: one reload (unmeasured).
  - **Test**: GAP: a loader test that pins "now" to January and asserts the returned zone has no DST for every hash seed.
  - **DEFECT**: D-13.08-2. The dump zone is picked from an unordered set and may be a DST zone.

- **FM-13.08-5 Legacy-engine table: reloaded rows get `_sign = 0`**
  - **Trigger**: the live table uses the legacy layout `ReplacingMergeTree(_version)` with `_sign Int8` (no default).
  - **Behaviour**: the loader skips `_sign` (a virtual column), so the scratch rows take the default 0
    (`ClickHouseAutoCreateTable.java:278-282`). After `REPLACE`, consumers and the checksum filtering
    `_sign > 0` (`clickhouse_table_checksum.py:233-234`) see none of the reloaded rows. The tool reports
    `REPLACED_OK`.
  - **Detection**: the checksum reports missing rows. A canary always fails for such a table (`_sign` is in the hash).
  - **Blast radius**: every reloaded row of the table, invisible to sign-filtering readers.
  - **Recovery**: none in the tool. Excluding the table is the only safe option until a fix sets `_sign = 1`.
  - **RTO**: unmeasured.
  - **Test**: GAP: a `cmd_patch` test on a table with a `_sign` column, asserting the reloaded rows carry `_sign = 1` or that the table is refused.
  - **DEFECT**: D-13.08-3. Reloaded rows get `_sign = 0` on legacy-engine tables.

- **FM-13.08-6 Pooled canary passes a table whose rows all differ**
  - **Trigger**: several canary tables, a large one rendering correctly and a small one not.
  - **Behaviour**: hits and totals are summed (`mysql_resync.py:507`) and compared once (`:519`). Reproduced:
    1000/1000 plus 0/5 gives 0.995 ≥ 0.99, so every table becomes `REPLACED_OK` with exit 0. A canary table
    with an expression key is skipped without a log line.
  - **Detection**: the per-table `canary <t>: identical rows 0/5` log line, if someone reads it. Otherwise the checksum.
  - **Blast radius**: the mis-rendered table and every non-canary table with the same column types.
  - **Recovery**: re-run with a canary list containing only the suspect table. Fix the rendering and re-patch.
  - **RTO**: unmeasured.
  - **Test**: GAP: a `TestCanaryGate` case with two canary tables (1000/1000, 0/5), expecting `CANARY_FAILED` and no `REPLACE`.
  - **DEFECT**: D-13.08-4. The canary threshold is applied to the pooled ratio, not per table.

- **FM-13.08-7 A lagging connector undoes the repair**
  - **Trigger**: while `patch --apply` replaces partitions, the running connector's applied position is
    earlier than the unlogged change, for example because it lagged or was stopped for the incident.
  - **Behaviour**: nothing compares the connector's position with the recorded position before `REPLACE`
    (`mysql_resync.py:536-550`). Events the connector then applies carry versions far above the reloaded rows'
    0 (§3.9.4), so older after-images replace the repaired rows. The unlogged change never appears in the
    binlog, so no later event corrects them. The rewind does not help: it moves the connector forward
    (FM-13.08-8).
  - **Detection**: only the post-repair checksum.
  - **Blast radius**: every repaired row the lagging connector touches.
  - **Recovery**: let the connector catch up past the recorded position (`show_replica_status`, spec 10.03), then re-run `patch --apply --skip-load`.
  - **RTO**: the connector catch-up plus the `REPLACE` time (unmeasured).
  - **Test**: GAP: a `cmd_patch` test that supplies a connector offset behind the recorded position and expects a refusal.
  - **DEFECT**: D-13.08-5. `patch` does not check that the connector is at or past the recorded position.

- **FM-13.08-8 `rewind-sql` moves the offset forward**
  - **Trigger**: the connector's stored offset is behind the recorded `file:pos`.
  - **Behaviour**: `cmd_rewind_sql` reads the current `offset_val` but uses only its `server_id`
    (`mysql_resync.py:601-604`). Reproduced: current `mysql-bin.000300:4`, recorded `mysql-bin.000350:1000`.
    The run prints the INSERT that moves to 000350 with no warning, and the connector skips every event in
    between for **all** of its tables.
  - **Detection**: none from the tool. The printed current `offset_val` comment shows it to a careful reader.
  - **Blast radius**: every change in the skipped range for every table of that connector, lost silently.
  - **Recovery**: before starting, put the earlier position back with `sink-connector-client update_binlog` (connector stopped), or re-snapshot.
  - **RTO**: a connector restart plus the replay (unmeasured).
  - **Test**: GAP: a `TestRewind` case with a current offset behind the recorded position, expecting `ValueError`.
  - **DEFECT**: D-13.08-6. The rewind never compares positions and can move the offset forward.

- **FM-13.08-9 `rewind-sql` emits an INSERT that matches no row**
  - **Trigger**: `--offset-key` is given and the table is not read (no or missing `--ch-config`), or it is read but empty, and the key does not exist.
  - **Behaviour**: `if rows:` is false, so the key is used unchecked (`mysql_resync.py:600-603`). The printed
    `INSERT ... SELECT ... WHERE offset_key = '<key>'` inserts zero rows when run. Reproduced for both cases,
    exit 0 each time.
  - **Detection**: none. ClickHouse reports success for a zero-row INSERT ... SELECT.
  - **Blast radius**: the operator believes the connector has been rewound. The dump-window changes are
    missing from the patched tables, silently.
  - **Recovery**: verify with `SELECT offset_val FROM <offset table> FINAL WHERE offset_key = '<key>'` before starting the connector.
  - **RTO**: minutes once noticed, plus the replay.
  - **Test**: GAP: a `cmd_rewind_sql` test with an empty table and a key, expecting a refusal.
  - **DEFECT**: D-13.08-7. Zero candidate offset rows are accepted, against spec 11.04 §3.5.

- **FM-13.08-10 `--skip-load` replaces from a scratch table loaded from another dump**
  - **Trigger**: `patch --skip-load --stamp B` while the scratch tables still hold a load from stamp A.
  - **Behaviour**: scratch names do not carry the stamp. Only `count()` is compared with dump B
    (`mysql_resync.py:496-497`). A table whose row count did not change passes, and dump A's values are
    installed. The operator then rewinds to B's position, so the changes between A and B are lost for that table.
  - **Detection**: none from the tool. The checksum.
  - **Blast radius**: every table whose count matched.
  - **Recovery**: re-run without `--skip-load`.
  - **RTO**: one reload (unmeasured).
  - **Test**: GAP: a `--skip-load` test that requires a stamp marker on the scratch table (for example a table comment).
  - **DEFECT**: D-13.08-8. `--skip-load` does not tie the scratch tables to the stamp.

- **FM-13.08-11 Empty or colliding `--restore-suffix` drops a live table**
  - **Trigger**: `--restore-suffix ""`, or a suffix for which `<schema><suffix>` is another live database (for example both `a` and `a_restore` in `--schemas`).
  - **Behaviour**: `restore_db = schema + suffix` (`mysql_resync.py:426`). `DROP TABLE IF EXISTS <restore_db>.<t>`
    (`:468`) hits the live table. Reproduced: `DROP TABLE IF EXISTS `mydb`.`t``, followed by
    `CREATE TABLE `mydb`.`t` AS `mydb`.`t`` (which fails on a real server).
  - **Detection**: the `[APPLY] DROP TABLE` line and the following CREATE error (traceback).
  - **Blast radius**: the live table is dropped.
  - **Recovery**: `UNDROP TABLE` within `database_atomic_delay_before_drop_table_sec` on Atomic databases (server-version dependent), otherwise restore from backup or re-snapshot the table.
  - **RTO**: unmeasured.
  - **Test**: GAP: a `cmd_patch` test with an empty suffix, expecting a refusal before any write.
  - **DEFECT**: D-13.08-11. The scratch database name is not validated against live databases.

- **FM-13.08-12 Partial apply: replaced tables left regressed, rewind withheld**
  - **Trigger**: `patch --apply` where some tables reach `REPLACED_OK` and others end in `SCHEMA_DRIFT`, `COUNT_MISMATCH` and so on.
  - **Behaviour**: the replacements are kept. The run exits 1 and prints `do not rewind the connector until every table is repaired`
    (`mysql_resync.py:582-584`). The replaced tables miss every change made between their dump read and now,
    for as long as the operator works on the failed tables.
  - **Detection**: exit 1 and the report. The regression itself is not reported.
  - **Blast radius**: the `REPLACED_OK` tables are stale for the duration.
  - **Recovery**: rewind right away (harmless on `ReplacingMergeTree`), or replace only once every table is ready (`--tables`).
  - **RTO**: as long as the operator takes to fix the failed tables.
  - **Test**: `sink-connector/python/db_load/tests/test_mysql_resync.py::TestCanaryGate::test_apply_fails_when_a_selected_table_is_skipped_for_schema_drift` (exit code only). GAP: a mixed run asserting no table is replaced unless every selected table is ready.
  - **DEFECT**: D-13.08-13. A partial apply leaves replaced tables regressed and advises against the rewind that would fix them.

- **FM-13.08-13 Two `patch` runs share scratch tables**
  - **Trigger**: two runs on the same schema at the same time (same suffix).
  - **Behaviour**: no lock exists. Run B drops and reloads a scratch table that run A reconciled in phase 1.
    Run A's `REPLACE` (phase 2) copies B's partial table, and the count check is not repeated.
  - **Detection**: run A's verify gives `REPLACED_VERIFY_FAIL` (loud), after the live partition has already been replaced.
  - **Blast radius**: the live table holds a partial load until it is re-run.
  - **Recovery**: run one at a time and re-run with `--skip-load`.
  - **RTO**: unmeasured.
  - **Test**: GAP: a test asserting the scratch count is re-checked immediately before the first `REPLACE`, or that a run lock is taken.
  - **DEFECT**: D-13.08-15. Scratch tables are neither locked nor re-reconciled before `REPLACE`.

- **FM-13.08-14 Verify fails while the connector runs**
  - **Trigger**: the connector inserts into a replaced table between `before` and the verify `count()`, or the
    scratch table merges rows with equal sorting keys (non-unique key, all-columns fallback key, TTL).
  - **Behaviour**: plain `count()` without `FINAL` (`mysql_resync.py:496,561`). `after != expect` gives
    `REPLACED_VERIFY_FAIL`, or `COUNT_MISMATCH` before any `REPLACE`.
  - **Detection**: loud: status and exit 1.
  - **Blast radius**: no data harm, but the result is a false failure and the rewind advice is withheld.
  - **Recovery**: re-verify by hand with the dump counts, or pause the connector for the replace phase.
  - **RTO**: minutes.
  - **Test**: GAP: a fake whose live `count()` grows between calls, expecting a distinct status rather than `REPLACED_VERIFY_FAIL`.
  - **DEFECT**: D-13.08-18. The verify is a plain `count()` that races the connector and merges.

- **FM-13.08-15 Replicated tables cannot be repaired**
  - **Trigger**: live tables are `ReplicatedReplacingMergeTree` or `SharedReplacingMergeTree`.
  - **Behaviour**: `live[t][1] != "ReplacingMergeTree"` gives `ENGINE_<engine>` and the table is skipped
    (`mysql_resync.py:450`). With `--apply` the run exits 1.
  - **Detection**: loud.
  - **Blast radius**: the divergence persists. The tool is unusable on replicated clusters.
  - **Recovery**: none in the tool. A manual procedure is needed.
  - **RTO**: unbounded within the tool.
  - **Test**: GAP: a test with a replicated engine, asserting supported handling (scratch on one replica, then `REPLACE PARTITION` replicates).
  - **DEFECT**: D-13.08-19. The engine check is an exact string comparison that rejects every replicated engine.

- **FM-13.08-16 Rows MySQL deleted survive in replica-only partitions**
  - **Trigger**: a partitioned table where MySQL has no rows left in some partitions (purged history, or rows moved to another partition).
  - **Behaviour**: by design those partitions are kept and listed in the drop file. `expect` includes them,
    so `REPLACED_OK` and exit 0 follow (`mysql_resync.py:551-563`).
  - **Detection**: the drop file, and the checksum reports the extra rows.
  - **Blast radius**: the extra rows in those partitions. Moved rows show twice under `FINAL`.
  - **Recovery**: review `drop_ch_only_<ts>.sql`, then re-run with `--drop-ch-only` or run the listed statements.
  - **RTO**: seconds per partition.
  - **Test**: `sink-connector/python/db_load/tests/test_mysql_resync.py::TestPlanReplace::test_partitioned_replaces_dump_partitions_and_lists_replica_only_ones`.

- **FM-13.08-17 `dump` interrupted half way**
  - **Trigger**: `mysqlsh` killed, source restart, or a full disk.
  - **Behaviour**: as FM-11.04-2: `FAILED <schema>`, exit 1. Re-runs keep the position and refuse the incomplete directory.
  - **Detection**: exit 1 and log lines.
  - **Blast radius**: none on ClickHouse.
  - **Recovery**: move the directory away and re-run with the same `--stamp`.
  - **RTO**: one re-dump of the schema (unmeasured).
  - **Test**: `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestDumpRerun::test_rerun_keeps_the_position_skips_complete_and_refuses_incomplete`.

- **FM-13.08-18 Source is MySQL 8.4 or later**
  - **Trigger**: `dump` against a server that no longer has `SHOW MASTER STATUS` (replaced by `SHOW BINARY LOG STATUS`).
  - **Behaviour**: `mysqlsh` fails, and `RuntimeError("mysqlsh SHOW MASTER STATUS failed ...")` is raised (`mysql_resync.py:276-279`).
  - **Detection**: loud: traceback, exit 1.
  - **Blast radius**: no resync is possible.
  - **Recovery**: none in the tool. Capture the position by hand and write `binlog_position_<stamp>.json` before running `dump`. The kept-file branch then skips the capture.
  - **RTO**: minutes.
  - **Test**: GAP: a test feeding a `SHOW BINARY LOG STATUS` result, expecting a captured position.
  - **DEFECT**: D-13.08-20. The position capture uses a statement removed in MySQL 8.4.

- **FM-13.08-19 A child process hangs**
  - **Trigger**: `mysqlsh` dump, the loader, or `zstd | wc` stalls (stuck NFS, network partition inside a pipeline).
  - **Behaviour**: none of these `subprocess.run` calls has a timeout (`mysql_resync.py:231,334,364`). Only
    the tool's own clickhouse-client calls are bounded (3600 s plus 60 s).
  - **Detection**: none: the run just stops making progress.
  - **Blast radius**: the repair window grows, and so does the binlog-retention risk (FM-11.04-5).
  - **Recovery**: kill the run and re-run (FM-13.08-1 / FM-13.08-17).
  - **RTO**: unbounded until someone notices.
  - **Test**: GAP: tests asserting a timeout is passed to each `subprocess.run`.
  - **DEFECT**: D-13.08-22. The dump, loader and count child processes are unbounded in time.

- **FM-13.08-20 Legacy loader exposes the ClickHouse password**
  - **Trigger**: `--loader-cmd "python db_load/clickhouse_loader.py"`.
  - **Behaviour**: the legacy loader resolves the password from the config and adds `--password <pw>` to each
    clickhouse-client command (`db_load/clickhouse_loader.py:494-498`). Reproduced. The log is redacted, the
    process list is not.
  - **Detection**: none.
  - **Blast radius**: the password is visible to every local user for the duration of the load.
  - **Recovery**: rotate the password and use the packaged loader.
  - **RTO**: password rotation time.
  - **Test**: GAP: a legacy-loader test asserting no `--password` in the command when `--clickhouse_config_file` is used.
  - **DEFECT**: D-13.08-12. The legacy loader puts the config-file password on the command line.

- **FM-13.08-21 Patch pointed at replication-history (SCD2) tables**
  - **Trigger**: `--schemas` names a mode-2 history database (spec 12.01).
  - **Behaviour**: as FM-11.04-9. The tables pass the engine check, the history columns get defaults, and the
    open-row partition would be overwritten.
  - **Detection**: `CANARY_FAILED` if a canary list is given, otherwise none.
  - **Blast radius**: the current-state view of every patched SCD2 table.
  - **Recovery**: spec 12.03 §7.
  - **RTO**: see spec 12.03 §7.
  - **Test**: GAP: a `cmd_patch` test on a table with `_valid_to`, expecting a refusal.
  - **DEFECT**: D-13.08-10. SCD2 tables are not refused (FM-11.04-9).

- **FM-13.08-22 Rewind preconditions not enforced (connector running, failover, purged binlog, KeeperMap store)**
  - **Trigger**: any of FM-11.04-4, -5, -6 or -7.
  - **Behaviour**:
    - The INSERT is superseded by the running connector's next flush.
    - File and position name another server's binlog (no `gtids` in the new offset).
    - The binlog file has been purged.
    - `FINAL` is rejected by a KeeperMap offset table.
    - None of these is checked by `cmd_rewind_sql` (`mysql_resync.py:592-615`).
  - **Detection**: loud only for KeeperMap and purged binlogs (at connector start). The other two are silent.
  - **Blast radius**: dump-window changes missing from the patched tables.
  - **Recovery**: as in FM-11.04-4 to FM-11.04-7 (`sink-connector-client update_binlog` with the connector stopped, or a new dump).
  - **RTO**: a connector restart plus the replay, or a full redo.
  - **Test**: `sink-connector/python/db_load/tests/test_resync_failure_modes.py::TestRewindAfterFailover::test_rewind_carries_the_captured_gtid_set` (skipped). GAP for the other three.
  - **DEFECT**: D-13.08-9, D-13.08-16, D-13.08-17 and D-13.08-28. The rewind's safety rests on unchecked manual preconditions.

Summary: 22 failure modes, 18 DEFECT, 19 GAP.

---

## 7. Defect Register
| ID | Severity | Copy (legacy/packaged/both) | Location | Evidence | Summary |
|---|---|---|---|---|---|
| D-13.08-1 | S1 | both (loader) | `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py:399` (legacy same branch), reached from `mysql_resync.py:353` | reproduced (loader `main()` with resync's argv: `` `b` ``,`` `vb` ``,`` `bt` `` selected verbatim) | Binary columns are loaded as the dump's text, never as the connector's `bytes`-mode lower-case hex. Base64-mode agreement depends on MySQL Shell line wrapping (unverified) |
| D-13.08-2 | S1 | packaged (loader) | `ch_sink_tools/db_load/clickhouse_loader.py:268-285,347,517` | reproduced (9/30 hash seeds pick a DST zone in January) | `TIMESTAMP` parse zone is the first `+00:00` zone in set hash order. Summer values are shifted 1–2 h when a DST zone is picked |
| D-13.08-3 | S1 | both | `mysql_resync.py:353` (loader skips `_sign`) + connector DDL `_sign Int8` with no default | code-read | Legacy-engine tables: reloaded rows get `_sign = 0` and vanish from `_sign > 0` readers (including the checksum) while the tool reports `REPLACED_OK` |
| D-13.08-4 | S1 | packaged (tool) | `mysql_resync.py:507,519` | reproduced (1000/1000 plus 0/5 → all `REPLACED_OK`, exit 0) | Canary threshold applies to the pooled ratio, so a table whose rows all differ passes |
| D-13.08-5 | S1 | packaged | `mysql_resync.py:536-550` | code-read | No check that the connector is at or past the recorded position before `REPLACE`. A lagging connector's older after-images outrank the `_version = 0` reloaded rows and undo the repair |
| D-13.08-6 | S1 | packaged | `mysql_resync.py:592-605` | reproduced (current 000300:4 → INSERT to 000350:1000, no warning) | `rewind-sql` can move the offset forward, skipping binlog events for every table of the connector |
| D-13.08-7 | S1 | packaged | `mysql_resync.py:595-603` | reproduced (empty table plus key, and no config plus a typo key: rc 0) | A key that matches no row yields a zero-row INSERT. Spec 11.04 §3.5 says zero candidates is an error |
| D-13.08-8 | S1 | packaged | `mysql_resync.py:465-477,496` | code-read | `--skip-load` does not tie scratch tables to the stamp. A count-equal scratch table from another dump is installed |
| D-13.08-9 | S1 | packaged | `mysql_resync.py:606-614` | code-read (restates FM-11.04-6) | Rewind correctness depends on an unenforced "stop the connector" step. A running connector silently supersedes the row |
| D-13.08-10 | S1 | packaged | `mysql_resync.py:450` | code-read (restates FM-11.04-9) | SCD2 history tables are not refused, and their open-row partition would be overwritten |
| D-13.08-11 | S2 | packaged | `mysql_resync.py:426,468` | reproduced (`--restore-suffix ""` → `DROP TABLE IF EXISTS `mydb`.`t``) | The scratch name is not validated: an empty or colliding suffix drops a live table |
| D-13.08-12 | S2 | legacy (loader) | `sink-connector/python/db_load/clickhouse_loader.py:494-498,549` | reproduced (`--password p1` in the shell command) | Legacy loader (via `--loader-cmd`) puts the config-file password on the clickhouse-client command line |
| D-13.08-13 | S2 | packaged | `mysql_resync.py:580-584` | code-read | A partial apply keeps the replacements, leaves those tables at dump state, and advises against the rewind that would bring them current |
| D-13.08-14 | S2 | packaged | `mysql_resync.py:436-441` | reproduced (mechanism: live-only table listed) + code-read | Drop file proposes `DROP TABLE` for every live table missing from the dump, including tables excluded by `dump --tables`, materialized views and `.inner` tables |
| D-13.08-15 | S2 | packaged | `mysql_resync.py:465-469,496,547-550` | code-read | Scratch tables are not locked or re-reconciled before `REPLACE`. A concurrent run's partial scratch table can be installed |
| D-13.08-16 | S2 | packaged | `mysql_resync.py:169-173` | code-read; skipped test (restates FM-11.04-4) | Rewind writes file/pos only and drops the recorded GTID set, so it is not failover-safe (S1 if Debezium silently accepts a foreign coordinate; not verified) |
| D-13.08-17 | S2 | packaged | `mysql_resync.py:611` | code-read (restates FM-11.04-5) | Binlog retention at the recorded position is never checked |
| D-13.08-18 | S3 | packaged | `mysql_resync.py:496,561-563` | code-read | Verify and reconcile use a plain `count()`. A running connector or key-collapsing merges produce false `REPLACED_VERIFY_FAIL` / `COUNT_MISMATCH` |
| D-13.08-19 | S3 | packaged | `mysql_resync.py:450` | code-read | Exact engine-name check rejects `Replicated*` and `Shared*` `ReplacingMergeTree`, so the tool is unusable on replicated clusters |
| D-13.08-20 | S3 | packaged | `mysql_resync.py:277` | code-read (MySQL 8.4 removed `SHOW MASTER STATUS`; not verified offline) | Position capture fails on MySQL 8.4 and later |
| D-13.08-21 | S3 | packaged | `mysql_resync.py:595-599,246` | reproduced (`subprocess.run` with a `None` argument raises `TypeError`) | `rewind-sql --ch-config` without `--ch-host` crashes, and a missing `--ch-config` file is silently ignored |
| D-13.08-22 | S3 | packaged | `mysql_resync.py:231,334,364` | code-read | No timeout on the `mysqlsh` dump, the loader or the `zstd \| wc` children |
| D-13.08-23 | S3 | packaged | `mysql_resync.py:182-185,369,435,455,538,547,597` | reproduced (`database='my'db'`, `INSERT INTO db.t; DROP TABLE x`) | Schema and table names are put into string literals unescaped, and `--offset-table` is put into SQL verbatim |
| D-13.08-24 | S3 | packaged | `mysql_resync.py:374-380,503-504` | reproduced (SQL for a source `is_deleted` column; silent skip of an expression-key canary table) | Canary treats a source `is_deleted` column as the delete flag, leaves `_is_deleted`/`_sign` in the hash, and skips expression-key tables without a log line |
| D-13.08-25 | S3 | both (loader) | `ch_sink_tools/db_load/clickhouse_loader.py:517` | reproduced (`-uNone` with a config that has no `<user>`) | A loader user that resolves to `None` is emitted as `-uNone`, so the load fails although clickhouse-client would use the config's defaults |
| D-13.08-26 | S3 | both (loader) | `ch_sink_tools/db_load/clickhouse_loader.py:517` | code-read | The data-file path (from `--dump-base`) is not shell-quoted in the loader pipeline |
| D-13.08-27 | S3 | packaged | `mysql_resync.py:298,227-235` | code-read (restates FM-11.04-3) | The unit of repair is a whole table, with no partition or key-range scope |
| D-13.08-28 | S3 | packaged | `mysql_resync.py:184,597` | code-read (restates FM-11.04-7) | `FINAL` in the read and in the generated INSERT fails on a KeeperMap offset table |
| D-13.08-29 | S4 | packaged | `mysql_resync.py:545` | reproduced (dry-run report: `live_before` value under `partitions_replaced`) | Dry-run report row is misaligned with the header |
| D-13.08-30 | S4 | packaged | `mysql_resync.py:492-495,540-546,580` | reproduced (dry run prints no `REPLACE`/`DROP PARTITION`, rc 0) | A dry run without `--skip-load` shows no replacement plan, and exits 0 even when the apply would fail (drift, missing table, engine) |
| D-13.08-31 | S4 | packaged | `mysql_resync.py:465-469` | code-read | Scratch databases and tables are never cleaned up |
| D-13.08-32 | S4 | both | `sink-connector/python/pyproject.toml` (`requires-python = ">=3.6"`) | code-read | The tool needs 3.7 or later (`from __future__ import annotations`) and the loader 3.9 or later (`zoneinfo`) |

Not determinable offline:
- How MySQL Shell encodes binary columns: base64, and whether line-wrapped.
- Whether clickhouse-client parses `input()` data on the client side under `--use_client_time_zone`.
- How the server casts `DATETIME` strings into zone-less columns, zero dates and out-of-range values.
- Debezium's behaviour with a foreign or forward file/pos and with `server_id: 0`.
- What `util.dumpTables` does with an empty table list.
