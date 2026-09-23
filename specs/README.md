# ClickHouse Sink Connector: Specification-Driven Development (2.11.0)

Welcome to the specification-driven development (SDD) repository for the `clickhouse-sink-connector`.

This repository operates under a formal, spec-first methodology. The connector is strictly defined as an exact, high-performance, zero-loss replication tool from MySQL to ClickHouse using the MySQL binary log (binlog).

To facilitate fine-grained feature development and rigorous agentic engineering, all specifications are organized into **small, focused, modular encapsulations** across 11 architectural domains.

---

## Master Specification Index

### Core Governance & Protocols
- **[`specs/CONSTITUTION.md`](CONSTITUTION.md)**: System mission, the Prime Directive, and 13 immutable architectural invariants (`I1`–`I13`).
- **[`specs/SMART_RALPH_PROTOCOL.md`](SMART_RALPH_PROTOCOL.md)**: Spec-Driven Agentic Engineering protocol (Smart Ralph).

---

### Domain 01: CDC Ingestion & Binlog Stream Processing (`specs/01-cdc-engine/`)
- **[01.01: Embedded CDC Engine Lifecycle & Bootstrap](01-cdc-engine/01-embedded-engine-lifecycle.md)**: Bootstrap, Guice injection, and graceful shutdown.
- **[01.02: Binary Log Coordinate Extraction & Ordering](01-cdc-engine/02-binlog-coordinates.md)**: Coordinate tuples `(fileSeq, offset, rowIdx)` and total ordering.
- **[01.03: CDC Event Batch Dispatch & Triage Loop](01-cdc-engine/03-event-dispatch-loop.md)**: `handleChangeEventBatch` loop and DDL/DML/Control triage.
- **[01.04: Transaction Boundaries & Timestamp Semantics](01-cdc-engine/04-transaction-boundaries.md)**: Statement vs. commit timestamp handling and floor clamping.
- **[01.05: Batch Buffering & Worker Handoff Queue](01-cdc-engine/05-batch-buffering-handoff.md)**: `LinkedBlockingQueue` handoff and backpressure propagation.
- **[01.06: Control Records, Heartbeats & Snapshot Quiescence](01-cdc-engine/06-control-records-heartbeats.md)**: Pipeline quiescence verification before control commits.

---

### Domain 02: Monotonic Versioning & Ordering (`specs/02-versioning/`)
- **[02.01: 64-Bit Monotonic Version Formula & Bit Allocation](02-versioning/01-version-formula-encoding.md)**: Mathematical formula: $V = (\text{effectiveTs} \times 10^6) + \text{seq}$.
- **[02.02: Commit Monotonicity Floor & Late-Commit Inversion Prevention](02-versioning/02-commit-monotonicity-floor.md)**: High-water clamping (`sequenceMaxSourceTs`).
- **[02.03: Intra-Window Sequence Counter Lifecycle & Reset](02-versioning/03-sequence-counter-lifecycle.md)**: Counter reset at the 2000 ms anchor boundary, `SEQUENCE_START` / `SEQUENCE_START_INITIAL` seeds.
- **[02.04: Redelivery Stability & Offset Rewind Versioning](02-versioning/04-redelivery-stability.md)**: Preserving high-water coordinates during replays.
- **[02.05: Version Validation & Underivable Version Rejection](02-versioning/05-version-validation.md)**: Fail-fast assertion rejecting non-positive versions.

---

### Domain 03: ClickHouse Batch Writer & Execution Engine (`specs/03-execution-engine/`)
- **[03.01: Scheduled Batch Executor & Worker Thread Pool](03-execution-engine/01-executor-thread-pool.md)**: `ClickHouseBatchExecutor` and gate monitor.
- **[03.02: Dual Execution Engines: Multi-Threaded vs. Single-Threaded Modes](03-execution-engine/02-execution-modes.md)**: Behavioral parity between runnable and writer.
- **[03.03: Batch Dequeuing & Topic Partitioning](03-execution-engine/03-topic-partitioning.md)**: Grouping batches by target topic/table.
- **[03.04: Destination Database & Table Name Resolution](03-execution-engine/04-destination-resolution.md)**: Override maps, prefixes, and schema suffix templates.
- **[03.05: HikariCP Connection Pooling & Lifecycle Management](03-execution-engine/05-connection-pooling.md)**: Thread-confined JDBC connections and pooling.
- **[03.06: PreparedStatement Batch Flushing & Limits](03-execution-engine/06-jdbc-batch-flushing.md)**: `buffer.max.records` and `buffer.flush.time` boundaries.

---

### Domain 04: Query Template & Statement Construction (`specs/04-query-generation/`)
- **[04.01: Query Template Grouping & Cache Keying](04-query-generation/01-query-template-grouping.md)**: Partitioning records by prepared statement signature.
- **[04.02: Parameterized Insert Query Formatting](04-query-generation/02-insert-query-formatting.md)**: Parameterized SQL formatting with engine columns.
- **[04.03: Field Membership Rules: Explicit NULLs vs. Omitted Columns](04-query-generation/03-field-membership-rules.md)**: Preserving explicit `NULL`s vs defaults.
- **[04.04: UPDATE Event Handling & Before/After Image Processing](04-query-generation/04-update-splitting.md)**: In-place update vs history mode.
- **[04.05: TRUNCATE Table Event Handling](04-query-generation/05-truncate-handling.md)**: Executing `TRUNCATE TABLE` on ClickHouse.

---

### Domain 05: Primary & Sorting Key Relocation (`specs/05-sorting-key-mutation/`)
- **[05.01: Sorting Key Relocation Detection Algorithm](05-sorting-key-mutation/01-relocation-detection.md)**: `updateRelocatesSortingKey` algorithm.
- **[05.02: Old Key Tombstone Synthesis & Versioning](05-sorting-key-mutation/02-tombstone-synthesis.md)**: Phase 1 delete tombstone (`is_deleted=1`, `_version` $= V$, later-insert-wins tie).
- **[05.03: New Key Live Row Insertion](05-sorting-key-mutation/03-live-row-insertion.md)**: Phase 2 live row insert (`is_deleted=0`, $V$).
- **[05.04: Sign Column Handling for CollapsingMergeTree](05-sorting-key-mutation/04-collapsing-merge-tree-sign.md)**: Binding `_sign` ($-1$ and $+1$).

---

### Domain 06: DDL Interception, Translation & Barrier Synchronization (`specs/06-ddl-replication/`)
- **[06.01: Pre-DDL Queue Draining & Barrier Synchronization Protocol](06-ddl-replication/01-ddl-interception-drain.md)**: `drainBeforeDDL` protocol.
- **[06.02: Thread Pool Barrier Pause & Quiescence Monitor](06-ddl-replication/02-executor-barrier-pause.md)**: Executor pause and quiescence monitor.
- **[06.03: ANTLR4 MySQL DDL Parser Architecture](06-ddl-replication/03-mysql-ddl-parsing.md)**: Grammars and listener AST traversal.
- **[06.04: ALTER TABLE Clause Translation Rules](06-ddl-replication/04-alter-table-translation.md)**: Clause mapping (ADD, DROP, MODIFY, RENAME).
- **[06.05: Nullability Translation & NOT NULL Modification Rules](06-ddl-replication/05-nullability-rules.md)**: Preserving Nullable to prevent Code: 36.
- **[06.06: Generated Columns Mapping: DEFAULT vs. MATERIALIZED](06-ddl-replication/06-generated-columns.md)**: Mapping to `DEFAULT` to prevent write rejection.
- **[06.07: Primary Key Alteration Rules](06-ddl-replication/07-primary-key-alteration-rules.md)**: Restatement skipped; identity change rebuilt (06.09) or loud.
- **[06.08: DDL Execution, Cache Invalidation & Pipeline Resumption](06-ddl-replication/08-pipeline-resumption.md)**: Cache bump, offset commit, and resume.
- **[06.09: Primary Key Change — Replica Rebuild at the DDL Barrier](06-ddl-replication/09-primary-key-change-rebuild.md)**: MySQL's clustered-index rebuild adapted to ClickHouse; source-valued key columns read from MySQL.

---

### Domain 07: Data Type Mapping & Value Conversion (`specs/07-type-system/`)
- **[07.01: Integer Data Type Mapping & Range Rules](07-type-system/01-integer-types.md)**: Signed/unsigned mapping (Int8..64 / UInt8..64).
- **[07.02: Floating Point & Fixed-Precision Decimal Types](07-type-system/02-floating-decimal-types.md)**: Exact `BigDecimal` scaling without float rounding.
- **[07.03: Temporal Data Types & Timezone Handling](07-type-system/03-temporal-types.md)**: Dates, microsecond timestamps, and timezone mapping.
- **[07.04: Strings, Text, JSON, ENUM & SET Types](07-type-system/04-string-json-enum-types.md)**: UTF-8 strings, JSON objects, and enum/set handling.
- **[07.05: Binary, Bit Fields & Endianness Reversal](07-type-system/05-binary-bit-types.md)**: Reversing bit endianness for ClickHouse compatibility.
- **[07.06: Spatial & Geometric Types (WKB to Geo Types)](07-type-system/06-spatial-geometry-types.md)**: WKB parsing to Point and Polygon.
- **[07.07: Nullability Semantics & Explicit NULL Binding](07-type-system/07-nullability-binding.md)**: Explicit `ps.setNull()` binding.

---

### Domain 08: Schema Catalog & Invalidation (`specs/08-schema-catalog/`)
- **[08.01: DbWriter Schema Cache & Metadata Resolution](08-schema-catalog/01-dbwriter-cache.md)**: Cached column maps, engines, and sorting keys.
- **[08.02: Multi-Epoch Cache Invalidation & Version Counters](08-schema-catalog/02-cache-invalidation-manager.md)**: Table versions and global epoch invalidation.
- **[08.03: Metadata Query Storm Prevention & Proven-Absent Tracking](08-schema-catalog/03-query-storm-prevention.md)**: Proven-absent column set cache.
- **[08.04: Column Writability Enforcement & MATERIALIZED to DEFAULT Alteration](08-schema-catalog/04-column-writability-enforcement.md)**: Automatic ALTER to `DEFAULT`.
- **[08.05: Automatic Target Table Creation & Engine Selection](08-schema-catalog/05-auto-table-creation.md)**: Auto-table synthesis and engine resolution.

---

### Domain 09: Offset Management, Quiescence & Checkpointing (`specs/09-offset-management/`)
- **[09.01: FIFO Batch Tracking by Handoff Sequence & Written-Once Acknowledgement](09-offset-management/01-fifo-batch-tracking.md)**: handoff-sequence FIFO (binlog order) for offset acknowledgement; a written batch is never re-executed.
- **[09.02: Mutual Exclusion & Debezium Flush Semaphore Protection](09-offset-management/02-offset-commit-lock.md)**: `OFFSET_COMMIT_LOCK` synchronization.
- **[09.03: ClickHouse-Backed Durable Offset Storage (`replica_source_info`)](09-offset-management/03-clickhouse-offset-store.md)**: ReplacingMergeTree offset store.
- **[09.04: Non-DML Control Record Commit & Quiescence Gating](09-offset-management/04-quiescent-control-commit.md)**: Gating control record commits.

---

### Domain 10: Error Classification, Recovery & Metrics (`specs/10-resilience-monitoring/`)
- **[10.01: Error Classification Taxonomy & Code Mapping](10-resilience-monitoring/01-error-classifier-taxonomy.md)**: FATAL, RETRIABLE, UNKNOWN classification.
- **[10.02: Exponential Backoff & Retry Interval Calculation](10-resilience-monitoring/02-retry-exponential-backoff.md)**: Backoff intervals and retry ceilings.
- **[10.03: Replica Status View & Monitoring Metrics](10-resilience-monitoring/03-replica-status-view.md)**: `<offset database>.show_replica_status` lag view (configured by `replica.status.view`).
- **[10.04: Loud Failure Guarantee & Anti-Swallowing Protocol](10-resilience-monitoring/04-loud-failure-guarantee.md)**: Invariant I9 (Loud Failure) enforcement.
- **[10.05: Redelivered-Record De-duplication](10-resilience-monitoring/05-record-deduplication.md)**: `deduplication.policy` keys on event identity `(topic, partition, offset)`, never on the row key.

---

### Domain 11: Verification, Tooling & Checksums (`specs/11-verification-tooling/`)
- **[11.01: Automated Specification Validator Architecture](11-verification-tooling/01-spec-validator.md)**: `scripts/validate_specs.py` verification engine.
- **[11.02: Value-Level Checksum Verification (`db_compare`)](11-verification-tooling/02-db-compare-checksums.md)**: Bitwise checksumming vs row count fallacies.
- **[11.03: Lean 4 Formal Verification & Simulation Model](11-verification-tooling/03-lean-formal-simulation.md)**: Mathematical state machine proofs.

---

## Formal Verification in Lean 4
The mathematical proof assistant files are located in `formal_specs/lean/`:
- **[Formal Model & Verification Guide](../formal_specs/lean/README.md)**
- `Replication.Basic`: Core data types (Key, Value, Row, Schema).
- `Replication.Binlog`: Binlog positions and MySQL transition semantics.
- `Replication.ClickHouse`: ReplacingMergeTree storage model and FINAL view.
- `Replication.Engine`: Translation semantics and state machine.
- `Replication.Invariants`: Formal mathematical definitions of invariants.
- `Replication.Proofs`: Complete machine-checked proofs of convergence and consistency.
