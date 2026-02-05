# Anti-Patterns

This document catalogs code quality issues, design anti-patterns, and poor practices found in the ClickHouse Sink Connector codebase.

## Overview

**Total Anti-Patterns:** 18  
**High Severity:** 6  
**Medium Severity:** 12  

Anti-patterns don't necessarily cause immediate failures but lead to maintainability issues, subtle bugs, and technical debt.

---

## High-Severity Anti-Patterns

### AP-HIGH-1: God Object - ClickHouseBatchRunnable

**Severity:** HIGH  
**Location:** [`ClickHouseBatchRunnable.java`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)  
**Lines:** Entire class (~500 lines)

**Problem:**
Single class responsible for too many concerns:
- Connection management
- Query building
- Batch processing
- Schema mapping
- Error handling
- Retry logic
- Metrics collection
- Transaction management

**Current Structure:**
```java
public class ClickHouseBatchRunnable implements Runnable {
    // 20+ instance variables
    private Map<String, Connection> databaseToConnectionMap;
    private Map<String, PreparedStatement> queryToPs;
    private Map<String, Buffer> queryToRecordsMap;
    // ... 15+ more fields
    
    // 30+ methods
    public void run() { ... }
    private void processRecords() { ... }
    private void buildQuery() { ... }
    private void executeQuery() { ... }
    private void handleErrors() { ... }
    // ... 25+ more methods
}
```

**Impact:**
- **Maintainability:** Hard to understand and modify
- **Testing:** Difficult to unit test individual concerns
- **Coupling:** High coupling between unrelated functionality
- **Reusability:** Can't reuse individual components

**Recommended Refactoring:**
```java
// Split into focused classes

public class ClickHouseBatchProcessor {
    private final ConnectionManager connectionManager;
    private final QueryBuilder queryBuilder;
    private final BatchExecutor batchExecutor;
    private final ErrorHandler errorHandler;
    private final MetricsCollector metrics;
    
    public void processBatch(Batch batch) {
        Connection conn = connectionManager.getConnection(batch.getDatabase());
        String query = queryBuilder.buildQuery(batch);
        
        try {
            batchExecutor.execute(conn, query, batch.getRecords());
            metrics.recordSuccess(batch);
        } catch (SQLException e) {
            errorHandler.handle(e, batch);
        }
    }
}

class ConnectionManager {
    private Map<String, Connection> connections = new ConcurrentHashMap<>();
    
    public Connection getConnection(String database) { ... }
    public void closeConnection(String database) { ... }
}

class QueryBuilder {
    public String buildInsertQuery(String table, List<String> columns) { ... }
    public String buildUpdateQuery(String table, Map<String, Object> values) { ... }
}

class BatchExecutor {
    public void execute(Connection conn, String query, List<Record> records) { ... }
}

class ErrorHandler {
    public void handle(Exception e, Batch batch) { ... }
    public boolean shouldRetry(Exception e) { ... }
}
```

**Benefits:**
- Single Responsibility Principle
- Easier testing (mock individual components)
- Better reusability
- Clearer dependencies

---

### AP-HIGH-2: Static State in DBMetadata

**Severity:** HIGH  
**Location:** [`DBMetadata.java`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java)

**Problem:**
Static mutable state shared across all instances, making the class difficult to test and potentially causing issues in multi-connector deployments.

**Current Code:**
```java
public class DBMetadata {
    // Static mutable state - ANTI-PATTERN!
    private static Map<String, Map<String, ClickHouseStruct>> tableToSchemaMap = new HashMap<>();
    
    public static void addTableSchema(String tableName, Map<String, ClickHouseStruct> schema) {
        tableToSchemaMap.put(tableName, schema);
    }
    
    public static Map<String, ClickHouseStruct> getTableSchema(String tableName) {
        return tableToSchemaMap.get(tableName);
    }
    
    // No way to clear or reset state!
    // No way to have multiple independent instances!
}
```

**Problems:**
1. **Testing:** Can't isolate tests, state bleeds between tests
2. **Multi-tenant:** Can't run multiple connectors in same JVM
3. **Thread Safety:** Requires synchronization (currently missing!)
4. **Lifecycle:** No clear initialization/cleanup
5. **Mockability:** Can't mock for testing

**Impact:**
```java
// Test 1
@Test
public void testSchemaA() {
    DBMetadata.addTableSchema("users", schemaA);
    // ... test code
}

// Test 2 - FAILS because Test 1's state still present!
@Test
public void testSchemaB() {
    DBMetadata.addTableSchema("users", schemaB);
    // Expects clean slate, but has Test 1's data!
}
```

**Recommended Refactoring:**
```java
// Instance-based with dependency injection
public class SchemaRegistry {
    private final Map<String, TableSchema> schemas = new ConcurrentHashMap<>();
    
    public void registerSchema(String tableName, TableSchema schema) {
        schemas.put(tableName, schema);
    }
    
    public TableSchema getSchema(String tableName) {
        return schemas.get(tableName);
    }
    
    public void clear() {
        schemas.clear();
    }
}

// Inject into classes that need it
public class ClickHouseBatchProcessor {
    private final SchemaRegistry schemaRegistry;
    
    public ClickHouseBatchProcessor(SchemaRegistry schemaRegistry) {
        this.schemaRegistry = schemaRegistry;
    }
    
    public void process(Record record) {
        TableSchema schema = schemaRegistry.getSchema(record.getTable());
        // ... use schema
    }
}

// Testing becomes easy
@Test
public void testProcessor() {
    SchemaRegistry registry = new SchemaRegistry();  // Clean instance
    registry.registerSchema("users", testSchema);
    
    ClickHouseBatchProcessor processor = new ClickHouseBatchProcessor(registry);
    // ... test
}
```

---

### AP-HIGH-3: Swallowed Exceptions

**Severity:** HIGH  
**Location:** Multiple files  

**Problem:**
Exceptions caught and logged but not propagated, hiding errors and making debugging difficult.

**Examples:**

**Example 1: Connection Cleanup**
```java
// ClickHouseBatchRunnable.java:250
try {
    if (conn != null) {
        conn.close();
    }
} catch (SQLException e) {
    log.error("Error closing connection", e);
    // Exception swallowed! No indication of problem to caller
}
```

**Example 2: Schema Update**
```java
// DBMetadata.java:120
try {
    updateSchema(tableName, newSchema);
} catch (Exception e) {
    log.error("Failed to update schema", e);
    // Swallowed! Caller thinks schema was updated successfully
}
```

**Example 3: Batch Processing**
```java
// ClickHouseSinkTask.java:180
for (Record record : records) {
    try {
        processRecord(record);
    } catch (Exception e) {
        log.error("Failed to process record", e);
        // Continue processing! May process same record again on restart
    }
}
```

**Impact:**
- **Silent Failures:** Errors not visible to monitoring
- **Debugging Difficulty:** Root cause hidden in logs
- **Data Loss:** Records silently dropped
- **State Corruption:** Partial operations succeed

**Recommended Pattern:**
```java
// 1. Propagate exceptions
public void closeConnection(Connection conn) throws SQLException {
    try {
        if (conn != null) {
            conn.close();
        }
    } catch (SQLException e) {
        log.error("Error closing connection", e);
        throw e;  // Propagate for caller to handle
    }
}

// 2. Wrap in custom exception
public void updateSchema(String table, Schema schema) {
    try {
        executeUpdate(table, schema);
    } catch (SQLException e) {
        log.error("Schema update failed for table {}", table, e);
        throw new SchemaUpdateException("Failed to update schema", e);
    }
}

// 3. Use error handler for batches
public void processRecords(List<Record> records) {
    List<Record> failed = new ArrayList<>();
    
    for (Record record : records) {
        try {
            processRecord(record);
        } catch (Exception e) {
            log.error("Failed to process record {}", record.id(), e);
            failed.add(record);
        }
    }
    
    if (!failed.isEmpty()) {
        throw new BatchProcessingException(
            String.format("Failed to process %d/%d records", failed.size(), records.size()),
            failed
        );
    }
}
```

---

### AP-HIGH-4: String-Based Error Handling

**Severity:** HIGH  
**Location:** Multiple files

**Problem:**
Errors identified by parsing exception messages (brittle) instead of exception types or error codes.

**Current Code:**
```java
// ClickHouseBatchRunnable.java:300
try {
    executeQuery(query);
} catch (SQLException e) {
    // Brittle! Message format could change
    if (e.getMessage().contains("Duplicate entry")) {
        // Handle duplicate
        log.warn("Duplicate entry, skipping");
    } else if (e.getMessage().contains("Connection reset")) {
        // Retry logic
        retry();
    } else if (e.getMessage().contains("Timeout")) {
        // Timeout handling
        handleTimeout();
    }
}
```

**Problems:**
1. **Fragile:** Exception messages can change between versions
2. **Localization:** Messages may be in different languages
3. **Substrings:** Partial matches can be unreliable
4. **Performance:** String matching is slower than type checks

**Better Approach:**
```java
// Use exception types
try {
    executeQuery(query);
} catch (SQLIntegrityConstraintViolationException e) {
    // Duplicate key violation
    handleDuplicate(e);
} catch (SQLTransientConnectionException e) {
    // Connection issue - retry
    retry();
} catch (SQLTimeoutException e) {
    // Timeout
    handleTimeout();
} catch (SQLException e) {
    // Generic SQL error
    handleSQLError(e);
}

// Or use error codes
try {
    executeQuery(query);
} catch (SQLException e) {
    switch (e.getErrorCode()) {
        case 1062:  // MySQL duplicate entry
            handleDuplicate(e);
            break;
        case 1213:  // Deadlock
            retry();
            break;
        case 2006:  // MySQL server has gone away
            reconnect();
            break;
        default:
            throw e;
    }
}

// ClickHouse specific
try {
    executeQuery(query);
} catch (ClickHouseException e) {
    if (e.getErrorCode() == ClickHouseErrorCode.DUPLICATE_KEY) {
        handleDuplicate(e);
    } else if (e.getErrorCode() == ClickHouseErrorCode.TIMEOUT) {
        handleTimeout();
    } else {
        throw e;
    }
}
```

---

### AP-HIGH-5: No Resource Management (Missing try-with-resources)

**Severity:** HIGH  
**Location:** Multiple files

**Problem:**
JDBC resources not properly managed, leading to resource leaks.

**Current Code:**
```java
// ClickHouseBatchRunnable.java:200
public void run() {
    Connection conn = null;
    PreparedStatement ps = null;
    
    try {
        conn = getConnection();
        ps = conn.prepareStatement(query);
        ps.execute();
    } catch (SQLException e) {
        log.error("Error", e);
        // Resources NOT closed on exception!
    }
    // No finally block - resources leaked if exception occurs
}
```

**Impact:**
- Connection leaks → pool exhaustion
- PreparedStatement leaks → memory exhaustion  
- ResultSet leaks → cursor exhaustion

**Recommended Pattern:**
```java
// Use try-with-resources (Java 7+)
public void run() {
    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(query)) {
        
        ps.execute();
        
    } catch (SQLException e) {
        log.error("Error executing query", e);
        throw new RuntimeException(e);
    }
    // Resources automatically closed, even on exception
}

// For complex scenarios
public void processBatch() {
    Connection conn = null;
    try {
        conn = getConnection();
        conn.setAutoCommit(false);
        
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            // Execute batch
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        
    } catch (SQLException e) {
        log.error("Batch processing failed", e);
        throw new BatchProcessingException(e);
    } finally {
        closeQuietly(conn);
    }
}

private void closeQuietly(Connection conn) {
    if (conn != null) {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing connection", e);
        }
    }
}
```

---

### AP-HIGH-6: Primitive Obsession

**Severity:** HIGH  
**Location:** Multiple files

**Problem:**
Using primitives/strings instead of value objects, losing type safety and validation.

**Current Code:**
```java
// Using primitives everywhere
public void processRecord(
    String tableName,
    String database,
    String columnName,
    String columnType,
    Object value,
    long timestamp
) {
    // No validation!
    // No type safety!
    // Easy to swap parameters
}

// Calling code - error-prone
processRecord(
    "users",      // table
    "production", // database
    "email",      // column
    "String",     // type
    value,
    System.currentTimeMillis()
);

// Easy to make mistakes
processRecord(
    "production", // WRONG! swapped with database
    "users",      // WRONG! swapped with table
    "email",
    "String",
    value,
    System.currentTimeMillis()
);
```

**Recommended Pattern:**
```java
// Value Objects
public class TableName {
    private final String database;
    private final String table;
    
    public TableName(String database, String table) {
        if (database == null || database.isEmpty()) {
            throw new IllegalArgumentException("Database cannot be empty");
        }
        if (table == null || table.isEmpty()) {
            throw new IllegalArgumentException("Table cannot be empty");
        }
        this.database = database;
        this.table = table;
    }
    
    public String getDatabase() { return database; }
    public String getTable() { return table; }
    public String getFullName() { return database + "." + table; }
    
    @Override
    public boolean equals(Object o) { ... }
    @Override
    public int hashCode() { ... }
}

public class ColumnDefinition {
    private final String name;
    private final ClickHouseDataType type;
    private final boolean nullable;
    
    public ColumnDefinition(String name, ClickHouseDataType type, boolean nullable) {
        this.name = requireNonNull(name, "Column name required");
        this.type = requireNonNull(type, "Column type required");
        this.nullable = nullable;
    }
    
    // Getters, equals, hashCode
}

public class Record {
    private final TableName table;
    private final Map<ColumnDefinition, Object> values;
    private final Instant timestamp;
    
    // Type-safe constructor, validation, etc.
}

// Usage - type safe and clear
public void processRecord(Record record) {
    TableName table = record.getTable();
    // Can't swap parameters - compiler enforced!
}
```

---

## Medium-Severity Anti-Patterns

### AP-MED-1: Magic Numbers

**Severity:** MEDIUM  
**Location:** Multiple files

**Problem:**
Hardcoded numeric literals without explanation.

**Examples:**
```java
// What is 1000?
if (records.size() > 1000) {
    flush();
}

// What is 30000?
Thread.sleep(30000);

// What is 10?
for (int i = 0; i < 10; i++) {
    retry();
}
```

**Fix:**
```java
private static final int MAX_BATCH_SIZE = 1000;
private static final int RETRY_DELAY_MS = 30000;
private static final int MAX_RETRIES = 10;

if (records.size() > MAX_BATCH_SIZE) {
    flush();
}

Thread.sleep(RETRY_DELAY_MS);

for (int i = 0; i < MAX_RETRIES; i++) {
    retry();
}
```

---

### AP-MED-2: Deep Nesting

**Severity:** MEDIUM  
**Location:** [`ClickHouseBatchRunnable.java:300`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)

**Problem:**
Excessive nesting (4+ levels) making code hard to read.

**Current Code:**
```java
public void process() {
    if (isEnabled()) {
        for (Record record : records) {
            if (record.isValid()) {
                try {
                    if (shouldProcess(record)) {
                        if (connection != null) {
                            // 5 levels deep!
                            processRecord(record);
                        } else {
                            log.error("No connection");
                        }
                    }
                } catch (Exception e) {
                    handleError(e);
                }
            }
        }
    }
}
```

**Refactored:**
```java
public void process() {
    if (!isEnabled()) {
        return;  // Early return
    }
    
    for (Record record : records) {
        processRecord(record);
    }
}

private void processRecord(Record record) {
    if (!record.isValid()) {
        return;  // Guard clause
    }
    
    if (!shouldProcess(record)) {
        return;  // Guard clause
    }
    
    if (connection == null) {
        log.error("No connection");
        return;
    }
    
    try {
        executeProcess(record);
    } catch (Exception e) {
        handleError(e);
    }
}
```

---

### AP-MED-3: Long Methods

**Severity:** MEDIUM  
**Location:** [`ClickHouseBatchRunnable.run()`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)

**Problem:**
Methods exceeding 50 lines, doing too many things.

**Current:** `run()` method is 200+ lines

**Fix:** Extract to smaller methods
```java
public void run() {
    prepareConnection();
    List<Batch> batches = prepareBatches();
    executeBatches(batches);
    cleanup();
}

private void prepareConnection() { ... }
private List<Batch> prepareBatches() { ... }
private void executeBatches(List<Batch> batches) { ... }
private void cleanup() { ... }
```

---

### AP-MED-4: Mutable Static State

**Severity:** MEDIUM  
**Location:** Configuration classes

**Problem:**
Static configuration that can be modified at runtime.

**Current:**
```java
public class Config {
    public static int batchSize = 1000;  // Mutable!
    public static String endpoint = "localhost";  // Can be changed!
}
```

**Fix:**
```java
public class Config {
    private final int batchSize;
    private final String endpoint;
    
    public Config(int batchSize, String endpoint) {
        this.batchSize = batchSize;
        this.endpoint = endpoint;
    }
    
    public int getBatchSize() { return batchSize; }
    public String getEndpoint() { return endpoint; }
}
```

---

### AP-MED-5: Boolean Parameters

**Severity:** MEDIUM  
**Location:** Multiple files

**Problem:**
Boolean flags make method calls unclear.

**Current:**
```java
processRecord(record, true, false, true);
// What do these booleans mean?!
```

**Fix:**
```java
// Use enum or builder
enum ProcessingMode {
    WITH_VALIDATION,
    WITHOUT_VALIDATION
}

enum RetryPolicy {
    RETRY_ON_FAILURE,
    FAIL_FAST
}

processRecord(record, 
    ProcessingMode.WITH_VALIDATION,
    RetryPolicy.RETRY_ON_FAILURE,
    LogLevel.DEBUG);

// Or use builder
new RecordProcessor.Builder()
    .withValidation(true)
    .withRetry(false)
    .withLogging(true)
    .process(record);
```

---

### AP-MED-6: Null Return Values

**Severity:** MEDIUM  
**Location:** Multiple files

**Problem:**
Returning null instead of Optional or empty collections.

**Current:**
```java
public Map<String, Object> getSchema(String table) {
    if (!schemas.containsKey(table)) {
        return null;  // NullPointerException waiting to happen!
    }
    return schemas.get(table);
}

// Caller must remember to check
Map<String, Object> schema = getSchema("users");
if (schema != null) {  // Easy to forget!
    // use schema
}
```

**Fix:**
```java
// Use Optional
public Optional<Map<String, Object>> getSchema(String table) {
    return Optional.ofNullable(schemas.get(table));
}

// Usage
getSchema("users").ifPresent(schema -> {
    // use schema
});

// Or for collections, return empty
public List<Record> getRecords(String table) {
    List<Record> records = recordMap.get(table);
    return records != null ? records : Collections.emptyList();
}
```

---

### AP-MED-7: Commented-Out Code

**Severity:** MEDIUM  
**Location:** Multiple files

**Problem:**
Large blocks of commented code clutter codebase.

**Fix:** Remove and rely on version control

---

### AP-MED-8: Inconsistent Naming

**Severity:** MEDIUM  
**Location:** Multiple files

**Problem:**
Same concept named differently in different places.

**Examples:**
- `tableName` vs `table` vs `tbl`
- `connection` vs `conn` vs `c`
- `record` vs `rec` vs `r`

**Fix:** Use consistent naming conventions

---

### AP-MED-9: No Input Validation

**Severity:** MEDIUM  
**Location:** Public methods

**Problem:**
Methods don't validate parameters.

**Current:**
```java
public void setTableName(String table) {
    this.table = table;  // What if table is null or empty?
}
```

**Fix:**
```java
public void setTableName(String table) {
    if (table == null || table.trim().isEmpty()) {
        throw new IllegalArgumentException("Table name cannot be null or empty");
    }
    this.table = table;
}

// Or use Objects.requireNonNull
public void setTableName(String table) {
    this.table = Objects.requireNonNull(table, "Table name required");
}
```

---

### AP-MED-10: Catching Generic Exceptions

**Severity:** MEDIUM  
**Location:** Multiple files

**Problem:**
Catching `Exception` or `Throwable` instead of specific types.

**Current:**
```java
try {
    processRecord(record);
} catch (Exception e) {  // Too broad!
    // Catches everything, including programming errors
    log.error("Error", e);
}
```

**Fix:**
```java
try {
    processRecord(record);
} catch (SQLException | IOException e) {
    // Specific exceptions expected
    log.error("Processing error", e);
    retry();
} catch (IllegalArgumentException e) {
    // Validation error - don't retry
    log.error("Invalid record", e);
    sendToDeadLetterQueue(record);
}
// Let unchecked exceptions propagate
```

---

### AP-MED-11: Missing toString/equals/hashCode

**Severity:** MEDIUM  
**Location:** Data classes

**Problem:**
Data classes without proper `toString()`, `equals()`, `hashCode()`.

**Fix:**
```java
public class Record {
    private final String table;
    private final Map<String, Object> values;
    
    @Override
    public String toString() {
        return String.format("Record{table='%s', values=%s}", table, values);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Record record = (Record) o;
        return Objects.equals(table, record.table) &&
               Objects.equals(values, record.values);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(table, values);
    }
}

// Or use Lombok
@Data
public class Record {
    private final String table;
    private final Map<String, Object> values;
}
```

---

### AP-MED-12: No Logging Levels

**Severity:** MEDIUM  
**Location:** Multiple files

**Problem:**
Everything logged at same level or wrong level.

**Current:**
```java
log.info("Starting processing");  // OK
log.info("Record: " + record);    // Should be DEBUG
log.info("Error occurred");       // Should be ERROR
```

**Fix:**
```java
log.debug("Processing record: {}", record);  // Verbose details
log.info("Batch of {} records processed", count);  // Important events
log.warn("Retry attempt {} failed", attempt);  // Warnings
log.error("Failed to connect to database", e);  // Errors
```

---

## Summary

### By Severity

| Severity | Count | Estimated Fix Time |
|----------|-------|-------------------|
| High | 6 | 60 hours |
| Medium | 12 | 40 hours |
| **Total** | **18** | **100 hours** |

### By Category

| Category | Count |
|----------|-------|
| Architecture/Design | 6 |
| Error Handling | 4 |
| Resource Management | 3 |
| Code Quality | 5 |

### Priority Order

1. **AP-HIGH-4** - String-based error handling (breaks easily)
2. **AP-HIGH-3** - Swallowed exceptions (hides errors)
3. **AP-HIGH-5** - Resource management (causes leaks)
4. **AP-HIGH-2** - Static state (testing/deployment issues)
5. **AP-HIGH-1** - God object (maintainability)
6. **AP-HIGH-6** - Primitive obsession (type safety)
7. Medium-severity items as time permits

---

## Refactoring Strategy

### Phase 1: Critical Fixes (Weeks 1-2)
- Fix resource leaks (AP-HIGH-5)
- Fix exception handling (AP-HIGH-3, AP-HIGH-4)
- Add input validation (AP-MED-9)

### Phase 2: Architecture (Weeks 3-6)
- Refactor God object (AP-HIGH-1)
- Remove static state (AP-HIGH-2)
- Introduce value objects (AP-HIGH-6)

### Phase 3: Code Quality (Weeks 7-8)
- Fix naming inconsistencies
- Reduce nesting and method length
- Add proper logging
- Improve code documentation

### Phase 4: Testing & Validation
- Add comprehensive unit tests
- Add integration tests
- Performance testing
- Code review

---

**Related Documents:**
- [Concurrency Bugs](./CONCURRENCY-BUGS.md) - Thread safety issues
- [Fix Priority](./FIXES-PRIORITY.md) - Overall implementation plan
- [Production Readiness](./PRODUCTION-READINESS.md) - Deployment guidance
