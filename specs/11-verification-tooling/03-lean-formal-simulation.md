# Spec 11.03: Lean 4 Formal Verification & Simulation Model

## 1. Executive Summary & Purpose
Specifies the mathematical formalization of the MySQL-to-ClickHouse replication engine in the Lean 4 proof assistant (`formal_specs/lean/`), how it is built and gated in CI, and — because the Constitution requires honesty about coverage — which invariants and empirical suites are **not** covered.

---

## 2. Codebase Mapping on 2.11.0
- **Package**: `formal_specs/lean/`
- **Modules** (all listed as roots in `formal_specs/lean/lakefile.lean`):
  - `Replication.Basic`: Relational foundational types
  - `Replication.Binlog`: Binlog positions and transition system
  - `Replication.ClickHouse`: ReplacingMergeTree and FINAL semantics
  - `Replication.Engine`: Translation semantics and state machine
  - `Replication.Invariants`: Propositions for I1–I4 and the replay-idempotency statement
  - `Replication.Proofs`: Machine-checked theorems and proofs
  - `Replication.Upgrade`: Drop-in upgrade safety (I11)
  - `Replication.Snapshot`: Snapshot completion and control-record offset commit (I12)
  - `Replication.GeneratedColumn`: Generated-column type integrity (I13)
  - `Replication.DdlBarrier`: DDL barrier covers every handoff path (Invariant I5, spec 06.01)
  - `Replication.OffsetFifo`: Handoff-sequence FIFO for offset acknowledgement (Invariant I8, spec 09.01)
  - `Replication.DdlTranslation`: ALTER clause classification (specs 06.03/06.04/06.05/06.07)
  - `Replication.BatchOrder`: batch execution order around a replicated TRUNCATE (spec 04.05)
  - `Replication.VersionFloor`: the shipped version-sequence statics, the restart-boundary floor seed and control-record exclusion (Invariant I2 across a restart, specs 02.02 §3.5 / 02.04 §3.2)
  - `Replication.CreateTable`: CREATE TABLE sorting-key selection (specs 06.05 §3.6 / 08.05 §3.2)
- **CI**: `.github/workflows/spec-governance.yml`
- **Empirical gap registries**: `sink-connector-lightweight/tests/integration/regression_manual.py` (TestFlows `xfails`), `@Disabled` annotations under `sink-connector/src/test` and `sink-connector-lightweight/src/test`

---

## 3. Operational Specification

### 3.0 Model design (what is abstracted, and faithfully)
- **Versioning is by stream ordinal.** `replicateFrom` assigns each event a
  strictly increasing version (`liveVersion i = 2*i`; the relocation tombstone
  carries the SAME version, `tombstoneVersion i = liveVersion i`, exactly as
  the connector binds `record.getVersion()` for both rows). This is the
  abstract form of the connector's guarantee that every committed event
  receives a strictly greater `_version` than the events before it — the
  property the high-water floor and sequence counter enforce concretely.
  Because the ordinal is strictly monotonic by construction, convergence holds
  for ANY stream, with no monotonic-position hypothesis required. The shipped
  formula `effectiveTs * 1_000_000 + seq`, the floor `sequenceMaxSourceTs`, the
  counter seeds and the high-water position are modelled separately in
  `VersionFloor.lean` for the **restart boundary** only (seeding the floor from a
  high-water mark orders every first delivery of a new run above the previous
  run; control records do not touch the state); within-run monotonicity of that
  formula and the GTID precedence are not modelled (specs 02.01–02.04).
- **`FINAL` resolves equal versions to the later-inserted row.** `maxStep`
  compares with `>=`, so among records of one key with equal version the one
  appended later wins — ClickHouse's ReplacingMergeTree tie rule. This is what
  makes a tombstone written at the same version as an earlier live row (the
  same-transaction relocation case, Spec 05.02 §3.2) retire it.
- **`encodeVersion` is a separate, documented order-witness.** The
  `(fileSeq, offset, rowIdx)` → integer encoding is proved strictly monotonic
  only within `BinlogPos.WellFormed` (offset/rowIdx bounded so the fields do
  not carry). It is NOT used by the engine — a single integer cannot encode an
  unbounded lexicographic triple monotonically, which is exactly why the real
  connector versions by time+sequence, not by position.
- **TRUNCATE clears the ClickHouse table** in `replicateFrom`, in lock-step with
  `applyBinlogEvent` emptying the source — matching the connector executing
  `TRUNCATE TABLE`. (A per-event translation cannot enumerate keys, so this must
  live in the fold, not in `translateEvent`.)

### 3.1 Theorem Inventory (all machine-checked, zero `sorry`)
1. `version_strictly_monotonic`: within `BinlogPos.WellFormed`, coordinate
   advance preserves strict version monotonicity ($p_1 < p_2 \implies v_1 < v_2$).
2. `insert_convergence_single`: single-row insert parity between MySQL and ClickHouse `FINAL`.
3. `delete_convergence_single`: tombstone erasure in ClickHouse `FINAL`.
4. `update_same_key_convergence`: in-place updates converge to the latest row.
5. `update_pk_relocation_soundness`: two-phase tombstoning cleans up the old key and establishes the new key (tombstone and live row share the version).
6. `tombstone_wins_version_tie`: a tombstone appended after a live row of the
   same key and the SAME version evaluates the key to `none` — the tie rule
   the equal-version relocation relies on.
7. `master_replication_convergence`: global convergence for ALL binlog streams
   (`chFinalView (replicateStream events) k = evalMySQL events emptyMySQL k`),
   proved via the general lemma `replicate_converges_gen` by induction on the
   stream with a coherence + version-bound invariant.
7. `Upgrade.lean`: `replicate_convergesV`, `upgrade_safe`, `liveVersion_gapMono` (I11; see spec 02.06 §6 for the hypothesis the code does not yet establish).
8. `Snapshot.lean`: `control_commit_safe`, `committed_stable_while_outstanding`, `quiescent_control_commits`, `handoffs_preserve_committed`, `snapshot_completes` (I12).
9. `GeneratedColumn.lean`: `alter_preserves_type`, `generated_expr_is_default`, `type_is_never_expression`, `generated_has_default` (I13).
10. `OffsetFifo.commit_never_passes_outstanding`, `acked_downward_closed`,
   `commitPoint_acked`, `outstanding_ge_commitPoint`: in every reachable state
   of the handoff FIFO, acknowledged sequences are a prefix of the handoff order
   and no outstanding sequence lies below the commit point.
11. `OffsetFifo.write_at_most_once`, `written_batch_not_reexecuted`: a batch's
   write event occurs at most once; a written (parked) batch is never executed
   again.
12. `OffsetFifo.old_overlap_rule_unsafe`: concrete counterexample — two batches
   with equal timestamps where the strict timestamp-overlap rule acknowledges
   the later-finished one while the other is outstanding, and the FIFO does not.
13. `BatchOrder.segments_match_source`, `segmented_batch_converges`: executing a
   batch split at every TRUNCATE as ordered segments equals applying the
   events in binlog order; `every_truncate_is_its_own_segment`: two TRUNCATEs
   never collapse; `truncate_last_loses_rows`, `truncate_first_resurrects_rows`:
   the pre-fix hash-map order disagrees with the source either way (spec 04.05).
14. `VersionFloor.restart_boundary`, `version_ge_floor`, `floor_mono`,
   `seed_floor_gt`: with the floor seeded as `v / 1_000_000 + 1` from a
   high-water mark `v` at or above every previous version, every first delivery
   of the new run is versioned strictly above `v`.
15. `VersionFloor.dispatch_control_preserves_state`,
   `old_dispatch_control_moves_floor`, `dispatch_control_keeps_source_floor`:
   a heartbeat leaves the sequence statics unchanged; the pre-fix loop pinned
   the floor to the connector clock.
16. `VersionFloor.seeded_restart_example`, `unseeded_restart_inverts`: the
   regression scenario of spec 02.02 §6, executed (`decide`) with and without
   the seed.

The proposition `ReplayIdempotency` in `Invariants.lean` is stated but has no theorem.

### 3.2 Build, axiom verification and CI gate
- `lake build` in `formal_specs/lean/` type-checks every theorem. The
  `lean_lib` is marked `@[default_target]` in `lakefile.lean`; without that
  attribute a bare `lake build` has no target, reports success and compiles
  nothing (observed before this was added), so the attribute is part of the
  verification contract. Proof of a real build is the per-module
  `Built Replication.<Module>` lines.
- Each theorem depends only on Lean's standard axioms `[propext, Quot.sound]` —
  verified with `#print axioms` — and on NO `sorryAx`.
- **CI builds the proofs on every pull request and on every push to `2.11.0`**:
  the `Spec Governance` workflow (`.github/workflows/spec-governance.yml`)
  installs `elan` with no default toolchain so that `lake` resolves exactly the
  version pinned in `formal_specs/lean/lean-toolchain` (`leanprover/lean4:v4.11.0`),
  runs `lake build`, greps every `.lean` file for `sorry`/`admit`/`native_decide`,
  runs the validator self-tests and then `python3 scripts/validate_specs.py
  --changed-base origin/<base branch>` (spec 11.01). `scripts/validate_specs.py`
  independently rejects any `sorry`/`admit`/`native_decide` and any module
  missing from the lakefile roots; with `--lake` it also runs the build.

---

## 4. Invariants Preserved
- **Mathematical Correctness**: Provides a machine-checked proof of
  replication convergence (Invariant I3), sorting-key relocation soundness (I4),
  ordinal version monotonicity in the model (I1/I2 in abstract form), upgrade
  convergence under gap-monotone versions (I11), control-record offset safety
  and liveness (I12) and generated-column type integrity (I13). See
  `formal_specs/lean/README.md` §6 and `specs/CONSTITUTION.md` §5 for the
  per-invariant coverage table, including the invariants with no Lean
  proposition (I6, I7, I9, I10).

---

## 5. Verification Criteria
- `lake build` exits 0 with zero type errors and zero `sorry`.
- `#print axioms master_replication_convergence` lists only `[propext, Quot.sound]`.
- `Replication.Proofs.master_replication_convergence`, `Replication.Upgrade.upgrade_safe`, `Replication.Snapshot.snapshot_completes`, `Replication.GeneratedColumn.type_is_never_expression` are declared and built by CI.

---

## 6. Known coverage gaps in the empirical suites
These tests exist in the tree but do not run, or are registered as expected
failures. They are listed here so the gaps are visible next to the proofs;
none of them is covered by a Lean theorem either.

### 6.1 `@Disabled` JUnit classes (whole class skipped)
| Module | Class | Note |
|---|---|---|
| sink-connector | `ClickHouseAutoCreateTableIT` | class-level; also `testCreateMergeTreeHistoryTable`, `testCreateNewTable` individually |
| sink-connector-lightweight | `ClickHouseDebeziumEmbeddedMongoIT` | MongoDB source |
| sink-connector-lightweight | `SinkConnectorClientRestAPITest` | REST client |
| sink-connector-lightweight | `EmployeesDBIT` | sample-database replication |
| sink-connector-lightweight | `AlterTableSchemaOverrideByDataTypeMappingIT` | schema override on ALTER |
| sink-connector-lightweight | `AutoCreateTableIT` | lightweight auto-create |
| sink-connector-lightweight | `MergeTreeHistoryTableWithAdditionalColumnsIT` | history table with extra columns |
| sink-connector-lightweight | `ClickHouseDelayedStartIT` | ClickHouse unavailable at start |
| sink-connector-lightweight | `DestinationDBColumnMissingIT` | target column missing |
| sink-connector-lightweight | `BatchRetryOnFailureIT` | batch retry end to end (spec 10.02) |

### 6.2 `@Disabled` JUnit methods (class otherwise runs)
| Module | Method | Note |
|---|---|---|
| sink-connector | `DbWriterTest.testGetEngineType()` | engine detection via `SHOW CREATE TABLE` |
| sink-connector | `DbWriterTest.testBatchInsert()` | annotated "This test is not working" |
| sink-connector-lightweight | `MySqlDDLParserListenerImplTest.testPartitionedByRangeTable()` | `PARTITION BY RANGE` translation |
| sink-connector-lightweight | `MySQLDemoIT.testModifyOfficeCode()` | sorting-key column modification |
| sink-connector-lightweight | `SourceDBColumnMissingIT.testColumnMismatch()` | source column missing |
| sink-connector-lightweight | `Debezium15KTablesLoadIT.testLoadingTablesInSchemaOnlyMode()` | 15k-table load |

### 6.3 TestFlows expected failures (`regression_manual.py`, `xfails`)
Keys of the `xfails` dictionary at the top of `sink-connector-lightweight/tests/integration/regression_manual.py`; each entry marks the named scenario as an expected `Fail` with the quoted reason:
`schema changes/table recreation with different datatypes` (debezium data conflict crash),
`schema changes/consistency` and `consistency` (doesn't finished),
`primary keys/no primary key` (issue #39),
`delete/no primary key innodb`, `delete/no primary key` (doesn't work in raw),
`update/no primary key innodb`, `update/no primary key` (makes delete),
`truncate/no primary key innodb`, `truncate/no primary key` (doesn't work),
`partition limits` (doesn't ready),
`types/json` (doesn't work in raw), `types/double` (issue #170), `types/bigint` (issue #15), `types/enum` (doesn't replicate data),
`delete/many partition many parts`, `delete/one million datapoints`, `delete/many partition one part`,
`update/many partition many parts`, `update/one million datapoints`, `update/many partition one part`
(doesn't work without primary key and doesn't insert duplicates of primary key),
`insert/many partition many parts/*_no_primary_key`, `insert/one million datapoints/*_no_primary_key`,
`insert/many partition one part/*_no_primary_key`, `insert/one partition one part/*_no_primary_key`,
`insert/one partition mixed parts/*_no_primary_key`, `insert/many partition mixed parts/*_no_primary_key`
(no-primary-key variants),
`insert/parallel` (different results in MySQL and ClickHouse).

The keyless-table entries correspond to the GIPK requirement surfaced by the keyless-table preflight (spec 01.01 §3.2); the `types/*` entries are open type-mapping gaps for Domain 07.
