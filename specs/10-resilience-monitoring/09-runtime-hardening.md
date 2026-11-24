# Spec 10.09: Runtime Hardening of Settings, Metrics, Loss Reporting, Zone Checks and Preflight

## 1. Executive Summary & Purpose
Specifies six small runtime behaviours that keep a misconfiguration or a race from turning into silent data change or a
silent loss of diagnostics:

1. User JDBC settings add to the connector's required defaults; they never replace them (section 3.1).
2. The Prometheus endpoint is reported enabled only while it is bound, and stop is safe against a concurrent start (3.2).
3. The lossy-conversion reporter counts exactly under concurrency and writes its trailing summary at shutdown (3.3).
4. Type conversion never moves the position of a shared `ByteBuffer`, and a `NanoTimestamp` value is converted instead of
   being written as a raw number (3.4).
5. The startup zone cross-check treats equivalent zone names as equal (3.5).
6. The keyless-table preflight honours the same filters as the connector and states the real GIPK mode (3.6).

---

## 2. Codebase Mapping on 2.11.1
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/BaseDbWriter.java`: `customSettings`,
  `parseSettingsList`, `splitJdbcProperties`, `getConnection`.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/Metrics.java`: `initialize`, `stop`,
  `isEnabled`.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/LossyConversionReporter.java`: `report`,
  `flush`.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`: `convert`,
  the `NanoTimestamp` branch, `dataTypesMap`.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseZoneCrossCheck.java`: `findMismatches`,
  `sameZoneName`, `JdbcZoneMetadataSource.DATETIME_COLUMNS_SQL`.
- `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/KeylessTablePreflight.java`:
  `warningPreExisting`, the include, exclude and `database.exclude.list` handling.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/KeylessTableWarning.java`: `banner`.
- Tests: `BaseDbWriterSettingsMergeTest`, `JdbcCustomSettingsTest`, `MetricsBindLifecycleTest`,
  `LossyConversionReporterConcurrencyTest`, `ClickHouseDataTypeMapperByteBufferNanoTest`, `ClickHouseZoneCrossCheckTest`
  and `KeylessTablePreflightTest`.

---

## 3. Operational Specification

### 3.1 JDBC settings merge
`BaseDbWriter.customSettings` starts from the connector's own required settings and applies the user's
`clickhouse.jdbc.settings` list on top, key by key. A user key that names a default replaces that one default; every other
default stays. `splitJdbcProperties` and `parseSettingsList` split each entry at the first `=` only (so a value may contain
`=`), trim whitespace, skip empty entries, and refuse an entry with no `=` with an `IllegalArgumentException` naming the
entry. A blank list is the same as no list. `getConnection` logs the target and the cause when it cannot connect and still
returns `null`, because callers already tolerate a missing handle.

### 3.2 Metrics lifecycle
`Metrics.initialize` and `Metrics.stop` are `synchronized`. The server is started synchronously, and the enabled flag is set
only after the bind succeeds, so `isEnabled()` is true only between a successful bind and `stop()`. A bind failure leaves the
metrics disabled and a later `initialize` can try again. A start racing a stop leaves no uncaught exception. `stop()` flushes
the lossy-conversion reporter (3.3) before it closes the server.

### 3.3 Lossy-conversion reporting
`LossyConversionReporter.report` is lock-free: counters use atomics, the set of columns that have already warned is a
concurrent key set (each column warns once), and the summary window rolls over by compare-and-set so exactly one thread
writes each summary. `flush()` writes the suppressed count of the open window once; with nothing suppressed it writes
nothing, and reports that race with it are not lost.

### 3.4 Buffer position and NanoTimestamp
`ClickHouseDataTypeMapper` reads WKB geometry, point and variable-scale decimal buffers through `duplicate()`, so the
caller's buffer keeps its position and can be read again (for example by a retry). A `NanoTimestamp` value (nanoseconds
past the epoch, wall time read in the configured source zone) is converted to the `DateTime64(9)` text with nine fraction
digits, bounded by the configured range policy. `dataTypesMap` maps `INT64` with `NanoTimestamp.SCHEMA_NAME` to
`DateTime64`.

### 3.5 Zone cross-check
`ClickHouseZoneCrossCheck.sameZoneName` compares zone names by their rules, so `US/Central` and `America/Chicago` are one
zone, for both the server zone and column zones. A different zone is one finding naming both zones. The column scan skips the
system databases and the inner tables of materialized views (`.inner%`). Nothing in this class refuses startup.

### 3.6 Keyless-table preflight
`KeylessTablePreflight` matches `table.include.list`, `table.exclude.list` and `database.exclude.list` case-insensitively,
anchored to the whole name; an unparseable pattern fails safe (the table is kept in the scan). `warningPreExisting` receives
whether GIPK is enabled and passes it to `KeylessTableWarning.banner`, so the banner states the real mode.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure)**: a failed ALTER, a malformed JDBC setting, a bind failure and a lost summary are each
  reported, not swallowed.
- **Defaults are not clobbered**: no user setting removes a required connector setting that the user did not name.
- **Input is not mutated**: converting a value never changes the position of a buffer that the caller still holds.
- **Exactly-once reporting**: each lossy column warns once per process and each window summary is written once.
- **No new refusal**: the zone cross-check and the preflight only add findings and never stop a start that was allowed before.

---

## 5. Verification Criteria
- Settings merge: `BaseDbWriterSettingsMergeTest.userSettingDoesNotClobberDefaults()`,
  `.userOverridesIndividualDefaultKey()`, `.equalsInsideValueAndWhitespaceAreHandled()`, `.blankUserListIsTheDefault()`,
  `.splitKeepsEqualsInValue()`, `.splitTrimsAndSkipsEmpty()`, `.splitRefusesMalformedEntry()`,
  `.getConnectionKeepsNullContractWhenAcquisitionFails()`.
- Metrics: `MetricsBindLifecycleTest.enabledOnlyBetweenBindAndStop()`, `.bindFailureLeavesMetricsDisabled()`,
  `.recoversAfterBindFailure()`, `.disabledFlagStaysDisabled()`, `.startStopRaceLeavesNoUncaughtException()`.
- Reporter: `LossyConversionReporterConcurrencyTest.concurrentReportsAreCountedExactly()`,
  `.distinctColumnsEachWarnOnce()`, `.summaryRollsOverOnce()`, `.flushWritesTrailingSuppressedOnce()`,
  `.flushWithNothingSuppressedIsNoOp()`, `.flushRacingReportsLosesNothing()`.
- Mapper: `ClickHouseDataTypeMapperByteBufferNanoTest.geometryWkbBufferIsNotMutated()`, `.pointWkbBufferIsNotMutated()`,
  `.variableScaleDecimalBufferIsNotMutated()`, `.nanoTimestampIsConverted()`, `.nanoTimestampBeforeEpoch()`,
  `.nanoTimestampHonoursSourceZone()`, `.nanoTimestampHasADataTypeMapping()`.
- Zone check: `ClickHouseZoneCrossCheckTest.equivalentServerZoneNameIsNotReported()`,
  `.differentServerZoneIsStillReported()`, `.columnScanExcludesInnerTables()`.
- Preflight: `KeylessTablePreflightTest.testPreExistingWarningReflectsGipkOffAndOn()`,
  `.testTableExcludeListIsCaseInsensitive()`, `.testTableIncludeListIsCaseInsensitive()`,
  `.testDatabaseExcludeListIsRespected()`, `.testUnparseableDatabaseExcludePatternFailsSafe()`.

---

## 6. Failure Modes & Recovery
- **FM-10.09-1 Malformed `clickhouse.jdbc.settings` entry**
  - **Trigger**: an entry with no `=`.
  - **Detection**: `IllegalArgumentException` naming the entry at writer construction.
  - **Recovery**: correct the entry and restart.
  - **RTO**: one restart.
  - **Test**: `BaseDbWriterSettingsMergeTest.splitRefusesMalformedEntry()`.
- **FM-10.09-2 Metrics port cannot be bound**
  - **Trigger**: the port is in use.
  - **Detection**: `Metrics.isEnabled()` is false and the bind error is logged; replication continues.
  - **Recovery**: free the port; the next `initialize` binds it.
  - **RTO**: one restart, or the next initialize call.
  - **Test**: `MetricsBindLifecycleTest.bindFailureLeavesMetricsDisabled()`, `.recoversAfterBindFailure()`.
- **FM-10.09-3 Process stops before the last summary window closes**
  - **Trigger**: shutdown with suppressed lossy-conversion warnings.
  - **Detection**: the trailing summary line is written by `flush()` from `Metrics.stop()`.
  - **Recovery**: none needed.
  - **RTO**: none; the summary is written during shutdown.
  - **Test**: `LossyConversionReporterConcurrencyTest.flushWritesTrailingSuppressedOnce()`.
- **FM-10.09-4 Server zone differs from the configured zone**
  - **Trigger**: `SELECT timezone()` names a zone with different rules.
  - **Detection**: one WARN naming both zones.
  - **Recovery**: align `clickhouse.datetime.timezone` with the server or the column zones, then restart.
  - **RTO**: one restart.
  - **Test**: `ClickHouseZoneCrossCheckTest.differentServerZoneIsStillReported()`.
- **FM-10.09-5 Keyless table present at startup**
  - **Trigger**: a source table in scope has no primary key and no non-null unique key.
  - **Detection**: the banner from `KeylessTableWarning.banner` states the GIPK mode.
  - **Recovery**: add a key or exclude the table with the exclude lists, then restart.
  - **RTO**: one restart.
  - **Test**: `KeylessTablePreflightTest.testPreExistingWarningReflectsGipkOffAndOn()`,
    `.testDatabaseExcludeListIsRespected()`.

Summary: 5 failure modes, 0 DEFECT, 0 GAP.
