# 08.06 Engine Delete-Column Resolution

## 1. Executive Summary & Purpose

A ReplacingMergeTree table has a delete column only when its engine clause names one:
`ReplacingMergeTree(<version>, <delete column>)` (ClickHouse 23.2 and later). A bare
`ReplacingMergeTree(<version>)` never reads `is_deleted` or `_sign`, so for that engine
those names are plain columns. Writing a sign-style value (1, or -1 stored as 255) into
them is silent corruption: the engine keeps the old row as the live one.

This spec fixes the delete semantics to the live engine clause, read from
`system.tables.engine_full`. It never derives them from a column name and never from the
`replacingmergetree.delete.column` default. A bare engine gets plain rows that cannot be
misread, and a row that needs a delete marker on a bare engine is refused loudly with the
rebuild that a human must run. Metadata tables that the connector writes with a bare engine
keep writing, because they never produce a DELETE record.

## 2. Codebase Mapping on 2.11.1

- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java`: `resolveReplacingMergeTreeDeleteColumn` (the engine-clause resolver), `configureReplacingMergeTreeColumns`, `requireReplacingMergeTreeColumns`.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java`: `getInsertQueryUsingInputFunction` (12-argument form carries `nameGuessedFlagsManaged`), `isConnectorManagedColumn`.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`: `updateQueryToRecordsMap` (the template build that passes the resolved delete column and the managed-name flag).
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`: `requireDeleteColumn` (the refusal for DELETE records and key-moving UPDATE tombstones on a bare engine).
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/history/BinLogHistory.java`: unchanged. History tables are created as `ReplacingMergeTree(_version, is_deleted)` and never take the bare path.
- `sink-connector-lightweight/src/main/resources/config.properties`: sets the default `replacingmergetree.delete.column=is_deleted` for the embedded jar. This default is not an input to the resolution.
- `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/BareReplacingMergeTreeDeleteColumnTest.java`: resolver unit tests.
- `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/batch/BareReplacingMergeTreeFlagColumnTest.java`: INSERT, live-row and refusal unit tests.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java`: `TERMINAL_EXCEPTION_TYPES` (lists the refusal of 6.1 as terminal) and `classify`.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/BareReplacingMergeTreeDeleteRefusedException.java`: the terminal refusal type thrown by `requireDeleteColumn`.

## 3. Operational Specification

3.1 Source of truth. The delete column of a ReplacingMergeTree table is the second argument of
the engine clause read at schema load. The resolver takes the argument text and returns the
second argument, trimmed, or nothing when there is no second argument or it is blank. It
takes no configuration input.

3.2 Bare engine (`ReplacingMergeTree(<version>)`, no second argument).
- The delete column is none. `is_deleted` and `_sign` are plain columns. They are not
  connector-managed, so an INSERT keeps them as ordinary columns and a live row binds no value
  into them.
- A DELETE record, and the tombstone of an UPDATE that moves a row to another sorting key,
  is refused. The refusal is an `IllegalStateException` that names the table, the engine clause
  and the rebuild: add the flag column, create a table with `ReplacingMergeTree(<version>, is_deleted)`,
  copy the live rows, and exchange the tables. The alternative is `ignore_delete=true`, a
  decision the operator takes knowingly.
- The refusal is not an exemption from the rest of the write path. A bare table with no
  DELETE records and no key-moving UPDATE writes exactly as before.

3.3 Two-argument engine (`ReplacingMergeTree(<version>, <delete column>)`).
- The delete column is the named argument. A DELETE binds 1 into it, and a live row binds 0.
- The name guards for `is_deleted` and `_sign` remain in force, as before, because the engine
  names the flags it manages.

3.4 Configured default. `replacingmergetree.delete.column` is not consulted when the engine
clause of a table with a version column is known. A grouper that is built without a version
column keeps the historical name-based behaviour. Every production construction passes the
version column (`ClickHouseBatchRunnable` and `ClickHouseBatchWriter`), so that path is not
reached for a live ReplacingMergeTree table.

3.5 Replication history. The replication-history mode (`replication.history.enable=true`)
keeps the historical behaviour for its own tables, which are created as
`ReplacingMergeTree(_version, is_deleted)`. The refusal in 3.2 is exempt there, as it is for
`ignore_delete=true`.

3.6 No contradicting value. A flag column is bound a value only when the resolved delete column
names it. A value is never written into a column that the engine does not read as a delete
marker.

## 4. Invariants Preserved

- A bare ReplacingMergeTree table never receives a delete-marker value. A row that would need
  one is refused, so a before image cannot come back as a live row at the old key.
- For a two-argument engine, the value written into the delete column follows the CDC
  operation: 1 for a DELETE, 0 for a live row.
- The resolver output is a function of the engine clause alone. Changing the configured
  default does not change the resolution of an existing table.
- Metadata tables written by the connector (`altinity_sink_connector.*`), which are bare and have
  no delete column, keep writing live rows.
- The history-table definition in `BinLogHistory` is unchanged.

## 5. Verification Criteria

Unit tests:
- `BareReplacingMergeTreeDeleteColumnTest.bareEngineHasNoDeleteColumn` resolves a bare clause, a clause with a trailing comma and a null clause to no delete column.
- `BareReplacingMergeTreeDeleteColumnTest.twoArgumentEngineNamesItsDeleteColumn` resolves `is_deleted`, `_sign` and `_is_deleted` from their two-argument clauses.
- `BareReplacingMergeTreeFlagColumnTest.bareEngineInsertOmitsFlagColumns` checks that an INSERT for a bare engine with `is_deleted UInt8` and `_sign Int8` does not name those columns as flags.
- `BareReplacingMergeTreeFlagColumnTest.bareEngineLiveRowBindsNoFlag` checks that a live row binds two parameters and no flag.
- `BareReplacingMergeTreeFlagColumnTest.bareEngineDeleteRefusedWithRebuild` checks that a DELETE on a bare engine fails with the rebuild message.
- `BareReplacingMergeTreeFlagColumnTest.twoArgumentEngineStillBindsDeleteFlag` checks that a DELETE on a two-argument engine binds the delete flag, and a live row binds 0.
- `BareReplacingMergeTreeFlagColumnTest.bareEngineRefusalIsTerminal` checks that the 6.1 refusal, alone and wrapped in a RuntimeException, classifies FATAL through `ClickHouseErrorClassifier.classify` (6.5). Mutation: removing the class from `TERMINAL_EXCEPTION_TYPES` must fail this test.
- Existing suites stay green: `PreparedStatementFieldMapperEngineColumnTest`, `QueryFormatterPreAlterRecordTest` and `DbWriterEngineColumnsTest`.

Formal artifacts: the four files for sections 3 and 4 state the resolution, the write rule and the
no-contradiction property. The four files for section 6.5 state that a terminal type anywhere in the cause
chain is FATAL, that a refusal is never retried (the batch stops and is not written), and that a plain
IllegalStateException with no code stays UNKNOWN. Each file carries a `spec-for` header that names the changed symbol.

End-to-end (base 2.11.1 against the fix): the bare-engine scenarios must reproduce
the divergence on base and pass or refuse on the fix. The two-argument controls must PASS on both.

## 6. Failure Modes & Recovery

### 6.1 A DELETE or key-moving UPDATE reaches a bare ReplacingMergeTree table

- Detection: the batch fails with `IllegalStateException` whose message begins "A DELETE for ReplacingMergeTree table" (or "The tombstone of an UPDATE"), names `<db>.<table>` and the engine clause, and is logged through the existing batch error path. The row is not written.
- Recovery: an operator runs the rebuild in the message (add the flag column, create the two-argument table, copy the live rows with the flag at 0, exchange the tables), then restarts the connector so the schema is reloaded. If the operator accepts the risk, `ignore_delete=true` skips the refusal for that deployment.
- RTO: operator action within the working day. The rebuild time is proportional to the table size; the batch stays blocked until the table is rebuilt or the operator sets `ignore_delete`.
- Terminal: the refusal is a terminal exception (`BareReplacingMergeTreeDeleteRefusedException`, listed in `ClickHouseErrorClassifier.TERMINAL_EXCEPTION_TYPES`). It is classified FATAL, so the batch task stops and the engine stops loudly with this cause. It is never retried. See 6.5 for the retry loop this replaced.

### 6.2 The engine clause cannot be read

- Detection: the `select engine_full from system.tables` lookup in `DBMetadata.getTableEngineUsingSystemTables` fails, and the exception is rethrown to the caller, so the failure is visible in the connector log.
- Recovery: restore read access to `system.tables` for the connector user, then restart the connector so the schema is reloaded.
- RTO: minutes once the grant is restored.

### 6.3 A two-argument engine names a delete column that the table does not have

- Detection: the existing WARN from `requireReplacingMergeTreeColumns` for a configured-but-absent column, naming the table and the column.
- Recovery: add the column with `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, or correct the engine definition through a rebuild. Nothing is written until the column exists.
- RTO: one `ALTER` on a table of any size, plus one schema refresh on the connector.

### 6.4 A metadata table receives a DELETE record

- Detection: a DELETE row for an `altinity_sink_connector.*` table fails with the 6.1 message. This is not expected in normal flow; the e2e run checks that metadata tables still write live rows.
- Recovery: find the source of the DELETE record for the metadata table, fix it, then restart. Live rows keep writing to the metadata table throughout.
- RTO: minutes for a routing fix.

### 6.5 The refusal of 6.1 is retried as a transient ClickHouse error (fixed by this section)

- Before this section: the refusal was an `IllegalStateException` with no terminal classification, so `ClickHouseErrorClassifier.classify` returned UNKNOWN, the batch runnable logged "Retriable ClickHouse error (Code: -1, Category: UNKNOWN) -- the same batch will be retried", and with `errors.max.retries = -1` it retried forever. Every table hashed to that batch thread waited behind it. Observed in an end-to-end run of a bare-engine table with no pinned delete column: consecutive failures climbed while the drain never completed.
- Detection (after the fix): one ERROR with the 6.1 message and a `Fatal ClickHouse error, stopping task` RuntimeException whose cause is the refusal. No "Retriable ClickHouse error" line for the table.
- Recovery: the same as 6.1: rebuild the table, then restart the connector.
- RTO: as 6.1. The difference is that the stop is immediate and visible, not a silent stall of the batch thread.
