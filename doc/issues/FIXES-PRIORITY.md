# Fix Priority and Implementation Roadmap

This document provides a prioritized roadmap for fixing all identified issues in the ClickHouse Sink Connector, with effort estimates, dependencies, and implementation guidelines.

## Overview

**Total Issues:** 38  
**Total Estimated Effort:** 350-400 hours (2-3 months with 2-3 developers)  
**Critical Path:** P0 fixes → P1 fixes → P2 fixes

---

## Priority Levels

| Priority | Description | Criteria | Timeline |
|----------|-------------|----------|----------|
| **P0** | Critical - Blocks production use | Data corruption, crashes, data loss | Week 1-2 |
| **P1** | High - Major functionality gaps | Missing features, significant bugs | Week 3-6 |
| **P2** | Medium - Quality improvements | Performance, usability, edge cases | Week 7-10 |
| **P3** | Low - Nice to have | Minor improvements, optimizations | Week 11+ |

---

## P0: Critical Fixes (Week 1-2)

**Goal:** Make connector safe for limited production use  
**Effort:** 100 hours (2 weeks with 2-3 developers)  
**Success Criteria:** No data corruption, no crashes on valid data

### P0-1: Fix HashMap Race Conditions

**Issue:** [CONC-1](./CONCURRENCY-BUGS.md#bug-conc-1), [CONC-6](./CONCURRENCY-BUGS.md#bug-conc-6), [CONC-7](./CONCURRENCY-BUGS.md#bug-conc-7)  
**Severity:** CRITICAL  
**Effort:** 8 hours  
**Dependencies:** None

**Files to Modify:**
- `ClickHouseBatchRunnable.java:61-72`
- `ClickHouseSinkTask.java:89`

**Implementation:**
```java
// Replace all HashMap with ConcurrentHashMap
private Map<String, Connection> databaseToConnectionMap = new ConcurrentHashMap<>();
private Map<String, String> databaseOverrideMap = new ConcurrentHashMap<>();
private Map<String, PreparedStatement> queryToPs = new ConcurrentHashMap<>();
private Map<String, DbWriter> topicToDbWriterMap = new ConcurrentHashMap<>();
```

**Testing:**
- Multi-threaded integration test with `thread.pool.size=4`
- Concurrent inserts to multiple databases
- Stress test with 10,000+ operations
- Verify no ConcurrentModificationException

**Validation:**
```bash
# Run for 1 hour with high concurrency
thread.pool.size=8
tasks.max=16
# Monitor for exceptions
```

---

### P0-2: Fix NULL Handling Crash

**Issue:** [DATA-1](./DATA-TYPE-BUGS.md#bug-data-1)  
**Severity:** CRITICAL  
**Effort:** 8 hours  
**Dependencies:** None

**Files to Modify:**
- `ClickHouseConverter.java:180-200`

**Implementation:**
```java
public Object convert(Object value, Schema schema, ClickHouseColumn targetColumn) {
    if (value == null) {
        if (!targetColumn.isNullable()) {
            String behavior = config.getNullHandling();
            
            switch (behavior) {
                case "FAIL":
                    throw new DataException(String.format(
                        "Cannot insert NULL into non-nullable column '%s'",
                        targetColumn.getName()
                    ));
                case "DEFAULT":
                    return getTypeDefault(targetColumn.getType());
                case "SKIP_RECORD":
                    return SKIP_RECORD_MARKER;
                default:
                    throw new ConfigException("Invalid null.handling: " + behavior);
            }
        }
        return null;
    }
    
    return convertValue(value, targetColumn.getType());
}
```

**Configuration:**
```properties
clickhouse.null.handling=FAIL  # FAIL | DEFAULT | SKIP_RECORD
clickhouse.defaults.email=""
clickhouse.defaults.age=0
```

**Testing:**
- Test NULL in non-nullable column (should throw clear error)
- Test NULL in nullable column (should work)
- Test default value substitution
- Test record skipping

---

### P0-3: Fix Unmapped Types Silent Failure

**Issue:** [DATA-2](./DATA-TYPE-BUGS.md#bug-data-2)  
**Severity:** CRITICAL  
**Effort:** 6 hours  
**Dependencies:** None

**Files to Modify:**
- `ClickHouseDataTypeMapper.java:45-80`

**Implementation:**
```java
public ClickHouseDataType mapType(String mysqlType, String columnName) {
    String normalizedType = mysqlType.toUpperCase();
    
    // Known unsupported types
    if (UNSUPPORTED_TYPES.contains(normalizedType)) {
        throw new DataException(String.format(
            "MySQL type '%s' for column '%s' is not supported. " +
            "See documentation for supported types.",
            mysqlType, columnName
        ));
    }
    
    ClickHouseDataType mapped = tryMap(normalizedType);
    if (mapped != null) {
        return mapped;
    }
    
    // Unknown type
    String behavior = config.getUnmappedTypeBehavior();
    switch (behavior) {
        case "FAIL":
            throw new DataException("Unknown type: " + mysqlType);
        case "WARN":
            log.error("UNMAPPED TYPE: '{}' for column '{}' - defaulting to String", 
                mysqlType, columnName);
            metrics.incrementUnmappedTypeCount();
            return ClickHouseDataType.String;
        default:
            throw new ConfigException("Invalid unmapped.type.behavior");
    }
}
```

**Testing:**
- Test all supported types
- Test unsupported type (should fail with clear message)
- Test unknown type behavior
- Verify metrics incremented

---

### P0-4: Fix Resource Leak on Exception

**Issue:** [CONC-4](./CONCURRENCY-BUGS.md#bug-conc-4)  
**Severity:** CRITICAL  
**Effort:** 12 hours  
**Dependencies:** None

**Files to Modify:**
- `ClickHouseBatchRunnable.java:200-250`

**Implementation:**
```java
public void run() {
    Connection conn = null;
    try {
        conn = getOrCreateConnection(database);
        
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            for (Record record : records) {
                setParameters(ps, record);
                ps.addBatch();
            }
            
            ps.executeBatch();
            conn.commit();
            
        } catch (SQLException e) {
            rollbackSafely(conn);
            closeAndRemoveConnection(database, conn);
            conn = null;  // Don't close again in finally
            throw new BatchFailedException("Batch execution failed", e);
        }
        
    } catch (Exception e) {
        log.error("Fatal error in batch processing", e);
        throw new RuntimeException(e);
    } finally {
        // Connection kept in pool for reuse unless error occurred
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
```

**Testing:**
- Simulate exceptions during batch processing
- Monitor connection pool metrics
- Verify no connection leaks after 1000+ exceptions
- Memory profiling for PreparedStatement leaks

---

### P0-5: Fix Unsynchronized DDL Cache

**Issue:** [CONC-2](./CONCURRENCY-BUGS.md#bug-conc-2)  
**Severity:** CRITICAL  
**Effort:** 6 hours  
**Dependencies:** None

**Files to Modify:**
- `DBMetadata.java`

**Implementation:**
```java
public class DBMetadata {
    // Thread-safe cache
    private static final Map<String, Map<String, ClickHouseColumn>> tableToSchemaMap = 
        new ConcurrentHashMap<>();
    
    public static void addTableSchema(String tableName, Map<String, ClickHouseColumn> schema) {
        // Store defensive copy with thread-safe map
        tableToSchemaMap.put(tableName, new ConcurrentHashMap<>(schema));
    }
    
    public static Map<String, ClickHouseColumn> getTableSchema(String tableName) {
        return tableToSchemaMap.get(tableName);
    }
    
    public static void updateColumn(String tableName, String columnName, ClickHouseColumn column) {
        Map<String, ClickHouseColumn> schema = tableToSchemaMap.get(tableName);
        if (schema != null) {
            schema.put(columnName, column);
        }
    }
    
    public static void removeColumn(String tableName, String columnName) {
        Map<String, ClickHouseColumn> schema = tableToSchemaMap.get(tableName);
        if (schema != null) {
            schema.remove(columnName);
        }
    }
}
```

**Testing:**
- Concurrent schema reads during DDL operations
- Multiple threads updating different tables
- Stress test with 100+ concurrent operations

---

### P0-6: Fix Batch Partial Commit

**Issue:** [TX-3](./TRANSACTION-BUGS.md#bug-tx-3)  
**Severity:** CRITICAL  
**Effort:** 12 hours  
**Dependencies:** None

**Files to Modify:**
- `PreparedStatementExecutor.java:80-120`

**Implementation:**
```java
public void executeBatch(List<Record> records) {
    Connection conn = null;
    PreparedStatement ps = null;
    
    try {
        conn = getConnection();
        conn.setAutoCommit(false);
        
        ps = conn.prepareStatement(insertQuery);
        
        // Add all to batch
        for (Record record : records) {
            setParameters(ps, record);
            ps.addBatch();
        }
        
        // Execute atomically
        int[] results = ps.executeBatch();
        
        // Verify all succeeded
        for (int i = 0; i < results.length; i++) {
            if (results[i] == Statement.EXECUTE_FAILED) {
                throw new BatchExecutionException(
                    "Record " + i + " failed in batch");
            }
        }
        
        // All succeeded - commit
        conn.commit();
        log.info("Batch of {} records committed", records.size());
        
    } catch (BatchUpdateException e) {
        // Partial execution - rollback everything
        int[] updateCounts = e.getUpdateCounts();
        int successful = countSuccessful(updateCounts);
        
        log.error("Batch partial execution: {}/{} succeeded, rolling back all",
            successful, records.size());
        
        rollbackSafely(conn);
        
        throw new BatchFailedException(
            String.format("Batch failed at record %d/%d", 
                successful, records.size()), e);
        
    } catch (SQLException e) {
        log.error("Batch execution failed", e);
        rollbackSafely(conn);
        throw new BatchFailedException("Batch failed", e);
        
    } finally {
        closeQuietly(ps);
        closeQuietly(conn);
    }
}
```

**Testing:**
- Test batch with invalid record in middle
- Verify rollback on failure
- Verify no partial commits
- Test retry behavior

---

### P0-7: Fix Reserved Keywords Not Escaped

**Issue:** [SCHEMA-5](./SCHEMA-EVOLUTION-BUGS.md#bug-schema-5)  
**Severity:** HIGH (Upgraded to P0 due to SQL errors)  
**Effort:** 8 hours  
**Dependencies:** None

**Files to Modify:**
- `ClickHouseQueryBuilder.java:50-80`

**Implementation:**
```java
public class ClickHouseQueryBuilder {
    private static final Set<String> RESERVED_KEYWORDS = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "ALTER", "CREATE", "DROP",
        "TABLE", "DATABASE", "FROM", "WHERE", "JOIN", "ORDER", "GROUP",
        "TIMESTAMP", "USER", "DATE", "KEY", "INDEX", "VALUES", "DEFAULT"
        // ... complete list from ClickHouse docs
    );
    
    public String buildInsertQuery(String tableName, List<String> columns) {
        String escapedTable = escapeIdentifier(tableName);
        
        String columnList = columns.stream()
            .map(this::escapeIdentifier)
            .collect(Collectors.joining(", "));
        
        String placeholders = String.join(", ", 
            Collections.nCopies(columns.size(), "?"));
        
        return String.format("INSERT INTO %s (%s) VALUES (%s)",
            escapedTable, columnList, placeholders);
    }
    
    private String escapeIdentifier(String identifier) {
        // Always escape reserved keywords
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

**Testing:**
- Test all common reserved keywords
- Test special characters in names
- Verify generated SQL syntax
- Integration test with ClickHouse

---

## P0 Summary

| Fix | Effort | Dependencies | Risk |
|-----|--------|--------------|------|
| P0-1: HashMap races | 8h | None | Low |
| P0-2: NULL handling | 8h | None | Low |
| P0-3: Unmapped types | 6h | None | Low |
| P0-4: Resource leaks | 12h | None | Medium |
| P0-5: DDL cache sync | 6h | None | Low |
| P0-6: Partial commits | 12h | None | Medium |
| P0-7: Keyword escaping | 8h | None | Low |
| **TOTAL** | **60h** | | |

**After P0 Completion:**
- Production Readiness Score: **6.0/10** (up from 3.6)
- Safe for: Single-threaded, simple schemas, INSERT-only
- Blocks: Multi-threading, complex schemas, schema evolution

---

## P1: High Priority Fixes (Week 3-6)

**Goal:** Enable full production deployment  
**Effort:** 140 hours (4 weeks with 2-3 developers)  
**Success Criteria:** Full DDL/DML support, transaction handling

### P1-1: Implement Transaction Support

**Issue:** [TX-1](./TRANSACTION-BUGS.md#bug-tx-1), [TX-2](./TRANSACTION-BUGS.md#bug-tx-2)  
**Severity:** CRITICAL  
**Effort:** 40 hours  
**Dependencies:** P0-6

**Implementation:**
```java
public class ClickHouseTransactionManager {
    private final Map<String, TransactionContext> activeTransactions = 
        new ConcurrentHashMap<>();
    
    public void handleBeginTransaction(String txnId, String gtid) {
        TransactionContext ctx = new TransactionContext(txnId, gtid);
        activeTransactions.put(txnId, ctx);
    }
    
    public void handleOperation(String txnId, Operation op) {
        TransactionContext ctx = activeTransactions.get(txnId);
        if (ctx == null) {
            processImmediate(op);
        } else {
            ctx.addOperation(op);
        }
    }
    
    public void handleCommit(String txnId) {
        TransactionContext ctx = activeTransactions.remove(txnId);
        if (ctx == null) return;
        
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            for (Operation op : ctx.getOperations()) {
                executeOperation(conn, op);
            }
            
            conn.commit();
        } catch (Exception e) {
            rollbackSafely(conn);
            throw new TransactionException("Transaction failed", e);
        }
    }
    
    public void handleRollback(String txnId) {
        TransactionContext ctx = activeTransactions.remove(txnId);
        if (ctx != null) {
            log.info("Rolled back transaction {} ({} ops discarded)",
                txnId, ctx.getOperations().size());
        }
    }
}
```

**Configuration:**
```properties
clickhouse.preserve.transactions=true
clickhouse.transaction.timeout=30000
clickhouse.transaction.max.operations=10000
```

**Testing:**
- Multi-statement transaction test
- ROLLBACK detection test
- Large transaction handling
- Concurrent transactions

**Effort Breakdown:**
- Transaction buffering: 16h
- Commit/rollback handling: 12h
- Integration with Debezium events: 8h
- Testing: 4h

---

### P1-2: Fix Schema Evolution Issues

**Issue:** [SCHEMA-1](./SCHEMA-EVOLUTION-BUGS.md#bug-schema-1), [SCHEMA-2](./SCHEMA-EVOLUTION-BUGS.md#bug-schema-2), [SCHEMA-3](./SCHEMA-EVOLUTION-BUGS.md#bug-schema-3)  
**Severity:** HIGH  
**Effort:** 44 hours  
**Dependencies:** P0-5

**Sub-tasks:**

**1. Wire up DROP COLUMN (16h)**
```java
public void onSchemaChange(SchemaChangeEvent event) {
    switch (event.getType()) {
        case DROP_COLUMN:
            String behavior = config.getDropColumnBehavior();
            switch (behavior) {
                case "DROP":
                    executeDropColumn(event.getTableName(), event.getColumnName());
                    break;
                case "RENAME":
                    renameColumnToDeleted(event.getTableName(), event.getColumnName());
                    break;
                case "IGNORE":
                    log.warn("DROP COLUMN ignored for {}", event.getColumnName());
                    break;
            }
            break;
    }
}
```

**2. Implement RENAME detection (12h)**
```java
// Detect DROP+ADD as RENAME
private Map<String, DropColumnEvent> recentDrops = new ConcurrentHashMap<>();

public void handleColumnChange(SchemaChangeEvent event) {
    if (event.getType() == ADD_COLUMN) {
        Optional<DropColumnEvent> recentDrop = findRecentDrop(
            event.getTableName(), 
            event.getColumnDefinition()
        );
        
        if (recentDrop.isPresent()) {
            // This is a RENAME
            handleColumnRename(
                event.getTableName(),
                recentDrop.get().getColumnName(),
                event.getColumnName()
            );
        }
    }
}
```

**3. Type change validation (16h)**
```java
public void handleModifyColumn(SchemaChangeEvent event) {
    ColumnDefinition oldDef = getCurrentDefinition(event.getTableName(), event.getColumnName());
    ColumnDefinition newDef = event.getColumnDefinition();
    
    if (!isSafeTypeChange(oldDef.getType(), newDef.getType())) {
        if (config.isStrictTypeChange()) {
            throw new SchemaException("Unsafe type change");
        }
        log.error("UNSAFE type change: {} → {}", oldDef.getType(), newDef.getType());
    }
    
    executeTypeChange(event.getTableName(), event.getColumnName(), newDef);
}
```

---

### P1-3: Fix Data Type Handling

**Issue:** [DATA-3](./DATA-TYPE-BUGS.md#bug-data-3) through [DATA-8](./DATA-TYPE-BUGS.md#bug-data-8)  
**Severity:** HIGH  
**Effort:** 36 hours  
**Dependencies:** P0-2, P0-3

**Sub-tasks:**
- ENUM/SET support: 12h
- Date range validation: 6h
- Zero date handling: 4h
- Binary encoding: 8h
- Decimal precision: 4h
- UTF-8 validation: 2h

---

### P1-4: Implement Concurrency Fixes

**Issue:** [CONC-3](./CONCURRENCY-BUGS.md#bug-conc-3), [CONC-5](./CONCURRENCY-BUGS.md#bug-conc-5)  
**Severity:** HIGH  
**Effort:** 10 hours  
**Dependencies:** P0-1

**Implementation:**
```java
// Fix AtomicBoolean misuse
public void addToBatch(Record record) {
    if (isProcessing.compareAndSet(false, true)) {
        try {
            processBatch();
        } finally {
            isProcessing.set(false);
        }
    } else {
        queueForNextBatch(record);
    }
}

// Fix check-then-act race
private final Map<String, Lock> tableToLock = new ConcurrentHashMap<>();

public void write(String table, List<Record> records) {
    Lock lock = tableToLock.computeIfAbsent(table, k -> new ReentrantLock());
    
    if (lock.tryLock()) {
        try {
            flushIfNeeded(table, records);
        } finally {
            lock.unlock();
        }
    }
}
```

---

### P1-5: Add Comprehensive Validation

**Issue:** Multiple anti-patterns  
**Severity:** MEDIUM (Upgraded to P1)  
**Effort:** 10 hours  
**Dependencies:** None

**Implementation:**
- Input validation for all public methods
- Parameter null checks
- Range validation for numerics
- Length validation for strings

---

## P1 Summary

| Fix | Effort | Dependencies |
|-----|--------|--------------|
| P1-1: Transactions | 40h | P0-6 |
| P1-2: Schema evolution | 44h | P0-5 |
| P1-3: Data types | 36h | P0-2, P0-3 |
| P1-4: Concurrency | 10h | P0-1 |
| P1-5: Validation | 10h | None |
| **TOTAL** | **140h** | |

**After P1 Completion:**
- Production Readiness Score: **7.5/10**
- Safe for: Most production workloads
- Remaining gaps: Performance optimization, monitoring

---

## P2: Medium Priority Fixes (Week 7-10)

**Goal:** Production hardening and optimization  
**Effort:** 100 hours  
**Success Criteria:** Comprehensive monitoring, performance tuning

### P2-1: Refactor God Object

**Issue:** [AP-HIGH-1](./ANTI-PATTERNS.md#ap-high-1)  
**Effort:** 40 hours  
**Dependencies:** All P0, P1 fixes

**Implementation:** Break into focused classes:
- ConnectionManager
- QueryBuilder
- BatchExecutor
- ErrorHandler
- MetricsCollector

---

### P2-2: Remove Static State

**Issue:** [AP-HIGH-2](./ANTI-PATTERNS.md#ap-high-2)  
**Effort:** 16 hours  
**Dependencies:** None

---

### P2-3: Improve Error Handling

**Issue:** [AP-HIGH-3](./ANTI-PATTERNS.md#ap-high-3), [AP-HIGH-4](./ANTI-PATTERNS.md#ap-high-4)  
**Effort:** 12 hours  
**Dependencies:** None

---

### P2-4: Add Value Objects

**Issue:** [AP-HIGH-6](./ANTI-PATTERNS.md#ap-high-6)  
**Effort:** 20 hours  
**Dependencies:** None

---

### P2-5: Performance Optimization

**Effort:** 12 hours  
- Batch size tuning
- Connection pooling optimization
- Query optimization
- Memory profiling

---

## P2 Summary

| Fix | Effort |
|-----|--------|
| P2-1: Refactor god object | 40h |
| P2-2: Remove static state | 16h |
| P2-3: Error handling | 12h |
| P2-4: Value objects | 20h |
| P2-5: Performance | 12h |
| **TOTAL** | **100h** |

**After P2 Completion:**
- Production Readiness Score: **8.5/10**
- Enterprise-grade quality

---

## Implementation Timeline

### Week 1-2: P0 Fixes
- **Team:** 2-3 developers
- **Focus:** Critical bug fixes
- **Deliverable:** No crashes, no data corruption

| Week | Tasks | Hours |
|------|-------|-------|
| Week 1 | P0-1, P0-2, P0-3 | 22h |
| Week 2 | P0-4, P0-5, P0-6, P0-7 | 38h |

### Week 3-6: P1 Fixes
- **Team:** 2-3 developers
- **Focus:** Feature completion
- **Deliverable:** Full DDL/DML/transaction support

| Week | Tasks | Hours |
|------|-------|-------|
| Week 3 | P1-1 (Transactions) | 40h |
| Week 4-5 | P1-2 (Schema), P1-3 (Types) | 80h |
| Week 6 | P1-4, P1-5, testing | 20h |

### Week 7-10: P2 Fixes
- **Team:** 2 developers
- **Focus:** Quality and performance
- **Deliverable:** Production-ready

| Week | Tasks | Hours |
|------|-------|-------|
| Week 7-8 | P2-1 (Refactoring) | 40h |
| Week 9 | P2-2, P2-3, P2-4 | 48h |
| Week 10 | P2-5, final testing | 12h |

---

## Testing Strategy

### Unit Tests (Continuous)
- Test each fix in isolation
- Aim for 80%+ code coverage
- Mock external dependencies

### Integration Tests (Per Phase)
- P0: Basic integration tests
- P1: Full DDL/DML/transaction tests
- P2: Performance and stress tests

### Regression Tests (Weekly)
- Run full test suite weekly
- Ensure no regressions from new fixes

### Production Validation (End of Each Phase)
- Deploy to staging environment
- Run production-like workload
- Monitor for 24+ hours

---

## Success Metrics

### P0 Success Criteria
- [ ] Zero ConcurrentModificationExceptions in 24h stress test
- [ ] Zero connection leaks after 10,000 exceptions
- [ ] Zero NULL crashes with proper error messages
- [ ] Zero silent type conversion failures

### P1 Success Criteria
- [ ] All DDL operations supported (15/15)
- [ ] All DML operations supported (7/7)
- [ ] Transaction boundaries preserved
- [ ] ROLLBACK properly handled
- [ ] Schema changes detected and applied

### P2 Success Criteria
- [ ] Code coverage > 80%
- [ ] Zero god objects (classes > 500 lines)
- [ ] Zero static mutable state
- [ ] Performance: 10,000+ records/sec throughput
- [ ] Memory: < 2GB heap for 1M records

---

## Risk Mitigation

### High-Risk Changes
1. **Transaction refactoring (P1-1):** Large change, potential for bugs
   - **Mitigation:** Extensive testing, feature flag
   
2. **God object refactoring (P2-1):** Major architectural change
   - **Mitigation:** Incremental refactoring, maintain backward compatibility

3. **Connection pool changes (P0-4):** Could cause new leaks
   - **Mitigation:** Memory profiling, long-running tests

### Rollback Plan
- Maintain feature flags for major changes
- Version releases incrementally (P0 → P1 → P2)
- Keep ability to revert to previous behavior

---

## Configuration Management

### New Configuration Options

```properties
# P0 Fixes
clickhouse.null.handling=FAIL
clickhouse.unmapped.type.behavior=FAIL
clickhouse.always.escape.identifiers=true

# P1 Fixes
clickhouse.preserve.transactions=true
clickhouse.transaction.timeout=30000
clickhouse.drop.column.behavior=RENAME
clickhouse.column.rename.behavior=RENAME
clickhouse.type.change.behavior=FAIL
clickhouse.zero.date.behavior=EPOCH
clickhouse.date.range.overflow=FAIL

# P2 Fixes
clickhouse.batch.size=1000
clickhouse.connection.pool.size=20
clickhouse.metrics.enabled=true
```

---

## Deliverables

### End of P0
- [x] All 7 critical bugs fixed
- [x] Integration test suite (basic)
- [x] Migration guide for existing users
- [x] Release notes

### End of P1
- [x] Full DDL/DML support
- [x] Transaction support
- [x] Comprehensive test suite
- [x] Updated documentation
- [x] Performance baseline

### End of P2
- [x] Refactored codebase
- [x] Performance optimized
- [x] Production deployment guide
- [x] Monitoring and alerting setup
- [x] Final release

---

**Related Documents:**
- [Concurrency Bugs](./CONCURRENCY-BUGS.md) - Thread safety details
- [Data Type Bugs](./DATA-TYPE-BUGS.md) - Type conversion details
- [Schema Evolution Bugs](./SCHEMA-EVOLUTION-BUGS.md) - DDL details
- [Transaction Bugs](./TRANSACTION-BUGS.md) - Transaction details
- [Production Readiness](./PRODUCTION-READINESS.md) - Deployment guide
