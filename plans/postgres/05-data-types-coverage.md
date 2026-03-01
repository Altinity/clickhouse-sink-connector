# PostgreSQL Data Types Coverage

## Executive Summary

This document provides a comprehensive test matrix for all PostgreSQL data types supported in the clickhouse-sink-connector. It includes test data examples, expected ClickHouse conversions, edge cases, and validation queries for each type.

**Coverage**: 40+ PostgreSQL data types across 10 categories

**Purpose**: Ensure 100% data type compatibility and correctness

---

## 1. Data Type Categories

```
PostgreSQL Data Types (40+)
├── Integer Types (5)
├── Numeric Types (4)
├── String Types (4)
├── Binary Types (1)
├── Date/Time Types (6)
├── Boolean Type (1)
├── UUID Type (1)
├── JSON Types (2)
├── Array Types (Variable)
├── Network Types (3)
├── Geometric Types (7)
└── Special Types (6+)
```

---

## 2. Integer Types

### 2.1 SMALLINT / INT2

**PostgreSQL Type**: `SMALLINT`, `INT2`  
**ClickHouse Type**: `Int16`  
**Range**: -32,768 to 32,767  
**Size**: 2 bytes

#### Test Data

```sql
CREATE TABLE test_smallint (
    id SERIAL PRIMARY KEY,
    col_smallint SMALLINT,
    col_int2 INT2
);

INSERT INTO test_smallint (col_smallint, col_int2) VALUES
    (0, 0),                    -- Zero
    (1, 1),                    -- Positive
    (-1, -1),                  -- Negative
    (32767, 32767),            -- Max value
    (-32768, -32768),          -- Min value
    (NULL, NULL);              -- NULL
```

#### Expected ClickHouse DDL

```sql
CREATE TABLE test_smallint (
    id Int32,
    col_smallint Nullable(Int16),
    col_int2 Nullable(Int16),
    _sign Int8 DEFAULT 1,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
ORDER BY id;
```

#### Verification Queries

```sql
-- ClickHouse
SELECT 
    col_smallint,
    col_int2,
    toTypeName(col_smallint) as type_name
FROM test_smallint FINAL
WHERE id IN (1, 4, 5);

-- Expected results:
-- id=1: col_smallint=0, type_name='Nullable(Int16)'
-- id=4: col_smallint=32767 (max value)
-- id=5: col_smallint=-32768 (min value)
```

#### Edge Cases

- ✅ Maximum value (32767)
- ✅ Minimum value (-32768)
- ✅ Zero
- ✅ NULL handling
- ⚠️ Overflow: PostgreSQL rejects, ClickHouse should not receive

---

### 2.2 INTEGER / INT / INT4 / SERIAL

**PostgreSQL Type**: `INTEGER`, `INT`, `INT4`, `SERIAL`  
**ClickHouse Type**: `Int32`  
**Range**: -2,147,483,648 to 2,147,483,647  
**Size**: 4 bytes

#### Test Data

```sql
CREATE TABLE test_integer (
    id SERIAL PRIMARY KEY,              -- AUTO_INCREMENT
    col_integer INTEGER,
    col_int INT,
    col_int4 INT4
);

INSERT INTO test_integer (col_integer, col_int, col_int4) VALUES
    (0, 0, 0),
    (42, 42, 42),
    (-42, -42, -42),
    (2147483647, 2147483647, 2147483647),      -- Max
    (-2147483648, -2147483648, -2147483648),   -- Min
    (NULL, NULL, NULL);
```

#### Expected ClickHouse DDL

```sql
CREATE TABLE test_integer (
    id Int32,                          -- SERIAL becomes Int32
    col_integer Nullable(Int32),
    col_int Nullable(Int32),
    col_int4 Nullable(Int32),
    _sign Int8 DEFAULT 1,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
ORDER BY id;
```

#### Verification Queries

```sql
-- Verify SERIAL conversion
SELECT id FROM test_integer FINAL ORDER BY id;
-- Expected: 1, 2, 3, 4, 5, 6 (sequential)

-- Verify max/min values
SELECT col_integer FROM test_integer FINAL WHERE id IN (4, 5);
-- Expected: 2147483647, -2147483648
```

---

### 2.3 BIGINT / INT8 / BIGSERIAL

**PostgreSQL Type**: `BIGINT`, `INT8`, `BIGSERIAL`  
**ClickHouse Type**: `Int64`  
**Range**: -9,223,372,036,854,775,808 to 9,223,372,036,854,775,807  
**Size**: 8 bytes

#### Test Data

```sql
CREATE TABLE test_bigint (
    id BIGSERIAL PRIMARY KEY,
    col_bigint BIGINT,
    col_int8 INT8
);

INSERT INTO test_bigint (col_bigint, col_int8) VALUES
    (0, 0),
    (9223372036854775807, 9223372036854775807),      -- Max
    (-9223372036854775808, -9223372036854775808),    -- Min
    (1000000000000, 1000000000000),                  -- 1 trillion
    (NULL, NULL);
```

#### Expected ClickHouse DDL

```sql
CREATE TABLE test_bigint (
    id Int64,
    col_bigint Nullable(Int64),
    col_int8 Nullable(Int64),
    _sign Int8 DEFAULT 1,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
ORDER BY id;
```

#### Edge Cases

- ✅ 64-bit max/min values
- ✅ Large numbers (trillions)
- ✅ BIGSERIAL auto-increment preserved as Int64

---

## 3. Numeric Types

### 3.1 NUMERIC / DECIMAL

**PostgreSQL Type**: `NUMERIC(p, s)`, `DECIMAL(p, s)`  
**ClickHouse Type**: `Decimal(p, s)`  
**Precision**: Up to 38 digits  
**Scale**: Variable

#### Test Data

```sql
CREATE TABLE test_numeric (
    id SERIAL PRIMARY KEY,
    col_numeric_10_2 NUMERIC(10, 2),
    col_numeric_21_5 NUMERIC(21, 5),
    col_decimal DECIMAL(15, 4),
    col_numeric_no_scale NUMERIC,              -- Default precision
    col_numeric_high_precision NUMERIC(38, 10)
);

INSERT INTO test_numeric VALUES
    (DEFAULT, 12345.67, 1234567890.12345, 12345678901.1234, 123456789, 1234567890123456789012345678.1234567890),
    (DEFAULT, -99999.99, -99999999999999.99999, -999999999999.9999, -999999999, -9999999999999999999999999999.9999999999),
    (DEFAULT, 0.01, 0.00001, 0.0001, 1, 0.0000000001),
    (DEFAULT, NULL, NULL, NULL, NULL, NULL);
```

#### Expected ClickHouse DDL

```sql
CREATE TABLE test_numeric (
    id Int32,
    col_numeric_10_2 Nullable(Decimal(10, 2)),
    col_numeric_21_5 Nullable(Decimal(21, 5)),
    col_decimal Nullable(Decimal(15, 4)),
    col_numeric_no_scale Nullable(Decimal(38, 9)),   -- Default
    col_numeric_high_precision Nullable(Decimal(38, 10)),
    _sign Int8 DEFAULT 1,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
ORDER BY id;
```

#### Verification Queries

```sql
-- Verify precision preservation
SELECT 
    col_numeric_21_5,
    toString(col_numeric_21_5) as str_value
FROM test_numeric FINAL WHERE id = 1;
-- Expected: 1234567890.12345

-- Verify scale
SELECT col_decimal FROM test_numeric FINAL WHERE id = 3;
-- Expected: 0.0001
```

#### Edge Cases

- ✅ High precision (38 digits)
- ✅ Small decimal values (0.00001)
- ✅ Negative values
- ✅ Default precision handling
- ⚠️ Precision loss if exceeding Decimal(38, x) - document this

---

### 3.2 REAL / FLOAT4

**PostgreSQL Type**: `REAL`, `FLOAT4`  
**ClickHouse Type**: `Float32`  
**Precision**: ~6 decimal digits  
**Size**: 4 bytes

#### Test Data

```sql
CREATE TABLE test_real (
    id SERIAL PRIMARY KEY,
    col_real REAL,
    col_float4 FLOAT4
);

INSERT INTO test_real (col_real, col_float4) VALUES
    (3.14159, 3.14159),
    (-2.71828, -2.71828),
    (1.23e-4, 1.23e-4),              -- Scientific notation
    (1.23e10, 1.23e10),              -- Large number
    (NULL, NULL);
```

#### Expected ClickHouse Type

```sql
col_real Nullable(Float32)
col_float4 Nullable(Float32)
```

#### Verification

```sql
SELECT col_real, col_float4 FROM test_real FINAL WHERE id = 1;
-- Expected: ~3.14159 (may have small floating point differences)
```

#### Edge Cases

- ⚠️ Floating point precision differences acceptable
- ⚠️ Exact equality checks may fail - use epsilon comparison
- ✅ Scientific notation preserved

---

### 3.3 DOUBLE PRECISION / FLOAT8

**PostgreSQL Type**: `DOUBLE PRECISION`, `FLOAT8`  
**ClickHouse Type**: `Float64`  
**Precision**: ~15 decimal digits  
**Size**: 8 bytes

#### Test Data

```sql
CREATE TABLE test_double (
    id SERIAL PRIMARY KEY,
    col_double DOUBLE PRECISION,
    col_float8 FLOAT8
);

INSERT INTO test_double (col_double, col_float8) VALUES
    (3.141592653589793, 3.141592653589793),
    (2.718281828459045, 2.718281828459045),
    (1.23456789012345e-10, 1.23456789012345e-10),
    (9.87654321098765e20, 9.87654321098765e20),
    (NULL, NULL);
```

#### Expected ClickHouse Type

```sql
col_double Nullable(Float64)
col_float8 Nullable(Float64)
```

---

## 4. String Types

### 4.1 VARCHAR(n) / CHARACTER VARYING(n)

**PostgreSQL Type**: `VARCHAR(n)`, `CHARACTER VARYING(n)`  
**ClickHouse Type**: `String`  
**Note**: ClickHouse String has no length limit

#### Test Data

```sql
CREATE TABLE test_varchar (
    id SERIAL PRIMARY KEY,
    col_varchar_10 VARCHAR(10),
    col_varchar_255 VARCHAR(255),
    col_varchar_max VARCHAR(65535)
);

INSERT INTO test_varchar VALUES
    (DEFAULT, 'Short', 'Medium length string', 'Very long string...'),
    (DEFAULT, '12345', 'test@example.com', REPEAT('A', 10000)),
    (DEFAULT, 'UTF-8: 你好', 'Emoji: 🎉🎊', 'Special: @#$%^&*()'),
    (DEFAULT, NULL, NULL, NULL);
```

#### Expected ClickHouse DDL

```sql
col_varchar_10 Nullable(String)       -- Length constraint dropped
col_varchar_255 Nullable(String)
col_varchar_max Nullable(String)
```

#### Verification

```sql
-- Verify UTF-8 preservation
SELECT col_varchar_10 FROM test_varchar FINAL WHERE id = 3;
-- Expected: 'UTF-8: 你好'

-- Verify long strings
SELECT LENGTH(col_varchar_max) FROM test_varchar FINAL WHERE id = 2;
-- Expected: 10000
```

---

### 4.2 CHAR(n) / CHARACTER(n)

**PostgreSQL Type**: `CHAR(n)`, `CHARACTER(n)`  
**ClickHouse Type**: `FixedString(n)` or `String`  
**Note**: CHAR is space-padded in PostgreSQL

#### Test Data

```sql
CREATE TABLE test_char (
    id SERIAL PRIMARY KEY,
    col_char_10 CHAR(10),
    col_character_5 CHARACTER(5)
);

INSERT INTO test_char (col_char_10, col_character_5) VALUES
    ('ABC', 'XY'),              -- Will be padded
    ('1234567890', '12345'),    -- Exact length
    (NULL, NULL);
```

#### Expected ClickHouse DDL

```sql
col_char_10 Nullable(FixedString(10))     -- Fixed length preserved
col_character_5 Nullable(FixedString(5))
```

#### Verification

```sql
SELECT 
    col_char_10,
    LENGTH(col_char_10) as len
FROM test_char FINAL WHERE id = 1;
-- Expected: 'ABC       ' (space-padded), len=10
```

#### Edge Cases

- ✅ Space padding preserved
- ✅ Fixed length enforced
- ⚠️ Or converted to String (depends on implementation choice)

---

### 4.3 TEXT

**PostgreSQL Type**: `TEXT`  
**ClickHouse Type**: `String`  
**Note**: Unlimited length

#### Test Data

```sql
CREATE TABLE test_text (
    id SERIAL PRIMARY KEY,
    col_text TEXT
);

INSERT INTO test_text (col_text) VALUES
    ('Short text'),
    (REPEAT('Long text paragraph. ', 1000)),     -- ~20KB
    ('Multi-line
text with
newlines'),
    ('Special chars: Tab	NewLine
Quote" Apostrophe'' Backslash\'),
    (NULL);
```

#### Expected ClickHouse Type

```sql
col_text Nullable(String)
```

#### Verification

```sql
-- Verify newlines preserved
SELECT col_text FROM test_text FINAL WHERE id = 3;
-- Should contain actual newlines

-- Verify large text
SELECT LENGTH(col_text) FROM test_text FINAL WHERE id = 2;
-- Expected: ~20000
```

---

## 5. Binary Types

### 5.1 BYTEA

**PostgreSQL Type**: `BYTEA`  
**ClickHouse Type**: `String`  
**Encoding**: Binary data stored as hex or base64 string

#### Test Data

```sql
CREATE TABLE test_bytea (
    id SERIAL PRIMARY KEY,
    col_bytea BYTEA
);

INSERT INTO test_bytea (col_bytea) VALUES
    ('\xDEADBEEF'::bytea),
    ('\x00010203'::bytea),
    (decode('48656C6C6F', 'hex')),      -- 'Hello' in hex
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_bytea Nullable(String)             -- Stored as hex string
```

#### Verification

```sql
-- ClickHouse - verify hex representation
SELECT col_bytea FROM test_bytea FINAL WHERE id = 1;
-- Expected: hex representation or base64
```

#### Edge Cases

- ✅ Binary data preserved
- ⚠️ Encoding format (hex vs base64) should be consistent
- ✅ NULL handling

---

## 6. Date/Time Types

### 6.1 DATE

**PostgreSQL Type**: `DATE`  
**ClickHouse Type**: `Date32`  
**Range**: PostgreSQL: 4713 BC to 5874897 AD, ClickHouse Date32: 1900-01-01 to 2299-12-31

#### Test Data

```sql
CREATE TABLE test_date (
    id SERIAL PRIMARY KEY,
    col_date DATE
);

INSERT INTO test_date (col_date) VALUES
    ('2024-01-15'),
    ('1900-01-01'),                    -- ClickHouse Date32 min
    ('2299-12-31'),                    -- ClickHouse Date32 max
    ('2000-02-29'),                    -- Leap year
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_date Nullable(Date32)
```

#### Verification

```sql
SELECT col_date, toTypeName(col_date) FROM test_date FINAL WHERE id = 1;
-- Expected: '2024-01-15', 'Nullable(Date32)'
```

#### Edge Cases

- ✅ Date range within ClickHouse limits
- ⚠️ Dates before 1900 or after 2299 may fail - document this
- ✅ Leap years handled correctly

---

### 6.2 TIME / TIME WITHOUT TIME ZONE

**PostgreSQL Type**: `TIME`, `TIME WITHOUT TIME ZONE`  
**ClickHouse Type**: `String` (no native Time type)  
**Format**: HH:MM:SS.microseconds

#### Test Data

```sql
CREATE TABLE test_time (
    id SERIAL PRIMARY KEY,
    col_time TIME
);

INSERT INTO test_time (col_time) VALUES
    ('14:30:00'),
    ('00:00:00'),
    ('23:59:59.999999'),
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_time Nullable(String)              -- No native Time type
```

#### Verification

```sql
SELECT col_time FROM test_time FINAL WHERE id = 1;
-- Expected: '14:30:00' (as string)
```

---

### 6.3 TIME WITH TIME ZONE / TIMETZ

**PostgreSQL Type**: `TIME WITH TIME ZONE`, `TIMETZ`  
**ClickHouse Type**: `String`  
**Format**: HH:MM:SS±TZ

#### Test Data

```sql
CREATE TABLE test_timetz (
    id SERIAL PRIMARY KEY,
    col_timetz TIME WITH TIME ZONE
);

INSERT INTO test_timetz (col_timetz) VALUES
    ('14:30:00-05:00'),
    ('14:30:00+00:00'),
    ('09:15:30.123456-08:00'),
    (NULL);
```

#### Expected ClickHouse Type

```sql
col_timetz Nullable(String)
```

---

### 6.4 TIMESTAMP / TIMESTAMP WITHOUT TIME ZONE

**PostgreSQL Type**: `TIMESTAMP`, `TIMESTAMP WITHOUT TIME ZONE`  
**ClickHouse Type**: `DateTime64(6)`  
**Precision**: Microseconds (6 digits)

#### Test Data

```sql
CREATE TABLE test_timestamp (
    id SERIAL PRIMARY KEY,
    col_timestamp TIMESTAMP
);

INSERT INTO test_timestamp (col_timestamp) VALUES
    ('2024-01-15 14:30:00'),
    ('2024-01-15 14:30:00.123456'),
    ('1970-01-01 00:00:00'),               -- Unix epoch
    ('2038-01-19 03:14:07'),               -- Near Unix timestamp limit
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_timestamp Nullable(DateTime64(6))      -- Microsecond precision
```

#### Verification

```sql
SELECT 
    col_timestamp,
    toUnixTimestamp64Micro(col_timestamp) as unix_micro
FROM test_timestamp FINAL WHERE id = 2;
-- Verify microsecond precision preserved
```

---

### 6.5 TIMESTAMP WITH TIME ZONE / TIMESTAMPTZ

**PostgreSQL Type**: `TIMESTAMP WITH TIME ZONE`, `TIMESTAMPTZ`  
**ClickHouse Type**: `DateTime64(6, 'UTC')` or `DateTime64(6, 'timezone')`  
**Storage**: Always stored in UTC, displayed in session timezone

#### Test Data

```sql
CREATE TABLE test_timestamptz (
    id SERIAL PRIMARY KEY,
    col_timestamptz TIMESTAMP WITH TIME ZONE
);

INSERT INTO test_timestamptz (col_timestamptz) VALUES
    ('2024-01-15 14:30:00+00'),            -- UTC
    ('2024-01-15 14:30:00-05:00'),         -- EST
    ('2024-01-15 14:30:00+09:00'),         -- JST
    ('2024-01-15 14:30:00.123456+00'),     -- With microseconds
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_timestamptz Nullable(DateTime64(6, 'UTC'))
```

#### Verification

```sql
-- All timestamps should be normalized to UTC
SELECT 
    col_timestamptz,
    formatDateTime(col_timestamptz, '%Y-%m-%d %H:%i:%s', 'UTC') as utc_time
FROM test_timestamptz FINAL WHERE id IN (1, 2, 3);

-- id=1: 2024-01-15 14:30:00 (already UTC)
-- id=2: 2024-01-15 19:30:00 (EST +5 hours)
-- id=3: 2024-01-15 05:30:00 (JST -9 hours)
```

#### Edge Cases

- ✅ Timezone conversion to UTC
- ✅ Microsecond precision preserved
- ✅ All timezones handled correctly

---

### 6.6 INTERVAL

**PostgreSQL Type**: `INTERVAL`  
**ClickHouse Type**: `String` or `Int64` (nanoseconds)  
**Note**: No direct INTERVAL type in ClickHouse

#### Test Data

```sql
CREATE TABLE test_interval (
    id SERIAL PRIMARY KEY,
    col_interval INTERVAL
);

INSERT INTO test_interval (col_interval) VALUES
    ('1 day'),
    ('2 hours 30 minutes'),
    ('1 year 2 months 3 days'),
    (NULL);
```

#### Expected ClickHouse Type

```sql
col_interval Nullable(String)          -- Stored as string representation
```

---

## 7. Boolean Type

### 7.1 BOOLEAN / BOOL

**PostgreSQL Type**: `BOOLEAN`, `BOOL`  
**ClickHouse Type**: `Bool` (ClickHouse 22.3+) or `UInt8`  
**Values**: TRUE, FALSE, NULL

#### Test Data

```sql
CREATE TABLE test_boolean (
    id SERIAL PRIMARY KEY,
    col_boolean BOOLEAN,
    col_bool BOOL
);

INSERT INTO test_boolean (col_boolean, col_bool) VALUES
    (TRUE, TRUE),
    (FALSE, FALSE),
    ('t', 'f'),                        -- PostgreSQL accepts t/f
    ('yes', 'no'),                     -- PostgreSQL accepts yes/no
    ('1', '0'),                        -- PostgreSQL accepts 1/0
    (NULL, NULL);
```

#### Expected ClickHouse DDL

```sql
col_boolean Nullable(Bool)             -- ClickHouse 22.3+
-- OR
col_boolean Nullable(UInt8)            -- Older ClickHouse
```

#### Verification

```sql
SELECT col_boolean, col_bool FROM test_boolean FINAL;

-- Expected values:
-- id=1: true, true (or 1, 1)
-- id=2: false, false (or 0, 0)
-- id=6: NULL, NULL
```

#### Edge Cases

- ✅ TRUE/FALSE values
- ✅ Alternative input formats (t/f, yes/no, 1/0) normalized
- ✅ NULL handling

---

## 8. UUID Type

### 8.1 UUID

**PostgreSQL Type**: `UUID`  
**ClickHouse Type**: `UUID`  
**Format**: 8-4-4-4-12 hex digits (36 chars with hyphens)

#### Test Data

```sql
CREATE TABLE test_uuid (
    id SERIAL PRIMARY KEY,
    col_uuid UUID DEFAULT gen_random_uuid()
);

INSERT INTO test_uuid (col_uuid) VALUES
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'),
    ('00000000-0000-0000-0000-000000000000'),  -- Nil UUID
    ('ffffffff-ffff-ffff-ffff-ffffffffffff'),  -- Max UUID
    (gen_random_uuid()),                        -- Generated
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_uuid Nullable(UUID)
```

#### Verification

```sql
SELECT 
    col_uuid,
    toTypeName(col_uuid)
FROM test_uuid FINAL WHERE id = 1;
-- Expected: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'Nullable(UUID)'

-- Verify format preservation
SELECT UUIDStringToNum(toString(col_uuid)) FROM test_uuid FINAL WHERE id = 1;
```

#### Edge Cases

- ✅ Standard UUID format
- ✅ Nil UUID (all zeros)
- ✅ Max UUID (all F's)
- ✅ Generated UUIDs
- ✅ Case preservation (lowercase)

---

## 9. JSON Types

### 9.1 JSON

**PostgreSQL Type**: `JSON`  
**ClickHouse Type**: `String`  
**Note**: Stored as JSON string, no validation

#### Test Data

```sql
CREATE TABLE test_json (
    id SERIAL PRIMARY KEY,
    col_json JSON
);

INSERT INTO test_json (col_json) VALUES
    ('{"name": "John", "age": 30}'),
    ('{"nested": {"key": "value"}}'),
    ('{"array": [1, 2, 3]}'),
    ('{"empty": {}}'),
    ('{"null_value": null}'),
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_json Nullable(String)
```

#### Verification

```sql
-- ClickHouse
SELECT 
    col_json,
    JSONExtractString(col_json, 'name') as name
FROM test_json FINAL WHERE id = 1;
-- Expected: '{"name": "John", "age": 30}', 'John'
```

---

### 9.2 JSONB

**PostgreSQL Type**: `JSONB`  
**ClickHouse Type**: `String`  
**Note**: Binary JSON in PostgreSQL, stored as text in ClickHouse

#### Test Data

```sql
CREATE TABLE test_jsonb (
    id SERIAL PRIMARY KEY,
    col_jsonb JSONB
);

INSERT INTO test_jsonb (col_jsonb) VALUES
    ('{"key": "value"}'),
    ('{"number": 42, "boolean": true, "null": null}'),
    ('{"array": [1, 2, 3], "object": {"nested": "data"}}'),
    ('{"unicode": "你好世界"}'),
    ('{"special": "Quote\" Backslash\\ Newline\n"}'),
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_jsonb Nullable(String)
```

#### Verification

```sql
SELECT 
    col_jsonb,
    JSONExtractInt(col_jsonb, 'number') as num
FROM test_jsonb FINAL WHERE id = 2;
-- Expected: JSON string, num=42
```

#### Edge Cases

- ✅ Complex nested structures
- ✅ Unicode characters
- ✅ Special characters escaped
- ✅ NULL within JSON vs SQL NULL
- ⚠️ JSON key ordering may differ (JSONB normalizes in PostgreSQL)

---

## 10. Array Types

### 10.1 INTEGER[]

**PostgreSQL Type**: `INTEGER[]`  
**ClickHouse Type**: `Array(Int32)`

#### Test Data

```sql
CREATE TABLE test_array_int (
    id SERIAL PRIMARY KEY,
    col_int_array INTEGER[]
);

INSERT INTO test_array_int (col_int_array) VALUES
    (ARRAY[1, 2, 3, 4, 5]),
    (ARRAY[]),                         -- Empty array
    (ARRAY[NULL, 1, NULL, 2]),         -- Array with NULLs
    (ARRAY[-100, 0, 100]),
    (NULL);                            -- NULL array
```

#### Expected ClickHouse DDL

```sql
col_int_array Nullable(Array(Int32))
```

#### Verification

```sql
SELECT 
    col_int_array,
    length(col_int_array) as arr_len
FROM test_array_int FINAL WHERE id = 1;
-- Expected: [1,2,3,4,5], len=5
```

---

### 10.2 TEXT[]

**PostgreSQL Type**: `TEXT[]`  
**ClickHouse Type**: `Array(String)`

#### Test Data

```sql
CREATE TABLE test_array_text (
    id SERIAL PRIMARY KEY,
    col_text_array TEXT[]
);

INSERT INTO test_array_text (col_text_array) VALUES
    (ARRAY['one', 'two', 'three']),
    (ARRAY['UTF-8: 你好', 'Emoji: 🎉']),
    (ARRAY['Special "quotes"', 'Comma,test']),
    (ARRAY[]::TEXT[]),
    (NULL);
```

#### Expected ClickHouse Type

```sql
col_text_array Nullable(Array(String))
```

---

### 10.3 UUID[]

**PostgreSQL Type**: `UUID[]`  
**ClickHouse Type**: `Array(UUID)`

#### Test Data

```sql
CREATE TABLE test_array_uuid (
    id SERIAL PRIMARY KEY,
    col_uuid_array UUID[]
);

INSERT INTO test_array_uuid (col_uuid_array) VALUES
    (ARRAY[
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'
    ]::UUID[]),
    (ARRAY[]::UUID[]),
    (NULL);
```

#### Expected ClickHouse Type

```sql
col_uuid_array Nullable(Array(UUID))
```

---

### 10.4 Multidimensional Arrays

**PostgreSQL Type**: `INTEGER[][]`, `TEXT[][]`  
**ClickHouse Type**: `Array(Array(Int32))`, `Array(Array(String))`

#### Test Data

```sql
CREATE TABLE test_multidim_array (
    id SERIAL PRIMARY KEY,
    col_2d_int INTEGER[][],
    col_2d_text TEXT[][]
);

INSERT INTO test_multidim_array (col_2d_int, col_2d_text) VALUES
    (
        ARRAY[[1,2,3],[4,5,6]],
        ARRAY[['a','b'],['c','d']]
    ),
    (NULL, NULL);
```

#### Expected ClickHouse Type

```sql
col_2d_int Nullable(Array(Array(Int32)))
col_2d_text Nullable(Array(Array(String)))
```

---

## 11. Network Types

### 11.1 INET

**PostgreSQL Type**: `INET`  
**ClickHouse Type**: `IPv4` or `IPv6`  
**Format**: IPv4 or IPv6 address

#### Test Data

```sql
CREATE TABLE test_inet (
    id SERIAL PRIMARY KEY,
    col_inet INET
);

INSERT INTO test_inet (col_inet) VALUES
    ('192.168.1.1'),                   -- IPv4
    ('10.0.0.0'),
    ('2001:0db8:85a3:0000:0000:8a2e:0370:7334'), -- IPv6
    ('::1'),                           -- IPv6 loopback
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_inet Nullable(IPv4)                -- Or IPv6 based on value
-- Or String if mixed IPv4/IPv6
```

#### Verification

```sql
SELECT col_inet FROM test_inet FINAL WHERE id = 1;
-- Expected: '192.168.1.1'
```

---

### 11.2 CIDR

**PostgreSQL Type**: `CIDR`  
**ClickHouse Type**: `IPv4` or `String`  
**Format**: Network address with subnet

#### Test Data

```sql
CREATE TABLE test_cidr (
    id SERIAL PRIMARY KEY,
    col_cidr CIDR
);

INSERT INTO test_cidr (col_cidr) VALUES
    ('192.168.0.0/24'),
    ('10.0.0.0/8'),
    ('2001:db8::/32'),
    (NULL);
```

#### Expected ClickHouse Type

```sql
col_cidr Nullable(String)              -- CIDR notation as string
```

---

### 11.3 MACADDR

**PostgreSQL Type**: `MACADDR`  
**ClickHouse Type**: `String`  
**Format**: MAC address

#### Test Data

```sql
CREATE TABLE test_macaddr (
    id SERIAL PRIMARY KEY,
    col_macaddr MACADDR
);

INSERT INTO test_macaddr (col_macaddr) VALUES
    ('08:00:2b:01:02:03'),
    ('08-00-2b-01-02-03'),             -- Alternative format
    ('0800.2b01.0203'),                -- Cisco format
    (NULL);
```

#### Expected ClickHouse Type

```sql
col_macaddr Nullable(String)
```

---

## 12. Geometric Types

### 12.1 POINT

**PostgreSQL Type**: `POINT`  
**ClickHouse Type**: `Tuple(Float64, Float64)`  
**Format**: (x, y) coordinates

#### Test Data

```sql
CREATE TABLE test_point (
    id SERIAL PRIMARY KEY,
    col_point POINT
);

INSERT INTO test_point (col_point) VALUES
    (POINT(0, 0)),
    (POINT(1.5, 2.5)),
    (POINT(-10.123, 20.456)),
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_point Nullable(Tuple(Float64, Float64))
```

#### Verification

```sql
SELECT 
    col_point,
    col_point.1 as x,
    col_point.2 as y
FROM test_point FINAL WHERE id = 2;
-- Expected: (1.5, 2.5), x=1.5, y=2.5
```

---

### 12.2 LINE, LSEG, BOX, PATH, POLYGON, CIRCLE

**PostgreSQL Types**: Geometric types  
**ClickHouse Type**: `String` (WKT or JSON representation)

#### Test Data

```sql
CREATE TABLE test_geometry (
    id SERIAL PRIMARY KEY,
    col_line LINE,
    col_box BOX,
    col_path PATH,
    col_polygon POLYGON,
    col_circle CIRCLE
);

INSERT INTO test_geometry VALUES
    (
        DEFAULT,
        '((0,0),(1,1))',                   -- LINE
        '((0,0),(1,1))',                   -- BOX
        '((0,0),(1,1),(2,0))',             -- PATH
        '((0,0),(1,0),(1,1),(0,1))',       -- POLYGON
        '<(0,0),1>'                        -- CIRCLE
    );
```

#### Expected ClickHouse Type

```sql
col_line Nullable(String)
col_box Nullable(String)
col_path Nullable(String)
col_polygon Nullable(String)
col_circle Nullable(String)
```

---

## 13. Special Types

### 13.1 HSTORE

**PostgreSQL Type**: `HSTORE` (key-value pairs)  
**ClickHouse Type**: `Map(String, String)`

#### Test Data

```sql
CREATE EXTENSION IF NOT EXISTS hstore;

CREATE TABLE test_hstore (
    id SERIAL PRIMARY KEY,
    col_hstore HSTORE
);

INSERT INTO test_hstore (col_hstore) VALUES
    ('key1=>value1, key2=>value2'::hstore),
    ('"special key"=>"special value"'::hstore),
    (NULL);
```

#### Expected ClickHouse DDL

```sql
col_hstore Nullable(Map(String, String))
```

---

### 13.2 XML

**PostgreSQL Type**: `XML`  
**ClickHouse Type**: `String`

#### Test Data

```sql
CREATE TABLE test_xml (
    id SERIAL PRIMARY KEY,
    col_xml XML
);

INSERT INTO test_xml (col_xml) VALUES
    ('<root><element>value</element></root>'),
    ('<data attr="test">content</data>'),
    (NULL);
```

#### Expected ClickHouse Type

```sql
col_xml Nullable(String)
```

---

### 13.3 Range Types

**PostgreSQL Types**: `INT4RANGE`, `INT8RANGE`, `NUMRANGE`, `TSRANGE`, `TSTZRANGE`, `DATERANGE`  
**ClickHouse Type**: `String` (no native range type)

#### Test Data

```sql
CREATE TABLE test_ranges (
    id SERIAL PRIMARY KEY,
    col_int4range INT4RANGE,
    col_tstzrange TSTZRANGE
);

INSERT INTO test_ranges VALUES
    (DEFAULT, '[1,10]', '[2024-01-01 00:00:00+00, 2024-12-31 23:59:59+00]'),
    (DEFAULT, '[,100]', '[2024-06-01,)'),  -- Unbounded ranges
    (DEFAULT, NULL, NULL);
```

#### Expected ClickHouse Type

```sql
col_int4range Nullable(String)
col_tstzrange Nullable(String)
```

---

## 14. Composite Data Type Test

### 14.1 All Types Combined Table

**Complete test with all supported types**:

```sql
CREATE TABLE postgres_all_types_comprehensive (
    -- Primary key
    id BIGSERIAL PRIMARY KEY,
    
    -- Integer types
    col_smallint SMALLINT,
    col_integer INTEGER,
    col_bigint BIGINT,
    
    -- Numeric types
    col_numeric NUMERIC(21, 5),
    col_real REAL,
    col_double DOUBLE PRECISION,
    
    -- String types
    col_varchar VARCHAR(255),
    col_char CHAR(10),
    col_text TEXT,
    
    -- Binary
    col_bytea BYTEA,
    
    -- Date/Time
    col_date DATE,
    col_time TIME,
    col_timestamp TIMESTAMP,
    col_timestamptz TIMESTAMP WITH TIME ZONE,
    
    -- Boolean
    col_boolean BOOLEAN,
    
    -- UUID
    col_uuid UUID,
    
    -- JSON
    col_json JSON,
    col_jsonb JSONB,
    
    -- Arrays
    col_int_array INTEGER[],
    col_text_array TEXT[],
    col_uuid_array UUID[],
    
    -- Network types
    col_inet INET,
    col_macaddr MACADDR,
    
    -- Geometric
    col_point POINT,
    
    -- Special
    col_hstore HSTORE,
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Insert comprehensive test data
INSERT INTO postgres_all_types_comprehensive VALUES (
    DEFAULT,                                                  -- id (auto)
    32767,                                                    -- smallint
    2147483647,                                              -- integer
    9223372036854775807,                                     -- bigint
    12345.67890,                                             -- numeric
    3.14159,                                                 -- real
    3.141592653589793,                                       -- double
    'Test VARCHAR string',                                   -- varchar
    'FixedChar ',                                            -- char
    'This is a long TEXT field',                             -- text
    '\xDEADBEEF'::bytea,                                     -- bytea
    '2024-01-15',                                            -- date
    '14:30:00',                                              -- time
    '2024-01-15 14:30:00',                                   -- timestamp
    '2024-01-15 14:30:00+00',                                -- timestamptz
    TRUE,                                                     -- boolean
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',                  -- uuid
    '{"name": "John", "age": 30}',                           -- json
    '{"nested": {"key": "value"}, "array": [1,2,3]}',        -- jsonb
    ARRAY[1,2,3,4,5],                                        -- int_array
    ARRAY['one', 'two', 'three'],                            -- text_array
    ARRAY['550e8400-e29b-41d4-a716-446655440000']::UUID[],   -- uuid_array
    '192.168.1.1',                                           -- inet
    '08:00:2b:01:02:03',                                     -- macaddr
    POINT(1.5, 2.5),                                         -- point
    'key1=>value1, key2=>value2',                            -- hstore
    NOW(),                                                    -- created_at
    NOW()                                                     -- updated_at
);
```

### 14.2 Complete Verification Query

```sql
-- ClickHouse - Verify all types after replication
SELECT 
    id,
    col_smallint,
    col_integer,
    col_bigint,
    col_numeric,
    col_varchar,
    col_text,
    col_date,
    col_timestamptz,
    col_boolean,
    col_uuid,
    col_jsonb,
    col_int_array,
    col_inet,
    col_point,
    _sign,
    _version
FROM postgres_all_types_comprehensive FINAL
WHERE id = 1;

-- Verify types
SELECT 
    toTypeName(col_smallint) as type_smallint,
    toTypeName(col_uuid) as type_uuid,
    toTypeName(col_jsonb) as type_jsonb,
    toTypeName(col_int_array) as type_array
FROM postgres_all_types_comprehensive FINAL
LIMIT 1;
```

---

## 15. Summary & Coverage Matrix

| Category | Total Types | Tested | Coverage |
|----------|-------------|--------|----------|
| Integer Types | 5 | 5 | 100% |
| Numeric Types | 4 | 4 | 100% |
| String Types | 4 | 4 | 100% |
| Binary Types | 1 | 1 | 100% |
| Date/Time Types | 6 | 6 | 100% |
| Boolean | 1 | 1 | 100% |
| UUID | 1 | 1 | 100% |
| JSON Types | 2 | 2 | 100% |
| Array Types | Variable | 4 | 100% |
| Network Types | 3 | 3 | 100% |
| Geometric Types | 7 | 2 | 29% ⚠️ |
| Special Types | 6+ | 3 | 50% ⚠️ |
| **TOTAL** | **40+** | **36** | **90%** |

### Coverage Notes:
- ✅ **Full Coverage**: All common PostgreSQL types
- ⚠️ **Partial Coverage**: Geometric types (stored as String)
- ⚠️ **Partial Coverage**: Some special types (ENUM, custom types)

---

## 16. Test Execution Checklist

### 16.1 Batch Dump Testing
- [ ] Create all type test tables in PostgreSQL
- [ ] Insert test data with edge cases
- [ ] Run `postgres_dumper.py`
- [ ] Run `clickhouse_loader.py`
- [ ] Verify all tables created in ClickHouse
- [ ] Run verification queries for each type
- [ ] Compare checksums

### 16.2 CDC Replication Testing
- [ ] Start Debezium connector
- [ ] Create type test tables (will auto-create in ClickHouse)
- [ ] Insert test data (will replicate)
- [ ] Verify real-time replication
- [ ] Test UPDATE operations
- [ ] Test DELETE operations
- [ ] Verify type preservation

### 16.3 Edge Case Testing
- [ ] NULL values for all types
- [ ] Maximum/minimum values
- [ ] Empty arrays/strings
- [ ] Unicode and special characters
- [ ] Large objects (MB+ size)
- [ ] Timezone edge cases

---

## Conclusion

This comprehensive data type coverage document provides:

✅ **Complete Type Mapping**: All 40+ PostgreSQL types documented  
✅ **Test Data Examples**: Real SQL for every type  
✅ **Verification Queries**: How to validate each type  
✅ **Edge Cases**: Known limitations and special handling  
✅ **Coverage Matrix**: Clear visibility of support level  

**Use this document to**:
1. Guide implementation of type conversions
2. Create comprehensive test suites
3. Validate batch dump and CDC replication
4. Document known limitations
5. Train users on supported types
