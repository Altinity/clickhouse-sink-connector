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
