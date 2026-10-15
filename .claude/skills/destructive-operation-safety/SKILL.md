---
name: destructive-operation-safety
description: >
  Use whenever a change adds or modifies a destructive statement or
  operation — DROP, TRUNCATE, DELETE, ALTER ... DROP, DETACH PARTITION,
  rm -rf, or an apply-mode run of a resync/repair tool. Defines the
  DESTRUCTIVE comment and Destructive-Op-Check commit-trailer convention
  that scripts/review_gates.py's destructive gate enforces in CI.
---

# Destructive Operation Safety

## Overview
A past incident involving an under-reviewed destructive statement in
infrastructure tooling caused unplanned data loss and led to this
project's current convention: every destructive statement must be
visibly marked, counted, and checked before merge — not caught only if a
reviewer happens to notice it in a large diff.

`scripts/review_gates.py`'s **destructive** gate enforces this
mechanically in CI. It cannot judge whether the blast-radius claim in the
`DESTRUCTIVE:` comment is actually true — that's still a human reviewer's
job.

## The Three Requirements

1. **A `DESTRUCTIVE:` comment within 5 lines of each destructive
   statement**, stating what it destroys and the expected blast radius:

   ```python
   # DESTRUCTIVE: drops all rows in the target table for the given
   # partition before reloading from source; blast radius is one
   # partition, confirmed by the --partition argument above.
   cursor.execute(f"ALTER TABLE {table} DROP PARTITION {partition}")
   ```

2. **A `Destructive-Op-Check: sites=<N>; result=pass` commit trailer**,
   where `<N>` is the number of destructive statements touched by the
   commit and `result=pass` asserts each one was reviewed against this
   checklist. The gate checks that the count in the trailer matches the
   number of `DESTRUCTIVE:`-marked sites actually found in the diff —
   a mismatch fails CI.

3. **A dry-run mode for any CLI tool whose normal operation is
   destructive.** This project's own resync/repair tooling (e.g.
   `sink-connector/python/ch_sink_tools/db_load/mysql_resync.py`, which
   spec 13.08 documents as having distinct dry-run and apply modes) is the
   model to follow: the dry-run path must exercise the same logic as the
   apply path right up to the point of mutation, not be a separate,
   driftable code path that can silently stop matching reality. A new
   destructive tool added to this project should follow the same pattern.

## Worked Example

```sql
-- DESTRUCTIVE: removes the old partition entirely before the
-- REPLACE PARTITION below swaps in the freshly-resynced data; if the
-- REPLACE fails partway, this partition is gone until the resync is
-- re-run. Blast radius is the single partition named above, confirmed
-- by the WHERE/partition-key filter on the preceding SELECT.
ALTER TABLE target_table DROP PARTITION '2026-09';
ALTER TABLE target_table REPLACE PARTITION '2026-09' FROM staging_table;
```

Commit trailer: `Destructive-Op-Check: sites=1; result=pass`

## The Cardinal Rule

Before trusting a tool's `--help` text or a config flag's name to tell you
whether an operation is safe, read the consumer code that actually executes
it. A flag named `--safe-mode` or a tool whose help text implies it only
reports differences can still issue a real `DELETE`/`DROP` under certain
argument combinations — the only way to know is to read what the code
actually does with the arguments it's given, not what its interface
suggests.

## Review Checklist

- [ ] Every destructive statement in the diff has a `DESTRUCTIVE:` comment
      within 5 lines
- [ ] The comment states the actual blast radius, and that claim is
      verifiable from the surrounding code (a filter, a partition key, an
      explicit allowlist) — not just asserted
- [ ] The commit has a `Destructive-Op-Check: sites=<N>; result=pass`
      trailer with the correct count
- [ ] Any new destructive CLI tool has a working, logic-sharing dry-run mode
- [ ] The reviewer traced the actual consumer code for any flag or helper
      the PR relies on being "safe," rather than trusting its name or docs

## Anti-Patterns
A destructive statement with no `DESTRUCTIVE:` comment · a comment that
states a blast radius that isn't actually enforced by the surrounding code
· a commit trailer whose count doesn't match the diff · a dry-run mode
implemented as a separate, unverified code path from the real one ·
trusting a tool's help text instead of reading what it executes.

## Cross-References
`pr-self-review`, `mandatory-code-change-validation`, `code-quality-sql`,
`code-quality-bash`, `sink-connector-mysql-source-of-truth`.
