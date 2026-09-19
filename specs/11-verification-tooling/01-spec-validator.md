# Spec 11.01: Automated Specification Validator Architecture

## 1. Executive Summary & Purpose
Specifies the automated validation tool (`scripts/validate_specs.py`) that checks the completeness, formatting, and structural synchronization of all specification documents and formal verification artifacts.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `scripts/validate_specs.py`

---

## 3. Operational Specification

### 3.1 Validation Passes
The validator runs four deterministic validation passes:
1. **System Governance**: Asserts presence of `CONSTITUTION.md`, `README.md`, and `SMART_RALPH_PROTOCOL.md`.
2. **Domain Specifications**: Recursively discovers all domain subdirectories (`01-cdc-engine` through `11-verification-tooling`) and asserts that every markdown spec contains required analytical sections:
   - `Executive Summary`
   - `Codebase Mapping`
   - `Invariants Preserved`
   - `Verification Criteria`
3. **Formal Verification Suite (Lean 4)**: Asserts presence of `lean-toolchain`, `lakefile.lean`, and all core Lean modules (`Basic`, `Binlog`, `ClickHouse`, `Engine`, `Invariants`, `Proofs`).
4. **Agent Guidance**: Verifies that `AGENTS.md`, `CLAUDE.md`, and `.github/copilot-instructions.md` link directly to the spec-driven mandate.

---

## 4. Invariants Preserved
- **Spec Integrity**: Prevents un-specified code changes or malformed specifications from merging into the repository.

---

## 5. Verification Criteria
- `python3 scripts/validate_specs.py` exits 0 on valid repository state and non-zero if any required section or file is missing.
