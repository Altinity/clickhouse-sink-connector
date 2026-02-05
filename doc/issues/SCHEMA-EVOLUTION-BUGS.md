# Schema Evolution Bugs

This document details all issues related to detecting and handling schema changes (DDL operations) in the ClickHouse Sink Connector.

## Overview

**Total Bugs:** 5  
**Severity:** HIGH (4), MEDIUM (1)  
**Affected Component:** Schema change detection, DDL synchronization  
**Production Impact:** Schema drift, data corruption, silent failures

## Summary Table

| ID | Issue | Severity | Impact | Status |
|----|-------|----------|--------|--------|
| BUG-SCHEMA-1 | ALTER DROP COLUMN Dead Code | HIGH | Feature not working | Dead code |
| BUG-SCHEMA-2 | Column Rename Not Detected | HIGH | Data to wrong column | Not implemented |
| BUG-SCHEMA-3 | Type Change Not Supported | HIGH | Type mismatch errors | Not implemented |
| BUG-SCHEMA-4 | Case Sensitivity Mismatch | MEDIUM | Column mapping fails | Inconsistent |
| BUG-SCHEMA-5 | Reserved Keywords Not Escaped | HIGH | SQL syntax errors | Missing escaping |

---

## BUG-SCHEMA-1: ALTER TABLE DROP COLUMN Dead Code

**Severity:** HIGH  
**Location:** [`ClickHouseAlterTable.java:120-150`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseAlterTable.java)

### Current Code

```java
public class ClickHouseAlterTable {
    // Line 120-150
    public void handleDropColumn(String tableName, String columnName) {
        String sql = String.format("ALTER TABLE %s DROP COLUMN %s", tableName, columnName);
        
        log.info("Dropping column {} from table {}", columnName, tableName);
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            log.info("Successfully dropped column {}", columnName);
        } catch (SQLException e) {
            log.error("Failed to drop column {}", columnName, e);
            throw new RuntimeException(e);
        }
    }
}
```

### Problem

This code is **never called**. The schema change detection logic does not detect `DROP COLUMN` operations.

**Analysis of Schema Change Detection:**
```java
// ClickHouseSchemaChangeListener.java
public void onSchemaChange(SchemaChangeEvent event) {
    switch (event.getType()) {
        case ADD_COLUMN:
            handleAddColumn(event);  // ✓ Implemented
            break;
            
        case MODIFY_COLUMN:
            handleModifyColumn(event);  // ✓ Implemented
            break;
            
        case DROP_COLUMN:
            // MISSING! Never calls handleDropColumn()
            log.warn("DROP COLUMN not supported, ignoring");
            break;
            
        default:
            log.warn("Unknown schema change type: {}", event.getType());
    }
}
```

### Impact

**MySQL:**
```sql
ALTER TABLE users DROP COLUMN middle_name;
```

**ClickHouse:** Column NOT dropped, causing:

1. **Schema Mismatch:** ClickHouse has column, MySQL doesn't
2. **NULL Values:** Future inserts set `middle_name` to NULL
3. **Wasted Space:** Column stores NULLs indefinitely
4. **Query Confusion:** Column exists in CH but not in source

### Root Cause

DROP COLUMN is intentionally not supported due to ClickHouse limitations:
- ClickHouse doesn't support DROP COLUMN in MergeTree engine easily
- Requires data rewriting which is expensive
- Risk of data loss

**ClickHouse Limitation:**
```sql
-- This is complex in ClickHouse
ALTER TABLE users DROP COLUMN middle_name;

-- May require:
-- 1. Create new table without column
-- 2. Copy all data
-- 3. Drop old table
-- 4. Rename new table
```

### Proposed Fix

**Option 1: Implement DROP COLUMN (Full Support)**
```java
public void handleDropColumn(String tableName, String columnName) {
    String dropBehavior = config.getDropColumnBehavior();
    
    switch (dropBehavior) {
        case "DROP":
            executeDropColumn(tableName, columnName);
            break;
            
        case "RENAME":
            // Safer: rename to _deleted_<name>
            String newName = "_deleted_" + columnName + "_" + System.currentTimeMillis();
            executeRenameColumn(tableName, columnName, newName);
            log.info("Renamed dropped column {} to {}", columnName, newName);
            break;
            
        case "IGNORE":
            log.warn("DROP COLUMN ignored for {}.{}, column remains in ClickHouse", 
                tableName, columnName);
            break;
            
        case "FAIL":
            throw new SchemaException(
                "DROP COLUMN not allowed by configuration for " + tableName + "." + columnName
            );
            
        default:
            throw new ConfigException("Invalid drop.column.behavior: " + dropBehavior);
    }
}

private void executeDropColumn(String tableName, String columnName) {
    // ClickHouse-safe DROP COLUMN
    try {
        String sql = String.format("ALTER TABLE %s DROP COLUMN %s", tableName, columnName);
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            log.info("Successfully dropped column {}.{}", tableName, columnName);
        }
    } catch (SQLException e) {
        if (e.getMessage().contains("not supported")) {
            // Fallback: recreate table without column
            log.warn("DROP COLUMN not supported, recreating table");
            recreateTableWithoutColumn(tableName, columnName);
        } else {
            throw new SchemaException("Failed to drop column", e);
        }
    }
}

private void recreateTableWithoutColumn(String tableName, String columnName) {
    // 1. Get current table schema
    Map<String, ClickHouseColumn> schema = getTableSchema(tableName);
    schema.remove(columnName);
    
    // 2. Create new table
    String tempTable = tableName + "_temp_" + System.currentTimeMillis();
    createTable(tempTable, schema);
    
    // 3. Copy data (excluding dropped column)
    String columns = String.join(", ", schema.keySet());
    String copySql = String.format(
        "INSERT INTO %s (%s) SELECT %s FROM %s",
        tempTable, columns, columns, tableName
    );
    executeSQL(copySql);
    
    // 4. Atomic swap
    String oldTable = tableName + "_old";
    executeSQL(String.format("RENAME TABLE %s TO %s, %s TO %s", 
        tableName, oldTable, tempTable, tableName));
    
    // 5. Drop old table
    executeSQL(String.format("DROP TABLE %s", oldTable));
    
    log.info("Recreated table {} without column {}", tableName, columnName);
}
```

**Option 2: Wire Up Existing Code**
```java
public void onSchemaChange(SchemaChangeEvent event) {
    switch (event.getType()) {
        case ADD_COLUMN:
            handleAddColumn(event);
            break;
            
        case MODIFY_COLUMN:
            handleModifyColumn(event);
            break;
            
        case DROP_COLUMN:
            // NOW IMPLEMENTED!
            ClickHouseAlterTable alterTable = new ClickHouseAlterTable(connection, config);
            alterTable.handleDropColumn(event.getTableName(), event.getColumnName());
            
            // Update schema cache
            DBMetadata.removeColumn(event.getTableName(), event.getColumnName());
            break;
            
        default:
            log.warn("Unknown schema change type: {}", event.getType());
    }
}
```

### Configuration

```properties
# How to handle DROP COLUMN
clickhouse.drop.column.behavior=RENAME  # DROP | RENAME | IGNORE | FAIL

# Whether to recreate table if DROP not supported
clickhouse.drop.column.recreate.table=false

# Prefix for renamed dropped columns
clickhouse.drop.column.rename.prefix=_deleted_
```

### Testing Requirements

1. Test DROP COLUMN detection from Debezium
2. Test each behavior: DROP, RENAME, IGNORE, FAIL
3. Test table recreation if needed
4. Verify data integrity after drop
5. Test rollback on failure

---

## BUG-SCHEMA-2: Column Rename Not Detected

**Severity:** HIGH  
**Location:** [`ClickHouseSchemaChangeListener.java:80-100`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseSchemaChangeListener.java)

### Problem

Column renames are not detected as schema changes. Data continues to be written to the old column name.

**MySQL:**
```sql
-- Rename column
ALTER TABLE users CHANGE COLUMN first_name given_name VARCHAR(100);
```

**Current Behavior:**
1. Debezium may not emit rename event (depends on configuration)
2. Connector sees it as DROP + ADD
3. ClickHouse not updated
4. Data written to wrong column

**Result:**
```
MySQL columns:  id, given_name, last_name
ClickHouse:     id, first_name, last_name

INSERT: {id: 1, given_name: "John", last_name: "Doe"}
↓
ClickHouse: first_name = NULL (wrong!), last_name = "Doe"
```

### Impact

- **Data Loss:** Values written to non-existent column
- **NULL Values:** New column name not populated
- **Silent Failure:** No error raised
- **Schema Drift:** Schemas diverge over time

### Detection Challenge

Debezium may report rename as:
- **Option A:** Rename event (if supported)
- **Option B:** DROP `first_name` + ADD `given_name` (two separate events)

### Proposed Fix

```java
public class ClickHouseSchemaChangeListener {
    // Track recent schema changes to detect rename patterns
    private Map<String, DropColumnEvent> recentDrops = new ConcurrentHashMap<>();
    
    public void onSchemaChange(SchemaChangeEvent event) {
        switch (event.getType()) {
            case DROP_COLUMN:
                // Remember drop for possible rename detection
                DropColumnEvent drop = new DropColumnEvent(
                    event.getTableName(), 
                    event.getColumnName(),
                    System.currentTimeMillis()
                );
                recentDrops.put(event.getTableName() + "." + event.getColumnName(), drop);
                
                // Schedule cleanup
                scheduleDropCleanup(drop, 5000);  // 5 second window
                break;
                
            case ADD_COLUMN:
                // Check if this is actually a rename
                String tableCol = event.getTableName() + ".*";
                
                Optional<DropColumnEvent> recentDrop = findRecentDrop(
                    event.getTableName(), 
                    event.getColumnDefinition()
                );
                
                if (recentDrop.isPresent()) {
                    // This is a RENAME!
                    handleColumnRename(
                        event.getTableName(),
                        recentDrop.get().getColumnName(),  // old name
                        event.getColumnName()              // new name
                    );
                    
                    recentDrops.remove(recentDrop.get().getKey());
                } else {
                    // Actual new column
                    handleAddColumn(event);
                }
                break;
                
            case RENAME_COLUMN:
                // Direct rename event (if Debezium supports it)
                handleColumnRename(
                    event.getTableName(),
                    event.getOldColumnName(),
                    event.getNewColumnName()
                );
                break;
        }
    }
    
    private Optional<DropColumnEvent> findRecentDrop(String tableName, ColumnDefinition newCol) {
        return recentDrops.values().stream()
            .filter(drop -> drop.getTableName().equals(tableName))
            .filter(drop -> drop.getColumnType().equals(newCol.getType()))
            .filter(drop -> System.currentTimeMillis() - drop.getTimestamp() < 5000)
            .findFirst();
    }
    
    private void handleColumnRename(String tableName, String oldName, String newName) {
        log.info("Detected column rename: {}.{} → {}", tableName, oldName, newName);
        
        String renameBehavior = config.getColumnRenameBehavior();
        
        switch (renameBehavior) {
            case "RENAME":
                // Execute ClickHouse RENAME
                executeRename(tableName, oldName, newName);
                break;
                
            case "ADD_DROP":
                // Add new column, copy data, drop old
                executeAddDropRename(tableName, oldName, newName);
                break;
                
            case "IGNORE":
                log.warn("Column rename ignored, schema will drift");
                break;
                
            case "FAIL":
                throw new SchemaException("Column rename not allowed: " + oldName + " → " + newName);
                
            default:
                throw new ConfigException("Invalid column.rename.behavior: " + renameBehavior);
        }
        
        // Update schema cache
        DBMetadata.renameColumn(tableName, oldName, newName);
    }
    
    private void executeRename(String tableName, String oldName, String newName) {
        String sql = String.format("ALTER TABLE %s RENAME COLUMN %s TO %s", 
            tableName, oldName, newName);
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            log.info("Renamed column {}.{} to {}", tableName, oldName, newName);
        } catch (SQLException e) {
            log.error("Failed to rename column", e);
            throw new SchemaException("Column rename failed", e);
        }
    }
}
```

### Configuration

```properties
# How to handle column renames
clickhouse.column.rename.behavior=RENAME  # RENAME | ADD_DROP | IGNORE | FAIL

# Time window to detect DROP+ADD as rename (milliseconds)
clickhouse.rename.detection.window=5000
```

### Testing Requirements

1. Test direct RENAME COLUMN
2. Test DROP+ADD pattern detection
3. Test rename with data preservation
4. Test rename with different types (should fail)
5. Test concurrent renames

---

## BUG-SCHEMA-3: Type Change Not Supported

**Severity:** HIGH  
**Location:** [`ClickHouseSchemaChangeListener.java:100-120`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseSchemaChangeListener.java)

### Problem

Column type changes (e.g., `INT` → `BIGINT`, `VARCHAR(50)` → `VARCHAR(100)`) are not handled.

**MySQL:**
```sql
ALTER TABLE users MODIFY COLUMN age BIGINT;  -- was INT
ALTER TABLE users MODIFY COLUMN email VARCHAR(255);  -- was VARCHAR(100)
```

**Current Behavior:** Connector ignores the change, causing:
1. Type mismatch on insert
2. Data truncation
3. Insert failures

### Impact

**Example 1: Size Increase**
```sql
-- MySQL: VARCHAR(50) → VARCHAR(255)
INSERT INTO users (email) VALUES ('very.long.email.address@example.com');

-- ClickHouse still has String with old length limit
-- May truncate or fail depending on configuration
```

**Example 2: Type Change**
```sql
-- MySQL: INT → BIGINT
INSERT INTO stats (counter) VALUES (3000000000);  -- > INT max

-- ClickHouse still Int32
-- ERROR: Value too large for Int32
```

### Proposed Fix

```java
public void handleModifyColumn(SchemaChangeEvent event) {
    String tableName = event.getTableName();
    String columnName = event.getColumnName();
    ColumnDefinition oldDef = getColumnDefinition(tableName, columnName);
    ColumnDefinition newDef = event.getColumnDefinition();
    
    // Detect what changed
    boolean typeChanged = !oldDef.getType().equals(newDef.getType());
    boolean sizeChanged = oldDef.getSize() != newDef.getSize();
    boolean nullabilityChanged = oldDef.isNullable() != newDef.isNullable();
    
    if (typeChanged) {
        handleTypeChange(tableName, columnName, oldDef, newDef);
    } else if (sizeChanged) {
        handleSizeChange(tableName, columnName, oldDef, newDef);
    } else if (nullabilityChanged) {
        handleNullabilityChange(tableName, columnName, oldDef, newDef);
    }
}

private void handleTypeChange(String table, String column, 
                               ColumnDefinition oldDef, ColumnDefinition newDef) {
    log.info("Type change detected: {}.{} {} → {}", 
        table, column, oldDef.getType(), newDef.getType());
    
    String typeChangeBehavior = config.getTypeChangeBehavior();
    
    switch (typeChangeBehavior) {
        case "MODIFY":
            if (isSafeTypeChange(oldDef.getType(), newDef.getType())) {
                executeTypeChange(table, column, newDef);
            } else {
                log.error("Unsafe type change: {} → {}", oldDef.getType(), newDef.getType());
                throw new SchemaException("Unsafe type change");
            }
            break;
            
        case "RECREATE":
            recreateColumnWithNewType(table, column, newDef);
            break;
            
        case "IGNORE":
            log.warn("Type change ignored, may cause data errors");
            break;
            
        case "FAIL":
            throw new SchemaException("Type change not allowed by configuration");
            
        default:
            throw new ConfigException("Invalid type.change.behavior: " + typeChangeBehavior);
    }
}

private boolean isSafeTypeChange(String oldType, String newType) {
    // Safe: widening conversions
    Map<String, Set<String>> safeConversions = Map.of(
        "Int8", Set.of("Int16", "Int32", "Int64"),
        "Int16", Set.of("Int32", "Int64"),
        "Int32", Set.of("Int64"),
        "Float32", Set.of("Float64"),
        "Date", Set.of("DateTime"),
        "String", Set.of("String")  // Size increase is safe
    );
    
    return safeConversions.getOrDefault(oldType, Set.of()).contains(newType);
}

private void executeTypeChange(String table, String column, ColumnDefinition newDef) {
    String newType = mapToClickHouseType(newDef);
    
    String sql = String.format("ALTER TABLE %s MODIFY COLUMN %s %s", 
        table, column, newType);
    
    try (Statement stmt = connection.createStatement()) {
        stmt.execute(sql);
        log.info("Changed type of {}.{} to {}", table, column, newType);
    } catch (SQLException e) {
        throw new SchemaException("Type change failed", e);
    }
}
```

### Safe Type Changes

| From | To | Safe? | Notes |
|------|-----|-------|-------|
| Int8 | Int16, Int32, Int64 | ✓ | Widening |
| Int16 | Int32, Int64 | ✓ | Widening |
| Int32 | Int64 | ✓ | Widening |
| Float32 | Float64 | ✓ | Precision increase |
| Date | DateTime | ✓ | Adds time component |
| String | String (larger) | ✓ | Size increase |
| Int32 | Int16 | ✗ | Narrowing, data loss |
| Float64 | Float32 | ✗ | Precision loss |
| String | Int32 | ✗ | Type conversion |

### Configuration

```properties
# How to handle type changes
clickhouse.type.change.behavior=MODIFY  # MODIFY | RECREATE | IGNORE | FAIL

# Allow only safe type changes
clickhouse.type.change.safe.only=true
```

---

## BUG-SCHEMA-4: Case Sensitivity Mismatch

**Severity:** MEDIUM  
**Location:** Multiple files - schema comparison logic

### Problem

MySQL column names are case-insensitive, but ClickHouse preserves case. This causes mapping failures.

**MySQL:**
```sql
CREATE TABLE users (
    ID int,
    FirstName varchar(50),
    LASTNAME varchar(50)
);

-- All these work in MySQL:
SELECT id FROM users;
SELECT ID FROM users;
SELECT Id FROM users;
```

**ClickHouse:**
```sql
-- Case preserved from CREATE TABLE
Columns: ID, FirstName, LASTNAME

-- Case-sensitive lookups
SELECT id FROM users;  -- ERROR: Unknown column 'id'
```

### Impact

```java
// Record from Kafka
{
  "id": 1,           // lowercase
  "firstname": "John",  // lowercase
  "lastname": "Doe"     // lowercase
}

// ClickHouse columns
ID, FirstName, LASTNAME

// Mapping fails - column not found!
```

### Proposed Fix

```java
public class ClickHouseSchemaMapper {
    private boolean caseSensitive = false;
    
    public String mapColumnName(String kafkaColumn, Map<String, ClickHouseColumn> chSchema) {
        // Try exact match first
        if (chSchema.containsKey(kafkaColumn)) {
            return kafkaColumn;
        }
        
        // Try case-insensitive match
        if (!caseSensitive) {
            for (String chColumn : chSchema.keySet()) {
                if (chColumn.equalsIgnoreCase(kafkaColumn)) {
                    log.debug("Case-insensitive match: {} → {}", kafkaColumn, chColumn);
                    return chColumn;
                }
            }
        }
        
        // Not found
        String behavior = config.getUnmappedColumnBehavior();
        
        switch (behavior) {
            case "FAIL":
                throw new SchemaException("Column not found: " + kafkaColumn);
            case "SKIP":
                log.warn("Column {} not found, skipping", kafkaColumn);
                return null;
            case "CREATE":
                log.info("Column {} not found, will create", kafkaColumn);
                return kafkaColumn;
            default:
                throw new ConfigException("Invalid unmapped.column.behavior: " + behavior);
        }
    }
}
```

### Configuration

```properties
# Case sensitivity for column name matching
clickhouse.column.name.case.sensitive=false

# What to do with unmapped columns
clickhouse.unmapped.column.behavior=FAIL  # FAIL | SKIP | CREATE
```

---

## BUG-SCHEMA-5: Reserved Keywords Not Escaped

**Severity:** HIGH  
**Location:** [`ClickHouseQueryBuilder.java:50-80`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseQueryBuilder.java)

### Problem

SQL reserved keywords used as column names are not properly escaped, causing syntax errors.

**MySQL:**
```sql
CREATE TABLE events (
    `timestamp` BIGINT,
    `user` VARCHAR(50),
    `select` VARCHAR(100),
    `table` VARCHAR(100)
);
```

**ClickHouse Query Generated:**
```sql
-- WRONG: No escaping
INSERT INTO events (timestamp, user, select, table) VALUES (?, ?, ?, ?);

-- ERROR: Syntax error at 'select'
```

### ClickHouse Reserved Keywords

```
SELECT, INSERT, UPDATE, DELETE, ALTER, CREATE, DROP, TABLE, DATABASE,
FROM, WHERE, JOIN, ORDER, GROUP, HAVING, LIMIT, OFFSET, AS, ON,
timestamp, user, date, table, column, index, key, values, etc.
```

### Impact

```sql
-- Any table with reserved keywords fails
CREATE TABLE analytics (
    id INT,
    date DATE,      -- Reserved!
    user VARCHAR(50),  -- Reserved!
    table VARCHAR(50)  -- Reserved!
);

-- Connector generates:
INSERT INTO analytics (id, date, user, table) VALUES (?, ?, ?, ?);

-- Result: SQL syntax error, connector crashes
```

### Proposed Fix

```java
public class ClickHouseQueryBuilder {
    private static final Set<String> RESERVED_KEYWORDS = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "ALTER", "CREATE", "DROP",
        "TABLE", "DATABASE", "FROM", "WHERE", "JOIN", "ORDER", "GROUP",
        "HAVING", "LIMIT", "OFFSET", "AS", "ON", "USER", "DATE", "TIMESTAMP",
        "KEY", "INDEX", "COLUMN", "VALUES", "DEFAULT", "CASE", "WHEN", "THEN",
        "ELSE", "END", "DISTINCT", "ALL", "ANY", "SOME", "EXISTS", "IN", "NOT"
        // ... add all ClickHouse reserved keywords
    );
    
    public String buildInsertQuery(String tableName, List<String> columns) {
        String escapedTable = escapeIdentifier(tableName);
        String columnList = columns.stream()
            .map(this::escapeIdentifier)
            .collect(Collectors.joining(", "));
        
        String placeholders = columns.stream()
            .map(c -> "?")
            .collect(Collectors.joining(", "));
        
        return String.format("INSERT INTO %s (%s) VALUES (%s)",
            escapedTable, columnList, placeholders);
    }
    
    private String escapeIdentifier(String identifier) {
        // Always escape if reserved keyword
        if (RESERVED_KEYWORDS.contains(identifier.toUpperCase())) {
            return "`" + identifier + "`";
        }
        
        // Escape if contains special characters
        if (identifier.matches(".*[^a-zA-Z0-9_].*")) {
            return "`" + identifier + "`";
        }
        
        // Optional: always escape for safety
        if (config.isAlwaysEscapeIdentifiers()) {
            return "`" + identifier + "`";
        }
        
        return identifier;
    }
}
```

### Escaping Rules

```sql
-- Reserved keywords - MUST escape
`user`, `date`, `timestamp`, `select`, `table`

-- Special characters - MUST escape  
`my-column`, `my column`, `my.column`

-- Numbers at start - SHOULD escape
`123column`, `2nd_value`

-- Safe identifiers - can skip escaping
user_id, first_name, created_at
```

### Configuration

```properties
# Always escape all identifiers (safest)
clickhouse.always.escape.identifiers=true

# Escape only reserved keywords
clickhouse.escape.keywords.only=false
```

### Testing Requirements

1. Test all common reserved keywords
2. Test special characters in names
3. Test case sensitivity with escaping
4. Verify generated SQL is valid
5. Test with real ClickHouse database

---

## Summary of Fixes

| Bug ID | Priority | Effort | Implementation |
|--------|----------|--------|----------------|
| SCHEMA-1 | P1 | 16h | Wire up + config options |
| SCHEMA-2 | P1 | 12h | Detection + rename logic |
| SCHEMA-3 | P1 | 16h | Type change validation |
| SCHEMA-4 | P2 | 4h | Case-insensitive matching |
| SCHEMA-5 | P0 | 8h | Identifier escaping |
| **TOTAL** | | **56h** | |

## Recommended Implementation Order

1. **SCHEMA-5** - Prevents immediate SQL errors (8h)
2. **SCHEMA-2** - Critical for data integrity (12h)
3. **SCHEMA-3** - Prevents type mismatch errors (16h)
4. **SCHEMA-1** - Complete DROP COLUMN support (16h)
5. **SCHEMA-4** - Improves robustness (4h)

## Testing Strategy

```java
@Test
public void testSchemaEvolution() {
    // Initial schema
    mysql.execute("CREATE TABLE test (id INT, name VARCHAR(50))");
    waitForSync();
    
    // Add column
    mysql.execute("ALTER TABLE test ADD COLUMN age INT");
    waitForSync();
    verifyClickHouseSchema("test", List.of("id", "name", "age"));
    
    // Rename column
    mysql.execute("ALTER TABLE test CHANGE COLUMN name full_name VARCHAR(50)");
    waitForSync();
    verifyClickHouseSchema("test", List.of("id", "full_name", "age"));
    
    // Modify type
    mysql.execute("ALTER TABLE test MODIFY COLUMN age BIGINT");
    waitForSync();
    verifyColumnType("test", "age", "Int64");
    
    // Drop column
    mysql.execute("ALTER TABLE test DROP COLUMN age");
    waitForSync();
    verifyClickHouseSchema("test", List.of("id", "full_name"));
}
```

---

**Related Documents:**
- [DDL/DML Coverage](./DDL-DML-COVERAGE.md) - Full operation support matrix
- [Fix Priority](./FIXES-PRIORITY.md) - Implementation roadmap
- [Production Readiness](./PRODUCTION-READINESS.md) - Deployment guidance
