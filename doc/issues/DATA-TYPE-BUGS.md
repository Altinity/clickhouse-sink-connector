# Data Type Conversion Bugs

This document details all data type handling and conversion issues that can cause crashes, data corruption, or silent data loss.

## Overview

**Total Bugs:** 8  
**Severity:** CRITICAL (3), HIGH (5)  
**Affected Component:** Type conversion, NULL handling, encoding  
**Production Impact:** Crashes, data loss, silent failures

## Summary Table

| ID | Issue | Severity | Impact | Workaround |
|----|-------|----------|--------|------------|
| BUG-DATA-1 | NULL Handling Crash | CRITICAL | Connector crash | Use non-nullable schemas |
| BUG-DATA-2 | Unmapped Types Silent Fail | CRITICAL | Data loss | Manual type mapping |
| BUG-DATA-3 | Missing ENUM/SET Support | HIGH | Type mismatch | Convert to String |
| BUG-DATA-4 | Date Range Overflow | HIGH | Invalid dates | Validate date ranges |
| BUG-DATA-5 | Zero Date Crash | HIGH | Connector crash | Avoid 0000-00-00 dates |
| BUG-DATA-6 | Binary String Encoding | HIGH | Data corruption | Use BLOB type |
| BUG-DATA-7 | Decimal Precision Loss | MEDIUM | Silent data loss | Use larger scale |
| BUG-DATA-8 | Emoji/4-byte UTF-8 Issues | HIGH | Character corruption | Use utf8mb4 |

---

## BUG-DATA-1: NULL Handling Crash in Non-Nullable Columns

**Severity:** CRITICAL  
**Location:** [`ClickHouseConverter.java:180-200`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

### Current Code

```java
public class ClickHouseConverter {
    public Object convert(Object value, Schema schema, ClickHouseDataType targetType) {
        // Line 180-200
        if (value == null) {
            // No validation if target column is nullable!
            return null;  // This crashes when column is NOT NULL
        }
        
        return convertValue(value, targetType);
    }
}
```

### Problem

The converter does not check if NULL values are being inserted into non-nullable ClickHouse columns. ClickHouse throws exception when NULL is inserted into a non-nullable column.

**Missing Validation:**
```java
// Should check target column's nullability
if (value == null && !targetColumn.isNullable()) {
    throw new DataException("Cannot insert NULL into non-nullable column");
}
```

### Impact

- **Immediate Crash:** Connector stops on first NULL value
- **Data Loss:** All subsequent records in batch lost
- **Silent Failure:** Error may not be obvious from logs
- **Offset Not Committed:** Records reprocessed on restart

### Crash Stack Trace

```
com.clickhouse.client.ClickHouseException: Code: 53, 
  e.displayText() = DB::Exception: Attempt to insert NULL value into non-nullable column 'email'
  
  at com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable.run()
  at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker()
  ...
```

### Reproduction

```sql
-- MySQL source table (nullable column)
CREATE TABLE users (
    id INT PRIMARY KEY,
    email VARCHAR(255)  -- NULL allowed
);

INSERT INTO users VALUES (1, NULL);  -- Valid in MySQL

-- ClickHouse target table (non-nullable)
CREATE TABLE users (
    id Int32,
    email String  -- NOT NULL by default in ClickHouse
) ENGINE = MergeTree() ORDER BY id;

-- Result: Connector crashes with "Attempt to insert NULL"
```

### Proposed Fix

**Option 1: Validation with Exception (Recommended)**
```java
public class ClickHouseConverter {
    public Object convert(Object value, Schema schema, ClickHouseColumn targetColumn) {
        if (value == null) {
            if (!targetColumn.isNullable()) {
                throw new DataException(String.format(
                    "Cannot insert NULL value into non-nullable column '%s' of type '%s'. " +
                    "Either make the ClickHouse column Nullable or provide a default value.",
                    targetColumn.getName(), targetColumn.getType()
                ));
            }
            return null;
        }
        
        return convertValue(value, targetColumn.getType());
    }
}
```

**Option 2: Default Value Substitution**
```java
public Object convert(Object value, Schema schema, ClickHouseColumn targetColumn) {
    if (value == null) {
        if (!targetColumn.isNullable()) {
            // Use configured default or type-specific default
            Object defaultValue = config.getDefaultValue(targetColumn.getName());
            if (defaultValue != null) {
                log.warn("Substituting NULL with default value {} for column {}", 
                    defaultValue, targetColumn.getName());
                return defaultValue;
            }
            // Use type-specific defaults
            return getTypeDefault(targetColumn.getType());
        }
        return null;
    }
    
    return convertValue(value, targetColumn.getType());
}

private Object getTypeDefault(ClickHouseDataType type) {
    switch (type) {
        case Int32: return 0;
        case String: return "";
        case Float64: return 0.0;
        case Date: return LocalDate.of(1970, 1, 1);
        default: throw new DataException("No default for type " + type);
    }
}
```

### Configuration Option

Add connector configuration:
```properties
# Behavior when NULL encountered in non-nullable column
clickhouse.null.handling=FAIL  # FAIL | DEFAULT | SKIP_RECORD

# Default values for specific columns
clickhouse.defaults.email=""
clickhouse.defaults.age=0
```

### Testing Requirements

1. Test NULL insertion into non-nullable column
2. Test NULL insertion into nullable column
3. Test default value substitution
4. Test error message clarity
5. Verify proper error propagation

---

## BUG-DATA-2: Unmapped Data Types Silently Fail

**Severity:** CRITICAL  
**Location:** [`ClickHouseDataTypeMapper.java:45-80`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/mapper/ClickHouseDataTypeMapper.java)

### Current Code

```java
public class ClickHouseDataTypeMapper {
    public ClickHouseDataType mapType(String mysqlType) {
        // Line 45-80
        switch (mysqlType.toUpperCase()) {
            case "INT": return ClickHouseDataType.Int32;
            case "VARCHAR": return ClickHouseDataType.String;
            case "DATETIME": return ClickHouseDataType.DateTime;
            // ... other mappings ...
            
            default:
                log.warn("Unknown MySQL type: {}, defaulting to String", mysqlType);
                return ClickHouseDataType.String;  // SILENT FAILURE!
        }
    }
}
```

### Problem

Unmapped or unknown data types silently default to `String`, causing:
1. **Data Loss:** Structured data (JSON, GEOMETRY) converted to string
2. **Type Mismatch:** Numeric types stored as strings
3. **Silent Failure:** No error raised, hard to debug
4. **Query Issues:** String columns can't be used in numeric operations

### Impact

**Example 1: JSON Type**
```sql
-- MySQL
CREATE TABLE events (
    id INT,
    metadata JSON  -- Rich structured data
);

INSERT INTO events VALUES (1, '{"user": "john", "action": "login"}');

-- ClickHouse (after silent conversion)
metadata String  -- Lost JSON structure, can't query nested fields
SELECT metadata.user FROM events;  -- FAILS!
```

**Example 2: GEOMETRY Type**
```sql
-- MySQL
CREATE TABLE locations (
    id INT,
    point GEOMETRY  -- Spatial data
);

-- ClickHouse (after silent conversion)
point String  -- Lost spatial indexing, can't do spatial queries
```

### Affected Types

| MySQL Type | Current Behavior | Impact |
|------------|------------------|--------|
| JSON | → String | Lost structure, no nested queries |
| GEOMETRY | → String | Lost spatial functions |
| POINT | → String | No geometric operations |
| LINESTRING | → String | No spatial indexing |
| POLYGON | → String | No area calculations |
| ENUM | → String | Lost type safety |
| SET | → String | Lost set operations |
| BIT | → String | Lost bitwise operations |
| BINARY | → String | Encoding corruption |
| VARBINARY | → String | Data corruption |

### Proposed Fix

**Option 1: Fail Fast (Recommended for Production)**
```java
public class ClickHouseDataTypeMapper {
    private static final Set<String> UNSUPPORTED_TYPES = Set.of(
        "JSON", "GEOMETRY", "POINT", "LINESTRING", "POLYGON", 
        "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION"
    );
    
    public ClickHouseDataType mapType(String mysqlType, String columnName) {
        String normalizedType = mysqlType.toUpperCase();
        
        // Check if type is explicitly unsupported
        if (UNSUPPORTED_TYPES.contains(normalizedType)) {
            throw new DataException(String.format(
                "MySQL type '%s' for column '%s' is not supported. " +
                "Supported types: INT, BIGINT, VARCHAR, TEXT, DATE, DATETIME, DECIMAL, etc. " +
                "See documentation for full list.",
                mysqlType, columnName
            ));
        }
        
        switch (normalizedType) {
            case "INT": case "INTEGER": return ClickHouseDataType.Int32;
            case "BIGINT": return ClickHouseDataType.Int64;
            case "VARCHAR": case "TEXT": return ClickHouseDataType.String;
            case "DATETIME": case "TIMESTAMP": return ClickHouseDataType.DateTime;
            case "DATE": return ClickHouseDataType.Date;
            case "DECIMAL": return ClickHouseDataType.Decimal;
            case "FLOAT": return ClickHouseDataType.Float32;
            case "DOUBLE": return ClickHouseDataType.Float64;
            
            default:
                // Unknown type - fail with helpful message
                throw new DataException(String.format(
                    "Unknown MySQL type '%s' for column '%s'. " +
                    "Please add explicit mapping in connector configuration or " +
                    "file a bug report if this type should be supported.",
                    mysqlType, columnName
                ));
        }
    }
}
```

**Option 2: Configurable Fallback**
```java
public ClickHouseDataType mapType(String mysqlType, String columnName, Config config) {
    String normalizedType = mysqlType.toUpperCase();
    
    // Try explicit mapping first
    ClickHouseDataType mapped = tryMap(normalizedType);
    if (mapped != null) {
        return mapped;
    }
    
    // Check configuration for custom mapping
    String customMapping = config.getTypeMapping(columnName);
    if (customMapping != null) {
        return ClickHouseDataType.valueOf(customMapping);
    }
    
    // Check fallback behavior
    String fallbackBehavior = config.getUnmappedTypeBehavior(); // FAIL | WARN | STRING
    
    switch (fallbackBehavior) {
        case "FAIL":
            throw new DataException("Unmapped type: " + mysqlType);
            
        case "WARN":
            log.error("CRITICAL: Unmapped type '{}' for column '{}', defaulting to String. " +
                     "This may cause data loss!", mysqlType, columnName);
            metrics.incrementUnmappedTypeCount();
            return ClickHouseDataType.String;
            
        case "STRING":
            log.warn("Unmapped type '{}' for column '{}', using String", mysqlType, columnName);
            return ClickHouseDataType.String;
            
        default:
            throw new DataException("Invalid unmapped.type.behavior: " + fallbackBehavior);
    }
}
```

### Configuration

```properties
# How to handle unmapped MySQL types
clickhouse.unmapped.type.behavior=FAIL  # FAIL | WARN | STRING

# Custom type mappings
clickhouse.type.mapping.json=String  # Explicit: store JSON as String
clickhouse.type.mapping.geometry=String  # Explicit: store GEOMETRY as String

# Or fail on specific types
clickhouse.type.blacklist=JSON,GEOMETRY,POINT
```

### Testing Requirements

1. Test all supported MySQL types
2. Test unmapped type handling
3. Test custom type mappings
4. Verify error messages include type and column name
5. Test metrics for unmapped types

---

## BUG-DATA-3: Missing ENUM/SET Support

**Severity:** HIGH  
**Location:** [`ClickHouseDataTypeMapper.java:45-80`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/mapper/ClickHouseDataTypeMapper.java)

### Problem

MySQL `ENUM` and `SET` types are not mapped to ClickHouse equivalents.

**MySQL:**
```sql
CREATE TABLE users (
    status ENUM('active', 'inactive', 'suspended'),
    permissions SET('read', 'write', 'admin')
);
```

**Current Behavior:** Both converted to String, losing type safety.

### Proposed Fix

```java
public ClickHouseDataType mapType(String mysqlType, String typeDefinition) {
    if (mysqlType.startsWith("ENUM")) {
        // Extract enum values: ENUM('a','b','c')
        String[] values = parseEnumValues(typeDefinition);
        return new ClickHouseEnum8(values);  // or Enum16 if > 256 values
    }
    
    if (mysqlType.startsWith("SET")) {
        // SET not directly supported - use Array(String) or String
        log.warn("MySQL SET type converted to Array(String)");
        return ClickHouseDataType.ArrayString;
    }
    
    // ... other mappings
}
```

---

## BUG-DATA-4: Date Range Overflow Not Validated

**Severity:** HIGH  
**Location:** [`ClickHouseConverter.java:220-240`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

### Problem

ClickHouse `Date` type supports 1970-01-01 to 2149-06-06, but MySQL `DATE` supports 1000-01-01 to 9999-12-31. Out-of-range dates cause errors.

### Current Code

```java
public Object convertDate(Object value) {
    // No range validation!
    return LocalDate.parse(value.toString());
}
```

### Impact

```sql
-- MySQL
INSERT INTO events VALUES (1, '1969-12-31');  -- Valid in MySQL
INSERT INTO events VALUES (2, '2150-01-01');  -- Valid in MySQL

-- ClickHouse: CRASH!
-- Code: 41, e.displayText() = DB::Exception: Date is out of range
```

### Proposed Fix

```java
public Object convertDate(Object value, ClickHouseColumn column) {
    LocalDate date = parseDate(value);
    
    // ClickHouse Date valid range
    LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    LocalDate MAX_DATE = LocalDate.of(2149, 6, 6);
    
    if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
        String behavior = config.getDateRangeOverflowBehavior();
        
        switch (behavior) {
            case "FAIL":
                throw new DataException(String.format(
                    "Date %s out of ClickHouse Date range [%s, %s] for column %s",
                    date, MIN_DATE, MAX_DATE, column.getName()
                ));
                
            case "CLAMP":
                LocalDate clamped = date.isBefore(MIN_DATE) ? MIN_DATE : MAX_DATE;
                log.warn("Date {} out of range, clamped to {}", date, clamped);
                return clamped;
                
            case "USE_DATETIME":
                // Automatically upgrade to DateTime64 which has wider range
                log.info("Date {} out of range, using DateTime64 instead", date);
                return date.atStartOfDay();
                
            default:
                throw new ConfigException("Invalid date.range.overflow: " + behavior);
        }
    }
    
    return date;
}
```

### Configuration

```properties
clickhouse.date.range.overflow=FAIL  # FAIL | CLAMP | USE_DATETIME
```

---

## BUG-DATA-5: Zero Date (0000-00-00) Crash

**Severity:** HIGH  
**Location:** [`ClickHouseConverter.java:220-240`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

### Problem

MySQL allows "zero dates" (`0000-00-00`), but Java `LocalDate` and ClickHouse reject them.

### Impact

```sql
-- MySQL (with sql_mode allowing zero dates)
INSERT INTO orders VALUES (1, '0000-00-00');  -- Valid in MySQL

-- Connector: CRASH!
java.time.format.DateTimeParseException: Invalid value for Year (valid values 1 - 999999999): 0
```

### Proposed Fix

```java
public Object convertDate(Object value) {
    String dateStr = value.toString();
    
    // Handle MySQL zero dates
    if ("0000-00-00".equals(dateStr) || "0000-00-00 00:00:00".equals(dateStr)) {
        String zeroDateBehavior = config.getZeroDateBehavior();
        
        switch (zeroDateBehavior) {
            case "NULL":
                return null;  // Requires Nullable column
                
            case "EPOCH":
                return LocalDate.of(1970, 1, 1);
                
            case "MIN_DATE":
                return LocalDate.of(1000, 1, 1);  // MySQL min date
                
            case "FAIL":
                throw new DataException("Zero date not allowed: " + dateStr);
                
            default:
                throw new ConfigException("Invalid zero.date.behavior: " + zeroDateBehavior);
        }
    }
    
    return LocalDate.parse(dateStr);
}
```

### Configuration

```properties
clickhouse.zero.date.behavior=EPOCH  # NULL | EPOCH | MIN_DATE | FAIL
```

---

## BUG-DATA-6: Binary String Encoding Issues

**Severity:** HIGH  
**Location:** [`ClickHouseConverter.java:150-170`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

### Problem

MySQL `BINARY` and `VARBINARY` types contain raw bytes, but connector converts them to String, causing encoding corruption.

### Current Code

```java
public Object convertBinary(Object value) {
    // WRONG: Treats binary data as string!
    return value.toString();  // Encoding corruption
}
```

### Impact

```sql
-- MySQL
CREATE TABLE files (
    id INT,
    data BINARY(16)  -- e.g., UUID bytes, hash
);

INSERT INTO files VALUES (1, 0x8F4B2C1A9D3E5F7B);

-- ClickHouse: Data corrupted by string encoding
-- Binary: 8F 4B 2C 1A 9D 3E 5F 7B
-- String: "�K,��>_{"  -- CORRUPTED!
```

### Proposed Fix

```java
public Object convertBinary(Object value, ClickHouseColumn column) {
    if (value instanceof byte[]) {
        byte[] bytes = (byte[]) value;
        
        String binaryHandling = config.getBinaryHandling();
        
        switch (binaryHandling) {
            case "BYTES":
                // Use ClickHouse FixedString or String for binary data
                return bytes;  // Preserve raw bytes
                
            case "HEX":
                // Convert to hex string for readability
                return bytesToHex(bytes);
                
            case "BASE64":
                // Convert to base64
                return Base64.getEncoder().encodeToString(bytes);
                
            default:
                throw new ConfigException("Invalid binary.handling: " + binaryHandling);
        }
    }
    
    return value;
}

private String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
        sb.append(String.format("%02x", b));
    }
    return sb.toString();
}
```

### Configuration

```properties
clickhouse.binary.handling=BYTES  # BYTES | HEX | BASE64
```

---

## BUG-DATA-7: Decimal Precision Loss

**Severity:** MEDIUM  
**Location:** [`ClickHouseConverter.java:260-280`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

### Problem

MySQL `DECIMAL(65,30)` is larger than ClickHouse `Decimal128(38)` max. Precision can be lost silently.

### Current Code

```java
public Object convertDecimal(Object value, Schema schema) {
    // No precision/scale validation!
    return new BigDecimal(value.toString());
}
```

### Impact

```sql
-- MySQL
CREATE TABLE prices (
    amount DECIMAL(65, 30)  -- Very high precision
);

INSERT INTO prices VALUES (12345678901234567890.123456789012345678901234567890);

-- ClickHouse: Precision lost!
-- Stored as: 12345678901234567890.12345678901234567890 (truncated)
```

### Proposed Fix

```java
public Object convertDecimal(Object value, int precision, int scale) {
    BigDecimal decimal = new BigDecimal(value.toString());
    
    // ClickHouse Decimal limits
    int MAX_PRECISION = 76;  // Decimal256 max
    
    if (precision > MAX_PRECISION) {
        log.error("Decimal precision {} exceeds ClickHouse max {}, data loss may occur", 
            precision, MAX_PRECISION);
            
        if (config.isStrictDecimalValidation()) {
            throw new DataException(String.format(
                "Decimal precision %d exceeds maximum %d", precision, MAX_PRECISION
            ));
        }
    }
    
    // Validate scale
    if (decimal.scale() > scale) {
        String behavior = config.getDecimalScaleOverflow();
        
        switch (behavior) {
            case "ROUND":
                return decimal.setScale(scale, RoundingMode.HALF_UP);
                
            case "FAIL":
                throw new DataException("Decimal scale overflow");
                
            default:
                log.warn("Decimal scale {} exceeds target {}", decimal.scale(), scale);
                return decimal;
        }
    }
    
    return decimal;
}
```

---

## BUG-DATA-8: Emoji/4-byte UTF-8 Character Issues

**Severity:** HIGH  
**Location:** [`ClickHouseConverter.java:150-170`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converter/ClickHouseConverter.java)

### Problem

MySQL `utf8` (3-byte) vs `utf8mb4` (4-byte) encoding mismatch. Emojis and rare characters can be corrupted or cause errors.

### Impact

```sql
-- MySQL (utf8mb4)
CREATE TABLE messages (
    content VARCHAR(255) CHARACTER SET utf8mb4
);

INSERT INTO messages VALUES ('Hello 👋 World 🌍');  -- 4-byte emojis

-- ClickHouse: May store incorrectly if not utf8mb4
-- Result: "Hello ?? World ??" -- CORRUPTED!
```

### Proposed Fix

```java
public Object convertString(Object value, ClickHouseColumn column) {
    String str = value.toString();
    
    // Validate UTF-8 encoding
    if (!isValidUTF8(str)) {
        log.warn("Invalid UTF-8 in column {}, may contain corrupted data", column.getName());
    }
    
    // Check for 4-byte UTF-8 characters
    if (contains4ByteUTF8(str)) {
        if (!column.isUTF8MB4()) {
            log.error("4-byte UTF-8 characters (emojis) in column {} which is not utf8mb4", 
                column.getName());
                
            if (config.isStrictUTF8Validation()) {
                throw new DataException("4-byte UTF-8 not supported in this column");
            }
        }
    }
    
    return str;
}

private boolean contains4ByteUTF8(String str) {
    for (int i = 0; i < str.length(); i++) {
        if (Character.isHighSurrogate(str.charAt(i))) {
            return true;  // 4-byte character found
        }
    }
    return false;
}
```

### Configuration

```properties
clickhouse.string.encoding=UTF8  # UTF8 | UTF8MB4
clickhouse.strict.utf8.validation=false
```

---

## Summary of Fixes

| Bug ID | Priority | Effort | Fix Type |
|--------|----------|--------|----------|
| DATA-1 | P0 | 8h | Validation + Config |
| DATA-2 | P0 | 6h | Fail-fast + Mapping |
| DATA-3 | P1 | 12h | Type mapping |
| DATA-4 | P1 | 6h | Range validation |
| DATA-5 | P1 | 4h | Special case handling |
| DATA-6 | P1 | 8h | Binary handling |
| DATA-7 | P2 | 4h | Precision validation |
| DATA-8 | P1 | 6h | Encoding validation |
| **TOTAL** | | **54h** | |

## Recommended Configuration

```properties
# NULL handling
clickhouse.null.handling=FAIL
clickhouse.defaults.enabled=false

# Type mapping
clickhouse.unmapped.type.behavior=FAIL
clickhouse.type.blacklist=JSON,GEOMETRY,POINT

# Date handling
clickhouse.date.range.overflow=FAIL
clickhouse.zero.date.behavior=EPOCH

# Binary data
clickhouse.binary.handling=BYTES

# Decimal precision
clickhouse.strict.decimal.validation=true
clickhouse.decimal.scale.overflow=FAIL

# String encoding
clickhouse.string.encoding=UTF8MB4
clickhouse.strict.utf8.validation=true
```

---

**Related Documents:**
- [Crash Scenarios](./CRASH-SCENARIOS.md) - Specific crash triggers
- [Edge Cases](./EDGE-CASES.md) - More edge cases
- [Fix Priority](./FIXES-PRIORITY.md) - Implementation order
