# Spec 06.06: Generated Columns Mapping: DEFAULT vs. MATERIALIZED

## 1. Executive Summary & Purpose
Specifies the translation of MySQL generated columns (`GENERATED ALWAYS AS (expr) STORED/VIRTUAL`) to ClickHouse column definitions, establishing why they must be mapped to `DEFAULT (expr)` and never `MATERIALIZED`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java`
- **Both paths** must handle the generated-column clause via a
  `GeneratedColumnConstraintContext` branch: the CREATE TABLE column loop
  (`parseColumnDefinitions`) AND the ALTER TABLE ADD/MODIFY loop. Both extract the
  expression with the shared `extractGeneratedExpression` helper.
- **Formal model**: `formal_specs/lean/Replication/GeneratedColumn.lean`.

---

## 3. Operational Specification

### 3.1 The MATERIALIZED Trap
In ClickHouse:
- A `MATERIALIZED` column is computed at insertion time by ClickHouse and **strictly rejects** explicit values in `INSERT` statements (`Code: 44. Cannot insert value into a column with type MATERIALIZED`).
- Debezium, depending on configuration, may transmit the generated value from MySQL in CDC payloads.
- If mapped to `MATERIALIZED`, ClickHouse rejects the insert, stalling replication.

### 3.2 Mapping to DEFAULT (expr)
- When parsing:
  ```sql
  ALTER TABLE t ADD COLUMN full_name VARCHAR(100) GENERATED ALWAYS AS (CONCAT(first_name, ' ', last_name)) STORED
  ```
- The translator emits:
  ```sql
  ALTER TABLE t ADD COLUMN IF NOT EXISTS full_name Nullable(String) DEFAULT (CONCAT(first_name, ' ', last_name))
  ```
- **Benefits**:
  1. If MySQL sends the generated value, ClickHouse accepts and stores it directly.
  2. If MySQL omits the column, ClickHouse evaluates the default expression automatically.

### 3.3 The generation expression is a DEFAULT, NEVER the column type (MANDATORY)
The emitted ClickHouse column MUST keep its DECLARED data type; the generation
expression MUST map to a `DEFAULT` clause and MUST NEVER be treated as the column
type. The ALTER TABLE column loop previously had no branch for
`GeneratedColumnConstraintContext`, so the clause fell into the catch-all that
treats any unrecognised token as the column type and OVERWROTE the type with the
raw expression text — emitting malformed DDL like `ADD COLUMN c AS(a+b)` instead
of `ADD COLUMN c Int32 DEFAULT a+b`. The fix adds the missing branch (setting a
`DEFAULT <expr>` modifier via `Constants.GENERATED_COLUMN_KIND`) so the ALTER path
matches the CREATE path.

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority)**: ClickHouse computed expressions never block source data ingestion.
- **Invariant I13 (Generated-Column Type Integrity)**: a generation expression is emitted as a `DEFAULT` and never as the column type; the ClickHouse column always keeps its declared data type, on both the CREATE and ALTER paths.

---

## 5. Verification Criteria
- `Replication.GeneratedColumn.alter_preserves_type` — the emitted type is always the declared data type.
- `Replication.GeneratedColumn.type_is_never_expression` — the type is never the generation expression.
- `Replication.GeneratedColumn.generated_has_default` — a generated column always produces a `DEFAULT`.
- `AlterTableGeneratedColumnTest` — `ADD COLUMN ... GENERATED ALWAYS AS`/`AS` keeps the type and emits `DEFAULT` (mutation-checked).

---

## 6. Failure Modes & Recovery
A generated column's value always arrives in the row image for every row written after the DDL (the writer binds it explicitly), so the `DEFAULT (expr)` matters only for (a) whether ClickHouse accepts the statement at all and (b) the back-fill of rows that pre-date an `ADD COLUMN`. Both depend on the MySQL expression text being valid, equivalent ClickHouse SQL, which nothing checks.

- **FM-06.06-1 Generation expression rendered without token boundaries**
  - **Trigger**: a generated column whose expression contains keyword operators or multi-word syntax: `CASE WHEN ... THEN ... END`, `a DIV b`, `x AND y`, `c IS NOT NULL`, `d + INTERVAL 1 DAY`.
  - **Behaviour**: `MySqlDDLParserListenerImpl.extractGeneratedExpression()` builds the expression with ANTLR `getText()`, which concatenates tokens without the whitespace between them: `CASE WHEN a > 0 THEN 1 ELSE 0 END` becomes `DEFAULT CASEWHENa>0THEN1ELSE0END`, on both the CREATE and the ALTER path. ClickHouse refuses the statement (the exact error code was not measured; a syntax error, Code 62, is non-retryable and halts loudly, see spec 06.08 §6 FM-06.08-1; any retryable code is swallowed, FM-06.08-2).
  - **Detection**: ERROR `DDL failed and ddl.retry is not enabled, so it is not retried; halting the pipeline rather than skipping the schema change: [<DDL>]` with the ClickHouse error as cause; engine restarts, then exit code 3.
  - **Blast radius**: all replication stops at the statement; nothing lost.
  - **Recovery**: create/alter the column by hand on ClickHouse with its declared type and a correctly spaced `DEFAULT (expr)` (or no default when the table is new or empty), add `ignore.ddl.regex` matching exactly that statement, restart, remove the entry once past it.
  - **RTO**: operator time + one restart; unmeasured.
  - **Test**: `DdlTranslationFailureModesTest.generatedExpressionKeepsTokenBoundaries()` (disabled; fails on 2.11.0), `DdlTranslationFailureModesTest.generatedExpressionTokensAreGluedToday()`.
  - **DEFECT**: generation expressions lose their whitespace, so any expression with a keyword operator yields DDL ClickHouse refuses; the expression must be rendered from the token stream with its hidden-channel whitespace (or refused loudly).

- **FM-06.06-2 Expression ClickHouse does not know (MySQL-only function or syntax)**
  - **Trigger**: a generated column over a MySQL function ClickHouse lacks or spells differently (JSON path functions, collation-aware string functions, MySQL date-format specifiers).
  - **Behaviour**: the expression is copied verbatim into `DEFAULT (...)`. ClickHouse refuses the CREATE/ALTER; if the error code is outside `DBMetadata.NON_RETRYABLE_ERROR_CODES` (e.g. an unknown function) the refusal is retried and then SWALLOWED (spec 06.08 §6 FM-06.08-2): a missing CREATE surfaces later as `UNKNOWN_TABLE` (Code 60, FATAL, exit code 3) on the first row of the table (unless the record-schema auto-create of spec 08.05 creates it), a missing ADD COLUMN as `MissingTargetColumnException` (spec 08.04) on the first row carrying the column.
  - **Detection**: ERROR `Error executing query: Retrying (n/10)` for the statement, then nothing until the first row of the table fails.
  - **Blast radius**: that table stops at its first row (the worker's batch fails; see spec 08.04 / 10.01 for its escalation); other tables continue until the next DDL barrier or the terminal stop.
  - **Recovery**: create or alter the column by hand with its declared type and a ClickHouse-equivalent `DEFAULT` (or none), then restart; the rows re-delivered from the last committed offset carry the source values.
  - **RTO**: operator time + restart; unmeasured.
  - **Test**: GAP: a translation test that a generated column over a function outside a ClickHouse-compatible allow-list is refused loudly (or emitted without a DEFAULT when the table is new) instead of copied verbatim.

- **FM-06.06-3 Back-fill of a STORED column added to a non-empty table uses ClickHouse semantics**
  - **Trigger**: `ALTER TABLE t ADD COLUMN g ... GENERATED ALWAYS AS (expr) STORED` on a table with rows.
  - **Behaviour**: MySQL computes `g` for every existing row inside the ALTER; the binlog carries only the statement. ClickHouse fills those rows by evaluating the copied `DEFAULT (expr)` with its own semantics (integer `/` returns Float64 where MySQL returns DECIMAL, string/NULL/collation rules differ). This is the unreproducible back-fill that spec 06.04 §3.2.2 rule 3 refuses loudly for ordinary non-literal defaults; the generated-column path does not apply that rule.
  - **Detection**: none.
  - **Blast radius**: column `g` of every pre-existing row may differ from MySQL, count-clean and permanently (no later event rewrites untouched rows).
  - **Recovery**: re-synchronise the table (`ch-mysql-resync`, spec 11.04) after the ALTER.
  - **RTO**: table re-synchronisation, proportional to its size; unmeasured.
  - **Test**: GAP: a translation test asserting that `ADD COLUMN ... GENERATED ALWAYS AS (expr) STORED` on a table the lookup reports as non-empty is refused (or flagged for re-synchronisation) the way spec 06.04 §3.2.2 rule 3 refuses other non-literal defaults.
  - **DEFECT**: pre-existing rows of an added STORED generated column are back-filled with ClickHouse's evaluation of a MySQL expression, silently.

Summary: 3 failure modes, 2 DEFECT, 2 GAP.
