# GitHub Copilot Instructions: ClickHouse Sink Connector

## Architectural Philosophy & Core Contract
The ClickHouse Sink Connector is strictly defined as an exact, zero-loss replication tool from MySQL to ClickHouse using the MySQL binary log (binlog).

## Non-Negotiable Operational Rules

1. **Spec-Driven Development (Mandatory)**:
   - This repository follows the Smart Ralph spec-driven development protocol (`specs/SMART_RALPH_PROTOCOL.md`).
   - Every change must begin with a specification declaration in `specs/`.
   - Never generate code changes that diverge from or expand beyond declared specifications.

2. **The Prime Directive: MySQL Is the Source of Truth**:
   - The source MySQL database is always correct.
   - ClickHouse is the replica and must conform to MySQL.
   - Every divergence is resolved by making ClickHouse match MySQL.
   - ALIAS columns are ignored (query-time only).
   - If MySQL defines a column that ClickHouse marks as MATERIALIZED, the MySQL value wins; the connector converts the ClickHouse column to DEFAULT to permit writing.

3. **Invariants & Formal Verification**:
   - Every modification must preserve the 10 System Invariants defined in `specs/CONSTITUTION.md`.
   - The system state machine is formally modeled in Lean 4 (`formal_specs/lean/`). Do not introduce non-monotonic versioning or un-tombstoned sorting key mutations.
   - Always validate with `python3 scripts/validate_specs.py`.
