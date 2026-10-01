# Spec 13.05: PostgreSQL Snapshot Dump and Load

## 1. Executive Summary & Purpose

`ch-pg-dump` is the PostgreSQL initial-snapshot tool of the Python toolset. In one
process it discovers the source tables, creates the ClickHouse databases and
`ReplacingMergeTree` tables, streams every table (or primary-key range of a large
table) with `psql ... COPY (SELECT ...) TO STDOUT WITH (FORMAT CSV ...)` piped into
`clickhouse-client ... INSERT ... SELECT ... FROM input(...) FORMAT CSV`, and finally
writes a Debezium offset row (the WAL LSN read at the start) into the connector's
ClickHouse offset table, so that the Java connector can start streaming with
`snapshot.mode=never`. The same module also carries two disk-based strategies
(`psql-copy`, `pgdump`), a benchmark mode, a source privilege validator and a
heartbeat-table bootstrap. A separate ANTLR DDL parser
(`parse_postgres_ddl`) translates PostgreSQL DDL text to a ClickHouse description;
nothing in the tree calls it.

This spec describes the code on 2.11.0 as built and records its defects. The
headline findings:

- Load failures are never detected. The `clickhouse-client` command string ends
  in `; rm -f <query file>`, so the shell exit status of every streaming,
  `psql-copy` and `pgdump` load is the exit status of `rm` (0). A failed COPY or a
  rejected INSERT is logged as `Done` and the offset is written (D-13.05-1,
  reproduced). Even without that, `psql -f` without `ON_ERROR_STOP` exits 0 when
  its COPY fails (D-13.05-2).
- The snapshot has no consistency or retention anchor. The LSN is read on its
  own autocommit connection before any data is read; every table and every
  segment is then read by its own `psql` session at its own time; no replication
  slot is created or checked, and no snapshot is exported. Duplicated changes
  are absorbed by `ReplacingMergeTree` (snapshot rows carry `_version = 0`), but
  if the slot does not exist when the dump starts, the WAL between the recorded
  LSN and the slot that the connector creates later is never delivered
  (D-13.05-3).
- `--skip_existing` treats any table with at least one row as done, and still
  writes the new LSN (D-13.05-4, reproduced). Keyless source tables are created
  with `ORDER BY (tuple())` and collapse to one row on merge (D-13.05-5). The
  connector-config list translation drops the schema and is unanchored, so the
  snapshot skips or adds tables that the connector does not (D-13.05-6,
  reproduced).
- The `psql-copy` and `pgdump` strategies cannot run from `main()`: both stop
  with a `TypeError` from wrong keyword arguments (D-13.05-15/16, reproduced).
- The type mapper with unit tests (`map_pg_type`) is not the mapper that
  `ch-pg-dump` uses. The dumper uses `pg_type_to_ch` (spec 13.02's file), and the
  two disagree on bare `numeric` and on `timestamp without time zone`
  (D-13.05-27).

Spec 13.02 owns `sink-connector/python/ch_sink_tools/db/postgres.py` and
`sink-connector/python/ch_sink_tools/db/clickhouse.py`. This spec restates only
what the dump path calls and how it uses it.

## 2. Codebase Mapping on 2.11.0

Packaged tree only. **The legacy top-level tree has no PostgreSQL copy** of any
file in this spec: there is no db/postgres.py, db_dump/postgres_dumper.py,
db_load/postgres_type_mapper.py or db_load/postgres_parser/ under
sink-connector/python. The single PostgreSQL test file lives in the legacy test
directory but imports the packaged modules. See §3.16.

- **Entry point**: `sink-connector/python/pyproject.toml`, `[project.scripts]`
  `ch-pg-dump = "ch_sink_tools.db_dump.postgres_dumper:main"`. There is no
  separate PostgreSQL load entry point: the dump tool also loads.
  `sink-connector/python/README.md` lists `ch-pg-dump`.
- **Dumper / loader**: `sink-connector/python/ch_sink_tools/db_dump/postgres_dumper.py`
  (2,836 lines). It contains `main`, `load_table`, `build_psql_copy_cmd`,
  `build_ch_insert_cmd`, `run_command`, `run_quick_command` (unused),
  `check_program_exists`, `pg_bin`, `filter_tables_by_regex`, `ensure_ch_database`,
  `create_ch_table`, `drop_ch_table`, `truncate_ch_table`, `check_ch_table_has_data`,
  `get_pg_approx_row_count`, `get_pk_range_boundaries`,
  `get_pk_type_is_segmentable`, `build_segment_where_clause`,
  `psqlcopy_dump_table`, `psqlcopy_load_table`, `pgdump_dump_tables`,
  `pgdump_load_table`, `run_benchmark`, `ensure_offset_database_and_table`,
  `write_lsn_offset`, `ensure_heartbeat_table`, `validate_postgres_privileges`,
  `_debezium_list_to_regex`, `_debezium_schema_list_to_regex`,
  `parse_sink_connector_config`, `load_config_file` and `merge_config_with_args`.
- **Type mapper and DDL / SQL builders**:
  `sink-connector/python/ch_sink_tools/db_load/postgres_type_mapper.py`. It contains
  `_BASE_MAP`, `_STRIP_PREFIXES` (unused), `_normalize_type`, `map_pg_type`,
  `map_udt_type` (unused), `build_column_defs`, `build_create_table`,
  `build_insert_structure`, `build_select_columns` and `build_offset_insert`.
- **Shared PostgreSQL helpers (owned by 13.02)**:
  `sink-connector/python/ch_sink_tools/db/postgres.py`. The dump path calls
  `get_postgres_connection`, `execute_pg`, `get_schemas`, `get_tables`,
  `get_table_columns`, `pg_type_to_ch` (through `get_table_columns`),
  `get_table_pk`, `get_table_row_count`, `get_standby_lsn`, `get_server_timezone`
  and `resolve_credentials_from_pgpass`. It imports `build_ch_create_table_ddl`
  but never calls it. `get_current_lsn` is not used by the dumper.
- **Shared ClickHouse helpers (owned by 13.02)**:
  `sink-connector/python/ch_sink_tools/db/clickhouse.py`. Used:
  `clickhouse_connection`, `clickhouse_execute_conn` and
  `resolve_credentials_from_config`.
- **Naming templates**: `sink-connector/python/ch_sink_tools/db_dump/naming.py`.
  Used: `validate_template` and `resolve_ch_names`.
- **Column type overrides (shared with 13.04)**:
  `sink-connector/python/ch_sink_tools/config/column_type_overrides.py`
  (`ColumnTypeOverrideConfig.from_cli_args`, `from_connector_properties`,
  `get_direct_override`, `get_alias_overrides`, `has_overrides`) and
  `sink-connector/python/ch_sink_tools/config/override_reconciler.py`
  (`ch_table_exists`, `reconcile_overrides_with_existing_table`,
  `ColumnTypeOverrideMismatchError`).
- **DDL parser**: `sink-connector/python/ch_sink_tools/db_load/postgres_parser/postgres_parser.py`
  (`parse_postgres_ddl`, `_PostgreSQLErrorListener`, `_main`),
  `sink-connector/python/ch_sink_tools/db_load/postgres_parser/CreateTablePostgreSQLParserListener.py`,
  `sink-connector/python/ch_sink_tools/db_load/postgres_parser/__init__.py` (re-exports
  `parse_postgres_ddl`),
  `sink-connector/python/ch_sink_tools/db_load/postgres_parser/README.md`. Public
  entry points of the generated files:
  `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLParser.py`
  (`PostgreSQLParser.root`),
  `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLLexer.py`,
  `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLParserBase.py`
  and `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLLexerBase.py`.
  The grammar sources are `sink-connector/python/antlr_grammars/postgres/PostgreSQLParser.g4`
  and `sink-connector/python/antlr_grammars/postgres/PostgreSQLLexer.g4`. The
  generation script is `sink-connector/python/build_grammars.sh`.
- **Java counterpart of the offset hand-off** (cross-reference only):
  `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumOffsetStorage.java`
  (`getOffsetKey`, `offsetValueQuery`, `updateLsnInformation`,
  `updateDebeziumStorageRow`).
- **Tests**: `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py`
  (`map_pg_type`, `filter_tables_by_regex`) and `sink-connector/python/tests/test_naming.py`
  (`render_template`, `validate_template`, `resolve_ch_names`,
  `filter_tables_by_regex`). No test calls `main`, `load_table`, any command
  builder, `pg_type_to_ch`, `get_table_columns`, the offset writer or the parser.
- **Related specs**: 11.05 (unit-coverage contract that declared the mapper tests);
  08.05 (`ORDER BY tuple()` collapse, FM-08.05-3); 07.02 / 07.03 (CDC-side numeric
  and temporal conversion); 09.03 (offset table, read query, FM-09.03-6); 13.01
  (packaging, two trees); 13.02 (connection and credential layer); 13.04 (column
  type overrides and reconciler); 13.07 (PostgreSQL verification).

## 3. Contract (Behaviour as Built)

### 3.1 Inputs, outputs and side effects at a glance

| Kind | What |
|---|---|
| Reads (source) | `information_schema.schemata`, `information_schema.tables`, `information_schema.columns`, `pg_index`/`pg_class`/`pg_namespace`/`pg_attribute`, `pg_stat_user_tables`, `pg_roles`, `pg_replication_slots`, `pg_publication`, `SHOW wal_level`, `SHOW timezone`, `pg_last_wal_replay_lsn()`, `pg_current_wal_lsn()`, and the table data through `COPY` |
| Writes (source) | `public.sink_connector_heartbeat`: `CREATE TABLE IF NOT EXISTS`, `INSERT ... ON CONFLICT DO NOTHING` and `GRANT`, on every non-benchmark run that passes the privilege check, including `--dry_run` (§3.11) |
| Writes (ClickHouse) | `CREATE DATABASE IF NOT EXISTS`, optional `DROP TABLE IF EXISTS` / `TRUNCATE TABLE IF EXISTS`, optional `ALTER TABLE ... ADD/MODIFY COLUMN ... ALIAS`, `CREATE TABLE IF NOT EXISTS`, data `INSERT`, offset database/table `CREATE ... IF NOT EXISTS` and one offset `INSERT` |
| Local files | `/tmp/pg_copy_<random>.sql` (COPY text), `/tmp/ch_insert_<random>.sql` (INSERT text); `psql-copy`: `<dump_dir>/<table>.csv.gz`, `/tmp/pg_dump_copy_<random>.sql`; `pgdump`: a `pg_dump --format=directory` tree in `<dump_dir>`; benchmark: `/tmp/benchmark_psql_copy/<table>.csv.gz` |
| Child processes | `/bin/bash -c "set -o pipefail; psql ... | clickhouse-client ...; rm -f ..."` per job; `/usr/bin/which` per binary check; `pg_dump` (argument vector, not shell) |
| Stdout | Log lines (`%(asctime)s - %(levelname)s - %(threadName)s - %(message)s`), including the merged stdout/stderr of every child, logged at INFO |
| Exit status | §3.13 |

### 3.2 CLI argument table (`ch-pg-dump`, `postgres_dumper.py:1741-1919`)

Flags use underscores. The README example `ch-pg-dump --pg-host ... --ch-host ...`
(`sink-connector/python/README.md:46`) uses hyphens, which argparse rejects as
unrecognised arguments (D-13.05-34).

| Flag | Type | Default | Effect | Honoured? |
|---|---|---|---|---|
| `--pg_host` | str | env `PG_HOST` | Source host for psycopg2 and `psql -h` (unquoted in the shell) | yes; required after config merge (`:1981-1992`) |
| `--pg_port` | int | env `PG_PORT` or 5432 | Source port. `int()` of a non-numeric env value raises before parsing | yes |
| `--pg_database` | str | env `PG_DATABASE` | Source database; `psql -d "<db>"`; `{{ database }}` template variable; override lookup key | yes; required |
| `--pg_user` | str | env `PG_USER` | Source user. If None and the password comes from `~/.pgpass`, the first pgpass entry's user is used | yes |
| `--pg_password` | str | env `PG_PASSWORD` | Source password; else `~/.pgpass` (first entry, host not matched, 13.02); else exit 1 | yes; put in `PGPASSWORD='...'` inside the bash command string (D-13.05-13) |
| `--pg_schema` | str, nargs `+` | `['public']` | Explicit schema list, used only when neither `--pg_schema_include` nor `--pg_schema_exclude` is set | yes. A scalar from a config file is iterated per character (D-13.05-19) |
| `--pg_schema_include` | regex (PostgreSQL ARE) | None | `schema_name ~ %s` in `get_schemas` (parameterised) | yes; replaces `--pg_schema` |
| `--pg_schema_exclude` | regex (PostgreSQL ARE) | None | `schema_name !~ %s` | yes; replaces `--pg_schema` |
| `--ch_host` | str | env `CH_HOST` | ClickHouse host (driver and `clickhouse-client -h`) | yes; required |
| `--ch_port` | int | env `CH_PORT` or 9000 | Native port for both the driver and the client. Config-file 8123/8443 are rewritten to 9000/9440 | yes |
| `--ch_database` | str | env `CH_DATABASE` | Literal target database for every table; beats the template (`:2108-2115`) | yes (multi-schema collision, D-13.05-7) |
| `--ch_user` | str | env `CH_USER` or `default` | ClickHouse user; replaced by the config-file user when `--ch_password` is unset and `--ch_config_file` is given | yes |
| `--ch_password` | str | env `CH_PASSWORD` | ClickHouse password; `--password '<pw>'` on the client command line | yes (D-13.05-13) |
| `--ch_config_file` | path | None | `--config-file '<f>'` for clickhouse-client; also the credential source when no password is given (13.02) | yes |
| `--ch_secure` | flag | False | `secure=True` for the driver, `--secure` for the client (the port is not changed) | yes |
| `--tables` | regex (PostgreSQL ARE) | `.` | `table_name ~ '<regex>'`, interpolated into SQL inside single quotes | yes |
| `--exclude_tables` | regex (PostgreSQL ARE) | None | `table_name !~ '<regex>'` | yes |
| `--pg_table_include` | regex (Python `re.search`) | None | Second include filter applied in Python | yes (unanchored) |
| `--pg_table_exclude` | regex (Python `re.search`) | None | Second exclude filter applied in Python | yes (unanchored) |
| `--ch_database_template` | template | `{{ database }}` | Rendered when `--ch_database` is unset; variables `database`, `schema`, `table` (`naming.py`) | yes |
| `--ch_table_template` | template | `{{ table }}` | CH table name; must contain `{{ table }}` (`:1977`) | yes (alias-override lookup uses the rendered name, D-13.05-18) |
| `--config` | path | None | YAML config: connector format or dumper-native format (§3.3) | yes |
| `--column_type_overrides_file` | path | None | YAML overrides; wins over the inline string | yes for DDL; ignored by the load SELECT (D-13.05-11) |
| `--column_type_overrides` | str | None | `direct:s.t.c=Type,alias:s.t.c=Type\|expr` | same as above |
| `--threads` | int | 4 | `ThreadPoolExecutor(max_workers=...)` for load jobs. 0 raises `ValueError`, giving exit 1 | yes |
| `--batch_size` | int | None | Passed to `build_psql_copy_cmd(batch_size=...)`, which ignores it | **ignored** (help text says so) |
| `--drop_existing` | flag | False | `DROP TABLE IF EXISTS` before create, in Step 3 (skipped by `--data_only`) | yes; wins over `--truncate` |
| `--truncate` | flag | False | `TRUNCATE TABLE IF EXISTS` before create, in Step 3 | yes |
| `--skip_existing` | flag | False | Drops from the load list every table whose `count() > 0` | yes (D-13.05-4) |
| `--order_by_size` | flag | True | No-op: `store_true` on a default of True | **no effect** |
| `--no_order_by_size` | flag | — | Disables the ascending `n_live_tup` sort | yes |
| `--segment_threshold` | int | 1,000,000 | Tables whose `n_live_tup` is above this are split into PK ranges (streaming only); 0 disables | yes |
| `--segments_per_table` | int | 4 | ntile count; 1 or less disables | yes |
| `--strategy` | choice | `streaming` | `streaming` \| `psql-copy` \| `pgdump` | streaming only; the others raise `TypeError` (D-13.05-15/16) |
| `--dump_dir` | path | `/tmp/pg_dump` | Intermediate directory for `psql-copy` / `pgdump` | only on unreachable paths |
| `--dump_only` | flag | False | Stop after phase 1 of a disk strategy | unreachable |
| `--load_only` | flag | False | Skip phase 1 of a disk strategy | `pgdump` reaches phase 2 and fails there (D-13.05-16) |
| `--dump_jobs` | int | 4 | Intended for `pg_dump --jobs` | unreachable |
| `--pg_bin_dir` | path | env `PG_BIN_DIR` | Prefix for `psql`, `pg_dump`, `pg_restore` (not for `clickhouse-client`) | yes |
| `--benchmark` | flag | False | Runs `run_benchmark` and exits 0 | yes (§3.12) |
| `--benchmark_table` | str | None | Benchmark table (first schema of `--pg_schema`); required with `--benchmark` | yes |
| `--benchmark_limit` | int | 1,000,000 | `LIMIT` for the `psql-copy` arm only | partly (the streaming arm loads the whole table) |
| `--offset_table` | `db.table` | None | Target of the Step 5 offset row | yes |
| `--connector_name` | str | `sink-connector` | Builds `offset_key` `["<name>",{"server":"embeddedconnector"}]` | yes; quoted unescaped (D-13.05-26) |
| `--schema_only` | flag | False | Create tables only; no load and no offset | yes |
| `--data_only` | flag | False | Skip Step 3 (no create, no drop/truncate) | yes |
| `--dry_run` | flag | False | Logs DDL and commands without running CH DDL or pipes | partly (D-13.05-25) |
| `--debug` | flag | False | DEBUG level, which logs full shell commands with passwords | yes (D-13.05-13) |

Explicit-set detection (`:1927-1935`): a flag counts as "explicitly set" when its
parsed value differs from its argparse default. A CLI value equal to the default
(for example `--pg_schema public` or `--threads 4`), or a value that came from an
environment variable (environment values are the defaults), is therefore
overwritten by the config file (reproduced: typing `--pg_schema public --threads 4`
gives an empty explicit set; D-13.05-32).

### 3.3 Config file (`--config`, `:1679-1734`)

`yaml.safe_load`. If the top-level keys intersect `{database.hostname,
database.port, database.dbname, clickhouse.server.url, database.server.name}`,
the file is a **connector config** and is translated by `parse_sink_connector_config`.
Otherwise it is **dumper-native**: every key that names an argparse dest is
copied as is, with no type coercion, and other keys are dropped silently.

Connector-config translation (`:1568-1676`):

| Connector key | Dumper dest | Translation |
|---|---|---|
| `database.hostname` / `port` / `dbname` / `user` / `password` | `pg_host` / `pg_port` (int) / `pg_database` / `pg_user` / `pg_password` | direct |
| `clickhouse.server.url` | `ch_host` | direct (treated as a host name) |
| `clickhouse.server.port` | `ch_port` | 8123→9000, 8443→9440, else unchanged |
| `clickhouse.server.user` / `password` | `ch_user` / `ch_password` | direct |
| `clickhouse.server.database` | `ch_database_template` if it contains `{{`…`}}`, else `ch_database` | |
| `clickhouse.common.database.prefix`, `clickhouse.common.schema.template`, `clickhouse.database.schema.suffix=true` | `ch_database_template = <prefix>{{ database }}<schema template>` | removes a literal `ch_database`. Reproduced: prefix `prod_` with template `__{{ schema }}` gives `prod_{{ database }}__{{ schema }}` |
| `schema.include.list` / `schema.exclude.list` | `pg_schema_include` / `pg_schema_exclude` | `_debezium_schema_list_to_regex`: `a,b` → `a\|b`; returned unchanged if it contains `.*`, `^`, `$`, `[`, `]`, `(`, `)`, `+` or `?` |
| `table.include.list` / `table.exclude.list` | `pg_table_include` / `pg_table_exclude` | `_debezium_list_to_regex`: `schema.table` → `table`, joined with `\|`, unanchored (D-13.05-6) |
| `database.include.list` / `database.exclude.list` | `pg_database_include` / `pg_database_exclude` | **no argparse dest: silently dropped** |
| `name` (else `database.server.name`) | `connector_name` | |
| `offset.storage.jdbc.table.name` (else `offset.storage.jdbc.offset.table.name`) | `offset_table` | |
| `column_type_override.*` | `_column_type_override_config` | `ColumnTypeOverrideConfig.from_connector_properties`. Used only when no CLI override is given (`:1945-1951`) |

Precedence: explicitly set CLI flag > config file > environment-variable default >
argparse default. Environment variables lose to the config file, because their
values are the argparse defaults.

### 3.4 Start-up sequence (`main`, before Step 1)

1. Parse arguments, compute the explicit-set flags, load and merge the config
   (`:1921-1941`). The INFO line `Loaded N parameters` is emitted before any
   handler exists and is lost (D-13.05-33).
2. Build the column-override config (CLI file > CLI string > connector config)
   (`:1945-1953`).
3. Set `PG_BIN_DIR` from `--pg_bin_dir` (`:1956-1958`).
4. Install a stdout handler on the root logger, at INFO or DEBUG (`:1961-1973`).
5. `validate_template` on both templates: an unknown variable, or a table
   template without `table`, raises `ValueError`. This happens outside the main
   `try`, so the process ends with a traceback and exit 1 (`:1976-1978`).
6. Require `pg_host`, `pg_database` and `ch_host`, else `parser.error` (exit 2)
   (`:1981-1992`).
7. `assert check_program_exists(...)` for `psql` (under `PG_BIN_DIR`) and
   `clickhouse-client`, and for `pg_dump`/`pg_restore` when `--strategy pgdump`.
   An `AssertionError` gives a traceback and exit 1. `/usr/bin/which` is
   hard-coded (`:114-117`, `:1995-2003`).
8. Resolve credentials (`:2006-2027`). No PostgreSQL password at all: exit 1.
9. `--benchmark`: §3.12, then `sys.exit(0)`.

### 3.5 The streaming algorithm end to end (`main`, `:2050-2832`)

All of the following runs inside one `try`. `KeyboardInterrupt` and
`SystemExit` lead to `os._exit(1)`; any other exception is logged with its
traceback and gives `sys.exit(1)` (`:2823-2829`).

**Step 1 — LSN and time zone** (`:2054-2063`). Open connection #1 (psycopg2,
`autocommit=True`, `connect_timeout=20`, `options='-c statement_timeout=0'`;
13.02). Call `get_standby_lsn`:

```sql
SELECT pg_last_wal_replay_lsn()::text AS lsn      -- non-NULL on a standby (and on a promoted ex-standby)
SELECT pg_current_wal_lsn()::text AS lsn          -- only if the first returned NULL
```

`lsn_int = int(hi,16) * 2**32 + int(lo,16)`, the full 64-bit value. The Java side
parses the same way (`DebeziumOffsetStorage.updateLsnInformation`:
`(high << 32) | low`; spec 09.03 §3). Reproduced: `9C9/21AE7C20` gives
`10759458159648` in both. The docstring example (`10755683362016`), the
`build_offset_insert` docstring ("LOW-32-BIT") and the log text `low32=`
(`:2812`, `:2820`) are all wrong (D-13.05-34). `SHOW timezone` is read once and
used as the time zone of every `timestamp without time zone` column (§3.8).

**Step 2 — schemas** (`:2068-2083`). With an include or exclude regex:

```sql
SELECT schema_name FROM information_schema.schemata
WHERE schema_name NOT IN ('pg_catalog','information_schema','pg_toast','pg_temp_1','pg_toast_temp_1')
  [AND schema_name ~ %s] [AND schema_name !~ %s] ORDER BY schema_name
```

Otherwise the literal `--pg_schema` list is used, unchecked. An empty result
gives exit 1. Only `pg_temp_1` and `pg_toast_temp_1` are excluded, so other
`pg_temp_N` schemas match an include such as `.*`.

**Step 2b — tables** (`:2088-2137`). For each schema, `get_tables` (13.02):

```sql
SELECT table_name FROM information_schema.tables
WHERE table_schema = '<schema>' AND table_type = 'BASE TABLE'
  AND table_name ~ '<--tables>' [AND table_name !~ '<--exclude_tables>'] ORDER BY table_name
```

Schema and regex are interpolated inside single quotes, without escaping
(D-13.05-22). `BASE TABLE` includes both partitioned parents (relkind `p`) and
their partitions (D-13.05-23). Views, foreign tables and matviews are excluded.
The Python filters `--pg_table_include` / `--pg_table_exclude` are then applied
(`filter_tables_by_regex`, `re.search`). Each table becomes a work item
`(schema, table, ch_database, ch_table)`. `ch_database` is `--ch_database` if set,
else the rendered database template; `ch_table` is always the rendered table
template. No work items: exit 1.

**Ordering** (`:2140-2167`). With more than one item and ordering enabled, a
second connection reads `n_live_tup` per item (`get_pg_approx_row_count`:
`pg_stat_user_tables`, falling back to the same query through
`get_table_row_count`; -1 when there are no stats) and sorts ascending.

**Step 2c — privileges** (`:2177-2193`), once per schema on connection #1. Any
critical failure: exit 1 (§3.10).

**Step 2d — heartbeat** (`:2196-2197`) on connection #1 (§3.11). Connection #1 is
then closed. **No replication slot is created, no snapshot is exported, and no
transaction stays open past this point.**

**Step 3 — ClickHouse schema** (`:2212-2304`), unless `--data_only`. A driver
connection to `default` runs `CREATE DATABASE IF NOT EXISTS \`<db>\`` per distinct
database. Errors are logged and swallowed (`ensure_ch_database`, `:187-194`).
Then, per database, a connection to that database; per item of that database,
serially:
1. a new PostgreSQL connection for `get_table_columns(..., pg_server_timezone,
   override_config, pg_database)` and `get_table_pk`;
2. `DROP TABLE IF EXISTS \`db\`.\`t\`` (`--drop_existing`) or
   `TRUNCATE TABLE IF EXISTS \`db\`.\`t\`` (`--truncate`);
3. if overrides are configured, not `--dry_run`, and the table exists:
   `reconcile_overrides_with_existing_table` (direct mismatch raises and aborts
   the run with exit 1; alias columns are `ALTER ... ADD/MODIFY COLUMN ... ALIAS`;
   13.04);
4. `CREATE TABLE IF NOT EXISTS` (§3.7).

A create error propagates and gives exit 1 before any data moves.

**Step 4 — load** (`:2310-2778`), unless `--schema_only`. With `--skip_existing`, a
connection to `default` runs `EXISTS TABLE` and `SELECT count()` per item
(`check_ch_table_has_data`; exceptions count as "not existing"). Items with
`count() > 0` are dropped. If none remain, loading is skipped and Step 5 still
runs (D-13.05-4). Streaming then builds the job list per item: a new connection
reads `n_live_tup` and the PK. When `segment_threshold > 0`, `n_live_tup >
threshold` and `segments_per_table > 1`, `get_pk_type_is_segmentable` decides.
If the PK is segmentable, one job per boundary pair (§3.6); otherwise one
whole-table job. All jobs go into one `ThreadPoolExecutor(max_workers=--threads,
thread_name_prefix='pg_loader')`. Each job runs `load_table`:

1. opens its own psycopg2 connection and re-reads `get_table_columns(conn,
   schema, table, pg_server_timezone=...)`, **without the override config**
   (`:471`; D-13.05-11), plus PK and `n_live_tup`, then closes it;
2. builds the COPY command (§3.9.1) and the INSERT command (§3.9.2);
3. runs `set -o pipefail; <psql cmd> | <clickhouse-client cmd>; rm -f <insert file>`
   through `run_command` (`/bin/bash`, stdout+stderr merged, every line logged at
   INFO with a 20 ms sleep per line);
4. unlinks the COPY file and returns `(label, approx_rows, elapsed, rc == "0")`.
   Exceptions are caught and give `success=False`.

The summary is sorted by label. Any `False` logs `FAILED tables: [...]` and calls
`sys.exit(1)`, which skips Step 5. Because of D-13.05-1 and D-13.05-2, only
failures before the pipe (metadata queries, connection errors, temp-file
creation) can produce `False`. Row figures in the summary are `n_live_tup`
estimates, not loaded counts. Nothing compares source and target counts.

**Step 5 — offset** (`:2786-2821`), when `--offset_table` is set and not
`--schema_only`. On a driver connection to `default`:

```sql
CREATE DATABASE IF NOT EXISTS `<offset_db>`
CREATE TABLE IF NOT EXISTS `<offset_db>`.`<offset_tbl>` (
    `id` String, `offset_key` String, `offset_val` String,
    `record_insert_ts` DateTime, `record_insert_seq` UInt64
) ENGINE = ReplacingMergeTree(record_insert_seq) ORDER BY offset_key
INSERT INTO <offset_table> (id, offset_key, offset_val, record_insert_ts, record_insert_seq)
VALUES ('<uuid3(NAMESPACE_URL, key)>', '["<connector_name>",{"server":"embeddedconnector"}]',
        '{"transaction_id":null,"lsn_proc":<lsn_int>,"lsn":<lsn_int>,"ts_usec":<now µs>}', now(), 1)
```

An `offset_table` without a `.` skips the CREATE statements with a warning; the
INSERT is still attempted. Without `--offset_table`, a WARNING prints the LSN for
manual hand-off. The log tells the operator to start the connector with
`snapshot.mode=never`. Divergences from the Java table are covered in §3.14 and
D-13.05-26.

**Exit**: `postgres_dumper finished successfully` and `sys.exit(0)`.

The ordered event log below was reproduced offline by driving `main()` with
every PostgreSQL, ClickHouse and shell call mocked. One table had 10 rows; the
other had 2,000,000 rows with PK `id`.

```text
  2 PG #1: SELECT pg_last_wal_replay_lsn() / pg_current_wal_lsn()  -> 9C9/21AE7C20
  3 PG #1: SHOW timezone
  4 PG #1: get_tables(schema='public', include='.', exclude=None)
  7 PG: privilege checks (incl. list pg_replication_slots, no create)
  8 PG: ensure public.sink_connector_heartbeat
  9 PG close #1
 10 CH: CREATE DATABASE IF NOT EXISTS `mydb`
 13 CH: CREATE TABLE IF NOT EXISTS `mydb`.`t_small` (...)
 16 CH: CREATE TABLE IF NOT EXISTS `mydb`.`t_big` (...)
 21 PG #6: WITH ranked AS (SELECT "id", ntile(4) OVER (ORDER BY "id") ...
 31 SHELL pipe: COPY (SELECT "id", "v" FROM "public"."t_small") TO STDOUT WITH (FORMAT CSV, HEADER false, FORCE_QUOTE *)
 34 SHELL pipe: COPY (... "public"."t_big" WHERE "id" < '500001') ...
 35 SHELL pipe: COPY (... WHERE "id" >= '500001' AND "id" < '1000001') ...
 36 SHELL pipe: COPY (... WHERE "id" >= '1000001' AND "id" < '1500001') ...
 37 SHELL pipe: COPY (... WHERE "id" >= '1500001') ...
 38 CH: CREATE DATABASE IF NOT EXISTS `offsets`
 39 CH: CREATE TABLE IF NOT EXISTS `offsets`.`replica_source_info_mydb` (...)
 40 CH: INSERT INTO offsets.replica_source_info_mydb (...)
```

### 3.6 Primary-key range segmentation

`get_pk_type_is_segmentable` (`:264-304`) accepts exactly one PK column whose
`information_schema.columns.data_type` (string-built query) is one of `integer,
bigint, smallint, serial, bigserial, text, character varying, varchar,
character, char, uuid, timestamp without time zone, timestamp with time zone,
date, real, double precision, numeric`. `serial`, `bigserial`, `varchar` and
`char` never appear as a `data_type`, so those entries are dead. Composite PKs and
other types (for example a `bytea` or enum PK) load as one stream.

`get_pk_range_boundaries` (`:214-261`) runs a full sort:

```sql
WITH ranked AS (SELECT "<pk>", ntile(<n>) OVER (ORDER BY "<pk>") AS seg FROM "<schema>"."<table>")
SELECT seg, MIN("<pk>") AS seg_min, MAX("<pk>") AS seg_max FROM ranked GROUP BY seg ORDER BY seg
```

Segment i covers `[seg_min(i), seg_min(i+1))`. The first segment has no lower
bound and the last has no upper bound, so rows inserted after the boundary query
still fall into some segment. Coverage has no gap. An empty table gives
`[(None, None)]`, which becomes `WHERE TRUE`. `build_segment_where_clause`
(`:539-562`) renders `"<pk>" >= '<str(v)>' AND "<pk>" < '<str(v)>'`, doubling single
quotes, so every bound is an untyped literal cast to the column type.
Reproduced renderings: int `'500001'`, timestamptz
`'2024-03-10 02:30:00+00:00'` (explicit offset, so it is exact under `PGTZ=UTC`),
uuid `'00000000-0000-0000-0000-000000000005'`, numeric `'1E+3'`, text
`'O''Brien'`. Range comparison and ntile ordering both use the column's collation,
so text ranges are consistent. Each segment is its own `psql` session and its own
statement snapshot (§3.15).

### 3.7 ClickHouse DDL as built (`build_create_table`, `postgres_type_mapper.py:363-411`)

```sql
CREATE TABLE IF NOT EXISTS `<ch_db>`.`<ch_table>`
(
    `<col>` <ch_type>,                                   -- per column, ordinal order; direct override replaces ch_type
    `<col>_<normalized type>` <Type> ALIAS <expr>,       -- per alias override (looked up by the CH table name, D-13.05-18)
    `_version` UInt64 DEFAULT 0,
    `is_deleted` UInt8 DEFAULT 0
)
ENGINE = ReplacingMergeTree(_version, is_deleted)
ORDER BY (`pk1`, `pk2`)          -- or ORDER BY (tuple()) when the table has no PRIMARY KEY (D-13.05-5)
SETTINGS index_granularity = 8192
```

- `ch_type` comes from `pg_type_to_ch` (§3.8), wrapped as `Nullable(...)` when
  `is_nullable = 'YES'`.
- No `PARTITION BY`; no `PRIMARY KEY` clause distinct from `ORDER BY`. Column
  defaults, identity and generated expressions, comments and CHECK constraints
  are not carried over.
- Identifiers are wrapped in backticks without escaping. A backtick in a name
  breaks the DDL (D-13.05-22).
- **Version / delete columns**: snapshot rows do not list `_version` or
  `is_deleted` in the INSERT, so they get `0` and `0`. Any CDC row (version > 0)
  wins over a snapshot row on merge or `FINAL`. Two snapshot copies of one key
  (a re-run without `--truncate`) have equal versions, and the one inserted last
  survives. Contrary to the module header (`postgres_type_mapper.py:21`) and the
  `build_ch_create_table_ddl` docstring in 13.02's file, `_version` is **not**
  `Nullable` (D-13.05-34).
- `CREATE TABLE IF NOT EXISTS` is a no-op against an existing table of a
  different shape. The existing table is used as is. Only override columns are
  reconciled.

### 3.8 Complete PostgreSQL → ClickHouse type mapping

The dumper maps the **`information_schema.columns.data_type`** string through
`pg_type_to_ch(data_type, precision=numeric_precision, scale=numeric_scale,
nullable, pg_server_timezone)` (13.02's file, `postgres.py:142-210`). `udt_name`
is fetched but never consulted. `map_pg_type` (`postgres_type_mapper.py:188-301`)
is used **only** by the DDL parser and the unit test. Both columns were
reproduced offline by calling the functions:

| PostgreSQL type (as `data_type` reports it) | `ch-pg-dump` DDL type (`pg_type_to_ch`) | Load SELECT expression (`build_select_columns`) | CSV text that `psql` emits | `map_pg_type` (parser) |
|---|---|---|---|---|
| `smallint` / int2 / smallserial | `Int16` | `"c"` (implicit String→Int cast on INSERT) | `"12"` | `Int16` |
| `integer` / int4 / serial / `GENERATED ... AS IDENTITY` | `Int32` | `"c"` | `"12"` | `Int32` (`serial` → `Int32`) |
| `bigint` / int8 / bigserial / identity | `Int64` | `"c"` | `"12"` | `Int64` |
| `numeric(p,s)` (precision, scale from information_schema) | `Decimal(p, s)`. p > 76 or negative s (PG 15+) produce a type ClickHouse rejects (D-13.05-21) | `"c"` (String→Decimal cast; `NaN`/`Infinity` fail the INSERT) | `"123.45"`, `"NaN"`, `"Infinity"` (PG 14+) | `Decimal(p, s)`; a negative scale → `String` |
| `numeric(p)` | `Decimal(p, 0)` | `"c"` | | `Decimal(p, 0)` |
| `numeric` (unconstrained) | **`String`** (exact text) | `"c"` | `"0.1000000000000000000001"` | **`Decimal(18, 6)`** (truncates; D-13.05-27) |
| `real` / float4 | `Float32` | `"c"` | shortest round-trip text (PG 12+), `"NaN"`, `"Infinity"`, `"-Infinity"` | `Float32` |
| `double precision` / float8 / float | `Float64` | `"c"` | as above | `Float64` |
| `money` | `String` | `"c"` | locale-formatted, e.g. `"$1,234.56"` (`lc_monetary`) | `String` |
| `boolean` | `UInt8` | `multiIf("c"='t',1,"c"='true',1,"c"='1',1,0)`, wrapped in `if(isNull("c"),null,...)` when Nullable | `"t"` / `"f"` | `UInt8` |
| `text`, `character varying[(n)]`, `character(n)`, `"char"`, `name` | `String` | `"c"` | char(n) blank-padded as PostgreSQL prints it | `String` |
| `bytea` | `String` | `"c"` | `"\x48656c6c6f"` (hex; `bytea_output` is inherited, §3.9.4) | `String` |
| `uuid` | `String` | `"c"` | canonical lower-case | `String` |
| `json` / `jsonb` | `String` | `"c"` | raw text (jsonb normalised) | `String` |
| arrays (`data_type = 'ARRAY'`) | `String` | `"c"` | array literal `"{1,2,NULL}"` | `String` (for `x[]` text) |
| `USER-DEFINED` (enum, domain over an extension type, `hstore`, `citext`, PostGIS `geometry`) | `String`, plus WARNING `Unknown PG type 'USER-DEFINED'` | `"c"` | enum label, hstore literal, hex EWKB | `String` |
| domain over a built-in type | as its base type (information_schema reports the base `data_type`) | | | |
| `date` | `Date32` | `toDate32OrNull("c")` | ISO `"2024-03-10"` under `DateStyle` ISO; `"infinity"`, `"... BC"` | `Date32` |
| `time [p] without time zone` | `String` | `"c"` | `"12:34:56.789"` | `String` |
| `time [p] with time zone` | `String` | `"c"` | `"12:34:56+05:30"` (the stored offset) | `String` |
| `timestamp [p] without time zone` | **`DateTime64(6, '<server TimeZone>')`** (bare `DateTime64(6)` if no zone) | `parseDateTime64BestEffortOrNull("c", 6)` evaluated with `--session_timezone=UTC` | wall clock `"2024-03-10 02:30:00.123"` (PGTZ does not change it) | **`DateTime64(6, 'UTC')`** |
| `timestamp [p] with time zone` | `DateTime64(6, 'UTC')` | `parseDateTime64BestEffortOrNull("c", 6)` | `"2024-03-10 02:30:00.123+00"` (PGTZ=UTC) | `DateTime64(6, 'UTC')` |
| `interval` | `String` | `"c"` | `IntervalStyle`-dependent, e.g. `"1 day 02:00:00"` | `String` |
| `inet`, `cidr`, `macaddr`, `macaddr8` | `String` | `"c"` | text | `String` |
| `point`, `line`, `lseg`, `box`, `path`, `polygon`, `circle` | `String` | `"c"` | text | `String` |
| `bit(n)`, `bit varying(n)` | `String` | `"c"` | `"0101"` | `String` |
| `tsvector`, `tsquery`, `xml` | `String` | `"c"` | text | `String` |
| range and multirange types | `String` | `"c"` | `"[1,10)"` | `String` |
| `oid`, `xid`, `cid`, `tid`, `pg_lsn`, `jsonpath`, anything else | `String` (unknown types log a WARNING) | `"c"` | text | `String` |

Notes:
- Precision is ignored for temporal types: every timestamp is `DateTime64(6)`;
  `datetime_precision` is never read.
- Generated (`STORED`) columns are listed by information_schema and are
  COPY-able, so they are loaded as plain columns.
- `map_pg_type` strips modifiers only through the regex `^([a-z][a-z ]*?)\s*\(...\)$`.
  Two spaces (`double  precision`) or a quoted name fall back to `String`.
  `map_udt_type` would map `_int4` (an array) to `Int32`, but it is never called.
- The time-zone annotation of `timestamp without time zone` and the parse zone
  used by the INSERT differ. The wall-clock text is parsed as UTC (the session
  zone), so the stored instant is the wall clock read as UTC. Displayed in the
  column's zone (`America/Chicago`, say), it reads several hours off. The CDC
  path renders zone-less digits in the column's zone (spec 07.03 §3.1.1, §3.1.3),
  so CDC rows and snapshot rows of the same table disagree by the zone offset
  whenever the server `TimeZone` is not UTC (D-13.05-8).

### 3.9 Value transfer (streaming)

#### 3.9.1 Source side (`build_psql_copy_cmd`, `:311-367`)

COPY text, written to `/tmp/pg_copy_<random>.sql` (mode 0600, `delete=False`):

```sql
COPY (SELECT "<c1>", "<c2>", ... FROM "<schema>"."<table>"[ WHERE <segment>]) TO STDOUT WITH (FORMAT CSV, HEADER false, FORCE_QUOTE *)
```

Shell command:

```bash
PGPASSWORD='<pw>' PGTZ=UTC <PG_BIN_DIR/>psql -h <host> -p <port> -U <user> -d "<db>" -f /tmp/pg_copy_<random>.sql
```

- **NULL vs empty string**: `FORCE_QUOTE *` quotes every non-NULL value, and
  NULL is written as an unquoted empty field. So NULL is `,,` and `''` is `,"",`.
- **Embedded newline, comma, quote**: CSV quoting; `"` is doubled. **Tab** and
  **backslash** are literal (CSV has no backslash escapes).
- **Time zone**: `PGTZ=UTC` sets the session `TimeZone`, so `timestamptz` prints
  with `+00`. It does not affect `timestamp without time zone`, `date`, `time` or
  `timetz`.
- **Not pinned**: `DateStyle`, `IntervalStyle`, `bytea_output`,
  `extra_float_digits`, `lc_monetary`, `client_encoding`, `statement_timeout`
  (PGOPTIONS is not set) and `ON_ERROR_STOP` (D-13.05-2, D-13.05-10). `psql` is
  not run with `-q`. Whether it prints a `COPY n` command tag to stdout after
  the data, which would reach `clickhouse-client` as a malformed last CSV
  line, **could not be determined offline**. If it does, the resulting INSERT
  error is hidden by D-13.05-1. Error text goes to stderr and is merged into the
  log at INFO.
- Identifiers are wrapped in `"` without doubling embedded `"` (D-13.05-22).

#### 3.9.2 ClickHouse side (`build_ch_insert_cmd`, `:370-428`)

INSERT text, written to `/tmp/ch_insert_<random>.sql`. Reproduced for a
9-column table:

```sql
INSERT INTO "mydb"."orders"("id", "flag", ..., "Mixed Case")
SELECT "id", if(isNull("flag"), null, multiIf("flag" = 't', 1, "flag" = 'true', 1, "flag" = '1', 1, 0)), ...,
       toDate32OrNull("d"), parseDateTime64BestEffortOrNull("ts", 6), parseDateTime64BestEffortOrNull("tstz", 6), "amt", "raw", "Mixed Case"
FROM input('"id" Nullable(String), "flag" Nullable(String), ..., "Mixed Case" Nullable(String)')
SETTINGS format_csv_null_representation='', input_format_csv_empty_as_default=0 FORMAT CSV
```

Shell command (reproduced):

```bash
clickhouse-client [--config-file '<f>'] -h ch-host --port 9000 -u default [--password '<pw>'] [--secure] \
  --session_timezone=UTC --throw_if_no_data_to_insert=0 --max_partitions_per_insert_block=1000 \
  --queries-file /tmp/ch_insert_<random>.sql; rm -f /tmp/ch_insert_<random>.sql
```

- Every input column is `Nullable(String)`, and the SELECT converts. Non-temporal,
  non-boolean columns rely on ClickHouse's implicit String→target cast during
  `INSERT ... SELECT`.
- An empty unquoted CSV field becomes NULL through
  `format_csv_null_representation=''`, and a quoted `""` stays an empty string.
  This relies on ClickHouse CSV parsing semantics, which were **not executed**
  offline (no ClickHouse in the harness; GAP in §5).
- Single quotes inside column names are not escaped in the `input('...')`
  structure literal (D-13.05-22).
- `--throw_if_no_data_to_insert=0` makes an empty stream (zero rows, or a psql
  that produced nothing) a successful insert. Together with D-13.05-1 and
  D-13.05-2, a COPY that failed at once yields an empty table and `Done`.
- `--session_timezone=UTC` is the zone that `parseDateTime64BestEffortOrNull`
  and `toDate32OrNull` parse in when the text has no offset (§3.8, D-13.05-8).

#### 3.9.3 Exit status of the pipe

`pipe_cmd = "set -o pipefail; " + psql_cmd + " | " + ch_cmd` (`:497`), where
`ch_cmd` already ends with `; rm -f <file>`. Bash returns the status of the last
list element, `rm -f`, which is 0. Reproduced with a fake `psql` (one row, then
exit 2) and a fake `clickhouse-client` (exit 27) first on `PATH`:

```text
LOG INFO psql: error: server closed the connection unexpectedly
LOG INFO Code: 27. DB::Exception: Cannot parse input
LOG INFO [t1] Done in 0.0s (~10 rows, ~228 rows/s)
load_table result (label, rows, elapsed, success) = ('t1', 10, 0.04389381408691406, True)
rc without trailing rm -f = 27
rc with trailing rm -f    = 0
```

The comment at `:493-496` ("a failure in psql ... propagates as the exit code")
describes behaviour that the trailing `rm` defeats (D-13.05-1).

#### 3.9.4 Conversion hazards by value class

| Value | Snapshot result | Evidence |
|---|---|---|
| `timestamptz 'infinity'` / `'-infinity'` | `parseDateTime64BestEffortOrNull` → NULL. In a NOT NULL column, ClickHouse's `insert_null_as_default` stores the type default (epoch 0). CDC clamps to the DateTime64 limits (07.03 §3.3) | code-read (D-13.05-9) |
| `date 'infinity'`, BC dates, values outside the Date32/DateTime64 range | NULL or default, as above | code-read (D-13.05-9) |
| `DateStyle` set to `SQL`, `Postgres` or `German` on the server | non-ISO text, best-effort parsing (DMY/MDY ambiguity) or NULL | code-read (D-13.05-10) |
| `numeric 'NaN'` / `'Infinity'` into `Decimal(p,s)` | the INSERT fails, which D-13.05-1 hides | code-read |
| float `NaN` / `±Infinity` | expected to parse into Float32/64 (07.02 measured that these are valid values); String-cast parsing not executed offline | GAP |
| `bytea` | hex text `\x..` stored verbatim in `String` | code-read |
| `money` | locale text | code-read |
| text containing a newline, tab, comma or `"` | safe under CSV quoting | code-read |

### 3.10 Privilege validator (`validate_postgres_privileges`, `:1254-1514`)

The validator runs on the main connection; every failed probe is followed by
`rollback()`. Critical items cause exit 1 in `main`; warnings are only logged.

| # | Probe | Pass | Missing → |
|---|---|---|---|
| 1 | `SHOW wal_level` | `logical` | critical |
| 2 | `SELECT rolcanlogin FROM pg_roles WHERE rolname=%s` | true | critical (also when the role is absent) |
| 3 | `SELECT rolreplication FROM pg_roles WHERE rolname=%s` | true | **critical**. The COPY does not need it, and managed services that grant replication by role membership fail this probe (D-13.05-24) |
| 4 | `has_schema_privilege(%s,%s,'USAGE')` | true | critical |
| 5 | `has_table_privilege(%s,'"s"."t"','SELECT')` per table | true | critical (aggregated) |
| 6 | `has_schema_privilege(%s,%s,'CREATE')` | true | warning (heartbeat) |
| 7 | `has_table_privilege(%s,'pg_catalog.<t>','SELECT')` for pg_index, pg_attribute, pg_class, pg_namespace | true | critical |
| 8 | `SELECT slot_name, active FROM pg_replication_slots` | lists the slots | INFO "No replication slots found. The connector will attempt to create one". No slot is created and no LSN is compared (D-13.05-3) |
| 9 | `SELECT pubname FROM pg_publication` | lists them | warning |

The `config={'connector_name': ...}` argument is accepted and unused.

### 3.11 Heartbeat bootstrap on the source (`ensure_heartbeat_table`, `:1173-1247`)

The table is always `public.sink_connector_heartbeat`, whatever schemas were
selected:

```sql
SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='sink_connector_heartbeat'
CREATE TABLE IF NOT EXISTS public.sink_connector_heartbeat (id INTEGER PRIMARY KEY DEFAULT 1, ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now())
INSERT INTO public.sink_connector_heartbeat (id, ts) VALUES (1, now()) ON CONFLICT (id) DO NOTHING
GRANT SELECT, INSERT, UPDATE ON public.sink_connector_heartbeat TO "<pg_user>"
```

The connection is in autocommit, so each statement commits at once and the
explicit `commit()` calls do nothing. The GRANT goes to the creating user, who
already owns the table. Every failure is a WARNING. This runs even with
`--dry_run` (D-13.05-25).

### 3.12 Benchmark mode (`run_benchmark`, `:886-1091`)

`--benchmark --benchmark_table T` reads the metadata of `T` in the first
`--pg_schema` entry (with overrides). For each of `streaming` and `psql-copy` it
drops and recreates `` `<ch_db>`.`_benchmark_<strategy>_<T>` ``, loads the table,
logs `count()`, and drops the table again. The streaming arm loads the **whole**
table; only the `psql-copy` arm applies `LIMIT --benchmark_limit` (its dump goes
to `/tmp/benchmark_psql_copy`, which is not cleaned up). The run prints a
comparison table and a `WINNER`. It always exits 0, ignores `--dry_run`, and
leaks the connection used for `get_server_timezone` (D-13.05-25). The arms report
`OK` under the same exit-status masking (D-13.05-1, D-13.05-17).

### 3.13 Exit codes

| Code | When |
|---|---|
| 0 | Success, including "all tables already have data" (`--skip_existing`); `--benchmark` always; a load whose pipe failed (D-13.05-1, D-13.05-2) |
| 1 | No PostgreSQL password; `--benchmark` without a table; no schemas; no tables; a privilege check failed; a load job raised before or around the pipe; any exception inside the main `try` (DDL failure, override mismatch, `TypeError` of the disk strategies); `KeyboardInterrupt` (`os._exit(1)`); an uncaught `AssertionError` (missing binary), `ValueError` (template) or config/YAML error raised before the `try` (Python's default 1, with a traceback) |
| 2 | argparse errors: an unknown flag (for example the README's `--pg-host`), a bad `int`, an invalid `--strategy`, or a required value missing after the config merge |

Every `sys.exit(1)` inside the `try` is caught as `SystemExit`, logged as
`Received interrupt`, and turned into `os._exit(1)` (D-13.05-33). `os._exit`
skips interpreter cleanup but kills the worker threads; child `psql` and
`clickhouse-client` processes started by a worker are not signalled and run to
completion if the tool is killed with SIGTERM.

### 3.14 CDC hand-off contract versus the Java offset store

| Aspect | Python (`build_offset_insert`, `ensure_offset_database_and_table`) | Java (`DebeziumOffsetStorage`, spec 09.03) |
|---|---|---|
| `offset_key` | `json.dumps([name, {"server":"embeddedconnector"}], separators=(',',':'))`, byte-identical to `String.format("[\"%s\",{\"server\":\"%s\"}]", ...)` for plain names | same |
| `lsn` / `lsn_proc` | full 64-bit `hi<<32 \| lo` | full 64-bit (`updateLsnInformation`) |
| `id` | `uuid3(NAMESPACE_URL, key)`. Reproduced: `39e75cb3-...` | `UUID.nameUUIDFromBytes(key)` (MD5 without a namespace). Reproduced emulation: `b41aeaf2-...`. The docstring's claim that they match is false. Harmless, because both tables are `ORDER BY offset_key` (D-13.05-34) |
| Table DDL | `ReplacingMergeTree(record_insert_seq) ORDER BY offset_key`, no `_version` | `offset.storage.jdbc.table.ddl`, for example `... _version UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9))) ENGINE=ReplacingMergeTree(_version) ORDER BY offset_key`, or KeeperMap. If the dumper creates the table first, the Java `CREATE ... IF NOT EXISTS` is a no-op and the connector runs on the dumper's shape (D-13.05-26) |
| Read | n/a | `select offset_val from T FINAL where offset_key='...' order by record_insert_ts desc, record_insert_seq desc limit 1`. The dumper row (seq 1, `now()`) is the newest at hand-off |
| Escaping | `'<offset_key>'` and `'<payload>'` interpolated, so a `'` in `--connector_name` breaks the INSERT (reproduced) | prepared statement |

What the hand-off requires and the dumper does not ensure: a logical replication
slot whose `confirmed_flush_lsn` is at or before the recorded LSN, created
**before** Step 1 so that the WAL is retained. PostgreSQL starts logical decoding
at the slot's confirmed position when the requested start is earlier. A slot
created by the connector after the dump therefore starts at its creation point,
and every change committed after a table was read and before that point is lost
(D-13.05-3; compare FM-09.03-6).

### 3.15 Consistency model as built

- Each COPY is a single statement in its own `psql` session, so it sees one
  statement-level snapshot of its table or segment. Different tables, and
  different segments of one table, see different snapshots, taken at
  different, unordered times between Step 1 and the end of Step 4.
- The LSN is read before any data is read, on a different autocommit session,
  outside any exported snapshot. No `pg_export_snapshot()`,
  `SET TRANSACTION SNAPSHOT`, `REPEATABLE READ`, or
  `CREATE_REPLICATION_SLOT ... EXPORT_SNAPSHOT` appears anywhere in the module
  (verified by search).
- Consequence, if the slot retains WAL from the LSN: every change in `[LSN,
  read time of each row]` is both in the snapshot and replayed by CDC. Replayed
  events carry `_version > 0` and win over the snapshot rows (`_version = 0`),
  and replay ends at the newest state, so each row converges after a transient
  regression to older replayed states. The design tolerates **duplication**,
  not **loss**.
- Loss arises when the WAL after the LSN is not retained by a slot (D-13.05-3),
  when a table is skipped or partly loaded (D-13.05-4, D-13.05-1, D-13.05-2,
  D-13.05-6), when rows collapse (D-13.05-5), or when two tables merge
  (D-13.05-7).

### 3.16 Legacy versus packaged copies

| File | Legacy tree | Packaged tree | Used by |
|---|---|---|---|
| PostgreSQL dumper | absent | `ch_sink_tools/db_dump/postgres_dumper.py` | `ch-pg-dump`. No Dockerfile ships it (the three Dockerfiles are MySQL/checksum images) |
| type mapper | absent | `ch_sink_tools/db_load/postgres_type_mapper.py` | dumper (builders), parser (`map_pg_type`), legacy-dir test |
| DDL parser | absent (the README, the module header comments and `build_grammars.sh` still name the legacy `db_load/postgres_parser` directory) | `ch_sink_tools/db_load/postgres_parser/` | nothing |
| PostgreSQL helpers | absent | `ch_sink_tools/db/postgres.py` (13.02) | dumper, PG checksum tools (13.07) |
| test | `db_load/tests/test_postgres_type_mapper_unit.py` | — | imports `ch_sink_tools...` (the packaged copy) |

So there is no behavioural divergence to report. One packaging fact matters:
`build_grammars.sh` regenerates the PostgreSQL parser into the **legacy** path
`db_load/postgres_parser` (`build_grammars.sh:15-29`), not into the packaged
directory, and the parser package imports `antlr4`, which `pyproject.toml` lists
only under the `[mysql]` extra (D-13.05-34).

### 3.17 Disk-based strategies (as written; unreachable from `main`)

**psql-copy**: `psqlcopy_dump_table` (`:626-694`) runs
`PGPASSWORD='..' PGTZ=UTC psql ... -f /tmp/pg_dump_copy_<r>.sql | gzip -1 > <dump_dir>/<table>.csv.gz`
with `COPY (SELECT ... [LIMIT n]) TO STDOUT WITH (FORMAT CSV, HEADER true, FORCE_QUOTE *)`.
There is **no `pipefail`**, so a psql failure leaves a small gzip and returns
normally (reproduced: 28 bytes, no exception; D-13.05-17). The file name has no
schema, so `a.t` and `b.t` overwrite each other. `psqlcopy_load_table`
(`:697-746`) runs `set -o pipefail; gunzip -c <f> | tail -n +2 | <ch_cmd>` and is
masked by D-13.05-1. `main` calls the dump with an unknown `dry_run=` keyword
(`:2483-2494`) and the load with `ch_table=` and without `columns_meta`
(`:2542-2555`), so the run stops with `TypeError` and exit 1 (reproduced;
D-13.05-15).

**pgdump**: `pgdump_dump_tables` (`:749-795`) first runs
`shutil.rmtree(<dump_dir>)` with no guard (D-13.05-14), then
`pg_dump -h -p -U -d --format=directory --jobs=N --no-owner --no-privileges --data-only --schema <s> -f <dir> [-t <s>."<t>"]...`
(an argument vector with `PGPASSWORD` in the environment, the one
credential-safe command in the module). `pgdump_load_table` (`:798-879`) runs
`pg_restore --data-only -t <table> <dir> 2>/dev/null | awk ... | <ch_cmd>`. The
awk program does not decode COPY-text escapes, and it drops data rows that begin
with `COPY `, `SET `, `SELECT `, `--`, `\.`, or that are empty. Reproduced:

```text
COPY-text in : '1\tline1\\nline2\t\\N\n2\tback\\\\slash\ttab\\there\n--x\tdash-led key\tv\nSET \tset-led key\tv\n\\.\n'
CSV out      : '"1","line1\\nline2",\n"2","back\\\\slash","tab\\there"\n'
```

The escaped newline arrives as the two characters `\n`, the escaped backslash
stays doubled, and the rows whose keys are `--x` and `SET ` are gone
(D-13.05-12). `main` calls the dump with `pg_schemas=`, `dump_jobs=` and
`dry_run=` (`:2366-2376`) and the load with `ch_table=` and `pg_schema=`
(`:2397-2410`). Both raise `TypeError`: the first is caught by the outer
handler; the second fails inside a worker, so the table is counted as FAILED
and the run exits 1 (reproduced; D-13.05-16).

### 3.18 DDL parser (`parse_postgres_ddl`)

`parse_postgres_ddl(source)` builds `PostgreSQLLexer` → `CommonTokenStream` →
`PostgreSQLParser.root()`, with both default error listeners replaced by
`_PostgreSQLErrorListener`, which raises `SyntaxError("PostgreSQL DDL parse error
at line L col C: msg")` on the first syntax error. It then walks the tree with
`CreateTablePostgreSQLParserListener` and returns `listener.get_result()`. If
several statements are given, the last matching statement wins (reproduced:
`CREATE TABLE a (x int); DROP TABLE a` gives `drop_table`). `_main(argv)`
(`python -m ...postgres_parser <file>`) prints the result as JSON and sets the
root logger to DEBUG. **No module in the tree imports it.**

Result shapes (reproduced):

| Input | `action` and fields |
|---|---|
| `CREATE TABLE public.orders (id bigint PRIMARY KEY, amount numeric(10,2) NOT NULL, ...)` | `create_table`; `columns` (column_name, pg_type text, ch_type via `map_pg_type`, nullable, primary_key); `primary_keys`; `clickhouse_sql` |
| `ALTER TABLE orders ADD COLUMN amount numeric(10,2) NOT NULL` | `alter_table`; `alter_cmds=[{op:add_column, column_name, pg_type, ch_type:'Decimal(10, 2)'}]` (no nullability) |
| `ALTER TABLE orders DROP COLUMN note` / `ALTER COLUMN amount TYPE ...` / `ALTER COLUMN amount SET NOT NULL` | **`AttributeError: 'list' object has no attribute 'start'`**. `Alter_table_cmdContext.colid()` returns a list and `_text()` expects a context (`CreateTablePostgreSQLParserListener.py:246-266`; D-13.05-28) |
| `ALTER TABLE orders RENAME TO archived_orders` | `rename_table`, `new_name='archived_orders'` |
| `ALTER TABLE orders RENAME COLUMN note TO memo` | **`rename_table`, `new_name='memo'`**; also for `RENAME CONSTRAINT` (D-13.05-30) |
| `DROP TABLE IF EXISTS public.a, public.b` | `drop_table`, `table_name='public.a, public.b'` (one string) |
| `DROP INDEX ...`, `CREATE INDEX ...` | `unknown` |
| any `CHECK (...)` constraint | **`NameError: name 'this' is not defined`**. The generated parser contains the Java-style action `this.OnlyAcceptableOps()` (`PostgreSQLParser.py:58290`, also `:36010` `this.ParseRoutineBody()`; D-13.05-29) |
| syntax error | `SyntaxError` |

`clickhouse_sql` for `create_table` is `CREATE TABLE <table as written> (<cols>,
\`_version\` Nullable(UInt64), \`is_deleted\` UInt8 DEFAULT 0) ENGINE =
ReplacingMergeTree(_version, is_deleted) ORDER BY (<pk names unquoted> | tuple())`.
Its defects (D-13.05-31): PK columns are `Nullable` unless `NOT NULL` is written,
and a nullable column then sits in the sorting key; the version column is
`Nullable`; a table-level `PRIMARY KEY (a, b)` is ignored, giving `ORDER BY
(tuple())`; quoted identifiers keep their double quotes inside backticks
(`` `"Id"` ``); a schema-qualified name becomes a ClickHouse `database.table`;
`DEFAULT 'NOT NULL'` makes a column non-nullable because the constraint text is
substring-matched. The README's "Known Limitations" item 1 (`numeric(p,s)` maps
to `String`) is outdated: it maps to `Decimal(p, s)` (D-13.05-34).

### 3.19 Public function reference (dump module)

| Function | Inputs → outputs | Side effects |
|---|---|---|
| `pg_bin(name)` | name → `PG_BIN_DIR/name` or name | — |
| `check_program_exists(name)` | → bool | runs `/usr/bin/which` |
| `run_command(cmd)` | shell string → rc as a **string** | bash child; logs every output line at INFO |
| `run_quick_command(cmd)` | → (rc, stdout) | unused |
| `filter_tables_by_regex(tables, inc, exc)` | list → list (`re.search`) | — |
| `ensure_ch_database(conn, db, dry_run)` | — | CREATE DATABASE; swallows errors |
| `create_ch_table(conn, db, t, cols, pk, dry_run, override_config, schema, pg_database)` | — | CREATE TABLE |
| `drop_ch_table` / `truncate_ch_table` | — | DROP / TRUNCATE (`IF EXISTS`) |
| `check_ch_table_has_data(conn, db, t)` | → (exists, count); `(False, 0)` on any error | EXISTS, count() |
| `get_pg_approx_row_count(conn, s, t)` | → `n_live_tup` or -1 | — |
| `get_pk_type_is_segmentable(conn, s, t, pks)` | → (bool, col) | information_schema query |
| `get_pk_range_boundaries(conn, s, t, col, n)` | → list of (lo, hi) | ntile query (full sort) |
| `build_segment_where_clause(col, lo, hi)` | → SQL text or `TRUE` | — |
| `build_psql_copy_cmd(...)` | → (cmd, tmpfile) | creates `/tmp/pg_copy_*` |
| `build_ch_insert_cmd(...)` | → cmd | creates `/tmp/ch_insert_*` |
| `load_table(...)` | → (label, approx_rows or -1, seconds, success) | PG metadata connection; pipe |
| `psqlcopy_dump_table`, `psqlcopy_load_table`, `pgdump_dump_tables`, `pgdump_load_table` | §3.17 | files, rmtree, pipes |
| `run_benchmark(args, ...)` | — | §3.12 |
| `ensure_offset_database_and_table(conn, offset_table, dry_run)` | — | CREATE DATABASE/TABLE; re-raises |
| `write_lsn_offset(conn, offset_table, lsn_int, connector_name, dry_run)` | — | INSERT |
| `ensure_heartbeat_table(conn, user)` | — | source DDL/DML (§3.11) |
| `validate_postgres_privileges(conn, user, schema, tables, config)` | → bool | §3.10 |
| `parse_sink_connector_config(cfg)` / `load_config_file(path)` / `merge_config_with_args(args, cfg)` | §3.3 | — |
| `postgres_type_mapper.build_insert_structure(cols)` | → `"c" Nullable(String), ...` | — |
| `postgres_type_mapper.build_select_columns(cols)` | → SELECT list (§3.9.2); checks the `pg_type` key for `boolean`/`bool` | — |
| `postgres_type_mapper.build_offset_insert(table, lsn_int, connector_name)` | → INSERT text (§3.5) | — |

## 4. Invariants Preserved

- **Snapshot rows never outrank CDC rows**: all snapshot rows have `_version = 0`
  and `is_deleted = 0` (`postgres_type_mapper.py:358-359`, plus the INSERT column
  list without them), so any replayed or live CDC event wins on merge or `FINAL`.
  This makes duplication between the snapshot and CDC replay harmless **for keyed
  tables**. It does not hold for keyless tables (D-13.05-5).
- **LSN encoding agrees with the Java store**: `get_standby_lsn` and
  `updateLsnInformation` both compute `(hi << 32) | lo`.
- **Offset key format agrees with the Java store** for connector names without
  `"`, `\` or `'`.
- **Segment coverage is complete**: open-ended first and last ranges, half-open
  middle ranges (`[lo, hi)`), and the boundaries come from distinct PK values.
- **The offset is written only after the load phase finishes without a detected
  failure**. A detected failure skips Step 5, but detection is almost absent
  (D-13.05-1, D-13.05-2), and `--skip_existing` writes it with nothing loaded
  (D-13.05-4).
- **No destructive ClickHouse statement runs unless asked**: `DROP` / `TRUNCATE`
  only with `--drop_existing` / `--truncate`; tables are otherwise created with
  `IF NOT EXISTS`. Exceptions: the benchmark drops the `_benchmark_*` tables it
  creates, and `pgdump_dump_tables` would `rmtree` `--dump_dir` (unreachable;
  D-13.05-14).
- Not preserved (recorded so that later fixes can restore them): consistent
  point-in-time snapshot (§3.15); WAL retention from the LSN (D-13.05-3);
  per-table row-count verification (GAP); credentials kept off argv and out of
  logs (D-13.05-13).

## 5. Verification Criteria

Existing offline tests (pass on 2.11.0: 52 passed for these two files with the
toolset venv):

- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_integer_family`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_boolean_and_float`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_numeric_with_precision_scale`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_numeric_precision_embedded_in_text`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_bare_numeric_safe_default`
  (asserts `Decimal(18, 6)`, which the dumper does **not** use; D-13.05-27)
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_timestamp_maps_to_datetime64_utc`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_time_and_interval_are_strings`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_varchar_and_text_are_strings`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_array_is_string`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_uuid_is_string`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_nullable_wrapping`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestMapPgType::test_unknown_type_falls_back_to_string`
- `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py::TestFilterTablesByRegex::test_include_only`,
  `::test_exclude_only`, `::test_include_and_exclude`, `::test_no_patterns_returns_all`
- `sink-connector/python/tests/test_naming.py::TestResolveCHNames::test_passthrough`
  and the other `TestRenderTemplate` / `TestValidateTemplate` /
  `TestResolveCHNames` / `TestFilterTablesByRegex` cases in that file.

Offline reproductions used for this spec. They are throwaway harnesses, not
committed, and each is described so that it can be rebuilt as a unit test:

1. **Type tables (§3.8)**: call `pg_type_to_ch` with every `data_type` /
   precision / scale combination in the table, and `map_pg_type` with the
   parser's type strings. The outputs are the two type columns of §3.8.
2. **Command builders (§3.9)**: call `build_insert_structure`,
   `build_select_columns`, `build_create_table`, `build_psql_copy_cmd`,
   `build_ch_insert_cmd`, `build_segment_where_clause` and `build_offset_insert`
   on a 9-column table. The outputs are quoted in §3.5–§3.9 and show the
   passwords on the command line and the trailing `; rm -f`.
3. **Exit masking (§3.9.3)**: put a fake `psql` (one row, exit 2) and a fake
   `clickhouse-client` (exit 27) first on `PATH` / `PG_BIN_DIR`, patch
   `get_postgres_connection`, `get_table_columns`, `get_table_pk` and
   `get_table_row_count` with `unittest.mock`, and call `load_table`:
   `success=True`. The same harness calls `psqlcopy_dump_table`, which returns
   normally after the failure.
4. **`main()` end to end (§3.5)**: patch every PostgreSQL helper,
   `clickhouse_connection`, `clickhouse_execute_conn`, `ch_table_exists`,
   `run_command`, `check_program_exists` and `os._exit`, then run the scenarios
   streaming, `--strategy psql-copy`, `--strategy pgdump`,
   `--strategy pgdump --load_only`, `--skip_existing --data_only`, a native
   config with `pg_schema: public`, and two schemas with `--ch_database`. They
   give the event logs and exit codes cited in §3.5, §3.17 and §6.
5. **Parser (§3.18)**: call `parse_postgres_ddl` on the statements listed there.
6. **Config/filter/LSN (§3.3, §3.5)**: `_debezium_list_to_regex`,
   `filter_tables_by_regex`, `parse_sink_connector_config`, `resolve_ch_names`,
   and `get_standby_lsn` with a mocked `execute_pg` (primary, promoted
   ex-standby).
7. **Overrides and awk (§3.17, D-13.05-11, D-13.05-18)**: `get_table_columns`
   with a mocked `execute_pg`, with and without overrides; `build_create_table`
   with a pass-through and a schema-prefixed CH table name; the `pgdump_load_table`
   awk program, captured through a patched `run_command` and fed COPY-text with
   escapes.

Acceptance criteria that the fixes must meet. Each is a GAP today:

- A failing `psql` or `clickhouse-client` makes `load_table` return
  `success=False`, and `main` exits 1 without writing the offset (harness 3 as a
  test).
- `main` creates, or verifies, a logical slot whose position is at or before the
  recorded LSN before reading any data, or reads all tables under one exported
  snapshot that matches the slot's consistent point.
- `--skip_existing` neither skips partially loaded tables nor writes a newer LSN
  than the oldest loaded data.
- After load, per-table `count()` equals the source `count(*)` read in the same
  snapshot (spec 13.07 provides the verification tools).
- `pg_type_to_ch` has its own unit tests; one mapper serves both DDL and parser.
- ClickHouse-side CSV NULL / empty-string handling and every SELECT conversion
  in §3.8 are exercised against a real ClickHouse (`clickhouse local`) in CI.

## 6. Failure Modes & Recovery

- **FM-13.05-1 A COPY or INSERT fails mid-stream (connection loss, statement timeout, parse or cast error, unknown setting, column mismatch)**
  - **Trigger**: any non-zero `psql` or `clickhouse-client` exit, including a server-side `statement_timeout` (the `psql` session does not get `statement_timeout=0`), a `numeric 'NaN'` into Decimal, a single quote in a column name, a missing table under `--data_only`, or a ClickHouse server that rejects `--session_timezone`.
  - **Behaviour**: the shell status is that of the trailing `rm -f` (`postgres_dumper.py:426`, `:497`, `:506`), so the job is logged `Done` with `success=True`. Even a correct pipeline would not see COPY errors, because `psql -f` without `ON_ERROR_STOP` exits 0 on SQL errors (`postgres_dumper.py:359-366`). The child's error text is logged at INFO only (`:134-136`). Rows inserted in blocks before the error stay; the rest are missing. Step 5 writes the offset.
  - **Detection**: none automatic. Grep the log for `DB::Exception`, `psql: error` or `ERROR:` lines between `Starting load` and `Done`; compare `count()` per table with the source count (13.07).
  - **Blast radius**: every table or segment whose pipe failed is silently short or empty, and CDC starts from the recorded LSN, so the missing rows never arrive.
  - **Recovery**: fix the cause, rerun with `--truncate` (or `--drop_existing`) for the affected tables (`--tables '^t$'`) while the connector is stopped, write the offset again, then verify counts.
  - **RTO**: unmeasured (no live databases in the offline harness); dominated by the reload time of the affected tables.
  - **Test**: `GAP: load_table with failing fake psql/clickhouse-client returns success=False (harness 3)`
  - **DEFECT**: D-13.05-1 (exit status masked by `; rm -f`) and D-13.05-2 (no `ON_ERROR_STOP`).

- **FM-13.05-2 No replication slot exists when the dump starts**
  - **Trigger**: first-time onboarding, where the connector has never run, so `pg_replication_slots` has no slot for it.
  - **Behaviour**: Step 1 records the LSN (`postgres_dumper.py:2054-2059`); the validator only lists slots (`:1449-1470`); no slot is created and no snapshot is exported anywhere (§3.15). The connector, started later with `snapshot.mode=never`, creates its slot at the then-current WAL position. PostgreSQL decodes from the slot's position, so changes committed after each table was read and before the slot was created are never delivered. The WAL is not retained in any case.
  - **Detection**: compare the slot's `confirmed_flush_lsn` / `restart_lsn` with the LSN in the dumper log right after the connector starts (a slot newer than the logged LSN means a loss window); checksum the tables with 13.07.
  - **Blast radius**: all tables that changed during the dump and the gap before connector start. The loss is silent, with no error on either side.
  - **Recovery**: create the slot first (start the connector once, or create the slot manually, then stop the connector), confirm that its `confirmed_flush_lsn` is at or before the LSN the dumper will read, then rerun the dump with `--truncate` and start the connector.
  - **RTO**: unmeasured; equals a full snapshot reload.
  - **Test**: `GAP: main() must create or verify a slot whose position is at or before the recorded LSN before any COPY`
  - **DEFECT**: D-13.05-3.

- **FM-13.05-3 Rerun after a partial failure with `--skip_existing`**
  - **Trigger**: a previous run was interrupted or failed after some tables got rows; the operator reruns with `--skip_existing`.
  - **Behaviour**: any table with `count() > 0` is skipped, including tables that were cut off mid-load (`postgres_dumper.py:2314-2351`). Step 5 then writes the **new** LSN read by this run (`:2786-2808`), even when every table was skipped. Reproduced: two tables each report `count()` 5, no COPY runs, and the offset INSERT is issued with exit 0.
  - **Detection**: the log line `Skipping — already has N rows`; a count comparison against the source.
  - **Blast radius**: skipped tables lose all changes between their earlier load and the new LSN, and partly loaded tables keep their gap for good.
  - **Recovery**: rerun the affected tables with `--truncate`, and write an offset no newer than the start LSN of the oldest kept load (the LSN from the earlier run's log).
  - **RTO**: unmeasured; equals the reload time of the affected tables.
  - **Test**: `GAP: --skip_existing with all tables populated must not write a newer offset (harness 4 scenario)`
  - **DEFECT**: D-13.05-4.

- **FM-13.05-4 Source table without a primary key**
  - **Trigger**: a selected table has no PRIMARY KEY.
  - **Behaviour**: `ORDER BY (tuple())` with `ReplacingMergeTree(_version, is_deleted)` (`postgres_type_mapper.py:399-408`). Every row has the same sorting key, so merges and `FINAL` keep one row (spec 08.05, FM-08.05-3).
  - **Detection**: `count()` drops after a merge, or `SELECT count() FROM t FINAL` returns 1.
  - **Blast radius**: the whole table collapses to one row.
  - **Recovery**: create the table by hand with a real key (a unique NOT NULL column set, or all columns) or as a plain MergeTree, then reload with `--data_only`.
  - **RTO**: unmeasured; equals the table reload time.
  - **Test**: `GAP: build_create_table with pk_columns=[] must not emit ORDER BY tuple() for ReplacingMergeTree`
  - **DEFECT**: D-13.05-5.

- **FM-13.05-5 Table filters taken from the connector config**
  - **Trigger**: `--config` with `table.include.list` / `table.exclude.list` in Debezium form (`schema.table`, comma-separated).
  - **Behaviour**: `_debezium_list_to_regex` drops the schema and joins with `|`, and `filter_tables_by_regex` uses an unanchored `re.search` (`postgres_dumper.py:1524-1548`, `:174-179`). Reproduced: exclude `public.log` also removes `audit_log`, `login_events` and `catalog`; include `public.users` also selects `users_archive`; `sales.items,hr.items` gives `items|items` and matches `items` in every schema. `database.include.list` is dropped with no warning.
  - **Detection**: compare the `Mapping PG ... → CH ...` log lines with the connector's table list.
  - **Blast radius**: tables the connector replicates are never snapshotted, so they hold only post-LSN changes. Extra tables are snapshotted that CDC never updates, so they go stale.
  - **Recovery**: pass explicit anchored `--tables` / `--exclude_tables` (PostgreSQL ARE) instead of the config lists, then load the missed tables with the slot precautions of FM-13.05-2.
  - **RTO**: unmeasured.
  - **Test**: `GAP: _debezium_list_to_regex('public.log') must match only public.log, anchored`
  - **DEFECT**: D-13.05-6.

- **FM-13.05-6 Same table name in two schemas mapped to one ClickHouse database**
  - **Trigger**: several schemas (`--pg_schema a b`, or an include regex) with a literal `--ch_database`, or a database/table template pair without `{{ schema }}`.
  - **Behaviour**: both items resolve to the same `db.table` (`postgres_dumper.py:2106-2127`; reproduced with `resolve_ch_names`). The second `CREATE ... IF NOT EXISTS` is a no-op, so the second schema's rows load into the first schema's table shape. With `--drop_existing`, the second item drops the table just created for the first. Both schemas' rows end up in one table and collapse on equal keys.
  - **Detection**: duplicate `→ CH db.t` targets in the mapping log.
  - **Blast radius**: data from different schemas merges, and rows with equal keys overwrite each other.
  - **Recovery**: use `--ch_table_template '{{ schema }}___{{ table }}'` or a schema-aware database template, drop the merged tables, and reload.
  - **RTO**: unmeasured.
  - **Test**: `GAP: main() must refuse duplicate (ch_database, ch_table) targets`
  - **DEFECT**: D-13.05-7.

- **FM-13.05-7 Source server `TimeZone` is not UTC and a table has `timestamp without time zone`**
  - **Trigger**: `SHOW timezone` returns, for example, `America/Chicago`.
  - **Behaviour**: the column is created as `DateTime64(6, 'America/Chicago')` (`postgres.py:193-194`), but the value is parsed by `parseDateTime64BestEffortOrNull("c", 6)` under `--session_timezone=UTC` (`postgres_type_mapper.py:446-447`, `postgres_dumper.py:422`). The stored instant is the wall clock read as UTC and displays shifted by the zone offset. CDC rows are written as wall-clock digits in the column zone (07.03 §3.1.3), so the two populations of one table disagree.
  - **Detection**: compare a known row's displayed value with the source; 13.07 checksums.
  - **Blast radius**: every zone-less timestamp column of every table on such a server.
  - **Recovery**: create the tables with `DateTime64(6, 'UTC')` (`map_pg_type` already does), or pass the column zone to the parse function, then reload.
  - **RTO**: unmeasured.
  - **Test**: `GAP: clickhouse-local round trip of a wall-clock value through build_select_columns into the created column type`
  - **DEFECT**: D-13.05-8.

- **FM-13.05-8 Special or non-ISO temporal text**
  - **Trigger**: `infinity` / `-infinity`, BC dates, dates outside the Date32/DateTime64 range, or a server `DateStyle` other than ISO.
  - **Behaviour**: the `...OrNull` parsers return NULL (`postgres_type_mapper.py:446-449`). NOT NULL columns get the type default through ClickHouse's null-as-default insert behaviour, and non-ISO text is best-effort parsed or NULL. The psql session does not pin `DateStyle` (`postgres_dumper.py:359-366`).
  - **Detection**: NULL or epoch-0 values in NOT NULL source columns; 13.07 checksums.
  - **Blast radius**: affected values only, silently.
  - **Recovery**: set the role or database `DateStyle` to ISO, map such columns to String through a direct override (but see FM-13.05-9), and reload.
  - **RTO**: unmeasured.
  - **Test**: `GAP: conversion table of special temporal literals against clickhouse local`
  - **DEFECT**: D-13.05-9, D-13.05-10.

- **FM-13.05-9 Column type overrides configured**
  - **Trigger**: `--column_type_overrides[_file]` or `column_type_override.*` in the connector config.
  - **Behaviour**: Step 3 applies direct overrides to the DDL, but `load_table` re-reads the columns without overrides (`postgres_dumper.py:471`), so the conversion expressions follow the original type. Reproduced: a `timestamp` column overridden to `String` is loaded through `parseDateTime64BestEffortOrNull("ts", 6)`, which changes the text and nulls unparseable values. Alias overrides are looked up by the **ClickHouse** table name (`:2283-2302` → `postgres_type_mapper.py:350-355`). Reproduced: with `--ch_table_template '{{ schema }}___{{ table }}'`, the alias column is silently missing.
  - **Detection**: compare `DESCRIBE TABLE` with the override file, and sample values.
  - **Blast radius**: overridden columns of every table.
  - **Recovery**: add alias columns by hand (`ALTER TABLE ... ADD COLUMN ... ALIAS`), and reload overridden columns through a corrected path.
  - **RTO**: unmeasured.
  - **Test**: `GAP: load_table must build the SELECT from override-applied metadata; alias lookup must use the PG table name`
  - **DEFECT**: D-13.05-11, D-13.05-18.

- **FM-13.05-10 A disk-based strategy is selected**
  - **Trigger**: `--strategy psql-copy` or `--strategy pgdump` (with or without `--load_only` / `--dump_only`).
  - **Behaviour**: `TypeError` from wrong keyword arguments (`postgres_dumper.py:2483`, `:2542`, `:2366`, `:2397`). This is caught, and the run exits 1 after the ClickHouse tables were already created (reproduced). If the call sites were fixed, the latent defects would surface: the psql-copy dump pipe without `pipefail`, the awk converter that corrupts escapes and drops rows, the unguarded `rmtree` of `--dump_dir`, and the schema-less dump file names.
  - **Detection**: `TypeError: ... unexpected keyword argument` in the log; exit 1.
  - **Blast radius**: no data moves (empty tables are created); latent data corruption once fixed.
  - **Recovery**: use `--strategy streaming`.
  - **RTO**: minutes (a rerun with a different flag); unmeasured.
  - **Test**: `GAP: main() smoke test per --strategy value (harness 4 scenarios)`
  - **DEFECT**: D-13.05-15, D-13.05-16 (latent: D-13.05-12, D-13.05-14, D-13.05-17).

- **FM-13.05-11 Credentials exposed**
  - **Trigger**: any run (argv visible in the process table), or `--debug` (logged).
  - **Behaviour**: `PGPASSWORD='<pw>'` and `--password '<pw>'` are part of the `bash -c` string (`postgres_dumper.py:360`, `:389`, `:658`) and are logged by `logging.debug` (`:502`, `:669`, `:728`, `:861`). The password is single-quoted without escaping, so a `'` in it breaks or injects the shell command. Host, user and paths are unquoted.
  - **Detection**: `ps -ef` during a load; `PGPASSWORD=` in debug logs.
  - **Blast radius**: the source and ClickHouse credentials for anyone who can read the process list or the logs.
  - **Recovery**: rotate both passwords; use `~/.pgpass` and a ClickHouse client config file until the fix lands.
  - **RTO**: unmeasured (operational rotation).
  - **Test**: `GAP: build_psql_copy_cmd/build_ch_insert_cmd must not contain the password; debug log redaction test`
  - **DEFECT**: D-13.05-13.

- **FM-13.05-12 Source is a promoted former standby**
  - **Trigger**: the dump runs on a primary that was once a standby (promoted, or restored with archive recovery).
  - **Behaviour**: `pg_last_wal_replay_lsn()` keeps returning the last LSN replayed during recovery, which is non-NULL, so `get_standby_lsn` never asks for `pg_current_wal_lsn()` (`postgres.py:478-488`). Reproduced with a mock: `1/0` is returned while the current LSN is `9C9/21AE7C20`. The offset is written far in the past.
  - **Detection**: the logged pre-snapshot LSN is far below `pg_current_wal_lsn()`.
  - **Blast radius**: the connector requests WAL that is long gone. It either starts from the slot (re-replaying harmlessly) or fails to start (FM-09.03-6).
  - **Recovery**: write the offset manually from `pg_current_wal_lsn()` read before the dump (with the slot precautions of FM-13.05-2).
  - **RTO**: unmeasured.
  - **Test**: `GAP: get_standby_lsn must consult pg_is_in_recovery()`
  - **DEFECT**: D-13.05-20.

- **FM-13.05-13 Source metadata that produces an invalid ClickHouse type**
  - **Trigger**: `numeric(p,s)` with p > 76, a negative scale, or a server `TimeZone` name that ClickHouse does not know (for example `localtime`).
  - **Behaviour**: `pg_type_to_ch` emits `Decimal(100, 10)`, `Decimal(5, -2)` or `DateTime64(6, 'localtime')` (reproduced), and `CREATE TABLE` fails. The exception aborts the run with exit 1 before any load.
  - **Detection**: a ClickHouse exception in the Step 3 log; exit 1.
  - **Blast radius**: the whole run stops (loudly).
  - **Recovery**: a direct override to `String` for the column; set the role `TimeZone` to an IANA name.
  - **RTO**: minutes; unmeasured.
  - **Test**: `GAP: pg_type_to_ch must clamp or refuse precision above 76 and validate the zone name`
  - **DEFECT**: D-13.05-21.

- **FM-13.05-14 Privilege validation fails**
  - **Trigger**: a role without `rolreplication` (common on managed PostgreSQL, where replication is a role membership), `wal_level` not `logical`, or a missing SELECT.
  - **Behaviour**: critical issues are listed, then exit 1 before any ClickHouse change (`postgres_dumper.py:2177-2193`). The REPLICATION attribute is required although the COPY does not need it (`:1322-1346`).
  - **Detection**: `Privilege validation FAILED` and exit 1.
  - **Blast radius**: nothing is written on either side (the check runs before the heartbeat and before Step 3); onboarding is blocked.
  - **Recovery**: grant the missing privilege; on managed services there is no bypass flag, so the code must change.
  - **RTO**: unmeasured.
  - **Test**: `GAP: validate_postgres_privileges with a mocked cursor per probe`
  - **DEFECT**: D-13.05-24.

- **FM-13.05-15 Run interrupted (Ctrl-C, SIGTERM, host loss)**
  - **Trigger**: an operator interrupt or process death during Step 4.
  - **Behaviour**: `KeyboardInterrupt` gives `os._exit(1)` (`postgres_dumper.py:2823-2825`). There is no checkpoint or state file. Some tables are complete, some partial, some empty. No offset is written. On SIGTERM, child pipes may keep inserting after the parent has died.
  - **Detection**: no `postgres_dumper finished successfully` line; exit status 1 or the signal.
  - **Blast radius**: the current run's tables.
  - **Recovery**: make sure no `psql`/`clickhouse-client` children remain, then rerun with `--truncate` (not `--skip_existing`, see FM-13.05-3).
  - **RTO**: unmeasured; a full rerun.
  - **Test**: `GAP: interrupt handling test with fake long-running children`

- **FM-13.05-16 Large table segmentation and no read consistency**
  - **Trigger**: `n_live_tup` above `--segment_threshold` with a single-column segmentable PK.
  - **Behaviour**: a full-sort ntile query (`postgres_dumper.py:239-250`), then N independent `psql` sessions, each with its own snapshot (§3.15). Coverage is complete; consistency between segments relies on CDC replay. Stale statistics (`n_live_tup` 0 or -1) disable segmentation silently.
  - **Detection**: `splitting into N PK segments` log lines; a long first phase on the source.
  - **Blast radius**: load on the source (sort, temp files); transient inconsistency until CDC catches up.
  - **Recovery**: run `ANALYZE` before the dump; tune `--segments_per_table`.
  - **RTO**: unmeasured.
  - **Test**: `GAP: get_pk_range_boundaries/build_segment_where_clause coverage test over typed bounds`

- **FM-13.05-17 Dumper-native config gives `pg_schema` as a scalar**
  - **Trigger**: `pg_schema: public` (not a list) in a dumper-native YAML.
  - **Behaviour**: `merge_config_with_args` copies the string, and `for schema_name in schemas` iterates characters (`postgres_dumper.py:2077`, `:2091`). Reproduced: `get_tables` is called for schemas `p`, `u`, `b`, `l`, `i`, `c`. In practice this ends with "No tables found" and exit 1.
  - **Detection**: `Schemas from --pg_schema (6): public` followed by single-letter schemas in the mapping log.
  - **Blast radius**: the run fails (loudly), or snapshots unrelated single-letter schemas if they exist.
  - **Recovery**: write `pg_schema: [public]`.
  - **RTO**: minutes; unmeasured.
  - **Test**: `GAP: merge_config_with_args type coercion for nargs='+' dests`
  - **DEFECT**: D-13.05-19.

- **FM-13.05-18 `--dry_run` or `--benchmark` against a production source**
  - **Trigger**: an operator expects no side effects.
  - **Behaviour**: `--dry_run` still connects, still creates or inserts the heartbeat table on the source (`postgres_dumper.py:2196-2197`), and leaks `/tmp/pg_copy_*` and `/tmp/ch_insert_*` files. `--benchmark` ignores `--dry_run`, drops and creates `_benchmark_*` tables, and loads the full table in its streaming arm (`:886-1091`).
  - **Detection**: `Created heartbeat table` in a dry-run log; files in `/tmp`.
  - **Blast radius**: one small table on the source; benchmark load on both systems.
  - **Recovery**: drop `public.sink_connector_heartbeat` if it is unwanted; remove the temp files.
  - **RTO**: minutes; unmeasured.
  - **Test**: `GAP: dry-run must issue no source DDL/DML`
  - **DEFECT**: D-13.05-25.

- **FM-13.05-19 The DDL parser is fed real CDC DDL**
  - **Trigger**: a future caller passes PostgreSQL DDL to `parse_postgres_ddl` (no caller exists on 2.11.0).
  - **Behaviour**: ALTER DROP/TYPE/SET NOT NULL crash with `AttributeError`; any CHECK constraint crashes with `NameError`; `RENAME COLUMN` is reported as a table rename; `clickhouse_sql` is not valid DDL (§3.18; all reproduced).
  - **Detection**: exceptions; manual review of `clickhouse_sql`.
  - **Blast radius**: none today (dead code); a wrong rename or invalid DDL if wired in.
  - **Recovery**: do not use the parser until the listener and the generated predicate code are fixed.
  - **RTO**: not applicable (no runtime use); unmeasured.
  - **Test**: `GAP: parser unit tests for each statement in §3.18`
  - **DEFECT**: D-13.05-28, D-13.05-29, D-13.05-30, D-13.05-31.

Summary: 19 failure modes, 17 DEFECT, 19 GAP.

## 7. Defect Register

All defects are in the packaged tree (`Copy` = packaged). No legacy copy of these
files exists.

| ID | Severity | Copy (legacy/packaged/both) | Location | Evidence | Summary |
|---|---|---|---|---|---|
| D-13.05-1 | S1 | packaged | `sink-connector/python/ch_sink_tools/db_dump/postgres_dumper.py:426`, `:497`, `:506-516`, `:725`, `:858` | **reproduced**: fake psql (exit 2) and fake clickhouse-client (exit 27) give `load_table(...) = ('t1', 10, 0.04, True)`; `rc without trailing rm -f = 27`, `rc with trailing rm -f = 0` | The INSERT command ends with `; rm -f <file>`, so every pipe's exit status is 0; failed loads are reported `Done` and the offset is written |
| D-13.05-2 | S1 | packaged | `postgres_dumper.py:359-366` | code-read: `psql -f` without `-v ON_ERROR_STOP=1` exits 0 when a statement in the script fails (psql exit-status rules); `statement_timeout` is not disabled for this session | A failed or cancelled COPY returns 0, so the table is empty or partial with no failure, even once D-13.05-1 is fixed |
| D-13.05-3 | S1 | packaged | `postgres_dumper.py:2054-2059`, `:1449-1470`, `:2786-2815` | code-read plus the reproduced `main()` event log: LSN on autocommit connection #1, no slot statement, no `pg_export_snapshot`/`REPEATABLE READ` anywhere (search) | The LSN hand-off depends on a slot that the tool neither creates nor checks; if the connector creates the slot after the dump, changes between table reads and slot creation are lost silently |
| D-13.05-4 | S1 | packaged | `postgres_dumper.py:2314-2351`, `:2786-2808` | **reproduced**: `--skip_existing --data_only` with `count()=5` → no COPY, offset INSERT, exit 0 | Partly loaded tables count as done, and the run writes the new LSN even when it loaded nothing |
| D-13.05-5 | S1 | packaged | `sink-connector/python/ch_sink_tools/db_load/postgres_type_mapper.py:399-408` | code-read; ClickHouse semantics measured in spec 08.05 (FM-08.05-3) | Keyless tables get `ReplacingMergeTree ... ORDER BY (tuple())` and collapse to one row |
| D-13.05-6 | S1 | packaged | `postgres_dumper.py:1524-1548`, `:174-179`, `:1629-1634` | **reproduced**: exclude `public.log` keeps only `users`, `users_archive`, `orders` (drops `audit_log`, `login_events`, `catalog`); include `public.users` selects `users_archive` | Connector table lists lose their schema and become unanchored regexes, so replicated tables are skipped and extra tables are loaded; database lists are dropped |
| D-13.05-7 | S1 | packaged | `postgres_dumper.py:2106-2127`, `:2259-2302` | **reproduced** (`resolve_ch_names`: `public.items` and `sales.items` both → `mydb.items`) | Same-named tables of different schemas target one ClickHouse table; with `--drop_existing` the second drop removes the first; the rows merge |
| D-13.05-8 | S1 | packaged | `sink-connector/python/ch_sink_tools/db/postgres.py:193-196`; `postgres_type_mapper.py:446-447`; `postgres_dumper.py:422` | code-read; DDL and SELECT reproduced; ClickHouse evaluation not run offline | `timestamp without time zone` columns are declared in the server zone but parsed in UTC, so values shift by the zone offset and differ from CDC-written rows |
| D-13.05-9 | S1 | packaged | `postgres_type_mapper.py:446-449` | code-read | `...OrNull` parsing silently turns `infinity`, BC and out-of-range temporal values into NULL (or the default in NOT NULL columns); CDC clamps instead |
| D-13.05-10 | S1 | packaged | `postgres_dumper.py:359-366` | code-read | The psql session inherits `DateStyle`, `IntervalStyle`, `bytea_output` and `lc_monetary`; a non-ISO `DateStyle` produces misparsed or NULL dates with no error |
| D-13.05-11 | S1 | packaged | `postgres_dumper.py:471` | **reproduced**: DDL `ts String` with load SELECT `parseDateTime64BestEffortOrNull("ts", 6)` | The load path ignores direct overrides, so conversion expressions do not match the overridden column types |
| D-13.05-12 | S1 (latent) | packaged | `postgres_dumper.py:837-858` | **reproduced**: the awk output keeps `\n` and `\\` literally and drops the rows keyed `--x` and `SET ` | The pgdump converter corrupts escaped values and drops rows (unreachable today, see D-13.05-16) |
| D-13.05-13 | S2 | packaged | `postgres_dumper.py:360`, `:389`, `:502`, `:658`, `:669`, `:728`, `:861` | **reproduced**: the built commands contain `PGPASSWORD='s3cr'et'` and `--password 'chpw'` | Passwords are on the process command line, in DEBUG logs, and single-quoted without escaping (shell injection); host, user and paths are unquoted |
| D-13.05-14 | S2 (latent) | packaged | `postgres_dumper.py:757-760` | code-read | `pgdump_dump_tables` runs `shutil.rmtree(--dump_dir)` with no guard |
| D-13.05-15 | S3 | packaged | `postgres_dumper.py:2483-2494`, `:2542-2555` | **reproduced**: `TypeError: psqlcopy_dump_table() got an unexpected keyword argument 'dry_run'`, exit 1 | `--strategy psql-copy` cannot run |
| D-13.05-16 | S3 | packaged | `postgres_dumper.py:2366-2376`, `:2397-2410` | **reproduced**: `unexpected keyword argument 'pg_schemas'`; with `--load_only`, `... 'ch_table'` in every worker, exit 1 | `--strategy pgdump` cannot run |
| D-13.05-17 | S3 | packaged | `postgres_dumper.py:657-665` | **reproduced**: a failing psql gives a 28-byte `.csv.gz` and no exception | The psql-copy dump pipe has no `pipefail`; a source failure leaves a truncated file treated as good (reachable through `--benchmark`) |
| D-13.05-18 | S3 | packaged | `postgres_dumper.py:2283-2302`; `postgres_type_mapper.py:350-355` | **reproduced**: with CH name `public___orders`, the alias column is absent | Alias overrides and reconciliation look up the ClickHouse table name instead of the PostgreSQL name |
| D-13.05-19 | S3 | packaged | `postgres_dumper.py:1729-1733`, `:2077`, `:2091` | **reproduced**: `get_tables(schema='p')`, `'u'`, ... | A scalar `pg_schema` from a dumper-native config is iterated per character; no type coercion on config values |
| D-13.05-20 | S3 | packaged | `postgres.py:478-488` | **reproduced** (mock: replay `1/0` returned instead of current `9C9/21AE7C20`) | On a promoted former standby, the static replay LSN is recorded instead of the current LSN |
| D-13.05-21 | S3 | packaged | `postgres.py:169-176`, `:193-194` | **reproduced**: `Decimal(100, 10)`, `Decimal(5, -2)`, `DateTime64(6, 'localtime')` | The DDL emits types that ClickHouse rejects for wide or negative-scale numerics and non-IANA server zones |
| D-13.05-22 | S3 | packaged | `postgres_dumper.py:342`, `:387`; `postgres_type_mapper.py:347`, `:428`; `postgres.py:303-311`, `:349-362`, `:403-414` | code-read | Identifiers and regexes are interpolated without escaping (`"`, `'`, backtick); the resulting errors are hidden by D-13.05-1 |
| D-13.05-23 | S3 | packaged | `postgres.py:308-316` | code-read (information_schema reports relkind `p` as `BASE TABLE`) | Partitioned parents and their partitions are both loaded as separate tables, duplicating rows across tables |
| D-13.05-24 | S3 | packaged | `postgres_dumper.py:1322-1346`, `:2186-2193` | code-read | The REPLICATION attribute is a hard requirement although the COPY does not need it; this blocks managed services |
| D-13.05-25 | S3 | packaged | `postgres_dumper.py:2196-2197`, `:504-512`, `:886-1091`, `:2040-2045` | code-read | `--dry_run` still writes the heartbeat table on the source and leaks temp files; `--benchmark` ignores `--dry_run`, does not limit the streaming arm, leaks a connection, and always exits 0 |
| D-13.05-26 | S3 | packaged | `postgres_dumper.py:1130-1139`; `postgres_type_mapper.py:526-531` | **reproduced**: `connector_name="it's"` gives `'["it's",...]'` (broken SQL) | The offset table DDL differs from the Java DDL (no `_version`; RMT on `record_insert_seq`); values are interpolated unescaped |
| D-13.05-27 | S3 | packaged | `postgres_type_mapper.py:188-301` versus `postgres.py:142-210` | **reproduced** (§3.8: bare `numeric` gives `Decimal(18, 6)` versus `String`; timestamp gives UTC versus the server zone) | The unit-tested mapper is not the one the dumper uses; the tests give false assurance and the two mappers disagree |
| D-13.05-28 | S3 | packaged | `sink-connector/python/ch_sink_tools/db_load/postgres_parser/CreateTablePostgreSQLParserListener.py:246-266` | **reproduced**: `AttributeError: 'list' object has no attribute 'start'` | ALTER DROP COLUMN, ALTER TYPE and SET/DROP NOT NULL crash the listener |
| D-13.05-29 | S3 | packaged | `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLParser.py:58290`, `:36010` | **reproduced**: `NameError: name 'this' is not defined` for `CHECK (c > 0)` | The generated parser contains Java `this.` predicate actions |
| D-13.05-30 | S3 | packaged | `CreateTablePostgreSQLParserListener.py:297-326` | **reproduced**: `RENAME COLUMN note TO memo` gives `rename_table`, `new_name='memo'` | Column and constraint renames are reported as table renames |
| D-13.05-31 | S3 | packaged | `CreateTablePostgreSQLParserListener.py:114-131`, `:152-171` | **reproduced** (§3.18) | `clickhouse_sql` is invalid or wrong: nullable PK in the sorting key, `Nullable` version column, table-level PK ignored, quoted names in backticks, `DEFAULT 'NOT NULL'` flips nullability |
| D-13.05-32 | S4 | packaged | `postgres_dumper.py:1927-1935`, `:1629-1634`, `:1838-1840` | **reproduced**: `--pg_schema public --threads 4` gives an empty explicit set | Explicit-flag detection compares against defaults (config beats CLI values equal to the default, and beats environment variables); `--order_by_size` is a no-op; `--batch_size` is ignored |
| D-13.05-33 | S4 | packaged | `postgres_dumper.py:1940`, `:134-136`, `:2823-2825` | code-read | Logging starts after config loading (messages lost); child stderr is logged at INFO; every `sys.exit(1)` is logged as `Received interrupt` |
| D-13.05-34 | S4 | packaged | `sink-connector/python/README.md:46`; `sink-connector/python/ch_sink_tools/db_load/postgres_parser/README.md:98`, `:200-204`; `sink-connector/python/build_grammars.sh:15-29`; `postgres_type_mapper.py:7-8`, `:14`, `:21`, `:480`, `:512-516`; `postgres.py:441-445`, `:476`; `postgres_dumper.py:2812`, `:2820`; `sink-connector/python/pyproject.toml` | **reproduced** (uuid3 versus `nameUUIDFromBytes` differ; LSN docstring example wrong) and code-read | Documentation and packaging drift: hyphenated flags in the README, outdated numeric limitation, grammar regeneration into the legacy path, "LOW-32" LSN wording, `_version Nullable`, a nonexistent `clickhouse_loader --source postgres`, a false "matches Java" UUID claim, and `antlr4` only in the `[mysql]` extra |

Dead code noted (not defects): `run_quick_command`, `runTime`, `import gzip`,
the `build_ch_create_table_ddl` import, `map_udt_type`, `_STRIP_PREFIXES`, the
`batch_size` parameter of `build_psql_copy_cmd`, the `config` argument of
`validate_postgres_privileges`, and the whole `postgres_parser` package (no
importer).
