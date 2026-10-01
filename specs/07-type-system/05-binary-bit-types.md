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

---

## 6. Failure Modes & Recovery

Recovery posture: a `BYTES` value is rendered to text before it leaves the JVM — lower-case hex by default, `unhex('<hex>')` from the V2 driver under `persist.raw.bytes=true` (`JdbcUtils.convertToUnhexExpression`, jdbc-v2 0.9.8 bytecode) — so every binary value costs at least twice its size in heap and a value near 1 GiB cannot be written at all. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-07.05-1 BLOB near or above 1 GiB (hex rendering limit)**
  - **Trigger**: a `LONGBLOB`/`VARBINARY` value of hundreds of MB up to `max_allowed_packet` (1 GiB).
  - **Behaviour**: `ClickHouseDataTypeMapper.convert` hex-encodes with Guava `BaseEncoding.base16().lowerCase().encode(rawBytes)` (2 characters per byte), then the V2 driver copies the text again while building the statement (see spec 07.04 §6 FM-07.04-3). Measured with a probe of Guava 33.0.0 on JDK 17 (`-Xmx12g`): exactly 2^30 bytes throws `NegativeArraySizeException: -2147483648` (the encoded length overflows `int`); 2^30 - 1 bytes throws `OutOfMemoryError: Requested array size exceeds VM limit`; 10^9 bytes encodes (2·10^9 chars). So: at 1 GiB the batch fails with a `RuntimeException` that classifies UNKNOWN and is retried forever; just below it, and for any value large enough to exhaust the heap (a few hundred MB under the shipped `-Xmx4G`, unmeasured), an `OutOfMemoryError` kills the worker and the process exits 3. `persist.raw.bytes=true` does not help: the driver renders `unhex('<hex>')`, the same text.
  - **Detection**: NegativeArraySize case: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `java.lang.NegativeArraySizeException` and WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN)` every ≤ 30 s, no exit. OOM case: `java.lang.OutOfMemoryError`, `Sink worker <i> of <n> is dead ...`, FATAL `Replication is STOPPED: ...`, exit 3 within ≤ 5 s, repeating on every restart until systemd gives up (5 starts / 300 s). Neither names the row or the column.
  - **Blast radius**: UNKNOWN case: the worker's tables stop, offsets freeze, the next DDL drain waits forever; OOM case: the whole connector stops; in both nothing of the batch is written, nothing lost.
  - **Recovery**: below the hard limit, raise `-Xmx` (≈ 10× the value; unmeasured) and restart; at or above it there is no bounded recovery: exclude the column on the source connector (`column.exclude.list`), P-SKIP the transaction and P-RESYNC the table (the column is then absent from the replica), or change the application.
  - **RTO**: heap change + restart ≈ 1–2 min when it suffices; otherwise P-SKIP + resync; the limit itself measured ad hoc (Guava probe, not a checked-in harness).
  - **Test**: GAP: a checked-in harness (it needs a multi-GB heap, so not a unit test) that measures the largest BLOB a given `-Xmx` replicates and asserts a loud, row-naming refusal above it.
  - **DEFECT**: the replicable value size is capped far below MySQL's limit by the text rendering, and at exactly 1 GiB the failure is retried forever instead of refused; a streaming binary insert format (RowBinary) or an explicit size refusal naming the column is needed.

- **FM-07.05-2 `ByteBuffer` carrier with an offset, a limit, or read-only**
  - **Trigger**: a `BYTES` value delivered as a `ByteBuffer` that is a slice, has a non-zero position, or is read-only/direct (possible with Kafka Connect converters; Debezium's MySQL converter delivers a fully wrapped array — not verified for every converter).
  - **Behaviour**: `ClickHouseDataTypeMapper.convert` reads `((ByteBuffer) value).array()`, which returns the whole backing array regardless of position, limit and offset: a slice binds bytes that are not the value; a read-only buffer throws `ReadOnlyBufferException` and a direct buffer `UnsupportedOperationException` (both UNKNOWN, retried forever). The `Geometry` branch of the same method reads `remaining()` correctly.
  - **Detection**: slice: none (DEFECT); read-only/direct: ERROR + WARN `Retriable ... Category: UNKNOWN` every ≤ 30 s, no exit.
  - **Blast radius**: slice: silent value divergence; read-only/direct: the worker's tables stop.
  - **Recovery**: none in configuration; for the Kafka path, change the value converter so it delivers `byte[]`, restart, P-RESYNC.
  - **RTO**: unbounded detection for the silent case; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperPoisonValueTest.byteBufferSliceBindsOnlyItsRemainingBytes()` (disabled, fails on 2.11.0: binds `01020304` for the slice `0203`).
  - **DEFECT**: the `BYTES` branch ignores the buffer's position/limit/offset; read `remaining()` bytes like the `Geometry` branch does.

- **FM-07.05-3 `binary.handling.mode` changed on a running replica**
  - **Trigger**: an operator changes Debezium `binary.handling.mode` (e.g. `bytes` → `base64`) or the connector's `persist.raw.bytes` between runs.
  - **Behaviour**: rows written after the change carry another text representation (hex, base64, Debezium's upper-case hex or raw bytes, §3.2) in the same `String` column; nothing records which representation a row uses.
  - **Detection**: none. DEFECT.
  - **Blast radius**: the column mixes representations; value-level comparison and consumers decoding it break for part of the rows.
  - **Recovery**: revert the setting and P-RESYNC the tables written meanwhile, or keep the new setting and P-RESYNC every binary column's table.
  - **RTO**: restart + resync; unmeasured.
  - **Test**: GAP: a start-up check that refuses a representation change against a replica written in another one (for example by recording the mode in the offset storage).
  - **DEFECT**: a configuration change silently changes the stored value format.

Summary: 3 failure modes, 3 DEFECT, 2 GAP.
