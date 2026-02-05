# Concurrency Bugs

This document details all thread-safety issues found in the ClickHouse Sink Connector that can cause race conditions, data corruption, and crashes in multi-threaded deployments.

## Overview

**Total Bugs:** 7  
**Severity:** CRITICAL (7)  
**Affected Component:** All components using `thread.pool.size > 1`  
**Production Impact:** Data corruption, connection leaks, crashes, state inconsistency

## Summary Table

| ID | Issue | Location | Severity | Impact |
|----|-------|----------|----------|--------|
| BUG-CONC-1 | HashMap Race Condition | ClickHouseBatchRunnable.java:61-72 | CRITICAL | Data corruption, crashes |
| BUG-CONC-2 | Unsynchronized DDL Cache | DBMetadata.java | CRITICAL | Schema corruption |
| BUG-CONC-3 | AtomicBoolean Misuse | PreparedStatementExecutor.java:45-58 | CRITICAL | State corruption |
| BUG-CONC-4 | Resource Leak | ClickHouseBatchRunnable.java:200-250 | CRITICAL | Connection exhaustion |
| BUG-CONC-5 | Check-Then-Act Race | DbWriter.java:150-165 | HIGH | Duplicate processing |
| BUG-CONC-6 | Non-Thread-Safe Map | ClickHouseSinkTask.java:89 | CRITICAL | Task state corruption |
| BUG-CONC-7 | Shared Map Sync Issues | ClickHouseBatchRunnable.java:300-320 | HIGH | Query deduplication fails |

---

## BUG-CONC-1: HashMap Race Condition in ClickHouseBatchRunnable

**Severity:** CRITICAL  
**Location:** [`ClickHouseBatchRunnable.java:61-72`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)

### Current Code

```java
public class ClickHouseBatchRunnable implements Runnable {
    // Line 61-72
    private Map<String, Connection> databaseToConnectionMap = new HashMap<>();
    private Map<String, String> databaseOverrideMap = new HashMap<>();
    private Map<String, PreparedStatement> queryToPs = new HashMap<>();
    
    public void run() {
        // Multiple threads access these maps concurrently
        for (Map.Entry<String, Buffer> entry : queryToRecordsMap.entrySet()) {
            Connection conn = databaseToConnectionMap.get(database);
            // ... concurrent read/write to HashMap
        }
    }
}
```

### Problem

Non-thread-safe `HashMap` instances are accessed by multiple threads when `thread.pool.size > 1`. The connector creates multiple `ClickHouseBatchRunnable` instances and executes them concurrently without any synchronization.

**Race Condition Flow:**
1. Thread A reads `databaseToConnectionMap.get(database)` 
2. Thread B simultaneously writes `databaseToConnectionMap.put(database, newConn)`
3. Internal HashMap structure corrupts
4. Throws `ConcurrentModificationException` or returns wrong connection
5. Data goes to wrong database or connector crashes

### Impact

- **Immediate:** `ConcurrentModificationException` crashes
- **Data Corruption:** Records written to wrong database
- **Connection Leaks:** Lost connection references
- **State Corruption:** HashMap internal structure breaks, requiring restart

### Reproduction

```java
// Configuration that triggers the bug
thread.pool.size=4
tasks.max=8

// Run with multiple databases
INSERT INTO db1.table1 VALUES (...); -- Thread 1
INSERT INTO db2.table2 VALUES (...); -- Thread 2
// ConcurrentModificationException thrown
```

### Proposed Fix

Replace all `HashMap` instances with `ConcurrentHashMap`:

```java
public class ClickHouseBatchRunnable implements Runnable {
    // Thread-safe concurrent collections
    private Map<String, Connection> databaseToConnectionMap = new ConcurrentHashMap<>();
    private Map<String, String> databaseOverrideMap = new ConcurrentHashMap<>();
    private Map<String, PreparedStatement> queryToPs = new ConcurrentHashMap<>();
    
    public void run() {
        // Now safe for concurrent access
        for (Map.Entry<String, Buffer> entry : queryToRecordsMap.entrySet()) {
            Connection conn = databaseToConnectionMap.get(database);
            // ... operations are thread-safe
        }
    }
}
```

### Testing Requirements

1. Multi-threaded integration test with `thread.pool.size=4`
2. Concurrent inserts to multiple databases
3. Stress test with 1000+ concurrent operations
4. Verify no `ConcurrentModificationException` thrown
5. Verify correct database routing under load

---

## BUG-CONC-2: Unsynchronized DDL Cache Access

**Severity:** CRITICAL  
**Location:** [`DBMetadata.java`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java)

### Current Code

```java
public class DBMetadata {
    // Shared cache accessed by all threads
    private static Map<String, Map<String, ClickHouseStruct>> tableToSchemaMap = new HashMap<>();
    
    public static void addTableSchema(String tableName, Map<String, ClickHouseStruct> schema) {
        // No synchronization - race condition!
        tableToSchemaMap.put(tableName, schema);
    }
    
    public static Map<String, ClickHouseStruct> getTableSchema(String tableName) {
        // No synchronization - race condition!
        return tableToSchemaMap.get(tableName);
    }
}
```

### Problem

Static schema cache used by all worker threads is not synchronized. Multiple threads:
1. Read schema during record processing
2. Update schema during DDL operations  
3. Modify schema during ALTER detection

**Race Condition Scenarios:**

**Scenario A: Concurrent Schema Update**
```
T1: getTableSchema("users") → returns schema v1
T2: addTableSchema("users", schema v2) → updates cache
T1: Uses stale schema v1 → inserts with wrong column mapping
Result: Data written to wrong columns
```

**Scenario B: Concurrent Read During Update**
```
T1: getTableSchema("orders") → starts reading HashMap
T2: addTableSchema("orders", newSchema) → modifies HashMap structure  
T1: ConcurrentModificationException OR reads corrupted schema
Result: Crash or silent data corruption
```

### Impact

- **Data Corruption:** Records mapped to wrong columns
- **Schema Mismatch:** Type conversion uses outdated schema
- **Crashes:** ConcurrentModificationException
- **Silent Failures:** Inserts succeed but data is corrupted

### Reproduction

```sql
-- Terminal 1: Continuous inserts
INSERT INTO users (id, name, email) VALUES (1, 'John', 'john@example.com');

-- Terminal 2: Concurrent ALTER (triggers schema refresh)
ALTER TABLE users ADD COLUMN age INT;

-- Result: Some inserts use old schema, some use new schema
-- Data corruption occurs
```

### Proposed Fix

**Option 1: ConcurrentHashMap (Recommended)**
```java
public class DBMetadata {
    private static Map<String, Map<String, ClickHouseStruct>> tableToSchemaMap = 
        new ConcurrentHashMap<>();
    
    public static void addTableSchema(String tableName, Map<String, ClickHouseStruct> schema) {
        tableToSchemaMap.put(tableName, new ConcurrentHashMap<>(schema));
    }
    
    public static Map<String, ClickHouseStruct> getTableSchema(String tableName) {
        return tableToSchemaMap.get(tableName);
    }
}
```

**Option 2: Synchronized Access**
```java
public class DBMetadata {
    private static Map<String, Map<String, ClickHouseStruct>> tableToSchemaMap = new HashMap<>();
    private static final Object SCHEMA_LOCK = new Object();
    
    public static void addTableSchema(String tableName, Map<String, ClickHouseStruct> schema) {
        synchronized (SCHEMA_LOCK) {
            tableToSchemaMap.put(tableName, schema);
        }
    }
    
    public static Map<String, ClickHouseStruct> getTableSchema(String tableName) {
        synchronized (SCHEMA_LOCK) {
            return tableToSchemaMap.get(tableName);
        }
    }
}
```

### Testing Requirements

1. Concurrent schema reads while DDL operations execute
2. Multiple threads inserting to different tables simultaneously
3. Schema evolution test with concurrent inserts
4. Verify no ConcurrentModificationException
5. Validate data integrity under concurrent load

---

## BUG-CONC-3: AtomicBoolean Misuse in PreparedStatementExecutor

**Severity:** CRITICAL  
**Location:** [`PreparedStatementExecutor.java:45-58`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/PreparedStatementExecutor.java)

### Current Code

```java
public class PreparedStatementExecutor {
    private AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    public void addToBatch(Record record) {
        // Line 45-58
        if (!isProcessing.get()) {  // Check
            isProcessing.set(true);  // Act - NOT ATOMIC!
            processBatch();
            isProcessing.set(false);
        }
    }
}
```

### Problem

Classic **check-then-act** race condition. `AtomicBoolean` is misused - the get/set operations are separate, not atomic as a whole.

**Race Condition Flow:**
```
T1: isProcessing.get() → returns false (line 45)
T2: isProcessing.get() → returns false (line 45)  [RACE!]
T1: isProcessing.set(true) → claims lock (line 46)
T2: isProcessing.set(true) → overwrites lock (line 46)
T1: processBatch() → starts processing
T2: processBatch() → ALSO starts processing [DUPLICATE!]
```

### Impact

- **Duplicate Processing:** Same batch processed twice
- **Data Duplication:** Records inserted multiple times
- **Resource Waste:** Double database operations
- **State Corruption:** Batch state becomes inconsistent

### Reproduction

```bash
# Configure multi-threaded
thread.pool.size=4

# High-frequency inserts trigger race
for i in {1..10000}; do
  mysql> INSERT INTO test VALUES ($i);
done

# Result: Duplicate records in ClickHouse
SELECT count(*), count(DISTINCT id) FROM test;
-- count(*) > count(DISTINCT id) -- DUPLICATES!
```

### Proposed Fix

Use `compareAndSet()` for atomic check-then-act:

```java
public class PreparedStatementExecutor {
    private AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    public void addToBatch(Record record) {
        // Atomic check-and-set operation
        if (isProcessing.compareAndSet(false, true)) {
            try {
                processBatch();
            } finally {
                isProcessing.set(false);
            }
        } else {
            // Already processing, queue for next batch
            queueForNextBatch(record);
        }
    }
}
```

**Alternative: Use ReentrantLock**
```java
public class PreparedStatementExecutor {
    private final ReentrantLock batchLock = new ReentrantLock();
    
    public void addToBatch(Record record) {
        if (batchLock.tryLock()) {
            try {
                processBatch();
            } finally {
                batchLock.unlock();
            }
        } else {
            queueForNextBatch(record);
        }
    }
}
```

### Testing Requirements

1. Concurrent addToBatch() calls from multiple threads
2. Verify batch processed exactly once
3. Verify no duplicate records in database
4. Stress test with 10,000+ concurrent operations

---

## BUG-CONC-4: Resource Leak - Connection Not Closed on Exception

**Severity:** CRITICAL  
**Location:** [`ClickHouseBatchRunnable.java:200-250`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)

### Current Code

```java
public void run() {
    Connection conn = null;
    PreparedStatement ps = null;
    try {
        conn = databaseToConnectionMap.get(database);
        if (conn == null) {
            conn = DriverManager.getConnection(url);
            databaseToConnectionMap.put(database, conn);  // Line 210
        }
        
        ps = conn.prepareStatement(query);
        // ... execute batch ...
        
    } catch (SQLException e) {
        log.error("Error executing batch", e);
        // Connection and PreparedStatement NOT closed!
        // Leaked resources accumulate in pool
    }
    // No finally block to ensure cleanup
}
```

### Problem

Exception handling does not close database resources. When exceptions occur:
1. Connection remains in `databaseToConnectionMap` but is dead
2. PreparedStatement not closed → memory leak
3. Connection not returned to pool → pool exhaustion
4. Next operation gets stale connection → more errors

**Leak Accumulation:**
```
Iteration 1: Exception → 1 connection leaked
Iteration 2: Exception → 2 connections leaked  
Iteration 3: Exception → 3 connections leaked
...
Iteration N: Connection pool exhausted → total failure
```

### Impact

- **Connection Pool Exhaustion:** Eventually all connections leak
- **OutOfMemoryError:** PreparedStatements accumulate
- **Cascading Failures:** Dead connections cause more exceptions
- **Service Downtime:** Requires connector restart to recover

### Reproduction

```sql
-- Trigger exceptions that leak resources
INSERT INTO invalid_table VALUES (...);  -- Table doesn't exist
INSERT INTO users VALUES ('invalid');     -- Type mismatch

-- After ~100 exceptions
ERROR: Could not get connection from pool - timeout
-- Connection pool exhausted, connector dead
```

### Proposed Fix

Implement proper resource cleanup with try-with-resources:

```java
public void run() {
    String database = extractDatabase();
    
    try {
        Connection conn = getOrCreateConnection(database);
        
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            // Execute batch
            for (Record record : records) {
                setParameters(ps, record);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
            
        } catch (SQLException e) {
            log.error("Error executing batch", e);
            rollbackSafely(conn);
            // Remove dead connection from map
            closeAndRemoveConnection(database, conn);
            throw e;  // Propagate for retry logic
        }
        
    } catch (Exception e) {
        log.error("Fatal error in batch processing", e);
        metrics.incrementFailureCount();
    }
}

private void closeAndRemoveConnection(String database, Connection conn) {
    try {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    } catch (SQLException e) {
        log.warn("Error closing connection", e);
    } finally {
        databaseToConnectionMap.remove(database);
    }
}

private void rollbackSafely(Connection conn) {
    try {
        if (conn != null && !conn.getAutoCommit()) {
            conn.rollback();
        }
    } catch (SQLException e) {
        log.warn("Error rolling back transaction", e);
    }
}
```

### Testing Requirements

1. Exception handling test - verify connections closed
2. Connection pool monitoring under error conditions
3. Memory leak test - verify no PreparedStatement accumulation
4. Recovery test - verify connector recovers after errors

---

## BUG-CONC-5: Check-Then-Act Race in DbWriter

**Severity:** HIGH  
**Location:** [`DbWriter.java:150-165`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java)

### Current Code

```java
public class DbWriter {
    private Map<String, Long> tableToLastWriteTime = new HashMap<>();
    
    public void write(String table, List<Record> records) {
        // Line 150-165
        Long lastWrite = tableToLastWriteTime.get(table);  // Check
        
        if (lastWrite == null || System.currentTimeMillis() - lastWrite > FLUSH_INTERVAL) {
            // Act - race condition here!
            flushData(table, records);
            tableToLastWriteTime.put(table, System.currentTimeMillis());
        }
    }
}
```

### Problem

Multiple threads can pass the check simultaneously before any thread updates the timestamp.

**Race Condition:**
```
T1: lastWrite = null (line 151)
T2: lastWrite = null (line 151)  [Both pass check]
T1: flushData() starts
T2: flushData() starts [DUPLICATE FLUSH!]
T1: put(table, time1)
T2: put(table, time2) [Overwrites T1's timestamp]
```

### Impact

- **Duplicate Flushes:** Same data flushed multiple times
- **Data Duplication:** If not using deduplication
- **Resource Waste:** Unnecessary database operations
- **Performance Degradation:** Double the I/O operations

### Proposed Fix

Use `ConcurrentHashMap.computeIfAbsent()` for atomic check-update:

```java
public class DbWriter {
    private Map<String, Long> tableToLastWriteTime = new ConcurrentHashMap<>();
    private Map<String, Lock> tableToLock = new ConcurrentHashMap<>();
    
    public void write(String table, List<Record> records) {
        Lock lock = tableToLock.computeIfAbsent(table, k -> new ReentrantLock());
        
        if (lock.tryLock()) {
            try {
                Long lastWrite = tableToLastWriteTime.get(table);
                
                if (lastWrite == null || System.currentTimeMillis() - lastWrite > FLUSH_INTERVAL) {
                    flushData(table, records);
                    tableToLastWriteTime.put(table, System.currentTimeMillis());
                }
            } finally {
                lock.unlock();
            }
        } else {
            // Another thread is flushing, skip this batch
            log.debug("Skipping flush for table {}, already in progress", table);
        }
    }
}
```

### Testing Requirements

1. Concurrent writes to same table from multiple threads
2. Verify data flushed exactly once per interval
3. Verify no duplicate data in ClickHouse

---

## BUG-CONC-6: Non-Thread-Safe topicToDbWriterMap

**Severity:** CRITICAL  
**Location:** [`ClickHouseSinkTask.java:89`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkTask.java)

### Current Code

```java
public class ClickHouseSinkTask extends SinkTask {
    // Line 89
    private Map<String, DbWriter> topicToDbWriterMap = new HashMap<>();
    
    @Override
    public void put(Collection<SinkRecord> records) {
        for (SinkRecord record : records) {
            String topic = record.topic();
            DbWriter writer = topicToDbWriterMap.get(topic);  // Read
            
            if (writer == null) {
                writer = new DbWriter(config);
                topicToDbWriterMap.put(topic, writer);  // Write - RACE!
            }
            
            writer.write(record);  // Multiple threads access same writer
        }
    }
}
```

### Problem

Kafka Connect can call `put()` from multiple threads concurrently when `tasks.max > 1`. The `HashMap` is not thread-safe, leading to:
1. Concurrent modification exceptions
2. Lost writer instances
3. Multiple writers created for same topic

### Impact

- **State Corruption:** Task state becomes inconsistent
- **Crashes:** ConcurrentModificationException
- **Duplicate Writers:** Multiple writers for same topic waste resources
- **Data Loss:** Lost writer references mean lost data

### Proposed Fix

```java
public class ClickHouseSinkTask extends SinkTask {
    private Map<String, DbWriter> topicToDbWriterMap = new ConcurrentHashMap<>();
    
    @Override
    public void put(Collection<SinkRecord> records) {
        for (SinkRecord record : records) {
            String topic = record.topic();
            
            // Atomic get-or-create
            DbWriter writer = topicToDbWriterMap.computeIfAbsent(topic, 
                t -> new DbWriter(config, t));
            
            writer.write(record);
        }
    }
}
```

---

## BUG-CONC-7: Shared queryToRecordsMap Synchronization Issues

**Severity:** HIGH  
**Location:** [`ClickHouseBatchRunnable.java:300-320`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)

### Current Code

```java
public class ClickHouseBatchRunnable implements Runnable {
    // Shared across multiple runnable instances
    private Map<String, Buffer> queryToRecordsMap;
    
    public void run() {
        // Line 300-320
        for (Map.Entry<String, Buffer> entry : queryToRecordsMap.entrySet()) {
            String query = entry.getKey();
            Buffer buffer = entry.getValue();
            
            // Process buffer
            // ... other threads may modify queryToRecordsMap during iteration
        }
    }
}
```

### Problem

Iterating over shared map while other threads modify it causes `ConcurrentModificationException`.

### Proposed Fix

```java
public void run() {
    // Create snapshot for safe iteration
    Map<String, Buffer> snapshot = new HashMap<>(queryToRecordsMap);
    
    for (Map.Entry<String, Buffer> entry : snapshot.entrySet()) {
        String query = entry.getKey();
        Buffer buffer = entry.getValue();
        processBuffer(query, buffer);
    }
}
```

---

## Summary of Fixes

| Bug ID | Fix Complexity | Estimated Effort | Risk Level |
|--------|---------------|------------------|------------|
| CONC-1 | Low | 2 hours | Low |
| CONC-2 | Medium | 4 hours | Medium |
| CONC-3 | Medium | 3 hours | Low |
| CONC-4 | High | 8 hours | Medium |
| CONC-5 | Medium | 4 hours | Low |
| CONC-6 | Low | 2 hours | Low |
| CONC-7 | Low | 2 hours | Low |
| **TOTAL** | | **25 hours** | |

## Recommended Fix Order

1. **CONC-1** - Most critical, easiest fix
2. **CONC-6** - Critical for task stability
3. **CONC-2** - Critical for data integrity
4. **CONC-4** - Prevents resource exhaustion
5. **CONC-3** - Prevents data duplication
6. **CONC-5** - Performance optimization
7. **CONC-7** - Iterator stability

## Testing Strategy

### Unit Tests
```java
@Test
public void testConcurrentHashMapAccess() {
    ExecutorService executor = Executors.newFixedThreadPool(10);
    ClickHouseBatchRunnable runnable = new ClickHouseBatchRunnable();
    
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> runnable.run());
    }
    
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);
    
    // Verify no exceptions thrown
    // Verify data integrity
}
```

### Integration Tests
- Multi-threaded connector with `thread.pool.size=4`
- Concurrent operations on multiple tables
- Schema changes during active processing
- Exception scenarios with resource cleanup verification

### Stress Tests
- 10,000+ concurrent operations
- Long-running tests (24+ hours)
- Resource monitoring (connections, memory, threads)
- Data integrity validation

---

**Related Documents:**
- [Fix Priority](./FIXES-PRIORITY.md) - Implementation roadmap
- [Production Readiness](./PRODUCTION-READINESS.md) - Deployment guidance
- [Anti-Patterns](./ANTI-PATTERNS.md) - Code quality issues
