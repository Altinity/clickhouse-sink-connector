# Spec 13.03: MySQL Snapshot Dump (`mysql_dumper`)

## 1. Executive Summary & Purpose

`mysql_dumper` is the **initial-snapshot** step of the MySQL-to-ClickHouse toolset. It selects the tables
(and optionally partitions) of one MySQL schema with `information_schema` queries over a SQLAlchemy/PyMySQL
connection, writes one MySQL Shell JavaScript statement (`util.dumpTables(...)`) to a temporary file, and runs
`mysqlsh ... -f <tmpfile>` through `/bin/sh`. The dump files are produced entirely by MySQL Shell. They are
consumed later by the ClickHouse loader (`ch-mysql-load --mysqlshell`, Spec 13.04). The packaged copy is
installed as `ch-mysql-dump`.

This spec describes the tool **as built on 2.11.0**, both source copies, plus `ch_sink_tools/db_dump/naming.py`
(the name-template engine used by the PostgreSQL dumper, Spec 13.05). It records every faulty behaviour as a
defect. Do not read it as a design. The main conclusions:

- **Only one dump engine is supported: MySQL Shell `util.dumpTables`.** No code path builds `mydumper`,
  `mysqldump`, `util.dumpSchemas` or `util.dumpInstance` commands. The loader still accepts mydumper layouts
  (Spec 13.04). That support is for dumps produced outside this tool.
- **Consistency is delegated to MySQL Shell.** Both copies leave mysqlsh's `consistent` option at `true` by
  default: `FLUSH TABLES WITH READ LOCK` or `LOCK TABLES`, then per-thread `START TRANSACTION WITH CONSISTENT
  SNAPSHOT`, then `LOCK INSTANCE FOR BACKUP`. Only the legacy copy can switch it off (`--no_consistent`).
- **The tool captures no snapshot position (binlog file/position or GTID set) and hands none to the
  connector.** MySQL Shell writes the position into the dump's `@.json` file when it has the privilege to read
  it. No Python component reads that file, checks that it is present, or logs it. The table and partition list
  is resolved **outside** the snapshot, before mysqlsh starts. Both points are S1 defects (D-13.03-1,
  D-13.03-2).
- **The tool cannot run on SQLAlchemy 2.x**, which the declared dependency `sqlalchemy>=1.4` installs by
  default: rows are indexed by column name and that raises `TypeError` before mysqlsh is called (D-13.03-3,
  reproduced).
- **Password mode is broken and leaks the password.** Both copies pass `--password <pw>` with a space, which
  MySQL Shell does not read as a password. The password is visible on the process command line. The packaged
  copy wraps it in double quotes, so the shell runs any command substitution in it, and it logs the password in
  clear text at DEBUG level.

## 2. Codebase Mapping on 2.11.0

| Concern | Legacy copy | Packaged copy |
|---|---|---|
| Dumper CLI and command builder | `sink-connector/python/db_dump/mysql_dumper.py` (329 lines) | `sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py` (289 lines) |
| Package marker | `sink-connector/python/db_dump/__init__.py` (`__version__ = "0.1"`) | `sink-connector/python/ch_sink_tools/db_dump/__init__.py` (docstring only) |
| MySQL connection, selection SQL, credentials (Spec 13.02) | `sink-connector/python/db/mysql.py` | `sink-connector/python/ch_sink_tools/db/mysql.py` |
| Name-template engine (PostgreSQL dumper only) | none | `sink-connector/python/ch_sink_tools/db_dump/naming.py` |
| Unit tests | `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py` (imports the **legacy** dumper only) | `sink-connector/python/tests/test_naming.py` (packaged `naming` and `postgres_dumper.filter_tables_by_regex`) |
| Entry point | run as a script: `python db_dump/mysql_dumper.py` with `PYTHONPATH=.` (`sink-connector/python/install.sh:4`) | `ch-mysql-dump = ch_sink_tools.db_dump.mysql_dumper:main` (`sink-connector/python/pyproject.toml:48`) |
| Dependency floor that triggers D-13.03-3 | `sink-connector/python/requirements.txt:5` | `sink-connector/python/pyproject.toml:25` |

Line anchors used throughout (L = legacy dumper, P = packaged dumper):

| Function | Legacy lines | Packaged lines |
|---|---|---|
| imports / path set-up | `sink-connector/python/db_dump/mysql_dumper.py:10-27` | `sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py:10-27` |
| `check_program_exists` | L30-33 | P32-35 |
| log-record factory hack | L36-44 | P38-46 |
| `register_secret`, `redact_password` | L48-77 | absent |
| `run_command` | L80-96 | P48-64 |
| `run_quick_command` (dead code) | L99-112 | P67-80 |
| `generate_mysqlsh_dump_tables_clause` | L115-133 | P83-100 |
| `generate_mysqlsh_command` | L136-181 | P103-145 |
| `main` | L184-323 | P148-283 |

Connection-layer functions the dumper calls (full contract in Spec 13.02):

| Function | Legacy | Packaged |
|---|---|---|
| `get_mysql_connection` | `sink-connector/python/db/mysql.py:28-34` | `sink-connector/python/ch_sink_tools/db/mysql.py:20-26` |
| `get_tables_from_regex_sql` | `sink-connector/python/db/mysql.py:37-50` | `sink-connector/python/ch_sink_tools/db/mysql.py:29-42` |
| `get_tables_from_regex` | `sink-connector/python/db/mysql.py:53-62` | `sink-connector/python/ch_sink_tools/db/mysql.py:45-54` |
| `get_partitions_from_regex` | `sink-connector/python/db/mysql.py:65-80` | `sink-connector/python/ch_sink_tools/db/mysql.py:57-72` |
| `execute_mysql` | `sink-connector/python/db/mysql.py:108-124` | `sink-connector/python/ch_sink_tools/db/mysql.py:100-116` |
| `resolve_credentials_from_config` | `sink-connector/python/db/mysql.py:127-139` | `sink-connector/python/ch_sink_tools/db/mysql.py:119-131` |

Related code outside the scope, cited for contrast or handoff:
- `sink-connector/python/ch_sink_tools/db_load/mysql_resync.py:275-287`: `capture_binlog_position()`, the only
  place in the toolset that captures `SHOW MASTER STATUS`. It runs before its own `util.dumpTables`
  (`sink-connector/python/ch_sink_tools/db_load/mysql_resync.py:307-320`). Spec 11.04 and Spec 13.08.
- `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py:350-361` and
  `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py:470-480`: the loader reads only
  `<schema>@<table>.sql` and `<schema>@<table>@*.tsv.zst`. It never opens the dump's metadata JSON (Spec 13.04).
- `sink-connector/python/ch_sink_tools/db_dump/postgres_dumper.py:64`,
  `sink-connector/python/ch_sink_tools/db_dump/postgres_dumper.py:1976-1978` and
  `sink-connector/python/ch_sink_tools/db_dump/postgres_dumper.py:2104-2125`: the only callers of `naming`.
- `sink-connector/python/Dockerfile_db_load` installs the latest `mysql-shell`. No Dockerfile runs the dumper.
- `sink-connector/python/test_db.sh` contains a commented-out `util.dumpSchemas` example. No code builds that
  call.

## 3. Contract (Behaviour as Built)

### 3.1 Role in the lifecycle and what it does not do

1. Input: one MySQL schema (`--mysql_database`) and selection flags.
2. Output: a MySQL Shell dump directory (`--dump_dir`) written by `mysqlsh`. The Python process writes one
   temporary file holding the JS statement and nothing else.
3. It does **not**: create ClickHouse objects, translate DDL, load data, capture or record a binlog
   position or GTID set, write a connector offset, validate the dump after mysqlsh exits, retry, or resume.
4. Single schema per run. Single mysqlsh invocation per run. No Python-level concurrency.

### 3.2 Command-line interface

Both copies use `argparse` with `--snake_case` flags. Argument errors exit 2 (argparse). No configuration
file other than the MySQL option file is read. No environment variable is read by the Python code. `PATH` is
used to find `mysqlsh`, and `HOME` is used by `os.path.expanduser` inside `resolve_credentials_from_config`.

| Flag | Type | Default | Effect | Honoured? |
|---|---|---|---|---|
| `--mysql_host` | str, **required** | none | PyMySQL host. mysqlsh `-h <host>`, unquoted in the shell string. | yes |
| `--mysql_user` | str | `None` | Password mode: user for both PyMySQL and mysqlsh (`--user <u>`, unquoted). Config mode: still passed to mysqlsh if given, while PyMySQL uses the option file's user, so the two can diverge. | yes, see D-13.03-4 |
| `--mysql_password` | str | `None` | Turns on password mode. Logs a WARNING (L248/P209) and asserts `--mysql_user` is set (L249/P210). PyMySQL gets it. mysqlsh gets `--password <pw>` (L159 `shlex.quote`; P124 `"<pw>"`). | **not as a password by mysqlsh** (D-13.03-4), exposed (D-13.03-5/6/7) |
| `--defaults_file` | str | `'~/.my.cnf'` | Config mode (no `--mysql_password`): `resolve_credentials_from_config` reads `[client] user/password` with `configparser` for PyMySQL. **Always** passed to mysqlsh as `--defaults-file=<value>`, unquoted and unexpanded, because the default is never `None` (L164-165/P129-130). | yes, see D-13.03-16 |
| `--mysql_database` | str, **required** | none | Schema for selection SQL (`table_schema = '<db>'`), the PyMySQL default DB, and the first `dumpTables` argument (`'<db>'`, unescaped). | yes |
| `--mysql_port` | str when given, int 3306 by default (no `type=`) | `3306` | PyMySQL port (`int(...)`). mysqlsh `--port <p>`, always emitted. | yes |
| `--dump_dir` | str, **required** | none | Third `dumpTables` argument (`'<dir>'`, unescaped JS literal). | yes |
| `--include_tables_regex` | str | `'.'` | `table_name rlike '<re>'` in the table query and in the partition query's sub-select. Interpolated unescaped. | yes, see D-13.03-8 |
| `--exclude_tables_regex` | str | `None` | `and table_name not rlike '<re>'` in both queries. | yes, see D-13.03-8 |
| `--include_partitions_regex` | str | `None` | Partition query only: `and partition_name rlike '<re>'`. **Not** passed to the table query (L257-261/P218-222). | partially (D-13.03-10) |
| `--where` | str | `None` | Passed to `generate_mysqlsh_command` and on to the clause builder as `where`, then **never used**. | **ignored** (D-13.03-11) |
| `--threads` | int | `1` | mysqlsh `threads` (mysqlsh's own default is 4). | yes |
| `--bytes_per_chunk` | str | `'64M'` | mysqlsh `bytesPerChunk`. Setting it implicitly turns chunking on. Not validated (mysqlsh minimum `128k`). | yes |
| `--debug` | flag | `False` | Root logger and handler at DEBUG: logs the full mysqlsh command (redacted in legacy, **clear text in packaged**, D-13.03-7) and every selection SQL. | yes |
| `--schema_only` | flag | `False` | `ddlOnly: 1`. Suppresses the `partitions` option. | yes |
| `--data_only` | flag | `False` | `dataOnly: 1`. Not mutually exclusive with `--schema_only` (D-13.03-20). A data-only dump has no `<schema>@<table>.sql`, which the loader requires (Spec 13.04). | yes |
| `--non_partitioned_tables_only` | flag | `False` | Adds a `count(*) = 1` partition-count filter to both queries. A table with exactly one partition counts as non-partitioned (D-13.03-14). | yes (imprecise) |
| `--partitioned_tables_only` | flag | `False` | Skips the table query rows and builds the table list from the partition rows. Non-partitioned tables are still included because their single `PARTITION_NAME IS NULL` row is in the result (D-13.03-9). | **not as named** |
| `--dry_run` | flag | `False` | `dryRun: 1`. mysqlsh prints what it would dump and writes no data. The Python side still requires `mysqlsh` in PATH, connects with PyMySQL, runs the selection SQL, writes the temp file and runs mysqlsh, which connects to the server. | yes |
| `--consistent` | flag (legacy only) | `True` | `store_true` with `default=True`: a **no-op** (L219-220). | no-op (D-13.03-17) |
| `--no_consistent` | flag (legacy only, dest `non_consistent`) | `False` | `consistent: 0` (L310, L127). | legacy yes. Packaged rejects it: argparse exit 2 (reproduced) |

### 3.3 Credential resolution (both copies, L241-252 / P202-213)

1. `assert check_program_exists("mysqlsh")` runs first, **outside** the `try` block. It runs
   `/usr/bin/which mysqlsh`. If mysqlsh is missing, an uncaught `AssertionError` gives a traceback and exit 1.
2. Password mode (`--mysql_password` is truthy): WARNING `Using password on the command line is not secure...`,
   then `assert args.mysql_user is not None` (outside the `try`). PyMySQL credentials are `(--mysql_user,
   --mysql_password)`.
3. Config mode: `resolve_credentials_from_config(args.defaults_file)` (outside the `try`). It asserts the
   path exists after `expanduser`, ends with `.cnf`, and has a `[client]` section, then reads `user` and
   `password` with `configparser`. The Python side differs from MySQL option-file syntax: quotes are kept,
   `%` triggers interpolation errors, and bare keys such as `skip-ssl` raise `ParsingError` (reproduced
   offline, see Spec 13.02). Any failure here gives an uncaught exception and exit 1.
4. **The two consumers get credentials from different places.** PyMySQL gets the resolved pair. mysqlsh gets
   `args.mysql_user` / `args.mysql_password` (both `None` in config mode), plus `--defaults-file`. In config
   mode mysqlsh is expected to read the option file itself (MySQL Shell 8.0.32 or later reads `[client]` and
   `[mysqlsh]`). PyMySQL and mysqlsh can therefore authenticate as different users, or with differently
   parsed passwords (a quoted password works for mysqlsh but reaches PyMySQL with its quotes).

### 3.4 Table and partition selection

All SQL goes through `execute_mysql(conn, sql)`, which runs `conn.execute(text(sql))` and returns a
SQLAlchemy `CursorResult`. Warnings are counted and the first one is logged. Every value is interpolated
with f-strings and nothing is escaped or bound.

Table query (`get_tables_from_regex_sql`, called with `include_partitions_regex=None` by the dumper):

```sql
select TABLE_SCHEMA as table_schema, TABLE_NAME as table_name
from information_schema.tables
where table_type='BASE TABLE' and table_schema = '<db>' and table_name rlike '<include_re>'
  [and table_name not rlike '<exclude_re>']
  [and (table_schema, table_name) in (select table_schema, table_name from information_schema.partitions
        where table_schema = '<db>' group by table_schema, table_name having count(*) = 1 )]   -- --non_partitioned_tables_only
order by 1
```

Partition query (`get_partitions_from_regex`):

```sql
select TABLE_SCHEMA as table_schema, TABLE_NAME as table_name, PARTITION_NAME as partition_name,
       PARTITION_EXPRESSION as partition_expression
from information_schema.partitions
where table_schema = '<db>' [and partition_name rlike '<part_re>']
  and (table_schema, table_name) IN (<table query above, without a partition clause>)
order by 1,2,3
```

Then the dumper builds the list (L270-289 / P231-250):
- `tables_to_dump` is every `row['table_name']` from the table query, unless `--partitioned_tables_only`.
- `partition_map` is keyed `"<schema>.<table>"`. The first row for a key sets `[partition_name]`, or `[]` when
  the name is `NULL`. Later rows append. With `--partitioned_tables_only`, every table seen in the partition
  rows is appended to `tables_to_dump`, and that includes non-partitioned tables, whose single row carries
  `NULL`.
- The table list is logged only at DEBUG (each name) and as part of the INFO-level clause (3.5).

Resulting scopes (reproduced with `r2_main_flow.py`, see 5.3):

| Flags | `tables_to_dump` | `partitions` option | What mysqlsh dumps |
|---|---|---|---|
| none | all base tables | every table: `[]` for non-partitioned tables (mysqlsh ignores an empty list), the full list of partitions **known at selection time** for partitioned ones | everything, except partitions created after selection (D-13.03-2) |
| `--include_partitions_regex R` | **all** base tables | only tables with at least one partition matching R | matching partitions, **plus** non-partitioned tables in full, **plus** partitioned tables with no match in full (D-13.03-10) |
| `--partitioned_tables_only` | non-partitioned and partitioned tables | as in the first row | everything (D-13.03-9) |
| `--partitioned_tables_only --include_partitions_regex R` | tables with at least one matching partition | matching partitions | the intended scope |
| `--non_partitioned_tables_only` | tables with exactly one `information_schema.partitions` row | single-partition tables carry `[p]` | non-partitioned and single-partition tables |
| `--schema_only` | as selected | omitted (`partition_map and not schema_only`) | DDL only |

Regex semantics: MySQL `RLIKE` (ICU in 8.0). Case sensitivity follows the collation of
`information_schema.TABLES.TABLE_NAME`, which depends on `lower_case_table_names`. This was not determined
offline. The regex sits inside a single-quoted SQL **string literal**, so MySQL string-escape processing
runs first: `\d` becomes `d`, so a user regex `^t\d+$` reaches the regex engine as `^td+$` (D-13.03-8). A
single quote in a regex or in `--mysql_database` ends the literal (SQL error, or injection). The default
include `.` matches every non-empty name. Views are excluded (`table_type='BASE TABLE'`).

### 3.5 The MySQL Shell statement (`generate_mysqlsh_dump_tables_clause`)

Template, written verbatim (leading and trailing space included) to the temp file:

```
 util.dumpTables('<database>',<python repr of tables_to_dump>, '<dump_dir>', <python repr of dump_options> ); 
```

`dump_options` is a Python dict whose `repr()` happens to be a valid JS object literal:

| Key | Legacy (L127) | Packaged (P94) |
|---|---|---|
| `dryRun` | `int(dry_run)` | same |
| `ddlOnly` | `int(schema_only)` | same |
| `dataOnly` | `int(data_only)` | same |
| `threads` | `threads` | same |
| `bytesPerChunk` | `'<bytes_per_chunk>'` | same |
| `consistent` | `int(consistent)`, 1 unless `--no_consistent` | **absent**: mysqlsh default `true` |
| `partitions` | `partition_map` when non-empty and not `--schema_only` | same |

Example output (reproduced, legacy, default flags):

```
 util.dumpTables('mydb',['t_np', 't_p', 't_q'], '/backups/dump', {'dryRun': 0, 'ddlOnly': 0, 'dataOnly': 0, 'threads': 1, 'bytesPerChunk': '64M', 'consistent': 1, 'partitions': {'mydb.t_np': [], 'mydb.t_p': ['p2025', 'p2026'], 'mydb.t_q': ['p2024']}} );
```

Quoting: table names are rendered by Python `repr()`, which yields valid JS string literals for names with
quotes, backslashes or non-printables. `database` and `dump_dir` go into raw `'...'` with no escaping, so a
`'` breaks the statement or injects JS (reproduced: a `dump_dir` of `/d', {}); println('INJECTED'); //`
produces a second statement, D-13.03-15). Keys in `partitions` are `schema.table`, unquoted. MySQL Shell
expects backtick quoting there when a name needs it.

Options the tool never sets, so MySQL Shell defaults apply (vendor documentation, not verifiable offline):
`compression: "zstd"`, `chunking: true`, `tzUtc: true`, `triggers: true`, `defaultCharacterSet: "utf8mb4"`,
`showProgress` (on only for a TTY), `where`, `ocimds`, `skipConsistencyChecks: false`. The `partitions` (and
`where`) options exist only in MySQL Shell 8.0.32 and later, and so does `--defaults-file` support. Since
`--defaults-file` is always emitted, **MySQL Shell 8.0.32 or later is effectively required**.

The clause is logged at INFO twice: the options dict (L130/P97), then the full statement (L132/P99). It holds
no credentials.

### 3.6 The shell command (`generate_mysqlsh_command`)

Template (L180 / P144). Each clause is empty when its value is `None`:

```
mysqlsh {defaults_file_clause} -h {mysql_host} {mysql_user_clause} {mysql_password_clause} {mysql_port_clause} -f {temp_file.name} 
  defaults_file_clause = " --defaults-file=<defaults_file>"          (always present: default '~/.my.cnf')
  mysql_user_clause    = " --user <mysql_user>"
  mysql_password_clause= " --password <shlex.quote(pw)> "            (legacy, L159)
                       = ' --password "<pw>" '                        (packaged, P124)
  mysql_port_clause    = " --port <mysql_port>"
```

Executed with `subprocess.Popen(cmd, shell=True, stdout=PIPE, stderr=STDOUT)`, that is, `/bin/sh -c`.
Observed tokenisation, with `mysqlsh` replaced by an argv printer (`r1_shell_argv.py`):

| Input password | Legacy argv after `--password` | Packaged argv after `--password` |
|---|---|---|
| `s3cret` | `'--password', 's3cret'` | `'--password', 's3cret'` |
| `pw$(echo INJECTED)` | `'pw$(echo INJECTED)'` (literal) | `'pwINJECTED'`: **command substitution ran** |
| `pa ss$(echo INJECTED)"x` | one literal token | shell syntax error (`unexpected EOF while looking for matching '"'`) |

Consequences:
- MySQL Shell documents that `--password` takes its value only as `--password=value` or `-pvalue`. With a
  space, "the value is not interpreted as a password and might be interpreted as another connection
  parameter", and a bare `--password` requests a prompt. **In both copies the password never reaches mysqlsh
  as a password** (D-13.03-4).
- The password appears in the argv of `/bin/sh -c` and of `mysqlsh`, visible to any local user through `ps`
  or `/proc/<pid>/cmdline` (D-13.03-5). MySQL Shell's `--passwords-from-stdin` (already used by
  `mysql_resync.py`) avoids this.
- Host, user, port, defaults file and temp path are interpolated **unquoted** in both copies. A value with
  whitespace or shell metacharacters splits or injects (D-13.03-15).
- `--defaults-file=~/.my.cnf`: the shell does not expand `~` after `=` in a non-assignment word. Reproduced:
  `printf '%s\n' --defaults-file=~/.my.cnf` prints the literal `~`. Whether mysqlsh expands it is
  version-dependent, and the MySQL manual advises against `~` here (D-13.03-16). The Python side expands it
  (`os.path.expanduser`), so PyMySQL and mysqlsh may read different files.
- The temp file comes from `tempfile.NamedTemporaryFile()` with no suffix. It is reopened by name with
  `open(tmp.name, 'w')` (mode 0600 in the system temp dir). It has no `.js` extension, so mysqlsh runs it in
  its default language. A `[mysqlsh]` group selecting `sql` or `py` mode breaks the statement (D-13.03-22).
  It is removed when `tmp` is finalised at interpreter exit, but not after `os._exit(1)` on Ctrl-C.

### 3.7 Running mysqlsh, exit codes and failure detection

`run_command(cmd)` (L80-96 / P48-64):
1. DEBUG log `cmd <command>`: legacy through `redact_password`, packaged **unredacted** (D-13.03-7).
2. Reads the merged stdout+stderr line by line. Each line goes out as `logging.info(line.decode().strip())`
   (strict UTF-8; a non-UTF-8 byte raises and exits 1 while mysqlsh keeps running), followed by
   `time.sleep(0.02)`, which caps throughput at about 50 lines/s and back-pressures mysqlsh through the pipe.
3. At EOF, `rc = str(process.poll())`. `poll()` does not wait. If the child closed its output but has not yet
   exited, `rc` is `'None'` (reproduced, D-13.03-12). A grandchild that inherits the pipe keeps the loop
   blocked until it exits (reproduced: 2.02 s).
4. Returns the string. A signal death gives a negative number as a string.

`main` then runs `assert rc == "0", "mysqldumper failed, check the log."` (L313/P273), inside the `try`.
mysqlsh output is **not parsed**: the tool never inspects warnings such as a failed consistency check, a
missing `REPLICATION CLIENT` privilege (binlog info then silently left out of the metadata), or skipped tables.

Exit codes of the process:

| Code | Cause |
|---|---|
| 0 | `rc == "0"`. Also **any** mysqlsh failure, or mysqlsh missing, when Python runs with `-O` / `PYTHONOPTIMIZE`, because every check is an `assert` (reproduced, D-13.03-13). |
| 1 | Exception inside the `try`: connection failure, selection SQL error, SQLAlchemy `TypeError` (D-13.03-3), `AssertionError` from `rc != "0"` (including `'None'`). Logged as `Exception in main thread : ...` plus traceback, then `sys.exit(1)`. |
| 1 | Uncaught exception outside the `try`: mysqlsh not on PATH, password without user, option-file problems. Python prints a traceback. |
| 1 | `KeyboardInterrupt`/`SystemExit` raised inside the `try`: `os._exit(1)`, with no cleanup and the temp file left behind. |
| 2 | argparse error (unknown flag, `--threads abc`, packaged `--no_consistent`). |

`run_quick_command` (L99-112 / P67-80) uses `communicate()` and logs `command failed : terminating` on a
non-zero rc. **Nothing calls it** (dead code).

### 3.8 Consistency guarantees and the snapshot position (offset seeding)

What the code guarantees:
- One `util.dumpTables` call over the selected tables. With `consistent` true (the default in both copies),
  MySQL Shell takes `FLUSH TABLES WITH READ LOCK` (with `RELOAD`) or `LOCK TABLES` (without it), starts a
  `REPEATABLE READ` / `WITH CONSISTENT SNAPSHOT` transaction on every worker thread, then holds `LOCK INSTANCE
  FOR BACKUP` and releases the global lock (vendor documentation). Consistency covers InnoDB tables only.
  From MySQL Shell 8.0.29, a table dump without `BACKUP_ADMIN` runs an extra consistency check. If it fails,
  the dump **continues** and reports an error message. The tool does not parse that message (3.7), and
  whether mysqlsh's exit code reflects it could not be determined offline.
- Legacy `--no_consistent` sends `consistent: 0`: each thread reads at its own time and nothing warns the
  operator.

What the code does **not** do (proved by absence; `grep -n -i "gtid\|binlog\|SHOW MASTER\|@.json"` over both
dumpers, both connection layers and both loaders finds no hit):
1. It does not run `SHOW MASTER STATUS` / `SHOW BINARY LOG STATUS` / `SELECT @@gtid_executed`.
2. It does not read MySQL Shell's dump metadata JSON. MySQL Shell writes `binlogFile`, `binlogPosition` and
   `gtidExecuted` there when the account has `REPLICATION CLIENT`. Without that privilege the dump continues
   without them.
3. It does not log, print or persist any position. It writes no offset into the connector's offset store
   (Spec 09.03) and prints no instruction for doing so.
4. It does not check that the position exists, that the dump was consistent, or that the dump finished
   (MySQL Shell's completion marker file).
5. The ClickHouse loader does not read the metadata either (Spec 13.04).

The handoff from snapshot to stream is therefore **manual and undocumented**. An operator must find the
metadata file in the dump directory, extract the binlog coordinates or GTID set, and seed the connector's
offset with them before it starts. Anything else (connector default `snapshot.mode`, a schema-only snapshot,
or starting from "now") silently loses every change committed between the snapshot point and the connector
start. The only correct reference implementation in the toolset is `ch-mysql-resync dump`, which captures
`SHOW MASTER STATUS` **before** its dump and persists it (`mysql_resync.py:307-320`; Spec 11.04 and Spec
13.08). It deliberately accepts at-least-once replay over a gap. This is D-13.03-1 (S1).

In addition, the table and partition list is computed over a **separate PyMySQL connection before mysqlsh
starts** (L255-289 / P216-250). The snapshot is taken later, inside mysqlsh. Any table created, or partition
added or reorganised under a new name, in that window is absent from the explicit list. A table is then not
dumped. A partition is excluded because the `partitions` option "limits the export to the specified
partitions". Both happen silently, and their pre-snapshot rows never reach ClickHouse. A dropped table or
partition fails loudly (MySQL Shell rejects missing partitions with `Invalid partitions`). This is
D-13.03-2 (S1).

### 3.9 Output directory layout and file naming

The Python code names no dump file. MySQL Shell owns the layout (vendor behaviour, relied on by Spec 13.04):
- `--dump_dir` must not exist or must be empty. mysqlsh creates it (`rwxr-x---`, files `rw-r-----`). Re-running
  into a used directory fails loudly. There is no resume.
- Top-level metadata: `@.json` (dump metadata, including the binlog position), `@.sql`, `@.post.sql`, and
  `@.done.json` (written last). Per schema: `<schema>.json`, `<schema>.sql`. Per table: `<schema>@<table>.json`,
  `<schema>@<table>.sql` (DDL; absent with `--data_only`), data chunks `<schema>@<table>@<n>.tsv.zst` with the
  final chunk `<schema>@<table>@@<n>.tsv.zst`, plus `.idx` files. A partitioned table becomes
  `<schema>@<table>@<partition>@<n>.tsv.zst`. Identifiers with special characters are percent-encoded by
  MySQL Shell.
- With `--dry_run` no dump files are written.

The `naming` module plays **no part** in MySQL dump file naming (3.10).

### 3.10 `naming.py`: name-template engine (packaged only)

Used only by the PostgreSQL dumper (Spec 13.05) to map `database.schema.table` to a ClickHouse `database.table`.
Pure functions with no I/O.

- `_TEMPLATE_VAR_RE = r'\{\{\s*(\w+)\s*\}\}'` (`naming.py:31`). Placeholders take a single `\w+` name with
  optional whitespace, including newlines. `\w` is Unicode-aware.
- `VALID_VARS = {'database', 'schema', 'table'}` (`naming.py:34`). Case-sensitive.
- `render_template(template, context)` (`naming.py:42-72`): a single-pass `re.sub`. A known variable is
  replaced by `context[var]`. An unknown variable stays literal. A substituted value is **not** re-scanned
  (a table called `{{ schema }}` renders literally; reproduced). A non-`str` value raises `TypeError`.
- `validate_template(template, name, required_vars=frozenset())` (`naming.py:75-121`): raises `ValueError` if
  a matched placeholder name is not in `VALID_VARS`, or if `required_vars` is non-empty and none of them
  appears. The PostgreSQL dumper validates `--ch_database_template` with no required variables and
  `--ch_table_template` with `{'table'}` (`postgres_dumper.py:1976-1978`).
- `resolve_ch_names(pg_database, pg_schema, pg_table, db_template, table_template)` (`naming.py:124-160`):
  renders both templates against one context and returns `(ch_database, ch_table)`.

Character mapping, collisions, length limits (reproduced with `r7_naming.py`):

| Property | Behaviour |
|---|---|
| Character mapping | **None.** `-`, `.`, space, backtick and upper case pass through unchanged (`('my-db', 'Sch.ema.tab le`x')`). Callers backtick-quote the names (for example `postgres_dumper.py:567`). A backtick inside a name is not escaped there. |
| Collision detection | **None.** `{{ schema }}_{{ table }}` maps `a_b.c` and `a.b_c` to the same `a_b_c`. The **default** table template `{{ table }}` maps `public.users` and `sales.users` to the same `users` whenever several schemas are selected (`--pg_schema` takes several values). The caller appends work items without checking (`postgres_dumper.py:2104-2125`). D-13.03-23. |
| Length limit | **None.** `{{ schema }}___{{ table }}` over two 63-byte PostgreSQL names yields 129 characters. |
| Malformed placeholders | `{{ data base }}`, `{{ database.x }}` and `{{{ table }}}` pass validation. The first two render literally, and the third renders `{u}`. `{{ Database }}` and `{{ tablé }}` are rejected as unknown. |
| Empty template | An empty `--ch_database_template` is valid and renders `''`. An empty table template is rejected (`table` required). |

### 3.11 Logging

- At import time both copies replace the global `logging` record factory so that every record gets
  `user="me"` (L36-44/P38-46). That is a process-wide side effect on importers, tests included.
- `main` adds a stdout `StreamHandler` (`%(asctime)s - %(levelname)s - %(threadName)s - %(message)s`) to the
  root logger at INFO, or DEBUG with `--debug`.
- INFO: the options dict, the `util.dumpTables` statement, and every mysqlsh output line. WARNING: password on
  the command line, SQL warnings from `execute_mysql`. ERROR: `Exception in main thread : <e>` plus traceback.
  DEBUG: SQL text, table names, `partition_map`, the command (legacy redacted, packaged clear text), and the
  return code.
- Legacy redaction (`redact_password`, L63-77) masks every registered secret (longest first), then any
  `--password[= ]<shell word>`. `-p<pw>` is masked only if registered. The password is registered at L158
  before the command is built, so the legacy log is clean (reproduced: `cmd true --password '****' --user app`).

### 3.12 Ordering, concurrency, resources, time zones, types

- Order: parse args, set up logging, assert mysqlsh, resolve credentials, open the PyMySQL connection
  (session `wait_timeout=28000`, `charset=utf8mb4`), run the table query, run the partition query, build the
  lists, write the temp file, run mysqlsh, then exit. The PyMySQL connection is never closed explicitly. It
  stays open, idle, for the whole dump.
- Concurrency: none in Python. mysqlsh opens `--threads` worker connections plus a coordinator.
- Time zones: the tool does nothing with them. MySQL Shell's default `tzUtc: true` dumps `TIMESTAMP` values in
  UTC and records `SET TIME_ZONE='+00:00'` in the DDL files, which the loader reads (FM-11.05-4).
- Types: the tool does no type handling. Values are whatever MySQL Shell writes to TSV.
- Retries: none.

### 3.13 Legacy-vs-packaged differences

| Function | Legacy | Packaged | Behavioural effect |
|---|---|---|---|
| imports | `from db.mysql import *` at L16, **before** the `sys.path.append` at L26-27 | explicit `from ch_sink_tools.db.mysql import ...` | Legacy fails with `ModuleNotFoundError: No module named 'db'` unless `PYTHONPATH` contains `sink-connector/python` (reproduced, D-13.03-18). The star import also pulls in `pandas`. |
| `register_secret` / `redact_password` | present | **absent** | Packaged logs the password (D-13.03-7). |
| `run_command` | redacted DEBUG log | clear-text DEBUG log | as above |
| `run_quick_command` | redacted, unused | clear text, unused | none (dead) |
| `generate_mysqlsh_dump_tables_clause` | `consistent` parameter, emits `'consistent': 0/1` | no `consistent` key | Packaged always uses mysqlsh's default (`true`). |
| `generate_mysqlsh_command` | `--password <shlex.quote(pw)>`, `consistent=True` keyword | `--password "<pw>"` | Packaged runs `$()`, backticks and `$VAR` in the password and breaks on `"` (D-13.03-6). Neither form is read as a password by mysqlsh (D-13.03-4). |
| `main` argparse | adds `--consistent` (no-op) and `--no_consistent` | neither | Packaged cannot make an inconsistent dump. `--no_consistent` exits 2. |
| `get_mysql_connection` (13.02) | `quote_plus` on user and password | raw interpolation | Packaged: a password with `@` gives the wrong host (`'p@ss'` parses as password `p`, host `ss@db1`), and `%41` is decoded to `A` (reproduced, D-13.03-19). |
| `is_binary_datatype`, `get_table_partition_key` (13.02) | exact keyword match, `.mappings()` | substring match, `.fetchall()` | Not called by the dumper. |
| Tests | `db_dump/tests/test_mysql_dumper_unit.py` | **none** for the dumper | The installed entry point is untested. |

Every other line is behaviourally identical, including all selection logic and every defect not marked as
packaged-only.

## 4. Invariants Preserved

What the tool **does** preserve, as built:
1. **Dump engine:** the only engine is MySQL Shell `util.dumpTables`. Its file layout is exactly the one the
   loader's `--mysqlshell` path parses (Spec 13.04). Compression is always zstd because the tool exposes no
   compression option.
2. **Default consistency:** with default flags, both copies ask MySQL Shell for a consistent dump. The
   packaged copy cannot ask for anything else.
3. **Selection is read-only:** the tool issues only `SELECT`s on `information_schema` and never writes to
   MySQL. mysqlsh takes only the locks described in 3.8.
4. **Non-destructive output:** MySQL Shell refuses a non-empty `--dump_dir`, so an existing dump is never
   overwritten.
5. **A non-zero mysqlsh exit becomes a non-zero tool exit**, but only when assertions are enabled (not under
   `-O`).
6. **The legacy copy never logs a password** given through `--mysql_password` (registered before use).

Invariants a snapshot step **should** hold but does not (each is a defect in section 7):
- (missing) The snapshot position is captured inside, or before, the snapshot and handed to the stream
  (D-13.03-1).
- (missing) The object list is resolved inside the snapshot (D-13.03-2).
- (missing) Credentials never appear on a command line or in a log (D-13.03-5, D-13.03-7).

Constitution relationship: MySQL is the source of truth (Spec 11.04, `AGENTS.md`). A snapshot that silently
lacks a window of changes or a newly created partition breaks that principle with no detection until a checksum
(Spec 11.02 / Spec 13.06) is run.

## 5. Verification Criteria

### 5.1 Existing tests (offline) and results

Run on 2026-10-01 from `sink-connector/python` with the toolset venv (Python 3.12.11, SQLAlchemy 2.1.1,
PyMySQL 2.2.8):
`python -m pytest -q -p no:cacheprovider db_dump/tests tests` gives **48 passed in 0.73 s**. The full offline
baseline (`db_compare/tests db_load/tests db_dump/tests tests`) gives **227 passed, 5 skipped in 5.91 s**,
unchanged from the brief.

Dumper tests (all target the **legacy** copy only):
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestRedactPassword::test_registered_secret_is_masked`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestRedactPassword::test_password_flag_value_masked_even_if_unregistered`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestRedactPassword::test_secret_with_single_quote_is_fully_masked`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestRedactPassword::test_non_secret_text_preserved`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestDumpTablesClause::test_targets_database_table_and_dir`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestDumpTablesClause::test_data_only_and_schema_only_flags`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestDumpTablesClause::test_schema_only_sets_ddlonly`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestDumpTablesClause::test_partitions_included_for_data_dump`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestDumpTablesClause::test_partitions_omitted_for_schema_only`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestDumpTablesClause::test_threads_and_chunk_carried`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestCheckProgramExists::test_known_program`
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestCheckProgramExists::test_missing_program`

Naming tests (packaged `naming.py`; 29 of the 36 tests in the file, the other 7 are
`TestFilterTablesByRegex` for Spec 13.05):
- `sink-connector/python/tests/test_naming.py::TestRenderTemplate` (13 methods, for example
  `sink-connector/python/tests/test_naming.py::TestRenderTemplate::test_unknown_variable_left_intact` and
  `sink-connector/python/tests/test_naming.py::TestRenderTemplate::test_whitespace_in_braces`)
- `sink-connector/python/tests/test_naming.py::TestValidateTemplate` (10 methods, for example
  `sink-connector/python/tests/test_naming.py::TestValidateTemplate::test_unknown_variable_raises` and
  `sink-connector/python/tests/test_naming.py::TestValidateTemplate::test_missing_required_variable_raises`)
- `sink-connector/python/tests/test_naming.py::TestResolveCHNames` (6 methods)

What the existing tests do **not** reach: `main()`, `generate_mysqlsh_command` (shell string and password
form), `run_command` (rc capture), the packaged dumper at all, the selection SQL, the `partition_map` scopes,
SQLAlchemy row access, and naming collisions or malformed placeholders. These tests do not run in CI
(FM-11.05-3).

### 5.2 Acceptance criteria for this spec (each one is a test to add; `GAP` until added)

1. `main()` with mocked connection rows. One case per row of the scope table in 3.4. Assert `tables_to_dump`
   and `partitions` for both copies.
2. The `main()` row loop is fed real SQLAlchemy `Row` objects (`sqlalchemy.engine.result.result_tuple`) under
   the installed SQLAlchemy major version.
3. The shell string from `generate_mysqlsh_command` is tokenised by `shlex.split` (or by `/bin/sh` with a
   printer) and asserted to contain no password token. The password must be supplied by stdin or by an
   option file.
4. `run_command` against `sh -c 'echo x; exec 1>&- 2>&-; sleep 1; exit 0'` returns `"0"`.
5. A failing command under `python -O` gives a non-zero exit.
6. A snapshot-position test: after a (mocked) dump, the tool emits or persists the binlog file/position/GTID
   set, or refuses to finish without it.
7. Naming: a collision between two work items is detected and refused.

### 5.3 Offline reproduction scripts used by this spec

All under the throwaway repro directory (not part of the repo), run with the toolset venv Python:
`r1_shell_argv.py` (shell tokenisation, tilde), `r2_main_flow.py` (scopes, `--where`, `--dry_run`,
`--no_consistent`, credential routing, exit codes), `r3_run_command.py` (rc race, grandchild block, DEBUG
leak), `r4_sqlalchemy_row.py` (Row `TypeError`), `r5_selection_sql.py` (selection SQL text, backslash
semantics), `r6_optimize_assert.py` (`-O`), `r7_naming.py` (naming edge cases), `r8_js_clause.py` (JS
statement quoting). Key outputs are quoted with each defect.

## 6. Failure Modes & Recovery

- **FM-13.03-1 The connector starts after the snapshot without the snapshot position**
  - **Trigger**: any initial snapshot taken with `mysql_dumper`, loaded with the loader, followed by a connector
    start whose offset was not seeded by hand from the dump metadata (or whose metadata lacks the position
    because the account has no `REPLICATION CLIENT`, or because the dump used legacy `--no_consistent`).
  - **Behaviour**: the tool captures, logs and persists no position (3.8; absence across
    `sink-connector/python/db_dump/mysql_dumper.py:184-323` and
    `sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py:148-283`), and the loader ignores the
    metadata (`clickhouse_loader.py:470-480`). Changes committed between the snapshot point and the
    connector's start position are never applied.
  - **Detection**: none at dump or load time. Only a later checksum or count comparison (Spec 11.02 / Spec
    13.06) shows missing or stale rows.
  - **Blast radius**: every table in the snapshot. Rows inserted, updated or deleted in the gap are silently
    wrong in ClickHouse.
  - **Recovery**: read `binlogFile`/`binlogPosition`/`gtidExecuted` from the dump metadata, set the connector
    offset to that point (Spec 09.03), and replay (idempotent under ReplacingMergeTree). If the binlog has
    been purged, resynchronise with `ch-mysql-resync` (Spec 11.04), which captures its own position.
  - **RTO**: unmeasured (no database offline). Bounded by the replay length, or by a full resync.
  - **Test**: `GAP: dumper emits or persists the snapshot position and fails when it is absent`
  - **DEFECT**: no snapshot-to-stream position handoff (D-13.03-1).

- **FM-13.03-2 A table or partition appears between selection and snapshot**
  - **Trigger**: `CREATE TABLE`, `ALTER TABLE ... ADD PARTITION` or `REORGANIZE PARTITION` into new names
    (for example a scheduled partition-maintenance job) between the PyMySQL selection queries and mysqlsh's
    lock.
  - **Behaviour**: the explicit table list and the per-table `partitions` list are fixed before the snapshot
    (`sink-connector/python/db_dump/mysql_dumper.py:255-289`,
    `sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py:216-250`). mysqlsh dumps only the listed
    partitions. The new object's pre-snapshot rows are left out and nothing reports it. Disappeared objects
    fail loudly (`Invalid partitions`).
  - **Detection**: none. A checksum or count comparison per partition shows the shortfall.
  - **Blast radius**: the affected tables or partitions. Silent missing rows.
  - **Recovery**: re-dump the affected tables or partitions (`--include_tables_regex`,
    `--partitioned_tables_only --include_partitions_regex`) and load them. Or resync with `ch-mysql-resync`.
  - **RTO**: unmeasured (no database offline). Re-dump plus load time of the affected objects.
  - **Test**: `GAP: partition map not passed when no partition filter is requested`
  - **DEFECT**: object list resolved outside the snapshot, explicit partition list passed by default
    (D-13.03-2).

- **FM-13.03-3 The tool crashes before dumping under SQLAlchemy 2.x**
  - **Trigger**: an installation that resolves `sqlalchemy>=1.4` to 2.x (the default today), with at least one
    selected table.
  - **Behaviour**: `row['table_name']` on a SQLAlchemy 2.x `Row` raises `TypeError: tuple indices must be
    integers or slices, not str` (`sink-connector/python/db_dump/mysql_dumper.py:274`,
    `sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py:235`, and with `--partitioned_tables_only`
    L278/P239). It is caught and the tool exits 1 without starting mysqlsh. Reproduced (`r4_sqlalchemy_row.py`):
    `legacy [] exit 1 | mysqlsh invoked: False | Exception in main thread : tuple indices must be integers or
    slices, not str`, and the same for the packaged copy and for `--partitioned_tables_only`.
  - **Detection**: exit 1 and the ERROR line.
  - **Blast radius**: the tool is unusable. No data is affected.
  - **Recovery**: pin `sqlalchemy<2` in the tool's environment, or use `row._mapping[...]` / `.mappings()`
    (as `db_compare/mysql_table_checksum.py:460` already does).
  - **RTO**: minutes (reinstall). Unmeasured.
  - **Test**: `GAP: main() row loop fed real SQLAlchemy Row objects`
  - **DEFECT**: name-indexed row access incompatible with SQLAlchemy 2.x (D-13.03-3).

- **FM-13.03-4 Password mode: mysqlsh never receives the password, and the password is exposed**
  - **Trigger**: `--mysql_user u --mysql_password pw`.
  - **Behaviour**: the command carries `--password <pw>` with a space (L159/P124). MySQL Shell treats a bare
    `--password` as a request to prompt, and the next token as something other than a password. The
    PyMySQL selection succeeds, then mysqlsh prompts (it hangs on a TTY and fails without one) or misreads the
    stray token. The password is on the argv of `sh -c` and `mysqlsh` (reproduced:
    `'--password', 's3cret'`).
  - **Detection**: mysqlsh error or hang. No log message names the cause.
  - **Blast radius**: password-mode dumps fail. The password is exposed to local users through `ps`.
  - **Recovery**: use config mode with an option file that mysqlsh 8.0.32 or later accepts, and rotate any
    password that was passed on a command line.
  - **RTO**: unmeasured (no mysqlsh offline).
  - **Test**: `GAP: generated command contains no password token; password supplied by stdin`
  - **DEFECT**: wrong `--password` syntax (D-13.03-4) and password on the argv (D-13.03-5).

- **FM-13.03-5 Packaged copy: shell metacharacters in the password run or break**
  - **Trigger**: `ch-mysql-dump --mysql_password` containing `$(`, a backtick, `$VAR`, `"` or `\`.
  - **Behaviour**: `--password "<pw>"` (`sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py:124`) is
    expanded by `/bin/sh`. Reproduced: `pw$(echo INJECTED)` becomes the argv token `pwINJECTED`, and a `"`
    gives `unexpected EOF while looking for matching '"'`.
  - **Detection**: a shell syntax error or authentication failure. Injected commands are not detected.
  - **Blast radius**: arbitrary command execution as the tool's user, for whoever controls the password
    value (for example a parameterised job).
  - **Recovery**: avoid password mode. Fix with stdin password passing.
  - **RTO**: unmeasured.
  - **Test**: `GAP: packaged generate_mysqlsh_command tokenisation with metacharacter passwords`
  - **DEFECT**: unescaped double-quoted password in a shell string (D-13.03-6).

- **FM-13.03-6 Packaged copy: the password is written to the log**
  - **Trigger**: `ch-mysql-dump --debug --mysql_password ...`.
  - **Behaviour**: `logging.debug("cmd " + cmd)` (`sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py:54`,
    and `:68` in the unused helper). Reproduced: `cmd true --password 'S3cretPW' --user app`. The legacy
    copy logs `'****'`.
  - **Detection**: inspection of the job log.
  - **Blast radius**: everyone with read access to the logs.
  - **Recovery**: rotate the password and purge the logs.
  - **RTO**: unmeasured.
  - **Test**: `GAP: packaged run_command DEBUG log contains no secret` (the legacy equivalent is
    `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestRedactPassword::test_password_flag_value_masked_even_if_unregistered`)
  - **DEFECT**: packaged copy lacks the redaction the legacy copy has (D-13.03-7).

- **FM-13.03-7 A selection regex is altered by SQL string escaping, or breaks the SQL**
  - **Trigger**: `--include_tables_regex`, `--exclude_tables_regex` or `--include_partitions_regex` containing
    `\` (for example `\d`, `\.`), or `'`.
  - **Behaviour**: the regex is put into `rlike '<re>'` unescaped (`sink-connector/python/db/mysql.py:41,47,49,71`;
    `sink-connector/python/ch_sink_tools/db/mysql.py:33,39,41,63`). MySQL removes the backslash before
    matching, so `^t\d+$` matches `tdd` and not `t1` (reproduced as SQL text plus the regex equivalence in
    `r5_selection_sql.py`). An exclude regex can likewise fail to exclude. A `'` produces a SQL error.
  - **Detection**: the INFO-logged `util.dumpTables` statement shows the actual table list. Nothing compares
    it with intent.
  - **Blast radius**: tables silently left out of, or wrongly added to, the snapshot.
  - **Recovery**: double every backslash in the regex (`\\d`), check the logged table list, and re-dump what
    is missing.
  - **RTO**: unmeasured.
  - **Test**: `GAP: selection SQL escapes regex literals / uses bound parameters`
  - **DEFECT**: unescaped string interpolation in the selection SQL (D-13.03-8).

- **FM-13.03-8 Partition-scope flags dump more than requested**
  - **Trigger**: `--partitioned_tables_only` without a partition regex, `--include_partitions_regex R`
    without `--partitioned_tables_only`, or `--non_partitioned_tables_only` over a single-partition table.
  - **Behaviour**: reproduced (`r2_main_flow.py`): `--partitioned_tables_only` lists `['t_np', 't_p', 't_q']`
    with `'mydb.t_np': []`. `--include_partitions_regex p2026` lists all three tables but only
    `{'mydb.t_p': ['p2026']}`, so `t_np` and `t_q` (with no p2026) are dumped whole
    (`sink-connector/python/db_dump/mysql_dumper.py:257-288`). `count(*) = 1` classifies single-partition
    tables as non-partitioned (`sink-connector/python/db/mysql.py:44`).
  - **Detection**: the INFO-logged statement. Unexpected dump size.
  - **Blast radius**: larger dumps and longer loads. A partition-scoped reload re-inserts whole tables
    (duplicates until ReplacingMergeTree merges, or permanent duplicates on other engines).
  - **Recovery**: combine `--partitioned_tables_only` with `--include_partitions_regex`, or narrow
    `--include_tables_regex`.
  - **RTO**: unmeasured.
  - **Test**: `GAP: main() scope matrix (5.2 item 1)`
  - **DEFECT**: flags do not select what they name (D-13.03-9, D-13.03-10, D-13.03-14).

- **FM-13.03-9 `--where` is silently ignored**
  - **Trigger**: `--where "<condition>"`.
  - **Behaviour**: the value is threaded through `generate_mysqlsh_command` into the clause builder's `where`
    parameter and never used (L121/L131, P89/P98). Reproduced: the statement for `--where 'id>5'` is identical
    to the one without it.
  - **Detection**: none, apart from dump size.
  - **Blast radius**: full tables are dumped instead of a subset. If loaded, all rows are re-inserted.
  - **Recovery**: none in the tool. Use mysqlsh directly with `where: {"schema.table": "..."}`.
  - **RTO**: unmeasured.
  - **Test**: `GAP: --where reaches the dump options or is rejected`
  - **DEFECT**: accepted-but-ignored flag (D-13.03-11).

- **FM-13.03-10 mysqlsh succeeds but the tool reports failure, or blocks**
  - **Trigger**: mysqlsh closes its output before it has fully exited, or leaves a descendant holding the pipe.
  - **Behaviour**: `rc = str(process.poll())` without `wait()` (`sink-connector/python/db_dump/mysql_dumper.py:94`,
    `sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py:62`). Reproduced: `close-then-exit0 ->
    'None' after 0.02s`, and the tool exits 1. A background grandchild blocks the loop for its lifetime (2.02 s
    in the repro).
  - **Detection**: `mysqldumper failed, check the log.` even though mysqlsh printed success.
  - **Blast radius**: a complete dump is reported as failed. Automation may discard or redo it.
  - **Recovery**: check for MySQL Shell's completion marker in `--dump_dir`. If it is present, the dump is
    complete.
  - **RTO**: unmeasured.
  - **Test**: `GAP: run_command returns the real exit status (5.2 item 4)`
  - **DEFECT**: exit status read with `poll()` instead of `wait()` (D-13.03-12).

- **FM-13.03-11 Failures exit 0 under `python -O`**
  - **Trigger**: the interpreter runs with `-O` or `PYTHONOPTIMIZE` set.
  - **Behaviour**: every check is an `assert` (L244, L249, L313; P205, P210, P273) and all are stripped.
    Reproduced (`r6_optimize_assert.py`, `PATH=/nonexistent`): `legacy -> exit 0 | shell rc of mysqlsh: 127`,
    and the same for packaged. Without `-O`: `AssertionError: mysqlsh should in the PATH`.
  - **Detection**: none from the tool. A partial or empty dump directory is the only clue.
  - **Blast radius**: a failed or partial snapshot is reported as successful and may be loaded (silent loss).
  - **Recovery**: run without `-O`, and verify MySQL Shell's completion marker before loading.
  - **RTO**: unmeasured.
  - **Test**: `GAP: failing command under -O exits non-zero (5.2 item 5)`
  - **DEFECT**: control flow by `assert` (D-13.03-13).

- **FM-13.03-12 Option-file handling breaks config mode**
  - **Trigger**: config mode with any of: `~` in `--defaults_file` (the default), MySQL Shell older than
    8.0.32, `[client]` options mysqlsh rejects (for example `default-character-set`), a quoted password, `%` in
    the password, or a bare key such as `skip-ssl`.
  - **Behaviour**: mysqlsh receives the literal `--defaults-file=~/.my.cnf` (reproduced). PyMySQL parsing
    differs from MySQL's (reproduced in 3.3; Spec 13.02). The file is passed even in password mode
    (`sink-connector/python/db_dump/mysql_dumper.py:164-165`), so it must exist and be accepted there too.
  - **Detection**: mysqlsh option error, PyMySQL authentication error, or a Python traceback. All loud.
  - **Blast radius**: the dump does not run.
  - **Recovery**: pass an absolute `--defaults_file` path whose content both parsers accept, and use MySQL
    Shell 8.0.32 or later.
  - **RTO**: unmeasured.
  - **Test**: `GAP: command built with an absolute defaults file; config parsing parity cases`
  - **DEFECT**: defaults file always forced, unexpanded, and version-gated (D-13.03-16).

- **FM-13.03-13 mysqlsh fails mid-dump (non-empty directory, privilege, invalid partition, disk full)**
  - **Trigger**: any MySQL Shell error.
  - **Behaviour**: mysqlsh output is relayed at INFO. A non-zero rc trips the assertion, which logs `Exception
    in main thread : mysqldumper failed, check the log.`, and the tool exits 1
    (`sink-connector/python/db_dump/mysql_dumper.py:312-321`). The partial directory stays (no completion
    marker). There is no retry and no resume.
  - **Detection**: exit 1 and the mysqlsh error text in the log.
  - **Blast radius**: no dump. MySQL locks are released by mysqlsh when it exits.
  - **Recovery**: move the partial directory aside, fix the cause, and re-run (mysqlsh requires an empty
    directory).
  - **RTO**: unmeasured. A full re-dump.
  - **Test**: `GAP: run_command non-zero rc gives exit 1` (reproduced offline with `r2_main_flow.py`:
    `run_command rc='1' | exit: 1`)

- **FM-13.03-14 Identifiers or paths containing quotes or shell metacharacters**
  - **Trigger**: `--mysql_database` or `--dump_dir` containing `'`, or host, user or defaults path containing
    whitespace or `;`, `$`, a backtick.
  - **Behaviour**: they go raw into the JS literal (`sink-connector/python/db_dump/mysql_dumper.py:131`) and the
    shell string (L180). Reproduced: `util.dumpTables('my'db', ..., '/backups/o'brien', ...)`, and a crafted
    `dump_dir` appends `println('INJECTED')`.
  - **Detection**: mysqlsh JS syntax error or shell error. Crafted values are not detected.
  - **Blast radius**: a failed run, or code execution inside mysqlsh (which has `os`/`shell` APIs) or in the
    shell.
  - **Recovery**: use plain identifiers and paths.
  - **RTO**: unmeasured.
  - **Test**: `GAP: JS literal and shell quoting of every interpolated value`
  - **DEFECT**: no escaping of interpolated values (D-13.03-15).

- **FM-13.03-15 Packaged connection URL with special characters in the password**
  - **Trigger**: `ch-mysql-dump` with a password containing `@` or `%xx`.
  - **Behaviour**: the URL is built without `quote_plus` (`sink-connector/python/ch_sink_tools/db/mysql.py:22`).
    Reproduced: `'p@ss' -> password 'p', host 'ss@db1'`, and `'p%41ss' -> 'pAss'`.
  - **Detection**: connection or authentication error (loud).
  - **Blast radius**: the dump does not run.
  - **Recovery**: use the legacy copy, or a password without `@` or `%`.
  - **RTO**: unmeasured.
  - **Test**: `GAP: packaged get_mysql_connection URL quoting` (owned by Spec 13.02)
  - **DEFECT**: unquoted URL credentials in the packaged copy (D-13.03-19).

- **FM-13.03-16 Two PostgreSQL tables map to one ClickHouse table (naming)**
  - **Trigger**: `ch-pg-dump` with several schemas and the default `{{ table }}` template, or any template
    that makes distinct inputs equal.
  - **Behaviour**: `resolve_ch_names` returns the same name for both
    (`sink-connector/python/ch_sink_tools/db_dump/naming.py:124-160`). Reproduced: `('app','users')` for both
    `public.users` and `sales.users`, and `a_b_c` for `a_b.c` and `a.b_c`. The caller does not check
    (`postgres_dumper.py:2104-2125`).
  - **Detection**: only the INFO `Mapping PG ... → CH ...` lines.
  - **Blast radius**: the loads of the two sources interleave into, or overwrite, one target table. End-to-end
    effect is specified in Spec 13.05.
  - **Recovery**: use a table template containing `{{ schema }}` with an unambiguous separator, then reload
    the affected tables.
  - **RTO**: unmeasured.
  - **Test**: `GAP: collision across work items is refused`
  - **DEFECT**: no collision detection (D-13.03-23).

- **FM-13.03-17 Malformed name templates pass validation (naming)**
  - **Trigger**: `--ch_database_template ''`, `{{ data base }}`, `{{ database.x }}` or `{{{ table }}}`.
  - **Behaviour**: `validate_template` accepts them (`naming.py:107-121`). Rendering leaves literal braces or an
    empty name (reproduced).
  - **Detection**: ClickHouse rejects the identifier later (loud), or a strangely named database or table is
    created.
  - **Blast radius**: wrong target names. No silent data change.
  - **Recovery**: fix the template and drop stray objects.
  - **RTO**: unmeasured.
  - **Test**: `GAP: validate_template rejects unmatched brace pairs and empty templates`
  - **DEFECT**: weak template validation (D-13.03-24).

- **FM-13.03-18 The legacy script cannot import its connection layer**
  - **Trigger**: `python db_dump/mysql_dumper.py ...` without `PYTHONPATH` containing `sink-connector/python`.
  - **Behaviour**: `from db.mysql import *` (L16) runs before `sys.path.append` (L26-27). Reproduced:
    `ModuleNotFoundError: No module named 'db'`.
  - **Detection**: immediate traceback, exit 1.
  - **Blast radius**: the legacy tool does not start.
  - **Recovery**: `export PYTHONPATH=.` from `sink-connector/python` (as `install.sh` does), or use `ch-mysql-dump`.
  - **RTO**: seconds.
  - **Test**: `GAP: legacy script starts from an arbitrary working directory`
  - **DEFECT**: path set-up after the import (D-13.03-18).

Summary: 18 failure modes, 17 DEFECT, 18 GAP.

## 7. Defect Register

Locations are relative to `sink-connector/python/` in the 2.11.0 tree.

| ID | Severity | Copy | Location | Evidence | Summary |
|---|---|---|---|---|---|
| D-13.03-1 | S1 | both | `db_dump/mysql_dumper.py:184-323`, `ch_sink_tools/db_dump/mysql_dumper.py:148-283`; loader `ch_sink_tools/db_load/clickhouse_loader.py:470-480` | code-read (absence: no `SHOW MASTER STATUS`, GTID or metadata read anywhere in the dumpers, connection layers or loaders; contrast `mysql_resync.py:275-287`) | The initial snapshot captures, logs and persists no binlog position or GTID set and hands none to the connector. The position exists only inside MySQL Shell's metadata, which nothing reads or checks (and which lacks it without `REPLICATION CLIENT`). Any connector start not seeded by hand loses the gap silently. |
| D-13.03-2 | S1 | both | `db_dump/mysql_dumper.py:255-289` (L128-129 clause), `ch_sink_tools/db_dump/mysql_dumper.py:216-250` (P95-96) | code-read, with vendor semantics: listed partitions only, empty list ignored, missing partition rejected | The table list and the full per-table partition list are resolved over a separate connection **before** mysqlsh takes its snapshot. The partition list is passed even when no partition filter was requested. Tables created, or partitions added or renamed, in the window are silently left out. |
| D-13.03-3 | S3 | both | `db_dump/mysql_dumper.py:273-274,278-280`, `ch_sink_tools/db_dump/mysql_dumper.py:234-235,239-241` | reproduced (`r4_sqlalchemy_row.py`, SQLAlchemy 2.1.1: `TypeError ... not str`, exit 1, mysqlsh never invoked) | `row['col']` on SQLAlchemy Rows. The tool cannot run on SQLAlchemy 2.x although `sqlalchemy>=1.4` allows it. |
| D-13.03-4 | S3 | both | `db_dump/mysql_dumper.py:159`, `ch_sink_tools/db_dump/mysql_dumper.py:124` | code-read with MySQL Shell docs ("with a space ... not interpreted as a password"); argv reproduced (`'--password', 's3cret'`) | Password mode emits `--password <pw>`, which mysqlsh does not read as a password, so password-mode dumps prompt or fail. |
| D-13.03-5 | S2 | both | `db_dump/mysql_dumper.py:159,180,87-90`, `ch_sink_tools/db_dump/mysql_dumper.py:124,144,55-58` | reproduced (argv printer shows the password token) | The password is on the `sh -c` and `mysqlsh` command lines, visible to any local user through `ps` or `/proc`. |
| D-13.03-6 | S2 | packaged | `ch_sink_tools/db_dump/mysql_dumper.py:124` | reproduced (`pw$(echo INJECTED)` becomes `pwINJECTED`; `"` gives a shell syntax error) | Password wrapped in double quotes in a `shell=True` string: command substitution and variable expansion run, and quotes break the command. |
| D-13.03-7 | S2 | packaged | `ch_sink_tools/db_dump/mysql_dumper.py:54,68` | reproduced (`cmd true --password 'S3cretPW' --user app`) | `--debug` logs the full command including the clear-text password. The legacy redaction was not ported. |
| D-13.03-8 | S2 | both | `db/mysql.py:41,44,47,49,71,76`, `ch_sink_tools/db/mysql.py:33,36,39,41,63,68` | reproduced (SQL text with `rlike '^t\d+$'`); server-side unescape per the MySQL manual, so `^td+$` matches `tdd`, not `t1` | Regexes and the schema name are interpolated into SQL string literals unescaped: backslash escapes are consumed (silently different table set) and quotes break or inject SQL. |
| D-13.03-9 | S3 | both | `db_dump/mysql_dumper.py:271,286-288`, `ch_sink_tools/db_dump/mysql_dumper.py:232,247-249` | reproduced (`--partitioned_tables_only` lists `t_np` with `'mydb.t_np': []`) | `--partitioned_tables_only` still dumps non-partitioned tables (their NULL partition row is counted). |
| D-13.03-10 | S3 | both | `db_dump/mysql_dumper.py:257-261`, `ch_sink_tools/db_dump/mysql_dumper.py:218-222` | reproduced (`--include_partitions_regex p2026` dumps all three tables, map only `{'mydb.t_p': ['p2026']}`) | `--include_partitions_regex` does not restrict the table list: non-partitioned tables and partitioned tables without a matching partition are dumped in full. |
| D-13.03-11 | S3 | both | `db_dump/mysql_dumper.py:121,131,200,305`, `ch_sink_tools/db_dump/mysql_dumper.py:89,98,164,266` | reproduced (statement identical with and without `--where`) | `--where` is accepted and silently ignored. |
| D-13.03-12 | S3 | both | `db_dump/mysql_dumper.py:91-96`, `ch_sink_tools/db_dump/mysql_dumper.py:59-64` | reproduced (`'None' after 0.02s`; grandchild blocks 2.02 s) | Exit status read with `poll()` right after EOF: it can be `'None'`, so a successful dump is reported as failed. |
| D-13.03-13 | S2 | both | `db_dump/mysql_dumper.py:244,249,313`, `ch_sink_tools/db_dump/mysql_dumper.py:205,210,273` | reproduced (`python -O`: mysqlsh rc 127 and exit 0) | All failure checks are `assert`s. Under `-O`/`PYTHONOPTIMIZE` a failed or partial dump exits 0. |
| D-13.03-14 | S4 | both | `db/mysql.py:44`, `ch_sink_tools/db/mysql.py:36` | code-read (`having count(*) = 1` over `information_schema.partitions`) | A single-partition table is classified as non-partitioned. |
| D-13.03-15 | S3 | both | `db_dump/mysql_dumper.py:131,155,165,180`, `ch_sink_tools/db_dump/mysql_dumper.py:98,121,130,144` | reproduced (`'my'db'`, `'/backups/o'brien'`, injected `println`) | Database and dump directory go unescaped into the JS literal. Host, user, port and defaults path go unquoted into the shell string. |
| D-13.03-16 | S3 | both | `db_dump/mysql_dumper.py:192-193,164-165`, `ch_sink_tools/db_dump/mysql_dumper.py:156-157,129-130` | reproduced (sh keeps `--defaults-file=~/.my.cnf` literal); version gate per vendor docs | `--defaults-file` is always emitted (the default is never `None`), with an unexpanded `~`, even in password mode. It requires MySQL Shell 8.0.32 or later and an option file both parsers accept. |
| D-13.03-17 | S4 | legacy | `db_dump/mysql_dumper.py:219-220` | reproduced (consistent stays 1 with or without the flag; packaged `--no_consistent` exits 2) | `--consistent` is a no-op (`store_true` with `default=True`). The copies differ in consistency control. |
| D-13.03-18 | S4 | legacy | `db_dump/mysql_dumper.py:16,26-27` | reproduced (`ModuleNotFoundError: No module named 'db'`) | `sys.path` is fixed up after the import that needs it. |
| D-13.03-19 | S3 | packaged | `ch_sink_tools/db/mysql.py:21-22` | reproduced (`'p@ss'` gives password `p`, host `ss@db1`; `%41` gives `A`) | URL credentials not `quote_plus`-encoded (legacy encodes them). Owned by Spec 13.02, restated for its effect here. |
| D-13.03-20 | S4 | both | `db_dump/mysql_dumper.py:209-212,127`, `ch_sink_tools/db_dump/mysql_dumper.py:173-176,94` | reproduced (`'ddlOnly': 1, 'dataOnly': 1` emitted) | `--schema_only` and `--data_only` are not mutually exclusive. The contradiction is passed to mysqlsh. |
| D-13.03-21 | S4 | both | `db_dump/mysql_dumper.py:23,36-44,99-112`, `ch_sink_tools/db_dump/mysql_dumper.py:27,38-46,67-80` | code-read | Dead code (`run_quick_command`, `runTime`, the `where` parameter). The import-time global log-record-factory hack. The `run_command` docstring says it returns True/False, but it returns a string. |
| D-13.03-22 | S4 | both | `db_dump/mysql_dumper.py:292-293,317`, `ch_sink_tools/db_dump/mysql_dumper.py:253-254,277` | code-read | The JS temp file has no `.js` suffix, so it runs in mysqlsh's default language (a `[mysqlsh]` `sql`/`py` mode breaks it). It is leaked on `os._exit(1)`. |
| D-13.03-23 | S2 | packaged | `ch_sink_tools/db_dump/naming.py:124-160`; caller `ch_sink_tools/db_dump/postgres_dumper.py:2104-2125` | reproduced (`public.users` and `sales.users` both give `users`; `a_b.c` and `a.b_c` both give `a_b_c`) | No collision detection in name resolution or its caller. The default template collides across schemas. End-to-end severity is owned by Spec 13.05. |
| D-13.03-24 | S4 | packaged | `ch_sink_tools/db_dump/naming.py:31,107-121` | reproduced (`{{ data base }}`, `{{ database.x }}`, `{{{ table }}}` and an empty db template all valid) | Template validation misses malformed placeholders and empty names. There is no character mapping or length limit. |
