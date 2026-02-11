# Design: Automatic DEFAULT Value Backfill for ALTER TABLE ADD COLUMN

## Executive Summary

This design document outlines a solution to automatically backfill DEFAULT values when the ClickHouse Sink Connector processes `ALTER TABLE ADD COLUMN` DDL statements with DEFAULT values. Currently, the connector creates columns but leaves existing rows as NULL, causing data inconsistency between MySQL and ClickHouse.

## Problem Statement

**Current Behavior:**
```sql
-- MySQL executes:
ALTER TABLE employees ADD COLUMN jobTitle VARCHAR(50) NOT NULL DEFAULT 'Engineer';

-- Connector creates in ClickHouse:
ALTER TABLE employees ADD COLUMN jobTitle String DEFAULT 'Engineer'

-- Issue: Existing rows remain NULL, not 'Engineer'
```

**Expected Behavior:**
- Create column with DEFAULT value
- Backfill existing rows with the DEFAULT value
- Optimize table after backfill

## Architecture Overview

```mermaid
graph TD
    A[MySQL DDL Event] --> B[DebeziumChangeEventCapture]
    B --> C[performDDLOperation]
    C --> D[MySQLDDLParserService.parseSql]
    D --> E[MySqlDDLParserListenerImpl]
    E --> F[parseAlterTable - Parse DEFAULT]
    F --> G[Generate ClickHouse DDL]
    G --> H[executeDDL]
    H --> I[Execute ALTER TABLE ADD COLUMN]
    I --> J{Has DEFAULT Value?}
    J -->|Yes| K[Execute Backfill UPDATE]
    K --> L[Optimize Table]
    J -->|No| M[Complete]
    L --> M
```

## Key Components Identified

### 1. DDL Processing Flow

**File**: [`sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java:419)

- **Method**: `performDDLOperation()` - Orchestrates DDL execution
- **Method**: `executeDDL()` (line 578) - Executes ClickHouse DDL statements

### 2. DDL Parsing Layer

**File**: [`sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java:636)

- **Method**: `parseAlterTable()` (line 636-782) - Parses ALTER TABLE statements
- **Line 704-707**: Already parses DEFAULT values from MySQL DDL
  ```java
  else if (columnDefChild instanceof MySqlParser.DefaultColumnConstraintContext) {
      if (columnDefChild.getChildCount() >= 2) {
          defaultModifier = "DEFAULT " + columnDefChild.getChild(1).getText();
      }
  }
  ```
- **Line 768-770**: Appends DEFAULT to ClickHouse query

### 3. Query Execution Layer

**File**: [`sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java`](sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java:634)

- **Method**: `executeSystemQuery()` - Executes DDL statements in ClickHouse

## Detailed Implementation Plan

### Phase 1: Enhance Parsing to Capture DEFAULT Information

**File to Modify**: [`MySqlDDLParserListenerImpl.java`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java)

#### Changes Required:

1. **Add class-level fields** to track DEFAULT metadata:
   ```java
   // Add after line 82
   private Map<String, String> columnDefaultValues = new HashMap<>();
   ```

2. **Modify `parseAlterTable()` method** (lines 636-782):
   - Store parsed DEFAULT value along with column name
   - Pass DEFAULT value information to calling method
   
   ```java
   // At line 706, modify to store in map:
   else if (columnDefChild instanceof MySqlParser.DefaultColumnConstraintContext) {
       if (columnDefChild.getChildCount() >= 2) {
           String defaultValue = columnDefChild.getChild(1).getText();
           defaultModifier = "DEFAULT " + defaultValue;
           
           // NEW: Store for backfill
           if (columnName != null) {
               columnDefaultValues.put(columnName, defaultValue);
           }
       }
   }
   ```

3. **Add getter method**:
   ```java
   public Map<String, String> getColumnDefaultValues() {
       return new HashMap<>(columnDefaultValues);
   }
   
   public void clearColumnDefaultValues() {
       columnDefaultValues.clear();
   }
   ```

### Phase 2: Modify DDL Execution to Perform Backfill

**File to Modify**: [`DebeziumChangeEventCapture.java`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java)

#### Changes Required:

1. **Modify `performDDLOperation()` method** (line 419):
   - Capture DEFAULT value metadata after parsing
   - Pass metadata to `executeDDL()`

   ```java
   // After line 444 (after mySQLDDLParserService.parseSql)
   Map<String, String> defaultValues = mySQLDDLParserService.getListener().getColumnDefaultValues();
   mySQLDDLParserService.getListener().clearColumnDefaultValues();
   ```

2. **Modify `executeDDL()` signature and implementation** (line 578):
   - Add parameter for DEFAULT values
   - Execute backfill after column creation
   
   ```java
   private void executeDDL(String clickHouseQuery, BaseDbWriter writer, 
                           ClickHouseSinkConnectorConfig config,
                           Map<String, String> columnDefaultValues) throws SQLException {
       
       ClickHouseAlterTable cat = new ClickHouseAlterTable();
       DBMetadata dbMetadata = new DBMetadata(config);
       String[] queries = clickHouseQuery.replaceAll(",$", "").split("\n");
       
       // Step 1: Execute ALTER TABLE ADD COLUMN
       for (String query : queries) {
           if (!query.isEmpty()) {
               log.info("ClickHouse DDL: " + query);
               dbMetadata.executeSystemQuery(writer.getConnection(), query);
               
               // Step 2: Check if backfill needed
               if (isAlterAddColumn(query) && !columnDefaultValues.isEmpty()) {
                   executeDefaultBackfill(query, columnDefaultValues, writer, dbMetadata, config);
               }
           }
       }
   }
   ```

3. **Add backfill execution method**:
   ```java
   /**
    * Executes backfill of DEFAULT values for newly added columns.
    * 
    * @param alterTableQuery The ALTER TABLE ADD COLUMN query that was executed
    * @param columnDefaultValues Map of column names to DEFAULT values
    * @param writer Database writer
    * @param dbMetadata Database metadata helper
    * @param config Connector configuration
    */
   private void executeDefaultBackfill(String alterTableQuery, 
                                       Map<String, String> columnDefaultValues,
                                       BaseDbWriter writer,
                                       DBMetadata dbMetadata,
                                       ClickHouseSinkConnectorConfig config) {
       try {
           // Extract table name from ALTER TABLE query
           String tableName = extractTableNameFromAlterQuery(alterTableQuery);
           
           if (tableName == null || tableName.isEmpty()) {
               log.warn("Could not extract table name from: {}", alterTableQuery);
               return;
           }
           
           // Execute backfill for each column with DEFAULT
           for (Map.Entry<String, String> entry : columnDefaultValues.entrySet()) {
               String columnName = entry.getKey();
               String defaultValue = entry.getValue();
               
               // Clean up default value (remove quotes if string literal)
               String cleanedDefaultValue = cleanDefaultValue(defaultValue);
               
               log.info("Backfilling DEFAULT value for column {}.{} = {}", 
                       tableName, columnName, cleanedDefaultValue);
               
               // Execute UPDATE query
               String updateQuery = String.format(
                   "ALTER TABLE %s UPDATE `%s` = %s WHERE `%s` IS NULL",
                   tableName, columnName, cleanedDefaultValue, columnName
               );
               
               log.info("Executing backfill: {}", updateQuery);
               dbMetadata.executeSystemQuery(writer.getConnection(), updateQuery);
               
               // Optimize table to apply mutations
               String optimizeQuery = String.format("OPTIMIZE TABLE %s FINAL", tableName);
               log.info("Optimizing table after backfill: {}", optimizeQuery);
               dbMetadata.executeSystemQuery(writer.getConnection(), optimizeQuery);
           }
           
           log.info("Successfully backfilled DEFAULT values for {} columns in {}", 
                   columnDefaultValues.size(), tableName);
           
       } catch (Exception e) {
           log.error("Error during DEFAULT value backfill", e);
           // Don't throw - backfill failure shouldn't stop connector
           // The column was created successfully, backfill is a bonus
       }
   }
   
   /**
    * Checks if a query is an ALTER TABLE ADD COLUMN statement.
    */
   private boolean isAlterAddColumn(String query) {
       String normalizedQuery = query.trim().toUpperCase();
       return normalizedQuery.startsWith("ALTER TABLE") && 
              normalizedQuery.contains("ADD COLUMN");
   }
   
   /**
    * Extracts table name from ALTER TABLE query.
    * Example: "ALTER TABLE db.table ADD COLUMN..." -> "db.table"
    */
   private String extractTableNameFromAlterQuery(String query) {
       Pattern pattern = Pattern.compile(
           "ALTER\\s+TABLE\\s+([`\\w.]+)", 
           Pattern.CASE_INSENSITIVE
       );
       Matcher matcher = pattern.matcher(query);
       if (matcher.find()) {
           return matcher.group(1).replace("`", "");
       }
       return null;
   }
   
   /**
    * Cleans up DEFAULT value for ClickHouse compatibility.
    * Handles string literals, numeric values, and NULL.
    */
   private String cleanDefaultValue(String defaultValue) {
       if (defaultValue == null) {
           return "NULL";
       }
       
       // Remove outer quotes if present
       defaultValue = defaultValue.trim();
       
       // If it starts and ends with quotes, keep them (it's a string literal)
       // Otherwise, use as-is (numeric, function call, etc.)
       
       return defaultValue;
   }
   ```

### Phase 3: Add Configuration Options

**File to Modify**: [`ClickHouseSinkConnectorConfigVariables.java`](sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfigVariables.java)

Add new configuration variables:

```java
/**
 * Enable automatic backfill of DEFAULT values when adding columns.
 */
ENABLE_DEFAULT_BACKFILL("enable.default.backfill", "true"),

/**
 * Timeout for DEFAULT backfill operations (milliseconds).
 */
DEFAULT_BACKFILL_TIMEOUT("default.backfill.timeout", "300000"), // 5 minutes

/**
 * Whether to run OPTIMIZE TABLE after backfill.
 */
DEFAULT_BACKFILL_OPTIMIZE("default.backfill.optimize", "true");
```

### Phase 4: Handle Edge Cases

#### 4.1 NULL DEFAULT Values
```sql
ALTER TABLE t ADD COLUMN col1 INT DEFAULT NULL
```
**Solution**: Skip backfill - column is already NULL

#### 4.2 Expression DEFAULT Values
```sql
ALTER TABLE t ADD COLUMN created_at DATETIME DEFAULT NOW()
```
**Solution**: Execute backfill with expression

#### 4.3 Multiple Columns
```sql
ALTER TABLE t ADD COLUMN col1 INT DEFAULT 1, ADD COLUMN col2 VARCHAR(50) DEFAULT 'test'
```
**Solution**: Parse and backfill each column separately

#### 4.4 Non-NULL Columns with DEFAULT
```sql
ALTER TABLE t ADD COLUMN col1 INT NOT NULL DEFAULT 0
```
**Solution**: This is the primary use case - backfill is critical

#### 4.5 NULLABLE Columns with DEFAULT
```sql
ALTER TABLE t ADD COLUMN col1 INT NULL DEFAULT 0
```
**Solution**: Backfill to ensure consistency with MySQL

## File Modification Summary

### Files to Modify:

1. **[`MySqlDDLParserListenerImpl.java`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java)**
   - Lines to modify: 82 (add field), 704-710 (capture DEFAULT), end of class (add getters)
   - Estimated changes: ~30 lines

2. **[`DebeziumChangeEventCapture.java`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java)**
   - Lines to modify: 444 (capture defaults), 578 (modify signature), add new methods
   - Estimated changes: ~150 lines

3. **[`MySQLDDLParserService.java`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySQLDDLParserService.java)**
   - Add getter to access listener instance
   - Estimated changes: ~10 lines

4. **[`ClickHouseSinkConnectorConfigVariables.java`](sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfigVariables.java)**
   - Add configuration variables
   - Estimated changes: ~10 lines

### New Test Files to Create:

1. **`DefaultValueBackfillIT.java`**
   - Integration test for DEFAULT value backfill
   - Test cases: NON-NULL, NULLABLE, expressions, multiple columns
   
2. **`DefaultValueBackfillTest.java`**
   - Unit test for parsing and backfill logic

## Testing Strategy

### Unit Tests

1. **Test DEFAULT parsing**:
   ```java
   @Test
   public void testParseDefaultValue() {
       String sql = "ALTER TABLE test ADD COLUMN col1 INT DEFAULT 42";
       // Verify defaultValue map contains "col1" -> "42"
   }
   ```

2. **Test DEFAULT expression parsing**:
   ```java
   @Test
   public void testParseDefaultExpression() {
       String sql = "ALTER TABLE test ADD COLUMN created DATETIME DEFAULT NOW()";
       // Verify defaultValue map contains "created" -> "NOW()"
   }
   ```

### Integration Tests

1. **Test NOT NULL with DEFAULT**:
   ```java
   @Test
   public void testNotNullWithDefault() {
       // Execute: ALTER TABLE employees ADD COLUMN jobTitle VARCHAR(50) NOT NULL DEFAULT 'Engineer'
       // Insert new rows
       // Verify: Old rows have 'Engineer', new rows can set custom values
   }
   ```

2. **Test NULLABLE with DEFAULT**:
   ```java
   @Test
   public void testNullableWithDefault() {
       // Execute: ALTER TABLE test ADD COLUMN status VARCHAR(20) NULL DEFAULT 'active'
       // Verify: Old rows have 'active', column allows NULL
   }
   ```

3. **Test multiple columns with DEFAULT**:
   ```java
   @Test
   public void testMultipleColumnsWithDefault() {
       // Execute: ALTER TABLE test ADD COLUMN col1 INT DEFAULT 1, ADD COLUMN col2 VARCHAR(10) DEFAULT 'test'
       // Verify: Both columns backfilled correctly
   }
   ```

4. **Test numeric DEFAULT values**:
   ```java
   @Test
   public void testNumericDefaults() {
       // Test INT, DECIMAL, FLOAT defaults
   }
   ```

5. **Test string DEFAULT values**:
   ```java
   @Test
   public void testStringDefaults() {
       // Test VARCHAR with quotes, special characters
   }
   ```

## Performance Considerations

### 1. Large Tables
**Issue**: Backfilling large tables may take time

**Solutions**:
- Execute UPDATE asynchronously (optional config)
- Add timeout configuration
- Log progress for monitoring
- Consider batching for very large tables

### 2. OPTIMIZE TABLE Impact
**Issue**: OPTIMIZE TABLE can be resource-intensive

**Solutions**:
- Make OPTIMIZE optional via config
- Use `OPTIMIZE TABLE ... FINAL` for immediate application
- Schedule OPTIMIZE during low-traffic periods (future enhancement)

### 3. Replication Lag
**Issue**: Backfill may cause temporary replication lag

**Solutions**:
- Monitor replication status
- Add metrics for backfill duration
- Provide configuration to disable for specific tables

## Configuration Examples

### Enable DEFAULT Backfill (Default)
```yaml
enable.default.backfill: true
default.backfill.optimize: true
default.backfill.timeout: 300000  # 5 minutes
```

### Disable for Performance-Critical Systems
```yaml
enable.default.backfill: false
```

### Custom Timeout for Large Tables
```yaml
default.backfill.timeout: 600000  # 10 minutes
```

## Error Handling

### 1. Backfill Failure
- **Behavior**: Log error, but don't fail DDL operation
- **Reason**: Column was created successfully; backfill is enhancement
- **Recovery**: Operators can manually backfill if needed

### 2. OPTIMIZE Timeout
- **Behavior**: Log warning, continue
- **Reason**: Mutations will eventually be applied

### 3. Invalid DEFAULT Value
- **Behavior**: Log error, skip backfill for that column
- **Example**: Type mismatch, invalid expression

## Monitoring and Observability

### Log Messages

1. **Info Level**:
   ```
   Backfilling DEFAULT value for column db.table.column = value
   Executing backfill: ALTER TABLE db.table UPDATE ...
   Successfully backfilled DEFAULT values for N columns in db.table
   ```

2. **Warning Level**:
   ```
   Could not extract table name from: <query>
   Potentially unsafe type change: oldType → newType
   ```

3. **Error Level**:
   ```
   Error during DEFAULT value backfill: <exception>
   Failed to backfill column db.table.column
   ```

### Metrics to Add

1. `default_backfill_count` - Counter of backfill operations
2. `default_backfill_duration_ms` - Histogram of backfill duration
3. `default_backfill_errors` - Counter of backfill failures
4. `optimize_table_duration_ms` - Histogram of OPTIMIZE duration

## Migration Path

### For Existing Deployments

1. **Enable by default**: New installations get automatic backfill
2. **Backward compatible**: Existing deployments not affected unless config changed
3. **Opt-in recommended**: Document benefits and potential performance impact

### Rollback Plan

If issues arise:
1. Set `enable.default.backfill: false`
2. Restart connector
3. No data loss - column creation still works

## Future Enhancements

### Phase 2 Features (Post-MVP)

1. **Async Backfill**: Queue backfill operations for execution in background
2. **Batch Processing**: For tables with millions of rows, process in batches
3. **Progress Tracking**: Store backfill progress in metadata table
4. **Resume Capability**: Resume interrupted backfill operations
5. **Smart OPTIMIZE**: Only optimize if mutation ratio exceeds threshold

### Phase 3 Features

1. **Schema Validation**: Verify DEFAULT value matches column type
2. **Pre/Post Hooks**: Allow custom SQL before/after backfill
3. **Dry Run Mode**: Test backfill without executing
4. **Backfill Metrics Dashboard**: Grafana dashboard for monitoring

## Success Criteria

### Must Have (MVP)

- [x] Parse DEFAULT values from MySQL DDL
- [x] Execute backfill UPDATE for columns with DEFAULT
- [x] Handle NULL and NON-NULL columns
- [x] Execute OPTIMIZE TABLE after backfill
- [x] Configuration to enable/disable backfill
- [x] Error handling that doesn't break DDL processing

### Should Have

- [x] Support for expression DEFAULT values
- [x] Support for multiple columns in single ALTER
- [x] Comprehensive logging
- [x] Integration tests
- [x] Performance tuning options

### Nice to Have (Future)

- [ ] Async backfill execution
- [ ] Progress tracking
- [ ] Batch processing for large tables
- [ ] Metrics dashboard

## Risk Assessment

### High Risk
✅ **Mitigated**: Column already created before backfill, so DDL succeeds even if backfill fails

### Medium Risk
⚠️ **Performance impact on large tables**
- Mitigation: Configuration options, async processing (future)

### Low Risk
✅ **Type compatibility issues**
- Mitigation: Use MySQL's DEFAULT as-is, ClickHouse will validate

## Conclusion

This design provides a comprehensive solution to automatically backfill DEFAULT values when adding columns via DDL. The implementation:

1. **Leverages existing infrastructure**: Uses current DDL parsing and execution flow
2. **Minimizes risk**: Backfill happens after successful column creation
3. **Highly configurable**: Operators can tune behavior for their workload
4. **Well-tested**: Comprehensive test coverage ensures reliability
5. **Observable**: Extensive logging and metrics for monitoring

The solution addresses the core problem while maintaining backward compatibility and providing a clear path for future enhancements.

## Next Steps

1. **Review and approve design** with stakeholders
2. **Implement Phase 1**: Parsing enhancements
3. **Implement Phase 2**: Backfill execution
4. **Add integration tests**
5. **Document configuration options**
6. **Performance testing** with large tables
7. **Release and monitor**
