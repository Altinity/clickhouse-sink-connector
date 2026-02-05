# Edge Cases

This document catalogs edge cases that can cause issues with the ClickHouse Sink Connector, organized by category.

## Overview

Edge cases are boundary conditions and unusual data patterns that may not cause immediate crashes but can lead to data corruption, silent failures, or unexpected behavior.

---

## Character Encoding Edge Cases

### Summary Table

| Case | MySQL Input | Expected CH Output | Actual Behavior | Impact | Workaround |
|------|-------------|-------------------|-----------------|--------|------------|
| Emojis (4-byte UTF-8) | '👋🌍' | '👋🌍' | May be corrupted '??' | Data loss | Use utf8mb4 |
| Zero-width characters | 'Hello​World' (zero-width space) | 'Hello​World' | May display as 'HelloWorld' | Silent corruption | Filter at source |
| RTL text | 'مرحبا' (Arabic) | 'مرحبا' | May reverse 'ابحرم' | Display issues | Check encoding |
| Mixed scripts | '日本語English' | '日本語English' | Corrupted if not utf8mb4 | Partial loss | Ensure utf8mb4 |
| Null bytes (\0) | 'Hello\0World' | 'Hello\0World' | String truncated 'Hello' | Data truncation | Escape nulls |
| Control characters | 'Line1\nLine2' | 'Line1\nLine2' | May break parsing | Format issues | Escape special chars |
| Surrogate pairs | '𝕳𝖊𝖑𝖑𝖔' (mathematical bold) | '𝕳𝖊𝖑𝖑𝖔' | May split pairs | Corruption | Validate UTF-16 |
| Combining diacritics | 'café' (e + ´) | 'café' | May separate 'cafe´' | Visual corruption | Normalize NFC/NFD |
| BOM (Byte Order Mark) | '\uFEFFtext' | 'text' | May keep BOM | Extra character | Strip BOM |
| Invalid UTF-8 sequences | Binary masquerading as UTF-8 | [error] | Silent conversion or crash | Data corruption | Validate encoding |

### Detailed Analysis

#### 1. Emojis and 4-byte UTF-8 Characters

**Problem:**
MySQL `utf8` charset (3-byte max) vs `utf8mb4` (4-byte) encoding mismatch.

**Example:**
```sql
-- MySQL (utf8mb4)
CREATE TABLE messages (
    content VARCHAR(255) CHARACTER SET utf8mb4
);

INSERT INTO messages VALUES ('Hello 👋 World 🌍!');

-- ClickHouse (default String = UTF-8)
-- May store as: 'Hello ?? World ??!' if not handled correctly
```

**Detection:**
```java
public boolean contains4ByteUTF8(String str) {
    for (int i = 0; i < str.length(); i++) {
        if (Character.isHighSurrogate(str.charAt(i))) {
            return true;  // 4-byte UTF-8 detected
        }
    }
    return false;
}
```

**Solution:**
```properties
# Ensure UTF-8 support
clickhouse.string.encoding=UTF8
clickhouse.validate.utf8=true
```

**Validation Query:**
```sql
-- ClickHouse: Check for corrupted emojis
SELECT * FROM messages WHERE content LIKE '%?%' OR content LIKE '%�%';
```

---

#### 2. Null Bytes in Strings

**Problem:**
Null bytes (`\0`) can truncate strings or break binary protocols.

**Example:**
```sql
INSERT INTO data VALUES ('Before\0After');

-- May be stored as: 'Before' (truncated at null byte)
```

**Impact:**
- Data loss after null byte
- Binary protocol issues
- Query parsing errors

**Solution:**
```java
public String sanitizeString(String value) {
    if (value.contains("\0")) {
        log.warn("Null byte detected, replacing with space");
        return value.replace("\0", " ");
    }
    return value;
}
```

---

#### 3. Control Characters

**Problem:**
Line breaks, tabs, and other control characters can break CSV exports, logs, or JSON formatting.

**Example:**
```sql
INSERT INTO logs VALUES ('Line1\nLine2\tColumn');

-- In CSV export: May create extra rows
-- In JSON: May break JSON structure
```

**Solution:**
```java
public String escapeControlChars(String value) {
    return value
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
}
```

---

## Numeric Overflow Edge Cases

### Summary Table

| Type | MySQL Range | ClickHouse Range | Overflow Value | Behavior | Workaround |
|------|-------------|------------------|----------------|----------|------------|
| TINYINT | -128 to 127 | Int8: -128 to 127 | 128 | Overflow error | Use Int16 |
| SMALLINT | -32,768 to 32,767 | Int16: -32,768 to 32,767 | 32,768 | Overflow error | Use Int32 |
| INT | -2³¹ to 2³¹-1 | Int32: -2³¹ to 2³¹-1 | 2³¹ | Overflow error | Use Int64 |
| BIGINT | -2⁶³ to 2⁶³-1 | Int64: -2⁶³ to 2⁶³-1 | 2⁶³ | Overflow error | Use Int128 |
| UNSIGNED BIGINT | 0 to 2⁶⁴-1 | UInt64: 0 to 2⁶⁴-1 | 2⁶⁴ | Overflow error | Use UInt128 |
| FLOAT | ±3.4E+38 | Float32: ±3.4E+38 | Infinity | Stores as Inf | Validate range |
| DOUBLE | ±1.7E+308 | Float64: ±1.7E+308 | Infinity | Stores as Inf | Validate range |
| DECIMAL(65,30) | Very large | Decimal128(38) max | Overflow | Precision loss | Use Decimal256 |
| Auto-increment near max | 2³¹-1 | N/A | Wraparound | ID collision | Migrate to BIGINT |

### Detailed Analysis

#### 1. Integer Overflow

**Problem:**
Values at type boundaries can cause overflow errors or unexpected behavior.

**Example:**
```sql
-- MySQL
CREATE TABLE stats (counter INT);

-- Insert max value
INSERT INTO stats VALUES (2147483647);  -- INT max

-- Increment in app (if not handled)
UPDATE stats SET counter = counter + 1;  -- Overflow!

-- ClickHouse may:
-- Option A: Throw overflow error
-- Option B: Wrap around to -2147483648 (dangerous!)
```

**Detection:**
```java
public void validateIntRange(long value, String columnType) {
    long min, max;
    
    switch (columnType) {
        case "Int8":  min = -128; max = 127; break;
        case "Int16": min = -32768; max = 32767; break;
        case "Int32": min = -2147483648L; max = 2147483647L; break;
        case "Int64": min = Long.MIN_VALUE; max = Long.MAX_VALUE; break;
        default: return;
    }
    
    if (value < min || value > max) {
        throw new DataException(String.format(
            "Value %d out of range for %s [%d, %d]", 
            value, columnType, min, max
        ));
    }
}
```

**Solution:**
```sql
-- Use wider types
CREATE TABLE stats (
    counter Int64  -- Instead of Int32
) ENGINE = MergeTree() ORDER BY tuple();
```

---

#### 2. Floating Point Special Values

**Problem:**
`NaN`, `Infinity`, `-Infinity` may not be handled consistently.

**Example:**
```sql
-- MySQL calculation
SELECT 0.0 / 0.0;  -- NaN
SELECT 1.0 / 0.0;  -- Infinity

-- ClickHouse storage
INSERT INTO metrics VALUES (CAST('NaN' AS Float64));
INSERT INTO metrics VALUES (CAST('Inf' AS Float64));
```

**Behavior:**
```sql
-- ClickHouse
SELECT isNaN(value) FROM metrics;  -- true for NaN
SELECT isInfinite(value) FROM metrics;  -- true for Inf

-- Comparisons
SELECT value > 1000 FROM metrics WHERE isInfinite(value);  -- false (unexpected!)

-- Aggregations
SELECT avg(value) FROM metrics;  -- NaN if any value is NaN
```

**Solution:**
```java
public Float64 sanitizeFloat(double value) {
    if (Double.isNaN(value)) {
        String nanBehavior = config.getNaNBehavior();
        switch (nanBehavior) {
            case "NULL": return null;
            case "ZERO": return 0.0;
            case "FAIL": throw new DataException("NaN not allowed");
            default: return value;
        }
    }
    
    if (Double.isInfinite(value)) {
        String infBehavior = config.getInfinityBehavior();
        switch (infBehavior) {
            case "NULL": return null;
            case "MAX": return value > 0 ? Double.MAX_VALUE : -Double.MAX_VALUE;
            case "FAIL": throw new DataException("Infinity not allowed");
            default: return value;
        }
    }
    
    return value;
}
```

---

#### 3. Decimal Precision Loss

**Problem:**
MySQL `DECIMAL(65,30)` exceeds ClickHouse `Decimal128(38)` maximum precision.

**Example:**
```sql
-- MySQL: 65 digits total, 30 after decimal
CREATE TABLE prices (
    amount DECIMAL(65, 30)
);

INSERT INTO prices VALUES (12345678901234567890123456789012345.123456789012345678901234567890);

-- ClickHouse: Max Decimal128(38) = 38 digits total
-- Precision loss or overflow!
```

**Precision Comparison:**

| Type | Max Precision | Max Value |
|------|---------------|-----------|
| Decimal32 | 9 | ±999,999,999 |
| Decimal64 | 18 | ±999,999,999,999,999,999 |
| Decimal128 | 38 | ±10³⁸ - 1 |
| Decimal256 | 76 | ±10⁷⁶ - 1 |
| MySQL DECIMAL | 65 | ±10⁶⁵ - 1 |

**Solution:**
```sql
-- Option 1: Use Decimal256
CREATE TABLE prices (
    amount Decimal256(76)  -- Wider than MySQL's 65
) ENGINE = MergeTree() ORDER BY tuple();

-- Option 2: Split into multiple columns
CREATE TABLE prices (
    amount_int Int128,    -- Integer part
    amount_frac Int128    -- Fractional part
) ENGINE = MergeTree() ORDER BY tuple();
```

---

## Date/Time Edge Cases

### Summary Table

| Case | MySQL Value | ClickHouse Type | Behavior | Impact | Workaround |
|------|-------------|-----------------|----------|--------|------------|
| Zero date | 0000-00-00 | Date | Parse error | Crash | Convert to epoch |
| Pre-1970 date | 1969-12-31 | Date | Out of range | Crash | Use DateTime64 |
| Far future date | 2150-01-01 | Date | Out of range | Crash | Use DateTime64 |
| Leap seconds | 23:59:60 | DateTime | Not supported | Rounded to 59 | Accept rounding |
| DST transitions | 2023-03-12 02:30 | DateTime | Ambiguous | May shift 1 hour | Use UTC |
| Time zones | 2023-01-01 00:00 +08:00 | DateTime | Lost timezone | Time shift | Store timezone separately |
| Microsecond precision | 2023-01-01 00:00:00.123456 | DateTime64(6) | Preserved | None | Use DateTime64 |
| Negative timestamp | -1 (1969-12-31) | DateTime | Invalid | Crash | Validate range |
| Year 2038 problem | 2038-01-19 03:14:08 | DateTime (Int32) | Overflow | Crash | Use DateTime64 |
| Fractional seconds | 12:34:56.789 | Time | Lost precision | Data loss | Use DateTime64 |

### Detailed Analysis

#### 1. Zero Date (0000-00-00)

**Problem:**
MySQL allows zero dates with permissive `sql_mode`, but Java and ClickHouse reject them.

**Example:**
```sql
-- MySQL (with sql_mode allowing zero dates)
SET sql_mode = '';

CREATE TABLE orders (order_date DATE);
INSERT INTO orders VALUES ('0000-00-00');  -- Valid!

-- Connector: CRASH
-- java.time.format.DateTimeParseException: Invalid value for Year: 0
```

**Frequency:**
- Very common in legacy MySQL databases
- Often used to indicate "no date"
- May appear in default values

**Solution:**
```java
public LocalDate convertDate(String dateStr) {
    // Check for zero dates
    if ("0000-00-00".equals(dateStr) || 
        "0000-00-00 00:00:00".equals(dateStr)) {
        
        String behavior = config.getZeroDateBehavior();
        
        switch (behavior) {
            case "NULL":
                return null;  // Requires Nullable(Date) column
            case "EPOCH":
                return LocalDate.of(1970, 1, 1);
            case "MIN_DATE":
                return LocalDate.of(1000, 1, 1);  // MySQL min
            case "FAIL":
                throw new DataException("Zero date not allowed");
            default:
                throw new ConfigException("Invalid zero.date.behavior");
        }
    }
    
    return LocalDate.parse(dateStr);
}
```

---

#### 2. Date Range Limitations

**Problem:**
ClickHouse `Date` type limited to 1970-01-01 through 2149-06-06.

**ClickHouse Date Types:**

| Type | Range | Storage | Use Case |
|------|-------|---------|----------|
| Date | 1970-01-01 to 2149-06-06 | 2 bytes | Recent dates only |
| Date32 | 1900-01-01 to 2299-12-31 | 4 bytes | Historical dates |
| DateTime | 1970-01-01 to 2106-02-07 | 4 bytes (Unix timestamp) | Timestamps |
| DateTime64 | 1900-01-01 to 2299-12-31 | 8 bytes | Wide range + microseconds |

**Example:**
```sql
-- MySQL: Wide range
INSERT INTO events VALUES ('1850-01-01');  -- Historical
INSERT INTO events VALUES ('2200-01-01');  -- Far future

-- ClickHouse Date: CRASH on both!
```

**Solution:**
```sql
-- Use Date32 or DateTime64
CREATE TABLE events (
    event_date Date32  -- Supports 1900-2299
) ENGINE = MergeTree() ORDER BY event_date;

-- Or use DateTime64 for maximum flexibility
CREATE TABLE events (
    event_date DateTime64(3)  -- Supports 1900-2299 + milliseconds
) ENGINE = MergeTree() ORDER BY event_date;
```

---

#### 3. Timezone Handling

**Problem:**
MySQL `TIMESTAMP` stores timezone, but ClickHouse `DateTime` doesn't preserve it.

**Example:**
```sql
-- MySQL
CREATE TABLE events (
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Session in Tokyo (UTC+9)
SET time_zone = '+09:00';
INSERT INTO events VALUES ('2023-01-01 00:00:00');  -- Midnight Tokyo time

-- Stored in UTC: 2022-12-31 15:00:00

-- ClickHouse
-- Receives: '2022-12-31 15:00:00'
-- Stores without timezone awareness
-- Query in different timezone may show wrong time!
```

**Solution:**
```sql
-- Option 1: Always use UTC
SET time_zone = '+00:00';  -- MySQL

CREATE TABLE events (
    created_at DateTime('UTC')  -- ClickHouse
) ENGINE = MergeTree() ORDER BY created_at;

-- Option 2: Store timezone separately
CREATE TABLE events (
    created_at DateTime,
    timezone String  -- 'America/New_York'
) ENGINE = MergeTree() ORDER BY created_at;
```

---

#### 4. DST (Daylight Saving Time) Transitions

**Problem:**
During DST transitions, some times occur twice (fall back) or not at all (spring forward).

**Example:**
```sql
-- US DST spring forward: 2023-03-12 02:00 -> 03:00
-- Time 02:30 doesn't exist!

INSERT INTO events VALUES ('2023-03-12 02:30:00');

-- Different systems may interpret as:
-- 01:30 (before DST)
-- 03:30 (after DST)
-- Error (time doesn't exist)
```

**Solution:**
Use UTC everywhere to avoid DST issues:
```properties
# MySQL
default-time-zone='+00:00'

# ClickHouse
clickhouse.timezone=UTC

# Application
java.util.TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
```

---

## Binary/BLOB Size Limits

### Summary Table

| Type | MySQL Max | ClickHouse Type | CH Max | Behavior | Impact |
|------|-----------|-----------------|--------|----------|--------|
| TINYBLOB | 255 bytes | String | 4 GB | Fits | None |
| BLOB | 64 KB | String | 4 GB | Fits | None |
| MEDIUMBLOB | 16 MB | String | 4 GB | Fits | May hit memory limits |
| LONGBLOB | 4 GB | String | 4 GB | May truncate | Data loss |
| BINARY(n) | n bytes | FixedString(n) | 4 GB | Fits | Encoding issues |
| VARBINARY(n) | n bytes | String | 4 GB | Fits | Encoding issues |

### Detailed Analysis

#### 1. Large BLOB Performance

**Problem:**
Large BLOBs (>16MB) can cause memory issues and slow performance.

**Example:**
```sql
-- MySQL
CREATE TABLE files (
    id INT,
    data LONGBLOB  -- Up to 4GB
);

INSERT INTO files VALUES (1, LOAD_FILE('/path/to/large/file.bin'));  -- 500MB file
```

**Impact:**
- **Memory:** Entire BLOB loaded into memory
- **Network:** Large network transfers
- **Serialization:** Slow JSON/Avro serialization
- **ClickHouse:** May hit `max_insert_block_size` limits

**Metrics:**
```sql
-- Check large BLOBs in ClickHouse
SELECT 
    table,
    formatReadableSize(sum(data_compressed_bytes)) as compressed,
    formatReadableSize(sum(data_uncompressed_bytes)) as uncompressed,
    sum(data_uncompressed_bytes) / sum(rows) as avg_row_size
FROM system.parts
WHERE database = 'replicated_db'
GROUP BY table
HAVING avg_row_size > 1048576  -- > 1MB average
ORDER BY avg_row_size DESC;
```

**Solution:**
```properties
# Connector configuration
clickhouse.max.blob.size=16777216  # 16MB max
clickhouse.blob.handling=FAIL  # FAIL | TRUNCATE | SKIP | EXTERNAL

# External storage
clickhouse.blob.external.storage=s3://bucket/blobs
clickhouse.blob.external.url.column=blob_url
```

**Alternative: Store references**
```sql
-- Instead of storing BLOB in ClickHouse
CREATE TABLE files (
    id Int32,
    blob_url String,  -- s3://bucket/file-123.bin
    blob_size Int64,
    blob_hash String  -- SHA256 checksum
) ENGINE = MergeTree() ORDER BY id;
```

---

#### 2. Binary Data Encoding

**Problem:**
Binary data (BINARY, VARBINARY) corrupted when treated as UTF-8 strings.

**Example:**
```sql
-- MySQL
CREATE TABLE hashes (
    id INT,
    hash BINARY(32)  -- SHA256 hash (raw bytes)
);

INSERT INTO hashes VALUES (1, UNHEX('8f4b2c1a9d3e5f7b8c1a2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a'));

-- ClickHouse (if treated as String)
-- Binary bytes: 8F 4B 2C 1A 9D 3E 5F 7B ...
-- UTF-8 string: "�K,��>_{ ... " (CORRUPTED!)
```

**Solution:**
```java
public String convertBinary(byte[] bytes, String handling) {
    switch (handling) {
        case "HEX":
            // Store as hex string
            return bytesToHex(bytes);
            
        case "BASE64":
            // Store as base64
            return Base64.getEncoder().encodeToString(bytes);
            
        case "RAW":
            // Store as FixedString (preserves bytes)
            return new String(bytes, StandardCharsets.ISO_8859_1);
            
        default:
            throw new ConfigException("Invalid binary handling: " + handling);
    }
}

private String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
        sb.append(String.format("%02x", b));
    }
    return sb.toString();
}
```

**ClickHouse Schema:**
```sql
-- Option 1: Hex string (human-readable)
CREATE TABLE hashes (
    id Int32,
    hash String  -- '8f4b2c1a9d3e5f7b...'
) ENGINE = MergeTree() ORDER BY id;

-- Option 2: FixedString (preserve bytes)
CREATE TABLE hashes (
    id Int32,
    hash FixedString(32)  -- Exactly 32 bytes
) ENGINE = MergeTree() ORDER BY id;
```

---

## Spatial/GIS Type Support

### Summary Table

| MySQL Type | Supported | ClickHouse Equivalent | Notes |
|------------|-----------|----------------------|-------|
| GEOMETRY | ✗ | String (WKT) | Lost spatial indexing |
| POINT | ✗ | Tuple(Float64, Float64) | Lost type safety |
| LINESTRING | ✗ | Array(Tuple(Float64, Float64)) | No spatial functions |
| POLYGON | ✗ | Array(Array(Tuple(Float64, Float64))) | Complex representation |
| MULTIPOINT | ✗ | String (WKT) | No native support |
| MULTILINESTRING | ✗ | String (WKT) | No native support |
| MULTIPOLYGON | ✗ | String (WKT) | No native support |
| GEOMETRYCOLLECTION | ✗ | String (WKT) | No native support |

### Detailed Analysis

#### 1. Spatial Data Conversion

**Problem:**
MySQL spatial types have no direct ClickHouse equivalent.

**Example:**
```sql
-- MySQL
CREATE TABLE locations (
    id INT,
    name VARCHAR(100),
    coordinates POINT
);

INSERT INTO locations VALUES 
    (1, 'New York', ST_GeomFromText('POINT(-74.0060 40.7128)'));

-- Current behavior: Converted to String
-- Lost: Spatial indexing, ST_Distance(), ST_Contains(), etc.
```

**Conversion Options:**

**Option 1: WKT String**
```sql
-- ClickHouse
CREATE TABLE locations (
    id Int32,
    name String,
    coordinates String  -- 'POINT(-74.0060 40.7128)'
) ENGINE = MergeTree() ORDER BY id;

-- Queries require parsing
SELECT * FROM locations 
WHERE coordinates LIKE '%40.7128%';  -- Slow, no spatial index!
```

**Option 2: Coordinate Columns**
```sql
-- ClickHouse
CREATE TABLE locations (
    id Int32,
    name String,
    lat Float64,
    lon Float64
) ENGINE = MergeTree() ORDER BY (id);

-- Enable spatial queries
SELECT * FROM locations
WHERE lat BETWEEN 40.0 AND 41.0
  AND lon BETWEEN -75.0 AND -73.0;

-- Distance calculation
SELECT 
    name,
    sqrt(pow(lat - 40.7128, 2) + pow(lon - (-74.0060), 2)) as distance
FROM locations
ORDER BY distance
LIMIT 10;
```

**Option 3: GeoHash or H3**
```sql
-- ClickHouse with geohash
CREATE TABLE locations (
    id Int32,
    name String,
    lat Float64,
    lon Float64,
    geohash String  -- Encoded location
) ENGINE = MergeTree() 
ORDER BY geohash;  -- Spatial index!

-- Insert with geohash
INSERT INTO locations 
SELECT 
    id, 
    name, 
    lat, 
    lon,
    geohashEncode(lon, lat, 8) as geohash
FROM source;

-- Spatial queries
SELECT * FROM locations
WHERE geohash LIKE 'dr5ru%';  -- Nearby locations
```

---

#### 2. Spatial Functions

**MySQL vs ClickHouse:**

| Function | MySQL | ClickHouse |
|----------|-------|------------|
| Distance | ST_Distance() | Manual calculation or geohashDistance() |
| Contains | ST_Contains() | pointInPolygon() |
| Intersects | ST_Intersects() | Not supported |
| Buffer | ST_Buffer() | Not supported |
| Area | ST_Area() | Manual calculation |
| Length | ST_Length() | Manual calculation |

**ClickHouse Spatial Functions:**
```sql
-- Point in polygon
SELECT pointInPolygon((lon, lat), [
    (-74.1, 40.6),
    (-74.1, 40.8),
    (-73.9, 40.8),
    (-73.9, 40.6),
    (-74.1, 40.6)
]) as is_in_area
FROM locations;

-- Distance (haversine formula)
SELECT greatCircleDistance(
    lon1, lat1,
    lon2, lat2
) as distance_meters
FROM pairs;

-- Geohash operations
SELECT geohashDistance('dr5ru6p', 'dr5ru7m') as distance;
```

---

## Edge Case Testing Checklist

### Character Encoding
- [ ] Test emojis (👋, 🌍, 💻)
- [ ] Test CJK characters (日本語, 中文, 한국어)
- [ ] Test RTL scripts (العربية, עברית)
- [ ] Test null bytes in strings
- [ ] Test control characters (\n, \r, \t)
- [ ] Test zero-width characters
- [ ] Test combining diacritics
- [ ] Test invalid UTF-8 sequences

### Numeric Overflow
- [ ] Test INT max value (2,147,483,647)
- [ ] Test BIGINT max value
- [ ] Test unsigned overflow
- [ ] Test float NaN and Infinity
- [ ] Test decimal precision loss
- [ ] Test very small floats (underflow)
- [ ] Test negative zero (-0.0)

### Date/Time
- [ ] Test zero date (0000-00-00)
- [ ] Test pre-1970 dates
- [ ] Test post-2149 dates
- [ ] Test leap seconds
- [ ] Test DST transitions
- [ ] Test timezone conversions
- [ ] Test microsecond precision
- [ ] Test year 2038 boundary

### Binary/BLOB
- [ ] Test small BLOBs (<1KB)
- [ ] Test medium BLOBs (1-16MB)
- [ ] Test large BLOBs (>16MB)
- [ ] Test BINARY encoding
- [ ] Test null bytes in binary
- [ ] Test empty BLOBs

### Spatial
- [ ] Test POINT conversion
- [ ] Test POLYGON conversion
- [ ] Test spatial queries
- [ ] Test coordinate precision

---

## Monitoring Recommendations

```sql
-- ClickHouse queries to detect edge cases

-- 1. Detect corrupted UTF-8
SELECT table, count(*) as corrupted_rows
FROM system.tables AS t
ARRAY JOIN ['�', '??', '\uFFFD'] AS marker
WHERE isNotEmpty(
    SELECT 1 FROM t.table_name 
    WHERE string_column LIKE '%' || marker || '%'
    LIMIT 1
)
GROUP BY table;

-- 2. Detect special float values
SELECT 
    count(*) as total,
    countIf(isNaN(value)) as nan_count,
    countIf(isInfinite(value)) as inf_count
FROM metrics;

-- 3. Detect date range issues
SELECT 
    min(date_column) as min_date,
    max(date_column) as max_date,
    countIf(date_column < '1970-01-01') as pre_epoch,
    countIf(date_column > '2149-06-06') as post_limit
FROM events;

-- 4. Detect large BLOBs
SELECT 
    table,
    formatReadableSize(max(length(blob_column))) as max_size,
    avg(length(blob_column)) as avg_size
FROM tables
GROUP BY table
ORDER BY max_size DESC;
```

---

**Related Documents:**
- [Data Type Bugs](./DATA-TYPE-BUGS.md) - Type conversion issues
- [Crash Scenarios](./CRASH-SCENARIOS.md) - Edge cases that crash
- [Fix Priority](./FIXES-PRIORITY.md) - Implementation roadmap
