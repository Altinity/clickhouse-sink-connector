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
Single-byte values and BLOB / `ByteBuffer` payloads (non-`Bits` schemas) are bound unchanged. For a `ByteBuffer`, the payload is exactly its remaining bytes (`position` inclusive through `limit` exclusive), including sliced, direct and read-only buffers. Read through a duplicate so binding and retries never change the original position or limit. Apply the same BIT reversal, raw/hex encoding and spill handling to those bytes as to a `byte[]`. Failure to reverse multi-byte values results in byte transposition (e.g. `BIT(16)` value `0x0001` stored as `0x0100`).

### 3.2 Canonical stored representation of binary values
The text that lands in the (`String`) ClickHouse column depends on two
settings, and a value-level comparison must know which one applies:

| Debezium `binary.handling.mode` | Connector `persist.raw.bytes` | Delivered as | Stored as |
|---|---|---|---|
| `base64` (the **default**: the connector sets it when the key is absent or blank, `DebeziumChangeEventCapture.ensureBinaryHandlingMode`) | any | `STRING` (Debezium already base64-encoded it) | the **base64 text** verbatim, e.g. `3q2+7w==` — equals `TO_BASE64(col)` on MySQL; `persist.raw.bytes` has no effect because the value is no longer `BYTES` |
| `bytes` (Debezium's own default, used when the operator sets it explicitly) | `false` | `BYTES` (`byte[]` / `ByteBuffer`) | **lower-case hex text** of the bytes (`BaseEncoding.base16().lowerCase()`), e.g. `deadbeef` — equals `LOWER(HEX(col))` on MySQL |
| `bytes` | `true` | `BYTES` | the raw bytes (`ps.setBytes`); the `String` column holds the bytes verbatim — equals the MySQL bytes |
| `hex` | any | `STRING` | Debezium's hex text verbatim (`DEADBEEF`, upper case — note the case difference from the connector's own hex) |

The default is base64 in **every** component, so a snapshot and the streamed
rows it is joined by carry the same text and a checksum compares like with
like: the connector sets it (`ensureBinaryHandlingMode`), the Python loader's
`--binary_handling_mode` defaults to `base64` (Spec 13.04 §3.7.1), and the
checksum tools' `--binary_encoding` defaults to `base64` (Spec 11.02 §3.6). An
existing deployment that relied on Debezium's `bytes` default must set
`binary.handling.mode: "bytes"` explicitly and pass `--binary_handling_mode
bytes` / `--binary_encoding hex` to the tools.

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
- `BinaryHandlingModeDefaultTest` — the connector sets `binary.handling.mode`
  to `base64` when it is absent or blank, and leaves an explicit value alone
  (§3.2).

---

## 6. Failure Modes & Recovery

Recovery posture: a `BYTES` value only arises when the operator sets `binary.handling.mode=bytes` explicitly (base64 is the default, §3.2); it is written as text — lower-case hex, or `unhex('<hex>')` from the V2 driver under `persist.raw.bytes=true` (`JdbcUtils.convertToUnhexExpression`, jdbc-v2 0.9.8 bytecode). Since spec 03.06 §3.4 a value of 64 KiB or more in a chunk at or above `insert.spill.threshold.bytes` is streamed from its array into a spill file as that same text, so it no longer costs its hex text in the heap. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-07.05-1 BLOB near or above 1 GiB (hex rendering limit)**
  - **Trigger**: a `LONGBLOB`/`VARBINARY` value of hundreds of MB up to `max_allowed_packet` (1 GiB).
  - **Behaviour (since spec 03.06 §3.4)**: the chunk holding such a value always reaches the spill threshold, and `ClickHouseDataTypeMapper.convert` offers the array to the spilling statement (`SpillingInsertStatement.offerHexText` / `offerUnhex`), which streams the same hex text into the spill file: no hex `String`, no driver copy, so the `int`-length limit below no longer applies to the writer. The heap still holds the value itself and its row images while Debezium decodes them (spec 01.08 FM-01.08-5): measured with `sink-connector-lightweight/tests/e2e/large_row_spill.sh` (one row inserted and updated in one transaction, i.e. three images of the value in one payload, 4 GiB heap): with `binary.handling.mode=bytes` rows of 64, 128, 256 and 512 MiB are value-exact with no `OutOfMemoryError` (heap after GC at most 2.6 GiB at 512 MiB), where 2.11.0 before §3.4 exited on the 256 MiB row in 2 of 3 runs and crash-looped on the restart; with `binary.handling.mode=base64` (the Ansible template's setting) 64 and 256 MiB are value-exact at 4 GiB and 512 MiB needs more than 4 GiB (value-exact at 8 GiB, heap after GC 3.1 GiB). **Before §3.4 (and when the spill path refuses itself, spec 03.06 FM-03.06-7):** `ClickHouseDataTypeMapper.convert` hex-encodes with Guava `BaseEncoding.base16().lowerCase().encode(rawBytes)` (2 characters per byte), then the V2 driver copies the text again while building the statement (see spec 07.04 §6 FM-07.04-3). Measured with a probe of Guava 33.0.0 on JDK 17 (`-Xmx12g`): exactly 2^30 bytes throws `NegativeArraySizeException: -2147483648` (the encoded length overflows `int`); 2^30 - 1 bytes throws `OutOfMemoryError: Requested array size exceeds VM limit`; 10^9 bytes encodes (2·10^9 chars). So: at 1 GiB the batch fails with a `RuntimeException` that classifies UNKNOWN and is retried forever; just below it, and for any value large enough to exhaust the heap (a few hundred MB under the shipped `-Xmx4G`, unmeasured), an `OutOfMemoryError` kills the worker and the process exits 3. `persist.raw.bytes=true` does not help: the driver renders `unhex('<hex>')`, the same text.
  - **Detection**: NegativeArraySize case: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `java.lang.NegativeArraySizeException` and WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN)` every ≤ 30 s, no exit. OOM case: `java.lang.OutOfMemoryError`, `Sink worker <i> of <n> is dead ...`, FATAL `Replication is STOPPED: ...`, exit 3 within ≤ 5 s, repeating on every restart until systemd gives up (5 starts / 300 s). Neither names the row or the column.
  - **Blast radius**: UNKNOWN case: the worker's tables stop, offsets freeze, the next DDL drain waits forever; OOM case: the whole connector stops; in both nothing of the batch is written, nothing lost.
  - **Recovery**: below the hard limit, raise `-Xmx` (≈ 10× the value; unmeasured) and restart; at or above it there is no bounded recovery: exclude the column on the source connector (`column.exclude.list`), P-SKIP the transaction and P-RESYNC the table (the column is then absent from the replica), or change the application.
  - **RTO**: heap change + restart ≈ 1–2 min when it suffices; otherwise P-SKIP + resync; the limit itself measured ad hoc (Guava probe, not a checked-in harness).
  - **Test**: `SpillingInsertStatementTest.offeredHexTextMatchesTheDriver()`, `SpillingInsertStatementTest.largeBytesMatchTheDriver()`, `large_row_spill.sh` (64-512 MiB rows at a 4 GiB heap); GAP: a value of 1 GiB itself (a MySQL row event cannot exceed `max_allowed_packet`, so the case needs a single-image insert of ~1 GiB).
  - **DEFECT** (residual, reader side): the decoded row images must still fit the heap (spec 01.08 FM-01.08-5); there is no loud, row-naming refusal when they do not — the process exits on `OutOfMemoryError`.

- **FM-07.05-2 `ByteBuffer` carrier with an offset, a limit, or read-only**
  - **Trigger**: a `BYTES` value delivered as a `ByteBuffer` that is a slice, has a non-zero position, or is read-only/direct (possible with Kafka Connect converters; Debezium's MySQL converter delivers a fully wrapped array — not verified for every converter).
  - **Behaviour**: the mapper reads `remaining()` bytes through a duplicate, preserving position and limit for retries. Sliced, direct and read-only buffers follow the same raw/hex and BIT endian rules as `byte[]`. Previously `array()` bound the whole backing array or threw for direct/read-only buffers.
  - **Detection**: regression tests assert the exact bound bytes and unchanged cursor on repeated calls.
  - **Blast radius before the fix**: slices could silently diverge; direct/read-only buffers could stall the worker.
  - **Recovery**: the corrected mapper prevents new corruption; previously corrupted values still require reconciliation against the source.
  - **RTO**: historical reconciliation is outside this fix; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperPoisonValueTest.byteBufferSliceBindsOnlyItsRemainingBytes()` is enabled; `ClickHouseDataTypeMapperBitBytesTest.byteBufferBoundsAndReadOnlyCarriersSurviveRepeatedBinding()` covers sliced, positioned, direct, read-only and empty buffers, both raw/hex modes and BIT/non-BIT semantics.

- **FM-07.05-3 `binary.handling.mode` changed on a running replica**
  - **Trigger**: an operator changes Debezium `binary.handling.mode` (e.g. `bytes` → `base64`) or the connector's `persist.raw.bytes` between runs.
  - **Behaviour**: rows written after the change carry another text representation (hex, base64, Debezium's upper-case hex or raw bytes, §3.2) in the same `String` column; nothing records which representation a row uses.
  - **Detection**: none. DEFECT.
  - **Blast radius**: the column mixes representations; value-level comparison and consumers decoding it break for part of the rows.
  - **Recovery**: revert the setting and P-RESYNC the tables written meanwhile, or keep the new setting and P-RESYNC every binary column's table.
  - **RTO**: restart + resync; unmeasured.
  - **Test**: GAP: a start-up check that refuses a representation change against a replica written in another one (for example by recording the mode in the offset storage).
  - **DEFECT**: a configuration change silently changes the stored value format.

Summary: 3 failure modes, 2 unresolved DEFECT, 2 GAP; FM-07.05-2 has permanent regression coverage.
