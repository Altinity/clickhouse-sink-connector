# Spec 04.05: TRUNCATE Table Event Handling

## 1. Executive Summary & Purpose
Specifies the translation and execution of upstream MySQL TRUNCATE operations (`op == 't'`) on ClickHouse target tables.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java` — the TRUNCATE branch inside `executePreparedStatement` (the per-record loop, lines 215–238 on 2.11.0)
- **DDL execution**: `DBMetadata.truncateTable(Connection conn, String databaseName, String tableName)` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java`

---

## 3. Operational Specification

A TRUNCATE record is applied **at its position inside the batch**, not at the end of it. When the per-record loop reaches a record whose operation is `TRUNCATE`:
1. `ps.executeBatch()` flushes every row staged so far for this prepared statement — those rows belong to the pre-truncate state and must reach ClickHouse first. A `SQLException` here is rethrown as `RuntimeException("Failed to flush records staged before TRUNCATE for db.table")`.
2. `metadata.truncateTable(conn, databaseName, tableName)` executes the truncate on ClickHouse. It attempts the statement up to `DBMetadata.MAX_RETRIES` (10) times, reconnecting between attempts when pooling is enabled, and **throws `SQLException` (cause: the last refusal) once every attempt has failed** — it never returns normally without having executed the truncate. The executor rethrows that as `RuntimeException("TRUNCATE failed for db.table")`, so the batch fails and is not acknowledged. (Previously the method fell out of its retry loop silently: the batch continued, reported success and was acknowledged while the pre-truncate rows survived in ClickHouse.) `DBMetadata.getPreparedStatement` follows the same rule — after `MAX_RETRIES` failed attempts it throws `SQLException` naming the statement instead of returning `null`.
3. `continue` — the loop keeps accumulating the records that follow the TRUNCATE, which are the new state and are flushed by the normal `executeBatch()` at the end of the partition.

There is **no schema-cache invalidation step** on this path: a TRUNCATE does not change the table's shape, and the code does not call `CacheInvalidationManager`. (Executing the truncate after the final `executeBatch()` — the previous behaviour — discarded every row the same batch had just inserted whenever a TRUNCATE was followed by more DML.)

---

## 4. Invariants Preserved
- **Relational Parity**: truncating the source table empties the replica of the pre-truncate state while rows after the truncate in the same batch survive.
- **Invariant I9 (Loud Failure)**: both the pre-truncate flush and the truncate itself fail the batch loudly.

---

## 5. Verification Criteria
- `PreparedStatementExecutorTruncateTest.truncateRefusedByClickHouseFailsTheBatch()` — §3 step 2: with a connection that refuses the qualified `TRUNCATE TABLE` statement, `addToPreparedStatementBatch` throws (root cause: the refusal) and never answers `true`.
- `DBMetadataStatementFailureTest.truncateFailureIsRethrownAfterRetries()`, `DBMetadataStatementFailureTest.preparedStatementFailureIsRethrownNotNull()` — `truncateTable` throws after `MAX_RETRIES` refused attempts; `getPreparedStatement` throws instead of returning `null`.
- `TruncateTableIT.testIsDeleted()` — a TRUNCATE on MySQL empties the ClickHouse table.
- `TruncateTableIT.testRowsInsertedAfterTruncateSurvive()` — rows following the TRUNCATE in the same batch are retained.
