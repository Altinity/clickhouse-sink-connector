# Spec 01.11: Recovery schema and historical binlog replay

## 1. Executive Summary

A schema snapshot taken after a source ALTER is not the schema of row events
before that ALTER. Reproduce this boundary with the real embedded Debezium
engine and a throwaway MySQL server, without production data or credentials.
Do not treat a successful current-schema snapshot as historical recovery.

## 2. Invariants

- Source values and event ordering remain authoritative (I1, I3, I7).
- Never skip an undecodable event, pad a historical row with invented values,
  or advance its acknowledged offset to make recovery appear successful (I8, I9).
- Normal resume with valid historical schema must decode both pre-ALTER and
  post-ALTER events and preserve their column values.
- A reproduction must execute the native engine's snapshot and replay paths;
  a manually thrown exception or a width-only model is not equivalent evidence.
- Schema-history recovery must not silently replace historical definitions
  needed by a retained older offset with the source's current definition.
- In the opt-in custom `history_preserving_recovery` snapshotter, when an
  offset exists, its initial snapshot is complete, and Debezium has actually
  recovered table definitions from schema history, retain those definitions
  and stream from that offset without a new schema snapshot. Log this choice
  visibly. The built-in `recovery` and all other existing modes are unchanged:
  callers intentionally rebuilding history or onboarding tables retain their
  existing behavior.
- Otherwise retain the pinned recovery snapshotter's original behavior:
  snapshot schema, never data, and retain its missing-history recovery flag.
  Missing or already-incorrect historical definitions cannot be reconstructed
  by this change and must still fail loudly on incompatible events.

## 3. Codebase Mapping

`HistoryPreservingRecoverySnapshotter` extends the pinned Debezium recovery
snapshotter and checks its already-restored `HistorizedRelationalDatabaseSchema`
flag. SPI registration makes it available through Debezium's existing custom
snapshotter mechanism:

```properties
snapshot.mode=custom
snapshot.mode.custom.name=history_preserving_recovery
```

It adds no history query, offset edit, or data write. It does not replace the
built-in recovery class or change its behavior; a global override was rejected
because existing callers may intentionally force a current-schema rebuild.
The extension must be checked against the dependency on upgrades.
`RecoverySchemaPolicy` holds the three-boolean decision without engine dependencies
so generated tests can execute the actual policy with the JDK alone.
`RecoverySnapshotterTest` exercises all eight policy states and the inherited
snapshotter contract. The native integration test exercises restored, missing
and already-incorrect history through the real snapshotter boundary. Lean, Dafny, TLA+ and real-Java Hypothesis specifications
cover the same decision.

`RecoverySchemaReplayIT` runs the pinned Debezium engine on synthetic source
rows. The test owns its MySQL process, source database, offset files and
in-memory schema-history fixture. It never connects to a production endpoint
or starts the ClickHouse sink writer. The server executable is provided by
`MYSQL_PREFIX` in the isolated sandbox.

## 4. Verification

Start with 43 source columns and a committed streaming checkpoint. Stop the
engine, write another 43-column row, append a nullable decimal column, and
write a 44-column row. Preserve the original checkpoint and schema history.
Run independent normal-history and recovery-mode replays from that checkpoint.
Assert actual values, not only row counts, and capture the native error when
current-schema recovery cannot decode the older event. Then assert the custom
snapshotter preserves every value with retained history and still fails on
missing or already-incorrect history. Print each executed
phase and the observed server and Debezium versions. An unexecuted phase,
missing checkpoint, timeout, or unexpected error is a failed test.

## 5. Failure Modes & Recovery

- Detection: the native engine completion failure and exact cause chain are
  retained; successful controls assert the values of both replayed rows.
- Recovery: valid historical schema is necessary for historical replay. A
  current-schema snapshot alone must not be described as reconstruction of
  lost history. Any already-skipped interval requires separate reconciliation.
- RTO: no production recovery-time claim. The fixture and each engine run have
  hard wall limits and run only inside the bounded boxcar sandbox.

## 6. Cost

One throwaway source server and sequential embedded-engine runs; four synthetic
rows, one ALTER, and no target-table scan. This is a reproduction, not a
production repair procedure. No production configuration, offset or history is
modified by the test.
