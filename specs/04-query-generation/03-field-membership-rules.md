# Spec 04.03: Field Membership Rules: Explicit NULLs vs. Omitted Columns

## 1. Executive Summary & Purpose
Specifies the critical distinction between columns that are omitted from a CDC event schema versus columns that are present in the schema but carry explicit `null` values.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java`
- **Method**: `getInsertQueryUsingInputFunction(...)` (builds the INSERT column list from the record's unfiltered schema)
- **Bind-time counterpart**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java` (Spec 07.07 §3.1)

---

## 3. Operational Specification

### 3.1 The Two Column Membership Cases

```
Incoming Record Field Evaluation
       |
       +---> Case A: Column is PRESENT in record schema, but value is NULL:
       |        -> MUST BE INCLUDED in INSERT column list.
       |        -> JDBC binds: `ps.setNull(index, type)`.
       |        -> ClickHouse stores native NULL.
       |        -> Rationale: Prevents ClickHouse from substituting column DEFAULTs!
       |
       +---> Case B: Column is ABSENT from record schema (e.g. pre-ALTER record):
                -> MUST BE EXCLUDED from INSERT column list.
                -> ClickHouse evaluates native DEFAULT expression.
                -> Rationale: Pre-ALTER records should receive the table's default.
```

### 3.2 Production Hazard Prevented
In earlier versions, `null`-valued fields were omitted from the query column list. Consequently, ClickHouse applied column defaults (e.g. converting `NULL` to `0`, `""`, or `1970-01-01`), causing silent data divergence. The 2.11.0 rule strictly preserves explicit `NULL`s.

### 3.3 The Connect-schema default is the same hazard at bind time
Membership (this spec) is decided from the record's unfiltered schema. The
*value* bound for a member column is read with `Struct.getWithoutDefault`, never
`Struct.get`: `Struct.get` substitutes the Connect-schema `defaultValue` (which
Debezium fills from the MySQL column `DEFAULT`) for a stored `null`, so the
column would be present in the INSERT but bound to the default. See Spec 07.07
§3.1. Both halves are required for Case A to actually store `NULL`.

### 3.4 Column names are matched to source fields case-insensitively, everywhere
Membership (`QueryFormatter.createColumns`) compares the ClickHouse column name
to the record's field names case-insensitively, so a table created by hand as
`` `ID` Int32, `Amount` Float64 `` for source columns `id, amount` is a member
match. The bind-time value read, however, used the ClickHouse column name
verbatim (`struct.getWithoutDefault(colName)`), which Kafka Connect resolves
case-sensitively: it threw `DataException`, `PreparedStatementFieldMapper`
classified that as a stale cache (`StaleSchemaCacheException`) and the batch
was retried forever against a cache that was never stale — a permanent stall
with nothing written.

Rule (`PreparedStatementFieldMapper.resolveSourceField(struct, colName)`): the
source field a column is bound from is resolved against the **record's
schema** — an exact name match first, then a case-insensitive match — and the
value is read with `getWithoutDefault(field.name())`. The record's schema, not
the modified-field list, is consulted because that list omits NULL-valued
fields; a NULL source value in a case-mismatched column must still be bound as
NULL (Case A). Only a column that matches no field under either comparison
reaches the stale-cache branch, which is exactly the condition it was written
for.

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority)** & **Invariant I7 (Type Equivalence)**: Explicit source `NULL` values are preserved on the replica without default substitution.

---

## 5. Verification Criteria
- `NullValueColumnDropTest.testNullColumnIsBoundOnInsert()` — Case A: a column
  present in the schema with a `null` value is a member of the INSERT and is
  bound as SQL NULL.
- `NullValueColumnDropTest.testUpdateClearingColumnBindsIt()` — an UPDATE that
  sets a column to `null` binds it (Case A on the after-image).
- `PreparedStatementFieldMapperColumnCaseTest.columnCaseMismatchIsResolvedToTheSourceField()`
  — §3.4: ClickHouse columns `ID`, `Amount`, `NOTE` for source fields `id`,
  `amount`, `note` (the last NULL) are bound (`7`, `12.5`, `setNull`); the
  pre-fix code throws `StaleSchemaCacheException` for `ID`.
- `NullValueColumnDropTest.testPreAlterRecordStillOmitsUnknownColumn()` — Case B:
  a column absent from a pre-ALTER record's schema is not a member.
- `NullValueColumnDropTest.testSchemaDefaultIsNotSubstitutedForNull()` — §3.3:
  the Connect-schema default is never bound in place of a stored `null`.
- `NullColumnValueRoundTripIT.testNullOnInsertStaysNull()`,
  `NullColumnValueRoundTripIT.testUpdateToNullClearsTheStoredValue()`,
  `NullColumnValueRoundTripIT.testPreAlterRowStillReceivesTheColumnDefault()` —
  end to end against MySQL and ClickHouse: explicit `NULL` lands as NULL,
  a pre-ALTER row receives the ClickHouse column DEFAULT.
