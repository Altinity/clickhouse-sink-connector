# Spec 13.06: MySQL-to-ClickHouse Verification Runners

## 1. Executive Summary & Purpose

The verification runners are the non-streaming tools that tell an operator whether a ClickHouse replica holds
the same rows as its MySQL source. They are:

- the **driver** `top_level_table_checksum.py`, which enumerates MySQL tables, optionally locks each one on the
  source, starts one MySQL side process and one ClickHouse side process per replica, and compares the
  `(checksum, count)` pairs they print;
- the two **side scripts** `mysql_table_checksum.py` and `clickhouse_table_checksum.py`, which each reduce one
  table to an order-independent checksum;
- the two **count runners** `mysql_table_count.py` and `clickhouse_table_count.py`, which print exact row counts
  and are not wired into any comparison.

Spec 11.02 is the protocol spec: canonical row string, per-type rendering, aggregate, `FINAL` and sign rules,
coverage warnings, and nine failure modes. Spec 11.05 covers the offline test layer of the dumper and loader.
This spec does not repeat 11.02. It adds what 11.02 does not cover: the CLI and config contract of every runner,
the shell layer between driver and side scripts, the lock and catch-up timing, the exact verdict and exit-code
table, the count runners, and a function-by-function comparison of the **two divergent copies**. It also records
every faulty behaviour found as a defect. The legacy copy is in `db_compare/` and `db/`. The packaged copy is in
`ch_sink_tools/`.

Main conclusions (2.11.0):

- **Spec 11.02 describes only the legacy copy.** The packaged copy (`ch-mysql-checksum` and `ch-ch-checksum`
  entry points) is a snapshot from before 11.02. It has no bounded lock, no `pipefail`, no time-zone handling,
  no coverage warnings, `eval()`-based placeholder substitution, trim-based datetime rendering, a 1969 clamp,
  and JSON included by default. Every test in `db_compare/tests` imports the legacy copy only.
- **The packaged driver reports "No difference" when both side scripts fail** (reproduced). Its pipeline is
  `set -e pipefail`, so the exit status is `awk`'s. Two crashed sides each parse to `(None, None)`, and these
  compare equal. The installed `ch-mysql-checksum` runs `python db_compare/<side>.py` relative to the current
  directory. Run from anywhere except `sink-connector/python`, every table reports "No difference" and the
  run exits 0 (D-13.06-1, S1).
- **Five more false-match paths exist in both copies.** A column, table or database name containing
  `checksum` (D-13.06-2). A `$` in a table name, which the shell expands, so a different table is compared
  (D-13.06-3). A MySQL host string equal to a replica host, which produces no verdict at all (D-13.06-4).
  Zero rows on both sides after a filter that matches nothing, which reports "No difference" without a warning
  (D-13.06-5). The packaged copy also has a pre-1969 datetime clamp (D-13.06-6) and one-sided JSON
  normalisation that is on by default (D-13.06-7).
- **No mechanism establishes that the connector has caught up.** The only one is
  `time.sleep(--sleep_after_lock)` (default 3 s) after `LOCK TABLES ... READ`. No binlog position is read and
  no connector offset is compared. A lag makes the result DIFFERENT (noise), not a false match (§3.7).
- **The legacy driver cannot run on SQLAlchemy 2.x.** `sqlalchemy>=1.4` installs 2.x on a fresh install. The
  driver indexes `fetchall()` rows by column name and exits 1 at the first table (reproduced, D-13.06-9). The
  same applies to both count runners and to the packaged MySQL code paths.
- **A MySQL table partitioned by an expression aborts the driver run.** For example `RANGE (TO_DAYS(dt))`.
  Its partition expression is pasted unquoted into the shell as `--partition_key to_days(dt)`, which is a shell
  syntax error (reproduced, D-13.06-10).
- **The side scripts' coverage WARNINGs never reach the driver log.** These are the WARNINGs for columns not
  compared and for clamped values. The `grep -i checksum` pipeline drops them (reproduced, D-13.06-8), which
  contradicts 11.02 §3.9.

**Fixed since 2.11.0** (this spec's section 3 describes the fixed behaviour; the conclusions above describe
2.11.0): D-13.06-1 to -8, -10, -14, -17, -18 and -25 to -27. Both drivers now run their side scripts as argv
lists without a shell, parse the side output in Python (exactly one `Checksum for table` line for the expected
table), relay every side ERROR line (that side then gives no result) and every side WARNING line (at INFO, as a
side note), give every table an explicit verdict (`MATCH`, `DIFFERENT`, `EMPTY` or `ERROR`) and exit 1 when
any table has no verdict. `EMPTY` (zero rows on both sides) is logged at INFO with exit 0 unless
`--fail_on_empty` is passed: in the driver log, WARNING is reserved for `Checksum difference`, the line
scheduled jobs scan for (§3.17). The packaged driver runs the packaged side modules with its own interpreter,
passes the full DateTime64 range to both sides, and excludes JSON on both sides by default. The manual paths
work in both copies: `--no_wc` (driver, MySQL side, both count runners), `--exclude_columns` as
space-separated words or comma lists alike on both sides, the ClickHouse count without
`--include_partitions_regex`, and `--debug_output`, which writes the per-row files and still prints the
checksum line.

**Fixed by the end-to-end suite** (`sink-connector/python/tests_e2e/mysql`, which runs the scheduled job, the
manual recipes, the snapshot path and ch-mysql-resync against a real MySQL, ClickHouse and connector): D-13.06-9
(every runner reads catalog rows by name through `mappings()`, so the tools run on the SQLAlchemy 2.x that a
fresh `install.sh` installs), D-13.06-38 (BIT(n>1) is rendered as lower-case hex under every
`--binary_encoding`, as the connector stores it; with `--binary_encoding base64` every table with such a column
was DIFFERENT on clean data) and D-13.06-39 (`install.sh` can be sourced by the job's `set -euo pipefail`
script). **Fixed after running the scheduled job against a production-shaped schema**: D-13.06-40 (the
trailing null flags are value-based, one per compared column on both sides; a column declared nullable on
MySQL and non-Nullable on ClickHouse, such as a stored generated column, made every row of an equal table
differ).

## 2. Codebase Mapping on 2.11.0

| Concern | Legacy copy | Packaged copy |
|---|---|---|
| Driver (orchestrator) | `sink-connector/python/db_compare/top_level_table_checksum.py` (692 lines) | `sink-connector/python/ch_sink_tools/db_compare/top_level_table_checksum.py` (477 lines) |
| MySQL side script | `sink-connector/python/db_compare/mysql_table_checksum.py` (482) | `sink-connector/python/ch_sink_tools/db_compare/mysql_table_checksum.py` (439) |
| ClickHouse side script | `sink-connector/python/db_compare/clickhouse_table_checksum.py` (514) | `sink-connector/python/ch_sink_tools/db_compare/clickhouse_table_checksum.py` (423) |
| MySQL count runner | `sink-connector/python/db_compare/mysql_table_count.py` (234) | `sink-connector/python/ch_sink_tools/db_compare/mysql_table_count.py` (232) |
| ClickHouse count runner | `sink-connector/python/db_compare/clickhouse_table_count.py` (178) | `sink-connector/python/ch_sink_tools/db_compare/clickhouse_table_count.py` (182) |
| Shared checksum pieces | `sink-connector/python/db/checksum_common.py` (147) | none (the packaged code inlines the final md5) |
| PostgreSQL CH-expression helper (not used by any MySQL runner) | none | `sink-connector/python/ch_sink_tools/db_compare/_expressions.py` (62) |
| MySQL helpers (Spec 13.02) | `sink-connector/python/db/mysql.py` | `sink-connector/python/ch_sink_tools/db/mysql.py` |
| ClickHouse helpers | `sink-connector/python/db/clickhouse.py` | `sink-connector/python/ch_sink_tools/db/clickhouse.py` |
| Entry points | none. Run as `python db_compare/<tool>.py` with the python root on `PYTHONPATH` | `sink-connector/python/pyproject.toml`: `ch-mysql-checksum` = packaged driver, `ch-ch-checksum` = packaged ClickHouse side, `ch-ch-count` = packaged ClickHouse count. No entry point exists for the packaged MySQL side or the packaged MySQL count. |
| Container images | `sink-connector/python/Dockerfile_mysql_checksum` and `sink-connector/python/Dockerfile_clickhouse_checksum` copy `sink-connector/python/db/` and `sink-connector/python/db_compare/`, so they run the **legacy** side scripts | none |
| Dependency floor | `sink-connector/python/requirements.txt` (`sqlalchemy>=1.4`) | `sink-connector/python/pyproject.toml` (`[mysql]` extra `sqlalchemy>=1.4`) |
| Unit tests (all import the **legacy** copy) | `sink-connector/python/db_compare/tests/test_checksum_fidelity.py`, `sink-connector/python/db_compare/tests/test_table_locking.py`, `sink-connector/python/db_compare/tests/test_bounded_source_lock.py`, `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py`, `sink-connector/python/db_compare/tests/test_top_level_where_quoting.py`, `sink-connector/python/db_compare/tests/mysql_table_checksum_test.py` | none |
| Integration callers (legacy side scripts, standalone) | `sink-connector-lightweight/tests/checksum/test_checksum_replication.py`, `sink-connector-lightweight/tests/checksum/test_sysbench_checksum_replication.py`, `sink-connector/python/test_db.sh`, `sink-connector/tests/diff_sakila_data.sh` | none |

Line anchors. **LT** is the legacy driver, **PT** the packaged driver, **LM**/**PM** the MySQL side scripts,
**LC**/**PC** the ClickHouse side scripts.

| Function | LT | PT | LM | PM | LC | PC |
|---|---|---|---|---|---|---|
| `compute_checksum` | 130-199 | 91-131 | 25-52 | 27-54 | 36-74 | 39-81 |
| `get_mysql_checksum_command` / `get_clickhouse_checksum_command` | 238-267 / 271-322 | 138-161 / 164-187 | | | | |
| `analyze_differences`, `parse_checksum` | 343-355, 99-113 | 190-202, 61-75 | | | | |
| `lock_tables` / `unlock_tables` | 368-386 / 389-392 | 205-208 / 211-214 | | | | |
| `run_config` | 428-569 | 242-364 | | | | |
| `run_quick_safe_command` | 571-584 | 366-379 | | | | |
| `main` / parser | 609-690 | 404-474 | 366-478 | 332-435 | 415-509 | 333-422 |
| row-expression builder | | | 71-164 | 57-165 | 118-207 | 100-216 |
| `get_table_checksum_query` | | | 167-196 | 57-165 | 240-291 | 100-216 |
| `fstr` | | | 199-204 | 168-170 | 293-298 | 218-220 |
| `select_table_statements` | | | 207-246 | 173-208 | 300-348 | 222-260 |
| `calculate_checksum` | | | 293-351 | 255-317 | 364-400 | 276-316 |

## 3. Contract (Behaviour as Built)

### 3.1 Which copy runs where

- **Legacy driver.** It is started as `python db_compare/top_level_table_checksum.py ...` from
  `sink-connector/python`, with that directory on `PYTHONPATH`. The script does `from db.mysql import *`, and
  `sys.path[0]` is the script's own directory, so the python root must be added. The offline test
  `test_bounded_source_lock.py::TestCliContract` sets `PYTHONPATH` the same way.
- **Packaged driver.** It is installed as `ch-mysql-checksum`. It spawns
  `<sys.executable> -m ch_sink_tools.db_compare.mysql_table_checksum` and
  `<sys.executable> -m ch_sink_tools.db_compare.clickhouse_table_checksum` (`side_command`), with the directory
  that holds the imported `ch_sink_tools` package prepended to the child's `PYTHONPATH` (`side_environment`).
  It therefore runs the **packaged** side modules of its own installation from any cwd. Before the fix it ran
  `python db_compare/<side>.py` relative to the cwd: the legacy sides from `sink-connector/python`, nothing
  anywhere else, and every table was reported "No difference" (D-13.06-1, fixed).
- **Packaged side scripts.** `ch-ch-checksum` (packaged ClickHouse side) and `ch-ch-count` are also reachable
  standalone. The packaged MySQL side and the packaged MySQL count have no entry point. The packaged MySQL side
  runs through `python -m ch_sink_tools.db_compare.<module>`, which is how the packaged driver calls it.
- **Container images.** They run the legacy side scripts directly. The `ENTRYPOINT` is in exec form, so the
  arguments `"$MYSQL_HOST"`, `"$CLICKHOUSE_PASSWORD"` and so on are passed **literally**. No shell expands them
  (D-13.06-30). `PYTHONPATH "${PYTHONPATH}:/app/db"` expands to `:/app/db` at build time. Its empty first entry
  means "current directory" (`/app`), so `import db` works by accident. This was checked offline:
  `PYTHONPATH=":/x"` puts the cwd on `sys.path`.
- **Tests.** The unit tests import `db_compare.*` and `db.*` (legacy), except
  `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py`, which covers the packaged driver
  and the packaged side modules. The integration tests run the legacy side scripts standalone (not the driver)
  and compare the checksum lines themselves.

### 3.2 Process architecture (legacy driver)

```
driver (Python, ThreadPoolExecutor(max_workers=--threads) over tables)
  └─ per table: compute_checksum()
       ├─ [--lock_tables_on_source] own MySQL connection: SET SESSION lock_wait_timeout=N; LOCK TABLES `t` READ; sleep
       ├─ ThreadPoolExecutor(max_workers=1+replicas):
       │    Popen(["python", "db_compare/mysql_table_checksum.py", ...])             (argv list, no shell)
       │    Popen(["python", "db_compare/clickhouse_table_checksum.py", ...])        (one per replica)
       ├─ UNLOCK TABLES; close lock connection   (finally)
       └─ returns [(host, "<db>.<table>", md5 | None, count | None) | None, ...] in submission order
  main thread: as_completed → analyze_differences() → verdict per table → report_run_summary() → exit code
```

Each side script writes its log to **stdout** (`logging.StreamHandler(sys.stdout)`, format
`%(asctime)s - %(levelname)s - %(threadName)s - %(message)s`). `run_quick_safe_command` runs
`Popen(argv, stdout=PIPE, stderr=STDOUT)` without a shell and `communicate()` with **no timeout**. The return
code is the side script's own `str(process.poll())`. A side that cannot be started (`OSError`) gives rc `127`.
`run_quick_safe_checksum` then:

- relays every side line containing ` - ERROR - ` or ` - CRITICAL - ` at ERROR, and every side line containing
  ` - WARNING - ` at INFO as `<host> <table> side note: <line>`, with the level word removed and any other
  `WARNING` lowered (`relay_side_messages`, `side_note_text`, §3.17);
- on rc 0 when the side logged an ERROR or CRITICAL line, logs `<argv>. logged an ERROR although it exited 0`
  and the last 20 side lines at ERROR, and returns `None`, so the table is `ERROR`;
- otherwise on rc 0, parses the output with `parse_checksum` (§3.8). An unparseable output logs the last 20 side
  lines at ERROR and returns `(host, table, None, None)`;
- on rc ≠ 0, logs `<argv>. failed with return code <rc>` and the last 20 side lines at ERROR, and returns
  `None`.

The legacy driver still runs `python` from `PATH` with the cwd-relative script path, so it must be started
from `sink-connector/python` with that directory on `PYTHONPATH` (§3.1). A missing script is now a failed side
(ERROR verdict), not a match.

### 3.3 Driver CLI (legacy, LT609-661)

| Flag | Type / default | Effect | Honoured? |
|---|---|---|---|
| `--config_file` | str, **required** | YAML config (§3.4) | yes |
| `--partition_date` | `valid_date` (`YYYY-MM-DD` or `YYYY/MM/DD`), none | Appends `{partition_expression}=YYYYMMDD` to the MySQL where and `{partition_expression}=toDate('YYYY-MM-DD')` to the ClickHouse where (§3.13) | yes |
| `--mysql_user` | str | Overwritten by `resolve_credentials_from_config(--defaults_file)` (LT450-452) | **ignored** |
| `--defaults_file` | str, `~/.my.cnf` | `[client] user/password` for the driver's own connections. Also forwarded to the MySQL side as `--defaults_file=` | yes |
| `--mysql_database` | str | Single database. Otherwise the config `source.mysql.databases` is used | yes |
| `--mysql_port` | str, `3306` (no `type=int`) | The driver's own connections (`int()` in `get_mysql_connection`). **Not forwarded** to the MySQL side, which connects to 3306 (D-13.06-15) | partly |
| `--tables_regex` | str, `.` | MySQL `rlike` on `information_schema.tables.table_name` | yes |
| `--exclude_tables_regex` | str | `and table_name not rlike '<re>'` | yes |
| `--include_partitions_regex` | str | Keeps only tables having a partition whose name matches. The **whole table** is still checksummed (D-13.06-37) | yes, table filter only |
| `--non_partitioned_tables_only` | flag | Tables with exactly one `information_schema.partitions` row. A one-partition partitioned table counts as non-partitioned (D-13.03-14) | yes |
| `--clickhouse_user`, `--clickhouse_config_file`, `--clickhouse_database`, `--clickhouse_port`, `--secure` | str | Parsed, then **never used or forwarded**. The ClickHouse side uses its own defaults (`./clickhouse-client.xml`, port 9000), and the database is the MySQL name or `database_override_map` | **ignored** |
| `--threads_per_table` | int, 1 | Forwarded to the MySQL side (PK-range chunks, §3.9) | yes |
| `--chunk_size` | int, 10000 | Not forwarded (the MySQL side uses its own default 10000) | **ignored** |
| `--threads` | int, 1 | Size of the per-table pool, i.e. the number of tables, and therefore of **locks**, in flight. Also forwarded as `--threads=N` to both sides, where it sizes a per-table pool over the one matched table | yes |
| `--debug` | flag | Root logger DEBUG. Logs each command and the captured side output line by line, through the same WARNING filter as the side notes (§3.17) | yes |
| `--debug_output` | flag | Forwarded to both sides, which write their per-row strings to `out.<table>.mysql.txt` / `out.<table>.ch.txt` in the cwd and still print the checksum line, so the verdict is unaffected. Before the fix they printed no checksum line and every table failed (D-13.06-27, fixed) | yes |
| `--no_wc` | flag | `--tables_regex` is the table name: `get_tables_from_regex` returns `[[regex]]` and `run_config` takes it as that one table. Before the fix it called `.fetchall()` on the list: AttributeError, exit 1 (D-13.06-26, fixed) | yes |
| `--where` | str | ANDed into both sides' where, inside a double-quoted shell word (§3.13) | yes |
| `--lock_tables_on_source` | flag | §3.7 | yes |
| `--sleep_after_lock` | int, 3 | Seconds slept after the lock, before the sides start | yes |
| `--source_timezone` | str, resolved | 11.02 §3.2 step 1. Forwarded to both sides | yes |
| `--binary_encoding` | `hex`/`base64`/`raw`, `hex` | Forwarded to both sides. With `raw`, `--hex_columns` is derived per table | yes |
| `--include_floating_point_columns`, `--include_json_columns` | flag | Forwarded to both sides | yes |
| `--lock_wait_timeout` | int, 30 | `SET SESSION lock_wait_timeout` before `LOCK TABLES` | yes |
| `--fail_on_lock_timeout` | flag | A lock timeout aborts the run instead of skip-and-warn | yes |
| `--fail_on_empty` | flag | A table with verdict `EMPTY` (zero rows on both sides) makes the run exit 1. Default: INFO only, exit 0 (§3.8) | yes |

The packaged driver (PT404-445) has the same flags **minus** `--source_timezone`, `--binary_encoding`, the two
include flags, `--lock_wait_timeout` and `--fail_on_lock_timeout`. It has `--fail_on_empty`. Their effects
differ as described in §3.6.2, §3.7.2 and §3.16.

The scheduled job runs the legacy driver from a bash script with `set -euo pipefail` that sources
`install.sh` (a venv with `requirements.txt`, then `PYTHONPATH="${PYTHONPATH:-}":.`), runs the partitioned and
the non-partitioned command through `tee`, and fails when any line of the two logs contains WARNING.
`install.sh` read `"${PYTHONPATH}"` unguarded, so with `PYTHONPATH` unset `set -u` aborted the job before any
checksum (D-13.06-39, fixed).

Environment: neither driver reads environment variables. The legacy children inherit the environment,
including `PATH` (which supplies `python`) and `PYTHONPATH`. The packaged children inherit it with the package
root prepended to `PYTHONPATH`. Files: no artefact is written by the driver itself. With `--debug_output`, the sides write `out.<table>.mysql.txt` and `out.<table>.ch.txt` in the
cwd.

### 3.4 YAML config (both drivers, LT428-488 / PT242-301)

```yaml
source:
  mysql:
    host: mysql-host                      # required (validate_config)
    databases: [db1, db2]                 # used when --mysql_database is absent; empty -> exit 1
    table_include_list: "db1.orders,db1.cust.*"   # comma-separated Python regexes on "<db>.<table>"
    ignored_columns: [db1.orders.secret]  # "<db>.<table>.<column>" excluded on both sides
    tables:
      - db1.orders:
          where: "created >= '2024-01-01'"  # ANDed after --where for that table
replicas:
  - clickhouse:
      host: ch-host                       # required
      database_override_map: "db1:ch_db1,db2:ch_db2"
```

Parsing rules as built:

- `validate_config` checks only `source.mysql.host` and that each `replicas[i].clickhouse.host` is present.
  Failure prints `Invalid configuration...` to stdout and exits 1. A YAML error or a missing file exits 1.
- `table_include_list` is split on `,`. Per database, only the patterns that **literally start with
  `<db>.`** are kept (LT499). If none are kept, the filter is off and **every** table is checksummed. A
  pattern written with an escaped dot (`db1\.orders`) or a leading wildcard (`.*\.orders`) is therefore
  dropped, and the run covers all tables (reproduced, D-13.06-33). The kept patterns use `re.match`, a prefix
  match: `db1.orders` also matches `db1.orders_archive`.
- `ignored_columns` entries are split on `.`. Index `[2]` is read **before** the length check (LT469-473), so a
  two-part entry raises IndexError outside any `try` and the driver exits 1 with a traceback (reproduced,
  D-13.06-31). An entry with more than three parts is silently ignored.
- `tables[].where` (legacy only) goes through `normalize_where_override` (LT219-235), which folds the eval-era
  `\'` into `'` and logs a note. The packaged driver forwards it unchanged.
- `database_override_map` is the **raw string**. The override is applied only if
  `mysql_database in <string>` (a substring test, LT148). The string is then split on `,` and `:`, and the
  pair is taken only if `source_db == mysql_database` **exactly**. `"db1:ch_db1, db2:ch_db2"` (space after the
  comma) never matches ` db2`. The ClickHouse side then silently uses database `db2` and still logs
  "Overriding database ... to db2" (reproduced, D-13.06-16). An entry without `:` raises ValueError and
  aborts the run.

### 3.5 Table enumeration, ordering and concurrency (legacy)

1. Databases are processed **sequentially** in config order. Each one gets a new metadata connection, which is
   never closed. `resolve_source_timezone` runs on the first database and stores its result in
   `args.source_timezone`, so later databases reuse it.
2. Tables come from `get_tables_from_regex_sql` (Spec 13.02): `select TABLE_SCHEMA, TABLE_NAME from
   information_schema.tables where table_type='BASE TABLE' and table_schema='<db>' and table_name rlike '<re>'
   [and table_name not rlike '<ex>'] [partition subqueries] order by 1`. `order by 1` sorts by schema, which is
   constant, so the **table order is unspecified**. The regexes are interpolated unescaped (D-13.03-8). Only
   MySQL is enumerated. A table that exists only in ClickHouse is never reported, by design (MySQL is the
   source of truth).
3. For each table, in the main thread and without a lock: `mysql_pk_columns` (integer PK columns, the first one
   is used, `NULL` if none), `get_min_max_pk_value` (unused by the driver), `get_table_partition_key`
   (`PARTITION_EXPRESSION` of the first partition), `TIMESTAMP` columns, binary columns (only in `raw` mode),
   `json` columns, and the config-ignored columns. Then `compute_checksum` is submitted.
4. Up to `--threads` tables run at once. Each table runs `1 + len(replicas)` side processes concurrently.
   Verdicts are logged in **completion order** (`as_completed`). Log lines of different tables interleave
   (`threadName` is in every line).
5. A failed or unparseable side no longer raises: the table gets verdict `ERROR` and the other tables carry on
   (§3.8). When a future raises (any exception other than a lock timeout), the exception is re-raised
   **inside** the `with ThreadPoolExecutor` block. `__exit__` waits for **every submitted table** to finish (locks taken, both
   engines loaded) before the driver exits 1, and their verdicts are never logged (reproduced, D-13.06-12).
   Spec 11.02 FM-11.02-3 says those tables are "skipped". They are not.

### 3.6 Side command templates and shell quoting

#### 3.6.1 Legacy (`get_mysql_checksum_command`, `get_clickhouse_checksum_command`)

Both builders return an **argv list**; each element below is one argv word:

```
python db_compare/mysql_table_checksum.py --threads_per_table <N> --threads=<T>
  --min_date_value 1900-01-01 --mysql_host <host> --mysql_database <db> --tables_regex <exact table regex>
  --where " 1=1 [ and <where> ][ and {partition_expression}=YYYYMMDD]" --source_timezone <tz>
  --binary_encoding <enc> [--include_floating_point_columns] [--include_json_columns]
  [--exclude_columns a,b] [--debug_output] [--defaults_file=<file>]

python db_compare/clickhouse_table_checksum.py --max_memory_usage 80000000000 --threads=<T>
  --clickhouse_host <replica> --clickhouse_database <db|override> --tables_regex <exact table regex>
  --where " 1=1 [ and <where> ][ and {partition_expression}=toDate('YYYY-MM-DD') ]" --source_timezone <tz>
  [--timestamp_columns c1,c2] [--json_columns j1] --binary_encoding <enc> [--hex_columns b1,b2]
  [include flags] --exclude_columns _version,is_deleted,_is_deleted,__is_deleted[,ignored...]
  --sign_column "" [--debug_output] [--partition_key <mysql partition expression without backticks>]
```

Quoting, as built:

- No shell is involved, so every value reaches the side byte for byte: no `$` expansion, no backtick command
  substitution, no `\` processing, no word splitting (D-13.06-3 and D-13.06-10, fixed).
  - `--tables_regex` is `exact_table_regex(table)`: `^<table>$` with each of `. $ | ? * + ( ) { }` written as a
    one-character class (`orders$archive` → `^orders[$]archive$`). A class is used instead of a backslash
    because both sides paste the regex into an SQL string literal, which would consume the backslash. Plain
    names are unchanged (`^orders$`). The side's `Checksum for table` line must also name exactly
    `<db>.<table>` (§3.8), so a regex that still matched another table gives `ERROR`, not a verdict for the
    wrong table.
  - A where override `` `status` = 'A' and note <> '$HOME' `` reaches both sides unchanged.
  - The MySQL partition expression is forwarded as one argv word whenever the table is partitioned:
    `` to_days(`created`) `` becomes `--partition_key` `to_days(created)`.

#### 3.6.2 Packaged (`get_mysql_checksum_command`, `get_clickhouse_checksum_command`)

The packaged builders also return argv lists with the same quoting rules (`exact_table_regex`, one word per
value, no shell). They differ from the legacy ones in five ways:

- The program is `<sys.executable> -m ch_sink_tools.db_compare.<side>` (§3.1), run with the package root on
  `PYTHONPATH`. Before the fix it was `set -e pipefail;python db_compare/<side>.py ... | grep | awk`, where
  `set -e pipefail` left pipefail off, so the status was `awk`'s 0 (D-13.06-1, fixed).
- Both sides get `--min_datetime_value "1900-01-01 00:00:00" --max_datetime_value "2299-12-31 23:59:59"`, the
  ClickHouse DateTime64 range the connector clamps to (`DATETIME_RANGE_MIN`/`_MAX`). The packaged sides' own
  defaults are the same values. Only values ClickHouse cannot hold are clamped, identically on both sides.
  Before the fix the floor was `1969-12-31 18:00:00` and the ceiling `2299-12-31 00:00:00`, so distinct
  earlier values compared equal (D-13.06-6, fixed).
- The MySQL side gets a hard-coded `--binary_encoding base64`. The ClickHouse side gets no encoding.
- The MySQL JSON columns (read from `information_schema.columns`, `mysql_json_columns`) are passed to the
  ClickHouse side as `--json_columns`. Both packaged sides exclude JSON by default with a WARNING, as the
  legacy sides do (D-13.06-7, fixed). No `--source_timezone`, `--timestamp_columns`, `--hex_columns` or include
  flags are passed.
- The partition literal is `toDate(\'YYYY-MM-DD\')` (one backslash before each quote), which the packaged
  `eval`-based `fstr` turns into `toDate('YYYY-MM-DD')`. This is the text the side received through the shell
  before the fix.

Run against the packaged sides, binary columns are DIFFERENT when the connector stores hex (the MySQL side
renders base64), and the packaged rendering differences of §3.11 (D-13.06-20) apply.

### 3.7 Source locking and replication catch-up

#### 3.7.1 Legacy (`compute_checksum`, LT160-199; `lock_tables`, LT368-386)

Timeline per table, when `--lock_tables_on_source` is set:

1. A new SQLAlchemy connection to `source.mysql.host` (`get_mysql_connection`, `wait_timeout=28000`).
2. `SET SESSION lock_wait_timeout = <int(--lock_wait_timeout)>`, then ``LOCK TABLES `<t>` READ`` (backticks
   doubled by `quote_mysql_identifier`). Errno 1205, read from the structured driver error code or from the
   exact message, becomes `LockAcquisitionError`. That gives skip-and-warn, or exit 1 with
   `--fail_on_lock_timeout` (11.02 §3.2 step 2, FM-11.02-7).
3. `time.sleep(--sleep_after_lock)`.
4. The MySQL side and every ClickHouse side start **at the same time**. Each side runs its own sessions. A
   `READ` lock held by the lock session does not block their reads.
5. `UNLOCK TABLES` runs in `finally` **after the last side process has exited**, and the connection is closed
   after that. If the unlock fails, its exception propagates and the run exits 1. The connection is still
   closed (`close_connection` swallows errors).

The questions this spec must answer:

- **When is the lock released relative to the ClickHouse read?** After it. The ClickHouse query starts while
  the lock is held, and the lock is released only when every side has finished. The hold time is unbounded,
  because no side has a timeout (FM-11.02-4).
- **Relative to the streaming offset?** Not related at all. The driver reads no binlog position (`SHOW BINARY
  LOG STATUS` / `SHOW MASTER STATUS`, `@@gtid_executed`) and no connector offset or replica-status table. The
  lock freezes the table's **future** writes on that server. Events committed before the lock but not yet
  flushed by the connector are still in flight.
- **Can the ClickHouse side be compared while the connector is still behind the locked position?** Yes. The
  only "catch-up" is the fixed sleep (default 3 s). The ClickHouse `SELECT ... FINAL` reads the parts that
  exist when it starts. Rows the connector inserts afterwards are not seen. A lag therefore yields
  `Checksum difference`, not a false match: the replica shows an older state of the same table, which equals
  the current state only if the pending events did not change the table (FM-11.02-6).
- **How is "replica caught up" established?** It is not. This is recorded as D-13.06-36 (S3): a lag produces
  noise that looks exactly like a real divergence. Three effects compound it, all code-read:
  1. The lock is taken on `source.mysql.host`. If the connector reads a different server's binlog, for
     example the primary while the config names a replica, writes continue upstream. ClickHouse may then be
     **ahead** of the frozen table, which is also noise.
  2. On a MySQL replica with a single-threaded applier, a `READ` lock stalls the applier at the first event
     for that table. **All** replication on that replica stops until the unlock, and with it every connector
     reading that replica.
  3. `--threads N` holds up to N table locks at once.

Without `--lock_tables_on_source`, nothing is frozen. With `--threads_per_table > 1`, the MySQL side reads its
PK chunks over separate connections at different moments, so a written table gives noise.

#### 3.7.2 Packaged (`run_config`, PT319-353)

- The lock statement is ``FLUSH TABLE `<t>` WITH READ LOCK``. The name is not escaped. No `lock_wait_timeout`
  is set, so the server default applies (often one year), and no errno is classified.
- The lock is taken **in the main thread, for every table, before any result is read**: lock t1, sleep, submit
  t1, lock t2, sleep, submit t2, and so on. Unlocking happens in the `as_completed` loop, which starts only
  after the last table has been locked and submitted. With `--threads 1` and three tables, t3 is locked while
  t1 and t2 are still checksumming. Every table is locked for the whole run (reproduced: `LOCK t1, LOCK t2,
  LOCK t3, ... UNLOCK t1, UNLOCK t2, UNLOCK t3`, D-13.06-13). `test_table_locking.py::TestPerTableLockIsolation`
  describes exactly this pattern as the "old approach" that the legacy copy replaced.
- The metadata variable `conn` is overwritten by each lock connection, so the metadata queries of table k run
  on table k's lock session. None of these connections is ever closed. When a table raises, only that table is
  unlocked before the re-raise. The other locks are released when the process exits.

### 3.8 Output parsing, verdict and exit codes

`parse_checksum(data, table, expected_name)` (identical in both copies) matches each line of the raw side
output against `CHECKSUM_LINE`, the whole log message ` - INFO - <thread> - Checksum for table <name> = <32
hex> count <digits>`. Exactly one matching line gives `(name, md5, int(count))`. Zero or several matching
lines, or a name other than `expected_name` (`<database>.<table>` taken from the side command's
`--mysql_database`/`--clickhouse_database`), log `Invalid checksum output for table ...` and return
`(name, None, None)`. Other log lines, including ones that contain the word "checksum" in a database, table or
column name, are ignored (D-13.06-2, fixed).

`analyze_differences(results, mysql_host, replica_hosts, table_name)` (identical in both copies) returns and
logs the table's verdict:

- `results` is in submission order: the MySQL source first, then one entry per replica in `replica_hosts`
  order (the packaged `compute_checksum` now also keeps submission order). The source is identified by that
  **position**, never by its host string, so equal MySQL and replica host strings still get a verdict
  (D-13.06-4, fixed).
- `ERROR`: the number of results is not `1 + len(replica_hosts)`, or any result is `None` (side failed,
  including a side that logged an ERROR line while exiting 0) or has a `None` md5 or count (unparseable output). Logged at ERROR as `Checksum ERROR for <db.t>: ...; no
  verdict`. A failed side is never compared, so `(None, None)` can no longer equal `(None, None)`
  (D-13.06-1, fixed).
- `DIFFERENT`: some replica's `(md5, count)` differs from the source's (`!=`). Logged as
  `WARNING Checksum difference : <replica tuple> to <source tuple>`, as before.
- `EMPTY`: all equal and the count is 0. Logged as `INFO EMPTY on both sides for <db.t>: 0 rows compared
  ...` (D-13.06-5, fixed). INFO, not WARNING: empty partitions are normal in date-partitioned runs, and WARNING
  is reserved for `Checksum difference` (§3.17).
- `MATCH`: all equal with rows. Logged as `INFO No difference for <db.t>`, as before.

At the end of the run `report_run_summary` logs `Run summary: N table(s) verified: a MATCH, b DIFFERENT,
c EMPTY, d ERROR`, then `EMPTY on both sides: <n> table(s) compared 0 rows ...: <tables>` when any table is
EMPTY (at INFO; at ERROR with `--fail_on_empty`, which fails the run), and an ERROR `<n> table(s) have NO verdict ...: <tables>` when any is ERROR. It returns the
exit code: 1 when any table is `ERROR`, or when any is `EMPTY` and `--fail_on_empty` is set; otherwise 0. A
lock-timeout skip keeps its own `COVERAGE GAP` summary (legacy).

Outcome table (`exit` is the process exit code of the driver):

| Situation | Legacy driver | Packaged driver |
|---|---|---|
| All sides equal | `No difference for db.t`, exit 0 | same |
| A replica differs | `WARNING Checksum difference`, exit 0 (FM-11.02-1, unchanged) | same |
| One side script exits non-zero (connection refused, SQL error, script or module not found) | `ERROR` for that table, the other tables still get verdicts, exit 1 | same (before the fix: `Checksum difference`, exit 0) |
| **Both** sides fail | `ERROR`, exit 1 | same (before the fix: **`No difference`, exit 0**, D-13.06-1) |
| Side exits 0 but prints no checksum line (table missing on the replica) | `ERROR`, exit 1 (before the fix the legacy driver reported `Checksum difference`, exit 0, for a missing replica table) | same |
| Side logs an ERROR or CRITICAL line but exits 0 | `ERROR`, exit 1 | same |
| Side logs a WARNING (columns not compared, clamped values, SQL or client-library warnings) | INFO side note, verdict unchanged | same |
| `--debug_output` | normal verdict, per-row files written by both sides (before the fix: `ERROR` for every table, D-13.06-27) | same |
| A name containing "checksum" in other side log lines | parsed correctly, normal verdict | same |
| MySQL host string equals a replica host | normal verdict | same |
| Filter matches 0 rows on both sides | `EMPTY` at INFO plus an INFO summary line, exit 0; exit 1 (summary at ERROR) with `--fail_on_empty` | same |
| Table name contains `$` | that table is compared (`^orders[$]archive$`) | same |
| Source lock timeout | `COVERAGE GAP` WARNINGs, exit 0, or 1 with `--fail_on_lock_timeout` | waits for the server `lock_wait_timeout`, then `Exception in main thread`, exit 1 |
| Partition expression with `(` or a space | passed as one argv word; the side runs | same |
| Unexpected exception | `Exception in main thread` + traceback, exit 1 | same (`os` and `traceback` are now imported, D-13.06-14 fixed) |
| Ctrl-C | `Received interrupt`, `os._exit(1)` | same |
| Invalid YAML or config | exit 1 | same |
| SQLAlchemy 2.x installed | normal verdicts: catalog rows are read by name through `mappings()` (before the fix: TypeError at the first table, exit 1, D-13.06-9) | same (before the fix: via `get_table_partition_key`) |

**Can a run end with exit 0 while a table was skipped, errored or compared zero rows?** Only in these cases:

- lock-timeout skip (designed, named in the log and in the `COVERAGE GAP` summary);
- zero rows on both sides without `--fail_on_empty` (named in the `EMPTY` lines and the summary);
- `--ignore_tables_regex`, which is not reachable from the driver;
- a table missing on MySQL, which is never enumerated.

### 3.9 MySQL side script (legacy, LM366-478)

| Flag | Default | Effect | Honoured? |
|---|---|---|---|
| `--mysql_host` | required | connection host | yes |
| `--mysql_user` / `--mysql_password` | none | Password mode, which needs both (`assert`) and logs a WARNING. Otherwise `[client]` of `--defaults_file` | yes |
| `--defaults_file` | `~/.my.cnf` | `.cnf` only, `~` expanded | yes |
| `--mysql_database` | required | the schema | yes |
| `--mysql_port` | 3306 (str) | `int()` in the URL | yes |
| `--tables_regex` | required | `rlike` table enumeration | yes |
| `--where` | none | aggregate where, `{partition_expression}` substituted (§3.13) | yes |
| `--order_by` | none | parsed, never used | ignored |
| `--ignore_tables_regex` | none | `re.match(..., IGNORECASE)`. A matched table prints nothing and exits 0 | yes |
| `--no_wc` | flag | `--tables_regex` is the table name, no catalog query. Before the fix `.mappings()` on the `[[regex]]` list raised AttributeError, exit 1 (D-13.06-26, fixed) | yes |
| `--debug_output` | flag | Also writes the per-row strings to `out.<table>.mysql.txt` (truncated per run, appended per chunk; `--debug_limit` applies to this query only). The checksum line is still printed. Before the fix it printed **no** checksum (D-13.06-27, fixed) | yes |
| `--debug_limit` | none | `limit N` on the debug query only | yes |
| `--binary_encoding` | `hex` (choices) | §3.11 | yes |
| `--min_date_value` / `--max_date_value` | `1900-01-01` / `2299-12-31` | DATE clamp (not counted or warned, D-13.06-34) | yes |
| `--source_timezone` | `UTC` | DATETIME clamp bounds shifted into the zone | yes |
| `--min_datetime_value` / `--max_datetime_value` | `DATETIME_MIN` / `DATETIME_MAX` | canonicalised and confined (11.02 §3.4) | yes |
| `--debug` | flag | DEBUG logging | yes |
| `--exclude_columns` | `nargs='+'`, `[]` | Space-separated words and comma-separated lists alike: each token is split on `,` and stripped (`parse_exclude_columns`, the same parser as the ClickHouse side, D-13.06-17) | yes |
| `--threads_per_table` | 1 | > 1 with an integer PK: PK-range chunks in parallel | yes |
| `--chunk_size` | 10000 | rows per chunk, estimated from `EXPLAIN` rows | yes |
| `--threads` | 1 | tables in parallel | yes |
| `--include_floating_point_columns`, `--include_json_columns` | False | 11.02 §3.9 | yes |

Flow:

1. `main` validates the bounds and the time zone. Bad values raise ValueError before logging is set up, and the
   process exits 1.
2. It resolves credentials and enumerates tables (`tables.mappings().fetchall()`, which is safe on
   SQLAlchemy 2.x).
3. Per table, `calculate_checksum`:
   - takes the integer PK (`data_type like '%int%'`, which also matches `point`);
   - substitutes the partition expression into `--where` if the placeholder is present and the table is
     partitioned. Otherwise the placeholder stays and MySQL rejects the SQL;
   - builds chunks with `divide_table_into_even_chunks`. With no PK (or `--threads_per_table 1`) this is one
     chunk `{}`. With a PK it is contiguous `BETWEEN lo AND hi` ranges over `[min, max]` of the filtered rows,
     each yielded only if it contains a row. An empty table yields **no chunk**, so the totals stay
     `(0,0,0,0,0,0)` and the output is `md5('0#0#0#0#0#') count 0`.
4. Per chunk, on a new connection (closed in `finally`):
   - Statements: `set names utf8mb4`, `set session wait_timeout=28000`, `set time_zone = '+00:00'`, then
     `set @md5sum := "", @a := cast(0 as signed), ...`.
   - Then the aggregate (LM223-240):

```sql
select count(*) as "cnt", coalesce(max(a),0) as a, coalesce(max(b),0) as b, coalesce(max(c),0) as c,
       coalesce(max(d),0) as d, coalesce(sum(clamped),0) as clamped
from ( select @md5sum := md5(convert(concat_ws('#', <pieces>) using utf8mb4)) as `hash`,
              @a := @a + cast(conv(substring(@md5sum, 1, 8), -16, 10) as signed) as a, ... (b: 9, c: 17, d: 25),
              <clamped expression> as clamped
       from <db>.<table> where 1=1 and <where> [and <pk> between <lo> and <hi>] ) as t;
```

5. The chunk tuples are summed in Python (arbitrary precision). `checksum_from_aggregate(cnt, a, b, c, d)` is
   computed, and `INFO Checksum for table <db>.<table> = <md5> count <cnt>` is printed. A `WARNING <n>
   out-of-range datetime values clamped ...` is printed when `clamped > 0`.

`<db>.<table>` in the aggregate is **not quoted**. Only the unused `query` string (LM184) is backticked. A table
named with `-`, or a reserved word, fails here even though the lock path quotes it (D-13.06-28). Column names
are backticked but embedded backticks are not doubled. The `information_schema` query interpolates the database
and table into `'...'` literals.

Exit codes: 0, also when nothing matched; 1 on any exception (`Exception in main thread` + traceback); 2 on
argparse errors. A credential `assert` failure exits 1 with a raw traceback, outside the `try`.

### 3.10 ClickHouse side script (legacy, LC415-509)

| Flag | Default | Effect | Honoured? |
|---|---|---|---|
| `--clickhouse_host` / `--clickhouse_port` / `--secure` | required / 9000 / False | `clickhouse_driver.connect(..., connect_timeout=20)`. `--secure` is a string, so `--secure False` is truthy and turns on TLS (D-13.06-32) | yes, see defect |
| `--clickhouse_user` / `--clickhouse_password` / `--clickhouse_config_file` | none / none / `./clickhouse-client.xml` | Password mode (`assert` user) or credentials from XML (`<user>`, `<password>`) or YAML (`config.user/password`) | yes |
| `--clickhouse_database` | required | the database | yes |
| `--sign_column` | `''` | Empty: derived from `engine_full` (11.02 §3.8) | yes |
| `--tables_regex` | required | `match(name,'<re>')` on `system.tables` | yes |
| `--where`, `--partition_key` | none | §3.13 | yes |
| `--order_by` | none | parsed, unused | ignored |
| `--ignore_tables_regex` | none | `re.match` IGNORECASE → table skipped silently | yes |
| `--no_wc` | flag | `--tables_regex` is used as the table name | yes |
| `--debug_output` / `--debug_limit` | flag / none | Per-row strings to `out.<table>.ch.txt` (`--debug_limit` applies to that query only), and the checksum line is still printed. Before the fix there was **no** checksum line (D-13.06-27, fixed) | yes |
| `--binary_encoding` | `hex` (choices) | §3.11 | yes |
| `--hex_columns` | `nargs='+'`, `[]` | Joined with `,` and split again. Requires `raw`, else ValueError (exit 1) | yes |
| `--exclude_columns` | `nargs='+'`, `['_sign,_version,is_deleted,_is_deleted']` | `parse_exclude_columns`: space-separated words and comma-separated lists alike, as on the MySQL side. Before the fix it was `"','".join(tokens).split(',')`, so several tokens got stray quotes and nothing was excluded (D-13.06-17, fixed) | yes |
| `--threads` | 1 | tables in parallel | yes |
| `--source_timezone`, `--timestamp_columns` | `UTC`, `''` | 11.02 §3.4 | yes |
| `--min_datetime_value` / `--max_datetime_value` | `DATETIME_MIN` / `DATETIME_MAX` | 11.02 §3.4 | yes |
| `--max_memory_usage` | none | `settings max_memory_usage = N` | yes |
| `--include_floating_point_columns`, `--include_json_columns`, `--json_columns` | False, False, `''` | 11.02 §3.9 | yes |

Per table (`calculate_checksum`, LC364-400):

1. Substitute the partition expression if the where contains the placeholder. The forwarded `--partition_key`
   wins. Otherwise the side reads `system.tables.partition_key`, which is a list of rows. With no row, the
   list `[]` is substituted as the text `[]`. With `''`, a WARNING is logged and an empty string substituted.
   Both give a ClickHouse syntax error.
2. Read `engine_full` and derive the sign column. Read `system.columns` (`name, type,
   if(match(type,'Nullable'),1,0), numeric_scale, is_in_partition_key, is_in_sorting_key`).
3. Apply the exclusion and un-exclusion rule. A column in the exclusion list is kept if `_<name>` also exists
   **and** the name is a connector metadata name.
4. Build the row expression, then issue (LC327-344):

```sql
select count(*) as "cnt",
  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 1, 8))))),0) as "a", ... (9, 17, 25),
  coalesce(sum(clamped),0) as "clamped"
from ( select hex(MD5( <pieces joined by ||'#'||> )) as "hash", <clamped expression> as clamped
       from <db>.<table> final where <where>[ and <sign> > 0 ] /*order by ...*/ [limit N] ) as t
[ settings do_not_merge_across_partitions_select_final=1[, max_memory_usage = N] ]
```

`FINAL` is always applied. Deleted rows are removed by `FINAL` only for `ReplacingMergeTree(ver, is_deleted)`
tables. For an older `ReplacingMergeTree(_version)` table that has an `is_deleted` column but no engine
`is_deleted` argument, no filter is derived. Its delete markers survive `FINAL` and are counted, which is noise
(code-read; 11.02 §3.8). The WHERE is applied to the merged rows. `optimize_move_to_prewhere_if_final` is not
set, so the server default applies (not determined offline).

`<db>.<table>` is not quoted (D-13.06-28). Column names are wrapped in `"..."` without escaping. Type
classification uses **substrings of the full ClickHouse type**: `'Bool'`, `'Decimal'`, `'DateTime'`, `'Float'`,
`'json'` (lower-cased) and `'String'`. An `Enum8('Float' = 1, ...)` column, which only a hand-made table can
have because the connector maps ENUM to `String`, is skipped as float or rendered as a datetime. That is noise
or a SQL error (code-read, D-13.06-29), the same bug class that 11.02 fixed on the MySQL side.

### 3.11 Per-type checksum expression on both sides, as built

The table was generated offline by repro R01. It calls each copy's real builder over stubbed catalogs. The
legacy copy is shown as the legacy driver runs it. The packaged copy is shown with the values the packaged
driver passes. ClickHouse types are those the connector auto-creates (Spec 07.01 to 07.05). "Equal?" means:
equal values give byte-identical text (expressions compared, not executed).

| MySQL type → CH type | Legacy MySQL | Legacy ClickHouse | Equal? | Packaged MySQL | Packaged ClickHouse | Equal? |
|---|---|---|---|---|---|---|
| `int`, `bigint` (signed) → `Int32`/`Int64` | `` `c` `` | `toString("c")` | yes | same | same | yes |
| `int unsigned`, `bigint unsigned` → `UInt32`/`UInt64` | `` `c` `` | `toString("c")` | yes | same | same | yes |
| `decimal(10,2)` → `Decimal(10, 2)` | `` `c` `` (prints the scale) | `toDecimalString("c",2)` | yes. For `Nullable(Decimal)` the `numeric_scale` that `system.columns` reports was **not determined** offline (a NULL gives `toDecimalString(c,None)`, a SQL error) | same | same | same |
| `float`, `double` → `Float32`/`Float64` | skipped (WARNING) | skipped (WARNING) | not compared | skipped (also any `COLUMN_TYPE` containing `float`, `double` or `real`) | skipped (`'Float'` substring) | not compared |
| `date` → `Date32` | `case when c >='2299-12-31' then CAST(... AS date) else case when c <= '1900-01-01' then CAST('1900-01-01' AS date) else c end end` | `toString("c")` | yes inside the range. Out-of-range values rely on the connector clamping to the same bounds. A zero date `0000-00-00` maps to `1900-01-01` on MySQL; the connector's value for it was not determined | same | `toString` (the `'date' ==` branch never matches `Date32`) | same |
| `datetime(p)`, p = 0, 3, 6 → `DateTime64(p, 'UTC')` | shared clamp over `date_format(c,'%Y-%m-%d %H:%i:%s.%f')`, bounds shifted into `--source_timezone` | shared clamp over `toString(toDateTime64("c",6),'<source tz>')` | yes, as instants (11.02 §3.4) | p ≤ 3: `case when c >= substr('2299-12-31 23:59:59',1,length(c)) ... when c <= '1900-01-01 00:00:00' then <min trimmed> else substr(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM cast(c as char))),1,length(c)) end`. p 4-6: the same with `datetime(6)` | `DateTime64(0`/`DateTime64(6`: `if(toString(c) >= '2299-12-31 23:59:59', ..., if(toString(c) < '1900-01-01 00:00:00', '1900-01-01 00:00:00', trim(...)))`. `DateTime64(3)` and others: trim only, no clamp | yes inside the DateTime64 range, which both sides now share (before the fix the floor was `1969-12-31 18:00:00` and every earlier value rendered as the same constant, D-13.06-6). `datetime(0)` against `DateTime64(3)` trims differently (noise) |
| `timestamp(p)` → `DateTime64(6, 'UTC')` | same clamp. Session `time_zone='+00:00'` | rendered in `'UTC'` (`--timestamp_columns`) | yes | `substr(TRIM(... cast(c as char)))` in the **server** session zone, no clamp | `DateTime64(6` branch, rendered in the column zone (UTC), clamped | no, unless the MySQL server zone is UTC (noise, D-13.06-20) |
| `time(p)` → `String` (`[-]HH:MM:SS.ffffff`) | `cast(c as time(6))` | `toString("c")` | yes | `substr(cast(c as time(6)),1,length(c))`: `time(3)` gives 12 characters | `toString("c")` (26 characters stored) | no (noise, D-13.06-20) |
| `year` → `Int32`/`UInt16` | `` `c` `` | `toString("c")` | yes for 1901-2155. `YEAR` 0 (`0000` against `0`) **not determined** | same | same | same |
| `bit(1)` → `Bool` / `Nullable(Bool)` | `` `c`+0 `` | `toString(toUInt8("c"))` | yes | `replace(to_base64(cast(c as binary)),'\n','')` (packaged `is_binary_datatype` substring `bit`) | `Bool` exact: `toUInt8`. `Nullable(Bool)`: `toString` = `true`/`false` | no (noise, D-13.06-20) |
| `bit(n>1)` → `String` | `lower(hex(cast(c as binary)))` under every `--binary_encoding`: the connector stores BIT(n>1) as lower-case hex text under every `binary.handling.mode` (base64 applies to binary/varbinary/blob only; observed end to end). Before the fix `--binary_encoding base64` rendered base64 (D-13.06-38) | `toString` (raw: `lower(hex(c))` if listed) | yes | base64 (driver-forced) | `toString` | only when the connector stores base64 |
| `enum('float','x')` → `String` | `` `c` `` (classified on `DATA_TYPE`) | `toString` | yes | **skipped** (`'float' in COLUMN_TYPE`) | `toString` | no (column sets differ, noise, D-13.06-20) |
| `set('realtime','b')` → `String` | `` `c` `` | `toString` | yes | **skipped** (`'real'` substring) | `toString` | no |
| `json` → `String` | skipped unless opted in. Opt-in: eleven `REGEXP_REPLACE`s over `json_pretty` | skipped if listed in `--json_columns` (the driver derives the list) | not compared by default | skipped by default with a WARNING (`--include_json_columns` now defaults to False). Opt-in: the same regexes | skipped by default with a WARNING: native JSON types and the `--json_columns` the packaged driver derives from MySQL. Opt-in: `toString` (stored text) | not compared by default, as in legacy. Before the fix JSON was always compared with one-sided normalisation, so `{"a": 1.0}` against `{"a":1}` compared EQUAL (D-13.06-7) |
| `blob`, `binary(n)`, `varbinary(n)` → `String` | hex mode: `lower(hex(cast(c as binary)))`. base64: `replace(to_base64(...),'\n','')`. raw: hex | hex/base64: `toString("c")`. raw: `lower(hex("c"))` for `--hex_columns` | yes with a matching mode (11.02 §3.6) | base64 (forced by the driver). Note `cast(` `` `c` ``as binary)` with no space, which is valid | `toString`. `--hex_columns` means `toString(unhex(c))` (the inverse), never passed | only when the connector uses base64 |
| spatial (`point`, `geometry`, ...) → per Spec 07.06 | binary rendering of MySQL's internal SRID+WKB | `toString` | **not determined** (depends on the connector's spatial encoding) | binary rendering | `toString` | not determined |
| `char`/`varchar`/`text`, one collation in the table | `` `c` `` (the outer `convert(concat_ws(...) using utf8mb4)` turns it into UTF-8) | `toString` | yes (MySQL strips CHAR trailing spaces, and so does Debezium; `PAD_CHAR_TO_FULL_LENGTH` not considered) | same | same | yes |
| text, ≥ 2 collated columns | `convert(c using utf8mb4)` per column. `same_charset` counts **columns**, not distinct collations (D-13.06-35) | `toString` | yes | same | same | yes |
| NULL in a nullable column | `ifnull(<expr>,'')` for a column declared nullable, plus one trailing `concat(ISNULL(a),ISNULL(b),...)` with one flag per **compared** column, nullable or not | `case when c is null then '' else <expr> end` for a `Nullable` column, plus one trailing `case when c is null then '1' else '0' end \|\| ...` with one flag per **compared** column (a non-Nullable column yields `0`) | yes, whatever each catalog declares nullable: the flags are value-based over the same columns on both sides, so equal values give the same flags. A NULL on MySQL against the non-Nullable default on ClickHouse still differs in its flag (`1` against `0`). Before the fix each side flagged only the columns its own catalog declares nullable, so a nullability mismatch with equal values made every row differ (D-13.06-40) | same rule (`ifnull` per nullable column, `, concat(ISNULL(...))` over every compared column) | same rule, `\|\|'#'` before the flags | yes, as in legacy (D-13.06-40 fixed in both copies) |
| empty string `''` | `''` plus flag `0` | `''` plus flag `0` | yes, distinct from NULL | same | same | yes |

Whole-row shape: legacy joins the pieces with `','` inside `concat_ws('#', ...)` on MySQL and with `||'#'||` on
ClickHouse, and skipped columns contribute nothing (11.02 §3.3). In both copies the last element is always the
null flags: one value-based flag per compared column, in column order, the same set of columns on both sides
(after exclusions and the floating-point/JSON skips), so `(id int NOT NULL, name varchar NOT NULL)` gives
`id#name#00` on both sides for a row without NULLs (D-13.06-40). The packaged ClickHouse side now writes
`||'#'||` before every compared column but the first, so `(id Int32, name String, f Float64)` gives
`toString("id")||'#'||toString("name")`, matching MySQL's `id#name`. Before the fix it appended `||'#'` after
every column except the last of the unfiltered list, which left a dangling `#` whenever the last column was
skipped (noise, part of D-13.06-20; fixed together with D-13.06-7, whose JSON exclusion would otherwise have
added such tables). The structural blind spots of 11.02 §6 (no length prefix, positional hashing) apply to
both copies.

### 3.12 Aggregation arithmetic

- Per row: MD5 as 32 hex characters, split into four 8-hex words. MySQL reads each word with `conv(w, -16, 10)`
  cast to signed. ClickHouse reads it with `reinterpretAsInt64(reverse(unhex(w)))`. Both give the unsigned
  32-bit value, 0 to 2^32-1.
- Sums. The MySQL side keeps a running sum `@a := @a + w` in signed BIGINT, and `max(a)` takes the final value
  because the increments are ≥ 0. The ClickHouse side uses `sum()` over Int64.
- Final value: `md5('<cnt>#<a>#<b>#<c>#<d>#')` (`checksum_from_aggregate`). The packaged copy inlines the same
  formula (PM304-317, PC66-76).
- **Overflow.** The expected word sum reaches 2^63 at about 2^32 rows (4.3 × 10^9) per table, or per chunk on
  MySQL.
  - ClickHouse `sum(Int64)` wraps silently.
  - MySQL signed BIGINT arithmetic raises error 1690 ("BIGINT value is out of range") rather than wrapping.
    This is documented MySQL behaviour and was not executed here.
  - With chunks, each chunk stays below the limit and Python sums the totals exactly, so they never wrap.
  - Result: very large tables give a loud MySQL failure or a DIFFERENT, never a false match. Spec 11.02 §6
    item 2 ("two's complement in both engines") is inaccurate for MySQL (D-13.06-35).
- An empty result is `md5('0#0#0#0#0#') count 0` on both sides. Nothing distinguishes "empty table" from
  "filter matched nothing", so the driver reports both as verdict `EMPTY` (logged at INFO), never as a match
  (§3.8, D-13.06-5 fixed).

### 3.13 `--where`, `--partition_date` and `{partition_expression}` handling

- **One SQL text for two dialects.** The driver passes the same where text (`--where` AND the per-table
  override) to MySQL and to ClickHouse. The operator must write dialect-neutral SQL. The tests show a
  `/*!50000 ... */` trick that hides MySQL-only parts from ClickHouse.
- **Literal semantics differ.** On MySQL the session is `time_zone='+00:00'`, so a `TIMESTAMP` literal is a UTC
  instant. On ClickHouse, a literal compared with `DateTime64(p,'UTC')` is parsed in UTC. Those agree. A
  **`DATETIME`** literal, however, is a wall clock in the source zone on MySQL, while on ClickHouse it is parsed
  in the column zone (UTC). In a non-UTC deployment the two sides therefore select **different row sets**
  (code-read with engine semantics, D-13.06-21). That is noise, not a false match.
- **Substitution.** The legacy `fstr` (LM199-204, LC293-298, count LMC41-46) replaces every literal
  `{partition_expression}` with the partition expression and leaves everything else alone (pinned by
  `mysql_table_checksum_test.py`). The packaged `fstr` (PM168-170, PC218-220, PMC44-46) is
  `eval(f"f'{template}'")`, decorated with `@staticmethod` at module level:
  - a `'` in the where is a SyntaxError (reproduced);
  - `{expr}` evaluates Python (`{__import__('os').getpid() > 0}` gave `True`, reproduced, D-13.06-22);
  - on Python < 3.10 the staticmethod object is not callable (`TypeError`, reproduced with Python 3.9), although
    `pyproject.toml` declares `requires-python = ">=3.6"` (D-13.06-23).
- **`--partition_date`.** MySQL gets `{pe}=YYYYMMDD`, ClickHouse gets `{pe}=toDate('YYYY-MM-DD')`. The
  ClickHouse `{pe}` is the **MySQL** partition expression (forwarded as `--partition_key`), not the ClickHouse
  table's own key. 11.02 §6 item 5 lists the pairing limits. This spec adds three consequences:
  1. An unpartitioned MySQL table leaves the placeholder on the MySQL side. MySQL rejects it and the run aborts
     (D-13.06-11).
  2. A function partition used to abort at the shell (D-13.06-10, fixed: it is now one argv word). Under
     `--partition_date` the ClickHouse side then compares that MySQL expression with `toDate(...)` (11.02 §6
     item 5).
  3. A `KEY`, `HASH` or `LIST` partition on an integer column gives `id=20260928` on MySQL and
     `id=toDate('2026-09-28')` on ClickHouse. Both typically select zero rows, which is now verdict `EMPTY`
     (exit 1 with `--fail_on_empty`) instead of "No difference" (D-13.06-5).
- **Quoting.** No shell is involved (§3.6.1).

### 3.14 Count runners

Neither count runner is called by a driver, and no tool compares their outputs. Each prints one INFO line per
table, `Count for table <db>.<table> = <n>`, and the operator compares the lines by hand.

**MySQL count** (`mysql_table_count.py`, both copies). Flags:

- `--mysql_host` (required), `--mysql_user`, `--mysql_password`, `--defaults_file`, `--mysql_database`
  (required), `--mysql_port`;
- `--include_tables_regex` (default `.`; note the name differs from the ClickHouse runner's `--tables_regex`),
  `--exclude_tables_regex`, `--include_partitions_regex`, `--non_partitioned_tables_only`, `--where`;
- `--threads` (tables), `--threads_per_table` (partitions in parallel);
- `--no_wc` takes `--include_tables_regex` as the table name (it crashed before, D-13.06-26, fixed);
  `--order_by`, `--debug_output`, `--debug_limit` and `--exclude_columns` are parsed and unused.

Behaviour per table:

1. Read `information_schema.partitions` rows for the table, filtered by the partition regex.
2. For each row, issue the tuple `('set local innodb_parallel_read_threads=32', 'select count(*) from
   <db>.<table> [partition(<p>)] [WHERE <where>]')`. The variable exists from MySQL 8.0.14, so older servers
   raise an error. `<db>.<table>` is unquoted.
3. Sum the counts.

Faults:

- Fixed: `partitions.fetchall()` followed by `row['partition_name']` (LMC67-70, PMC67-70) and `tables.fetchall()`
  followed by `table['table_name']` (LMC215, PMC213) crashed on SQLAlchemy 2.x (D-13.06-9); the rows are now
  read through `mappings()`.
- A sub-partitioned table has one `information_schema.partitions` row per **sub**partition with a repeated
  `PARTITION_NAME`. Each row counts the whole partition, so the total is multiplied by the number of
  subpartitions (code-read, D-13.06-19).
- The where placeholder is substituted only for partitioned tables.

**ClickHouse count** (`clickhouse_table_count.py`, both copies, identical apart from imports). Flags:
`--clickhouse_host`, `--clickhouse_database` and `--tables_regex` (all required), the connection flags,
`--include_partitions_regex`, `--where`, `--ignore_tables_regex`, `--no_wc`, `--debug` and `--threads`. No
option compares two hosts (there is no `--dr_host`): run the runner once per host and compare the lines.

Behaviour per table:

1. Without `--include_partitions_regex`: one `select count(*) cnt from <db>.<table> final where 1=1 [and
   <where>] settings do_not_merge_across_partitions_select_final=1` over the whole table.
2. With it: read `select distinct partition from system.parts where ... active and
   match(partition,'<include_partitions_regex>')`, run the same count per partition with `and <partition_key> =
   '<partition>'` (no filter when the partition key is empty), then sum. With `--no_wc` (`--tables_regex` is
   the table name) the partition key is read from `system.tables`.

Faults:

- Fixed: with the default `--include_partitions_regex` (None) the f-string emitted `match(partition,'None')`.
  No partition matched, so **every table printed `Count for table ... = 0`** (reproduced for both copies,
  D-13.06-18). The table is now counted whole. `--no_wc` raised IndexError (its `[[regex]]` row has no
  partition key) and now counts the named table.
- The partition filter is added only when a regex is given and the table is partitioned. If the regex matched
  partitions without a filter, each partition would count the whole table.
- The per-partition-`FINAL` setting is always on (11.02 §3.7 explains why that over-counts rows moved between
  partitions), even though the code's own comment calls it unsafe.
- No sign filter is applied. `FINAL` drops `is_deleted` rows only for `ReplacingMergeTree(ver, is_deleted)`.

### 3.15 Shared modules as used by these runners

- **`db/checksum_common.py`** (legacy only). It is described function by function in 11.02 §3.4-3.5 and
  §3.9: `checksum_from_aggregate`, `canonical_datetime_bound`, `datetime_bounds`, `clamp_datetime_expression`,
  `clamped_datetime_flag`, `clamped_count_expression`, `validate_timezone`, `shift_datetime_bounds`,
  `warn_not_compared` and `parse_column_list`. Nothing to add, except that the WARNINGs of `warn_not_compared`
  and the clamp WARNING are now relayed into the driver log by both drivers, at INFO as side notes (D-13.06-8,
  fixed; §3.17). `parse_exclude_columns` (added with D-13.06-17) turns the `--exclude_columns` words into one
  list of names for both legacy sides; the packaged sides inline the same rule.
- **`db/mysql.py` / `ch_sink_tools/db/mysql.py`** (Spec 13.02). Used here:
  - `get_mysql_connection`. Legacy URL-encodes with `quote_plus`, so a password containing a space arrives as
    `+` after SQLAlchemy decoding (reproduced: `'a b'` → `'a+b'`). Packaged does not encode, so `p@ss:w/rd`
    raises ValueError (reproduced, D-13.03-19).
  - `get_tables_from_regex[_sql]`, `get_partitions_from_regex`, `get_table_partition_key` (legacy uses
    `.mappings()`, packaged `.fetchall()` + `row['...']`), `mysql_pk_columns`, `get_min_max_pk_value`,
    `estimate_table_count`, `divide_table_into_even_chunks`, `execute_mysql` (SQL warnings are logged at WARNING
    with the first message), `resolve_credentials_from_config` and `is_binary_datatype` (legacy matches the bare
    keyword exactly, packaged matches substrings `blob`, `binary`, `varbinary`, `bit`).
  - `mysql_columns_by_data_type` and `binary_datatypes` (legacy only; the packaged list lacks `tinyblob`,
    `mediumblob`, `longblob` and `geometrycollection`).
- **`db/clickhouse.py` / `ch_sink_tools/db/clickhouse.py`**:
  - `clickhouse_connection` (packaged turns a `None` password into `""`), `clickhouse_execute_conn` (legacy
    closes the cursor in `finally`), and `execute_sql`, which returns `(rows, len(rows))`. "rowcount" is
    therefore the number of result rows.
  - `get_table_partition_key` (rows of `system.tables.partition_key`).
  - `resolve_credentials_from_config`. Legacy masks the password in its DEBUG line, packaged logs it in clear
    (reproduced: `clickhouse_password S3cret!`, D-13.06-24). Packaged uses `yaml.FullLoader`, legacy
    `safe_load`.
- **`ch_sink_tools/db_compare/_expressions.py`**. `_build_ch_col_expr(col_name, pg_type, is_nullable)` builds the
  ClickHouse text of one column for the **PostgreSQL** checksum (`top_level_postgres_checksum.py` and
  `auto_diff.py` import it). It renders:
  - `boolean` as `if(c = 0,'0','1')`, with NULL kept for nullable columns;
  - `timestamp with time zone` as `toString(toTimeZone(c,'UTC'))`;
  - other `timestamp` types as `toString(c)`;
  - `json`/`jsonb` as the raw column;
  - everything else as `toString(c)`;
  - and wraps nullable columns in `coalesce(..., '')`.

  No MySQL runner imports it. Its contract belongs to the PostgreSQL verification spec of this domain.

### 3.16 Legacy against packaged, function by function

**Driver** (LT / PT):

| Function | Behavioural difference |
|---|---|
| imports | LT: `from db.mysql import *` (also brings `os`), plus `traceback`, `validate_timezone`. PT: explicit list plus `os`, `traceback` (added, D-13.06-14 fixed), without `mysql_columns_by_data_type` and `binary_datatypes` |
| `LockAcquisitionError`, `_mysql_error_code`, `_is_lock_wait_timeout` | LT only. PT has no lock-timeout classification |
| `parse_config`, `validate_config`, `parse_checksum`, `relay_side_messages`, `run_quick_safe_checksum`, `analyze_differences`, `report_run_summary`, `exact_table_regex`, `unlock_tables`, `match_table_include_list`, `get_tables_from_regexp` | identical behaviour |
| `run_quick_safe_command` | both: argv list, no shell, rc `127` when the program cannot start. PT also prepends the package root to the child's `PYTHONPATH` (`side_environment`) |
| `compute_checksum` | LT owns the lock lifecycle (lock, sleep, sides, unlock/close in `finally`). PT has no lock (it is in `run_config`) and forwards `json_columns`. Both return results in submission order |
| `get_mysql_checksum_command` | LT: `python db_compare/mysql_table_checksum.py`, `--source_timezone`, `--binary_encoding <arg>`, include flags, no datetime bounds. PT: `<sys.executable> -m ch_sink_tools.db_compare.mysql_table_checksum`, `--min_datetime_value "1900-01-01 00:00:00" --max_datetime_value "2299-12-31 23:59:59"`, `--binary_encoding base64` |
| `get_clickhouse_checksum_command` | LT: plain `toDate('...')`, `--source_timezone`, `--timestamp_columns`, `--json_columns`, `--binary_encoding` and raw `--hex_columns`, include flags. PT: `-m ch_sink_tools.db_compare.clickhouse_table_checksum`, `toDate(\'...\')` for the eval `fstr`, the full-range bounds, `--json_columns`, none of the other flags |
| `mysql_json_columns`, `side_command`, `side_environment` | PT only |
| `include_flags_clause`, `normalize_where_override`, `quote_mysql_identifier`, `close_connection`, `resolve_source_timezone` | LT only |
| `lock_tables` | LT: ``LOCK TABLES `t` READ``, backticks doubled, optional `SET SESSION lock_wait_timeout`, errno 1205 → `LockAcquisitionError`. PT: ``FLUSH TABLE `t` WITH READ LOCK``, no escaping, no timeout |
| `run_config` | LT: resolves the source zone; prefetches TIMESTAMP, binary and JSON columns; locks per table inside the future; skip-and-warn plus `COVERAGE GAP` summary; normalises where overrides. PT: prefetches JSON columns; locks every table serially in the main thread before reading results; overwrites `conn`; unlocks in the result loop; no lock-timeout handling. Both collect one verdict per table and end with `report_run_summary`'s exit code |
| `valid_date` | LT catches `ValueError` on the first format. PT uses a bare `except`. Same practical result |
| `main` | PT lacks six flags (§3.3). Both have `--fail_on_empty` |

**MySQL side** (LM / PM):

| Function | Behavioural difference |
|---|---|
| `compute_checksum` | LM leaves the connection to the caller. PM closes it in `finally` (then the caller closes it again) |
| row-expression builder | LM: `mysql_column_expression` and `build_mysql_row_expression` classify on `DATA_TYPE` plus `DATETIME_PRECISION`, use the shared datetime clamp, `cast(c as time(6))`, `bit(1)` as `+0`, hex/base64 binary, and list-join. PM: inline loop over `COLUMN_TYPE` substrings (`float`, `double`, `real`, `json`, `bit`, `blob`, `binary`), trim-based datetime/timestamp/time, no clamp count, `first_column` comma logic |
| `get_table_checksum_query` | LM returns 5 values (with the clamped expression) and warns once per table about skipped columns. PM returns 4 and logs each skipped float or JSON column at WARNING (`Not compared in table ...`; once per chunk) |
| `fstr` | LM literal replace. PM `eval` f-string plus module-level `@staticmethod` |
| `select_table_statements` | LM adds `set time_zone = '+00:00'` and the `clamped` column. PM does not |
| `calculate_sql_checksum`, `calculate_checksum_single_thread`, `get_tables_from_regexp` | same flow (LM threads the clamped expression) |
| `calculate_checksum` | LM sums 6 values, logs the clamp WARNING and calls `checksum_from_aggregate`. PM sums 5 and inlines the md5. LM logs `Checksum failed for <t>` before re-raising |
| `main` / parser | LM `build_argument_parser()`: `--binary_encoding` with choices, `--source_timezone`, bounds `DATETIME_MIN`/`DATETIME_MAX` canonicalised, `--include_json_columns` default False. PM: free-text `--binary_encoding` (anything but `base64` means hex), bounds `1900-01-01 00:00:00`/`2299-12-31 23:59:59` used raw (the floor was `1970-01-01 00:00:00` before D-13.06-6 was fixed), `--include_json_columns` `store_true` with default False (it was True and could not be disabled before D-13.06-7 was fixed) |

**ClickHouse side** (LC / PC):

| Function | Behavioural difference |
|---|---|
| `compute_checksum` | LC reads 6 values, logs the clamp WARNING and uses `checksum_from_aggregate`. PC hashes every returned value (5) inline. Same formula |
| `get_table_checksum_query` | LC: `build_clickhouse_row_expression` (list-join, `'Bool' in type`, `toDecimalString`, six-digit datetime in the UTC or source zone with the shared clamp, raw hex, JSON skip by type or `--json_columns`), un-exclusion only for connector metadata names, and the per-partition `FINAL` decision. PC: inline loop (`'Bool' ==` exact, a PostgreSQL-style `to_char` for types containing `timestamp` or equal to `date` that never match ClickHouse types, trim-based `DateTime64(0`/`(6` clamp with the given bounds, `toString(unhex(c))` for `--hex_columns`, JSON skip by type (case-insensitive) or `--json_columns` with a WARNING, separator before each compared column), un-exclusion for **any** excluded column whose `_`-prefixed twin exists, an unused `excluded_columns_str` |
| `SINK_METADATA_COLUMNS`, `is_datetime_type`, `clickhouse_datetime_rendering`, `clickhouse_column_expression`, `build_clickhouse_row_expression`, `get_engine_full`, `sign_column_from_engine`, `partition_key_within_sorting_key`, `warned_tables` | LC only |
| `fstr` | literal (LC) against `eval` (PC) |
| `select_table_statements` | LC: settings only when needed (per-partition `FINAL`, memory), sign column passed in. PC: always `do_not_merge_across_partitions_select_final=1`, sign from `args.sign_column` |
| `calculate_checksum` | LC: engine-derived sign. PC: sign from `--sign_column`. Neither runs a count pre-check (PC's dead `select count(*)` pre-check was removed, D-13.06-25 fixed) |
| `main` / parser | PC no longer runs `CREATE FUNCTION ... format_decimal` (unused; it needed the DDL privilege, D-13.06-25 fixed). PC defaults: `--sign_column _sign`, `--exclude_columns nargs='*'`, `--include_json_columns` False, `--json_columns ''`, min/max `1900-01-01 00:00:00` / `2299-12-31 23:59:59` (the max was `2299-12-31 23:59:59.000000`). PC lacks `--binary_encoding`, `--source_timezone` and `--timestamp_columns`, so the legacy driver's flags would make it exit 2 |

**Count runners**: MySQL count differs only in `fstr` (literal against `eval`) and in error handling
(`Count failed for <t>` logged in legacy). ClickHouse count differs only in its import lines.

**Shared helpers**: see §3.15.

### 3.17 Logging, artefacts, resources, time zones

- **Log record factory.** Every runner installs a log-record factory that sets `record.user = "me"` at import
  time, which is a global side effect.
- **Log-level contract (both drivers).** In the driver log, WARNING is reserved for `Checksum difference`,
  whose message format is unchanged. Scheduled jobs tee the driver's stdout to a file and fail when any line
  contains the word WARNING; that scan exists to catch differences. So everything a clean run logs is INFO or
  below: `No difference`, `EMPTY on both sides`, the run summary and the side notes. A side WARNING (columns not
  compared, clamped values, SQL or client-library warnings) is relayed at INFO as `<host> <table> side note:
  ...`, with the level word removed and any other `WARNING` lowered (`side_note_text`). A side ERROR or
  CRITICAL line is relayed at ERROR and voids that side's result, so the table is `ERROR` and the run exits 1.
  `--debug` dumps the side output line by line through the same filter. Pre-existing driver WARNINGs are
  unchanged: the lock-timeout `COVERAGE GAP` lines, `Failed to close connection`, and the `SQL warnings` that
  `execute_mysql` logs when the client library raises a Python warning on the driver's own catalog or lock
  queries. Side scripts run standalone still log their notices at WARNING. Pinned by
  `sink-connector/python/db_compare/tests/test_checksum_job_log_contract.py::TestScheduledJobLogContract`.
- **Side output.** The driver relays side ERROR and CRITICAL lines at ERROR and side WARNING lines at INFO
  (above), logs the last 20 side lines at ERROR when a side fails or its output cannot be parsed, and logs the
  command and the side output at DEBUG.
- **Resources.** Per table, the legacy driver holds:
  - one lock connection (when locking);
  - one metadata connection per database, never closed;
  - `1 + replicas` shells, each with its Python and one engine session per chunk.

  `--max_memory_usage 80000000000` is hard-coded for ClickHouse. No execution-time limit exists on either side
  (FM-11.02-4).
- **Time zones.** The source zone comes from the driver (11.02 §3.2). The packaged copy has no zone handling.

## 4. Invariants Preserved

- **I3 (Eventual Convergence)** and **I7 (Value-Level Type Equivalence)**. The legacy rendering table (§3.11)
  is the operational definition of "same value". The structural false matches D-13.06-1 to -7 are fixed. The
  packaged copy still does not preserve I7 (its renderings report DIFFERENT for equal values, D-13.06-20).
- **I9 (Loud Failure)**. Violated: a difference exits 0 (FM-11.02-1). Preserved since the fixes: failed or
  unparseable sides are `ERROR` with exit 1 (D-13.06-1, -2), side WARNINGs are relayed as side notes (D-13.06-8),
  and zero-row comparisons are an explicit `EMPTY` verdict, exit 1 with `--fail_on_empty` (D-13.06-5).
- **I14 (Bounded Bookkeeping)**. Not applicable. These are operator tools that scan replicated data on purpose,
  outside the connector.
- **I15 (Bounded, Declared Recovery)**. Every failure mode below declares Detection, Recovery and RTO. Most
  RTOs are proportional to table size and unmeasured, because no database was available.

## 5. Verification Criteria

### 5.1 Existing tests (offline) and results

The suite was run on 2026-10-01 from `sink-connector/python` with the toolset venv (Python 3.12.11, SQLAlchemy
2.1.1, PyMySQL 2.2.8). `python -m pytest -q -p no:cacheprovider db_compare/tests` gives **109 passed,
2 skipped in 4.90 s**. The skips are the two DEFECT pins of FM-11.02-1 and FM-11.02-2. All tests target the
**legacy** copy. Representative tests per contract:

- Shared rendering (11.02 §5 lists the full set):
  - `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestClickHouseRowExpression::test_trailing_float_column_leaves_no_dangling_separator`
  - `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestMySQLColumnClassification::test_enum_labels_do_not_classify_the_column`
  - `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestInstantComparison::test_driver_resolves_the_source_zone_from_mysql`
  - `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestBinaryEncoding::test_driver_passes_the_encoding_to_both_sides_and_raw_columns_only_in_raw_mode`
  - `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestEndToEndChecksum::test_flipped_clickhouse_value_reports_different`
  - `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestRemovedDeadPaths::test_exclude_columns_nargs_match_on_both_sides`. It checks `nargs` only. The multi-token parse of D-13.06-17 is covered by `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestExcludeColumnsForms`.
- Lock lifecycle (§3.7.1):
  - `sink-connector/python/db_compare/tests/test_table_locking.py::TestComputeChecksumLockLifecycle::test_lock_released_on_mysql_checksum_exception`
  - `sink-connector/python/db_compare/tests/test_table_locking.py::TestLockHoldDuration::test_lock_held_during_both_checksums`
  - `sink-connector/python/db_compare/tests/test_table_locking.py::TestConcurrentChecksums::test_mysql_and_clickhouse_run_concurrently`
  - `sink-connector/python/db_compare/tests/test_table_locking.py::TestComputeChecksumResults::test_database_override_map_applied`. It covers only the exact `a:b` form.
- Bounded lock:
  - `sink-connector/python/db_compare/tests/test_bounded_source_lock.py::TestIsLockWaitTimeout::test_does_not_match_table_name_containing_1205`
  - `sink-connector/python/db_compare/tests/test_bounded_source_lock.py::TestLockTables::test_sets_session_timeout_before_lock`
  - `sink-connector/python/db_compare/tests/test_bounded_source_lock.py::TestCliContract::test_fail_on_lock_timeout_flag_exists`
- Verdict and exit code (§3.8):
  - `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestRunExitCode::test_failed_side_script_fails_the_table_and_the_run_non_zero`
  - `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestRunExitCode::test_lock_timeout_skips_the_table_and_names_it_in_the_summary`
  - `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestVerdictOnUnparseableOutput::test_parse_checksum_refuses_more_than_one_line`
  - `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestVerdictOnUnparseableOutput::test_both_sides_unparseable_is_never_reported_equal`
- Fixed false-match paths and manual paths (added with the fixes; the suite then gives 296 passed, 4 skipped
  over `db_compare/tests db_load/tests db_dump/tests tests`):
  - `sink-connector/python/db_compare/tests/test_checksum_verdicts.py` (legacy driver: failed sides, "checksum"
    in names, argv quoting with a stand-in `python`, equal host strings, `EMPTY`, side notes, side ERROR lines)
  - `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py` (packaged driver and sides:
    side modules from a foreign cwd, verdicts, datetime bounds, JSON coverage, read-only ClickHouse side)
  - `sink-connector/python/db_compare/tests/test_checksum_job_log_contract.py` (the legacy driver's main() with the scheduled jobs' flags and real
    side processes against a stand-in `python`: a clean run with an `EMPTY` table and side notes logs no
    WARNING and exits 0; a difference logs exactly the `Checksum difference` WARNING)
  - `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py` (`--no_wc`, `--exclude_columns` forms, the ClickHouse count,
    `--debug_output`; both copies)
- Defects found by the end-to-end suite (§5.3; with them the four suites give 550 passed, 4 skipped):
  - `sink-connector/python/db_compare/tests/test_sqlalchemy_rows.py` (real SQLAlchemy rows from an in-memory
    SQLite database through both drivers, both MySQL count runners, the packaged MySQL side and both dumpers;
    D-13.06-9, D-13.03-3; passes on SQLAlchemy 1.4 and 2.x)
  - `sink-connector/python/db_compare/tests/test_scheduled_job_findings.py` (BIT(n>1) rendering, D-13.06-38;
    `install.sh` under `set -euo pipefail` with stand-in `python3`/`pip`, D-13.06-39)
- Defect found by the scheduled job on a production-shaped schema (with it the four suites give 559 passed,
  4 skipped):
  - `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestNullFlagsOverEveryComparedColumn`
    (legacy: one value-based null flag per compared column on both sides, whatever each catalog declares
    nullable; excluded and skipped columns flag nothing; D-13.06-40)
  - `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedNullFlagsOverEveryComparedColumn`
    (the same for the packaged sides; D-13.06-40)
- Where quoting (§3.13):
  - `sink-connector/python/db_compare/tests/test_top_level_where_quoting.py::ClickHouseWhereQuotingTestCase::test_partition_date_uses_plain_quotes`
  - `sink-connector/python/db_compare/tests/test_top_level_where_quoting.py::WhereOverrideNormalizationTestCase::test_legacy_escaped_quotes_are_folded`
  - `sink-connector/python/db_compare/tests/test_top_level_where_quoting.py::MySQLWhereQuotingTestCase::test_fragment_after_fstr`
  - `sink-connector/python/db_compare/tests/mysql_table_checksum_test.py::FstrTestCase::test_does_not_evaluate_python_in_template`

What the tests do **not** reach:

- the packaged renderings against the fidelity fixtures, and the packaged count runners;
- the MySQL count runner;
- SQLAlchemy `Row` objects (every fake returns dicts);
- `database_override_map` whitespace;
- the YAML `ignored_columns` and `table_include_list` parsing.

These tests do not run in CI (FM-11.05-3).

### 5.2 Acceptance criteria (each one is a test to add; `GAP` until added)

1. `analyze_differences` never logs "No difference" when either side's checksum or count is `None`. Covered:
   `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestFailedSidesAreErrors::test_both_sides_failing_exits_non_zero_and_never_reports_equal`.
2. `analyze_differences` logs an explicit verdict, or raises, when the source host string is also a replica
   host. Covered: `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestSameHostString::test_equal_host_strings_still_get_a_difference_verdict`.
3. A zero count on both sides is logged as the verdict `EMPTY on both sides` ("0 rows compared"), at INFO so
   that scheduled jobs failing on WARNING keep passing. Covered:
   `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestEmptyOnBothSides::test_empty_is_logged_at_info_and_exit_zero_by_default`.
4. The driver command line, executed with a stand-in `python` that prints its argv, delivers
   `--tables_regex`, `--where` and `--partition_key` byte-identical to their inputs for `$`, backticks, quotes
   and parentheses. Covered (argv list, no shell):
   `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestArgumentsReachTheSideUnchanged::test_clickhouse_side_receives_table_where_and_function_partition_key`.
5. A table, database or column name containing `checksum` still yields one parsed result per side. Covered:
   `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestChecksumWordInNames::test_differing_data_with_checksum_named_column_reports_a_difference`.
6. The driver surfaces every side-script WARNING line (coverage, clamp) as an INFO side note, and a clean run
   logs no WARNING at all. Covered:
   `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestSideWarningsReachTheDriverLog::test_side_warnings_are_logged_by_the_driver`
   and `sink-connector/python/db_compare/tests/test_checksum_job_log_contract.py::TestScheduledJobLogContract::test_clean_run_with_empty_table_and_side_notes_logs_no_warning`.
7. The driver row loop runs over real SQLAlchemy `Row` objects of the installed major version. Covered:
   `sink-connector/python/db_compare/tests/test_sqlalchemy_rows.py::TestDriversReadRealRows::test_legacy_driver_compares_a_table_listed_by_sqlalchemy`
   (and the packaged, count-runner and dumper tests of that file), end to end by
   `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_clean_data_passes_the_job`.
12. With the connector's `binary.handling.mode` base64, a table with a BIT(n>1) column is equal on clean data.
    Covered: `sink-connector/python/db_compare/tests/test_scheduled_job_findings.py::TestBitColumnRendering::test_bit_n_is_lower_hex_under_every_encoding`,
    end to end by `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_clean_data_passes_the_job`.
13. The scheduled job's `set -euo pipefail` script can source `install.sh` with `PYTHONPATH` unset. Covered:
    `sink-connector/python/db_compare/tests/test_scheduled_job_findings.py::TestInstallShUnderStrictMode::test_sources_with_pythonpath_unset`,
    end to end by `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_job_sources_install_sh_under_set_euo_pipefail`.
8. A packaged-copy test, or the removal of the packaged MySQL runners, pins which copy `ch-mysql-checksum`
   executes. The driver must not depend on the cwd. Covered:
   `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedSidesAndFailures::test_side_module_starts_from_a_foreign_cwd_without_pythonpath`.
9. The ClickHouse count with no `--include_partitions_regex` counts the whole table. Covered:
   `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestClickHouseCount::test_legacy` (and `::test_packaged`).
10. `--exclude_columns a b` and `--exclude_columns a,b` exclude the same columns. Covered:
    `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestExcludeColumnsForms::test_legacy_clickhouse_side_space_separated_recipe`.
11. `--no_wc` and `--debug_output` work in every runner that has them. Covered:
    `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestNoWc::test_legacy_driver` and
    `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestDebugOutput::test_legacy_mysql_side` (and the other tests of both classes).

### 5.3 End-to-end tests (real MySQL, ClickHouse and connector)

`sink-connector/python/tests_e2e/mysql` (CI: `.github/workflows/python-toolset-e2e-mysql.yml`, job
`python-toolset-e2e-mysql`) runs the tools from a copy of the tool tree with `.my.cnf` and
`clickhouse-client.xml`, against a connector with `binary.handling.mode: base64`,
`database.connectionTimeZone: UTC` and a database override map. Its JUSTIFICATION.md maps each test to the fix
it proves (pre-fix tree d42a8740 against this branch).

- The scheduled job, exactly (`set -euo pipefail`, `source ./install.sh`, both commands through `tee`, the
  WARNING scan):
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_job_sources_install_sh_under_set_euo_pipefail` (D-13.06-39)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_clean_data_passes_the_job` (two databases, `database_override_map`, `ignored_columns`, excluded tables; D-13.06-9, D-13.06-38)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_bitemporal_where_selects_the_trading_day_window`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_debug_run_passes_the_job`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_single_database_run_passes_the_job`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_table_include_list_restricts_the_job`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_empty_partition_does_not_fail_the_job`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_empty_partition_is_reported_empty_not_matched` (D-13.06-5)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_side_notes_reach_the_job_log_below_warning` (D-13.06-8)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_planted_difference_fails_the_job`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_planted_difference_in_the_renamed_database_fails_the_job`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_difference_in_an_ignored_column_passes_the_job`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_clickhouse_side_failure_fails_the_job`
- The manual recipes (side scripts with `--no_wc --debug_output`, sorted diff, count runners):
  - `sink-connector/python/tests_e2e/mysql/test_mysql_02_manual_recipes.py::test_manual_checksum_recipe_is_equal_on_clean_data` (D-13.06-17, -26, -27)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_02_manual_recipes.py::test_manual_checksum_recipe_with_the_connector_binary_encoding`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_02_manual_recipes.py::test_manual_checksum_recipe_names_the_diverged_row`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_02_manual_recipes.py::test_mysql_count_agrees_with_real_counts`
  - `sink-connector/python/tests_e2e/mysql/test_mysql_02_manual_recipes.py::test_clickhouse_count_agrees_with_real_counts` (D-13.06-18)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_03_snapshot.py::test_clickhouse_count_agrees_between_the_live_and_the_restored_copy`
- Dedicated runs of the false-match shapes:
  - `sink-connector/python/tests_e2e/mysql/test_mysql_05_justification.py::test_checksum_in_column_names_does_not_confuse_the_verdict` (D-13.06-2)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_05_justification.py::test_dollar_table_is_compared_under_its_own_name` (D-13.06-3)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_05_justification.py::test_equal_mysql_and_clickhouse_host_strings_still_give_verdicts` (D-13.06-4)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_05_justification.py::test_function_partition_expression_reaches_the_sides` (D-13.06-10)
  - `sink-connector/python/tests_e2e/mysql/test_mysql_05_justification.py::test_packaged_driver_run_from_another_directory_reports_a_difference` (D-13.06-1)

What they established about the deployment: the connector stores BIT(n>1) as hex under base64 mode (the origin
of D-13.06-38); a bitemporal `where` that hides `CONVERT_TZ` from ClickHouse in `/*!50000 */` comments selects
the same rows on both sides only because the ClickHouse server zone is the conversion's source zone
(America/Chicago); a table name containing `temp` (for example `positions_bitemporal`) is silently dropped by
the jobs' `--exclude_tables_regex "(temp|...)"`.

### 5.4 Offline reproduction scripts used by this spec

All scripts live in a throwaway repro directory (not part of the repo) and run with the toolset venv Python. No
database or network is contacted. Side scripts are stubbed, or the shell runs a stand-in `python`.

| Script | What it does | Key output |
|---|---|---|
| R01 `r01_expressions.py` | Builds both sides' expressions in both copies for 22 column types (§3.11) | the table in §3.11; packaged `toString("id")\|\|'#'\|\|toString("name")\|\|'#'`; packaged `enum('float','x')` `<skipped>` |
| R02 `r02_failed_sides_verdict.py` | Real driver pipelines with failing or missing side scripts | legacy `[None, None]` → TypeError (exit 1); packaged `('mysql-host','orders',None,None), ('ch-host','orders',None,None)` → `INFO No difference for orders` |
| R03 `r03_driver_edges.py` | Equal hosts; zero rows; shell expansion via an argv-printing stand-in | no verdict line for equal hosts; `No difference for db1.orders` at count 0; `ARG<^orders$>` for `orders$archive`; `syntax error near unexpected token '('`; packaged `toDate(\'2026-09-28\')` with rc 0 |
| R04 `r04_sqlalchemy_rows.py` | A real `IteratorResult` (SQLAlchemy 2.1.1) through the legacy `run_config` | `TypeError tuple indices must be integers or slices, not str`, exit 1; `--no_wc`: `'list' object has no attribute 'fetchall'`, exit 1 |
| R05 `r05_ch_side_parsing_and_count.py` | Multi-token `--exclude_columns`; ClickHouse count defaults | `["_version'", "'is_deleted"]` and both columns kept; `match(partition,'None')` → `Count for table db1.t1 = 0` (both copies) |
| R06 `r06_packaged_driver.py` | Packaged lock order, error path, `fstr`; legacy `ignored_columns` | `LOCK t1, LOCK t2, LOCK t3 ... UNLOCK`; `NameError name 'traceback' is not defined`; `SyntaxError` on a quote; `True` from `{__import__('os').getpid() > 0}`; `IndexError` |
| R07 `r07_checksum_word_in_column.py` | Legacy sides on stubbed engines with a float column `checksum_ratio` and **different** data, through the real grep/awk/parse | both outputs `b'table floating columns\ndb1.t1 <md5> 2\n'`, verdict `No difference for t1` although the md5s differ |
| R08 `r08_packaged_datetime_model.py` | Python model of the packaged datetime SQL | `1950-05-05` and `1961-01-01` both render `1969-12-31 18:00:00` → EQUAL; legacy DIFFERENT |
| R09 `r09_urls_and_creds.py` | URL encoding and ClickHouse credential logging | legacy `'a b'` → `'a+b'`; packaged `p@ss:w/rd` → ValueError; packaged DEBUG `clickhouse_password S3cret!` |
| R10 `r10_config_parsing.py` | include-list pre-filter, override whitespace, `--secure` | `db1\.orders` → ALL tables; override with a space → database `db2`; `'False'` is truthy |
| R11 `r11_warnings_dropped_by_driver.py` | Same as R07 with a float column `ratio` and equal data | each side logs `Not compared in table db1.t1: floating point columns ['ratio']`; after grep/awk only `db1.t1 <md5> 2` remains |
| R12 `r12_abort_waits_for_all.py` | Failure-modes harness: the first table fails | exit 1, `tables computed after the failure: ['b', 'c', 'd']`, no verdict for them |

## 6. Failure Modes & Recovery

The runners only read, apart from the optional source lock, so the recovery posture is always "fix the cause,
re-run the affected tables". The entries that matter are those where the runner **lies**: a match that is not
one, or a green exit over an unverified table. They come first. The FM-11.02 entries are not repeated. They are
cross-referenced where this spec adds a trigger or corrects them.

- **FM-13.06-1 Packaged driver: failed sides reported as "No difference"**
  - **Trigger**: `ch-mysql-checksum` is run from a cwd without `db_compare/` (any wheel install), or without the
    python root on `PYTHONPATH`, or both side scripts fail for any reason (credentials file missing, connection
    refused, SQL error).
  - **Behaviour**: `set -e pipefail` leaves pipefail off (PT159, PT186). The pipeline exits with awk's 0.
    `run_quick_safe_checksum` parses the traceback text to `(None, None)` for each side, and
    `analyze_differences` finds `(None, None) == (None, None)` → `INFO No difference` (PT190-202). One failed
    side gives `Checksum difference` instead.
  - **Detection**: ERROR `Invalid checksum output from b'...'` per side, next to INFO `No difference`. Exit 0.
  - **Blast radius**: every table of the run is certified equal without being compared.
  - **Recovery**: treat any `Invalid checksum output` as a failed run. Re-run with the legacy driver from
    `sink-connector/python` with `PYTHONPATH=.`.
  - **RTO**: unmeasured (no database available). One full legacy run.
  - **Test**: `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedSidesAndFailures::test_both_sides_failing_exits_non_zero_and_never_reports_equal`
    (also `...::test_side_module_starts_from_a_foreign_cwd_without_pythonpath`).
  - **FIXED**: D-13.06-1. The packaged sides run as `<sys.executable> -m ch_sink_tools.db_compare.<side>` without a shell, and a failed or unparseable side is verdict `ERROR` with exit 1.

- **FM-13.06-2 A name containing "checksum" makes both outputs unparseable**
  - **Trigger**: a table, database or **column** whose name contains `checksum` (any case) appears in a side
    script's own log lines. Column cases: `Not compared ... floating point columns ['checksum_ratio']`,
    `Excluded columns, [...]` / `Excluding column ...` for an ignored column, `Row filter ... engine
    <engine_full>` when it names such a column, `FINAL per partition: ... ['checksum_date']`.
  - **Behaviour**: `grep -i checksum` keeps two lines per side. Each parses to `(None, None)`, and
    `analyze_differences` logs `No difference` (LT343-355). This extends FM-11.02-2, which names only database
    and table names.
  - **Detection**: ERROR `Invalid checksum output` per side. Exit 0.
  - **Blast radius**: that table is certified equal even when its data differs (reproduced with differing
    data).
  - **Recovery**: run the two side scripts standalone and compare their `Checksum for table` lines by hand.
  - **RTO**: one standalone pair of runs for the table, proportional to its size (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestChecksumWordInNames::test_differing_data_with_checksum_named_column_reports_a_difference`.
    The verdict half: `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestVerdictOnUnparseableOutput::test_both_sides_unparseable_is_never_reported_equal` (no longer skipped).
  - **FIXED**: D-13.06-2. The side output is parsed per line for exactly one `Checksum for table <expected db.table>` message; anything else is `ERROR`.

- **FM-13.06-3 The shell rewrites a table name or a where clause**
  - **Trigger**: a table name containing `$`, or a `--where` or YAML `where` containing `$`, backticks, `"` or
    `\`.
  - **Behaviour**: these tokens sit inside a double-quoted word of a `sh -c` string (LT265, LT321, PT159,
    PT186). `orders$archive` becomes `^orders$`, so both sides checksum `orders`. `` `col` `` runs a command.
    `'$x'` becomes `''`. A changed filter applies identically to both sides, so it compares a silent subset.
  - **Detection**: none for `$` in a table name: the verdict line names `db.orders`, not the enumerated table.
    `command not found` appears only in the side output, which grep drops.
  - **Blast radius**: a table is reported verified when another table, or another subset, was compared.
    Backticks execute arbitrary commands on the driver host.
  - **Recovery**: avoid `$` and backticks in names and filters. Checksum such tables standalone with argv
    quoting (`--tables_regex '^orders\$archive$'`).
  - **RTO**: one standalone run per affected table (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestArgumentsReachTheSideUnchanged::test_mysql_side_receives_table_and_where_byte_for_byte`.
  - **FIXED**: D-13.06-3. Side commands are argv lists run without a shell, and the table regex matches the name literally (`^orders[$]archive$`).

- **FM-13.06-4 MySQL and ClickHouse share a host string**
  - **Trigger**: `source.mysql.host` equals a `replicas[].clickhouse.host` string, for example both
    `localhost`.
  - **Behaviour**: `source_results` has two entries, so `analyze_differences` logs nothing (LT346).
  - **Detection**: absence of any verdict line for the table. Exit 0.
  - **Blast radius**: every table of the run is unverified with a green exit.
  - **Recovery**: use distinct host spellings (an IP address for one, a name for the other), then re-run.
  - **RTO**: one re-run (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestSameHostString::test_run_with_equal_host_strings_logs_a_verdict`.
  - **FIXED**: D-13.06-4. The source is identified by its position in the results, and a table without a verdict is `ERROR` with exit 1.

- **FM-13.06-5 Zero rows on both sides is reported as equality**
  - **Trigger**: a `--where`, a YAML `where` or `--partition_date` that matches no row on either side. Examples:
    a typo in a value; `--partition_date` on a `KEY`, `HASH` or `LIST` integer partition (`id=20260928` against
    `id=toDate(...)`); a day with no data.
  - **Behaviour**: both sides print `md5('0#0#0#0#0#') count 0` and the verdict is `No difference` (LM342-351,
    LC327-344, LT343-355). Nothing flags a zero count.
  - **Detection**: only the `count 0` in the `(host, table, md5, count)` INFO line.
  - **Blast radius**: the intended data is not compared, and the table is reported equal.
  - **Recovery**: check the counts in the per-host INFO lines. Re-run with a corrected filter, or without
    `--partition_date` for non-date-partitioned tables.
  - **RTO**: one re-run (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestEmptyOnBothSides::test_empty_is_logged_at_info_and_exit_zero_by_default`
    (and `...::test_fail_on_empty_makes_it_non_zero`).
  - **FIXED**: D-13.06-5. Verdict `EMPTY on both sides`, logged per table and in the run summary at INFO (WARNING is reserved for `Checksum difference`, §3.17); exit 0 by default, exit 1 with `--fail_on_empty`.

- **FM-13.06-6 Packaged driver hides every pre-1969 datetime difference**
  - **Trigger**: any packaged-driver run on a table with `DATETIME` values before `1969-12-31 18:00:00`.
  - **Behaviour**: both sides get `--min_datetime_value "1969-12-31 18:00:00"` (PT159, PT186). Packaged sides
    render every earlier value as `1969-12-31 18:00:00` (PM104-107, PC166-168). Legacy sides clamp them to the
    same canonical bound. Distinct earlier values compare EQUAL. Values on the last day of 2299 collapse the
    same way.
  - **Detection**: none from the packaged sides. The legacy sides print a clamp WARNING, which the driver
    drops (FM-13.06-8).
  - **Blast radius**: any divergence among 1900-1969 datetimes is certified equal.
  - **Recovery**: re-run with the legacy driver, which passes no bounds.
  - **RTO**: one legacy run (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedDatetimeBounds::test_driver_passes_identical_full_range_bounds_to_both_sides`.
  - **FIXED**: D-13.06-6. Both sides get the DateTime64 range `1900-01-01 00:00:00` .. `2299-12-31 23:59:59`, and the packaged sides' defaults agree with it.

- **FM-13.06-7 Packaged MySQL side always normalises JSON on one side**
  - **Trigger**: a table with a `json` column, checksummed with the packaged MySQL side (`python -m`).
  - **Behaviour**: `--include_json_columns` is `store_true` with default True (PM379-380), so JSON is always
    compared. MySQL applies eleven regexes to `json_pretty()` (PM97-101), while ClickHouse compares the stored
    text. `{"a": 1.0}` against `{"a":1}` is EQUAL. Layouts the regexes do not expect are DIFFERENT.
  - **Detection**: none.
  - **Blast radius**: JSON numeric and whitespace divergences are masked.
  - **Recovery**: use the legacy sides (JSON excluded by default, with a WARNING).
  - **RTO**: one legacy run (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedJsonCoverage::test_driver_derives_json_columns_for_the_clickhouse_side`
    (and `...::test_mysql_side_excludes_json_with_a_warning`, `...::test_clickhouse_side_excludes_named_json_string_columns_with_a_warning`).
  - **FIXED**: D-13.06-7. JSON is excluded on both packaged sides by default with a WARNING, as in legacy; the packaged driver passes the MySQL JSON columns as `--json_columns`.

- **FM-13.06-8 Coverage WARNINGs never reach the driver log**
  - **Trigger**: any driver run (either copy) on a table with float or JSON columns, or with clamped datetimes.
  - **Behaviour**: the side scripts log `Not compared in table ...` and `<n> out-of-range datetime values
    clamped ...` to stdout. The driver pipeline keeps only lines containing `checksum` (LT265, LT321). The
    driver logs captured stdout only at DEBUG, and it is already filtered.
  - **Detection**: none in the driver log. The warnings are visible only when the side scripts run standalone.
  - **Blast radius**: operators cannot tell that an EQUAL verdict excludes columns or rests on clamped values.
    This contradicts 11.02 §3.9 ("a column the tool does not compare ... is never silent").
  - **Recovery**: run the side scripts standalone for tables with float, JSON or out-of-range datetime columns.
  - **RTO**: one standalone pair per table (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestSideWarningsReachTheDriverLog::test_side_warnings_are_logged_by_the_driver`.
  - **FIXED**: D-13.06-8. Both drivers relay every side ERROR/CRITICAL line at ERROR (that side gives no result) and every side WARNING line at INFO as a side note (§3.17).

- **FM-13.06-9 The tools crash on SQLAlchemy 2.x**
  - **Trigger**: a fresh install from `requirements.txt` or `pyproject.toml` (`sqlalchemy>=1.4` resolves to
    2.x).
  - **Behaviour**: `tables.fetchall()` followed by `row['table_name']` (LT500-501), `partitions.fetchall()`
    followed by `row['partition_name']` (LMC67-70, PMC67-70, `ch_sink_tools/db/mysql.py:77-80`), and packaged
    side rows (PM70-78, PM417-419) raise `TypeError: tuple indices must be integers or slices, not str`.
  - **Detection**: loud. `Exception in main thread` and exit 1 at the first table.
  - **Blast radius**: no verification at all. Legacy side scripts run standalone are unaffected (they use
    `.mappings()`).
  - **Recovery**: pin `sqlalchemy<2` in the tool's environment.
  - **RTO**: one reinstall plus a re-run (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_sqlalchemy_rows.py::TestDriversReadRealRows::test_legacy_driver_compares_a_table_listed_by_sqlalchemy`;
    end to end `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_clean_data_passes_the_job`
    (the scheduled job on a fresh `install.sh`, SQLAlchemy 2.x).
  - **FIXED**: D-13.06-9 (with D-13.03-3). Every runner reads catalog rows through `mappings()`, which SQLAlchemy 1.4 and 2.x both provide.

- **FM-13.06-10 A function-partitioned MySQL table aborts the run**
  - **Trigger**: a table partitioned by an expression with parentheses (`TO_DAYS(dt)`, `YEAR(dt)`) or with
    spaces. `--partition_date` is not needed.
  - **Behaviour**: `--partition_key <expr>` is pasted unquoted into the shell (LT295-297, PT183-185), which
    gives a syntax error or argparse exit 2. The side returns `None`, and the run exits 1 after waiting for the
    other tables.
  - **Detection**: loud. ERROR `<cmd>. failed`, then `Exception in main thread : 'NoneType' object is not
    subscriptable`.
  - **Blast radius**: no verdict for the run. Every remaining table is computed and discarded (FM-13.06-12).
  - **Recovery**: exclude such tables with `--exclude_tables_regex` and checksum them standalone.
  - **RTO**: one re-run without the table plus one standalone pair (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestArgumentsReachTheSideUnchanged::test_clickhouse_side_receives_table_where_and_function_partition_key`.
  - **FIXED**: D-13.06-10. The partition expression is one argv word (no shell), so the side starts.

- **FM-13.06-11 `--partition_date` with an unpartitioned table fails that table**
  - **Trigger**: `--partition_date` and any selected table that is not partitioned in MySQL.
  - **Behaviour**: the MySQL side leaves `{partition_expression}` in the SQL (LM314-317). MySQL rejects it, the
    side exits 1. Since the verdict fix that table is `ERROR`, the other tables still get verdicts, and the run
    exits 1.
  - **Detection**: loud (`<cmd>. failed with return code 1`, `Checksum ERROR for <db.t>`).
  - **Blast radius**: every unpartitioned table of the run has no verdict.
  - **Recovery**: restrict the run to partitioned tables (`--include_partitions_regex .`).
  - **RTO**: one re-run (unmeasured).
  - **Test**: GAP: a driver test that excludes or explicitly refuses unpartitioned tables under
    `--partition_date`.
  - **DEFECT**: D-13.06-11.

- **FM-13.06-12 One failing table keeps every other table running, then discards their verdicts**
  - **Trigger**: any non-lock exception in a table (a failed side no longer raises: it is verdict `ERROR`, §3.8).
  - **Behaviour**: `raise` inside `with ThreadPoolExecutor` (LT549, LT551). `__exit__` waits for every
    submitted future: locks are taken, both engines are loaded, results are thrown away. Then the run exits 1.
  - **Detection**: loud, but the log shows no verdict for tables that were in fact computed. FM-11.02-3 says
    they were "skipped".
  - **Blast radius**: hours of lock time and engine load wasted. Coverage is lost for the run.
  - **Recovery**: fix the failing table or exclude it, then re-run.
  - **RTO**: the full run time again (unmeasured).
  - **Test**: GAP: a driver test asserting that pending tables are cancelled (or reported) when one fails. The
    current behaviour was reproduced (R12).
  - **DEFECT**: D-13.06-12.

- **FM-13.06-13 Packaged driver locks every table for the whole run**
  - **Trigger**: `ch-mysql-checksum --lock_tables_on_source`.
  - **Behaviour**: ``FLUSH TABLE `t` WITH READ LOCK`` is taken serially for all tables in the main thread before
    any unlock (PT319-326), with no `lock_wait_timeout`. Each table is unlocked only after the submit loop ends.
    A hot table waits for the server default lock timeout.
  - **Detection**: on the source, `SHOW PROCESSLIST` shows writers waiting on table locks. The tool says
    nothing.
  - **Blast radius**: writes to every selected table on that server are blocked for the whole run. On a replica
    with a single-threaded applier, all replication stops.
  - **Recovery**: kill the driver. MySQL releases table locks when the sessions end (server behaviour, not
    verified here). Use the legacy driver.
  - **RTO**: immediate on kill (unmeasured).
  - **Test**: GAP: a packaged lock-order test, the mirror of
    `sink-connector/python/db_compare/tests/test_table_locking.py::TestLockHoldDuration::test_lock_held_during_both_checksums`.
  - **DEFECT**: D-13.06-13.

- **FM-13.06-14 Catch-up is a fixed sleep**
  - **Trigger**: the connector is more than `--sleep_after_lock` seconds behind, or reads a different server
    than `source.mysql.host`.
  - **Behaviour**: the lock freezes only that table's future writes on that server. No position or offset is
    compared before the ClickHouse read (LT165-171). This extends FM-11.02-6.
  - **Detection**: `Checksum difference`, indistinguishable from a real divergence.
  - **Blast radius**: noise only. Never a false match unless the pending events did not change the table.
  - **Recovery**: re-run the table with a `--sleep_after_lock` above the connector's current lag. Confirm with
    the connector's lag view (spec 10.03) that it has applied past the lock time.
  - **RTO**: one table re-run (unmeasured).
  - **Test**: GAP: a driver test with a position-based wait (record the binlog position under the lock and wait
    until the connector offset passes it).
  - **DEFECT**: D-13.06-36. Catch-up is not established.

- **FM-13.06-15 The run targets the wrong instance or database**
  - **Trigger**: a non-3306 MySQL `--mysql_port`; any driver `--clickhouse_*` flag; a `database_override_map`
    with spaces or without an exact match.
  - **Behaviour**: the MySQL side gets no `--mysql_port`, so it uses 3306 (LT265). ClickHouse connection flags
    are never forwarded (LT321). The override silently falls back to the MySQL database name (LT148-155).
  - **Detection**: usually loud (connection refused, missing table, so the run aborts). Silent when another
    instance on 3306, or a same-named ClickHouse database, holds similar data. The log line "Overriding database
    ... to db2" names the fallback.
  - **Blast radius**: a comparison against the wrong source or the wrong replica database.
  - **Recovery**: run the MySQL instance on 3306, or run the side scripts standalone with explicit ports. Write
    the override map without spaces.
  - **RTO**: one re-run (unmeasured).
  - **Test**: GAP: a driver test asserting that the port and ClickHouse connection flags are forwarded, and that
    override parsing tolerates spaces.
  - **DEFECT**: D-13.06-15 and D-13.06-16.

- **FM-13.06-16 Columns are not excluded on the ClickHouse side**
  - **Trigger**: `clickhouse_table_checksum.py --exclude_columns a b`, with space-separated words as the help
    text and the FM-11.02-9 recovery recipe show.
  - **Behaviour**: `"','".join(tokens).split(',')` gives `["a'", "'b"]` (LC241-242, PC101-102). Nothing is
    excluded.
  - **Detection**: `Excluded columns, ["a'", "'b"]` at INFO in the side output, then `Checksum difference`.
  - **Blast radius**: noise. The FM-11.02-9 history-table recovery cannot work as written.
  - **Recovery**: pass one comma-separated token (`--exclude_columns a,b`).
  - **RTO**: one re-run.
  - **Test**: `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestExcludeColumnsForms::test_legacy_clickhouse_side_space_separated_recipe`
    (and the packaged and MySQL-side tests of that class).
  - **FIXED**: D-13.06-17. Both sides parse `--exclude_columns` words and comma lists alike (`parse_exclude_columns` in the legacy copy).

- **FM-13.06-17 Count runners print wrong counts**
  - **Trigger**: `clickhouse_table_count.py` without `--include_partitions_regex`; `mysql_table_count.py` on a
    sub-partitioned table.
  - **Behaviour**: `match(partition,'None')` selects no partition, so the count is 0 (LCC60, PCC64). MySQL
    counts each partition once per subpartition row (LMC67-77).
  - **Detection**: the operator sees a count mismatch, or zeros everywhere.
  - **Blast radius**: no data risk. The count check is meaningless.
  - **Recovery**: pass `--include_partitions_regex .` to the ClickHouse count. For sub-partitioned MySQL tables,
    use `select count(*)` directly.
  - **RTO**: one re-run (seconds to minutes, unmeasured).
  - **Test**: GAP: a sub-partition fixture for the MySQL count. The ClickHouse half (D-13.06-18, fixed) is
    pinned by `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestClickHouseCount::test_legacy`.
  - **DEFECT**: D-13.06-19. (D-13.06-18 is fixed: without `--include_partitions_regex` the ClickHouse count counts the whole table.)

- **FM-13.06-18 Packaged renderings report DIFFERENT for equal tables**
  - **Trigger**: packaged sides, or the packaged driver's flags, on tables with `bit(1)`, `time`, `timestamp`
    (non-UTC server), enum or set labels containing `float`, `double`, `real` or `json`, a skipped last column,
    or `Nullable(Bool)`.
  - **Behaviour**: see §3.11, packaged columns.
  - **Detection**: `Checksum difference` on equal data.
  - **Blast radius**: noise that trains operators to ignore real differences.
  - **Recovery**: use the legacy runners.
  - **RTO**: one legacy run (unmeasured).
  - **Test**: GAP: run `test_checksum_fidelity.py` against the packaged modules (it fails today).
  - **DEFECT**: D-13.06-20.

- **FM-13.06-19 `DATETIME` filters select different rows on the two sides**
  - **Trigger**: a `--where` or YAML `where` comparing a `DATETIME` column with a literal, in a deployment whose
    source zone is not UTC.
  - **Behaviour**: MySQL compares wall clocks. ClickHouse parses the literal in the column zone (UTC).
  - **Detection**: `Checksum difference` and differing counts.
  - **Blast radius**: noise. Boundary rows are counted on one side only.
  - **Recovery**: filter on `TIMESTAMP` columns or on the primary key, or write the ClickHouse side with
    `toDateTime('...', '<source zone>')` through a standalone run.
  - **RTO**: one re-run (unmeasured).
  - **Test**: GAP: a fidelity test with a non-UTC source zone and a datetime filter.
  - **DEFECT**: D-13.06-21.

- **FM-13.06-20 Packaged ClickHouse side leaks its password or cannot start**
  - **Trigger**: `ch-ch-checksum --debug` with a config file; any run without the `CREATE FUNCTION` privilege.
  - **Behaviour**: `resolve_credentials_from_config` logs `clickhouse_password <clear text>` at DEBUG
    (`ch_sink_tools/db/clickhouse.py:68`). `main` executes `CREATE FUNCTION if not exists format_decimal` before
    any table (PC400).
  - **Detection**: the password appears in the log, which nothing detects. The privilege error is loud (exit
    1).
  - **Blast radius**: credential exposure. Read-only accounts cannot use `ch-ch-checksum`.
  - **Recovery**: rotate the password and scrub logs. Grant `CREATE FUNCTION` or use the legacy side.
  - **RTO**: one credential rotation (unmeasured).
  - **Test**: GAP: a packaged redaction test. The DDL half (D-13.06-25, fixed) is pinned by
    `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedClickHouseSideIsReadOnly::test_main_runs_no_ddl`.
  - **DEFECT**: D-13.06-24. (D-13.06-25 is fixed: no `CREATE FUNCTION`, no count pre-check.)

- **FM-13.06-21 Malformed or unusual input crashes a runner**
  - **Trigger**: `--no_wc` (driver, MySQL side, MySQL count); `--debug_output` through the driver; a two-part
    `ignored_columns` entry; a table name that is a reserved word or contains `-`; `--secure False`; a packaged
    `where` containing `'`; Python < 3.10 for the packaged runners.
  - **Behaviour**: each one gives an AttributeError, missing checksum lines, an IndexError, a SQL syntax error,
    an unexpected TLS connection, a SyntaxError or a TypeError (§3.3, §3.9, §3.10, §3.13).
  - **Detection**: loud. Exit 1, or exit 2 from argparse.
  - **Blast radius**: no verdict. No false result.
  - **Recovery**: avoid the flag, fix the entry, or checksum the table standalone.
  - **RTO**: one re-run (unmeasured).
  - **Test**: GAP: one parser or driver test per remaining trigger. The `--no_wc` and `--debug_output` triggers
    (D-13.06-26, -27, fixed) are pinned by `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestNoWc::test_legacy_driver` and
    `sink-connector/python/db_compare/tests/test_manual_runner_recipes.py::TestDebugOutput::test_legacy_mysql_side`.
  - **DEFECT**: D-13.06-28, -31, -32, -22, -23. (D-13.06-26 and -27 are fixed.)

- **FM-13.06-22 BIT(n>1) columns are DIFFERENT on clean data under base64**
  - **Trigger**: `--binary_encoding base64` (the connector runs `binary.handling.mode: base64`) on a table with a
    `bit(n)` column, n > 1.
  - **Behaviour**: the connector stores BIT(n>1) as lower-case hex text under every binary mode; the MySQL side
    rendered it with `to_base64`, the ClickHouse side the stored hex (LM `mysql_column_expression`).
  - **Detection**: `Checksum difference` on equal data, so the scheduled job fails every day.
  - **Blast radius**: noise on every table with such a column; a real divergence there is hidden in the noise.
  - **Recovery**: `ignored_columns` for the BIT columns, or the manual recipe with `--binary_encoding hex`.
  - **RTO**: one re-run (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_scheduled_job_findings.py::TestBitColumnRendering::test_bit_n_is_lower_hex_under_every_encoding`;
    end to end `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_clean_data_passes_the_job`.
  - **FIXED**: D-13.06-38. BIT(n>1) is rendered `lower(hex(cast(c as binary)))` under every encoding.

- **FM-13.06-23 The scheduled job dies sourcing install.sh**
  - **Trigger**: the job script's `set -euo pipefail`, then `source ./install.sh`, with `PYTHONPATH` unset.
  - **Behaviour**: `export PYTHONPATH="${PYTHONPATH}":.` is an unbound variable under `set -u`; the script exits 1
    before any checksum.
  - **Detection**: loud (`install.sh: line 4: PYTHONPATH: unbound variable`, exit 1).
  - **Blast radius**: no verification that day.
  - **Recovery**: export `PYTHONPATH=` before sourcing.
  - **RTO**: one re-run.
  - **Test**: `sink-connector/python/db_compare/tests/test_scheduled_job_findings.py::TestInstallShUnderStrictMode::test_sources_with_pythonpath_unset`;
    end to end `sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py::test_job_sources_install_sh_under_set_euo_pipefail`.
  - **FIXED**: D-13.06-39. `install.sh` reads `${PYTHONPATH:-}`.

- **FM-13.06-24 A nullability mismatch makes an equal table DIFFERENT**
  - **Trigger**: a compared column declared nullable on one side and not on the other while its values are
    equal, e.g. a MySQL stored generated column (nullable, never NULL) replicated as a non-Nullable integer.
  - **Behaviour**: each side built the trailing null-flags element over the columns its own catalog declares
    nullable, so the flags strings had different lengths (`0000000010` against `000000001`) and every row hashed
    differently (LM `build_mysql_row_expression`, LC `build_clickhouse_row_expression`, and the PM/PC
    `get_table_checksum_query`).
  - **Detection**: `Checksum difference` on equal data; the per-row files of `--debug_output` differ only in the
    last element.
  - **Blast radius**: hides nothing, but every scheduled run fails on every such table.
  - **Recovery**: `ignored_columns` for the mismatched column.
  - **RTO**: one re-run (unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestNullFlagsOverEveryComparedColumn::test_nullability_mismatch_gives_the_same_flags_on_both_sides`;
    packaged `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedNullFlagsOverEveryComparedColumn::test_nullability_mismatch_gives_the_same_flags_on_both_sides`.
  - **FIXED**: D-13.06-40. One value-based flag per compared column on both sides, in both copies.

Summary: 24 failure modes, 10 DEFECT, 10 GAP.

## 7. Defect Register

Locations are relative to `sink-connector/python/` in the 2.11.0 tree. LMC/PMC are the MySQL count runners,
LCC/PCC the ClickHouse count runners.

| ID | Severity | Copy | Location | Evidence | Summary |
|---|---|---|---|---|---|
| D-13.06-1 | S1 | packaged | `ch_sink_tools/db_compare/top_level_table_checksum.py:159,186,190-202` | reproduced (R02: both sides `(None, None)` → `INFO No difference for orders`; also with no `db_compare/` in the cwd) | FIXED: sides run as `<sys.executable> -m ch_sink_tools.db_compare.<side>` (argv, no shell, package root on `PYTHONPATH`); a failed or unparseable side is `ERROR`, exit 1. Test: `test_packaged_checksum_verdicts.py::TestPackagedSidesAndFailures::test_both_sides_failing_exits_non_zero_and_never_reports_equal`. Was: `set -e pipefail` left pipefail off, failed sides parsed to `None`, `None == None` reported equality, exit 0, and the entry point ran `python db_compare/...` relative to the cwd. |
| D-13.06-2 | S1 | both | `db_compare/top_level_table_checksum.py:265,321,99-113,343-355`; side log lines `db/checksum_common.py:142`, `db_compare/clickhouse_table_checksum.py:243,260,268,395` | reproduced (R07: different data, verdict `No difference for t1`) | FIXED: per-line parse of exactly one `Checksum for table <expected db.table>` message; anything else is `ERROR`. Test: `test_checksum_verdicts.py::TestChecksumWordInNames::test_differing_data_with_checksum_named_column_reports_a_difference`. Was: a column name containing `checksum` (not only a database or table name, as in FM-11.02-2) made both outputs unparseable and the verdict "No difference". |
| D-13.06-3 | S1 | both | `db_compare/top_level_table_checksum.py:240-245,265,273-284,321`; `ch_sink_tools/db_compare/top_level_table_checksum.py:140-145,159,166-172,186` | reproduced (R03: `orders$archive` → `ARG<^orders$>`; backticks executed, `$HOME` expanded) | FIXED: argv lists without a shell; literal table regex (`^orders[$]archive$`). Test: `test_checksum_verdicts.py::TestArgumentsReachTheSideUnchanged::test_mysql_side_receives_table_and_where_byte_for_byte`. Was: the table name and where clause went into a double-quoted `sh -c` word; `$` expansion compared a different table or subset under a green verdict, and backticks ran commands. |
| D-13.06-4 | S1 | both | `db_compare/top_level_table_checksum.py:344-355`; `ch_sink_tools/db_compare/top_level_table_checksum.py:191-202` | reproduced (R03: nothing logged for differing checksums) | FIXED: the source is identified by position; a table without a verdict is `ERROR`, exit 1. Test: `test_checksum_verdicts.py::TestSameHostString::test_run_with_equal_host_strings_logs_a_verdict`. Was: with the MySQL host string equal to a replica host there were two "source" results, no verdict, exit 0. |
| D-13.06-5 | S1 | both | `db_compare/mysql_table_checksum.py:342-351`; `db_compare/clickhouse_table_checksum.py:327-344`; `db_compare/top_level_table_checksum.py:343-355` | reproduced (R03: count 0 on both sides → `No difference`) plus code-read (`--partition_date` on KEY/HASH partitions) | FIXED: verdict `EMPTY on both sides`, logged per table and in the summary at INFO (WARNING is reserved for `Checksum difference`, §3.17); exit 0 by default, exit 1 with `--fail_on_empty`. Test: `test_checksum_verdicts.py::TestEmptyOnBothSides::test_empty_is_logged_at_info_and_exit_zero_by_default`. Was: zero rows on both sides was reported as "No difference" with no warning. |
| D-13.06-6 | S1 | packaged | `ch_sink_tools/db_compare/top_level_table_checksum.py:159,186`; `ch_sink_tools/db_compare/mysql_table_checksum.py:102-107`; `ch_sink_tools/db_compare/clickhouse_table_checksum.py:165-168` | reproduced (R01 expressions plus R08 evaluation model: `1950-05-05` and `1961-01-01` both render `1969-12-31 18:00:00`) | FIXED: both sides get the DateTime64 range `1900-01-01 00:00:00` .. `2299-12-31 23:59:59`; the packaged side defaults agree. Test: `test_packaged_checksum_verdicts.py::TestPackagedDatetimeBounds::test_driver_passes_identical_full_range_bounds_to_both_sides`. Was: the clamp was narrowed to `1969-12-31 18:00:00` .. `2299-12-31 00:00:00`, so divergent values outside it compared EQUAL. |
| D-13.06-7 | S1 | packaged | `ch_sink_tools/db_compare/mysql_table_checksum.py:86-101,379-380`; `ch_sink_tools/db_compare/clickhouse_table_checksum.py:139-142,366-367` | code-read (R01 shows the expressions; masking per 11.02 §3.9) | FIXED: JSON excluded on both packaged sides by default with a WARNING (as in legacy); the driver passes the MySQL JSON columns as `--json_columns`. Test: `test_packaged_checksum_verdicts.py::TestPackagedJsonCoverage::test_driver_derives_json_columns_for_the_clickhouse_side`. Was: `--include_json_columns` defaulted to True and JSON was always compared with one-sided regex normalisation, masking `1.0` against `1` and whitespace differences. |
| D-13.06-8 | S2 | both | `db_compare/top_level_table_checksum.py:265,321,571-584`; `ch_sink_tools/db_compare/top_level_table_checksum.py:159,186` | reproduced (R11: side WARNING present, absent after grep/awk) | FIXED: both drivers relay every side ERROR/CRITICAL line at ERROR (that side gives no result) and every side WARNING line at INFO as a side note (§3.17). Test: `test_checksum_verdicts.py::TestSideWarningsReachTheDriverLog::test_side_warnings_are_logged_by_the_driver`. Was: the side scripts' coverage and clamp WARNINGs were dropped by the grep pipeline, so EQUAL verdicts hid skipped columns and clamped values. |
| D-13.06-9 | S3 | both | `db_compare/top_level_table_checksum.py:500-501`; `db_compare/mysql_table_count.py:67-70,215`; `ch_sink_tools/db_compare/mysql_table_count.py:67-70,213`; `ch_sink_tools/db_compare/top_level_table_checksum.py:313-314`; `ch_sink_tools/db_compare/mysql_table_checksum.py:70-78,417-419`; `ch_sink_tools/db/mysql.py:77-80` | reproduced (R04, SQLAlchemy 2.1.1: `TypeError ... not str`, exit 1; end to end: the scheduled job on a fresh `install.sh` (SQLAlchemy 2.0) exited 1 at the first table) | FIXED: every runner reads catalog rows through `mappings()` (both copies; the dumpers too, D-13.03-3). Test: `test_sqlalchemy_rows.py::TestDriversReadRealRows::test_legacy_driver_compares_a_table_listed_by_sqlalchemy`; e2e `tests_e2e/mysql/test_mysql_01_production_job.py::test_clean_data_passes_the_job`. Was: rows from `fetchall()` were indexed by name, so the driver, both count runners and the packaged MySQL paths failed on SQLAlchemy 2.x, which `>=1.4` allows. |
| D-13.06-10 | S3 | both | `db_compare/top_level_table_checksum.py:295-297`; `ch_sink_tools/db_compare/top_level_table_checksum.py:183-185` | reproduced (R03: `syntax error near unexpected token '('`; a space splits argv) | FIXED (by the argv change of D-13.06-3): the partition expression is one argv word. Test: `test_checksum_verdicts.py::TestArgumentsReachTheSideUnchanged::test_clickhouse_side_receives_table_where_and_function_partition_key`. Was: pasted unquoted as `--partition_key` for every partitioned table, so function or space partitions aborted the run. |
| D-13.06-11 | S3 | both | `db_compare/mysql_table_checksum.py:311-317`; `ch_sink_tools/db_compare/mysql_table_checksum.py:273-279` | code-read (the placeholder is kept when `get_table_partition_key` returns None) | `--partition_date` with an unpartitioned table sends `{partition_expression}=YYYYMMDD` to MySQL, and the run aborts. |
| D-13.06-12 | S3 | legacy | `db_compare/top_level_table_checksum.py:496-551` | reproduced (R12: tables b, c, d computed after the failure, no verdicts, exit 1) | A raise inside the executor block waits for every submitted table to finish (locks, load), then discards their results. FM-11.02-3 wrongly says they are skipped. |
| D-13.06-13 | S3 | packaged | `ch_sink_tools/db_compare/top_level_table_checksum.py:205-208,319-353` | reproduced (R06: all locks taken before the first unlock) | `FLUSH TABLE ... WITH READ LOCK` on every table serially, held until each result is read after all are submitted. No timeout, no escaping, connections never closed. |
| D-13.06-14 | S3 | packaged | `ch_sink_tools/db_compare/top_level_table_checksum.py:6-10,357,360` | reproduced (R06: `NameError name 'traceback' is not defined`) | FIXED: `os` and `traceback` are imported. Test: `test_packaged_checksum_verdicts.py::TestPackagedSidesAndFailures::test_unexpected_exception_is_logged_and_exits_one`. Was: not imported, so every error and interrupt path raised NameError instead of logging. |
| D-13.06-15 | S2 | both | `db_compare/top_level_table_checksum.py:265,321,614-635`; `ch_sink_tools/db_compare/top_level_table_checksum.py:159,186,409-430` | code-read (templates omit `--mysql_port` and every `--clickhouse_*` flag) | The driver's `--mysql_port`, `--mysql_user`, `--clickhouse_user/config_file/database/port`, `--secure` and `--chunk_size` are not forwarded. The sides use defaults and may compare a different instance. |
| D-13.06-16 | S2 | both | `db_compare/top_level_table_checksum.py:148-156`; `ch_sink_tools/db_compare/top_level_table_checksum.py:108-116` | reproduced (R10: `db1:ch_db1, db2:ch_db2` → database `db2`) | `database_override_map` uses a substring test and exact unstripped pairs. A space or partial match silently falls back to the MySQL name. A pair without `:` aborts the run. |
| D-13.06-17 | S2 | both | `db_compare/clickhouse_table_checksum.py:241-242`; `ch_sink_tools/db_compare/clickhouse_table_checksum.py:101-102` | reproduced (R05: `["_version'", "'is_deleted"]`, columns kept) | FIXED: both sides parse space-separated words and comma lists alike (`parse_exclude_columns`; inlined in the packaged sides). Test: `test_manual_runner_recipes.py::TestExcludeColumnsForms::test_legacy_clickhouse_side_space_separated_recipe`. Was: space-separated `--exclude_columns` (as the help text and the FM-11.02-9 recipe use) excluded nothing on the ClickHouse side, and `a, b` kept ` b` on the MySQL side. |
| D-13.06-18 | S2 | both | `db_compare/clickhouse_table_count.py:60`; `ch_sink_tools/db_compare/clickhouse_table_count.py:64` | reproduced (R05: `match(partition,'None')` → `= 0`, both copies) | FIXED: without `--include_partitions_regex` the table is counted whole; `--no_wc` counts the named table (it raised IndexError). Test: `test_manual_runner_recipes.py::TestClickHouseCount::test_legacy`. Was: the ClickHouse count without `--include_partitions_regex` printed 0 for every table. No `--dr_host` (prod against DR) option exists in either copy. |
| D-13.06-19 | S2 | both | `db_compare/mysql_table_count.py:59-78`; `ch_sink_tools/db_compare/mysql_table_count.py:59-78` | code-read (`information_schema.partitions` has one row per subpartition; each issues `count(*) ... partition(p)`) | The MySQL count multiplies sub-partitioned tables by their subpartition count. |
| D-13.06-20 | S2 | packaged | `ch_sink_tools/db_compare/mysql_table_checksum.py:82-128`; `ch_sink_tools/db_compare/clickhouse_table_checksum.py:128-192` | reproduced (R01: `enum('float','x')` and `set('realtime','b')` skipped on MySQL only, `bit(1)` base64 against `Nullable(Bool)` `toString`, `time(3)` substr, timestamp in the session zone, trailing `\|\|'#'`) | Packaged renderings are pre-11.02 and report DIFFERENT for equal tables on common types. (The trailing `\|\|'#'` after a skipped last column is fixed, §3.11; the other renderings remain.) |
| D-13.06-21 | S2 | both | `db_compare/top_level_table_checksum.py:240-245,273-284`; `db_compare/mysql_table_checksum.py:210` | code-read (the same literal is compared with a wall clock on MySQL and parsed in the column zone on ClickHouse) | `DATETIME` filters select different rows on the two sides in a non-UTC deployment. |
| D-13.06-22 | S2 | packaged | `ch_sink_tools/db_compare/mysql_table_checksum.py:168-170`; `ch_sink_tools/db_compare/clickhouse_table_checksum.py:218-220`; `ch_sink_tools/db_compare/mysql_table_count.py:44-46` | reproduced (R06: `{__import__('os').getpid() > 0}` → `True`; a quote → SyntaxError) | `fstr` evaluates the where clause as a Python f-string: code execution from CLI and YAML input, and any `'` breaks it. |
| D-13.06-23 | S3 | packaged | same lines as D-13.06-22; `pyproject.toml` `requires-python` | reproduced (Python 3.9: `TypeError 'staticmethod' object is not callable`) | The module-level `@staticmethod fstr` is uncallable on Python < 3.10, while the package declares `>=3.6`. |
| D-13.06-24 | S2 | packaged | `ch_sink_tools/db/clickhouse.py:68` | reproduced (R09: `clickhouse_password S3cret!`) | ClickHouse password logged in clear at DEBUG (`ch-ch-checksum --debug`, `ch-ch-count --debug`). |
| D-13.06-25 | S3 | packaged | `ch_sink_tools/db_compare/clickhouse_table_checksum.py:291-310,331,400` | code-read (`execute_sql` returns one row for `count(*)`; DDL before any table) | FIXED (needed once the packaged driver runs this side, D-13.06-1): both removed, as in legacy. Test: `test_packaged_checksum_verdicts.py::TestPackagedClickHouseSideIsReadOnly::test_main_runs_no_ddl`. Was: `CREATE FUNCTION` on every run (needs the DDL privilege) and a dead count pre-check that would print `md5('')`. |
| D-13.06-26 | S3 | both | `db_compare/top_level_table_checksum.py:500`; `db_compare/mysql_table_checksum.py:460`; `db_compare/mysql_table_count.py:215`; packaged equivalents; `db/mysql.py:54-55` | reproduced (R04: `'list' object has no attribute 'fetchall'`) | FIXED: the driver, the MySQL side and both count runners (both copies) take the `[[regex]]` list as the one named table. Test: `test_manual_runner_recipes.py::TestNoWc::test_legacy_driver`. Was: `--no_wc` returned a list where a result object was expected (AttributeError, exit 1). |
| D-13.06-27 | S3 | both | `db_compare/top_level_table_checksum.py:252-254,292-293`; `db_compare/mysql_table_checksum.py:337-339`; `db_compare/clickhouse_table_checksum.py:53-59` | code-read (debug mode prints no checksum line, so the pipeline fails) | FIXED: both sides (both copies) run the aggregate and then the per-row query, write `out.<table>.<side>.txt` and print the checksum line. Test: `test_manual_runner_recipes.py::TestDebugOutput::test_legacy_mysql_side`. Was: the driver's `--debug_output` made every table fail. |
| D-13.06-28 | S3 | both | `db_compare/mysql_table_checksum.py:238`; `db_compare/clickhouse_table_checksum.py:342`; packaged PM200, PC254; count runners LMC76, LCC68 | code-read | `<db>.<table>` is unquoted in every aggregate and count statement (the lock path quotes it). Reserved words and `-` fail. Embedded backticks and double quotes in column names are not escaped. |
| D-13.06-29 | S3 | legacy | `db_compare/clickhouse_table_checksum.py:107,127-138,188-191` | code-read | ClickHouse types are classified by substring of the full type (`Enum8('Float'=1)` matches `Float`), the bug class 11.02 fixed on the MySQL side. |
| D-13.06-30 | S3 | legacy | `Dockerfile_mysql_checksum:20`; `Dockerfile_clickhouse_checksum:20` | code-read (exec-form `ENTRYPOINT` performs no variable expansion) | The container images pass `"$MYSQL_HOST"` and similar strings literally. The password is meant for argv. |
| D-13.06-31 | S3 | both | `db_compare/top_level_table_checksum.py:469-473`; `ch_sink_tools/db_compare/top_level_table_checksum.py:283-287` | reproduced (R06: IndexError escapes `run_config`) | A two-part `ignored_columns` entry crashes the driver outside its error handling. Entries with more than three parts are silently ignored. |
| D-13.06-32 | S3 | both | `db_compare/clickhouse_table_checksum.py:427`; `db_compare/clickhouse_table_count.py:113-114`; packaged equivalents | reproduced (R10: `'False'` is truthy) | `--secure` is a string option, so `--secure False` enables TLS. |
| D-13.06-33 | S4 | both | `db_compare/top_level_table_checksum.py:499,503`; `ch_sink_tools/db_compare/top_level_table_checksum.py:312,316` | reproduced (R10: `db1\.orders` → all tables) | `table_include_list` patterns are pre-filtered by the literal prefix `<db>.`. Regex-style patterns are dropped and the include list silently turns off. |
| D-13.06-34 | S4 | both | `db_compare/mysql_table_checksum.py:100-102`; `ch_sink_tools/db_compare/mysql_table_checksum.py:113-115` | code-read | The DATE clamp (`1900-01-01`..`2299-12-31`) is neither counted nor warned, unlike the datetime clamp. |
| D-13.06-35 | S4 | both | `db_compare/mysql_table_checksum.py:137-138`; `ch_sink_tools/db_compare/mysql_table_checksum.py:72-73`; specs 11.02 §3.9, §3.10, §6 item 2, FM-11.02-3, FM-11.02-9 | reproduced (R01: two same-collation columns get `convert()`) and code-read | `same_charset` counts columns, not distinct collations (harmless). Spec 11.02 drifts from the code: MySQL errors rather than wraps on overflow; the ClickHouse side does not split multi-token exclusions; failed runs do not skip pending tables; the FM-11.02-9 recipe uses space-separated exclusions; coverage warnings are dropped by the driver. |
| D-13.06-36 | S3 | both | `db_compare/top_level_table_checksum.py:160-199`; `ch_sink_tools/db_compare/top_level_table_checksum.py:319-326` | code-read (no position, GTID or connector-offset read anywhere in the runners) | "Replica caught up" is never established. The only mechanism is a fixed sleep after locking, so lag is reported as a difference indistinguishable from divergence. |
| D-13.06-37 | S4 | both | `db_compare/top_level_table_checksum.py:203`; `db/mysql.py:46-47` | code-read | The driver's `--include_partitions_regex` filters tables but checksums them whole. The name suggests a partition-restricted comparison. |
| D-13.06-38 | S2 | legacy | `db_compare/mysql_table_checksum.py` (`mysql_column_expression`, binary branch) | reproduced end to end (connector `binary.handling.mode: base64`: ClickHouse holds `abcd` for `b'1010101111001101'`, the MySQL side rendered `q80=`; the scheduled job reported `Checksum difference` for every table with a BIT(16) column on clean data) | FIXED: BIT(n>1) is rendered `lower(hex(cast(c as binary)))` under every `--binary_encoding`. Test: `test_scheduled_job_findings.py::TestBitColumnRendering::test_bit_n_is_lower_hex_under_every_encoding`; e2e `tests_e2e/mysql/test_mysql_01_production_job.py::test_clean_data_passes_the_job`. Was: base64 with `--binary_encoding base64`, while the connector applies base64 to binary/varbinary/blob only. |
| D-13.06-39 | S2 | legacy | `install.sh:4` | reproduced end to end (`install.sh: line 4: PYTHONPATH: unbound variable` under the job's `set -euo pipefail`) | FIXED: `export PYTHONPATH="${PYTHONPATH:-}":.`. Test: `test_scheduled_job_findings.py::TestInstallShUnderStrictMode::test_sources_with_pythonpath_unset`; e2e `tests_e2e/mysql/test_mysql_01_production_job.py::test_job_sources_install_sh_under_set_euo_pipefail`. Was: a job sourcing `install.sh` after `set -u` with `PYTHONPATH` unset died before any checksum. |
| D-13.06-40 | S1 | both | `db_compare/mysql_table_checksum.py` (`build_mysql_row_expression`); `db_compare/clickhouse_table_checksum.py` (`build_clickhouse_row_expression`); `ch_sink_tools/db_compare/mysql_table_checksum.py` and `ch_sink_tools/db_compare/clickhouse_table_checksum.py` (`get_table_checksum_query`) | reproduced end to end (a MySQL `tinyint(1) GENERATED ALWAYS AS (...) STORED` column, nullable but never NULL, replicated as a non-Nullable `Int8`: the `--debug_output` row strings were identical except the trailing flags, `0000000010` on MySQL against `000000001` on ClickHouse; every row differed and the scheduled job reported a `Checksum difference` for a table whose values were all equal) | FIXED in both copies: the trailing null-flags element holds one value-based flag per compared column (after exclusions and the floating-point/JSON skips) on both sides, nullable or not: `ISNULL(c)` on MySQL, `case when c is null then '1' else '0' end` on ClickHouse. The per-value NULL guards are unchanged, so a MySQL NULL against a non-Nullable default still differs in its flag. Tests: `test_checksum_fidelity.py::TestNullFlagsOverEveryComparedColumn::test_nullability_mismatch_gives_the_same_flags_on_both_sides`, `::test_excluded_and_skipped_columns_contribute_no_flag`, `::test_table_without_nullable_columns_gets_one_flag_per_column`, `::test_returned_nullables_are_still_the_declared_nullable_columns`; packaged `test_packaged_checksum_verdicts.py::TestPackagedNullFlagsOverEveryComparedColumn::test_nullability_mismatch_gives_the_same_flags_on_both_sides`, `::test_excluded_and_skipped_columns_contribute_no_flag`, `::test_table_without_nullable_columns_gets_one_flag_per_column`. Was: each side flagged only the columns its own catalog declares nullable, so a column declared nullable on one side and not on the other made every row hash differently on clean data. It hid nothing, but every scheduled run failed on every such table. |
