# ClickHouse Sink Connector - Configuration Reference

**Version:** 2.0.0  
**Last Updated:** 2026-02-03

---

## Table of Contents

1. [Overview](#overview)
2. [Phase 1: Concurrency Parameters](#phase-1-concurrency-parameters)
3. [Phase 2: Data Validation Parameters](#phase-2-data-validation-parameters)
4. [Phase 3: DDL Operation Parameters](#phase-3-ddl-operation-parameters)
5. [Phase 4: Transaction Parameters](#phase-4-transaction-parameters)
6. [Core Parameters](#core-parameters)
7. [Connection Parameters](#connection-parameters)
8. [Performance Parameters](#performance-parameters)
9. [Error Handling Parameters](#error-handling-parameters)
10. [Complete Configuration Examples](#complete-configuration-examples)

---

## Overview

This document provides a comprehensive reference for all configuration parameters available in ClickHouse Sink Connector v2.0.0. Parameters are organized by implementation phase and functional category.

### Parameter Format

Each parameter is documented with:
- **Name:** Parameter identifier
- **Type:** Data type (Boolean, Integer, String, Enum, etc.)
- **Default:** Default value
- **Required:** Whether parameter is mandatory
- **Phase:** When parameter was introduced
- **Description:** Detailed explanation
- **Valid Values:** Acceptable values
- **Example:** Usage example

### Configuration Priority

Parameters can be set via:
1. **Connector Configuration** (highest priority)
2. **Environment Variables** (medium priority)
3. **Default Values** (lowest priority)

---

## Phase 1: Concurrency Parameters

**Implementation Phase:** Phase 1 - Concurrency Fixes  
**Date Introduced:** 2026-02-03  
**Purpose:** Fix critical race conditions and thread safety issues

### ✅ Automatic Fixes (No Configuration Required)

Phase 1 fixes are **automatic** and require **no configuration changes**:

- ✅ BUG-CONC-1: HashMap → ConcurrentHashMap (automatic)
- ✅ BUG-CONC-2: NULL handling (automatic)
- ✅ BUG-CONC-3: Atomic buffer operations (automatic)
- ✅ BUG-CONC-4: Resource cleanup (automatic)
- ✅ BUG-CONC-5: Schema cache synchronization (automatic)
- ✅ BUG-CONC-6: Atomic batch commits (automatic)
- ✅ BUG-CONC-7: Keyword escaping (automatic)

### thread.pool.size

**Type:** Integer  
**Default:** `4`  
**Required:** No  
**Phase:** Existing (now safe to increase)  
**Valid Values:** 1-32

**Description:**  
Number of worker threads for parallel processing. In v1.x, values >1 caused race conditions. **In v2.0.0, multi-threading is now safe** due to Phase 1 fixes.

**Recommendations:**
- **Development:** `1` (simplicity)
- **Production (Small):** `4-8`
- **Production (Medium):** `8-16`
- **Production (Large):** `16-32`

**Formula:**
```
thread.pool.size = min(CPU_CORES * 2, MAX_CONCURRENT_INSERTS)
```

**Example:**
```properties
# Safe multi-threading in v2.0.0
thread.pool.size=16
```

**Performance Impact:**
- Lower values: Single-threaded, lower throughput
- Higher values: Parallel processing, higher throughput
- Too high: Diminishing returns, resource contention

---

## Phase 2: Data Validation Parameters

**Implementation Phase:** Phase 2 - Data Type Validation  
**Date Introduced:** 2026-02-03  
**Purpose:** Prevent crashes and data loss from edge cases

---

### strict.date.validation

**Type:** Boolean  
**Default:** `true`  
**Required:** No  
**Phase:** Phase 2 (NEW)

**Description:**  
Enforces ClickHouse Date32 range validation (1900-2299). MySQL supports years 1000-9999, but ClickHouse Date32 only supports 1900-2299.

**Behavior:**
- `true` (default): Throws exception for dates outside range
- `false`: Allows dates to be clamped by DebeziumConverter

**Valid Values:** `true`, `false`

**Example:**
```properties
# Strict mode (recommended for data integrity)
strict.date.validation=true

# Permissive mode (for legacy data)
strict.date.validation=false
```

**Use Cases:**
- `true`: Financial systems, compliance requirements
- `false`: Historical data with dates before 1900

**Error Message (when true):**
```
IllegalArgumentException: Date 1899-12-31 outside ClickHouse Date32 range (1900-2299)
```

**Related Bug:** BUG-DATA-4

---

### strict.bigint.validation

**Type:** Boolean  
**Default:** `true`  
**Required:** No  
**Phase:** Phase 2 (NEW)

**Description:**  
Detects BIGINT UNSIGNED overflow. MySQL BIGINT UNSIGNED can be 2^64-1, but ClickHouse Int64 max is 2^63-1.

**Behavior:**
- `true` (default): Throws exception for overflow values
- `false`: Allows negative values (unsigned overflow) to pass through

**Valid Values:** `true`, `false`

**Example:**
```properties
# Detect overflow (recommended)
strict.bigint.validation=true

# Allow overflow (use with caution)
strict.bigint.validation=false
```

**Technical Details:**
```
MySQL BIGINT UNSIGNED max: 18,446,744,073,709,551,615 (2^64-1)
ClickHouse Int64 max:       9,223,372,036,854,775,807 (2^63-1)

Overflow detection: if Java Long < 0 → likely unsigned overflow
```

**Recommendation:**  
Consider using ClickHouse `UInt64` type for BIGINT UNSIGNED columns.

**Error Message (when true):**
```
IllegalArgumentException: BIGINT UNSIGNED value -1 exceeds Int64 max (2^63-1). 
Consider using UInt64 in ClickHouse or set strict.bigint.validation=false
```

**Related Bug:** BUG-DATA-3

---

### allow.decimal.precision.loss

**Type:** Boolean  
**Default:** `false`  
**Required:** No  
**Phase:** Phase 2 (NEW)

**Description:**  
Controls behavior when decimal precision would be lost during conversion.

**Behavior:**
- `false` (default): Throws exception on precision loss
- `true`: Allows truncation with warning log

**Valid Values:** `true`, `false`

**Example:**
```properties
# Strict mode (prevent data loss)
allow.decimal.precision.loss=false

# Permissive mode (allow truncation)
allow.decimal.precision.loss=true
```

**Scenario:**
```sql
-- MySQL
DECIMAL(30, 10): 12345678901234567890.1234567890

-- ClickHouse
Decimal(20, 5): 12345678901234567890.12345
                                      ^^^^^
                                      Lost precision
```

**Recommendation:**  
Keep `false` for financial data. Set `true` only if precision loss is acceptable.

**Error Message (when false):**
```
IllegalArgumentException: Decimal precision would be lost. 
Original: 123.456789012345, Truncated: 123.45678. 
Set allow.decimal.precision.loss=true to allow.
```

**Related Bug:** BUG-DATA-7

---

### zero.date.behavior

**Type:** String (Enum)  
**Default:** `"null"`  
**Required:** No  
**Phase:** Phase 2 (NEW)

**Description:**  
Defines how to handle MySQL's special zero date (0000-00-00 00:00:00).

**Valid Values:**
- `"null"` (default): Convert to SQL NULL
- `"error"`: Throw exception

**Example:**
```properties
# Convert to NULL (default)
zero.date.behavior=null

# Strict mode (reject zero dates)
zero.date.behavior=error
```

**Scenarios:**

**Value: `"null"`**
```sql
-- MySQL
INSERT INTO users VALUES (1, '0000-00-00');

-- ClickHouse
id | birthdate
1  | NULL
```

**Value: `"error"`**
```sql
-- MySQL
INSERT INTO users VALUES (1, '0000-00-00');

-- Result
IllegalArgumentException: Zero date (0000-00-00) is not supported. 
Configure zero.date.behavior=null to allow.
```

**Recommendation:**  
Use `"null"` for legacy MySQL data. Use `"error"` for new applications.

**Related Bug:** BUG-DATA-5

---

## Phase 3: DDL Operation Parameters

**Implementation Phase:** Phase 3 - DDL Support  
**Date Introduced:** 2026-02-03  
**Purpose:** Enable comprehensive schema evolution

---

### clickhouse.drop.column.behavior

**Type:** Enum  
**Default:** `RENAME`  
**Required:** No  
**Phase:** Phase 3 (NEW)

**Description:**  
Defines behavior when MySQL executes `ALTER TABLE DROP COLUMN`.

**Valid Values:**
- `RENAME` (default): Rename column to `_deleted_<name>_<timestamp>`
- `DROP`: Execute DROP COLUMN in ClickHouse
- `IGNORE`: Leave column in ClickHouse
- `FAIL`: Throw exception (manual approval required)

**Examples:**

**RENAME (Safest - Default):**
```properties
clickhouse.drop.column.behavior=RENAME
```
```sql
-- MySQL
ALTER TABLE users DROP COLUMN old_email;

-- ClickHouse
ALTER TABLE users RENAME COLUMN `old_email` TO `_deleted_old_email_1738608000`;
```

**DROP:**
```properties
clickhouse.drop.column.behavior=DROP
```
```sql
-- MySQL
ALTER TABLE users DROP COLUMN old_email;

-- ClickHouse
ALTER TABLE users DROP COLUMN `old_email`;
```

**IGNORE:**
```properties
clickhouse.drop.column.behavior=IGNORE
```
```sql
-- MySQL
ALTER TABLE users DROP COLUMN old_email;

-- ClickHouse
-- No action taken, column remains
```

**FAIL:**
```properties
clickhouse.drop.column.behavior=FAIL
```
```sql
-- MySQL
ALTER TABLE users DROP COLUMN old_email;

-- Result
IllegalStateException: DROP COLUMN operation blocked by configuration. 
Behavior set to FAIL.
```

**Recommendations:**
- **Production (Default):** `RENAME` (safest, allows recovery)
- **Development:** `DROP` (cleaner)
- **Strict Environments:** `FAIL` (manual approval)

**Manual Cleanup (for RENAME):**
```sql
-- After verification, manually drop renamed columns
ALTER TABLE users DROP COLUMN `_deleted_old_email_1738608000`;
```

**Related:** BUG-SCHEMA-1

---

### clickhouse.drop.table.behavior

**Type:** Enum  
**Default:** `RENAME`  
**Required:** No  
**Phase:** Phase 3 (NEW)

**Description:**  
Defines behavior when MySQL executes `DROP TABLE`.

**Valid Values:**
- `RENAME` (default): Rename table to `_deleted_<name>_<timestamp>`
- `DROP`: Execute DROP TABLE in ClickHouse
- `IGNORE`: Leave table in ClickHouse
- `FAIL`: Throw exception

**Examples:**

**RENAME (Default):**
```properties
clickhouse.drop.table.behavior=RENAME
```
```sql
-- MySQL
DROP TABLE old_users;

-- ClickHouse
RENAME TABLE `old_users` TO `_deleted_old_users_1738608000`;
```

**DROP:**
```properties
clickhouse.drop.table.behavior=DROP
```
```sql
-- MySQL
DROP TABLE old_users;

-- ClickHouse
DROP TABLE `old_users`;
```

**Recommendations:**
- **Production:** `RENAME` (allows data recovery)
- **Development:** `DROP`
- **Mission-Critical:** `FAIL` (prevent accidental drops)

---

### clickhouse.rename.column.behavior

**Type:** Enum  
**Default:** `RENAME`  
**Required:** No  
**Phase:** Phase 3 (NEW)

**Description:**  
Defines behavior when MySQL executes `ALTER TABLE RENAME COLUMN`.

**Valid Values:**
- `RENAME` (default): Execute RENAME COLUMN in ClickHouse
- `IGNORE`: Leave old column name in ClickHouse
- `FAIL`: Throw exception

**Example:**
```properties
clickhouse.rename.column.behavior=RENAME
```
```sql
-- MySQL
ALTER TABLE users RENAME COLUMN email TO email_address;

-- ClickHouse
ALTER TABLE users RENAME COLUMN `email` TO `email_address`;
```

**Recommendations:**
- **Most Cases:** `RENAME` (keep schemas in sync)
- **Testing:** `IGNORE` (analyze impact first)

**Related:** BUG-SCHEMA-2

---

### clickhouse.type.change.behavior

**Type:** Enum  
**Default:** `MODIFY`  
**Required:** No  
**Phase:** Phase 3 (NEW)

**Description:**  
Defines behavior when MySQL executes `ALTER TABLE MODIFY COLUMN` (type change).

**Valid Values:**
- `MODIFY` (default): Execute type change in ClickHouse
- `IGNORE`: Leave original type in ClickHouse
- `FAIL`: Throw exception

**Example:**
```properties
clickhouse.type.change.behavior=MODIFY
```
```sql
-- MySQL
ALTER TABLE users MODIFY COLUMN age BIGINT;

-- ClickHouse (if safe: Int32 → Int64)
ALTER TABLE users MODIFY COLUMN `age` Int64;
```

**Related:** BUG-SCHEMA-3

---

### clickhouse.type.change.safe.only

**Type:** Boolean  
**Default:** `true`  
**Required:** No  
**Phase:** Phase 3 (NEW)

**Description:**  
When `clickhouse.type.change.behavior=MODIFY`, this controls whether only safe type changes are allowed.

**Behavior:**
- `true` (default): Only allow widening conversions
- `false`: Allow all type changes (use with caution)

**Safe Type Changes (when true):**

| From | To | Safe? |
|------|-----|-------|
| Int8 | Int16, Int32, Int64 | ✅ Yes |
| Int16 | Int32, Int64 | ✅ Yes |
| Int32 | Int64 | ✅ Yes |
| Float32 | Float64 | ✅ Yes |
| Date | DateTime, DateTime64 | ✅ Yes |
| String(N) | String(M) where M>N | ✅ Yes |
| Decimal(P1,S1) | Decimal(P2,S2) where P2>P1 | ✅ Yes |
| T | Nullable(T) | ✅ Yes |
| Int64 | Int32 | ❌ No (narrowing) |
| Float64 | Float32 | ❌ No (precision loss) |
| String | Int32 | ❌ No (incompatible) |

**Example:**
```properties
# Safe changes only (recommended)
clickhouse.type.change.safe.only=true

# Allow unsafe changes (development only)
clickhouse.type.change.safe.only=false
```

**Recommendations:**
- **Production:** `true` (prevent data loss)
- **Development/Testing:** `false` (more flexibility)

**Error Message (when true and unsafe):**
```
IllegalArgumentException: Unsafe type change from Int64 to Int32. 
Set clickhouse.type.change.safe.only=false to allow.
```

---

## Phase 4: Transaction Parameters

**Implementation Phase:** Phase 4 - Transaction Support  
**Date Introduced:** 2026-02-03  
**Purpose:** Preserve MySQL transaction atomicity in ClickHouse

---

### clickhouse.transaction.support.enable

**Type:** Boolean  
**Default:** `false`  
**Required:** No  
**Phase:** Phase 4 (NEW)

**Description:**  
Enables MySQL transaction boundary detection and atomic replication to ClickHouse.

**Behavior:**
- `false` (default): Disabled (backward compatible)
- `true`: Enable transaction support

**Requirements (when true):**
1. Debezium must be configured with `provide.transaction.metadata=true`
2. Buffer size must be configured appropriately
3. Timeout must be set for long-running transactions

**Example:**
```properties
# Enable transaction support
clickhouse.transaction.support.enable=true

# Required Debezium configuration
# (in source connector)
provide.transaction.metadata=true
```

**Impact:**
- **Atomicity:** Multi-statement transactions replicated atomically
- **ROLLBACK:** Rolled-back transactions not written to ClickHouse
- **Memory:** Buffers records in memory until COMMIT
- **Latency:** Slight increase (buffering delay)

**Recommendations:**
- **Transactional Workloads:** `true`
- **Append-Only Workloads:** `false` (lower latency)
- **Mission-Critical:** `true` (data consistency)

**Related:** BUG-TX-1, BUG-TX-2

---

### clickhouse.transaction.buffer.size

**Type:** Integer  
**Default:** `10000`  
**Required:** No  
**Phase:** Phase 4 (NEW)

**Description:**  
Maximum number of records to buffer per transaction. Prevents memory exhaustion from very large transactions.

**Behavior:**
- When limit reached, buffered records are force-committed
- New transaction context created for remaining records
- Warning logged

**Valid Values:** 100 - 100,000

**Examples:**
```properties
# Small transactions (< 1000 records)
clickhouse.transaction.buffer.size=1000

# Medium transactions (default)
clickhouse.transaction.buffer.size=10000

# Large transactions (use with caution)
clickhouse.transaction.buffer.size=50000
```

**Memory Impact:**
```
Memory = buffer.size × avg_record_size × tasks.max
```

**Example:**
```
10,000 records × 1 KB × 8 tasks = 80 MB
```

**Recommendations:**
- **Small Transactions:** 1,000 - 5,000
- **Medium Transactions:** 10,000 (default)
- **Large Transactions:** 25,000 - 50,000
- **Monitor:** Track transaction sizes in your workload

**Warning Message (when limit exceeded):**
```
WARN: Transaction tx123 exceeded buffer size (10000). 
Force-committing buffered records. Transaction atomicity may be lost.
```

---

### clickhouse.transaction.timeout.ms

**Type:** Long  
**Default:** `300000` (5 minutes)  
**Required:** No  
**Phase:** Phase 4 (NEW)

**Description:**  
Maximum time to wait for transaction completion. Stale transactions are cleaned up after timeout.

**Valid Values:** 1,000 - 3,600,000 (1 second to 1 hour)

**Examples:**
```properties
# Short timeout (1 minute)
clickhouse.transaction.timeout.ms=60000

# Default (5 minutes)
clickhouse.transaction.timeout.ms=300000

# Long-running transactions (30 minutes)
clickhouse.transaction.timeout.ms=1800000
```

**Cleanup Behavior:**
- Periodic check for stale transactions
- Force-commit buffered records
- Warning logged

**Recommendations:**
- **OLTP Workloads:** 60,000 (1 minute)
- **Mixed Workloads:** 300,000 (5 minutes, default)
- **Batch Processing:** 1,800,000 (30 minutes)

**Warning Message (on timeout):**
```
WARN: Transaction tx456 timed out after 300000ms. 
Force-committing 5000 buffered records.
```

---

## Core Parameters

### name

**Type:** String  
**Required:** Yes

**Description:**  
Unique name for the connector instance.

**Example:**
```properties
name=clickhouse-sink-production
```

---

### connector.class

**Type:** String  
**Required:** Yes

**Description:**  
Fully qualified class name for the connector.

**Example:**
```properties
connector.class=com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector
```

---

### tasks.max

**Type:** Integer  
**Default:** `1`  
**Required:** No

**Description:**  
Maximum number of tasks for parallel processing across partitions.

**Example:**
```properties
tasks.max=8
```

**Recommendations:**
- **Small Deployment:** 1-4
- **Medium Deployment:** 4-8
- **Large Deployment:** 8-16

---

### topics

**Type:** String (comma-separated)  
**Required:** Yes (or use topics.regex)

**Description:**  
List of Kafka topics to consume.

**Example:**
```properties
topics=mysql.database.table1,mysql.database.table2,mysql.database.table3
```

---

### topics.regex

**Type:** String (regex pattern)  
**Required:** No

**Description:**  
Regular expression to match topic names.

**Example:**
```properties
# All MySQL topics
topics.regex=mysql\\..*

# Specific database
topics.regex=mysql\\.mydb\\..*
```

---

## Connection Parameters

### clickhouse.server.url

**Type:** String  
**Required:** Yes

**Description:**  
JDBC connection URL for ClickHouse server.

**Format:**
```
jdbc:clickhouse://<host>:<port>/<database>?<options>
```

**Examples:**
```properties
# Basic
clickhouse.server.url=jdbc:clickhouse://localhost:8123/default

# With SSL
clickhouse.server.url=jdbc:clickhouse://clickhouse:8443/mydb?ssl=true

# With options
clickhouse.server.url=jdbc:clickhouse://clickhouse:8123/mydb?socket_timeout=60000
```

---

### clickhouse.server.user

**Type:** String  
**Required:** Yes

**Description:**  
Username for ClickHouse authentication.

**Example:**
```properties
clickhouse.server.user=connector_user
```

---

### clickhouse.server.password

**Type:** String  
**Required:** Yes

**Description:**  
Password for ClickHouse authentication. Use environment variables for security.

**Examples:**
```properties
# Direct (not recommended for production)
clickhouse.server.password=mypassword

# Environment variable (recommended)
clickhouse.server.password=${env:CLICKHOUSE_PASSWORD}
```

---

### clickhouse.server.database

**Type:** String  
**Required:** Yes

**Description:**  
Default ClickHouse database for tables.

**Example:**
```properties
clickhouse.server.database=replicated_db
```

---

### clickhouse.server.port

**Type:** Integer  
**Default:** `8123`  
**Required:** No

**Description:**  
ClickHouse HTTP port.

**Example:**
```properties
clickhouse.server.port=8123
```

---

## Performance Parameters

### buffer.flush.time

**Type:** Integer (milliseconds)  
**Default:** `500`  
**Required:** No

**Description:**  
Maximum time to buffer records before flushing to ClickHouse.

**Trade-offs:**
- **Lower:** Lower latency, higher overhead
- **Higher:** Higher throughput, higher latency

**Examples:**
```properties
# Real-time (low latency)
buffer.flush.time=100

# Balanced
buffer.flush.time=500

# Batch-oriented (high throughput)
buffer.flush.time=5000
```

---

### batch.size

**Type:** Integer  
**Default:** `1000`  
**Required:** No

**Description:**  
Number of records per batch insert to ClickHouse.

**Examples:**
```properties
# Small batches
batch.size=1000

# Medium batches
batch.size=5000

# Large batches
batch.size=10000
```

**Recommendations:**
- **Real-time:** 1,000 - 2,000
- **Balanced:** 5,000 (good default)
- **Throughput:** 10,000+

---

### clickhouse.connection.pool.size

**Type:** Integer  
**Default:** `10`  
**Required:** No

**Description:**  
Maximum connections in the pool.

**Formula:**
```
connection.pool.size = thread.pool.size × 2 + 5
```

**Example:**
```properties
clickhouse.connection.pool.size=20
```

---

## Error Handling Parameters

### errors.tolerance

**Type:** Enum  
**Default:** `none`  
**Required:** No

**Description:**  
Error tolerance level.

**Valid Values:**
- `none`: Fail on any error
- `all`: Continue on errors (use DLQ)

**Example:**
```properties
# Strict (recommended for data integrity)
errors.tolerance=none

# Tolerant (for best-effort pipelines)
errors.tolerance=all
errors.deadletterqueue.topic.name=clickhouse-sink-dlq
```

---

### errors.deadletterqueue.topic.name

**Type:** String  
**Required:** No

**Description:**  
Kafka topic for failed records.

**Example:**
```properties
errors.deadletterqueue.topic.name=clickhouse-sink-dlq
```

---

### errors.log.enable

**Type:** Boolean  
**Default:** `false`  
**Required:** No

**Description:**  
Enable error logging.

**Example:**
```properties
errors.log.enable=true
errors.log.include.messages=true
```

---

## Complete Configuration Examples

### Example 1: Minimal Production

```properties
# Connector basics
name=clickhouse-sink
connector.class=com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector
tasks.max=4
topics=mysql.mydb.users,mysql.mydb.orders

# ClickHouse connection
clickhouse.server.url=jdbc:clickhouse://clickhouse:8123
clickhouse.server.user=connector_user
clickhouse.server.password=${env:CLICKHOUSE_PASSWORD}
clickhouse.server.database=replicated_db
clickhouse.server.port=8123

# Threading
thread.pool.size=4

# Batching
buffer.flush.time=500
batch.size=1000

# Phase 2: Data validation (strict)
strict.date.validation=true
strict.bigint.validation=true
allow.decimal.precision.loss=false
zero.date.behavior=null

# Error handling
errors.tolerance=none
errors.log.enable=true
errors.deadletterqueue.topic.name=clickhouse-sink-dlq
```

---

### Example 2: Full Production with All Features

```properties
# Connector basics
name=clickhouse-sink-production
connector.class=com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector
tasks.max=8
topics.regex=mysql\\..*

# ClickHouse connection
clickhouse.server.url=jdbc:clickhouse://clickhouse-cluster:8123
clickhouse.server.user=connector_user
clickhouse.server.password=${env:CLICKHOUSE_PASSWORD}
clickhouse.server.database=replicated_db
clickhouse.server.port=8123
clickhouse.connection.pool.size=20
clickhouse.connection.timeout.ms=30000

# Threading (Phase 1 - safe multi-threading)
thread.pool.size=8

# Batching
buffer.flush.time=1000
batch.size=5000
buffer.max.records=50000

# Phase 2: Data validation
strict.date.validation=true
strict.bigint.validation=true
allow.decimal.precision.loss=false
zero.date.behavior=null

# Phase 3: DDL operations
clickhouse.drop.column.behavior=RENAME
clickhouse.drop.table.behavior=RENAME
clickhouse.rename.column.behavior=RENAME
clickhouse.type.change.behavior=MODIFY
clickhouse.type.change.safe.only=true

# Phase 4: Transaction support
clickhouse.transaction.support.enable=true
clickhouse.transaction.buffer.size=10000
clickhouse.transaction.timeout.ms=300000

# Error handling
errors.tolerance=none
errors.log.enable=true
errors.log.include.messages=true
errors.deadletterqueue.topic.name=clickhouse-sink-dlq

# Monitoring
clickhouse.metrics.enabled=true
clickhouse.metrics.jmx.enabled=true
```

---

### Example 3: High-Performance Configuration

```properties
# Optimized for maximum throughput
name=clickhouse-sink-highperf
connector.class=com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector
tasks.max=16
topics.regex=mysql\\..*

# Connection
clickhouse.server.url=jdbc:clickhouse://clickhouse-cluster:8123
clickhouse.server.user=connector_user
clickhouse.server.password=${env:CLICKHOUSE_PASSWORD}
clickhouse.server.database=replicated_db
clickhouse.connection.pool.size=50

# Threading (maximum safe)
thread.pool.size=16

# Batching (large batches)
buffer.flush.time=2000
batch.size=10000
buffer.max.records=100000

# Phase 2: Permissive (for performance)
strict.date.validation=false
strict.bigint.validation=false
allow.decimal.precision.loss=true
zero.date.behavior=null

# Phase 3: DDL
clickhouse.drop.column.behavior=RENAME
clickhouse.rename.column.behavior=RENAME
clickhouse.type.change.behavior=MODIFY
clickhouse.type.change.safe.only=false

# Phase 4: Large transaction buffers
clickhouse.transaction.support.enable=true
clickhouse.transaction.buffer.size=50000
clickhouse.transaction.timeout.ms=600000

# Error handling (tolerant)
errors.tolerance=all
errors.deadletterqueue.topic.name=clickhouse-sink-dlq
```

---

### Example 4: Mission-Critical Configuration

```properties
# Strictest settings for financial/compliance systems
name=clickhouse-sink-critical
connector.class=com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector
tasks.max=4
topics=mysql.critical.accounts,mysql.critical.transactions

# Connection (HA)
clickhouse.server.url=jdbc:clickhouse://clickhouse-ha-cluster:8123
clickhouse.server.user=connector_user
clickhouse.server.password=${env:CLICKHOUSE_PASSWORD}
clickhouse.server.database=critical_db
clickhouse.connection.pool.size=20
clickhouse.max.retries=10

# Threading (conservative)
thread.pool.size=4

# Batching (small for atomicity)
buffer.flush.time=500
batch.size=1000

# Phase 2: Strictest validation
strict.date.validation=true
strict.bigint.validation=true
allow.decimal.precision.loss=false
zero.date.behavior=error

# Phase 3: Manual approval required
clickhouse.drop.column.behavior=FAIL
clickhouse.drop.table.behavior=FAIL
clickhouse.rename.column.behavior=RENAME
clickhouse.type.change.behavior=FAIL

# Phase 4: Transaction support (mandatory)
clickhouse.transaction.support.enable=true
clickhouse.transaction.buffer.size=5000
clickhouse.transaction.timeout.ms=120000

# Error handling (fail-fast)
errors.tolerance=none
errors.log.enable=true
errors.deadletterqueue.topic.name=clickhouse-sink-dlq-critical

# Monitoring
clickhouse.metrics.enabled=true
clickhouse.metrics.jmx.enabled=true
```

---

## Configuration Validation

### Pre-Deployment Validation

Use Kafka Connect REST API to validate configuration:

```bash
curl -X PUT -H "Content-Type: application/json" \
  --data @clickhouse-sink-config.json \
  http://localhost:8083/connector-plugins/ClickHouseSinkConnector/config/validate
```

---

## Quick Reference Table

| Parameter | Default | Phase | Critical? |
|-----------|---------|-------|-----------|
| `strict.date.validation` | `true` | 2 | ⚠️ Medium |
| `strict.bigint.validation` | `true` | 2 | ⚠️ Medium |
| `allow.decimal.precision.loss` | `false` | 2 | ⚠️ Medium |
| `zero.date.behavior` | `"null"` | 2 | ⚠️ Medium |
| `clickhouse.drop.column.behavior` | `RENAME` | 3 | ⚠️ Medium |
| `clickhouse.drop.table.behavior` | `RENAME` | 3 | ⚠️ Medium |
| `clickhouse.rename.column.behavior` | `RENAME` | 3 | ℹ️ Low |
| `clickhouse.type.change.behavior` | `MODIFY` | 3 | ⚠️ Medium |
| `clickhouse.type.change.safe.only` | `true` | 3 | 🔴 High |
| `clickhouse.transaction.support.enable` | `false` | 4 | 🔴 High |
| `clickhouse.transaction.buffer.size` | `10000` | 4 | ⚠️ Medium |
| `clickhouse.transaction.timeout.ms` | `300000` | 4 | ⚠️ Medium |
| `thread.pool.size` | `4` | 1* | 🔴 High |
| `batch.size` | `1000` | Core | 🔴 High |

*Now safe for multi-threading in v2.0.0

---

## Related Documentation

- **Production Deployment:** [`PRODUCTION-DEPLOYMENT-GUIDE.md`](PRODUCTION-DEPLOYMENT-GUIDE.md)
- **Release Summary:** [`RELEASE-SUMMARY.md`](RELEASE-SUMMARY.md)
- **Test Report:** [`TEST-REPORT.md`](TEST-REPORT.md)

---

**End of Configuration Reference**
