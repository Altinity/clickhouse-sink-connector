# Spec 07.05: Binary, Bit Fields & Endianness Reversal

## 1. Executive Summary & Purpose
Specifies the handling of binary arrays, BLOBs, and bit fields (`BIT(N)`), formalizing the mandatory endianness reversal required for ClickHouse bit compatibility.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java` — the `Bits.LOGICAL_NAME` (`io.debezium.data.Bits`) branch of the bytes handler

---

## 3. The Bit Endianness Reversal Algorithm

In MySQL / Debezium:
- Debezium encodes `io.debezium.data.Bits` as a **little-endian** byte array.
- ClickHouse expects bit masks and numeric bit fields to follow **big-endian** integer byte order.

### 3.1 Reversal Logic
When `ClickHouseDataTypeMapper` processes a value whose schema name is `Bits.LOGICAL_NAME` **and the byte array is longer than one byte** (`rawBytes.length > 1`), the bytes are reversed before binding:
```java
byte[] sourceBytes = (byte[]) value;
byte[] reversed = new byte[sourceBytes.length];
for (int i = 0; i < sourceBytes.length; i++) {
    reversed[i] = sourceBytes[sourceBytes.length - 1 - i];
}
// Bind reversed byte array into ClickHouse UInt64 or String
```
Single-byte values and BLOB / `ByteBuffer` payloads (non-`Bits` schemas) are bound unchanged. Failure to reverse multi-byte values results in byte transposition (e.g. `BIT(16)` value `0x0001` stored as `0x0100`).

---

## 4. Invariants Preserved
- **Bit-Level Equivalence**: Bit flags and bit masks evaluate identically in MySQL and ClickHouse queries.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperBitEndianTest.testBit64AsymmetricValueIsBigEndian()`, `ClickHouseDataTypeMapperBitEndianTest.testBit24AsymmetricValueIsBigEndian()`, `ClickHouseDataTypeMapperBitEndianTest.testBit16HighByteIsBigEndian()`, `ClickHouseDataTypeMapperBitEndianTest.testSingleHighByteUnchanged()`, `ClickHouseDataTypeMapperBitEndianTest.testPalindromicValuesUnchanged()`, `ClickHouseDataTypeMapperBitEndianTest.testBlobByteBufferNotReversed()`.
- `ClickHouseDataTypeMapperBitBytesTest` — bit / bytes binding.
