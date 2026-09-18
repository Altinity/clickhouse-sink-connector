# ClickHouse Sink Connector: Specification-Driven Development (2.11.0)

Welcome to the specification-driven development (SDD) repository for the `clickhouse-sink-connector`.

This repository operates under a formal, spec-first methodology. The connector is strictly defined as an exact, high-performance, zero-loss replication tool from MySQL to ClickHouse using the MySQL binary log (binlog).

---

## Quick Navigation

### 1. Governance & Protocol
- **[System Constitution](CONSTITUTION.md)**: The core mission, Prime Directive, and 10 immutable system invariants (I1–I10).
- **[Smart Ralph Protocol](SMART_RALPH_PROTOCOL.md)**: Agent and developer workflow rules, specification lifecycle, and verification requirements.

### 2. Component Specifications (Ground Truth 2.11.0)
The complete operational behavior of the connector on branch `2.11.0` is specified in detail across nine modular components:

1. **[001: CDC Ingestion & Binlog Stream Processing](001-cdc-ingestion.md)**:
   Embedded Debezium engine, binlog coordinate parsing, GTID extraction, batch buffering, and control record processing.
2. **[002: Monotonic Versioning & Ordering](002-monotonic-versioning.md)**:
   64-bit `_version` calculation, late-committing transaction handling, sequence rollover, and redelivery stability.
3. **[003: ClickHouse Batch Writer & Execution Engine](003-clickhouse-writer-batching.md)**:
   Dual execution engines (`ClickHouseBatchRunnable` and `ClickHouseBatchWriter`), topic partitioning, database overrides, and JDBC batch execution.
4. **[004: Primary & Sorting Key Mutation Handling](004-sorting-key-mutation.md)**:
   Detection of updates to `ORDER BY` sorting keys, two-phase tombstone generation, and new key insertion.
5. **[005: DDL Interception, Translation & Barrier Synchronization](005-ddl-barrier-synchronization.md)**:
   Pre-DDL queue draining, worker thread pausing, ANTLR MySQL DDL translation, nullability rules, and cache invalidation.
6. **[006: Comprehensive Data Type Mapping & Conversion](006-type-mapping.md)**:
   Exhaustive mapping from MySQL types (numerics, dates/times, strings, decimals, bits, spatial) to ClickHouse types.
7. **[007: Schema Catalog, Metadata Caching & Invalidation](007-schema-catalog-invalidation.md)**:
   `DbWriter` column metadata caching, table versioning, global epochs, and `MATERIALIZED` column writability enforcement.
8. **[008: Offset Management, Quiescence & Checkpointing](008-offset-management-quiescence.md)**:
   FIFO batch commitment, `OFFSET_COMMIT_LOCK` synchronization, durable offset storage in `replica_source_info`, and quiescence checks.
9. **[009: Error Classification, Retries & Status Monitoring](009-error-handling-and-recovery.md)**:
   Error taxonomy (FATAL vs RETRIABLE vs UNKNOWN), offset semaphore leak handling, circuit breaking, and replica status views.

---

## 3. Formal Verification in Lean 4

The replication problem can be completely simulated and verified as a mathematical state machine. The formal model and machine-checked proofs are located in `formal_specs/lean/`:
- **[Formal Model & Verification Guide](../formal_specs/lean/README.md)**
- **Source Modules**:
  - `Replication.Basic`: Core data types (Key, Value, Row, Schema).
  - `Replication.Binlog`: Binlog stream and MySQL execution semantics.
  - `Replication.ClickHouse`: ClickHouse `ReplacingMergeTree` storage model and `FINAL` view.
  - `Replication.Engine`: Translation semantics and replication state machine.
  - `Replication.Invariants`: Formal statements of convergence, monotonicity, and source authority.
  - `Replication.Proofs`: Complete machine-checked proofs of convergence and consistency.

---

## 4. Spec Validation Tooling

To ensure that specifications remain synchronized with the codebase and follow all architectural rules, run the automated validator:

```bash
python3 scripts/validate_specs.py
```

The validator verifies:
- Presence and completeness of all component specifications.
- Required sections (Executive Summary, Codebase Mapping, Invariants, Failure Modes, Verification).
- Formal Lean model file presence and structural linkage.
- Spec-first traceability across the repository.
