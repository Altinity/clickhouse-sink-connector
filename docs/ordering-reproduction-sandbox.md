# Ordering reproduction sandbox

This is an opt-in, deterministic JVM suite for the six unresolved ordering
families in [the correctness audit](replication-correctness-audit.md): GTID
ordering, mixed version domains, restart identity, source-failover identity,
durable restart floors and Kafka Connect timestamps. Eight cases cover those
six families; two additional cases are positive controls. It uses the real connector
classes and synthetic events, without database clients, credentials, containers,
network connections, sleeps or wall-clock changes. Run it in an isolated build
sandbox or the disposable CI runner, never against production.

## Run

Use JDK 17 and the repository's Maven build. From the repository root:

```sh
timeout 180s mvn --batch-mode -pl sink-connector-lightweight -am \
  -Dtest=SourcePositionTest,OrderingReproductionScenarios \
  -DfailIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=false test
```

The manual **Ordering defect reproductions** workflow runs that selector and
uploads both modules' Surefire reports even when assertions fail. GitHub must
have the workflow on the default branch before it can be dispatched; adding the
file on a feature branch alone does not register a runnable manual workflow.

## Interpret the result

`OrderingReproductionScenarios` contains eight correctness assertions and two
positive controls. Every method has a 20-second deadline. The class is explicitly
selected above; its name intentionally does not match normal `*Test`/`*IT`
discovery. Nothing is disabled inside this suite. The six existing disabled
regressions are invoked directly, with a fresh sequence state for each scenario;
their assertions propagate rather than becoming expected-failure passes.

A reproduced defect is **RED**, not a successful replication test. Compilation,
discovery, dependency and fixture errors are harness failures, not reproductions.
Check the per-method XML and console output, not just Maven's exit status. A
missing scenario, skipped method, failed control or zero-test run is incomplete.
A real fix turns its existing correctness assertion green without changing the
assertion or excluding the case. Do not add `continue-on-error`, ignore failure
exit codes or weaken checksum coverage to make the suite look successful.

The core `SourcePositionTest` selector is also included so both Maven reactor
modules execute tests; `failIfNoTests` remains enabled.

## Evidence and the path to regression coverage

On code baseline `9b10407c`, the isolated Java 17 run executed all ten scenarios:
**8 assertion failures, 2 passing controls, 0 errors and 0 skips**. Maven returned
1, honestly leaving the suite red. There were no compilation or fixture errors
in that completed run. The test class took 0.568 seconds; the bounded build took
23.01 seconds. The existing selected regression suite separately passed 104
tests with 3 pre-existing skips. Other JDK versions were not tested.

This suite is opt-in because the defects are still open. It is a reproduction
artifact, not a passing acceptance gate. As each defect is fixed, enable its
existing regression in normal discovery and add it to the ordinary regression
workflow's selector in the same change. For the two new Kafka cases, move the
unchanged correctness assertions into the default test suite and reuse their
fixture. Keep the explicit reproduction selector working while cases change
from red to green. Do not make this a permanently detached substitute for tests.

The 20-second cap bounds a hung scenario; the present cases have no external
service to wait for. A timeout is a harness/runtime failure to investigate, not
an ordering counterexample. The class executes serially and resets the same
sequence fields as the existing tests before every method. If the lifecycle
state changes, update and validate that shared reset contract before interpreting
results; cross-test contamination is not proof of a connector defect.

## Version terms

A version is the number used to rank competing row images. The durable horizon
records a version already covered before handoff; the restart floor is the
minimum timestamp derived from that version. The clock-step case calls the real
`VersionHighWaterMark` constructor with its existing `LongSupplier` clock
injection and fake JDBC catalog. It exercises the production 24-hour
`PLAUSIBLE_FUTURE_MS` check with a value 72 hours ahead, without changing the
host clock or inventing a second threshold.

`SnowFlakeId.generate` keeps only bits 0..21 of a GTID transaction number or
fallback Kafka offset; the timestamp occupies bits 22..62. Therefore 4194304
wraps that ordering field to zero. The Kafka fixtures pass offsets through a
real `SinkRecord` and `ClickHouseConverter.convert`, then assert that both the
offset and source timestamp survived conversion before calculating the version.
They do not generate broker traffic or test Kafka delivery itself.

## Scenarios

| Method | Mechanism exercised | Existing fixture or real entry point |
|---|---|---|
| `gtidTieFieldWrap` | Transactions 4194303 then 4194304 at one effective millisecond | `GtidVersionTieBreakTest` |
| `differentUuidTransactionCounters` | Unrelated transaction numbers after a simulated primary change | `GtidVersionTieBreakTest` |
| `mixedGtidAndAnonymousDomains` | A later anonymous transaction competes with a stored GTID-domain version | `GtidVersionTieBreakTest` |
| `inProcessRestartAfterReadingUnwrittenRows` | Redelivery of a read-but-unwritten late-commit pair with retained static position state | `InRunRedeliveryVersionTest` |
| `sameBasenameFailoverToLowerFileNumber` | New source log sequence below the old source's mark | `InRunRedeliveryVersionTest` |
| `durableFloorAfterBackwardClockStep` | Persisted horizon 72 hours ahead of an injected connector clock | `VersionHighWaterMarkClockStepTest` and its fake JDBC catalog |
| `kafkaConnectLateCommitStatementTime` | Real `SinkRecord` conversion retains older statement time for a later GTID commit | `ClickHouseConverter.convert` then `ClickHouseStruct.calculateVersion` |
| `kafkaConnectAnonymousOffsetWrap` | Real converted anonymous records cross the 22-bit Kafka-offset boundary | `ClickHouseConverter.convert` then `ClickHouseStruct.calculateVersion` |
| `controlLightweightLateCommitKeepsOrder` | The existing lightweight floor correctly handles an ordinary late commit | `GtidLateCommitVersionTest` |
| `controlDurableFloorWithinFutureWindow` | A persisted horizon inside 24 hours remains the seed | `VersionHighWaterMarkClockStepTest` |

## Scope

These are real-code **logic reproductions**, not a full MySQL/Kafka/ClickHouse
cluster. The restart and failover fixtures reproduce retained state and changed
coordinates; they do not start or reconnect a Debezium engine. The UUID fixture
uses extracted transaction numbers, not a live UUID handoff. The durable-floor
fixture uses the existing fake JDBC catalog. Kafka cases use the real converter,
but do not run a broker or a sink task.

This suite proves the ordering counterexamples. It does not prove network
failover reachability, crash recovery, actual ReplacingMergeTree readback,
physical multi-GiB binlog behavior or daily/weekly end-to-end value parity.
Those remain separate integration and acceptance checks. Green here is necessary
for these ordering invariants, not sufficient to declare a release safe: a
versioning fix also needs compatibility checks against existing stored versions,
real restart/failover and source-to-target value comparisons. Daily and weekly
checksum acceptance must include nonzero intended table coverage and explicit
range/exclusion reporting. No production action is needed or performed by this
suite.
