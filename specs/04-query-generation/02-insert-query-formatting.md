# Spec 04.02: Parameterized Insert Query Formatting

## 1. Executive Summary & Purpose
Specifies the construction of parameterized `INSERT` SQL strings and column-to-index parameter mappings in `QueryFormatter`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java`
- **Methods**: the `getInsertQueryUsingInputFunction(String tableName, List<Field> fields, Map<String, String> columnNameToDataTypeMap, boolean includeKafkaMetaData, boolean includeRawData, String rawDataColumn, String dbName, String deleteColumn, List<Field> schemaFields)` overloads (there is no `createColumns()` method); `getInsertQueryForUpdate(...)`, `getInsertQueryForDelete(...)`

---

## 3. Operational Specification

### 3.1 Column List Generation
For a given table $T$ and record schema $S$:
1. Identifies the intersection of ClickHouse physical columns (`columnNameToDataTypeMap`, spec 08.01) and CDC record fields, honouring the field-membership rules of spec 04.03.
2. Appends the engine columns present in the ClickHouse table:
   - the version column (`_version` by default),
   - the delete column (`is_deleted`, or the configured `replacingmergetree.delete.column`),
   - `_sign` for `CollapsingMergeTree` targets,
   - Kafka metadata columns when `includeKafkaMetaData`, and the raw-data column when `includeRawData`.
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
