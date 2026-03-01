# PostgreSQL Operations Verification Plan

## Executive Summary

This document provides detailed verification procedures for all PostgreSQL operations supported by the clickhouse-sink-connector. It covers both batch dump/load operations and CDC replication operations (INSERT, UPDATE, DELETE, TRUNCATE, DDL changes).

**Purpose**: Ensure 100% correctness and reliability of all PostgreSQL data operations.

**Scope**: Complete verification methodology for batch dumps and CDC replication.

---

## 1. Verification Framework

### 1.1 Verification Levels

```
Level 1: Syntax Verification
    └── DDL/DML executes without errors

Level 2: Data Presence Verification
    └── Data appears in ClickHouse

Level 3: Data Correctness Verification
    └── Values match source exactly

Level 4: Semantic Verification
    └── Business logic preserved (PKs, constraints, etc.)

Level 5: Performance Verification
    └── Operations complete within SLA
```

### 1.2 Verification Tools

| Tool | Purpose | Location |
|------|---------|----------|
| PostgreSQL psql | Execute source operations | System |
| ClickHouse client | Query destination data | System |
| Checksum tools | Validate data integrity | [`postgres_table_checksum.py`](sink-connector/python/db_compare/postgres_table_checksum.py) |
| Row count tools | Validate counts | [`postgres_table_count.py`](sink-connector/python/db_compare/postgres_table_count.py) |
| Custom validators | Operation-specific checks | Test suite |

---

## 2. Batch Dump Operations Verification

### 2.1 Schema Dump Verification

#### 2.1.1 Test Scenario: Simple Table

**PostgreSQL Source**:
```sql
CREATE TABLE public.users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

**Expected ClickHouse DDL**:
```sql
CREATE TABLE public.users (
    id Int32,
    email String,
    created_at DateTime64(6, 'UTC'),
    _sign Int8 DEFAULT 1,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
ORDER BY id;
```

**Verification Steps**:
1. Dump schema: `python db_dump/postgres_dumper.py --schema_only ...`
2. Load schema: `python db_load/clickhouse_loader.py --schema_only ...`
3. Verify table exists: `SHOW TABLES FROM public`
4. Verify columns:
   ```sql
   DESCRIBE TABLE public.users;
   ```
5. Verify engine:
   ```sql
   SELECT engine FROM system.tables WHERE name = 'users';
   ```

**Expected Results**:
- ✅ Table created in ClickHouse
- ✅ All columns present with correct types
- ✅ Virtual columns (_sign, _version) added
- ✅ Engine is ReplacingMergeTree
- ✅ ORDER BY matches primary key

#### 2.1.2 Test Scenario: Complex Table with Constraints

**PostgreSQL Source**:
```sql
CREATE TABLE public.orders (
    order_id BIGSERIAL PRIMARY KEY,
    customer_id UUID NOT NULL,
    order_date DATE NOT NULL,
    total_amount NUMERIC(10,2) CHECK (total_amount > 0),
    status VARCHAR(50) DEFAULT 'pending',
    metadata JSONB,
    tags TEXT[],
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT customer_fk FOREIGN KEY (customer_id) 
        REFERENCES customers(id) ON DELETE CASCADE
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_date ON orders(order_date);
```

**Expected ClickHouse DDL**:
```sql
CREATE TABLE public.orders (
    order_id Int64,
    customer_id UUID,
    order_date Date32,
    total_amount Decimal(10,2),
    status Nullable(String),
    metadata Nullable(String),
    tags Array(String),
    created_at DateTime64(6, 'UTC'),
    updated_at DateTime64(6, 'UTC'),
    _sign Int8 DEFAULT 1,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
ORDER BY order_id;
```

**Verification Steps**:
1. Dump and load schema
2. Verify all columns and types
3. Verify PRIMARY KEY becomes ORDER BY
4. Verify CHECK constraint not replicated (ClickHouse doesn't support)
5. Verify FOREIGN KEY not replicated (documented limitation)
6. Verify DEFAULT values not replicated (except virtual columns)
7. Verify indexes not replicated (ClickHouse uses different indexing)

**Expected Results**:
- ✅ All columns created with correct ClickHouse types
- ✅ Array type preserved
- ✅ JSONB stored as String
- ✅ Nullable applied correctly
- ⚠️ Constraints not enforced in ClickHouse (document this)
- ⚠️ Indexes not created (ClickHouse has different approach)

### 2.2 Data Dump Verification

#### 2.2.1 Test Scenario: Small Dataset (1K rows)

**Setup**:
```sql
-- PostgreSQL
CREATE TABLE public.products (
    product_id SERIAL PRIMARY KEY,
    name VARCHAR(255),
    price NUMERIC(10,2),
    in_stock BOOLEAN
);

INSERT INTO products (name, price, in_stock)
SELECT 
    'Product ' || i,
    (RANDOM() * 1000)::NUMERIC(10,2),
    (RANDOM() > 0.5)
FROM generate_series(1, 1000) i;
```

**Dump & Load**:
```bash
# Dump
python db_dump/postgres_dumper.py \
    --postgres_host localhost \
    --postgres_database testdb \
    --dump_dir /tmp/dumps

# Load
python db_load/clickhouse_loader.py \
    --clickhouse_host localhost \
    --clickhouse_database testdb \
    --dump_dir /tmp/dumps
```

**Verification Queries**:

1. **Count Verification**:
   ```sql
   -- PostgreSQL
   SELECT COUNT(*) FROM public.products;
   -- Expected: 1000
   
   -- ClickHouse
   SELECT COUNT(*) FROM public.products FINAL;
   -- Expected: 1000
   ```

2. **Sum Verification**:
   ```sql
   -- PostgreSQL
   SELECT SUM(price) FROM public.products;
   
   -- ClickHouse
   SELECT SUM(price) FROM public.products FINAL;
   -- Should match PostgreSQL
   ```

3. **Sample Data Verification**:
   ```sql
   -- PostgreSQL
   SELECT * FROM public.products WHERE product_id = 1;
   
   -- ClickHouse
   SELECT * FROM public.products FINAL WHERE product_id = 1;
   -- All fields should match exactly
   ```

4. **Checksum Verification**:
   ```bash
   # PostgreSQL checksum
   python db_compare/postgres_table_checksum.py \
       --postgres_database testdb \
       --tables_regex '^products$'
   
   # ClickHouse checksum
   python db_compare/clickhouse_table_checksum.py \
       --clickhouse_database testdb \
       --tables_regex '^products$'
   
   # Compare checksums - should match
   ```

**Expected Results**:
- ✅ Row count matches exactly
- ✅ Aggregates (SUM, AVG) match
- ✅ Sample rows match field by field
- ✅ Checksums match
- ✅ All boolean values correct
- ✅ All numeric precision preserved

#### 2.2.2 Test Scenario: NULL Handling

**Setup**:
```sql
CREATE TABLE public.nulltest (
    id SERIAL PRIMARY KEY,
    col_nullable_int INTEGER,
    col_nullable_text TEXT,
    col_not_null_int INTEGER NOT NULL,
    col_nullable_timestamp TIMESTAMP WITH TIME ZONE
);

INSERT INTO nulltest (col_not_null_int, col_nullable_int, col_nullable_text, col_nullable_timestamp)
VALUES 
    (1, NULL, NULL, NULL),
    (2, 42, 'test', '2024-01-01 12:00:00+00'),
    (3, NULL, 'text only', NULL);
```

**Verification**:
```sql
-- ClickHouse
SELECT * FROM public.nulltest FINAL ORDER BY id;

-- Verify NULLs preserved
SELECT id, col_nullable_int IS NULL as is_null 
FROM public.nulltest FINAL 
WHERE id IN (1, 3);
-- Expected: is_null = 1 for both rows
```

**Expected Results**:
- ✅ NULL values preserved for nullable columns
- ✅ NOT NULL columns never contain NULL
- ✅ Null representation correct in ClickHouse

#### 2.2.3 Test Scenario: Special Characters & Encoding

**Setup**:
```sql
CREATE TABLE public.encoding_test (
    id SERIAL PRIMARY KEY,
    utf8_text TEXT,
    special_chars VARCHAR(255)
);

INSERT INTO encoding_test (utf8_text, special_chars) VALUES
    ('Hello World', 'Simple ASCII'),
    ('你好世界', 'Chinese Characters'),
    ('Привет мир', 'Cyrillic'),
    ('🎉🎊🎈', 'Emojis'),
    ('Line1
Line2', 'Newline'),
    ('Tab	Separated', 'Tab character'),
    ('Quote"Test', 'Double quotes'),
    ('It''s a test', 'Single quote escaped'),
    ('Backslash \ test', 'Backslash'),
    (NULL, 'NULL value test');
```

**Verification**:
```sql
-- ClickHouse
SELECT * FROM public.encoding_test FINAL ORDER BY id;

-- Verify each special case
SELECT id, utf8_text FROM public.encoding_test FINAL WHERE id = 2;
-- Expected: '你好世界'

SELECT id, special_chars FROM public.encoding_test FINAL WHERE id = 4;
-- Expected: 'Double quotes' with proper quote handling
```

**Expected Results**:
- ✅ UTF-8 characters preserved
- ✅ Special characters not corrupted
- ✅ Emojis rendered correctly
- ✅ Newlines and tabs preserved
- ✅ Quotes escaped properly

---

## 3. CDC Replication Operations Verification

### 3.1 INSERT Operations

#### 3.1.1 Test Scenario: Single Row Insert

**Setup**:
```sql
-- Start CDC replication first
-- Then create table
CREATE TABLE public.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL,
    email VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

**Operation**:
```sql
INSERT INTO public.users (username, email)
VALUES ('john_doe', 'john@example.com');
```

**Verification Steps**:

1. **Wait for replication** (5-10 seconds)

2. **Check row exists**:
   ```sql
   -- ClickHouse
   SELECT * FROM public.users FINAL WHERE username = 'john_doe';
   ```

3. **Verify all fields**:
   ```sql
   SELECT 
       id,
       username,
       email,
       created_at,
       _sign,
       _version
   FROM public.users FINAL WHERE username = 'john_doe';
   ```

4. **Verify virtual columns**:
   ```sql
   SELECT _sign, _version 
   FROM public.users 
   WHERE username = 'john_doe';
   ```

**Expected Results**:
- ✅ Row appears in ClickHouse
- ✅ All field values match PostgreSQL
- ✅ UUID preserved correctly
- ✅ Timestamp preserved with timezone
- ✅ `_sign = 1` (insert)
- ✅ `_version` is set

#### 3.1.2 Test Scenario: Batch Insert

**Operation**:
```sql
INSERT INTO public.users (username, email)
SELECT 
    'user_' || i,
    'user' || i || '@example.com'
FROM generate_series(1, 1000) i;
```

**Verification**:
```sql
-- PostgreSQL count
SELECT COUNT(*) FROM public.users;

-- ClickHouse count
SELECT COUNT(*) FROM public.users FINAL;

-- Checksum verification
-- Run checksum tools and compare
```

**Expected Results**:
- ✅ All 1000 rows replicated
- ✅ Row count matches
- ✅ Checksum matches
- ✅ Replication lag < 30 seconds

#### 3.1.3 Test Scenario: Insert with All Data Types

**Setup**:
```sql
CREATE TABLE public.all_types (
    id SERIAL PRIMARY KEY,
    col_uuid UUID,
    col_boolean BOOLEAN,
    col_smallint SMALLINT,
    col_integer INTEGER,
    col_bigint BIGINT,
    col_numeric NUMERIC(21,5),
    col_real REAL,
    col_double DOUBLE PRECISION,
    col_varchar VARCHAR(255),
    col_text TEXT,
    col_bytea BYTEA,
    col_date DATE,
    col_timestamp TIMESTAMP,
    col_timestamptz TIMESTAMP WITH TIME ZONE,
    col_json JSON,
    col_jsonb JSONB,
    col_array_int INTEGER[],
    col_array_text TEXT[],
    col_inet INET,
    col_point POINT
);
```

**Operation**:
```sql
INSERT INTO public.all_types VALUES (
    DEFAULT,
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    TRUE,
    32767,
    2147483647,
    9223372036854775807,
    12345.67890,
    3.14159,
    3.141592653589793,
    'Test string',
    'Long text field',
    '\xDEADBEEF'::bytea,
    '2024-01-15',
    '2024-01-15 14:30:00',
    '2024-01-15 14:30:00+00',
    '{"key": "value"}',
    '{"nested": {"data": true}}',
    ARRAY[1,2,3,4,5],
    ARRAY['one', 'two', 'three'],
    '192.168.1.1',
    POINT(1.5, 2.5)
);
```

**Verification**:
```sql
-- ClickHouse - verify each type
SELECT 
    col_uuid,
    col_boolean,
    col_numeric,
    col_jsonb,
    col_array_int,
    col_timestamptz
FROM public.all_types FINAL;
```

**Expected Results**:
- ✅ UUID: `a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11`
- ✅ Boolean: `1` or `true`
- ✅ Numeric: `12345.67890`
- ✅ JSONB: `{"nested": {"data": true}}`
- ✅ Array: `[1,2,3,4,5]`
- ✅ Timestamp with TZ preserved

### 3.2 UPDATE Operations

#### 3.2.1 Test Scenario: Single Column Update

**Setup**:
```sql
-- Insert initial data
INSERT INTO public.users (id, username, email, created_at)
VALUES (
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    'alice',
    'alice@old.com',
    '2024-01-01 12:00:00+00'
);
```

**Operation**:
```sql
UPDATE public.users 
SET email = 'alice@new.com'
WHERE username = 'alice';
```

**Verification**:

1. **Check updated value**:
   ```sql
   -- ClickHouse
   SELECT username, email FROM public.users FINAL 
   WHERE username = 'alice';
   ```
   Expected: `email = 'alice@new.com'`

2. **Check version history**:
   ```sql
   -- ClickHouse (without FINAL)
   SELECT username, email, _sign, _version 
   FROM public.users 
   WHERE username = 'alice'
   ORDER BY _version;
   ```
   Expected: 2 rows (old version with _sign=1, new version with _sign=1)

3. **Verify old value is not in FINAL**:
   ```sql
   SELECT COUNT(*) FROM public.users FINAL WHERE email = 'alice@old.com';
   ```
   Expected: 0

**Expected Results**:
- ✅ Email updated to new value
- ✅ FINAL query shows only new value
- ✅ Version history preserved (without FINAL)
- ✅ `_version` incremented

#### 3.2.2 Test Scenario: Multi-Column Update

**Operation**:
```sql
UPDATE public.users 
SET 
    email = 'alice@newest.com',
    username = 'alice_smith'
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';
```

**Verification**:
```sql
SELECT username, email FROM public.users FINAL 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';
```

**Expected Results**:
- ✅ Both columns updated
- ✅ Values match PostgreSQL exactly

#### 3.2.3 Test Scenario: Update to NULL

**Operation**:
```sql
UPDATE public.users 
SET email = NULL
WHERE username = 'alice_smith';
```

**Verification**:
```sql
SELECT username, email, email IS NULL as is_null 
FROM public.users FINAL 
WHERE username = 'alice_smith';
```

**Expected Results**:
- ✅ Email is NULL
- ✅ is_null = 1

#### 3.2.4 Test Scenario: Batch Update

**Operation**:
```sql
UPDATE public.users 
SET email = LOWER(email)
WHERE email IS NOT NULL;
```

**Verification**:
```sql
-- Count affected rows
SELECT COUNT(*) FROM public.users FINAL WHERE email IS NOT NULL;

-- Verify lowercase
SELECT username, email FROM public.users FINAL LIMIT 10;
```

**Expected Results**:
- ✅ All emails lowercased
- ✅ NULL emails unchanged
- ✅ Row count matches

### 3.3 DELETE Operations

#### 3.3.1 Test Scenario: Single Row Delete

**Setup**:
```sql
INSERT INTO public.users (id, username, email)
VALUES ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'bob', 'bob@example.com');
```

**Operation**:
```sql
DELETE FROM public.users WHERE username = 'bob';
```

**Verification**:

1. **Check row not in FINAL**:
   ```sql
   -- ClickHouse
   SELECT COUNT(*) FROM public.users FINAL WHERE username = 'bob';
   ```
   Expected: 0

2. **Check deletion marker**:
   ```sql
   -- ClickHouse (without FINAL)
   SELECT username, _sign FROM public.users WHERE username = 'bob';
   ```
   Expected: Row exists with `_sign = -1`

3. **Verify PostgreSQL deletion**:
   ```sql
   -- PostgreSQL
   SELECT COUNT(*) FROM public.users WHERE username = 'bob';
   ```
   Expected: 0

**Expected Results**:
- ✅ Row not visible in FINAL query
- ✅ Deletion marker present (_sign = -1)
- ✅ PostgreSQL and ClickHouse consistent

#### 3.3.2 Test Scenario: Batch Delete

**Operation**:
```sql
DELETE FROM public.users 
WHERE created_at < '2024-01-01';
```

**Verification**:
```sql
-- Count remaining rows
SELECT COUNT(*) FROM public.users FINAL;

-- Verify deleted rows have _sign = -1
SELECT COUNT(*) FROM public.users WHERE _sign = -1;
```

**Expected Results**:
- ✅ Correct number of rows deleted
- ✅ All deleted rows marked with _sign = -1
- ✅ Remaining rows correct

#### 3.3.3 Test Scenario: Delete with Foreign Key Cascade

**Setup**:
```sql
CREATE TABLE public.customers (
    id UUID PRIMARY KEY,
    name VARCHAR(255)
);

CREATE TABLE public.orders (
    id BIGSERIAL PRIMARY KEY,
    customer_id UUID REFERENCES customers(id) ON DELETE CASCADE,
    amount NUMERIC(10,2)
);

INSERT INTO customers VALUES ('c1111111-1111-1111-1111-111111111111', 'Customer 1');
INSERT INTO orders VALUES (1, 'c1111111-1111-1111-1111-111111111111', 100.00);
INSERT INTO orders VALUES (2, 'c1111111-1111-1111-1111-111111111111', 200.00);
```

**Operation**:
```sql
DELETE FROM public.customers WHERE id = 'c1111111-1111-1111-1111-111111111111';
-- Cascade should delete orders
```

**Verification**:
```sql
-- ClickHouse
SELECT COUNT(*) FROM public.customers FINAL;
-- Expected: 0

SELECT COUNT(*) FROM public.orders FINAL;
-- Expected: 0 (cascade deletion replicated)
```

**Expected Results**:
- ✅ Parent row deleted
- ✅ Child rows deleted (cascade)
- ✅ All deletions replicated to ClickHouse

### 3.4 TRUNCATE Operations

#### 3.4.1 Test Scenario: TRUNCATE TABLE

**Setup**:
```sql
CREATE TABLE public.logs (
    id BIGSERIAL PRIMARY KEY,
    message TEXT,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

INSERT INTO logs (message)
SELECT 'Log entry ' || i FROM generate_series(1, 1000) i;
```

**Operation**:
```sql
TRUNCATE TABLE public.logs;
```

**Verification**:

1. **PostgreSQL count**:
   ```sql
   SELECT COUNT(*) FROM public.logs;
   ```
   Expected: 0

2. **ClickHouse FINAL count**:
   ```sql
   SELECT COUNT(*) FROM public.logs FINAL;
   ```
   Expected: 0

3. **Check deletion markers**:
   ```sql
   SELECT COUNT(*) FROM public.logs WHERE _sign = -1;
   ```
   Expected: 1000 (all rows marked deleted)

**Expected Results**:
- ✅ All rows deleted in PostgreSQL
- ✅ All rows marked deleted in ClickHouse (_sign = -1)
- ✅ FINAL query returns 0 rows
- ✅ Table structure preserved

#### 3.4.2 Test Scenario: TRUNCATE with RESTART IDENTITY

**Operation**:
```sql
-- Re-populate
INSERT INTO logs (message) VALUES ('New log');

TRUNCATE TABLE public.logs RESTART IDENTITY;

-- Insert after truncate
INSERT INTO logs (message) VALUES ('After truncate');
SELECT id FROM logs;
```

**Verification**:
```sql
-- ClickHouse
SELECT id, message FROM public.logs FINAL ORDER BY id;
```

**Expected Results**:
- ✅ TRUNCATE replicated
- ✅ New insert has id = 1 (identity restarted)
- ✅ Data consistent

### 3.5 DDL Change Operations

#### 3.5.1 Test Scenario: ADD COLUMN

**Initial State**:
```sql
CREATE TABLE public.products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255)
);

INSERT INTO products (name) VALUES ('Product 1'), ('Product 2');
```

**DDL Change**:
```sql
ALTER TABLE public.products 
ADD COLUMN price NUMERIC(10,2) DEFAULT 0.00;
```

**Verification**:

1. **Check column added in ClickHouse**:
   ```sql
   DESCRIBE TABLE public.products;
   ```
   Expected: `price` column present

2. **Check existing data**:
   ```sql
   SELECT id, name, price FROM public.products FINAL;
   ```
   Expected: Existing rows have `price = 0.00` (default)

3. **Insert new row**:
   ```sql
   INSERT INTO products (name, price) VALUES ('Product 3', 99.99);
   
   -- ClickHouse
   SELECT * FROM public.products FINAL WHERE name = 'Product 3';
   ```
   Expected: `price = 99.99`

**Expected Results**:
- ✅ Column added to ClickHouse table
- ✅ Existing rows have default value
- ✅ New inserts replicate correctly

#### 3.5.2 Test Scenario: DROP COLUMN

**DDL Change**:
```sql
ALTER TABLE public.products DROP COLUMN price;
```

**Verification**:
```sql
-- ClickHouse
DESCRIBE TABLE public.products;
```

**Expected Results**:
- ✅ Column removed from ClickHouse
- ⚠️ Or column preserved but not updated (depends on configuration)

**Note**: Document behavior of DROP COLUMN

#### 3.5.3 Test Scenario: RENAME COLUMN

**DDL Change**:
```sql
ALTER TABLE public.products 
RENAME COLUMN name TO product_name;
```

**Verification**:
```sql
-- ClickHouse
DESCRIBE TABLE public.products;

SELECT product_name FROM public.products FINAL;
```

**Expected Results**:
- ✅ Column renamed in ClickHouse
- ✅ Data still accessible

#### 3.5.4 Test Scenario: ALTER COLUMN TYPE

**DDL Change**:
```sql
ALTER TABLE public.products 
ALTER COLUMN product_name TYPE TEXT;
```

**Verification**:
```sql
-- ClickHouse
DESCRIBE TABLE public.products;
```

**Expected Results**:
- ✅ Column type updated (VARCHAR → String, no change needed)
- ⚠️ Document type change limitations

---

## 4. Transaction Handling Verification

### 4.1 Test Scenario: COMMIT Transaction

**Operation**:
```sql
BEGIN;
INSERT INTO public.users (username, email) VALUES ('tx_user1', 'tx1@example.com');
INSERT INTO public.users (username, email) VALUES ('tx_user2', 'tx2@example.com');
COMMIT;
```

**Verification**:
```sql
-- ClickHouse
SELECT COUNT(*) FROM public.users FINAL WHERE username LIKE 'tx_user%';
```

**Expected Results**:
- ✅ Both rows replicated
- ✅ Count = 2

### 4.2 Test Scenario: ROLLBACK Transaction

**Operation**:
```sql
BEGIN;
INSERT INTO public.users (username, email) VALUES ('rollback_user', 'rb@example.com');
ROLLBACK;
```

**Verification**:
```sql
-- PostgreSQL
SELECT COUNT(*) FROM public.users WHERE username = 'rollback_user';
-- Expected: 0

-- ClickHouse
SELECT COUNT(*) FROM public.users FINAL WHERE username = 'rollback_user';
-- Expected: 0 (rolled back data should not replicate)
```

**Expected Results**:
- ✅ Rolled back data NOT replicated
- ✅ ClickHouse consistent with PostgreSQL

---

## 5. Edge Cases & Error Scenarios

### 5.1 Large Object Handling

**Test Scenario**: Insert large TEXT/BYTEA values

**Setup**:
```sql
CREATE TABLE public.large_objects (
    id SERIAL PRIMARY KEY,
    large_text TEXT,
    large_binary BYTEA
);
```

**Operation**:
```sql
INSERT INTO large_objects (large_text, large_binary) VALUES (
    REPEAT('A', 10000000), -- 10MB text
    REPEAT('\xDEADBEEF'::bytea, 2500000) -- 10MB binary
);
```

**Verification**:
```sql
-- ClickHouse
SELECT 
    id,
    LENGTH(large_text) as text_length,
    LENGTH(large_binary) as binary_length
FROM public.large_objects FINAL;
```

**Expected Results**:
- ✅ Large values replicated
- ✅ Lengths match source
- ⚠️ Or size limits documented

### 5.2 Constraint Violation Handling

**Test Scenario**: Attempt to violate PostgreSQL constraints

**Setup**:
```sql
CREATE TABLE public.constrained (
    id SERIAL PRIMARY KEY,
    value INTEGER CHECK (value > 0)
);
```

**Operation**:
```sql
-- This should fail in PostgreSQL
INSERT INTO constrained (value) VALUES (-1);
```

**Expected Results**:
- ✅ Insert fails in PostgreSQL
- ✅ Nothing replicated to ClickHouse
- ✅ Error logged appropriately

### 5.3 Connection Loss During Replication

**Test Scenario**: Simulate network interruption

**Steps**:
1. Start replication
2. Insert data
3. Stop ClickHouse or network
4. Insert more data in PostgreSQL
5. Restart ClickHouse/network

**Verification**:
```sql
-- After reconnection
SELECT COUNT(*) FROM public.users FINAL;
```

**Expected Results**:
- ✅ Replication resumes automatically
- ✅ All missed changes replicated
- ✅ Data consistent after recovery

---

## 6. Performance Verification

### 6.1 Replication Lag Measurement

**Test Scenario**: Measure time from PostgreSQL write to ClickHouse visibility

**Method**:
```sql
-- PostgreSQL
INSERT INTO public.users (username, email, created_at)
VALUES ('perf_test', 'perf@example.com', NOW());

-- Immediately query ClickHouse repeatedly
-- Measure time until row appears
```

**Expected Results**:
- ✅ Replication lag < 5 seconds (95th percentile)
- ✅ Replication lag < 10 seconds (99th percentile)

### 6.2 Bulk Operation Performance

**Test Scenario**: Large batch insert/update/delete

**Setup**:
```sql
-- Insert 100K rows
INSERT INTO public.users (username, email)
SELECT 
    'bulk_user_' || i,
    'bulk' || i || '@example.com'
FROM generate_series(1, 100000) i;
```

**Verification**:
- Start time
- End time in ClickHouse
- Measure throughput

**Expected Results**:
- ✅ Throughput > 10K rows/second
- ✅ All rows replicated
- ✅ No data loss

---

## 7. Automated Verification Scripts

### 7.1 Comprehensive Verification Script

```bash
#!/bin/bash
# File: verify_postgres_operations.sh

set -e

echo "=== PostgreSQL Operations Verification ==="

# 1. Verify batch dump
echo "1. Testing batch dump..."
python db_dump/postgres_dumper.py \
    --postgres_host localhost \
    --postgres_database testdb \
    --dump_dir /tmp/verify_dumps

python db_load/clickhouse_loader.py \
    --clickhouse_host localhost \
    --dump_dir /tmp/verify_dumps

# 2. Verify checksums
echo "2. Verifying checksums..."
PG_CHECKSUM=$(python db_compare/postgres_table_checksum.py \
    --postgres_database testdb \
    --tables_regex '^users$' | grep "Checksum" | awk '{print $6}')

CH_CHECKSUM=$(python db_compare/clickhouse_table_checksum.py \
    --clickhouse_database testdb \
    --tables_regex '^users$' | grep "Checksum" | awk '{print $6}')

if [ "$PG_CHECKSUM" == "$CH_CHECKSUM" ]; then
    echo "✅ Checksums match"
else
    echo "❌ Checksum mismatch!"
    exit 1
fi

# 3. Verify CDC operations
echo "3. Testing CDC operations..."
# INSERT
psql -h localhost -U root -d testdb -c \
    "INSERT INTO users (username, email) VALUES ('verify_user', 'verify@test.com');"

sleep 5

CH_COUNT=$(clickhouse-client --query \
    "SELECT COUNT(*) FROM testdb.users FINAL WHERE username = 'verify_user'")

if [ "$CH_COUNT" -eq "1" ]; then
    echo "✅ INSERT replicated"
else
    echo "❌ INSERT not replicated"
    exit 1
fi

# UPDATE
psql -h localhost -U root -d testdb -c \
    "UPDATE users SET email = 'updated@test.com' WHERE username = 'verify_user';"

sleep 5

CH_EMAIL=$(clickhouse-client --query \
    "SELECT email FROM testdb.users FINAL WHERE username = 'verify_user'" | tr -d ' ')

if [ "$CH_EMAIL" == "updated@test.com" ]; then
    echo "✅ UPDATE replicated"
else
    echo "❌ UPDATE not replicated"
    exit 1
fi

# DELETE
psql -h localhost -U root -d testdb -c \
    "DELETE FROM users WHERE username = 'verify_user';"

sleep 5

CH_COUNT=$(clickhouse-client --query \
    "SELECT COUNT(*) FROM testdb.users FINAL WHERE username = 'verify_user'")

if [ "$CH_COUNT" -eq "0" ]; then
    echo "✅ DELETE replicated"
else
    echo "❌ DELETE not replicated"
    exit 1
fi

echo "=== All verifications passed! ==="
```

### 7.2 Data Type Verification Script

```python
# File: verify_postgres_data_types.py

import psycopg2
from clickhouse_driver import Client

def verify_all_data_types():
    """Verify all PostgreSQL data types replicate correctly"""
    
    pg_conn = psycopg2.connect(
        host='localhost',
        database='testdb',
        user='root',
        password='root'
    )
    
    ch_client = Client(host='localhost', user='root', password='root')
    
    # Test cases for each data type
    test_cases = [
        ('UUID', "'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'"),
        ('BOOLEAN', 'TRUE'),
        ('INTEGER', '42'),
        ('BIGINT', '9223372036854775807'),
        ('NUMERIC(10,2)', '12345.67'),
        ('TEXT', "'Test string'"),
        ('JSONB', '\'{"key": "value"}\''),
        ('INTEGER[]', 'ARRAY[1,2,3]'),
        ('TIMESTAMP WITH TIME ZONE', "'2024-01-01 12:00:00+00'"),
    ]
    
    for type_name, test_value in test_cases:
        print(f"Testing {type_name}...")
        
        # Insert in PostgreSQL
        pg_cursor = pg_conn.cursor()
        pg_cursor.execute(f"""
            INSERT INTO type_test (test_column) 
            VALUES ({test_value})
            RETURNING id;
        """)
        row_id = pg_cursor.fetchone()[0]
        pg_conn.commit()
        
        # Wait and check ClickHouse
        import time
        time.sleep(5)
        
        result = ch_client.execute(f"""
            SELECT test_column 
            FROM testdb.type_test FINAL 
            WHERE id = {row_id}
        """)
        
        if result:
            print(f"✅ {type_name} replicated")
        else:
            print(f"❌ {type_name} NOT replicated")
            return False
    
    return True

if __name__ == '__main__':
    if verify_all_data_types():
        print("All data types verified!")
    else:
        print("Verification failed!")
        exit(1)
```

---

## 8. Verification Checklist

### 8.1 Pre-Deployment Checklist

- [ ] All batch dump tests passing
- [ ] All CDC operation tests passing
- [ ] All data type tests passing
- [ ] Checksum validation 100% match
- [ ] Performance benchmarks met
- [ ] Edge cases handled
- [ ] Error scenarios tested
- [ ] Documentation complete
- [ ] Automated tests in CI/CD

### 8.2 Post-Deployment Checklist

- [ ] Production data dump successful
- [ ] Production data load successful
- [ ] Production checksums verified
- [ ] CDC replication working
- [ ] Monitoring dashboards operational
- [ ] Alerts configured
- [ ] Runbook documented
- [ ] Team trained

---

## 9. Troubleshooting Guide

### 9.1 Common Issues

| Issue | Symptom | Diagnosis | Resolution |
|-------|---------|-----------|------------|
| Checksum mismatch | Checksums don't match | Data corruption or type conversion issue | Investigate specific rows, check data types |
| Row count mismatch | Counts differ | Missing rows or duplicate rows | Check for DELETEs, investigate _sign column |
| Replication lag | Data delayed | Network or performance issue | Check connector logs, ClickHouse performance |
| Type conversion error | Data appears incorrect | Wrong type mapping | Review type mapping, adjust parser |
| NULL handling issue | NULLs not preserved | Nullable not applied | Check column definitions |

### 9.2 Diagnostic Queries

**Find rows with mismatched _sign**:
```sql
SELECT * FROM table WHERE _sign = -1;
```

**Check version history**:
```sql
SELECT *, _sign, _version FROM table WHERE id = 'xxx' ORDER BY _version;
```

**Find duplicates**:
```sql
SELECT id, COUNT(*) FROM table FINAL GROUP BY id HAVING COUNT(*) > 1;
```

---

## 10. Continuous Verification

### 10.1 Automated Monitoring

**Daily Checks**:
- Run checksum validation on all tables
- Compare row counts
- Check replication lag
- Review error logs

**Weekly Checks**:
- Full data validation
- Performance benchmarks
- Capacity planning

**Monthly Checks**:
- Comprehensive audit
- Data quality assessment
- Update documentation

---

## Conclusion

This verification plan ensures complete correctness of all PostgreSQL operations in the clickhouse-sink-connector. By following these procedures, teams can achieve 100% confidence in data integrity and operational reliability.

**Key Success Factors**:
- ✅ Systematic testing of every operation
- ✅ Automated verification scripts
- ✅ Continuous monitoring
- ✅ Clear troubleshooting procedures
- ✅ Comprehensive documentation

**Next Steps**:
1. Implement verification scripts
2. Integrate into CI/CD pipeline
3. Train operations team
4. Deploy to production with confidence
