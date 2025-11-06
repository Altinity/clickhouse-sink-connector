# Temporal Data Tracking and Version History

This document explains how the ClickHouse Sink Connector implements comprehensive temporal data tracking using two complementary mechanisms:

1. **`binlog_history` table** - A complete, immutable audit trail of every change
2. **`_valid_to` column** - Enables efficient time-travel queries on data tables

Together, these mechanisms provide point-in-time queries, complete audit trails, and automatic data lifecycle management.

![Temporal Data Tracking Architecture](binlog_history_diagram.png)

## Overview

The sink connector's temporal data tracking system enables:

- **Complete Audit Trail**: Every change is permanently recorded
- **Point-in-Time Queries**: View data as it existed at any moment in history
- **Time Travel**: Query historical states without performance penalty
- **Automatic Data Lifecycle**: TTL-based partition management
- **Efficient Storage**: Automatic cleanup of expired data

> **Important**: Version history tracking is designed to be enabled in a **separate connector instance**. This separation ensures that binlog history and schema (DDL) changes can be reliably captured and audited without impacting normal data ingestion throughput or risking missed changes due to connector restarts or failures.

## Architecture Components

### 1. binlog_history Table - The Complete Audit Trail

The `binlog_history` table captures **every single change** that flows through the replication pipeline.

#### Schema Structure

```sql
CREATE TABLE binlog_history.history (
    `gtid` String,
    `database` LowCardinality(String),
    `table` LowCardinality(String),
    `ddl` String,
    `before` String,              -- JSON of fields before change
    `after` String,               -- JSON of fields after change
    `_raw` String,                -- Complete CDC event payload
    `_time` DateTime,             -- When the change occurred
    `is_deleted` UInt8,
    `_operation` String,          -- INSERT, UPDATE, DELETE
    `_version` UInt64,
    `host` String,
    `logfile` String,
    `position` UInt64,
    `primary_host` String,
    `server_id` UInt64,
    `row` UInt64,
    `sequence` UInt64
)
ENGINE = ReplacingMergeTree(_version, is_deleted)
PARTITION BY toDate(_time)
ORDER BY (server_id, logfile, position, sequence, _time)
TTL toDate(_time) + toIntervalDay(30);
```

#### Key Characteristics

**The `_time` Column:**
- Records the exact timestamp when each change occurred in the source database
- Used for partitioning data by date
- Enables chronological ordering of all changes
- Foundation for time-based queries and audit trails

**Partitioning Strategy:**
- Each day's changes go into a separate partition: `PARTITION BY toDate(_time)`
- Partitions are named by date: `2025-11-06`, `2025-11-07`, etc.
- Queries can efficiently skip irrelevant partitions
- Old partitions are automatically dropped by TTL

**Data Retention:**
- TTL automatically removes partitions older than 30 days (configurable)
- Formula: `TTL toDate(_time) + toIntervalDay(30)`
- Entire partitions are dropped, not individual rows (efficient)

**Use Cases:**
1. **Compliance & Auditing**: Immutable record of all changes
2. **Debugging**: Trace exact sequence of operations
3. **Change Analysis**: Understand when and what changed
4. **Rollback Planning**: Know exactly what changed to plan recovery

#### Example: DML Event in History Table

```
gtid:         2290064
database:     sbtest
table:        embeddedconnector.sbtest.sbtest1
ddl:          
before:       
after:        [{"name":"id","index":0,"schema":{"type":"INT32","optional":false},"value":1},{"name":"k","index":1,"schema":{"type":"INT32","optional":false},"value":50},{"name":"c","index":2,"schema":{"type":"STRING","optional":false},"value":"31451373586-15688153734-79729593694-96509299839-83724898275-86711833539-78981337422-35049690573-51724173961-87474696253"},{"name":"pad","index":3,"schema":{"type":"STRING","optional":false},"value":"98996621624-36689827414-04092488557-09587706818-65008859162"}]
_raw:         {"key":{"id":1},"value":{"op":"c","before":null,"ts_us":1757199874527207,"after":{"pad":"98996621624-36689827414-04092488557-09587706818-65008859162","c":"31451373586-15688153734-79729593694-96509299839-83724898275-86711833539-78981337422-35049690573-51724173961-87474696253","id":1,"k":50},"source":{"ts_us":1757111993608980,"query":null,"thread":27172,"server_id":940,"version":"3.1.3.Final","sequence":null,"file":"mysql-bin.000004","connector":"mysql","pos":23226335,"name":"embeddedconnector","gtid":"ed8a2f96-8919-11f0-b8c4-8e913c21687b:2290064","row":0,"ts_ns":1757111993608980000,"ts_ms":1757111993608,"snapshot":"false","db":"sbtest","table":"sbtest1"},"ts_ns":1757199874527207770,"transaction":null,"ts_ms":1757199874527},"topic":"embeddedconnector.sbtest.sbtest1","sourceOffset":{"ts_sec":1757111993,"file":"mysql-bin.000004","pos":23226191,"gtids":"ed8a2f96-8919-11f0-b8c4-8e913c21687b:1-2290063","row":1,"server_id":940,"event":2},"sourcePartition":{"server":"embeddedconnector"}}
_time:        1757111993608
is_deleted:   0
operation:    CREATE
_version:     0
host:         940
logfile:      mysql-bin.000004
position:     23226335
primary_host: 940
```

#### Example: DDL Event in History Table

```
gtid:         2316537
database:     sbtest
table:        
ddl:          alter table sbtest1 add column o varchar(100)
before:       
after:        
_raw:         
_time:        1757216371080
is_deleted:   0
operation:    
_version:     0
host:         940
logfile:      mysql-bin.000004
position:     35924824
primary_host: 940
```

### 2. Data Table with _valid_to - Time Travel Enabled

Data tables store both **current** and **historical** versions of records, enabling efficient temporal queries.

#### Schema Structure

```sql
CREATE TABLE users (
    `id` Int32,
    `name` String,
    `email` String,
    -- Temporal tracking columns:
    `_valid_to` DateTime DEFAULT '2100-01-01 00:00:00',
    `_operation` String,
    `_version` UInt64,
    `is_deleted` UInt8
)
ENGINE = ReplacingMergeTree(_version, is_deleted)
PARTITION BY toDate(_valid_to)
ORDER BY (id, _valid_to)
TTL _valid_to + toIntervalDay(30);
```

#### Key Characteristics

**The `_valid_to` Column:**
- Indicates **when this version of the record expired** (was replaced or deleted)
- Active records have `_valid_to = '2100-01-01 00:00:00'` (far future default)
- Superseded records have `_valid_to` set to the timestamp of the next change
- Enables efficient "as-of" queries without scanning entire history

**Partitioning Strategy:**
- **Active Partition** (`2100-01-01`): Contains all current, live records
- **Historical Partitions** (date-based): Contain superseded versions
- Most queries only touch the active partition → excellent performance
- Historical queries only scan relevant date partitions

**How Updates Work:**

When a record changes (e.g., UPDATE or DELETE):

1. **Old Record Processing:**
   - Existing record's `_valid_to` is updated from `2100-01-01` to current timestamp
   - Record automatically moves to today's partition (based on `_valid_to`)
   - Record becomes "historical" but remains queryable

2. **New Record Processing:**
   - New version is inserted with `_valid_to = '2100-01-01'`
   - New record goes to the active partition
   - Higher `_version` number ensures proper ordering

3. **Partition Movement:**
   ```
   Before Update:
   Partition 2100-01-01: [id=1, name='John', _valid_to='2100-01-01', _version=1]
   
   After Update (at 2025-11-06 10:30):
   Partition 2100-01-01: [id=1, name='Jane', _valid_to='2100-01-01', _version=2]
   Partition 2025-11-06: [id=1, name='John', _valid_to='2025-11-06 10:30', _version=1]
   ```

**TTL and Data Lifecycle:**
- Historical partitions older than 30 days are automatically dropped
- Active partition (`2100-01-01`) is never removed by TTL
- Storage automatically scales with retention policy

## Temporal Query Patterns

### 1. Current State Query (Most Common)

Get only the active, current records:

```sql
SELECT id, name, email
FROM users
WHERE _valid_to = '2100-01-01 00:00:00'
  AND is_deleted = 0;
```

**Performance:** Excellent - only scans the active partition

### 2. Point-in-Time Query

View data as it existed at a specific moment:

```sql
-- What was the state at 2025-11-06 10:45?
SELECT id, name, email
FROM users
WHERE _valid_to > '2025-11-06 10:45:00'
  AND _version = (
    SELECT MAX(_version)
    FROM users AS u2
    WHERE u2.id = users.id
      AND u2._valid_to > '2025-11-06 10:45:00'
  );
```

**Performance:** Scans only the active partition + specific historical partitions

### 3. Time-Range Analysis

See what changed during a specific period:

```sql
-- Find all records that were modified between 10:00 and 11:00
SELECT id, name, _valid_to, _version
FROM users
WHERE toDate(_valid_to) = '2025-11-06'
  AND _valid_to BETWEEN '2025-11-06 10:00:00' AND '2025-11-06 11:00:00'
ORDER BY _valid_to;
```

### 4. Complete History Audit

Track all changes to a specific record:

```sql
-- Full history of user id=1 from binlog_history
SELECT 
    _time,
    _operation,
    before,
    after,
    gtid
FROM binlog_history.history
WHERE table = 'users'
  AND JSONExtractInt(after, 'id') = 1
ORDER BY _time;
```

### 5. Change Frequency Analysis

Understand update patterns:

```sql
-- How often was each record updated in the last 7 days?
SELECT 
    id,
    COUNT(*) as change_count,
    MIN(_valid_to) as first_change,
    MAX(_valid_to) as last_change
FROM users
WHERE toDate(_valid_to) >= today() - 7
  AND toDate(_valid_to) < today()
GROUP BY id
ORDER BY change_count DESC;
```

## Partition Management in Detail

### Active Partition (2100-01-01)

- **Purpose**: Holds all current, live records
- **Characteristics**:
  - Always the largest partition
  - Constantly updated as changes arrive
  - Never expired by TTL (date is far in the future)
  - Primary target for most application queries
  
- **Query Pattern**: 
  ```sql
  WHERE _valid_to = '2100-01-01 00:00:00'
  ```

### Historical Partitions (Date-Based)

- **Purpose**: Store superseded versions of records
- **Naming**: Based on the date in `_valid_to` (e.g., `2025-11-06`)
- **Characteristics**:
  - Immutable after creation (append-only)
  - Grow throughout the day as updates occur
  - Automatically expired after retention period
  - Only queried for temporal/audit operations

- **Creation Trigger**: When a record's `_valid_to` changes from `2100-01-01` to a specific timestamp

### TTL-Based Cleanup

Both tables use TTL for automatic data lifecycle management:

**binlog_history Table:**
```sql
TTL toDate(_time) + toIntervalDay(30)
```
- Partitions are dropped 30 days after the changes occurred

**Data Table:**
```sql
TTL _valid_to + toIntervalDay(30)
```
- Historical versions are dropped 30 days after they were superseded
- Active partition (`2100-01-01`) is never affected

**Benefits:**
- Automatic storage management
- No manual cleanup required
- Predictable storage costs
- Compliance with data retention policies

## Configuration

### Enabling Version History

To enable version history tracking, configure the following properties:

```properties
# Enable replication history
replication.history.enable=true

# Database for history table
replication.history.database.name=binlog_history

# History table name
replication.history.table.name=history

# TTL days for history retention (default: 30)
replication.history.ttl.days=30

# Server timezone for DateTime columns
clickhouse.datetime.timezone=America/Chicago
```

### Deployment Recommendations

**Rationale for Separate Instance:**  
Keeping version history in a separate connector instance decouples schema monitoring from data synchronization, allowing detailed tracking of all schema (DDL) changes alongside operational events. This setup is particularly useful for compliance, audits, and disaster recovery.

To deploy version history tracking:

1. **Deploy a dedicated instance** of the sink connector
2. **Set `replication.history.enable=true`** in the dedicated instance
3. **Configure database and table names** using `replication.history.database.name` and `replication.history.table.name`
4. **Keep your main data pipeline connector** focused on data ingestion without version history overhead

> **Note**: If replicating DDL changes is not required for your application, you may leave version history tracking disabled in your main data pipeline connector.



## Performance Considerations

### Storage Overhead

- **binlog_history**: Stores every change; size grows with update frequency
- **Data Tables**: Store current + historical versions; size depends on retention and change rate
- **Typical Overhead**: 2-5x the current data size for 30-day retention

### Query Performance

- **Current State Queries**: Excellent (single partition scan)
- **Recent History**: Good (few partitions)
- **Deep Historical Queries**: Moderate (more partitions to scan)
- **Full Scans**: Use `binlog_history` with appropriate filters

### Optimization Tips

1. **Adjust Retention**: Balance compliance needs vs. storage costs
   ```sql
   ALTER TABLE users MODIFY TTL _valid_to + toIntervalDay(7); -- Shorter retention
   ```

2. **Partition Pruning**: Always include time predicates
   ```sql
   -- Good: Scans only relevant partitions
   WHERE _valid_to > '2025-11-01'
   
   -- Bad: Scans all partitions
   WHERE name = 'John'
   ```

3. **Materialized Views**: Pre-aggregate common temporal queries
   ```sql
   CREATE MATERIALIZED VIEW daily_changes
   ENGINE = SummingMergeTree
   ORDER BY (date, table_name)
   AS SELECT
       toDate(_time) as date,
       table as table_name,
       count() as change_count
   FROM binlog_history.history
   GROUP BY date, table_name;
   ```

## Best Practices

1. **Set Appropriate TTL**: Balance compliance requirements with storage costs
2. **Monitor Partition Sizes**: Watch for tables with high update frequency
3. **Use Current State Views**: Create views that filter to `_valid_to = '2100-01-01'` for applications
4. **Archive Before Expiry**: Export data from `binlog_history` before TTL if long-term retention needed
5. **Index Strategy**: Ensure ORDER BY includes temporal columns for efficient queries
6. **Test Temporal Queries**: Validate point-in-time query performance under load

## Troubleshooting

### Issue: Historical Queries Are Slow

**Solution**: Ensure queries include partition key in WHERE clause:
```sql
-- Add date filter to enable partition pruning
WHERE toDate(_valid_to) BETWEEN '2025-11-01' AND '2025-11-06'
```

### Issue: Storage Growing Too Fast

**Solution**: Reduce TTL retention period:
```sql
ALTER TABLE users MODIFY TTL _valid_to + toIntervalDay(7);
```

### Issue: Missing Historical Data

**Check**: TTL may have removed data. Verify retention settings:
```sql
SELECT 
    partition,
    min_time,
    max_time,
    rows
FROM system.parts
WHERE table = 'users';
```
