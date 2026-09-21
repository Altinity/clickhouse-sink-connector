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
1. Reads the integer primary key, its min/max and the partition expression
   from MySQL `information_schema` on a shared connection.
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
| binary, temporal, boolean/bit, floating point, JSON | — | see the following sections | see the following sections |

### 3.4 Aggregate (the actual algorithm)
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
  - `TestEndToEndChecksum.test_equal_fixtures_report_equal` — §3.4: both side
    scripts, driven through their real `compute_checksum` /
    `calculate_checksum` paths with stubbed engines, print the same checksum
    and count for identical fixtures (including the §3.3 fixture whose last
    column is a skipped float).
  - `TestEndToEndChecksum.test_flipped_clickhouse_value_reports_different` —
    §3.4 negative test: flipping one character of one row on the ClickHouse
    side changes the printed checksum while the count stays equal, and
    `analyze_differences()` logs `Checksum difference`.
  - `TestChecksumFromAggregate.test_is_md5_of_hash_separated_values` — §3.4
    step 3, including the empty-table value.
- `sink-connector/python/db_compare/tests/test_table_locking.py` — §3.2 lock
  lifecycle (lock before, unlock after both sides, connection closed on
  failure, no lock when disabled).
- `sink-connector/python/db_compare/tests/mysql_table_checksum_test.py` —
  `{partition_expression}` substitution is literal, never `eval`.
