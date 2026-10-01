# Spec 13.04: ClickHouse Snapshot Loader and MySQL-to-ClickHouse DDL Translation

## 1. Executive Summary & Purpose

`clickhouse_loader` is the **snapshot load** step of the non-streaming toolset. It runs after the dump (Spec
13.03) and before the streaming connector takes over. It reads a MySQL Shell dump (`--mysqlshell`, the only
layout the in-tree dumper produces) or a mydumper dump. For every table it translates the MySQL
`CREATE TABLE` text into a ClickHouse `ReplacingMergeTree` DDL, creates the database and tables through
`clickhouse_driver`, and pipes every data chunk through a shell pipeline
(`zstd -d | clickhouse-client --query "INSERT ... SELECT ... FROM input(...) FORMAT TSV"`, or
`gunzip | sed | sed | clickhouse-client ... FORMAT CSV` for mydumper). `ch-mysql-resync` (Spec 11.04) drives
the same program once per table with `--mysqlshell --data_only --rmt_delete_support`.

The DDL translator has two implementations. The ANTLR translator (`convert_to_clickhouse_table_antlr`, a
hand-written listener over the generated MySQL grammar) is always tried first. The regexp translator is
used only when ANTLR raises. The `--use_regexp_parser` flag is ignored.

This spec records the behaviour as built in both copies (legacy `db_load/` and packaged `ch_sink_tools/`).
It also covers the column type override configuration and reconciler under `ch_sink_tools/config/`, which
only the PostgreSQL dumper consumes today. The most consequential findings:

- **Keyless tables (S1, fixed).** A table without a `PRIMARY KEY` used to be created as
  `ReplacingMergeTree ... ORDER BY tuple()`, which merges and `FINAL` collapse to one row. The ANTLR
  translator now derives the sorting key exactly as the streaming DDL path does: the first `UNIQUE` key when
  all its columns are `NOT NULL`, otherwise every stored column, with `allow_nullable_key=1` when one of them
  is nullable (§3.12). The regexp fallback refuses a keyless table.
- **Dump time zone (S1, fixed).** The packaged copy used to pick a random zone from an unsorted set (a
  January `'+00:00'` gave a DST zone for 4 of 10 hash seeds). Both copies now map a MySQL zone value
  deterministically (`UTC`, a fixed-offset `Etc/GMT±N` zone or a named zone), and the MySQL Shell zone comes
  from the dump's `@.json` `tzUtc` flag. An undeterminable zone gives `UTC` with a WARNING (§3.9).
- **Binary values (S1, fixed).** The decode transform used to test the translated type (`String`) and never
  ran, so binary, BIT and spatial columns received MySQL Shell's base64 text. The loader now renders them in
  the connector's representation, selected by `--binary_handling_mode` and `--persist_raw_bytes` (defaults
  match the connector's defaults: lower-case hex) and decoded as the dump's per-table metadata says (§3.7.1).
- **Pipeline failures (S1, fixed).** Load pipelines now run under `bash -o pipefail`, so a failing
  decompressor or `sed` stage fails the load with a non-zero exit (§3.10). The loader still never compares
  loaded row counts with the dump (D-13.04-19).
- **Other S1 defects (fixed).** A `-` in a mydumper `--dump_dir` no longer hides the data files. A source
  column named `_sign` or `_is_deleted` is loaded, and a source column that collides with an appended
  bookkeeping column is refused loudly. A lower-case `null` modifier is recorded as nullable.
- **Divergence from the streaming connector (S2).** Generated columns become `MATERIALIZED`, which the
  connector's own inserts then fail on (Spec 06.06). ENUM becomes `Enum8` (the stream uses `String`), BIT(1)
  becomes `String` (the stream uses `Bool`), and DATETIME/TIMESTAMP carry no zone (the stream uses
  `'UTC'`).
- **Destructive and credential defects (S2).** `--truncate_tables` truncates
  `<mysql_source_database>.<table>`, not the target database. Credentials leak in three ways: the packaged
  copy logs the password and breaks on a quote in it (FM-11.05-1), the legacy copy puts it in the failure
  exception (FM-11.05-2), and the legacy copy puts a config-file password on the `clickhouse-client` argv.

Snapshot version semantics are correct. Every loaded row gets `_version = 0` from the column `DEFAULT`, and
`is_deleted = 0` (or `_sign = 1`). Every streamed version is strictly positive (Spec 02.01), so streamed
rows always outrank snapshot rows, and a snapshot row never ties a streamed one. The same property means a
re-load into a table that already holds streamed rows cannot repair them (§3.11).

---

## 2. Codebase Mapping on 2.11.0

| Concern | Legacy copy | Packaged copy |
|---|---|---|
| Loader CLI, schema phase, data phase | `sink-connector/python/db_load/clickhouse_loader.py` (655 lines) | `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py` (623 lines) |
| ANTLR entry point (`convert_to_clickhouse_table_antlr`, `MyErrorListener`) | `sink-connector/python/db_load/mysql_parser/mysql_parser.py` | `sink-connector/python/ch_sink_tools/db_load/mysql_parser/mysql_parser.py` |
| Hand-written translator listener (type mapping, engine, keys) | `sink-connector/python/db_load/mysql_parser/CreateTableMySQLParserListener.py` | `sink-connector/python/ch_sink_tools/db_load/mysql_parser/CreateTableMySQLParserListener.py` |
| Generated parser (public entry `MySqlParser.sqlStatements`, out of scope) | `sink-connector/python/db_load/mysql_parser/MySqlParser.py`, `sink-connector/python/db_load/mysql_parser/MySqlLexer.py`, `sink-connector/python/db_load/mysql_parser/MySqlParserListener.py` | byte-identical copies under `sink-connector/python/ch_sink_tools/db_load/mysql_parser/` |
| Grammar sources and generator | `sink-connector/python/antlr_grammars/mysql/MySqlParser.g4`, `sink-connector/python/antlr_grammars/mysql/MySqlLexer.g4`, `sink-connector/python/build_grammars.sh` (writes the legacy directory only) | same grammar |
| Binary-type predicate `is_binary_datatype` | `sink-connector/python/db/mysql.py:11-25` | `sink-connector/python/ch_sink_tools/db/mysql.py:10-17` (substring-based, diverges) |
| Driver connection, statement execution, credential file | `sink-connector/python/db/clickhouse.py:9-72` | `sink-connector/python/ch_sink_tools/db/clickhouse.py:9-69` |
| Column type override model | none | `sink-connector/python/ch_sink_tools/config/column_type_overrides.py` |
| Override reconciler | none | `sink-connector/python/ch_sink_tools/config/override_reconciler.py` |
| Override consumer (PostgreSQL only) | none | `sink-connector/python/ch_sink_tools/db_dump/postgres_dumper.py`, `sink-connector/python/ch_sink_tools/db_load/postgres_type_mapper.py` |
| Programmatic caller | `sink-connector/python/db_load/mysql_resync.py` | `sink-connector/python/ch_sink_tools/db_load/mysql_resync.py` (`loader_command`, `run_loader`) |
| Unit tests | `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py` (legacy only), `sink-connector/python/db_load/tests/test_loader_failure_modes.py` (both copies) | none |
| Entry points | `sink-connector/python/test_db.sh` (`python db_load/clickhouse_loader.py`) | `ch-mysql-load` in `sink-connector/python/pyproject.toml:49` |
| Docker image (does not contain the loader) | `sink-connector/python/Dockerfile_db_load` | n/a |
| Streaming DDL path used for comparison | `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/parser/DataTypeConverter.java`, `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` | |
| Streaming auto-create used for comparison | `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java` | |

Line anchors used throughout. L means the legacy loader, P the packaged loader, and LS the listener. The
listener is identical in both copies except for the import lines 2-4. The anchors refer to the 2.11.0 sources,
before the fixes of D-13.04-1 to D-13.04-7 and D-13.04-28. Code added by those fixes is cited by function
name: in the loader `get_unix_timezone_from_mysql_timezone`, `find_mysqlshell_dump_timezone`,
`is_loaded_column`, `mysqlshell_binary_kind`, `read_mysqlshell_decode_columns` and
`mysqlshell_column_expression`; in the listener `UnsafeTableDefinitionError`, `index_column_names`,
`exitUniqueKeyTableConstraint` and `sorting_key`.

| Function | Legacy (L) | Packaged (P) |
|---|---|---|
| `run_command` (dead) | L28-44 | P28-44 |
| `run_quick_command` | L47-60 | P47-60 |
| `get_connection` | L63-66 | P63-66 |
| `parse_schema_path`, `parse_schema_path_mysqlshell` | L69-86 | P69-86 |
| `find_primary_key`, `find_dump_timezone`, `find_create_table`, `find_partitioning_options` | L89-130 | P89-130 |
| `convert_to_clickhouse_table_regexp` | L133-246 | P133-246 |
| `convert_to_clickhouse_table` (selector) | L249-265 | P249-265 |
| `get_unix_timezone_from_mysql_timezone` | L268-284 | P268-285 |
| `load_schema` / `load_schema_mysqlshell` | L287-330 / L333-377 | P288-331 / P334-378 |
| `get_column_list` | L380-405 | P381-406 |
| `load_data` (mydumper data path) | L408-441 | P409-441 |
| `register_secret`, `redact_password` | L444-472 | absent |
| `execute_load` | L475-483 | P444-452 |
| `load_data_mysqlshell` | L486-554 | P455-522 |
| `check_program_exists` | L557-560 | P525-528 |
| `main` | L563-650 | P531-618 |

---

## 3. Contract (Behaviour as Built)

### 3.1 Entry points and which copy runs where

| Invocation | Copy | Notes |
|---|---|---|
| `ch-mysql-load ...` (installed wheel) | packaged | `pyproject.toml:49` → `ch_sink_tools.db_load.clickhouse_loader:main` |
| `ch-mysql-resync patch` | packaged by default | `mysql_resync.loader_command` runs `python -m ch_sink_tools.db_load.clickhouse_loader` unless `--loader-cmd` is given. Fixed arguments: `--mysqlshell --data_only --rmt_delete_support --clickhouse_host H --clickhouse_port P --clickhouse_database <restore db> --mysql_source_database <schema> --dump_dir <per-table hard-link dir> --threads N --clickhouse_config_file <cfg>`. `--truncate_tables` is deliberately never passed (11.04 §3, because of D-13.04-8). |
| `python db_load/clickhouse_loader.py ...` | legacy | `test_db.sh`, and `--loader-cmd` overrides |
| Unit tests | legacy (except one packaged redaction test, which is skipped) | §5.1 |
| `Dockerfile_db_load` | none | The image installs `clickhouse-client` and `mysql-shell` with entrypoint `mysqlsh`. No Python and no loader are included. |

`main()` is also the import-time contract: `execute_load` reads the module global `args` (L477/P446). That
global is set only by `main()` (`global args`, L619/P587). Calling `load_data*` from another module without
setting `module.args` raises `NameError`. The unit tests set it by hand.

### 3.2 Command-line interface (identical flag set in both copies, L581-618 / P549-586)

| Flag | Type | Default | Effect | Honoured? |
|---|---|---|---|---|
| `--clickhouse_host` | str | required | `-h` for clickhouse-client, `host=` for the driver | yes |
| `--clickhouse_user` | str | `None` | User. Must be set when `--clickhouse_password` is given (an `assert`, L631/P599). Otherwise the user comes from the config file. | yes |
| `--clickhouse_password` | str | `None` | Password. When given, the config file is **not** parsed for credentials. A warning is logged. | yes (D-13.04-11/12) |
| `--clickhouse_config_file` | str | `'./clickhouse-client.xml'` | (a) Without `--clickhouse_password`, `resolve_credentials_from_config` reads `<user>`/`<password>` (XML root children) or `config.user`/`config.password` (YAML). (b) It is **always** passed to every clickhouse-client as `--config-file`, because the default is never `None` (L423-424/P423-424). | yes; the default always applies |
| `--clickhouse_port` | int | `9000` | driver `port=`, clickhouse-client `--port` | yes |
| `--clickhouse_secure` | **str** (no `type`/`action`) | `False` | Truthiness of the string: any non-empty value, including `False`, enables `--secure` and `secure=True` | partly (D-13.04-21) |
| `--clickhouse_database` | str | required | Target database. Used for the driver connection that creates tables, and in `INSERT INTO <db>.<table>`. With `--mysqlshell` it is the database created. | yes, except mydumper `CREATE DATABASE` and `--truncate_tables` (D-13.04-8/20) |
| `--mysql_source_database` | str | required | Source schema. Builds the schema-file globs (`<db>@*.sql`, `<db>-schema-create.sql.gz`). Also the database in `truncate table <db>.<t>`. | yes |
| `--dump_dir` | str | required | Dump directory. Concatenated into globs and into shell commands without quoting. | yes |
| `--threads` | int | `8`, but `required=True` | `ThreadPoolExecutor(max_workers=threads)` for mysqlshell chunks. The mydumper data path is sequential and ignores it. | partly (D-13.04-30) |
| `--debug` | flag | False | Parsed, never read. The root logger is fixed at INFO. | no |
| `--schema_only` | flag | False | Skip the data phase | yes |
| `--data_only` | flag | False | Skip schema creation. The schema is still re-parsed with `dry_run=True` to build the column map (L645-647). | yes |
| `--use_regexp_parser` | flag | False | Passed to `convert_to_clickhouse_table`, whose use of it is commented out (L255-256) | no |
| `--truncate_tables` | flag | False | mysqlshell only: `truncate table <mysql_source_database>.<table>` before loading each table | yes, wrong target (D-13.04-8) |
| `--dry_run` | flag | False | Schema SQL is not executed and clickhouse-client commands are logged but not run. Database/table creation is skipped. | yes |
| `--virtual_columns` | list (`nargs='+'`) | `` `_sign` `_version` `is_deleted` `_is_deleted` `` (backticked) | Names excluded from the INSERT column list and the input structure. The match is on the backticked spelling. It applies only to column dicts not marked `source_column`, i.e. the bookkeeping columns the regexp translator lists; a MySQL column from the ANTLR translator is always loaded (§3.7.1). | only for regexp-translated tables |
| `--mysqlshell` | flag | False | Selects the MySQL Shell layout. Without it the mydumper layout is used. | yes; mydumper mode fails the zstd assertion (D-13.04-14) |
| `--rmt_delete_support` | flag | False | `ReplacingMergeTree(_version, is_deleted)` plus an `is_deleted UInt8 DEFAULT 0` column. Without the flag: `ReplacingMergeTree(_version)` plus `_sign Int8 DEFAULT 1`. | yes |
| `--clickhouse_datetime_timezone` | str | `None` | Appends `,'<tz>'` to every `DateTime64(p)` from DATETIME/TIMESTAMP (ANTLR only). Not used by the `--data_only` re-parse. | schema phase only |
| `--binary_handling_mode` | `bytes`/`base64`/`hex` | `bytes` | Mirror of the streaming connector's (Debezium's) `binary.handling.mode`; set it to the value of the connector that streams into the tables. Selects how binary/varbinary/blob values of a MySQL Shell dump are rendered: lower-case hex, base64 text or upper-case hex. BIT and spatial values are hex under every mode (§3.7.1). The default is Debezium's default; the shipped `deploy/ansible-systemd/templates/config.yml.j2` sets `base64`. | mysqlshell path |
| `--persist_raw_bytes` | flag | False | Mirror of the connector's `persist.raw.bytes`: load the decoded bytes instead of lower-case hex (binary types only under `--binary_handling_mode bytes`; BIT and spatial always) | mysqlshell path |

There are no environment variables. `TZ` is **set** inside every shell command (§3.9), never read.

### 3.3 Credentials (`main`, L628-634 / P596-602; `db/clickhouse.py`)

1. If `--clickhouse_password` is given, user and password come from the CLI.
2. Otherwise `resolve_credentials_from_config(--clickhouse_config_file)` runs. It asserts that the file
   exists and has extension `.xml`/`.yml`/`.yaml`. For XML it reads `root.findtext('user')` and
   `root.findtext('password')`, the direct children of the root element (the clickhouse-client XML shape).
   For YAML it reads `['config']['user']` and `['config']['password']`. That is **not** the clickhouse-client
   YAML shape, whose keys are top level, so a working client YAML raises `KeyError: 'config'`. The packaged
   copy logs the password at DEBUG (`ch_sink_tools/db/clickhouse.py:68`). The loader's handler is INFO, so
   this is not emitted by the loader.
3. The driver gets `user`/`password` (L63-66). The packaged `clickhouse_connection` maps a `None` password
   to `""`.
4. clickhouse-client gets the credentials in three different ways:
   - Legacy, both data paths: `-u<user> --password <shlex.quote(resolved password)>`. This is done **even
     when the password came from the config file**, so it appears on the process argv (D-13.04-13). The
     legacy copy registers the secret so that logged commands are masked (L420, L497, L459-472).
   - Packaged mydumper path (`load_data`): `--password '<resolved password>'`, single-quoted without
     escaping (P421).
   - Packaged mysqlshell path: `--password '<args.clickhouse_password>'` (P463-466), CLI password only. With
     config-file credentials no `--password` is emitted and clickhouse-client reads the `--config-file`.
     `-u<user>` is still emitted.

### 3.4 Dump layouts, discovery and name parsing

**MySQL Shell layout (`--mysqlshell`)**, as written by `util.dumpTables` (Spec 13.03 §3.9):

- Schema files: `glob(<dump_dir>/<mysql_source_database>@*.sql)` (L349-351/P350-352). A file is kept only if
  `re.search(r'@[^.]+\.sql', path)` matches (L357/P358), which excludes `<db>@<t>.triggers.sql`. The name is
  parsed by `parse_schema_path_mysqlshell`: strip `.sql`, then `db = split('@')[0]` and
  `table = split('@')[1]`. Percent-encoded identifiers (MySQL Shell encodes special characters) are **not**
  decoded, so the ClickHouse table name is the encoded file name.
- Data files per table: `glob(<dump_dir>/<db>@<table>@*.tsv.zst) + glob(<dump_dir>/<db>@<table>.tsv.zst)`
  (L511-512/P479-480). This covers chunked (`@0`, `@@N` last chunk), partitioned (`@<partition>@N`) and
  unchunked dumps. `.tsv.gz` or uncompressed `.tsv` dumps match nothing, so zero rows are loaded silently
  (D-13.04-19).
- If no schema file is found, `load_schema_mysqlshell` logs `Cannot find schema files` and returns `None`.
  `main` then fails on tuple unpacking with `TypeError: cannot unpack non-iterable NoneType object`
  (reproduced, D-13.04-19).

**mydumper layout (no `--mysqlshell`)**:

- Database file: `<dump_dir>/<mysql_source_database>-schema-create.sql.gz` (L296-297), executed verbatim.
- Schema files: `glob(<dump_dir>/*-schema.sql.gz)` for **all** databases in the directory, not only
  `--mysql_source_database`. `parse_schema_path` takes the file name and strips `-schema.sql.gz`. Then
  `db = split('.')[0]` and `table = split('.')[1]`, so dots in names truncate the table name.
- Data files: the schema file path minus its `-schema.sql.gz` suffix, glob-escaped, plus `.*dat.gz`, i.e.
  `<dump_dir>/<db>.<table>.*dat.gz`. A `-` (or a glob metacharacter) in `--dump_dir` or in a table name no
  longer matters. Before the fix the whole path was split on `-`, so `dump-mydumper/` gave 0 commands and
  exit 0 (D-13.04-5, fixed).

Glob results are unsorted, in filesystem order, so table and chunk order are not deterministic.

### 3.5 Phase order (`main`, L563-650 / P531-618)

1. A root logger handler is set up (INFO, stdout, format
   `%(asctime)s - %(name)s - %(threadName)s - %(levelname)s - %(message)s`). Arguments are parsed.
2. Credentials (§3.3).
3. Dependency checks, both of them `assert`s (L637-640/P605-608):
   - `check_program_exists('clickhouse-client')` (runs `/usr/bin/which`).
   - `assert args.mysqlshell and check_program_exists('zstd')`. This is **false whenever `--mysqlshell` is
     absent**, so the mydumper mode always stops here with
     `AssertionError: zstd should be in the PATH for util.dumpSchemas load` (reproduced, D-13.04-14). Under
     `python -O` both checks disappear.
4. If not `--data_only`: `load_schema(dry_run=--dry_run, datetime_timezone=--clickhouse_datetime_timezone)`
   returns `(unix_tz, schema_map)`.
5. If not `--schema_only`: if the schema phase was skipped, `load_schema(..., dry_run=True)` re-parses the
   schema files to rebuild `schema_map`, without the datetime zone. Then `load_data(...)` runs.
6. `logging.info("Finished")` runs only under `__main__`. The process exits 0 if no exception escaped.

`schema_map` maps `"<db>.<table>"` to the list of column dicts returned by the translator (§3.14).

### 3.6 Schema phase

**mysqlshell** (`load_schema_mysqlshell`, L333-377 / P334-378):

```
-- driver connection, database='default'
create database if not exists <clickhouse_database>        -- unquoted; any exception is logged at ERROR and swallowed
-- driver connection, database=<clickhouse_database>
<translated CREATE TABLE ...>                              -- one statement per schema file, sequential, same connection
```

The dump zone is read from `<dump_dir>/@.json` by `find_mysqlshell_dump_timezone` before any table is created
(§3.9). Statements run through `clickhouse_execute_conn`
(`cursor.execute` + `fetchall`). The first failing `CREATE TABLE` raises out of `main`, so later tables are
not created and the process exits 1.

**mydumper** (`load_schema`, L287-330 / P288-331):

```
-- driver connection, database='default'
<contents of <src_db>-schema-create.sql.gz verbatim>        -- e.g. CREATE DATABASE /*!32312 IF NOT EXISTS*/ `mydb` /*!40100 ... */;
-- driver connection, database=<clickhouse_database>
<translated CREATE TABLE ...>
```

The database created is the **source** name from the dump, not `--clickhouse_database`. The `IF NOT EXISTS`
sits inside a MySQL version comment, which ClickHouse treats as a comment. Re-runs therefore fail on the
database, and on every table, because the ANTLR DDL has no `IF NOT EXISTS` (D-13.04-20). The time zone is
read from every schema file (`SET TIME_ZONE='...'`). Files without the statement are ignored; two files
naming different zones raise `ValueError` (no glob-order winner).

The translated statement is unqualified unless the source DDL was qualified. ANTLR copies `tableName` text
verbatim, so `` CREATE TABLE `mydb`.`qt` `` stays qualified with the source schema (reproduced). Tables
otherwise land in the connection's database, `--clickhouse_database`.

### 3.7 Data phase, MySQL Shell layout (`load_data_mysqlshell`, L486-554 / P455-522)

Per schema file (glob order), the main thread:

1. reads the table's MySQL Shell metadata `<db>@<table>.json` (`read_mysqlshell_decode_columns`:
   `options.decodeColumns`, the decode function per encoded column; `None` when the file is absent, which
   logs a WARNING if the table has binary, BIT or spatial columns), then builds `columns` =
   `get_column_list(transform=False)` and `transformed_columns` =
   `get_column_list(transform=True, mysqlshell=True, decode_columns=..., binary_handling_mode=..., persist_raw_bytes=...)`;
2. with `--truncate_tables` and not `--dry_run`, opens a new driver connection and executes
   `truncate table <schema>.<table_name>`, where `<schema>` is the **source** schema parsed from the file name
   (L518-521/P486-489; reproduced `truncate table mydb.t1` while the target was `mydb_ch`). This is
   D-13.04-8.
3. For every data file it builds the input structure. For each column of `schema_map[db.table]` that is not
   filtered out (filter in §3.7.1), it adds `` \`name\` <type> ``. `<type>` is `Nullable(<translated type>)`
   or `<translated type>` when the **MySQL** type contains `timestamp`. Every other column gets
   `Nullable(String)` or `String`, by the column's `nullable` flag. `'` in a type is escaped as `\'`.
4. It submits the command below to the pool.

```
export TZ=<unix_tz>; zstd -d --stdout <data_file>  | clickhouse-client <--config-file F> --use_client_time_zone 1 \
  --throw_if_no_data_to_insert=0  --max_partitions_per_insert_block=1000 -h <host> --port <port> <--secure> \
  --query="INSERT INTO <clickhouse_database>.<table>(<columns>)  SELECT <transformed_columns> FROM input('<structure>') FORMAT TSV" \
  -u<user> <--password ...> -mn
```

Reproduced (legacy, password `pa'ss word`; before the D-13.04-2 fix the zone was `Africa/Abidjan`, now it
is `UTC` for a `tzUtc` dump). The string is run as `bash -o pipefail -c '<cmd>'` (§3.10):

```
export TZ=UTC; zstd -d --stdout /backups/dump/mydb@t1@@1.tsv.zst  | clickhouse-client --config-file ./clickhouse-client.xml
 --use_client_time_zone 1 --throw_if_no_data_to_insert=0  --max_partitions_per_insert_block=1000 -h ch-host --port 9000
 --query="INSERT INTO mydb_ch.t1(\`id\`,\`ts\`,\`payload\`,\`note\`)  SELECT \`id\`,\`ts\`,\`payload\`,\`note\` FROM
 input(' \`id\`  String,  \`ts\`  Nullable( DateTime64(0)),  \`payload\`  Nullable(String),  \`note\`  Nullable(String)') FORMAT TSV"
 -uloader --password 'pa'"'"'ss word' -mn
```

The packaged copy emits `--password 'pa'ss word'`. The quote is unbalanced, so the shell command breaks.
Different password text can inject shell syntax (D-13.04-11).

Every value except `TIMESTAMP` columns is parsed as `String` and converted by ClickHouse during the
`INSERT ... SELECT` to the column type (integers, `Decimal`, `Date32`, `DateTime64`, `Enum`).
`TIMESTAMP` columns are parsed as `DateTime64(p)`, in the client zone given by `TZ` (§3.9).

#### 3.7.1 Column list (`get_column_list`, `is_loaded_column`, `mysqlshell_column_expression`)

- When the table is missing from `schema_map`, the result is `*`, a broken statement in both paths.
- A column is kept when `is_loaded_column`: `(source_column or name not in virtual_columns) and not generated`.
  Every ANTLR column dict carries `source_column: True`, because the ANTLR translator's `columns_map` holds
  only MySQL columns (the bookkeeping columns are appended to the DDL, never to the map). So:
  - Generated columns are always excluded. They are `MATERIALIZED` in the target and cannot be inserted.
  - The bookkeeping columns `_version`, `is_deleted`/`_is_deleted` and `_sign` are never in the INSERT, so
    the target defaults apply (§3.11).
  - A **source** column is always loaded whatever its name: `is_deleted`, `_sign` (with
    `--rmt_delete_support`) and `_is_deleted` (when the bookkeeping column is `is_deleted`) are data. A source
    column whose name equals a bookkeeping column the translator appends is refused at translation (§3.11), so
    it can neither be dropped nor land in the bookkeeping column (D-13.04-6, fixed).
  - `--virtual_columns` still filters the regexp translator's dicts, which list the bookkeeping columns and
    carry no `source_column` key (those dicts then fail on `generated`, D-13.04-18).
- Identifiers are rendered as `` \`name\` ``. The backticks of the parsed name are escaped for the
  double-quoted shell string. No other escaping is done: `$`, `"` or `\` in a name are interpreted by the
  shell.
- **Binary, BIT and spatial columns (mysqlshell, `transform=True`).** MySQL Shell writes every column whose
  `DATA_TYPE` ends in `binary` or `blob`, every `BIT` and every spatial column with `TO_BASE64(col)` (its
  default `useBase64: true`) or `HEX(col)`, and records the inverse (`FROM_BASE64`/`UNHEX`) per column in the
  table's `<db>@<table>.json` under `options.decodeColumns` (MySQL Shell `instance_cache.cc` `csv_unsafe`,
  `dumper.cc` `encoding_type`/`decode_column`; the MySQL Shell manual's dump utility page states that columns
  not safe to store as text, such as `BLOB`, are converted to Base64). `mysqlshell_binary_kind` classifies the column from its
  **MySQL** type (`mysql_datatype`: `bit`, a spatial type, or a keyword ending in `binary`/`blob`), and
  `mysqlshell_column_expression` builds the SELECT expression:

  | Step | Expression |
  |---|---|
  | decode `FROM_BASE64` (also assumed when the metadata file is absent) | `raw = base64Decode(replaceAll(col, char(10), ''))`. MySQL's `TO_BASE64` breaks lines every 76 characters, and ClickHouse's `base64Decode` rejects the newline (measured with `clickhouse local` 24.8). |
  | decode `UNHEX` | `raw = unhex(col)` |
  | not encoded per the metadata | `raw = col` |
  | spatial | `raw = substring(raw, 5)`: MySQL's internal value is a 4-byte SRID followed by the WKB; the connector stores the WKB only (Spec 07.06 §3.2, POINT(1 2) = `0101000000000000000000f03f0000000000000040`) |
  | binary type, `--binary_handling_mode base64` | `base64Encode(raw)` (no line breaks, like the connector's base64 text) |
  | binary type, `--binary_handling_mode hex` | `hex(raw)` (upper case, as Debezium's hex mode, Spec 07.05 §3.2) |
  | otherwise, `--persist_raw_bytes` | `raw` |
  | otherwise | `lower(hex(raw))`, the connector's default rendering |

  BIT(n) and spatial values reach the connector as bytes under every `binary.handling.mode` (Spec 07.05 §3.2,
  Spec 07.06 §3.2), so they ignore `--binary_handling_mode`. A NULL needs no special case: the input
  structure declares a nullable column `Nullable(String)` and every function propagates NULL. A column the
  metadata lists as encoded whose MySQL type has no rule (e.g. `VECTOR`) raises `ValueError`. BIT(1) is
  loaded as hex (`01`/`00`) into the `String` column the loader creates; the stream binds a Boolean there,
  which stays divergent until the type itself is aligned (D-13.04-10). The values of the default rendering
  (varbinary, a 60-byte blob with a wrapped base64 line, BIT(16), POINT, NULL) are checked with
  `clickhouse local` in `test_loader_s1_fixes.py::TestBinaryRepresentation::test_values_match_the_connector_rendering`.
- **mydumper (`transform=True`, `mysqlshell=False`)**: unchanged. `is_binary_datatype(datatype)` tests the
  translated type, so `lower(hex(col))` never applies. mydumper's binary rendering was not verified.

#### 3.7.2 Concurrency and ordering

- One `ThreadPoolExecutor(max_workers=--threads)` covers all chunks of all tables. Each task runs one shell
  pipeline, so at most `--threads` concurrent `clickhouse-client` INSERTs run. Each is a separate client
  process and server session.
- The submission order is glob order (tables), then chunk glob order. Completion order is arbitrary. The
  per-table `truncate` runs synchronously in the main thread before that table's chunks are submitted, while
  earlier tables' chunks may still be loading.
- No lock and no shared state exist, except the module-level `_REGISTERED_SECRETS` set (legacy). It is
  written in the main thread before any task starts.
- Errors: after every submission, `for f in as_completed(futures): if f.exception(): raise f.exception()`.
  The first failure in **completion** order is raised. Leaving the `with` block calls `shutdown(wait=True)`,
  so every other queued or running chunk still runs to completion. Then the exception propagates and the
  process exits 1 with a traceback. Reproduced: chunk `@@1` failed, and all 3 commands were still executed.
  Chunks already loaded stay loaded (FM-11.05-6). There is no retry, rollback, or per-table status.

### 3.8 Data phase, mydumper layout (`load_data`, L408-441 / P409-441)

1. When `--mysqlshell` is set, `load_data` first calls `load_data_mysqlshell` and does **not return**
   (L410-411/P411-412). It then runs the mydumper loop below over `*-schema.sql.gz`. That loop is empty for a
   MySQL Shell dump, but loads twice if both layouts share the directory.
2. The loop is sequential (no threads). For each schema file it prints the file path to **stdout** with
   `print`, then for each `.dat.gz` file:

```
export TZ=<unix_tz>; gunzip --stdout <data_file>  | sed -e 's/\\"/""/g' | sed -e "s/\\\'/'/g" | clickhouse-client <--config-file F> \
  --use_client_time_zone 1 -h <host> --port <port> <--secure> \
  --query="INSERT INTO <clickhouse_database>.<table>(<columns>)  SELECT <transformed_columns> FROM input('<structure>') FORMAT CSV" \
  -u<user> <--password ...> -mn
```

The structure is `columns.replace(",", " Nullable(String),") + " Nullable(String)"`, so every column is
`Nullable(String)`. The two `sed` passes rewrite mydumper's `\"` to CSV's `""` and `\'` to `'`. Other
backslash escapes (`\\`, `\n`, `\t`, `\0`) pass through literally (CSV has no backslash escapes), so values
holding them are stored with the escape text, not the character. This is code-read. mydumper's escaping is
vendor behaviour that was not reproduced here. Unlike the mysqlshell path, there is no
`--throw_if_no_data_to_insert=0` and no `--max_partitions_per_insert_block` override (the server default
of 100 applies).

### 3.9 Time zones

- **Dump zone, MySQL Shell** (`find_mysqlshell_dump_timezone`). MySQL Shell's dump option `tzUtc` (default
  `true`: `Ddl_dumper_options::m_timezone_utc = true` in `modules/util/dump/ddl_dumper_options.h`) makes every
  dump session run `SET TIME_ZONE = '+00:00'` (`Dumper::on_init_thread_session` in
  `modules/util/dump/dumper.cc`), so `TIMESTAMP` values are written in UTC, and records the option as
  `"tzUtc"` in the dump's `@.json`. The per-table `.sql` files carry no `SET TIME_ZONE` (the schema dumper's
  `write_header`, which writes it for mysqldump-style output, is not called for them). Vendor documentation:
  the `tzUtc` option of `util.dumpInstance()`/`dumpSchemas()`/`dumpTables()` in the MySQL Shell manual
  (https://dev.mysql.com/doc/mysql-shell/8.0/en/mysql-shell-utilities-dump-instance-schema.html). Hence:
  - `@.json` with `"tzUtc": true`: `'+00:00'`, mapped to `UTC`.
  - `"tzUtc": false`: `ValueError`. The values are in the dump session's zone, which the dump does not
    record, and loading them as UTC would shift every `TIMESTAMP`.
  - `@.json` missing (WARNING) or without a boolean `tzUtc` (WARNING): `None`, mapped to `UTC` with a WARNING.
    A malformed `@.json` raises.
  `ch-mysql-resync` hard-links `@.json` into each per-table directory (`isolate_table_dir`), so its loads
  read the flag. The in-tree dumpers never set `tzUtc`, so their dumps are UTC.
- **Dump zone, mydumper**: `find_dump_timezone(schema text)` (`SET TIME_ZONE='(.*?)'`, case-insensitive,
  first match) per schema file; conflicting files raise (§3.6); possibly `None`.
- **MySQL zone value to IANA name** (`get_unix_timezone_from_mysql_timezone`, identical in both copies):
  - `'+00:00'`/`'-00:00'` → `UTC`.
  - Another whole-hour offset within MySQL's range (±14:00) → the fixed-offset zone `Etc/GMT∓N` (POSIX sign:
    `+05:00` → `Etc/GMT-5`), when zoneinfo knows it. Never a regional zone whose offset merely equals the value
    today: a DST zone would shift the other half of the year.
  - A valid offset that no fixed-offset IANA zone represents (`+05:30`, `+05:45`, `-13:00`) → `ValueError`
    asking for a UTC re-dump. No zone choice would be correct.
  - A named zone known to zoneinfo (e.g. `Europe/Paris`) → itself.
  - `None`, empty, `SYSTEM`, out of range or malformed → `UTC` with a WARNING ("cannot be determined").
  - The result does not depend on `PYTHONHASHSEED` or on the date of the run.
  `Etc/GMT-5`, `Etc/GMT+12` and `Etc/GMT-14` are listed in ClickHouse's `system.time_zones` (24.8), and
  `TZ=Etc/GMT-5 clickhouse local --use_client_time_zone 1` parses `2026-07-01 12:00:00` as 07:00:00 UTC.
- **Before the fix (D-13.04-2).** The packaged copy iterated an unsorted set, discarded `sorted()`, matched
  offsets at `datetime.now()` and returned the last iterated zone when nothing matched, so the result varied
  with `PYTHONHASHSEED` (a January `'+00:00'` gave `Europe/Dublin`, `WET` or `Antarctica/Troll` for 4 of 10
  seeds; `None` gave `Japan`, `Europe/Lisbon`, ...). The legacy copy sorted and returned the first zone with
  the matching offset today (`Africa/Abidjan` for `+00:00`), else `UTC`: deterministic for `+00:00`, but a
  seasonal choice for other offsets.
- **Application.** The name is exported as `TZ` in the shell command, and clickhouse-client runs with
  `--use_client_time_zone 1`. TIMESTAMP text (UTC in MySQL Shell dumps) is meant to be interpreted in that
  zone. Whether `input()` parsing honours the client zone was not verified offline (GAP in §5.2). DATETIME
  values travel as `String` and are converted by the server into a zone-less `DateTime64(p)` column (server
  zone), or into the `--clickhouse_datetime_timezone` zone when one is set.
- **Column zone.** Only with `--clickhouse_datetime_timezone` (ANTLR `add_timezone`, LS28-31) does
  `DateTime64(p)` become `DateTime64(p,'<tz>')`. The regexp translator never adds a zone.

### 3.10 Command execution, logging, exit codes

- `run_quick_command(cmd)` runs `Popen(['bash', '-o', 'pipefail', '-c', cmd], stdout=PIPE, stderr=STDOUT)`
  and `communicate()`, so all output is buffered in memory. The return code is `str(process.poll())`. It logs
  stdout at INFO and `command failed : terminating` at ERROR when `rc != "0"`. `bash` must be on `PATH`
  (otherwise `FileNotFoundError` fails the load).
- **The pipeline's status is that of its last failing stage** (`pipefail`). A failing `zstd`/`gunzip`/`sed`
  makes the command non-zero, `execute_load` raises, and the run exits 1 (§3.7.2). Before the fix the command
  ran under `/bin/sh` without `pipefail`, and with `wc -l` standing in for clickhouse-client a truncated
  `.tsv.zst` gave `rc = 0` with 91 500 of 200 000 rows, a missing file `rc = 0` with 0 rows, and a truncated
  `.dat.gz` `rc = 0` with 49 865 of 100 000 rows (D-13.04-4, fixed). A truncated chunk is now refused even
  when the prefix ends on a row boundary, because the decompressor itself reports the truncation.

  `--throw_if_no_data_to_insert=0` is kept: a valid empty chunk (an empty table) must load. A missing or
  unreadable chunk no longer reaches it, because the decompressor fails first.
- `execute_load(cmd)` (L475-483/P444-452) logs the command at INFO, redacted in the legacy copy and in clear
  text in the packaged one. If `args.dry_run` is set it returns. Otherwise it raises
  `AssertionError("command " + cmd + " failed")` on non-zero rc. The message holds the **unredacted** command,
  password included, in both copies (FM-11.05-2, D-13.04-12).
- `run_command` (L28-44) is never called.
- The ANTLR parser keeps antlr4's default `ConsoleErrorListener` in addition to `MyErrorListener`, so every
  syntax error is also printed to stderr (`line 3:21 no viable alternative at input ...`).
- The translator listener logs every column dict and the final DDL at INFO, and `load_schema*` logs every
  source DDL and translated DDL at INFO. Schema text therefore appears in the logs.

| Outcome | Exit code | Data state |
|---|---|---|
| All statements and pipelines return 0 | 0 | Loaded, but **not verified** against the dump's row counts. Chunk files the glob does not find still exit 0 (D-13.04-19). |
| A decompressor or `sed` stage fails | 1, after all submitted chunks finish | partial table(s) |
| Undeterminable dump zone | continues, WARNING | `TIMESTAMP` read as UTC |
| `tzUtc: false` dump, unrepresentable offset, conflicting mydumper zones, bookkeeping-name collision, or a keyless table the translators cannot key | 1 (`ValueError` / `UnsafeTableDefinitionError`) | tables created before the failing one remain |
| `assert` failure (missing clickhouse-client, mydumper mode, password without user, config file missing) | 1 (traceback) | nothing done |
| CREATE DATABASE fails (mysqlshell) | continues | the next CREATE TABLE fails |
| CREATE TABLE / CREATE DATABASE (mydumper) / truncate fails | 1 | tables created so far remain |
| A data pipeline returns non-zero | 1, after all submitted chunks finish | partial table(s) |
| No mysqlshell schema files | 1 (`TypeError`) | database created |

No post-load verification exists. Nothing compares `count()` with the dump (MySQL Shell records chunk row
counts in its metadata, which the loader never reads). `ch-mysql-resync` adds its own exact count check
(11.04).

### 3.11 Engine, bookkeeping columns and version semantics

ANTLR (`exitColumnCreateTable`, LS156-184) appends to the source columns:

| `--rmt_delete_support` | Source has `is_deleted` column | Appended columns | Engine |
|---|---|---|---|
| yes | no | `` `_version` UInt64 DEFAULT 0 ``, `` `is_deleted` UInt8 DEFAULT 0 `` | `engine=ReplacingMergeTree(_version,is_deleted)` |
| yes | yes | `` `_version` UInt64 DEFAULT 0 ``, `` `_is_deleted` UInt8 DEFAULT 0 `` | `engine=ReplacingMergeTree(_version,_is_deleted)` |
| no | any | `` `_version` UInt64 DEFAULT 0 ``, `` `_sign` Int8 DEFAULT 1 `` | `engine=ReplacingMergeTree(_version)` |

**Bookkeeping-name collisions.** Before appending, `exitColumnCreateTable` compares the source column names
(backticks stripped, exact case, as ClickHouse compares) with the two columns it is about to append. A match
raises `UnsafeTableDefinitionError` naming the column: `_version` always; `_sign` without
`--rmt_delete_support` (the message suggests the flag); `_is_deleted` when the source also has `is_deleted`.
Without the check the CREATE carried a duplicate column, and a `--data_only` load would have written the MySQL
values into the bookkeeping column or left them out. The streaming DDL path renames the delete flag to
`_is_deleted` for a source `is_deleted` the same way (`MySqlDDLParserListenerImpl.enterColumnCreateTable`).

The regexp translator (L188-200) appends `` `is_deleted` UInt8 DEFAULT 0, `_version` UInt64 DEFAULT 0 `` (or
`_sign`/`_version`) and `ENGINE = ReplacingMergeTree(_version[, is_deleted]) ... SETTINGS index_granularity = 8192`.
It does **not** rename for a source `is_deleted`, which produces a duplicate column (reproduced).

Neither translator emits `PRIMARY KEY`, `Replicated*` engines, `ON CLUSTER`, TTL or other settings.

**Version value of loaded rows.** `_version`, `is_deleted`/`_is_deleted` and `_sign` are never in the INSERT
column list (§3.7.1), so every snapshot row gets the column defaults: `_version = 0`, `is_deleted = 0`,
`_sign = 1`.

**Comparison with the streaming connector.** On the lightweight path the connector writes `_version` as
`effectiveTs × 10^6 + counter` (≈1.8·10^18). On the GTID path it is a snowflake (≈2·10^18), with
`snowflake.id=false` the raw GTID transaction number (≥ 1), and for PostgreSQL the LSN (Spec 02.01 §3.1).
An underivable version is refused (Spec 02.05). Hence:

- A streamed row always has `_version > 0` and **always outranks** a snapshot row of the same key. A snapshot
  row and a streamed row can **never tie**. This is the intended contract: changes applied after the dump
  point win. A key deleted after the dump gets a streamed `is_deleted = 1` row with a positive version, which
  wins.
- Snapshot rows tie only with each other (all 0). A duplicate load of the same key (re-run without truncate,
  or the double load of §3.8 item 1) keeps whichever part ReplacingMergeTree considers last. The contents are
  identical when both come from one dump.
- A **re-load into a table that already holds streamed rows cannot change them.** Snapshot rows (version 0)
  lose to every streamed row, so `--data_only` over a live table does not repair divergence. That is why
  `ch-mysql-resync` loads into a scratch table and uses `REPLACE PARTITION` (11.04). This is a design
  property, not a defect. The safe use of the loader is an empty target.
- Default divergence: without `--rmt_delete_support` the loader creates the legacy `_sign`/
  `ReplacingMergeTree(_version)` shape. Streaming auto-create on ClickHouse ≥ 23.2 creates
  `_version UInt64, is_deleted UInt8` with `ReplacingMergeTree(_version,is_deleted)` (Spec 08.05 §3.1). The
  Java columns have no `DEFAULT 0`, which makes no difference for named inserts. `ch-mysql-resync` always
  passes `--rmt_delete_support`.

### 3.12 ORDER BY, PRIMARY KEY and PARTITION BY

| Source | ANTLR (LS91-92, LS136-146, LS175-183) | Regexp (L140-149, L199-200) |
|---|---|---|
| Table-level `PRIMARY KEY (a, b)` | `order by (`a`,`b`)`. The raw text of `indexColumnNames`, parentheses included. | `ORDER BY (<text inside the last parentheses on the PRIMARY KEY line>)` |
| Column-level `id int NOT NULL PRIMARY KEY` | `order by `id`` | `ORDER BY (tuple())`, plus a missing comma after the next column, so invalid DDL (reproduced) |
| No primary key, first `UNIQUE` key (table- or column-level) with every column `NOT NULL` | `order by (<unique key columns>)`, identifiers as written, prefix lengths and `ASC`/`DESC` dropped (`index_column_names`) | refused: `UnsafeTableDefinitionError` |
| No primary key, no such `UNIQUE` key | `order by (<every stored column in declaration order>)`, plus ` SETTINGS allow_nullable_key=1` when one of them is nullable. Generated columns are left out. A WARNING names the table and the key. | refused: `UnsafeTableDefinitionError` |
| No primary key and no stored column | refused: `UnsafeTableDefinitionError` (not passed to the regexp fallback) | n/a |

**Parity with the streaming connector (D-13.04-1, fixed).** The listener's `sorting_key` reproduces the table
shape the streaming DDL path creates for the same source table, so a snapshot-created table and a
stream-created table agree:
`sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java`
lines 652-667 (the first UNIQUE key is adopted only when every column is NOT NULL; table-level keys at
line 1160, column-level at line 1314, column names from `indexColumnNames` at line 1471), lines 704-724 (the
all-columns fallback over the stored, non-generated columns in declaration order, refusing a table with none)
and line 870 (`allow_nullable_key=1` only when the fallback key names a nullable column). The record-schema
path builds the same identity (`sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java`
lines 347 and 466, `keylessSortingKey`). Known residual differences, both for hand-written DDL only: the
Python side takes nullability from the column's own `NOT NULL` modifier, while Java also treats
`AUTO_INCREMENT` and `SERIAL` columns as NOT NULL (`SHOW CREATE TABLE`, which dumps contain, always prints
`NOT NULL` for them); and the Java path honours a schema-override `primary_key`, which the loader has no input
for (D-13.04-27).
| PK with prefix length `PRIMARY KEY (name(10))` | `order by (`name`(10))`, not a valid ClickHouse key (D-13.04-16) | `ORDER BY (10)`, a constant key that collapses all rows (D-13.04-16, latent because ANTLR does not raise) |
| PK with `DESC` | copied verbatim | copied |
| `PRIMARY KEY` clause in ClickHouse DDL | never emitted | never emitted |

**Partitioning.** The listener's `exitPartitionClause` (LS148-154) is bound to the grammar rule
`partitionClause`, which is the **window-function** `PARTITION BY` (`MySqlParser.g4:2541`), not the
CREATE TABLE partition definition (`partitionDefinitions`). It never fires for a table and would raise
`AttributeError` (no `partitionTypeDef`) if it did. The only partition source is the loader's regex
`find_partitioning_options` (L118-130): `PARTITION\s+BY\s+RANGE\s+COLUMNS\((.*?)\)`, applied to the raw text
including MySQL's `/*!50500 ... */` comment. It yields `PARTITION BY <cols>`, passed as `partition_options`
to both translators. Reproduced:

| MySQL partitioning | ClickHouse output |
|---|---|
| `RANGE COLUMNS(d)` (in a version comment or not) | `PARTITION BY d`: one partition per **distinct value** of `d` |
| `RANGE COLUMNS(id,d)` | `PARTITION BY id,d`: not a single expression. ClickHouse is expected to reject it (not verified offline). |
| `RANGE (year(d))`, `LIST`, `HASH`, `KEY`, subpartitions | no partitioning |

A raw DATE or DATETIME partition key makes one partition per day or per second. A load block that touches
more distinct values than `--max_partitions_per_insert_block` (1000 in the mysqlshell path, server default
100 in the mydumper path) fails with *Too many partitions for single INSERT block*. Even under the limit the
table accumulates very many parts (D-13.04-17). The streaming auto-create partitions only from the schema
override `partition_by` (Spec 08.05).

### 3.13 Translator selection (`convert_to_clickhouse_table`, L249-265)

1. If `CREATE TABLE` (case-insensitive) is absent from the text, return `('', [])`. The table is skipped
   silently and is not put in `schema_map`.
2. `partition_options = find_partitioning_options(source)`.
3. `try: return convert_to_clickhouse_table_antlr(source, rmt, partition_options, datetime_timezone)`.
4. `except UnsafeTableDefinitionError`: re-raised. A refusal (keyless table without a stored column,
   bookkeeping-name collision) is not a parse failure and must not be papered over by the regexp translator.
5. `except Exception`: log `Use regexp DDL converter` and the exception at **INFO**, then return the regexp
   translation (FM-11.05-5). Only translator exceptions are caught. ClickHouse rejecting the ANTLR DDL is
   not a translator exception, so the fallback is never used for semantic errors.

ANTLR raises on syntax errors (`MyErrorListener.syntaxError`, `mysql_parser.py:16-17`) and on listener
exceptions. A probe of 19 MySQL 8 constructs found exactly one rejected: `point NOT NULL SRID 4326` written
outside a version comment. MySQL's own `SHOW CREATE TABLE` writes `/*!80003 SRID 4326 */`, which the lexer
hides. Accepted constructs included functional indexes, CHECK constraints, expression defaults,
`/*!80023 INVISIBLE */`, `ON UPDATE CURRENT_TIMESTAMP(3)`, FULLTEXT, foreign keys and
`serial`/`bool`/`int8`/`real`/`national varchar`/`long varchar`. So the regexp path is rarely reached.
When it is, its output cannot be loaded (D-13.04-18).

### 3.14 ANTLR translator internals

`convert_to_clickhouse_table_antlr(source, rmt_delete_support, partition_options='', datetime_timezone=None)`
(`mysql_parser.py:20-35`) builds `InputStream → MySqlLexer → CommonTokenStream → MySqlParser`, adds
`MyErrorListener`, parses `sqlStatements()` and walks the tree with `CreateTableMySQLParserListener`. It
returns `(listener.buffer, listener.columns_map)`. The lexer sends `/*! ... */` to a hidden channel
(`SPEC_MYSQL_COMMENT`), so version-gated clauses (partitioning, SRID, INVISIBLE, `IF NOT EXISTS` in
`CREATE DATABASE`) are invisible to the listener. The module's `main(argv)` translates one file with
`rmt_delete_support=True` and logs the result at DEBUG level.

Listener callbacks that matter for CREATE TABLE:

| Callback | Effect |
|---|---|
| `enterColumnCreateTable` (LS141-146) | resets buffer, columns, `columns_map`, sets `primary_key='tuple()'`, `partition_keys=None` |
| `exitColumnDeclaration` (LS108-134) | appends `<fullColumnName text><translated definition>` to `columns` and a dict to `columns_map` |
| `exitPrimaryKeyTableConstraint` (LS136-139) | `primary_key` = raw `indexColumnNames` text |
| `exitColumnCreateTable` (LS156-184) | assembles `CREATE TABLE <tableName text> (<cols>,\n...) engine=... <partition> order by <pk>` |

The other callbacks are dead or out of scope for the loader. `exitAlterList` (LS189-245) handles a rule
the grammar does not have, and it calls the undefined `translateFieldDefinition`. `exitAlterTable`,
`exitRenameTable`, `exitTruncateTable` and `exitDropTable` (LS247-282) overwrite the buffer with
pass-through text. A dump with such statements does not reach them, because dump files contain one
CREATE TABLE.

Column dict schema (one per declared column, generated columns included):

| Key | Value |
|---|---|
| `column_name` | the identifier as written, backticks included (`` `id` ``) |
| `datatype` | the translated ClickHouse type text (§3.15) |
| `nullable` | bool (§3.16) |
| `mysql_datatype` | the original MySQL type text |
| `generated` | True for `GENERATED ALWAYS AS` |
| `has_is_deleted_column` | the running flag: True from the first column named `is_deleted` on |

The regexp translator returns dicts with only `column_name`, `datatype` and `nullable`, and it includes the
appended bookkeeping columns.

### 3.15 Complete MySQL-to-ClickHouse type mapping as built

ANTLR `convertDataType` (LS33-54) handles types as follows:

1. Strip `CHARACTER SET.*` (case-insensitive).
2. Strip `CHARSET.*`, case-insensitive (`flags=re.IGNORECASE`). Before the fix the flag was passed as the
   positional `count`, so a lower-case `charset latin1` survived into the ClickHouse DDL (D-13.04-28, fixed).
3. `DATE` becomes `Date32`.
4. `DATETIME`/`TIMESTAMP` become `DateTime64(<fsp or 0>)[,'tz']`.
5. `TIME` becomes `String`.
6. `JSON`, or any type for which `is_binary_datatype(text)` holds, becomes `String`.
7. **Everything else is passed through verbatim.** ClickHouse then resolves the MySQL spelling through its
   MySQL-compatibility aliases.

The table lists the as-built output, reproduced by calling both translators. The ClickHouse resolution
column cites the type names present in ClickHouse's `system.data_types` (21.8 to 25.8). Acceptance of the
exact emitted text by a server was **not** verified offline.

| MySQL type (SHOW CREATE TABLE form) | ANTLR output (legacy = packaged unless noted) | Regexp output | ClickHouse resolution of the emitted text | Streaming DDL path (Specs 07.x, 08.05) | Divergence |
|---|---|---|---|---|---|
| `tinyint`, `tinyint(1)` | verbatim | verbatim | `TINYINT` → Int8 (display width tolerated) | `Int8` (Spec 07.01) | none |
| `tinyint unsigned` | verbatim | verbatim | `TINYINT UNSIGNED` → UInt8 | `UInt8` | none |
| `smallint [unsigned]` | verbatim | verbatim | Int16 / UInt16 | same | none |
| `mediumint [unsigned]` | verbatim | verbatim | `MEDIUMINT` → Int32, `MEDIUMINT UNSIGNED` → UInt32 | Int32 / UInt32 | none |
| `int`, `int unsigned`, `int(10) unsigned` | verbatim | verbatim | Int32 / UInt32 (width+modifier combination unverified) | Int32 / UInt32 | none |
| `bigint [unsigned]` | verbatim | verbatim | Int64 / UInt64 | Int64 / UInt64 | none |
| `... zerofill` (`int(10) unsigned zerofill`) | verbatim, `zerofill` kept | verbatim | no alias contains ZEROFILL: CREATE expected to fail | ZEROFILL → unsigned `UInt*` (Spec 07.01 §3.2) | D-13.04-15 |
| `serial`, `int1..int4`, `int8`, `middleint`, `bool` (hand-written DDL only; SHOW CREATE TABLE normalises them) | verbatim | verbatim | `int8` is not an alias (only `INT1`, and `Int8` is case-sensitive). `serial` is unknown. | normalised (Spec 07.01 §3.2) | D-13.04-15 |
| `decimal(p,s)` incl. `decimal(65,30)` | verbatim | verbatim | `Decimal(p,s)`, P ≤ 76 | `Decimal(p,s)` | none |
| `float` | verbatim | verbatim | `FLOAT` → Float32 | `Float32` (Types.FLOAT) | none |
| `float(7,3)`, `double(10,2)` | verbatim, dimensions kept | verbatim | ClickHouse acceptance of dimensions on float types is version-dependent. Spec 07.02 §3.1 records a rejection of `Float64(7,3)`. | dimensions stripped | D-13.04-15 |
| `double`, `double precision`, `real` (shown as `double`) | verbatim | verbatim | Float64 | `Float64` (DOUBLE) / `Float32` (REAL) | none for `double` |
| `date` | `Date32` | `Date32` | Date32 | `Date`/`Date32` (Spec 07.03) | none in practice |
| `datetime` | `DateTime64(0)` | `DateTime64(3)` | zone-less: server zone | `DateTime64(0,'UTC')` (Spec 08.05 §3.1.1) | zone (D-13.04-10); regexp precision |
| `datetime(p)` | `DateTime64(p)` | `DateTime64(p)` | zone-less | `DateTime64(p,'UTC')` | zone (D-13.04-10) |
| `timestamp`, `timestamp(p)` | `DateTime64(0)` / `DateTime64(p)`, `+ ,'tz'` with the option | **`String`**: `\stime(.*?)\s` runs before the timestamp rule | zone-less | `DateTime64(p,'UTC')` | zone (D-13.04-10); regexp type (D-13.04-18) |
| `time`, `time(p)` | `String` | `String` | String | `String`, always 6 fractional digits (Spec 07.03 §3.2) | value text: MySQL Shell writes `12:00:00` for TIME(0) where the stream writes `12:00:00.000000` (D-13.04-10) |
| `year` | verbatim `year` | verbatim | `YEAR` alias (22.5+) → UInt16 | `UInt16`/`Int32` | minor |
| `bit(1)` | `String` | `String` | String | `Bool` (Spec 07.04 §3.1) | **type** (D-13.04-10); value loaded as hex `01`/`00` (§3.7.1) |
| `bit(n>1)` | `String` | `String` | String | `String` (hex) | none since the D-13.04-3 fix (§3.7.1) |
| `enum('a','b,c','it''s')` | verbatim | verbatim | `ENUM` → Enum8/Enum16 with auto-numbered values. `''` escaping and commas inside labels survive. | `String` (`Types.CHAR`) | **type** (D-13.04-10) |
| `set('x','y')` | verbatim | `String` | `SET` alias (22.5+) is an integer type: CREATE with string arguments expected to fail | `String` | D-13.04-15 |
| `json` | `String` | `String` | String | `String` | value text: MySQL rendering vs Debezium re-rendering (Spec 07.04 §3.1) |
| `char(n)`, `varchar(n)`, `*text` | verbatim, `CHARACTER SET x` stripped | verbatim, `CHARACTER SET`/`COLLATE` stripped | String aliases | `String` | none |
| `varchar(64) COLLATE utf8mb4_bin` (column collation without charset, MySQL 8's normal form) | `varchar(64) COLLATE utf8mb4_bin` | stripped | a MySQL collation name in a ClickHouse COLLATE clause: CREATE expected to fail (not verified) | `String` | D-13.04-15 |
| `binary(n)`, `varbinary(n)`, `blob`, `tinyblob`, `mediumblob`, `longblob` | `String`. Packaged: upper-case `MEDIUMBLOB`/`VARBINARY(16)` pass through verbatim (still String aliases). | `binary(n)` → String; `blob`/`longblob`/`varbinary(n)` verbatim (String aliases) | String | `String` (hex by default) | none since the D-13.04-3 fix, with `--binary_handling_mode`/`--persist_raw_bytes` set like the connector (§3.7.1) |
| `geometry`, `point`, `linestring`, `polygon`, `multi*`, `geomcollection` | `String` | `String` (`multi*` partly verbatim) | String | `String` (WKB hex, Spec 07.06 §3.4) | none since the D-13.04-3 fix (§3.7.1) |
| `geometrycollection` | legacy `String`; packaged **verbatim** (missing from its tuple) | verbatim | unknown to ClickHouse | `String` | D-13.04-22 |
| `enum('bit','byte')` | legacy verbatim enum; packaged **`String`** (`"bit" in datatype`) | verbatim | | `String` | copies diverge (D-13.04-22) |

### 3.16 Column attributes

| Attribute | ANTLR (LS56-106) | Regexp |
|---|---|---|
| `NOT NULL` | appended verbatim. `nullable=False`. | kept. `nullable=False`. |
| `NULL`, any case (`null`, `Null`) | appended verbatim. `nullable=True` (case-insensitive test `text.upper() == "NULL"`). Before the fix only the exact upper-case text matched, and a lower-case `null` fell into the NOT branch with `nullable=False`, so the load structure declared `String` (D-13.04-7, fixed). | kept. `nullable=True`. |
| no modifier | ` NULL` appended. `nullable=True`. MySQL columns are nullable by default; ClickHouse columns are not. | `DEFAULT NULL` appended on lines without `NULL`/`DEFAULT`, but the type is **not** made Nullable |
| `DEFAULT <expr>`, `ON UPDATE`, `AUTO_INCREMENT`, `COMMENT`, `INVISIBLE`, `SRID` | dropped (never copied). The target has no defaults except the bookkeeping columns. | `\bDEFAULT\b.*,` removes up to the **last comma on the line**. `AUTO_INCREMENT` removed. |
| `GENERATED ALWAYS AS (expr) VIRTUAL\|STORED` | `<type> NULL MATERIALIZED <expr>`. The MySQL expression is copied verbatim after `re.sub(r"\b_.*?'", "'", text)` strips charset introducers (`_utf8mb4'x'` → `'x'`). That regex also deletes any text from a word starting with `_` up to the next quote, e.g. `` if((`_x` > 0),'pos','neg') `` → `` if((`'pos','neg') `` (reproduced, D-13.04-23). VIRTUAL and STORED both become MATERIALIZED (D-13.04-9). | the column line is deleted |
| `CHARACTER SET` / `COLLATE` | `CHARACTER SET x [COLLATE y]` stripped. A bare `COLLATE y` is kept. | both stripped |
| Indexes, `UNIQUE`, `FOREIGN KEY`, `CHECK`, `CONSTRAINT`, `FULLTEXT` | ignored | lines containing `KEY`, `UNIQUE`, `CONSTRAINT`, `foreign` (case-sensitive words) are deleted. A column line containing upper-case `KEY` is deleted with them. |
| Table options (`ENGINE=InnoDB`, charset, row format, comments) | ignored | `ENGINE=InnoDB[^;]*` is replaced by the ClickHouse engine clause. Other engines (`MyISAM`) are left in. |

### 3.17 Regexp translator internals (`convert_to_clickhouse_table_regexp`, L133-246)

The substitutions are applied in this order to the whole statement text:

1. `CREATE TABLE` → `CREATE TABLE IF NOT EXISTS `.
2. Delete `/* ... */` comments, which drops MySQL's partition comment from the body, so partitioning comes
   only from `partition_options`.
3. `AUTO_INCREMENT` → ``.
4. Type rules: `\stime\s` and `\stime(.*?)\s` → String (this also catches `timestamp`), `\sjson\s` → String,
   `\sdate\s` → Date32, `\sdatetime\s` → DateTime64(3), `\sdatetime(.*?)\s` → DateTime64\1, the
   timestamp rules (unreachable), `\spoint\s` → Point, `\sgeometry\s` → Geometry.
5. `\bDEFAULT\b.*,` → `,`.
6. Strip COLLATE and CHARACTER SET.
7. Delete generated-column lines and `VIRTUAL`.
8. Delete CONSTRAINT/PRIMARY KEY/UNIQUE/KEY/foreign lines.
9. Insert the bookkeeping columns before `) ENGINE` and replace `ENGINE=InnoDB...`.
10. Line loop: append ` DEFAULT NULL` to lines lacking NULL/DEFAULT. Match `^\s*(`.*?`)\s+(.*?)\s+` to record
    column dicts. Append missing commas to matched lines.
11. Second pass: Point/Geometry/geomcollection/linestring/multi*/polygon/`bit`/`bit(n)`/`binary`/`binary(n)`/
    `set(...)` → String.
12. Drop the trailing comma after `_version`.

The column dicts lack `generated`/`has_is_deleted_column`, so any data load after a regexp translation
raises `KeyError: 'generated'` in `get_column_list` (reproduced).

### 3.18 Reproduced translations of tricky DDL (ANTLR, `rmt_delete_support=True`)

Each case was produced by calling `convert_to_clickhouse_table_antlr(src, rmt, find_partitioning_options(src), None)`
on both copies. The output is identical in both except where §3.15 notes otherwise.

| Input fragment | Output fragment |
|---|---|
| `` `id` int(10) unsigned NOT NULL AUTO_INCREMENT `` | `` `id` int(10) unsigned NOT NULL `` |
| `` `zf` int(10) unsigned zerofill DEFAULT NULL `` | `` `zf` int(10) unsigned zerofill NULL `` |
| `` `d1` decimal(65,30) DEFAULT NULL `` | `` `d1` decimal(65,30) NULL `` |
| `` `dtt6` datetime(6) DEFAULT NULL `` | `` `dtt6` DateTime64(6) NULL `` |
| `` `ts` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP `` | `` `ts` DateTime64(0) NOT NULL `` (default and ON UPDATE dropped) |
| `` `e` enum('a','b,c','it''s') DEFAULT 'a' `` | `` `e` enum('a','b,c','it''s') NULL `` (default dropped) |
| `` CREATE TABLE `order` (`select` int NOT NULL, `from` varchar(10) NOT NULL, `My Col` int ..., PRIMARY KEY (`select`,`from`)) `` | `` CREATE TABLE `order` (`select` int NOT NULL, `from` varchar(10) NOT NULL, `My Col` int NULL, ... order by (`select`,`from`) ``. Backticks preserved, reserved words and spaces safe. |
| keyless table `a int NOT NULL, msg varchar(20) NOT NULL, UNIQUE KEY u (msg(10),a)` | `... engine=ReplacingMergeTree(_version,is_deleted)  order by (`msg`,`a`)` |
| keyless table `a int DEFAULT NULL, b varchar(10) NOT NULL, g int GENERATED ... VIRTUAL` | `... order by (`a`,`b`) SETTINGS allow_nullable_key=1` |
| `` `b` int GENERATED ALWAYS AS ((`a` * 2)) VIRTUAL `` | `` `b` int NULL MATERIALIZED (`a` * 2) `` |
| `` `c` varchar(20) GENERATED ALWAYS AS (concat(_utf8mb4'x_', `a`)) STORED `` | `` `c` varchar(20) NULL MATERIALIZED concat('x_', `a`) `` |
| `/*!50500 PARTITION BY RANGE COLUMNS(d) (...) */` | `engine=ReplacingMergeTree(_version,is_deleted) PARTITION BY d order by (`id`,`d`)` |
| `/*!50100 PARTITION BY RANGE (year(`d`)) ... */` | no PARTITION BY |
| `PRIMARY KEY (`name`(10))` | `order by (`name`(10))` |
| source column `is_deleted tinyint` | `` `is_deleted` tinyint NULL, `_version` UInt64 DEFAULT 0, `_is_deleted` UInt8 DEFAULT 0 ) engine=ReplacingMergeTree(_version,_is_deleted) `` |
| source column `_version bigint` | `UnsafeTableDefinitionError` naming `_version` (was a duplicate column and a failing CREATE) |
| source column `_sign int` with `rmt_delete_support=True` | `` `_sign` int NULL `` kept as a data column, loaded |
| `CREATE TABLE `mydb`.`qt`` | `CREATE TABLE `mydb`.`qt`` (source schema kept) |

### 3.19 Quoting and escaping summary

| Item | Where | Quoting |
|---|---|---|
| Column identifiers in DDL | ANTLR | as written in the source (backticks kept) |
| Table name in DDL | ANTLR | as written (possibly schema-qualified) |
| Database in `create database if not exists` | mysqlshell | none |
| `INSERT INTO <db>.<table>` | both data paths | none. Names needing quoting (`-`, space, percent-encoded names) break the statement. |
| `truncate table <db>.<table>` | mysqlshell | none |
| Column names in the INSERT list and `input()` structure | both | `` \` `` inside the double-quoted `--query`. `$`, `"`, `\` are not escaped. |
| `--dump_dir`, data file paths | shell | none (spaces and metacharacters break or inject) |
| Password | legacy | `shlex.quote` |
| Password | packaged | `'...'` without escaping |
| `--config-file` | legacy / packaged | `shlex.quote` / `'...'` |
| `TZ` value | shell | none (IANA names are safe) |

### 3.20 Column type overrides (`ch_sink_tools/config/column_type_overrides.py`, packaged only)

Data model:
- `DirectOverride(database, schema, table, column, target_type)` replaces the mapped type at CREATE time.
- `AliasOverride(column, alias_type, expression, database='*', schema='*', table='*')` adds a companion
  `ALIAS` column named `alias_column_name = f"{column}_{normalize_type_name(alias_type)}"`.
  `normalize_type_name` lower-cases the type, maps non-alphanumerics to `_`, collapses runs, and ends with
  exactly one `_` (`DateTime64(3)` → `datetime64_3_`).

**Consumers.** Only the PostgreSQL dumper (`postgres_dumper.py:1815-1953`, `postgres_type_mapper.build_column_defs`).
**The MySQL loader does not read overrides at all.** The streaming MySQL DDL path applies
`column_type_override.direct.*` and `.alias.*` (`MySqlDDLParserListenerImpl.java` around lines 765 and
1557), so a table created by this loader lacks configured override types and alias columns (D-13.04-27).

Configuration sources (`from_cli_args`: file takes precedence over string, neither gives `None`):

1. YAML (`from_yaml`, `yaml.safe_load`). The top-level key `column_type_overrides` is optional (the whole
   document is used otherwise):
   ```yaml
   column_type_overrides:
     direct:
       - table: "mydb.public.events"     # db.schema.table | schema.table | table | "*"
         column: "created_at"
         target_type: "DateTime64(3)"
     alias:
       - table: "mydb.public.events"
         column: "created_at"
         alias_type: "DateTime64(3)"
         expression: "parseDateTime64BestEffort(created_at)"
   ```
   The table spec is split by `_split_table_spec`: three or more parts give `(p0, p1, '.'.join(rest))`, two
   parts give `('*', schema, table)`, one part gives `('*','*',table)`, `'*'` gives all wildcards. Entries
   missing `column`/`target_type` (or `alias_type`/`expression`) are skipped with a WARNING.
2. CLI string (`from_cli_string`): comma-separated `direct:<qualified col>=<type>` and
   `alias:<qualified col>=<type>|<expr>`. The qualified column is split by `_split_qualified_column` (four
   or more, three, two or one parts). The string is split on **every comma**, so `Decimal(18,2)`,
   `DateTime64(3,'UTC')` and multi-argument expressions are cut. Reproduced:
   `direct:public.t.amount=Decimal(18,2)` gives target type `Decimal(18` plus a warning
   `unknown entry prefix in '2)'` (D-13.04-24).
3. Connector properties (`from_connector_properties`): keys `column_type_override.direct.<db>.<schema>.<table>.<col>`
   (four parts, or three with db `*`), values `<type>` or `<type>|<expr>`. Other part counts are skipped with
   a WARNING. Returns `None` when nothing is found. The PostgreSQL dumper uses it when no file or string was
   given.

Lookups. Every comparison is case-insensitive, except that a wildcard on an entry is only recognised as the
literal `'*'`.

- `get_direct_override(db, schema, table, col)` returns, in priority order: an exact match;
  `*.schema.table.col`; `*.*.table.col`; `*.*.*.col`. Within one priority level the last entry wins. **Any
  other wildcard shape never matches** (e.g. `mydb.*.events`, or `*.public.*`). Reproduced: entry
  `('mydb','*','events','ts')` gives `None` (D-13.04-25).
- `get_direct_overrides(db, schema, table)` and `get_alias_overrides(db, schema, table)` match each part
  exactly or by `'*'` **independently**, so `mydb.*.events` does match there. The reconciler uses these
  plural forms while CREATE uses the singular form, so the two disagree about which entries apply.

### 3.21 Override reconciler (`ch_sink_tools/config/override_reconciler.py`, packaged only)

`reconcile_overrides_with_existing_table(ch_conn, ch_database, table_name, schema, override_config, logger_override=None, database='*')`
is called by the PostgreSQL dumper when the target table already exists and overrides are configured. It
runs these steps:

1. Return if there are no overrides. Query
   `SELECT name, type, default_kind, default_expression FROM system.columns WHERE database = '<db>' AND table = '<t>'`
   (values interpolated into single quotes without escaping). Return if no rows come back.
2. **Direct.** For each `get_direct_overrides(database, schema, table)` entry, look up the CH column by
   **exact, case-sensitive** name. When the column is absent, log at DEBUG "will be created" and skip,
   although nothing creates a column in an existing table. When present, compare
   `_strip_nullable(existing) == _strip_nullable(configured)` as **exact strings**. On mismatch, log at ERROR
   and raise `ColumnTypeOverrideMismatchError`. The message lists four remedies: DROP TABLE, ALTER MODIFY,
   change the config, or remove the override. The reconciler itself never alters a direct-override column.
3. **Alias.** For each `get_alias_overrides` entry:
   - If the alias column is missing, run
     `` ALTER TABLE `db`.`t` ADD COLUMN `<alias>` <type> ALIAS <expr> ``.
   - If it exists and its type, expression or `default_kind` differs from the configuration (exact string
     compares), run `` ALTER TABLE `db`.`t` MODIFY COLUMN `<alias>` <type> ALIAS <expr> ``.
   ALTERs run immediately on the passed connection. There is no dry run.

ClickHouse reports types in normalised form (`Decimal(18, 2)`, `DateTime64(3, 'UTC')`) and expressions as
formatted ASTs. With the reconciler as built:
- a configured `Decimal(18,2)` against an existing `Nullable(Decimal(18, 2))` raises a false mismatch
  (reproduced with a mocked `system.columns` row);
- an alias expression `parseDateTime64BestEffort(ts,3)` is re-ALTERed on every run against CH's
  `parseDateTime64BestEffort(ts, 3)` (reproduced);
- an override for `ts` misses the CH column `Ts`, and no mismatch is raised (reproduced).

The Java reconciler compares types with `equalsIgnoreCase` and trims expressions (`ColumnTypeOverrideReconciler.java:164-214`).
This is D-13.04-26.

### 3.22 Legacy-vs-packaged differences (behaviour)

| Function / area | Legacy | Packaged | Effect |
|---|---|---|---|
| Imports | `from db.clickhouse import *`. `sys.path` is appended **after** the imports that need it (L21-25), so it only works with `PYTHONPATH` set to `sink-connector/python`. | explicit package imports | packaging only |
| `get_unix_timezone_from_mysql_timezone`, `find_mysqlshell_dump_timezone` | identical (§3.9) | identical | none (D-13.04-2 fixed in both) |
| Listener fixes (D-13.04-1/6/7/28), `get_column_list`/`mysqlshell_column_expression` (D-13.04-3), `run_quick_command` (D-13.04-4), mydumper data glob (D-13.04-5) | identical | identical | none |
| Password quoting | `shlex.quote` | `'...'` | D-13.04-11 |
| Log redaction | `register_secret` + `redact_password` | none: full command at INFO | D-13.04-11 (FM-11.05-1) |
| Failure exception text | unredacted command | unredacted command | D-13.04-12 (both) |
| Password source, mysqlshell path | resolved password (CLI or config file) on argv | CLI `--clickhouse_password` only; config-file creds stay in the config file | D-13.04-13 (legacy) |
| Password source, mydumper path | resolved password | resolved password | D-13.04-13 (both) |
| `load_data` → `load_data_mysqlshell` | passes `dry_run` | passes `dry_run=False`; ignored anyway (`execute_load` reads `args.dry_run`) | none |
| `is_binary_datatype` (`db/mysql.py` vs `ch_sink_tools/db/mysql.py`) | keyword before `(`, lower-cased, exact match against 16 names | substring test (`blob`, `binary`, `varbinary`, `bit`, case-sensitive), then exact lower-case match against 12 names | D-13.04-22: `enum('bit')` and `char(10) binary` become String in packaged; `geometrycollection`, `MEDIUMBLOB` and `VARBINARY(16)` stay verbatim in packaged |
| `clickhouse_execute_conn` | closes the cursor in `finally` | does not close | resource only |
| `resolve_credentials_from_config` | `yaml.safe_load`, password masked in DEBUG | `yaml.FullLoader`, password in clear in DEBUG | not emitted by the loader (INFO) |
| Listener, `mysql_parser.py`, generated parser | identical (imports only) | identical | none |

### 3.23 Relationship with neighbouring specs

- Spec 13.03 produces the MySQL Shell dump this loader reads. The loader does not read `@.json` metadata,
  binlog position, chunk row counts or `@.done.json` completeness, so an incomplete dump is loaded silently
  (D-13.04-19). The position hand-off gap was D-13.03-1, now fixed in the dumper: it verifies the dump and
  writes `snapshot_position.json` into the dump directory (Spec 13.03, 3.8). The loader still does not read it.
- Spec 11.04 (`ch-mysql-resync`) wraps the packaged loader per table, adds exact count reconciliation and an
  optional canary, and avoids `--truncate_tables`. FM-11.04-8 is the downstream effect of the rendering
  differences recorded here (D-13.04-10; D-13.04-3 is fixed, but `ch-mysql-resync` passes no
  `--binary_handling_mode`, so a connector running `binary.handling.mode=base64` needs the flag through
  `--loader-cmd`).
- Spec 11.05 owns the unit-test contract. FM-11.05-1, -2, -4, -5 and -6 are restated here with their full
  control flow.

---

## 4. Invariants Preserved

| ID | Invariant | Status as built |
|---|---|---|
| INV-13.04-1 | Every loaded row carries `_version = 0` and `is_deleted = 0` (or `_sign = 1`), strictly below every streamed version, so streaming changes after the dump point always win and never tie. | **Holds** (§3.11) |
| INV-13.04-2 | A table is created as `ReplacingMergeTree` keyed so that distinct source rows stay distinct. | **Holds** for keyless tables (keyed like the stream, §3.12; rows identical in every column still collapse, as on the stream). **Violated** for prefix PKs (D-13.04-16) |
| INV-13.04-3 | Column types equal the types the streaming DDL path would declare for the same source column. | **Violated** for ENUM, BIT(1), DATETIME/TIMESTAMP zone, generated columns, overrides (D-13.04-9/10/27) |
| INV-13.04-4 | Values are stored in the same canonical rendering the streaming writer stores. | **Holds** for binary/BIT(n>1)/spatial when `--binary_handling_mode`/`--persist_raw_bytes` match the connector (§3.7.1). **Violated** for BIT(1) and TIME(0) (D-13.04-10). Unverified for TIMESTAMP zone handling. |
| INV-13.04-5 | Every source row in the dump is loaded, or the loader exits non-zero. | **Violated** only by chunk files the glob does not find and the absent count check (D-13.04-19). D-13.04-4/5/6 are fixed. |
| INV-13.04-6 | A failed chunk makes the run fail (non-zero exit). | **Holds** (clickhouse-client, decompressor and `sed` failures, §3.10) |
| INV-13.04-7 | Generated columns are not inserted. | **Holds** (§3.7.1) |
| INV-13.04-8 | The loader writes only to `--clickhouse_database`. | **Violated** by `--truncate_tables` (D-13.04-8) and by the mydumper `CREATE DATABASE` (D-13.04-20) |
| INV-13.04-9 | Credentials appear neither in logs nor on process argv. | **Violated** (D-13.04-11/12/13) |
| INV-13.04-10 | The translation is deterministic (same dump gives same DDL and same load). | **Holds** for DDL and the time zone (D-13.04-2 fixed). Load order is glob order. |

---

## 5. Verification Criteria

### 5.1 Existing tests (offline) and results

Run from `sink-connector/python` with the toolset venv Python:

- Loader suites: `pytest -q -p no:cacheprovider db_load/tests/test_clickhouse_loader_unit.py db_load/tests/test_loader_failure_modes.py`
  gave **22 passed, 2 skipped**. The skips are the declared defects FM-11.05-1 and FM-11.05-2.
- Full offline baseline (`db_compare/tests db_load/tests db_dump/tests tests`): **227 passed, 5 skipped** on
  2.11.0. With the S1 fixes and `db_load/tests/test_loader_s1_fixes.py` (83 tests, every one parametrised over
  both copies): **310 passed, 5 skipped**.

What the existing tests pin (all legacy unless noted):

- `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py::TestSchemaPathParsing::test_parse_schema_path_mydumper`, `::TestSchemaPathParsing::test_parse_schema_path_mysqlshell`, `::TestSchemaPathParsing::test_mysqlshell_table_with_special_chars`: file-name parsing (§3.4).
- `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py::TestDumpTimezone::test_finds_set_time_zone`, `::TestDumpTimezone::test_case_insensitive`, `::TestDumpTimezone::test_absent_returns_none`: `find_dump_timezone`.
- `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py::TestUnixTimezoneFromMysqlTimezone::test_utc_offset_resolves_to_a_zero_offset_zone`, `::TestUnixTimezoneFromMysqlTimezone::test_unknown_offset_falls_back_to_utc`: legacy mapping only; both still pass after the D-13.04-2 fix.
- `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py::TestSourceIntrospection::test_find_primary_key`, `::TestSourceIntrospection::test_find_partitioning_options_range_columns` and the related absent/true/false cases: regex helpers.
- `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py::TestDdlConversionAntlr::test_returns_ddl_and_columns`, `::TestDdlConversionAntlr::test_datetime_maps_to_datetime64`, `::TestDdlConversionAntlr::test_decimal_precision_preserved`, `::TestDdlConversionAntlr::test_replacing_merge_tree_engine`, `::TestDdlConversionAntlr::test_order_by_primary_key`, `::TestDdlConversionAntlr::test_datetime_timezone_applied_when_configured`, `::TestDdlConversionAntlr::test_no_create_table_returns_empty`: structural ANTLR contract.
- `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_logged_command_is_redacted`: legacy log redaction, and a failing load raises.
- `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_failure_message_is_redacted`: skipped, DEFECT D-13.04-12.
- `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestPackagedLoaderRedaction::test_logged_command_is_redacted`: packaged, skipped, DEFECT D-13.04-11.

`sink-connector/python/db_load/tests/test_loader_s1_fixes.py` covers both copies (offline; data pipelines run
with a stand-in `clickhouse-client` script, binary values are checked with `clickhouse local`, skipped when
it is not installed):

- `TestDumpTimezoneMapping`, `TestMysqlShellDumpTimezone`: D-13.04-2 (UTC, `Etc/GMT±N`, named zones,
  WARNING + UTC when undeterminable, refusal of `+05:30` and of `tzUtc: false`, identical result for
  `PYTHONHASHSEED` 0-5, `@.json` read by `load_schema`).
- `TestKeylessSortingKey`: D-13.04-1 (all-columns key with `allow_nullable_key`, NOT NULL UNIQUE key
  table- and column-level, nullable UNIQUE fallback, PK unchanged, refusals not passed to the regexp fallback,
  regexp refusal).
- `TestSourceColumnsNamedLikeBookkeeping`: D-13.04-6. `TestColumnModifiers`: D-13.04-7, D-13.04-28.
- `TestBinaryRepresentation`: D-13.04-3 (expressions per mode and per decode function, values via
  `clickhouse local`).
- `TestPipelineFailure`: D-13.04-4 (`false | cat`, a truncated `.tsv.zst` through `load_data_mysqlshell`, a
  truncated `.dat.gz` through `load_data`, both with `execute_load` raising).
- `TestMydumperDataFileDiscovery`: D-13.04-5. `test_resync_isolated_table_dir_carries_the_dump_metadata`:
  the `@.json` hand-off to `ch-mysql-resync` loads.

No test covers the type mapping table, overrides or the reconciler.

### 5.2 Acceptance criteria (each is a test to add; GAP until added)

1. Keyless DDL never yields `order by tuple()` (both translators, both copies). **Covered** (§5.1).
2. `get_unix_timezone_from_mysql_timezone` is deterministic across `PYTHONHASHSEED` values, returns `UTC` for
   `None` or unknown offsets, and never returns a DST zone for a fixed offset (packaged). **Covered**.
3. For a MySQL Shell dump with a `varbinary` and a `bit(1)` column, the rendered INSERT applies the binary
   transform that matches the configured connector rendering. **Covered** (BIT(1) type still D-13.04-10).
4. A data pipeline whose decompressor fails makes the loader exit non-zero (`set -o pipefail` or equivalent).
   **Covered**. An empty chunk is refused unless the dump metadata says the chunk is empty: GAP (D-13.04-19).
5. The mydumper data glob is independent of `-` in `--dump_dir`. **Covered**.
6. `--truncate_tables` targets `--clickhouse_database`.
7. Generated columns are emitted as `DEFAULT (expr)`, matching Spec 06.06.
8. The type mapping in §3.15 equals the streaming DDL path's mapping for every row marked as divergent.
9. After loading, `count()` per table equals the dump's row count, or the loader exits non-zero.
10. The packaged loader redacts credentials in logs and exceptions, and quotes them with `shlex.quote`.
11. A server-backed integration test confirms the ClickHouse acceptance of every emitted type spelling in
    §3.15, and the zone used to parse `TIMESTAMP` text in `input()` under `--use_client_time_zone 1`. Both
    are unverified here because no database may be contacted.
12. Overrides: the CLI string accepts commas inside parentheses. Singular and plural lookups agree. The
    reconciler compares normalised types and expressions case-insensitively.

### 5.3 Offline reproduction scripts used by this spec

All scripts live in the throwaway repro directory (not part of the repo) and were run with the toolset venv
Python. No database was contacted: driver connections, `clickhouse-client` and `which` were mocked, and
`wc -l` stood in for clickhouse-client.

| Script | What it checks |
|---|---|
| `r1_translate.py` | both translators on 15 DDL cases (§3.15, §3.18) |
| `r2_flow.py` | `main()` end to end with mocks: SQL issued, command templates, truncate target, chunk failure, mydumper assertion, `-` in path, config-file password on argv, `--clickhouse_secure False` |
| `r3_overrides.py` | override parsing and lookups, reconciler with a mocked `system.columns` |
| `r4_pure.py` | time-zone mapping, dead binary transform, regexp metadata `KeyError`, virtual-column collisions, `is_binary_datatype` copies, CHARSET flag, generated-expression strip |
| `r5_dst.py` | the zone each copy picks for `+00:00` in January, across hash seeds |
| `r6_grammar.py` | which MySQL 8 constructs make ANTLR raise |
| `r7_pipefail.py` / `r8_zstd.py` | pipeline exit status with truncated or missing gzip/zstd chunks |

---

## 6. Failure Modes & Recovery

- **FM-13.04-1 Keyless source table collapses to one row**
  - **Trigger**: a MySQL table without `PRIMARY KEY` (with or without UNIQUE keys) is loaded.
  - **Behaviour**: both translators emit `ORDER BY tuple()` with `ReplacingMergeTree` (`CreateTableMySQLParserListener.py:145,182-183`, `clickhouse_loader.py:140-144`). Every row has the same sorting key, so background merges and `FINAL` keep one row (Spec 08.05 §3.2, measured there with clickhouse local).
  - **Detection**: `SELECT count() FROM t FINAL` falls far below the MySQL count. The checksum job (11.02) reports a mismatch. Nothing at load time.
  - **Blast radius**: all rows of every keyless table, silently, after the first merge.
  - **Recovery**: drop and recreate the table with a sorting key of all columns (as the connector does) or a declared identity, then reload.
  - **RTO**: unmeasured (no server). The time to recreate and reload the table.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestKeylessSortingKey::test_no_key_uses_every_stored_column_and_allows_nullable_key`
  - **FIXED**: D-13.04-1. The listener keys a keyless table like the streaming DDL path (NOT NULL UNIQUE key, else all stored columns with `allow_nullable_key=1` when needed, §3.12); the regexp fallback refuses it.

- **FM-13.04-2 Packaged loader interprets TIMESTAMP text in a random zone**
  - **Trigger**: `ch-mysql-load` or `ch-mysql-resync` (packaged) on any MySQL Shell dump, or a mydumper dump without `SET TIME_ZONE`.
  - **Behaviour**: `get_unix_timezone_from_mysql_timezone` iterates an unsorted set, keeps the last iterated zone when nothing matches, and matches offsets at `now()` (`ch_sink_tools/db_load/clickhouse_loader.py:268-285`). The zone is exported as `TZ` for `clickhouse-client --use_client_time_zone 1`. Reproduced: `None` gives `Japan`, `Europe/Lisbon` or `Europe/San_Marino` by seed, and a January run gives `Europe/Dublin`, `WET` or `Antarctica/Troll` for `+00:00` in 4 of 10 seeds.
  - **Detection**: the TIMESTAMP columns differ from MySQL by whole hours in a value-level checksum. Each loader log line shows `export TZ=<zone>`.
  - **Blast radius**: every TIMESTAMP value of every table in the run (all, or only DST-period values).
  - **Recovery**: re-run the load with the legacy copy (`--loader-cmd`) or with `PYTHONHASHSEED` pinned to a seed known to pick a UTC-equivalent zone, until fixed. Reload the affected tables.
  - **RTO**: unmeasured (no server). The time to reload the affected tables.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestDumpTimezoneMapping::test_deterministic_across_hash_seeds`
  - **FIXED**: D-13.04-2. Both copies map the zone deterministically (`UTC`, `Etc/GMT±N` or a named zone; WARNING + UTC when undeterminable) and take the MySQL Shell zone from `@.json` `tzUtc` (§3.9).

- **FM-13.04-3 Binary, BIT and spatial values loaded as base64 text**
  - **Trigger**: a MySQL Shell dump of a table with `binary`/`varbinary`/`blob`/`bit`/spatial columns.
  - **Behaviour**: `get_column_list(transform=True)` tests `is_binary_datatype` on the **translated** type `String` (`clickhouse_loader.py:398`, `CreateTableMySQLParserListener.py:127-130`). The decode transform never runs, so the dump's base64 text is stored verbatim. The connector stores lower-case hex by default, raw bytes with `persist.raw.bytes`, or base64 only under `binary.handling.mode=base64` (Spec 07.05 §3.2).
  - **Detection**: a value checksum reports mismatches on binary columns. `ch-mysql-resync`'s canary fails (FM-11.04-8).
  - **Blast radius**: every binary-family value in snapshot rows. Rows streamed later use the connector's rendering, so one column mixes two renderings.
  - **Recovery**: transform in place with `ALTER TABLE ... UPDATE col = lower(hex(base64Decode(col)))` on snapshot rows (`_version = 0`), or reload after a fix.
  - **RTO**: unmeasured (no server). The duration of one mutation per affected table.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestBinaryRepresentation::test_values_match_the_connector_rendering`
  - **FIXED**: D-13.04-3. The transform classifies by the MySQL type, decodes as the dump metadata says, and renders per `--binary_handling_mode`/`--persist_raw_bytes` (connector defaults: lower-case hex, §3.7.1).

- **FM-13.04-4 Decompressor failure is invisible and the chunk is loaded partially or not at all**
  - **Trigger**: a truncated, corrupt, unreadable or vanished `.tsv.zst` or `.dat.gz` chunk.
  - **Behaviour**: the pipeline status is clickhouse-client's (`/bin/sh` without pipefail, `clickhouse_loader.py:47-60,549` and P517). `--throw_if_no_data_to_insert=0` accepts empty input. Reproduced: truncated zst gives rc 0 with 91 500 of 200 000 rows, missing file gives rc 0 with 0 rows, truncated gz gives rc 0 with 49 865 of 100 000 rows.
  - **Detection**: only the INFO log line with zstd's message (`premature end`, `can't stat`). A later count check (11.04 does one) or checksum catches it.
  - **Blast radius**: the rows of the affected chunk. Exit code 0.
  - **Recovery**: re-dump or repair the chunk. Truncate the target table and reload it.
  - **RTO**: unmeasured (no server). Reload time of one table.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestPipelineFailure::test_truncated_mysqlshell_chunk_fails_the_load`
  - **FIXED**: D-13.04-4. Pipelines run under `bash -o pipefail`, so a failing decompressor or `sed` fails the load (exit 1, §3.10).

- **FM-13.04-5 mydumper dump directory containing `-` loads nothing**
  - **Trigger**: mydumper layout with a `-` anywhere in `--dump_dir` or in a table name. This requires first getting past D-13.04-14.
  - **Behaviour**: `files.split("-")[0]` truncates the full path, and the `.dat.gz` glob is empty (`clickhouse_loader.py:428-430`). Reproduced: `dump-mydumper/` gives 0 commands, exit 0.
  - **Detection**: empty tables after a successful run.
  - **Blast radius**: every table of the dump.
  - **Recovery**: move or rename the dump directory without `-`, then re-run `--data_only`.
  - **RTO**: unmeasured (no server). The rename plus a full data load.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestMydumperDataFileDiscovery::test_dash_in_dump_dir_still_finds_data`
  - **FIXED**: D-13.04-5. The data glob strips only the `-schema.sql.gz` suffix and glob-escapes the prefix (§3.4).

- **FM-13.04-6 Source column named like a bookkeeping column is not loaded**
  - **Trigger**: a source column `_sign` (with `--rmt_delete_support`), `_is_deleted`, or `_version`.
  - **Behaviour**: the virtual-column filter drops the column from the INSERT (`clickhouse_loader.py:388,528`). `_sign` stays NULL silently (reproduced: load list `` \`id\` `` only). `_version` produces a duplicate column and CREATE fails.
  - **Detection**: the column is entirely NULL, and a checksum mismatch appears.
  - **Blast radius**: one column of the affected tables.
  - **Recovery**: pass `--virtual_columns` without the colliding name, or rename at the source, then reload.
  - **RTO**: unmeasured (no server). Reload time of the table.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestSourceColumnsNamedLikeBookkeeping::test_source_sign_and_is_deleted_lookalikes_are_loaded`
  - **FIXED**: D-13.04-6. Every ANTLR source column is loaded whatever its name; a collision with an appended bookkeeping column raises `UnsafeTableDefinitionError` (§3.7.1, §3.11).

- **FM-13.04-7 Lower-case `null` turns NULL into empty string**
  - **Trigger**: hand-written DDL with a lower-case `null` modifier (`SHOW CREATE TABLE` writes `NULL`).
  - **Behaviour**: `nullable=False` (`CreateTableMySQLParserListener.py:65,77-86`) while the column is Nullable, so the input structure declares `String` and the TSV `\N` cannot be NULL. Reproduced metadata `nullable False` for `` `a` varchar(10) null ``. The conversion of `\N` to `''` follows ClickHouse's `input_format_null_as_default`, not verified offline.
  - **Detection**: count of NULLs differs in a checksum.
  - **Blast radius**: NULLs of the affected columns.
  - **Recovery**: `ALTER TABLE ... UPDATE col = NULL WHERE col = ''` only if `''` never occurs at the source. Otherwise reload.
  - **RTO**: unmeasured (no server). One mutation or one reload.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestColumnModifiers::test_lower_case_null_is_nullable`
  - **FIXED**: D-13.04-7. The NULL modifier test is case-insensitive (§3.16).

- **FM-13.04-8 `--truncate_tables` truncates a table in the source-named database**
  - **Trigger**: `--mysqlshell --truncate_tables` where a ClickHouse database named like the MySQL schema exists (typically the live replica, while `--clickhouse_database` is a scratch database).
  - **Behaviour**: `truncate table <mysql_source_database>.<table>` (`clickhouse_loader.py:518-521`, P486-489). Reproduced: `truncate table mydb.t1` with target `mydb_ch`. When the source-named database does not exist, the statement fails and the run aborts. The target table is never truncated.
  - **Detection**: the live replica table is suddenly empty. Spec 11.04 documents the hazard and never passes the flag.
  - **Blast radius**: one full live table per loaded table.
  - **Recovery**: restore the live table (resync per 11.04, or reload from a dump while streaming continues). Rows streamed after the truncate are newer and survive a re-load.
  - **RTO**: unmeasured (no server). A full table reload.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-8.

- **FM-13.04-9 Generated columns become MATERIALIZED and the stream stalls**
  - **Trigger**: a table with `GENERATED ALWAYS AS` columns is created by the loader and then streamed.
  - **Behaviour**: `<type> NULL MATERIALIZED <mysql expr>` (`CreateTableMySQLParserListener.py:93-105`). The connector inserts generated values, which ClickHouse rejects for MATERIALIZED columns (Code 44, Spec 06.06 §3.1). MySQL-only functions in the expression make the CREATE itself fail.
  - **Detection**: connector insert errors and growing lag on that table, or a CREATE error at load.
  - **Blast radius**: replication of the table, and the batch it shares.
  - **Recovery**: `ALTER TABLE ... MODIFY COLUMN c <type> DEFAULT <expr>` (metadata only), then resume the connector.
  - **RTO**: unmeasured (no server). One ALTER plus connector catch-up.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-9.

- **FM-13.04-10 Snapshot types or renderings differ from the stream's**
  - **Trigger**: ENUM, BIT(1), SET, DATETIME/TIMESTAMP, TIME(0) or JSON columns.
  - **Behaviour**: as tabulated in §3.15. ENUM becomes `Enum8` (a later MySQL label, or MySQL's invalid `''`, then fails to insert). BIT(1) becomes `String` (the stream writes Bool renderings into it). DATETIME/TIMESTAMP have no zone. TIME(0) text lacks `.000000`.
  - **Detection**: value checksum mismatches and connector insert errors on Enum columns.
  - **Blast radius**: affected columns of affected tables. ENUM failures stall replication.
  - **Recovery**: `ALTER TABLE ... MODIFY COLUMN` to the streaming type, then fix the renderings by mutation or reload.
  - **RTO**: unmeasured (no server). ALTER and mutation duration.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-10.

- **FM-13.04-11 Credentials exposed**
  - **Trigger**: a run with `--clickhouse_password` (packaged), a failing load (legacy), or any legacy run with config-file credentials.
  - **Behaviour**: the packaged copy logs `--password '<pw>'` at INFO and builds a broken or injectable shell string for passwords with quotes (P421,466,445). Both copies raise `AssertionError` containing the full command. The legacy copy puts `--password <pw>` from the config file on the clickhouse-client argv (L418-421,494-498). All of this was reproduced.
  - **Detection**: grep the logs, or `ps` during a load.
  - **Blast radius**: the ClickHouse loader account.
  - **Recovery**: rotate the password, scrub the logs, and run with `--clickhouse_config_file` (packaged mysqlshell path) until fixed.
  - **RTO**: unmeasured (no server). The time to rotate the credential.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestPackagedLoaderRedaction::test_logged_command_is_redacted` and `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_failure_message_is_redacted` (both skipped as defects). The argv exposure is a GAP.
  - **DEFECT**: D-13.04-11, D-13.04-12, D-13.04-13.

- **FM-13.04-12 One chunk fails to load**
  - **Trigger**: clickhouse-client returns non-zero (parse error, server down, too many partitions).
  - **Behaviour**: `execute_load` raises. `as_completed` re-raises the first failure after `shutdown(wait=True)` has let every other submitted chunk finish (`clickhouse_loader.py:552-554`, P520-522). Reproduced: 3 of 3 commands ran and the exit code was 1. Partial data remains, with no retry or cleanup (FM-11.05-6).
  - **Detection**: non-zero exit and a traceback with `command ... failed`.
  - **Blast radius**: the failed table is partial. Other tables in the run are complete or partial.
  - **Recovery**: fix the cause, truncate the affected target tables, and re-run `--data_only`. Snapshot rows tie at version 0, so a re-run without truncation is idempotent in content but doubles parts until merged.
  - **RTO**: unmeasured (no server). Reload of the affected tables.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_logged_command_is_redacted` (a failing load raises).

- **FM-13.04-13 mydumper mode cannot run**
  - **Trigger**: any run without `--mysqlshell`.
  - **Behaviour**: `assert args.mysqlshell and check_program_exists('zstd')` fails (`clickhouse_loader.py:639-640`, P607-608). Reproduced: `AssertionError: zstd should be in the PATH for util.dumpSchemas load`. Under `python -O` all asserts vanish and the checks are skipped.
  - **Detection**: immediate traceback.
  - **Blast radius**: the mydumper workflow (no data touched).
  - **Recovery**: none without a code change. Use a MySQL Shell dump.
  - **RTO**: unmeasured (no server). Immediate failure.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-14.

- **FM-13.04-14 Emitted DDL is rejected by ClickHouse**
  - **Trigger**: ZEROFILL, SET, a column-level COLLATE, float dimensions, prefix-length PK, multi-column RANGE COLUMNS, or a generated expression with `_`-prefixed identifiers.
  - **Behaviour**: the text is passed verbatim (§3.15, §3.12). The first rejected CREATE aborts the schema phase (exit 1). Later tables are not created.
  - **Detection**: an exception from the driver at CREATE TABLE.
  - **Blast radius**: the schema phase stops at the first bad table.
  - **Recovery**: create the offending tables by hand with corrected DDL, then run `--data_only`.
  - **RTO**: unmeasured (no server). Manual DDL time.
  - **Test**: GAP (needs a server-backed test, §5.2 item 11).
  - **DEFECT**: D-13.04-15, D-13.04-16, D-13.04-17, D-13.04-23.

- **FM-13.04-15 Raw partition key creates too many partitions**
  - **Trigger**: MySQL `PARTITION BY RANGE COLUMNS(<date or datetime column>)`.
  - **Behaviour**: `PARTITION BY <col>` gives one partition per distinct value (`clickhouse_loader.py:118-130`). Blocks spanning more than 1000 (mysqlshell) or 100 (mydumper) values fail, and otherwise parts accumulate.
  - **Detection**: `Too many partitions for single INSERT block`, or a high `system.parts` count.
  - **Blast radius**: one table. Merge pressure affects the server.
  - **Recovery**: recreate with `PARTITION BY toYYYYMM(col)` (or none) and reload.
  - **RTO**: unmeasured (no server). The time to recreate and reload.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-17.

- **FM-13.04-16 Regexp fallback produces unusable output**
  - **Trigger**: ANTLR raises on the DDL, e.g. a plain `SRID 4326`.
  - **Behaviour**: INFO log `Use regexp DDL converter` (`clickhouse_loader.py:262-265`). Then TIMESTAMP becomes `String`, a source `is_deleted` is duplicated, a column-level PK is lost, and `DEFAULT NULL` lands on non-Nullable columns. Any data load raises `KeyError: 'generated'` (reproduced). This is FM-11.05-5.
  - **Detection**: the INFO line, a CREATE error or the `KeyError` traceback.
  - **Blast radius**: the table, or the whole data phase (the `KeyError` aborts the run).
  - **Recovery**: create the table by hand and run `--data_only` (the re-parse falls back again and fails). In practice, edit the DDL in the dump so ANTLR accepts it.
  - **RTO**: unmeasured (no server). Manual DDL time.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-18.

- **FM-13.04-17 Incomplete dump loaded as complete**
  - **Trigger**: missing chunk files, a non-zstd MySQL Shell dump, or a dump still in progress.
  - **Behaviour**: data files are taken from globs only, `@.done.json` and chunk metadata are never read, and there is no `count()` check (§3.10). The table loads with fewer or zero rows and exit 0.
  - **Detection**: an external count or checksum (11.02, 11.04).
  - **Blast radius**: any table of the dump.
  - **Recovery**: complete the dump, truncate, reload.
  - **RTO**: unmeasured (no server). Reload time.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-19.

- **FM-13.04-18 Schema phase cannot be re-run, or creates the wrong database**
  - **Trigger**: re-running without `--data_only`, or a mydumper dump with `--clickhouse_database` different from the source name.
  - **Behaviour**: the ANTLR DDL has no `IF NOT EXISTS`, so CREATE fails. mydumper runs the dump's `CREATE DATABASE <source>` verbatim (reproduced). mysqlshell swallows a database-creation error (`clickhouse_loader.py:339-344`).
  - **Detection**: `Table ... already exists` or `Database ... doesn't exist` exceptions.
  - **Blast radius**: the run aborts. Nothing is overwritten.
  - **Recovery**: use `--data_only` for re-runs, and pre-create the target database.
  - **RTO**: unmeasured (no server). Minutes of operator time.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-20.

- **FM-13.04-19 `--clickhouse_secure False` enables TLS**
  - **Trigger**: passing any value to `--clickhouse_secure`.
  - **Behaviour**: string truthiness (L589-590): `'False'` gives `--secure` and `secure=True` (reproduced).
  - **Detection**: connection errors against a plain-text port.
  - **Blast radius**: the run cannot connect.
  - **Recovery**: omit the flag for plain text.
  - **RTO**: unmeasured (no server). Immediate.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-21.

- **FM-13.04-20 Snapshot re-loaded over streamed rows has no effect**
  - **Trigger**: `--data_only` into a table that already holds streamed rows, expecting it to repair divergence.
  - **Behaviour**: snapshot rows carry `_version = 0` and lose to every streamed row (§3.11). Only keys absent from the table appear. This is the designed precedence.
  - **Detection**: the checksum still fails after the "repair".
  - **Blast radius**: none beyond the unrepaired divergence.
  - **Recovery**: use `ch-mysql-resync` (scratch table plus `REPLACE PARTITION`, 11.04).
  - **RTO**: unmeasured (no server). One resync run.
  - **Test**: GAP: a unit test asserting that `_version` and `is_deleted` are never in the INSERT column list.

- **FM-13.04-21 Packaged binary predicate diverges from the legacy one**
  - **Trigger**: the packaged loader on `enum('bit',...)`, `char(n) binary`, `geometrycollection` or upper-case binary types.
  - **Behaviour**: substring and case-sensitive matching (`ch_sink_tools/db/mysql.py:13-17`). Reproduced: `enum('bit','byte')` becomes `String` in packaged but stays an Enum in legacy, and `geometrycollection` is passed verbatim in packaged.
  - **Detection**: different DDL from the two copies.
  - **Blast radius**: the affected columns (type, and CREATE failure for `geometrycollection`).
  - **Recovery**: ALTER the column type, or create the table by hand.
  - **RTO**: unmeasured (no server). One ALTER.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-22.

- **FM-13.04-22 Override CLI string cut at commas**
  - **Trigger**: `--column_type_overrides "direct:s.t.c=Decimal(18,2)"` (PostgreSQL dumper).
  - **Behaviour**: `s.split(',')` (`column_type_overrides.py:254`) gives target type `Decimal(18`. CREATE then fails, or alias expressions are truncated (reproduced).
  - **Detection**: WARNING `unknown entry prefix`, then a DDL error.
  - **Blast radius**: the dumper run.
  - **Recovery**: use the YAML file form.
  - **RTO**: unmeasured (no server). Immediate.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-24.

- **FM-13.04-23 Override applies in reconciliation but not at CREATE**
  - **Trigger**: an override entry with a specific database and a wildcard schema (or wildcard table), e.g. `mydb.*.events`.
  - **Behaviour**: `get_direct_override` ignores it, so CREATE uses the default type. `get_direct_overrides` matches it, so the next run raises `ColumnTypeOverrideMismatchError` (`column_type_overrides.py:138-155` vs `:191-196`, reproduced).
  - **Detection**: the mismatch error on the second run.
  - **Blast radius**: the dumper is blocked for the table.
  - **Recovery**: spell the entry as one of the four supported shapes.
  - **RTO**: unmeasured (no server). Config edit.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-25.

- **FM-13.04-24 Reconciler false mismatch or missed mismatch**
  - **Trigger**: a configured type spelled differently from ClickHouse's normalised form, or different column-name case.
  - **Behaviour**: exact-string compares (`override_reconciler.py:153,161,178,258,265`). Reproduced: `Decimal(18,2)` vs `Nullable(Decimal(18, 2))` raises, `ts` vs `Ts` passes silently, and the alias is re-ALTERed on every run.
  - **Detection**: the mismatch error, or ALTER log lines on every run.
  - **Blast radius**: the dumper is blocked for the table, or the mismatch goes unnoticed.
  - **Recovery**: copy ClickHouse's exact spelling into the config.
  - **RTO**: unmeasured (no server). Config edit.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-26.

- **FM-13.04-25 MySQL snapshot ignores configured column type overrides**
  - **Trigger**: `column_type_override.*` set for MySQL tables in the connector config, with tables created by the loader.
  - **Behaviour**: the loader has no override input (§3.20). The streaming DDL path would apply the overrides (`MySqlDDLParserListenerImpl.java` around line 1557).
  - **Detection**: column types and alias columns differ from the configuration.
  - **Blast radius**: overridden columns.
  - **Recovery**: ALTER the columns, or add the alias columns by hand.
  - **RTO**: unmeasured (no server). ALTER duration.
  - **Test**: GAP.
  - **DEFECT**: D-13.04-27.

Summary: 25 failure modes, 16 DEFECT, 17 GAP.

---

## 7. Defect Register

| ID | Severity | Copy (legacy/packaged/both) | Location | Evidence | Summary |
|---|---|---|---|---|---|
| D-13.04-1 | S1 | both | `db_load/mysql_parser/CreateTableMySQLParserListener.py:145,182-183`; `db_load/clickhouse_loader.py:140-144,195-200` (same lines in `ch_sink_tools/`) | reproduced (`r1_translate.py no_pk`: `order by tuple()` / `ORDER BY (tuple())`); collapse semantics measured in Spec 08.05 §3.2 | FIXED: keyed like the streaming DDL path (`MySqlDDLParserListenerImpl.java` 652-724, 870): NOT NULL UNIQUE key, else every stored column with `allow_nullable_key=1` when needed; the regexp fallback refuses. Test `test_loader_s1_fixes.py::TestKeylessSortingKey`. Was: keyless tables created as `ReplacingMergeTree ORDER BY tuple()`, collapsed to one row by merges and `FINAL`. |
| D-13.04-2 | S1 | packaged | `ch_sink_tools/db_load/clickhouse_loader.py:268-285` | reproduced (`r4_pure.py`, `r5_dst.py`: `None` gives `Japan`/`Europe/Lisbon`; a January `+00:00` gives `Europe/Dublin`/`WET`/`Antarctica/Troll` in 4/10 seeds) | FIXED (both copies converged): deterministic mapping (`UTC`, `Etc/GMT±N`, named zone; WARNING + UTC when undeterminable; refusal of unrepresentable offsets) and the MySQL Shell zone read from `@.json` `tzUtc`. Tests `test_loader_s1_fixes.py::TestDumpTimezoneMapping`, `::TestMysqlShellDumpTimezone`. Was: non-deterministic mapping falling back to the last iterated zone, possibly a DST zone. |
| D-13.04-3 | S1 | both | `db_load/clickhouse_loader.py:398-402` (P399-403); `CreateTableMySQLParserListener.py:51-52,127-130` | reproduced (`r4_pure.py` B: transformed list equals plain list) | FIXED: classified by the MySQL type, decoded per `<db>@<table>.json` `decodeColumns`, rendered per the new `--binary_handling_mode` (default `bytes`) and `--persist_raw_bytes` flags, mirroring the connector settings. Test `test_loader_s1_fixes.py::TestBinaryRepresentation`. Was: the transform tested the translated type `String`, never fired, and base64 text was stored. |
| D-13.04-4 | S1 | both | `db_load/clickhouse_loader.py:47-60,440,549`; `ch_sink_tools/db_load/clickhouse_loader.py:47-60,440,517` | reproduced (`r7_pipefail.py`, `r8_zstd.py`: rc 0 with 91 500/200 000, 49 865/100 000 and 0 rows) | FIXED: commands run under `bash -o pipefail`; a failing decompressor or `sed` fails the load with exit 1. Test `test_loader_s1_fixes.py::TestPipelineFailure`. Was: the pipeline status hid decompressor failures, so truncated or missing chunks loaded partially or not at all with exit 0. (`--throw_if_no_data_to_insert=0` stays for valid empty chunks.) |
| D-13.04-5 | S1 | both | `db_load/clickhouse_loader.py:428-430`; `ch_sink_tools/db_load/clickhouse_loader.py:428-430` | reproduced (`r2_flow.py mydumper_noassert dump-mydumper`: 0 commands, exit 0) | FIXED: the data glob strips only the `-schema.sql.gz` suffix and glob-escapes the prefix. Test `test_loader_s1_fixes.py::TestMydumperDataFileDiscovery`. Was: `path.split("-")[0]`, so a `-` in the dump path or table name loaded zero rows silently. |
| D-13.04-6 | S1 | both | `db_load/clickhouse_loader.py:388,528,612` (P389,496,580); `CreateTableMySQLParserListener.py:159-167` | reproduced (`r4_pure.py` D: `_sign` dropped from the load list; `_version` duplicated in DDL) | FIXED: every ANTLR source column is loaded (`source_column`); a collision with an appended bookkeeping column (`_version`; `_sign` without `--rmt_delete_support`; `_is_deleted` next to a source `is_deleted`) raises `UnsafeTableDefinitionError`. Test `test_loader_s1_fixes.py::TestSourceColumnsNamedLikeBookkeeping`. Was: such columns silently excluded (NULL), `_version` duplicated. |
| D-13.04-7 | S1 | both | `CreateTableMySQLParserListener.py:64-89` | reproduced (`r1_translate.py lower_null`: `` `a` varchar(10) null `` gives `nullable False`) | FIXED: case-insensitive `NULL` test. Test `test_loader_s1_fixes.py::TestColumnModifiers::test_lower_case_null_is_nullable`. Was: a lower-case `null` was recorded as NOT NULL, so the load structure declared `String` and NULLs became `''`. |
| D-13.04-8 | S2 | both | `db_load/clickhouse_loader.py:518-521`; `ch_sink_tools/db_load/clickhouse_loader.py:486-489` | reproduced (`r2_flow.py mysqlsh`: `truncate table mydb.t1`, target `mydb_ch`) | `--truncate_tables` truncates `<mysql_source_database>.<table>`, potentially the live replica, never the target. |
| D-13.04-9 | S2 | both | `CreateTableMySQLParserListener.py:93-105` | reproduced (DDL `MATERIALIZED (`a` * 2)`); stream rejection per Spec 06.06 §3.1 (code-read) | Generated columns are emitted as `MATERIALIZED <MySQL expr>`. The connector's inserts into them fail (Code 44). The streaming DDL path uses `DEFAULT`. |
| D-13.04-10 | S2 | both | `CreateTableMySQLParserListener.py:33-54`; `db_load/clickhouse_loader.py:159-167` | reproduced (DDL in §3.15); streaming types from Specs 07.03/07.04/08.05 and `DataTypeConverter.java` (code-read) | Type/rendering divergence from the streaming path: ENUM to Enum8, BIT(1) to String, zone-less DateTime64, TIME(0) text, legacy `_sign` engine by default. Enum inserts of new labels fail, and other values compare unequal. |
| D-13.04-11 | S2 | packaged | `ch_sink_tools/db_load/clickhouse_loader.py:421,424,445,466,469` | reproduced (`r2_flow.py packaged mysqlsh`: password in log output True; `--password 'pa'ss word'`) | The password is logged in clear text, and single-quote wrapping without escaping breaks or injects into the shell command (FM-11.05-1). |
| D-13.04-12 | S2 | both | `db_load/clickhouse_loader.py:482-483`; `ch_sink_tools/db_load/clickhouse_loader.py:451-452` | reproduced (`r2_flow.py legacy mysqlsh_fail`: exception contains `--password 'pa'"'"'ss`) | A failed load raises `AssertionError` with the unredacted command, so the password reaches the traceback (FM-11.05-2). |
| D-13.04-13 | S2 | both (legacy: both paths; packaged: mydumper path) | `db_load/clickhouse_loader.py:418-421,494-498`; `ch_sink_tools/db_load/clickhouse_loader.py:418-421` | reproduced (`r2_flow.py legacy cfg`: `-uloader --password cfgsecret`) | A password read from the config file is put on the `clickhouse-client` argv (visible via `ps`/`/proc`), defeating the config-file option. |
| D-13.04-14 | S3 | both | `db_load/clickhouse_loader.py:637-640`; `ch_sink_tools/db_load/clickhouse_loader.py:605-608` | reproduced (`r2_flow.py mydumper`: `AssertionError: zstd should be in the PATH...`) | `assert args.mysqlshell and ...` makes the mydumper mode unusable. All prerequisite checks are `assert`s, so they vanish under `-O`. |
| D-13.04-15 | S3 | both | `CreateTableMySQLParserListener.py:33-54` | reproduced (output keeps `zerofill`, `set('x','y')`, `COLLATE utf8mb4_bin`, `float(7,3)`); ClickHouse rejection not verified offline | Unsupported MySQL type text is passed verbatim, so CREATE TABLE is expected to fail. The streaming path normalises these. |
| D-13.04-16 | S3 | both | `CreateTableMySQLParserListener.py:136-139`; `db_load/clickhouse_loader.py:89-95` | reproduced (`order by (`name`(10))`; regexp `ORDER BY (10)`) | A prefix-length PK yields an invalid sorting key (ANTLR) or a constant key that collapses all rows (regexp, latent). |
| D-13.04-17 | S3 | both | `db_load/clickhouse_loader.py:118-130`; `CreateTableMySQLParserListener.py:148-154,175-177` | reproduced (`PARTITION BY d`, `PARTITION BY id,d`, nothing for `RANGE(year(d))`) | Partitioning: only RANGE COLUMNS is recognised. Multi-column keys emit invalid syntax, and raw columns create one partition per value (too many partitions). The listener hook is bound to the window-function rule and is dead. |
| D-13.04-18 | S3 | both | `db_load/clickhouse_loader.py:133-246,260-265,380-405` | reproduced (`r4_pure.py` C: `KeyError: 'generated'`; `r1_translate.py`: TIMESTAMP→String, duplicate `is_deleted`, missing comma) | The regexp fallback produces wrong DDL and metadata that crashes the data phase. The fallback is logged only at INFO (FM-11.05-5). |
| D-13.04-19 | S3 | both | `db_load/clickhouse_loader.py:349-354,408-441,486-554` (P350-355,409-441,455-522) | reproduced (`r2_flow.py nofiles`: `TypeError`); absence of any count check (code-read) | No post-load verification and no use of dump metadata: missing or non-zstd chunks load zero rows with exit 0. No schema files gives a `TypeError` instead of a clear error. |
| D-13.04-20 | S3 | both | `db_load/clickhouse_loader.py:296-303,339-344`; `CreateTableMySQLParserListener.py:158` | reproduced (`r2_flow.py`: `CREATE DATABASE ... `mydb`` with target `mydb_ch`; ANTLR DDL lacks `IF NOT EXISTS`) | The schema phase is not idempotent. mydumper creates the source-named database. A mysqlshell database-create error is swallowed. |
| D-13.04-21 | S3 | both | `db_load/clickhouse_loader.py:589-590`; `ch_sink_tools/db_load/clickhouse_loader.py:557-558` | reproduced (`--clickhouse_secure False` gives `'False'`, then `--secure`) | `--clickhouse_secure` is an untyped string. Any value, including `False`, enables TLS. |
| D-13.04-22 | S3 | packaged | `ch_sink_tools/db/mysql.py:10-17` | reproduced (`r4_pure.py` E/F: `enum('bit','byte')` gives `String`; `geometrycollection` verbatim) | The packaged `is_binary_datatype` uses substring and case-sensitive matching, so the packaged DDL differs from the legacy DDL. |
| D-13.04-23 | S3 | both | `CreateTableMySQLParserListener.py:98` | reproduced (`r4_pure.py` H: `` if((`'pos','neg') ``) | The charset-introducer strip `\b_.*?'` deletes text in generated expressions that reference `_`-prefixed identifiers. |
| D-13.04-24 | S3 | packaged | `ch_sink_tools/config/column_type_overrides.py:254` | reproduced (`r3_overrides.py` 1: `Decimal(18`) | The CLI override string is split on every comma, so parameterised types and expressions are cut. |
| D-13.04-25 | S3 | packaged | `ch_sink_tools/config/column_type_overrides.py:138-155,186-197` | reproduced (`r3_overrides.py` 2: singular `None`, plural match) | `get_direct_override` ignores db-specific/schema-wildcard (and similar) entries that `get_direct_overrides` matches, so CREATE and reconciliation disagree. |
| D-13.04-26 | S3 | packaged | `ch_sink_tools/config/override_reconciler.py:153-183,258-279` | reproduced with mocked `system.columns` (`r3_overrides.py` 5-7) | The reconciler compares type/expression strings exactly and column names case-sensitively: false mismatches, missed mismatches, and a needless ALTER on every run. |
| D-13.04-27 | S3 | both | `db_load/clickhouse_loader.py` (no override input; whole file); contrast `MySqlDDLParserListenerImpl.java` around line 1557 | code-read | The MySQL snapshot loader ignores `column_type_override.*`. Snapshot tables lack override types and alias columns that the streaming path would create. |
| D-13.04-28 | S4 | both | `CreateTableMySQLParserListener.py:37` | reproduced (`'varchar(5) charset latin1'` unchanged) | FIXED: `flags=re.IGNORECASE`. Test `test_loader_s1_fixes.py::TestColumnModifiers::test_lower_case_charset_is_stripped`. Was: `re.sub("CHARSET.*", '', t, re.IGNORECASE)` passed the flag as `count`, so the strip was case-sensitive. |
| D-13.04-29 | S4 | both | `CreateTableMySQLParserListener.py:148-154,189-282`; `db_load/clickhouse_loader.py:28-44`; `mysql_parser.py:26` | code-read (grammar rule `partitionClause` is the window clause; `translateFieldDefinition` undefined); stderr noise reproduced | Dead code: `exitPartitionClause`, `exitAlterList` (calls an undefined method), `run_command`. antlr4's default console error listener stays installed. |
| D-13.04-30 | S4 | both | `db_load/clickhouse_loader.py:255-256,410-411,597-600,605-606` (P similar) | code-read | Flags declared but ignored or mis-declared: `--use_regexp_parser`, `--debug`, `--threads` (required although it has a default; ignored by the mydumper path), the `load_data` `dry_run` parameter. `load_data` falls through after the mysqlshell load. |
| D-13.04-31 | S4 | both | `db_load/clickhouse_loader.py:1`; `ch_sink_tools/db_load/postgres_type_mapper.py:8`; `ch_sink_tools/config/column_type_overrides.py:17-18`; `Dockerfile_db_load` | code-read | Doc drift. The usage header names a nonexistent script and flags. The PostgreSQL mapper claims a `--source postgres` loader flag. The override docstring uses dashed flag names (the dumper uses underscores). The `db_load` image contains no loader. Unit tests cover the legacy copy only. |
