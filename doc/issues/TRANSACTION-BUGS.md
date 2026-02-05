# Transaction Bugs

This document details all transaction handling issues that can cause data inconsistency, partial commits, and atomicity violations.

## Overview

**Total Bugs:** 3  
**Severity:** CRITICAL (3)  
**Affected Component:** Transaction management, batch processing  
**Production Impact:** Data inconsistency, partial commits, lost data integrity

## Summary Table

| ID | Issue | Severity | Impact | Root Cause |
|----|-------|----------|--------|------------|
| BUG-TX-1 | No Transaction Atomicity | CRITICAL | Multi-row transactions split | No transaction tracking |
| BUG-TX-2 | ROLLBACK Not Handled | CRITICAL | Failed batches not rolled back | Missing rollback logic |
| BUG-TX-3 | Batch Partial Commit | CRITICAL | Partial data on failure | No all-or-nothing guarantee |

---

## BUG-TX-1: No Transaction Atomicity Guarantee

**Severity:** CRITICAL  
**Location:** [`ClickHouseBatchRunnable.java:200-300`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java)

### Current Code

```java
public class ClickHouseBatchRunnable implements Runnable {
    public void run() {
        Connection conn = getConnection();
        
        // Process records in batches
        for (Map.Entry<String, Buffer> entry : queryToRecordsMap.entrySet()) {
            String query = entry.getKey();
            Buffer buffer = entry.getValue();
            
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                for (Record record : buffer.getRecords()) {
                    setParameters(ps, record);
                    ps.addBatch();
                }
                
                ps.executeBatch();  // Each query executed separately!
                conn.commit();       // Commit per query, not per transaction
                
            } catch (SQLException e) {
                log.error("Error executing batch", e);
                // No rollback, no transaction boundary preservation
            }
        }
    }
}
```

### Problem

MySQL transactions that span multiple statements are not preserved as atomic units in ClickHouse.

**MySQL Transaction (Atomic):**
```sql
START TRANSACTION;
  INSERT INTO accounts (id, balance) VALUES (1, 1000);
  UPDATE accounts SET balance = balance - 100 WHERE id = 1;
  INSERT INTO transactions (from_id, amount) VALUES (1, 100);
COMMIT;
```

**ClickHouse Processing (Non-Atomic):**
```
Batch 1: INSERT INTO accounts ... [COMMIT]
Batch 2: UPDATE accounts ...      [COMMIT]  -- May fail here!
Batch 3: INSERT INTO transactions ... [NOT EXECUTED]

Result: Partial transaction committed!
  ✓ Account created
  ✗ Balance not updated (failed)
  ✗ Transaction record not created
```

### Impact

**Scenario 1: Banking Transaction**
```sql
-- MySQL (atomic)
BEGIN;
  UPDATE accounts SET balance = balance - 100 WHERE id = 1;  -- Alice
  UPDATE accounts SET balance = balance + 100 WHERE id = 2;  -- Bob
COMMIT;

-- ClickHouse (non-atomic)
UPDATE accounts SET balance = 900 WHERE id = 1;  ✓ Committed
UPDATE accounts SET balance = 1100 WHERE id = 2; ✗ Failed (network error)

-- RESULT: $100 disappeared! Alice -100, Bob +0
```

**Scenario 2: Order Processing**
```sql
-- MySQL (atomic)
BEGIN;
  INSERT INTO orders (id, user_id, total) VALUES (1, 100, 250.00);
  INSERT INTO order_items (order_id, product_id, qty) VALUES (1, 5, 2);
  UPDATE inventory SET qty = qty - 2 WHERE product_id = 5;
COMMIT;

-- ClickHouse (non-atomic)
INSERT INTO orders ...     ✓ Committed
INSERT INTO order_items... ✗ Failed (type error)
UPDATE inventory ...       ✗ Not executed

-- RESULT: Order exists but has no items, inventory unchanged
```

### Root Cause Analysis

1. **No Transaction Boundary Detection:**
   - Debezium marks BEGIN/COMMIT events, but connector ignores them
   - Each batch treated independently

2. **Immediate Commits:**
   - Each query batch is committed immediately
   - No buffering of related operations

3. **No Txn Context Tracking:**
   - No correlation between related operations
   - Transaction ID from MySQL not preserved

### Proposed Fix

**Option 1: Transaction Buffering (Recommended)**

```java
public class ClickHouseTransactionManager {
    // Track active transactions
    private Map<String, TransactionContext> activeTransactions = new ConcurrentHashMap<>();
    
    public void handleBeginTransaction(String transactionId, String gtid) {
        log.info("Starting transaction: {}", transactionId);
        
        TransactionContext ctx = new TransactionContext(transactionId, gtid);
        activeTransactions.put(transactionId, ctx);
    }
    
    public void handleOperation(String transactionId, Operation operation) {
        TransactionContext ctx = activeTransactions.get(transactionId);
        
        if (ctx == null) {
            // No active transaction - process immediately
            processImmediate(operation);
        } else {
            // Buffer operation in transaction
            ctx.addOperation(operation);
            log.debug("Buffered operation in txn {}: {}", transactionId, operation);
        }
    }
    
    public void handleCommit(String transactionId) {
        TransactionContext ctx = activeTransactions.remove(transactionId);
        
        if (ctx == null) {
            log.warn("Commit for unknown transaction: {}", transactionId);
            return;
        }
        
        log.info("Committing transaction {} with {} operations", 
            transactionId, ctx.getOperations().size());
        
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            
            // Execute all operations atomically
            for (Operation op : ctx.getOperations()) {
                executeOperation(conn, op);
            }
            
            // All succeeded - commit
            conn.commit();
            log.info("Successfully committed transaction {}", transactionId);
            
            metrics.recordSuccessfulTransaction(transactionId, ctx.getOperations().size());
            
        } catch (Exception e) {
            log.error("Transaction {} failed, rolling back", transactionId, e);
            
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException re) {
                log.error("Rollback failed for transaction {}", transactionId, re);
            }
            
            metrics.recordFailedTransaction(transactionId);
            
            // Rethrow to trigger retry
            throw new TransactionException("Transaction failed: " + transactionId, e);
            
        } finally {
            closeConnection(conn);
        }
    }
    
    public void handleRollback(String transactionId) {
        TransactionContext ctx = activeTransactions.remove(transactionId);
        
        if (ctx != null) {
            log.info("Rolling back transaction {} ({} operations discarded)", 
                transactionId, ctx.getOperations().size());
            metrics.recordRolledBackTransaction(transactionId);
        }
    }
}

class TransactionContext {
    private final String transactionId;
    private final String gtid;
    private final List<Operation> operations = new ArrayList<>();
    private final long startTime = System.currentTimeMillis();
    
    public TransactionContext(String transactionId, String gtid) {
        this.transactionId = transactionId;
        this.gtid = gtid;
    }
    
    public void addOperation(Operation op) {
        operations.add(op);
    }
    
    public List<Operation> getOperations() {
        return operations;
    }
    
    public long getAge() {
        return System.currentTimeMillis() - startTime;
    }
}
```

**Integration with Kafka Records:**
```java
public void put(Collection<SinkRecord> records) {
    for (SinkRecord record : records) {
        Struct value = (Struct) record.value();
        Struct source = value.getStruct("source");
        
        // Extract transaction metadata
        String transactionId = source.getString("txId");
        String gtid = source.getString("gtid");
        
        // Check for transaction boundaries
        if (isBeginTransaction(record)) {
            txnManager.handleBeginTransaction(transactionId, gtid);
            continue;
        }
        
        if (isCommitTransaction(record)) {
            txnManager.handleCommit(transactionId);
            continue;
        }
        
        if (isRollbackTransaction(record)) {
            txnManager.handleRollback(transactionId);
            continue;
        }
        
        // Regular operation
        Operation op = convertToOperation(record);
        txnManager.handleOperation(transactionId, op);
    }
}
```

**Option 2: Best-Effort Batching**
```java
public class ClickHouseBatchProcessor {
    private Map<String, List<Operation>> transactionBuffers = new ConcurrentHashMap<>();
    
    public void process(SinkRecord record) {
        String txnId = extractTransactionId(record);
        
        if (txnId != null) {
            // Part of a transaction - buffer it
            transactionBuffers.computeIfAbsent(txnId, k -> new ArrayList<>())
                .add(convertToOperation(record));
        } else {
            // No transaction context - process immediately
            processImmediate(record);
        }
    }
    
    public void flushTransaction(String txnId) {
        List<Operation> ops = transactionBuffers.remove(txnId);
        
        if (ops == null || ops.isEmpty()) {
            return;
        }
        
        // Execute all operations in single transaction
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            for (Operation op : ops) {
                executeOperation(conn, op);
            }
            
            conn.commit();
        } catch (SQLException e) {
            // Rollback handled by connection close
            throw new RuntimeException("Transaction failed", e);
        }
    }
}
```

### Configuration

```properties
# Enable transaction preservation
clickhouse.preserve.transactions=true

# Transaction buffer timeout (milliseconds)
clickhouse.transaction.timeout=30000

# Max operations per transaction before forced commit
clickhouse.transaction.max.operations=10000

# What to do with large transactions
clickhouse.large.transaction.behavior=SPLIT  # SPLIT | FAIL | WARN

# Enable transaction metrics
clickhouse.transaction.metrics.enabled=true
```

### Testing Requirements

1. Multi-statement transaction test
2. Transaction rollback test
3. Large transaction handling
4. Transaction timeout test
5. Concurrent transaction test
6. Transaction failure recovery test

---

## BUG-TX-2: ROLLBACK Not Handled

**Severity:** CRITICAL  
**Location:** [`ClickHouseSinkTask.java:150-180`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkTask.java)

### Problem

MySQL ROLLBACK events are ignored. Data that was rolled back in MySQL still gets written to ClickHouse.

**MySQL:**
```sql
START TRANSACTION;
  INSERT INTO users (id, email) VALUES (999, 'test@example.com');
  -- Application detects duplicate email
ROLLBACK;  -- Transaction aborted

-- Result in MySQL: No data inserted (correct)
```

**ClickHouse:**
```
INSERT INTO users (id, email) VALUES (999, 'test@example.com');  [COMMITTED]

-- Result in ClickHouse: Data inserted! (WRONG)
```

### Impact

**Scenario 1: Application-Level Validation**
```sql
-- E-commerce checkout
BEGIN;
  INSERT INTO orders (id, user_id, total) VALUES (1, 100, 250.00);
  -- Check inventory
  -- IF inventory insufficient:
ROLLBACK;

-- MySQL: No order created ✓
-- ClickHouse: Order created! ✗
-- Analytics shows phantom orders
```

**Scenario 2: Error Recovery**
```sql
BEGIN;
  UPDATE accounts SET balance = balance - 1000 WHERE id = 1;
  UPDATE accounts SET balance = balance + 1000 WHERE id = 2;
  -- Second update fails (account doesn't exist)
ROLLBACK;

-- MySQL: No changes ✓
-- ClickHouse: First update committed! ✗
-- Data inconsistency
```

### Current Code

```java
public void put(Collection<SinkRecord> records) {
    for (SinkRecord record : records) {
        // Only handles data operations
        if (record.value() == null) {
            // Assume it's a tombstone, skip
            continue;
        }
        
        // Process INSERT/UPDATE/DELETE
        processDataChange(record);
        
        // ROLLBACK events ignored!
    }
}
```

### Proposed Fix

```java
public void put(Collection<SinkRecord> records) {
    for (SinkRecord record : records) {
        Struct value = (Struct) record.value();
        
        if (value == null) {
            continue;
        }
        
        // Check for transaction control events
        String op = value.getString("op");
        
        switch (op) {
            case "r":  // Read (snapshot)
            case "c":  // Create (INSERT)
            case "u":  // Update
            case "d":  // Delete
                String txnId = extractTransactionId(value);
                Operation operation = convertToOperation(record);
                txnManager.handleOperation(txnId, operation);
                break;
                
            case "m":  // Message (transaction boundary)
                handleTransactionMessage(value);
                break;
                
            default:
                log.warn("Unknown operation type: {}", op);
        }
    }
}

private void handleTransactionMessage(Struct value) {
    String status = value.getString("status");
    String txnId = value.getString("id");
    
    switch (status) {
        case "BEGIN":
            txnManager.handleBeginTransaction(txnId);
            log.debug("Transaction started: {}", txnId);
            break;
            
        case "COMMIT":
            txnManager.handleCommit(txnId);
            log.info("Transaction committed: {}", txnId);
            break;
            
        case "ROLLBACK":
            txnManager.handleRollback(txnId);
            log.info("Transaction rolled back, discarding {} operations", txnId);
            metrics.incrementRollbackCount();
            break;
            
        default:
            log.warn("Unknown transaction status: {}", status);
    }
}
```

### Debezium Configuration

Ensure Debezium emits transaction metadata:

```properties
# Emit transaction boundary events
event.processing.failure.handling.mode=warn
provide.transaction.metadata=true
```

### Testing Requirements

1. Test ROLLBACK detection
2. Verify rolled-back data NOT in ClickHouse
3. Test partial transaction rollback
4. Test concurrent transactions with rollbacks
5. Verify metrics for rollback count

---

## BUG-TX-3: Batch Partial Commit on Failure

**Severity:** CRITICAL  
**Location:** [`PreparedStatementExecutor.java:80-120`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/PreparedStatementExecutor.java)

### Current Code

```java
public void executeBatch(List<Record> records) {
    Connection conn = getConnection();
    
    try (PreparedStatement ps = conn.prepareStatement(insertQuery)) {
        for (Record record : records) {
            setParameters(ps, record);
            ps.addBatch();
        }
        
        // Execute entire batch
        int[] results = ps.executeBatch();
        
        // Problem: If this fails, some rows may be committed!
        conn.commit();
        
    } catch (BatchUpdateException e) {
        // Partial execution!
        // Some rows succeeded, some failed
        int[] updateCounts = e.getUpdateCounts();
        
        log.error("Batch partially executed: {} succeeded, {} failed",
            countSuccessful(updateCounts), 
            records.size() - countSuccessful(updateCounts));
        
        // No rollback - partial data remains committed!
    }
}
```

### Problem

JDBC `executeBatch()` can partially succeed. If row 500 out of 1000 fails, rows 1-499 may already be committed.

**Batch Processing:**
```
Batch of 1000 records:
  Records 1-499:   ✓ Inserted successfully
  Record 500:      ✗ Failed (type conversion error)
  Records 501-1000: Not processed

If conn.commit() is called: Records 1-499 committed (PARTIAL!)
If exception thrown: Records 1-499 still committed in some drivers
```

### Impact

**Scenario 1: Data Quality Issue**
```sql
-- Batch of 1000 user records
Record 1-750:    Valid data, inserted ✓
Record 751:      Invalid email format, fails ✗
Records 752-1000: Not inserted

-- Result: 75% of batch committed, 25% lost
-- Offset committed → Lost records never retried
```

**Scenario 2: Duplicate Data**
```sql
-- Batch of 1000 records
Record 1-500:  Inserted successfully ✓
Record 501:    Network timeout ✗
Records 502-1000: Not attempted

-- Connector retries entire batch
-- Records 1-500 inserted AGAIN → DUPLICATES!
```

### Root Cause

1. **No Pre-Validation:** Records not validated before batch execution
2. **No Atomic Batch:** JDBC batch can partially execute
3. **Incomplete Error Handling:** Partial success not detected
4. **No Offset Management:** Can't resume from partial batch

### Proposed Fix

**Option 1: All-or-Nothing with Rollback**

```java
public void executeBatch(List<Record> records) {
    Connection conn = null;
    PreparedStatement ps = null;
    
    try {
        conn = getConnection();
        conn.setAutoCommit(false);  // Explicit transaction
        
        ps = conn.prepareStatement(insertQuery);
        
        // Add all records to batch
        for (Record record : records) {
            try {
                setParameters(ps, record);
                ps.addBatch();
            } catch (Exception e) {
                // Validation failed - fail entire batch
                log.error("Invalid record at position {}: {}", 
                    records.indexOf(record), e.getMessage());
                throw new BatchValidationException("Invalid record", e);
            }
        }
        
        // Execute batch
        int[] results = ps.executeBatch();
        
        // Verify all succeeded
        for (int i = 0; i < results.length; i++) {
            if (results[i] == Statement.EXECUTE_FAILED) {
                throw new BatchExecutionException(
                    "Record " + i + " failed to insert");
            }
        }
        
        // All succeeded - commit
        conn.commit();
        log.info("Successfully committed batch of {} records", records.size());
        
    } catch (BatchUpdateException e) {
        // Partial execution detected
        int[] updateCounts = e.getUpdateCounts();
        int successful = countSuccessful(updateCounts);
        
        log.error("Batch partial execution: {}/{} succeeded", 
            successful, records.size());
        
        // Rollback everything
        try {
            if (conn != null) {
                conn.rollback();
                log.info("Rolled back partial batch");
            }
        } catch (SQLException re) {
            log.error("Rollback failed!", re);
        }
        
        // Fail entire batch for retry
        throw new BatchFailedException(
            "Batch partially executed, rolled back", e);
        
    } catch (Exception e) {
        // Any other error
        log.error("Batch execution failed", e);
        
        try {
            if (conn != null) {
                conn.rollback();
            }
        } catch (SQLException re) {
            log.error("Rollback failed!", re);
        }
        
        throw new BatchFailedException("Batch failed", e);
        
    } finally {
        closeQuietly(ps);
        closeQuietly(conn);
    }
}
```

**Option 2: Pre-Validation + Retry**

```java
public void executeBatch(List<Record> records) {
    // Step 1: Pre-validate all records
    List<Record> validRecords = new ArrayList<>();
    List<Record> invalidRecords = new ArrayList<>();
    
    for (Record record : records) {
        try {
            validateRecord(record);
            validRecords.add(record);
        } catch (ValidationException e) {
            log.error("Invalid record: {}", e.getMessage());
            invalidRecords.add(record);
            
            if (config.isStrictValidation()) {
                throw new BatchValidationException(
                    "Batch contains invalid records", e);
            }
        }
    }
    
    if (!invalidRecords.isEmpty()) {
        log.warn("Skipping {} invalid records out of {}", 
            invalidRecords.size(), records.size());
        
        // Send to DLQ
        sendToDeadLetterQueue(invalidRecords);
    }
    
    if (validRecords.isEmpty()) {
        log.warn("No valid records in batch");
        return;
    }
    
    // Step 2: Execute valid records atomically
    executeValidatedBatch(validRecords);
}

private void executeValidatedBatch(List<Record> records) {
    try (Connection conn = getConnection()) {
        conn.setAutoCommit(false);
        
        try (PreparedStatement ps = conn.prepareStatement(insertQuery)) {
            for (Record record : records) {
                setParameters(ps, record);
                ps.addBatch();
            }
            
            int[] results = ps.executeBatch();
            
            // All pre-validated, should all succeed
            conn.commit();
            
        } catch (BatchUpdateException e) {
            conn.rollback();
            throw new BatchFailedException("Pre-validated batch failed", e);
        }
    }
}
```

**Option 3: Single-Record Fallback**

```java
public void executeBatch(List<Record> records) {
    try {
        // Try batch insert
        executeBatchOptimized(records);
        
    } catch (BatchUpdateException e) {
        log.warn("Batch execution failed, falling back to single-record inserts");
        
        // Fallback: insert one-by-one with error handling
        for (Record record : records) {
            try {
                executeSingle(record);
            } catch (SQLException se) {
                log.error("Failed to insert record: {}", record, se);
                
                if (config.skipInvalidRecords()) {
                    sendToDeadLetterQueue(record, se);
                } else {
                    throw new BatchFailedException("Record failed", se);
                }
            }
        }
    }
}
```

### Configuration

```properties
# Batch execution mode
clickhouse.batch.execution.mode=ALL_OR_NOTHING  # ALL_OR_NOTHING | BEST_EFFORT | SINGLE_RECORD

# Pre-validation
clickhouse.batch.pre.validate=true
clickhouse.strict.validation=true

# Error handling
clickhouse.skip.invalid.records=false
clickhouse.dead.letter.queue.enabled=true

# Retry on partial failure
clickhouse.batch.retry.on.partial.failure=true
```

### Testing Requirements

1. Test batch with invalid record in middle
2. Test rollback on partial failure
3. Test retry behavior
4. Verify no duplicate data
5. Test DLQ for invalid records
6. Stress test with large batches

---

## Summary of Fixes

| Bug ID | Priority | Effort | Risk |
|--------|----------|--------|------|
| TX-1 | P0 | 24h | High |
| TX-2 | P0 | 16h | Medium |
| TX-3 | P0 | 12h | Medium |
| **TOTAL** | | **52h** | |

## Recommended Implementation Order

1. **TX-3** - Immediate risk of data corruption (12h)
2. **TX-2** - Prevent phantom data (16h)
3. **TX-1** - Full transaction support (24h)

## Transaction Guarantees After Fixes

| Guarantee | Before | After |
|-----------|--------|-------|
| Atomicity | ✗ | ✓ |
| Rollback Handling | ✗ | ✓ |
| All-or-Nothing Batches | ✗ | ✓ |
| No Partial Commits | ✗ | ✓ |
| Transaction Boundaries | ✗ | ✓ |

---

**Related Documents:**
- [Concurrency Bugs](./CONCURRENCY-BUGS.md) - Thread safety for transactions
- [Fix Priority](./FIXES-PRIORITY.md) - Implementation roadmap
- [Production Readiness](./PRODUCTION-READINESS.md) - Safe deployment
