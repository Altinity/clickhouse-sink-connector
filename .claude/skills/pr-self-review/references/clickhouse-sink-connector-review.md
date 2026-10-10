# Domain Review Checklist: clickhouse-sink-connector

This reference adapts a gate-based C++/ClickHouse-server review methodology
(the kind used by Altinity's own ClickHouse code review practice) to this
project's actual domain: a CDC connector that replicates MySQL/Postgres
into ClickHouse. The five gates and severity model live in the main
`pr-self-review` skill; this file is the per-invariant and per-domain
supporting checklist to run during Gate 1–3 when the diff touches
replication semantics.

Use this alongside `sink-connector-mysql-source-of-truth` — that skill
explains *why* each rule exists; this file is the terse checklist form to
run during a review pass.

## Per-invariant review questions

Ask the question that matches whichever invariant(s) from
`specs/CONSTITUTION.md` the diff touches. Don't ask all fifteen on every
PR — only the ones the change actually risks.

- **I2 (Deterministic Version Monotonicity)** — can two different orderings
  of the same source events produce two different final states in
  ClickHouse? If yes, this is a Blocker regardless of how rare the
  reordering is in practice.
- **I6 (column-kind handling, if numbered separately in your spec set)** —
  does the diff treat an ALIAS column as stored, or let a MATERIALIZED
  column's value diverge from what MySQL/Postgres would compute?
- **I8 (Durable Offset Quiescence)** — can the committed offset ever get
  ahead of what has actually been durably applied to ClickHouse? An offset
  commit must always lag the corresponding write, never lead it.
- **I9 (Loud Failure)** — is there a path where a row is dropped, a batch
  is skipped, or an error is caught and silently ignored instead of
  surfacing as a loud, actionable failure?
- **I14 (Bounded Bookkeeping)** — does the diff add a scan over replicated
  or target-table data whose cost grows with table size rather than with
  the size of the actual work item? If so, it needs an
  `I14-scan-allowed: <spec reference>` marker or it should be rejected.
- **I15 (Bounded, Declared Recovery)** — after a crash or restart, does
  recovery still complete within the declared RTO, or does this diff add
  an unbounded resync/rebuild path on the recovery route?

## Supporting domain checks

1. **DDL translation** — a change to the DDL-translation path (source
   DDL → ClickHouse DDL) must be checked against more than one source
   dialect/version if the translation logic is shared; a rule that is
   correct for one source version and silently wrong for another is a
   common defect class here.
2. **Checksum / verification tooling** (`sink-connector/python/db_compare`
   and related tools) — a change to row counts, hashing, or comparison
   logic should be checked for whether a row-count match can still hide a
   value-level mismatch (e.g., a dropped column silently replaced by its
   `DEFAULT`). Prefer value-level verification over count-only checks when
   touching this area.
3. **Dual verification** — for anything claiming to fix a replication
   correctness bug, does the PR include both an empirical test that fails
   without the fix, and, where the touched invariant has a Lean 4 module
   under `formal_specs/lean`, a proof update or at least a note on why the
   existing proof still covers the new code path?
4. **Spec citation accuracy** — when a PR cites `(Spec NN.MM §section)`,
   confirm the spec actually says what the PR claims it says. A citation
   to the wrong section, or to a spec that was never updated to match a
   behavior change, is a Gate 1 finding.
5. **Schema change handling** — a change affecting how the connector reacts
   to `ADD COLUMN`/`DROP COLUMN`/type changes on the source should be
   checked for whether it requires a restart to take effect, and whether
   that requirement is documented where an operator would see it.

## What this checklist does not cover

General JVM/Python/Go code quality, concurrency bugs, and SQL craft are
covered by the `code-quality-<lang>` skills — don't duplicate that work
here. This file is specifically about replication correctness and the
project's own invariants.
