# Phase 2: Quick Reference Guide

## Configuration Parameters

```properties
# Date Validation
strict.date.validation=true                    # Enforce 1900-2299 range for Date32
zero.date.behavior=null                        # "null" or "error" for 0000-00-00

# Numeric Validation
strict.bigint.validation=true                  # Detect BIGINT UNSIGNED overflow
allow.decimal.precision.loss=false             # Allow decimal truncation
```

## Bug Fixes Summary

| Bug ID | Issue | Fix Location | Severity |
|--------|-------|--------------|----------|
| BUG-DATA-4 | Date Range Overflow | ClickHouseDataTypeMapper.java:276-310 | HIGH |
| BUG-DATA-5 | Zero Date Crash | ClickHouseDataTypeMapper.java:276-293 | HIGH |
| BUG-DATA-3 | BIGINT UNSIGNED Overflow | ClickHouseDataTypeMapper.java:298-318 | HIGH |
| BUG-DATA-7 | Decimal Precision Loss | ClickHouseDataTypeMapper.java:457-478 | MEDIUM |
| BUG-DATA-8 | Emoji/UTF-8 Issues | ClickHouseDataTypeMapper.java:194-283 | HIGH |
| BUG-DATA-2 | Unmapped Type Silent Fail | ClickHouseDataTypeMapper.java:474-481 | CRITICAL |

## Error Messages

### Date Out of Range
```
IllegalArgumentException: Date 1899-12-31 outside ClickHouse Date32 range (1900-2299)
```
**Solution:** Use dates within 1900-2299 or set `strict.date.validation=false`

### Zero Date
```
IllegalArgumentException: Zero date (0000-00-00) is not supported
```
**Solution:** Set `zero.date.behavior=null` to convert to NULL

### BIGINT UNSIGNED Overflow
```
IllegalArgumentException: BIGINT UNSIGNED value -1 exceeds Int64 max (2^63-1)
```
**Solution:** Use UInt64 in ClickHouse or set `strict.bigint.validation=false`

### Decimal Precision Loss
```
IllegalArgumentException: Decimal precision would be lost. Original: 123.456789, Truncated: 123.456
```
**Solution:** Set `allow.decimal.precision.loss=true` or reduce decimal precision

### Unmapped Type
```
IllegalArgumentException: Unmapped data type: schema=custom.type, field=my_field, value=HashMap
```
**Solution:** Add type mapping or convert to supported type

## Testing

```bash
# Run edge case tests
mvn test -Dtest=EdgeCaseValidationTest

# Run specific test
mvn test -Dtest=EdgeCaseValidationTest#testDateRangeValidation_BelowMinimum
```

## Migration Guide

### From Legacy (No Validation)
```properties
# Old behavior (silent failures)
# No configuration needed

# New behavior (strict validation)
strict.date.validation=true
strict.bigint.validation=true
allow.decimal.precision.loss=false
zero.date.behavior=error
```

### Permissive Mode (Compatible)
```properties
# Allow edge cases for backward compatibility
strict.date.validation=false
strict.bigint.validation=false
allow.decimal.precision.loss=true
zero.date.behavior=null
```

## Files Modified

1. [`ClickHouseSinkConnectorConfigVariables.java`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfigVariables.java) - Config params
2. [`ClickHouseDataTypeMapper.java`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java) - Main fixes
3. [`PreparedStatementExecutor.java`](../sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java) - Call site update
4. [`EdgeCaseValidationTest.java`](../sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/datatypes/EdgeCaseValidationTest.java) - Test suite

## See Also

- [Full Implementation Summary](PHASE2-IMPLEMENTATION-SUMMARY.md)
- [Data Type Bugs](DATA-TYPE-BUGS.md)
- [Edge Cases Catalog](EDGE-CASES.md)
