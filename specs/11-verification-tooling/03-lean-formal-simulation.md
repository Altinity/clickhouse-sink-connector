# Spec 11.03: Lean 4 Formal Verification & Simulation Model

## 1. Executive Summary & Purpose
Specifies the mathematical formalization of the MySQL-to-ClickHouse replication engine in the Lean 4 proof assistant (`formal_specs/lean/`).

---

## 2. Codebase Mapping on 2.11.0
- **Package**: `formal_specs/lean/`
- **Modules**:
  - `Replication.Basic`: Relational foundational types
  - `Replication.Binlog`: Binlog positions and transition system
  - `Replication.ClickHouse`: ReplacingMergeTree and FINAL semantics
  - `Replication.Engine`: Translation semantics and state machine
  - `Replication.Invariants`: Mathematical definitions of system invariants
  - `Replication.Proofs`: Machine-checked theorems and proofs

---

## 3. Operational Specification

### 3.1 Theorem Inventory
1. `version_strictly_monotonic`: Proves that coordinate advance preserves strict version monotonicity ($p_1 < p_2 \implies v_1 < v_2$).
2. `insert_convergence_single`: Proves single-row insert parity between MySQL and ClickHouse `FINAL`.
3. `delete_convergence_single`: Proves tombstone erasure in ClickHouse `FINAL`.
4. `update_same_key_convergence`: Proves in-place updates converge to latest row.
5. `update_pk_relocation_soundness`: Proves two-phase tombstoning cleans up old key and establishes new key.
6. `master_replication_convergence`: Proves global convergence across arbitrary well-formed binlog streams.

### 3.2 Build Verification
- Running `lake build` in `formal_specs/lean/` checks all theorems and verifies the replication simulation mathematically.

---

## 4. Invariants Preserved
- **Mathematical Correctness**: Provides formal proof of replication convergence.

---

## 5. Verification Criteria
- `lake build` exits 0 with zero type errors.
