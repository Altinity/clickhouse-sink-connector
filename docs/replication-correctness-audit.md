# Replication correctness audit

Audit baseline: `ab2ff7730391b7689b48a8715b8c0ce286c2d8dd`, 2026-11-15.

## Acceptance and scope

Source values are authoritative. A successful unit suite is not proof of replica
parity. Acceptance requires successful scheduled value-level checksums for the
intended daily and weekly populations, with explicit table, column and range
coverage. A successful job that compares zero tables proves no data parity.

The review covered version assignment and persistence, position extraction,
batch grouping, write/offset ordering, cancellation, schema refresh, checksum
canonicalization, child endpoint selection and binary binding. It is not an
exhaustive proof of every supported source, type, deployment or failover path.
No physical multi-GiB binlog integration or live failover was performed for this
audit. Production data reconciliation is not part of these source changes.

## Reproduced defects addressed by the accompanying changes

- Schema refresh stopped after the first unknown field. A record carrying two
  uncached fields could recover the first and silently omit a second missing
  field from its first INSERT. Verify the entire record before grouping; bound
  retries by its field count and fail loudly if verification cannot finish.
  Regression: `SchemaCacheFailureModesTest.everyUnknownColumnMustBeVerifiedBeforeGrouping`.
- Binary binding read a ByteBuffer's whole backing array. A payload at position
  1 and limit 3 over `11 22 33 44` bound `11223344`, not `2233`. Read remaining
  bytes through a duplicate, including direct/read-only carriers, without
  consuming the original. Regressions: `ClickHouseDataTypeMapperBitBytesTest`
  and the enabled slice test in `ClickHouseDataTypeMapperPoisonValueTest`.
- Checksums retained replica physical column order instead of source ordinal
  order. Source `(a=1,b=2)` and replica physical `(b=1,a=2)` both produced
  `1#2#00`; wrong named values could falsely match. Align replica metadata to
  source order before building values and NULL flags. Regression:
  `db_compare/tests/test_checksum_identity.py`.
- The checksum driver omitted child connection options. Discovery/locking and
  hashing could use different ports or replica configuration files. Forward the
  source port and replica port/configuration consistently; never put passwords
  in argv. Regression: `db_compare/tests/test_checksum_identity.py`.

- Interrupted synchronous persistence returned normally. Its caller could then
  acknowledge a later durable record beyond the failed INSERT. Propagate
  `BatchPersistenceException`, retain the cause and interrupt status, and stop
  before subsequent acknowledgements. Regressions:
  `MissingTargetTableEngineTest.interruptedInsertStopsBeforeLaterAcknowledgement`
  and `interruptedAcknowledgementFailsTheCaller` exercise the actual writer.
- Resetting the sequence counter discarded carry after a high-volume window.
  A two-million-row example fell from `1757900003001000000` to
  `1757900003000000000`. Retain the maximum assigned sequence value and guard
  first deliveries, including later statements of the marked compressed event,
  without changing the persisted numeric domain. Regressions:
  `CounterCarryResetInversionTest`, including older-event replay controls.

## Ordering audit status

These are reproduced failures, not assurances that production has encountered
every case. The engine-restart case below is corrected by a seeded engine epoch;
the other listed families remain open. Existing disabled tests were invoked
directly against the compiled implementation; their failures must not be hidden
by a green selected suite. They need a compatible version/replay design, not a
multiplier change.

1. **GTID tie-field wrap and UUID transition.** `SnowFlakeId.generate` retains
   only 22 transaction bits. Under one effective millisecond, GTID 4194303 then
   4194304 yields `1967401333031960575` then `1967401333027766272`: the newer
   update loses. Transaction numbers from different UUIDs are not comparable
   either. See `GtidVersionTieBreakTest` and
   `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/SnowFlakeId.java`.
2. **In-process engine restart — corrected.** An old static position mark
   classified previously read but never persisted rows as replay. The baseline
   late-commit pair became `1757900001110000003` then `1757900001100000004`.
   Startup now establishes the version floor before clearing the position gate;
   missing or unreadable seeding stops startup. Anchor, counter, assigned-version
   maximum and persisted encoding are unchanged. The original restart regression
   is enabled and calls the actual startup-seeding path. `EngineEpochVersionTest`
   also covers partial writes, DELETE/re-INSERT and failed seeds. This does not
   establish attribution to any historical data mismatch.
3. **GTID/anonymous numeric domains.** An anonymous transaction a minute later
   produced `1757900061000000000`, below the preceding GTID version
   `1967401312056246772`. Simply switching to sequence versions would also lose
   against existing snowflakes. See
   `GtidVersionTieBreakTest.laterRowWithoutGtidRanksAboveEarlierGtidRow`.
4. **Same-basename source failover.** A different primary with a lower log
   sequence is mistaken for old delivery. See
   `InRunRedeliveryVersionTest.failoverToALowerNumberedLogOfTheSameNameIsAFirstDelivery`.
   Source-generation identity must distinguish this from real replay.
5. **Future durable horizon.** `VersionHighWaterMark.seedFloor` discards a
   persisted floor more than 24 hours ahead of the local clock and can seed a
   lower one after a clock step. The reproducer lowers `1787875201001` to
   `1787616005000`. See `VersionHighWaterMarkClockStepTest`.
6. **Kafka Connect statement-time ordering.** The Kafka conversion path assigns
   neither the lightweight sequence nor its timestamp floor. A later commit
   with an older statement timestamp produced `1967401312056246773` below
   `1967401316250550772`. Its Kafka-offset fallback also truncates the offset
   to 22 bits. See `ClickHouseConverter.readBeforeOrAfterSection`,
   `ClickHouseStruct.calculateVersion` and `SnowFlakeId.generate`.

The durable high-water representation currently decodes sequence-domain values;
GTID snowflakes, raw transaction IDs and history versions have other encodings.
A correction must preserve comparisons against existing destination rows and
restart/replay ordering. An untagged encoding switch is not a safe repair.

## Verification of the combined changes

- Java 17, bounded offline Maven reactor for the 15 affected/neighboring test
  classes: 107 discovered, 104 passed, 3 pre-existing poison-value skips,
  zero failures/errors; exit 0. The permanent workflow includes the changed
  regression classes. This was not the complete Maven integration suite.
- All four Python workflow unit-test directories (`db_compare/tests`,
  `db_load/tests`, `db_dump/tests`, `tests`): 1029 passed, 2 skipped,
  93 subtests passed; exit 0. Local interpreter: Python 3.13.12; the workflow
  selects Python 3.12, so this is not a same-interpreter CI result.
- Local Lean validation did not finish. CI subsequently built
  `Replication.VersionFloor` with Lean 4.11.0 and passed the spec validator
  against code revision `49dd6d68` in
  [run 43684769279](https://github.com/Altinity/clickhouse-sink-connector/actions/runs/43684769279).
  This proves the model builds, not that all replication paths are modeled.
  Existing disabled ordering tests listed above remain known failures, not
  evidence of correctness from their exclusion.
- All tests used isolated, bounded execution. No production checksum, physical
  large-binlog test, source failover or data repair was run.

## Checksum coverage boundary retained intentionally

`top_level_table_checksum.snapshot_slices` starts at the source minimum primary
key. With source keys 10 and 20, it generates an initial condition `id >= 10`;
a replica-only key 1 is outside that scope. Spec 13.06 section 3.7.4 intentionally
retains this boundary for historical data and bounded work. This audit does not
widen that scope or delete historical rows. A MATCH in that range must not be
represented as whole-table parity.

Floating-point/JSON exclusions, ignored columns, empty partitions and any
explicit filters likewise remain visible coverage limits. No exclusion or
failure check is weakened to make scheduled jobs green.
