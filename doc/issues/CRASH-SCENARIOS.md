# Crash Scenarios

This document details all documented crash scenarios that can cause the ClickHouse Sink Connector to fail or stop processing records.

## Overview

**Total Crash Scenarios:** 10  
**Severity:** CRITICAL (6), HIGH (4)  
**Common Causes:** NULL handling, type conversion, concurrency, resource exhaustion

---

## Crash Summary Table

| ID | Trigger | Crash Point | Exception | Severity | Workaround |
|----|---------|-------------|-----------|----------|------------|
| CRASH-1 | NULL in non-nullable column | ClickHouseConverter.java:195 | ClickHouseException | CRITICAL | Use Nullable columns |
| CRASH-2 | ConcurrentModificationException | ClickHouseBatchRunnable.java:305 | ConcurrentModificationException | CRITICAL | Use thread.pool.size=1 |
| CRASH-3 | Zero date (0000-00-00) | ClickHouseConverter.java:225 | DateTimeParseException | CRITICAL | Avoid zero dates |
| CRASH-4 | Date out of range | ClickHouseConverter.java:225 | ClickHouseException | HIGH | Use DateTime64 |
| CRASH-5 | Unmapped enum type | ClickHouseDataTypeMapper.java:75 | ClassCastException | HIGH | Manual type mapping |
| CRASH-6 | Connection pool exhaustion | ClickHouseBatchRunnable.java:215 | SQLException | CRITICAL | Fix resource leak |
| CRASH-7 | Reserved keyword unescaped | ClickHouseQueryBuilder.java:65 | SQLException | HIGH | Escape identifiers |
| CRASH-8 | Decimal overflow | ClickHouseConverter.java:270 | ArithmeticException | MEDIUM | Validate precision |
| CRASH-9 | Binary encoding corruption | ClickHouseConverter.java:165 | CharacterCodingException | MEDIUM | Use BLOB handling |
| CRASH-10 | Schema cache corruption | DBMetadata.java:95 | NullPointerException | CRITICAL | Fix synchronization |

---

## CRASH-1: NULL in Non-Nullable Column

**Severity:** CRITICAL  
**Frequency:** Very Common  
**Impact:** Complete connector failure on first NULL value

### Trigger Condition

```sql
-- MySQL (nullable column)
CREATE TABLE users (
    id INT PRIMARY KEY,
    email VARCHAR(255)  -- NULL allowed
);

INSERT INTO users VALUES (1, NULL);  -- Valid in MySQL

-- ClickHouse (non-nullable by default)
CREATE TABLE users (
    id Int32,
    email String  -- NOT NULL by default
) ENGINE = MergeTree() ORDER BY id;

-- Connector attempts: INSERT INTO users VALUES (1, NULL)
-- Result: CRASH
```

### Crash Point

**File:** [`ClickHouseConverter.java:195`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

```java
public Object convert(Object value, Schema schema, ClickHouseDataType targetType) {
    if (value == null) {
        return null;  // No validation! Crashes if column non-nullable
    }
    return convertValue(value, targetType);
}
```

### Exception

```
com.clickhouse.client.ClickHouseException: Code: 53, 
e.displayText() = DB::Exception: Attempt to insert NULL value into a non-nullable column 'email'
(version 23.3.1.2823 (official build))

  at com.clickhouse.jdbc.SqlExceptionUtils.handle(SqlExceptionUtils.java:88)
  at com.clickhouse.jdbc.ClickHouseStatementImpl.executeQuery(ClickHouseStatementImpl.java:276)
  at com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable.run(ClickHouseBatchRunnable.java:195)
  at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
  at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
```

### Impact

- **Immediate:** Connector stops processing
- **Data Loss:** All subsequent records in batch lost
- **Offset:** Not committed, same records retried indefinitely
- **Recovery:** Manual intervention required

### Workaround

**Option 1: Make ClickHouse columns nullable**
```sql
CREATE TABLE users (
    id Int32,
    email Nullable(String)
) ENGINE = MergeTree() ORDER BY id;
```

**Option 2: Filter NULLs at MySQL level**
```sql
-- Create filtered view for replication
CREATE VIEW users_no_nulls AS
SELECT id, COALESCE(email, '') as email
FROM users;
```

**Option 3: Use connector defaults** (requires fix)
```properties
clickhouse.null.handling=DEFAULT
clickhouse.defaults.email=""
```

### Required Fix

See [DATA-TYPE-BUGS.md#BUG-DATA-1](./DATA-TYPE-BUGS.md#bug-data-1)

---

## CRASH-2: ConcurrentModificationException

**Severity:** CRITICAL  
**Frequency:** Common with multi-threading  
**Impact:** Intermittent crashes, difficult to debug

### Trigger Condition

```properties
# Configuration that triggers the bug
thread.pool.size=4  # Any value > 1
tasks.max=8

# Multiple tables with concurrent inserts
INSERT INTO db1.users VALUES (...);  -- Thread 1
INSERT INTO db2.orders VALUES (...); -- Thread 2
INSERT INTO db1.users VALUES (...);  -- Thread 3
```

### Crash Point

**File:** [`ClickHouseBatchRunnable.java:305`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)

```java
public void run() {
    // Non-thread-safe HashMap accessed by multiple threads
    for (Map.Entry<String, Buffer> entry : queryToRecordsMap.entrySet()) {
        // CRASH: ConcurrentModificationException if another thread modifies map
        String query = entry.getKey();
        Buffer buffer = entry.getValue();
        processBuffer(query, buffer);
    }
}
```

### Exception

```
java.util.ConcurrentModificationException
  at java.base/java.util.HashMap$HashIterator.nextNode(HashMap.java:1597)
  at java.base/java.util.HashMap$EntryIterator.next(HashMap.java:1630)
  at java.base/java.util.HashMap$EntryIterator.next(HashMap.java:1628)
  at com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable.run(ClickHouseBatchRunnable.java:305)
  at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
```

### Impact

- **Intermittent:** Happens randomly under load
- **Hard to Reproduce:** Requires specific timing
- **Data Loss:** Batch being processed is lost
- **Service Disruption:** Connector restart required

### Workaround

**Temporary: Use single thread**
```properties
thread.pool.size=1
tasks.max=1
```

**Note:** Significantly reduces throughput

### Required Fix

See [CONCURRENCY-BUGS.md#BUG-CONC-1](./CONCURRENCY-BUGS.md#bug-conc-1) and [BUG-CONC-7](./CONCURRENCY-BUGS.md#bug-conc-7)

---

## CRASH-3: Zero Date (0000-00-00)

**Severity:** CRITICAL  
**Frequency:** Common with legacy MySQL databases  
**Impact:** Connector crash on zero date

### Trigger Condition

```sql
-- MySQL with sql_mode allowing zero dates
SET sql_mode = '';  -- Disable strict mode

CREATE TABLE orders (
    id INT,
    order_date DATE
);

INSERT INTO orders VALUES (1, '0000-00-00');  -- Valid in MySQL with permissive mode
INSERT INTO orders VALUES (2, '0000-00-00 00:00:00');  -- Also valid
```

### Crash Point

**File:** [`ClickHouseConverter.java:225`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

```java
public Object convertDate(Object value) {
    String dateStr = value.toString();
    // No zero-date handling!
    return LocalDate.parse(dateStr);  // CRASH on "0000-00-00"
}
```

### Exception

```
java.time.format.DateTimeParseException: Text '0000-00-00' could not be parsed: 
Invalid value for Year (valid values 1 - 999999999): 0
  at java.base/java.time.format.DateTimeFormatter.createError(DateTimeFormatter.java:2020)
  at java.base/java.time.format.DateTimeFormatter.parse(DateTimeFormatter.java:1955)
  at java.base/java.time.LocalDate.parse(LocalDate.java:428)
  at com.altinity.clickhouse.sink.connector.converter.ClickHouseConverter.convertDate(ClickHouseConverter.java:225)
```

### Impact

- **Immediate Failure:** Connector stops on first zero date
- **Legacy Data:** Common in older MySQL databases
- **Migration Blocker:** Prevents migration of legacy data

### Workaround

**Option 1: Clean data at source**
```sql
-- Update zero dates in MySQL
UPDATE orders SET order_date = '1970-01-01' WHERE order_date = '0000-00-00';
```

**Option 2: Use MySQL replication filter**
```sql
-- Create view that converts zero dates
CREATE VIEW orders_clean AS
SELECT 
    id,
    CASE 
        WHEN order_date = '0000-00-00' THEN '1970-01-01'
        ELSE order_date
    END as order_date
FROM orders;
```

**Option 3: Enable strict mode** (prevents new zero dates)
```sql
SET GLOBAL sql_mode = 'STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE';
```

### Required Fix

See [DATA-TYPE-BUGS.md#BUG-DATA-5](./DATA-TYPE-BUGS.md#bug-data-5)

---

## CRASH-4: Date Out of Range

**Severity:** HIGH  
**Frequency:** Rare but critical  
**Impact:** Crashes on historical or future dates

### Trigger Condition

```sql
-- MySQL supports wide date range
INSERT INTO events VALUES (1, '1969-12-31');  -- Before 1970
INSERT INTO events VALUES (2, '2150-01-01');  -- After 2149

-- ClickHouse Date range: 1970-01-01 to 2149-06-06
-- Both inserts will crash
```

### Crash Point

**File:** [`ClickHouseConverter.java:225`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

```java
public Object convertDate(Object value) {
    LocalDate date = LocalDate.parse(value.toString());
    // No range validation!
    return date;  // Crashes if out of ClickHouse Date range
}
```

### Exception

```
com.clickhouse.client.ClickHouseException: Code: 41, 
e.displayText() = DB::Exception: Date '1969-12-31' is out of range [1970-01-01, 2149-06-06]

  at com.clickhouse.jdbc.SqlExceptionUtils.handle(SqlExceptionUtils.java:88)
  at com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable.run(ClickHouseBatchRunnable.java:210)
```

### Impact

- **Historical Data:** Can't replicate pre-1970 dates
- **Future Dates:** Can't replicate far-future dates
- **Crash:** Connector stops on out-of-range date

### Workaround

**Option 1: Use DateTime64** (wider range)
```sql
-- ClickHouse DateTime64 supports wider range
CREATE TABLE events (
    id Int32,
    event_date DateTime64(3)  -- Supports 1900-2299
) ENGINE = MergeTree() ORDER BY id;
```

**Option 2: Clamp dates**
```sql
-- MySQL view with clamped dates
CREATE VIEW events_clamped AS
SELECT 
    id,
    CASE 
        WHEN event_date < '1970-01-01' THEN '1970-01-01'
        WHEN event_date > '2149-06-06' THEN '2149-06-06'
        ELSE event_date
    END as event_date
FROM events;
```

### Required Fix

See [DATA-TYPE-BUGS.md#BUG-DATA-4](./DATA-TYPE-BUGS.md#bug-data-4)

---

## CRASH-5: Unmapped Enum Type

**Severity:** HIGH  
**Frequency:** Common with ENUMs  
**Impact:** ClassCastException on enum conversion

### Trigger Condition

```sql
-- MySQL
CREATE TABLE users (
    id INT,
    status ENUM('active', 'inactive', 'suspended')
);

INSERT INTO users VALUES (1, 'active');
```

### Crash Point

**File:** [`ClickHouseDataTypeMapper.java:75`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/mapper/ClickHouseDataTypeMapper.java)

```java
public ClickHouseDataType mapType(String mysqlType) {
    switch (mysqlType.toUpperCase()) {
        // ... other types ...
        default:
            // ENUM not explicitly handled
            return ClickHouseDataType.String;  // May cause ClassCastException later
    }
}
```

### Exception

```
java.lang.ClassCastException: class java.lang.Integer cannot be cast to class java.lang.String
  at com.altinity.clickhouse.sink.connector.converter.ClickHouseConverter.convert(ClickHouseConverter.java:165)
  
-- OR --

com.clickhouse.client.ClickHouseException: Type mismatch: 
Column status expects String but got Int8
```

### Impact

- **Type Confusion:** Enum values may be integers or strings
- **Conversion Errors:** Wrong type used for conversion
- **Data Corruption:** Values stored incorrectly

### Workaround

**Option 1: Convert ENUM to String in MySQL**
```sql
-- Use VARCHAR instead of ENUM
ALTER TABLE users MODIFY status VARCHAR(20);
```

**Option 2: Explicit ClickHouse mapping**
```sql
-- Create with Enum8 in ClickHouse
CREATE TABLE users (
    id Int32,
    status Enum8('active' = 1, 'inactive' = 2, 'suspended' = 3)
) ENGINE = MergeTree() ORDER BY id;
```

### Required Fix

See [DATA-TYPE-BUGS.md#BUG-DATA-3](./DATA-TYPE-BUGS.md#bug-data-3)

---

## CRASH-6: Connection Pool Exhaustion

**Severity:** CRITICAL  
**Frequency:** Guaranteed after enough errors  
**Impact:** Complete connector failure

### Trigger Condition

```sql
-- Repeated errors that leak connections
-- After ~100 errors:

INSERT INTO invalid_table VALUES (...);  -- Table doesn't exist
INSERT INTO users VALUES ('invalid');     -- Type mismatch
-- ... errors continue ...

-- Eventually: Connection pool exhausted
```

### Crash Point

**File:** [`ClickHouseBatchRunnable.java:215`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)

```java
public void run() {
    Connection conn = null;
    try {
        conn = getConnection();
        // ... process batch ...
    } catch (SQLException e) {
        // Connection NOT closed on exception!
        log.error("Error", e);
    }
    // No finally block - connection leaked
}
```

### Exception

```
java.sql.SQLException: Cannot get a connection, pool exhausted - timeout waiting for connection
  at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:186)
  at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:145)
  at com.clickhouse.jdbc.ClickHouseDriver.connect(ClickHouseDriver.java:89)

Caused by: java.sql.SQLTransientConnectionException: 
HikariPool-1 - Connection is not available, request timed out after 30000ms.
```

### Impact

- **Progressive Failure:** Gets worse over time
- **Memory Leak:** Connections and PreparedStatements accumulate
- **Total Failure:** Eventually all connections leaked
- **Restart Required:** Only recovery is connector restart

### Monitoring

```sql
-- Check ClickHouse connections
SELECT * FROM system.processes WHERE user = 'connector_user';

-- Shows leaked connections
SELECT count(*) FROM system.processes WHERE elapsed > 3600;
```

### Workaround

**Temporary: Increase pool size** (delays problem)
```properties
clickhouse.connection.pool.size=100  # Default: 10
clickhouse.connection.timeout=60000
```

**Permanent: Restart connector periodically**
```bash
# Cron job to restart connector daily
0 2 * * * systemctl restart kafka-connect
```

### Required Fix

See [CONCURRENCY-BUGS.md#BUG-CONC-4](./CONCURRENCY-BUGS.md#bug-conc-4)

---

## CRASH-7: Reserved Keyword Unescaped

**Severity:** HIGH  
**Frequency:** Common with certain column names  
**Impact:** SQL syntax error, connector crash

### Trigger Condition

```sql
-- MySQL (backticks required)
CREATE TABLE analytics (
    `timestamp` BIGINT,
    `user` VARCHAR(50),
    `select` VARCHAR(100),
    `table` VARCHAR(100)
);

INSERT INTO analytics VALUES (1675432100, 'john', 'product', 'orders');
```

### Crash Point

**File:** [`ClickHouseQueryBuilder.java:65`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseQueryBuilder.java)

```java
public String buildInsertQuery(String tableName, List<String> columns) {
    String columnList = String.join(", ", columns);  // No escaping!
    return String.format("INSERT INTO %s (%s) VALUES (?)",
        tableName, columnList);
    
    // Generated: INSERT INTO analytics (timestamp, user, select, table) VALUES (?)
    // Error: SQL syntax error at 'select'
}
```

### Exception

```
com.clickhouse.client.ClickHouseException: Code: 62, 
e.displayText() = DB::Exception: Syntax error: failed at position 45: 
'select'. Expected one of: token, Comma, ClosingRoundBracket

  at com.clickhouse.jdbc.SqlExceptionUtils.handle(SqlExceptionUtils.java:88)
  at com.altinity.clickhouse.sink.connector.db.ClickHouseQueryBuilder.buildInsertQuery(ClickHouseQueryBuilder.java:65)
```

### Impact

- **Immediate Failure:** SQL syntax error on first insert
- **Common Issue:** Many tables use reserved words
- **Data Loss:** No data inserted until fixed

### Workaround

**Option 1: Rename columns in MySQL**
```sql
ALTER TABLE analytics CHANGE `timestamp` ts BIGINT;
ALTER TABLE analytics CHANGE `user` user_name VARCHAR(50);
ALTER TABLE analytics CHANGE `select` selection VARCHAR(100);
```

**Option 2: Rename in ClickHouse**
```sql
-- Create table with different column names
CREATE TABLE analytics (
    ts Int64,
    user_name String,
    selection String,
    table_name String
) ENGINE = MergeTree() ORDER BY ts;

-- Map in connector config
clickhouse.column.mapping.timestamp=ts
clickhouse.column.mapping.user=user_name
```

### Required Fix

See [SCHEMA-EVOLUTION-BUGS.md#BUG-SCHEMA-5](./SCHEMA-EVOLUTION-BUGS.md#bug-schema-5)

---

## CRASH-8: Decimal Overflow

**Severity:** MEDIUM  
**Frequency:** Rare  
**Impact:** ArithmeticException on large decimals

### Trigger Condition

```sql
-- MySQL supports very large decimals
CREATE TABLE prices (
    amount DECIMAL(65, 30)
);

INSERT INTO prices VALUES (12345678901234567890123456789012345.123456789012345678901234567890);
```

### Crash Point

**File:** [`ClickHouseConverter.java:270`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

```java
public Object convertDecimal(Object value) {
    BigDecimal decimal = new BigDecimal(value.toString());
    // No precision check!
    return decimal;  // May overflow ClickHouse Decimal128(38)
}
```

### Exception

```
java.lang.ArithmeticException: Decimal overflow
  at com.clickhouse.data.ClickHouseDataType.convert(ClickHouseDataType.java:142)
  at com.altinity.clickhouse.sink.connector.converter.ClickHouseConverter.convertDecimal(ClickHouseConverter.java:270)
```

### Workaround

```sql
-- Use smaller precision in MySQL
ALTER TABLE prices MODIFY amount DECIMAL(38, 18);

-- Or use Decimal256 in ClickHouse
CREATE TABLE prices (
    amount Decimal256(76)
) ENGINE = MergeTree() ORDER BY tuple();
```

### Required Fix

See [DATA-TYPE-BUGS.md#BUG-DATA-7](./DATA-TYPE-BUGS.md#bug-data-7)

---

## CRASH-9: Binary Encoding Corruption

**Severity:** MEDIUM  
**Frequency:** Common with BINARY/VARBINARY  
**Impact:** CharacterCodingException

### Trigger Condition

```sql
-- MySQL
CREATE TABLE files (
    id INT,
    data BINARY(16)  -- UUID bytes
);

INSERT INTO files VALUES (1, UNHEX('8F4B2C1A9D3E5F7B8C1A2D3E4F5A6B7C'));
```

### Crash Point

**File:** [`ClickHouseConverter.java:165`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

```java
public Object convertBinary(Object value) {
    // Treats binary as string - encoding error!
    return value.toString();
}
```

### Exception

```
java.nio.charset.CharacterCodingException: Input length = 1
  at java.base/java.nio.charset.CoderResult.throwException(CoderResult.java:274)
  at com.altinity.clickhouse.sink.connector.converter.ClickHouseConverter.convertBinary(ClickHouseConverter.java:165)
```

### Workaround

```sql
-- Store as hex string
CREATE VIEW files_hex AS
SELECT id, HEX(data) as data FROM files;
```

### Required Fix

See [DATA-TYPE-BUGS.md#BUG-DATA-6](./DATA-TYPE-BUGS.md#bug-data-6)

---

## CRASH-10: Schema Cache Corruption

**Severity:** CRITICAL  
**Frequency:** Rare but catastrophic  
**Impact:** NullPointerException, wrong schema used

### Trigger Condition

```sql
-- Thread 1: Reading schema
SELECT * FROM users;  -- Getting schema from cache

-- Thread 2: Updating schema (concurrent ALTER)
ALTER TABLE users ADD COLUMN age INT;

-- Thread 1: Uses corrupted schema
INSERT INTO users VALUES (...);  -- CRASH
```

### Crash Point

**File:** [`DBMetadata.java:95`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java)

```java
public static Map<String, ClickHouseStruct> getTableSchema(String tableName) {
    // Unsynchronized access!
    return tableToSchemaMap.get(tableName);  // May return null or corrupted data
}
```

### Exception

```
java.lang.NullPointerException: Cannot invoke "Map.get(Object)" because "schema" is null
  at com.altinity.clickhouse.sink.connector.db.DBMetadata.getTableSchema(DBMetadata.java:95)
  at com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable.run(ClickHouseBatchRunnable.java:180)
```

### Impact

- **Data Corruption:** Records written to wrong columns
- **Crashes:** NullPointerException when schema is null
- **Intermittent:** Hard to reproduce

### Workaround

```properties
# Disable schema caching (performance impact)
clickhouse.schema.cache.enabled=false

# Or use single thread
thread.pool.size=1
```

### Required Fix

See [CONCURRENCY-BUGS.md#BUG-CONC-2](./CONCURRENCY-BUGS.md#bug-conc-2)

---

## Crash Recovery Procedures

### Immediate Actions

1. **Check connector status**
   ```bash
   curl http://localhost:8083/connectors/clickhouse-sink/status
   ```

2. **Review error logs**
   ```bash
   tail -f /var/log/kafka-connect/connect.log | grep -i "exception\|error"
   ```

3. **Identify crash scenario** (match exception to table above)

4. **Apply workaround** (see specific crash scenario)

### Long-term Solutions

1. **Apply fixes** - See [FIXES-PRIORITY.md](./FIXES-PRIORITY.md)
2. **Add monitoring** - Alert on exceptions
3. **Implement validation** - Pre-validate records
4. **Add retry logic** - Don't crash on single bad record

### Monitoring Recommendations

```sql
-- ClickHouse monitoring queries

-- Check for connection leaks
SELECT count(*) FROM system.processes WHERE elapsed > 3600;

-- Check for failed inserts
SELECT count(*) FROM system.query_log 
WHERE type = 'ExceptionWhileProcessing' 
  AND event_time > now() - INTERVAL 1 HOUR;

-- Check table sizes (detect if replication stopped)
SELECT 
    table,
    formatReadableSize(total_bytes) as size,
    total_rows
FROM system.tables
WHERE database = 'replicated_db'
ORDER BY total_bytes DESC;
```

---

## Summary

| Priority | Count | Action |
|----------|-------|--------|
| P0 (Critical) | 6 | Fix immediately |
| P1 (High) | 4 | Fix within 1 month |
| **Total** | **10** | |

**Estimated Fix Time:** 60-80 hours

---

**Related Documents:**
- [Concurrency Bugs](./CONCURRENCY-BUGS.md) - Thread safety fixes
- [Data Type Bugs](./DATA-TYPE-BUGS.md) - Type conversion fixes
- [Schema Evolution Bugs](./SCHEMA-EVOLUTION-BUGS.md) - DDL fixes
- [Fix Priority](./FIXES-PRIORITY.md) - Implementation roadmap
