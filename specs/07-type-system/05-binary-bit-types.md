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

### 3.2 Canonical stored representation of binary values
The text that lands in the (`String`) ClickHouse column depends on two
settings, and a value-level comparison must know which one applies:

| Debezium `binary.handling.mode` | Connector `persist.raw.bytes` | Delivered as | Stored as |
|---|---|---|---|
| `bytes` (Debezium default) | `false` (default) | `BYTES` (`byte[]` / `ByteBuffer`) | **lower-case hex text** of the bytes (`BaseEncoding.base16().lowerCase()`), e.g. `deadbeef` — equals `LOWER(HEX(col))` on MySQL |
| `bytes` | `true` | `BYTES` | the raw bytes (`ps.setBytes`); the `String` column holds the bytes verbatim — equals the MySQL bytes |
| `base64` (set by the shipped deployment templates) | any | `STRING` (Debezium already base64-encoded it) | the **base64 text** verbatim, e.g. `3q2+7w==` — equals `TO_BASE64(col)` on MySQL; `persist.raw.bytes` has no effect because the value is no longer `BYTES` |
| `hex` | any | `STRING` | Debezium's hex text verbatim (`DEADBEEF`, upper case — note the case difference from the connector's own hex) |

`BIT(n>1)` is delivered as `BYTES` (`io.debezium.data.Bits`) under every
`binary.handling.mode` and follows the first two rows after the §3.1
reversal; `BIT(1)` is delivered as `BOOLEAN` (Spec 07.04 §3.1). Spatial values
bound for a `String` column follow the first two rows as well (Spec 07.06 §3.2).

---

## 4. Invariants Preserved
- **Bit-Level Equivalence**: Bit flags and bit masks evaluate identically in MySQL and ClickHouse queries.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperBitEndianTest.testBit64AsymmetricValueIsBigEndian()`, `ClickHouseDataTypeMapperBitEndianTest.testBit24AsymmetricValueIsBigEndian()`, `ClickHouseDataTypeMapperBitEndianTest.testBit16HighByteIsBigEndian()`, `ClickHouseDataTypeMapperBitEndianTest.testSingleHighByteUnchanged()`, `ClickHouseDataTypeMapperBitEndianTest.testPalindromicValuesUnchanged()`, `ClickHouseDataTypeMapperBitEndianTest.testBlobByteBufferNotReversed()`.
- `ClickHouseDataTypeMapperBitBytesTest` — bit / bytes binding.
