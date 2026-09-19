# Spec 04.03: Field Membership Rules: Explicit NULLs vs. Omitted Columns

## 1. Executive Summary & Purpose
Specifies the critical distinction between columns that are omitted from a CDC event schema versus columns that are present in the schema but carry explicit `null` values.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/QueryFormatter.java`
- **Method**: `createColumns()`

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

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority)** & **Invariant I7 (Type Equivalence)**: Explicit source `NULL` values are preserved on the replica without default substitution.

---

## 5. Verification Criteria
- `QueryFormatterTest.testNullFieldRetainedInColumnList()`
- `DataTypesIT.testExplicitNullPreservation()`
