# Spec 11.05: Unit Coverage for the Snapshot/Load Tooling (`mysql_dumper`, `clickhouse_loader`)

## 1. Executive Summary & Purpose
The production reload path (Spec 11.04 and the ansible `initial_load`) is
`mysql_dumper.py` (dump) → `clickhouse_loader.py` (schema translate + load) →
`db_compare` (value-level verify). Domain 11 already specifies the checksum
side (11.02) and the resync tool (11.04), but the two tools that actually move
the data — the MySQL dumper and the ClickHouse loader — had **zero** unit
coverage. Their deterministic, database-free logic (credential redaction,
mysqlsh dump-clause construction, dump-file path parsing, dump-timezone
discovery, MySQL→ClickHouse DDL translation) is exactly the part that is cheap
to test and expensive to get wrong: a redaction miss leaks a password into a
log, a DDL-translation miss makes every load of that table fail, a
dump-timezone miss shifts every timestamp. This spec declares the offline unit
contract for that logic. The integrated dump→load→checksum path against a live
MySQL+ClickHouse is specified and covered separately (e2e suite); this spec is
the fast, no-container layer that runs on every commit.

## 2. Codebase Mapping on 2.11.0
- **Dumper**: `sink-connector/python/db_dump/mysql_dumper.py` —
  `register_secret`, `redact_password`, `generate_mysqlsh_dump_tables_clause`,
  `check_program_exists`.
- **Loader**: `sink-connector/python/db_load/clickhouse_loader.py` —
  `parse_schema_path`, `parse_schema_path_mysqlshell`, `find_dump_timezone`,
  `find_create_table`, `find_primary_key`, `find_partitioning_options`,
  `get_unix_timezone_from_mysql_timezone`, `convert_to_clickhouse_table`.
- **DDL translator**: `sink-connector/python/db_load/mysql_parser/mysql_parser.py`
  `convert_to_clickhouse_table_antlr` (the antlr path the loader uses).
- **Postgres mapper/filter**: `sink-connector/python/ch_sink_tools/db_load/postgres_type_mapper.py`
  `map_pg_type`, `sink-connector/python/ch_sink_tools/db_dump/postgres_dumper.py`
  `filter_tables_by_regex`.
- **Tests**: `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py`,
  `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py`,
  `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py`,
  collected by the same `pytest`/`unittest` invocation that runs the existing
  157 tests.

## 3. Contract
### 3.1 Credential redaction is exact
`redact_password` masks every registered secret (by exact literal value, so a
password containing a quote or whitespace is masked whole regardless of shell
quoting) and any `--password <word>` value even when unregistered, while
leaving non-secret tokens (user, host, port) intact.

### 3.2 The mysqlsh dump clause is built faithfully
`generate_mysqlsh_dump_tables_clause` emits `util.dumpTables('<db>', <tables>,
'<dir>', <options>)` with `dataOnly`/`ddlOnly` reflecting the requested mode,
`partitions` present for a data dump with a partition map and absent for a
schema-only dump, and `threads`/`bytesPerChunk` carried through.

### 3.3 Dump-file identity and timezone are parsed correctly
`parse_schema_path` (mydumper `db.table-schema.sql.gz`) and
`parse_schema_path_mysqlshell` (mysqlsh `db@table.sql`) return `(db, table)`;
`find_dump_timezone` extracts the dump's `SET TIME_ZONE='...'` (case
insensitive) or `None`; `get_unix_timezone_from_mysql_timezone` maps a numeric
offset to an IANA zone at that offset and falls back to `UTC` for an
unmatched offset.

### 3.4 MySQL→ClickHouse DDL translation preserves the shape
`convert_to_clickhouse_table_antlr` maps `DATETIME(p)`/`TIMESTAMP` to
`DateTime64`, preserves `DECIMAL(p,s)` precision/scale, emits a
`ReplacingMergeTree(_version, is_deleted)` engine and an `ORDER BY` on the
primary key, and stamps a configured `datetime_timezone` onto the DateTime64
columns; a source with no `CREATE TABLE` yields `('', [])`.

## 4. Invariants Preserved
- **No credential ever survives redaction** (I: logs are safe to ship).
- **The translated DDL is loadable and lossless in shape** — precision,
  nullability, engine and sort key are preserved (a mistranslation makes the
  load path in Spec 11.04 fail; MySQL remains the source of truth).
- **The offline unit layer needs no database** and runs on every commit,
  independent of the container-backed e2e.

## 5. Verification Criteria
All tests are offline and DB-free
(`db_dump/tests/test_mysql_dumper_unit.py`,
`db_load/tests/test_clickhouse_loader_unit.py`), run by the same pytest
invocation as the existing suite in `.github/workflows/spec-governance.yml`:

- `TestRedactPassword` — §3.1: a registered secret is masked; a `--password`
  value is masked even when unregistered; a secret containing a single quote
  is fully masked; user/host survive.
- `TestDumpTablesClause` — §3.2: the clause targets the database/table/dir,
  sets `dataOnly`/`ddlOnly` per mode, includes `partitions` only for a data
  dump, and carries `threads`/`bytesPerChunk`.
- `TestCheckProgramExists` — §3.2: a present program resolves, a missing one
  does not.
- `TestSchemaPathParsing` — §3.3: mydumper and mysqlsh dump paths parse to
  `(db, table)`.
- `TestDumpTimezone` — §3.3: `SET TIME_ZONE` is extracted case-insensitively,
  absent returns `None`.
- `TestUnixTimezoneFromMysqlTimezone` — §3.3: `+00:00` resolves to a zone at
  offset 0, an impossible offset falls back to `UTC`.
- `TestSourceIntrospection` — §3.4: `find_create_table`, `find_primary_key`,
  `find_partitioning_options` detect / return `None`/`''` correctly.
- `TestDdlConversionAntlr` — §3.4: DATETIME→DateTime64, DECIMAL(30,15)
  preserved, ReplacingMergeTree engine, ORDER BY the primary key, configured
  timezone stamped, and a non-DDL source yields `('', [])`.
- `TestMapPgType` — §3.4 (Postgres): integer/float/boolean families,
  `numeric(p,s)`→`Decimal(p,s)` (explicit and embedded), bare numeric→
  `Decimal(18, 6)`, `timestamp`→`DateTime64(6, 'UTC')`, time/interval/varchar/
  text/array/uuid→`String`, `Nullable(...)` wrapping, and an unknown type
  falling back to `String`.
- `TestFilterTablesByRegex` — the Postgres dumper's include/exclude regex
  table filter, singly and combined, and the no-pattern passthrough.

## 6. Failure Modes & Recovery
The component here is the offline test layer and the logic it pins. A failure of the layer does not stop replication. It lets a broken dumper or loader reach production, where the damage is either a leaked credential or a reload that renders values differently from the connector. The spec 11.04 canary and the spec 11.02 checksum catch the second kind, but only after a load. The entries below are ordered by how silently they fail.

- **FM-11.05-1 The packaged loader has no redaction, and the tests cover the other copy**
  - **Trigger**: the packaged loader (`python -m ch_sink_tools.db_load.clickhouse_loader`, the `ch-mysql-resync` default and the `pyproject.toml` entry point) is run with `--clickhouse_password`.
  - **Behaviour**: `ch_sink_tools/db_load/clickhouse_loader.py` `load_data_mysqlshell()` builds `--password '<password>'` from `args.clickhouse_password` (single quotes, not `shlex.quote`). Its `execute_load()` logs the whole command with `logging.info(cmd)`, and it has no `register_secret` / `redact_password`. A password containing `'` breaks the shell command, and the load fails. `ch-mysql-resync` itself passes only `--clickhouse_config_file`, so on that path no password reaches the command line. The unit tests of §5 import only the legacy `db_load/clickhouse_loader.py`, which does redact its log line.
  - **Detection**: none. The redaction contract of §3.1 is green because it tests the other copy.
  - **Blast radius**: the ClickHouse password is in plain text in the loader log of every such run.
  - **Recovery**: rotate the password and scrub the logs. Run the packaged loader only with `--clickhouse_config_file`.
  - **RTO**: a credential rotation (operator-dependent, unmeasured).
  - **Test**: `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestPackagedLoaderRedaction::test_logged_command_is_redacted`, `...::test_failure_message_is_redacted`, `...::test_password_with_shell_metacharacters_stays_one_word`.
  - **FIXED**: the packaged loader quotes `--password` / `--config-file` with `shlex.quote`, registers the password, logs and raises only `redact_password(cmd)`, and `load_data_mysqlshell()` uses the resolved password (the legacy behaviour).

- **FM-11.05-2 The legacy loader leaks the password when a load fails**
  - **Trigger**: any failing insert. ClickHouse can be down or read-only, a value can fail to parse, or `TOO_MANY_PARTS` can appear. This includes `ch-mysql-resync --loader-cmd` runs that select this legacy copy.
  - **Behaviour**: the legacy `load_data_mysqlshell()` puts the password resolved from `--clickhouse_config_file` on the command line (`--password <shlex-quoted>`), where it is visible in the process list during each insert. `execute_load()` logs the redacted command. On failure, however, it raises `AssertionError("command " + cmd + " failed")` with the raw command, so the traceback prints `--password <secret>`. `run_quick_command()` also logs `"cmd " + cmd` unredacted at DEBUG.
  - **Detection**: loud about the failure (`command failed : terminating`, traceback, non-zero exit). Silent about the leak.
  - **Blast radius**: the password appears in every failure log of a load (for resync, `load_<schema>.<table>.log`).
  - **Recovery**: rotate the password and scrub the logs. The fix is to raise with `redact_password(cmd)`.
  - **RTO**: a credential rotation (unmeasured).
  - **Test**: `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_failure_message_is_redacted`. The redacted INFO line and the loud failure are pinned by `...::TestLegacyLoaderFailurePath::test_logged_command_is_redacted`.
  - **FIXED**: both loaders raise `AssertionError("command " + redact_password(cmd) + " failed")` and `run_quick_command()` logs the redacted command at DEBUG.

- **FM-11.05-3 The tests this spec declares never run in CI**
  - **Trigger**: any change to `db_dump/`, `db_load/`, `ch_sink_tools/` or `db_compare/`.
  - **Behaviour**: §5 says the tests are "run by the same pytest invocation as the existing suite in `.github/workflows/spec-governance.yml`". That workflow runs only `scripts/tests` and `db_load/tests/test_mysql_resync.py`. `db_load/tests/test_clickhouse_loader_unit.py` does not even import without the `antlr4` runtime (`ModuleNotFoundError: No module named 'antlr4'`), and `test_postgres_type_mapper_unit.py` needs `psycopg2`. The workflow installs neither. The spec 11.02 checksum tests are not run either.
  - **Detection**: none. A regression merges green.
  - **Blast radius**: every contract of §3, and all of spec 11.02 §5, is unenforced between manual runs.
  - **Recovery**: run the suite locally before a tooling change: `pip install -r sink-connector/python/requirements.txt`, then `python3 -m pytest db_compare/tests db_load/tests db_dump/tests` from `sink-connector/python`. The fix is a workflow step doing exactly that.
  - **RTO**: about 5 s for the offline suite (measured: 154 tests of `db_compare/tests`, `db_dump/tests` and the resync and failure-mode files in 5.3 s on the dev host, 2026-09-30), plus the install.
  - **Test**: GAP: a workflow step (not a unit test) that runs the whole offline Python suite with the requirements installed.
  - **DEFECT**: §5's claim that CI runs these tests is false.

- **FM-11.05-4 A dump time zone is mapped to the wrong zone, silently**
  - **Trigger**: a dump whose `SET TIME_ZONE='<offset>'` matches no IANA zone's offset today, or matches only zones whose offset differs on other dates.
  - **Behaviour**: `get_unix_timezone_from_mysql_timezone()` compares the offset with each zone's offset at `datetime.now()`, returns the first match in alphabetical order, and falls back to `"UTC"` without a warning. The load runs under `export TZ=<zone>`. `TIMESTAMP` values of dates with a different offset, or all values after the fallback, are shifted.
  - **Detection**: none. Per-zone lines are logged at DEBUG only.
  - **Blast radius**: every `TIMESTAMP` column of the loaded tables can be shifted by the offset difference.
  - **Recovery**: the spec 11.04 canary (`CANARY_FAILED`) or the spec 11.02 checksum exposes it. Reload with the zone given explicitly.
  - **RTO**: a reload of the affected tables (proportional to size).
  - **Test**: `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py::TestUnixTimezoneFromMysqlTimezone::test_unknown_offset_falls_back_to_utc` pins the silent fallback.
  - **DEFECT**: an unmatched or date-dependent offset is resolved without a warning.

- **FM-11.05-5 The ANTLR translator fails and the regexp translator is used silently**
  - **Trigger**: a `CREATE TABLE` the ANTLR grammar cannot translate (a new MySQL syntax).
  - **Behaviour**: `convert_to_clickhouse_table()` catches every exception from `convert_to_clickhouse_table_antlr`, logs `Use regexp DDL converter` at INFO and returns `convert_to_clickhouse_table_regexp(...)`. That path is not covered by §5 and may map types differently.
  - **Detection**: an INFO line only.
  - **Blast radius**: tables created by an initial load (schema phase) can get a shape different from what the connector would create. `ch-mysql-resync` is not affected: it loads with `--data_only` into `CREATE TABLE ... AS <live>`.
  - **Recovery**: compare the created DDL with `SHOW CREATE TABLE` of a connector-created table. Recreate the table from the connector's DDL and reload.
  - **RTO**: a reload of that table.
  - **Test**: GAP: a loader test feeding a DDL the grammar rejects and asserting a WARNING and the regexp output's column types.
  - **DEFECT**: a translator switch that can change column types is logged at INFO.

- **FM-11.05-6 One data file of a table fails to load**
  - **Trigger**: an insert of one `*.tsv.zst` chunk fails. Causes include ClickHouse down, `MEMORY_LIMIT_EXCEEDED`, or a parse error.
  - **Behaviour**: `execute_load()` raises. `load_data_mysqlshell()` re-raises the first failed future, and the loader exits non-zero with a traceback. Other chunks of the same table may already be inserted, so the target is partial.
  - **Detection**: loud. `command failed : terminating`, traceback, non-zero exit. `ch-mysql-resync` records `LOAD_FAILED` and replaces nothing.
  - **Blast radius**: under `ch-mysql-resync`, only the scratch table is partial. A standalone load into a live table leaves it partial with no reconciliation.
  - **Recovery**: under `ch-mysql-resync`, re-run `patch --apply`. It drops and recreates only the scratch copy and reloads. For a standalone load, reload into an empty table and compare counts with the dump.
  - **RTO**: a reload of that table (proportional to size).
  - **Test**: `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_logged_command_is_redacted` (a failing insert raises).

Summary: 6 failure modes, 5 DEFECT, 2 GAP.
