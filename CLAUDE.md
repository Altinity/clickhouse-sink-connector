# CLAUDE.md

Read [AGENTS.md](AGENTS.md) before making any change to this repository.

It carries the prime directive for this codebase, which decides most design
questions here:

> **This project is a replication engine from a transactional source
> database (MySQL, PostgreSQL) into ClickHouse. The source data is the
> truth and the source is always correct. The ClickHouse side must conform
> to the source, and every mismatch is fixed by making ClickHouse match the
> source — always.**

Two rules from it that are wrong most often, so they are repeated here:

- **ALIAS columns:** ignore them. Not stored, so nothing can diverge.
- **MATERIALIZED columns:** these *are* stored, computed by ClickHouse. If
  the source also defines a column of that name, the replica silently holds
  ClickHouse's derived value instead of the source's — no error, matching
  row counts. That is a real divergence, and the remediation is on the
  ClickHouse side.

AGENTS.md also covers the fail-loudly rules, the value-level verification
requirement (row counts are not proof), and the repository layout.
