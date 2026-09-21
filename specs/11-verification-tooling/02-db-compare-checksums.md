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
  `sink-connector/python/db_compare/tests/test_table_locking.py`.
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
   (`resolve_source_timezone()`): `--source_timezone` if given, else
   `@@session.time_zone`, falling back to `@@system_time_zone` when that is
   `SYSTEM`. The value must be an IANA name (a `CST`-style abbreviation or a
   `+05:00` offset is refused with an error naming the flag) and it must be
   the zone the connector's `database.connectionTimeZone` names, because it
   defines what instant a MySQL `DATETIME` denotes (§3.4). The zone is passed
   to both side scripts as `--source_timezone`, the column list to the
   replica script as `--timestamp_columns`.
2. If `--lock_tables_on_source` is set, opens a dedicated MySQL connection,
   runs `LOCK TABLES <table> READ` and sleeps `--sleep_after_lock` seconds so
   replication drains. **Only the driver locks.** Neither side script issues
   a lock; run standalone they compare whatever each engine holds at the
   moment of the query.
3. Runs `mysql_table_checksum.py` and one `clickhouse_table_checksum.py` per
   replica host **concurrently under the lock** (`compute_checksum()`), each
   restricted to the table with `--tables_regex "^<table>$"` and the same
   `--where` / `{partition_expression}` filter. The lock is released in a
   `finally` block; the lock connection is closed even if `UNLOCK TABLES`
   fails (`test_table_locking.py`).
4. Parses the single line `Checksum for table <db>.<table> = <md5> count <n>`
   from each process (`parse_checksum()`; the shell pipeline is
   `set -eo pipefail; ... | grep -i checksum | awk '{print $11" "$13" "$15}'`,
   so no other line printed by the side scripts may contain the word
   "checksum").
5. `analyze_differences()` compares each replica's `(md5, count)` with the
   source's. A mismatch is logged as `WARNING Checksum difference : ...`;
   agreement as `INFO No difference for <table>`.

### 3.3 Canonical row string
Both sides render every compared column to text and join the pieces with the
single character `#`:

- Columns are taken in ordinal position (`information_schema.columns
  .ordinal_position` / `system.columns.position`).
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
  concatenation, in column order, of `1` (NULL) or `0` (not NULL) for every
  nullable compared column (`concat(ISNULL(a), ISNULL(b))` /
  `case when a is null then '1' else '0' end || ...`). This keeps `NULL`
  distinguishable from `''`.
- Per-type rendering (both sides must produce byte-identical text for equal
  values):

| MySQL type (`DATA_TYPE`) | ClickHouse type | MySQL expression | ClickHouse expression |
|---|---|---|---|
| integers, `year`, `char`/`varchar`/`text`, `enum`, `set` | `Int*`/`UInt*`, `String`, `LowCardinality(String)` | the column (`convert(col using utf8mb4)` when the table mixes collations) | `toString(col)` |
| `decimal` | `Decimal(p,s)` | the column (MySQL prints the declared scale) | `toDecimalString(col, s)` (`numeric_scale` from `system.columns`; `toString` would drop trailing zeros) |
| `date` | `Date`/`Date32` | `case when col >= max then max when col <= min then min else col end` with `--min_date_value` / `--max_date_value` | `toString(col)` |
| `time(p)`, any `p` | `String` holding `[-]HH:MM:SS.ffffff` (spec 07.03 §3.2) | `cast(col as time(6))` — six fraction digits unconditionally; the old `substr(cast(col as time(6)),1,length(col))` truncated `time(0)` to `10:00:00` and reported DIFFERENT (`test_checksum_fidelity.py::TestMySQLTemporalRendering.test_time_is_rendered_with_six_fraction_digits_for_every_precision`) | `toString(col)` |
| `datetime(p)`, `timestamp(p)` | `DateTime`, `DateTime64(s[, tz])` | §3.4 | §3.4 |
| `bit(1)` (`COLUMN_TYPE` exactly `bit(1)`; Debezium emits it as BOOLEAN) | `Bool`, `Nullable(Bool)` (or `UInt8`) | `col+0` — renders `1`/`0`; the generic binary rendering gave the hex text `01` | `toString(toUInt8(col))` — matched with `'Bool' in type`, so `Nullable(Bool)` no longer falls through to `toString(col)` = `true`/`false` (`test_checksum_fidelity.py::TestBooleanAndBit`) |
| binary, floating point, JSON | — | see the following sections | see the following sections |

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
  reported such a table EQUAL (finding C1, spec 08.05 §3.1). The tool now
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
  - `--source_timezone` defaults to `UTC` on both side scripts (a fully-UTC
    deployment needs no flags); the driver resolves it from MySQL (§3.2 step
    1) and passes it, together with `--timestamp_columns`, to the scripts.
    Standalone runs in a non-UTC deployment must pass both, or they report
    DIFFERENT for every `TIMESTAMP` column — noise on the safe side, never a
    masked divergence. The value must be an IANA name accepted by both
    Python's `zoneinfo` and ClickHouse (`validate_timezone()`).
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
  driver therefore passes no bounds any more (it used to pass
  `1969-12-31 18:00:00` / `2299-12-31 00:00:00`, hiding 1900–1969 and the
  last day of 2299).
- **Clamped-value counts are printed.** Each datetime column contributes
  `clamped_datetime_flag()` = `(rendered > max or rendered < min)`; the
  per-row sum (`clamped_count_expression()`, `coalesce(flag, 0)` so NULLs
  count as 0) is aggregated as a sixth value `clamped` next to
  `cnt,a,b,c,d`. It is **never hashed**; when it is non-zero the side script
  logs `WARNING <n> out-of-range datetime values clamped to [min, max] in
  table <db>.<table>` (a line that does not contain the word "checksum",
  see §3.2 step 4) so an operator knows the EQUAL verdict rests on clamped
  values (`test_checksum_fidelity.py::TestClampedRowCounts`).

### 3.5 Aggregate (the actual algorithm)
The checksum is **not** `MD5(groupArray(cityHash64(*)))` and there is no
`ORDER BY`; it is an order-independent sum of per-row MD5 words, so both
engines can compute it over any physical order and MySQL can compute it in
parallel chunks:

1. Per row, `hash = md5(row_string)` as 32 lowercase hex characters. MySQL:
   `md5(convert(concat_ws('#', <pieces>) using utf8mb4))`; ClickHouse:
   `hex(MD5(<pieces joined by ||'#'||>))` (case does not matter, the hex is
   consumed numerically).
2. The hash is split into four 32-bit words: `a = hex[0:8]`, `b = hex[8:16]`,
   `c = hex[16:24]`, `d = hex[24:32]`, each read as an unsigned integer
   (MySQL `cast(conv(substring(h, k, 8), -16, 10) as signed)`; ClickHouse
   `reinterpretAsInt64(reverse(unhex(substring(h, k, 8))))`) and **summed over
   all rows** into signed 64-bit accumulators (MySQL through the session
   variables `@a := @a + ...` with `max()` over the derived table; ClickHouse
   `sum(...)`).
3. The side script prints
   `Checksum for table <db>.<table> = md5('<cnt>#<a>#<b>#<c>#<d>#') count <cnt>`
   — `checksum_from_aggregate()` in `db/checksum_common.py` is the single
   definition of that final step and both scripts call it. An empty table
   yields `md5('0#0#0#0#0#')` on both sides.
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
skipped columns (`Not compared in table <db>.<table>: floating point columns
[...]` / `... JSON columns [...]`; the line does not contain the word
"checksum", §3.2 step 4). Compared by default: every type in the §3.3 table,
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

(`test_checksum_fidelity.py::TestFloatAndJsonCoverage`)

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
  list of connector metadata columns with nothing). Both sides split each
  token on commas.
  (`test_checksum_fidelity.py::TestRemovedDeadPaths`)

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
    f Float64)`, the built expression is exactly
    `toString("id")||'#'||toString("name")`; the pre-fix code produced
    `toString("id")||'#'||toString("name")||'#'`.
  - `TestClickHouseRowExpression.test_nullable_flags_are_one_trailing_element`
    — §3.3 nullability element.
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
    datetime columns in `--source_timezone` with matching bounds, and
    defaults to `UTC` for every column; the driver passes
    `--source_timezone` to both scripts and `--timestamp_columns` to the
    replica script, and resolves the zone from `@@session.time_zone` /
    `@@system_time_zone` when not given, refusing an abbreviation.
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
    `Object('json')` and `--json_columns`) and log one WARNING per table
    naming them; the opt-in flags include them; the driver passes
    `--json_columns` and the two include flags through.
  - `TestClampedRowCounts` — §3.4: both aggregate queries carry
    `coalesce(sum(clamped),0)`; a table without datetime columns contributes
    `0`; a non-zero count is logged as a WARNING that does not change the
    checksum and does not contain the word "checksum".
- `sink-connector/python/db_compare/tests/test_table_locking.py` — §3.2 lock
  lifecycle (lock before, unlock after both sides, connection closed on
  failure, no lock when disabled).
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
