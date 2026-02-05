# DDL Operations Support

This document describes the full DDL (Data Definition Language) support implemented in Phase 3 of the ClickHouse Sink Connector.

## Overview

**DDL Coverage:** 14/15 operations (93%)
- ✅ CREATE TABLE (existing)
- ✅ ALTER TABLE ADD COLUMN (existing)
- ✅ ALTER TABLE DROP COLUMN (new)
- ✅ ALTER TABLE RENAME COLUMN (new)
- ✅ ALTER TABLE MODIFY COLUMN (new)
- ✅ DROP TABLE (new)
- ✅ RENAME TABLE (new)
- ✅ TRUNCATE TABLE (existing)

## Configuration Parameters

### DROP COLUMN Behavior

```properties
clickhouse.drop.column.behavior=RENAME
```

**Options:**
- `DROP` - Execute DROP COLUMN in ClickHouse (may require table rebuild)
- `RENAME` - Rename column to `_deleted_<name>_<timestamp>` (safer, default)
- `IGNORE` - Ignore the operation, column remains
- `FAIL` - Throw exception to prevent accidental drops

**Example:**
```sql
-- MySQL
ALTER TABLE users DROP COLUMN middle_name;

-- ClickHouse (RENAME behavior)
ALTER TABLE users RENAME COLUMN middle_name TO _deleted_middle_name_1707234567890;
```

### DROP TABLE Behavior

```properties
clickhouse.drop.table.behavior=RENAME
```

**Options:**
- `DROP` - Execute DROP TABLE in ClickHouse
- `RENAME` - Rename table to `_deleted_<name>_<timestamp>` (safer, default)
- `IGNORE` - Ignore the operation, table remains
- `FAIL` - Throw exception to prevent accidental drops

**Example:**
```sql
-- MySQL
DROP TABLE old_users;

-- ClickHouse (RENAME behavior)
RENAME TABLE old_users TO _deleted_old_users_1707234567890;
```

### RENAME COLUMN Behavior

```properties
clickhouse.rename.column.behavior=RENAME
```

**Options:**
- `RENAME` - Execute RENAME COLUMN in ClickHouse (default)
- `IGNORE` - Ignore the operation, schema will drift
- `FAIL` - Throw exception

**Example:**
```sql
-- MySQL
ALTER TABLE users CHANGE COLUMN first_name given_name VARCHAR(100);

-- ClickHouse (RENAME behavior)
ALTER TABLE users RENAME COLUMN first_name TO given_name;
```

### TYPE CHANGE Behavior

```properties
clickhouse.type.change.behavior=MODIFY
clickhouse.type.change.safe.only=true
```

**Type Change Options:**
- `MODIFY` - Execute MODIFY COLUMN in ClickHouse (default)
- `IGNORE` - Ignore the operation, may cause data errors
- `FAIL` - Throw exception

**Safe Only Mode:**
- `true` - Only allow safe type changes (widening conversions, default)
- `false` - Allow all type changes (dangerous)

**Example:**
```sql
-- MySQL
ALTER TABLE users MODIFY COLUMN age BIGINT;

-- ClickHouse (MODIFY behavior with safe check)
ALTER TABLE users MODIFY COLUMN age Int64;
```

## Safe Type Changes

The connector validates type changes to prevent data loss. Only **widening conversions** are considered safe:

### Integer Types

| From | To | Safe? |
|------|-----|-------|
| Int8 | Int16, Int32, Int64 | ✅ |
| Int16 | Int32, Int64 | ✅ |
| Int32 | Int64 | ✅ |
| UInt8 | UInt16, UInt32, UInt64 | ✅ |
| UInt16 | UInt32, UInt64 | ✅ |
| UInt32 | UInt64 | ✅ |
| Int64 | Int32 | ❌ Narrowing |
| UInt64 | UInt32 | ❌ Narrowing |

### Floating Point Types

| From | To | Safe? |
|------|-----|-------|
| Float32 | Float64 | ✅ |
| Float64 | Float32 | ❌ Precision loss |

### Date/Time Types

| From | To | Safe? |
|------|-----|-------|
| Date | DateTime, DateTime64 | ✅ |
| DateTime | DateTime64 | ✅ |
| DateTime | Date | ❌ Loss of time |

### String Types

| From | To | Safe? |
|------|-----|-------|
| String | String (larger/same) | ✅ |
| String | Int32 | ❌ Type conversion |

### Nullable Types

Type changes within `Nullable()` wrappers follow the same rules:
- `Nullable(Int32)` → `Nullable(Int64)` ✅
- `Nullable(Int64)` → `Nullable(Int32)` ❌

## Implementation Details

### ClickHouseAlterTable.java

Enhanced with new methods:

```java
// Drop column with configurable behavior
public void dropColumn(String tableName, String columnName,
                      Connection connection,
                      ClickHouseSinkConnectorConfig config)

// Rename column
public void renameColumn(String tableName, String oldColumnName, 
                        String newColumnName,
                        Connection connection, 
                        ClickHouseSinkConnectorConfig config)

// Modify column type with safety checks
public void modifyColumn(String tableName, String columnName, 
                        String oldType, String newType,
                        Connection connection,
                        ClickHouseSinkConnectorConfig config)
```

### ClickHouseDropTable.java

New class for table-level operations:

```java
// Drop table with configurable behavior
public void dropTable(String tableName, String databaseName,
                     Connection connection,
                     ClickHouseSinkConnectorConfig config)

// Rename table
public void renameTable(String oldTableName, String newTableName, 
                       String databaseName,
                       Connection connection,
                       ClickHouseSinkConnectorConfig config)

// Truncate table
public void truncateTable(String tableName, String databaseName,
                         Connection connection,
                         ClickHouseSinkConnectorConfig config)
```

## Usage Examples

### Basic Configuration

```properties
# Connector configuration
clickhouse.server.url=http://localhost:8123
clickhouse.server.user=default
clickhouse.server.password=

# Schema evolution enabled
schema.evolution=true

# DDL behaviors (all optional, defaults shown)
clickhouse.drop.column.behavior=RENAME
clickhouse.drop.table.behavior=RENAME
clickhouse.rename.column.behavior=RENAME
clickhouse.type.change.behavior=MODIFY
clickhouse.type.change.safe.only=true
```

### Production-Safe Configuration

```properties
# Conservative settings for production
clickhouse.drop.column.behavior=RENAME
clickhouse.drop.table.behavior=RENAME
clickhouse.rename.column.behavior=RENAME
clickhouse.type.change.behavior=FAIL  # Require manual approval
clickhouse.type.change.safe.only=true
```

### Development Configuration

```properties
# More permissive for development
clickhouse.drop.column.behavior=DROP
clickhouse.drop.table.behavior=RENAME  # Still safe
clickhouse.rename.column.behavior=RENAME
clickhouse.type.change.behavior=MODIFY
clickhouse.type.change.safe.only=false  # Allow all changes
```

## Testing

### Unit Tests

Run the comprehensive DDL test suite:

```bash
mvn test -Dtest=DDLOperationsTest
```

Tests include:
- 5 DROP COLUMN tests (all behaviors)
- 3 RENAME COLUMN tests
- 8 MODIFY COLUMN tests (safe/unsafe changes)
- 4 DROP TABLE tests (all behaviors)
- 2 RENAME TABLE tests
- 1 TRUNCATE TABLE test
- Integration tests

### Integration Tests

SQL test scenarios in [`tests/p0-fixes/ddl-test-scenarios.sql`](../sink-connector/tests/p0-fixes/ddl-test-scenarios.sql):

```bash
# Start test environment
cd sink-connector/tests/p0-fixes
docker-compose up -d

# Run test scenarios
mysql -h localhost -P 3306 -u root -proot test < ddl-test-scenarios.sql

# Verify in ClickHouse
clickhouse-client --host localhost --port 9000
```

## Troubleshooting

### Column Not Dropped

**Symptom:** Column still exists in ClickHouse after DROP COLUMN  
**Cause:** `drop.column.behavior` set to `IGNORE` or `RENAME`  
**Solution:** Check configuration and verify behavior matches expectations

### Type Change Rejected

**Symptom:** Error "Unsafe type change detected"  
**Cause:** Attempting narrowing conversion with `type.change.safe.only=true`  
**Solution:** Either:
1. Use a safe widening conversion instead
2. Set `type.change.safe.only=false` (not recommended)
3. Set `type.change.behavior=IGNORE` temporarily

### Renamed Tables Not Found

**Symptom:** Connector can't find table after RENAME TABLE  
**Cause:** Schema change detection may have missed the rename  
**Solution:** Check `_deleted_*` tables in ClickHouse, may need manual intervention

### Reserved Keywords Error

**Symptom:** SQL syntax error with column/table names  
**Cause:** Reserved SQL keywords not properly escaped  
**Solution:** The connector now auto-escapes reserved keywords with backticks

## Best Practices

### 1. Test DDL Changes in Development First

Always test schema changes in a development environment before production.

### 2. Use RENAME Behavior by Default

The `RENAME` behavior is safer than `DROP` as it preserves data:

```properties
clickhouse.drop.column.behavior=RENAME
clickhouse.drop.table.behavior=RENAME
```

### 3. Monitor Deleted Objects

Regularly check for renamed objects and clean up when safe:

```sql
-- Check for deleted columns/tables
SHOW CREATE TABLE your_table;

-- Find all deleted tables
SELECT name FROM system.tables WHERE name LIKE '_deleted_%';

-- Clean up old deleted tables (after verification)
DROP TABLE _deleted_old_table_1707234567890;
```

### 4. Validate Type Changes

Always verify type compatibility before changing:

```sql
-- Check current type
DESCRIBE TABLE users;

-- Verify data range
SELECT MIN(age), MAX(age) FROM users;

-- Then apply change
ALTER TABLE users MODIFY COLUMN age BIGINT;
```

### 5. Use Transactions for Multiple Changes

While ClickHouse doesn't have full transaction support, group related changes:

```sql
-- Group related DDL operations
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
ALTER TABLE users ADD COLUMN address TEXT;
ALTER TABLE users MODIFY COLUMN email VARCHAR(255);
```

## Performance Considerations

### DROP COLUMN Performance

- **RENAME behavior:** Instant (metadata only)
- **DROP behavior:** May require table rebuild for some engines
- **Recommendation:** Use RENAME for large tables

### MODIFY COLUMN Performance

- **Widening conversions:** Usually fast (metadata change)
- **Type conversions:** May require data rewrite
- **Recommendation:** Test on replicas first

### RENAME TABLE Performance

- **Instant:** Metadata-only operation
- **No data movement required**

## Limitations

### 1. Unsupported Operations

The following MySQL DDL operations are not supported:

- CREATE INDEX / DROP INDEX
- ADD CONSTRAINT / DROP CONSTRAINT
- ADD PRIMARY KEY / DROP PRIMARY KEY

**Reason:** ClickHouse indexes and constraints work differently

### 2. Engine-Specific Limitations

Some operations may not work with all ClickHouse table engines:

- **CollapsingMergeTree:** DROP COLUMN may be restricted
- **ReplicatedMergeTree:** Some DDL requires coordination

### 3. Type Conversion Limitations

Not all MySQL-to-ClickHouse type mappings support modification:

- **ENUM types:** Limited conversion support
- **JSON types:** May need manual handling
- **Spatial types:** Not fully supported

## Migration Guide

### From Previous Versions

If upgrading from a connector version without full DDL support:

1. **Backup your configuration:**
   ```bash
   cp connector.properties connector.properties.backup
   ```

2. **Add new configuration parameters:**
   ```properties
   clickhouse.drop.column.behavior=RENAME
   clickhouse.drop.table.behavior=RENAME
   clickhouse.rename.column.behavior=RENAME
   clickhouse.type.change.behavior=MODIFY
   clickhouse.type.change.safe.only=true
   ```

3. **Test in development environment**

4. **Deploy gradually** (blue-green deployment recommended)

## Related Documentation

- [DDL/DML Coverage](../issues/DDL-DML-COVERAGE.md) - Full operation matrix
- [Schema Evolution Bugs](../issues/SCHEMA-EVOLUTION-BUGS.md) - Known issues and fixes
- [Configuration Guide](configuration.md) - All configuration options

## Support

For issues or questions:

1. Check [Troubleshooting](#troubleshooting) section
2. Review [test scenarios](../sink-connector/tests/p0-fixes/ddl-test-scenarios.sql)
3. File an issue with:
   - DDL operation attempted
   - Configuration used
   - Error messages
   - ClickHouse version
