# Spec 03.08: Legacy JDBC V1 Driver Refused at Start

## 1. Executive Summary & Purpose

`clickhouse.jdbc.v1=true` selects the legacy ClickHouse JDBC V1 driver. On 2.11.1 that driver
cannot bind the epoch-text datetime values the connector writes. The first batch with a datetime
column throws `DateTimeParseException` (`Text '1768478400.500000' could not be parsed at index 0`)
in `PreparedStatementExecutor.executePreparedStatement`, and the retry loop never drains. The
connector looks healthy, and the stall is only visible as a DRAIN-TIMEOUT.

This spec refuses the flag when the configuration is parsed. The connector fails at start with a
`ConfigException` that names the key, the datetime failure and the action (remove the key). The V2
driver is the only driver a running connector can use. An operator who sets the key will see the
refusal at the next start. The failure is loud and happens before any batch is written.

## 2. Codebase Mapping on 2.11.1

- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/validators/JdbcV1DriverValidator.java`: `ensureValid` (refuses `Boolean.TRUE`), `REFUSAL_MESSAGE`.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java`: the `clickhouse.jdbc.v1` define passes `new JdbcV1DriverValidator()`. Parsing runs the validator, so the refusal fires in every constructor of the configuration (Kafka Connect start, the embedded application `main`, and the tests).
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/BaseDbWriter.java`: the `useV1Driver` branch (~line 439) and `dropV1OnlyProperties` (~line 279). Both are unreachable from configuration after this change. They are kept unchanged so that a future re-enable is one validator change, not a rewrite.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/SinkConnectorDataSource.java`: `applyDriverSelection` (appends the V1 marker only when given `true` directly, which configuration no longer allows).
- `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` (~line 351): the V1 branch of the Debezium storage URL tagging. Unreachable from configuration after this change, kept unchanged for the same reason.
- Tests: `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/DualJdbcDriverToggleTest.java` (`testConfigRefusesV1Toggle`), `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/validators/JdbcV1DriverValidatorTest.java`, and `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/DualJdbcDriverEndToEndIT.java` (`testV1DriverIsRefusedAtStart`; `testCrossDriverReadback` disabled because there is no V1 writer to read back). The other tests that mention the key (`PoolServerIdentityTest`, `sink-connector-lightweight` `DualDriverUrlParamTest`) build no configuration with the key set to true, so they are unchanged.

## 3. Operational Specification

3.1 Parse-time refusal. A configuration whose `clickhouse.jdbc.v1` value parses to `true` is
refused with a `ConfigException`. The message names the key, states that the V1 driver cannot bind
the datetime values (DateTimeParseException on epoch text), and tells the operator to remove the key.

3.2 Accepted values. An unset key and `false` are accepted. The driver is V2, with the
`jdbc_ignore_unsupported_values=true` URL flag (unchanged).

3.3 No V1 connection. Because parsing fails first, no `SinkConnectorDataSource` is constructed with
`useV1Driver = true` from configuration. The V1 marker can no longer be added through configuration.

3.4 Scope. The refusal is independent of the time zone and of the table shapes. It applies to every
connector mode, including replication history.

## 4. Invariants Preserved

- A configuration that selects the V1 driver never starts. The refusal is raised before any
  connection is opened, so the DateTimeParseException stall cannot occur.
- A configuration that starts uses the V2 driver. The driver selected by an accepted configuration is never V1.
- The refusal does not change the behaviour of an accepted configuration. Every key other than
  `clickhouse.jdbc.v1` parses as before.

## 5. Verification Criteria

Unit tests:
- `DualJdbcDriverToggleTest.testConfigRefusesV1Toggle` parses `clickhouse.jdbc.v1=true` and expects a `ConfigException` whose message names the key and `DateTimeParseException`.
- `DualJdbcDriverToggleTest.testConfigDefaultsToV2Driver` (unchanged): the default is false.
- `JdbcV1DriverValidatorTest.testFalseIsAccepted`, `testUnsetIsAccepted`, `testTrueIsRefusedWithKeyAndCause`.
- Mutation M1: passing `null` instead of `new JdbcV1DriverValidator()` in the define makes `testConfigRefusesV1Toggle` fail (observed: 1 failure).
- Mutation M2: disabling the refusal inside `ensureValid` (`if (false && ...)`) makes `testConfigRefusesV1Toggle` and `JdbcV1DriverValidatorTest.testTrueIsRefusedWithKeyAndCause` fail (observed: 2 failures). The unmutated tree is green (2026-10-22): in `sink-connector`, `JdbcV1DriverValidatorTest` 3 tests, `DualJdbcDriverToggleTest` 7, `PoolServerIdentityTest` 9 (19 tests, BUILD SUCCESS); in `sink-connector-lightweight`, `DualDriverUrlParamTest` 6 (BUILD SUCCESS).

End-to-end (a UTC workload with the V1 key, base 2.11.1 against the fix):
- Base 2.11.1 with `CFG:clickhouse.jdbc.v1=true` reproduces the stall: ERROR DRAIN-TIMEOUT with DateTimeParseException lines.
- Fix with `CFG:clickhouse.jdbc.v1=true`: the connector exits before streaming with the refusal (CONNECTOR-EXITED-BEFORE-STREAM, rc non-zero) and no DateTimeParseException.
- Negative control: fix without the key, same workload: PASS.

## 6. Failure Modes & Recovery

### 6.1 An operator sets clickhouse.jdbc.v1=true

- Detection: the connector exits at start. The log carries `Invalid value true for configuration clickhouse.jdbc.v1: the legacy ClickHouse JDBC V1 driver is not supported ...`.
- Recovery: remove the key (or set it to false) and restart. No data is affected, because no batch was written.
- RTO: minutes.

### 6.2 A future change needs the V1 driver back

- Detection: none at runtime. The refusal is the designed gate.
- Recovery: the V1 datetime binding must be fixed first (the epoch-text instant binding, spec 07.03). Only then may the validator be relaxed, together with this spec.
- RTO: a development change, not an operational action.
