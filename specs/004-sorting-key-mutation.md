# Specification 004: Primary & Sorting Key Mutation Handling

## 1. Executive Summary & Purpose

In ClickHouse, table deduplication and mutation replacement under `ReplacingMergeTree` are governed strictly by the table's `ORDER BY` sorting key. Unlike relational databases, ClickHouse cannot update a row's sorting key in-place. When an upstream MySQL `UPDATE` statement modifies a column that belongs to the ClickHouse sorting key, an in-place insert under the new key leaves the old sorting key intact, causing ghost rows and data duplication.

This specification details the connector's two-phase tombstoning protocol to ensure consistent primary and sorting key relocations.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Classes**:
  - `com.altinity.clickhouse.sink.connector.db.operations.GroupInsertQueryWithBatchRecords` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.PreparedStatementExecutor` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.PreparedStatementFieldMapper` (`sink-connector/...`)
- **Key Methods**:
  - `PreparedStatementExecutor.updateRelocatesSortingKey()`
  - `PreparedStatementFieldMapper.insertTombstonePreparedStatement()`
  - `PreparedStatementFieldMapper.insertPreparedStatement()`

---

## 3. The Sorting Key Relocation Problem

Consider a table with primary key / sorting key `(tenant_id, account_id)`.
1. MySQL initial state: `(1, 100, name='Alice')`.
2. MySQL executes: `UPDATE accounts SET tenant_id = 2 WHERE account_id = 100`.
3. If the connector simply inserts the new row `(2, 100, name='Alice')` into ClickHouse:
   - Part 1 contains `(1, 100)`.
   - Part 2 contains `(2, 100)`.
   - When ClickHouse merges parts or queries with `FINAL`, both `(1, 100)` and `(2, 100)` remain visible because their sorting keys differ. The row exists twice under two different tenants.

---

## 4. Two-Phase Tombstone Protocol

When processing an `UPDATE` event (`operation == 'u'`), the connector executes the following deterministic protocol:

```
CDC UPDATE Event
       |
       v
PreparedStatementExecutor.updateRelocatesSortingKey()
       |
       +---> Compare `beforeStruct` vs `afterStruct` on all sorting key columns
       |
       +---> Do any sorting key column values differ?
                |
                +--- [NO: Normal In-Place Update]:
                |      Bind afterStruct values:
                |      is_deleted = 0
                |      _version = record.getVersion()
                |
                +--- [YES: Sorting Key Relocation]:
                       Phase 1: Synthesize Old Key Delete Tombstone
                       |  Bind beforeStruct values (old sorting key)
                       |  is_deleted = 1  (or _sign = -1 for CollapsingMergeTree)
                       |  _version = record.getVersion() - 1
                       |  ps.addBatch()
                       |
                       Phase 2: Insert New Key Live Row
                          Bind afterStruct values (new sorting key)
                          is_deleted = 0  (or _sign = 1)
                          _version = record.getVersion()
                          ps.addBatch()
```

### 4.1 Version Assignment Rules for Relocation
- **New Key Version**: Assigned the canonical event version $V = \text{record.getVersion()}$.
- **Old Key Tombstone Version**: Assigned $V - 1$.
  - Assigning $V - 1$ guarantees that the tombstone ranks higher than the original row on the old key (which had an earlier commit version $< V - 1$), while ranking strictly below the new row.

---

## 5. Invariants Preserved

1. **Ghost Row Elimination (Invariant I4)**:
   The old sorting key is explicitly marked deleted (`is_deleted = 1`). A query with `FINAL` on the old key returns no rows.
2. **Atomicity**:
   Both the tombstone and the new live row are submitted in the same JDBC statement batch (`executeBatch()`), ensuring they land in the same ingestion cycle.
3. **CollapsingMergeTree Support**:
   For `CollapsingMergeTree` engines, `_sign = -1` cancels the old key, and `_sign = 1` activates the new key.

---

## 6. Edge Cases

- **Partial Column Binlog Formats (`binlog_row_image != FULL`)**:
  If MySQL is configured with `MINIMAL` or `NOBLOB` row images, `beforeStruct` may omit unchanged columns. The connector requires `binlog_row_image = FULL` to guarantee that all sorting key columns are present in the before-image.
- **Multiple Rapid Relocations**:
  If a row's key is updated multiple times within the same second, monotonic version clamping ensures each step receives a strictly ordered sequence of tombstone and live rows.

---

## 7. Verification Criteria

- **Unit Tests**:
  `PreparedStatementExecutorTest.testUpdateRelocatesSortingKey()`.
- **Integration Tests**:
  `PrimaryKeyRelocationIT`, asserting that querying `FINAL` on the old key returns 0 rows and on the new key returns 1 row with updated values.
- **Formal Verification**:
  Corresponds to `Replication.Engine.translate_event` (`Update` branch) and `theorem update_pk_relocation_convergence` in `formal_specs/lean/`.
