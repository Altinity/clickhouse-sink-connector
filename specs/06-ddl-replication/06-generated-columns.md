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
