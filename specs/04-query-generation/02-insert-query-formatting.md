# Spec 04.02: Parameterized Insert Query Formatting

## 1. Executive Summary & Purpose
Specifies the construction of parameterized `INSERT` SQL strings and column-to-index parameter mappings in `QueryFormatter`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java`
- **Methods**: the `getInsertQueryUsingInputFunction(String tableName, List<Field> fields, Map<String, String> columnNameToDataTypeMap, boolean includeKafkaMetaData, boolean includeRawData, String rawDataColumn, String dbName, String deleteColumn, List<Field> schemaFields, String versionColumn, String signColumn)` overloads (the shorter overloads delegate with `null` engine columns); `getInsertQueryForUpdate(...)`, `getInsertQueryForDelete(...)`
- **Caller carrying the resolved names**: `GroupInsertQueryWithBatchRecords(String versionColumn, String signColumn, String deleteColumn)` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`, constructed by `ClickHouseBatchRunnable` / `ClickHouseBatchWriter` from `DbWriter.getVersionColumn()`, `getSignColumn()`, `getReplacingMergeTreeDeleteColumn()` (spec 08.01)
- **Bind-time backstop**: `PreparedStatementFieldMapper.requireEngineColumnPlaceholder` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`

---

## 3. Operational Specification

### 3.1 Column List Generation
For a given table $T$ and record schema $S$:
1. Identifies the intersection of ClickHouse physical columns (`columnNameToDataTypeMap`, spec 08.01) and CDC record fields, honouring the field-membership rules of spec 04.03.
2. Appends the engine columns present in the ClickHouse table:
   - the version column: the name **resolved from the engine clause** (`ReplacingMergeTree(ver)` → `ver`, spec 08.01 §3.1) as well as the default `_version`,
   - the delete column: the resolved one for the new-style engine (`ReplacingMergeTree(ver, removed)` → `removed`), else the configured `replacingmergetree.delete.column`, as well as the default `is_deleted`,
   - the sign column: the name resolved from `CollapsingMergeTree(sgn)` as well as the default `_sign`,
   - Kafka metadata columns when `includeKafkaMetaData`, and the raw-data column when `includeRawData`.

   Engine columns are never carried by the source record, so membership cannot come from the record's schema: they are retained by name. Recognising them only by the default constants left a differently named engine column out of the INSERT, and ClickHouse stored the type default for it in every row — `ver = 0`, so a redelivered older row won every merge; `sgn = 0`, so no `+1`/`-1` pair ever collapsed; a non-default delete column at its default, so a DELETE inserted a live row. The resolved names therefore travel from `DbWriter` through `GroupInsertQueryWithBatchRecords` into this method on every production path, and `PreparedStatementFieldMapper` refuses (with `IllegalStateException`) to bind a row whose engine column exists in the table but has no placeholder in the INSERT — except in replication-history mode, whose SCD Type 2 statement emits those columns as SQL literals by design.
3. Generates SQL text with `String.format("INSERT INTO %s(%s) VALUES (%s)", ...)`:
   ```sql
   INSERT INTO db.table(col1, col2, ..., _version, is_deleted) VALUES (?, ?, ..., ?, ?)
   ```
4. Produces the parameter mapping `Map<String, Integer> columnNameToIndexMap` (1-indexed for JDBC `setObject`) and returns it with the SQL as a `MutablePair`.

---

## 4. Invariants Preserved
- **Parameterized values**: values are passed only through JDBC positional placeholders (`?`), never interpolated into the SQL text.

---

## 5. Verification Criteria
- `QueryFormatterTest.testGetInsertQueryUsingInputFunctionWithKafkaMetaDataEnabled()`, `QueryFormatterTest.testGetInsertQueryUsingInputFunctionWithKafkaMetaDataDisabled()`, `QueryFormatterTest.testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnNotProvided()`, `QueryFormatterTest.testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnProvided()`.
- `QueryFormatterOperationColumnTest`, `QueryFormatterNullValueDropTest`, `QueryFormatterPreAlterRecordTest`.
- `QueryFormatterNullValueDropTest.testConnectorManagedColumnsAlwaysRetained()` — §3.1 step 2: `_version` / `is_deleted` and the resolved `ver` / `sgn` / `removed` are all retained as parameters.
- `GroupInsertQueryWithBatchRecordsTest.resolvedEngineColumnsAreBoundParameters()` — the resolved names handed to the grouper reach the INSERT.
- `PreparedStatementFieldMapperEngineColumnTest.testNonStandardVersionColumnIsBound()`, `PreparedStatementFieldMapperEngineColumnTest.testVersionColumnWithoutPlaceholderFailsLoudly()`, `PreparedStatementFieldMapperEngineColumnTest.testDeleteColumnWithoutPlaceholderFailsLoudly()`, `PreparedStatementFieldMapperEngineColumnTest.testNonStandardSignColumnIsBoundOrRefused()`, `PreparedStatementFieldMapperEngineColumnTest.testHistoryModeStatementWithLiteralEngineColumnsIsAccepted()` — the bind-time backstop and its history-mode exemption.
- `PreparedStatementFieldMapperEngineColumnTest.testDeleteForTableWithoutDeleteColumnIsRefused()`, `PreparedStatementFieldMapperEngineColumnTest.testInsertForTableWithoutDeleteColumnIsAccepted()` — a delete column absent from the TABLE (not merely from the INSERT) refuses only the rows that need the delete marker (spec 08.01 §3.2).

---

## 6. Failure Modes & Recovery

Recovery posture: the INSERT text is a deterministic function of the table name, the column map and the record schema, so a statement ClickHouse cannot parse fails identically on every retry; values travel only as parameters, but the V2 driver substitutes them into one SQL string per chunk before sending, so the statement's size is bounded by the JVM, not by ClickHouse. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-04.02-1 Identifier containing a backtick**
  - **Trigger**: a MySQL table or column name containing a backtick character (legal in MySQL, written doubled inside a quoted identifier) is replicated; below, BT stands for that character.
  - **Behaviour**: `QueryFormatter.createColumns` and `getInsertQueryUsingInputFunction` wrap every name in BT without escaping an embedded BT, so a column named aBTb is emitted as BT a BT b BT (without the spaces), which does not parse; ClickHouse refuses the INSERT with `Code: 62 ... Syntax error` (measured with `clickhouse local` 24.8.14; the form with the embedded BT doubled is accepted). 62 is not in `FATAL_ERROR_CODES`: the worker retries forever. Names with spaces or reserved words are quoted correctly.
  - **Detection**: ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>)` with the `Code: 62` message, WARN `Retriable ClickHouse error (Code: 62, Category: RETRIABLE)` every ≤ 30 s; metric `clickhouse.sink.topics.error.records`; no exit.
  - **Blast radius**: every table hashed to that worker stops; offsets freeze; the next DDL drain waits forever; nothing lost.
  - **Recovery**: rename the source column (the `ALTER TABLE ... RENAME COLUMN` replicates through the DDL path — spec 06.x) or exclude it (`column.exclude.list`); the retained batch still carries the old name, so P-SKIP the transaction and P-RESYNC the table.
  - **RTO**: unbounded until an operator acts; then P-SKIP + resync; unmeasured.
  - **Test**: `QueryFormatterIdentifierQuotingTest.backtickInIdentifierIsEscaped()` (disabled, fails on 2.11.0), `QueryFormatterIdentifierQuotingTest.spaceAndReservedWordAreQuoted()` (the correct half), `PoisonValueClassificationTest.syntaxErrorInAGeneratedStatementIsFatal()` (disabled).
  - **DEFECT**: identifiers are not escaped, and the resulting deterministic `SYNTAX_ERROR` is retried forever. Fix: double embedded backticks in every generated identifier (INSERT, TRUNCATE, history statements).

- **FM-04.02-2 INSERT column list longer than `max_query_size`**
  - **Trigger**: a very wide table: MySQL allows 4096 columns of up to 64-character names, so the `INSERT INTO t(<columns>) VALUES` header can exceed ClickHouse's `max_query_size` (262144 bytes by default).
  - **Behaviour**: ClickHouse parses the INSERT header with the SQL parser under `max_query_size` (the VALUES data is streamed and not counted); measured with `clickhouse local` 24.8.14: a 4096-column, 64-character-name header of 274446 bytes fails with `Code: 62 ... Syntax error: failed at position 262119`. 62 is RETRIABLE: retried forever.
  - **Detection**: as FM-04.02-1 (`Code: 62`, failing near position 262144).
  - **Blast radius**: as FM-04.02-1, for every batch of that table.
  - **Recovery**: raise the limit for the connector's statements — `clickhouse.jdbc.settings` with `max_query_size=1048576` (keep `input_format_null_as_default=0` in the list, spec 07.07 §3.2.1) or a settings profile for the connector user — and restart; the batch is redelivered and written, nothing to resync.
  - **RTO**: config change + restart ≈ 1–2 min + re-apply of the in-flight transaction; unmeasured end to end.
  - **Test**: GAP: a unit test that the generated header length is compared with the effective `max_query_size` and refused with a message naming the setting, instead of a retried `SYNTAX_ERROR`.
  - **DEFECT**: the failure is retried forever and its message does not name the setting that fixes it.

- **FM-04.02-3 Statement too large for the JVM (chunk rendered as one string)**
  - **Trigger**: wide rows (BLOB/TEXT/JSON) or many rows in one chunk; `thread.pool.size` workers render chunks concurrently.
  - **Behaviour**: `BatchChunker.chunk` closes a chunk at `buffer.max.records` (default 100000) or `buffer.max.bytes` (default 256 MiB of estimated heap per chunk, `RecordSizeEstimator`); a single row wider than the byte limit is its own chunk. The V2 driver builds each row's VALUES tuple in `addBatch` and the whole chunk's SQL in one `StringBuilder` in `executeInsertBatch` (jdbc-v2 0.9.8 bytecode), so peak heap per worker is several copies of the chunk text; a string cannot exceed about 2^31 bytes. An `OutOfMemoryError` escapes `ClickHouseBatchRunnable.run` (`catch (Exception e)`), the worker dies and the process exits 3. With 10 workers × 256 MiB estimated chunks the shipped `-Xmx4G` can be exceeded (unmeasured).
  - **Detection**: `java.lang.OutOfMemoryError` in the log, `Sink worker <i> of <n> is dead ...`, FATAL `Replication is STOPPED: ...`, exit 3 within ≤ 5 s; repeats on restart until systemd gives up (5 starts / 300 s).
  - **Blast radius**: the whole connector stops; nothing lost (offsets were not committed past the chunk).
  - **Recovery**: lower `buffer.max.bytes` and/or `buffer.max.records` or `thread.pool.size`, or raise `-Xmx`; restart. A single row too big for any heap: spec 07.05 §6 FM-07.05-1.
  - **RTO**: config change + restart ≈ 1–2 min + re-apply of the in-flight transaction; unmeasured.
  - **Test**: `BatchChunkerTest.byteLimitClosesEarly()`, `BatchChunkerTest.oversizedRowIsItsOwnChunk()`; GAP: a heap-bounded harness that runs the default `thread.pool.size` × `buffer.max.bytes` against the default heap.

- **FM-04.02-4 Engine column absent from the INSERT**
  - **Trigger**: the table's version/sign/delete column is named in the engine clause but the resolved name did not reach the formatter (a new code path constructing `GroupInsertQueryWithBatchRecords` without the resolved names), or a DELETE arrives for a table without a delete column.
  - **Behaviour**: `PreparedStatementFieldMapper.requireEngineColumnPlaceholder` throws `IllegalStateException` instead of letting ClickHouse store the type default (§3.1 step 2); UNKNOWN, retried forever.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `The <engine> <role> column '<c>' exists in the ClickHouse table but the generated INSERT has no placeholder for it ... Refusing to write the row.` (or the delete-column refusal of spec 08.01 §3.2), WARN `Retriable ... Category: UNKNOWN` every ≤ 30 s; metric `clickhouse.sink.topics.error.records`; no exit.
  - **Blast radius**: the worker's tables stop; nothing lost.
  - **Recovery**: for a table without a delete column, add it (`ALTER TABLE ... ADD COLUMN is_deleted UInt8` and recreate the engine, spec 08.01 §3.2) or recreate the table with the connector's engine; a missing resolved name is a code defect (downgrade to the previous release); restart.
  - **RTO**: unbounded until an operator acts; unmeasured.
  - **Test**: `PreparedStatementFieldMapperEngineColumnTest.testVersionColumnWithoutPlaceholderFailsLoudly()`, `PreparedStatementFieldMapperEngineColumnTest.testDeleteForTableWithoutDeleteColumnIsRefused()`.
  - **DEFECT**: as FM-04.01-1 — a deterministic refusal retried forever with no exit.

Summary: 4 failure modes, 3 DEFECT, 2 GAP.
