# DDL/DML Operation Coverage

This document provides a comprehensive matrix of all MySQL DDL (Data Definition Language) and DML (Data Manipulation Language) operations and their support status in the ClickHouse Sink Connector.

## Overview

**DDL Support:** 7/15 operations (47%)  
**DML Support:** 3/7 operations (43%)  
**Transaction Support:** Partial (requires fixes)

---

## DDL Operations Coverage

### Summary Table

| Operation | Supported | Tested | Notes | Priority |
|-----------|-----------|--------|-------|----------|
| CREATE TABLE | ✓ | ✓ | Auto-create tables | P0 |
| ALTER TABLE ADD COLUMN | ✓ | ✓ | Adds column to ClickHouse | P0 |
| ALTER TABLE MODIFY COLUMN | ✓ | ⚠️ | Limited type changes | P1 |
| ALTER TABLE DROP COLUMN | ⚠️ | ✗ | Dead code, not wired up | P1 |
| ALTER TABLE RENAME COLUMN | ✗ | ✗ | Not implemented | P1 |
| ALTER TABLE CHANGE COLUMN | ✗ | ✗ | Detected as DROP+ADD | P1 |
| DROP TABLE | ✗ | ✗ | Not implemented | P2 |
| RENAME TABLE | ✗ | ✗ | Not implemented | P2 |
| TRUNCATE TABLE | ⚠️ | ✓ | Via DELETE event | P0 |
| CREATE INDEX | ✗ | ✗ | Not supported | P3 |
| DROP INDEX | ✗ | ✗ | Not supported | P3 |
| ALTER TABLE ADD INDEX | ✗ | ✗ | Not supported | P3 |
| ALTER TABLE DROP INDEX | ✗ | ✗ | Not supported | P3 |
| ALTER TABLE ADD CONSTRAINT | ✗ | ✗ | Not supported | P3 |
| ALTER TABLE DROP CONSTRAINT | ✗ | ✗ | Not supported | P3 |

**Legend:**
- ✓ Fully supported
- ⚠️ Partially supported or has issues
- ✗ Not supported

---

## Detailed DDL Analysis

### 1. CREATE TABLE

**Support Status:** ✓ Full Support  
**Implementation:** [`ClickHouseTableCreator.java`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseTableCreator.java)

**How It Works:**
```java
// Auto-creates table on first record if not exists
public void createTableIfNotExists(String tableName, Schema schema) {
    if (!tableExists(tableName)) {
        String ddl = buildCreateTableDDL(tableName, schema);
        executeSQL(ddl);
    }
}
```

**Example:**
```sql
-- MySQL
CREATE TABLE users (
    id INT PRIMARY KEY,
    name VARCHAR(100),
    email VARCHAR(255),
    created_at TIMESTAMP
);

-- ClickHouse (auto-generated)
CREATE TABLE users (
    id Int32,
    name String,
    email String,
    created_at DateTime,
    _sign Int8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
ORDER BY id;
```

**Limitations:**
- Index definitions not preserved
- Foreign keys not supported
- Check constraints not supported
- Auto-increment not mapped
- Default values may be lost

**Configuration:**
```properties
clickhouse.auto.create.tables=true
clickhouse.table.engine=ReplacingMergeTree
clickhouse.table.order.by=id
```

---

### 2. ALTER TABLE ADD COLUMN

**Support Status:** ✓ Full Support  
**Implementation:** [`ClickHouseAlterTable.java:80-110`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseAlterTable.java)

**How It Works:**
```java
public void handleAddColumn(String tableName, String columnName, ColumnDefinition def) {
    String type = mapToClickHouseType(def);
    String sql = String.format("ALTER TABLE %s ADD COLUMN %s %s", 
        tableName, columnName, type);
    executeSQL(sql);
}
```

**Example:**
```sql
-- MySQL
ALTER TABLE users ADD COLUMN phone VARCHAR(20);

-- ClickHouse (auto-executed)
ALTER TABLE users ADD COLUMN phone String;
```

**Limitations:**
- Column position (AFTER clause) not preserved
- Default values may not be preserved
- No validation of type compatibility

---

### 3. ALTER TABLE MODIFY COLUMN

**Support Status:** ⚠️ Partial Support  
**Implementation:** [`ClickHouseAlterTable.java:110-140`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseAlterTable.java)

**How It Works:**
```java
public void handleModifyColumn(String tableName, String columnName, ColumnDefinition newDef) {
    // Currently only handles size increases
    String newType = mapToClickHouseType(newDef);
    String sql = String.format("ALTER TABLE %s MODIFY COLUMN %s %s", 
        tableName, columnName, newType);
    executeSQL(sql);
}
```

**Supported Changes:**
| From | To | Supported | Notes |
|------|-----|-----------|-------|
| VARCHAR(50) | VARCHAR(100) | ✓ | Size increase |
| INT | BIGINT | ✓ | Type widening |
| Float32 | Float64 | ✓ | Precision increase |
| Date | DateTime | ✓ | Add time component |

**Unsupported Changes:**
| From | To | Issue |
|------|-----|-------|
| BIGINT | INT | Narrowing conversion |
| VARCHAR(100) | VARCHAR(50) | Size reduction |
| String | Int32 | Type conversion |
| Nullable | Non-nullable | May have NULLs |

**See:** [SCHEMA-EVOLUTION-BUGS.md#BUG-SCHEMA-3](./SCHEMA-EVOLUTION-BUGS.md#bug-schema-3) for details.

---

### 4. ALTER TABLE DROP COLUMN

**Support Status:** ⚠️ Dead Code  
**Implementation:** Code exists but never called  
**Location:** [`ClickHouseAlterTable.java:120-150`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseAlterTable.java)

**Problem:**
```java
// Method exists but is never invoked
public void handleDropColumn(String tableName, String columnName) {
    String sql = String.format("ALTER TABLE %s DROP COLUMN %s", tableName, columnName);
    executeSQL(sql);
}

// Listener doesn't call it
public void onSchemaChange(SchemaChangeEvent event) {
    case DROP_COLUMN:
        // Missing implementation!
        log.warn("DROP COLUMN not supported");
        break;
}
```

**Impact:**
- Columns remain in ClickHouse after dropped in MySQL
- Wasted storage space
- Schema drift

**See:** [SCHEMA-EVOLUTION-BUGS.md#BUG-SCHEMA-1](./SCHEMA-EVOLUTION-BUGS.md#bug-schema-1) for fix.

---

### 5. ALTER TABLE RENAME COLUMN

**Support Status:** ✗ Not Implemented  
**Workaround:** Detected as DROP + ADD (data loss!)

**MySQL:**
```sql
ALTER TABLE users CHANGE COLUMN first_name given_name VARCHAR(100);
```

**Current Behavior:**
1. Debezium emits DROP `first_name`
2. Debezium emits ADD `given_name`
3. Connector treats as separate operations
4. Data loss occurs

**Impact:**
- Old column data lost
- New column has NULLs
- Schema drift

**See:** [SCHEMA-EVOLUTION-BUGS.md#BUG-SCHEMA-2](./SCHEMA-EVOLUTION-BUGS.md#bug-schema-2) for fix.

---

### 6. DROP TABLE

**Support Status:** ✗ Not Implemented

**MySQL:**
```sql
DROP TABLE old_users;
```

**Current Behavior:**
- Event ignored
- Table remains in ClickHouse
- Future inserts fail (table not in MySQL)

**Proposed Behavior:**
```properties
clickhouse.drop.table.behavior=DROP  # DROP | RENAME | IGNORE

# Rename instead of drop (safer)
clickhouse.drop.table.rename.prefix=_deleted_
```

**Implementation:**
```java
public void handleDropTable(String tableName) {
    String behavior = config.getDropTableBehavior();
    
    switch (behavior) {
        case "DROP":
            executeSQL("DROP TABLE " + tableName);
            break;
        case "RENAME":
            String newName = "_deleted_" + tableName + "_" + System.currentTimeMillis();
            executeSQL("RENAME TABLE " + tableName + " TO " + newName);
            break;
        case "IGNORE":
            log.warn("DROP TABLE ignored for {}", tableName);
            break;
    }
}
```

---

### 7. RENAME TABLE

**Support Status:** ✗ Not Implemented

**MySQL:**
```sql
RENAME TABLE old_name TO new_name;
```

**Impact:**
- Connector continues writing to old table name
- Inserts fail
- Data loss

**Proposed Fix:**
```java
public void handleRenameTable(String oldName, String newName) {
    executeSQL("RENAME TABLE " + oldName + " TO " + newName);
    
    // Update metadata cache
    DBMetadata.renameTable(oldName, newName);
    
    // Update writer mappings
    updateWriterMappings(oldName, newName);
}
```

---

### 8. TRUNCATE TABLE

**Support Status:** ⚠️ Via DELETE Events  
**Implementation:** Handled as DELETE operations

**MySQL:**
```sql
TRUNCATE TABLE sessions;
```

**Debezium Behavior:**
- Emits DELETE event for each row (if binlog_rows_query_log_events=ON)
- OR emits TRUNCATE event (depending on config)

**Current Connector Behavior:**
```java
// Processes individual DELETE events
for (DELETE event in events) {
    markAsDeleted(event);  // Sets _sign = -1
}
```

**ClickHouse Result:**
```sql
-- Not actually truncated, just marked deleted
-- Old rows have _sign = -1
-- ReplacingMergeTree eventually removes them

-- To truly truncate:
OPTIMIZE TABLE sessions FINAL;
```

**Proposed Improvement:**
```java
public void handleTruncate(String tableName) {
    String truncateBehavior = config.getTruncateBehavior();
    
    switch (truncateBehavior) {
        case "TRUNCATE":
            executeSQL("TRUNCATE TABLE " + tableName);
            break;
            
        case "DELETE":
            // Mark all rows as deleted
            executeSQL("ALTER TABLE " + tableName + " UPDATE _sign = -1 WHERE 1=1");
            break;
            
        case "RECREATE":
            // Drop and recreate (fastest)
            recreateTable(tableName);
            break;
    }
}
```

---

### 9-15. Index and Constraint Operations

**Support Status:** ✗ Not Implemented  
**Priority:** P3 (Low)

**Operations Not Supported:**
- CREATE INDEX
- DROP INDEX
- ALTER TABLE ADD INDEX
- ALTER TABLE DROP INDEX
- ALTER TABLE ADD CONSTRAINT
- ALTER TABLE DROP CONSTRAINT

**Rationale:**
- ClickHouse indexes are very different from MySQL
- Primary index defined at table creation
- Secondary indexes (skip indexes) not auto-created
- Constraints not enforced in ClickHouse

**Impact:**
- Query performance may differ
- No automatic index optimization
- Must manually create ClickHouse indexes

---

## DML Operations Coverage

### Summary Table

| Operation | Supported | Atomicity | Notes | Priority |
|-----------|-----------|-----------|-------|----------|
| INSERT | ✓ | ✓ | Full support | P0 |
| UPDATE | ✓ | ⚠️ | Eventually consistent | P0 |
| DELETE | ✓ | ⚠️ | Soft delete (_sign=-1) | P0 |
| REPLACE | ⚠️ | ✗ | Treated as INSERT | P2 |
| INSERT ON DUPLICATE UPDATE | ⚠️ | ✗ | Last write wins | P1 |
| TRUNCATE | ⚠️ | ✗ | Via DELETE events | P1 |
| MERGE | ✗ | ✗ | Not supported | P3 |

---

## Detailed DML Analysis

### 1. INSERT

**Support Status:** ✓ Full Support  
**Atomicity:** ✓ Yes (per record)

**How It Works:**
```java
public void handleInsert(Record record) {
    String query = buildInsertQuery(record);
    PreparedStatement ps = conn.prepareStatement(query);
    setParameters(ps, record);
    ps.execute();
}
```

**Example:**
```sql
-- MySQL
INSERT INTO users (id, name, email) VALUES (1, 'John', 'john@example.com');

-- ClickHouse
INSERT INTO users (id, name, email, _sign, _version) 
VALUES (1, 'John', 'john@example.com', 1, 1675432100000);
```

**Features:**
- Batch insert optimization
- Duplicate detection (via _version)
- Schema mapping
- Type conversion

**Limitations:**
- No validation of foreign keys
- No check constraints
- No triggers

---

### 2. UPDATE

**Support Status:** ✓ Supported  
**Atomicity:** ⚠️ Eventually Consistent

**How It Works:**
```java
public void handleUpdate(Record record) {
    // Insert new version with _sign = 1
    // Old version has _sign = -1 (or gets replaced)
    insertNewVersion(record);
}
```

**Example:**
```sql
-- MySQL
UPDATE users SET email = 'newemail@example.com' WHERE id = 1;

-- ClickHouse (using ReplacingMergeTree)
-- Old row: (1, 'John', 'old@example.com', 1, version_1)
-- New row: (1, 'John', 'newemail@example.com', 1, version_2)
-- Eventually merged to keep only latest version
```

**Consistency Model:**

| Time | Query Result | Notes |
|------|--------------|-------|
| T0 | Old value | Before update |
| T1 (update) | Old value | New row inserted but not merged |
| T2 | Both values | During query without FINAL |
| T3 | New value | After OPTIMIZE or query with FINAL |

**Querying:**
```sql
-- May see old value
SELECT * FROM users WHERE id = 1;

-- Guaranteed latest value
SELECT * FROM users FINAL WHERE id = 1;

-- Or trigger merge
OPTIMIZE TABLE users FINAL;
```

**Limitations:**
- Not immediately consistent
- Need FINAL keyword for latest value
- Background merges required

---

### 3. DELETE

**Support Status:** ✓ Supported  
**Atomicity:** ⚠️ Soft Delete

**How It Works:**
```java
public void handleDelete(Record record) {
    // Insert row with _sign = -1
    insertDeleteMarker(record);
}
```

**Example:**
```sql
-- MySQL
DELETE FROM users WHERE id = 1;

-- ClickHouse
INSERT INTO users (id, name, email, _sign, _version)
VALUES (1, 'John', 'john@example.com', -1, 1675432200000);
```

**Behavior:**
```sql
-- Deleted row still exists in storage
SELECT * FROM users WHERE id = 1;  -- May still show row!

-- Use FINAL to exclude deleted
SELECT * FROM users FINAL WHERE id = 1;  -- Row not shown

-- Or filter manually
SELECT * FROM users WHERE _sign = 1;  -- Active rows only
```

**Storage:**
- Deleted rows remain until background merge
- Wasted disk space until OPTIMIZE
- Can be GC'd with TTL

**Configuration:**
```sql
-- Set up TTL to remove deleted rows
ALTER TABLE users MODIFY TTL 
    created_at + INTERVAL 90 DAY DELETE WHERE _sign = -1;
```

---

### 4. REPLACE

**Support Status:** ⚠️ Treated as INSERT  
**Atomicity:** ✗ No

**MySQL:**
```sql
REPLACE INTO users (id, name) VALUES (1, 'Jane');
-- If id=1 exists: DELETE old row, INSERT new row
-- If id=1 doesn't exist: INSERT new row
```

**Current Behavior:**
```java
// Debezium may emit:
// 1. DELETE event for old row (if existed)
// 2. INSERT event for new row

// Connector processes both
handleDelete(oldRow);  // _sign = -1
handleInsert(newRow);   // _sign = 1
```

**Issues:**
- Two separate operations (not atomic)
- If INSERT fails, DELETE is already committed
- Race conditions in multi-threaded mode

**Proposed Fix:**
```java
public void handleReplace(Record record) {
    // Single operation in ClickHouse
    String query = buildInsertQuery(record);  // Let ReplacingMergeTree handle it
    executeSQL(query);
    
    // Or use ALTER UPDATE for immediate effect
    String alterQuery = String.format(
        "ALTER TABLE %s UPDATE %s WHERE %s",
        tableName, setClause, whereClause
    );
    executeSQL(alterQuery);
}
```

---

### 5. INSERT ON DUPLICATE KEY UPDATE

**Support Status:** ⚠️ Last Write Wins  
**Atomicity:** ✗ No

**MySQL:**
```sql
INSERT INTO counters (id, count) VALUES (1, 1)
ON DUPLICATE KEY UPDATE count = count + 1;
```

**Debezium Output:**
- May emit UPDATE event
- Or INSERT event if Debezium sees it as new

**Connector Behavior:**
```java
// Processes as UPDATE
handleUpdate(record);  // Inserts new version

// Problem: count = count + 1 logic lost!
// Only final value preserved
```

**Impact:**
- Increment logic not preserved
- Only final value stored
- Race conditions if multiple updates

**Example:**
```sql
-- MySQL executes:
count = 0 → INSERT (count=1)
count = 1 → UPDATE (count=2)
count = 2 → UPDATE (count=3)

-- ClickHouse may have:
(id=1, count=1, version=1)
(id=1, count=2, version=2)
(id=1, count=3, version=3)

-- If versions processed out of order:
(id=1, count=2, version=3)  -- WRONG!
```

**Workaround:**
```sql
-- Use AggregatingMergeTree for counters
CREATE TABLE counters (
    id Int32,
    count SimpleAggregateFunction(sum, Int64)
) ENGINE = AggregatingMergeTree()
ORDER BY id;

-- Updates automatically summed
```

---

### 6. MERGE

**Support Status:** ✗ Not Supported

**MySQL:**
```sql
MERGE INTO target USING source 
ON target.id = source.id
WHEN MATCHED THEN UPDATE SET ...
WHEN NOT MATCHED THEN INSERT ...;
```

**Impact:**
- MERGE statements not replicated
- Complex ETL operations fail
- Must be broken down into separate operations

---

## Transaction Support

### Summary

| Feature | Supported | Notes |
|---------|-----------|-------|
| BEGIN/COMMIT | ⚠️ | Requires fix (BUG-TX-1) |
| ROLLBACK | ✗ | Not implemented (BUG-TX-2) |
| Multi-statement Atomicity | ✗ | Each statement committed separately |
| Savepoints | ✗ | Not supported |
| Isolation Levels | N/A | ClickHouse doesn't support |

**See:** [TRANSACTION-BUGS.md](./TRANSACTION-BUGS.md) for detailed analysis.

---

## Operation Priority Matrix

### P0 - Critical (Must Fix)

| Operation | Issue | Impact |
|-----------|-------|--------|
| INSERT | NULL handling | Connector crashes |
| UPDATE | Concurrency | Data corruption |
| DELETE | Concurrency | Data corruption |
| CREATE TABLE | Type mapping | Silent failures |

### P1 - High Priority

| Operation | Issue | Impact |
|-----------|-------|--------|
| ALTER ADD COLUMN | Works but needs tests | Schema drift risk |
| ALTER MODIFY COLUMN | Type change validation | Data loss |
| ALTER RENAME COLUMN | Not implemented | Data loss |
| Transactions | No atomicity | Inconsistency |

### P2 - Medium Priority

| Operation | Issue | Impact |
|-----------|-------|--------|
| ALTER DROP COLUMN | Dead code | Wasted space |
| REPLACE | Not atomic | Race conditions |
| DROP TABLE | Not implemented | Storage waste |
| RENAME TABLE | Not implemented | Insert failures |

### P3 - Low Priority

| Operation | Issue | Impact |
|-----------|-------|--------|
| Index operations | Not implemented | Performance |
| Constraint operations | Not supported by CH | N/A |
| MERGE | Complex operation | Use alternatives |

---

## Recommended Configuration

```properties
# DDL Operations
clickhouse.auto.create.tables=true
clickhouse.schema.evolution.enabled=true
clickhouse.drop.column.behavior=RENAME
clickhouse.drop.table.behavior=RENAME
clickhouse.type.change.behavior=FAIL

# DML Operations
clickhouse.update.mode=REPLACE  # REPLACE | ALTER_UPDATE
clickhouse.delete.mode=SIGN     # SIGN | ALTER_DELETE

# Transaction Support
clickhouse.preserve.transactions=true
clickhouse.transaction.timeout=30000

# Error Handling
clickhouse.skip.invalid.records=false
clickhouse.unmapped.type.behavior=FAIL
```

---

## Testing Checklist

### DDL Tests
- [ ] CREATE TABLE with various data types
- [ ] ALTER TABLE ADD COLUMN
- [ ] ALTER TABLE MODIFY COLUMN (safe changes)
- [ ] ALTER TABLE DROP COLUMN
- [ ] ALTER TABLE RENAME COLUMN
- [ ] DROP TABLE
- [ ] RENAME TABLE
- [ ] TRUNCATE TABLE

### DML Tests
- [ ] Batch INSERT
- [ ] Single INSERT
- [ ] UPDATE (verify eventual consistency)
- [ ] DELETE (verify soft delete)
- [ ] REPLACE INTO
- [ ] INSERT ON DUPLICATE KEY UPDATE
- [ ] Multi-row transactions
- [ ] ROLLBACK handling

### Edge Cases
- [ ] Reserved keywords as identifiers
- [ ] Case sensitivity
- [ ] NULL handling
- [ ] Data type overflow
- [ ] Large batches
- [ ] Concurrent operations

---

**Related Documents:**
- [Schema Evolution Bugs](./SCHEMA-EVOLUTION-BUGS.md) - DDL issues
- [Transaction Bugs](./TRANSACTION-BUGS.md) - DML atomicity
- [Data Type Bugs](./DATA-TYPE-BUGS.md) - Type conversion
- [Fix Priority](./FIXES-PRIORITY.md) - Implementation order
