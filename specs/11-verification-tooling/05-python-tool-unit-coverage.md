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
