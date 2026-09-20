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
- **CI**: `.github/workflows/spec-governance.yml`
- **Empirical gap registries**: `sink-connector-lightweight/tests/integration/regression_manual.py` (TestFlows `xfails`), `@Disabled` annotations under `sink-connector/src/test` and `sink-connector-lightweight/src/test`

---

## 3. Operational Specification

### 3.0 Model design (what is abstracted, and faithfully)
- **Versioning is by stream ordinal.** `replicateFrom` assigns each event a
  strictly increasing version (`liveVersion i = 2*i`, with the relocation
  tombstone at `2*i - 1`). This is the abstract form of the connector's
  guarantee that every committed event receives a strictly greater `_version`
  than the events before it — the property the high-water floor and sequence
  counter enforce concretely. Because the ordinal is strictly monotonic by
  construction, convergence holds for ANY stream, with no monotonic-position
  hypothesis required. **The shipped formula `effectiveTs * 1_000_000 + seq`,
  the floor `sequenceMaxSourceTs`, the counter seeds and the GTID precedence are
  not modelled** (specs 02.01–02.04 state the consequences).
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
5. `update_pk_relocation_soundness`: two-phase tombstoning cleans up the old key and establishes the new key.
6. `master_replication_convergence`: global convergence for ALL binlog streams
   (`chFinalView (replicateStream events) k = evalMySQL events emptyMySQL k`),
   proved via the general lemma `replicate_converges_gen` by induction on the
   stream with a coherence + version-bound invariant.
7. `Upgrade.lean`: `replicate_convergesV`, `upgrade_safe`, `liveVersion_gapMono` (I11; see spec 02.06 §6 for the hypothesis the code does not yet establish).
8. `Snapshot.lean`: `control_commit_safe`, `committed_stable_while_outstanding`, `quiescent_control_commits`, `handoffs_preserve_committed`, `snapshot_completes` (I12).
9. `GeneratedColumn.lean`: `alter_preserves_type`, `generated_expr_is_default`, `type_is_never_expression`, `generated_has_default` (I13).

The proposition `ReplayIdempotency` in `Invariants.lean` is stated but has no theorem.

### 3.2 Build, axiom verification and CI gate
- `lake build` in `formal_specs/lean/` type-checks every theorem.
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
