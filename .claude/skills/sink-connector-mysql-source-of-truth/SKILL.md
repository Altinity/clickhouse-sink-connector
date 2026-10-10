---
name: sink-connector-mysql-source-of-truth
description: >
  Use whenever a change touches replication semantics between MySQL/Postgres
  and ClickHouse in this connector — column-kind handling, ordering,
  offsets, DDL propagation, or anything claiming to fix a divergence
  between source and target. States the prime directive, the column-kind
  rules, and the ordering/offset invariants that have caused real
  correctness bugs when violated.
---

# MySQL/Postgres Is the Source of Truth

## Overview
This is the project's prime directive, already stated in `AGENTS.md`:
MySQL (or Postgres) is the absolute source of truth; ClickHouse, as the
replication target, must conform to it. The connector is an **active
enforcer of convergence**, not a passive reporter of whatever state
ClickHouse happens to be in. Every rule below is a specific, previously
violated consequence of that directive — read `AGENTS.md` and
`specs/CONSTITUTION.md` for the formal statement; this skill is the
review-time checklist built from real failure modes.

## Column-Kind Rules

| Kind | Rule |
|---|---|
| `ALIAS` | Never stored; ignore entirely when comparing or replicating — it is computed by ClickHouse, not data from the source. |
| `MATERIALIZED` | The source's value wins whenever the source defines that column; the connector must detect drift and reissue `ALTER TABLE ... MODIFY COLUMN` rather than let ClickHouse's locally computed value stand. |
| `DEFAULT` / ordinary | Bind the source value exactly, including `NULL` — never silently substitute a default for a `NULL` source value. |

These are the two most-violated rules in practice (per `CLAUDE.md`): code
that treats an `ALIAS` column as real stored data, and code that lets a
`MATERIALIZED` column's locally computed value silently diverge from what
the source would produce.

## Fail Loudly, Not Silently

Per Invariant I9 (Loud Failure), a silent divergence is worse than a loud
error. A row-count match between source and target is not proof of
correctness — a dropped column whose value was silently replaced by its
`DEFAULT` can produce matching row counts while every value in that column
is wrong. Verification tooling (`sink-connector/python/db_compare`) should
be trusted for value-level checks, not just counts, and any change to it
should be reviewed with that in mind.

## Ordering Invariants

Two properties of the replication path are easy to assume and expensive to
get wrong:

1. **At-least-once delivery requires idempotent apply.** The connector may
   redeliver an already-applied change after a restart; the apply logic
   must be safe to run twice on the same change without producing a
   different final state. Code that assumes "this batch has never been
   seen before" is a latent correctness bug the moment a restart happens
   mid-batch.
2. **The source's statement timestamp is not the commit/visibility time.**
   A `ts_ms`-style field captured at statement execution time can be
   earlier than when the transaction actually became visible, especially
   under longer-running transactions. Code that orders events by that
   timestamp rather than by binlog/WAL position (or an equivalent
   monotonic source-ordering key) can apply changes out of order even
   though each individual timestamp looks correct — this is a concrete
   instance of what Invariant I2 (Deterministic Version Monotonicity)
   exists to prevent. Binlog/WAL position is the only ordering that is
   actually correct; wall-clock-adjacent fields are a convenience field,
   not an ordering key.
3. **The committed offset must always lag the corresponding write, never
   lead it.** If the offset is marked committed before the write it
   corresponds to is durably applied, a crash in between leaves the
   connector believing work is done that never happened — a direct
   violation of Invariant I8 (Durable Offset Quiescence). Any change to
   offset-commit timing should be checked against this ordering explicitly,
   not just against "does it still pass existing tests."

## Known Unsupported / Edge-Case Source Operations

These are real operational limitations worth knowing before assuming a
divergence is a connector bug rather than an inherent limitation:

- **Primary-key changes on the source are not supported for in-place
  replication** — a PK change requires a full table rebuild on the target,
  not an incremental repair. Code or tooling that tries to patch around a
  PK change incrementally is solving the wrong problem.
- **Foreign-key cascade operations on older MySQL versions (pre-9.6/9.7)
  have a known replication gap** where a cascading `DELETE`/`UPDATE`
  triggered by a FK constraint may not appear in the binlog the same way an
  explicit statement would, depending on the server version and
  configuration. Don't assume cascade effects are always captured
  identically across source versions without verifying against the
  specific version in use.
- **`ADD COLUMN` on the source requires the connector to pick up the new
  schema, and in some configurations requires a connector restart to take
  effect** — this should be verified empirically against `system.query_log`
  (or the equivalent target-side audit) rather than assumed from the
  connector's documentation alone, since the actual behavior can depend on
  how schema-change detection is configured.
- **DST transition boundaries are a real edge case for timestamp validity**
  — a `DATETIME`/`TIMESTAMP` value that falls in a DST fall-back window can
  be ambiguous on the source and needs an explicit, tested rule for how the
  connector resolves it, rather than leaving it to whatever the underlying
  driver happens to do.

## Specify Before Implementing

For any of the above areas, a behavior change belongs in `specs/` before
the code, per the Smart Ralph Protocol — these are exactly the kinds of
subtle ordering and edge-case rules that are cheap to get right when
written down first and expensive to get right by trial and error against
production data.

## If This Conflicts With Something Else

Per `AGENTS.md`'s own closing rule: when this skill's guidance conflicts
with anything else, the prime directive (MySQL/Postgres is the source of
truth, ClickHouse must conform) wins.

## Cross-References
`pr-self-review`, `destructive-operation-safety`, `code-quality-sql`,
`code-quality-jvm`, `repro-harness-negative-proof`.
