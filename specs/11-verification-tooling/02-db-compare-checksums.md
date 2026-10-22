# Spec 11.02: Value-Level Checksum Verification (`db_compare`)

## 1. Executive Summary & Purpose
Specifies the value-level verification protocol implemented by the
`sink-connector/python/db_compare/` tools: how a MySQL table and its ClickHouse
replica are each reduced to one order-independent checksum, what canonical text
every column type is rendered to on both sides so that the two checksums are
comparable, and which tests pin that behaviour. The tool is the only
value-level proof that ClickHouse equals MySQL (AGENTS.md, "Silence is the
enemy"), so it is held to two rules:

1. It must **never report EQUAL when values differ** (a rendering that maps two
   different source values to one string is a masked divergence).
2. It must **not report DIFFERENT for representation noise** (a rendering that
   maps one value to two strings because the two engines print it differently
   is noise, and noise trains operators to ignore the tool).

---

## 2. Codebase Mapping on 2.11.0
- **Source side**: `sink-connector/python/db_compare/mysql_table_checksum.py` —
  `get_table_checksum_query()` builds the canonical row expression from
  `information_schema.columns`, `select_table_statements()` wraps it in the
  aggregate query, `calculate_checksum()` sums the per-chunk aggregates and
  prints the checksum line.
- **Replica side**: `sink-connector/python/db_compare/clickhouse_table_checksum.py` —
  `get_table_checksum_query()` builds the row expression from `system.columns`
  (delegating to `build_clickhouse_row_expression()`),
  `select_table_statements()` wraps it in the `FINAL` aggregate query,
  `compute_checksum()` prints the checksum line.
- **Driver**: `sink-connector/python/db_compare/top_level_table_checksum.py` —
  runs one source process and one replica process per ClickHouse host for
  every table, optionally under `LOCK TABLES ... READ`, and compares the
  parsed `(md5, count)` pairs in `analyze_differences()`.
- **Shared**: `sink-connector/python/db/checksum_common.py` —
  `checksum_from_aggregate()`, the one definition of how the five aggregate
  values become the printed checksum. `sink-connector/python/db/mysql.py` and
  `sink-connector/python/db/clickhouse.py` hold the connection and catalog
  helpers.
- **Tests** (offline; the database layer is stubbed, no test opens a
  connection): `sink-connector/python/db_compare/tests/test_checksum_fidelity.py`,
  `sink-connector/python/db_compare/tests/mysql_table_checksum_test.py`,
  `sink-connector/python/db_compare/tests/test_table_locking.py`,
  `sink-connector/python/db_compare/tests/test_bounded_source_lock.py`.
  Runner: `python3 -m pytest db_compare/tests` from `sink-connector/python`
  (also `python3 -m unittest discover -s db_compare/tests -t .`).

---

## 3. Operational Specification

### 3.1 The Row Count Fallacy
- **Principle**: Row count parity between MySQL and ClickHouse is **not** proof
  of replication correctness.
- In `ReplacingMergeTree`, un-merged duplicate rows, un-purged deletes
  (`is_deleted = 1`), and column-level default substitutions (e.g. `0` for
  `NULL`) can all report matching row counts while table contents
  fundamentally differ. Every incident listed in AGENTS.md had matching row
  counts and was caught only by this tool.

### 3.2 Protocol (driver)
For every table selected by `--tables_regex` (and the optional
`table_include_list` of the YAML config), `top_level_table_checksum.py`:
1. Reads the integer primary key, its min/max, the partition expression and
   the list of `TIMESTAMP` columns from MySQL `information_schema` on a
   shared connection, and resolves the **source time zone**
   (`resolve_source_timezone()`) from `--source_timezone` only. The flag is
   required by every checksum tool: the top-level driver, `mysql_table_checksum.py`,
   `clickhouse_table_checksum.py` and their wrappers. A run without it is refused
   before any connection is opened, with an error that names `--source_timezone`,
   suggests a region (for example `--source_timezone America/Chicago`) and states
   that `--source_timezone UTC` must be passed explicitly when the connector used
   UTC. No tool queries `@@session.time_zone` or `@@system_time_zone`, and no tool
   assumes UTC (§3.4). `resolve_source_zone(flag)` validates the flag: an empty
   flag is refused, never read as unset. Every run prints the effective zone on
   one line, `source time zone: <zone> (source=flag)`, and the one-zone rule
   (below). The value must be an IANA name (a `CST`-style abbreviation, an empty
   value or a `+05:00` offset is refused with an error naming the flag) and it must
   be the zone the connector's `database.connectionTimeZone` names, because it
   defines what instant a MySQL `DATETIME` denotes (§3.4). The zone is passed
   to both side scripts as `--source_timezone`, the column list to the
   replica script as `--timestamp_columns`.
   **One zone per invocation.** `connectionTimeZone` is a property of the
   connector instance, so one run compares every selected table in one zone. A
   database whose tables were written in different zones (mixed zone) needs one
   invocation per zone, each selecting its tables with `--tables_regex` (and
   `--exclude_tables_regex`). **Mixed-zone detection: not implemented, and it
   cannot be done from the data.** A checksum digest does not record the zone a
   table was written in, and a DATETIME verdict of DIFFERENT looks the same for a
   wrong zone and for data drift. The tool therefore does not claim a detection.
   It prints the one-zone rule on every run, and each table with a DIFFERENT
   verdict gets one INFO line in the run summary (spec 13.06) that names the table
   and the zone this run compared in: `Mixed-zone limit for <table>: DIFFERENT,
   and this run read every DATETIME/TIMESTAMP value in <zone> (--source_timezone)
   ...`. That line is the documented limit of a mixed-zone database, not a silent
   false positive. It is INFO because the driver log keeps WARNING for the
   difference itself, one line per table (step 4), which is what the scheduled
   job's WARNING scan reads. It names only the run's zone, because the tool does
   not know the other zone. A clean run logs none. The per-table zone option that
   would remove the limit is described in §3.4 and is not built.
2. If `--lock_tables_on_source` is set, opens a dedicated MySQL connection,
   runs `SET SESSION lock_wait_timeout = <--lock_wait_timeout>` (default 30
   seconds) and then `LOCK TABLES <table> READ`, and sleeps
   `--sleep_after_lock` seconds so replication drains. **Only the driver
   locks.** Neither side script issues a lock; run standalone they compare
   whatever each engine holds at the moment of the query.
   - **The wait for the lock is bounded per table.** `LOCK TABLES ... READ`
     waits for the table's metadata lock; on a replica whose SQL applier
     writes the table continuously there is no quiescent moment, and with
     the server's `lock_wait_timeout` (very large unless the server sets
     it lower) the acquisition stalls for that whole time and then fails
     with MySQL errno 1205. Without the bound, one such table stalled a run
     for the server's full timeout (about an hour on that server) and then
     aborted it, although every other table had checksummed fine.
   - Errno 1205 is classified as `LockAcquisitionError` in `lock_tables()`
     from the **structured driver error code** — `args[0]` of the raw
     `pymysql` error or of the SQLAlchemy-wrapped `.orig` — with the exact
     `Lock wait timeout exceeded` message as the fallback. It is
     deliberately not a `1205` substring match: an unrelated error on a
     table named `trades_1205`, or any message that merely contains those
     digits, must never be reclassified as a skippable timeout.
   - **Default: skip, name, and fail the run.** `run_config()` logs
     `WARNING COVERAGE GAP -- skipping checksum for <db>.<table>: source
     lock could not be acquired`, records the table, and keeps
     checksumming the remaining tables. The skipped list spans every
     database of the run. After the last table `report_run_summary()`
     logs `ERROR COVERAGE GAP: <n> table(s) were NOT compared (source lock
     timeout), so the run fails: <names>` and the run exits 1 (§3.9).
     `--allow_coverage_gap` is the opt-out: the same names are logged as
     `INFO COVERAGE GAP accepted (--allow_coverage_gap)` and the run
     exits 0 when nothing else fails it. The accepted line is INFO, not
     WARNING: the driver log keeps WARNING for differences only (step 4). Both are driver log lines, not
     side-script output, so step 4's parser never sees them.
     `--fail_on_lock_timeout` makes any such timeout abort the run instead
     (that table gets no verdict, so the run exits 1), as every other
     exception from a table still does.
3. Runs `mysql_table_checksum.py` and one `clickhouse_table_checksum.py` per
   replica host **concurrently under the lock** (`compute_checksum()`), each
   restricted to the table with `--tables_regex "^<table>$"` and the same
   `--where` / `{partition_expression}` filter. The lock is released in a
   `finally` block; the lock connection is closed even if `UNLOCK TABLES`
   fails (`test_table_locking.py`).
4. Parses the single line `Checksum for table <db>.<table> = <md5> count <n>`
   from each process (`parse_checksum()`). The side commands are argv lists
   run without a shell; the raw output is matched line by line, exactly one
   such message naming the expected `<db>.<table>` must be present. A side
   ERROR line is relayed at ERROR and that side gives no result; a side WARNING
   line is relayed at INFO as a side note, so the driver log keeps WARNING for
   differences (spec 13.06 §3.2, §3.8, §3.17). Other lines may contain the
   word "checksum".
5. `analyze_differences()` gives each table a verdict: a mismatch is logged as
   `WARNING Checksum difference : ...`; agreement as `INFO No difference for
   <table>`; agreement on zero rows as `INFO EMPTY on both sides ...`; a
   failed or unparseable side as `ERROR`, which makes the run exit 1 (spec
   13.06 §3.8).

### 3.3 Canonical row string
Both sides render every compared column to text and join the pieces with the
single character `#`:

- Columns are taken in ordinal position (`information_schema.columns
  .ordinal_position` / `system.columns.position`).
- **Column set.** The compared columns are the source table's. The driver
  reads every column name of the source table from `information_schema.columns`
  (`db.mysql.mysql_column_names()`) and passes them to each replica side as
  `--source_columns '["a", "b", ...]'`, a JSON list so that a column name
  holding a comma or a space arrives intact (`parse_source_columns()`; a
  hand-typed comma separated list is also accepted, a malformed JSON list is
  an error); the source side is unchanged. The replica side
  (`restrict_to_source_columns()`) keeps only the `system.columns` rows whose
  name the source table also has, compared without regard to case (MySQL
  column names are case-insensitive). A **replica-only column** — one the
  source table does not have, such as a column added on ClickHouse alone with
  `ADD COLUMN x ... DEFAULT <expression over other columns>` — holds no
  replicated value, so no source value exists to compare it against: hashing
  it could only make the two sides differ for equal replicated data. Per the
  parity-scope clarification of Invariant I6 (`specs/CONSTITUTION.md`), such a
  column is outside the comparison's scope entirely: it is **tolerated**. It
  is left out (no value, no separator, no null flag) and reported once per
  table at INFO as tolerated — never a WARNING, never affecting the exit code
  (§3.9). `--allow_replica_only_columns` remains an accepted flag
  (Invariant I11: no flag is removed) but is now a no-op, since tolerance is
  the only behaviour. A source column the replica lacks cannot be left
  out on the replica side (the source side hashes it), so the table still
  reports DIFFERENT; the replica side names it in a WARNING so the difference
  explains itself. Excluded columns and the connector's own columns are
  neither compared nor reported. Without `--source_columns` (a standalone run,
  or a source table the driver could not describe) every replica column is
  compared, as before. Before this, a column added on one replica only made
  every later run report DIFFERENT for that table with nothing pointing at the
  column: a replicated table `t(id, payload JSON, ...)` whose replicas gained
  `name Nullable(String) DEFAULT JSONExtractString(payload, 'Name')` (the
  source `t` has no such column) failed every scheduled checksum from then on
  while every replicated column agreed value for value. With the column set
  restricted, the replica side's aggregate over the same live table equals
  the source checksum
  (`test_checksum_fidelity.py::TestReplicaOnlyColumns`,
  `test_checksum_verdicts.py::TestSourceColumnSetReachesTheReplicaSide`).
- A MySQL column is **classified on `information_schema.columns.DATA_TYPE`**
  (the bare type keyword: `enum`, `datetime`, `blob`, ...) plus
  `DATETIME_PRECISION`; `COLUMN_TYPE` is read only for the declared width of
  `bit`. `COLUMN_TYPE` carries user text — the labels of
  `enum('float','json','blob')` — and the old substring tests on it
  (`'float' in data_type`, `'json' in`, `is_binary_datatype()` matching
  `bit`/`blob`/`binary` anywhere in the string) skipped such an enum column
  from the comparison entirely, JSON-normalised it or hex-encoded it
  (`test_checksum_fidelity.py::TestMySQLColumnClassification`).
  `db.mysql.is_binary_datatype()` strips a `(length)` suffix and matches the
  bare keyword exactly, so it accepts both `DATA_TYPE` and a declared type as
  the dump loader passes it.
- A column that is excluded (`--exclude_columns`, the connector's own
  `_sign`, `_version`, `is_deleted`, `_is_deleted`, `__is_deleted`) or
  skipped by type (floating point unless `--include_floating_point_columns`)
  contributes **nothing** — no value and no separator. The row expression is
  therefore built as a **list of column expressions joined by `'#'`**, never
  by appending a separator after each column: the old replica-side code
  appended `||'#'` to every column except the last of the *unfiltered* list,
  so a table whose last column was a skipped `Float64` produced `1#bob#`
  against MySQL's `concat_ws('#', ...)` = `1#bob` and reported DIFFERENT for a
  table that was equal (`test_checksum_fidelity.py::
  TestClickHouseRowExpression.test_trailing_float_column_leaves_no_dangling_separator`).
- A `NULL` value renders as the empty string on both sides
  (`ifnull(expr, '')` / `case when col is null then '' else expr end`), and
  the nullability of the row is carried by one extra trailing element: the
  concatenation, in column order, of `1` (NULL) or `0` (not NULL) for
  **every** compared column, whether or not its catalog declares it nullable
  (`concat(ISNULL(a), ISNULL(b))` /
  `case when a is null then '1' else '0' end || ...`). This keeps `NULL`
  distinguishable from `''`. The flags are value-based over the same columns
  on both sides, so a column declared nullable on MySQL and non-Nullable on
  ClickHouse (a stored generated column, for instance) gives the same flags
  for equal values, while a MySQL NULL against the ClickHouse default still
  differs in its flag (spec 13.06 D-13.06-40,
  `test_checksum_fidelity.py::TestNullFlagsOverEveryComparedColumn`).
- **Escaping (format v2).** Every value piece is escaped before the join:
  each backslash is doubled first, then each `#` is prefixed with a
  backslash (`escape_checksum_piece()` / `join_checksum_pieces()` in
  `db/checksum_common.py`). The join is therefore injective on the pieces: an
  unescaped `#` in the joined text can only be a separator, so `a#b` + `c` and
  `a` + `b#c` are different texts (before v2 both gave `a#b#c`). The NULL guard
  and the value expression are escaped together, so an empty piece stays empty.
  The flags element is `0`/`1` text and is not escaped. Both renderers apply the
  same escape with the same vectors: MySQL
  `REPLACE(REPLACE(<piece>, CHAR(92 USING utf8mb4), CONCAT(CHAR(92 USING utf8mb4), CHAR(92 USING utf8mb4))), '#', CONCAT(CHAR(92 USING utf8mb4), '#'))`,
  ClickHouse `replaceAll(replaceAll(<piece>, char(92), concat(char(92), char(92))), '#', concat(char(92), '#'))`.
  The backslash is produced by `CHAR(92)` / `char(92)`, not written as a
  literal, so the result does not depend on `NO_BACKSLASH_ESCAPES`. Vectors:
  `a#b` -> `a\#b`, `a\b` -> `a\\b`, `a\#b` -> `a\\\#b`
  (`db_compare/tests/test_checksum_row_escaping.py`;
  `test_checksum_fidelity.py::TestClickHouseRowExpression`). Changing the
  escape changes every row hash, so the printed checksum carries the format tag
  `v2` (§3.5).
- Per-type rendering (both sides must produce byte-identical text for equal
  values):

| MySQL type (`DATA_TYPE`) | ClickHouse type | MySQL expression | ClickHouse expression |
|---|---|---|---|
| integers, `year`, `char`/`varchar`/`text`, `enum`, `set` | `Int*`/`UInt*`, `String`, `LowCardinality(String)` | the column (`convert(col using utf8mb4)` when the table mixes collations) | `toString(col)` |
| `decimal` | `Decimal(p,s)` | the column, then the trailing zeros after the point and the bare point removed (`mysql_decimal_text`) | `toDecimalString(col, s)` (`numeric_scale` from `system.columns`), then the same removal (`clickhouse_decimal_text`) |
| `date` | `Date`/`Date32` | `case when col >= max then max when col <= min then min else col end` with `--min_date_value` / `--max_date_value`; the clamp is counted (`clamped_date_flag`, §3.4) | `toString(col)` (a Date32 replica cannot hold an out-of-range value, so it counts nothing) |
| `time(p)`, any `p` | `String` holding `[-]HH:MM:SS.ffffff` (spec 07.03 §3.2) | `cast(col as time(6))` — six fraction digits unconditionally; the old `substr(cast(col as time(6)),1,length(col))` truncated `time(0)` to `10:00:00` and reported DIFFERENT (`test_checksum_fidelity.py::TestMySQLTemporalRendering.test_time_is_rendered_with_six_fraction_digits_for_every_precision`) | `toString(col)` |
| `datetime(p)`, `timestamp(p)` | `DateTime`, `DateTime64(s[, tz])` | §3.4 | §3.4 |
| `bit(1)` (`COLUMN_TYPE` exactly `bit(1)`; Debezium emits it as BOOLEAN) | `Bool`, `Nullable(Bool)` (or `UInt8`) | `col+0` — renders `1`/`0`; the generic binary rendering gave the hex text `01` | `toString(toUInt8(col))` — matched with `'Bool' in type`, so `Nullable(Bool)` no longer falls through to `toString(col)` = `true`/`false` (`test_checksum_fidelity.py::TestBooleanAndBit`) |
| binary, floating point, JSON | — | see the following sections | see the following sections |

Two normalisations apply to the table above (unit D; the same value must give
the same row text on both sides, and a difference in declared scale or padding
is not a data difference):

- **DECIMAL.** Both sides drop the trailing zeros after the point and then the
  bare point (`10.50` and `10.5000` both give `10.5`; `12.000` gives `12`).
  Text without a point is unchanged. The comparison is therefore by value: the
  same DECIMAL value declared at two scales compares equal, and two different
  values never do (`test_checksum_fidelity.py::TestUnitDNormalisation`;
  e2e case c07).
- **CHAR (PAD SPACE).** MySQL does not return the trailing spaces of a `CHAR`
  value, so a `CHAR` column whose value ends in spaces would differ from its
  replica. The driver reads the `CHAR` columns of the source table from
  `information_schema` (`DATA_TYPE = 'char'`) and forwards their names to the
  ClickHouse side as `--char_columns`. The ClickHouse side then trims trailing
  spaces (`replaceRegexpOne(toString(col), ' +$', '')`) only for those `String`
  columns; a `VARCHAR` or `TEXT` column keeps its trailing spaces, and a `FixedString`
  or hex-encoded column is never trimmed. A standalone ClickHouse run (not
  started by the driver) must pass `--char_columns` itself, or a `CHAR` column
  with trailing spaces reports DIFFERENT (e2e case c03).

### 3.4 DATETIME / TIMESTAMP: one canonical rendering, one clamp
Both sides render a DATETIME or TIMESTAMP value to the **fixed-width** text
`YYYY-MM-DD HH:MM:SS.ffffff` — six fraction digits for every declared
precision, never trimmed:

- MySQL: `date_format(col, '%Y-%m-%d %H:%i:%s.%f')`
  (`mysql_datetime_rendering()`); ClickHouse:
  `toString(toDateTime64(col, 6), '<zone>')` (`clickhouse_datetime_rendering()`;
  the scale is widened, the instant is unchanged, the zone is chosen per
  column as described next).
- **Instants, not wall clocks.** A ClickHouse `DateTime64` holds an instant;
  `toString(col)` prints it in the column's declared zone, so comparing that
  text with MySQL's text compares two renderings, not two instants. The
  connector's record-schema path creates `DateTime64(p,'UTC')` columns but
  formats the value in the server zone, so it stores an instant off by the
  server offset while the two wall-clock texts still agree; the old tool
  reported such a table EQUAL (spec 08.05 §3.1). The tool now
  compares instants:
  - `TIMESTAMP` is an instant in MySQL. The source script runs
    `set time_zone = '+00:00'` on its session, so `date_format` prints the
    UTC wall clock; the replica script renders columns named in
    `--timestamp_columns` with `toString(toDateTime64(col, 6), 'UTC')`. Equal
    text ⇔ equal instant, and UTC has no daylight-saving fold, so no two
    instants share a text.
  - `DATETIME` has no instant in MySQL; the connector defines one by
    interpreting the wall clock in the source zone (spec 07.03 §3). The
    source script prints the wall clock unchanged (the session zone does not
    affect `DATETIME`); the replica script renders every other
    `DateTime`/`DateTime64` column with `toString(toDateTime64(col, 6),
    '<--source_timezone>')`. The texts agree exactly when ClickHouse holds the
    instant that wall clock denotes in the source zone; a value stored as
    "wall clock read as UTC" in a non-UTC deployment renders shifted and is
    reported DIFFERENT.
  - The clamp bounds are instants too (`DataTypeRange` is defined in UTC).
    For `TIMESTAMP` columns they apply to the UTC text as they are; for
    `DATETIME` columns both sides use the bounds rendered in the source zone
    (`shift_datetime_bounds()`, e.g. `2299-12-31 23:59:59` UTC is
    `2300-01-01 08:59:59` in `Asia/Tokyo`), which is exactly the wall clock
    the connector writes for an out-of-range value. `--min/--max_datetime_value`
    are therefore given as UTC instants.
  - **No default.** `--source_timezone` has no default on any checksum tool.
    No tool falls back to UTC, and none reads the MySQL server zone. The side
    scripts parse the flag with no default and check it in `main()`, before any
    connection is opened: a missing flag exits with an error that names
    `--source_timezone`, suggests `America/Chicago`, and says UTC must be passed
    explicitly when the connector used UTC. The driver applies the same check
    and passes the flag, together with `--timestamp_columns`, to both side
    scripts. `resolve_source_zone(flag)` has no session-zone or default input.
    An accepted flag logs at INFO as `source=flag`.
  - **Effect on existing invocations.** A run that passed a valid zone keeps
    its effective zone. Three kinds of invocation change: (a) a top-level run
    given `--source_timezone ""` used to fall back to the session zone silently
    and now fails with an error naming the flag; (b) a run whose zone is a
    refused name (`EST`, `CST6CDT`, `Etc/GMT+5`, a `posix/` name, an offset)
    fails instead of comparing a zone the connector never used; (c) a run with
    no flag, which used the MySQL session zone (top level) or UTC (side
    scripts), is refused before any connection and must be re-run with the
    connector's zone.
  - **One zone per run; mixed zones are not detected; a DIFFERENT table gets a
    named limit.** The tools cannot detect a set of tables written under
    different connector zones. A checksum row does not carry the zone the
    connector used, so the run has no evidence to compare against. The driver
    logs one INFO line per run that names the zone it applies to every compared
    table. Each DIFFERENT table also gets one INFO line in the run summary
    (`Mixed-zone limit for <table>: DIFFERENT, ... in <zone> (--source_timezone)
    ...`, spec 13.06) that names the table and the run's zone and says that a
    table written in another zone is DIFFERENT in the same way as a table with
    drift. The other zone is not named, because the tool does not know it. The
    line is INFO so that a difference stays one WARNING line in the job log, and
    it does not change the exit code. Tables whose connector zones differ
    need one run per zone, selected with `--tables_regex`.
    **Per-table zone option (described, not built).** Removing the limit needs
    one zone per table instead of one per run. That would take: a table-to-zone
    input (a file or a repeated option), validated the way the flag is, that
    names every table it covers; the driver passing each table's zone into the
    side commands it builds for that table (it already runs both side scripts
    once per table with `--tables_regex`); the zone in the INFO line and in the
    mixed-zone INFO line; and a rule for a table missing from the map. It is not a
    trivial change, and no tool can check the zone an operator gives for a
    table, so it is not built.
  - **Loader (`db_load`) and ch-mysql-resync: the flag is required on every run.**
    `clickhouse_loader.py` `main()` calls `check_source_timezone_flag` right after
    argument parsing, so a run without `--source_timezone` exits 2 before any
    connection, and the message names the flag, suggests `America/Chicago` and
    says UTC must be passed explicitly. A dump that records a zone still uses the
    recorded zone (`get_unix_timezone_from_mysql_timezone`); a flag that
    disagrees with it is an error. `ch-mysql-resync patch` has the same required
    `--source_timezone` and passes it to the loader it runs (spec 13.04). Only
    the explicit flag decides a load; the MySQL server zone is never read.
    **The flag is trusted, not validated, except against a recorded dump zone.**
    The checksum entry points (`ch-mysql-checksum`, `ch-ch-checksum`, and the
    side scripts the driver runs) trust the flag. No tool reads the MySQL server
    zone, and a checksum does not record the zone a table was written in, so a
    flag that names the wrong zone cannot be detected. It shows up only as
    DIFFERENT verdicts on DATETIME values. The loader (`ch-mysql-load`) and
    resync can reject a contradicting flag only when the dump records its zone:
    `get_unix_timezone_from_mysql_timezone` raises a `ValueError` that names
    `--source_timezone`. A dump without a recorded zone takes the flag as given.
    `ch-mysql-resync rewind-sql` takes no zone. `ch-mysql-dump`, `ch-ch-count`
    and the PostgreSQL tools take no MySQL zone flag, so this change does not
    cover them.
  - **UNREPRESENTABLE-IN-ZONE.** `classify_wall_clock(text,
    zone)` classifies one canonical wall clock with the zone's own rules,
    without a database: `gap` (a DST spring-forward skips it: Chicago
    2026-03-08 02:00-02:59, Lord Howe 2026-10-04 02:00-02:29, Europe/London
    2026-03-29 01:00-01:59, Pacific/Chatham 2026-09-27 02:45-03:44), `fold` (a
    fall-back repeats it: Chicago 2026-11-01 01:00-01:59, Lord Howe 2027-04-04
    01:30-01:59), `before-range` / `after-range` (outside the zone's shifted
    DATETIME bounds) or `ok`. ClickHouse cannot round-trip a gap time (Chicago
    02:30 becomes 01:30), so a MySQL row holding one can never match its
    ClickHouse copy by text. **Integration limit (not fixed):** the aggregate
    checksum of §3.2 does not expose per-row values, so the tools do not yet
    tag each mismatched row with its class; a mismatch on such a table reports
    `DIFFERENT`, which is loud and never masked. A per-row report would need
    the row-level path and is a follow-up. Unit tests:
    `db_compare/tests/test_checksum_timezone.py::TestWallClockClassifier`
    (every minute of each transition day).
  - **ClickHouse 25.8 edge limits.** Probe with `clickhouse local`,
    clickhouse-client 25.8.16.20002. Agreeing with zoneinfo: St_Johns 1900 LMT (UTC
    1900-01-01 04:00 renders 00:29:08; wall 1900-01-01 00:00 parses to 03:30:52
    UTC), Asia/Kolkata 1900 LMT, Chicago 1900 CST, and the east-zone walls of
    2299-12-31 23:59:59 (Kolkata, Tokyo). **Not agreeing, recorded and not
    fixed:** (a) ClickHouse clamps pre-1900 local walls in its text to
    `1900-01-01 00:00:00` (Chicago UTC 1900-01-01 00:00; St_Johns UTC 01:00); the
    epoch is correct; (b) Asia/Kolkata wall 2300-01-01 00:00 parses to
    2299-12-30 18:30 UTC, one day early (zoneinfo: 2299-12-31 18:30); (c) the
    west-zone walls 2299-12-31 23:59:59 (Chicago, St_Johns) come back as
    1970 values (ClickHouse LUT beyond 2037). MySQL is the truth and the tools
    use zoneinfo; `shift_datetime_bounds` and `saturation_floor` equal zoneinfo
    for the eight tested zones. A value in one of these
    ClickHouse windows is a DIFFERENT on that table (FM-11.02-15), and its
    zoneinfo class is one of the classes listed above. Test constants:
    `db_compare/tests/test_checksum_timezone.py::TestClickHouse258Probe`.
    Standalone runs in a non-UTC deployment must pass both, or they report
    DIFFERENT for every `TIMESTAMP` column — noise on the safe side, never a
    masked divergence. The value must be an IANA name accepted by both
    Python's `zoneinfo` and ClickHouse (`validate_timezone()`).
  - **Zone names.** `validate_timezone()` accepts exactly three
    forms: `UTC`, `GMT` and `Etc/UTC` (the three zero-offset names the
    connector resolves too), and a region identifier `Area/Location` (one or
    more `/` separated segments, e.g. `America/Chicago`, `US/Central`,
    `America/Argentina/Buenos_Aires`). It refuses, each with an error that
    names the option: a blank or padded value; `SYSTEM` (resolve it from
    `@@system_time_zone` first); an abbreviation or legacy fixed zone with no
    region (`CST`, `EST`, `MST`, `HST`, `CST6CDT`, `EST5EDT`); any other
    `Etc/` name (`Etc/GMT+5` has the inverted sign); `SystemV/`, `posix/` and
    `right/` names; and an offset (`+05:00`). Python's `zoneinfo` accepts
    several of these, so the rule is applied before the lookup, not left to it.
  - **tzdata missing is not an unknown name.** When the zone
    database itself is unavailable (no system `tzdata` and no `pip` `tzdata`
    package), `ZoneInfo` raises the same exception as for an unknown name. The
    tool first checks that `UTC` resolves: if it does not, the error says the
    IANA database is not installed and names the fix (install the system
    `tzdata` package or `pip install tzdata`), and the valid name is not
    blamed. Only when `UTC` resolves is a region name reported as unknown.
  (`test_checksum_fidelity.py::TestInstantComparison`)
- Why fixed width: the connector widens precision (`datetime(0)` is stored
  as `DateTime64(3)`), so the two sides legitimately print different numbers
  of fraction digits; the old code equalised them by trimming trailing zeros
  and a trailing dot, which is **not injective** when the printed lengths
  differ: `10:00:10` became `10:00:1` on one side and `10:00:10.000` became
  `10:00:10` on the other — DIFFERENT for equal values, and conversely two
  values whose trimmed text collided would compare EQUAL. Padding to six
  digits maps equal instants to equal text and distinct instants to distinct
  text (`test_checksum_fidelity.py::TestDatetimeRendering`).
- **Clamp.** ClickHouse `DateTime64` holds `1900-01-01 00:00:00` ..
  `2299-12-31 23:59:59` (`DataTypeRange.DATETIME64_MIN/MAX`); the connector
  clamps on write, so MySQL values outside that range can only compare equal
  if the tool clamps its rendering the same way. The clamp is **one shared
  definition** in `db/checksum_common.py` used by both sides:
  `DATETIME_MIN = '1900-01-01 00:00:00.000000'`,
  `DATETIME_MAX = '2299-12-31 23:59:59.000000'`;
  `clamp_datetime_expression(rendered, min, max, dialect)` renders
  `rendered >= max` as `max` and `rendered < min` as `min` in both dialects
  (the old code used `<=` on one side and `<` on the other, defaulted the
  minimum to `1970-01-01` on MySQL and `1900-01-01` on ClickHouse, trimmed the
  substituted bound on one side only, and clamped only `DateTime64(0)` and
  `DateTime64(6)` on ClickHouse). Comparison is on the canonical text, which
  orders chronologically. `--min_datetime_value` / `--max_datetime_value`
  default to these constants on both sides; a user value is normalised to
  the canonical form (`canonical_datetime_bound()`, accepting
  `YYYY-MM-DD[ HH:MM:SS[.ffffff]]`) and **confined to the ClickHouse range**
  with a WARNING, because a bound the replica cannot exceed would clamp
  MySQL alone. Narrower bounds turn every value beyond them, on both sides,
  into the same constant: differences inside that window are invisible. The
  driver therefore passes no bounds by default (it used to pass
  `1969-12-31 18:00:00` / `2299-12-31 00:00:00`, hiding 1900–1969).
  Narrowing is an explicit opt-in: `--min_datetime_value`
  / `--max_datetime_value` given to the driver are canonicalised with the
  same rule before any side runs (a non-datetime value or bounds with
  min >= max stop the run) and forwarded, as identical text, to both sides
  (`test_driver_datetime_bounds.py`). A narrower upper bound also moves the
  saturated last day (next item) to the UTC day of that bound.
- **The last day of the range is saturated.** A source value past the
  range has more than one representation on the replica: the connector
  stores the upper bound (`2299-12-31 23:59:59`, older builds
  `2299-12-31 23:59:59.999999`), while a bulk load (initial snapshot, a
  reload) stores the source's own time of day on `2299-12-31`
  (`9999-12-31 00:00:00` becomes `2299-12-31 00:00:00`, `9999-12-31 05:59:00`
  becomes `2299-12-31 05:59:00`). Bulk loading is a supported way to fill a
  replica, so all of these compare equal to every out-of-range source value:
  `saturation_floor(utc_bounds, zone)` is the first instant of the UTC
  calendar day that holds the upper bound (`2299-12-31 00:00:00` UTC for the
  default range), rendered in the column's comparison zone like the bounds
  (`2299-12-31 09:00:00` in `Asia/Tokyo`, `2299-12-30 18:00:00` in
  `America/Chicago`) and never above the upper bound in that zone. Both row
  builders pass it to `clamp_datetime_expression(rendered, min, max, dialect,
  saturate_from)`, which renders `rendered >= saturate_from` as `max` in both
  dialects. A difference between two values inside that last day is
  therefore invisible; a value on the day before is compared as it is.
  `clamp_datetime_expression` without `saturate_from` keeps the plain
  `>= max` rule.
- **Clamped-value counts are printed.** Each datetime column contributes
  `clamped_datetime_flag()` = `((rendered >= saturate_from and rendered !=
  max) or rendered < min)` (without `saturate_from`: `(rendered > max or
  rendered < min)`), i.e. every value the clamp changed; the
  per-row sum (`clamped_count_expression()`, `coalesce(flag, 0)` so NULLs
  count as 0) is aggregated as a sixth value `clamped` next to
  `cnt,a,b,c,d`. It is **never hashed**; when it is non-zero the side script
  logs `WARNING <n> out-of-range datetime values clamped to [min, max] in
  table <db>.<table>` (a line that does not contain the word "checksum",
  see §3.2 step 4). The driver reads the count back from that line
  (`CLAMP_NOTICE`), and any clamp on either side makes the table **DIFFERENT**,
  never MATCH, even when the checksums are equal: a clamped value compares as
  the bound, so equal checksums say nothing about its real value
  (`db_compare/tests/test_checksum_verdicts.py::TestClampedValuesMakeTheVerdictDifferent`;
  the side's count is `test_checksum_fidelity.py::TestClampedRowCounts`).
  The MySQL side also clamps `date` values to `[--min_date_value,
  --max_date_value]` (default `1900-01-01` .. `2299-12-31`, the Date32 range
  the replica can hold) and counts them in the same `clamped` value
  (`clamped_date_flag`, unit D). Its WARNING therefore reads
  `<n> out-of-range date/datetime values clamped (datetime to [...], date to
  [...]) in table <db>.<table>`. The ClickHouse side has no DATE clamp to count,
  so its WARNING keeps the datetime-only wording. A clamp on either side makes
  the verdict DIFFERENT, as above (`TestClampedValuesMakeTheVerdictDifferent`);
  the normalisation of the counted values is `test_checksum_fidelity.py::TestUnitDNormalisation`.

### 3.5 Aggregate (the actual algorithm)
The checksum is **not** `MD5(groupArray(cityHash64(*)))` and there is no
`ORDER BY`; it is an order-independent sum of per-row MD5 words, so both
engines can compute it over any physical order and MySQL can compute it in
parallel chunks:

1. Per row, `hash = md5(row_string)` as 32 lowercase hex characters, where the
   row string is the escaped pieces (§3.3) joined by `#`. MySQL:
   `md5(convert(concat_ws('#', <escaped pieces>) using utf8mb4))`; ClickHouse:
   `hex(MD5(<escaped pieces joined by ||'#'||>))` (case does not matter, the hex
   is consumed numerically).
2. The hash is split into four 32-bit words: `a = hex[0:8]`, `b = hex[8:16]`,
   `c = hex[16:24]`, `d = hex[24:32]`, each read as an unsigned integer
   (MySQL `cast(conv(substring(h, k, 8), -16, 10) as signed)`; ClickHouse
   `reinterpretAsInt64(reverse(unhex(substring(h, k, 8))))`) and **summed over
   all rows** into signed 64-bit accumulators (MySQL through the session
   variables `@a := @a + ...` with `max()` over the derived table; ClickHouse
   `sum(...)`).
3. The side script prints
   `Checksum for table <db>.<table> = md5('v2#<cnt>#<a>#<b>#<c>#<d>#') count <cnt>`
   — `checksum_from_aggregate()` in `db/checksum_common.py` is the single
   definition of that final step and both scripts call it. The `v2` tag is the
   format of the row text (§3.3 escaping): a checksum printed by an earlier
   version (no tag, unescaped pieces) is not comparable with a v2 checksum, so
   the two sides must run the same tool version. An empty table yields
   `md5('v2#0#0#0#0#0#')` on both sides.
4. ClickHouse reads `FROM <db>.<table> FINAL` so that
   `ReplacingMergeTree` versions collapse before hashing. MySQL, when the table
   has an integer primary key and `--threads_per_table > 1`, splits the key
   range into chunks (`divide_table_into_even_chunks()`), hashes each chunk in
   its own connection and adds the five accumulators in Python; the result is
   identical to a single pass because the aggregate is a sum.

Because the aggregate is a sum of per-row words, any change to any compared
value of any row changes the row's MD5 and therefore, with overwhelming
probability, at least one of `a..d`, and the printed checksum
(`test_checksum_fidelity.py::TestEndToEndChecksum.test_flipped_clickhouse_value_reports_different`).
Row count is carried separately in `cnt` and in the printed `count`.

### 3.6 Binary columns: one encoding on both sides
The connector writes `BLOB`/`BINARY`/`VARBINARY`/`BIT(n>1)` values into a
`String` column in one of three shapes, chosen by its configuration. The
replica catalog cannot tell such a column from a text `String`, so the tool
must be told which shape to expect, with the **same `--binary_encoding` on
both sides** (argparse rejects anything but `hex`, `base64`, `raw`):

| connector configuration | ClickHouse holds | `--binary_encoding` | MySQL expression | ClickHouse expression |
|---|---|---|---|---|
| default (`persist.raw.bytes=false`, `binary.handling.mode=bytes`) | lowercase hex **text** (`BaseEncoding.base16().lowerCase()` in `ClickHouseDataTypeMapper`) | `hex` (default) | `lower(hex(cast(col as binary)))` | `toString(col)` |
| `binary.handling.mode=base64` | the base64 text Debezium delivers | `base64` | `replace(to_base64(cast(col as binary)),'\n','')` | `toString(col)` |
| `persist.raw.bytes=true` | the raw bytes | `raw` | `lower(hex(cast(col as binary)))` | `lower(hex(col))` for the `String` columns named in `--hex_columns`, `toString(col)` otherwise |

- The driver's `--binary_encoding` (default `hex`) is passed to both scripts.
  It used to hard-code `base64` — as did the two integration test drivers —
  while a default connector stores hex text, so every table with a binary
  column reported DIFFERENT under the default configuration.
- In `raw` mode the driver derives `--hex_columns` per table from
  `information_schema` (`db.mysql.binary_datatypes`); a standalone replica
  run must pass the list itself. `--hex_columns` applies to `String` columns
  only, so a `bit(1)` → `Bool` column in that list is left to the boolean
  rendering; it is accepted only together with `--binary_encoding raw` (with
  `hex`/`base64` the replica already holds encoded text and the option is
  refused). The old meaning, `toString(unhex(col))`, was the inverse of what
  the MySQL side produces in every mode and could never compare equal.

(`test_checksum_fidelity.py::TestBinaryEncoding`)

### 3.7 `FINAL` across partitions
The replica query reads `FROM <table> FINAL` so that the versions of a row
collapse before hashing. The old query always added
`settings do_not_merge_across_partitions_select_final=1`, which makes `FINAL`
collapse **within each partition only**. MySQL allows an `UPDATE` to change a
row's partition-key column; the connector then writes the new version into
the new partition and the old version stays in the old one (spec 05.02
covers only sorting-key changes). With the setting, `FINAL` keeps both
versions — one per partition — and the tool reported DIFFERENT for a table
that was equal (finding C16).

The setting is therefore applied only when it cannot change the result:
when the partition key is a **function of the sorting key**, i.e. every
column referenced by the partition expression is a sorting-key column
(`system.columns.is_in_partition_key = 1` ⇒ `is_in_sorting_key = 1`, and the
table has a partition key at all). Then a row cannot change partition
without changing its sorting key, which the connector handles as a
tombstone in the old partition plus a live row in the new one (spec 05.02),
and per-partition `FINAL` is exact. Otherwise the setting is dropped and
`FINAL` merges across partitions; `get_table_checksum_query()` logs the
decision. (`test_checksum_fidelity.py::TestFinalAcrossPartitions`)

### 3.8 Deleted rows: the sign column follows the engine
The connector creates `ReplacingMergeTree(_version, is_deleted)` tables
(spec 08.05). ClickHouse `FINAL` drops a key whose latest version is a
delete row (`is_deleted = 1`), so the replica query needs **no row filter**
for such tables; `is_deleted` is only excluded from the row string (§3.3).
Older `CollapsingMergeTree` layouts need `WHERE <sign> > 0` instead.

`--sign_column` therefore defaults to the empty string, and when it is
empty the replica script derives the filter from
`system.tables.engine_full` (`sign_column_from_engine()`): for
`CollapsingMergeTree`, `VersionedCollapsingMergeTree` and their
`Replicated*` variants the sign is the first unquoted engine argument
(after the ZooKeeper path and replica name); for every other engine there
is no filter. An explicit `--sign_column` is used verbatim, for layouts
that keep a sign column outside the engine definition. The old default
`_sign` made every standalone run against a 2.x table fail with an unknown
identifier, which is why the driver had to pass `--sign_column ""`
explicitly. (`test_checksum_fidelity.py::TestSignColumn`)

Two stale wrapper scripts that invoked the tool with flags it never had
(`--sign_column=is_deleted --new_rmt=True --exclude-columns=[...]`) —
`sink-connector/tests/diff_datatypes_lightweight_data.sh` and
`sink-connector-lightweight/src/test/diff_data_types.sh` — were removed;
nothing referenced them.

### 3.9 Coverage: what is compared, what is not, and how loudly
A column the tool does not compare is a hole in the only value-level proof,
so it is never silent. Per table, each side logs **one `WARNING`** naming the
count and the skipped floating point columns, in the form `Not compared in table
<db>.<table>: N floating point columns [...] (pass --include_floating_point_columns
...)`; the count word is `column` when N is 1 (the line does not contain the word
"checksum", §3.2 step 4). JSON columns are logged the same way with `N JSON
column(s) [...]`. The default skip does not change for either type.

The driver relays a floating point line, like every side WARNING, at **INFO** as
a side note with the same count and names (spec 13.06 §3.17). The scheduled job
fails on any WARNING line, and a table whose values match must pass, so a float
column left out by default is named at INFO, per side and in the run summary
below, and never fails that scan. A relay at WARNING was tried and removed: it
failed every clean run of the production job in the end-to-end suite.

A run-level summary follows: after the per-table lines, the driver logs one INFO
line `NOT COMPARED (named, left out of the verdicts by default): N column(s) in T
table(s): ...` that names every hole found in the run, with the flag that
compares it (the side line text is unchanged by the summary). A table whose only
difference lies in a named hole reports MATCH by design, and the INFO summary is
the operator's record of that. Compared by default: every type in the §3.3 table,
§3.4 temporal, §3.6 binary, `Bool`/`bit(1)`. Not compared by default:

- **Floating point** (`float`, `double` / `Float32`, `Float64`), unless
  `--include_floating_point_columns` is passed to both sides. The opt-in
  compares each engine's text rendering (`col` / `toString(col)`), which
  agree for values both engines print in positional notation because both
  print the shortest round-trip digits; they are **not guaranteed to agree in
  exponent notation** (MySQL prints `1e15`, ClickHouse `1000000000000000`),
  so an opt-in DIFFERENT on a float column needs a value-level look before
  it is called a divergence. A rendering that is provably identical across
  the two engines (fixed 17-significant-digit text) is not available in
  MySQL SQL; this remains a **known gap**.
- **JSON** (`json` / native `JSON`, `Object('json')`, and the `String`
  columns named in `--json_columns`, which the driver derives from
  `information_schema` — the replica catalog cannot tell a JSON text column
  from any other `String`), unless `--include_json_columns` is passed to both
  sides. `--include_json_columns` used to be a `store_true` flag with
  `default=True`, i.e. a no-op that always included JSON while the two sides
  normalised differently: MySQL applied eleven regular expressions to
  `json_pretty(col)` to approximate the compact text Debezium writes, the
  replica compared the stored text as is. That one-sided normalisation strips
  `.0` from numbers and collapses whitespace, so `{"a": 1.0}` against
  `{"a":1}` compared EQUAL — a masked difference — while any layout the
  regexes did not anticipate compared DIFFERENT. The opt-in keeps that
  best-effort normalisation (spec 07.04 notes JSON as an open gap) and is
  documented as such; the default is exclusion with the WARNING.
  The eleven regular expressions must reach the regex engine intact: MySQL
  unescapes a string literal BEFORE the pattern is compiled, so every `\`
  in a pattern or replacement is written doubled in the SQL text (`\\s`,
  `\\d`, `\\.0\\b`, `\\\\u` for a literal backslash-u). Sent single, MySQL 8
  rejects the expression outright — error 3696 "unclosed bracket expression"
  for `\[` and error 3689 "unrecognized escape sequence" for `\u` — so the
  opt-in could never run. Verified against MySQL 8.0: the doubled form renders
  `{"a": 1.0, "e": -2.50}` as `{"a":1,"e":-2.5}`.
  (`TestFloatAndJsonCoverage::test_mysql_json_normalisation_keeps_its_regex_escaping`)

(`test_checksum_fidelity.py::TestFloatAndJsonCoverage`)

- **Replica-only columns** (§3.3, column set; parity scope, Invariant I6,
  `specs/CONSTITUTION.md`): a replica column the source table does not have
  is not compared, and it is **tolerated**, not reported as a divergence.
  Someone may add a column on the destination that the connector does not
  manage; parity is required only for columns that match a source column and
  for tables the connector creates and manages, so this is expected, not a
  schema-drift finding to chase. The replica side logs `Replica-only columns
  in table <db>.<table>: [...] (present on the ClickHouse destination,
  absent from the source table; not compared)` at INFO, once per table. The
  driver parses that line (`record_replica_only()`, `REPLICA_ONLY_RE`)
  instead of relaying it as a plain side note, and — regardless of
  `--allow_replica_only_columns` — always logs it at INFO as tolerated, once
  per (replica host, table), plus an end-of-run INFO summary naming every
  table with such columns (`report_run_summary()`). Neither ever logs a
  WARNING or changes the exit code. `--allow_replica_only_columns` is still
  accepted (Invariant I11: no flag is removed) but is a no-op: it matches the
  default and has nothing left to opt into. The table's verdict (MATCH /
  DIFFERENT) is about the replicated columns only, so a replica-only column
  never produces a `Checksum difference` that would send someone looking for
  a data divergence that does not exist. A column named in the job's
  `ignored_columns` is excluded before this check and is neither compared nor
  reported — that is the per-column, reviewed exclusion, independent of
  tolerance. Findings are per run (`run_config()` clears them). A source
  column the replica lacks is the opposite case, is within parity scope, and
  still stays a real difference (`Source columns missing in table
  <db>.<table> on the replica: [...]`, then `Checksum difference`).
  (`test_checksum_fidelity.py::TestReplicaOnlyColumns`,
  `test_checksum_verdicts.py::TestSourceColumnSetReachesTheReplicaSide`,
  `test_checksum_job_log_contract.py::TestScheduledJobLogContract`)

A **table** the driver could not lock within `--lock_wait_timeout` (§3.2
step 2) is a coverage gap of the same kind, one level up: it is neither
confirmed equal nor reported different, only **not compared**. It is named
twice — once when it is skipped and once in the end-of-run summary — and the
run **exits 1** unless `--allow_coverage_gap` accepts the gap, so a
green exit never hides an unverified table. `--fail_on_lock_timeout` aborts
at the timeout instead. (`test_checksum_failure_modes.py::TestRunExitCode`,
`test_coverage_gap_exit.py`)

The exit rules of the run, applied by `report_run_summary()`:

| Outcome | Default | Opt-in flag |
|---|---|---|
| a side failed or was unparseable (ERROR) | exit 1 | none |
| a coverage gap (lock timeout, not compared) | exit 1 | `--allow_coverage_gap` accepts it (exit 0, still named) |
| a checksum difference (DIFFERENT) | exit 0, named at INFO | `--fail_on_different` exit 1 |
| EMPTY on both sides (0 rows compared) | exit 0, named at INFO | `--fail_on_empty` exit 1 |

EMPTY keeps its documented default: an empty partition is normal in
date-partitioned runs. Whether a run that compared zero rows overall should
also fail by default is an open decision for the maintainers; the default
stays as documented until that decision is made.

### 3.10 Removed dead paths
- The replica script executed `CREATE FUNCTION IF NOT EXISTS format_decimal
  ...` on every run. Nothing called it (decimals render with the built-in
  `toDecimalString`, §3.3) and it required the `CREATE FUNCTION` privilege
  for a read-only job; it is gone.
- The replica script ran `select count(*) from <table>` before the checksum
  and, "when the count was 0", printed `d41d8cd98f00b204e9800998ecf8427e`
  (`md5('')`) with count 0. The branch could never run — `execute_sql`
  returns the number of result rows, and a `count(*)` always returns one —
  and its value would not have matched the source's empty-table value
  `md5('0#0#0#0#0#')` (§3.5). The query is gone; the
  `{partition_expression}` substitution it carried remains.
- `--exclude_columns` takes `nargs='+'` on both sides (the replica side had
  `nargs='*'`, so a bare `--exclude_columns` silently replaced the default
  list of connector metadata columns with nothing). Both sides accept
  space-separated words and comma-separated lists alike
  (`parse_exclude_columns`, spec 13.06 D-13.06-17).
  (`test_checksum_fidelity.py::TestRemovedDeadPaths`)

### 3.11 User WHERE composition
The operator's `--where` text is one predicate written by the operator, and a
per-table `where` override is operator text too. The tools combine both with
other predicates: the sign filter that skips deleted rows (§3.8), the
primary-key chunk bounds (`and <pk> between L and U`), the `--partition_date`
predicate, the consistent-snapshot exclusion and slice bounds, and the
per-replica extra filter.

SQL binds `AND` tighter than `OR`. Text that contains a top-level `OR` and is
joined bare to another predicate gives that predicate to the last branch only.
`1=1 and a = 'x' or b = 'zz' and sign > 0` keeps every row with `a = 'x'`,
deleted or not, and the sign filter and the chunk bounds never reach that
branch: a checksum over that text counts the wrong rows, and every chunk of a
chunked run counts all the rows with `a = 'x'` again.

Rule: whenever the user text is combined with another predicate, it is emitted
as `(<user text>)` before the `and`, so it is one operand. A text that is the
only predicate of a statement needs no parentheses. The rule holds on the
source side and on the replica side, on every path:
- the MySQL checksum: the single-thread builder (unchunked and chunked), the
  chunk divider's row-count and bounds queries, and the consistent-snapshot
  filter;
- the ClickHouse checksum: the builder that appends the sign filter;
- the top-level driver: the MySQL and ClickHouse command builders, the
  per-slice filter, and the table override joined to the user text;
- the standalone ClickHouse count tool, which appends the text to its base
  predicate;
- the PostgreSQL checksum: the tier-1 and tier-2 chunk queries.

With the rule, `--where "a = 'x' or b = 'zz'"` reaches both sides as
`(a = 'x' or b = 'zz')` followed by the sign filter or the chunk bounds, so
each statement and each chunk counts exactly the rows the operator asked for.
The rule is checked by the builders' SQL in
`sink-connector/python/db_compare/tests/test_where_parenthesised.py`.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)**: the tool is the empirical witness of
  convergence beyond row counts.
- **Invariant I7 (Value-Level Type Equivalence)**: the per-type rendering
  table is the operational definition of "the same value" for each type pair.
- **Invariant I9 (Loud Failure)**: a mismatch is a `WARNING` naming both
  checksums; a column the tool cannot compare is named, never silently
  dropped.

---

## 5. Verification Criteria
All tests run offline: `execute_sql` / `execute_mysql` and the connection
helpers are replaced with stubs that return catalog rows and the aggregate a
real engine would return for a fixture of canonical row strings. They never
connect to a database.

- `sink-connector/python/db_compare/tests/test_checksum_fidelity.py`
  - `TestClickHouseRowExpression.test_trailing_float_column_leaves_no_dangling_separator`
    — §3.3: with `execute_sql` stubbed to describe `(id Int32, name String,
    f Float64)`, the values part of the built expression is exactly
    `toString("id")||'#'||toString("name")`, followed only by the null-flags
    element over `id` and `name`; the pre-fix code produced
    `toString("id")||'#'||toString("name")||'#'`.
  - `TestClickHouseRowExpression.test_nullable_flags_are_one_trailing_element`
    — §3.3 nullability element.
  - `TestNullFlagsOverEveryComparedColumn` — §3.3: one null flag per compared
    column on both sides, the same columns whatever each catalog declares
    nullable; excluded and skipped columns flag nothing.
  - `TestReplicaOnlyColumns` — §3.3 column set and §3.9: with
    `--source_columns id,user`, a replica table `(id, user, name)` builds
    exactly the expression of the replica table `(id, user)` and logs one
    `Replica-only columns in table db1.t1: ['name']` INFO line per table that
    does not contain the word "WARNING" or "checksum" and that the driver's
    `REPLICA_ONLY_RE` parses; without the option `name` is compared; names match
    without regard to case; a source column the replica lacks still logs a
    WARNING and is named; excluded and connector columns are neither compared
    nor reported; the driver passes the option to the replica side only.
    Mutation-checked: keeping every column fails the first and fourth tests,
    dropping the option from the replica command fails the driver test and
    `test_checksum_verdicts.py::TestSourceColumnSetReachesTheReplicaSide`.
- `sink-connector/python/db_compare/tests/test_checksum_verdicts.py`
  - `TestSourceColumnSetReachesTheReplicaSide` — §3.3 column set and §3.9
    through the real `run_config()`: every replica command carries
    `--source_columns` with the source table's columns, the source command
    does not; a replica-only column is tolerated by default — logged once at
    INFO as accepted, no WARNING anywhere, exit 0, while the table's own
    verdict stays `No difference`; passing `--allow_replica_only_columns`
    changes nothing (it is a no-op); a later run does not inherit earlier
    findings; with no source columns known no command carries the option.
- `sink-connector/python/db_compare/tests/test_checksum_job_log_contract.py`
  - `TestScheduledJobLogContract` (replica-only cases) — §3.9 through `main()`
    with the scheduled job's own flags and real side processes: a
    replica-only column keeps the job green (exit 0, no line containing
    "WARNING") both by default and with `--allow_replica_only_columns`, and
    logs the tolerated finding at INFO either way.
- `sink-connector/python/db_compare/tests/test_checksum_fidelity.py` (continued)
  - `TestEndToEndChecksum.test_equal_fixtures_report_equal` — §3.5: both side
    scripts, driven through their real `compute_checksum` /
    `calculate_checksum` paths with stubbed engines, print the same checksum
    and count for identical fixtures (including the §3.3 fixture whose last
    column is a skipped float).
  - `TestEndToEndChecksum.test_flipped_clickhouse_value_reports_different` —
    §3.5 negative test: flipping one character of one row on the ClickHouse
    side changes the printed checksum while the count stays equal, and
    `analyze_differences()` logs `Checksum difference`.
  - `TestChecksumFromAggregate.test_is_md5_of_hash_separated_values` — §3.5
    step 3, including the empty-table value.
  - `TestMySQLColumnClassification` — §3.3 classification on `DATA_TYPE`:
    `enum('float','json','blob','bit','time')` and `set('double','binary')`
    are compared as plain strings; real `double`/`blob`/`json`/`time`
    columns are still skipped, hex-encoded, normalised and cast.
  - `TestMySQLTemporalRendering.test_time_is_rendered_with_six_fraction_digits_for_every_precision`
    — §3.3 `time(p)` row.
  - `TestSharedDatetimeClamp` — §3.4: the bounds are the `DataTypeRange`
    constants; user bounds are canonicalised and confined; both dialects use
    `>= max` / `< min`; the flag counts strictly-outside values.
  - `TestSaturatedLastDay` — §3.4 saturated last day: `saturation_floor()`
    is `2299-12-31 00:00:00` in UTC, shifted into `Asia/Tokyo` and
    `America/Chicago`, and capped at a shifted user bound; evaluated with
    sqlite3 (whose CASE and text comparison match both dialects), the
    out-of-range MySQL values and every replica representation of the last
    day (`00:00:00`, `05:59:00`, `23:59:59`, `23:59:59.999999`) render as the
    upper bound in both dialects and are counted as clamped unless they are
    the bound itself; the day before renders unchanged; without a floor the
    bulk-loaded `00:00:00` stays itself; both row builders carry the same
    floor.
  - `TestDatetimeRendering` — §3.4: `datetime`/`timestamp` at precisions 0,
    3, 6 and `DateTime`, `DateTime64(0)`, `DateTime64(3)`,
    `DateTime64(6,'UTC')`, `Nullable(DateTime64(3))` all render through the
    six-digit form inside the shared clamp; no `TRIM`/`substr` remains; a
    user bound lands identically on both sides.
  - `TestInstantComparison` — §3.4 instants: the source session sets
    `time_zone = '+00:00'`; `shift_datetime_bounds()` is the identity for
    UTC, shifts into `Asia/Tokyo`, and refuses a non-IANA name; MySQL
    `timestamp` clamps with the UTC bounds and `datetime` with the shifted
    bounds; ClickHouse renders `--timestamp_columns` in `UTC` and the other
    datetime columns in `--source_timezone` with matching bounds, and has no
    zone default; the driver passes
    `--source_timezone` to both scripts and `--timestamp_columns` to the
    replica script; the driver resolves the zone from the flag only, never
    queries `@@session.time_zone` or `@@system_time_zone`, refuses a run without
    the flag, and refuses an abbreviation. The side scripts refuse a run without
    `--source_timezone` (`TestNoFlagIsRefused` in
    `db_compare/tests/test_explicit_source_zone.py`).
  - `TestBooleanAndBit` — §3.3: `Bool` and `Nullable(Bool)` render through
    `toUInt8`; MySQL `bit(1)` renders as `col+0` while `bit(8)` stays hex.
  - `TestBinaryEncoding` — §3.6: MySQL renders hex by default, base64 on
    request and hex in `raw` mode; ClickHouse hexes the listed `String`
    columns only in `raw` mode, leaves `Bool` alone and refuses
    `--hex_columns` with `hex`/`base64`; the driver passes the encoding to
    both scripts and the raw column list only in `raw` mode.
  - `TestFinalAcrossPartitions` — §3.7: the setting is present only when
    every partition-key column is a sorting-key column; absent when the
    partition key is not in the sorting key or the table is unpartitioned;
    `max_memory_usage` still forms a well-formed `settings` clause.
  - `TestSignColumn` — §3.8: `sign_column_from_engine()` for Replacing,
    Collapsing, VersionedCollapsing, ReplicatedCollapsing and plain MergeTree;
    the default run adds no filter on a `ReplacingMergeTree(_version,
    is_deleted)` table, adds `sign > 0` on a Collapsing table, and an
    explicit `--sign_column` is used without consulting the engine.
  - `TestFloatAndJsonCoverage` — §3.9: by default both sides skip float and
    JSON columns (MySQL by `DATA_TYPE`; ClickHouse by `Float*`, `JSON`,
    `Object('json')` and `--json_columns`) and log one `Not compared` line per
    table and kind naming them (the driver relays a float line and a JSON line
    as INFO side notes; a side run on its own logs both at WARNING); the opt-in flags include them; the driver passes
    `--json_columns` and the two include flags through.
  - `TestClampedRowCounts` — §3.4: both aggregate queries carry
    `coalesce(sum(clamped),0)`; a table without datetime columns contributes
    `0`; a non-zero count is logged as a WARNING that does not change the
    checksum and does not contain the word "checksum".
- `sink-connector/python/db_compare/tests/test_table_locking.py` — §3.2 lock
  lifecycle (lock before, unlock after both sides, connection closed on
  failure, no lock when disabled; `lock_tables()` is called with the
  `lock_wait_timeout` keyword).
- `sink-connector/python/db_compare/tests/test_bounded_source_lock.py` —
  §3.2 step 2 bounded lock:
  - `TestIsLockWaitTimeout` — errno 1205 is recognised from the structured
    code of a raw driver error and of a SQLAlchemy-wrapped `.orig`, and from
    the exact message; errno 1146 on `prod.trades_1205`, errno 1142 with
    `orders_1205` in the SQL text, and a bare message mentioning `1205` are
    **not** classified as a timeout.
  - `TestLockTables` — `SET SESSION lock_wait_timeout = N` is issued before
    `LOCK TABLES` and not at all when no timeout is given; a timeout raises
    `LockAcquisitionError`; any other error propagates unchanged.
  - `TestCliContract` — `--help` names `--lock_wait_timeout` and
    `--fail_on_lock_timeout`, and no inverted `--allow_lock_timeout_skip`
    exists, so the documented default (skip-and-warn) cannot drift from the
    code.
- `sink-connector/python/db_compare/tests/mysql_table_checksum_test.py` —
  `{partition_expression}` substitution is literal, never `eval`.
- `test_checksum_fidelity.py::TestRemovedDeadPaths` — §3.10: no
  `CREATE FUNCTION` statement in the replica script, no count pre-check
  before the aggregate, `--exclude_columns` is `nargs='+'` on both sides.

---

## 6. Known structural gaps (not fixed; stated so they are not mistaken for guarantees)
1. **No length prefix in the row string.** Values are joined with `#` and
   not escaped, so a value containing `#` shifts the boundaries: the rows
   `('a#b', 'c')` and `('a', 'b#c')` hash identically, on both sides. A
   divergence that moves a `#` between two adjacent text columns is
   therefore invisible. The fix is a per-value length prefix (or escaping)
   applied identically on both sides; it changes every historical checksum.
2. **Word sums can wrap.** `a..d` are sums of 32-bit words in signed 64-bit
   accumulators; past about 2^31 rows a sum may exceed 2^63 and wrap
   (two's complement in both engines, so the two sides still agree, but the
   sum is no longer injective over the multiset of words).
3. **MySQL session-variable accumulation.** The source side accumulates
   with `@a := @a + ...` in a `SELECT` list, whose evaluation order MySQL
   documents as undefined (and the syntax is deprecated in 8.0). It works
   because each row's terms are independent and only the total is used, but
   a future MySQL may refuse the syntax; the replacement is a plain
   `sum(conv(substring(md5(...), ...)))` without variables.
4. **Positional hashing.** Columns are hashed in ordinal position without
   their names, so a table whose columns are reordered on one side hashes
   differently for equal values (noise), and two same-typed columns whose
   values are swapped in every row hash identically to the swapped table
   (masked). A per-column name prefix would remove both.
5. **Driver partition filter.** With `--partition_date` the driver
   substitutes `{partition_expression}=YYYYMMDD` on the source and
   `{partition_expression}=toDate('YYYY-MM-DD')` on the replica
   (`get_mysql_checksum_command()` / `get_clickhouse_checksum_command()`).
   Both are correct only when the MySQL partition expression yields a
   `YYYYMMDD` integer and the ClickHouse partition key is a `Date`; any
   other pair compares different subsets.
6. **Floating point and JSON opt-ins** are best-effort text comparisons
   (§3.9).

---

## 7. Failure Modes & Recovery
`db_compare` is read-only on both engines: none of its failures can change replicated data (the one side effect is the optional source `LOCK TABLES ... READ`), and every run can be repeated. Its recovery posture is "re-run, narrower if needed". The failures that matter are the ones that make it lie: EQUAL while the values differ, or a green exit while it reported a difference. Those are listed first.

- **FM-11.02-1 A checksum difference ends the run with exit code 0 (default)**
  - **Trigger**: any table whose replica `(md5, count)` differs from the source.
  - **Behaviour**: `analyze_differences()` only logs `WARNING Checksum difference : <replica> to <source>`. Without `--fail_on_different` `report_run_summary()` keeps exit 0 whatever was found (`top_level_table_checksum.py`). With `--fail_on_different` the run exits 1 and names every DIFFERENT table.
  - **Detection**: the log line `Checksum difference :`, printed as soon as that table's checksums return. With the default flags the exit code says nothing.
  - **Blast radius**: a scheduler or CI job that trusts the exit code and does not pass `--fail_on_different` reports a diverged replica as verified. The divergence itself keeps growing until someone reads the log.
  - **Recovery**: grep every run's output for `Checksum difference`, or pass `--fail_on_different` in the job. Then repair the table (spec 11.04) and re-run the checksum for it (`--tables_regex '^<table>$'`).
  - **RTO**: unbounded while the log goes unread. The repair is the spec 11.04 RTO.
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestRunExitCode::test_a_difference_fails_the_run_when_asked` (opt-in), `...::test_a_difference_is_exit_zero_by_default` (documented default), and `test_coverage_gap_exit.py::TestDifferentIsOptIn`. The original skipped test `...::test_a_difference_makes_the_run_exit_non_zero` stays skipped by design.
  - **STATUS**: opt-in fix. The default stays exit 0 until the jobs that schedule the tool pass `--fail_on_different`; this is a human change in the job definitions, listed in the release notes for this change.

- **FM-11.02-2 Two unparseable side outputs are reported as "No difference"**
  - **Trigger**: a database or table whose name contains `checksum` (any case). Each side script logs to stdout, and the driver keeps every line matching `grep -i checksum` (§3.2 step 4). The replica script always logs `REGEX QUERY: ... match(name,'^<table>$')`. Both sides log `Not compared in table <db>.<table>: ...` when a float or JSON column is skipped, and the clamp WARNING of §3.4 when values are clamped. So the pipeline hands back two lines instead of one.
  - **Behaviour**: `parse_checksum()` logs `Invalid checksum output from ... for table ...` and returns `(table, None, None)`. When both sides are malformed, `analyze_differences()` compares `(None, None)` with `(None, None)` and logs `No difference for <table>`. When only one side is malformed it reports a difference (noise).
  - **Detection**: an ERROR `Invalid checksum output` per malformed side, next to an INFO `No difference`. Nothing fails.
  - **Blast radius**: that table is reported equal without having been compared.
  - **Recovery**: treat any `Invalid checksum output` as a failed run. Compare the table standalone: run the two side scripts by hand and read their `Checksum for table` lines.
  - **RTO**: one standalone run per affected table.
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestVerdictOnUnparseableOutput::test_both_sides_unparseable_is_never_reported_equal`. The refusal of multi-line output is pinned by `...::test_parse_checksum_refuses_more_than_one_line`.
  - **FIXED**: an unparseable side is verdict `ERROR` (exit 1), and the output is parsed per line so other lines naming "checksum" are ignored (spec 13.06 D-13.06-2).

- **FM-11.02-3 One side script fails: that table has no verdict and the run exits 1**
  - **Trigger**: the side script exits non-zero, or exits 0 without a checksum line. Causes include a refused connection, `MEMORY_LIMIT_EXCEEDED` on the `FINAL` read, a table missing on the replica (no checksum line), or a bad `--where`.
  - **Behaviour**: `run_quick_safe_checksum()` logs `<argv>. failed with return code <rc>` and the last 20 side lines, then returns `None` (or a result with `None` md5 and count for a missing checksum line). `analyze_differences()` logs `Checksum ERROR for <db.t>: ...; no verdict`, the remaining tables are still compared, and the run ends with `<n> table(s) have NO verdict ...` and exit 1 (spec 13.06 §3.8).
  - **Detection**: loud: the ERROR names the table, and the side's own error lines are in the driver log.
  - **Blast radius**: no false verdict. Only the failed tables are unverified.
  - **Recovery**: fix the cause, or exclude the table with `--exclude_tables_regex`, then re-run the tables named in the summary (`--tables_regex`).
  - **RTO**: time to re-run the failed tables (unmeasured — proportional to their size).
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestRunExitCode::test_failed_side_script_fails_the_table_and_the_run_non_zero`.

- **FM-11.02-4 A huge table: no time bound, and the source lock is held throughout**
  - **Trigger**: a multi-hundred-GB table, or a slow replica under `FINAL` over a whole table.
  - **Behaviour**: `run_quick_safe_command()` calls `Popen.communicate()` with no timeout. The replica query carries only `max_memory_usage` (the driver passes 80 GB), with no `max_execution_time`. The MySQL session sets `wait_timeout=28000` and nothing else. With `--lock_tables_on_source` the `READ` lock is held from `LOCK TABLES` until the slowest side returns (`compute_checksum()`), so every writer of that table on that MySQL server waits for the whole duration.
  - **Detection**: none from the tool. The source shows sessions waiting on the table lock (`SHOW PROCESSLIST`).
  - **Blast radius**: if run against a primary, application writes to that table stall for hours. If run against a replica, its applier stalls and it lags. The connector itself is not affected.
  - **Recovery**: kill the driver. MySQL releases table locks when the session ends (MySQL server behaviour, not verified here). Then checksum the table in bounded slices: `--partition_date YYYY-MM-DD` (§6 item 5 limits), or a per-table `where` override in the YAML (`tables: - db.t: where: "id between A and B"`) repeated per range. `--threads_per_table N` parallelises only the MySQL side (by integer primary key).
  - **RTO**: unmeasured. Proportional to table size on both engines. One slice is bounded by its row range.
  - **Test**: GAP: a driver test asserting that a per-table timeout is passed to both side scripts and that the lock is released when it expires.
  - **DEFECT**: neither the run time nor the source-lock hold time is bounded.

- **FM-11.02-5 EQUAL although values differ (structural blind spots)**
  - **Trigger**: a divergence that the row string of §3.3 cannot see. That is a `#` moved between adjacent text values (§6 item 1), two same-typed columns swapped in every row (§6 item 4), word sums past about 2^31 rows (§6 item 2), float and JSON columns (excluded by default), or datetimes outside the clamp range or inside its saturated last day (§3.4).
  - **Behaviour**: the checksums match.
  - **Detection**: floating point columns give one `WARNING Not compared in table ...` per table and side; JSON columns give the same line as an INFO side note (the driver relays it at INFO). Clamping gives `WARNING <n> out-of-range datetime values clamped ...`. The `#` shift and the column swap give none.
  - **Blast radius**: a real divergence is certified equal.
  - **Recovery**: for suspected tables, compare the affected columns value by value (`SELECT` both sides by primary key). Include floats with `--include_floating_point_columns` and inspect any difference by value (§3.9).
  - **RTO**: unbounded for the silent cases.
  - **Test**: GAP: a fixture pair `('a#b','c')` / `('a','b#c')` that must checksum differently. It fails today by design of the row string.
  - **DEFECT**: the `#`-shift and column-swap divergences are silent (§6 items 1 and 4).

- **FM-11.02-6 Replication in flight: a difference that is only lag**
  - **Trigger**: a run without `--lock_tables_on_source` on a written table, or with it but with replication lag above `--sleep_after_lock` (default 3 s).
  - **Behaviour**: the two sides read different moments and report `Checksum difference`. This errs on the safe side: a lag is never reported as equality unless the moments happen to agree.
  - **Detection**: `WARNING Checksum difference`, indistinguishable from a real one.
  - **Blast radius**: noise only. No data changes.
  - **Recovery**: re-run the table with `--lock_tables_on_source --sleep_after_lock <S>`, where `S` is above the connector's current `seconds_behind_source` (spec 10.03 view).
  - **RTO**: one re-run of that table.
  - **Test**: `sink-connector/python/db_compare/tests/test_table_locking.py` (lock before both sides, unlock after, connection closed on failure).

- **FM-11.02-7 The source lock cannot be acquired**
  - **Trigger**: a continuously written table never gets its metadata lock within `--lock_wait_timeout` (default 30 s).
  - **Behaviour**: `lock_tables()` raises `LockAcquisitionError`, recognised only from errno 1205. `run_config()` logs `COVERAGE GAP -- skipping checksum for <db>.<table>: source lock could not be acquired` and continues. At the end `report_run_summary()` logs `ERROR COVERAGE GAP: <n> table(s) were NOT compared ... the run fails: <names>` and the run exits 1. `--allow_coverage_gap` accepts the gap (exit 0, still named); `--fail_on_lock_timeout` aborts the run at the timeout.
  - **Detection**: the skip line and the end-of-run COVERAGE GAP line, within `--lock_wait_timeout` seconds per table; the exit code is 1 by default.
  - **Blast radius**: that table is unverified for this run, and the run fails so the job shows it. Nothing else is affected.
  - **Recovery**: re-run that table at a quieter time, or with a longer `--lock_wait_timeout`.
  - **RTO**: `--lock_wait_timeout` per attempt, plus one table re-run.
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestRunExitCode::test_lock_timeout_skips_the_table_and_names_it_in_the_summary` (exit 1 by default), `...::test_lock_timeout_gap_is_accepted_with_allow_coverage_gap`, `...::test_lock_timeout_gap_in_an_earlier_database_still_fails_the_run`, `...::test_lock_timeout_fails_the_run_when_asked`, `test_coverage_gap_exit.py::TestCoverageGap`, and `test_bounded_source_lock.py::TestLockTables`.

- **FM-11.02-8 The driver is killed half way**
  - **Trigger**: Ctrl-C, `kill -9`, or the host rebooting during a run.
  - **Behaviour**: Ctrl-C reaches `except (KeyboardInterrupt, SystemExit)`, which logs `Received interrupt` and calls `os._exit(1)`. `kill -9` stops it without a word. The side scripts run as `shell=True` children and may keep running until their query ends. They only read. Held table locks go away with the lock connection's session (MySQL server behaviour, not verified here).
  - **Detection**: non-zero exit, or no final `Exiting Main Thread` in the log.
  - **Blast radius**: no data risk. There is no resume state.
  - **Recovery**: re-run with `--tables_regex` restricted to the tables that have no `No difference for` / `Checksum difference` line in the interrupted log. Check the source for orphaned checksum sessions (`SHOW PROCESSLIST`) and kill them if they hold resources.
  - **RTO**: re-run of the remaining tables (unmeasured).
  - **Test**: GAP: a driver test that interrupts `compute_checksum` and asserts the lock connection is closed.

- **FM-11.02-9 A replication-history (SCD2) table cannot be verified by the driver**
  - **Trigger**: a mode-2 connector (spec 12.01). Its tables hold one row per version plus `_valid_from`, `_valid_to` and `_operation`.
  - **Behaviour**: the driver applies the same `--where` to both sides, and its replica exclusion list is `_version,is_deleted,_is_deleted,__is_deleted`. It therefore compares every closed version against the current MySQL rows, and always reports a difference. (Since FM-11.02-10 the history columns, which the source table does not have, are out of parity scope: they are left out and reported as tolerated replica-only columns at INFO, no flag needed; the closed versions still differ.)
  - **Detection**: `WARNING Checksum difference` on every history table. This is noise, never a masked equality.
  - **Blast radius**: mode-2 replicas have no value-level proof through the driver.
  - **Recovery**: run the side scripts standalone. On the source, run `mysql_table_checksum.py` with `--tables_regex '^t$'`. On the replica, run `clickhouse_table_checksum.py --clickhouse_database <history db> --tables_regex '^t$' --where "_valid_to = toDateTime(4102444800)" --exclude_columns _sign _version is_deleted _is_deleted _valid_from _valid_to _operation`, then compare the two `Checksum for table` lines (spec 12.02 §4). `FINAL` already drops the delete markers.
  - **RTO**: one standalone pair of runs per table.
  - **Test**: GAP: a replica-script test asserting the open-row filter and the history-column exclusions produce the source's checksum for a fixture with closed versions.
  - **DEFECT**: the driver cannot verify a replication-history replica.

- **FM-11.02-10 A column only one side has**
  - **Trigger**: a column added to the replica table alone (replica-only), or a source column the replica table lacks (a missed `ADD COLUMN`).
  - **Behaviour**: replica-only — out of parity scope (Invariant I6, `specs/CONSTITUTION.md`), left out of the replica row string; the verdict reflects the replicated columns only. The driver always logs it at INFO as tolerated, never a WARNING, never affecting the exit code; `--allow_replica_only_columns` is accepted (Invariant I11) but is a no-op (§3.3, §3.9). Missing on the replica — within parity scope, a real difference: the source side hashes it, the replica cannot, so the table reports `Checksum difference`, and the replica side names the missing column with a WARNING.
  - **Detection**: `Source columns missing in table <db>.<table> on the replica: [...]` WARNING in the driver log; the replica-only case is an INFO line, by design, since it is not a divergence.
  - **Blast radius**: replica-only — the replica schema no longer conforms to the source for that one column; the column's content cannot be judged (no source value exists), but nothing worse happens: the connector's own write path (spec 04.03 FM-04.03-4) already excludes such a column from every generated INSERT at both the query-formatting and bind-time layers, so it is never NULL-filled, never warned about, and never ALTERed by the connector — the column simply keeps whatever value it already holds. A missing column is a real replication gap on every row.
  - **Recovery**: replica-only — none required; it is tolerated. A column the owner wants removed is dropped directly on the replica (no connector action), or added to the source table to bring it into parity scope. Missing — add the column on the replica and backfill it from the source (spec 11.04), then re-run.
  - **RTO**: replica-only: not applicable (no action required). Missing: the spec 11.04 RTO.
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_fidelity.py::TestReplicaOnlyColumns`, `sink-connector/python/db_compare/tests/test_checksum_verdicts.py::TestSourceColumnSetReachesTheReplicaSide`, `sink-connector/python/db_compare/tests/test_checksum_job_log_contract.py::TestScheduledJobLogContract`; Java side: spec 04.03 FM-04.03-4.

- **FM-11.02-11 A zone name is refused although the zone exists (tzdata missing on the tool host)**
  - **Trigger**: the Python on the host has no IANA database (no system `tzdata`, no `tzdata` package), so `zoneinfo` cannot load any region, including `UTC`.
  - **Detection**: the run stops before any table with `cannot check '<zone>': the IANA time zone database (tzdata) is not available`. It names the cause, not a bad zone name.
  - **Recovery**: install the system `tzdata` package or `pip install tzdata` in the tool's environment, then re-run. Nothing was compared, so there is nothing to repair on either engine.
  - **RTO**: one package install plus one re-run of the tool.
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_timezone.py::TestZoneNameRules::test_missing_tzdata_is_named_as_such_not_as_an_unknown_zone`.

- **FM-11.02-12 A zone spelling the connector cannot resolve reaches the tool**
  - **Trigger**: `--source_timezone` is an abbreviation or legacy zone (`CST`, `EST`, `MST`, `HST`, `CST6CDT`, `EST5EDT`), an `Etc/` name other than `Etc/UTC`, a `SystemV/`, `posix/` or `right/` name, blank, `SYSTEM` or an offset. Python's `zoneinfo` and ClickHouse may accept some of these, so a run could compare a zone the connector never used.
  - **Detection**: the tool exits before any comparison with an error naming the option and the refused value. A run that previously passed with one of these values now fails. That is the intended refusal, because the connector cannot resolve these names either; the affected invocations are the ones whose effective zone is one of the refused names (§3.2 step 1 lists them).
  - **Recovery**: set the MySQL session zone (or the connector's `database.connectionTimeZone`) to a region name, or pass `--source_timezone <Area/Location>` that matches the connector, then re-run.
  - **RTO**: one configuration change plus one re-run of the checksum job.
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_timezone.py::TestZoneNameRules`.

- **FM-11.02-13 A checksum run without `--source_timezone` compares DATETIME in a guessed zone**
  - **Trigger**: any checksum tool (top-level driver, `mysql_table_checksum.py`, `clickhouse_table_checksum.py`, or a wrapper) is run with no `--source_timezone`. Before this rule the top-level driver read the MySQL session zone and the side scripts assumed UTC; on a connector whose `database.connectionTimeZone` is not UTC, DATETIME columns then compared wrong.
  - **Behaviour**: refused before any connection is opened. The parser exits with status 2 and an error that names `--source_timezone`, suggests a region (for example `America/Chicago`) and states that `--source_timezone UTC` must be passed explicitly when the connector used UTC. No server zone is queried and no UTC is assumed.
  - **Detection**: the refusal message and the non-zero exit status; no table is compared.
  - **Blast radius**: none on data; a scheduled run without the flag fails until the flag is added.
  - **Recovery**: add `--source_timezone <connector zone>` (the value of the connector's `database.connectionTimeZone`) and re-run.
  - **RTO**: one configuration change plus one re-run.
  - **Test**: `sink-connector/python/db_compare/tests/test_explicit_source_zone.py::TestNoFlagIsRefused` and `::TestNoServerZoneQuery`.

- **FM-11.02-14 A DST gap or fold wall clock is compared as if it had one meaning (UNREPRESENTABLE-IN-ZONE)**
  - **Trigger**: a DATETIME value on the source falls in a spring-forward gap (it never existed on the clock) or a fall-back fold (it happened twice), in the connector's source zone.
  - **Behaviour**: ClickHouse cannot store a gap time as written (Chicago 2026-03-08 02:30 becomes 01:30), so the replica row cannot match the source text. The classifier (§3.4, `classify_wall_clock`) names the class; the aggregate checksum reports `DIFFERENT` for the table. A fold is ambiguous as text, so it is reported the same way.
  - **Detection**: the table's `Checksum difference` verdict; the classifier is a pure function that a row-level follow-up can call to name the class of each mismatched row. Today the class is not printed per row (§3.4 integration limit).
  - **Blast radius**: one table's verdict is DIFFERENT for a value that was never representable in the zone. No data is changed by the tool.
  - **Recovery**: the connector writes such values as the source's own text (spec 07.03); the owner decides whether the source row is correct and whether the replica value should be corrected. Nothing in the tool rewrites data.
  - **RTO**: the spec 11.04 backfill of the affected rows, when the owner decides a correction is needed.
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_timezone.py::TestWallClockClassifier`.

- **FM-11.02-15 A ClickHouse edge value renders or parses differently from the MySQL value (not fixed)**
  - **Trigger**: a DATETIME value near a ClickHouse 25.8 limit: a pre-1900 local wall in a west zone (text clamped to `1900-01-01 00:00:00`), a 2300-01-01 wall in Asia/Kolkata (parsed one day early), or a west-zone wall at 2299-12-31 23:59:59 (returned as a 1970 value).
  - **Behaviour**: ClickHouse computes a different text or epoch from zoneinfo for that value. The row cannot match its MySQL copy, so the table reports `DIFFERENT`. The tools do not mask it, and they do not change the bounds to hide it.
  - **Detection**: the table's `Checksum difference` verdict; the constants of `TestClickHouse258Probe` document which windows are affected.
  - **Blast radius**: the table's verdict only. Affected values are rare (pre-1900 or beyond-2037 west-zone walls); no data is changed by the tool.
  - **Recovery**: none in the tool. The owner decides whether the value is in scope (`--where` excludes it on both sides), or upgrades ClickHouse and re-runs the probe, which the constants test will flag when the behaviour changes.
  - **RTO**: one re-run after the ClickHouse upgrade or the `--where` change.
  - **Test**: `sink-connector/python/db_compare/tests/test_checksum_timezone.py::TestClickHouse258Probe`.

Summary: 15 failure modes. DEFECT label on FM-11.02-1, -4, -5, -9; GAP test label on FM-11.02-4, -5, -8, -9. FM-11.02-11 to -15 are later additions with unit tests (counted in the lines above, not in the DEFECT/GAP totals).
