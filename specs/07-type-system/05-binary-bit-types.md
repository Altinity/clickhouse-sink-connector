# Spec 07.05: Binary, Bit Fields & Endianness Reversal

## 1. Executive Summary & Purpose
Specifies the handling of binary arrays, BLOBs, and bit fields (`BIT(N)`), formalizing the mandatory endianness reversal required for ClickHouse bit compatibility.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`

---

## 3. The Bit Endianness Reversal Algorithm

In MySQL / Debezium:
- Debezium encodes `io.debezium.data.Bits` as a **little-endian** byte array.
- ClickHouse expects bit masks and numeric bit fields to follow **big-endian** integer byte order.

### 3.1 Reversal Logic
When `ClickHouseDataTypeMapper` processes a `Bits` schema:
```java
byte[] sourceBytes = (byte[]) value;
byte[] reversed = new byte[sourceBytes.length];
for (int i = 0; i < sourceBytes.length; i++) {
    reversed[i] = sourceBytes[sourceBytes.length - 1 - i];
}
// Bind reversed byte array into ClickHouse UInt64 or String
```
Failure to reverse bytes results in bit transposition (e.g. bit flag `0b00000001` becoming `0b10000000`).

---

## 4. Invariants Preserved
- **Bit-Level Equivalence**: Bit flags and bit masks evaluate identically in MySQL and ClickHouse queries.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperTest.testBitEndiannessReversal()`
