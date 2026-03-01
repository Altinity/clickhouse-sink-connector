# PostgreSQL Replication Test Gaps - Critical Analysis

## Executive Summary

This document provides a detailed analysis of **critical gaps** in the PostgreSQL CDC replication test coverage that prevent the connector from being legitimately claimed as "fully operational" for production use.

**Status**: ⚠️ **BLOCKS PRODUCTION DEPLOYMENT**

**Last Updated**: 2026-02-27

---

## 1. Critical Findings Overview

### 1.1 Gap Severity Classification

| Priority | Operation | Test Coverage | Production Risk | Status |
|----------|-----------|---------------|-----------------|--------|
| 🔴 **CRITICAL** | UPDATE operations | **0%** ❌ | Data corruption, stale records | UNTESTED |
| 🔴 **CRITICAL** | DELETE operations | **0%** ❌ | Data retention violations | UNTESTED |
| 🟡 **HIGH** | TRUNCATE operations | Test exists but **DISABLED** ⚠️ | Mass data loss | BROKEN |
| 🟡 **HIGH** | Data type coverage | **~30%** (12 of 40+ types) | Type conversion errors | INCOMPLETE |
| 🟠 **MEDIUM** | Batch operations | Only 2-40 rows tested | Performance issues | INADEQUATE |

### 1.2 Current Test Coverage Summary

**✅ What IS Tested** (Working):
- INSERT operations (initial snapshot + CDC)
- DDL changes (CREATE TABLE during CDC)
- Basic data types: UUID, JSONB, NUMERIC, TIMESTAMPTZ, TEXT, BOOLEAN, BIGINT
- Multiple schemas support
- Both pgoutput and decoderbufs plugins
- Basic replication flow

**❌ What is NOT Tested** (Critical Gaps):
- UPDATE operations (single row, multi-row, NULL updates, batch updates)
- DELETE operations (single row, multi-row, cascading deletes)
- TRUNCATE operations (assertion commented out)
- 28+ additional data types (ARRAY types, network types, geometric types, etc.)
- Large-scale operations (>1000 rows)
- Concurrent operations
- Error recovery scenarios

### 1.3 Risk Assessment

**Production Deployment Risk Level**: 🔴 **HIGH - NOT RECOMMENDED**

**Rationale**:
1. **UPDATE operations are fundamental to CDC** - Without verified UPDATE handling, any production workload with data modifications will be at risk
2. **DELETE operations are critical for data compliance** - GDPR, data retention policies require reliable DELETE handling
3. **Type conversion bugs will cause data corruption** - 70% of PostgreSQL types untested
4. **ReplacingMergeTree behavior unverified** - UPDATE/DELETE rely on this engine working correctly

---

## 2. Detailed Gap Analysis

### 2.1 UPDATE Operations (CRITICAL ❌)

#### 2.1.1 Why UPDATE Testing is Critical

**PostgreSQL CDC UPDATE Flow**:
```
PostgreSQL UPDATE → WAL event → Debezium connector → 
Kafka topic → Sink connector → ClickHouse ReplacingMergeTree
```

**ClickHouse ReplacingMergeTree Behavior**:
- Updates are handled via `_version` column (monotonically increasing)
- Multiple versions of same row exist until OPTIMIZE/FINAL
- Without testing, we cannot verify:
  - Version column increments correctly
  - FINAL queries return latest version
  - Old versions eventually removed
  - Concurrent updates handled correctly

**Real-World Impact**:
```sql
-- PostgreSQL: User updates their email
UPDATE users SET email = 'newemail@example.com' WHERE id = 123;

-- ClickHouse WITHOUT proper UPDATE handling:
-- Query: SELECT email FROM users WHERE id = 123;
-- ❌ Might return old email
-- ❌ Might return both old and new (duplicates)
-- ❌ Might fail to update at all
```

#### 2.1.2 Test Scenarios Needed

**Scenario 1: Single Row UPDATE**

```java
@Test
@DisplayName("PostgreSQL UPDATE - Single Row")
public void testSingleRowUpdate() throws Exception {
    // Setup: Insert initial data
    executePostgresSQL(
        "INSERT INTO users (id, email, name) VALUES (1, 'old@example.com', 'John Doe')"
    );
    Thread.sleep(5000); // Wait for replication
    
    // Verify initial state in ClickHouse
    ResultSet rs = executeClickHouseQuery(
        "SELECT email, _version FROM users FINAL WHERE id = 1"
    );
    Assert.assertEquals("old@example.com", rs.getString("email"));
    long version1 = rs.getLong("_version");
    
    // Execute UPDATE
    executePostgresSQL(
        "UPDATE users SET email = 'new@example.com' WHERE id = 1"
    );
    Thread.sleep(5000); // Wait for replication
    
    // Verify UPDATE applied
    rs = executeClickHouseQuery(
        "SELECT email, _version FROM users FINAL WHERE id = 1"
    );
    Assert.assertEquals("new@example.com", rs.getString("email"));
    long version2 = rs.getLong("_version");
    Assert.assertTrue(version2 > version1); // Version incremented
    
    // Verify only ONE row after FINAL
    rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM users FINAL WHERE id = 1"
    );
    Assert.assertEquals(1, rs.getInt(1));
}
```

**Scenario 2: Multi-Column UPDATE**

```java
@Test
@DisplayName("PostgreSQL UPDATE - Multiple Columns")
public void testMultiColumnUpdate() throws Exception {
    executePostgresSQL(
        "INSERT INTO users (id, email, name, age, active) " +
        "VALUES (2, 'user@example.com', 'Jane Smith', 30, true)"
    );
    Thread.sleep(5000);
    
    executePostgresSQL(
        "UPDATE users SET name = 'Jane Doe', age = 31, active = false WHERE id = 2"
    );
    Thread.sleep(5000);
    
    ResultSet rs = executeClickHouseQuery(
        "SELECT name, age, active FROM users FINAL WHERE id = 2"
    );
    Assert.assertEquals("Jane Doe", rs.getString("name"));
    Assert.assertEquals(31, rs.getInt("age"));
    Assert.assertFalse(rs.getBoolean("active"));
}
```

**Scenario 3: UPDATE to NULL**

```java
@Test
@DisplayName("PostgreSQL UPDATE - Set Column to NULL")
public void testUpdateToNull() throws Exception {
    executePostgresSQL(
        "INSERT INTO users (id, email, phone) VALUES (3, 'user@example.com', '555-1234')"
    );
    Thread.sleep(5000);
    
    // Update phone to NULL
    executePostgresSQL(
        "UPDATE users SET phone = NULL WHERE id = 3"
    );
    Thread.sleep(5000);
    
    ResultSet rs = executeClickHouseQuery(
        "SELECT phone FROM users FINAL WHERE id = 3"
    );
    Assert.assertNull(rs.getString("phone"));
}
```

**Scenario 4: Batch UPDATE**

```java
@Test
@DisplayName("PostgreSQL UPDATE - Batch Update")
public void testBatchUpdate() throws Exception {
    // Insert 1000 rows
    for (int i = 1; i <= 1000; i++) {
        executePostgresSQL(
            "INSERT INTO products (id, name, price, in_stock) " +
            "VALUES (" + i + ", 'Product " + i + "', 10.00, true)"
        );
    }
    Thread.sleep(10000);
    
    // Batch update: 20% discount on all products
    executePostgresSQL(
        "UPDATE products SET price = price * 0.8"
    );
    Thread.sleep(15000);
    
    // Verify all prices updated
    ResultSet rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM products FINAL WHERE price = 8.00"
    );
    Assert.assertEquals(1000, rs.getInt(1));
    
    // Verify no duplicates
    rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM products FINAL"
    );
    Assert.assertEquals(1000, rs.getInt(1));
}
```

**Scenario 5: UPDATE with WHERE clause (partial update)**

```java
@Test
@DisplayName("PostgreSQL UPDATE - Partial Update with WHERE")
public void testPartialUpdate() throws Exception {
    executePostgresSQL(
        "INSERT INTO orders (id, status, total) VALUES " +
        "(1, 'pending', 100), (2, 'pending', 200), (3, 'completed', 300)"
    );
    Thread.sleep(5000);
    
    // Update only pending orders
    executePostgresSQL(
        "UPDATE orders SET status = 'processing' WHERE status = 'pending'"
    );
    Thread.sleep(5000);
    
    // Verify 2 orders updated, 1 unchanged
    ResultSet rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM orders FINAL WHERE status = 'processing'"
    );
    Assert.assertEquals(2, rs.getInt(1));
    
    rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM orders FINAL WHERE status = 'completed'"
    );
    Assert.assertEquals(1, rs.getInt(1));
}
```

#### 2.1.3 Expected Behavior & Validation

**Expected ClickHouse Behavior**:
1. Each UPDATE creates a new row with incremented `_version`
2. Old row version remains until OPTIMIZE or explicitly queried without FINAL
3. FINAL clause returns only latest version (highest `_version`)
4. All updated columns reflect new values
5. NULL updates properly handled

**Validation Approach**:
```sql
-- Check version increment
SELECT id, email, _version 
FROM users 
WHERE id = 1 
ORDER BY _version DESC;
-- Should show multiple versions

-- Check FINAL returns latest
SELECT email FROM users FINAL WHERE id = 1;
-- Should show only updated value

-- Check no duplicates with FINAL
SELECT id, COUNT(*) FROM users FINAL GROUP BY id HAVING COUNT(*) > 1;
-- Should return 0 rows
```

---

### 2.2 DELETE Operations (CRITICAL ❌)

#### 2.2.1 Why DELETE Testing is Critical

**PostgreSQL CDC DELETE Flow**:
```
PostgreSQL DELETE → WAL event → Debezium tombstone → 
Sink connector → ClickHouse soft-delete marker
```

**ClickHouse Soft-Delete Mechanism**:
- DELETE implemented via `_sign` column (-1 = deleted, 1 = active)
- ReplacingMergeTree uses `_sign` to filter deleted rows
- Without testing, we cannot verify:
  - `_sign` set to -1 correctly
  - FINAL queries exclude deleted rows
  - Deleted rows eventually removed by OPTIMIZE
  - Cascading deletes handled (if supported)

**Real-World Impact**:
```sql
-- PostgreSQL: User deletes their account (GDPR compliance)
DELETE FROM users WHERE id = 123;

-- ClickHouse WITHOUT proper DELETE handling:
-- ❌ User data still visible (GDPR violation)
-- ❌ Soft-delete marker not set
-- ❌ Data retained indefinitely
```

#### 2.2.2 Test Scenarios Needed

**Scenario 1: Single Row DELETE**

```java
@Test
@DisplayName("PostgreSQL DELETE - Single Row")
public void testSingleRowDelete() throws Exception {
    // Insert row
    executePostgresSQL(
        "INSERT INTO users (id, email) VALUES (1, 'delete@example.com')"
    );
    Thread.sleep(5000);
    
    // Verify row exists
    ResultSet rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM users FINAL WHERE id = 1"
    );
    Assert.assertEquals(1, rs.getInt(1));
    
    // Delete row
    executePostgresSQL("DELETE FROM users WHERE id = 1");
    Thread.sleep(5000);
    
    // Verify row NOT visible with FINAL
    rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM users FINAL WHERE id = 1"
    );
    Assert.assertEquals(0, rs.getInt(1));
    
    // Verify soft-delete marker set
    rs = executeClickHouseQuery(
        "SELECT _sign FROM users WHERE id = 1 ORDER BY _version DESC LIMIT 1"
    );
    Assert.assertEquals(-1, rs.getInt("_sign"));
}
```

**Scenario 2: Batch DELETE**

```java
@Test
@DisplayName("PostgreSQL DELETE - Batch Delete")
public void testBatchDelete() throws Exception {
    // Insert 100 rows
    for (int i = 1; i <= 100; i++) {
        executePostgresSQL(
            "INSERT INTO logs (id, message) VALUES (" + i + ", 'Log " + i + "')"
        );
    }
    Thread.sleep(10000);
    
    // Delete old logs (first 50)
    executePostgresSQL("DELETE FROM logs WHERE id <= 50");
    Thread.sleep(10000);
    
    // Verify only 50 rows remain
    ResultSet rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM logs FINAL"
    );
    Assert.assertEquals(50, rs.getInt(1));
    
    // Verify deleted rows have _sign = -1
    rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM logs WHERE id <= 50 AND _sign = -1"
    );
    Assert.assertEquals(50, rs.getInt(1));
}
```

**Scenario 3: DELETE with WHERE clause**

```java
@Test
@DisplayName("PostgreSQL DELETE - Conditional Delete")
public void testConditionalDelete() throws Exception {
    executePostgresSQL(
        "INSERT INTO orders (id, status) VALUES " +
        "(1, 'pending'), (2, 'completed'), (3, 'pending'), (4, 'shipped')"
    );
    Thread.sleep(5000);
    
    // Delete only pending orders
    executePostgresSQL("DELETE FROM orders WHERE status = 'pending'");
    Thread.sleep(5000);
    
    // Verify 2 deleted, 2 remain
    ResultSet rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM orders FINAL"
    );
    Assert.assertEquals(2, rs.getInt(1));
    
    rs = executeClickHouseQuery(
        "SELECT status FROM orders FINAL ORDER BY id"
    );
    // Should return: 'completed', 'shipped'
    Assert.assertEquals("completed", rs.getString("status"));
    rs.next();
    Assert.assertEquals("shipped", rs.getString("status"));
}
```

**Scenario 4: DELETE then INSERT same ID (resurrection)**

```java
@Test
@DisplayName("PostgreSQL DELETE - Delete and Re-Insert Same ID")
public void testDeleteAndReinsert() throws Exception {
    // Insert
    executePostgresSQL(
        "INSERT INTO users (id, email) VALUES (1, 'first@example.com')"
    );
    Thread.sleep(5000);
    
    // Delete
    executePostgresSQL("DELETE FROM users WHERE id = 1");
    Thread.sleep(5000);
    
    // Re-insert same ID with different data
    executePostgresSQL(
        "INSERT INTO users (id, email) VALUES (1, 'second@example.com')"
    );
    Thread.sleep(5000);
    
    // Verify latest email visible
    ResultSet rs = executeClickHouseQuery(
        "SELECT email FROM users FINAL WHERE id = 1"
    );
    Assert.assertEquals("second@example.com", rs.getString("email"));
    
    // Verify only 1 row with FINAL
    rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM users FINAL WHERE id = 1"
    );
    Assert.assertEquals(1, rs.getInt(1));
}
```

#### 2.2.3 Expected Behavior & Validation

**Expected ClickHouse Behavior**:
1. DELETE creates new row with `_sign = -1`
2. FINAL clause excludes rows with `_sign = -1`
3. Version still increments on DELETE
4. Re-inserting same ID works correctly
5. Soft-deleted rows eventually removed by OPTIMIZE

**Validation Queries**:
```sql
-- Check soft-delete marker
SELECT id, _sign, _version FROM users WHERE id = 1 ORDER BY _version DESC;

-- Check FINAL excludes deleted
SELECT COUNT(*) FROM users FINAL WHERE id = 1;
-- Should be 0 if deleted

-- Check resurrection works
SELECT email, _sign FROM users WHERE id = 1 ORDER BY _version DESC;
-- Should show: sign=1 (latest insert), sign=-1 (delete), sign=1 (original insert)
```

---

### 2.3 TRUNCATE Operations (HIGH ⚠️)

#### 2.3.1 Current Status

**Test Location**: [`PostgresInitialDockerWKeeperMapStorageIT.java:162`](sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresInitialDockerWKeeperMapStorageIT.java:162)

**Current Code**:
```java
// Check if the clickhouse table is empty.
chRs = writer.getConnection().prepareStatement("select count(*) from public.tm").executeQuery();
while(chRs.next()) {
    tmCount =  chRs.getInt(1);
}

//Assert.assertTrue(tmCount == 0);  // ❌ COMMENTED OUT
```

**Why This is Problematic**:
1. Test exists but validation is disabled
2. No documentation on why assertion was commented out
3. Unknown if TRUNCATE works correctly
4. Production risk: TRUNCATE might fail silently

#### 2.3.2 TRUNCATE Test Fix Required

**Immediate Action**:
```java
@Test
@DisplayName("PostgreSQL TRUNCATE - Verify Table Emptied")
public void testTruncate() throws Exception {
    // Insert data
    executePostgresSQL(
        "INSERT INTO test_table (id, name) VALUES " +
        "(1, 'Row 1'), (2, 'Row 2'), (3, 'Row 3')"
    );
    Thread.sleep(5000);
    
    // Verify data replicated
    ResultSet rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM test_table FINAL"
    );
    Assert.assertEquals(3, rs.getInt(1));
    
    // Execute TRUNCATE
    executePostgresSQL("TRUNCATE TABLE test_table");
    Thread.sleep(5000);
    
    // Verify table empty in ClickHouse
    rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM test_table FINAL"
    );
    Assert.assertEquals(0, rs.getInt(1)); // ✅ RE-ENABLE THIS ASSERTION
    
    // Verify all rows have _sign = -1
    rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM test_table WHERE _sign = -1"
    );
    Assert.assertEquals(3, rs.getInt(1));
}
```

**Investigation Needed**:
1. Why was original assertion commented out?
2. Does TRUNCATE send individual DELETE events or single TRUNCATE event?
3. How does Debezium represent TRUNCATE in CDC stream?
4. Does current connector code handle TRUNCATE events?

**Root Cause Hypotheses**:
- TRUNCATE might not be replicated by Debezium (plugin limitation)
- TRUNCATE might arrive as batch DELETE events
- Timing issue: TRUNCATE processes slower than expected
- Bug in connector TRUNCATE handling

---

### 2.4 Data Type Coverage (HIGH 🟡)

#### 2.4.1 Current Coverage

**Tested Types** (~30% coverage):
- ✅ UUID
- ✅ JSONB
- ✅ NUMERIC(21,5)
- ✅ TIMESTAMPTZ
- ✅ TEXT / VARCHAR
- ✅ BOOLEAN
- ✅ BIGINT / BIGSERIAL
- ✅ INT

**Missing Types** (~70% - see [`05-data-types-coverage.md`](05-data-types-coverage.md)):

**High Priority Missing Types**:
1. **ARRAY Types** (Common in production):
   - INTEGER[]
   - TEXT[]
   - UUID[]
   - JSONB[]

2. **Date/Time Types**:
   - DATE
   - TIME
   - TIME WITH TIME ZONE
   - INTERVAL

3. **Numeric Types**:
   - SMALLINT
   - REAL
   - DOUBLE PRECISION
   - DECIMAL (various precisions)

4. **Network Types**:
   - INET
   - CIDR
   - MACADDR

5. **Specialized Types**:
   - BYTEA
   - HSTORE
   - XML
   - INT4RANGE, TSTZRANGE

#### 2.4.2 Enhanced Test SQL Required

**Add to [`init_postgres.sql`](sink-connector-lightweight/src/test/resources/init_postgres.sql)**:

```sql
-- Array types test table
CREATE TABLE array_types_test (
    id SERIAL PRIMARY KEY,
    int_array INTEGER[],
    text_array TEXT[],
    uuid_array UUID[],
    bool_array BOOLEAN[],
    numeric_array NUMERIC(10,2)[]
);

INSERT INTO array_types_test VALUES (
    1,
    ARRAY[1, 2, 3, 4, 5],
    ARRAY['apple', 'banana', 'cherry'],
    ARRAY['a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid, 'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12'::uuid],
    ARRAY[true, false, true],
    ARRAY[10.50, 20.75, 30.00]
);

-- Date/Time types test table
CREATE TABLE datetime_types_test (
    id SERIAL PRIMARY KEY,
    col_date DATE,
    col_time TIME,
    col_timetz TIME WITH TIME ZONE,
    col_timestamp TIMESTAMP,
    col_timestamptz TIMESTAMP WITH TIME ZONE,
    col_interval INTERVAL
);

INSERT INTO datetime_types_test VALUES (
    1,
    '2024-01-15',
    '14:30:00',
    '14:30:00+00',
    '2024-01-15 14:30:00',
    '2024-01-15 14:30:00+00',
    '1 year 2 months 3 days 4 hours 5 minutes'
);

-- Network types test table
CREATE TABLE network_types_test (
    id SERIAL PRIMARY KEY,
    col_inet INET,
    col_cidr CIDR,
    col_macaddr MACADDR
);

INSERT INTO network_types_test VALUES (
    1,
    '192.168.1.5',
    '192.168.1.0/24',
    '08:00:2b:01:02:03'
);

-- Special types test table
CREATE TABLE special_types_test (
    id SERIAL PRIMARY KEY,
    col_bytea BYTEA,
    col_xml XML,
    col_hstore HSTORE,
    col_int4range INT4RANGE,
    col_tstzrange TSTZRANGE
);

INSERT INTO special_types_test VALUES (
    1,
    E'\\xDEADBEEF',
    '<root><item>Test</item></root>',
    'key1=>value1, key2=>value2',
    '[1, 10)',
    '[2024-01-01 00:00:00+00, 2024-12-31 23:59:59+00)'
);
```

#### 2.4.3 Data Type Test Class Needed

```java
@Test
@DisplayName("PostgreSQL Data Types - Array Types")
public void testArrayTypes() throws Exception {
    Thread.sleep(10000); // Wait for initial snapshot
    
    ResultSet rs = executeClickHouseQuery(
        "SELECT int_array, text_array FROM array_types_test FINAL WHERE id = 1"
    );
    
    // Verify integer array
    Assert.assertEquals("[1,2,3,4,5]", rs.getString("int_array"));
    
    // Verify text array  
    Assert.assertEquals("['apple','banana','cherry']", rs.getString("text_array"));
}
```

---

### 2.5 Batch Operations & Scale Testing (MEDIUM 🟠)

#### 2.5.1 Current Limitations

**Current Test Scale**:
- Most tests: 2-40 rows
- Max observed: ~40 rows in [`init_postgres.sql`](sink-connector-lightweight/src/test/resources/init_postgres.sql)

**Production Reality**:
- Bulk imports: 100K+ rows
- Batch updates: 10K+ rows
- Mass deletes: 1K+ rows

**Untested Scenarios**:
1. INSERT 100K rows in single transaction
2. UPDATE 50K rows
3. DELETE 10K rows
4. Concurrent operations (multiple connections)
5. Replication lag under load
6. Memory usage during large batch
7. ClickHouse buffer overflow handling

#### 2.5.2 Scale Test Requirements

```java
@Test
@DisplayName("PostgreSQL Batch - Large Insert")
public void testLargeInsert() throws Exception {
    // Insert 100,000 rows
    executePostgresSQL("BEGIN");
    for (int i = 1; i <= 100000; i++) {
        if (i % 1000 == 0) {
            executePostgresSQL("COMMIT; BEGIN");
        }
        executePostgresSQL(
            "INSERT INTO large_test (id, data) VALUES (" + i + ", 'Data " + i + "')"
        );
    }
    executePostgresSQL("COMMIT");
    
    // Wait for replication (may take minutes)
    Thread.sleep(60000);
    
    // Verify all rows replicated
    ResultSet rs = executeClickHouseQuery(
        "SELECT COUNT(*) FROM large_test FINAL"
    );
    Assert.assertEquals(100000, rs.getInt(1));
    
    // Verify no duplicates
    rs = executeClickHouseQuery(
        "SELECT id, COUNT(*) FROM large_test FINAL GROUP BY id HAVING COUNT(*) > 1"
    );
    Assert.assertFalse(rs.next()); // No duplicates
}
```

---

## 3. Immediate Action Items

### 3.1 Fix TRUNCATE Test (Priority: CRITICAL)

**File**: [`PostgresInitialDockerWKeeperMapStorageIT.java`](sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresInitialDockerWKeeperMapStorageIT.java)

**Action**:
1. Investigate why assertion at line 162 was commented out
2. Determine if TRUNCATE is supported by Debezium PostgreSQL connector
3. If supported: Re-enable assertion and fix underlying issue
4. If not supported: Document limitation and remove test
5. Add proper TRUNCATE test to dedicated test class

**Timeline**: Immediate (1-2 days)

---

### 3.2 Create UPDATE Test Class (Priority: CRITICAL)

**New File**: `PostgresUpdateIT.java`

**Required Tests**:
1. `testSingleRowUpdate()` - Update single row, verify version increment
2. `testMultiColumnUpdate()` - Update multiple columns simultaneously
3. `testUpdateToNull()` - Set columns to NULL
4. `testBatchUpdate()` - Update 1000+ rows
5. `testPartialUpdate()` - UPDATE with WHERE clause
6. `testConcurrentUpdates()` - Multiple connections updating simultaneously
7. `testUpdateAllDataTypes()` - Update each supported data type

**Timeline**: High priority (1 week)

---

### 3.3 Create DELETE Test Class (Priority: CRITICAL)

**New File**: `PostgresDeleteIT.java`

**Required Tests**:
1. `testSingleRowDelete()` - Delete single row, verify soft-delete
2. `testBatchDelete()` - Delete 100+ rows
3. `testConditionalDelete()` - DELETE with WHERE clause
4. `testDeleteAndReinsert()` - Delete then re-insert same ID
5. `testDeleteAllRows()` - DELETE FROM table (all rows)
6. `testSoftDeleteVerification()` - Verify _sign = -1 set correctly

**Timeline**: High priority (1 week)

---

### 3.4 Expand Data Type Test Coverage (Priority: HIGH)

**File**: Enhanced [`init_postgres.sql`](sink-connector-lightweight/src/test/resources/init_postgres.sql)

**New File**: `PostgresDataTypeCoverageIT.java`

**Required Tests**:
1. Array types (INTEGER[], TEXT[], UUID[], BOOLEAN[])
2. Date/Time types (DATE, TIME, INTERVAL)
3. Network types (INET, CIDR, MACADDR)
4. Special types (BYTEA, HSTORE, XML, ranges)
5. Edge cases (NULL arrays, empty arrays, max precision)

**Timeline**: Medium priority (2 weeks)

---

### 3.5 Add Scale/Performance Tests (Priority: MEDIUM)

**New File**: `PostgresScaleIT.java`

**Required Tests**:
1. Large INSERT (100K rows)
2. Large UPDATE (50K rows)
3. Large DELETE (10K rows)
4. Concurrent operations
5. Replication lag measurement
6. Memory usage monitoring

**Timeline**: Lower priority (3 weeks)

---

## 4. Test Implementation Specifications

### 4.1 Docker TestContainers Setup Pattern

**Base Test Class**:
```java
public abstract class PostgresBaseIT {
    
    @Container
    public static ClickHouseContainer clickHouseContainer = 
        new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);
    
    @Container
    public static PostgreSQLContainer postgreSQLContainer = 
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withInitScript("init_postgres_extended.sql") // NEW EXTENDED SQL
            .withDatabaseName("testdb")
            .withUsername("postgres")
            .withPassword("postgres")
            .withCommand("postgres -c wal_level=logical")
            .withExposedPorts(5432);
    
    protected static BaseDbWriter clickhouseWriter;
    protected static Connection postgresConnection;
    protected static DebeziumChangeEventCapture debeziumEngine;
    
    @BeforeAll
    public static void setup() throws Exception {
        Network network = Network.newNetwork();
        postgreSQLContainer.withNetwork(network).start();
        clickHouseContainer.withNetwork(network).start();
        
        // Initialize connections
        clickhouseWriter = ITCommon.getDBWriter(clickHouseContainer);
        postgresConnection = DriverManager.getConnection(
            postgreSQLContainer.getJdbcUrl(),
            postgreSQLContainer.getUsername(),
            postgreSQLContainer.getPassword()
        );
        
        // Start Debezium CDC
        Properties props = getDebeziumProperties();
        debeziumEngine = new DebeziumChangeEventCapture();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                debeziumEngine.setup(props, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        
        Thread.sleep(10000); // Wait for CDC initialization
    }
    
    protected void executePostgresSQL(String sql) throws SQLException {
        try (Statement stmt = postgresConnection.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    protected ResultSet executeClickHouseQuery(String sql) throws SQLException {
        return clickhouseWriter.getConnection().prepareStatement(sql).executeQuery();
    }
    
    @AfterAll
    public static void teardown() {
        if (debeziumEngine != null) {
            debeziumEngine.stop();
        }
        HikariDbSource.close();
    }
}
```

### 4.2 Test Execution Pattern

**Standard Test Flow**:
```java
@Test
@DisplayName("Test Description")
public void testOperation() throws Exception {
    // 1. ARRANGE: Insert initial data
    executePostgresSQL("INSERT INTO table ...");
    Thread.sleep(5000); // Wait for CDC replication
    
    // 2. ACT: Perform operation (UPDATE/DELETE)
    executePostgresSQL("UPDATE table ...");
    Thread.sleep(5000); // Wait for CDC replication
    
    // 3. ASSERT: Verify results in ClickHouse
    ResultSet rs = executeClickHouseQuery("SELECT * FROM table FINAL ...");
    Assert.assertEquals(expectedValue, rs.getString("column"));
    
    // 4. VERIFY: Additional checks (version, sign, counts)
    rs = executeClickHouseQuery("SELECT _version, _sign FROM table WHERE id = ...");
    Assert.assertTrue(rs.getLong("_version") > initialVersion);
}
```

---

## 5. Risk Mitigation Strategies

### 5.1 Production Deployment Blockers

**DO NOT DEPLOY to production until**:
- [ ] All UPDATE tests passing (100% coverage)
- [ ] All DELETE tests passing (100% coverage)
- [ ] TRUNCATE test fixed or limitation documented
- [ ] Data type coverage > 80%
- [ ] Scale tests validate 10K+ row operations

### 5.2 Phased Testing Approach

**Phase 1: Critical Operations** (Week 1-2)
- Implement UPDATE tests
- Implement DELETE tests
- Fix TRUNCATE test

**Phase 2: Data Types** (Week 3-4)
- Add array type tests
- Add date/time type tests
- Add network type tests

**Phase 3: Scale & Performance** (Week 5-6)
- Add large batch tests
- Add concurrent operation tests
- Measure replication lag

**Phase 4: Edge Cases** (Week 7-8)
- Error recovery tests
- Connection failure tests
- Schema evolution tests

### 5.3 Safety Measures

**For Each Untested Operation in Production**:
1. **Monitor extensively**: Log all operations, track failures
2. **Start small**: Test with single table, limited rows
3. **Validate continuously**: Run checksum validation hourly
4. **Have rollback plan**: Ability to revert to snapshot
5. **Alert on anomalies**: Row count mismatches, version issues

---

## 6. Definition of "Fully Operational"

For PostgreSQL CDC to be legitimately claimed as "fully operational":

**✅ Functional Completeness**:
- [ ] INSERT verified (initial + CDC) ✅ DONE
- [ ] UPDATE verified (all scenarios) ❌ MISSING
- [ ] DELETE verified (all scenarios) ❌ MISSING
- [ ] TRUNCATE verified or documented as unsupported ⚠️ BROKEN
- [ ] DDL changes verified ✅ DONE

**✅ Data Type Coverage**:
- [ ] 90%+ of PostgreSQL types tested (36+ of 40 types) ❌ Currently ~30%

**✅ Scale & Performance**:
- [ ] 10K+ row batches tested ❌ Currently max 40 rows
- [ ] Replication lag < 5 seconds under load ❓ Unknown
- [ ] Memory usage acceptable for large batches ❓ Unknown

**✅ Reliability**:
- [ ] Connection failure recovery tested ❌ Not tested
- [ ] Network partition handling tested ❌ Not tested
- [ ] Graceful degradation verified ❌ Not tested

**Current Score**: **~40% Operational**

---

## 7. Recommended Timeline

### 7.1 Critical Path (Blocks Production)

| Week | Deliverable | Status |
|------|-------------|--------|
| Week 1 | Fix TRUNCATE test, Implement basic UPDATE tests | 🔴 Critical |
| Week 2 | Complete UPDATE test coverage, Basic DELETE tests | 🔴 Critical |
| Week 3 | Complete DELETE test coverage, Data type expansion | 🟡 High |
| Week 4 | Array/network types, Scale test foundation | 🟡 High |

### 7.2 Full Coverage (Production Ready)

| Week | Deliverable | Status |
|------|-------------|--------|
| Week 5 | Large batch tests, Performance benchmarks | 🟠 Medium |
| Week 6 | Error recovery tests, Edge cases | 🟠 Medium |
| Week 7 | Documentation, Production validation | 🟢 Low |
| Week 8 | Production deployment preparation | 🟢 Low |

---

## 8. Success Metrics

**Test Coverage Metrics**:
- Total test count: Target 50+ tests (currently ~6)
- Operation coverage: 100% (INSERT ✅, UPDATE ❌, DELETE ❌, TRUNCATE ⚠️)
- Data type coverage: 90%+ (currently ~30%)
- Test assertions: 200+ (currently ~20)

**Quality Metrics**:
- All tests passing: 100%
- No commented-out assertions
- All edge cases covered
- Performance benchmarks met

**Production Readiness**:
- Zero critical bugs
- Documentation complete
- Runbook created
- Team trained

---

## 9. Conclusion

The current PostgreSQL CDC connector test suite has **critical gaps** that prevent it from being production-ready:

1. **UPDATE operations are completely untested** - This is the most critical gap
2. **DELETE operations are completely untested** - Data compliance risk
3. **TRUNCATE test is broken** - Unknown functionality
4. **70% of data types are untested** - Type conversion bugs likely

**Recommendation**: **DO NOT claim "fully operational" status** until at minimum UPDATE and DELETE tests are implemented and passing.

**Estimated Work**: 4-6 weeks to achieve true "fully operational" status with comprehensive test coverage.

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-27  
**Author**: Technical Architecture Team  
**Status**: 🔴 CRITICAL GAPS IDENTIFIED - BLOCKS PRODUCTION DEPLOYMENT
