# Spec 13.02: Shared Connection, Credential and Configuration Layer

## 1. Executive Summary & Purpose

Every Python tool of the sink-connector toolset (snapshot dump, snapshot load,
checksum and count runners, the PostgreSQL pipeline, the resync tool) reaches
MySQL, ClickHouse and PostgreSQL through a small shared layer: three connection
factories, their execute helpers, a handful of catalog-query builders, and
credential resolvers for MySQL option files, ClickHouse client configs and
`.pgpass`. The layer exists twice: the legacy top-level `db/` package (MySQL and
ClickHouse only, imported by the legacy tools and by the unit tests) and the
packaged `ch_sink_tools/db/` package (MySQL, ClickHouse and PostgreSQL, imported
by every `ch-*` entry point).

This spec describes that layer as built on 2.11.0, function by function and copy
by copy: the DSN or keyword arguments each factory hands to its driver, the
session state it sets and does not set (time zone, charset, timeouts, TLS,
transactions), how results are materialised, how identifiers and values are
interpolated, where credentials come from, and where a password can end up
(process argv, shell command strings, log lines, exception text). It also
inventories every call site in both trees.

The layer is small, and so are most of its defects, but they are cross-cutting:

- Under SQLAlchemy 2.x, which `requirements.txt` allows (`sqlalchemy>=1.4`), the
  MySQL execute helper returns tuple-like rows. Twelve call sites in both trees
  index them by column name and crash with `TypeError` (D-13.02-12).
- The packaged MySQL factory does not URL-encode the password. A password
  containing `@` turns into a wrong host name, and that host name, which holds
  part of the password, appears in the error. A password containing `%41` is
  silently percent-decoded (D-13.02-1).
- Passwords leak into DEBUG logs (packaged ClickHouse resolver, packaged dumper,
  PostgreSQL dumper) and INFO logs (packaged loader). They leak into exception
  text (legacy loader, `.cnf` parser) and into `sh -c`/`bash -c` argv for whole
  loads (both loaders, PostgreSQL dumper) (D-13.02-2 to D-13.02-9).
- The factories never pin a session time zone. Only the legacy MySQL checksum
  pins one, in the tool itself. The packaged checksum therefore renders
  `TIMESTAMP` values in whatever zone the server defaults to (D-13.02-10).
- MySQL connections are never really closed: every call builds a private
  SQLAlchemy engine and pool, and `close()` only returns the socket to that
  pool. The packaged ClickHouse helper opens one native connection per query
  and never closes them (D-13.02-17, D-13.02-18).

Domain 13 is authoritative for per-tool behaviour; this spec restates and
deepens the credential facts of 11.05 (FM-11.05-1, FM-11.05-2) and the MySQL
session facts of 11.02 (sections 3.2 and 3.4), and the stdin-password design of
11.04.

## 2. Codebase Mapping on 2.11.0

Shared layer (both copies are covered function by function in section 3):

- `sink-connector/python/db/mysql.py` — legacy MySQL layer: `is_binary_datatype`,
  `get_mysql_connection`, `get_tables_from_regex_sql`, `get_tables_from_regex`,
  `get_partitions_from_regex`, `get_table_partition_key`, `mysql_execute_df`,
  `execute_mysql`, `resolve_credentials_from_config`, `estimate_table_count`,
  `get_min_max_pk_value`, `mysql_columns`, `mysql_pk_columns`,
  `mysql_columns_by_data_type`, `divide_table_into_even_chunks`.
- `sink-connector/python/ch_sink_tools/db/mysql.py` — packaged MySQL layer, the
  same functions minus `mysql_columns_by_data_type`, with the differences of
  section 3.13.
- `sink-connector/python/db/clickhouse.py` — legacy ClickHouse layer:
  `clickhouse_connection`, `clickhouse_execute_conn`, `get_table_partition_key`,
  `execute_sql`, `resolve_credentials_from_config`.
- `sink-connector/python/ch_sink_tools/db/clickhouse.py` — packaged ClickHouse
  layer, same five functions.
- `sink-connector/python/ch_sink_tools/db/postgres.py` — packaged-only
  PostgreSQL layer: `get_postgres_connection`, `execute_pg`, `pg_execute_df`,
  `get_schemas`, `get_tables`, `get_server_timezone`, `get_table_columns`,
  `get_table_pk`, `get_table_row_count`, `get_current_lsn`, `get_standby_lsn`,
  `pause_wal_replay`, `resume_wal_replay`, `is_in_recovery`,
  `is_wal_replay_paused`, `build_ch_create_table_ddl`,
  `resolve_credentials_from_pgpass` (the type map `PG_TO_CH_BASE` /
  `pg_type_to_ch` is owned by 13.05).
- `sink-connector/python/db/checksum_common.py` — shared checksum helpers; no
  connection logic (owned by 13.06).

Consumers whose credential and connection handling is inventoried here (their
behaviour is owned by 13.03 to 13.08):

- `sink-connector/python/db_compare/top_level_table_checksum.py`,
  `sink-connector/python/ch_sink_tools/db_compare/top_level_table_checksum.py`
- `sink-connector/python/db_compare/mysql_table_checksum.py`,
  `sink-connector/python/ch_sink_tools/db_compare/mysql_table_checksum.py`
- `sink-connector/python/db_compare/mysql_table_count.py`,
  `sink-connector/python/ch_sink_tools/db_compare/mysql_table_count.py`
- `sink-connector/python/db_compare/clickhouse_table_checksum.py`,
  `sink-connector/python/ch_sink_tools/db_compare/clickhouse_table_checksum.py`
- `sink-connector/python/db_compare/clickhouse_table_count.py`,
  `sink-connector/python/ch_sink_tools/db_compare/clickhouse_table_count.py`
- `sink-connector/python/db_dump/mysql_dumper.py`,
  `sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py`
- `sink-connector/python/db_load/clickhouse_loader.py`,
  `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py`
- `sink-connector/python/db_load/mysql_resync.py` (shim) and
  `sink-connector/python/ch_sink_tools/db_load/mysql_resync.py`
- `sink-connector/python/ch_sink_tools/db_dump/postgres_dumper.py`
- `sink-connector/python/ch_sink_tools/db_compare/top_level_postgres_checksum.py`,
  `sink-connector/python/ch_sink_tools/db_compare/postgres_table_checksum.py`,
  `sink-connector/python/ch_sink_tools/db_compare/postgres_table_count.py`,
  `sink-connector/python/ch_sink_tools/db_compare/auto_diff.py`
- `sink-connector/python/ch_sink_tools/config/override_reconciler.py` (receives a
  ClickHouse connection, opens none)
- `sink-connector/python/ch_sink_tools/db_load/mysql_parser/CreateTableMySQLParserListener.py`
  and `sink-connector/python/db_load/mysql_parser/CreateTableMySQLParserListener.py`
  (import `is_binary_datatype`)

Packaging and runtime inputs:

- `sink-connector/python/pyproject.toml` — extras: `mysql` = pymysql,
  sqlalchemy>=1.4, antlr4; `dataframe` = pandas; base = clickhouse-driver,
  psycopg2-binary, pyyaml.
- `sink-connector/python/requirements.txt` — unpinned `sqlalchemy>=1.4`, `pymysql`,
  `pandas`, `clickhouse-driver>=0.2.9`, `psycopg2-binary`, `pyyaml`.
- `sink-connector/python/Dockerfile_mysql_checksum`,
  `sink-connector/python/Dockerfile_clickhouse_checksum` — credentials as ENV,
  passed on argv by an exec-form ENTRYPOINT.
- `sink-connector/python/test_db.sh` — manual script; passes passwords on argv.

Tests that touch this layer:

- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py`
- `sink-connector/python/db_load/tests/test_loader_failure_modes.py`
- `sink-connector/python/db_compare/tests/test_table_locking.py`
- `sink-connector/python/db_compare/tests/test_bounded_source_lock.py`
- `sink-connector/python/db_compare/tests/test_checksum_fidelity.py`
- `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py`

Line numbers below refer to the 2.11.0 tree. In section 3, db/ means
sink-connector/python/db/ and ch_sink_tools/ means
`sink-connector/python/ch_sink_tools/`.

## 3. Contract (Behaviour as Built)

### 3.1 Module inventory and who imports which copy

| Shared module | Imported by (legacy tree) | Imported by (packaged tree) |
|---|---|---|
| `db/mysql.py` (`from db.mysql import *`) | `db_compare/top_level_table_checksum.py:6`, `db_compare/mysql_table_checksum.py:18`, `db_compare/mysql_table_count.py:18`, `db_dump/mysql_dumper.py:16`, `db_load/clickhouse_loader.py:4` (only `is_binary_datatype`), `db_load/mysql_parser/CreateTableMySQLParserListener.py:4` | none |
| `ch_sink_tools/db/mysql.py` (explicit names) | none | `db_compare/top_level_table_checksum.py:6`, `db_compare/mysql_table_checksum.py:19`, `db_compare/mysql_table_count.py:18`, `db_dump/mysql_dumper.py:16`, `db_load/clickhouse_loader.py:3`, `db_load/mysql_parser/CreateTableMySQLParserListener.py:4` |
| `db/clickhouse.py` (`import *`) | `db_compare/clickhouse_table_checksum.py:19`, `db_compare/clickhouse_table_count.py:20`, `db_load/clickhouse_loader.py:21` | none |
| `ch_sink_tools/db/clickhouse.py` | none | `db_compare/clickhouse_table_checksum.py:20`, `db_compare/clickhouse_table_count.py:20`, `db_load/clickhouse_loader.py:20`, `db_dump/postgres_dumper.py:63` and `:2024`, `db_compare/top_level_postgres_checksum.py:55`, `db_compare/auto_diff.py:33` |
| `ch_sink_tools/db/postgres.py` | none | `db_dump/postgres_dumper.py:52` (+ local imports at `:236`, `:281`, `:608`, `:2010`), `db_compare/top_level_postgres_checksum.py:40`, `db_compare/postgres_table_checksum.py:26`, `db_compare/postgres_table_count.py:19`, `db_compare/auto_diff.py:34` |

Cross-tree execution: the packaged orchestrator
`ch_sink_tools/db_compare/top_level_table_checksum.py:159` and `:186` shells out
to `python db_compare/mysql_table_checksum.py` and
`python db_compare/clickhouse_table_checksum.py`. These are paths relative to the
working directory, so the packaged `ch-mysql-checksum` runs the legacy child
scripts and therefore the legacy `db/` layer when started from
`sink-connector/python`, and fails otherwise. Its own metadata connection uses
the packaged layer. The resync tool runs the packaged loader by default
(`ch_sink_tools/db_load/mysql_resync.py:345-348`). Both Dockerfiles copy the
legacy `db/` and `db_compare/` trees.

### 3.2 MySQL connection factory `get_mysql_connection`

Signature (both copies): `get_mysql_connection(mysql_host, mysql_user, mysql_passwd, mysql_port, mysql_database)`.
It returns a `sqlalchemy.engine.Connection` that is already connected.

| Aspect | Legacy `db/mysql.py:28-34` | Packaged `ch_sink_tools/db/mysql.py:20-26` |
|---|---|---|
| Driver | SQLAlchemy `create_engine` + `pymysql` dialect | same |
| URL template | `mysql+pymysql://{quote_plus(user)}:{quote_plus(passwd)}@{host}:{int(port)}/{db}?charset=utf8mb4` | `mysql+pymysql://{user}:{passwd}@{host}:{int(port)}/{db}?charset=utf8mb4` (no encoding) |
| `connect_args` | `{"init_command": "SET SESSION wait_timeout=28000"}` | same |
| Database name | not encoded (`?`, `#` in a schema name break the URL in both copies) | same |
| Port | `int(mysql_port)`, so a CLI string is accepted | same |

Driver keyword arguments actually passed to `pymysql.connect` were observed with
a fake `pymysql.connect` (offline repro, SQLAlchemy 2.1.1 and PyMySQL 2.2.8).
They are the same in both copies: `host`, `database`, `user`, `password`,
`port` (int), `charset='utf8mb4'`, `client_flag=2` (`CLIENT.FOUND_ROWS`, added
by SQLAlchemy), `init_command='SET SESSION wait_timeout=28000'`.

Settings that are not passed, so library or server defaults apply:

- Connect timeout: PyMySQL default `connect_timeout=10` s. `read_timeout` and
  `write_timeout` are `None`, so a read can block for ever on a stuck server or
  a half-open TCP connection. There is no TCP keepalive option.
- TLS: no `ssl` argument. The session is plaintext, and a server with
  `require_secure_transport=ON` refuses it. No CLI flag can enable TLS.
- Charset and collation: PyMySQL sends `SET NAMES utf8mb4` on connect, so the
  collation is the server's default for utf8mb4.
- Time zone: not set. The session uses the server's `@@global.time_zone`. Only
  the legacy MySQL checksum sets `time_zone = '+00:00'` itself
  (`db_compare/mysql_table_checksum.py:210`). See section 3.8 and D-13.02-10.
- `sql_mode`, isolation level, `lock_wait_timeout`: server defaults. The legacy
  orchestrator sets `lock_wait_timeout` on its lock session only
  (`db_compare/top_level_table_checksum.py:375`).
- Transactions: PyMySQL connects with `autocommit=False`. SQLAlchemy 2.x
  "autobegin" opens a transaction at the first `execute`. Nothing in the layer
  ever commits. `Connection.close()` issues a rollback (repro: events after
  close = `[('rollback',)]`, commit never called). Under InnoDB's default
  REPEATABLE READ, a long-lived connection therefore sees one consistent
  snapshot, from its first InnoDB read until it is closed (MySQL server
  behaviour, not verified offline). This affects the shared metadata
  connections of the orchestrators, which compute `min/max(pk)` for every table
  on one connection.
- Pooling: every call builds a new `Engine` with its own `QueuePool`, with no
  `pool_pre_ping` and no `pool_recycle`. `Connection.close()` returns the DBAPI
  connection to that private pool. Nothing calls `engine.dispose()`, so the
  DBAPI `close()` is never called. In the repro, after
  `get_mysql_connection` / query / `close()` / `del` three times plus
  `gc.collect()`, 0 of 3 DBAPI connections were closed; `engine.dispose()`
  closes them. The MySQL session ends only when the PyMySQL object is
  finalised (socket closed without COM_QUIT) or the process exits
  (D-13.02-17).
- Retries: none.
- Laziness: `create_engine` is lazy, and `engine.connect()` performs the TCP
  connect and the authentication inside the factory. Connection errors
  therefore surface at the factory call as `sqlalchemy.exc.OperationalError`
  wrapping `pymysql.err.OperationalError`.

Error text: the SQLAlchemy message carries the PyMySQL text, for example
`(2003, "Can't connect to MySQL server on 'db1' (...)")` or
`(1045, "Access denied for user 'app'@'...' (using password: YES)")`. The host
is rendered with `{self.host!r}` (PyMySQL `connections.py:737`). The password
is not in the message, except through the packaged URL mis-parse
(D-13.02-1): `str(engine.url)` masks the password with `***`, but the mis-parsed
host carries a password fragment.

Repro (packaged URL, `sqlalchemy.engine.make_url` on the string the factory
builds):

```
legacy   in=('app','p@ss','mydb')  -> pw='p@ss'  host='db1'
packaged in=('app','p@ss','mydb')  -> pw='p'     host='ss@db1'
legacy   in=('app','a%41b','mydb') -> pw='a%41b'
packaged in=('app','a%41b','mydb') -> pw='aAb'
both     in=('app','secret','my?db') -> db='my' query={'db?charset': 'utf8mb4'}
```

### 3.3 MySQL execute helpers

`execute_mysql(conn, strSql)` is defined at legacy `db/mysql.py:108-124` and
packaged `ch_sink_tools/db/mysql.py:100-116`, and is identical in both. It:

1. Logs `"SQL=" + strSql` at DEBUG. The SQL is logged in full, including the
   operator `--where` text.
2. Inside `warnings.catch_warnings(record=True)` with
   `simplefilter('always')`, runs `conn.execute(text(strSql))`. `text()` parses
   `:name` tokens as bind parameters even inside quoted literals: in the repro,
   `text("... where c = 'a :b'")` compiles with bind `['b']`, and executing it
   raises "A value is required for bind parameter 'b'" (D-13.02-14). POSIX
   classes such as `[[:alpha:]]` are not affected (repro: no binds).
3. Returns `(CursorResult, -1)`. `rowcount` is hard-coded to `-1`, so the
   `rowcount != -1` branch in `mysql_table_count.compute_count` never runs.
4. Logs the warning count and the first warning at WARNING. PyMySQL 1.x and 2.x
   never turn server warnings into Python warnings (no `SHOW WARNINGS`
   round-trip on execute), so this branch only ever reports library warnings.
   `warnings.catch_warnings` mutates process-global state and is not
   thread-safe, yet the helper runs concurrently from worker threads
   (D-13.02-24).

Result materialisation: the PyMySQL default `Cursor` is buffered, so the whole
result set is read into client memory during `execute`. `fetchall()` then
builds SQLAlchemy `Row` objects. Under SQLAlchemy 2.x a `Row` is tuple-like:
`row['table_name']` raises
`TypeError: tuple indices must be integers or slices, not str`. That was
reproduced through the real factory with a fake DBAPI connection. Name access
requires `.mappings()`, which the legacy copy uses in four places
(`db/mysql.py:85`, `db_compare/mysql_table_checksum.py:173,460`,
`db_compare/top_level_table_checksum.py:334`) and nowhere else. See
D-13.02-12 for the crashing call sites.

`mysql_execute_df(conn, sql)` is defined at legacy `db/mysql.py:95-105` and
packaged `ch_sink_tools/db/mysql.py:87-97`, and is identical in both. It:

- logs the SQL at DEBUG;
- opens a raw DBAPI cursor on `conn.connection`, a PyMySQL buffered `Cursor` on
  the same connection and inside the same SQLAlchemy-begun transaction;
- runs `cursor.execute(sql)` with no parameters, so `%` is literal (the
  `like '%int%'` filter works);
- reads the column names from `cursor.description` and returns
  `pandas.DataFrame(rows, columns=names)`;
- closes the cursor in `finally`.

Memory: the full result is held twice, once in the PyMySQL buffer and once in
the DataFrame. The callers only use it for single-row aggregates and catalog
lists.

### 3.4 MySQL catalog and query builders (SQL templates, quoting)

All values are interpolated with f-strings. No driver parameters are used, and
no quote or backtick is escaped (reproduced, D-13.02-15). `where` is operator
SQL by design. `pk` is a column name read from the catalog.

| Function (legacy line / packaged line) | SQL template | Interpolated, unescaped |
|---|---|---|
| `get_tables_from_regex_sql` (37-50 / 29-42) | `select TABLE_SCHEMA as table_schema, TABLE_NAME as table_name from information_schema.tables where table_type='BASE TABLE' and table_schema = '{schema}' and table_name rlike '{include}' [and table_name not rlike '{exclude}'] [and (table_schema, table_name) in (select ... from information_schema.partitions where table_schema = '{schema}' group by ... having count(*) = 1 )] [and (...) in (select ... where ... partition_name rlike '{parts}' ... having count(*) > 0 )] order by 1` | schema, three regexes inside `'...'` |
| `get_tables_from_regex` (53-62 / 45-54) | as above via `execute_mysql`; with `no_wc=True` it returns the Python list `[[include_tables_regex]]` and issues no SQL | return type differs by branch (D-13.02-13) |
| `get_partitions_from_regex` (65-80 / 57-72) | `select TABLE_SCHEMA ..., PARTITION_NAME as partition_name, PARTITION_EXPRESSION as partition_expression from information_schema.partitions where table_schema = '{db}' [and partition_name rlike '{parts}'] and (table_schema, table_name) IN ({table_sql}) order by 1,2,3 [limit {limit}]` | db, regexes, limit |
| `get_table_partition_key` (83-92 / 75-84) | `get_partitions_from_regex(conn, db, '^'+table+'$', limit=1)`; returns the first non-NULL `partition_expression`, else `None` | the table name is used as a regex (D-13.02-16). Packaged uses `fetchall()` + `row['partition_name']` (crashes under SQLAlchemy 2.x); legacy uses `.mappings().fetchall()` |
| `estimate_table_count` (142-147 / 134-139) | ``explain select * from `{table}` where {where} and {pk} between {min} and {max}``; reads `rows` of the first EXPLAIN row | table inside backticks (no doubling), pk bare |
| `get_min_max_pk_value` (150-158 / 142-150) | ``select min({pk}) as min_pk, max({pk}) as max_pk from `{table}` where {where}``; `(None, None)` if `max_pk` is NULL, else both cast to `int` | same |
| `mysql_columns` (161-170 / 153-162) | `select column_name as COLUMN_NAME from information_schema.columns where table_schema='{db}' and table_name = '{table}' [and column_name <> '{pk}'] order by ORDINAL_POSITION` | db, table, pk |
| `mysql_pk_columns` (173-181 / 165-173) | `... and column_key='PRI' [and data_type like '%int%'] order by ORDINAL_POSITION` | db, table |
| `mysql_columns_by_data_type` (184-190 / absent) | `... and data_type in ('t1','t2',...) order by ORDINAL_POSITION` | db, table, type list |
| `divide_table_into_even_chunks` (193-229 / 176-212) | per chunk ``select {pk} from `{table}` where {where} and {pk} between {lo} and {hi} order by {pk} limit 1``; yields `{min_pk, max_pk}`; with no pk yields `{}` once | same |

`is_binary_datatype` behaves differently in the two copies:

- Legacy (`db/mysql.py:16-25`) lower-cases the type, strips everything from
  `(`, and tests membership in a 16-keyword tuple that includes `tinyblob`,
  `mediumblob`, `longblob` and `geometrycollection`.
- Packaged (`ch_sink_tools/db/mysql.py:13-17`) returns `True` whenever the raw
  string contains `blob`, `binary`, `varbinary` or `bit`, and otherwise tests
  exact membership in a 12-keyword tuple.

Repro results:

| Declared type | Legacy | Packaged |
|---|---|---|
| `enum('habit','orbit')` | False | True |
| `set('a','rabbit')` | False | True |
| `enum('bit','blob')` | False | True |
| `varchar(10) binary` | False | True |
| `tinyblob`, `mediumblob`, `longblob` | True | True |

The packaged ANTLR listener uses the function to map a column to `String`
(`ch_sink_tools/db_load/mysql_parser/CreateTableMySQLParserListener.py:51`). An
ENUM or SET whose labels contain those substrings is therefore created as
`String` by the packaged loader only (D-13.02-21; DDL consequences belong to
13.04).

The only correct identifier-quoting helper in the toolset is
`quote_mysql_identifier` in the legacy orchestrator
(`db_compare/top_level_table_checksum.py:358-365`). It doubles backticks and is
used only for `LOCK TABLES`. The packaged orchestrator locks with
``FLUSH TABLE `{table}` WITH READ LOCK``, without escaping
(`ch_sink_tools/db_compare/top_level_table_checksum.py:206`).

### 3.5 MySQL option-file resolver `resolve_credentials_from_config`

The function is at legacy `db/mysql.py:127-139` and packaged
`ch_sink_tools/db/mysql.py:119-131`, and the two copies are identical:

```
assert config_file is not None   # "A config file --default_file must be passed ..."
config_file = os.path.expanduser(config_file)
assert os.path.isfile(config_file)
assert config_file.endswith(".cnf")
config = configparser.ConfigParser()          # BasicInterpolation, strict=True
config.read(config_file)
assert 'client' in config
return (config['client']['user'], config['client']['password'])
```

It logs `mysql_user <u> mysql_password ****` at DEBUG, so the password is
masked. Only `user` and `password` are read: `host`, `port`, `socket` and
`ssl-*` in the file are ignored by the Python connection. The same file is
passed unchanged to `mysqlsh --defaults-file` by the dumpers, and `mysqlsh`
does honour those keys, so the dump connection and the catalog connection can
reach different endpoints.

Offline repro of how MySQL option-file syntax is handled (same result in both
copies):

| File content (`[client]` section) | Result |
|---|---|
| `password=ab%cdSECRET` | `InterpolationSyntaxError: '%' must be followed by '%' or '(', found: '%cdSECRET'`; the message carries the password tail |
| `password="s3cret"` | `('app', '"s3cret"')`: quotes kept (MySQL strips them) |
| `password=s3cret # rotated` | `('app', 's3cret # rotated')`: inline comment kept |
| no `user=` | `KeyError: 'user'` (MySQL would use the login name) |
| `password` twice | `DuplicateOptionError` (MySQL: last wins) |
| bare option `skip-ssl` | `ParsingError` |
| `!include /etc/mysql/common.cnf` first | `MissingSectionHeaderError` |
| only `[mysql]` section | `AssertionError: Expected a [client] section in f<path>` (literal `f` typo in the message) |
| file named `x.ini` | `AssertionError: Supported configuration extensions .cnf` |

Every tool calls the resolver outside its `try` block. The resulting exception
is therefore printed as an uncaught traceback on stderr, including the
password-bearing interpolation message (D-13.02-9).

### 3.6 ClickHouse layer

`clickhouse_connection(host, database='default', user='default', password='', port=9000, secure=False)`
is defined at legacy `db/clickhouse.py:9-19` and packaged
`ch_sink_tools/db/clickhouse.py:9-19`. It returns
`clickhouse_driver.dbapi.connect(...)`, a `clickhouse_driver.dbapi.Connection`.
No socket is opened until a cursor executes.

| Argument | Legacy | Packaged | Driver default if not passed |
|---|---|---|---|
| password | passed as given; `None` stays `None` | `password or ""` | `''` |
| port | `9000` unless the caller passes one; a CLI string is passed through | same | `9440` when `secure` is set and the port is `None`, else `9000` |
| secure | passed through without coercion | same | `False` |
| connect_timeout | `20` | `20` | `10` |
| send_receive_timeout | not passed | not passed | `300` s per socket operation |
| sync_request_timeout | not passed | not passed | `5` s |
| compression | not passed | not passed | off |
| verify / ca_certs | not passed | not passed | verify on, certifi bundle when installed, `check_hostname=True` |
| settings | none (no `session_timezone`, no `max_execution_time`) | none | none |

Repro outputs with driver 0.2.11:

- `secure='False'`, the string argparse yields for `--secure False`, gives
  `secure_socket='False'`, which is truthy, so TLS is used.
- `secure=True` without an explicit port gives hosts `[('ch-host', 9000)]`,
  because the factory always forces 9000. The driver alone would choose 9440
  (D-13.02-19).

The DBAPI layer creates one new `Client`, and so one native TCP connection,
handshake and authentication, per `cursor()`
(`clickhouse_driver/dbapi/connection.py:82-99`).

`clickhouse_execute_conn(conn, sql)` logs the SQL at DEBUG, opens a cursor,
runs `cursor.execute(sql)` (non-streaming: the driver reads every block into
memory) and returns `cursor.fetchall()`, a list of tuples.

- Legacy (`db/clickhouse.py:22-30`) closes the cursor in `finally`, so each
  query opens and closes one TCP connection.
- Packaged (`ch_sink_tools/db/clickhouse.py:22-27`) never closes the cursor.
  Every query leaves its native connection open, referenced from
  `conn.cursors`. `Connection.close()` iterates `self.cursors` while
  `Cursor.close()` removes items from that list
  (`clickhouse_driver/dbapi/cursor.py:80-94`), so only every other cursor is
  closed. Repro: 5 queries leave 5 clients open, and 2 are still open after
  `conn.close()`. Legacy: 0 (D-13.02-18).

`execute_sql(conn, strSql)` (legacy 37-53 / packaged 34-50) is the ClickHouse
twin of `execute_mysql`: DEBUG `SQL=...`, the same `catch_warnings` wrapper,
and it returns `(rows, len(rows))`.

`get_table_partition_key(conn, database, table)` (legacy 32-35 / packaged
29-32) runs
`SELECT partition_key FROM system.tables WHERE name = '{table}'  AND database = '{database}' FORMAT TabSeparated`
(values unescaped) and returns the raw row list, not a string. The legacy
ClickHouse checksum calls it (`db_compare/clickhouse_table_checksum.py:380`).
The effect of `FORMAT TabSeparated` over the native protocol was not verified
offline.

`resolve_credentials_from_config(config_file)` (legacy 56-72 / packaged 53-69):

- It asserts that the file is non-`None`, exists and ends in `.xml`, `.yml` or
  `.yaml`. There is no `~` expansion, unlike the MySQL resolver.
- XML: `root.findtext('user')` and `root.findtext('password')` read direct
  children of the root only. A file without `<password>` returns
  `('chuser', None)`. A file whose credentials are nested
  (`<connections_credentials>`) returns `(None, None)`.
- YAML: `values['config']['user']` and `['password']`. A flat YAML file raises
  `KeyError: 'config'`. Legacy loads with `yaml.safe_load`, packaged with
  `yaml.load(..., Loader=yaml.FullLoader)`.
- DEBUG log line: legacy masks the password
  (`clickhouse_password ****`). Packaged prints it in clear
  (`ch_sink_tools/db/clickhouse.py:68`). Repro: the packaged log line is
  `'clickhouse_user chuser clickhouse_password chS3cret'` (D-13.02-2).

A `None` user or password then breaks the legacy factory at the protocol hello:
`send_hello` raises `AttributeError: 'NoneType' object has no attribute 'encode'`
(repro). The packaged factory repairs a `None` password to `""` but not a
`None` user (D-13.02-20).

Error text from the driver carries `host:port` and never the password. Repro
with the socket mocked to refuse:
`OperationalError Code: 210. Connection refused (ch-host:9000)`, and the
password is not contained in it.

### 3.7 PostgreSQL layer (packaged only)

`get_postgres_connection(pg_host, pg_user, pg_password, pg_port, pg_database)`
(`ch_sink_tools/db/postgres.py:221-236`) calls
`psycopg2.connect(host, user, password, port=int(pg_port), dbname, connect_timeout=20, options='-c statement_timeout=0')`
and then sets `conn.autocommit = True`. It returns the psycopg2 connection,
whose default cursor yields tuples. The keyword arguments were confirmed in the
repro.

- The docstring says "autocommit=False"; the code sets `True` (D-13.02-25).
- Callers that need a snapshot change the transaction mode themselves (owned by
  13.05 and 13.07).
- Not set: `sslmode` (libpq default `prefer`, or `PGSSLMODE` from the
  environment), `TimeZone`, `DateStyle`, `IntervalStyle`, `extra_float_digits`,
  `bytea_output`, `application_name`.
- When `pg_password` is `None`, libpq falls back to `PGPASSWORD` and the
  passfile itself (libpq behaviour, not verified offline). The tools never let
  it get that far (section 3.9).

`execute_pg(conn, sql, params=None)` (`:239-248`) logs `SQL=...` at DEBUG,
opens a `RealDictCursor`, runs `execute(sql, params)` and `fetchall()`. A
`psycopg2.ProgrammingError` at fetch time ("no results to fetch") becomes `[]`.
It returns a list of `RealDictRow`, fully materialised.

`pg_execute_df` (`:251-259`) returns a DataFrame and has no caller in either
tree. `_PANDAS_AVAILABLE` (`:16-20`) is never consulted, so the function would
raise `NameError` without pandas (D-13.02-25).

Introspection helpers:

| Helper | SQL / behaviour | Quoting |
|---|---|---|
| `get_schemas` (266-293) | `SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN (...) [AND schema_name ~ %s] [AND schema_name !~ %s] ORDER BY schema_name` | parameterised (correct) |
| `get_tables` (296-318) | `... information_schema.tables WHERE table_schema = '{schema}' AND table_type = 'BASE TABLE' [AND table_name ~ '{inc}'] [AND table_name !~ '{exc}'] ORDER BY table_name` | interpolated |
| `get_server_timezone` (321-329) | `SHOW timezone` returns `rows[0]['TimeZone']`, or `'UTC'` when there are no rows | none |
| `get_table_columns` (332-391) | `information_schema.columns` for `'{schema}'`/`'{table}'`; type mapping per 13.05 | interpolated |
| `get_table_pk` (394-416) | `pg_index`/`pg_class`/`pg_namespace`/`pg_attribute`, `n.nspname = '{schema}' AND c.relname = '{table}'` | interpolated |
| `get_table_row_count` (419-434) | `n_live_tup` from `pg_stat_user_tables`, or `-1` | interpolated |
| `get_current_lsn` (437-456) | `pg_current_wal_lsn()`; returns `(str, int(low half, 16))` | none; imported by `top_level_postgres_checksum.py:47` but never called |
| `get_standby_lsn` (459-496) | tries `pg_last_wal_replay_lsn()` (any exception is swallowed); falls back to `pg_current_wal_lsn()`; returns `(str, hi*2^32+lo)` | none |
| `pause_wal_replay` / `resume_wal_replay` (503-530) | `SELECT pg_wal_replay_pause()` / `resume()`, then verifies with `pg_is_wal_replay_paused()`; raises `RuntimeError` on mismatch | none |
| `is_in_recovery`, `is_wal_replay_paused` (533-548) | single-value `fetchone()[0]` | none |
| `build_ch_create_table_ddl` (551-610) | ``CREATE TABLE IF NOT EXISTS `{db}`.`{table}` (... `_version` UInt64 DEFAULT 0, `is_deleted` UInt8 DEFAULT 0) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY (...) SETTINGS index_granularity = 8192`` | backticks without doubling; the docstring says `_version Nullable(UInt64)`, the code emits `UInt64 DEFAULT 0` |
| `resolve_credentials_from_pgpass` (613-630) | first non-comment line with at least 5 `:`-separated fields returns `(parts[3], parts[4])` | no host, port, database or user matching; no `\:` unescaping (D-13.02-8) |

The two LSN encodings differ. In the repro on `'9C9/21AE7C20'`,
`get_current_lsn` returns `565083168` (low half) and `get_standby_lsn` returns
`10759458159648`. The `get_standby_lsn` docstring example says
`10755683362016`, which is arithmetically wrong.

`pg_is_wal_replay_paused()` reports that a pause was requested, not that replay
has reached the paused state. This is PostgreSQL 14+ semantics; the helper
does not consult `pg_get_wal_replay_pause_state()`. The consequence is analysed
in 13.07, and it is not a defect claim here.

### 3.8 Session settings matrix

| Setting | MySQL factory (both) | MySQL tool-level additions | ClickHouse factory (both) | PostgreSQL factory |
|---|---|---|---|---|
| Time zone | not set (server default) | legacy checksum: `set time_zone = '+00:00'` (`db_compare/mysql_table_checksum.py:210`); packaged checksum: none (`ch_sink_tools/db_compare/mysql_table_checksum.py:174`); legacy orchestrator reads `@@session.time_zone`/`@@system_time_zone` to resolve `--source_timezone` (`db_compare/top_level_table_checksum.py:333`) | not set; the driver converts returned DateTime values with the server zone (`use_client_time_zone` off) | not set (server `TimeZone`); the checksum uses explicit `AT TIME ZONE 'UTC'` for timestamptz (13.07) |
| Charset/collation | `charset=utf8mb4`, `SET NAMES utf8mb4` | both checksums: `set names utf8mb4` | n/a (native protocol, UTF-8 strings) | client encoding default |
| Idle timeout | `wait_timeout=28000` (init_command) | both checksums repeat `set session wait_timeout=28000` | n/a | n/a |
| Statement timeout | none | none | none (`max_execution_time` unset); 300 s per socket read | `statement_timeout=0` (unbounded) |
| Connect timeout | 10 s (PyMySQL default) | n/a | 20 s | 20 s |
| Transactions | autocommit off + autobegin; never committed; rollback on close | `LOCK TABLES` (legacy) implicitly commits; `FLUSH TABLE ... WITH READ LOCK` (packaged) | n/a | `autocommit=True` |
| TLS | none, not configurable | n/a | `secure` flag only; no CA/cert options | libpq default (`prefer`) |
| Other | `client_flag=FOUND_ROWS` | count tools: `set local innodb_parallel_read_threads=32` per count statement | none | `options='-c statement_timeout=0'` |

### 3.9 Credential sources per tool

| Tool (entry point) | Copy | MySQL credentials | ClickHouse credentials | PostgreSQL credentials |
|---|---|---|---|---|
| `mysql_table_checksum.py` / `ch-mysql-checksum` children | both | `--mysql_password` + `--mysql_user` (asserted), else `--defaults_file` (default `~/.my.cnf`) `[client]` | n/a | n/a |
| `mysql_table_count.py` | both | same | n/a | n/a |
| `clickhouse_table_checksum.py` / `ch-ch-checksum` | both | n/a | `--clickhouse_password` (+ `--clickhouse_user`, asserted), else `--clickhouse_config_file` (default `./clickhouse-client.xml`, relative to the working directory) | n/a |
| `clickhouse_table_count.py` / `ch-ch-count` | both | n/a | same | n/a |
| `top_level_table_checksum.py` (orchestrator) | both | always `--defaults_file`; `--mysql_user` is read and then overwritten (`db_compare/top_level_table_checksum.py:450-452`; packaged `:264-266`); there is no `--mysql_password` | `--clickhouse_user`, `--clickhouse_config_file`, `--clickhouse_port`, `--secure` are accepted and never forwarded to the children (D-13.02-11) | n/a |
| `mysql_dumper.py` / `ch-mysql-dump` | both | `--mysql_password` (+ `--mysql_user`), else `--defaults_file`; the catalog connection uses the resolved pair; `mysqlsh` receives the CLI values plus `--defaults-file={defaults_file}` | n/a | n/a |
| `clickhouse_loader.py` / `ch-mysql-load` | both | n/a | `--clickhouse_password` (+ `--clickhouse_user`, asserted), else `--clickhouse_config_file`; the resolved password is then put on the `clickhouse-client` command line (section 3.11) | n/a |
| `ch-mysql-resync` | packaged | `MYSQL_PWD` environment variable, given to `mysqlsh --passwords-from-stdin` on stdin (11.04) | `--ch-config` file, given to `clickhouse-client --config-file` and to the loader `--clickhouse_config_file` | n/a |
| `ch-pg-dump` | packaged | n/a | `--ch_password` (default env `CH_PASSWORD`), else `--ch_config_file`; `--ch_user` (default env `CH_USER`, then `default`); connector config keys `clickhouse.server.user/password` via `--config` | `--pg_password` (default env `PG_PASSWORD`), else the first `~/.pgpass` entry; connector config keys `database.user/password` |
| `ch-checksum` (`top_level_postgres_checksum`) | packaged | n/a | YAML `clickhouse.user` (default `default`), `clickhouse.password` (default `''`), else `clickhouse.config_file` (exceptions only logged as WARNING); `clickhouse.secure` via `bool()` | YAML `source.postgres.user/password`, else the first entry of `source.postgres.pgpass_file` (default `~/.pgpass`) |
| `ch-pg-checksum`, `ch-pg-count` | packaged | n/a | n/a | `--pg_password` (+ `--pg_user`, asserted), else `--pgpass_file` (default `~/.pgpass`), first entry; exit 1 if none |

Environment variables read by the toolset: `PG_HOST`, `PG_PORT`, `PG_DATABASE`,
`PG_USER`, `PG_PASSWORD`, `CH_HOST`, `CH_PORT`, `CH_DATABASE`, `CH_USER`,
`CH_PASSWORD`, `PG_BIN_DIR` (`postgres_dumper.py:1748-1787`, `:86`, `:1879`);
`MYSQL_PWD` and `PYTHONPATH` (`mysql_resync.py:308`, `:361`). The resync tool
also sets `RESYNC_*` for its embedded `mysqlsh` script. libpq implicitly honours
`PG*` variables (libpq behaviour). No MySQL or ClickHouse tool reads an
environment variable for credentials.

Port defaults: MySQL 3306, ClickHouse 9000 (even with TLS, see D-13.02-19),
PostgreSQL 5432. `ch-pg-dump` maps the connector's HTTP ports 8123/8443 to
9000/9440 (`postgres_dumper.py:1593-1597`). Ports are strings when they come
from argparse without `type=int` (the checksum and count tools); the MySQL
factory `int()`s them and the ClickHouse driver accepts strings.

### 3.10 Call-site inventory (connections)

| Call site | Copy | Factory | Credentials from | Closed? |
|---|---|---|---|---|
| `db_compare/top_level_table_checksum.py:167` | legacy | `get_mysql_connection` (lock) | `.cnf` | yes, `close_connection` in `finally` (`:194-199`) |
| `db_compare/top_level_table_checksum.py:492` | legacy | `get_mysql_connection` (metadata, per database) | `.cnf` | no |
| `ch_sink_tools/db_compare/top_level_table_checksum.py:305` | packaged | `get_mysql_connection` (metadata) | `.cnf` | no |
| `ch_sink_tools/db_compare/top_level_table_checksum.py:321` | packaged | `get_mysql_connection` (per-table lock; also rebinds `conn`) | `.cnf` | no (unlocked, never closed) |
| `db_compare/mysql_table_checksum.py:277` / `ch_sink_tools/...:239` | both | `get_mysql_connection` (per chunk) | flag or `.cnf` | yes (`calculate_sql_checksum` `finally`, `:270-271` / `:232-233`) |
| `db_compare/mysql_table_checksum.py:302` / `ch_sink_tools/...:264` | both | `get_mysql_connection` (per table, metadata) | flag or `.cnf` | no |
| `db_compare/mysql_table_checksum.py:454` / `ch_sink_tools/...:411` | both | `get_mysql_connection` (main) | flag or `.cnf` | no |
| `db_compare/mysql_table_count.py:25` / `ch_sink_tools/...:28` | both | `get_mysql_connection` | flag or `.cnf` | yes (`finally`) |
| `db_compare/mysql_table_count.py:125` / `ch_sink_tools/...:123` | both | `get_mysql_connection` | flag or `.cnf` | yes (in `calculate_sql_count`) |
| `db_compare/mysql_table_count.py:210` / `ch_sink_tools/...:208` | both | `get_mysql_connection` (main) | flag or `.cnf` | no |
| `db_dump/mysql_dumper.py:255` / `ch_sink_tools/...:216` | both | `get_mysql_connection` (catalog) | flag or `.cnf` | no |
| `db_compare/clickhouse_table_checksum.py:29` (`get_connection`) via `:37`, `:377`, `:490` | legacy | `clickhouse_connection` | flag or CH config | `:37` yes (`:74`); `:377` and `:490` no |
| `ch_sink_tools/db_compare/clickhouse_table_checksum.py:32` via `:40`, `:289`, `:397` | packaged | `clickhouse_connection` | flag or CH config | `:40` yes (`:81`, half the cursors); `:289` and `:397` no |
| `db_compare/clickhouse_table_count.py:27` via `:59`, `:63`, `:155` | legacy | `clickhouse_connection` | flag or CH config | `:59` and `:63` yes; `:155` no |
| `ch_sink_tools/db_compare/clickhouse_table_count.py:31` via `:63`, `:67`, `:159` | packaged | `clickhouse_connection` | flag or CH config | `:63` and `:67` yes; `:159` no |
| `db_load/clickhouse_loader.py:64` (`get_connection`) via `:294`, `:307`, `:337`, `:347`, `:520` | legacy | `clickhouse_connection` | flag or CH config | yes (`with` block; the DBAPI connection is a context manager) |
| `ch_sink_tools/db_load/clickhouse_loader.py:64` via `:295`, `:308`, `:338`, `:348`, `:488` | packaged | `clickhouse_connection` | flag or CH config | yes (`with`; half the cursors) |
| `ch_sink_tools/db_dump/postgres_dumper.py:469`, `:906`, `:2055`, `:2142`, `:2243`, `:2470`, `:2527`, `:2619` | packaged | `get_postgres_connection` | flag, env or pgpass | yes |
| `ch_sink_tools/db_dump/postgres_dumper.py:2041` | packaged | `get_postgres_connection` (inline argument of `get_server_timezone`) | same | no (left to garbage collection) |
| `ch_sink_tools/db_dump/postgres_dumper.py:943`, `:962`, `:1039`, `:2214`, `:2231`, `:2315`, `:2788` | packaged | `clickhouse_connection` | flag, env or CH config | yes (half the cursors). The comment at `:2208-2209` says the DBAPI connection "is NOT a context manager"; with driver 0.2.11 it is |
| `ch_sink_tools/db_compare/top_level_postgres_checksum.py:798`, `:1384`, `:1420`, `:1960` | packaged | `get_postgres_connection` | YAML or pgpass | yes (`:1054`, `:1939`, `:1431`/`:1465`/`:1929`, `:1978`) |
| `ch_sink_tools/db_compare/top_level_postgres_checksum.py:803`, `:1474`, `:1620`, `:1987` | packaged | `clickhouse_connection` | YAML or CH config | yes (half the cursors) |
| `ch_sink_tools/db_compare/auto_diff.py:984` | packaged | `clickhouse_connection` | caller (YAML) | yes (`:1149`) |
| `ch_sink_tools/db_compare/postgres_table_checksum.py:353`, `:602`, `:744` | packaged | `get_postgres_connection` | flag or pgpass | yes |
| `ch_sink_tools/db_compare/postgres_table_count.py:66`, `:164` | packaged | `get_postgres_connection` | flag or pgpass | yes |
| `ch_sink_tools/db_load/mysql_resync.py:245-251` (`ClickHouse._run`) | packaged | `clickhouse-client` subprocess (argv list, no shell) | `--config-file` | per process |
| `ch_sink_tools/db_load/mysql_resync.py:268-272`, `:333-334` | packaged | `mysqlsh` subprocess (argv list) | password on stdin | per process |

No tool calls `pymysql.connect`, `psycopg2.connect` or `clickhouse_driver.Client(`
directly: every connection goes through the three factories or a CLI
subprocess.

### 3.11 Password exposure: argv, shell strings, logs, exceptions

| Where | Copy | Exposure | Evidence |
|---|---|---|---|
| `--mysql_password`, `--clickhouse_password`, `--pg_password`, `--ch_password` flags | both | in the Python process argv (`ps`-visible) for the whole run; the tools log "Using password on the command line is not secure" | code-read (argparse); design choice with a warning |
| Dockerfile ENTRYPOINTs | legacy tree | `--mysql_password "$MYSQL_PASSWORD"` / `--clickhouse_password "$CLICKHOUSE_PASSWORD"` in exec form: the literal string `$MYSQL_PASSWORD` is passed (no shell expansion), and a real password would sit in ENV and argv (D-13.02-23) | code-read |
| `mysqlsh` command (legacy dumper `db_dump/mysql_dumper.py:156-180`) | legacy | the CLI password only, `shlex.quote`d, in the `sh -c` string; the DEBUG log is redacted (`:86`, `:100`); a `.cnf` password never reaches argv (`--defaults-file`) | reproduced: shell words `['mysqlsh', ..., '--password', 'pa"ss$(id)', ...]`, log `--password '****'` |
| `mysqlsh` command (packaged dumper `ch_sink_tools/db_dump/mysql_dumper.py:122-144`) | packaged | the CLI password in `"..."` without escaping; `run_command` logs `"cmd " + cmd` at DEBUG (`:54`) | reproduced: `--password "pa"ss$(id)"` gives "No closing quotation"; the DEBUG line holds the password (D-13.02-6) |
| `clickhouse-client` load pipeline (legacy loader `db_load/clickhouse_loader.py:417-440`, `:494-549`) | legacy | the password resolved from the config file (or the flag) is placed as `--password <shlex-quoted>` in a `sh -c` pipeline (`export TZ=...; gunzip | sed | sed | clickhouse-client ...`); the shell stays alive with the full string in argv for the whole insert | code-read (D-13.02-5) |
| same, logging | legacy | `execute_load` logs the redacted command at INFO (`:476`); `run_quick_command` logs it unredacted at DEBUG (`:48`); a failure raises `AssertionError("command "+cmd+" failed")` unredacted (`:483`) | reproduced: AssertionError contains the password; DEBUG line `cmd clickhouse-client ... --password chS3cret -mn` (D-13.02-4, FM-11.05-2) |
| `clickhouse-client` load pipeline (packaged loader `ch_sink_tools/db_load/clickhouse_loader.py:418-440`) | packaged | the resolved password as `--password '<pw>'` (no escaping) in `sh -c`; `execute_load` logs the whole command at INFO (`:445`) and raises it unredacted (`:452`); the mysqlshell path uses `args.clickhouse_password` (CLI only, `:463`), so `ch-mysql-resync` (config-file only) puts no password on argv | reproduced (INFO line contains the password) (D-13.02-3, D-13.02-5, FM-11.05-1) |
| `psql` COPY pipeline (`ch_sink_tools/db_dump/postgres_dumper.py:359-366`, `:657-665`) | packaged | `PGPASSWORD='<pw>'` prefix in a `bash -c` string, so it sits in bash argv for the whole COPY; a `'` in the password breaks quoting; logged at DEBUG (`:502`, `:669`, `:861`) | reproduced: `PGPASSWORD='it's'` gives "No closing quotation" (D-13.02-7) |
| `clickhouse-client` insert (`postgres_dumper.py:389`, `:415-426`) | packaged | `--password '<pw>'` in the same `bash -c` string | reproduced (command text) |
| `pg_dump` (`postgres_dumper.py:762-781`) | packaged | `PGPASSWORD` in the child environment only (`/proc/<pid>/environ`, same-user readable); argv list | code-read; correct pattern |
| `ch-mysql-resync` | packaged | `MYSQL_PWD` stays in the environment of every child (`mysqlsh`, loader); `mysqlsh` gets it on stdin; never on argv | code-read (11.04) |
| Packaged CH resolver DEBUG line (`ch_sink_tools/db/clickhouse.py:68`) | packaged | password in clear when `--debug` is set: `ch-ch-checksum` (`:382` before `:395`), `ch-ch-count` (`:144` before `:157`), `ch-pg-dump` (`:1971` before `:2025`), `ch-checksum` (`:2126`, resolver at `:1216`) | reproduced (D-13.02-2) |
| `.cnf` parse error | both | `InterpolationSyntaxError` text contains the password tail; uncaught, so it reaches the traceback | reproduced (D-13.02-9) |
| Packaged MySQL URL mis-parse | packaged | password fragment inside the host name in the PyMySQL "Can't connect" error | parse reproduced; message code-read (D-13.02-1) |
| Driver errors otherwise | all | no password (SQLAlchemy masks the URL; clickhouse-driver prints `host:port`; psycopg2 prints user and host) | reproduced for ClickHouse; code-read for the others |

### 3.12 Error wrapping and logging

- No layer function catches or wraps a connection or query error. Errors
  propagate as `sqlalchemy.exc.OperationalError` / `ProgrammingError`,
  `clickhouse_driver.dbapi.errors.OperationalError`, or
  `psycopg2.OperationalError`. The tools catch `Exception` in `main`, log
  `"Exception in main thread : " + str(e)` plus the traceback, and exit 1. The
  credential resolvers run before that `try`, so their exceptions are uncaught
  tracebacks with exit 1.
- The only swallowing in the layer is `get_standby_lsn` (any exception from the
  replay-LSN probe means "primary"; the fallback query still fails loudly on a
  standby) and `execute_pg` (`ProgrammingError` at fetch becomes `[]`). There
  are no retry loops anywhere in the Python tree: a search for
  retry/attempt/backoff finds none.
- All execute helpers log the full SQL at DEBUG. The ClickHouse factory logs
  nothing at connect. The MySQL factory relies on SQLAlchemy, whose `echo` is
  off.

### 3.13 Legacy versus packaged, function by function

| Function | Legacy behaviour | Packaged behaviour | Behavioural effect |
|---|---|---|---|
| `binary_datatypes` | 16 keywords incl. `tinyblob`, `mediumblob`, `longblob`, `geometrycollection` | 12 keywords | packaged relies on substring matching to catch `*blob` |
| `is_binary_datatype` | bare-keyword exact match | substring match, then exact | packaged misclassifies ENUM/SET labels and `... binary` (D-13.02-21) |
| `get_mysql_connection` | `quote_plus` on user and password | raw | packaged breaks on `@` and decodes `%xx` (D-13.02-1) |
| `get_table_partition_key` (MySQL) | `.mappings().fetchall()` | `.fetchall()` + `row['...']` | packaged raises `TypeError` under SQLAlchemy 2.x when the table exists |
| `mysql_columns_by_data_type` | present | absent | packaged checksum cannot list TIMESTAMP, binary or JSON columns per type |
| `clickhouse_connection` | password `None` passed through | `None` becomes `""` | legacy crashes at hello on an XML config without `<password>` |
| `clickhouse_execute_conn` | closes the cursor | leaks the cursor and its TCP connection | D-13.02-18 |
| CH `resolve_credentials_from_config` | `yaml.safe_load`; password masked in DEBUG | `yaml.FullLoader`; password in DEBUG | D-13.02-2 |
| All other functions (`get_tables_from_regex_sql`, `get_tables_from_regex`, `get_partitions_from_regex`, `mysql_execute_df`, `execute_mysql`, MySQL `resolve_credentials_from_config`, `estimate_table_count`, `get_min_max_pk_value`, `mysql_columns`, `mysql_pk_columns`, `divide_table_into_even_chunks`, CH `get_table_partition_key`, `execute_sql`) | identical | identical | none |
| PostgreSQL layer | absent | present | PostgreSQL tools exist only in the packaged tree |

Tool-level divergences in credential handling: the packaged dumper and packaged
loader have no `register_secret` / `redact_password`; the legacy ones do. The
packaged checksum issues no `set time_zone`.

### 3.14 Library versions this behaviour was observed with

SQLAlchemy 2.1.1, PyMySQL 2.2.8, clickhouse-driver 0.2.11, psycopg2 2.9.13,
PyYAML 6.0.3, pandas 2.3.3, Python 3.12. Under SQLAlchemy 1.4, `Row` still
accepts string keys (with a deprecation warning), so D-13.02-12 only fires on
2.x installs. Both `requirements.txt` and `pyproject.toml` allow 2.x.

## 4. Invariants Preserved

- **I-13.02-1 Read-only MySQL sessions.** Every statement the layer runs
  through `execute_mysql` / `mysql_execute_df` is inside an uncommitted
  transaction that `close()` rolls back. No caller issues DML through the
  layer: only `SELECT`, `EXPLAIN`, `SET`, `LOCK`/`UNLOCK`, `FLUSH ... WITH READ
  LOCK`. A DML statement routed through it would be discarded silently.
- **I-13.02-2 Connect attempts are bounded; reads are not.** Connect timeouts
  are 10 s (MySQL, library default), 20 s (ClickHouse) and 20 s (PostgreSQL).
  Query execution is unbounded on MySQL (`read_timeout=None`) and PostgreSQL
  (`statement_timeout=0`), and bounded per socket operation (300 s) on
  ClickHouse.
- **I-13.02-3 Errors are loud.** No retry and no swallow in the layer except
  the two documented cases (3.12). A failed connect or query reaches `main`
  and exits 1.
- **I-13.02-4 MySQL option-file passwords never reach argv.** This holds: the
  dumpers pass `--defaults-file`, and the orchestrators pass
  `--defaults_file=<path>` to the child scripts. It does not hold for
  ClickHouse config-file passwords in the loaders (D-13.02-5), nor for
  PostgreSQL passwords in `ch-pg-dump` (D-13.02-7).
- **I-13.02-5 The resync tool never puts a password on a command line**
  (11.04). This holds for MySQL (stdin) and ClickHouse (config file), and also
  for the loader it spawns: the packaged mysqlshell path ignores the
  file-resolved password.
- **I-13.02-6 Legacy redaction (11.05 section 3.1).** Every registered secret
  and every `--password <word>` is masked in the lines that go through
  `redact_password`. This holds for the legacy dumper. It is violated by the
  legacy loader's DEBUG line and exception text (D-13.02-4). There is no
  redaction at all in the packaged tools.
- **Intended but violated:** identifier and value quoting in catalog SQL
  (D-13.02-15); one closed session per opened connection (D-13.02-17,
  D-13.02-18); the same session state for the source and replica checksum
  sides (D-13.02-10).

## 5. Verification Criteria

Existing tests (offline suite: 227 passed, 5 skipped):

- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestRedactPassword::test_registered_secret_is_masked`,
  `::TestRedactPassword::test_password_flag_value_masked_even_if_unregistered`,
  `::TestRedactPassword::test_secret_with_single_quote_is_fully_masked`,
  `::TestRedactPassword::test_non_secret_text_preserved`: legacy dumper
  redaction (3.11).
- `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_logged_command_is_redacted`:
  the legacy INFO line is redacted.
  `::TestLegacyLoaderFailurePath::test_failure_message_is_redacted` and
  `::TestPackagedLoaderRedaction::test_logged_command_is_redacted` are skipped
  as known defects (D-13.02-4, D-13.02-3).
- `sink-connector/python/db_compare/tests/test_table_locking.py::TestCloseConnection::test_close_connection_calls_close`,
  `::TestCloseConnection::test_close_connection_swallows_exception`,
  `::TestComputeChecksumLockLifecycle::test_connection_closed_even_if_unlock_fails`,
  `::TestLockStatement::test_lock_escapes_table_name`: the legacy lock
  connection lifecycle and identifier quoting. The factory is mocked, so the
  pooled-socket reality of D-13.02-17 is not seen.
- `sink-connector/python/db_compare/tests/test_bounded_source_lock.py::TestLockTables::test_sets_session_timeout_before_lock`:
  the per-session `lock_wait_timeout`.
- `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestInstantComparison::test_mysql_session_renders_timestamps_in_utc`
  and `::TestInstantComparison::test_driver_resolves_the_source_zone_from_mysql`:
  the legacy tool-level time-zone pinning and resolution. Nothing tests the
  packaged copy.

Offline repros run for this spec. They used throwaway scripts with the drivers
mocked or never connected, and no database or network:

1. URL building: the packaged URL mis-parses `p@ss` into host `ss@db1` and
   decodes `a%41b` to `aAb` (3.2).
2. The real `get_mysql_connection` / `execute_mysql` / `mysql_execute_df` were
   driven with a fake `pymysql.connect`. This showed the connect keyword
   arguments, the autobegin and rollback-on-close behaviour, that commit is
   never called, that `close()` does not close the DBAPI connection while
   `dispose()` does, the `TypeError` on `row['table_name']`, and `text()`
   binding `:b` (3.2, 3.3).
3. Credential files: the `.cnf` cases of 3.5; the ClickHouse resolver outputs
   and the packaged DEBUG password; `.pgpass` returning the first entry and
   truncating `pa\:ss` to `pa\` (3.6, 3.7).
4. ClickHouse factory: the `secure='False'` truthiness, the forced port 9000,
   the hello `AttributeError` on a `None` user or password, the cursor leak
   (5 open; 2 left after `close()`), and a connect error text without the
   password (3.6).
5. Subprocess strings with `subprocess.Popen` mocked: the packaged dumper
   quoting break and DEBUG leak, legacy dumper redaction, the legacy loader
   exception and DEBUG leak, the packaged loader INFO leak, and the PostgreSQL
   builders' quoting break (3.11).
6. Quoting: the catalog SQL with `'` and `` ` `` in names, the regex semantics
   of `'^'+table+'$'`, `get_schemas` parameterised versus `get_tables`
   interpolated, and the `is_binary_datatype` table (3.4, 3.7).
7. Packaging: importing `ch_sink_tools.db.mysql` with pandas absent raises
   `ImportError` (D-13.02-22).
8. LSN encodings (3.7) and the `get_tables_from_regex(no_wc=True)` return type
   (D-13.02-13).

Acceptance criteria for a fixed layer. Each one is a GAP test today:

- With a fake DBAPI and a password `p@ss%41/#?`, both copies' factories hand
  exactly that password and host `db1` to the driver.
- No log record at any level, and no exception text, contains a registered
  password, for every tool path in 3.11.
- `execute_mysql` results support name access (or `.mappings()` is used) on
  SQLAlchemy 1.4 and 2.x.
- After `close()` of a factory connection, the DBAPI `close()` has been called,
  and the ClickHouse helper leaves zero open clients.
- `.cnf` parsing matches MySQL option-file semantics for `%`, quotes, inline
  comments, duplicates and bare options.
- `resolve_credentials_from_pgpass(host, port, db, user)` follows libpq
  matching, wildcards and escaping.
- `--secure False` gives a plaintext connection, and `--secure True` without a
  port uses 9440.
- The packaged MySQL checksum session pins `time_zone`, as the legacy one does.

## 6. Failure Modes & Recovery

- **FM-13.02-1 Password containing `@` or `%xx` with a packaged MySQL tool**
  - **Trigger**: any packaged MySQL entry point (`ch-mysql-checksum` metadata
    connection, `ch-mysql-dump`) with a password such as `p@ss` or `a%41b`,
    from the CLI or a `.cnf`.
  - **Behaviour**: `ch_sink_tools/db/mysql.py:21-22` builds the URL without
    encoding. SQLAlchemy parses user `app`, password `p`, host `ss@db1`, or
    decodes `%41` to `A`. The connect fails with "Can't connect to MySQL server
    on 'ss@db1'" or "Access denied".
  - **Detection**: an immediate traceback at startup. The host in the error
    contains part of the password.
  - **Blast radius**: the tool cannot run. A fragment of the password lands in
    the job log.
  - **Recovery**: use the legacy copy, which uses `quote_plus` at
    `db/mysql.py:29-30`, or a password without URL metacharacters. Rotate the
    password if the log is shared.
  - **RTO**: unmeasured (operator re-run; minutes).
  - **Test**: `GAP: fake-DBAPI test asserting the driver receives the exact password and host for both copies`
  - **DEFECT**: the packaged factory does not URL-encode credentials
    (D-13.02-1).

- **FM-13.02-2 ClickHouse password printed by `--debug`**
  - **Trigger**: `ch-ch-checksum`, `ch-ch-count`, `ch-pg-dump` or `ch-checksum`
    run with `--debug`, with credentials read from a ClickHouse config file.
  - **Behaviour**:
    `logging.debug(f"... clickhouse_password {clickhouse_password}")` at
    `ch_sink_tools/db/clickhouse.py:68`. The legacy copy masks it
    (`db/clickhouse.py:71`).
  - **Detection**: none. The line looks routine.
  - **Blast radius**: the password sits in plain text in every debug log and
    log shipper.
  - **Recovery**: rotate the password and scrub the logs. Avoid `--debug` with
    the packaged tools until the line is masked.
  - **RTO**: a credential rotation; unmeasured (operator-dependent).
  - **Test**: `GAP: assertLogs(DEBUG) around the packaged resolver must not contain the password`
  - **DEFECT**: the packaged ClickHouse resolver logs the password (D-13.02-2).

- **FM-13.02-3 Packaged loader logs and raises the ClickHouse password**
  - **Trigger**: `ch-mysql-load` without `--mysqlshell`, using either a config
    file or `--clickhouse_password`, or `--mysqlshell` with
    `--clickhouse_password`.
  - **Behaviour**: the password is put in the command as `--password '<pw>'`
    (`ch_sink_tools/db_load/clickhouse_loader.py:421`, `:466`). `execute_load`
    logs the whole command at INFO (`:445`) and raises it unredacted on failure
    (`:452`). A `'` in the password breaks the shell command.
  - **Detection**: none from the tool.
  - **Blast radius**: the INFO log of every load.
  - **Recovery**: rotate and scrub. Run the loader with
    `--clickhouse_config_file --mysqlshell`, the resync path, where no password
    reaches the command.
  - **RTO**: a credential rotation; unmeasured.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestPackagedLoaderRedaction::test_logged_command_is_redacted` (skipped as DEFECT)
  - **DEFECT**: the packaged loader has no redaction (D-13.02-3; same root as
    FM-11.05-1).

- **FM-13.02-4 Legacy loader leaks the password on failure or at DEBUG**
  - **Trigger**: a legacy loader insert fails, or the loader runs at DEBUG
    level.
  - **Behaviour**: `run_quick_command` logs `"cmd " + cmd` at DEBUG
    (`db_load/clickhouse_loader.py:48`). `execute_load` raises
    `AssertionError("command "+cmd+" failed")` (`:483`). Repro: the exception
    contains the password, and so does the DEBUG line.
  - **Detection**: the traceback is visible, but nothing flags the secret.
  - **Blast radius**: failure logs; for resync with `--loader-cmd` pointing at
    the legacy loader, `load_<schema>.<table>.log`.
  - **Recovery**: rotate and scrub. The fix is `redact_password(cmd)` in both
    places.
  - **RTO**: a credential rotation; unmeasured.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_failure_message_is_redacted` (skipped as DEFECT)
  - **DEFECT**: the legacy redaction is incomplete (D-13.02-4; same root as
    FM-11.05-2).

- **FM-13.02-5 Config-file ClickHouse password visible in the process list**
  - **Trigger**: either loader run with `--clickhouse_config_file` (no CLI
    password), on the non-mysqlshell path (both copies) or the legacy
    mysqlshell path.
  - **Behaviour**: the password resolved from the file is appended as
    `--password ...` to a `sh -c` pipeline (`db_load/clickhouse_loader.py:417-421`,
    `:494-498`; `ch_sink_tools/db_load/clickhouse_loader.py:418-421`). The shell
    process holds the string in argv until the insert ends. `--config-file` is
    passed as well, so the argv copy is redundant.
  - **Detection**: `ps -ef` on the load host shows it. Nothing logs it.
  - **Blast radius**: any local user can read the password for the whole
    duration of the load.
  - **Recovery**: rotate. Drop the `--password` option when a config file was
    given.
  - **RTO**: a credential rotation; unmeasured.
  - **Test**: `GAP: build the load command from a config-file credential and assert no --password option`
  - **DEFECT**: a file-sourced password is moved onto argv (D-13.02-5).

- **FM-13.02-6 Packaged dumper: password with shell metacharacters**
  - **Trigger**: `ch-mysql-dump --mysql_password 'pa"ss$(id)'`, or any password
    with `"`, `$`, `` ` `` or `\`.
  - **Behaviour**: `--password "{mysql_password}"`
    (`ch_sink_tools/db_dump/mysql_dumper.py:124`) is parsed by `sh`. `$(...)`
    and backticks run commands, and an unbalanced `"` breaks the command. The
    command is logged unredacted at DEBUG (`:54`). Repro: shell parse error;
    the DEBUG line holds the password.
  - **Detection**: the dump fails with a `mysqlsh` or shell error. The
    injection itself is silent.
  - **Blast radius**: local command execution as the tool user, with the
    password as payload. Password in debug logs.
  - **Recovery**: use the legacy dumper (`shlex.quote` plus redaction) or a
    `.cnf`. Rotate if it was logged.
  - **RTO**: unmeasured (operator re-run).
  - **Test**: `GAP: packaged generate_mysqlsh_command with a quote and $() password must round-trip through shlex.split`
  - **DEFECT**: no shell quoting and no redaction in the packaged dumper
    (D-13.02-6).

- **FM-13.02-7 `ch-pg-dump` exposes passwords in `bash -c` strings**
  - **Trigger**: any `ch-pg-dump` run in psql-copy or pipe mode.
  - **Behaviour**: `PGPASSWORD='{pg_password}'` and
    `--password '{ch_password}'` are interpolated into a `bash -c` command
    (`ch_sink_tools/db_dump/postgres_dumper.py:360`, `:389`, `:658`). The
    commands are logged at DEBUG (`:502`, `:669`, `:861`). A `'` in either
    password breaks quoting (repro).
  - **Detection**: none for the exposure. A quoting break fails the table load
    with a non-zero exit.
  - **Blast radius**: both passwords are readable via `ps` for every table copy,
    and appear in debug logs.
  - **Recovery**: rotate. Pass `PGPASSWORD` through `env=` (as `pg_dump` mode
    already does at `:762-763`) and the ClickHouse password through
    `--config-file`.
  - **RTO**: a credential rotation; unmeasured.
  - **Test**: `GAP: build_psql_copy_cmd / build_ch_insert_cmd must not contain the password text`
  - **DEFECT**: passwords are embedded in shell strings (D-13.02-7).

- **FM-13.02-8 `.pgpass` with several entries**
  - **Trigger**: `ch-pg-checksum`, `ch-pg-count`, `ch-pg-dump` or `ch-checksum`
    without an explicit password, where `~/.pgpass` lists more than one server
    or a password containing `:`.
  - **Behaviour**: `resolve_credentials_from_pgpass`
    (`ch_sink_tools/db/postgres.py:613-630`) returns the first entry whatever
    the host, port, database and user. `pa\:ss` becomes `pa\`. `ch-pg-dump`
    keeps `--pg_user` and takes another entry's password
    (`postgres_dumper.py:2007-2014`); `ch-checksum` does the same with its YAML
    user (`top_level_postgres_checksum.py:1197-1203`).
  - **Detection**: "password authentication failed", or a successful login as
    the wrong user with the wrong privileges.
  - **Blast radius**: the tool fails, or runs with another account's
    credentials.
  - **Recovery**: pass `--pg_password` / `source.postgres.password`, or keep
    one entry per file via `--pgpass_file`.
  - **RTO**: unmeasured (operator re-run).
  - **Test**: `GAP: multi-entry and escaped-colon pgpass fixtures`
  - **DEFECT**: there is no libpq-style matching or unescaping (D-13.02-8).

- **FM-13.02-9 MySQL option file in real MySQL syntax**
  - **Trigger**: a `.cnf` with `%` in the password, quoted values, inline
    comments, bare options, `!include`, duplicate keys, or no `user`.
  - **Behaviour**: `configparser.ConfigParser()` with interpolation
    (`db/mysql.py:133-137`, `ch_sink_tools/db/mysql.py:125-129`) raises, or
    returns the wrong value (repro table in 3.5). The `%` error message carries
    the password tail and is printed as an uncaught traceback.
  - **Detection**: a traceback at startup, or "Access denied" for quoted or
    commented values.
  - **Blast radius**: every MySQL tool (both trees) refuses to start. Password
    fragment in stderr.
  - **Recovery**: write a minimal `[client]` file with `user=` and `password=`
    unquoted, with `%` doubled as `%%`.
  - **RTO**: unmeasured (minutes).
  - **Test**: `GAP: .cnf fixtures for each row of the 3.5 table`
  - **DEFECT**: the option-file parser is not MySQL-compatible and leaks via the
    exception (D-13.02-9).

- **FM-13.02-10 Server time zone differs from UTC (packaged MySQL checksum)**
  - **Trigger**: the packaged MySQL checksum (`ch-mysql-checksum` children run
    from the packaged tree, or `ch_sink_tools/db_compare/mysql_table_checksum.py`
    directly) against a server whose `time_zone` is not UTC, on tables with
    `TIMESTAMP` columns.
  - **Behaviour**: the factory sets no `time_zone`. The packaged statements
    are only `set names` and `wait_timeout`
    (`ch_sink_tools/db_compare/mysql_table_checksum.py:174`), and `TIMESTAMP` is
    rendered by `cast(col as char)` (`:111`) in the server's zone. The legacy
    copy pins `'+00:00'` (`db_compare/mysql_table_checksum.py:210`).
  - **Detection**: checksum mismatches on every table with TIMESTAMP columns.
    In a DST fall-back hour, two instants render the same.
  - **Blast radius**: false mismatches on correct replicas. Results depend on
    the server configuration.
  - **Recovery**: run the legacy checksum, which pins UTC and resolves
    `--source_timezone` (11.02 section 3.4).
  - **RTO**: unmeasured.
  - **Test**: `GAP: packaged session statements must include time_zone`
  - **DEFECT**: the session time zone is pinned in neither factory and in only
    one checksum copy (D-13.02-10).

- **FM-13.02-11 Orchestrator children connect with default ports and credentials**
  - **Trigger**: `top_level_table_checksum.py` (either copy) with
    `--mysql_port` other than 3306, a non-default `--clickhouse_port`,
    `--secure`, `--clickhouse_config_file`, or `--clickhouse_user`.
  - **Behaviour**: the child commands (`db_compare/top_level_table_checksum.py:265`,
    `:321`; `ch_sink_tools/db_compare/top_level_table_checksum.py:159`, `:186`)
    carry none of these flags. The MySQL child connects to 3306. The ClickHouse
    child connects to 9000 in plaintext with `./clickhouse-client.xml` from the
    working directory. The orchestrator's own metadata connection does use
    `--mysql_port`.
  - **Detection**: child failures ("command failed"), or checksums computed
    against a different instance on a multi-instance host.
  - **Blast radius**: wrong or failed verification for the whole run.
  - **Recovery**: run the child scripts directly with explicit flags, or use
    default ports and a `clickhouse-client.xml` in the working directory.
  - **RTO**: unmeasured.
  - **Test**: `GAP: assert get_mysql_checksum_command / get_clickhouse_checksum_command forward port, TLS and credential-file flags`
  - **DEFECT**: connection settings are not forwarded to the child processes
    (D-13.02-11).

- **FM-13.02-12 Fresh install with SQLAlchemy 2.x**
  - **Trigger**: `pip install -r requirements.txt` or the `[mysql]` extra
    today, which resolves SQLAlchemy 2.x.
  - **Behaviour**: `execute_mysql` returns tuple-like rows. Name access raises
    `TypeError` at `ch_sink_tools/db/mysql.py:80`,
    `ch_sink_tools/db_compare/top_level_table_checksum.py:314`,
    `ch_sink_tools/db_dump/mysql_dumper.py:234`/`:239`,
    `ch_sink_tools/db_compare/mysql_table_count.py:70`/`:215`,
    `ch_sink_tools/db_compare/mysql_table_checksum.py:419`,
    `db_compare/top_level_table_checksum.py:501`,
    `db_dump/mysql_dumper.py:273`/`:278`, and
    `db_compare/mysql_table_count.py:70`/`:217`.
  - **Detection**: `TypeError: tuple indices must be integers or slices, not str`
    at the first table.
  - **Blast radius**: the legacy orchestrator, both dumpers, both count tools,
    and the packaged checksum and orchestrator cannot run. Only the legacy
    `mysql_table_checksum` works (`.mappings()`).
  - **Recovery**: pin `sqlalchemy<2` in the environment, or use `.mappings()`.
  - **RTO**: unmeasured (reinstall; minutes).
  - **Test**: `GAP: run each tool's table loop against a fake DBAPI under SQLAlchemy 2.x`
  - **DEFECT**: the row-access contract does not match the allowed dependency
    range (D-13.02-12).

- **FM-13.02-13 `--no_wc`**
  - **Trigger**: `--no_wc` on `mysql_table_checksum.py` or
    `mysql_table_count.py` (either copy), or on the orchestrator.
  - **Behaviour**: `get_tables_from_regex` returns `[[regex]]`
    (`db/mysql.py:54-55`; `ch_sink_tools/db/mysql.py:46-47`). The callers call
    `.mappings()`/`.fetchall()` on it (AttributeError), or then index
    `['table_name']`.
  - **Detection**: `AttributeError: 'list' object has no attribute 'mappings'`.
  - **Blast radius**: the flag is unusable.
  - **Recovery**: use `--tables_regex '^name$'` instead.
  - **RTO**: unmeasured (minutes).
  - **Test**: `GAP: no_wc path through each caller`
  - **DEFECT**: the return type differs by branch (D-13.02-13).

- **FM-13.02-14 A `:word` inside a literal passed through `execute_mysql`**
  - **Trigger**: a `--where` or per-table YAML `where` containing, for example,
    `note = 'x :y'`, executed via `execute_mysql` (checksum and count
    statements).
  - **Behaviour**: `text()` at `db/mysql.py:118` /
    `ch_sink_tools/db/mysql.py:110` binds `:y`, and execution raises "A value is
    required for bind parameter 'y'".
  - **Detection**: an immediate exception naming the bind parameter.
  - **Blast radius**: that table or run fails. There is no wrong result.
  - **Recovery**: rewrite the predicate to avoid `space + colon + word` (for
    example `concat('x ', ':y')`).
  - **RTO**: unmeasured.
  - **Test**: `GAP: execute_mysql with a colon-word literal against a fake DBAPI`
  - **DEFECT**: raw SQL goes through a bind-parsing constructor (D-13.02-14).

- **FM-13.02-15 Quote or backtick in a schema, table or regex**
  - **Trigger**: a table named `o'brien` or ``we`ird``, a regex containing
    `'`, or a PostgreSQL table with `'`.
  - **Behaviour**: unescaped interpolation (3.4 table; PostgreSQL helpers at
    `ch_sink_tools/db/postgres.py:303-311`, `:360-361`, `:411-412`,
    `:427-428`; ClickHouse at `db/clickhouse.py:33`). Repro:
    `table_name = 'o'brien'`, ``from `we`ird` ``, and the PostgreSQL
    `relname = 'o'brien'`.
  - **Detection**: a syntax error at that table. A crafted regex
    (`^a' or '1'='1`) changes the query semantics instead.
  - **Blast radius**: that table cannot be dumped, counted or checksummed. The
    operator-supplied regex can widen the selection.
  - **Recovery**: exclude the table by regex and handle it manually.
  - **RTO**: unmeasured.
  - **Test**: `GAP: quoting tests for every builder in 3.4/3.7`
  - **DEFECT**: no identifier or value escaping in the catalog SQL
    (D-13.02-15).

- **FM-13.02-16 Table name with regex metacharacters**
  - **Trigger**: MySQL tables such as `price$hist` or `a.c` with
    `{partition_expression}` in `--where`, or the orchestrator passing the
    partition key.
  - **Behaviour**: `get_table_partition_key` matches `'^'+table+'$'` with
    `RLIKE` (`db/mysql.py:84`). `price$hist` matches nothing, so `None` is
    returned and `{partition_expression}` is left unsubstituted. `a.c` also
    matches `abc`; ordered by name with `limit 1`, `a.c` comes first in this
    case, but other names can resolve to another table's expression.
  - **Detection**: a SQL error on the literal `{partition_expression}`, or a
    silently different partition filter.
  - **Blast radius**: partition-scoped checksums or counts of those tables.
  - **Recovery**: avoid `{partition_expression}` for such tables; use an
    explicit per-table `where`.
  - **RTO**: unmeasured.
  - **Test**: `GAP: get_table_partition_key with metacharacter names (escape or compare with =)`
  - **DEFECT**: an identifier is used as a regex (D-13.02-16).

- **FM-13.02-17 MySQL session accumulation in long or parallel runs**
  - **Trigger**: checksums with many tables, `--threads`, `--threads_per_table`,
    or orchestrator lock connections.
  - **Behaviour**: each `get_mysql_connection` creates an engine and a pool.
    `close()` returns the socket to that pool and never closes it (repro). Eleven
    call sites (five legacy, six packaged) never call `close()` at all (3.10). Sessions persist until
    garbage collection or process exit, which then closes the sockets without
    COM_QUIT. The shared metadata connection keeps one REPEATABLE READ
    snapshot (3.2).
  - **Detection**: `SHOW PROCESSLIST` shows idle sessions from the tool host.
    `Aborted_clients` grows. At worst "Too many connections".
  - **Blast radius**: source connection slots; a long-lived read view delays
    purge.
  - **Recovery**: lower the thread counts. The sessions end when the tool
    exits.
  - **RTO**: immediate on process exit.
  - **Test**: `GAP: fake-DBAPI test asserting DBAPI close after factory connection close`
  - **DEFECT**: there is no `engine.dispose()` and connections are left
    unclosed (D-13.02-17).

- **FM-13.02-18 ClickHouse connection accumulation (packaged)**
  - **Trigger**: any packaged tool issuing several queries on one connection
    (`ch-checksum`, `ch-pg-dump`, `auto_diff`, `ch-ch-checksum`).
  - **Behaviour**: `ch_sink_tools/db/clickhouse.py:22-27` never closes the
    cursor. Each query keeps a native TCP connection. `Connection.close()`
    closes only half of them (repro: 5 open, 2 left).
  - **Detection**: `system.processes` stays clean, but ClickHouse
    `CurrentMetric_TCPConnection` grows for the tool host.
  - **Blast radius**: server connection slots (`max_connections`); file
    descriptors on the tool host.
  - **Recovery**: the connections close at process exit. Split huge runs.
  - **RTO**: immediate on process exit.
  - **Test**: `GAP: mocked Client count after N clickhouse_execute_conn calls and close`
  - **DEFECT**: the cursor is leaked (D-13.02-18).

- **FM-13.02-19 `--secure False` or `--secure True` without a port**
  - **Trigger**: `--secure False` (or `--clickhouse_secure False`) on any
    ClickHouse tool; or TLS requested without a port.
  - **Behaviour**: argparse yields the string `'False'`, which is truthy, so the
    driver wraps the socket in TLS and the loader adds `--secure`. With TLS the
    factory still forces port 9000 (`db/clickhouse.py:9`), the plaintext port.
  - **Detection**: an SSL handshake error or a timeout at the first query.
  - **Blast radius**: the tool cannot connect.
  - **Recovery**: omit `--secure` for plaintext. For TLS, pass
    `--clickhouse_port 9440` explicitly.
  - **RTO**: unmeasured (minutes).
  - **Test**: `GAP: factory with secure strings and default port`
  - **DEFECT**: the flag is not parsed as a boolean and the TLS port default is
    overridden (D-13.02-19).

- **FM-13.02-20 ClickHouse config without top-level `<user>`/`<password>`**
  - **Trigger**: a `clickhouse-client.xml` that keeps credentials under
    `<connections_credentials>`, omits `<password>`, or a flat YAML file.
  - **Behaviour**: `findtext` returns `None` (`db/clickhouse.py:64-65`), or
    YAML raises `KeyError: 'config'`. The legacy factory passes `None` and
    `send_hello` raises `AttributeError: 'NoneType' object has no attribute 'encode'`
    (repro). Packaged replaces a `None` password with `""` but not a `None`
    user. `ch-checksum` logs a WARNING and continues as `default` with an
    empty password.
  - **Detection**: an obscure `AttributeError` at the first query, or an
    authentication failure.
  - **Blast radius**: the tool cannot run.
  - **Recovery**: put `<user>` and `<password>` as direct children of the root
    element.
  - **RTO**: unmeasured (minutes).
  - **Test**: `GAP: resolver fixtures for nested, missing-password and flat-YAML configs`
  - **DEFECT**: missing credentials are not reported; legacy crashes at the
    hello (D-13.02-20).

- **FM-13.02-21 ENUM or SET labels containing `bit`, `blob` or `binary`
  (packaged loader)**
  - **Trigger**: `ch-mysql-load` on a table with, for example,
    `enum('habit','orbit')`.
  - **Behaviour**: `is_binary_datatype` substring-matches
    (`ch_sink_tools/db/mysql.py:14`), and the listener maps the column to
    `String`
    (`ch_sink_tools/db_load/mysql_parser/CreateTableMySQLParserListener.py:51`).
    The legacy copy does not match.
  - **Detection**: a DDL difference between a packaged load and a legacy load
    or the connector's auto-created table.
  - **Blast radius**: the column type of such tables; later DDL or checksum
    expectations (13.04).
  - **Recovery**: use the legacy loader, or a column type override.
  - **RTO**: unmeasured.
  - **Test**: `GAP: is_binary_datatype parity test across both copies`
  - **DEFECT**: substring type classification (D-13.02-21).

- **FM-13.02-22 `ch-sink-tools[mysql]` installed without the `dataframe` extra**
  - **Trigger**: `pip install ch-sink-tools[mysql]`.
  - **Behaviour**: `ch_sink_tools/db/mysql.py:8` imports pandas
    unconditionally, so every MySQL entry point and `ch-mysql-load` (through
    `is_binary_datatype`) fails at import (repro: `ImportError`).
  - **Detection**: `ImportError` at startup.
  - **Blast radius**: all packaged MySQL tools.
  - **Recovery**: install `[all]` or add pandas.
  - **RTO**: unmeasured (minutes).
  - **Test**: `GAP: import test with pandas blocked`
  - **DEFECT**: the import does not match the packaging extras (D-13.02-22).

- **FM-13.02-23 Checksum containers started as documented**
  - **Trigger**: `docker run` of the images built from
    `Dockerfile_mysql_checksum` or `Dockerfile_clickhouse_checksum` with
    `-e MYSQL_HOST=...`.
  - **Behaviour**: the exec-form ENTRYPOINT passes the literal strings
    `$MYSQL_HOST`, `$MYSQL_PASSWORD` and so on (no shell expansion in exec
    form). The ENV defaults also bake placeholder credentials into the image,
    and a real password would travel through ENV and argv.
  - **Detection**: a connection error to host `$MYSQL_HOST`.
  - **Blast radius**: the containers cannot work as written.
  - **Recovery**: override the entrypoint with explicit arguments, or use a
    shell-form wrapper.
  - **RTO**: unmeasured.
  - **Test**: `GAP: container smoke test`
  - **DEFECT**: environment variables are not expanded in the exec-form
    entrypoint (D-13.02-23).

- **FM-13.02-24 Server unreachable or stalled**
  - **Trigger**: DNS failure, a refused connection, or a server that accepts
    the connection and then stalls.
  - **Behaviour**: connect fails after at most 10 s (MySQL), 20 s (ClickHouse)
    or 20 s (PostgreSQL), with `OperationalError`, exit 1, no retry (I-13.02-3).
    A stall after connect blocks indefinitely on MySQL (`read_timeout=None`)
    and PostgreSQL (`statement_timeout=0`), and for up to 300 s per socket read
    on ClickHouse. The checksum-level consequences are in 11.02.
  - **Detection**: a traceback for connect failures. For stalls, only elapsed
    time; the tool logs nothing.
  - **Blast radius**: one run. With source locks held, the writers on that
    table wait (11.02).
  - **Recovery**: kill the tool and re-run when the server is healthy.
  - **RTO**: connect failure detected in at most 20 s (configured timeouts);
    stalls unbounded, unmeasured.
  - **Test**: `GAP: fake socket that accepts and stalls; assert a bounded wait once timeouts are added`

- **FM-13.02-25 Wrong password or missing privilege**
  - **Trigger**: an expired or rotated password, or a user without access.
  - **Behaviour**: MySQL error 1045, ClickHouse code 516, or a psycopg2 "password
    authentication failed", raised from the factory or the first query. The
    message names the user and host, not the password (3.11). Exit 1.
  - **Detection**: an immediate traceback.
  - **Blast radius**: one run; nothing is written.
  - **Recovery**: fix the credential source (3.9) and re-run.
  - **RTO**: unmeasured (minutes).
  - **Test**: `GAP: assert the exception text of a mocked auth failure contains no password, for each factory`

Summary: 25 failure modes, 23 DEFECT, 23 GAP.

## 7. Defect Register

| ID | Severity | Copy (legacy/packaged/both) | Location | Evidence | Summary |
|---|---|---|---|---|---|
| D-13.02-1 | S2 | packaged | `ch_sink_tools/db/mysql.py:21-22` | reproduced (`make_url`: `p@ss` gives host `ss@db1`; `a%41b` gives `aAb`) | The MySQL URL is built without URL-encoding the user and password. The connect fails, and a password fragment appears in the error. |
| D-13.02-2 | S2 | packaged | `ch_sink_tools/db/clickhouse.py:68` | reproduced (DEBUG line `clickhouse_password chS3cret`) | The ClickHouse config resolver logs the password in clear at DEBUG. |
| D-13.02-3 | S2 | packaged | `ch_sink_tools/db_load/clickhouse_loader.py:421`, `:445`, `:452`, `:466` | reproduced (INFO line contains the password) | The loader logs and raises the full `clickhouse-client` command including `--password` (FM-11.05-1). |
| D-13.02-4 | S2 | legacy | `db_load/clickhouse_loader.py:48`, `:483` | reproduced (AssertionError and DEBUG line contain the password) | The redaction misses the DEBUG command line and the failure exception (FM-11.05-2). |
| D-13.02-5 | S2 | both | `db_load/clickhouse_loader.py:417-421`, `:494-498`; `ch_sink_tools/db_load/clickhouse_loader.py:418-421` | code-read (the resolved password is used to build `password_option`; `--config-file` is also passed) | A password read from the ClickHouse config file is moved onto the `sh -c` argv for the whole load. |
| D-13.02-6 | S2 | packaged | `ch_sink_tools/db_dump/mysql_dumper.py:124`, `:54`, `:68` | reproduced (`"pa"ss$(id)"` gives "No closing quotation"; the DEBUG line holds the password) | The `mysqlsh` password is put in double quotes without escaping (shell injection) and logged unredacted. |
| D-13.02-7 | S2 | packaged | `ch_sink_tools/db_dump/postgres_dumper.py:360`, `:389`, `:658`, `:502`, `:669`, `:861` | reproduced (`PGPASSWORD='it's'` breaks quoting; command text holds both passwords) | The PostgreSQL and ClickHouse passwords are embedded in `bash -c` strings (`ps`-visible, injectable) and logged at DEBUG. |
| D-13.02-8 | S2 | packaged | `ch_sink_tools/db/postgres.py:613-630` | reproduced (multi-entry file returns `otheruser/otherpw`; `pa\:ss` returns `pa\`) | The `.pgpass` resolver returns the first entry, with no host/port/db/user matching and no unescaping. |
| D-13.02-9 | S2 | both | `db/mysql.py:133-137`; `ch_sink_tools/db/mysql.py:125-129` | reproduced (InterpolationSyntaxError shows `%cdSECRET`; quotes and comments kept) | The `.cnf` resolver is not MySQL-compatible, and its uncaught `%` error prints part of the password. |
| D-13.02-10 | S2 | both (factory) / packaged (effect) | `db/mysql.py:32`; `ch_sink_tools/db/mysql.py:24`; `ch_sink_tools/db_compare/mysql_table_checksum.py:174`, `:111` | code-read (no `time_zone` in the factory; the packaged checksum renders TIMESTAMP with `cast(... as char)` in the session zone) | The session time zone is never pinned by the factory. The packaged checksum depends on the server zone, while the legacy one pins UTC. |
| D-13.02-11 | S2 | both | `db_compare/top_level_table_checksum.py:265`, `:321`, `:450-452`; `ch_sink_tools/db_compare/top_level_table_checksum.py:159`, `:186`, `:264-266` | code-read (child command templates) | The orchestrator does not forward `--mysql_port`, `--clickhouse_port`, `--secure`, `--clickhouse_config_file` or `--clickhouse_user` to the children, and `--mysql_user` is overwritten. |
| D-13.02-12 | S3 | both | `ch_sink_tools/db/mysql.py:77-81`; callers listed in FM-13.02-12 | reproduced (real `execute_mysql` + fake DBAPI under SQLAlchemy 2.1.1: `TypeError`) | Rows from `execute_mysql` are indexed by name at twelve call sites, which crashes under the allowed SQLAlchemy 2.x. |
| D-13.02-13 | S3 | both | `db/mysql.py:54-55`; `ch_sink_tools/db/mysql.py:46-47` | reproduced (returns `[['orders']]`, no `fetchall`/`mappings`) | `get_tables_from_regex(no_wc=True)` returns a list that no caller can consume. |
| D-13.02-14 | S3 | both | `db/mysql.py:118`; `ch_sink_tools/db/mysql.py:110` | reproduced (`text("... 'a :b'")` binds `b`) | Raw SQL passes through `sqlalchemy.text()`, so `:word` inside literals becomes a bind parameter. |
| D-13.02-15 | S3 | both | `db/mysql.py:41-49`, `:76`, `:143`, `:151`, `:163-166`, `:177`, `:188`, `:221`; `ch_sink_tools/db/mysql.py` same builders; `db/clickhouse.py:33`; `ch_sink_tools/db/clickhouse.py:30`; `ch_sink_tools/db/postgres.py:303-311`, `:360-361`, `:411-412`, `:427-428`, `:583`, `:604` | reproduced (generated SQL shown in 3.4/3.7) | Identifiers and values are interpolated into catalog SQL and DDL without escaping. |
| D-13.02-16 | S3 | both | `db/mysql.py:84`; `ch_sink_tools/db/mysql.py:76` | reproduced (regex semantics: `price$hist` matches nothing, `a.c` matches `abc`) | The partition-key lookup uses the table name as a regex. |
| D-13.02-17 | S3 | both | `db/mysql.py:32-34`; `ch_sink_tools/db/mysql.py:24-26`; unclosed sites in 3.10 | reproduced (fake DBAPI: 0 of 3 closed after close+gc; closed after dispose) | One engine and pool per call, never disposed; `close()` never closes the socket, and eleven call sites (five legacy, six packaged) do not close at all. |
| D-13.02-18 | S3 | packaged | `ch_sink_tools/db/clickhouse.py:22-27` | reproduced (5 queries leave 5 open clients; 2 open after `close()`) | The cursor is never closed: one leaked native connection per query, and the driver's `close()` releases only half. |
| D-13.02-19 | S3 | both | `db/clickhouse.py:9`, `:17`; `ch_sink_tools/db/clickhouse.py:9`, `:17`; `--secure`/`--clickhouse_secure` argparse definitions | reproduced (`secure='False'` stored truthy; `secure=True` gives port 9000) | The TLS flag is a string (so `"False"` enables TLS), and the factory forces port 9000 under TLS. |
| D-13.02-20 | S3 | both | `db/clickhouse.py:63-71`, `:13`; `ch_sink_tools/db/clickhouse.py:60-68` | reproduced (nested XML gives `(None, None)`; legacy hello `AttributeError`) | The ClickHouse config resolver returns `None` silently for nested or missing fields, and the legacy factory then crashes obscurely. |
| D-13.02-21 | S3 | packaged | `ch_sink_tools/db/mysql.py:13-17` | reproduced (`enum('habit','orbit')` gives True) | Substring-based binary-type detection maps some ENUM/SET columns to `String` in the packaged DDL (13.04). |
| D-13.02-22 | S3 | packaged | `ch_sink_tools/db/mysql.py:8`; `sink-connector/python/pyproject.toml` extras | reproduced (ImportError with pandas absent) | The packaged MySQL layer needs pandas, but pandas is only in the `dataframe` extra. |
| D-13.02-23 | S3 | legacy | `sink-connector/python/Dockerfile_mysql_checksum`, `sink-connector/python/Dockerfile_clickhouse_checksum` (ENTRYPOINT) | code-read (exec-form ENTRYPOINT does no variable expansion) | The containers pass literal `$VAR` strings as host, user and password, and route credentials through ENV and argv. |
| D-13.02-24 | S4 | both | `db/mysql.py:116-122`; `db/clickhouse.py:44-51`; packaged equivalents | code-read (PyMySQL issues no server warnings; `catch_warnings` is process-global) | The warning capture never reports server warnings, is not thread-safe, and `rowcount` is always `-1`. |
| D-13.02-25 | S4 | packaged | `ch_sink_tools/db/postgres.py:223`, `:235`, `:437-456`, `:476`, `:251-259`, `:16-20`, `:556` | reproduced (autocommit set True; LSN encodings `565083168` vs `10759458159648`) | Doc and code drift: the autocommit docstring, the LSN docstring arithmetic, the `_version` docstring; the unused `get_current_lsn` uses a different LSN encoding; `pg_execute_df` is dead and ignores `_PANDAS_AVAILABLE`. |
