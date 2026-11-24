# Spec 08.06: A Failed Column-Metadata Read Is Raised, Not Read as Absence

## 1. Executive Summary & Purpose
A read of `system.columns` that cannot be completed must not return an empty column map. An empty map means "the table has no columns". A caller that receives it for a read that failed treats every source column as absent: the staleness check keeps the cached map, and the INSERT is built without columns that were never checked. This spec fixes the contract of the two column-listing methods of `DBMetadata` and the handling of the statements they open. The staleness check that consumes the map is specified in spec 04.06 (failed staleness re-read) and spec 08.03.

---

## 2. Codebase Mapping on 2.11.1
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java`
  - `getColumnsDataTypesForTable(String tableName, Connection conn, String database)`
  - `getColumnsDataTypesForTable(Connection conn, String tableName, String database)`
  - `getAliasAndMaterializedColumnsForTableAndDatabase(String tableName, String databaseName, Connection conn)`
- **Exception**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ColumnMetadataUnavailableException.java`
- **Callers that must keep working**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java` (constructor metadata read, `updateColumnNameToDataTypeMap`), `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbKafkaOffsetWriter.java`, and `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java` (`refreshIfRecordHasUnknownColumn`).
- **Classification**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java` reads the error code from the cause chain, so the wrapped driver failure decides RETRIABLE or FATAL.

---

## 3. Operational Specification

### 3.1 Read contract
- A read makes at most `MAX_RETRIES` attempts (10, unchanged). Each attempt opens its own statement and result set, and reconnects a pooled connection between attempts, as before.
- A read that completes and finds no rows returns an empty map only when `system.tables` does not list the table, which is the correct answer for a table that does not exist yet. A table always has at least one column, so when `system.tables` lists the table and `system.columns` returns no rows (for example because a row policy hides them), the read is treated as failed and retried under the same rules. The check runs only on an empty listing, so a populated read costs one query.
- A read whose attempts are all exhausted raises `ColumnMetadataUnavailableException`. Its message names the database, the table and the attempt count. Its cause is the last failure. It is never returned as an empty map.

### 3.2 One listing per read
- ALIAS and MATERIALIZED columns are excluded from the same `system.columns` listing that supplies the map, by `default_kind`. The map read does not issue a second query for them.
- `getAliasAndMaterializedColumnsForTableAndDatabase` keeps its signature and result. It raises `ColumnMetadataUnavailableException` when its attempts are exhausted, and it closes its statement and result set on every path.

### 3.3 Resource handling
- Every statement and result set opened by these reads is closed on every path, including the failure paths, using try-with-resources.

### 3.4 Classification
- A `ColumnMetadataUnavailableException` whose cause carries a retriable ClickHouse code (for example `Code: 202`, too many simultaneous queries) is RETRIABLE. A cause that carries a FATAL code (for example `Code: 497`, access denied) is FATAL and stops the connector.

### 3.5 Callers raise the failure
- A `ColumnMetadataUnavailableException` from a column read is never caught and logged by a caller that goes on with the previous map. This covers the re-read after a schema-evolution ALTER (the AFTER-record branch of `GroupInsertQueryWithBatchRecords`), the re-read after an `ADD COLUMN` in the staleness check, and `DbWriter` construction.
- The number of re-reads on the AFTER-record branch is unchanged by this spec: under `schema.evolution=true` the base code re-reads the column list once per AFTER record. Reducing that count is a separate change, not part of this contract.
- The ALTER itself keeps its behaviour: `ClickHouseAlterTable.alterTable` logs its own failure and returns. The staleness check that follows reports the column as missing if it is still missing, so an ALTER that did not take effect still fails the batch.
- A `DbWriter` whose construction failed on a column read is not returned. The exception propagates out of `ClickHouseBatchRunnable.getDbWriterForTable`, no writer is cached for the table, and the next lookup reads ClickHouse again.

---

## 4. Invariants Preserved
- **No empty-on-failure**: a column-metadata read that did not complete never yields an empty map.
- **Resources returned**: every statement and result set a read opens is closed, on success and on failure.
- **Unchanged success path**: a successful read returns the same map as before for the same table (ALIAS and MATERIALIZED excluded, `ORDER BY position`).

---

## 5. Verification Criteria
- `DBMetadataColumnReadTest.unreadableColumnListIsRaisedNotReturnedAsEmpty()`, `DBMetadataColumnReadTest.unreadableColumnListIsRaisedThroughTheOtherOverload()` — §3.1: a read whose attempts are all refused raises `ColumnMetadataUnavailableException` (pre-fix: returns an empty map).
- `DBMetadataColumnReadTest.absentTableIsAnEmptyMap()` — §3.1: a read that completes with no rows, for a table `system.tables` does not list, is an empty map and no exception.
- `DBMetadataColumnReadTest.emptyListingForAnExistingTableIsRaised()` — §3.1: a read that completes with no rows, for a table `system.tables` lists, raises `ColumnMetadataUnavailableException` (pre-fix: returns an empty map, and the writer is built with no columns).
- `DBMetadataColumnReadTest.emptyListingForAnExistingTableIsRaisedThroughTheOtherOverload()` — §3.1: the same rule through the (connection, table, database) overload.
- `DBMetadataColumnReadTest.successfulReadClosesItsStatementAndResultSet()` — §3.3: one statement and one result set, both closed (pre-fix: neither is closed).
- `DBMetadataColumnReadTest.aliasColumnsNeedNoSecondQuery()` — §3.2: one listing query per read, MATERIALIZED column excluded.
- `DBMetadataColumnReadTest.aliasHelperRaisesWhenUnreadable()`, `DBMetadataColumnReadTest.aliasHelperReturnsAliasNames()` — §3.2: the alias helper raises on exhaustion and returns the alias names it reads.
- `SchemaCacheFailureModesTest.schemaEvolutionReReadFailureIsRaised()` — §3.5: a refused column re-read on the schema-evolution branch, with every column the record carries already cached, raises `ColumnMetadataUnavailableException` (pre-fix: logged and the previous map kept).
- `SchemaCacheFailureModesTest.addColumnReReadFailureIsRaisedNotReportedAsMissing()` — §3.5: a refused re-read after the evolve-branch ADD COLUMN surfaces as `StaleSchemaCacheException` with `ColumnMetadataUnavailableException` as its cause, not as `MissingTargetColumnException` (pre-fix: the read failure is reported as a missing column).
- `FailedMetadataBuildNotCachedTest.constructorRaisesUnreadableColumnList()`, `FailedMetadataBuildNotCachedTest.failedBuildIsNotServedFromTheCache()` — §3.5: `DbWriter` construction raises, and the next lookup reads ClickHouse again (pre-fix: a half-built writer is returned and cached).

---

## 6. Failure Modes & Recovery

### Column metadata read refused on every attempt
- **Trigger**: ClickHouse refuses or times out every `system.columns` query of a read (for example `Code: 202` under load, or a dropped connection), while INSERTs may still succeed.
- **Behaviour**: after 10 attempts the read raises `ColumnMetadataUnavailableException`. The caller (`DbWriter` construction, `DbKafkaOffsetWriter` construction, or the staleness re-read) does not receive an empty map.
- **Detection**: one ERROR `Exception retrieving Column Metadata, retrying (<n>/10)` per attempt, then ERROR `ClickHouseBatchRunnable exception` carrying the `ColumnMetadataUnavailableException` and its cause. No WARN claims the cached map is kept.
- **Blast radius**: the table's batches are held and retried with backoff, as for any RETRIABLE error. No row is written without the metadata it needs. Other tables are not affected.
- **Recovery**: none required once ClickHouse answers the read. The next attempt rebuilds the writer and reads again. A FATAL cause (for example access denied) stops the connector, as it does for any FATAL error.
- **RTO**: the duration of the ClickHouse metadata outage plus one backoff interval.
- **Test**: `DBMetadataColumnReadTest.unreadableColumnListIsRaisedNotReturnedAsEmpty()`.
